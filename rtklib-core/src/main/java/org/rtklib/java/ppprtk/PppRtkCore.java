package org.rtklib.java.ppprtk;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;

import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.ionosphere.SbasCorrection;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.ppp.PppOptimizations;
import org.rtklib.java.ppp.PppOsbModel;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PppRtkCore {
    private PppRtkCore() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppRtkCore.class);
    /** 临时接入诊断：设置环境变量 PPP_DEBUG_REJ=1 时输出逐星剔除原因统计 */
    private static final boolean DBG_REJ = System.getenv("PPP_DEBUG_REJ") != null;
    /** REJ 诊断输出的最大历元号（PPP_DEBUG_REJ_MAXEPOCH，缺省 3） */
    private static final int DBG_REJ_MAXEPOCH;
    static {
        int m = 3;
        String v = System.getenv("PPP_DEBUG_REJ_MAXEPOCH");
        if (v != null) {
            try { m = Integer.parseInt(v.trim()); } catch (NumberFormatException ignored) { }
        }
        DBG_REJ_MAXEPOCH = m;
    }
    private static final int MAX_ITER = 8;
    private static final int MIN_NSAT_SOL = 4;

    private static final double VAR_POS = PppCore.VAR_POS;
    private static final double VAR_VEL = PppCore.VAR_VEL;
    private static final double VAR_CLK = PppCore.VAR_CLK;
    private static final double VAR_ZTD = PppCore.VAR_ZTD;
    private static final double VAR_IONO = PppCore.VAR_IONO;
    private static final double VAR_BIAS = PppCore.VAR_BIAS;
    private static final double VAR_GRA = PppCore.VAR_GRA;
    private static final double VAR_GLO_IFB = PppCore.VAR_GLO_IFB;

    public static int NF(PrcOpt opt) {
        return PppCore.NF(opt);
    }

    public static int NP(PrcOpt opt) {
        return PppCore.NP(opt);
    }

    public static int NC() {
        return PppCore.NC();
    }

    public static int NT(PrcOpt opt) {
        return PppCore.NT(opt);
    }

    public static int NI(PrcOpt opt) {
        return PppCore.NI(opt);
    }

    public static int NB(PrcOpt opt) {
        return PppCore.NB(opt);
    }

    public static int ppprtknx(PrcOpt opt) {
        return PppCore.pppnx(opt);
    }

    public static int IC(int s, PrcOpt opt) {
        return PppCore.IC(s, opt);
    }

    public static int IT(PrcOpt opt) {
        return PppCore.IT(opt);
    }

    public static int II(int sat, PrcOpt opt) {
        return PppCore.II(sat, opt);
    }

    public static int IB(int sat, int f, PrcOpt opt) {
        return PppCore.IB(sat, f, opt);
    }

    public static void ppprtkos(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        PrcOpt opt = rtk.opt;
        RtkConfig cfg = rtk.rtkConfig;
        int nx = ppprtknx(opt);
        double ssrMaxAge = cfg != null ? cfg.ppp.ssrMaxAge : 60.0;
        boolean hasSsr = SsrCorrector.hasSsrData(nav.ssr);

        rtk.epoch++;

        if (rtk.epoch <= 3) {
            int[] owk = new int[1];
            double otow = TimeSystem.time2gpst(obs[0].time, owk);
            int[] ewk = new int[1];
            double etow = nav.eph[0] != null ? TimeSystem.time2gpst(nav.eph[0].toe, ewk) : -1;
            LOG.info("ppprtkos ENTRY epoch={} n={} nx={} hasSsr={} ionoopt={} tropopt={} sateph={} obsWeek={} obsTow={} eph0Week={} eph0Toe={}",
                rtk.epoch, n, nx, hasSsr, opt.ionoopt, opt.tropopt, opt.sateph,
                owk[0], String.format("%.1f", otow), ewk[0], String.format("%.1f", etow));
        }

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < opt.nf; j++) {
                rtk.ssat[i].fix[j] = 0;
            }
        }
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            for (int j = 0; j < opt.nf; j++) {
                rtk.ssat[obs[i].sat - 1].snrRover[j] = obs[i].SNR[j];
                rtk.ssat[obs[i].sat - 1].snrBase[j] = 0;
            }
            /* [ALIGN-PPPCore] sync ssat.code from obs.code (PppCore.pppRes L781-785).
             * PppRtkCore.ppprtkos() previously had NO code propagation, leaving ssat.code all zero
             * → PppAmbFix/PppRtkAmbFix code0=code1=0 → osbL1/osbP1=0 → WL sig blows up → AR fails → KF collapses. */
            for (int fc = 0; fc < Constants.NFREQ; fc++) {
                if (obs[i].code[fc] != 0) {
                    rtk.ssat[obs[i].sat - 1].code[fc][0] = obs[i].code[fc];
                }
            }
        }

        if (rtk.nx == 0 || rtk.nx != nx) {
            rtk.nx = nx;
            rtk.x = new double[nx];
            rtk.P = new double[nx * nx];
            rtk.xa = new double[nx];
            rtk.Pa = new double[nx * nx];
            for (int i = 0; i < 3 && i < nx; i++) {
                rtk.x[i] = rtk.sol.rr[i];
            }
        }

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] var = new double[n];
        int[] svh = new int[n];
        double[] azel = new double[n * 2];
        int[] exc = new int[n];

        udstate(rtk, obs, n, nav, nx);

        EphModel.satposs(obs[0].time, obs, n, nav, rs, dts, var, svh, opt.sateph);

        if (hasSsr) {
            SsrCorrector.applyOrbitCorrection(nav.ssr, rs, obs, n);
            SsrCorrector.applyClockCorrection(nav.ssr, dts, obs, n);
            SsrCorrector.applyHrClockCorrection(nav.ssr, dts, obs, n);
        }

        // [FIX-ALG-038] SSR validity check intentionally skipped for now.
        // In PPP-RTK, SSR corrections are optional enhancements; broadcast ephemeris
        // fallback should always be allowed. applyOrbitCorrection already skips
        // satellites without SSR data. The original unconditional exclusion prevented
        // BDS-2 satellites without SSR from participating at all.
        // TODO: Re-implement conditional exclusion (expired SSR → exclude, no SSR → fallback)
        // after verifying SSR data coverage and age for all satellite systems.

        if (opt.posopt[3] != 0) {
            PppCore.testeclipse(obs, n, nav, rs);
        }

        int maxnv = n * NF(opt) * 2 + Constants.MAXSAT + 3;
        double[] xp = new double[nx];
        double[] Pp = new double[nx * nx];
        double[] v = new double[maxnv];
        double[] H = new double[nx * maxnv];
        double[] R = new double[maxnv * maxnv];

        int stat = Constants.SOLQ_SINGLE;

        for (int iter = 0; iter < MAX_ITER; iter++) {
            System.arraycopy(rtk.x, 0, xp, 0, nx);
            System.arraycopy(rtk.P, 0, Pp, 0, nx * nx);

            int nv = ppprtkRes(0, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, v, H, R, azel, nx);

            if (rtk.epoch <= 3 || rtk.epoch % 50 == 0) {
                int nSatUsed = 0;
                for (int ii = 0; ii < Constants.MAXSAT; ii++) {
                    if (rtk.ssat[ii].vsat[0] != 0) nSatUsed++;
                }
                LOG.info("ppprtkos: epoch={} iter={} nv={} nSatUsed={} ionoopt={}", 
                    rtk.epoch, iter, nv, nSatUsed, opt.ionoopt);
            }

            if (nv == 0) {
                LOG.warn("ppprtkos: epoch={} iter={} nv=0 ionoopt={}", rtk.epoch, iter, opt.ionoopt);
                break;
            }

            int info = KalmanFilter.update(xp, Pp, H, v, R, nx, nv);
            if (info != 0) {
                LOG.debug("ppprtkos: epoch={} iter={} KF update failed info={}", rtk.epoch, iter, info);
                break;
            }

            if (rtk.epoch <= 3 || rtk.epoch % 50 == 0) {
                double[] posBefore = new double[3], posAfter = new double[3];
                double[] rrBefore = new double[3];
                for (int ii = 0; ii < 3; ii++) rrBefore[ii] = rtk.x[ii];
                CoordTransform.ecef2pos(rrBefore, posBefore);
                CoordTransform.ecef2pos(xp, posAfter);
                LOG.info("ppprtkos KF: epoch={} iter={} posBefore=({},{},{}) posAfter=({},{},{})",
                    rtk.epoch, iter,
                    String.format("%.8f", posBefore[0]*Constants.R2D), String.format("%.8f", posBefore[1]*Constants.R2D), String.format("%.3f", posBefore[2]),
                    String.format("%.8f", posAfter[0]*Constants.R2D), String.format("%.8f", posAfter[1]*Constants.R2D), String.format("%.3f", posAfter[2]));
            }

            // Always save the latest state (like PppCore does)
            System.arraycopy(xp, 0, rtk.x, 0, nx);
            System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);

            int postStat = ppprtkRes(iter + 1, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, null, null, null, azel, nx);
            if (postStat != 0) {
                stat = Constants.SOLQ_PPP;
                break;
            }
        }

        if (stat == Constants.SOLQ_PPP) {
            if (cfg != null && cfg.pppRtk.enableAR) {
                PppRtkAmbFix.ppprtkAmbFix(rtk, nav);
            }
            updateStat(rtk, obs, n, stat, nx);
        }
    }

    private static void udstate(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        PrcOpt opt = rtk.opt;
        double tt = rtk.tt;

        for (int i = 0; i < Constants.MAXSAT; i++) rtk.ssat[i].vs = 0;
        for (int i = 0; i < n; i++) {
            int sat = obs[i].sat;
            if (sat >= 1 && sat <= Constants.MAXSAT) {
                int sys = SatUtils.satsys(sat, null);
                if ((sys & opt.navsys) != 0) rtk.ssat[sat - 1].vs = 1;
            }
        }
        udpos(rtk, nx);
        udclk(rtk, nx);
        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG
            || (opt.tropopt == Constants.TROPOPT_SSR
                && (rtk.rtkConfig == null || !rtk.rtkConfig.ppp.enableSsrTropDirect))) {
            udtrop(rtk, nx);
        }
        if (opt.ionoopt == Constants.IONOOPT_EST || opt.ionoopt == Constants.IONOOPT_SSR) {
            udiono(rtk, obs, n, nav, nx);
        }
        udbias(rtk, obs, n, nav, nx);
    }

    private static void udpos(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        double norm = 0.0;
        for (int i = 0; i < 3; i++) norm += x[i] * x[i];
        norm = Math.sqrt(norm);

        if (norm <= 0.0) {
            for (int i = 0; i < 3; i++) {
                PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_POS, i);
            }
            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_VEL, i);
                for (int i = 6; i < 9; i++) PppCore.initx(x, P, nx, 1E-6, PppCore.VAR_ACC, i);
            }
            return;
        }

        if (opt.mode == Constants.PMODE_PPP_STATIC) {
            for (int i = 0; i < 3; i++) {
                P[i * (nx + 1)] += PppCore.SQR(opt.prn[5]) * Math.abs(rtk.tt);
            }
            return;
        }

        if (opt.dynamics == 0) {
            if (P[0] <= 0.0) {
                for (int i = 0; i < 3; i++) {
                    PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_POS, i);
                }
            } else {
                for (int i = 0; i < 3; i++) {
                    P[i * (nx + 1)] += PppCore.SQR(opt.prn[5]) * Math.abs(rtk.tt);
                }
            }
            return;
        }

        double var = 0.0;
        for (int i = 0; i < 3; i++) var += P[i * (nx + 1)];
        var /= 3.0;

        if (var > VAR_POS) {
            for (int i = 0; i < 3; i++) PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_POS, i);
            for (int i = 3; i < 6; i++) PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_VEL, i);
            for (int i = 6; i < 9; i++) PppCore.initx(x, P, nx, 1E-6, PppCore.VAR_ACC, i);
            return;
        }

        if (rtk.tt != 0.0) {
            for (int i = 0; i < 3; i++) {
                x[i] += rtk.tt * x[i + 3] + 0.5 * rtk.tt * rtk.tt * x[i + 6];
            }
            for (int i = 3; i < 6; i++) {
                x[i] += rtk.tt * x[i + 3];
            }
            for (int i = 0; i < 3; i++) {
                P[i * nx + i] += VAR_POS * rtk.tt * rtk.tt;
            }
            for (int i = 3; i < 6; i++) {
                P[i * nx + i] += 1E-1 * 1E-1 * Math.abs(rtk.tt);
            }
            for (int i = 6; i < 9; i++) {
                P[i * nx + i] += 1E-2 * 1E-2 * Math.abs(rtk.tt);
            }
        }
    }

    private static void udclk(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;
        double dtr;

        for (int i = 0; i < NC(); i++) {
            if (opt.sateph == Constants.EPHOPT_PREC) {
                dtr = rtk.sol.dtr[0];
            } else {
                dtr = i == 0 ? rtk.sol.dtr[0] : rtk.sol.dtr[0] + rtk.sol.dtr[i];
            }
            PppCore.initx(x, P, nx, Constants.CLIGHT * dtr, VAR_CLK, IC(i, opt));
        }
    }

    private static void udtrop(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;
        double[] pos = new double[3];
        double[] azel = {0.0, Constants.PI / 2.0};

        int i = IT(opt);

        if (rtk.x[i] == 0.0) {
            CoordTransform.ecef2pos(rtk.sol.rr, pos);
            double[] varT = new double[1];
            double ztd = SbasCorrection.sbstropcorr(rtk.sol.time, pos, azel, varT);
            PppCore.initx(x, P, nx, ztd, varT[0], i);

            if (opt.tropopt >= Constants.TROPOPT_ESTG) {
                for (int j = i + 1; j < i + 3; j++) PppCore.initx(x, P, nx, 1E-6, VAR_GRA, j);
            }
        } else {
            P[i * nx + i] += PppCore.SQR(opt.prn[2]) * Math.abs(rtk.tt);

            if (opt.tropopt >= Constants.TROPOPT_ESTG) {
                for (int j = i + 1; j < i + 3; j++) {
                    P[j * nx + j] += PppCore.SQR(opt.prn[2] * 0.1) * Math.abs(rtk.tt);
                }
            }
        }
    }

    private static final double VAR_IONO_INIT = 10.0 * 10.0;

    private static void udiono(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        if (rtk.epoch <= 1) {
            double[] pos = new double[3];
            CoordTransform.ecef2pos(rtk.sol.rr, pos);
            double[] azelZenith = {0.0, Constants.PI / 2.0};
            double[] ionOut = new double[2];
            IonosphereModel.ionocorr(rtk.sol.time, nav, 1, pos, azelZenith,
                    Constants.IONOOPT_BRDC, ionOut);
            double zenithDelay = ionOut[0];
            if (zenithDelay < 1.0) {
                zenithDelay = 10.0;
            }
            for (int i = 0; i < Constants.MAXSAT; i++) {
                int idx = II(i + 1, opt);
                PppCore.initx(x, P, nx, zenithDelay, VAR_IONO_INIT, idx);
            }
        }

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            int idx = II(sat, opt);
            double sinel = Math.sin(rtk.ssat[sat - 1].azel[1]);
            P[idx * nx + idx] += PppCore.SQR(opt.prn[1] / Math.max(sinel, 0.1)) * Math.abs(rtk.tt);
        }
    }

    private static void udbias(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < opt.nf; j++) {
                rtk.ssat[i].slip[j] = 0;
            }
        }

        PppCore.detslpLl(rtk, obs, n);
        PppCore.detslpGf(rtk, obs, n, nav);
        PppCore.detslpMw(rtk, obs, n, nav);

        for (int f = 0; f < NF(opt); f++) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                    if (rtk.ssat[i].vsat[f] != 0) {
                        rtk.ssat[i].outc[f] = 0;
                    }
                    if (rtk.ssat[i].vsat[f] == 0) {
                        rtk.ssat[i].outc[f]++;
                        if (rtk.ssat[i].outc[f] > opt.maxout) {
                            int idx = IB(i + 1, f, opt);
                            if (idx < nx && x[idx] != 0.0) {
                                PppCore.initx(x, P, nx, 0.0, PppCore.VAR_BIAS, idx);
                                rtk.ssat[i].mw[0] = 0.0;
                                rtk.ssat[i].gf[0] = 0.0;
                                rtk.ssat[i].xrwl = 0.0;
                                rtk.ssat[i].rw = 0.0;
                                rtk.ssat[i].xswl = 0.0;
                                rtk.ssat[i].niwl = 0;
                            }
                            rtk.ssat[i].outc[f] = 0;
                        }
                    }
                } else {
                    if (++rtk.ssat[i].outc[f] > opt.maxout && rtk.ssat[i].vsat[f] != 0) {
                        int idx = IB(i + 1, f, opt);
                        if (idx < nx && x[idx] != 0.0) {
                            PppCore.initx(x, P, nx, 0.0, PppCore.VAR_BIAS, idx);
                            rtk.ssat[i].mw[0] = 0.0;
                            rtk.ssat[i].gf[0] = 0.0;
                            rtk.ssat[i].xrwl = 0.0;
                            rtk.ssat[i].rw = 0.0;
                            rtk.ssat[i].xswl = 0.0;
                            rtk.ssat[i].niwl = 0;
                        }
                        rtk.ssat[i].outc[f] = 0;
                        rtk.ssat[i].vsat[f] = 0;
                    }
                }
            }

            // FIX-ALG-043: permanent exclusion for initialization-phase dead loops.
            // Target: sat=107-type sats where initial Pc-Lc is corrupted (372 m vs normal ~5 m),
            // → KF never converges → innovation always > maxinno → infinite dead loop
            // (repeats every epoch because udbias re-inits with the same bad Pc-Lc).
            //
            // Design:
            // 1. Trigger ONLY during first INIT_PHASE_EPOCH epochs (dead loops manifest
            //    within 5-7 epochs; normal sats never accumulate 10 rejections that fast).
            // 2. Freeze rejc to PERM_FROZEN_REJC once triggered — this is a one-way latch.
            // 3. All subsequent checks look for rejc >= PERM_FROZEN_REJC, which normal
            //    sats can never reach (even with persistent large residuals they top out
            //    at ~50-60 over 119 epochs).
            //
            // sat=107 scenario: epoch 5 rejc=10 → freeze → epoch 6+ permanently excluded ✓
            // sat=29 (GPS normal): epoch 38 rejc=1 → slowly grows to 20 by epoch 58 →
            //   never reaches freeze threshold during init phase → NOT excluded ✓
            // sat=106/108/109 (BDS): start rejecting at epoch 47 → epoch>10 → never trigger ✓
            final int INIT_PHASE_EPOCH = 10;
            final int INIT_REJC_THRESHOLD = 10;
            final int PERM_FROZEN_REJC = 1000;

            // Phase 1: Freeze sats that hit the threshold during initialization window
            if (rtk.epoch <= INIT_PHASE_EPOCH) {
                for (int i = 0; i < Constants.MAXSAT; i++) {
                    int idx = IB(i + 1, f, opt);
                    if (idx >= nx) continue;
                    if (rtk.ssat[i].rejc[f] >= INIT_REJC_THRESHOLD
                        && rtk.ssat[i].rejc[f] < PERM_FROZEN_REJC) {
                        rtk.ssat[i].rejc[f] = PERM_FROZEN_REJC;
                        LOG.warn("[PERM-FREEZE] epoch={} sat={} frq={} rejc>=10 in init phase -> perm freeze",
                            rtk.epoch, i + 1, f);
                    }
                }
            }

            // Phase 2: Apply permanent exclusion to frozen sats (all epochs)
            for (int i = 0; i < Constants.MAXSAT; i++) {
                int idx = IB(i + 1, f, opt);
                if (idx >= nx) continue;
                if (rtk.ssat[i].rejc[f] >= PERM_FROZEN_REJC) {
                    if (x[idx] != 0.0) {
                        PppCore.initx(x, P, nx, 0.0, PppCore.VAR_BIAS, idx);
                        rtk.ssat[i].mw[0] = 0.0;
                        rtk.ssat[i].gf[0] = 0.0;
                        rtk.ssat[i].xrwl = 0.0;
                        rtk.ssat[i].rw = 0.0;
                        rtk.ssat[i].xswl = 0.0;
                        rtk.ssat[i].niwl = 0;
                    }
                    rtk.ssat[i].vsat[f] = 0;
                }
            }

            double[] bias = new double[n];
            int[] slip = new int[n];
            double offset = 0.0;
            int k = 0;

            for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
                int sat = obs[i].sat;
                int j = IB(sat, f, opt);

                double[] Lc = new double[1];
                double[] Pc = new double[1];
                double[] L = new double[Constants.NFREQ];
                double[] P_arr = new double[Constants.NFREQ];
                double[] dantr = new double[Constants.NFREQ];
                double[] dants = new double[Constants.NFREQ];

                PppCore.corrMeas(obs[i], nav, rtk.ssat[sat - 1].azel, opt, dantr, dants, 0.0, L, P_arr, Lc, Pc);

                bias[i] = 0.0;

                if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                    bias[i] = Lc[0] - Pc[0];
                    slip[i] = (rtk.ssat[sat - 1].slip[0] != 0 || rtk.ssat[sat - 1].slip[1] != 0) ? 1 : 0;
                } else {
                    if (L[f] != 0.0 && P_arr[f] != 0.0) {
                        double freq1 = SatUtils.sat2freq(sat, obs[i].code[0], nav);
                        double freq2 = SatUtils.sat2freq(sat, obs[i].code[f], nav);
                        slip[i] = rtk.ssat[sat - 1].slip[f];
                        double ion = 0.0;
                        if (f == 0 || obs[i].P[0] == 0.0 || obs[i].P[f] == 0.0 || freq1 == 0.0 || freq2 == 0.0) {
                            ion = 0;
                        } else {
                            ion = (obs[i].P[0] - obs[i].P[f]) / (1.0 - PppCore.SQR(freq1 / freq2));
                        }
                        bias[i] = L[f] - P_arr[f] + 2.0 * ion * PppCore.SQR(freq1 / freq2);
                    }
                }

                // FIX-ALG-044: sanity-check initial ambiguity magnitude.
                // IFLC initial bias = Lc-Pc, dominated by pseudorange noise (~1 m).
                // Normal values are within ±50 m for most frequencies, up to ~500 m
                // for noisy receivers/geometry. Values beyond 500 m are almost
                // certainly corrupted pseudorange. Sats with 100-500 m bias should
                // converge within 1-2 epochs via the offset correction and KF; this
                // check only catches truly broken sats before they ever get init'd.
                if (bias[i] != 0.0 && Math.abs(bias[i]) > 500.0) {
                    if (rtk.epoch <= 3) {
                        LOG.warn("[FIX-044] epoch={} sat={} frq={} init_bias={} outlier -> skip initx",
                            rtk.epoch, sat, f, String.format("%.1f", bias[i]));
                    }
                    bias[i] = 0.0;
                }

                // FIX-ALG-043: skip permanently frozen sats (rejc frozen to 1000 during init phase)
                if (rtk.ssat[sat - 1].rejc[f] >= 100) continue;

                if (x[j] == 0.0 || slip[i] != 0 || bias[i] == 0.0) continue;

                offset += bias[i] - x[j];
                k++;
            }

            if (k >= 2 && Math.abs(offset / k) > 0.0005 * Constants.CLIGHT) {
                for (int i = 0; i < Constants.MAXSAT; i++) {
                    int j2 = IB(i + 1, f, opt);
                    if (x[j2] != 0.0) x[j2] += offset / k;
                }
            }

            for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
                int sat = obs[i].sat;
                int j = IB(sat, f, opt);

                P[j * nx + j] += PppCore.SQR(opt.prn[0]) * Math.abs(rtk.tt);

                // FIX-ALG-043: skip initx for permanently frozen sats
                if (rtk.ssat[sat - 1].rejc[f] >= 100) continue;
                if (bias[i] == 0.0 || (x[j] != 0.0 && slip[i] == 0)) continue;

                PppCore.initx(x, P, nx, bias[i], VAR_BIAS, IB(sat, f, opt));
                rtk.ssat[sat - 1].mw[0] = 0.0;
                rtk.ssat[sat - 1].gf[0] = 0.0;
                rtk.ssat[sat - 1].xrwl = 0.0;
                rtk.ssat[sat - 1].rw = 0.0;
                rtk.ssat[sat - 1].xswl = 0.0;
                rtk.ssat[sat - 1].niwl = 0;
            }
        }
    }

    private static int ppprtkRes(int post, Obsd[] obs, int n, double[] rs, double[] dts,
                                  double[] varRs, int[] svh, int[] exc,
                                  Nav nav, double[] x, Rtk rtk,
                                  double[] v, double[] H, double[] R,
                                  double[] azel, int nx) {
        final double THRES_REJECT = 4.0;
        PrcOpt opt = rtk.opt;
        double[] rr = new double[3];
        double[] pos = new double[3];
        double[] e = new double[3];
        double[] dtdx = new double[3];
        double[] L = new double[Constants.NFREQ];
        double[] P_arr = new double[Constants.NFREQ];
        double[] Lc = new double[1];
        double[] Pc = new double[1];
        double[] dantr = new double[Constants.NFREQ];
        double[] dants = new double[Constants.NFREQ];
        double[] dion_vari = new double[2];
        double[] varr = new double[n * 2 * NF(opt)];
        int nv = 0;
        int stat = 0;

        // 临时接入诊断（PPP_DEBUG_REJ=1）：逐星剔除原因统计
        final boolean dbg = DBG_REJ && post == 0 && rtk.epoch <= DBG_REJ_MAXEPOCH;
        int[] rejCnt = new int[8];
        StringBuilder rejLog = dbg ? new StringBuilder() : null;
        final String[] REJ_NAME = {"ok", "r<=0", "el<elmin", "excl", "trop", "iono", "bias0", "maxinno"};
        if (dbg) {
            for (int i = 0; i < 8; i++) rejCnt[i] = 0;
        }

        // For post-fit residual rejection
        int[] obsi = new int[n * 2 * NF(opt)];
        int[] frqi = new int[n * 2 * NF(opt)];
        double[] ve = new double[n * 2 * NF(opt)];
        int ne = 0;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < opt.nf; j++) rtk.ssat[i].vsat[j] = 0;
        }

        for (int i = 0; i < 3; i++) rr[i] = x[i];

        if (opt.tidecorr != 0) {
            double[] disp = new double[3];
            if (rtk.rtkConfig != null && rtk.rtkConfig.ppp.enableIers2010) {
                double[] disp2010 = PppOptimizations.tideDisplacementIers2010(
                        TimeSystem.gpst2utc(obs[0].time), rr, opt.tidecorr, nav.erp, opt.odisp[0], rtk.rtkConfig);
                if (disp2010 != null) {
                    for (int ii = 0; ii < 3; ii++) disp[ii] = disp2010[ii];
                } else {
                    PppCore.tidedisp(TimeSystem.gpst2utc(obs[0].time), rr, opt.tidecorr, nav.erp, opt.odisp[0], disp);
                }
            } else {
                PppCore.tidedisp(TimeSystem.gpst2utc(obs[0].time), rr, opt.tidecorr, nav.erp, opt.odisp[0], disp);
            }
            for (int i = 0; i < 3; i++) rr[i] += disp[i];
        }

        CoordTransform.ecef2pos(rr, pos);

        boolean hasSsr = SsrCorrector.hasSsrData(nav.ssr);

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            int sys = SatUtils.satsys(sat, null);

            double[] rsi = new double[3];
            rsi[0] = rs[i * 6];
            rsi[1] = rs[i * 6 + 1];
            rsi[2] = rs[i * 6 + 2];
            double r = RtklibCommon.geodist(rsi, rr, e);
            if (r <= 0.0) {
                if (dbg) { rejCnt[1]++; rejLog.append(sat).append(":r "); }
                exc[i] = 1;
                continue;
            }

            double[] azelI = new double[2];
            double el = RtklibCommon.satazel(pos, e, azelI);
            azel[i * 2] = azelI[0];
            azel[i * 2 + 1] = azelI[1];
            if (el < opt.elmin) {
                if (dbg) { rejCnt[2]++; rejLog.append(sat).append(":el(")
                        .append(String.format("%.1f", el * Constants.R2D)).append(") "); }
                exc[i] = 1;
                continue;
            }

            rtk.ssat[sat - 1].azel[0] = azelI[0];
            rtk.ssat[sat - 1].azel[1] = azelI[1];

            if (sys == 0 || rtk.ssat[sat - 1].vs == 0 ||
                    RtklibCommon.satexclude(sat, varRs[i], svh[i], opt) != 0 || exc[i] != 0) {
                if (dbg) { rejCnt[3]++; rejLog.append(sat).append(":excl(vs=")
                        .append(rtk.ssat[sat - 1].vs).append(",svh=").append(svh[i])
                        .append(",rs0=").append(rs[i * 6] == 0.0 ? 1 : 0).append(") "); }
                continue;
            }

            double dtrp = 0.0;
            double vart = 0.0;
            double[] dtrpArr = new double[1];
            double[] vartArr = new double[1];
            if (!PppCore.modelTrop(obs[i].time, pos, azelI, opt, x, dtdx, nav, dtrpArr, vartArr, rtk)) {
                if (dbg) { rejCnt[4]++; rejLog.append(sat).append(":trop "); }
                continue;
            }
            dtrp = dtrpArr[0];
            vart = vartArr[0];

            double dion = 0.0;
            double vari = 0.0;
            boolean ionoFromSsr = false;
            if (!PppCore.modelIono(obs[i].time, pos, azelI, opt, sat, x, nav, dion_vari)) {
                if (dbg) { rejCnt[5]++; rejLog.append(sat).append(":iono "); }
                continue;
            }
            dion = dion_vari[0];
            vari = dion_vari[1];

            if (opt.ionoopt == Constants.IONOOPT_SSR && hasSsr) {
                if (SsrIono.hasSsrIono(nav.ssr, sat)) {
                    double freq0 = SatUtils.sat2freq(sat, obs[i].code[0], nav);
                    double lam0 = freq0 > 0 ? Constants.CLIGHT / freq0 : 0.0;
                    if (lam0 > 0.0) {
                        dion = SsrIono.ssrIonoDelay(obs[i].time, nav, sat, pos, azelI, 0, lam0);
                        int satIdx = sat - 1;
                        if (nav.ssr != null && satIdx >= 0 && satIdx < nav.ssr.length && nav.ssr[satIdx] != null) {
                            vari = SsrIono.ssrIonoVar(nav.ssr[satIdx], obs[i].time);
                        } else {
                            vari = 0.01;
                        }
                        ionoFromSsr = true;
                    }
                }
            }

            for (int j = 0; j < Constants.NFREQ; j++) dantr[j] = dants[j] = 0.0;

            if (opt.posopt[0] != 0) {
                PppCore.satantpcv(rsi, rr, nav.pcvs[sat - 1], dants);
            }

            PppCore.antmodel(opt.pcvr[0], opt.antdel[0], azelI, opt.posopt[1], dantr);

            if (opt.posopt[2] != 0) {
                PppCore.windupcorr(rtk.sol.time, rsi, rr, rtk.ssat[sat - 1]);
            }

            double phw = rtk.ssat[sat - 1].phw;
            PppCore.corrMeas(obs[i], nav, azelI, opt, dantr, dants, phw, L, P_arr, Lc, Pc);

            double osbCorr = 0.0;
            if (rtk.rtkConfig != null && rtk.rtkConfig.ppp.enableOsb) {
                if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                    osbCorr = PppOsbModel.osbCorrectionIfComb(sat, obs[i].code[0], obs[i].code[1],
                            nav, rtk.sol.time.time, rtk.rtkConfig);
                }
            }

            for (int j = 0; j < 2 * NF(opt); j++) {
                int code = j % 2;
                int frq = j / 2;
                double y;
                double C = 0.0;
                double dcb = 0.0;
                double bias = 0.0;

                // FIX-ALG-043: permanently frozen sats (rejc frozen to 1000 during init
                // phase) — skip both phase and pseudorange in ppprtkRes.
                if (rtk.ssat[sat - 1].rejc[frq] >= 100) {
                    exc[i] = 1;
                    continue;
                }

                if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                    y = code == 0 ? Lc[0] : Pc[0];
                    if (y == 0.0) continue;
                } else {
                    y = code == 0 ? L[frq] : P_arr[frq];
                    if (y == 0.0) continue;
                    double freq = SatUtils.sat2freq(sat, obs[i].code[frq], nav);
                    if (freq == 0.0) continue;
                    C = PppCore.SQR(Constants.FREQL1 / freq) * (code == 0 ? -1.0 : 1.0);
                    if (rtk.rtkConfig != null && rtk.rtkConfig.ppp.enableOsb) {
                        osbCorr = PppOsbModel.osbCorrection(sat, frq, obs[i].code[frq],
                                nav, rtk.sol.time.time, rtk.rtkConfig);
                    }
                    /* [ALIGN-RTKLIB-2.5.1] postpos.c:430 / rtksvr.c:540:
                     * obs[i].L[j] -= nav->ssr[sat-1].pbias[code-1]*freq/CLIGHT;
                     * Java: L[frq] 单位=米，pbias 返回=米，直接减。仅非 IFLC 单频路径。 */
                    if (code == 0 && hasSsr) {
                        y -= SsrCorrector.getPhaseBias(nav.ssr, sat, obs[i].code[frq]);
                    }
                }

                if (H != null) {
                    for (int kk = 0; kk < nx; kk++) H[nv * nx + kk] = 0.0;
                    for (int kk = 0; kk < 3; kk++) H[nv * nx + kk] = -e[kk];
                }

                int k;
                switch (sys) {
                    case Constants.SYS_GLO: k = 1; break;
                    case Constants.SYS_GAL: k = 2; break;
                    case Constants.SYS_CMP: k = 3; break;
                    case Constants.SYS_IRN: k = 4; break;
                    default: k = 0; break;
                }
                double cdtr = x[IC(k, opt)];

                if (H != null) {
                    H[nv * nx + IC(k, opt)] = 1.0;
                    if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG
                        || opt.tropopt == Constants.TROPOPT_SSR) {
                        int nt = opt.tropopt >= Constants.TROPOPT_ESTG ? 3 : 1;
                        for (int kk = 0; kk < nt; kk++) {
                            H[nv * nx + IT(opt) + kk] = dtdx[kk];
                        }
                    }
                }

                if ((opt.ionoopt == Constants.IONOOPT_EST || opt.ionoopt == Constants.IONOOPT_SSR)
                        && !ionoFromSsr) {
                    if (H != null) H[nv * nx + II(sat, opt)] = C * PppCore.ionmapf(pos, azelI);
                }

                if (code == 0) {
                    bias = x[IB(sat, frq, opt)];
                    if (bias == 0.0) {
                        if (dbg) { rejCnt[6]++; rejLog.append(sat).append(":bias0 "); }
                        continue;
                    }
                    if (H != null) H[nv * nx + IB(sat, frq, opt)] = 1.0;
                }

                double ssrCb = 0.0;
                if (code == 1 && hasSsr) {
                    ssrCb = SsrCorrector.getCodeBias(nav.ssr, sat, obs[i].code[frq]);
                }

                double res = y - (r + cdtr - Constants.CLIGHT * dts[i * 2] + dtrp + C * dion + dcb + bias + ssrCb + osbCorr);
                if (v != null) v[nv] = res;

                if (code == 0) rtk.ssat[sat - 1].resc[frq] = res;
                else rtk.ssat[sat - 1].resp[frq] = res;

                varr[nv] = PppCore.varerr(sat, sys, azelI[1], rtk.ssat[sat - 1].snrRover[frq], j, opt);
                varr[nv] += vart + PppCore.SQR(C) * vari + varRs[i];
                if (sys == Constants.SYS_GLO && code == 1) varr[nv] += VAR_GLO_IFB;

                if (post == 0 && opt.maxinno[code] > 0.0) {
                    double maxinno_val = (rtk.epoch <= 30 && code == 0) ? 30.0 : opt.maxinno[code];
                    if (Math.abs(res) > maxinno_val) {
                        if (dbg) {
                            rejCnt[7]++;
                            rejLog.append(sat).append(":inno").append(String.format("%.0f", res)).append(" ");
                        }
                        exc[i] = 1;
                        rtk.ssat[sat - 1].rejc[frq]++;
                        // FIX-ALG-043b: immediate reset on persistent innovation rejection.
                        // exc[] is local to ppprtkRes and resets each epoch, but within one
                        // epoch it's shared across iters — once set, sat is excluded in
                        // remaining iters of this epoch. If rejc already exceeded threshold,
                        // reset ambiguity NOW and clear exc[i] so this sat gets a chance
                        // in later iters (same epoch).
                        // NOTE: rejc is NOT reset here — it accumulates monotonically so the
                        // permanent-exclude logic in udbias can eventually kill this sat.
                        final int MAX_REJC_IMMEDIATE = 4;
                        if (rtk.ssat[sat - 1].rejc[frq] >= MAX_REJC_IMMEDIATE) {
                            int idx = IB(sat, frq, opt);
                            if (idx < nx && x[idx] != 0.0) {
                                PppCore.initx(x, rtk.P, nx, 0.0, PppCore.VAR_BIAS, idx);
                                rtk.ssat[sat - 1].mw[0] = 0.0;
                                rtk.ssat[sat - 1].gf[0] = 0.0;
                                rtk.ssat[sat - 1].xrwl = 0.0;
                                rtk.ssat[sat - 1].rw = 0.0;
                                rtk.ssat[sat - 1].xswl = 0.0;
                                rtk.ssat[sat - 1].niwl = 0;
                            }
                            exc[i] = 0; // clear so it's not locked out in later iters
                        }
                        continue;
                    }
                }

                // Record large post-fit residuals for outlier rejection
                if (post != 0 && Math.abs(res) > Math.sqrt(varr[nv]) * THRES_REJECT) {
                    obsi[ne] = i;
                    frqi[ne] = frq;
                    ve[ne] = res;
                    ne++;
                }

                if (code == 0) {
                    rtk.ssat[sat - 1].vsat[frq] = 1;
                    stat++;
                }
                nv++;
            }
        }

        // Reject satellite with largest post-fit residual
        if (post != 0 && ne > 0) {
            double vmax = ve[0];
            int maxobs = obsi[0];
            int maxfrq = frqi[0];
            int rej = 0;
            for (int j = 1; j < ne; j++) {
                if (Math.abs(vmax) >= Math.abs(ve[j])) continue;
                vmax = ve[j];
                maxobs = obsi[j];
                maxfrq = frqi[j];
                rej = j;
            }
            int sat = obs[maxobs].sat;
            exc[maxobs] = 1;
            rtk.ssat[sat - 1].rejc[maxfrq]++;
            stat = 0;
            ve[rej] = 0;
        }

        if (R != null) {
            for (int j = 0; j < nv; j++) {
                for (int ii = 0; ii < nv; ii++) R[j * nv + ii] = 0.0;
                R[j * nv + j] = varr[j];
            }
        }

        if (dbg) {
            StringBuilder sums = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (rejCnt[i] > 0) sums.append(REJ_NAME[i]).append('=').append(rejCnt[i]).append(' ');
            }
            LOG.warn("ppprtkRes REJ epoch={} n={} nv={} nxf={} {} | {}",
                    rtk.epoch, n, nv, nx, sums.toString().trim(), rejLog.toString().trim());
        }

        if (LOG.isTraceEnabled() && post == 0 && rtk.epoch <= 3
                && opt.ionoopt == Constants.IONOOPT_IFLC) {
            int zeroLc = 0;
            for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
                double[] Lc_d = new double[1], Pc_d = new double[1];
                double[] L_d = new double[Constants.NFREQ], P_d = new double[Constants.NFREQ];
                PppCore.corrMeas(obs[i], nav, rtk.ssat[obs[i].sat - 1].azel, opt,
                        new double[Constants.NFREQ], new double[Constants.NFREQ], 0.0, L_d, P_d, Lc_d, Pc_d);
                if (Lc_d[0] == 0.0) {
                    zeroLc++;
                }
            }
            LOG.trace("ppprtkRes IFLC diag: epoch={} nv={} zeroLc={}", rtk.epoch, nv, zeroLc);
        }

        if (post == 0 && nv == 0 && rtk.epoch <= 2) {
            LOG.warn("ppprtkRes: nv=0! n={} hasSsr={} ionoopt={}", n, hasSsr, opt.ionoopt);
        }

        return post != 0 ? stat : nv;
    }

    private static void updateStat(Rtk rtk, Obsd[] obs, int n, int stat, int nx) {
        PrcOpt opt = rtk.opt;

        rtk.sol.ns = 0;
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            for (int j = 0; j < opt.nf; j++) {
                if (rtk.ssat[obs[i].sat - 1].vsat[j] == 0) continue;
                rtk.ssat[obs[i].sat - 1].lock[j]++;
                rtk.ssat[obs[i].sat - 1].outc[j] = 0;
                if (j == 0) rtk.sol.ns++;
            }
        }

        // FIX-ALG-040: preserve SOLQ_FIX from AR resolution. PppRtkAmbFix may have
        // upgraded rtk.sol.stat to SOLQ_FIX before updateStat runs — don't clobber it.
        if (rtk.sol.stat != Constants.SOLQ_FIX) {
            rtk.sol.stat = (byte)(rtk.sol.ns < MIN_NSAT_SOL ? Constants.SOLQ_NONE : stat);
        }

        for (int i = 0; i < 3; i++) {
            rtk.sol.rr[i] = rtk.x[i];
            rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
        }
        rtk.sol.qr[3] = (float) rtk.P[0 * nx + 1];
        rtk.sol.qr[4] = (float) rtk.P[1 * nx + 2];
        rtk.sol.qr[5] = (float) rtk.P[0 * nx + 2];

        rtk.sol.dtr[0] = rtk.x[IC(0, opt)] / Constants.CLIGHT;
        rtk.sol.dtr[1] = (rtk.x[IC(1, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;
        rtk.sol.dtr[2] = (rtk.x[IC(2, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;
        rtk.sol.dtr[3] = (rtk.x[IC(3, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;

        double[] dopAzel = new double[Constants.MAXSAT * 2];
        int dopNs = 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
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
    }
}