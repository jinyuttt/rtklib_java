package org.rtklib.java.rtkpos;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixQualityContext;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.SatFeature;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.config.AiOpts;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.ionosphere.SbasCorrection;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.ppp.PppCoreEx;
import org.rtklib.java.ppprtk.PppRtkCore;
import org.rtklib.java.rtkpos.Tides;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.trace.*;
import org.rtklib.java.troposphere.TroposphereModel;
import org.rtklib.java.common.MatrixUtil;
import org.ejml.simple.SimpleMatrix;

import java.util.Arrays;

public final class RtkCore {
    private RtkCore() {
    }

    private static final int MAXITR = 8;
    private static final double STD_PREC_VAR_THRESH = 0;
    private static final double TTOL_MOVEB = 1.05;
    private static final int MIN_ND = 4;
    private static final double RNX2CLK = 299792458.0;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RtkCore.class);

    public static int rtkpos(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        PrcOpt opt = rtk.opt;
        Sol solb = new Sol();
        int i, nu = 0, nr = 0;
        String[] msg = new String[1];
        double[] azel = new double[n * 2];

        if (opt.rb != null && Math.abs(rtk.rb[0]) < 0.1 && Math.abs(rtk.rb[1]) < 0.1 && Math.abs(rtk.rb[2]) < 0.1) {
            for (i = 0; i < 3; i++) rtk.rb[i] = opt.rb[i];
        }

        /* aligned with C rtkpos(): obs is const, never re-ordered/compacted.
         * Slot f of obsd must always hold the observation of frequency index f
         * (assigned by RINEX decoder code2idx, C rinex.c set_index()).
         * Compacting per-sat gaps would move e.g. B2a into slot-1 while the
         * other receiver keeps B2b there, mixing frequencies in IFLC/DD. */

        for (nu = 0; nu < n && obs[nu].rcv == 1; nu++) ;
        for (nr = 0; nu + nr < n && obs[nu + nr].rcv == 2; nr++) ;

        GTime prevTime = new GTime();
        prevTime.time = rtk.sol.time.time;
        prevTime.sec = rtk.sol.time.sec;

        if (rtk.P[0] == 0 || rtk.P[0] > STD_PREC_VAR_THRESH) {
            PrcOpt sppInitOpt = new PrcOpt(opt);
            sppInitOpt.mode = Constants.PMODE_SINGLE;
            sppInitOpt.ionoopt = Constants.IONOOPT_BRDC;
            sppInitOpt.tropopt = Constants.TROPOPT_SAAS;
            if (PntPos.pntpos(obs, nu, nav, sppInitOpt, rtk.sol, null, rtk.ssat) == 0) {
                /* aligned with C rtkpos.c L2364-2371: SPP failure does not invalidate
                 * the epoch. In dynamics mode the filter propagates its state (udpos)
                 * and relpos proceeds with rtk.x as linearization point. */
                LOG.info("rtkpos: point pos error (SPP failed, P[0]={}, nu={}, nr={})", rtk.P[0], nu, nr);
                if (opt.dynamics == 0) {
                    return 0;
                }
                /* dynamics=1: continue with propagated filter state */
            }
        } else {
            rtk.sol.time = obs[0].time;
        }

        if (prevTime.time != 0) {
            rtk.tt = TimeSystem.timediff(rtk.sol.time, prevTime);
        }

        if (opt.mode == Constants.PMODE_SINGLE) {
            return 1;
        }

        if (opt.outsingle == 0) {
            rtk.sol.stat = Constants.SOLQ_NONE;
        }

        if (opt.mode >= Constants.PMODE_PPP_KINEMA) {
            RtkConfig cfg = rtk.rtkConfig;
            /* PPP-RTK (SSR) dispatch first, mirroring PppProcessor L816-825:
             * enablePppRtk takes precedence over the PppCoreEx (AR/bias) switch
             * group. Without SSR data present in nav.ssr this degrades to
             * standard PPP via PppRtkCore internally. */
            if (cfg != null && cfg.pppRtk.enable) {
                PppRtkCore.ppprtkos(rtk, obs, nu, nav);
                return 1;
            }
            boolean useEx = cfg != null && (cfg.pppAr.enableAR || cfg.pppAr.enablePartialAR || cfg.pppAr.enableFixHold
                    || cfg.pppAr.enableBds3AR || cfg.pppAr.enableMultiFreqAR || cfg.ppp.enableIsbIfcbIfb
                    || cfg.ppp.enableOsb || cfg.ppp.enableAt1S2 || cfg.ppp.useGpt3Grid);
            if (useEx) {
                PppCoreEx.ppos(rtk, obs, nu, nav, cfg);
            } else {
                PppCore.pppos(rtk, obs, nu, nav);
            }
            return 1;
        }

        if (nr == 0) {
            return 1;
        }

        if (opt.mode == Constants.PMODE_MOVEB) {
            if (rtk.P[0] == 0 || rtk.P[0] > STD_PREC_VAR_THRESH) {
                if (PntPos.pntpos(Arrays.copyOfRange(obs, nu, nu + nr), nr, nav, opt, solb, null, null) == 0) {
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

        /* aligned with C rtkpos.c L2442-2446: relpos return value is ignored,
         * epoch always outputs (degraded sol.stat on failure) */
        relpos(rtk, obs, nu, nr, nav);
        rtk.epoch++;
        return 1;
    }

    private static int relpos(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav) {
        PrcOpt opt = rtk.opt;
        /* [Java扩展] 每历元先清空上一历元的 qPos，避免早期 return 时残留旧值。
         * 注意：rtk.sol 在 reset() 中只 new 一次，之后逐历元复用同一实例。 */
        rtk.sol.qPos = null;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int i, j, f, ns, nv = 0;
        int n = nu + nr;

        if (n > Constants.MAXOBS) {
            if (nu > Constants.MAXOBS) {
                nu = Constants.MAXOBS;
                nr = 0;
            } else {
                nr = Constants.MAXOBS - nu;
            }
            n = nu + nr;
        }
        TraceControl v1ctrl = rtk.traceControl;
        TraceCallback v1cb = rtk.traceCallback;
        TraceConfig v2cfg = rtk.traceConfig;
        TraceCallback v2cb = rtk.traceCallback;

        if (rtk.rtkConfig.rtk.enableIonoTropGradient && !opt.ionoGradient) {
            opt.ionoGradient = true;
        }

        for (i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i].sys = SatUtils.satsys(i + 1, null);
            for (j = 0; j < Constants.NFREQ; j++) {
                rtk.ssat[i].vsat[j] = 0;
                rtk.ssat[i].snrRover[j] = 0;
                rtk.ssat[i].snrBase[j] = 0;
            }
        }

        RtkWorkBuffer wb = (RtkWorkBuffer) rtk.workBufRtk;
        if (wb == null) {
            wb = new RtkWorkBuffer();
            rtk.workBufRtk = wb;
        }
        wb.ensureInit();

        double[] rs = wb.rs;
        double[] dts = wb.dts;
        double[] vare = wb.vare;
        int[] svh = wb.svh;
        double[] azel = wb.azel;
        double[] y = wb.y;
        double[] e = wb.e;
        double[] freq = wb.freq;

        /* C rtkpos.c relpos(): azel=zeros(2,n), freq=zeros(nf,n) — both
         * zero-initialized each call. Work buffer reuse leaves stale values
         * from the previous epoch; if a satellite is skipped by zdres (r<=0,
         * el<elmin, satexclude), its azel/freq slot retains the old value,
         * which can fool selsat (azel[1+j*2]>=elmin) or ddres. */
        Arrays.fill(azel, 0, n * 2, 0.0);
        Arrays.fill(freq, 0, n * nf, 0.0);

        EphModel.satposs(obs[0].time, obs, n, nav, rs, dts, vare, svh, opt.sateph);

        RtkTrace.traceStage1(v1ctrl, v1cb, rtk.epoch, obs[0].time, null, 0, nf,
                rs, dts, nav, rtk.rb, null);

        // [JAVA-ONLY] IFLC频率对逐卫星协商（需先于zdres(base)，协商结果供两站zdres/udbias共同使用）
        negotiateIflcPairs(rtk.toState(), obs, nu, nr);

        if (!zdres(rtk.toState(), 1, obs, nu, nr, rs, dts, vare, svh, nav, rtk.rb, opt,
                y, e, azel, freq, rtk.epoch)) {
            LOG.info("relpos: zdres(base) failed, epoch={}", rtk.epoch);
            return 0;
        }

        double dt = TimeSystem.timediff(obs[0].time, obs[nu].time);
        rtk.sol.age = (float) dt;
        if (Math.abs(rtk.sol.age) > opt.maxtdiff) {
            return 1;
        }

        int[] sat = wb.sat;
        int[] iu = wb.iu;
        int[] ir = wb.ir;
        ns = selsat(obs, azel, nu, nr, opt, sat, iu, ir);
        if (ns <= 0) {
            LOG.info("relpos: selsat returned 0, epoch={}", rtk.epoch);
            return 0;
        }

        /* [AI-3] 接口三门禁（§4.3.7）：IFLC 时 nf 已被归一为 1，y 中是消电离层组合量，
         * 逐频点偏差的物理含义不成立，故 core 侧直接不调用，等价于未注入。 */
        ObsCorrector aiCorrector = (opt.ionoopt != Constants.IONOOPT_IFLC)
                ? AiHooks.resolveObsCorrector(rtk) : null;
        double[] aiBias = (aiCorrector != null) ? new double[ns * nf] : null;
        double[] aiScale = (aiCorrector != null) ? new double[ns * nf] : null;
        boolean aiCorrectionDone = false;

        RtkTrace.traceStage0(v1ctrl, v1cb, rtk.epoch, obs, nu, nr, nav, rtk.ssat);
        Trace.emit("SATELLITE", "START", v2cfg, v2cb, rtk.epoch, obs[0].time,
                "rover_ns", nu, "base_ns", nr, "common_ns", ns);

        int nx_new = NR(rtk.toState()) + NB(rtk.toState());
        rtk.na = NR(rtk.toState());
        if (rtk.nx != nx_new) {
            rtk.nx = nx_new;
            rtk.x = new double[nx_new];
            rtk.P = new double[nx_new * nx_new];
            double baseline0 = rtk.sol.rr[0] - rtk.rb[0];
            double baseline1 = rtk.sol.rr[1] - rtk.rb[1];
            double baseline2 = rtk.sol.rr[2] - rtk.rb[2];
            initx(rtk.x, rtk.P, nx_new, baseline0, Constants.VAR_POS, 0);
            initx(rtk.x, rtk.P, nx_new, baseline1, Constants.VAR_POS, 1);
            initx(rtk.x, rtk.P, nx_new, baseline2, Constants.VAR_POS, 2);
            if (rtk.opt.dynamics != 0) {
                for (i = 3; i < 6; i++) initx(rtk.x, rtk.P, nx_new, 0.0, Constants.VAR_VEL, i);
                for (i = 6; i < 9; i++) initx(rtk.x, rtk.P, nx_new, 1E-6, Constants.VAR_ACC, i);
            }
        }
        int nx = rtk.nx;

        for (i = 0; i < ns; i++) {
            for (f = 0; f < nf; f++) {
                rtk.ssat[sat[i] - 1].snrRover[f] = obs[iu[i]].SNR[f];
                rtk.ssat[sat[i] - 1].snrBase[f] = obs[ir[i]].SNR[f];
            }
        }

        RtkOptimizations.computeSnrMedian(rtk, obs, nu, nr, sat, ns, nf, nav);

        udstate(rtk, obs, nu, nr, nav, sat, ns, iu, ir);

        RtkTrace.traceStage2(v1ctrl, v1cb, rtk.epoch, obs[0].time, rtk, sat, ns, nf);
        Trace.emit("BASELINE", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                "x", rtk.x[0] + rtk.rb[0],
                "y", rtk.x[1] + rtk.rb[1],
                "z", rtk.x[2] + rtk.rb[2]);
        Trace.emit("AMBIGUITY", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                "ns", ns, "nf", nf, "na", rtk.na, "nx", rtk.nx);

        // [OPT-BDS-BIAS] BDS码偏差改正
        RtkOptimizationsBdsBias.applyBdsCodeBias(rtk, obs, iu, ir, ns, nf, nav);

        // [OPT-PARAM-NOISE] 参数类型级自适应过程噪声
        RtkOptimizations.applyParamTypeNoise(rtk, rtk.P, rtk.nx, rtk.tt);

        wb.ensureState(nx);
        double[] xp = wb.xp;
        double[] Pp = wb.Pp;
        System.arraycopy(rtk.x, 0, xp, 0, nx);
        System.arraycopy(rtk.P, 0, Pp, 0, nx * nx);

        if ((rtk.epoch <= 1 || rtk.epoch == 79) && ns > 0) {
            double posVarB = (Pp[0] + Pp[1*nx+1] + Pp[2*nx+2]) / 3.0;
            int ib0 = IB(sat[0], 0, opt);
            double ambVarB = (ib0 >= 0 && ib0 < nx) ? Pp[ib0*nx+ib0] : 0;
            LOG.info("[STD-KF] epoch={} BEFORE Kalman: posVar={} ambVar={} nx={} ns={} ib0={}",
                    rtk.epoch, String.format("%.6f", posVarB), String.format("%.4f", ambVarB), nx, ns, ib0);
        }

        int ny = ns * nf * 2 + 2;
        wb.ensureObs(nx, ny);
        double[] v = wb.v;
        double[] H = wb.H;
        double[] R = wb.R;
        int[] vflg = wb.vflg;
        double[] xa = wb.xa;
        double[] bias = wb.bias;

        int stat = opt.mode <= Constants.PMODE_DGPS ? Constants.SOLQ_DGPS : Constants.SOLQ_FLOAT;

        /* [Java扩展] DIAG_QPOS：保留最后一次迭代的归约法方程，供 stat 确定后取块 */
        QposSolver.ReducedNormal qposRn = null;


        double[] rr_rover = wb.rr_rover;

        for (i = 0; i < opt.niter; i++) {
            for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
            if (!zdres(rtk.toState(), 0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                    y, e, azel, freq, rtk.epoch)) {
                LOG.info("relpos: zdres(rover) failed, epoch={}, iter={}", rtk.epoch, i);
                stat = Constants.SOLQ_NONE;
                break;
            }
            /* [AI-3] 每历元一次批量推理（§3.3）：必须在迭代首轮 zdres(rover) 之后、
             * 首次 ddres 之前——azel 由 zdres 写入，放在循环前只能拿到上一历元值。
             * 推理结果写入局部数组，ddres 内只读查表，故 ddres 跑 2~3 次也不会重复施加。 */
            if (i == 0 && aiCorrector != null && !aiCorrectionDone) {
                aiCorrectionDone = true;
                aiComputeCorrection(rtk, obs, sat, iu, ns, nf, dt, azel,
                        aiBias, aiScale, aiCorrector, v2cfg, v2cb);
            }
            if ((nv = ddres(rtk, obs, dt, xp, Pp, sat, y, e, azel, freq,
                    iu, ir, ns, nf, nav, v, H, R, vflg, aiBias, aiScale)) < 4) {
                stat = Constants.SOLQ_NONE;
                break;
            }

            /* [Java扩展] DIAG_QPOS：此处 H 与 R 是同一次 ddres 的原始 ddcov 产物，
             * 且尚未被 applyIggiii 改写，二者严格对齐。放到 IGGIII 之后会拿到
             * 块结构被破坏的 R，法方程不再对应真实观测权重。 */
            if ((opt.diagMask & PrcOpt.DIAG_QPOS) != 0) {
                qposRn = QposSolver.build(H, R, nv, nx);
            }

            RtkTrace.traceStage3(v1ctrl, v1cb, rtk.epoch, obs[0].time,
                    sat[0], sat, ns, nf, v, nv, R, H, nx, opt);

            RtkOptimizations.computeQScale(rtk, sat, ns);

            RtkOptimizations.applyIggiii(rtk, v, H, R, vflg, nv, nx, sat, ns,
                    obs, iu, azel, nf, Pp);

            if (rtk.epoch <= 2 && i == 0 && nv > 0) {
                System.out.printf("[DECISIVE-STD] epoch=%d nx=%d nv=%d ns=%d%n", rtk.epoch, nx, nv, ns);
                System.out.printf("[DECISIVE-STD] epoch=%d NR=%d NB=%d%n", rtk.epoch, NR(rtk.toState()), NB(rtk.toState()));
                System.out.printf("[DECISIVE-STD] epoch=%d sat[0:ns]=%s%n", rtk.epoch, java.util.Arrays.toString(java.util.Arrays.copyOf(sat, ns)));
                System.out.printf("[DECISIVE-STD] epoch=%d xp[0:3]=(%.4f,%.4f,%.4f) rb=(%.4f,%.4f,%.4f)%n", rtk.epoch,
                        xp[0], xp[1], xp[2], rtk.rb[0], rtk.rb[1], rtk.rb[2]);
                if (ns > 0) {
                    int ib0 = IB(sat[0], 0, opt);
                    System.out.printf("[DECISIVE-STD] epoch=%d IB(sat[0],f0)=%d xp[ib0]=%.6f Pp[ib0,ib0]=%.4f%n", rtk.epoch, ib0,
                            ib0 >= 0 ? xp[ib0] : Double.NaN,
                            ib0 >= 0 ? Pp[ib0*nx+ib0] : Double.NaN);
                }
                StringBuilder vsb = new StringBuilder();
                for (int iv = 0; iv < Math.min(nv, 8); iv++) vsb.append(String.format("%.6f ", v[iv]));
                System.out.printf("[DECISIVE-STD] epoch=%d v[0:8]=[%s]%n", rtk.epoch, vsb);
                StringBuilder hsb = new StringBuilder();
                for (int iv = 0; iv < Math.min(nv, 3); iv++) {
                    hsb.append(String.format("row%d:[", iv));
                    for (int k = 0; k < Math.min(nx, 8); k++) hsb.append(String.format("%.6f ", H[iv*nx+k]));
                    hsb.append("] ");
                }
                System.out.printf("[DECISIVE-STD] epoch=%d H first rows: %s%n", rtk.epoch, hsb);
            }
            if (false && rtk.epoch <= 2 && i == 0) {
                LOG.info("[DECISIVE-STD] epoch={} nx={} nv={} ns={}", rtk.epoch, nx, nv, ns);
                LOG.info("[DECISIVE-STD] epoch={} NR={} NB={}", rtk.epoch, NR(rtk.toState()), NB(rtk.toState()));
                LOG.info("[DECISIVE-STD] epoch={} sat[0:ns]={}", rtk.epoch, java.util.Arrays.toString(java.util.Arrays.copyOf(sat, ns)));
                LOG.info("[DECISIVE-STD] epoch={} xp[0:3]=({},{},{}) rb=({},{},{})", rtk.epoch,
                        String.format("%.4f", xp[0]), String.format("%.4f", xp[1]), String.format("%.4f", xp[2]),
                        String.format("%.4f", rtk.rb[0]), String.format("%.4f", rtk.rb[1]), String.format("%.4f", rtk.rb[2]));
                if (ns > 0) {
                    int ib0 = IB(sat[0], 0, opt);
                    LOG.info("[DECISIVE-STD] epoch={} IB(sat[0],f0)={} xp[ib0]={} Pp[ib0,ib0]={}", rtk.epoch, ib0,
                            ib0 >= 0 ? String.format("%.6f", xp[ib0]) : "N/A",
                            ib0 >= 0 ? String.format("%.4f", Pp[ib0*nx+ib0]) : "N/A");
                }
                StringBuilder vsb = new StringBuilder();
                for (int iv = 0; iv < Math.min(nv, 8); iv++) vsb.append(String.format("%.6f ", v[iv]));
                LOG.info("[DECISIVE-STD] epoch={} v[0:8]=[{}]", rtk.epoch, vsb);
                StringBuilder hsb = new StringBuilder();
                for (int iv = 0; iv < Math.min(nv, 3); iv++) {
                    hsb.append(String.format("row%d:[", iv));
                    for (int k = 0; k < Math.min(nx, 8); k++) hsb.append(String.format("%.6f ", H[iv*nx+k]));
                    hsb.append("] ");
                }
                LOG.info("[DECISIVE-STD] epoch={} H first rows: {}", rtk.epoch, hsb);
            }
            if (rtk.epoch == 1) {
                int na_dbg = rtk.na;
                int cnt_dbg = 0;
                for (int kk = na_dbg; kk < nx && cnt_dbg < 15; kk++) {
                    if (Pp[kk * nx + kk] > 0.0) {
                        cnt_dbg++;
                    }
                }
            }

            if (rtk.epoch <= 4 && i == 0 && ns > 0) {
                double posVarB = (Pp[0] + Pp[1*nx+1] + Pp[2*nx+2]) / 3.0;
                int ib0 = IB(sat[0], 0, opt);
                LOG.info("[STD-KF] epoch={} BEFORE Kalman: xp=({},{},{}) posVar={} ambVar={}",
                        rtk.epoch, String.format("%.6f", xp[0]), String.format("%.6f", xp[1]),
                        String.format("%.6f", xp[2]), String.format("%.6f", posVarB),
                        (ib0 >= 0 && ib0 < nx) ? String.format("%.4f", Pp[ib0*nx+ib0]) : "-");
            }

            int info = filter(rtk, xp, Pp, H, v, R, nx, nv);

            if ((rtk.epoch <= 4 || rtk.epoch == 79) && i == 0 && info == 0 && ns > 0) {
                double posVarA = (Pp[0] + Pp[1*nx+1] + Pp[2*nx+2]) / 3.0;
                int ib0 = IB(sat[0], 0, opt);
                double ambVarA = (ib0 >= 0 && ib0 < nx) ? Pp[ib0*nx+ib0] : 0;
                double crossPN = 0;
                if (ib0 >= 0 && ib0 < nx) crossPN = Math.abs(Pp[0*nx+ib0]) + Math.abs(Pp[1*nx+ib0]) + Math.abs(Pp[2*nx+ib0]);
                LOG.info("[STD-KF] epoch={} AFTER Kalman: xp=({},{},{}) posVar={} ambVar={} crossPN={} nx={} nv={} ib0={}",
                        rtk.epoch, String.format("%.6f", xp[0]), String.format("%.6f", xp[1]),
                        String.format("%.6f", xp[2]),
                        String.format("%.6f", posVarA), String.format("%.4f", ambVarA),
                        String.format("%.6f", crossPN), nx, nv, ib0);
            }

            RtkTrace.traceStage4(v1ctrl, v1cb, rtk.epoch, obs[0].time,
                    info, xp, rtk.x, nx, Pp);
            Trace.emit("FILTER", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                    "info", info, "nv", nv, "nx", nx);

            if (info != 0) {
                LOG.info("relpos: filter failed info={}, epoch={}, iter={}", info, rtk.epoch, i);
                stat = Constants.SOLQ_NONE;
                break;
            }

        }

        if (stat != Constants.SOLQ_NONE) {
            for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
            if (zdres(rtk.toState(), 0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                    y, e, azel, freq, rtk.epoch)) {
                nv = ddres(rtk, obs, dt, xp, Pp, sat, y, e, azel, freq,
                        iu, ir, ns, nf, nav, v, null, R, vflg, aiBias, aiScale);

                // [OPT-RES-EDIT] 残差时序周跳检测与伪距-载波一致性校验
                RtkOptimizationsResEdit.checkCycleSlip(rtk, v, R, vflg, nv);
                RtkOptimizationsResEdit.checkPcConsistency(rtk, obs, sat, ns, nf, nav);

                if (valpos(rtk, v, R, vflg, nv, 4.0)) {
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
            /* aligned with C rtkpos.c L2105: zdres failure after filter update leaves
             * stat unchanged (SOLQ_FLOAT) — the propagated float solution is still valid */
        }

        if (stat == Constants.SOLQ_FLOAT) {
            double[] bias_arr = wb.bias_arr;
            double[] xa_arr = wb.xa_arr;
            int nb = manage_amb_LAMBDA(rtk, bias_arr, xa_arr, sat, nf, ns, nav);

            RtkTrace.traceStage5(v1ctrl, v1cb, rtk.epoch, obs[0].time,
                    0, rtk.sol.ratio, sat, ns, nf, rtk, xa_arr, null);

            if (nb > 1) {
                for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xa_arr[j];
                boolean zdOk = zdres(rtk.toState(), 0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                        y, e, azel, freq, rtk.epoch);

                if (zdOk) {
                    nv = ddres(rtk, obs, dt, xa_arr, rtk.P, sat, y, e, azel, freq,
                            iu, ir, ns, nf, nav, v, null, R, vflg, aiBias, aiScale);
                    boolean valOk = nv > 0 && valpos(rtk, v, R, vflg, nv, 4.0);

                    if (valOk) {
                        /* [AI-1] 接口一接线：位置必须精确到 nfix 自增之前（§3.3）。
                         * 之后的 ++nfix / holdamb / PMODE_STATIC_START 切换均不可回滚，
                         * 故拒绝只能表现为「不进固定分支」，stat 保持 SOLQ_FLOAT，
                         * rtk.x / rtk.P 已是浮点解（上方已拷入），回退天然正确。 */
                        if (aiAcceptFix(rtk, obs, sat, iu, ns, nf, nb, xa_arr,
                                v, vflg, nv, azel, opt, v2cfg, v2cb)) {
                            if (++rtk.nfix >= opt.minfix) {
                                if (opt.modear == Constants.ARMODE_FIXHOLD) {
                                    holdamb(rtk, xa_arr);
                                }
                                if (opt.mode == Constants.PMODE_STATIC_START) {
                                    opt.mode = Constants.PMODE_KINEMA;
                                }
                            }
                            stat = Constants.SOLQ_FIX;
                            System.arraycopy(xa_arr, 0, xa, 0, nx);
                            Trace.emit("AR", "FIX", v2cfg, v2cb, rtk.epoch, obs[0].time,
                                    "nb", nb, "ratio", (double) rtk.sol.ratio,
                                    "x", xa_arr[0] + rtk.rb[0],
                                    "y", xa_arr[1] + rtk.rb[1],
                                    "z", xa_arr[2] + rtk.rb[2]);
                        }
                    } else {
                        Trace.emit("AR", "FAIL", v2cfg, v2cb, rtk.epoch, obs[0].time,
                                "nb", nb, "ratio", (double) rtk.sol.ratio,
                                "reason", "valpos_failed");
                    }
                } else {
                    Trace.emit("AR", "FAIL", v2cfg, v2cb, rtk.epoch, obs[0].time,
                            "nb", nb, "ratio", (double) rtk.sol.ratio,
                            "reason", "zdres_failed");
                }
            } else {
                Trace.emit("AR", "FAIL", v2cfg, v2cb, rtk.epoch, obs[0].time,
                        "nb", nb, "ratio", (double) rtk.sol.ratio,
                        "reason", "insufficient_ambiguities");
            }
        }



        if (rtk.epoch <= 4) {
            LOG.info("[STD-KF] epoch={} POST-AR: stat={} nfix={} ratio={} rtkx=({},{},{}) posVar={} xa=({},{},{})",
                    rtk.epoch, stat, rtk.nfix, String.format("%.3f", rtk.sol.ratio),
                    String.format("%.6f", rtk.x[0]), String.format("%.6f", rtk.x[1]),
                    String.format("%.6f", rtk.x[2]), String.format("%.6f", rtk.P[0]),
                    String.format("%.6f", xa[0]), String.format("%.6f", xa[1]),
                    String.format("%.6f", xa[2]));
        }

        if (stat == Constants.SOLQ_FIX) {
            for (i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.rb[i] + xa[i];
                rtk.sol.qr[i] = (float) rtk.Pa[i * rtk.na + i];
            }
            rtk.sol.qr[3] = (float) rtk.Pa[0 * rtk.na + 1];
            rtk.sol.qr[4] = (float) rtk.Pa[1 * rtk.na + 2];
            rtk.sol.qr[5] = (float) rtk.Pa[0 * rtk.na + 2];

            if (opt.dynamics != 0) {
                for (i = 3; i < 6; i++) {
                    rtk.sol.rr[i] = xa[i];
                    rtk.sol.qv[i - 3] = (float) rtk.Pa[i * rtk.na + i];
                }
                rtk.sol.qv[3] = (float) rtk.Pa[3 * rtk.na + 4];
                rtk.sol.qv[4] = (float) rtk.Pa[4 * rtk.na + 5];
                rtk.sol.qv[5] = (float) rtk.Pa[3 * rtk.na + 5];
            }
        } else {
            for (i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.rb[i] + rtk.x[i];
                rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
            }
            rtk.sol.qr[3] = (float) rtk.P[0 * nx + 1];
            rtk.sol.qr[4] = (float) rtk.P[1 * nx + 2];
            rtk.sol.qr[5] = (float) rtk.P[0 * nx + 2];

            if (opt.dynamics != 0) {
                for (i = 3; i < 6; i++) {
                    rtk.sol.rr[i] = rtk.x[i];
                    rtk.sol.qv[i - 3] = (float) rtk.P[i * nx + i];
                }
                rtk.sol.qv[3] = (float) rtk.P[3 * nx + 4];
                rtk.sol.qv[4] = (float) rtk.P[4 * nx + 5];
                rtk.sol.qv[5] = (float) rtk.P[3 * nx + 5];
            }
            rtk.nfix = 0;
        }

        /* aligned with C rtkpos.c relpos tail: only overwrite sol.stat when the
         * epoch produced a solution; SOLQ_NONE keeps the stat set by rtkpos
         * (SOLQ_SINGLE when outsingle, else SOLQ_NONE) */
        if (stat != Constants.SOLQ_NONE) {
            rtk.sol.stat = (byte) stat;
        }

        /* [Java扩展] DIAG_QPOS：stat 确定后才能区分 FIX/FLOAT 的列集口径。
         * FIX 分支用 A ∩ [0,NR)（剔除已固定为整数的模糊度列），位置块由 NR×NR
         * 法方程求逆后取 [0:3,0:3] 得到；只要 NR>3（ionoopt=EST / tropopt>=EST /
         * GLONASS autocal / dynamics!=0），电离层/对流层/速度等状态的不确定性
         * 会自然传播进位置块，而硬编码 {0,1,2} 会系统性偏乐观。
         * FLOAT 分支保留模糊度列，取全活跃列法方程的位置块。 */
        if ((opt.diagMask & PrcOpt.DIAG_QPOS) != 0 && qposRn != null
                && stat != Constants.SOLQ_NONE) {
            rtk.sol.qPos = (stat == Constants.SOLQ_FIX)
                    ? QposSolver.fixCov(qposRn, NR(rtk.toState()))
                    : QposSolver.floatCov(qposRn);
        }

        RtkTrace.traceStage6(v1ctrl, v1cb, rtk.epoch, obs[0].time, rtk.sol, opt.niter);
        Trace.emit("POSITION", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                "x", rtk.sol.rr[0], "y", rtk.sol.rr[1], "z", rtk.sol.rr[2],
                "Q", (int) stat, "ns", (int) rtk.sol.ns);
        if (stat == Constants.SOLQ_FIX || stat == Constants.SOLQ_FLOAT) {
            Trace.emit("RESULT", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                    "Q", (int) stat, "ns", (int) rtk.sol.ns,
                    "ratio", (double) rtk.sol.ratio);
        } else {
            Trace.emit("RESULT", "FAIL", v2cfg, v2cb, rtk.epoch, obs[0].time,
                    "Q", (int) stat, "reason", stat == Constants.SOLQ_NONE ? "no_solution" : "insufficient_sats");
        }

        computeDiagData(rtk, opt, v, H, R, vflg, nv, nx);

        double[] dopAzel = new double[Constants.MAXSAT * 2];
        int dopNs = 0;
        for (i = 0; i < Constants.MAXSAT; i++) {
            if (rtk.ssat[i].azel[1] > 0.0) {
                dopAzel[dopNs * 2] = rtk.ssat[i].azel[0];
                dopAzel[dopNs * 2 + 1] = rtk.ssat[i].azel[1];
                dopNs++;
            }
        }
        double[] dop = new double[4];
        RtklibCommon.dops(dopNs, dopAzel, opt.elmin, dop);
        rtk.sol.gdop = (float) dop[0];
        rtk.sol.pdop = (float) dop[1];
        rtk.sol.hdop = (float) dop[2];
        rtk.sol.vdop = (float) dop[3];

        for (i = 0; i < n; i++) {
            for (j = 0; j < nf; j++) {
                if (obs[i].L[j] == 0.0) continue;
                int s = obs[i].sat - 1;
                rtk.ssat[s].pt[obs[i].rcv - 1][j] = obs[i].time;
                rtk.ssat[s].ph[obs[i].rcv - 1][j] = obs[i].L[j];
            }
        }
        for (i = 0; i < Constants.MAXSAT; i++) {
            for (j = 0; j < nf; j++) {
                if ((rtk.ssat[i].slip[j] & Constants.LLI_SLIP) != 0) {
                    rtk.ssat[i].slipc[j]++;
                }
                if (rtk.ssat[i].vsat[j] == 0) continue;
                if (rtk.ssat[i].lock[j] < 0 || (rtk.nfix > 0 && rtk.ssat[i].fix[j] >= 2)) {
                    rtk.ssat[i].lock[j]++;
                }
            }
        }

        return stat != Constants.SOLQ_NONE ? 1 : 0;
    }

    public static int selsat(Obsd[] obs, double[] azel, int nu, int nr, PrcOpt opt,
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

    public static int NP(RtkState rtk) {
        return (rtk.opt().dynamics == 0) ? 3 : 9;
    }

    public static int NI(RtkState rtk) {
        if (rtk.opt().ionoopt == Constants.IONOOPT_EST) {
            if (rtk.opt().ionoGradient) {
                return Constants.MAXSAT * 3;
            }
            return Constants.MAXSAT;
        }
        return 0;
    }

    public static int NT(RtkState rtk) {
        if (rtk.opt().tropopt < Constants.TROPOPT_EST) return 0;
        if (rtk.opt().tropopt < Constants.TROPOPT_ESTG) return 2;
        return 6;
    }

    public static int NB(RtkState rtk) {
        int nf = (rtk.opt().ionoopt == Constants.IONOOPT_IFLC) ? 1 : rtk.opt().nf;
        if (rtk.opt().mode <= Constants.PMODE_DGPS) return 0;
        return Constants.MAXSAT * nf;
    }
    public static int NL(RtkState rtk) {
        if (rtk.opt().glomodear != Constants.GLO_ARMODE_AUTOCAL) return 0;
        return Constants.NFREQGLO;
    }
    public static int NR(RtkState rtk) {
        return NP(rtk) + NI(rtk) + NT(rtk) + NL(rtk);
    }
    public static int II(int sat, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        if (opt.ionoGradient) {
            return np + (sat - 1) * 3;
        }
        return np + (sat - 1);
    }
    public static int IT(int r, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        return np + ni + (nt / 2) * r;
    }
    public static int IL(int f, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        return np + ni + nt + f;
    }
    public static int IB(int sat, int f, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        int nl = (opt.glomodear != Constants.GLO_ARMODE_AUTOCAL) ? 0 : Constants.NFREQGLO;
        int nr = np + ni + nt + nl;
        return nr + Constants.MAXSAT * f + (sat - 1);
    }

    /**
     * 接口一（AI 固定质量校验）接线（§3.3）。
     * <p>
     * 未注入时直接返回 {@code true}：不构造上下文、不分配数组、不改变任何状态，
     * 原生行为逐位不变（I-1）。
     * </p>
     * <p>
     * 注入后：{@code confidence >= AiOpts.fixValidatorMinConfidence} 即接受；
     * 异常、非有限置信度一律按 {@code 1.0}（不干预）处理，禁止 fail-closed（§3.4）。
     * </p>
     * <p>
     * 已知不可回滚副作用：{@code manage_amb_LAMBDA} 内对 {@code prev_ratio1/2}、
     * {@code excsat}、{@code lock[]} 的修改在本次校验之前已经发生，拒绝固定不会撤销它们，
     * 这是期望行为（ratio 确实很高，AR 历史应如实记录），不是 bug（§3.3）。
     * </p>
     *
     * @return {@code true} 接受固定（进入原生固定分支）；{@code false} 拒绝（stat 保持浮点）
     */
    private static boolean aiAcceptFix(Rtk rtk, Obsd[] obs, int[] sat, int[] iu, int ns, int nf,
                                       int nb, double[] xa, double[] v, int[] vflg, int nv,
                                       double[] azel, PrcOpt opt,
                                       TraceConfig cfg, TraceCallback cb) {
        FixValidator validator = AiHooks.resolveFixValidator(rtk);
        if (validator == null) return true;
        /* [AI-1] IFLC 统一门禁（§4.3.7）：与接口三一致，组合观测下训练分布失效，直接不调用。 */
        if (opt.ionoopt == Constants.IONOOPT_IFLC) return true;

        double confidence;
        try {
            confidence = validator.validate(buildFixQualityContext(rtk, sat, iu, ns, nf, nb,
                    xa, v, vflg, nv, azel, opt));
        } catch (Throwable t) {
            if (rtk.rtkConfig.ai.traceDegrades && !rtk.aiDegradeLogged) {
                rtk.aiDegradeLogged = true;
                Trace.emit("AR", "AI_DEGRADE", cfg, cb, rtk.epoch, obs[0].time,
                        "interface", "fix_validator", "error", String.valueOf(t));
            }
            LOG.warn("AI fix_validator 抛出异常，按不干预(1.0)处理: epoch={}, {}",
                    rtk.epoch, t.toString());
            return true;
        }
        if (!Double.isFinite(confidence)) {
            LOG.warn("AI fix_validator 返回非有限置信度 {}，按不干预(1.0)处理: epoch={}",
                    confidence, rtk.epoch);
            return true;
        }
        double clamped = Math.max(0.0, Math.min(1.0, confidence));
        double threshold = rtk.rtkConfig.ai.fixValidatorMinConfidence;
        boolean accept = clamped >= threshold;
        if (!accept && rtk.rtkConfig.ai.traceRejects) {
            Trace.emit("AR", "AI_REJECT", cfg, cb, rtk.epoch, obs[0].time,
                    "nb", nb, "ratio", (double) rtk.sol.ratio,
                    "confidence", clamped, "threshold", threshold);
        }
        return accept;
    }

    /**
     * 构造接口一输入（§4.4.5 / §5.1 六维来源）。
     * <p>
     * {@code pdop} 必须局部重算：{@code rtk.sol.pdop} 的输入是 {@code ssat[].azel}，
     * 而该字段在全仓库范围内从未被赋值，故恒为 0；仰角只能取自 {@code relpos} 的工作
     * 缓冲 {@code azel}，按 {@code iu[]} 索引。此处**不回写** {@code rtk.sol.pdop}，
     * 否则会改变 sol 输出与 RESULT trace 的 pdop 字段，破坏 I-1 的日志基线。
     * </p>
     * <p>
     * {@code prr}/{@code pcr} 取自固定解回代那次 {@code ddres} 的 {@code v[]}，
     * 不用 {@code ssat.resp/resc}：后者按频点存、参考星恒 0、且入口被清零，
     * 用它会系统性低估 RMS（§5.1）。
     * </p>
     */
    private static FixQualityContext buildFixQualityContext(Rtk rtk, int[] sat, int[] iu,
                                                            int ns, int nf, int nb, double[] xa,
                                                            double[] v, int[] vflg, int nv,
                                                            double[] azel, PrcOpt opt) {
        double adrSum = 0.0;
        int adrCount = 0;
        for (int i = 0; i < ns; i++) {
            for (int f = 0; f < nf; f++) {
                int ib = IB(sat[i], f, opt);
                if (ib < 0 || ib >= rtk.nx) continue;
                double xi = rtk.x[ib];
                double xai = xa[ib];
                if (xi == 0.0 || xai == 0.0) continue;
                double d = xi - xai;
                adrSum += d * d;
                adrCount++;
            }
        }
        double adrRms = adrCount > 0 ? Math.sqrt(adrSum / adrCount) : 0.0;

        double prrSum = 0.0;
        double pcrSum = 0.0;
        int nPhase = 0;
        int nCode = 0;
        for (int k = 0; k < nv; k++) {
            boolean isCode = ((vflg[k] >> 4) & 0x01) == 1;
            if (isCode) {
                pcrSum += v[k] * v[k];
                nCode++;
            } else {
                prrSum += v[k] * v[k];
                nPhase++;
            }
        }
        double prrRms = nPhase > 0 ? Math.sqrt(prrSum / nPhase) : 0.0;
        double pcrRms = nCode > 0 ? Math.sqrt(pcrSum / nCode) : 0.0;

        double[] dopAzel = new double[Constants.MAXSAT * 2];
        int dopNs = 0;
        for (int i = 0; i < ns; i++) {
            dopAzel[dopNs * 2] = azel[iu[i] * 2];
            dopAzel[dopNs * 2 + 1] = azel[iu[i] * 2 + 1];
            dopNs++;
        }
        double[] dop = new double[4];
        RtklibCommon.dops(dopNs, dopAzel, opt.elmin, dop);

        double nsFixed = rtk.sol.ns > 0 ? (double) nb / rtk.sol.ns : 0.0;

        return new FixQualityContext(rtk.epoch, rtk.sol.ns, nf, nb, rtk.sol.ratio, dop[1],
                adrRms, prrRms, pcrRms, nCode, nPhase);
    }


    private static void udstate(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav,
                                int[] sat, int ns, int[] iu, int[] ir) {
        PrcOpt opt = rtk.opt;

        udpos(rtk);

        if (opt.ionoopt == Constants.IONOOPT_EST || opt.tropopt >= Constants.TROPOPT_EST) {
            double[] dr = new double[3];
            double bl = baseline(rtk.x, rtk.rb, dr);
            if (opt.ionoopt == Constants.IONOOPT_EST) {
                udion(rtk, bl, sat, ns);
            }
            if (opt.tropopt >= Constants.TROPOPT_EST) {
                udtrop(rtk, bl, ns);
            }
        }

        if (opt.glomodear == Constants.GLO_ARMODE_AUTOCAL && (opt.navsys & Constants.SYS_GLO) != 0) {
            udrcvbias(rtk);
        }

        if (opt.mode > Constants.PMODE_DGPS) {
            udbias(rtk, obs, nu, nr, nav, sat, ns, iu, ir);
        }
    }

    private static void udpos(Rtk rtk) {
        PrcOpt opt = rtk.opt;
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;
        double tt = rtk.tt;

        if (opt.mode == Constants.PMODE_FIXED) {
            for (int i = 0; i < 3; i++) initx(x, P, nx, opt.ru[i] - rtk.rb[i], Constants.VAR_POS_FIX, i);
            return;
        }

        double[] rr_abs = new double[3];
        for (int i = 0; i < 3; i++) rr_abs[i] = rtk.rb[i] + x[i];
        double normAbs = RtklibCommon.norm(rr_abs, 3);
        if (normAbs <= Constants.RE_WGS84 / 2.0) {
            for (int i = 0; i < 3; i++) initx(x, P, nx, rtk.sol.rr[i] - rtk.rb[i], Constants.VAR_POS, i);
            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) initx(x, P, nx, rtk.sol.rr[i], Constants.VAR_VEL, i);
                for (int i = 6; i < 9; i++) initx(x, P, nx, 1E-6, Constants.VAR_ACC, i);
            }
            return;
        }

        if (opt.mode == Constants.PMODE_STATIC || opt.mode == Constants.PMODE_STATIC_START) {
            return;
        }

        if (opt.dynamics == 0) {
            double prePosvar = (P[0 * nx + 0] + P[1 * nx + 1] + P[2 * nx + 2]) / 3.0;
            for (int i = 0; i < 3; i++) initx(x, P, nx, rtk.sol.rr[i] - rtk.rb[i], Constants.VAR_POS, i);
            double postPosvar = (P[0 * nx + 0] + P[1 * nx + 1] + P[2 * nx + 2]) / 3.0;
            return;
        }

        double var = 0.0;
        for (int i = 0; i < 3; i++) var += P[i * nx + i];
        var /= 3.0;

        if (var > Constants.VAR_POS) {
            for (int i = 0; i < 3; i++) initx(x, P, nx, rtk.sol.rr[i] - rtk.rb[i], Constants.VAR_POS, i);
            for (int i = 3; i < 6; i++) initx(x, P, nx, rtk.sol.rr[i], Constants.VAR_VEL, i);
            for (int i = 6; i < 9; i++) initx(x, P, nx, 1E-6, Constants.VAR_ACC, i);
            return;
        }

        int[] ix = new int[nx];
        int nnx = 0;
        for (int i = 0; i < nx; i++) {
            if (i < 9 || (x[i] != 0.0 && P[i * nx + i] > 0.0)) {
                ix[nnx++] = i;
            }
        }

        double[] F = new double[nnx * nnx];
        for (int i = 0; i < nnx; i++) F[i * nnx + i] = 1.0;

        for (int i = 0; i < 6; i++) {
            int row = -1, col = -1;
            for (int k = 0; k < nnx; k++) {
                if (ix[k] == i) row = k;
                if (ix[k] == i + 3) col = k;
            }
            if (row >= 0 && col >= 0) {
                F[row * nnx + col] = tt;
            }
        }

        if (var < opt.thresar[1]) {
            for (int i = 0; i < 3; i++) {
                int row = -1, col = -1;
                for (int k = 0; k < nnx; k++) {
                    if (ix[k] == i) row = k;
                    if (ix[k] == i + 6) col = k;
                }
                if (row >= 0 && col >= 0) {
                    F[row * nnx + col] = (tt >= 0 ? 1 : -1) * tt * tt / 2.0;
                }
            }
        }

        double[] xc = new double[nnx];
        double[] Pc = new double[nnx * nnx];
        for (int i = 0; i < nnx; i++) {
            xc[i] = x[ix[i]];
            for (int j = 0; j < nnx; j++) {
                Pc[i * nnx + j] = P[ix[i] * nx + ix[j]];
            }
        }

        SimpleMatrix FMat = MatrixUtil.createMatrix(F, nnx, nnx);
        SimpleMatrix xcMat = MatrixUtil.createMatrix(xc, nnx, 1);
        SimpleMatrix PcMat = MatrixUtil.createMatrix(Pc, nnx, nnx);

        SimpleMatrix xpMat = MatrixUtil.multiply(FMat, xcMat);
        SimpleMatrix FPMat = MatrixUtil.multiply(FMat, PcMat);
        SimpleMatrix PpMat = MatrixUtil.multiply(FPMat, MatrixUtil.transpose(FMat));

        for (int i = 0; i < nnx; i++) {
            x[ix[i]] = xpMat.get(i, 0);
            for (int j = 0; j < nnx; j++) {
                P[ix[i] * nx + ix[j]] = PpMat.get(i, j);
            }
        }

        double[] Q = new double[9];
        double qh = opt.prn[3] * opt.prn[3] * Math.abs(tt);
        double qv = opt.prn[4] * opt.prn[4] * Math.abs(tt);

        if (rtk.rtkConfig.rtk.enableAdaptiveQ && rtk.qScale != 1.0) {
            qh *= rtk.qScale * rtk.qScale;
            qv *= rtk.qScale * rtk.qScale;
        }

        Q[0] = qh;
        Q[4] = qh;
        Q[8] = qv;

        double[] pos = new double[3];
        double[] rrAbs = new double[3];
        for (int i = 0; i < 3; i++) rrAbs[i] = x[i] + rtk.rb[i];
        CoordTransform.ecef2pos(rrAbs, pos);

        double[] Qv = new double[9];
        CoordTransform.covecef(pos, Q, Qv);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                P[(i + 6) * nx + (j + 6)] += Qv[i * 3 + j];
            }
        }
    }

    private static void udion(Rtk rtk, double bl, int[] sat, int ns) {
        PrcOpt opt = rtk.opt;
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;
        boolean ionoGrad = opt.ionoGradient;

        if (rtk.rtkConfig.common.atmFrozenNsThresh > 0 && ns < rtk.rtkConfig.common.atmFrozenNsThresh) {
            return;
        }

        for (int i = 1; i <= Constants.MAXSAT; i++) {
            int j = II(i, opt);
            if (x[j] != 0.0 &&
                rtk.ssat[i - 1].outc[0] > Constants.GAP_RESION &&
                rtk.ssat[i - 1].outc[1] > Constants.GAP_RESION) {
                x[j] = 0.0;
                if (ionoGrad) {
                    x[j + 1] = 0.0;
                    x[j + 2] = 0.0;
                }
            }
        }

        double gradInitVar = rtk.rtkConfig.rtk.gradientIonoInitVar;
        double gradPrn = rtk.rtkConfig.rtk.gradientIonoPrn;

        for (int i = 0; i < ns; i++) {
            int j = II(sat[i], opt);
            if (x[j] == 0.0) {
                initx(x, P, nx, 1E-6, SQR(opt.std[1] * bl / 1E4), j);
                if (ionoGrad) {
                    initx(x, P, nx, 0.0, gradInitVar, j + 1);
                    initx(x, P, nx, 0.0, gradInitVar, j + 2);
                }
            } else {
                if (!rtk.rtkConfig.common.enableParamTypeNoise) {
                    double el = rtk.ssat[sat[i] - 1].azel[1];
                    double fact = Math.cos(el);
                    P[j * nx + j] += SQR(opt.prn[1] * bl / 1E4 * fact) * Math.abs(rtk.tt);
                    if (ionoGrad) {
                        P[(j + 1) * nx + (j + 1)] += SQR(gradPrn) * Math.abs(rtk.tt);
                        P[(j + 2) * nx + (j + 2)] += SQR(gradPrn) * Math.abs(rtk.tt);
                    }
                }
            }
        }
    }

    private static void udtrop(Rtk rtk, double bl, int ns) {
        PrcOpt opt = rtk.opt;
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;

        if (rtk.rtkConfig.common.atmFrozenNsThresh > 0 && ns < rtk.rtkConfig.common.atmFrozenNsThresh) {
            return;
        }

        for (int i = 0; i < 2; i++) {
            int j = IT(i, opt);

            if (x[j] == 0.0) {
                initx(x, P, nx, Constants.INIT_ZWD, SQR(opt.std[2]), j);
                if (opt.tropopt >= Constants.TROPOPT_ESTG) {
                    for (int k = 0; k < 2; k++) {
                        initx(x, P, nx, 1E-6, Constants.VAR_GRA, ++j);
                    }
                }
            } else {
                if (!rtk.rtkConfig.common.enableParamTypeNoise) {
                    P[j * nx + j] += SQR(opt.prn[2]) * Math.abs(rtk.tt);
                    if (opt.tropopt >= Constants.TROPOPT_ESTG) {
                        for (int k = 0; k < 2; k++) {
                            j++;
                            P[j * nx + j] += SQR(opt.prn[2] * 0.3) * Math.abs(rtk.tt);
                        }
                    }
                }
            }
        }
    }

    private static void udrcvbias(Rtk rtk) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;
        int na = rtk.na;

        for (int i = 0; i < Constants.NFREQGLO; i++) {
            int j = IL(i, rtk.opt);

            if (x[j] == 0.0) {
                initx(x, P, nx, rtk.opt.thresar[2] + 1e-6, rtk.opt.thresar[3], j);
            } else if (rtk.nfix >= rtk.opt.minfix) {
                initx(x, P, nx, rtk.xa[j], rtk.Pa[j * na + j], j);
            } else {
                P[j * nx + j] += SQR(rtk.opt.thresar[4]) * Math.abs(rtk.tt);
            }
        }
    }

    public static double baseline(double[] x, double[] rb, double[] dr) {
        if (dr == null) dr = new double[3];
        return Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
    }

    public static void tidedisp(GTime tutc, double[] rr, int opt, Erp erp,
                                  double[][][] odisp, double[] dr) {
        dr[0] = dr[1] = dr[2] = 0.0;
        if (RtklibCommon.norm(rr, 3) <= 0.0) return;

        if ((opt & 1) != 0) {
            double[] erpv = new double[4];
            if (Tides.geterp(erp, tutc, erpv) == 0) {
                erpv[0] = erpv[1] = erpv[2] = erpv[3] = 0.0;
            }
            double[] rsun = new double[3], rmoon = new double[3];
            Tides.sunmoonpos(tutc, erpv, rsun, rmoon, null);
            double[] drt = new double[3];
            Tides.dehanttideinel(tutc, rr, rsun, rmoon, drt);
            for (int i = 0; i < 3; i++) dr[i] += drt[i];
        }
    }

    public static void antmodel(Pcv pcv, double[] del, double[] azel, int opt, double[] dant) {
        double cosel = Math.cos(azel[1]);
        double sinel = Math.sin(azel[1]);
        double[] e = new double[]{Math.sin(azel[0]) * cosel, Math.cos(azel[0]) * cosel, sinel};

        for (int i = 0; i < Constants.NFREQ; i++) {
            double[] off = new double[3];
            for (int j = 0; j < 3; j++) off[j] = pcv.off[i][j] + del[j];

            double dot = off[0] * e[0] + off[1] * e[1] + off[2] * e[2];
            double pcvar = 0.0;
            if (opt != 0) {
                double eldeg = 90.0 - azel[1] * Constants.R2D;
                pcvar = interpvar(eldeg, pcv.var[i]);
            }
            dant[i] = -dot + pcvar;
        }
    }

    public static double interpvar(double eldeg, double[] var) {
        if (var == null) return 0.0;
        double a = eldeg / 5.0;
        int i = (int) a;
        if (i < 0) i = 0;
        if (i >= var.length - 1) return var[var.length - 1];
        double t = a - i;
        return var[i] * (1.0 - t) + var[i + 1] * t;
    }

    private static void udbias(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav,
                                int[] sat, int ns, int[] iu, int[] ir) {
        PrcOpt opt = rtk.opt;
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        for (int i = 0; i < ns; i++) {
            int s = sat[i] - 1;
            for (int f = 0; f < opt.nf; f++) {
                rtk.ssat[s].slip[f] &= 0xFC;
            }
        }

        detslpDop(rtk.toState(), obs, iu, ns, 1, nav);
        detslpDop(rtk.toState(), obs, ir, ns, 2, nav);

        for (int i = 0; i < ns; i++) {
            detslpCode(rtk.toState(), obs, iu[i], 1);
            detslpCode(rtk.toState(), obs, ir[i], 2);

            detslpLl(rtk.toState(), obs, iu[i], 1);
            detslpLl(rtk.toState(), obs, ir[i], 2);

            detslpGf(rtk.toState(), obs, iu[i], ir[i], nav);

            for (int f = 0; f < nf; f++) {
                rtk.ssat[sat[i] - 1].half[f] =
                    !((obs[iu[i]].LLI[f] & Constants.LLI_HALFC) != 0 ||
                      (obs[ir[i]].LLI[f] & Constants.LLI_HALFC) != 0) ? 1 : 0;
            }
        }

        for (int f = 0; f < nf; f++) {
            for (int i = 1; i <= Constants.MAXSAT; i++) {
                boolean reset = ++rtk.ssat[i - 1].outc[f] > opt.maxout;
                int idx = IB(i, f, opt);
                if (idx >= nx) continue;
                if (opt.modear == Constants.ARMODE_INST && x[idx] != 0.0) {
                    initx(x, P, nx, 0.0, 0.0, idx);
                } else if (reset && x[idx] != 0.0) {
                    initx(x, P, nx, 0.0, 0.0, idx);
                    rtk.ssat[i - 1].outc[f] = 0;
                }
                if (opt.modear != Constants.ARMODE_INST && reset) {
                    rtk.ssat[i - 1].lock[f] = -opt.minlock;
                }
            }

            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                int idx = IB(sat[i], f, opt);
                if (idx < nx) {
                    P[idx * nx + idx] += SQR(opt.prn[0]) * Math.abs(rtk.tt);
                }
                int slip = rtk.ssat[s].slip[f];
                int rejc = (int) rtk.ssat[s].rejc[f];
                if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                    if (iflcNegotiationActive(rtk.toState()) && rtk.ssat[s].iflcSlot[0] > 0) {
                        int[] pair = iflcSlots( rtk.toState(), sat[i]);
                        slip |= rtk.ssat[s].slip[pair[0]] | rtk.ssat[s].slip[pair[1]];
                    } else {
                        int f2 = RtklibCommon.seliflc(opt.nf, rtk.ssat[s].sys);
                        slip |= rtk.ssat[s].slip[f2];
                    }
                    // IFLC配对切换/消失 → 强制重置该星模糊度（等效周跳）
                    if (rtk.ssat[s].iflcPairSwitch) slip |= Constants.LLI_SLIP;
                }
                if (opt.modear != Constants.ARMODE_INST &&
                    (slip & Constants.LLI_SLIP) == 0 && rejc < 2) continue;
                if (x[idx] != 0.0) {
                    x[idx] = 0.0;
                    rtk.ssat[s].rejc[f] = 0;
                    rtk.ssat[s].lock[f] = -opt.minlock;
                    if (rtk.ssat[s].sys != Constants.SYS_GLO) {
                        rtk.ssat[s].icbias[f] = 0;
                    }
                }
            }

            // 消费IFLC配对切换标志（单历元一次性，防止重复重置）
            if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                for (int i = 0; i < ns; i++) {
                    rtk.ssat[sat[i] - 1].iflcPairSwitch = false;
                }
            }

            double[] bias = new double[ns];
            int cnt = 0;
            double offset = 0.0;
            for (int i = 0; i < ns; i++) {
                Obsd obsR = obs[iu[i]];
                Obsd obsB = obs[ir[i]];

                if (opt.ionoopt != Constants.IONOOPT_IFLC) {
                    if (obsR.P[f] == 0.0 || obsB.P[f] == 0.0) continue;
                    if (obsR.L[f] == 0.0 || obsB.L[f] == 0.0) continue;
                    double freqi = SatUtils.sat2freq(sat[i], obsR.code[f], nav);
                    if (freqi == 0.0) continue;
                    double cp = obsR.L[f] - obsB.L[f];
                    double pr = obsR.P[f] - obsB.P[f];
                    bias[i] = cp - pr * freqi / Constants.CLIGHT;
                } else {
                    // [JAVA-ONLY] IFLC槽位对：协商激活时用协商结果，否则回退固定槽位{0,seliflc()}（C版行为）
                    int[] pairU = iflcSlots(rtk.toState(), sat[i]);
                    int s1u = pairU[0], s2u = pairU[1];
                    if (obsR.L[s1u] == 0.0 || obsR.L[s2u] == 0.0 || obsB.L[s1u] == 0.0 || obsB.L[s2u] == 0.0) continue;
                    if (obsR.P[s1u] == 0.0 || obsR.P[s2u] == 0.0 || obsB.P[s1u] == 0.0 || obsB.P[s2u] == 0.0) continue;
                    double freq1 = SatUtils.sat2freq(sat[i], obsR.code[s1u], nav);
                    double freq2 = SatUtils.sat2freq(sat[i], obsR.code[s2u], nav);
                    if (freq1 <= 0.0 || freq2 <= 0.0) continue;
                    double C1 = SQR(freq1) / (SQR(freq1) - SQR(freq2));
                    double C2 = -SQR(freq2) / (SQR(freq1) - SQR(freq2));
                    double cp1 = (obsR.L[s1u] - obsB.L[s1u]) * Constants.CLIGHT / freq1;
                    double cp2 = (obsR.L[s2u] - obsB.L[s2u]) * Constants.CLIGHT / freq2;
                    double pr1 = obsR.P[s1u] - obsB.P[s1u];
                    double pr2 = obsR.P[s2u] - obsB.P[s2u];
                    bias[i] = (C1 * cp1 + C2 * cp2) - (C1 * pr1 + C2 * pr2);
                }
                int idx = IB(sat[i], f, opt);
                if (idx < nx && x[idx] != 0.0) {
                    offset += bias[i] - x[idx];
                    cnt++;
                }
            }

            if (cnt > 0) {
                for (int i = 1; i <= Constants.MAXSAT; i++) {
                    int idx = IB(i, f, opt);
                    if (idx < nx && x[idx] != 0.0) {
                        x[idx] += offset / cnt;
                    }
                }
            }

            int initCount = 0;
            for (int i = 0; i < ns; i++) {
                if (bias[i] == 0.0) continue;
                int idx = IB(sat[i], f, opt);
                if (idx >= nx) continue;
                if (x[idx] != 0.0) continue;
                initx(x, P, nx, bias[i], SQR(opt.std[0]), idx);
                initCount++;
                if (opt.modear != Constants.ARMODE_INST) {
                    rtk.ssat[sat[i] - 1].lock[f] = -opt.minlock;
                }
            }
        }

        // [OPT-RES-EDIT] 精细化残差编辑与周跳检测
        RtkOptimizationsResEdit.screenArcIntegrity(rtk);
    }

    public static boolean zdres(RtkState rtk, int base, Obsd[] obs, int nu, int nr,
                                double[] rs, double[] dts, double[] vare, int[] svh,
                                Nav nav, double[] rr, PrcOpt opt,
                                double[] y, double[] e, double[] azel, double[] freq,
                                int epoch) {
        int n = base != 0 ? nr : nu;
        int off = base != 0 ? nu : 0;
        int foff = base != 0 ? nu : 0;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        for (int i = 0; i < n * nf * 2; i++) y[off * nf * 2 + i] = 0.0;

        double rrNorm = RtklibCommon.norm(rr, 3);
        if (rrNorm <= Constants.RE_WGS84 / 2) {
            return false;
        }

        double[] rr_ = new double[]{rr[0], rr[1], rr[2]};
        if (opt.tidecorr != 0) {
            double[] disp = new double[3];
            tidedisp(TimeSystem.gpst2utc(obs[0].time), rr_, opt.tidecorr, nav.erp, opt.odisp[base], disp);
            for (int i = 0; i < 3; i++) rr_[i] += disp[i];
        }
        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr_, pos);

        double[] zazel = new double[]{0.0, Constants.PI / 2.0};
        double zhd = TroposphereModel.saastamoinen(pos, zazel, 0.0, 293.15);

        for (int i = 0; i < n; i++) {
            int idx = off + i;
            double[] rsi = new double[]{rs[idx * 6], rs[idx * 6 + 1], rs[idx * 6 + 2]};
            double[] ei = new double[3];
            double r = RtklibCommon.geodist(rsi, rr_, ei);
            if (r <= 0.0) {
                continue;
            }

            double[] ae = new double[2];
            double el = RtklibCommon.satazel(pos, ei, ae);
            azel[idx * 2] = ae[0];
            azel[idx * 2 + 1] = el;
            if (el < opt.elmin) {
                continue;
            }

            if (RtklibCommon.satexclude(obs[idx].sat, vare[idx], svh[idx], opt) != 0) {
                continue;
            }

            r += -Constants.CLIGHT * dts[idx * 2];

            double[] mapfh = new double[1];
            double mapf = TroposphereModel.tropmapf(obs[idx].time, pos, ae, mapfh);
            r += mapf * zhd;

            e[idx * 3] = ei[0];
            e[idx * 3 + 1] = ei[1];
            e[idx * 3 + 2] = ei[2];

            double[] dant = new double[Constants.NFREQ];
            antmodel(opt.pcvr[base], opt.antdel[base], ae, opt.posopt[1], dant);

            if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                // [JAVA-ONLY] IFLC槽位对：协商激活时用协商结果，否则回退固定槽位{0,seliflc()}（C版行为）
                int[] pair = iflcSlots(rtk, obs[idx].sat);
                int s1 = pair[0], s2 = pair[1];
                double freq1 = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[s1], nav);
                double freq2 = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[s2], nav);
                if (freq1 == 0.0 || freq2 == 0.0) {
                    continue;
                }

                if (RtklibCommon.testsnr(base, s1, el, obs[idx].SNR[s1], opt.snrmask) != 0 ||
                    RtklibCommon.testsnr(base, s2, el, obs[idx].SNR[s2], opt.snrmask) != 0) continue;

                double C1 = SQR(freq1) / (SQR(freq1) - SQR(freq2));
                double C2 = -SQR(freq2) / (SQR(freq1) - SQR(freq2));
                double dant_if = C1 * dant[s1] + C2 * dant[s2];

                freq[foff * nf + i * nf] = 1.0;

                if (obs[idx].L[s1] != 0.0 && obs[idx].L[s2] != 0.0) {
                    y[off * nf * 2 + i * nf * 2] = C1 * obs[idx].L[s1] * Constants.CLIGHT / freq1
                            + C2 * obs[idx].L[s2] * Constants.CLIGHT / freq2 - r - dant_if;
                }
                if (obs[idx].P[s1] != 0.0 && obs[idx].P[s2] != 0.0) {
                    y[off * nf * 2 + i * nf * 2 + nf] = C1 * obs[idx].P[s1]
                            + C2 * obs[idx].P[s2] - r - dant_if;
                }
            } else {
                for (int f = 0; f < nf; f++) {
                    double fq = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[f], nav);
                    freq[foff * nf + i * nf + f] = fq;
                    if (fq == 0.0) {
                        continue;
                    }

                    if (RtklibCommon.testsnr(base, f, el, obs[idx].SNR[f], opt.snrmask) != 0) {
                        continue;
                    }

                    double lam = Constants.CLIGHT / fq;

                    if (obs[idx].L[f] != 0.0) {
                        y[off * nf * 2 + i * nf * 2 + f] = obs[idx].L[f] * lam - r - dant[f];
                    }
                    if (obs[idx].P[f] != 0.0) {
                        y[off * nf * 2 + i * nf * 2 + nf + f] = obs[idx].P[f] - r - dant[f];
                    }
                }
            }
        }
        return true;
    }

    /* ===================== [JAVA-ONLY] IFLC频率对协商 ===================== */

    /**
     * 每系统IFLC频段槽位对优先级（槽位=code2idx固定频段映射，两站同一槽位即同一频段）。
     * 首槽位恒为0：保证ddres现有 code[frq=0] 频率检查在协商模式下不变。
     */
    private static final int[][] IFLC_PAIRS_GPS = {{0, 1}, {0, 2}};
    private static final int[][] IFLC_PAIRS_GLO = {{0, 1}};
    /** GAL nf>=3 优先 E1+E5a，对齐seliflc(nf>=3,GAL)=2语义 */
    private static final int[][] IFLC_PAIRS_GAL_NF3 = {{0, 2}, {0, 1}};
    private static final int[][] IFLC_PAIRS_GAL_NF2 = {{0, 1}, {0, 2}};
    /** BDS: B1I+B2(C2I+C7D) > B1I+B3I(C2I+C6I) > B1I+B2a(C2I+C5P) */
    private static final int[][] IFLC_PAIRS_BDS = {{0, 1}, {0, 3}, {0, 2}};
    private static final int[][] IFLC_PAIRS_QZS = {{0, 1}, {0, 2}};
    private static final int[][] IFLC_PAIRS_IRN = {{0, 1}};

    /** Poly coeffs used to adjust AR ratio by # of sats, derived by fitting to example from:
     * https://www.tudelft.nl/citg/over-faculteit/afdelingen/geoscience-remote-sensing/research/lambda/lambda
     * (C rtkpos.c ar_poly_coeffs) */
    static final double[][] AR_POLY_COEFFS = {
            {-1.94058448e-01, -7.79023476e+00, 1.24231120e+02, -4.03126050e+02, 3.50413202e+02},
            {6.42237302e-01, -8.39813962e+00, 2.92107285e+01, -2.37577308e+01, -1.14307128e+00},
            {-2.22600390e-02, 3.23169103e-01, -1.39837429e+00, 2.19282996e+00, -5.34583971e-02}};

    /**
     * IFLC协商是否激活（配置开启 + IONOOPT_IFLC）。
     * ssat.iflcSlot={0,0}时各读取点自动回退固定槽位{0,seliflc()}，与C版行为逐位一致。
     */
    public static boolean iflcNegotiationActive(RtkState rtk) {
        return rtk.rtkConfig() != null && rtk.rtkConfig().common.enableIflcPairNegotiation
                && rtk.opt().ionoopt == Constants.IONOOPT_IFLC;
    }

    /**
     * 取卫星的有效IFLC槽位对。
     * 协商激活且已有协商结果时返回协商槽位，否则回退固定槽位{0,seliflc()}（C版行为）。
     * 协商结果按卫星全局维护（槽位语义与接收端无关），两站共用同一对。
     */
    public static int[] iflcSlots(RtkState rtk, int sat) {
        int f2 = RtklibCommon.seliflc(rtk.opt().nf, SatUtils.satsys(sat, null));
        if (iflcNegotiationActive(rtk)) {
            int s1 = rtk.ssat()[sat - 1].iflcSlot[0];
            if (s1 > 0) {
                return new int[]{s1, rtk.ssat()[sat - 1].iflcSlot[1]};
            }
        }
        return new int[]{0, f2};
    }

    /**
     * 两站在槽位对(s1,s2)上是否都有跟踪（code非0即视为该频段结构可用）。
     * 槽位上界NFREQ-1：ssat.slip/vsat等数组按NFREQ分配；数据级空洞（L/P=0）不影响配对，
     * 仅导致该历元该星IFLC观测无效被跳过，避免配对来回抖动。
     */
    private static boolean iflcPairUsable(Obsd obsR, Obsd obsB, int s1, int s2) {
        if (s1 < 0 || s1 >= Constants.NFREQ || s2 < 0 || s2 >= Constants.NFREQ || s1 == s2) {
            return false;
        }
        return obsR.code[s1] != 0 && obsR.code[s2] != 0
                && obsB.code[s1] != 0 && obsB.code[s2] != 0;
    }

    private static int[][] iflcPriorityTable(int sys, int nf) {
        switch (sys) {
            case Constants.SYS_GPS: return IFLC_PAIRS_GPS;
            case Constants.SYS_GLO: return IFLC_PAIRS_GLO;
            case Constants.SYS_GAL: return nf >= 3 ? IFLC_PAIRS_GAL_NF3 : IFLC_PAIRS_GAL_NF2;
            case Constants.SYS_CMP: return IFLC_PAIRS_BDS;
            case Constants.SYS_QZS: return IFLC_PAIRS_QZS;
            case Constants.SYS_IRN: return IFLC_PAIRS_IRN;
            default: return new int[0][];
        }
    }

    /**
     * [JAVA-ONLY] 逐卫星IFLC频率对协商（sticky策略）。
     *
     * <p>在rtkpos()中zdres(base)之前调用，仅RTK双站模式（mode>PMODE_DGPS且nr>0）生效。
     * obs须按(接收端,卫星号)排序：obs[0..nu)流动站、obs[nu..nu+nr)基准站。
     * 对两站公共卫星：
     * <ul>
     *   <li>sticky：上一配对仍结构可用（两站该频段槽位code非0）则保持不变；</li>
     *   <li>失效重选：按系统优先级表从两站公共频段中重选，切换时置iflcPairSwitch
     *       （udbias消费后强制重置该星模糊度并清lock，等效周跳处理）；</li>
     *   <li>无公共频率对：iflcSlot清零，该星回退固定槽位（通常因数据缺失被IFLC跳过）。</li>
     * </ul>
     * 未协商/开关关闭/PPP模式：ssat.iflcSlot保持{0,0}，各读取点回退固定槽位，零影响。
     */
    static void negotiateIflcPairs(RtkState rtk, Obsd[] obs, int nu, int nr) {
        PrcOpt opt = rtk.opt();
        if (!iflcNegotiationActive(rtk)) {
            return;
        }
        LOG.debug("IFLC negotiate: active, nu={}, nr={}", nu, nr);
        if (opt.mode <= Constants.PMODE_DGPS || nr <= 0) {
            return;
        }

        int i = 0, j = nu;
        while (i < nu && j < nu + nr) {
            if (obs[i].sat < obs[j].sat) {
                i++;
            } else if (obs[i].sat > obs[j].sat) {
                j++;
            } else {
                selectIflcPair(rtk, obs[i], obs[j], obs[i].sat);
                i++;
                j++;
            }
        }
    }

    /** 单卫星协商：sticky保持，失效重选，切换/消失按周跳处理 */
    public static void selectIflcPair(RtkState rtk, Obsd obsR, Obsd obsB, int sat) {
        int p0 = rtk.ssat()[sat - 1].iflcSlot[0];
        int p1 = rtk.ssat()[sat - 1].iflcSlot[1];
        boolean hadPair = p0 > 0 || p1 > 0;

        if (hadPair && iflcPairUsable(obsR, obsB, p0, p1)) {
            return;
        }
        if (hadPair) {
            rtk.ssat()[sat - 1].iflcPairSwitch = true;
        }

        int[][] table = iflcPriorityTable(SatUtils.satsys(sat, null), rtk.opt().nf);
        for (int[] pair : table) {
            if (iflcPairUsable(obsR, obsB, pair[0], pair[1])) {
                if (hadPair && (pair[0] != p0 || pair[1] != p1)) {
                    rtk.ssat()[sat - 1].iflcPairSwitch = true;
                }
                rtk.ssat()[sat - 1].iflcSlot[0] = pair[0];
                rtk.ssat()[sat - 1].iflcSlot[1] = pair[1];
                LOG.debug("IFLC pair: sat={}, slot={},{}, sys={}", sat, pair[0], pair[1], SatUtils.satsys(sat, null));
                return;
            }
        }
        rtk.ssat()[sat - 1].iflcSlot[0] = 0;
        rtk.ssat()[sat - 1].iflcSlot[1] = 0;
    }

    private static int nb(Rtk rtk, int[] sat, int ns, PrcOpt opt) {
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nb = 0;
        for (int i = 0; i < ns; i++) {
            for (int f = 0; f < nf; f++) {
                if (rtk.ssat[sat[i] - 1].lock[f] > 0) nb++;
            }
        }
        return nb;
    }

    public static double varerr(int sat, int sys, double el, double snr_rover,
                                double snr_base, double bl, double dt, int f,
                                PrcOpt opt, Obsd obs, RtkState rtk) {
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
            double snrRef = opt.err[5];
            if (rtk != null && rtk.rtkConfig().rtk.enableSnrMedian && frq < rtk.snrMedian().length
                && rtk.snrMedian()[frq] > Double.NEGATIVE_INFINITY) {
                snrRef = rtk.snrMedian()[frq];
            }
            var += SQR(e) * (Math.pow(10, 0.1 * Math.max(snrRef - snr_rover, 0.0)) +
                             Math.pow(10, 0.1 * Math.max(snrRef - snr_base, 0.0)));
        }
        if (opt.err[7] > 0.0) {
            if (code) var += SQR(opt.err[7] * obs.Pstd[frq]);
            else var += SQR(opt.err[7] * obs.Lstd[frq] * 0.2);
        }

        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            var *= 9.0; /* SQR(3.0), aligned with C rtkpos.c varerr() */
        }

        return var;
    }

    private static void ddcov(int[] nb, int b, double[] Ri, double[] Rj, int nv,
                              double[] R) {
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

    public static double prectrop(RtkState rtk, double[] pos, int r,
                                  double[] azel, double[] dtdx, int off) {
        PrcOpt opt = rtk.opt();
        double[] x = rtk.x();
        int it = IT(r, opt);

        double[] mapWet = new double[1];
        TroposphereModel.tropmapf(rtk.sol().time, pos, azel, mapWet);
        double m_w = mapWet[0];

        if (opt.tropopt >= Constants.TROPOPT_ESTG && azel[1] > 0.0) {
            double cotz = 1.0 / Math.tan(azel[1]);
            double grad_n = m_w * cotz * Math.cos(azel[0]);
            double grad_e = m_w * cotz * Math.sin(azel[0]);
            m_w += grad_n * x[it + 1] + grad_e * x[it + 2];
            dtdx[off + 1] = grad_n * x[it];
            dtdx[off + 2] = grad_e * x[it];
        } else {
            dtdx[off + 1] = dtdx[off + 2] = 0.0;
        }
        dtdx[off] = m_w;
        return m_w * x[it];
    }

    /**
     * 接口三批量推理（§3.3）：每历元仅调用一次，结果写入 {@code bias}/{@code scale} 供 ddres 只读。
     * <p>
     * 所有降级路径（异常、null、长度不符、非有限值）都回落到「不干预」（bias=0、scale=1），
     * 绝不 fail-closed；偏差超 {@code ai.obsCorrectorMaxBias} 时置 0（不钳到边界），
     * 方差超 {@code ai.varianceScaleMax} 时钳到上限并告警（§4.3.4）。
     * </p>
     */
    private static void aiComputeCorrection(Rtk rtk, Obsd[] obs, int[] sat, int[] iu,
                                            int ns, int nf, double dt, double[] azel,
                                            double[] aiBias, double[] aiScale,
                                            ObsCorrector corrector,
                                            TraceConfig cfg, TraceCallback cb) {
        Arrays.fill(aiBias, 0.0);
        Arrays.fill(aiScale, 1.0);

        SatFeature[] feats = new SatFeature[ns];
        for (int i = 0; i < ns; i++) {
            Ssat ss = rtk.ssat[sat[i] - 1];
            feats[i] = new SatFeature(
                    sat[i],
                    SatUtils.satsys(sat[i], null),
                    azel[iu[i] * 2],
                    azel[iu[i] * 2 + 1],
                    aiDoubles(ss.snrRover),
                    aiDoubles(ss.snrBase),
                    ss.resp,
                    ss.resc,
                    ss.lock,
                    ss.slip,
                    ss.half,
                    ss.gf,
                    ss.vsat,
                    ss.fix);
        }

        CodeCorrection cc;
        try {
            cc = corrector.correct(new ObsCorrectionContext(rtk.epoch, ns, nf, dt, feats));
        } catch (Throwable t) {
            LOG.warn("接口三实现抛异常，本历元按不干预处理（epoch={}）: {}", rtk.epoch, t.toString());
            return;
        }
        if (cc == null || cc.biasMeters() == null || cc.varianceScale() == null
                || cc.biasMeters().length != ns * nf || cc.varianceScale().length != ns * nf) {
            LOG.warn("接口三输出非法（null 或长度不符，期望 {}），本历元按不干预处理（epoch={}）",
                    ns * nf, rtk.epoch);
            if (aiTraceDegrades(rtk)) {
                Trace.emit("AI3", "AI_DEGRADE", cfg, cb, rtk.epoch, obs[0].time,
                        "interface", "obs_corrector", "reason", "invalid_output");
            }
            return;
        }

        AiOpts ai = rtk.rtkConfig == null ? null : rtk.rtkConfig.ai;
        double maxBias = ai == null ? AiOpts.DEFAULT_OBS_CORRECTOR_MAX_BIAS : ai.obsCorrectorMaxBias;
        double maxScale = ai == null ? AiOpts.DEFAULT_VARIANCE_SCALE_MAX : ai.varianceScaleMax;

        for (int idx = 0; idx < ns * nf; idx++) {
            double b = cc.biasMeters()[idx];
            double s = cc.varianceScale()[idx];
            if (!Double.isFinite(b) || Math.abs(b) > maxBias) {
                if (!rtk.aiBiasClampLogged) {
                    rtk.aiBiasClampLogged = true;
                    LOG.warn("接口三偏差越界/非有限({} > {} m)，该行置 0（不钳边界，§4.3.4）: epoch={}",
                            b, maxBias, rtk.epoch);
                }
            } else {
                aiBias[idx] = b;
            }
            if (!Double.isFinite(s) || s < 1.0) {
                aiScale[idx] = 1.0;
            } else if (s > maxScale) {
                aiScale[idx] = maxScale;
                if (!rtk.aiScaleClampLogged) {
                    rtk.aiScaleClampLogged = true;
                    LOG.warn("接口三方差膨胀因子越界({} > {})，钳到上限（§4.3.4）: epoch={}",
                            s, maxScale, rtk.epoch);
                }
            } else {
                aiScale[idx] = s;
            }
        }

        Trace.emit("AI3", "CORRECT", cfg, cb, rtk.epoch, obs[0].time,
                "ns", ns, "nf", nf, "dt", dt);
    }

    private static boolean aiTraceDegrades(Rtk rtk) {
        return rtk.rtkConfig != null && rtk.rtkConfig.ai != null && rtk.rtkConfig.ai.traceDegrades;
    }

    private static double[] aiDoubles(float[] src) {
        double[] d = new double[src.length];
        for (int k = 0; k < src.length; k++) d[k] = src[k];
        return d;
    }

    private static int ddres(Rtk rtk, Obsd[] obs, double dt, double[] x, double[] P,
                             int[] sat, double[] y, double[] e, double[] azel,
                             double[] freq, int[] iu, int[] ir, int ns, int nf,
                             Nav nav, double[] v, double[] H, double[] R, int[] vflg,
                             double[] aiBias, double[] aiScale) {
        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;int i, j, k, m, f, nv = 0;
        int[] nb = new int[Constants.NFREQ * 7 * 2 + 2];
        int b = 0;
        double[] rr_f = new double[3];
        for (i = 0; i < 3; i++) rr_f[i] = rtk.rb[i] + x[i];
        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr_f, pos);
        double[] rr_r = rtk.rb;
        double[] pos_r = new double[3];
        CoordTransform.ecef2pos(rr_r, pos_r);
        double[] posu = new double[3];
        CoordTransform.ecef2pos(x, posu);

        double[] tropu = null, tropr = null, dtdxu = null, dtdxr = null;
        if (opt.tropopt >= Constants.TROPOPT_EST) {
            tropu = new double[ns];
            tropr = new double[ns];
            dtdxu = new double[ns * 3];
            dtdxr = new double[ns * 3];
            for (i = 0; i < ns; i++) {
                tropu[i] = prectrop(rtk.toState(), posu, 0,
                        new double[]{azel[iu[i] * 2], azel[iu[i] * 2 + 1]}, dtdxu, i * 3);
                tropr[i] = prectrop(rtk.toState(), pos_r, 1,
                        new double[]{azel[ir[i] * 2], azel[ir[i] * 2 + 1]}, dtdxr, i * 3);
            }
        }

        double bl = RtklibCommon.norm(x, 3);

        double[] Ri = null, Rj = null;
        if (R != null) {
            Ri = new double[ns * nf * 2 + 2];
            Rj = new double[ns * nf * 2 + 2];
        }

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
                double bestRefScore = Double.NEGATIVE_INFINITY;
                for (j = 0; j < ns; j++) {
                    int sysj = SatUtils.satsys(sat[j], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    if (sysj == Constants.SYS_SBS) continue;
                    if (!validobs(iu[j], ir[j], f, nf, y)) continue;
                    if (refIdx >= 0 && (rtk.ssat[sat[j] - 1].slip[frq] & Constants.LLI_SLIP) != 0) continue;
                    double el_j = azel[1 + iu[j] * 2];
                    if (rtk.rtkConfig.rtk.enableSnrMedian && frq < rtk.snrMedian.length
                        && rtk.snrMedian[frq] > Double.NEGATIVE_INFINITY) {
                        double snr_j = rtk.ssat[sat[j] - 1].snrRover[frq];
                        double snrMedianVal = rtk.snrMedian[frq];
                        double snrScore = 1.0 - Math.abs(snr_j - snrMedianVal) / Math.max(snrMedianVal, 1.0);
                        double score = el_j + snrScore * rtk.rtkConfig.rtk.snrMedianKPhase;
                        if (refIdx < 0 || score > bestRefScore) {
                            refIdx = j;
                            bestRefScore = score;
                        }
                    } else {
                        if (refIdx < 0 || el_j >= azel[1 + iu[refIdx] * 2]) {
                            refIdx = j;
                        }
                    }
                }
                if (refIdx < 0) {
                    continue;
                }


                int cntBefore = nv;
                for (j = 0; j < ns; j++) {
                    if (j == refIdx) continue;
                    int sysj = SatUtils.satsys(sat[j], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    if (!validobs(iu[j], ir[j], f, nf, y)) {
                        continue;
                    }

                    double freqi = SatUtils.sat2freq(sat[refIdx], obs[iu[refIdx]].code[frq], nav);
                    double freqj = SatUtils.sat2freq(sat[j], obs[iu[j]].code[frq], nav);
                    if (freqi <= 0.0 || freqj <= 0.0) {
                        continue;
                    }

                    if (H != null) {
                        for (k = 0; k < nx; k++) H[nv * nx + k] = 0.0;
                    }

                    int idx_i = iu[refIdx];
                    int idx_ir = ir[refIdx];
                    int idx_j = iu[j];
                    int idx_jr = ir[j];

                    v[nv] = (y[f + idx_i * nf * 2] - y[f + idx_ir * nf * 2])
                            - (y[f + idx_j * nf * 2] - y[f + idx_jr * nf * 2]);

                    if (H != null) {
                        for (k = 0; k < 3; k++) {
                            H[nv * nx + k] = -e[k + idx_i * 3] + e[k + idx_j * 3];
                        }
                    }

                    if (opt.mode > Constants.PMODE_DGPS && !code) {
                        int ii = IB(sat[refIdx], frq, opt);
                        int jj = IB(sat[j], frq, opt);
                        if (opt.ionoopt != Constants.IONOOPT_IFLC) {
                            double lami = Constants.CLIGHT / freqi;
                            double lamj = Constants.CLIGHT / freqj;
                            v[nv] -= lami * x[ii] - lamj * x[jj];
                            if (H != null) {
                                H[nv * nx + ii] = lami;
                                H[nv * nx + jj] = -lamj;
                            }
                        } else {
                            v[nv] -= x[ii] - x[jj];
                            if (H != null) {
                                H[nv * nx + ii] = 1.0;
                                H[nv * nx + jj] = -1.0;
                            }
                        }
                    }

                    if (opt.ionoopt == Constants.IONOOPT_EST && !code) {
                        int ii_m = II(sat[j], opt);
                        int ii_r = II(sat[refIdx], opt);
                        if (ii_m >= 0 && ii_r >= 0 && ii_m < nx && ii_r < nx) {
                            double az_m = azel[iu[j] * 2];
                            double el_m = azel[iu[j] * 2 + 1];
                            double az_r = azel[iu[refIdx] * 2];
                            double el_r = azel[iu[refIdx] * 2 + 1];
                            double im_m = IonosphereModel.ionmapf(pos, new double[]{az_m, el_m});
                            double im_r = IonosphereModel.ionmapf(pos, new double[]{az_r, el_r});
                            double freqRatio_m = SQR(Constants.FREQL1 / freqj);
                            double freqRatio_r = SQR(Constants.FREQL1 / freqi);
                            double didx_m = im_m * freqRatio_m;
                            double didx_r = im_r * freqRatio_r;
                            v[nv] += didx_m * x[ii_m] - didx_r * x[ii_r];
                            if (H != null) {
                                H[nv * nx + ii_m] += didx_m;
                                H[nv * nx + ii_r] -= didx_r;
                                if (opt.ionoGradient && el_m > 0.0 && el_r > 0.0) {
                                    double cotz_m = 1.0 / Math.tan(el_m);
                                    double grad_n_m = im_m * cotz_m * Math.cos(az_m);
                                    double grad_e_m = im_m * cotz_m * Math.sin(az_m);
                                    double didxGn_m = x[ii_m] * grad_n_m * freqRatio_m;
                                    double didxGe_m = x[ii_m] * grad_e_m * freqRatio_m;
                                    if (ii_m + 1 < nx) H[nv * nx + ii_m + 1] += didxGn_m;
                                    if (ii_m + 2 < nx) H[nv * nx + ii_m + 2] += didxGe_m;

                                    double cotz_r = 1.0 / Math.tan(el_r);
                                    double grad_n_r = im_r * cotz_r * Math.cos(az_r);
                                    double grad_e_r = im_r * cotz_r * Math.sin(az_r);
                                    double didxGn_r = x[ii_r] * grad_n_r * freqRatio_r;
                                    double didxGe_r = x[ii_r] * grad_e_r * freqRatio_r;
                                    if (ii_r + 1 < nx) H[nv * nx + ii_r + 1] -= didxGn_r;
                                    if (ii_r + 2 < nx) H[nv * nx + ii_r + 2] -= didxGe_r;
                                }
                            }
                        }
                    }

                    if (tropu != null) {
                        v[nv] -= (tropu[refIdx] - tropu[j]) - (tropr[refIdx] - tropr[j]);
                        if (H != null) {
                            int nt = (opt.tropopt < Constants.TROPOPT_ESTG) ? 1 : 3;
                            for (k = 0; k < nt; k++) {
                                H[nv * nx + IT(0, opt) + k] = dtdxu[k + refIdx * 3] - dtdxu[k + j * 3];
                                H[nv * nx + IT(1, opt) + k] = -(dtdxr[k + refIdx * 3] - dtdxr[k + j * 3]);
                            }
                        }
                    }

                    /* [AI-3] 偏差施加（§4.3.3/§4.3.6）：只读查表，仅伪距行。
                     * 符号照抄设计：v -= (参考星偏差 − 本星偏差)，索引用 sat[] 下标而非卫星号。
                     * v[nv] 每次由 y 现场重算，故 ddres 多次调用不会重复扣除（幂等）。 */
                    if (code && aiBias != null) {
                        v[nv] -= aiBias[refIdx * nf + frq] - aiBias[j * nf + frq];
                    }

                    int sysi = SatUtils.satsys(sat[refIdx], null);
                    if (sysi == Constants.SYS_GLO && sysj == Constants.SYS_GLO) {
                        if (opt.glomodear == Constants.GLO_ARMODE_AUTOCAL && frq < Constants.NFREQGLO) {
                            double df = (freqi - freqj) / (f == 0 ? Constants.DFRQ1_GLO : Constants.DFRQ2_GLO);
                            v[nv] -= df * x[IL(frq, opt)];
                            if (H != null) {
                                H[nv * nx + IL(frq, opt)] = df;
                            }
                        } else if (opt.glomodear == Constants.GLO_ARMODE_FIXHOLD && frq < Constants.NFREQGLO) {
                            v[nv] -= rtk.ssat[sat[refIdx] - 1].icbias[frq] * Constants.CLIGHT / freqi
                                    - rtk.ssat[sat[j] - 1].icbias[frq] * Constants.CLIGHT / freqj;
                        }
                    }

                    if (sysj == Constants.SYS_SBS && sysi == Constants.SYS_GPS) {
                        if (opt.glomodear == Constants.GLO_ARMODE_FIXHOLD && frq < Constants.NFREQ) {
                            v[nv] -= rtk.ssat[sat[refIdx] - 1].icbias[frq] * Constants.CLIGHT / freqi
                                    - rtk.ssat[sat[j] - 1].icbias[frq] * Constants.CLIGHT / freqj;
                        }
                    }

                    if (code) {
                        rtk.ssat[sat[j] - 1].resp[frq] = v[nv];
                    } else {
                        rtk.ssat[sat[j] - 1].resc[frq] = v[nv];
                    }

                    double threshadj = 1.0;
                    if (opt.mode > Constants.PMODE_DGPS) {
                        int ii = IB(sat[refIdx], frq, opt);
                        int jj = IB(sat[j], frq, opt);
                        double Pii = P[ii * nx + ii];
                        double Pjj = P[jj * nx + jj];
                        double std0sq = opt.std[0] * opt.std[0];
                        if (Pii == std0sq || Pjj == std0sq) {
                            threshadj = 10.0;
                        }
                    }
                    /* aligned with C rtkpos.c ddres(): maxinno[code]*threshadj, no epoch-based relaxation */
                    double maxinno_val = opt.maxinno[code ? 1 : 0] * threshadj;
                    if (Math.abs(v[nv]) > maxinno_val) {
                        /* aligned with C rtkpos.c ddres(): vsat=0 and rejc++ for both code and phase */
                        rtk.ssat[sat[j] - 1].vsat[frq] = 0;
                        rtk.ssat[sat[j] - 1].rejc[frq]++;
                        continue;
                    }

                    double eli = azel[1 + iu[refIdx] * 2];
                    double elj = azel[1 + iu[j] * 2];
                    int sysRef = SatUtils.satsys(sat[refIdx], null);
                    int sysJ = SatUtils.satsys(sat[j], null);
                    if (Ri != null) {
                        Ri[nv] = varerr(sat[refIdx], sysRef, eli, rtk.ssat[sat[refIdx]-1].snrRover[frq], rtk.ssat[sat[refIdx]-1].snrBase[frq], bl, dt, f, opt, obs[iu[refIdx]], rtk.toState());
                        Rj[nv] = varerr(sat[j], sysJ, elj, rtk.ssat[sat[j]-1].snrRover[frq], rtk.ssat[sat[j]-1].snrBase[frq], bl, dt, f, opt, obs[iu[j]], rtk.toState());
                        if (!code) {
                            if ((obs[iu[refIdx]].LLI[frq] & Constants.LLI_HALFC) != 0) Ri[nv] += 0.01;
                            if ((obs[iu[j]].LLI[frq] & Constants.LLI_HALFC) != 0) Rj[nv] += 0.01;
                        }
                        /* [AI-3] 方差膨胀（§4.3.4）：必须在半周 0.01 补偿之后施加，
                         * 否则 0.01 会被膨胀因子放大。Ri ↔ 参考星，Rj ↔ 非参考星，方向不得对调。
                         * code 行与 carrier 行共用同一 (i, frq) 的系数（同一观测质量判断）。 */
                        if (aiScale != null) {
                            Ri[nv] *= aiScale[refIdx * nf + frq];
                            Rj[nv] *= aiScale[j * nf + frq];
                        }
                    }

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

        if (R != null) {
            ddcov(nb, b, Ri, Rj, nv, R);
        }

        return nv;
    }

    private static boolean validobs(int iu, int ir, int f, int nf, double[] y) {
        return y[f + iu * nf * 2] != 0.0 && y[f + ir * nf * 2] != 0.0;
    }

    private static int filter(Rtk rtk, double[] xp, double[] Pp,
                              double[] H, double[] v, double[] R,
                              int nx, int nv) {
        int ret = KalmanFilter.update(xp, Pp, H, v, R, nx, nv, false);
        return ret;
    }

    private static int ddidx(Rtk rtk, int[] ix, int gps, int glo, int sbs) {
        if (rtk.rtkConfig.common.enableParRefReselect) {
            return RtkOptimizations.buildParIndex(rtk, ix, gps, glo, sbs);
        }
        return RtkOptimizations.ddidxFallback(rtk, ix, gps, glo, sbs);
    }

    private static int resamb_LAMBDA(Rtk rtk, double[] bias, double[] xa,
                                     int gps, int glo, int sbs, Nav nav) {
        // [OPT-CASCADE-AR] 真逐级模糊度固定（EWL→WL→NL，JAVA-ONLY 扩展）。
        // IFLC 模式状态空间压缩为每星单 IF 组合偏差，逐级固定不适用（FIX-ALG-006 守卫保留）；
        // 级联任一级失败（返回<=0）时回落标准 LAMBDA 路径，保证可用性不低于基线。
        int nfAr = (rtk.opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : rtk.opt.nf;
        if (rtk.rtkConfig.rtk.enableCascadeAR && nfAr >= 2) {
            int nbCas = RtkOptimizationsCascadeAR.cascadeAmbFix(rtk, bias, xa, gps, glo, sbs, nav);
            if (nbCas > 0) return nbCas;
        }

        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nx = rtk.nx;
        int na = rtk.na;

        rtk.sol.ratio = 0.0f;
        rtk.nb_ar = 0;

        int[] ix = new int[nx * 2];
        int nb = ddidx(rtk, ix, gps, glo, sbs);

        if (nb < opt.minfixsats - 1) {
            return -1;
        }
        rtk.nb_ar = nb;

        boolean enableAnchor = rtk.rtkConfig.rtk.enableAmbAnchor;

        int[] anchorMap = null;
        int[] freeMap = null;
        int anchorCount = 0;
        int freeCount = 0;

        if (enableAnchor) {
            anchorMap = new int[nb];
            freeMap = new int[nb];
            for (int i = 0; i < nb; i++) {
                int satIdx = (ix[i * 2 + 1] - na) % Constants.MAXSAT;
                int f = (ix[i * 2 + 1] - na) / Constants.MAXSAT;
                if (satIdx >= 0 && satIdx < Constants.MAXSAT && f >= 0 && f < nf) {
                    int globalIdx = satIdx * nf + f;
                    if (rtk.ambAnchored[globalIdx]) {
                        anchorMap[anchorCount++] = i;
                    } else {
                        freeMap[freeCount++] = i;
                    }
                } else {
                    freeMap[freeCount++] = i;
                }
            }
        }

        int nbLambda = enableAnchor ? freeCount : nb;

        if (nbLambda == 0 && anchorCount > 0) {
            for (int i = 0; i < na; i++) {
                rtk.xa[i] = rtk.x[i];
                for (int j = 0; j < na; j++) {
                    rtk.Pa[i * na + j] = rtk.P[i * nx + j];
                }
            }
            for (int i = 0; i < rtk.nx; i++) xa[i] = rtk.x[i];

            int nvBias = 0;
            for (int m = 0; m < 6; m++) {
                for (int f = 0; f < nf; f++) {
                    int[] index = new int[Constants.MAXSAT];
                    int n = 0;
                    for (int i = 0; i < Constants.MAXSAT; i++) {
                        if (!testSys(rtk.ssat[i].sys, m) || rtk.ssat[i].fix[f] != 2) continue;
                        index[n++] = IB(i + 1, f, opt);
                    }
                    if (n < 2) continue;
                    xa[index[0]] = rtk.x[index[0]];
                    for (int j = 1; j < n; j++) {
                        xa[index[j]] = xa[index[0]] - Math.round(rtk.x[index[0]] - rtk.x[index[j]]);
                    }
                }
            }

            rtk.sol.ratio = 999.9f;
            return nb;
        }

        if (nbLambda < opt.minfixsats - 1) {
            return -1;
        }

        double[] yFull = new double[nb];
        for (int i = 0; i < nb; i++) {
            yFull[i] = rtk.x[ix[i * 2]] - rtk.x[ix[i * 2 + 1]];
        }

        double[] y = new double[nbLambda];
        double[] DP = new double[nbLambda * (nx - na)];
        double[] b = new double[nbLambda * 2];
        double[] db = new double[nbLambda];
        double[] Qb = new double[nbLambda * nbLambda];
        double[] Qab = new double[na * nbLambda];
        double[] QQ = new double[na * nbLambda];

        if (enableAnchor) {
            for (int i = 0; i < freeCount; i++) {
                y[i] = yFull[freeMap[i]];
            }
        } else {
            System.arraycopy(yFull, 0, y, 0, nb);
        }

        int[] ixLambda = enableAnchor ? freeMap : null;
        int[] ixUsed = new int[nbLambda * 2];
        if (enableAnchor) {
            for (int i = 0; i < freeCount; i++) {
                ixUsed[i * 2] = ix[freeMap[i] * 2];
                ixUsed[i * 2 + 1] = ix[freeMap[i] * 2 + 1];
            }
        } else {
            System.arraycopy(ix, 0, ixUsed, 0, nb * 2);
        }

        int nAmb = nx - na;

        SimpleMatrix PMat = MatrixUtil.createMatrix(rtk.P, nx, nx);
        SimpleMatrix QcMat = new SimpleMatrix(nAmb, nAmb);
        for (int j = 0; j < nAmb; j++) {
            for (int i = 0; i < nAmb; i++) {
                QcMat.set(i, j, PMat.get(na + i, na + j));
            }
        }

        SimpleMatrix QacMat = new SimpleMatrix(na, nAmb);
        for (int j = 0; j < nAmb; j++) {
            for (int i = 0; i < na; i++) {
                QacMat.set(i, j, PMat.get(i, na + j));
            }
        }

        SimpleMatrix DMat = new SimpleMatrix(nbLambda, nAmb);
        for (int i = 0; i < nbLambda; i++) {
            DMat.set(i, ixUsed[i * 2] - na, 1.0);
            DMat.set(i, ixUsed[i * 2 + 1] - na, -1.0);
        }

        SimpleMatrix QbMat = MatrixUtil.multiply(MatrixUtil.multiply(DMat, QcMat), MatrixUtil.transpose(DMat));
        SimpleMatrix QabMat = MatrixUtil.multiply(QacMat, MatrixUtil.transpose(DMat));

        for (int i = 0; i < nbLambda; i++) {
            for (int j = 0; j < nbLambda; j++) {
                Qb[i * nbLambda + j] = QbMat.get(i, j);
            }
        }
        for (int i = 0; i < nbLambda; i++) {
            for (int j = i + 1; j < nbLambda; j++) {
                double avg = 0.5 * (Qb[i * nbLambda + j] + Qb[j * nbLambda + i]);
                Qb[i * nbLambda + j] = avg;
                Qb[j * nbLambda + i] = avg;
            }
        }

        for (int i = 0; i < na; i++) {
            for (int j = 0; j < nbLambda; j++) {
                Qab[i * nbLambda + j] = QabMat.get(i, j);
            }
        }

        double[] s = new double[2];

        int info = Lambda.lambda(nbLambda, 2, y, Qb, b, s);

        if (info == 0) {
            rtk.sol.ratio = s[0] > 0 ? (float) (s[1] / s[0]) : 0.0f;
            if (rtk.sol.ratio > 999.9f) rtk.sol.ratio = 999.9f;

            /* adjust AR ratio based on # of sats, unless minAR==maxAR (C rtkpos.c resamb_LAMBDA) */
            if (opt.thresar[5] != opt.thresar[6]) {
                int nb1 = nbLambda < 50 ? nbLambda : 50; /* poly only fitted for upto 50 sat pairs */
                double[] coeff = new double[3];
                /* generate poly coeffs based on nominal AR ratio */
                for (int i = 0; i < 3; i++) {
                    coeff[i] = AR_POLY_COEFFS[i][0];
                    for (int j = 1; j < 5; j++) {
                        coeff[i] = coeff[i] * opt.thresar[0] + AR_POLY_COEFFS[i][j];
                    }
                }
                /* generate adjusted AR ratio based on # of sat pairs */
                rtk.sol.thres = (float) coeff[0];
                for (int i = 1; i < 3; i++) {
                    rtk.sol.thres = (float) (rtk.sol.thres * 1.0 / (nb1 + 1.0) + coeff[i]);
                }
                rtk.sol.thres = (float) Math.min(Math.max(rtk.sol.thres, opt.thresar[5]), opt.thresar[6]);
            } else {
                rtk.sol.thres = (float) opt.thresar[0];
            }

            if (s[0] <= 0.0 || s[1] / s[0] >= rtk.sol.thres) {
                // [OPT-BOOTSTRAP] Bootstrapping联合判据
                if (rtk.rtkConfig.rtk.enableBootstrapping) {
                    if (!RtkOptimizationsBootstrap.validateFix(rtk, b, nbLambda)) {
                        rtk.sol.ratio = 0.0f;
                        rtk.diagBootstrapRejectCount++;
                        nb = 0;
                        return nb;
                    }
                }

                for (int i = 0; i < na; i++) {
                    rtk.xa[i] = rtk.x[i];
                    for (int j = 0; j < na; j++) {
                        rtk.Pa[i * na + j] = rtk.P[i * nx + j];
                    }
                }

                double[] biasFull = new double[nb];
                for (int i = 0; i < nbLambda; i++) {
                    double fixedVal = b[i * 2];
                    biasFull[enableAnchor ? freeMap[i] : i] = fixedVal;
                    y[i] -= fixedVal;
                }
                if (enableAnchor) {
                    for (int i = 0; i < anchorCount; i++) {
                        int idx = anchorMap[i];
                        biasFull[idx] = Math.round(yFull[idx]);
                    }
                }
                for (int i = 0; i < nb; i++) {
                    bias[i] = biasFull[i];
                }

                SimpleMatrix QbInvMat = MatrixUtil.createMatrix(Qb, nbLambda, nbLambda);
                java.util.Optional<SimpleMatrix> QbInvOpt = MatrixUtil.invertSafe(QbInvMat);
                if (QbInvOpt.isPresent()) {
                    SimpleMatrix QbInv = QbInvOpt.get();
                    SimpleMatrix yMat = MatrixUtil.createMatrix(y, nbLambda, 1);
                    SimpleMatrix QabInvMat = MatrixUtil.createMatrix(Qab, na, nbLambda);

                    SimpleMatrix dbMat = MatrixUtil.multiply(QbInv, yMat);
                    MatrixUtil.copyMatrix(dbMat, db);

                    SimpleMatrix xaMat = MatrixUtil.createMatrix(rtk.xa, na, 1);
                    SimpleMatrix xaNew = MatrixUtil.subtract(xaMat, MatrixUtil.multiply(QabInvMat, dbMat));
                    MatrixUtil.copyMatrix(xaNew, rtk.xa);

                    SimpleMatrix QQMat = MatrixUtil.multiply(QabInvMat, QbInv);
                    MatrixUtil.copyMatrix(QQMat, QQ);

                    SimpleMatrix PaMat = MatrixUtil.createMatrix(rtk.Pa, na, na);
                    SimpleMatrix PaNew = MatrixUtil.subtract(PaMat, MatrixUtil.multiply(QQMat, MatrixUtil.transpose(QabInvMat)));
                    MatrixUtil.copyMatrix(PaNew, rtk.Pa);

                    restamb(rtk, bias, nb, xa);
                } else {
                    nb = 0;
                }
            } else {
                nb = 0;
            }
        } else {
            nb = 0;
        }

        // [OPT-PARTIAL-AR] 部分模糊度固定
        if (nb <= 0 && rtk.rtkConfig.rtk.enablePartialAR) {
            int nbPar = RtkOptimizationsPartialAR.partialAmbFix(
                            rtk, bias, xa, ix, rtk.nb_ar, gps, glo, sbs);
            if (nbPar > 1) return nbPar;
        }

        return nb;
    }

    static void restamb(Rtk rtk, double[] bias, int nb, double[] xa) {
        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        for (int i = 0; i < rtk.nx; i++) xa[i] = rtk.x[i];
        for (int i = 0; i < rtk.na; i++) xa[i] = rtk.xa[i];

        int nv = 0;
        for (int m = 0; m < 6; m++) {
            for (int f = 0; f < nf; f++) {
                int[] index = new int[Constants.MAXSAT];
                int n = 0;
                for (int i = 0; i < Constants.MAXSAT; i++) {
                    if (!testSys(rtk.ssat[i].sys, m) || rtk.ssat[i].fix[f] != 2) {
                        continue;
                    }
                    index[n++] = IB(i + 1, f, opt);
                }
                if (n < 2) continue;

                xa[index[0]] = rtk.x[index[0]];
                for (int i = 1; i < n; i++) {
                    xa[index[i]] = xa[index[0]] - bias[nv++];
                }
            }
        }
    }

    private static int manage_amb_LAMBDA(Rtk rtk, double[] bias, double[] xa,
                                         int[] sat, int nf, int ns, Nav nav) {
        PrcOpt opt = rtk.opt;

        double posvar = 0.0;
        for (int i = 0; i < 3; i++) posvar += rtk.P[i * rtk.nx + i];
        posvar /= 3.0;

        boolean skip = opt.mode <= Constants.PMODE_DGPS || opt.modear == Constants.ARMODE_OFF ||
            opt.thresar[0] < 1.0 || posvar > opt.thresar[1];

        if (skip) {
            rtk.sol.ratio = 0.0f;
            rtk.sol.prev_ratio1 = 0.0f;
            rtk.sol.prev_ratio2 = 0.0f;
            rtk.nb_ar = 0;
            return 0;
        }

        int[] lockc = new int[Constants.NFREQ];
        int excsat = 0;
        if (rtk.sol.prev_ratio2 < rtk.sol.thres && rtk.nb_ar >= opt.mindropsats) {
            int i = 0;
            if (rtk.excsat != 0) {
                for (; i < ns; i++) {
                    if (rtk.excsat == sat[i]) {
                        i++;
                        break;
                    }
                }
                if (i >= ns) i = 0;
            }
            for (; i < ns; i++) {
                for (int f = 0; f < nf; f++) {
                    if (rtk.ssat[sat[i] - 1].vsat[f] != 0 && rtk.ssat[sat[i] - 1].lock[f] >= 0 &&
                        rtk.ssat[sat[i] - 1].azel[1] >= opt.elmin) {
                        excsat = sat[i];
                        break;
                    }
                }
                if (excsat != 0) break;
            }
            if (excsat != 0) {
                for (int f = 0; f < nf; f++) {
                    lockc[f] = rtk.ssat[excsat - 1].lock[f];
                    rtk.ssat[excsat - 1].lock[f] = -rtk.nb_ar;
                }
            }
            rtk.excsat = excsat;
        }

        int gps1 = 1;
        int glo1 = (opt.navsys & Constants.SYS_GLO) != 0 ?
                   ((opt.glomodear == Constants.GLO_ARMODE_FIXHOLD && rtk.holdambFlag == 0) ? 0 : 1) : 0;
        int sbas1 = (opt.navsys & Constants.SYS_GLO) != 0 ? glo1 :
                    ((opt.navsys & Constants.SYS_SBS) != 0 ? 1 : 0);

        int nb = resamb_LAMBDA(rtk, bias, xa, gps1, glo1, sbas1, nav);
        float ratio1 = rtk.sol.ratio;

        if (opt.arfilter != 0) {
            int rerun = 0;
            if (nb >= 0 && rtk.sol.prev_ratio2 >= rtk.sol.thres &&
                (rtk.sol.ratio < rtk.sol.thres ||
                 (rtk.sol.ratio < opt.thresar[0] * 1.1 && rtk.sol.ratio < rtk.sol.prev_ratio1 / 2.0f))) {
                int dly = 2;
                for (int i = 0; i < ns; i++) {
                    for (int f = 0; f < nf; f++) {
                        if (rtk.ssat[sat[i] - 1].fix[f] != 2) continue;
                        if (rtk.ssat[sat[i] - 1].lock[f] == 0) {
                            rtk.ssat[sat[i] - 1].lock[f] = -opt.minlock - dly;
                            dly += 2;
                            rerun = 1;
                        }
                    }
                }
            }
            if (rerun != 0) {
                nb = resamb_LAMBDA(rtk, bias, xa, gps1, glo1, sbas1, nav);
            }
        }

        rtk.sol.prev_ratio1 = ratio1;

        if ((opt.navsys & Constants.SYS_GLO) != 0 &&
            opt.glomodear == Constants.GLO_ARMODE_FIXHOLD &&
            rtk.sol.ratio < rtk.sol.thres) {
            int glo2 = 0;
            int sbas2 = 0;
            int gps2 = opt.gpsmodear == 0 && rtk.sol.ratio >= rtk.sol.thres ? 0 : 1;
            if (glo1 != glo2 || gps1 != gps2) {
                nb = resamb_LAMBDA(rtk, bias, xa, gps2, glo2, sbas2, nav);
            }
        }

        if (excsat != 0 && rtk.sol.ratio < rtk.sol.thres &&
            rtk.sol.ratio < 1.5f * rtk.sol.prev_ratio2) {
            for (int f = 0; f < nf; f++) {
                rtk.ssat[excsat - 1].lock[f] = lockc[f];
            }
        }

        rtk.sol.prev_ratio1 = ratio1 > 0 ? ratio1 : rtk.sol.ratio;
        rtk.sol.prev_ratio2 = rtk.sol.ratio;

        return nb;
    }

    private static boolean valpos(Rtk rtk, double[] v, double[] R, int[] vflg,
                                  int nv, double thres) {
        double fact = thres * thres;
        for (int i = 0; i < nv; i++) {
            if (v[i] * v[i] <= fact * R[i * nv + i]) continue;
        }
        return true;
    }

    private static void holdamb(Rtk rtk, double[] xa) {
        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;
        int na = rtk.na;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nb = nx - na;
        boolean enableAnchor = rtk.rtkConfig.rtk.enableAmbAnchor;

        if (enableAnchor && rtk.sol.stat == Constants.SOLQ_FIX) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                for (int f = 0; f < nf; f++) {
                    int globalIdx = i * nf + f;
                    if (rtk.ssat[i].fix[f] > 0) {
                        rtk.ambAnchorCount[globalIdx]++;
                        if (rtk.ambAnchorCount[globalIdx] >= rtk.rtkConfig.rtk.ambAnchorMinFixCount) {
                            rtk.ambAnchored[globalIdx] = true;
                        }
                    }
                }
            }
            rtk.diagAmbAnchorAttemptCount++;
        }

        double[] v = new double[nb];
        double[] H = new double[nb * nx];
        int nv = 0;

        for (int m = 0; m < 6; m++) {
            for (int f = 0; f < nf; f++) {
                int[] index = new int[Constants.MAXSAT];
                int n = 0;
                for (int i = 0; i < Constants.MAXSAT; i++) {
                    if (!testSys(rtk.ssat[i].sys, m) || rtk.ssat[i].fix[f] != 2 ||
                        rtk.ssat[i].azel[1] < opt.elmaskhold) {
                        continue;
                    }
                    index[n++] = IB(i + 1, f, opt);
                    rtk.ssat[i].fix[f] = 3;
                }
                for (int i = 1; i < n; i++) {
                    v[nv] = (xa[index[0]] - xa[index[i]]) - (rtk.x[index[0]] - rtk.x[index[i]]);
                    H[nv * nx + index[0]] = 1.0;
                    H[nv * nx + index[i]] = -1.0;
                    nv++;
                }
            }
        }

        if (opt.modear == Constants.ARMODE_FIXHOLD && nv < opt.minholdsats) {
            return;
        }

        rtk.holdambFlag = 1;

        double[] Rh = new double[nv * nv];
        for (int i = 0; i < nv; i++) Rh[i * nv + i] = opt.varholdamb;

        if (nv > 0) {
            KalmanFilter.update(rtk.x, rtk.P, H, v, Rh, nx, nv, false);
        }

        if (enableAnchor && rtk.sol.stat != Constants.SOLQ_FIX) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                for (int f = 0; f < nf; f++) {
                    if (rtk.ssat[i].fix[f] <= 0) {
                        int globalIdx = i * nf + f;
                        rtk.ambAnchorCount[globalIdx] = 0;
                    }
                }
            }
        }

        if (opt.glomodear != Constants.GLO_ARMODE_FIXHOLD) return;

        for (int f = 0; f < nf; f++) {
            int refI = -1;
            for (int j = 0; j < Constants.MAXSAT; j++) {
                if (testSys(rtk.ssat[j].sys, 1) && rtk.ssat[j].vsat[f] != 0 && rtk.ssat[j].lock[f] >= 0) {
                    if (refI < 0) {
                        refI = j;
                    } else {
                        double dd = rtk.x[IB(j + 1, f, opt)] - rtk.x[IB(refI + 1, f, opt)];
                        dd = opt.gainholdamb * (dd - Math.round(dd));
                        rtk.x[IB(j + 1, f, opt)] -= dd;
                        rtk.ssat[j].icbias[f] += dd;
                    }
                }
            }
        }

        for (int f = 0; f < nf; f++) {
            int refI = -1;
            for (int j = 0; j < Constants.MAXSAT; j++) {
                if (testSys(rtk.ssat[j].sys, 0) && rtk.ssat[j].vsat[f] != 0 && rtk.ssat[j].lock[f] >= 0) {
                    if (refI < 0) {
                        refI = j;
                    } else {
                        if (rtk.ssat[j].sys != Constants.SYS_SBS) continue;
                        double dd = rtk.x[IB(j + 1, f, opt)] - rtk.x[IB(refI + 1, f, opt)];
                        dd = opt.gainholdamb * (dd - Math.round(dd));
                        rtk.x[IB(j + 1, f, opt)] -= dd;
                        rtk.ssat[j].icbias[f] += dd;
                    }
                }
            }
        }
    }

    private static void initx(double[] x, double[] P, int nx, double xi, double var, int i) {
        x[i] = xi;
        for (int j = 0; j < nx; j++) {
            P[i * nx + j] = 0.0;
            P[j * nx + i] = 0.0;
        }
        P[i * nx + i] = var;
    }

    public static double SQR(double x) {
        return x * x;
    }

    static boolean testSys(int sys, int m) {
        switch (sys) {
            case Constants.SYS_GPS: return m == 0;
            case Constants.SYS_SBS: return m == 0;
            case Constants.SYS_GLO: return m == 1;
            case Constants.SYS_GAL: return m == 2;
            case Constants.SYS_CMP: return m == 3;
            case Constants.SYS_QZS: return m == 4;
            case Constants.SYS_IRN: return m == 5;
            default: return false;
        }
    }

    private static boolean matinv(double[] a, int n) {
        double[] w = new double[n];
        int[] idx = new int[n];

        for (int i = 0; i < n; i++) idx[i] = i;

        for (int k = 0; k < n; k++) {
            double maxVal = Math.abs(a[k * n + k]);
            int maxIdx = k;
            for (int i = k + 1; i < n; i++) {
                if (Math.abs(a[i * n + k]) > maxVal) {
                    maxVal = Math.abs(a[i * n + k]);
                    maxIdx = i;
                }
            }
            if (maxVal < 1E-20) return true;

            if (maxIdx != k) {
                for (int j = 0; j < n; j++) {
                    double tmp = a[k * n + j];
                    a[k * n + j] = a[maxIdx * n + j];
                    a[maxIdx * n + j] = tmp;
                }
                int tmpIdx = idx[k];
                idx[k] = idx[maxIdx];
                idx[maxIdx] = tmpIdx;
            }

            double piv = a[k * n + k];
            a[k * n + k] = 1.0;
            for (int j = 0; j < n; j++) a[k * n + j] /= piv;

            for (int i = 0; i < n; i++) {
                if (i == k) continue;
                double factor = a[i * n + k];
                a[i * n + k] = 0.0;
                for (int j = 0; j < n; j++) {
                    a[i * n + j] -= factor * a[k * n + j];
                }
            }
        }

        double[] tmp = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tmp[i * n + j] = a[i * n + j];
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[idx[i] * n + j] = tmp[i * n + j];
            }
        }

        return false;
    }

    private static void matmul(String tr, int m, int n, int k,
                               double[] A, double[] B, double[] C) {
        boolean trA = tr.charAt(0) == 'T' || tr.charAt(0) == 't';
        boolean trB = tr.length() > 1 && (tr.charAt(1) == 'T' || tr.charAt(1) == 't');

        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int l = 0; l < k; l++) {
                    double a = trA ? A[l * m + i] : A[i * k + l];
                    double b = trB ? B[j * k + l] : B[l * n + j];
                    sum += a * b;
                }
                C[i * n + j] = sum;
            }
        }
    }

    private static void matmulm(String tr, int m, int n, int k,
                                double[] A, double[] B, double[] C) {
        double[] tmp = new double[m * n];
        matmul(tr, m, n, k, A, B, tmp);
        for (int i = 0; i < m * n; i++) C[i] -= tmp[i];
    }

    private static double sdobs(Obsd[] obs, int i, int j, int f) {
        double val1, val2;
        if (f < Constants.NFREQ) {
            val1 = obs[i].L[f];
            val2 = obs[j].L[f];
        } else {
            val1 = obs[i].P[f - Constants.NFREQ];
            val2 = obs[j].P[f - Constants.NFREQ];
        }
        if (val1 == 0.0 || val2 == 0.0) return 0.0;
        return val1 - val2;
    }

    public static double gfobs(Obsd[] obs, int i, int j, int k, Nav nav) {
        double freq1 = SatUtils.sat2freq(obs[i].sat, obs[i].code[0], nav);
        double freq2 = SatUtils.sat2freq(obs[i].sat, obs[i].code[k], nav);
        double L1 = sdobs(obs, i, j, 0);
        double L2 = sdobs(obs, i, j, k);
        if (freq1 == 0.0 || freq2 == 0.0 || L1 == 0.0 || L2 == 0.0) return 0.0;
        return L1 * Constants.CLIGHT / freq1 - L2 * Constants.CLIGHT / freq2;
    }

    public static void detslpLl(RtkState rtk, Obsd[] obs, int i, int rcv) {
        int sat = obs[i].sat;
        int maxF = rtk.opt().nf;
        if (rtk.opt().ionoopt == Constants.IONOOPT_IFLC && iflcNegotiationActive(rtk)
                && rtk.ssat()[sat - 1].iflcSlot[0] > 0) {
            if (rtk.ssat()[sat - 1].iflcSlot[0] + 1 > maxF) maxF = rtk.ssat()[sat - 1].iflcSlot[0] + 1;
            if (rtk.ssat()[sat - 1].iflcSlot[1] + 1 > maxF) maxF = rtk.ssat()[sat - 1].iflcSlot[1] + 1;
        }
        for (int f = 0; f < maxF; f++) {
            if ((obs[i].L[f] == 0.0 && obs[i].LLI[f] == 0) ||
                    Math.abs(TimeSystem.timediff(obs[i].time, rtk.ssat()[sat - 1].pt[rcv - 1][f])) < Constants.DTTOL) {
                continue;
            }
            int LLI;
            if (rcv == 1) { LLI = rtk.ssat()[sat - 1].slip[f] & 0x03; }
            else { LLI = (rtk.ssat()[sat - 1].slip[f] >> 2) & 0x03; }
            int slip;
            if (rtk.tt() >= 0.0) { slip = obs[i].LLI[f]; }
            else { slip = LLI; }
            if (((LLI & Constants.LLI_HALFC) != 0 && (obs[i].LLI[f] & Constants.LLI_HALFC) == 0) ||
                    ((LLI & Constants.LLI_HALFC) == 0 && (obs[i].LLI[f] & Constants.LLI_HALFC) != 0)) {
                slip |= Constants.LLI_SLIP;
            }
            if (rcv == 1) { rtk.ssat()[sat - 1].slip[f] = (rtk.ssat()[sat - 1].slip[f] & 0xFC) | (obs[i].LLI[f] & 0x03); }
            else { rtk.ssat()[sat - 1].slip[f] = (rtk.ssat()[sat - 1].slip[f] & 0xF3) | ((obs[i].LLI[f] & 0x03) << 2); }
            rtk.ssat()[sat - 1].slip[f] |= slip;
            rtk.ssat()[sat - 1].half[f] = (obs[i].LLI[f] & Constants.LLI_HALFC) != 0 ? 0 : 1;
        }
    }

    public static void detslpGf(RtkState rtk, Obsd[] obs, int i, int j, Nav nav) {
        int sat = obs[i].sat;
        if (rtk.opt().thresslip == 0) return;
        for (int k = 0; k < rtk.opt().nf; k++) {
            if ((rtk.ssat()[sat - 1].slip[k] & Constants.LLI_SLIP) != 0) return;
        }
        if (rtk.opt().ionoopt == Constants.IONOOPT_IFLC && iflcNegotiationActive(rtk)
                && rtk.ssat()[sat - 1].iflcSlot[0] > 0) {
            int s1 = rtk.ssat()[sat - 1].iflcSlot[0];
            int s2 = rtk.ssat()[sat - 1].iflcSlot[1];
            if ((rtk.ssat()[sat - 1].slip[s1] & Constants.LLI_SLIP) != 0
                    || (rtk.ssat()[sat - 1].slip[s2] & Constants.LLI_SLIP) != 0) return;
            double gf1 = gfobsPair(obs, i, j, s1, s2, nav);
            if (gf1 == 0.0) return;
            double gf0 = rtk.ssat()[sat - 1].gf[0];
            rtk.ssat()[sat - 1].gf[0] = gf1;
            if (gf0 != 0.0 && Math.abs(gf1 - gf0) > rtk.opt().thresslip) {
                rtk.ssat()[sat - 1].slip[0] |= Constants.LLI_SLIP;
                rtk.ssat()[sat - 1].slip[s1] |= Constants.LLI_SLIP;
                rtk.ssat()[sat - 1].slip[s2] |= Constants.LLI_SLIP;
            }
            return;
        }
        for (int k = 1; k < rtk.opt().nf; k++) {
            double gf1 = gfobs(obs, i, j, k, nav);
            if (gf1 == 0.0) continue;
            double gf0 = rtk.ssat()[sat - 1].gf[k - 1];
            rtk.ssat()[sat - 1].gf[k - 1] = gf1;
            if (gf0 != 0.0 && Math.abs(gf1 - gf0) > rtk.opt().thresslip) {
                rtk.ssat()[sat - 1].slip[0] |= Constants.LLI_SLIP;
                rtk.ssat()[sat - 1].slip[k] |= Constants.LLI_SLIP;
            }
        }
    }

    /** [JAVA-ONLY] 两频率槽位对的geometry-free单差组合（米） */
    public static double gfobsPair(Obsd[] obs, int i, int j, int s1, int s2, Nav nav) {
        double freq1 = SatUtils.sat2freq(obs[i].sat, obs[i].code[s1], nav);
        double freq2 = SatUtils.sat2freq(obs[i].sat, obs[i].code[s2], nav);
        double L1 = obs[i].L[s1] - obs[j].L[s1];
        double L2 = obs[i].L[s2] - obs[j].L[s2];
        if (freq1 == 0.0 || freq2 == 0.0 || L1 == 0.0 || L2 == 0.0) return 0.0;
        return L1 * Constants.CLIGHT / freq1 - L2 * Constants.CLIGHT / freq2;
    }

    public static void detslpCode(RtkState rtk, Obsd[] obs, int i, int rcv) {
        int sat = obs[i].sat;
        int maxF = rtk.opt().nf;
        if (rtk.opt().ionoopt == Constants.IONOOPT_IFLC && iflcNegotiationActive(rtk)
                && rtk.ssat()[sat - 1].iflcSlot[0] > 0) {
            if (rtk.ssat()[sat - 1].iflcSlot[0] + 1 > maxF) maxF = rtk.ssat()[sat - 1].iflcSlot[0] + 1;
            if (rtk.ssat()[sat - 1].iflcSlot[1] + 1 > maxF) maxF = rtk.ssat()[sat - 1].iflcSlot[1] + 1;
        }
        for (int f = 0; f < maxF; f++) {
            int code = obs[i].code[f];
            if (code == Constants.CODE_NONE) continue;
            int ccode = rtk.ssat()[sat - 1].code[f][rcv - 1];
            if (code != ccode) {
                rtk.ssat()[sat - 1].code[f][rcv - 1] = code;
                if (ccode != Constants.CODE_NONE) {
                    rtk.ssat()[sat - 1].slip[f] |= Constants.LLI_SLIP;
                }
            }
        }
    }

    public static void detslpDop(RtkState rtk, Obsd[] obs, int[] ix, int ns, int rcv, Nav nav) {
        if (rtk.opt().thresdop <= 0) return;
        double[] dopdif = new double[Constants.MAXSAT * Constants.NFREQ];
        double[] tt = new double[Constants.MAXSAT * Constants.NFREQ];
        int ndop = 0;
        double meanDop = 0.0;
        for (int i = 0; i < ns; i++) {
            int ii = ix[i];
            int sat = obs[ii].sat;
            for (int f = 0; f < rtk.opt().nf; f++) {
                int idx = i * rtk.opt().nf + f;
                dopdif[idx] = 0.0;
                tt[idx] = 0.0;
                if (obs[ii].L[f] == 0.0 || obs[ii].D[f] == 0.0 || rtk.ssat()[sat - 1].ph[rcv - 1][f] == 0.0) continue;
                double dt = TimeSystem.timediff(obs[ii].time, rtk.ssat()[sat - 1].pt[rcv - 1][f]);
                tt[idx] = dt;
                if (Math.abs(dt) < Constants.DTTOL) continue;
                double dph = (obs[ii].L[f] - rtk.ssat()[sat - 1].ph[rcv - 1][f]) / dt;
                double dpt = -obs[ii].D[f];
                dopdif[idx] = dph - dpt;
                if (Math.abs(dopdif[idx]) < 3 * rtk.opt().thresdop) { meanDop += dopdif[idx]; ndop++; }
            }
        }
        if (ndop == 0) return;
        meanDop /= ndop;
        for (int i = 0; i < ns; i++) {
            int sat = obs[ix[i]].sat;
            for (int f = 0; f < rtk.opt().nf; f++) {
                int idx = i * rtk.opt().nf + f;
                if (dopdif[idx] == 0.0) continue;
                if (Math.abs(dopdif[idx] - meanDop) > rtk.opt().thresdop) {
                    rtk.ssat()[sat - 1].slip[f] |= Constants.LLI_SLIP;
                }
            }
        }
    }

    /**
     * [Java扩展] 计算诊断数据：hPos等效设计矩阵和新息向量摘要。
     * 由diagMask控制，未启用的项不计算，零开销。
     *
     * <p>hPos = (H_pos^T W H_pos)^{-1} H_pos^T W，其中 H_pos = H[:,0:3]，W = diag(R)^{-1}</p>
     * <p>innovRms = sqrt(v^T R^{-1} v / nv)，innovMax = max(|v_i| / sqrt(R_ii))</p>
     */
    private static void computeDiagData(Rtk rtk, PrcOpt opt,
                                        double[] v, double[] H, double[] R, int[] vflg,
                                        int nv, int nx) {
        int diagMask = opt.diagMask;
        if (diagMask == 0) return;

        if ((diagMask & PrcOpt.DIAG_HPOS) != 0 && nv >= 4 && nx >= 3) {
            try {
                double[] hPos = computeHPos(H, R, nv, nx);
                rtk.sol.hPos = hPos;
            } catch (Exception e) {
                LOG.debug("computeHPos failed: {}", e.getMessage());
                rtk.sol.hPos = null;
            }
        }

        if ((diagMask & PrcOpt.DIAG_INNOVATION) != 0 && nv > 0) {
            try {
                double vRv = 0.0;
                double maxNorm = 0.0;
                for (int i = 0; i < nv; i++) {
                    double ri = R[i * nv + i];
                    if (ri <= 0.0) continue;
                    double wi = 1.0 / ri;
                    vRv += v[i] * v[i] * wi;
                    double normI = Math.abs(v[i]) / Math.sqrt(ri);
                    if (normI > maxNorm) maxNorm = normI;
                }
                rtk.sol.innovRms = Math.sqrt(Math.max(vRv / nv, 0.0));
                rtk.sol.innovMax = maxNorm;
                rtk.sol.ddObsCount = nv;
            } catch (Exception e) {
                LOG.debug("computeInnovation failed: {}", e.getMessage());
                rtk.sol.innovRms = Double.NaN;
                rtk.sol.innovMax = Double.NaN;
                rtk.sol.ddObsCount = 0;
            }
        }
    }

    /**
     * [Java扩展] 从H[nv×nx]和R[nv×nv]计算位置分量等效设计矩阵hPos[9]。
     *
     * <p>hPos = N^{-1} × H_pos^T × W，其中：</p>
     * <ul>
     *   <li>H_pos = H[:, 0:3]（nv×3位置分量子矩阵）</li>
     *   <li>W = diag(R)^{-1}（对角加权）</li>
     *   <li>N = H_pos^T × W × H_pos（3×3法方程）</li>
     * </ul>
     *
     * @param H  设计矩阵（行优先，nv×nx）
     * @param R  观测噪声矩阵（行优先，nv×nv）
     * @param nv 双差观测数
     * @param nx 状态向量维度
     * @return hPos[3*nv]（3×nv行优先），null如果计算失败
     */
    private static double[] computeHPos(double[] H, double[] R, int nv, int nx) {
        double[] hPos = new double[3 * nv];

        double[] HposT_W = new double[3 * nv];
        for (int j = 0; j < nv; j++) {
            double rjj = R[j * nv + j];
            if (rjj <= 0.0) return null;
            double wj = 1.0 / rjj;
            for (int col = 0; col < 3; col++) {
                HposT_W[col * nv + j] = H[j * nx + col] * wj;
            }
        }

        double[] N = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < nv; k++) {
                    sum += HposT_W[i * nv + k] * H[k * nx + j];
                }
                N[i * 3 + j] = sum;
                N[j * 3 + i] = sum;
            }
        }

        double det = N[0] * (N[4] * N[8] - N[5] * N[5])
                   - N[1] * (N[1] * N[8] - N[2] * N[5])
                   + N[2] * (N[1] * N[5] - N[2] * N[4]);
        if (Math.abs(det) < 1e-30) return null;

        double[] NInv = new double[9];
        NInv[0] = (N[4] * N[8] - N[5] * N[5]) / det;
        NInv[1] = (N[2] * N[5] - N[1] * N[8]) / det;
        NInv[2] = (N[1] * N[5] - N[2] * N[4]) / det;
        NInv[3] = NInv[1];
        NInv[4] = (N[0] * N[8] - N[2] * N[2]) / det;
        NInv[5] = (N[1] * N[2] - N[0] * N[5]) / det;
        NInv[6] = NInv[2];
        NInv[7] = NInv[5];
        NInv[8] = (N[0] * N[4] - N[1] * N[1]) / det;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < nv; j++) {
                double sum = 0.0;
                for (int k = 0; k < 3; k++) {
                    sum += NInv[i * 3 + k] * HposT_W[k * nv + j];
                }
                hPos[i * nv + j] = sum;
            }
        }

        return hPos;
    }

    public static final class RtkWorkBuffer {
        // Fixed-size arrays (MAXSAT/MAXOBS sized, allocated once)
        double[] rs;         // MAXOBS * 6
        double[] dts;        // MAXOBS * 2
        double[] vare;       // MAXOBS
        int[] svh;           // MAXOBS
        double[] azel;       // MAXSAT * 2
        double[] y;          // NFREQ * 2 * MAXOBS
        double[] e;          // 3 * MAXOBS
        double[] freq;       // NFREQ * MAXOBS
        int[] sat;           // MAXSAT
        int[] iu;            // MAXSAT
        int[] ir;            // MAXSAT
        double[] rr_rover;   // 3
        double[] bias_arr;   // NX_RTK (max)
        double[] xa_arr;     // NX_RTK (max)

        // Variable-size arrays (lazy resize)
        double[] xp, Pp;
        double[] v, H, R;
        int[] vflg;
        double[] xa, bias;

        private void ensureInit() {
            if (rs == null) {
                rs = new double[Constants.MAXOBS * 6];
                dts = new double[Constants.MAXOBS * 2];
                vare = new double[Constants.MAXOBS];
                svh = new int[Constants.MAXOBS];
                azel = new double[Constants.MAXSAT * 2];
                y = new double[Constants.NFREQ * 2 * Constants.MAXOBS];
                e = new double[3 * Constants.MAXOBS];
                freq = new double[Constants.NFREQ * Constants.MAXOBS];
                sat = new int[Constants.MAXSAT];
                iu = new int[Constants.MAXSAT];
                ir = new int[Constants.MAXSAT];
                rr_rover = new double[3];
            }
        }

        void ensureState(int nx) {
            ensureInit();
            if (xp == null || xp.length < nx) {
                xp = new double[nx];
                xa = new double[nx];
                bias = new double[nx];
                bias_arr = new double[nx];
                xa_arr = new double[nx];
            }
            if (Pp == null || Pp.length < nx * nx) {
                Pp = new double[nx * nx];
            }
        }

        void ensureObs(int nx, int ny) {
            ensureState(nx);
            if (v == null || v.length < ny) {
                v = new double[ny];
                vflg = new int[ny];
            }
            if (H == null || H.length < nx * ny) {
                H = new double[nx * ny];
            }
            if (R == null || R.length < ny * ny) {
                R = new double[ny * ny];
            }
        }
    }
}