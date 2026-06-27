package org.rtklib.java.rtkpos;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RTK相对定位核心算法。
 *
 * <p>对应RTKLIB C源码 rtkpos.c 中的 relpos / zdres / ddres 等函数。</p>
 *
 * <h3>与RTKLIB C版本的关键差异</h3>
 * <ul>
 *   <li><b>状态向量定义不同</b>：C版本 rtk->x[0..2] 存储流动站绝对ECEF坐标；
 *       Java版本 rtk.x[0..2] 存储基线向量（流动站坐标 - 基准站坐标）。
 *       因此调用 zdres 时需要 rtk.rb + xp 计算流动站绝对坐标。</li>
 *   <li><b>矩阵存储顺序不同</b>：C版本使用列优先（Fortran风格），
 *       Java版本统一使用行优先（row-major），即 M[row * cols + col]。
 *       所有矩阵（P, H, R）均按行优先存储，通过 MatrixUtil 转为 EJML SimpleMatrix。</li>
 *   <li><b>矩阵运算</b>：全部使用 EJML 库，不直接手撸数组运算，
 *       避免行优先/列优先混淆和矩阵乘法顺序错误。</li>
 * </ul>
 *
 * <h3>矩阵存储约定（行优先）</h3>
 * <ul>
 *   <li>P[nx*nx]：协方差矩阵，P[i*nx+j] = 第i行第j列</li>
 *   <li>H[ny*nx]：设计矩阵，H[obs*nx+state] = 第obs行第state列</li>
 *   <li>R[ny*ny]：观测噪声协方差，R[i*ny+j] = 第i行第j列</li>
 * </ul>
 *
 * <h3>致命陷阱规避</h3>
 * <ul>
 *   <li>载波相位单位：L * λ（cycle→米），不可省略波长转换</li>
 *   <li>时间系统：GPST/BDT/UTC 严格区分，BDT = GPST - 14s</li>
 *   <li>矩阵乘法不满足交换律：P*H^T ≠ H^T*P</li>
 *   <li>卫星位置迭代：必须用信号传播时间迭代，不可省略</li>
 *   <li>地球自转修正：Sagnac效应必须包含在卫星位置计算中</li>
 * </ul>
 */
public final class RtkCore {
    private RtkCore() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(RtkCore.class);

    private static final int MAXITR = 8;
    private static final double TTOL_MOVEB = 1.05;
    private static final double VAR_POS = 30.0 * 30.0;
    private static final double VAR_VEL = 10.0 * 10.0;
    private static final double VAR_ACC = 10.0 * 10.0;
    private static final double VAR_AMB = 30.0 * 30.0;
    private static final double VAR_IONO_OFF = 1E4;
    private static final double VAR_GLO_IFB = 1E4;

    private static int NP(PrcOpt opt) {
        return opt.dynamics == 0 ? 3 : 9;
    }

    private static int NI(PrcOpt opt) {
        return opt.ionoopt != Constants.IONOOPT_EST ? 0 : Constants.MAXSAT;
    }

    private static int NT(PrcOpt opt) {
        if (opt.tropopt < Constants.TROPOPT_EST) return 0;
        if (opt.tropopt < Constants.TROPOPT_ESTG) return 2;
        return 6;
    }

    private static int NL(PrcOpt opt) {
        return opt.glomodear != Constants.GLO_ARMODE_AUTOCAL ? 0 : Constants.NFREQGLO;
    }

    private static int NR(PrcOpt opt) {
        return NP(opt) + NI(opt) + NT(opt) + NL(opt);
    }

    private static int IB(int sat, int f, int nf, PrcOpt opt) {
        return NR(opt) + Constants.MAXSAT * f + (sat - 1);
    }

    private static final double MIN_ARC_GAP = 300.0;

    /**
     * RTK定位入口函数。
     *
     * <p>对应RTKLIB rtkpos()。处理单点定位(SPP)和相对定位(RTK/DGPS)。</p>
     *
     * @param rtk RTK解算状态
     * @param obs 观测数据数组，rcv=1为流动站，rcv=2为基准站
     * @param n   观测数据总数
     * @param nav 导航数据
     * @return 1:成功 0:失败
     */
    public static int rtkpos(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        PrcOpt opt = rtk.opt;
        Sol solb = new Sol();
        int i, nu = 0, nr = 0;
        String[] msg = new String[1];
        double[] azel = new double[n * 2];

        for (nu = 0; nu < n && obs[nu].rcv == 1; nu++) ;
        for (nr = 0; nu + nr < n && obs[nu + nr].rcv == 2; nr++) ;

        LOG.debug("rtkpos: nu={} nr={} stat={}", nu, nr, rtk.sol.stat);

        GTime prevTime = new GTime(rtk.sol.time);

        if (rtk.P[0] == 0.0) {
            double[] rs_spp = new double[nu * 6];
            double[] dts_spp = new double[nu * 2];
            double[] vare_spp = new double[nu];
            int[] svh_spp = new int[nu];
            EphModel.satposs(obs[0].time, obs, nu, nav, rs_spp, dts_spp, vare_spp, svh_spp);
            if (SppCore.estpos(obs, nu, rs_spp, dts_spp, vare_spp, svh_spp, nav, opt,
                    rtk.ssat, rtk.sol, azel, new int[nu], new double[nu], msg) == 0) {
                return 0;
            }
        } else {
            rtk.sol.time = obs[0].time;
        }

        if (prevTime.time != 0) {
            rtk.tt = TimeSystem.timediff(rtk.sol.time, prevTime);
        } else {
            rtk.tt = 0.0;
        }

        if (opt.mode == Constants.PMODE_SINGLE) {
            return 1;
        }

        if (nr == 0) {
            return 1;
        }

        if (opt.refpos <= Constants.POSOPT_RINEX && opt.mode != Constants.PMODE_SINGLE &&
                opt.mode != Constants.PMODE_MOVEB) {
            for (i = 0; i < 6; i++) rtk.rb[i] = i < 3 ? opt.rb[i] : 0.0;
        }

        if (opt.mode == Constants.PMODE_MOVEB) {
            if (rtk.sol.stat == Constants.SOLQ_NONE) {
                double[] rs_spp = new double[nu * 6];
                double[] dts_spp = new double[nu * 2];
                double[] vare_spp = new double[nu];
                int[] svh_spp = new int[nu];
                EphModel.satposs(obs[0].time, obs, nu, nav, rs_spp, dts_spp, vare_spp, svh_spp);
                if (SppCore.estpos(obs, nu, rs_spp, dts_spp, vare_spp, svh_spp, nav, opt,
                        rtk.ssat, solb, azel, new int[nr], new double[nr], msg) == 0) {
                    return 0;
                }
                if (Math.abs(rtk.rb[0]) < 0.1) {
                    for (i = 0; i < 3; i++) rtk.rb[i] = solb.rr[i];
                } else {
                    for (i = 0; i < 3; i++) {
                        rtk.rb[i] = 0.95 * rtk.rb[i] + 0.05 * solb.rr[i];
                        rtk.rb[i + 3] = 0;
                    }
                }
            }
            double age = TimeSystem.timediff(rtk.sol.time, solb.time);
            if (Math.abs(age) > Math.min(TTOL_MOVEB, opt.maxtdiff)) {
                return 0;
            }
        }

        return relpos(rtk, obs, nu, nr, nav);
    }

    /**
     * 相对定位核心函数。
     *
     * <p>对应RTKLIB relpos()。实现RTK/DGPS相对定位的完整流程：</p>
     * <ol>
     *   <li>计算卫星位置（含传播时间迭代和地球自转修正）</li>
     *   <li>计算基准站零差残差 zdres(1,...)</li>
     *   <li>选择共视卫星 selsat()</li>
     *   <li>初始化/更新状态向量（基线向量 + 模糊度）</li>
     *   <li>迭代求解：零差残差 → 双差残差 → Kalman滤波</li>
     *   <li>LAMBDA模糊度固定（可选）</li>
     *   <li>输出解算结果</li>
     * </ol>
     *
     * <p><b>状态向量定义</b>（与C版本不同）：</p>
     * <ul>
     *   <li>x[0..2]: 基线向量（流动站ECEF - 基准站ECEF），单位：米</li>
     *   <li>x[3..5]: 速度分量（预留，当前置零）</li>
     *   <li>x[6..6+ns*nf-1]: 载波相位模糊度，单位：cycle</li>
     * </ul>
     *
     * @param rtk RTK解算状态
     * @param obs 观测数据数组
     * @param nu  流动站观测数
     * @param nr  基准站观测数
     * @param nav 导航数据
     * @return 1:成功 0:失败
     */
    private static int relpos(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav) {
        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int i, j, f, k, ns, nv = 0;
        int n = nu + nr;
        int[] sat = new int[Constants.MAXSAT];
        int[] iu = new int[Constants.MAXSAT];
        int[] ir = new int[Constants.MAXSAT];

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];
        double[] azel = new double[Constants.MAXSAT * 2];

