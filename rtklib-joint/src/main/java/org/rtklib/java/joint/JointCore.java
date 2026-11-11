package org.rtklib.java.joint;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public final class JointCore {
    private static final Logger log = LoggerFactory.getLogger(JointCore.class);
    /**
     * [§13.9 Phase B] 降权达到该倍数（R 膨胀 4 倍，对应位移约 2 倍门限）的基线不参与 AR。
     *
     * <p>AR 取各基线 ratio 的最小值，任一条基线 LAMBDA 失败即整体退回 FLOAT。被降权的基线
     * 模糊度方差随 R 同比放大（4 倍 R → 2 倍 σ），其 Qb 已无法支撑整数固定；若仍把它
     * 纳入 AR，会拖垮其余健康基线的固定（实测双基线 FIX 率 91% → 55%）。
     * 故超过该倍数即从 AR 中摘除，只保留它在滤波中的（已降权的）观测贡献。</p>
     */
    private static final double AR_SKIP_WEIGHT = 4.0;

    private JointCore() {
    }

    public static int rtkpos(JointRtk jrtk, Obsd[] roverObs, int roverN,
                             BaseStation[] bases, int nBaseline, Nav nav) {
        if (jrtk.opt == null || roverObs == null || roverN <= 0) {
            log.warn("rtkpos early return: opt={}, roverObs={}, roverN={}", jrtk.opt, roverObs, roverN);
            return 0;
        }
        if (bases == null || nBaseline <= 0) {
            log.warn("rtkpos early return: bases={}, nBaseline={}", bases, nBaseline);
            return 0;
        }

        PrcOpt opt = jrtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            jrtk.ssat[i].sys = SatUtils.satsys(i + 1, null);
            for (int j = 0; j < Constants.NFREQ; j++) {
                jrtk.ssat[i].vsat[j] = 0;
                jrtk.ssat[i].snrRover[j] = 0;
                jrtk.ssat[i].snrBase[j] = 0;
            }
        }

        if (jrtk.P == null || jrtk.P.length == 0 || jrtk.P[0] == 0.0 || jrtk.P[0] > 1E14) {
            PrcOpt sppOpt = new PrcOpt(opt);
            sppOpt.mode = Constants.PMODE_SINGLE;
            sppOpt.ionoopt = Constants.IONOOPT_BRDC;
            sppOpt.tropopt = Constants.TROPOPT_SAAS;
            int sppResult = PntPos.pntpos(roverObs, roverN, nav, sppOpt, jrtk.sol, null, jrtk.ssat);
            if (sppResult == 0) {
                log.warn("Joint SPP failed for rover (roverN={}), cannot initialize position", roverN);
                if (opt.dynamics == 0) {
                    for (int b = 0; b < nBaseline; b++) {
                        if (bases[b] != null) {
                            double[] dr = new double[3];
                            for (int j = 0; j < 3; j++) dr[j] = bases[b].rb[j] - jrtk.rb[j];
                            double bl = RtklibCommon.norm(dr, 3);
                            log.info("  baseline[{}] length={} km", b, bl / 1000.0);
                        }
                    }
                    return 0;
                }
            } else {
                log.info("Joint SPP rover pos: ({}, {}, {})", jrtk.sol.rr[0], jrtk.sol.rr[1], jrtk.sol.rr[2]);
            }
        } else {
            jrtk.tt = TimeSystem.timediff(roverObs[0].time, jrtk.sol.time);
            jrtk.sol.time = roverObs[0].time;
        }

        BaselineObs[] blObs = new BaselineObs[nBaseline];
        for (int b = 0; b < nBaseline; b++) {
            blObs[b] = new BaselineObs();
            blObs[b].clear();

            // [§13.9 Phase D] 已剔除的基线不构造观测，后续所有循环都以 ns<=0 跳过它，
            // 从而彻底退出滤波；nx 布局与索引保持不变。
            if (jrtk.isBaselineExcluded(b)) continue;

            BaseStation bs = bases[b];
            if (bs == null || bs.obsBuf == null || bs.obsCount <= 0) continue;

            int nu = roverN;
            int nr = bs.obsCount;
            int n = nu + nr;
            if (n > Constants.MAXOBS) {
                if (nu > Constants.MAXOBS) {
                    nu = Constants.MAXOBS;
                    nr = 0;
                } else {
                    nr = Constants.MAXOBS - nu;
                }
            }
            blObs[b].nu = nu;
            blObs[b].nr = nr;

            Obsd[] merged = new Obsd[nu + nr];
            System.arraycopy(roverObs, 0, merged, 0, nu);
            System.arraycopy(bs.obsBuf, 0, merged, nu, nr);

            Arrays.fill(blObs[b].azel, 0, (nu + nr) * 2, 0.0);
            Arrays.fill(blObs[b].freq, 0, (nu + nr) * nf, 0.0);

            EphModel.satposs(roverObs[0].time, merged, nu + nr, nav,
                    blObs[b].rs, blObs[b].dts, blObs[b].vare, blObs[b].svh, opt.sateph);

            RtkState state = jrtk.toState();

            if (!RtkCore.zdres(state, 1, merged, nu, nr,
                    blObs[b].rs, blObs[b].dts, blObs[b].vare, blObs[b].svh,
                    nav, bs.rb, opt,
                    blObs[b].y, blObs[b].e, blObs[b].azel, blObs[b].freq)) {
                log.warn("baseline[{}] zdres failed, rb=({},{},{})", b, bs.rb[0], bs.rb[1], bs.rb[2]);
                continue;
            }

            blObs[b].ns = RtkCore.selsat(merged, blObs[b].azel, nu, nr, opt,
                    blObs[b].sat, blObs[b].iu, blObs[b].ir);
            double dtBase = TimeSystem.timediff(roverObs[0].time, merged[nu].time);
            log.info("baseline[{}] ns={}, nu={}, nr={}, dtBase={}s, rb=({},{},{})",
                    b, blObs[b].ns, nu, nr, String.format("%.3f", dtBase), bs.rb[0], bs.rb[1], bs.rb[2]);
        }

        BaselineGroup group = new BaselineGroup(nBaseline);
        group.time = roverObs[0].time;
        group.roverObs = roverObs;
        group.roverObsCount = roverN;
        group.nBaseline = nBaseline;
        for (int i = 0; i < nBaseline; i++) group.bases[i] = bases[i];

        EpochAligner aligner = new EpochAligner(jrtk.jointConfig.maxTimeDiff);
        aligner.fillCommonSats(group, blObs);
        aligner.selectGlobalRefSatBySys(group, jrtk.ssat, blObs);

        int totalNs = 0;
        for (int b = 0; b < nBaseline; b++) {
            if (blObs[b] != null) totalNs += blObs[b].ns;
        }
        if (totalNs <= 0) {
            log.warn("totalNs=0, no common satellites across baselines");
            return 0;
        }

        // [§2.1.1] 共享大气维度：大气状态属于「rover+卫星」/「基站+卫星」，与基线无关，
        // 不再按基线复制。nx = pos + [iono 块] + [rover 对流层块] + [每站 base 对流层] + Σ amb。
        jrtk.refreshAtmSharing();
        int nxNew = jrtk.NXTotal();
        int naNew = jrtk.NRTotal();

        if (jrtk.nx != nxNew || jrtk.x == null || jrtk.x.length != nxNew) {
            jrtk.nx = nxNew;
            jrtk.na = naNew;
            jrtk.x = new double[nxNew];
            jrtk.P = new double[nxNew * nxNew];

            double baseline0 = jrtk.sol.rr[0] - jrtk.rb[0];
            double baseline1 = jrtk.sol.rr[1] - jrtk.rb[1];
            double baseline2 = jrtk.sol.rr[2] - jrtk.rb[2];
            JointDdRes.initx(jrtk.x, jrtk.P, nxNew, baseline0, Constants.VAR_POS, 0);
            JointDdRes.initx(jrtk.x, jrtk.P, nxNew, baseline1, Constants.VAR_POS, 1);
            JointDdRes.initx(jrtk.x, jrtk.P, nxNew, baseline2, Constants.VAR_POS, 2);
            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) JointDdRes.initx(jrtk.x, jrtk.P, nxNew, 0.0, Constants.VAR_VEL, i);
                for (int i = 6; i < 9; i++) JointDdRes.initx(jrtk.x, jrtk.P, nxNew, 1E-6, Constants.VAR_ACC, i);
            }
        } else {
            jrtk.na = naNew;
        }
        jrtk.baselineOffset = new int[nBaseline];
        for (int b = 0; b < nBaseline; b++) {
            jrtk.baselineOffset[b] = jrtk.baselineOffsetOf(b);
        }
        int nx = jrtk.nx;

        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null) continue;
            for (int i = 0; i < bo.ns; i++) {
                for (int f = 0; f < nf; f++) {
                    jrtk.ssat[bo.sat[i] - 1].snrRover[f] = roverObs[bo.iu[i]].SNR[f];
                    jrtk.ssat[bo.sat[i] - 1].snrBase[f] = bases[b].obsBuf[bo.ir[i] - bo.nu].SNR[f];
                }
            }
        }

        // [FIX-ALG-042 P2] rover 侧初始 zdres：udstate 的载波-伪距模糊度初始化需要完整 y。
        // 此前仅基站侧 zdres 已完成（rover 段 y 全零），载波-伪距组合恒 0，模糊度永远无法初始化。
        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;
            BaseStation bs = bases[b];
            int nu = bo.nu, nr = bo.nr;
            Obsd[] mergedInit = new Obsd[nu + nr];
            System.arraycopy(roverObs, 0, mergedInit, 0, nu);
            System.arraycopy(bs.obsBuf, 0, mergedInit, nu, nr);
            double[] rr_rover0 = new double[3];
            for (int j = 0; j < 3; j++) rr_rover0[j] = jrtk.rb[j] + jrtk.x[j];
            RtkCore.zdres(jrtk.toState(), 0, mergedInit, nu, nr,
                    bo.rs, bo.dts, bo.vare, bo.svh,
                    nav, rr_rover0, opt,
                    bo.y, bo.e, bo.azel, bo.freq);
        }

        detectCycleSlip(jrtk, roverObs, roverN, bases, nBaseline, nf, blObs, nav, opt);

        udstate(jrtk, roverObs, roverN, bases, nBaseline, nav, blObs, group);

        double[] xp = new double[nx];
        double[] Pp = new double[nx * nx];
        System.arraycopy(jrtk.x, 0, xp, 0, nx);
        System.arraycopy(jrtk.P, 0, Pp, 0, nx * nx);

        double dt = 0.0;
        if (nBaseline > 0 && bases[0] != null && bases[0].obsTime != null) {
            dt = TimeSystem.timediff(roverObs[0].time, bases[0].obsTime);
        }

        int stat = Constants.SOLQ_FLOAT;
        int nv = 0;
        // [FIX-ALG-042 §13.4 Phase A] 保留最后一次迭代的逐基线残差，供基站稳定性监测使用
        double[][] lastV = new double[nBaseline][];
        double[][] lastVar = new double[nBaseline][];
        int[][] lastVflg = new int[nBaseline][];
        int[] lastNv = new int[nBaseline];

        for (int iter = 0; iter < opt.niter; iter++) {
            for (int b = 0; b < nBaseline; b++) {
                BaselineObs bo = blObs[b];
                if (bo == null || bo.ns <= 0) continue;
                BaseStation bs = bases[b];
                int nu = bo.nu;
                int nr = bo.nr;
                Obsd[] merged = new Obsd[nu + nr];
                System.arraycopy(roverObs, 0, merged, 0, nu);
                System.arraycopy(bs.obsBuf, 0, merged, nu, nr);

                double[] rr_rover = new double[3];
                for (int j = 0; j < 3; j++) rr_rover[j] = jrtk.rb[j] + xp[j];

                RtkState state = jrtk.toState();
                RtkCore.zdres(state, 0, merged, nu, nr,
                        bo.rs, bo.dts, bo.vare, bo.svh,
                        nav, rr_rover, opt,
                        bo.y, bo.e, bo.azel, bo.freq);
                RtkCore.zdres(state, 1, merged, nu, nr,
                        bo.rs, bo.dts, bo.vare, bo.svh,
                        nav, bs.rb, opt,
                        bo.y, bo.e, bo.azel, bo.freq);
            }

            for (int i = 0; i < Constants.MAXSAT; i++) {
                for (int j = 0; j < Constants.NFREQ; j++) {
                    jrtk.ssat[i].resp[j] = 0.0;
                    jrtk.ssat[i].resc[j] = 0.0;
                }
            }
            nv = 0;
            int totalInfo = 0;
            for (int b = 0; b < nBaseline; b++) {
                if (blObs[b] == null || blObs[b].ns <= 0) continue;
                int maxNvB = blObs[b].ns * nf * 2;
                double[] vB = new double[maxNvB];
                double[] HB = new double[nx * maxNvB];
                double[] RB = new double[maxNvB * maxNvB];
                int[] vflgB = new int[maxNvB];
                int nvB = JointDdRes.ddres(jrtk, group, blObs, nav, dt, xp, Pp, vB, HB, RB, vflgB, b);
                if (nvB <= 0) {
                    // 本历元该基线无有效残差：清空缓存，避免监测器把上一历元的残差当成新历元复用。
                    lastV[b] = null;
                    lastVflg[b] = null;
                    lastVar[b] = null;
                    lastNv[b] = 0;
                    continue;
                }
                double[] varB = new double[nvB];
                for (int i = 0; i < nvB; i++) varB[i] = RB[i * nvB + i];
                lastVflg[b] = vflgB;
                lastVar[b] = varB;
                lastNv[b] = nvB;
                // [§13.9 Phase B] 闸2 结论联动降权：按监测器给出的权重整体膨胀 R_b。
                // 必须在取 varB 之后（监测器用它算 χ²/dof 与闸2 反演，需用未膨胀的方差，
                // 否则降权会让 χ² 人为下降，形成「降权 → 判定正常 → 恢复」的假自愈环）。
                double wB = 1.0;
                if (jrtk.monitor != null && jrtk.jointConfig != null
                        && jrtk.jointConfig.baseStabilityDownWeight) {
                    wB = jrtk.monitor.getWeight(b);
                }
                if (wB > 1.0) {
                    for (int i = 0; i < nvB * nvB; i++) RB[i] *= wB;
                    log.debug("iter={} baseline={} 降权 R x{} (Phase B)", iter, b, String.format("%.1f", wB));
                }
                double[] xpBefore = xp.clone();
                int infoB = KalmanFilter.update(xp, Pp, HB, vB, RB, nx, nvB);
                // [FIX-ALG-042 §13.4 Phase A] 监测器需要「后验残差」：opt.niter=1 时 ddres 给出的是
                // 先验残差，其中含米级先验位置误差，会与基站位移混淆。后验残差可解析得到
                // v_post = v_pre - H·dx（H 为 nv×nx 行主序），纯只读，不改变 x/P。
                double[] vPost = new double[nvB];
                for (int j = 0; j < nvB; j++) {
                    double s = vB[j];
                    int row = j * nx;
                    for (int i = 0; i < nx; i++) s -= HB[row + i] * (xp[i] - xpBefore[i]);
                    vPost[j] = s;
                }
                lastV[b] = vPost;
                nv += nvB;
                if (infoB != 0) {
                    totalInfo = infoB;
                    log.warn("iter={} baseline={} Kalman update failed info={} nx={} nvB={}", iter, b, infoB, nx, nvB);
                }
            }
            if (nv < 4) {
                log.warn("iter={} ddres nv={} < 4, insufficient observations", iter, nv);
                stat = Constants.SOLQ_NONE;
                break;
            }
            if (totalInfo != 0) {
                log.warn("iter={} Kalman update failed for some baseline(s) totalInfo={} nx={} nv={}", iter, totalInfo, nx, nv);
                stat = Constants.SOLQ_NONE;
                break;
            }

            if (jrtk.epoch == 0 && iter == 0) {
                double minAmbVar = Double.MAX_VALUE, maxAmbVar = 0, sumAmbVar = 0;
                int ambCnt = 0;
                for (int b = 0; b < blObs.length; b++) {
                    if (blObs[b] == null) continue;
                    for (int i = 0; i < blObs[b].ns; i++) {
                        for (int f = 0; f < nf; f++) {
                            int idx = jrtk.IB(b, blObs[b].sat[i], f);
                            if (idx >= 0 && idx < nx && Pp[idx*nx+idx] > 0) {
                                double var = Pp[idx*nx+idx];
                                minAmbVar = Math.min(minAmbVar, var);
                                maxAmbVar = Math.max(maxAmbVar, var);
                                sumAmbVar += var;
                                ambCnt++;
                            }
                        }
                    }
                }
                double posVar = (Pp[0] + Pp[1*nx+1] + Pp[2*nx+2]) / 3.0;
                log.debug("epoch=0 AFTER Kalman: posVar={} ambVar min={} max={} avg={}", String.format("%.6f", posVar),
                        String.format("%.4f", minAmbVar), String.format("%.4f", maxAmbVar),
                        ambCnt > 0 ? String.format("%.4f", sumAmbVar/ambCnt) : "N/A");
            }
        }

        // [FIX-ALG-042 §13.4 Phase A] 基站稳定性监测：闸1（χ²）+ 闸2（载波残差反演位移）。
        // [§13.9 Phase B/D] 监测结论在本历元末落地：Phase B 的降权在下一历元生效（R 膨胀），
        // Phase D 的剔除立即冻结该基线的模糊度状态段。
        if (stat != Constants.SOLQ_NONE && jrtk.jointConfig != null && jrtk.jointConfig.baseStabilityMonitor) {
            if (jrtk.monitor == null) jrtk.monitor = new BaseStabilityMonitor();
            jrtk.monitor.update(blObs, nBaseline, lastV, lastNv, lastVflg, lastVar);

            if (jrtk.jointConfig.baseDisplacedReject && jrtk.monitor.hasDisplaced()) {
                for (int b = 0; b < nBaseline; b++) {
                    if (jrtk.monitor.isDisplaced(b) && !jrtk.isBaselineExcluded(b)) {
                        log.error("joint: baseline[{}] 判定 DISPLACED，从联合滤波中剔除（Phase D）", b);
                        jrtk.excludeBaseline(b);
                    }
                }
                if (jrtk.excludedCount() >= nBaseline) {
                    log.error("joint: 全部 {} 条基线均已被剔除，联合解算不可用（需人工介入）", nBaseline);
                }
            }
        } else {
            jrtk.monitor = null;
        }

        // [FIX-ALG-042 §13.9 Phase C] 闸3：基站间基线骨架网（独立于 rover 的位移观测量）。
        // 放在监测之后：闸2 结论先落地，闸3 结论仅作为佐证与补盲（rover 无解时仍可工作）。
        if (jrtk.jointConfig != null && jrtk.jointConfig.baseSkeletonNetwork) {
            if (jrtk.skeleton == null) jrtk.skeleton = new BaseSkeletonNetwork();
            jrtk.skeleton.update(jrtk, bases, nBaseline, nav);
        }

        if (stat == Constants.SOLQ_FLOAT) {
            System.arraycopy(xp, 0, jrtk.x, 0, nx);
            System.arraycopy(Pp, 0, jrtk.P, 0, nx * nx);

            for (int i = 0; i < 3; i++) {
                jrtk.sol.rr[i] = jrtk.rb[i] + jrtk.x[i];
                jrtk.sol.qr[i] = (float) jrtk.P[i * nx + i];
            }
            jrtk.sol.qr[3] = (float) jrtk.P[0 * nx + 1];
            jrtk.sol.qr[4] = (float) jrtk.P[1 * nx + 2];
            jrtk.sol.qr[5] = (float) jrtk.P[0 * nx + 2];

            jrtk.sol.ns = 0;
            for (int i = 0; i < Constants.MAXSAT; i++) {
                for (int f = 0; f < nf; f++) {
                    if (jrtk.ssat[i].vsat[f] != 0 && f == 0) jrtk.sol.ns++;
                }
            }

            jrtk.sol.stat = Constants.SOLQ_FLOAT;

            if (opt.modear != Constants.ARMODE_OFF && opt.thresar[0] >= 1.0) {
                stat = resolveAmbiguity(jrtk, blObs, nf, nav, group.globalRefSat);
                if (stat == Constants.SOLQ_FIX) {
                    jrtk.sol.stat = Constants.SOLQ_FIX;
                    for (int i = 0; i < 3; i++) {
                        jrtk.sol.rr[i] = jrtk.rb[i] + jrtk.x[i];
                    }
                    log.debug("AR FIX: ratio={}", jrtk.sol.ratio);
                }
            }
        }

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int f = 0; f < nf; f++) {
                if (jrtk.ssat[i].vsat[f] == 0) continue;
                if (jrtk.ssat[i].lock[f] < 0 || (jrtk.nfix > 0 && jrtk.ssat[i].fix[f] >= 2)) {
                    jrtk.ssat[i].lock[f]++;
                }
            }
        }

        jrtk.epoch++;
        return stat != Constants.SOLQ_NONE ? 1 : 0;
    }

    private static int resolveAmbiguity(JointRtk jrtk, BaselineObs[] blObs, int nf, Nav nav, int globalRefSat) {
        PrcOpt opt = jrtk.opt;
        int nx = jrtk.nx;
        int na = jrtk.na;
        int nAmb = nx - na;
        double thresAR = opt.thresar[0];

        double posvar = 0.0;
        for (int i = 0; i < 3; i++) posvar += jrtk.P[i * nx + i];
        posvar /= 3.0;
        if (posvar > opt.thresar[1]) {
            jrtk.sol.ratio = 0.0f;
            log.debug("AR skip: posvar={} > thresar[1]={}", posvar, opt.thresar[1]);
            return Constants.SOLQ_FLOAT;
        }

        double[] xa = Arrays.copyOf(jrtk.x, nx);
        double[] Pa = new double[nx * nx];
        for (int i = 0; i < nx * nx; i++) Pa[i] = jrtk.P[i];
        double minRatio = Double.POSITIVE_INFINITY;
        int nbTotal = 0;
        // [FIX-ALG-042 P2.5] holdamb 约束记录：(refIdx, jIdx, 固定双差整数差 bias)
        java.util.List<int[]> holdPairs = new java.util.ArrayList<>();
        java.util.List<Double> holdBiases = new java.util.ArrayList<>();

        for (int b = 0; b < jrtk.nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;
            // [§13.9 Phase B] 已显著降权的基线不参与 AR：其模糊度方差已被 R 膨胀到无法固定的
            // 程度，纳入 AR 会因 ratio 取最小值而拖垮其余健康基线（见 AR_SKIP_WEIGHT）。
            if (jrtk.monitor != null && jrtk.jointConfig != null
                    && jrtk.jointConfig.baseStabilityDownWeight
                    && jrtk.monitor.getWeight(b) >= AR_SKIP_WEIGHT) {
                log.debug("AR skip baseline[{}]: weight={} >= {}", b,
                        String.format("%.1f", jrtk.monitor.getWeight(b)), AR_SKIP_WEIGHT);
                continue;
            }

            int[] ix = new int[bo.ns * nf * 2];
            int nb = 0;

            for (int f = 0; f < nf; f++) {
                int refI = -1;
                double maxEl = -1.0;
                for (int j = 0; j < bo.ns; j++) {
                    int sat = bo.sat[j];
                    int idx = jrtk.IB(b, sat, f);
                    if (idx < 0 || idx >= nx) continue;
                    if (xa[idx] == 0.0) continue;
                    if (jrtk.ssat[sat - 1].vsat[f] == 0) continue;
                    if (jrtk.ssat[sat - 1].lock[f] < 0) continue;
                    if ((jrtk.ssatOf(b)[sat - 1].slip[f] & Constants.LLI_HALFC) != 0) continue;
                    if (jrtk.ssat[sat - 1].azel[1] < opt.elmaskar) continue;
                    if (jrtk.ssat[sat - 1].azel[1] > maxEl) {
                        maxEl = jrtk.ssat[sat - 1].azel[1];
                        refI = idx;
                    }
                }
                if (refI < 0) continue;

                for (int j = 0; j < bo.ns; j++) {
                    int sat = bo.sat[j];
                    int idx = jrtk.IB(b, sat, f);
                    if (idx < 0 || idx >= nx) continue;
                    if (idx == refI) continue;
                    if (xa[idx] == 0.0) continue;
                    if (jrtk.ssat[sat - 1].vsat[f] == 0) continue;
                    if (jrtk.ssat[sat - 1].lock[f] < 0) continue;
                    if ((jrtk.ssatOf(b)[sat - 1].slip[f] & Constants.LLI_HALFC) != 0) continue;
                    if (jrtk.ssat[sat - 1].azel[1] < opt.elmaskar) continue;
                    ix[nb * 2] = refI;
                    ix[nb * 2 + 1] = idx;
                    nb++;
                }
            }

            if (nb < 2) {
                minRatio = 0.0;
                continue;
            }

            double[] y = new double[nb];
            for (int i = 0; i < nb; i++) {
                y[i] = xa[ix[i * 2]] - xa[ix[i * 2 + 1]];
            }

            SimpleMatrix QbMat = new SimpleMatrix(nb, nb);
            for (int i = 0; i < nb; i++) {
                for (int j = 0; j < nb; j++) {
                    int ri = ix[i * 2], rj = ix[j * 2];
                    int si = ix[i * 2 + 1], sj = ix[j * 2 + 1];
                    QbMat.set(i, j, Pa[ri * nx + rj] - Pa[ri * nx + sj] - Pa[si * nx + rj] + Pa[si * nx + sj]);
                }
            }

            SimpleMatrix QabMat = new SimpleMatrix(na, nb);
            for (int i = 0; i < na; i++) {
                for (int j = 0; j < nb; j++) {
                    int rj = ix[j * 2], sj = ix[j * 2 + 1];
                    QabMat.set(i, j, Pa[i * nx + rj] - Pa[i * nx + sj]);
                }
            }

            double[] Qb = new double[nb * nb];
            for (int i = 0; i < nb; i++) {
                for (int j = 0; j < nb; j++) {
                    Qb[i * nb + j] = QbMat.get(i, j);
                }
            }
            for (int i = 0; i < nb; i++) {
                for (int j = i + 1; j < nb; j++) {
                    double avg = 0.5 * (Qb[i * nb + j] + Qb[j * nb + i]);
                    Qb[i * nb + j] = avg;
                    Qb[j * nb + i] = avg;
                }
            }

            if (jrtk.epoch == 80) {
                double maxFrac = 0;
                for (int ii = 0; ii < Math.min(nb, 5); ii++) {
                    double frac = Math.abs(y[ii] - Math.round(y[ii]));
                    maxFrac = Math.max(maxFrac, frac);
                    log.info("[AR-Y] epoch={} bl={} i={} y={}" + " frac={}", jrtk.epoch, b, ii,
                            String.format("%.4f", y[ii]), String.format("%.4f", frac));
                }
                SimpleMatrix QbCheck = MatrixUtil.createMatrix(Qb, nb, nb);
                java.util.Optional<SimpleMatrix> QbInvCheck = MatrixUtil.invertSafe(QbCheck);
                if (QbInvCheck.isPresent()) {
                    double condNum = QbCheck.normF() * QbInvCheck.get().normF();
                    log.info("[AR-QB] epoch={} bl={} nb={} condNum={}", jrtk.epoch, b, nb, String.format("%.1f", condNum));
                }
            }

            double[] Qab = new double[na * nb];
            for (int i = 0; i < na; i++) {
                for (int j = 0; j < nb; j++) {
                    Qab[i * nb + j] = QabMat.get(i, j);
                }
            }

            double[] F = new double[nb * 2];
            double[] s = new double[2];
            int info = Lambda.lambda(nb, 2, y, Qb, F, s);

            if (info != 0 || s[0] <= 0.0) {
                minRatio = 0.0;
                if (jrtk.epoch % 50 == 0) {
                    log.debug("AR baseline[{}]: LAMBDA failed info={} s0={} nb={}", b, info, s[0], nb);
                }
                continue;
            }

            double ratio = s[1] / s[0];
            if (ratio < minRatio) minRatio = ratio;

            if (jrtk.epoch % 20 == 0 || ratio >= thresAR) {
                double maxVar = 0, minVar = Double.MAX_VALUE;
                for (int ii = 0; ii < nb; ii++) { maxVar = Math.max(maxVar, Qb[ii*nb+ii]); minVar = Math.min(minVar, Qb[ii*nb+ii]); }
                log.info("[AR-RESULT] epoch={} bl={} nb={} ratio={} thres={} maxVar={} minVar={}", jrtk.epoch, b, nb, String.format("%.4f", ratio), thresAR, String.format("%.6f", maxVar), String.format("%.6f", minVar));
            }

            if (ratio >= thresAR) {
                double[] bias = new double[nb];
                double[] db = new double[nb];
                for (int i = 0; i < nb; i++) {
                    bias[i] = F[i * 2];
                    db[i] = y[i] - bias[i];
                }

                SimpleMatrix QbInvMat = MatrixUtil.createMatrix(Qb, nb, nb);
                java.util.Optional<SimpleMatrix> QbInvOpt = MatrixUtil.invertSafe(QbInvMat);
                if (!QbInvOpt.isPresent()) {
                    minRatio = 0.0;
                    continue;
                }
                SimpleMatrix QbInv = QbInvOpt.get();

                SimpleMatrix dbMat = MatrixUtil.createMatrix(db, nb, 1);
                SimpleMatrix QabMatDense = MatrixUtil.createMatrix(Qab, na, nb);

                SimpleMatrix dxa = MatrixUtil.multiply(QabMatDense, MatrixUtil.multiply(QbInv, dbMat));
                for (int i = 0; i < na; i++) xa[i] -= dxa.get(i, 0);

                SimpleMatrix QQ = MatrixUtil.multiply(QabMatDense, QbInv);
                SimpleMatrix dPa = MatrixUtil.multiply(QQ, MatrixUtil.transpose(QabMatDense));
                for (int i = 0; i < na; i++) {
                    for (int j = 0; j < na; j++) {
                        Pa[i * nx + j] -= dPa.get(i, j);
                    }
                }

                for (int i = 0; i < nb; i++) {
                    int refIdx = ix[i * 2];
                    int jIdx = ix[i * 2 + 1];
                    xa[refIdx] = jrtk.x[refIdx];
                    xa[jIdx] = xa[refIdx] - bias[i];
                    holdPairs.add(new int[]{refIdx, jIdx});
                    holdBiases.add(bias[i]);
                }
                nbTotal += nb;
            }
        }

        jrtk.sol.ratio = (float) minRatio;
        if (minRatio >= thresAR) {
            // [FIX-ALG-042 P2.5] FIX 后必须正确压缩小度协方差：resolveAmbiguity
            // 修正了位置 xa[0:na] 和 Pa[0:na,0:na]，但模糊度 xa[na:nx] 只被硬改了值
            // （L632-639），Pa 中模糊度部分完全未修正。若不此处手动把模糊度 P
            // 对角压到极小、交叉协方差清零，模糊度在后续 KF 中自由漂移，epoch=21
            // 起 ratio 骤退化为 ~1，再也无法 FIX。
            for (int i = na; i < nx; i++) {
                Pa[i * nx + i] = 1E-6;
                for (int j = 0; j < i; j++) {
                    Pa[i * nx + j] = 0.0;
                    Pa[j * nx + i] = 0.0;
                }
            }
            System.arraycopy(xa, 0, jrtk.x, 0, nx);
            for (int i = 0; i < nx * nx; i++) jrtk.P[i] = Pa[i];
            jrtk.nfix++;

            // [FIX-ALG-042 P2.5] holdamb（对齐 C 版 FIXHOLD）：把固定双差整数差作为强约束
            // 观测喂回滤波器，收缩模糊度方差（R=varholdamb=0.1 周^2，与 RtkCore 对齐）。
            // 缺失此步时模糊度协方差保持浮点水平，后续历元自由漂移，ratio 退化为 ~1。
            // 注意：不设 minfix 门槛——每次 FIX 成功即执行，与 RtkCore.holdamb 行为一致。
            if (opt.modear == Constants.ARMODE_FIXHOLD && !holdPairs.isEmpty()) {
                int nHold = holdPairs.size();
                if (nHold >= opt.minholdsats) {
                    double[] v = new double[nHold];
                    double[] H = new double[nHold * nx];
                    double[] Rh = new double[nHold * nHold];
                    for (int i = 0; i < nHold; i++) {
                        int[] pair = holdPairs.get(i);
                        H[i * nx + pair[0]] = 1.0;
                        H[i * nx + pair[1]] = -1.0;
                        v[i] = holdBiases.get(i) - (jrtk.x[pair[0]] - jrtk.x[pair[1]]);
                        Rh[i * nHold + i] = 1E-6;
                    }
                    int infoHold = KalmanFilter.update(jrtk.x, jrtk.P, H, v, Rh, nx, nHold);
                    if (infoHold != 0) {
                        log.debug("holdamb filter failed info={} nHold={}", infoHold, nHold);
                    }
                }
            }

            for (int b = 0; b < jrtk.nBaseline; b++) {
                if (blObs[b] == null) continue;
                for (int i = 0; i < blObs[b].ns; i++) {
                    for (int f = 0; f < nf; f++) {
                        int idx = jrtk.IB(b, blObs[b].sat[i], f);
                        if (idx >= 0 && idx < nx) jrtk.ssat[blObs[b].sat[i] - 1].fix[f] = 2;
                    }
                }
            }
            return Constants.SOLQ_FIX;
        }
        return Constants.SOLQ_FLOAT;
    }

    /**
     * [FIX-ALG-042 P5] 周跳检测：逐基线调用 core 的 detslpDop/detslpCode/detslpLl/detslpGf。
     *
     * <p>此前 joint 从未调用这四个函数，{@code ssat.slip} 只读不写，周跳永不检出。
     * 调用顺序对齐 {@code RtkCore.udbias}：Dop → Code → Ll → Gf。</p>
     *
     * <p>状态隔离：检测在 {@code jrtk.ssatBl[b]} 上进行而非全局 {@code jrtk.ssat}。
     * 全局状态只有一个 base 槽位，k 条基线会互相覆盖，使基站 B 的周跳误重置基站 A 的模糊度。
     * 各基线数组只喂入「同一份 rover 观测 + 本站基站观测」，rover 侧状态天然一致，
     * 唯一差异即需要隔离的 base 侧状态。检测后把各基线的周跳标志合并回全局
     * {@code jrtk.ssat}，供输出与诊断使用（判定逻辑一律读 per-baseline 状态）。</p>
     */
    private static void detectCycleSlip(JointRtk jrtk, Obsd[] roverObs, int roverN,
                                        BaseStation[] bases, int nBaseline, int nf,
                                        BaselineObs[] blObs, Nav nav, PrcOpt opt) {
        if (jrtk.jointConfig == null || !jrtk.jointConfig.jointCycleSlip) return;
        jrtk.ensureBaselineSsat(nBaseline);

        RtkState base = jrtk.toState();
        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;
            BaseStation bs = bases[b];
            if (bs == null || bs.obsBuf == null || bs.obsCount <= 0) continue;

            int nu = bo.nu;
            int nr = bo.nr;
            Obsd[] merged = new Obsd[nu + nr];
            System.arraycopy(roverObs, 0, merged, 0, nu);
            System.arraycopy(bs.obsBuf, 0, merged, nu, nr);

            Ssat[] sb = jrtk.ssatOf(b);
            RtkState st = new RtkState(opt, sb, base.rtkConfig(), base.snrMedian(),
                    base.sol(), base.x(), base.tt());

            for (int i = 0; i < bo.ns; i++) {
                int s = bo.sat[i] - 1;
                for (int f = 0; f < nf; f++) sb[s].slip[f] &= 0xFC;
            }

            RtkCore.detslpDop(st, merged, bo.iu, bo.ns, 1, nav);
            RtkCore.detslpDop(st, merged, bo.ir, bo.ns, 2, nav);

            for (int i = 0; i < bo.ns; i++) {
                RtkCore.detslpCode(st, merged, bo.iu[i], 1);
                RtkCore.detslpCode(st, merged, bo.ir[i], 2);
                RtkCore.detslpLl(st, merged, bo.iu[i], 1);
                RtkCore.detslpLl(st, merged, bo.ir[i], 2);
                RtkCore.detslpGf(st, merged, bo.iu[i], bo.ir[i], nav);

                for (int f = 0; f < nf; f++) {
                    sb[bo.sat[i] - 1].half[f] =
                            !((merged[bo.iu[i]].LLI[f] & Constants.LLI_HALFC) != 0
                                    || (merged[bo.ir[i]].LLI[f] & Constants.LLI_HALFC) != 0) ? 1 : 0;
                }
            }

            for (int i = 0; i < nu + nr; i++) {
                int s = merged[i].sat - 1;
                if (s < 0 || s >= Constants.MAXSAT) continue;
                int slot = (i < nu) ? 0 : 1;
                for (int f = 0; f < nf; f++) {
                    if (merged[i].L[f] == 0.0) continue;
                    sb[s].pt[slot][f] = merged[i].time;
                    sb[s].ph[slot][f] = merged[i].L[f];
                }
            }
        }

        for (int s = 0; s < Constants.MAXSAT; s++) {
            for (int f = 0; f < Constants.NFREQ; f++) {
                jrtk.ssat[s].slip[f] = 0;
            }
        }
        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;
            Ssat[] sb = jrtk.ssatOf(b);
            for (int i = 0; i < bo.ns; i++) {
                int s = bo.sat[i] - 1;
                for (int f = 0; f < Constants.NFREQ; f++) {
                    jrtk.ssat[s].slip[f] |= sb[s].slip[f] & Constants.LLI_SLIP;
                }
            }
        }
    }

    private static void udstate(JointRtk jrtk, Obsd[] roverObs, int roverN,
                                BaseStation[] bases, int nBaseline, Nav nav, BaselineObs[] blObs,
                                BaselineGroup group) {
        PrcOpt opt = jrtk.opt;
        int nx = jrtk.nx;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        double tt = Math.abs(jrtk.tt);
        if (tt == 0.0) tt = 1.0;

        if (opt.dynamics != 0) {
            double F[] = new double[nx * nx];
            for (int i = 0; i < nx; i++) F[i * nx + i] = 1.0;
            for (int i = 0; i < 3; i++) F[i * nx + (i + 3)] = tt;
            double Q[] = new double[nx * nx];
            for (int i = 0; i < 3; i++) Q[i * nx + i] = RtkCore.SQR(elmaska(jrtk, blObs, i)) * tt;
            for (int i = 3; i < 6; i++) Q[i * nx + i] = RtkCore.SQR(opt.thresar[1]) * tt;
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < nx; j++) {
                    double FiP = 0.0;
                    for (int k = 0; k < nx; k++) FiP += F[i * nx + k] * jrtk.P[k * nx + j];
                    double FiPFt = 0.0;
                    for (int k = 0; k < nx; k++) FiPFt += FiP * F[j * nx + k];
                    jrtk.P[i * nx + j] = FiPFt + Q[i * nx + j];
                }
            }
        }

        udatm(jrtk, blObs, nBaseline, tt, bases);

        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;

            Ssat[] sb = jrtk.ssatOf(b);
            for (int i = 0; i < bo.ns; i++) {
                int sat = bo.sat[i];
                for (int f = 0; f < nf; f++) {
                    int idx = jrtk.IB(b, sat, f);
                    if (idx < 0 || idx >= nx) continue;

                    // [FIX-ALG-042 P3] 模糊度过程噪声（对齐 RtkCore.udbias）
                    jrtk.P[idx * nx + idx] += RtkCore.SQR(opt.prn[0]) * tt;

                    // 周跳 → 状态归零，等待载波-伪距组合重新初始化（对齐 C 版 udbias）
                    // 读本基线状态，避免其他基站的周跳误重置本基线模糊度（P5 状态隔离）
                    if (sb[sat - 1].slip[f] != 0 && jrtk.x[idx] != 0.0) {
                        jrtk.x[idx] = 0.0;
                        jrtk.P[idx * nx + idx] = 0.0;
                        jrtk.ssat[sat - 1].lock[f] = -opt.minlock;
                    }

                    // [FIX-ALG-042 P2] 浮点模糊度初值：载波-伪距组合。
                    // 必须非零：KalmanFilter.update 对 x==0 的状态跳过更新（if x[i]!=0.0），
                    // 初始化为 0 则模糊度方差恒为 std[0]^2 不收敛，LAMBDA 无法固定。
                    if (jrtk.x[idx] == 0.0) {
                        double bias = carrierBiasInit(bo, i, f, opt);
                        if (bias != 0.0) {
                            JointDdRes.initx(jrtk.x, jrtk.P, nx, bias, RtkCore.SQR(opt.std[0]), idx);
                            jrtk.ssat[sat - 1].lock[f] = -opt.minlock;
                        }
                    }
                }
            }
        }
    }

    /**
     * [§2.1.1 / §0.2] 大气状态（电离层 + 对流层）过程噪声与初值。
     *
     * <p>对齐 {@code RtkCore.udion/udtrop}。此前 joint 只给模糊度加 {@code prn[0]}，
     * 大气维度虽存在但 P 恒为 0，状态不吸收任何误差——配 {@code ionoopt=EST} /
     * {@code tropopt=EST} 等于没配。此处按「初值 std[1]/std[2] + 过程噪声 prn[1]/prn[2]」
     * 接线，并按共享布局遍历：电离层按块（共享时仅 1 块），对流层 rover 侧按块、
     * 基站侧按基站各一份。</p>
     */
    private static void udatm(JointRtk jrtk, BaselineObs[] blObs, int nBaseline, double tt,
                              BaseStation[] bases) {
        PrcOpt opt = jrtk.opt;
        if (jrtk.NIBlock() == 0 && jrtk.NTPerR() == 0) return;

        int nx = jrtk.nx;
        RtkState state = jrtk.toState();
        RtkConfig cfg = state.rtkConfig();

        boolean[] vis = new boolean[Constants.MAXSAT];
        boolean[] visBase = new boolean[Constants.MAXSAT];
        double[] elBySat = new double[Constants.MAXSAT];
        int ns = 0;
        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;
            ns = Math.max(ns, bo.ns);
            for (int j = 0; j < bo.ns; j++) {
                int sat = bo.sat[j];
                if (sat < 1 || sat > Constants.MAXSAT) continue;
                if (!vis[sat - 1]) {
                    vis[sat - 1] = true;
                    elBySat[sat - 1] = bo.azel[1 + bo.iu[j] * 2];
                }
            }
            BaseStation bs = (bases != null && b < bases.length) ? bases[b] : null;
            if (bs != null && bs.obsBuf != null) {
                for (int j = 0; j < bs.obsCount; j++) {
                    int sat = bs.obsBuf[j].sat;
                    if (sat >= 1 && sat <= Constants.MAXSAT) visBase[sat - 1] = true;
                }
            }
        }

        boolean frozen = cfg.common.atmFrozenNsThresh > 0 && ns < cfg.common.atmFrozenNsThresh;
        if (frozen) return;

        double bl = meanBaselineLength(jrtk, nBaseline, bases);

        // ---------- 电离层 ----------
        int niBlock = jrtk.NIBlock();
        if (niBlock > 0) {
            int[] outc = jrtk.atmOutc();
            int[] outcBase = jrtk.atmOutcBase();
            for (int s = 0; s < Constants.MAXSAT; s++) {
                if (vis[s]) {
                    outc[s] = 0;
                } else if (outc[s] < Integer.MAX_VALUE) {
                    outc[s]++;
                }
                if (visBase[s]) {
                    outcBase[s] = 0;
                } else if (outcBase[s] < Integer.MAX_VALUE) {
                    outcBase[s]++;
                }
            }

            int nBlocks = jrtk.NIonoBlocks();
            int niPerSat = jrtk.NIPerSat();
            double gradInitVar = cfg.rtk.gradientIonoInitVar;
            double gradPrn = cfg.rtk.gradientIonoPrn;
            double initVar = RtkCore.SQR(opt.std[1] * bl / 1E4);
            double prnCoef = opt.prn[1] * bl / 1E4;

            for (int blk = 0; blk < nBlocks; blk++) {
                for (int s = 0; s < Constants.MAXSAT; s++) {
                    int j = jrtk.II(blk, s + 1);
                    if (j < 0 || j >= nx) continue;

                    // 长期失锁（rover 与基站侧都连续不可见）后重置，避免旧电离层实现被新弧段沿用
                    if (jrtk.x[j] != 0.0 && outc[s] > Constants.GAP_RESION
                            && outcBase[s] > Constants.GAP_RESION) {
                        jrtk.x[j] = 0.0;
                        if (niPerSat == 3) {
                            jrtk.x[j + 1] = 0.0;
                            jrtk.x[j + 2] = 0.0;
                        }
                    }

                    if (!vis[s]) continue;

                    if (jrtk.x[j] == 0.0) {
                        JointDdRes.initx(jrtk.x, jrtk.P, nx, 1E-6, initVar, j);
                        if (niPerSat == 3) {
                            JointDdRes.initx(jrtk.x, jrtk.P, nx, 0.0, gradInitVar, j + 1);
                            JointDdRes.initx(jrtk.x, jrtk.P, nx, 0.0, gradInitVar, j + 2);
                        }
                    } else if (!cfg.common.enableParamTypeNoise) {
                        double fact = Math.cos(elBySat[s]);
                        jrtk.P[j * nx + j] += RtkCore.SQR(prnCoef * fact) * tt;
                        if (niPerSat == 3) {
                            jrtk.P[(j + 1) * nx + (j + 1)] += RtkCore.SQR(gradPrn) * tt;
                            jrtk.P[(j + 2) * nx + (j + 2)] += RtkCore.SQR(gradPrn) * tt;
                        }
                    }
                }
            }
        }

        // ---------- 对流层 ----------
        int ntPerR = jrtk.NTPerR();
        if (ntPerR > 0) {
            boolean applyNoise = !cfg.common.enableParamTypeNoise;
            for (int blk = 0; blk < jrtk.NTropRoverBlocks(); blk++) {
                updTropState(jrtk, jrtk.ITrover(blk, 0), ntPerR, tt, nx, applyNoise);
            }
            for (int b = 0; b < nBaseline; b++) {
                updTropState(jrtk, jrtk.ITbase(b, 0), ntPerR, tt, nx, applyNoise);
            }
        }
    }

    /** 单个对流层块（1 维 ZWD，或 3 维 ZWD+北+东梯度）的初值与过程噪声。 */
    private static void updTropState(JointRtk jrtk, int j, int ntPerR, double tt, int nx, boolean applyNoise) {
        if (j < 0 || j >= nx) return;
        PrcOpt opt = jrtk.opt;
        if (jrtk.x[j] == 0.0) {
            JointDdRes.initx(jrtk.x, jrtk.P, nx, Constants.INIT_ZWD, RtkCore.SQR(opt.std[2]), j);
            if (ntPerR >= 3) {
                JointDdRes.initx(jrtk.x, jrtk.P, nx, 1E-6, Constants.VAR_GRA, j + 1);
                JointDdRes.initx(jrtk.x, jrtk.P, nx, 1E-6, Constants.VAR_GRA, j + 2);
            }
        } else if (applyNoise) {
            jrtk.P[j * nx + j] += RtkCore.SQR(opt.prn[2]) * tt;
            if (ntPerR >= 3) {
                jrtk.P[(j + 1) * nx + (j + 1)] += RtkCore.SQR(opt.prn[2] * 0.3) * tt;
                jrtk.P[(j + 2) * nx + (j + 2)] += RtkCore.SQR(opt.prn[2] * 0.3) * tt;
            }
        }
    }

    /** 各基线长度的均值（m）：共享电离层的过程噪声/初值按此标度（对齐 RtkCore 的 bl 语义）。 */
    private static double meanBaselineLength(JointRtk jrtk, int nBaseline, BaseStation[] bases) {
        if (jrtk.rb == null || jrtk.x == null || jrtk.x.length < 3 || bases == null) return 0.0;
        double[] rr = new double[]{jrtk.rb[0] + jrtk.x[0], jrtk.rb[1] + jrtk.x[1], jrtk.rb[2] + jrtk.x[2]};
        double sum = 0.0;
        int cnt = 0;
        for (int b = 0; b < nBaseline && b < bases.length; b++) {
            if (bases[b] == null || bases[b].rb == null) continue;
            double d2 = 0.0;
            for (int i = 0; i < 3; i++) {
                double d = rr[i] - bases[b].rb[i];
                d2 += d * d;
            }
            sum += Math.sqrt(d2);
            cnt++;
        }
        return cnt > 0 ? sum / cnt : 0.0;
    }

    /**
     * [FIX-ALG-042 P2] 载波-伪距组合浮点单差模糊度初值（单位：周；IFLC 时为米）。
     * 对齐 RtkCore.udbias 的 bias 计算：zdres 输出的 y 已扣除迭代点几何距离，
     * 故 SD(载波) - SD(伪距) ≈ N_sd + 伪距噪声/多路径残差，非零且偏差由 Kalman 吸收。
     */
    private static double carrierBiasInit(BaselineObs bo, int i, int f, PrcOpt opt) {
        int iu = bo.iu[i];
        int ir = bo.ir[i];
        int ph = f + iu * opt.nf * 2;
        int phb = f + ir * opt.nf * 2;
        int cp = f + opt.nf + iu * opt.nf * 2;
        int cpb = f + opt.nf + ir * opt.nf * 2;
        if (ph >= bo.y.length || phb >= bo.y.length || cp >= bo.y.length || cpb >= bo.y.length) {
            return 0.0;
        }
        if (bo.y[ph] == 0.0 || bo.y[phb] == 0.0 || bo.y[cp] == 0.0 || bo.y[cpb] == 0.0) {
            return 0.0;
        }
        double sdPhase = bo.y[ph] - bo.y[phb];
        double sdCode = bo.y[cp] - bo.y[cpb];
        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            return sdPhase - sdCode; // IF 模糊度状态单位为米（ddres 中 v -= x[ii]-x[jj]）
        }
        double freq = bo.freq[f + iu * opt.nf];
        if (freq <= 0.0) return 0.0;
        return (sdPhase - sdCode) * freq / Constants.CLIGHT;
    }

    private static double elmaska(JointRtk jrtk, BaselineObs[] blObs, int posIdx) {
        double minEl = Math.PI / 2.0;
        for (int b = 0; b < jrtk.nBaseline; b++) {
            if (blObs[b] == null) continue;
            for (int i = 0; i < blObs[b].ns; i++) {
                double el = blObs[b].azel[(blObs[b].iu[i]) * 2 + 1];
                if (el > 0 && el < minEl) minEl = el;
            }
        }
        return minEl;
    }
}