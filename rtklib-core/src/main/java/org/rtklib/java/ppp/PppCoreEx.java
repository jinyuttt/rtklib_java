package org.rtklib.java.ppp;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.ppprtk.SsrCorrector;
import org.rtklib.java.tide.AtmosphericTideS1S2;
import org.rtklib.java.trace.PppTrace;
import org.rtklib.java.trace.Trace;
import org.rtklib.java.trace.TraceConfig;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.rtklib.java.pppar.PppArConfig;
import org.rtklib.java.pppar.PppArEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PppCoreEx {
    private PppCoreEx() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppCoreEx.class);

    private static volatile double lastArCovMinDiag = Double.NaN;
    private static volatile double lastArAmbMinDiag = Double.NaN;
    private static volatile long arCovPsdViolations = 0;
    private static volatile long arCovNegDiagEpochs = 0;
    private static volatile double lastArCovMinEig = Double.NaN;
    private static volatile double lastArCovMinPivot = Double.NaN;
    private static volatile double lastArCovFullAmbMinPivot = Double.NaN;
    private static final double AR_PSD_REL_TOL = 1e-10;

    public static double lastArCovMinDiag() {
        return lastArCovMinDiag;
    }

    public static double lastArAmbMinDiag() {
        return lastArAmbMinDiag;
    }

    public static long arCovPsdViolations() {
        return arCovPsdViolations;
    }

    public static long arCovNegDiagEpochs() {
        return arCovNegDiagEpochs;
    }

    public static double lastArCovFullAmbMinPivot() {
        return lastArCovFullAmbMinPivot;
    }

    public static double lastArCovMinEig() {
        return lastArCovMinEig;
    }

    public static double lastArCovMinPivot() {
        return lastArCovMinPivot;
    }

    public static void resetArCovPsdStats() {
        lastArCovMinDiag = Double.NaN;
        lastArAmbMinDiag = Double.NaN;
        lastArCovMinEig = Double.NaN;
        lastArCovMinPivot = Double.NaN;
        lastArCovFullAmbMinPivot = Double.NaN;
        arCovPsdViolations = 0;
        arCovNegDiagEpochs = 0;
    }

    public static void ppos(Rtk rtk, Obsd[] obs, int n, Nav nav, RtkConfig cfg) {
        LOG.info("PppCoreEx.ppos called: pppAR={}, bds3AR={}", cfg.pppAr.enableAR, cfg.pppAr.enableBds3AR);

        /* [FIX-ALG-057] 与 PppCore.pppos 一致：逐历元入口清除上一历元 AR 写入的
         * SOLQ_FIX 锁存，避免扩展状态分支跳过 PppCore.pppos 时 stat 永久停留
         * 在 FIX（浮点坐标被当成固定解输出、AR 不再执行）。 */
        PppCore.clearFixLatch(rtk);

        boolean needExtendedState = cfg.ppp.enableIsbIfcbIfb || cfg.ppp.enableOsb || cfg.ppp.enableAt1S2 || cfg.ppp.useGpt3Grid;
        boolean needAr = cfg.pppAr.enableAR || cfg.pppAr.enableFixHold || cfg.pppAr.enablePartialAR
                || cfg.pppAr.enableBds3AR || cfg.pppAr.enableMultiFreqAR;

        if (!needExtendedState && !needAr) {
            PppCore.pppos(rtk, obs, n, nav);
            return;
        }

        if (!needExtendedState && needAr) {
            PppCore.pppos(rtk, obs, n, nav);
            if (rtk.sol.stat == Constants.SOLQ_PPP || rtk.sol.stat == Constants.SOLQ_FLOAT) {
                applyAmbiguityResolution(rtk, nav, cfg);
            }
            return;
        }

        PrcOpt opt = rtk.opt;
        int nxOrig = PppCore.pppnx(opt);
        int extraDim = PppBiasModel.extraDim(cfg);
        int nx = nxOrig + extraDim;

        rtk.epoch++;

        if (rtk.nx == 0 || rtk.nx != nx) {
            rtk.nx = nx;
            rtk.x = new double[nx];
            rtk.P = new double[nx * nx];
            rtk.xa = new double[nx];
            rtk.Pa = new double[nx * nx];
            for (int i = 0; i < 3 && i < nx; i++) {
                rtk.x[i] = rtk.sol.rr[i];
            }
            LOG.info("PppCoreEx init: nx={} sol.rr=({},{},{})",
                    nx, String.format("%.0f", rtk.sol.rr[0]), String.format("%.0f", rtk.sol.rr[1]), String.format("%.0f", rtk.sol.rr[2]));
        }

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] var = new double[n];
        int[] svh = new int[n];
        double[] azel = new double[n * 2];
        int[] exc = new int[n];

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
        }

        udstateEx(rtk, obs, n, nav, nx, cfg);

        TraceControl ctrl = rtk.traceControl;
        TraceCallback cb = rtk.traceCallback;
        TraceConfig v2cfg = rtk.traceConfig;
        TraceCallback v2cb = rtk.traceCallback;
        PppTrace.tracePppInput(ctrl, cb, rtk.epoch, obs[0].time, obs, n, nav);
        Trace.emit("SATELLITE", "START", v2cfg, v2cb, rtk.epoch, obs[0].time, "n_obs", n);

        EphModel.satposs(obs[0].time, obs, n, nav, rs, dts, var, svh, opt.sateph);

        if (LOG.isDebugEnabled() && rtk.epoch <= 3) {
            int satOk = 0, satFail = 0;
            for (int ii = 0; ii < n && ii < Constants.MAXOBS; ii++) {
                double normRs = Math.sqrt(rs[ii*6]*rs[ii*6] + rs[ii*6+1]*rs[ii*6+1] + rs[ii*6+2]*rs[ii*6+2]);
                if (normRs > 1E4) satOk++; else satFail++;
            }
            double[] rrInit = new double[3];
            for (int ii = 0; ii < 3; ii++) rrInit[ii] = rtk.sol.rr[ii];
            double[] posInit = new double[3];
            CoordTransform.ecef2pos(rrInit, posInit);
            LOG.debug(String.format("PppCoreEx epoch=%d satposs: n=%d satOk=%d satFail=%d sateph=%d pos=(%.6f,%.6f,%.1f)",
                rtk.epoch, n, satOk, satFail, opt.sateph,
                posInit[0]*Constants.R2D, posInit[1]*Constants.R2D, posInit[2]));
        }

        if (SsrCorrector.hasSsrData(nav.ssr)) {
            // [FIX-ALG-038] 严格对齐 RTKLIB satpos_ssr：龄期超限或改正数异常的卫星不施加 SSR 改正。
            double ssrMaxAge = cfg != null ? cfg.ppp.ssrMaxAge : 60.0;
            SsrCorrector.applyOrbitCorrection(nav.ssr, rs, obs, n, ssrMaxAge);
            SsrCorrector.applyClockCorrection(nav.ssr, dts, obs, n, ssrMaxAge);
            SsrCorrector.applyHrClockCorrection(nav.ssr, dts, obs, n, ssrMaxAge);
        }

        if (opt.posopt[3] != 0) {
            testeclipse(obs, n, nav, rs);
        }

        PppTrace.tracePppUdstate(ctrl, cb, rtk.epoch, obs[0].time, rtk, nx);
        Trace.emit("AMBIGUITY", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                "x", rtk.x[0], "y", rtk.x[1], "z", rtk.x[2], "nx", nx);

        int maxnv = n * PppCore.NF(opt) * 2 + Constants.MAXSAT + 3;
        double[] xp = new double[nx];
        double[] Pp = new double[nx * nx];
        double[] v = new double[maxnv];
        double[] H = new double[nx * maxnv];
        double[] R = new double[maxnv * maxnv];

        int stat = Constants.SOLQ_SINGLE;

        for (int iter = 0; iter < PppCore.MAX_ITER; iter++) {
            System.arraycopy(rtk.x, 0, xp, 0, nx);
            System.arraycopy(rtk.P, 0, Pp, 0, nx * nx);

            int nv = pppResEx(0, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, v, H, R, azel, nx, cfg);

            if (rtk.epoch <= 5 || (rtk.epoch % 50 == 0 && iter == 0)) {
                LOG.info("PppCoreEx epoch={} iter={} nv={} nx={}", rtk.epoch, iter, nv, nx);
            }

            if (iter == 0) {
                PppTrace.tracePppSatPos(ctrl, cb, rtk.epoch, obs[0].time, obs, n, rs, dts, var, svh, azel, exc);
            }

            PppTrace.tracePppRes(ctrl, cb, rtk.epoch, obs[0].time, nv, v, R, nx, H, opt);
            Trace.emit("FILTER", "RESIDUAL", v2cfg, v2cb, rtk.epoch, obs[0].time, "nv", nv);

            if (nv == 0) {
                LOG.debug("PppCoreEx epoch={} iter={} nv=0", rtk.epoch, iter);
                break;
            }

            double[] xpPrev = new double[nx];
            System.arraycopy(xp, 0, xpPrev, 0, nx);

            int info = KalmanFilter.update(xp, Pp, H, v, R, nx, nv);

            PppTrace.tracePppFilter(ctrl, cb, rtk.epoch, obs[0].time, info, xp, xpPrev, Pp, nx);
            Trace.emit("FILTER", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                    "info", info, "nv", nv, "nx", nx);

            if (info != 0) {
                LOG.info("PppCoreEx epoch={} iter={} KF failed info={}", rtk.epoch, iter, info);
                break;
            }

            // Always save the latest state
            System.arraycopy(xp, 0, rtk.x, 0, nx);
            System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);

            int resCheck = pppResEx(iter + 1, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, null, null, null, azel, nx, cfg);
            if (rtk.epoch <= 2) {
                LOG.info("PppCoreEx epoch={} iter={} resCheck={}", rtk.epoch, iter, resCheck);
            }
            if (resCheck != 0) {
                stat = Constants.SOLQ_PPP;
                break;
            }
        }

        if (stat == Constants.SOLQ_PPP) {
            PppCore.updateStat(rtk, obs, n, stat, nx);
            applyAmbiguityResolution(rtk, nav, cfg);
        } else {
            rtk.nfix = 0;
            LOG.info("PppCoreEx epoch={} did not converge, stat={}", rtk.epoch, stat);
        }

        PppTrace.tracePppResult(ctrl, cb, rtk.epoch, obs[0].time, rtk.sol, PppCore.MAX_ITER);
        Trace.emit("POSITION", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                "x", rtk.sol.rr[0], "y", rtk.sol.rr[1], "z", rtk.sol.rr[2],
                "Q", (int) rtk.sol.stat, "ns", (int) rtk.sol.ns);
        if (rtk.sol.stat == Constants.SOLQ_FIX || rtk.sol.stat == Constants.SOLQ_FLOAT) {
            Trace.emit("RESULT", "UPDATE", v2cfg, v2cb, rtk.epoch, obs[0].time,
                    "Q", (int) rtk.sol.stat, "ns", (int) rtk.sol.ns);
        } else {
            Trace.emit("RESULT", "FAIL", v2cfg, v2cb, rtk.epoch, obs[0].time,
                    "Q", (int) rtk.sol.stat, "reason", "no_convergence");
        }
    }

    private static void checkCovariancePsdBeforeAr(Rtk rtk) {
        int nx = rtk.nx;
        if (rtk.P == null || nx <= 0) return;

        double minDiag = Double.MAX_VALUE;
        double minActiveDiag = Double.MAX_VALUE;
        int negDiag = 0;
        for (int i = 0; i < nx; i++) {
            double dii = rtk.P[i * nx + i];
            if (dii < 0.0) negDiag++;
            if (dii < minDiag) minDiag = dii;
            if (rtk.x != null && rtk.x[i] != 0.0 && dii > 0.0 && dii < minActiveDiag) minActiveDiag = dii;
        }
        lastArCovMinDiag = minDiag;
        if (negDiag > 0) {
            arCovNegDiagEpochs++;
            LOG.warn("[KF-ARMOR] AR entry negative diagonal count={} epoch={} nx={} minDiag={}",
                    negDiag, rtk.epoch, nx, minDiag);
        }

        int[] ambAll = ambiguityActiveIndices(rtk, false);
        if (ambAll.length > 0) {
            lastArCovFullAmbMinPivot = ldlMinPivot(subBlock(rtk.P, nx, ambAll), ambAll.length);
        }

        int[] amb = ambiguityActiveIndices(rtk, true);
        int m = amb.length;
        if (m == 0) return;

        double[] Qb = subBlock(rtk.P, nx, amb);
        double minAmbDiag = Double.MAX_VALUE;
        double maxAmbDiag = 0.0;
        for (int i = 0; i < m; i++) {
            double dii = Qb[i * m + i];
            if (dii < minAmbDiag) minAmbDiag = dii;
            maxAmbDiag = Math.max(maxAmbDiag, Math.abs(dii));
        }
        lastArAmbMinDiag = minAmbDiag;
        double tol = AR_PSD_REL_TOL * Math.max(maxAmbDiag, 1.0);

        double minPivot = ldlMinPivot(Qb, m);
        lastArCovMinPivot = minPivot;
        if (!(minPivot >= -tol)) {
            arCovPsdViolations++;
            LOG.warn("[KF-ARMOR] AR ambiguity block indefinite: epoch={} nb={} minPivot={} tol={} minDiag={}",
                    rtk.epoch, m, minPivot, tol, minAmbDiag);
        }
        if (rtk.epoch % 300 == 0) {
            double minEig = minEigenValue(Qb, m);
            lastArCovMinEig = minEig;
            LOG.info("[KF-ARMOR] AR ambiguity block PSD audit: epoch={} nx={} nb={} nbAll={} minActiveDiag={} minAmbDiag={} minPivot={} minEig={} fullAmbMinPivot={}",
                    rtk.epoch, nx, m, ambAll.length, minActiveDiag, minAmbDiag, minPivot, minEig,
                    lastArCovFullAmbMinPivot);
        }
    }

    /**
     * 模糊度子块索引集合。
     *
     * @param onlyKfUpdated true 时仅保留本历元实际参与 KalmanFilter.update() 压缩更新的
     *        状态（x[i]!=0 && P[i,i]>0 && H 列非全零），即正定化护甲在数学上保证 PSD 的
     *        状态子集，也正是 AR 引擎构造 QbWl/QbNl 时使用的观测卫星集合；
     *        false 时返回全部已初始化模糊度状态（含本历元无观测、未参与更新的状态）。
     */
    private static int[] ambiguityActiveIndices(Rtk rtk, boolean onlyKfUpdated) {
        int nx = rtk.nx;
        PrcOpt opt = rtk.opt;
        int nr = PppCore.NR(opt);
        int hi = Math.min(nr + PppCore.NB(opt), nx);
        if (hi <= nr) return new int[0];

        boolean[] updated = null;
        if (onlyKfUpdated) {
            updated = new boolean[nx];
            int[] last = KalmanFilter.lastActiveIndices();
            for (int k = 0; k < last.length; k++) {
                int s = last[k];
                if (s >= 0 && s < nx) updated[s] = true;
            }
        }

        int[] tmp = new int[hi - nr];
        int m = 0;
        for (int i = nr; i < hi; i++) {
            if (updated != null && !updated[i]) continue;
            if (rtk.x == null || rtk.x[i] == 0.0) continue;
            if (!(rtk.P[i * nx + i] > 0.0)) continue;
            tmp[m++] = i;
        }
        int[] out = new int[m];
        System.arraycopy(tmp, 0, out, 0, m);
        return out;
    }

    private static double[] subBlock(double[] P, int nx, int[] idx) {
        int m = idx.length;
        double[] q = new double[m * m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) q[i * m + j] = P[idx[i] * nx + idx[j]];
        }
        return q;
    }

    private static double ldlMinPivot(double[] P, int nx) {
        int m = 0;
        int[] idx = new int[nx];
        for (int i = 0; i < nx; i++) {
            if (P[i * nx + i] > 0.0) idx[m++] = i;
        }
        double maxAbs = 0.0;
        for (int i = 0; i < nx; i++) maxAbs = Math.max(maxAbs, Math.abs(P[i * nx + i]));
        double tol = AR_PSD_REL_TOL * Math.max(maxAbs, 1.0);
        for (int i = 0; i < nx; i++) {
            if (P[i * nx + i] > 0.0) continue;
            for (int j = 0; j < nx; j++) {
                if (Math.abs(P[i * nx + j]) > tol) return Double.NEGATIVE_INFINITY;
            }
        }
        if (m == 0) return 0.0;
        double[] l = new double[m * m];
        double[] d = new double[m];
        double minPivot = Double.MAX_VALUE;
        for (int j = 0; j < m; j++) {
            double djj = P[idx[j] * nx + idx[j]];
            for (int k = 0; k < j; k++) djj -= l[j * m + k] * l[j * m + k] * d[k];
            d[j] = djj;
            if (djj < minPivot) minPivot = djj;
            if (Math.abs(djj) < 1e-300) continue;
            for (int i = j + 1; i < m; i++) {
                double s = P[idx[i] * nx + idx[j]];
                for (int k = 0; k < j; k++) s -= l[i * m + k] * l[j * m + k] * d[k];
                l[i * m + j] = s / djj;
            }
        }
        return minPivot;
    }

    private static double minEigenValue(double[] P, int nx) {
        org.ejml.data.DMatrixRMaj a = new org.ejml.data.DMatrixRMaj(nx, nx);
        for (int i = 0; i < nx; i++) for (int j = 0; j < nx; j++) a.set(i, j, P[i * nx + j]);
        org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM eig =
                new org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM(false);
        if (!eig.decompose(a)) return Double.NaN;
        double mn = Double.MAX_VALUE;
        for (int i = 0; i < nx; i++) mn = Math.min(mn, eig.getEigenvalue(i).real);
        return mn;
    }

    private static void applyAmbiguityResolution(Rtk rtk, Nav nav, RtkConfig cfg) {
        checkCovariancePsdBeforeAr(rtk);
        PppArConfig arCfg = PppArConfig.from(cfg);
        PppArEngine.applyAmbiguityResolution(rtk, nav, arCfg);
    }

    private static void udstateEx(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx, RtkConfig cfg) {
        PppCore.udstate_ppp(rtk, obs, n, nav, nx);

        if (!cfg.ppp.enableIsbIfcbIfb) return;

        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        int isbIdx = PppBiasModel.isbIndex(cfg, opt);
        if (isbIdx >= 0 && cfg.ppp.estimateIsb) {
            for (int i = 0; i < 3; i++) {
                int idx = isbIdx + i;
                if (x[idx] == 0.0) {
                    PppCore.initx(x, P, nx, 0.0, PppCore.VAR_CLK, idx);
                } else {
                    P[idx * nx + idx] += PppCore.SQR(cfg.ppp.isbPrn) * Math.abs(rtk.tt);
                }
            }
        }

        int ifcbIdx = PppBiasModel.ifcbIndex(cfg, opt);
        if (ifcbIdx >= 0 && cfg.ppp.estimateIfcb) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                int idx = ifcbIdx + i;
                if (x[idx] == 0.0) {
                    PppCore.initx(x, P, nx, 0.0, PppCore.VAR_DCB, idx);
                } else {
                    P[idx * nx + idx] += PppCore.SQR(cfg.ppp.ifcbPrn) * Math.abs(rtk.tt);
                }
            }
        }

        int ifbIdx = PppBiasModel.ifbIndex(cfg, opt);
        if (ifbIdx >= 0 && cfg.ppp.estimateIfb) {
            for (int i = 0; i < 4; i++) {
                int idx = ifbIdx + i;
                if (x[idx] == 0.0) {
                    PppCore.initx(x, P, nx, 0.0, PppCore.VAR_CLK, idx);
                } else {
                    P[idx * nx + idx] += PppCore.SQR(cfg.ppp.ifbPrn) * Math.abs(rtk.tt);
                }
            }
        }
    }

    private static int pppResEx(int post, Obsd[] obs, int n, double[] rs, double[] dts,
                                double[] varRs, int[] svh, int[] exc,
                                Nav nav, double[] x, Rtk rtk, double[] v, double[] H,
                                double[] R, double[] azel, int nx, RtkConfig cfg) {
        int nv = PppCore.pppRes(post, obs, n, rs, dts, varRs, svh, exc, nav, x, rtk, v, H, R, azel, nx);

        if (!cfg.ppp.enableIsbIfcbIfb || H == null || nv == 0) return nv;

        PrcOpt opt = rtk.opt;

        int isbIdx = PppBiasModel.isbIndex(cfg, opt);
        int ifcbIdx = PppBiasModel.ifcbIndex(cfg, opt);
        int ifbIdx = PppBiasModel.ifbIndex(cfg, opt);

        int obsIdx = 0;
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            int sys = SatUtils.satsys(sat, null);
            if (exc[i] != 0) continue;

            for (int j = 0; j < 2 * PppCore.NF(opt); j++) {
                int code = j % 2;
                int frq = j / 2;

                if (obsIdx >= nv) break;

                if (isbIdx >= 0) {
                    int isbOff = -1;
                    if ((sys & Constants.SYS_GLO) != 0) isbOff = 0;
                    else if ((sys & Constants.SYS_GAL) != 0) isbOff = 1;
                    else if ((sys & Constants.SYS_CMP) != 0) isbOff = 2;

                    if (isbOff >= 0) {
                        H[obsIdx * nx + isbIdx + isbOff] = code == 0 ? 1.0 : 1.0;
                    }
                }

                if (ifcbIdx >= 0 && frq > 0 && code == 1) {
                    H[obsIdx * nx + ifcbIdx + sat - 1] = 1.0;
                }

                if (ifbIdx >= 0 && code == 1) {
                    int sysOff = -1;
                    if ((sys & Constants.SYS_GPS) != 0) sysOff = 0;
                    else if ((sys & Constants.SYS_GLO) != 0) sysOff = 1;
                    else if ((sys & Constants.SYS_GAL) != 0) sysOff = 2;
                    else if ((sys & Constants.SYS_CMP) != 0) sysOff = 3;

                    if (sysOff >= 0) {
                        H[obsIdx * nx + ifbIdx + sysOff] = 1.0;
                    }
                }

                obsIdx++;
            }
        }

        return nv;
    }

    private static void testeclipse(Obsd[] obs, int n, Nav nav, double[] rs) {
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            if (obs[i].sat <= 0 || obs[i].sat > Constants.MAXSAT) continue;
            double[] rsi = {rs[i * 6], rs[i * 6 + 1], rs[i * 6 + 2]};
            double norm = Math.sqrt(rsi[0] * rsi[0] + rsi[1] * rsi[1] + rsi[2] * rsi[2]);
            if (norm > 0.0) {
                double[] es = {rsi[0] / norm, rsi[1] / norm, rsi[2] / norm};
                double ea = Math.atan2(es[1], es[0]);
                double am = ea - 2.0 * Math.PI * Math.floor((ea + Math.PI) / (2.0 * Math.PI));
                if (Math.abs(am) < 0.05 && norm < 25000000.0) {
                    obs[i].sat = 0;
                }
            }
        }
    }
}