        for (i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i].sys = SatUtils.satsys(i + 1, null);
            for (j = 0; j < Constants.NFREQ; j++) {
                rtk.ssat[i].vsat[j] = 0;
                rtk.ssat[i].snrRover[j] = 0;
                rtk.ssat[i].snrBase[j] = 0;
            }
        }

        EphModel.satposs(obs[0].time, obs, n, nav, rs, dts, vare, svh);

        double[] y = new double[nf * 2 * n];
        double[] e = new double[3 * n];
        double[] freq = new double[nf * n];

        if (!zdres(1, obs, nu, nr, rs, dts, vare, svh, nav, rtk.rb, opt,
                y, e, azel, freq)) {
            LOG.warn("initial base station position error");
            return 0;
        }

        double dt = TimeSystem.timediff(obs[0].time, obs[nu].time);
        rtk.sol.age = (float) dt;
        if (Math.abs(rtk.sol.age) > opt.maxtdiff) {
            LOG.warn("age of differential error (age={})", rtk.sol.age);
            return 1;
        }

        ns = selsat(obs, azel, nu, nr, opt, sat, iu, ir);
        if (ns <= 0) return 0;

        int nState = NR(opt);
        int nx = nState + Constants.MAXSAT * nf;
        boolean reinit = (rtk.nx != nx);
        LOG.debug("relpos: ns={} nf={} nx={} rtk.nx={} reinit={}", ns, nf, nx, rtk.nx, reinit);
        if (rtk.nx != nx) {
            rtk.nx = nx;
            rtk.x = new double[nx];
            rtk.P = new double[nx * nx];
            initx(rtk, rtk.sol.rr[0] - rtk.rb[0], VAR_POS, 0);
            initx(rtk, rtk.sol.rr[1] - rtk.rb[1], VAR_POS, 1);
            initx(rtk, rtk.sol.rr[2] - rtk.rb[2], VAR_POS, 2);
            if (opt.dynamics != 0) {
                for (i = 3; i < 6; i++) initx(rtk, 0.0, VAR_VEL, i);
                for (i = 6; i < 9; i++) initx(rtk, 1E-6, VAR_ACC, i);
            }

        }

        udstate(rtk, obs, sat, iu, ir, ns, nav, nf);

        StringBuilder satListStr = new StringBuilder();
        for (i = 0; i < ns; i++) {
            int[] tmpPrn = new int[1];
            int sys = SatUtils.satsys(sat[i], tmpPrn);
            char sc = sys == Constants.SYS_GPS ? 'G' : sys == Constants.SYS_GLO ? 'R' : sys == Constants.SYS_GAL ? 'E' : 'C';
            satListStr.append(String.format("%c%02d(sat=%d,outc=%d/%d,slip=%d/%d,lock=%d/%d,vsat=%d/%d) ",
                    sc, tmpPrn[0], sat[i],
                    rtk.ssat[sat[i] - 1].outc[0], rtk.ssat[sat[i] - 1].outc[1],
                    rtk.ssat[sat[i] - 1].slip[0], rtk.ssat[sat[i] - 1].slip[1],
                    rtk.ssat[sat[i] - 1].lock[0], rtk.ssat[sat[i] - 1].lock[1],
                    rtk.ssat[sat[i] - 1].vsat[0], rtk.ssat[sat[i] - 1].vsat[1]));
        }
        LOG.debug("sat list after udstate: " + satListStr);
        StringBuilder ambStr = new StringBuilder();
        for (i = 0; i < ns; i++) {
            for (f = 0; f < nf; f++) {
                int idx = IB(sat[i], f, nf, opt);
                if (idx < nx) {
                    ambStr.append(String.format("sat=%d f=%d idx=%d x=%.4f P=%.1f | ", sat[i], f, idx, rtk.x[idx], rtk.P[idx * nx + idx]));
                }
            }
        }
        LOG.debug("amb states after udstate: " + ambStr);

        for (i = 0; i < ns; i++) {
            for (f = 0; f < nf; f++) {
                rtk.ssat[sat[i] - 1].snrRover[f] = obs[iu[i]].SNR[f];
                rtk.ssat[sat[i] - 1].snrBase[f] = obs[ir[i]].SNR[f];
            }
        }

        double[] xp = new double[nx];
        double[] Pp = new double[nx * nx];
        System.arraycopy(rtk.x, 0, xp, 0, nx);
        System.arraycopy(rtk.P, 0, Pp, 0, nx * nx);

        int ny = ns * nf * 2 + 2;
        double[] v = new double[ny];
        double[] H = new double[nx * ny];
        double[] R = new double[ny * ny];
        int[] vflg = new int[ny];
        double[] xa = new double[nx];
        double[] bias = new double[nx];

        int stat = opt.mode <= Constants.PMODE_DGPS ? Constants.SOLQ_DGPS : Constants.SOLQ_FLOAT;

        double[] rr_rover = new double[3];

        for (i = 0; i < opt.niter; i++) {
            LOG.debug(String.format("iter %d start: xp0=(%.6f,%.6f,%.6f) Pp[0]=%.4f Pp[432]=%.4f Pp[862]=%.4f Pp[1]=%.4f Pp[2]=%.4f",
                    i, xp[0], xp[1], xp[2], Pp[0], Pp[1*nx+1], Pp[2*nx+2], Pp[1], Pp[2]));
            for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
            LOG.debug(String.format("rover pos: rr=(%.2f,%.2f,%.2f) xp=(%.6f,%.6f,%.6f) rb=(%.2f,%.2f,%.2f)",
                    rr_rover[0], rr_rover[1], rr_rover[2],
                    xp[0], xp[1], xp[2],
                    rtk.rb[0], rtk.rb[1], rtk.rb[2]));
            if (!zdres(0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                    y, e, azel, freq)) {
                LOG.warn("rover initial position error");
                stat = Constants.SOLQ_NONE;
                break;
            }
            if ((nv = ddres(rtk, obs, dt, xp, Pp, sat, y, e, azel, freq,
                    iu, ir, ns, nf, nav, v, H, R, vflg)) < 4) {
                LOG.debug("not enough double-differenced residual, nv={}", nv);
                stat = Constants.SOLQ_NONE;
                break;
            }
            double xp0 = xp[0], xp1 = xp[1], xp2 = xp[2];
            StringBuilder vAll = new StringBuilder("v=[");
            for (int vi = 0; vi < nv; vi++) {
                int sat1 = (vflg[vi] >> 16) & 0xFF;
                int sat2 = (vflg[vi] >> 8) & 0xFF;
                int isCode = (vflg[vi] >> 4) & 1;
                int frqIdx = vflg[vi] & 0xF;
                vAll.append(String.format("%d:%d%d%d=%.4f ", sat1, sat2, isCode, frqIdx, v[vi]));
            }
            vAll.append("]");
            StringBuilder rDiag = new StringBuilder("Rdiag=[");
            for (int vi = 0; vi < nv; vi++) rDiag.append(String.format("%.4f ", R[vi * nv + vi]));
            rDiag.append("]");
            LOG.debug(String.format("pre-filter: nv=%d %s %s xp0=(%.6f,%.6f,%.6f) P0=%.1f P1=%.1f P2=%.1f", nv, vAll, rDiag, xp0, xp1, xp2, Pp[0], Pp[1*nx+1], Pp[2*nx+2]));
            int info = filter(xp, Pp, H, v, R, nx, nv);
            LOG.debug(String.format("filter: info=%d nv=%d dxp=(%.6f,%.6f,%.6f) P0=%.4f P1=%.4f P2=%.4f",
                    info, nv,
                    xp[0] - xp0, xp[1] - xp1, xp[2] - xp2, Pp[0], Pp[1*nx+1], Pp[2*nx+2]));
            if (info != 0) {
                LOG.debug("filter error (info={})", info);
                stat = Constants.SOLQ_NONE;
                break;
            }
        }

        if (stat != Constants.SOLQ_NONE) {
            for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
            if (zdres(0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                    y, e, azel, freq)) {
                nv = ddres(rtk, obs, dt, xp, Pp, sat, y, e, azel, freq,
                        iu, ir, ns, nf, nav, v, null, R, vflg);
                double maxRes = 0;
                for (int vi = 0; vi < nv; vi++) maxRes = Math.max(maxRes, Math.abs(v[vi]));
                LOG.debug(String.format("post-filter residuals: nv=%d max|v|=%.4f", nv, maxRes));

                System.arraycopy(xp, 0, rtk.x, 0, nx);
                System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);

                rtk.sol.ns = 0;
                for (i = 0; i < ns; i++) {
                    for (f = 0; f < nf; f++) {
                        if (rtk.ssat[sat[i] - 1].vsat[f] == 0) continue;
                        rtk.ssat[sat[i] - 1].outc[f] = 0;
                        if (f == 0) rtk.sol.ns++;
                    }
                }
                if (rtk.sol.ns < 4) stat = Constants.SOLQ_DGPS;
            } else {
                stat = Constants.SOLQ_NONE;
            }
        }

        if (opt.modear != Constants.ARMODE_OFF && stat == Constants.SOLQ_FLOAT) {
            int[] ix = new int[nx * 2];
            int nb = ddidx(rtk, sat, ns, nf, nx, ix);
            if (nb >= opt.minfixsats - 1) {
                double[] y_dd = new double[nb];
                double[] Qb = new double[nb * nb];
                double[] Qab = new double[nState * nb];
                for (i = 0; i < nb; i++) {
                    y_dd[i] = rtk.x[ix[i * 2]] - rtk.x[ix[i * 2 + 1]];
                }
                for (j = 0; j < nb; j++) {
                    for (i = 0; i < nb; i++) {
                        Qb[i * nb + j] = rtk.P[ix[i * 2] * nx + ix[j * 2]]
                                - rtk.P[ix[i * 2] * nx + ix[j * 2 + 1]]
                                - rtk.P[ix[i * 2 + 1] * nx + ix[j * 2]]
                                + rtk.P[ix[i * 2 + 1] * nx + ix[j * 2 + 1]];
                    }
                }
                for (j = 0; j < nb; j++) {
                    for (i = 0; i < nState; i++) {
                        Qab[i * nb + j] = rtk.P[i * nx + ix[j * 2]] - rtk.P[i * nx + ix[j * 2 + 1]];
                    }
                }

                double[] b = new double[nb * 2];
                double[] s = new double[2];
                int info = Lambda.lambda(nb, 2, y_dd, Qb, b, s);
                if (info == 0) {
                    rtk.sol.ratio = s[0] > 0.0 ? (float) (s[1] / s[0]) : 0.0f;
                    if (rtk.sol.ratio > 999.9f) rtk.sol.ratio = 999.9f;
                    rtk.sol.thres = (float) opt.thresar[0];

                    if (s[0] > 0.0 && s[1] / s[0] >= rtk.sol.thres) {
                        double[] ddBias = new double[nb];
                        for (i = 0; i < nb; i++) {
                            ddBias[i] = b[i];
                            y_dd[i] -= b[i];
                        }

                        SimpleMatrix QbMat = MatrixUtil.createMatrix(Qb, nb, nb);
                        SimpleMatrix QbInv = MatrixUtil.invert(QbMat);
                        SimpleMatrix yMat = MatrixUtil.createMatrix(y_dd, nb, 1);
                        SimpleMatrix dbVec = MatrixUtil.multiply(QbInv, yMat);
                        SimpleMatrix QabMat = MatrixUtil.createMatrix(Qab, nState, nb);
                        SimpleMatrix dx = MatrixUtil.multiply(QabMat, dbVec);

                        System.arraycopy(rtk.x, 0, xa, 0, nx);
                        double[] dxArr = MatrixUtil.toArray(dx);
                        for (i = 0; i < nState; i++) {
                            xa[i] -= dxArr[i];
                        }

                        restamb(rtk, ddBias, nb, sat, ns, nf, nx, xa);

                        double[] rr_fix = new double[3];
                        for (j = 0; j < 3; j++) rr_fix[j] = rtk.rb[j] + xa[j];
                        if (zdres(0, obs, nu, nr, rs, dts, vare, svh, nav, rr_fix, opt,
                                y, e, azel, freq)) {
                            nv = ddres(rtk, obs, dt, xa, rtk.P, sat, y, e, azel, freq,
                                    iu, ir, ns, nf, nav, v, null, R, vflg);
                            if (nv > 0) {
                                stat = Constants.SOLQ_FIX;
                                if (++rtk.nfix >= rtk.opt.minfix) {
                                    if (rtk.opt.modear == Constants.ARMODE_FIXHOLD) {
                                        holdamb(rtk, xa, sat, ns, nf, nx, nav);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (stat == Constants.SOLQ_FIX) {
            for (i = 0; i < 3; i++) {
                rtk.sol.rr[i] = xa[i] + rtk.rb[i];
            }
        } else if (stat != Constants.SOLQ_NONE) {
            for (i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.x[i] + rtk.rb[i];
            }
        }
        for (i = 3; i < 6; i++) rtk.sol.rr[i] = 0.0;
        for (i = 0; i < 6; i++) {
            rtk.sol.qr[i] = (float) Math.sqrt(Math.abs(rtk.P[i * nx + i]));
        }

        for (i = 0; i < Constants.MAXSAT; i++) {
            for (f = 0; f < nf; f++) {
                if ((rtk.ssat[i].slip[f] & Constants.LLI_SLIP) != 0) {
                    rtk.ssat[i].slipc[f]++;
                }
                if (rtk.ssat[i].vsat[f] == 0) continue;
                if (rtk.ssat[i].lock[f] < 0 || (rtk.nfix > 0 && rtk.ssat[i].fix[f] >= 2)) {
                    rtk.ssat[i].lock[f]++;
                }
            }
        }

        rtk.sol.stat = (byte) stat;

        LOG.debug("relpos done: nv={} stat={} x[0..2]=({},{},{})",
                nv, stat,
                String.format("%.4f", rtk.x[0]),
                String.format("%.4f", rtk.x[1]),
                String.format("%.4f", rtk.x[2]));

        return stat != Constants.SOLQ_NONE ? 1 : 0;
    }

    /**
     * 计算零差残差（Zero-Difference Residuals）。
     *
     * <p>对应RTKLIB zdres()。计算每个卫星的观测值与几何距离之差：</p>
     * <ul>
     *   <li>载波相位残差: y = L * λ - r （注意：L是cycle，必须乘波长λ转为米）</li>
     *   <li>伪距残差: y = P - r</li>
     * </ul>
     *
     * <p>同时计算视线单位向量e、方位角/高度角azel、频率freq。</p>
     *
     * <p><b>与C版本的差异</b>：C版本 obs 偏移由调用方处理（obs+nu），
     * Java版本通过 off 参数在函数内部处理偏移。</p>
     *
     * @param base  0=流动站 1=基准站
     * @param obs   观测数据数组（流动站+基准站连续存储）
     * @param nu    流动站观测数
     * @param nr    基准站观测数
     * @param rs    卫星位置/速度数组 [n*6]，每颗卫星 (x,y,z,vx,vy,vz)
     * @param dts   卫星钟差/钟漂数组 [n*2]，每颗卫星 (bias,drift)
     * @param vare  卫星位置方差 [n]
     * @param svh   卫星健康标志 [n]
     * @param nav   导航数据
     * @param rr    接收机绝对ECEF坐标 [3]（注意：不是基线向量！）
     * @param opt   处理选项
     * @param y     输出：零差残差 [nf*2*n]，行优先，每颗卫星 nf*2 个值（相位+伪距）
     * @param e     输出：视线单位向量 [3*n]
     * @param azel  输出：方位角/高度角 [2*n]
     * @param freq  输出：频率 [nf*n]
     * @return true=成功 false=接收机位置无效
     */
    private static boolean zdres(int base, Obsd[] obs, int nu, int nr,
                                 double[] rs, double[] dts, double[] vare, int[] svh,
                                 Nav nav, double[] rr, PrcOpt opt,
                                 double[] y, double[] e, double[] azel, double[] freq) {
        int n = base != 0 ? nr : nu;
        int off = base != 0 ? nu : 0;
        int foff = base != 0 ? nu : 0;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        for (int i = 0; i < n * nf * 2; i++) y[off * nf * 2 + i] = 0.0;

        double rrNorm = RtklibCommon.norm(rr, 3);
        if (rrNorm <= Constants.RE_WGS84 / 2) {
            LOG.debug("zdres: base={} rr=({},{},{}) norm={} too small", base,
                    String.format("%.1f", rr[0]),
                    String.format("%.1f", rr[1]),
                    String.format("%.1f", rr[2]),
                    String.format("%.1f", rrNorm));
            return false;
        }

        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr, pos);

        double[] zazel = new double[]{0.0, Constants.PI / 2.0};
        double zhd = TroposphereModel.saastamoinen(pos, zazel, 0.0, 293.15);

        for (int i = 0; i < n; i++) {
            int idx = off + i;
            double[] rsi = new double[]{rs[idx * 6], rs[idx * 6 + 1], rs[idx * 6 + 2]};
            double[] ei = new double[3];
            double r = RtklibCommon.geodist(rsi, rr, ei);
            if (r <= 0.0) continue;

            double[] ae = new double[2];
            double el = RtklibCommon.satazel(pos, ei, ae);
            azel[idx * 2] = ae[0];
            azel[idx * 2 + 1] = el;
            if (el < opt.elmin) continue;

            if (RtklibCommon.satexclude(obs[idx].sat, vare[idx], svh[idx], opt) != 0) continue;

            r += -Constants.CLIGHT * dts[idx * 2];

            double mapfh = TroposphereModel.tropmapf(obs[idx].time, pos, ae, null);
            r += mapfh * zhd;

            if (base == 0) {
                LOG.debug(String.format("zdres rover: sat=%d rs=(%.3f,%.3f,%.3f) r=%.6f c*dts=%.6f zhd=%.6f map=%.6f trop=%.6f",
                        obs[idx].sat, rsi[0], rsi[1], rsi[2],
                        r - mapfh * zhd + Constants.CLIGHT * dts[idx * 2],
                        -Constants.CLIGHT * dts[idx * 2], zhd, mapfh, mapfh * zhd));
            } else {
                LOG.debug(String.format("zdres base: sat=%d rs=(%.3f,%.3f,%.3f) r=%.6f c*dts=%.6f zhd=%.6f map=%.6f trop=%.6f",
                        obs[idx].sat, rsi[0], rsi[1], rsi[2],
                        r - mapfh * zhd + Constants.CLIGHT * dts[idx * 2],
                        -Constants.CLIGHT * dts[idx * 2], zhd, mapfh, mapfh * zhd));
            }

            e[idx * 3] = ei[0];
            e[idx * 3 + 1] = ei[1];
            e[idx * 3 + 2] = ei[2];

            for (int f = 0; f < nf; f++) {
                double fq = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[f], nav);
                freq[foff * nf + i * nf + f] = fq;
                if (fq == 0.0) continue;

                double lam = Constants.CLIGHT / fq;

                if (obs[idx].L[f] != 0.0) {
                    y[off * nf * 2 + i * nf * 2 + f] = obs[idx].L[f] * lam - r;
                    if (base == 0 && f == 0) {
                        LOG.debug(String.format("zdres rover: sat=%d f=%d L=%.6f lam=%.6f r=%.6f y=%.6f",
                                obs[idx].sat, f, obs[idx].L[f], lam, r, y[off * nf * 2 + i * nf * 2 + f]));
                    }
                }
                if (obs[idx].P[f] != 0.0) {
                    y[off * nf * 2 + i * nf * 2 + nf + f] = obs[idx].P[f] - r;
                    if (base == 0 && f == 0) {
                        LOG.debug(String.format("zdres rover: sat=%d f=%d P=%.6f r=%.6f y_P=%.6f y_L=%.6f",
                                obs[idx].sat, f, obs[idx].P[f], r, y[off * nf * 2 + i * nf * 2 + nf + f], y[off * nf * 2 + i * nf * 2 + f]));
                    }
                }
            }
        }
        return true;
    }

    /**
     * 计算双差残差及设计矩阵。
     *
     * <p>对应RTKLIB ddres()。核心步骤：</p>
     * <ol>
     *   <li>按卫星系统（GPS/GLO/GAL/BDS/QZS/IRN）和频率/码类型分组</li>
     *   <li>每组选择高度角最高的卫星作为参考星</li>
     *   <li>计算双差残差: v = (y_rover_ref - y_base_ref) - (y_rover_j - y_base_j)</li>
     *   <li>构建设计矩阵H（行优先存储 H[obs*nx+state]）</li>
     *   <li>位置偏导数: H[obs, 0..2] = -e_ref + e_j</li>
     *   <li>模糊度偏导数: H[obs, ii] = λ_ref, H[obs, jj] = -λ_j</li>
     *   <li>计算观测噪声方差 varerr()</li>
     *   <li>构建双差协方差矩阵 ddcov()</li>
     * </ol>
     *
     * <p><b>行优先存储约定</b>（与C版本列优先不同）：</p>
     * <ul>
     *   <li>H[ny*nx]：H[obs*nx + state]，即第obs个观测对第state个状态的偏导数</li>
     *   <li>R[ny*ny]：R[i*ny + j]，即第i行第j列</li>
     *   <li>P[nx*nx]：P[i*nx + j]，即第i行第j列</li>
     * </ul>
     *
     * @param rtk  RTK解算状态
     * @param obs  观测数据数组
     * @param dt   流动站与基准站时间差（秒）
     * @param x    状态向量（基线向量+模糊度），nx维
     * @param P    协方差矩阵，nx*nx，行优先
     * @param sat  共视卫星号数组
     * @param y    零差残差（来自zdres）
     * @param e    视线单位向量
     * @param azel 方位角/高度角
     * @param freq 频率数组
     * @param iu   流动站卫星索引
     * @param ir   基准站卫星索引
     * @param ns   共视卫星数
     * @param nf   频率数
     * @param nav  导航数据
     * @param v    输出：双差残差向量 [ny]
     * @param H    输出：设计矩阵 [ny*nx]，行优先；null则不计算
     * @param R    输出：双差协方差矩阵 [ny*ny]，行优先
     * @param vflg 输出：残差标志 [ny]
     * @return 有效双差残差数
     */
    private static int ddres(Rtk rtk, Obsd[] obs, double dt, double[] x, double[] P,
                             int[] sat, double[] y, double[] e, double[] azel, double[] freq,
                             int[] iu, int[] ir, int ns, int nf, Nav nav,
                             double[] v, double[] H, double[] R, int[] vflg) {
        PrcOpt opt = rtk.opt;
        int i, j, k, m, f, nv = 0;
        int[] nb = new int[Constants.NFREQ * 7 * 2 + 2];
        int b = 0;

        double[] rr_f = new double[3];
        for (i = 0; i < 3; i++) rr_f[i] = rtk.rb[i] + x[i];
        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr_f, pos);

        double bl = baseline(x, rtk.rb, null);

        double[] Ri = new double[ns * nf * 2 + 2];
        double[] Rj = new double[ns * nf * 2 + 2];

        for (i = 0; i < Constants.MAXSAT; i++) {
            for (j = 0; j < Constants.NFREQ; j++) {
                rtk.ssat[i].resp[j] = 0.0;
                rtk.ssat[i].resc[j] = 0.0;
            }
        }

        int[] sysMap = new int[]{Constants.SYS_GPS | Constants.SYS_SBS, Constants.SYS_GLO, Constants.SYS_GAL,
                Constants.SYS_CMP, Constants.SYS_QZS, Constants.SYS_IRN};

        for (m = 0; m < 6; m++) {
            for (f = (opt.mode > Constants.PMODE_DGPS ? 0 : nf); f < nf * 2; f++) {
                int frq = f % nf;
                boolean code = f >= nf;

                int refIdx = -1;
                for (j = 0; j < ns; j++) {
                    int sysj = SatUtils.satsys(sat[j], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    if (sysj == Constants.SYS_SBS) continue;
                    if (!validobs(iu[j], ir[j], f, nf, y)) continue;
                    if (refIdx >= 0 && (rtk.ssat[sat[j] - 1].slip[frq] & Constants.LLI_SLIP) != 0) continue;
                    if (refIdx < 0 || azel[1 + iu[j] * 2] >= azel[1 + iu[refIdx] * 2]) {
                        refIdx = j;
                    }
                }
                if (refIdx < 0) continue;
                if (f < nf) {
                    int refPrn = 0;
                    int[] tmp = new int[1];
                    SatUtils.satsys(sat[refIdx], tmp);
                    refPrn = tmp[0];
                    int refSys = SatUtils.satsys(sat[refIdx], null);
                    char sysChar = refSys == Constants.SYS_GPS ? 'G' : refSys == Constants.SYS_GLO ? 'R' : refSys == Constants.SYS_GAL ? 'E' : 'C';
                    LOG.debug(String.format("ddres ref: f=%d ref=%c%02d sat=%d el=%.1f", frq, sysChar, refPrn, sat[refIdx], azel[1 + iu[refIdx] * 2] * 180.0 / Math.PI));
                }

                for (j = 0; j < ns; j++) {
                    if (j == refIdx) continue;
                    int sysi = SatUtils.satsys(sat[refIdx], null);
                    int sysj = SatUtils.satsys(sat[j], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    if (!validobs(iu[j], ir[j], f, nf, y)) continue;

                    double freqi = SatUtils.sat2freq(sat[refIdx], obs[iu[refIdx]].code[frq], nav);
                    double freqj = SatUtils.sat2freq(sat[j], obs[iu[j]].code[frq], nav);
                    if (freqi <= 0.0 || freqj <= 0.0) continue;

                    if (H != null) {
                        for (k = 0; k < rtk.nx; k++) H[nv * rtk.nx + k] = 0.0;
                    }

                    int idx_i = iu[refIdx];
                    int idx_ir = ir[refIdx];
                    int idx_j = iu[j];
                    int idx_jr = ir[j];

                    v[nv] = (y[f + idx_i * nf * 2] - y[f + idx_ir * nf * 2])
                            - (y[f + idx_j * nf * 2] - y[f + idx_jr * nf * 2]);
                    if (code && frq == 0) {
                        LOG.debug(String.format("ddres P: ref=%d-%d f=%d y[%d]=%.4f y[%d]=%.4f y[%d]=%.4f y[%d]=%.4f dd=%.4f",
                                sat[refIdx], sat[j], f,
                                f + idx_i * nf * 2, y[f + idx_i * nf * 2],
                                f + idx_ir * nf * 2, y[f + idx_ir * nf * 2],
                                f + idx_j * nf * 2, y[f + idx_j * nf * 2],
                                f + idx_jr * nf * 2, y[f + idx_jr * nf * 2], v[nv]));
                        int fL1 = 0;
                        LOG.debug(String.format("ddres L: ref=%d-%d y[%d]=%.4f y[%d]=%.4f y[%d]=%.4f y[%d]=%.4f ddL=%.4f",
                                sat[refIdx], sat[j],
                                fL1 + idx_i * nf * 2, y[fL1 + idx_i * nf * 2],
                                fL1 + idx_ir * nf * 2, y[fL1 + idx_ir * nf * 2],
                                fL1 + idx_j * nf * 2, y[fL1 + idx_j * nf * 2],
                                fL1 + idx_jr * nf * 2, y[fL1 + idx_jr * nf * 2],
                                (y[fL1 + idx_i * nf * 2] - y[fL1 + idx_ir * nf * 2]) - (y[fL1 + idx_j * nf * 2] - y[fL1 + idx_jr * nf * 2])));
                    }
                    if (!code && frq == 0 && H != null) {
                        LOG.debug(String.format("ddres v: ref=%d-%d %s%d y_r=%.4f y_b=%.4f y_r2=%.4f y_b2=%.4f dd=%.4f",
                                sat[refIdx], sat[j], code ? "P" : "L", frq + 1,
                                y[f + idx_i * nf * 2], y[f + idx_ir * nf * 2],
                                y[f + idx_j * nf * 2], y[f + idx_jr * nf * 2], v[nv]));
                        LOG.debug(String.format("ddres e: e_ref=(%.8f,%.8f,%.8f) e_j=(%.8f,%.8f,%.8f) H_pos=(%.8f,%.8f,%.8f)",
                                e[idx_i * 3], e[idx_i * 3 + 1], e[idx_i * 3 + 2],
                                e[idx_j * 3], e[idx_j * 3 + 1], e[idx_j * 3 + 2],
                                -e[idx_i * 3] + e[idx_j * 3], -e[idx_i * 3 + 1] + e[idx_j * 3 + 1], -e[idx_i * 3 + 2] + e[idx_j * 3 + 2]));
                    }

                    if (H != null) {
                        for (k = 0; k < 3; k++) {
                            H[nv * rtk.nx + k] = -e[k + idx_i * 3] + e[k + idx_j * 3];
                        }
                    }

                    if (opt.mode > Constants.PMODE_DGPS && !code) {
                        int ii = IB(sat[refIdx], frq, nf, opt);
                        int jj = IB(sat[j], frq, nf, opt);
                        double lami = Constants.CLIGHT / freqi;
                        double lamj = Constants.CLIGHT / freqj;
                        v[nv] -= lami * x[ii] - lamj * x[jj];
                        if (H != null) {
                            H[nv * rtk.nx + ii] = lami;
                            H[nv * rtk.nx + jj] = -lamj;
                        }
                    }

                    if (code) {
                        rtk.ssat[sat[j] - 1].resp[frq] = v[nv];
                    } else {
                        rtk.ssat[sat[j] - 1].resc[frq] = v[nv];
                    }

                    double threshadj = 1.0;
                    if (opt.mode > Constants.PMODE_DGPS) {
                        int ii = IB(sat[refIdx], frq, nf, opt);
                        int jj = IB(sat[j], frq, nf, opt);
                        double Pii = P[ii * rtk.nx + ii];
                        double Pjj = P[jj * rtk.nx + jj];
                        double std0sq = opt.std[0] * opt.std[0];
                        if (Pii == std0sq || Pjj == std0sq) {
                            threshadj = 10.0;
                        }
                    }
                    if (Math.abs(v[nv]) > opt.maxinno[code ? 1 : 0] * threshadj) {
                        LOG.debug(String.format("outlier: sat=%d-%d %s%d v=%.4f thresh=%.1f adj=%.1f", sat[refIdx], sat[j], code ? "P" : "L", frq + 1, v[nv], opt.maxinno[code ? 1 : 0], threshadj));
                        rtk.ssat[sat[j] - 1].vsat[frq] = 0;
                        rtk.ssat[sat[j] - 1].rejc[frq]++;
                        continue;
                    }

                    double eli = azel[1 + iu[refIdx] * 2];
                    double elj = azel[1 + iu[j] * 2];
                    int sysRef = SatUtils.satsys(sat[refIdx], null);
                    int sysJ = SatUtils.satsys(sat[j], null);
                    Ri[nv] = varerr(sat[refIdx], sysRef, eli, rtk.ssat[sat[refIdx]-1].snrRover[frq], rtk.ssat[sat[refIdx]-1].snrBase[frq], bl, dt, f, opt, obs[iu[refIdx]]);
                    Rj[nv] = varerr(sat[j], sysJ, elj, rtk.ssat[sat[j]-1].snrRover[frq], rtk.ssat[sat[j]-1].snrBase[frq], bl, dt, f, opt, obs[iu[j]]);

                    if (opt.mode > Constants.PMODE_DGPS) {
                        if (!code) {
                            rtk.ssat[sat[refIdx] - 1].vsat[frq] = 1;
                            rtk.ssat[sat[j] - 1].vsat[frq] = 1;
                        }
                    } else {
                        rtk.ssat[sat[refIdx] - 1].vsat[frq] = 1;
                        rtk.ssat[sat[j] - 1].vsat[frq] = 1;
                    }

                    vflg[nv] = (sat[refIdx] << 16) | (sat[j] << 8) | ((code ? 1 : 0) << 4) | frq;
                    nv++;
                    nb[b]++;
                }
                b++;
            }
        }

        ddcov(nb, b, Ri, Rj, nv, R);

        return nv;
    }

    /**
     * 计算观测值误差方差。
     *
     * <p>对应RTKLIB varerr()。根据高度角、基线长度、时间差等计算
     * 单差观测值的误差方差，用于构建双差协方差矩阵。</p>
     *
     * @param sat 卫星号
     * @param sys 卫星系统
     * @param el  高度角（弧度）
     * @param bl  基线长度（米）
     * @param dt  时间差（秒）
     * @param f   频率/码索引（0..nf-1为相位，nf..2*nf-1为伪距）
     * @param opt 处理选项
     * @return 观测误差方差（平方米）
     */
    private static double varerr(int sat, int sys, double el, double snr_rover, double snr_base,
                                 double bl, double dt, int f, PrcOpt opt, Obsd obs) {
        double a, b, c, d;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int frq = f % nf;
        boolean code = f >= nf;
        double s_el = Math.sin(el);
        if (s_el <= 0.0) s_el = 0.001;

        double fact;
        if (code) {
            fact = opt.eratio[frq];
        } else {
            fact = opt.eratio[frq] / opt.eratio[0];
        }

        switch (sys) {
            case Constants.SYS_GPS: fact *= Constants.EFACT_GPS; break;
            case Constants.SYS_GLO: fact *= Constants.EFACT_GLO; break;
            case Constants.SYS_GAL: fact *= Constants.EFACT_GAL; break;
            case Constants.SYS_SBS: fact *= Constants.EFACT_SBS; break;
            case Constants.SYS_QZS: fact *= Constants.EFACT_QZS; break;
            case Constants.SYS_CMP: fact *= Constants.EFACT_CMP; break;
            case Constants.SYS_IRN: fact *= Constants.EFACT_IRN; break;
            default: fact *= Constants.EFACT_GPS; break;
        }

        a = fact * opt.err[1];
        b = fact * opt.err[2];
        c = opt.err[3] * bl / 1E4;
        d = Constants.CLIGHT * opt.sclkstab * dt;

        double var = 2.0 * (SQR(a) + SQR(b / s_el) + SQR(c)) + SQR(d);

        if (opt.err[6] > 0.0) {
            double e = fact * opt.err[6];
            var += SQR(e) * (Math.pow(10, 0.1 * Math.max(opt.err[5] - snr_rover, 0.0)) +
                             Math.pow(10, 0.1 * Math.max(opt.err[5] - snr_base, 0.0)));
        }
        if (opt.err[7] > 0.0) {
            if (code) var += SQR(opt.err[7] * obs.Pstd[frq]);
            else var += SQR(opt.err[7] * obs.Lstd[frq] * 0.2);
        }

        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            var *= SQR(Math.pow(Constants.FREQL1 / SatUtils.sat2freq(sat, frq == 0 ? Constants.CODE_L1C : Constants.CODE_L2P, null), 2));
        }

        return var;
    }

    private static double SQR(double x) {
        return x * x;
    }

    /**
     * 构建双差协方差矩阵。
     *
     * <p>对应RTKLIB ddcov()。将单差方差Ri和Rj组合为双差协方差矩阵R。</p>
     *
     * <p>双差协方差矩阵的分块结构：同一组（同系统同频率）内的观测值
     * 共享参考星，因此协方差非零。不同组之间协方差为零。</p>
     *
     * <p><b>行优先存储</b>：R[i*ny + j] = 第i行第j列</p>
     *
     * @param nb  每组观测数数组
     * @param b   组数
     * @param Ri  参考星单差方差
     * @param Rj  非参考星单差方差
     * @param nv  总观测数
     * @param R   输出：双差协方差矩阵 [nv*nv]，行优先
     */
    private static void ddcov(int[] nb, int b, double[] Ri, double[] Rj, int nv, double[] R) {
        int i, j, k = 0;

        for (i = 0; i < nv * nv; i++) R[i] = 0.0;

        for (int bi = 0; bi < b; k += nb[bi++]) {
            for (i = 0; i < nb[bi]; i++) {
                for (j = 0; j < nb[bi]; j++) {
                    R[(k + i) * nv + (k + j)] = Ri[k + i] + (i == j ? Rj[k + i] : 0.0);
                }
            }
        }
    }

    /**
     * 检查流动站和基准站是否都有有效观测值。
     *
     * @param iu  流动站索引
     * @param ir  基准站索引
     * @param f   频率/码索引
     * @param nf  频率数
     * @param y   零差残差数组
     * @return true=两站都有有效观测
     */
    private static boolean validobs(int iu, int ir, int f, int nf, double[] y) {
        return y[f + iu * nf * 2] != 0.0 && y[f + ir * nf * 2] != 0.0;
    }

    /**
     * 计算基线长度。
     *
     * <p>基线 = x[0..2] - rb[0..2]，其中x是基线向量状态，rb是基准站坐标。</p>
     *
     * @param x   状态向量（基线向量在前3个元素）
     * @param rb  基准站ECEF坐标
     * @param dr  输出：基线分量（可为null）
     * @return 基线长度（米）
     */
    private static double baseline(double[] x, double[] rb, double[] dr) {
        if (dr != null) {
            for (int i = 0; i < 3; i++) dr[i] = x[i];
        }
        return RtklibCommon.norm(x, 3);
    }

    private static void udstate(Rtk rtk, Obsd[] obs, int[] sat, int[] iu, int[] ir,
                                int ns, Nav nav, int nf) {
        double tt = rtk.tt;

        udpos(rtk, tt);

        if (rtk.opt.mode > Constants.PMODE_DGPS) {
            udbias(rtk, tt, obs, sat, iu, ir, ns, nav, nf);
        }
    }

    private static void udpos(Rtk rtk, double tt) {
        int np = NP(rtk.opt);
        LOG.debug(String.format("udpos: mode=%d dynamics=%d x[0:2]=(%.6f,%.6f,%.6f) sol.rr[0:2]=(%.3f,%.3f,%.3f)",
                rtk.opt.mode, rtk.opt.dynamics, rtk.x[0], rtk.x[1], rtk.x[2],
                rtk.sol.rr[0], rtk.sol.rr[1], rtk.sol.rr[2]));
        if (rtk.opt.mode == Constants.PMODE_FIXED) {
            for (int i = 0; i < 3; i++) {
                initx(rtk, rtk.opt.ru[i] - rtk.rb[i], VAR_POS_FIX, i);
            }
            return;
        }

        double[] rr_abs = new double[3];
        for (int i = 0; i < 3; i++) rr_abs[i] = rtk.rb[i] + rtk.x[i];
        double normAbs = RtklibCommon.norm(rr_abs, 3);
        LOG.debug(String.format("udpos: normAbs=%.1f RE/2=%.1f mode=%d dynamics=%d x[0:2]=(%.4f,%.4f,%.4f)",
                normAbs, Constants.RE_WGS84 / 2.0, rtk.opt.mode, rtk.opt.dynamics, rtk.x[0], rtk.x[1], rtk.x[2]));
        if (normAbs <= Constants.RE_WGS84 / 2.0) {
            for (int i = 0; i < 3; i++) {
                initx(rtk, rtk.sol.rr[i] - rtk.rb[i], VAR_POS, i);
            }
            if (rtk.opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) initx(rtk, rtk.sol.rr[i], VAR_VEL, i);
                for (int i = 6; i < 9; i++) initx(rtk, 1E-6, VAR_ACC, i);
            }
        }

        if (rtk.opt.mode == Constants.PMODE_STATIC || rtk.opt.mode == Constants.PMODE_STATIC_START) {
            return;
        }

        if (rtk.opt.dynamics == 0) {
            for (int i = 0; i < 3; i++) {
                initx(rtk, rtk.sol.rr[i] - rtk.rb[i], VAR_POS, i);
            }
            return;
        }

        double var = 0.0;
        for (int i = 0; i < 3; i++) var += rtk.P[i * rtk.nx + i];
        var /= 3.0;

        if (var > VAR_POS) {
            for (int i = 0; i < 3; i++) initx(rtk, rtk.sol.rr[i] - rtk.rb[i], VAR_POS, i);
            for (int i = 3; i < 6; i++) initx(rtk, rtk.sol.rr[i], VAR_VEL, i);
            for (int i = 6; i < 9; i++) initx(rtk, 1E-6, VAR_ACC, i);
            return;
        }

        int[] ix = new int[rtk.nx];
        int nxValid = 0;
        for (int i = 0; i < rtk.nx; i++) {
            if (i < np || (rtk.x[i] != 0.0 && rtk.P[i * rtk.nx + i] > 0.0)) {
                ix[nxValid++] = i;
            }
        }

        double[] F = new double[nxValid * nxValid];
        for (int i = 0; i < nxValid; i++) F[i * nxValid + i] = 1.0;

        for (int i = 0; i < 6; i++) {
            F[i * nxValid + (i + 3)] = tt;
        }

        if (var < rtk.opt.thresar[1]) {
            for (int i = 0; i < 3; i++) {
                F[i * nxValid + (i + 6)] = (tt >= 0 ? 1 : -1) * tt * tt / 2.0;
            }
        }

        double[] x = new double[nxValid];
        double[] P = new double[nxValid * nxValid];
        for (int i = 0; i < nxValid; i++) {
            x[i] = rtk.x[ix[i]];
            for (int j = 0; j < nxValid; j++) {
                P[i * nxValid + j] = rtk.P[ix[i] * rtk.nx + ix[j]];
            }
        }

        double[] xp = new double[nxValid];
        double[] FP = new double[nxValid * nxValid];
        double[] Pnew = new double[nxValid * nxValid];

        for (int i = 0; i < nxValid; i++) {
            double sum = 0.0;
            for (int j = 0; j < nxValid; j++) {
                sum += F[i * nxValid + j] * x[j];
            }
            xp[i] = sum;
        }

        for (int i = 0; i < nxValid; i++) {
            for (int j = 0; j < nxValid; j++) {
                double sum = 0.0;
                for (int k = 0; k < nxValid; k++) {
                    sum += F[i * nxValid + k] * P[k * nxValid + j];
                }
                FP[i * nxValid + j] = sum;
            }
        }

        for (int i = 0; i < nxValid; i++) {
            for (int j = 0; j < nxValid; j++) {
                double sum = 0.0;
                for (int k = 0; k < nxValid; k++) {
                    sum += FP[i * nxValid + k] * F[j * nxValid + k];
                }
                Pnew[i * nxValid + j] = sum;
            }
        }

        for (int i = 0; i < nxValid; i++) {
            rtk.x[ix[i]] = xp[i];
            for (int j = 0; j < nxValid; j++) {
                rtk.P[ix[i] * rtk.nx + ix[j]] = Pnew[i * nxValid + j];
            }
        }

        double[] Q = new double[9];
        Q[0] = rtk.opt.prn[3] * rtk.opt.prn[3] * Math.abs(tt);
        Q[4] = rtk.opt.prn[3] * rtk.opt.prn[3] * Math.abs(tt);
        Q[8] = rtk.opt.prn[4] * rtk.opt.prn[4] * Math.abs(tt);

        double[] pos = new double[3];
        double[] rrAbs = new double[3];
        for (int i = 0; i < 3; i++) rrAbs[i] = rtk.x[i] + rtk.rb[i];
        CoordTransform.ecef2pos(rrAbs, pos);

        double[] Qv = new double[9];
        CoordTransform.covecef(pos, Q, Qv);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                rtk.P[(i + 6) * rtk.nx + (j + 6)] += Qv[i * 3 + j];
            }
        }
    }

    private static void udbias(Rtk rtk, double tt, Obsd[] obs, int[] sat, int[] iu, int[] ir,
                               int ns, Nav nav, int nf) {
        PrcOpt opt = rtk.opt;
        for (int i = 0; i < ns; i++) {
            for (int k = 0; k < nf; k++) {
                rtk.ssat[sat[i] - 1].slip[k] &= 0xFC;
            }
        }

        for (int k = 0; k < nf; k++) {
            for (int i = 1; i <= Constants.MAXSAT; i++) {
                boolean reset = ++rtk.ssat[i - 1].outc[k] > opt.maxout;
                int j = IB(i, k, nf, opt);
                if (j >= rtk.nx) continue;
                if (opt.modear == Constants.ARMODE_INST && rtk.x[j] != 0.0) {
                    LOG.debug(String.format("udbias reset INST: sat=%d f=%d idx=%d old=%.4f", i, k, j, rtk.x[j]));
                    initx(rtk, 0.0, 0.0, j);
                } else if (reset && rtk.x[j] != 0.0) {
                    LOG.debug(String.format("udbias reset OUTC: sat=%d f=%d idx=%d outc=%d old=%.4f", i, k, j, rtk.ssat[i - 1].outc[k], rtk.x[j]));
                    initx(rtk, 0.0, 0.0, j);
                    rtk.ssat[i - 1].outc[k] = 0;
                }
                if (opt.modear != Constants.ARMODE_INST && reset) {
                    rtk.ssat[i - 1].lock[k] = -opt.minlock;
                }
            }

            for (int i = 0; i < ns; i++) {
                int j = IB(sat[i], k, nf, opt);
                if (j >= rtk.nx) continue;
                rtk.P[j * rtk.nx + j] += opt.prn[0] * opt.prn[0] * Math.abs(tt);
                int slip = rtk.ssat[sat[i] - 1].slip[k];
                int rejc = (int) rtk.ssat[sat[i] - 1].rejc[k];
                if (opt.modear == Constants.ARMODE_INST || ((slip & Constants.LLI_SLIP) == 0 && rejc < 2)) continue;
                LOG.debug(String.format("udbias reset SLIP/REJC: sat=%d f=%d idx=%d slip=%d rejc=%d old=%.4f", sat[i], k, j, slip, rejc, rtk.x[j]));
                rtk.x[j] = 0.0;
                rtk.ssat[sat[i] - 1].rejc[k] = 0;
                rtk.ssat[sat[i] - 1].lock[k] = -opt.minlock;
                if (rtk.ssat[sat[i] - 1].sys != Constants.SYS_GLO) {
                    rtk.ssat[sat[i] - 1].icbias[k] = 0;
                }
            }

            double[] bias = new double[ns];
            int cnt = 0;
            double offset = 0.0;
            for (int i = 0; i < ns; i++) {
                if (opt.ionoopt != Constants.IONOOPT_IFLC) {
                    double cp = sdobs(obs, iu[i], ir[i], k);
                    double pr = sdobs(obs, iu[i], ir[i], k + Constants.NFREQ);
                    double freqi = SatUtils.sat2freq(sat[i], obs[iu[i]].code[k], nav);
                    if (cp == 0.0 || pr == 0.0 || freqi == 0.0) continue;
                    bias[i] = cp - pr * freqi / Constants.CLIGHT;
                } else {
                    double cp1 = sdobs(obs, iu[i], ir[i], 0);
                    double cp2 = sdobs(obs, iu[i], ir[i], 1);
                    double pr1 = sdobs(obs, iu[i], ir[i], Constants.NFREQ);
                    double pr2 = sdobs(obs, iu[i], ir[i], Constants.NFREQ + 1);
                    double freq1 = SatUtils.sat2freq(sat[i], obs[iu[i]].code[0], nav);
                    double freq2 = SatUtils.sat2freq(sat[i], obs[iu[i]].code[1], nav);
                    if (cp1 == 0.0 || cp2 == 0.0 || pr1 == 0.0 || pr2 == 0.0 || freq1 <= 0.0 || freq2 <= 0.0)
                        continue;
                    double C1 = freq1 * freq1 / (freq1 * freq1 - freq2 * freq2);
                    double C2 = -freq2 * freq2 / (freq1 * freq1 - freq2 * freq2);
                    bias[i] = (C1 * cp1 * Constants.CLIGHT / freq1 + C2 * cp2 * Constants.CLIGHT / freq2) - (C1 * pr1 + C2 * pr2);
                }
                int idx = IB(sat[i], k, nf, opt);
                if (idx < rtk.nx && rtk.x[idx] != 0.0) {
                    offset += bias[i] - rtk.x[idx];
                    cnt++;
                }
            }

            if (cnt > 0) {
                for (int i = 1; i <= Constants.MAXSAT; i++) {
                    int idx = IB(i, k, nf, opt);
                    if (idx < rtk.nx && rtk.x[idx] != 0.0) {
                        rtk.x[idx] += offset / cnt;
                    }
                }
            }

            for (int i = 0; i < ns; i++) {
                if (bias[i] == 0.0) continue;
                int idx = IB(sat[i], k, nf, opt);
                if (idx >= rtk.nx) continue;
                if (rtk.x[idx] != 0.0) continue;
                initx(rtk, bias[i], opt.std[0] * opt.std[0], idx);
                LOG.debug(String.format("udbias init: sat=%d f=%d idx=%d bias=%.4f var=%.1f", sat[i], k, idx, bias[i], opt.std[0] * opt.std[0]));
                if (opt.modear != Constants.ARMODE_INST) {
                    rtk.ssat[sat[i] - 1].lock[k] = -opt.minlock;
                }
            }
        }
    }

    private static double sdobs(Obsd[] obs, int iu, int ir, int f) {
        double val1, val2;
        if (f < Constants.NFREQ) {
            val1 = obs[iu].L[f];
            val2 = obs[ir].L[f];
        } else {
            val1 = obs[iu].P[f - Constants.NFREQ];
            val2 = obs[ir].P[f - Constants.NFREQ];
        }
        if (val1 == 0.0 || val2 == 0.0) return 0.0;
        return val1 - val2;
    }

    private static final double VAR_POS_FIX = 1E-8;

    /**
     * 初始化状态向量中的一个元素及其协方差。
     *
     * <p>将 x[i] 设为 val，P 矩阵第 i 行和第 i 列清零，
     * 对角线 P[i*nx+i] 设为 var。</p>
     *
     * <p><b>行优先存储</b>：P[i*nx + j] = 第i行第j列</p>
     *
     * @param rtk RTK解算状态
     * @param val 状态值
     * @param var 状态方差
     * @param i   状态索引
     */
    private static void initx(Rtk rtk, double val, double var, int i) {
        rtk.x[i] = val;
        for (int j = 0; j < rtk.nx; j++) {
            rtk.P[i * rtk.nx + j] = 0.0;
            rtk.P[j * rtk.nx + i] = 0.0;
        }
        rtk.P[i * rtk.nx + i] = var;
    }

    /**
     * 选择流动站和基准站的共视卫星。
     *
     * <p>遍历流动站和基准站的观测数据，找出两站都观测到的卫星。</p>
     *
     * @param obs 观测数据数组
     * @param nu  流动站观测数
     * @param nr  基准站观测数
     * @param opt 处理选项
     * @param sat 输出：共视卫星号数组
     * @param iu  输出：流动站卫星索引
     * @param ir  输出：基准站卫星索引
     * @return 共视卫星数
     */
    private static int selsat(Obsd[] obs, double[] azel, int nu, int nr, PrcOpt opt,
                              int[] sat, int[] iu, int[] ir) {
        int ns = 0;
        int i = 0, j = nu;
        while (i < nu && j < nu + nr) {
            if (obs[i].sat < obs[j].sat) {
                i++;
            } else if (obs[i].sat > obs[j].sat) {
                j++;
            } else {
                if (azel[1 + j * 2] >= opt.elmin) {
                    sat[ns] = obs[i].sat;
                    iu[ns] = i;
                    ir[ns] = j;
                    ns++;
                }
                i++;
                j++;
            }
        }
        return ns;
    }

    /**
     * Kalman滤波测量更新。
     *
     * <p>封装 KalmanFilter.update()，所有矩阵按行优先存储，
     * 内部通过 MatrixUtil 转为 EJML SimpleMatrix 进行运算。</p>
     *
     * @param x 状态向量 [n]，输入：预测值，输出：更新值
     * @param P 协方差矩阵 [n*n]，行优先，输入：预测值，输出：更新值
     * @param H 设计矩阵 [m*n]，行优先
     * @param v 残差向量 [m]
     * @param R 观测协方差矩阵 [m*m]，行优先
     * @param n 状态维数
     * @param m 观测维数
     * @return 0:成功 -1:失败
     */
    private static int filter(double[] x, double[] P, double[] H, double[] v, double[] R, int n, int m) {
        return KalmanFilter.update(x, P, H, v, R, n, m);
    }

    /**
     * 构建双差变换索引。
     *
     * <p>对应RTKLIB ddidx()。生成单差模糊度到双差模糊度的变换索引。</p>
     *
     * <p>对每个频率，选择第一颗满足条件的卫星作为参考星，
     * 其余满足条件的卫星与参考星形成双差对。</p>
     *
     * <p>ix[i*2] = 参考星状态索引，ix[i*2+1] = 目标星状态索引</p>
     *
     * @param rtk RTK解算状态
     * @param sat 共视卫星号数组
     * @param ns  共视卫星数
     * @param nf  频率数
     * @param nx  状态维数
     * @param ix  输出：双差索引对 [nx*2]
     * @return 双差对数（nb）
     */
    private static int ddidx(Rtk rtk, int[] sat, int ns, int nf, int nx, int[] ix) {
        int nb = 0;
        PrcOpt opt = rtk.opt;
        for (int f = 0; f < nf; f++) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                rtk.ssat[i].fix[f] = 0;
            }
            int refIdx = -1;
            for (int i = 0; i < ns; i++) {
                int si = sat[i] - 1;
                int stateIdx = IB(sat[i], f, nf, opt);
                if (rtk.x[stateIdx] == 0.0 || rtk.ssat[si].vsat[f] == 0) continue;
                if (rtk.ssat[si].lock[f] >= 0 && rtk.ssat[si].azel[1] >= opt.elmaskar) {
                    rtk.ssat[si].fix[f] = 2;
                    refIdx = i;
                    break;
                } else {
                    rtk.ssat[si].fix[f] = 1;
                }
            }
            if (refIdx < 0) continue;
            int refStateIdx = IB(sat[refIdx], f, nf, opt);
            int n = 0;
            for (int i = 0; i < ns; i++) {
                if (i == refIdx) continue;
                int si = sat[i] - 1;
                int stateIdx = IB(sat[i], f, nf, opt);
                if (rtk.x[stateIdx] == 0.0 || rtk.ssat[si].vsat[f] == 0) continue;
                if (rtk.ssat[si].lock[f] >= 0 && rtk.ssat[si].azel[1] >= opt.elmaskar) {
                    ix[nb * 2] = refStateIdx;
                    ix[nb * 2 + 1] = stateIdx;
                    rtk.ssat[si].fix[f] = 2;
                    nb++;
                    n++;
                } else {
                    rtk.ssat[si].fix[f] = 1;
                }
            }
            if (n == 0) {
                rtk.ssat[sat[refIdx] - 1].fix[f] = 1;
            }
        }
        return nb;
    }

    /**
     * 将双差固定模糊度还原为单差固定模糊度。
     *
     * <p>对应RTKLIB restamb()。LAMBDA输出的是双差模糊度的固定值，
     * 需要转换回单差模糊度才能更新状态向量。</p>
     *
     * <p>转换规则：参考星单差模糊度保持浮点值不变，
     * 其他卫星单差模糊度 = 参考星单差 - 双差固定值。</p>
     *
     * @param rtk  RTK解算状态
     * @param bias 双差固定模糊度 [nb]
     * @param nb   双差对数
     * @param sat  共视卫星号数组
     * @param ns   共视卫星数
     * @param nf   频率数
     * @param nx   状态维数
     * @param xa   输出：固定解状态向量（单差）
     */
    private static void restamb(Rtk rtk, double[] bias, int nb, int[] sat, int ns, int nf, int nx, double[] xa) {
        int nv = 0;
        PrcOpt opt = rtk.opt;
        for (int f = 0; f < nf; f++) {
            int[] index = new int[ns];
            int n = 0;
            for (int i = 0; i < ns; i++) {
                int si = sat[i] - 1;
                if (rtk.ssat[si].fix[f] != 2) continue;
                index[n++] = IB(sat[i], f, nf, opt);
            }
            if (n < 2) continue;
            xa[index[0]] = rtk.x[index[0]];
            for (int i = 1; i < n; i++) {
                xa[index[i]] = xa[index[0]] - bias[nv++];
            }
        }
    }

    /**
     * 固定模糊度保持（Fix-and-Hold）。
     *
     * <p>当LAMBDA固定解连续达到minfix个历元后，将固定模糊度值
     * 写回状态向量，并用较小方差 thresar[1] 替代原始大方差，
     * 使后续历元模糊度不易漂移。</p>
     *
     * @param rtk RTK解算状态
     * @param xa  固定解状态向量
     * @param sat 共视卫星号数组
     * @param ns  共视卫星数
     * @param nf  频率数
     * @param nx  状态维数
     * @param nav 导航数据
     */
    private static void holdamb(Rtk rtk, double[] xa, int[] sat, int ns, int nf, int nx, Nav nav) {
        PrcOpt opt = rtk.opt;
        for (int i = 0; i < ns; i++) {
            for (int f = 0; f < nf; f++) {
                int idx = IB(sat[i], f, nf, opt);
                if (idx >= nx) continue;
                rtk.x[idx] = xa[idx];
                rtk.P[idx * nx + idx] = SQR(opt.thresar[1]);
            }
        }
    }
}
