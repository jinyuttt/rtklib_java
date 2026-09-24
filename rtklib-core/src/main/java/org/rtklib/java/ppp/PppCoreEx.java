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
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PppCoreEx {
    private PppCoreEx() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppCoreEx.class);

    public static void ppos(Rtk rtk, Obsd[] obs, int n, Nav nav, RtkConfig cfg) {
        LOG.info("PppCoreEx.ppos called: pppAR={}, bds3AR={}", cfg.enablePppAR, cfg.enableBds3PppAR);
        if (!cfg.enableIsbIfcbIfb && !cfg.enablePppAR && !cfg.enablePppArFixHold
            && !cfg.enablePppPartialAR && !cfg.enableBds3PppAR && !cfg.enableOsb
            && !cfg.enableAt1S2 && !cfg.useGpt3Grid) {
            PppCore.pppos(rtk, obs, n, nav);
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
        PppTrace.tracePppInput(ctrl, cb, rtk.epoch, obs[0].time, obs, n, nav);

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
            SsrCorrector.applyOrbitCorrection(nav.ssr, rs, obs, n);
            SsrCorrector.applyClockCorrection(nav.ssr, dts, obs, n);
            SsrCorrector.applyHrClockCorrection(nav.ssr, dts, obs, n);
        }

        if (opt.posopt[3] != 0) {
            testeclipse(obs, n, nav, rs);
        }

        PppTrace.tracePppUdstate(ctrl, cb, rtk.epoch, obs[0].time, rtk, nx);

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

            if (nv == 0) {
                LOG.debug("PppCoreEx epoch={} iter={} nv=0", rtk.epoch, iter);
                break;
            }

            double[] xpPrev = new double[nx];
            System.arraycopy(xp, 0, xpPrev, 0, nx);

            int info = KalmanFilter.update(xp, Pp, H, v, R, nx, nv);

            PppTrace.tracePppFilter(ctrl, cb, rtk.epoch, obs[0].time, info, xp, xpPrev, Pp, nx);

            if (info != 0) {
                LOG.info("PppCoreEx epoch={} iter={} KF failed info={}", rtk.epoch, iter, info);
                break;
            }

            int resCheck = pppResEx(iter + 1, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, null, null, null, azel, nx, cfg);
            if (rtk.epoch <= 2) {
                LOG.info("PppCoreEx epoch={} iter={} resCheck={}", rtk.epoch, iter, resCheck);
            }
            if (resCheck != 0) {
                System.arraycopy(xp, 0, rtk.x, 0, nx);
                System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);
                stat = Constants.SOLQ_PPP;
                break;
            }
        }

        if (stat == Constants.SOLQ_PPP) {
            PppCore.updateStat(rtk, obs, n, stat, nx);

            if (cfg.enablePppAR) {
                LOG.info("PppCoreEx: calling pppAmbFixWlNl, epoch={}", rtk.epoch);
                int nb = PppAmbFix.pppAmbFixWlNl(rtk, null, rtk.xa, 1, 0, 0, nav);
                if (nb > 1) {
                    rtk.sol.stat = Constants.SOLQ_FIX;
                    LOG.debug("PppCoreEx: PPP-AR fixed {} ambiguities", nb);
                }
            }

            if (cfg.enablePppPartialAR && rtk.sol.stat != Constants.SOLQ_FIX) {
                int nb = PppAmbFix.pppPartialAR(rtk, null, rtk.xa, 1, 0, 0, nav);
                if (nb > 1) {
                    rtk.sol.stat = Constants.SOLQ_FIX;
                    LOG.debug("PppCoreEx: PPP Partial AR fixed {} ambiguities", nb);
                }
            }

            if (cfg.enableBds3PppAR) {
                int nb = PppAmbFixBds3.pppAmbFixBds3(rtk, null, rtk.xa, nav);
                if (nb > 1) {
                    LOG.debug("PppCoreEx: BDS-3 PPP-AR fixed {} ambiguities", nb);
                }
            }

            if (cfg.enablePppArFixHold && rtk.sol.stat == Constants.SOLQ_FIX) {
                PppAmbFix.pppArFixHold(rtk, nav);
            }
        } else {
            LOG.info("PppCoreEx epoch={} did not converge, stat={}", rtk.epoch, stat);
        }

        PppTrace.tracePppResult(ctrl, cb, rtk.epoch, obs[0].time, rtk.sol, PppCore.MAX_ITER);
    }

    private static void udstateEx(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx, RtkConfig cfg) {
        PppCore.udstate_ppp(rtk, obs, n, nav, nx);

        if (!cfg.enableIsbIfcbIfb) return;

        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        int isbIdx = PppBiasModel.isbIndex(cfg, opt);
        if (isbIdx >= 0 && cfg.estimateIsb) {
            for (int i = 0; i < 3; i++) {
                int idx = isbIdx + i;
                if (x[idx] == 0.0) {
                    PppCore.initx(x, P, nx, 0.0, PppCore.VAR_CLK, idx);
                } else {
                    P[idx * nx + idx] += PppCore.SQR(cfg.isbPrn) * Math.abs(rtk.tt);
                }
            }
        }

        int ifcbIdx = PppBiasModel.ifcbIndex(cfg, opt);
        if (ifcbIdx >= 0 && cfg.estimateIfcb) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                int idx = ifcbIdx + i;
                if (x[idx] == 0.0) {
                    PppCore.initx(x, P, nx, 0.0, PppCore.VAR_DCB, idx);
                } else {
                    P[idx * nx + idx] += PppCore.SQR(cfg.ifcbPrn) * Math.abs(rtk.tt);
                }
            }
        }

        int ifbIdx = PppBiasModel.ifbIndex(cfg, opt);
        if (ifbIdx >= 0 && cfg.estimateIfb) {
            for (int i = 0; i < 4; i++) {
                int idx = ifbIdx + i;
                if (x[idx] == 0.0) {
                    PppCore.initx(x, P, nx, 0.0, PppCore.VAR_CLK, idx);
                } else {
                    P[idx * nx + idx] += PppCore.SQR(cfg.ifbPrn) * Math.abs(rtk.tt);
                }
            }
        }
    }

    private static int pppResEx(int post, Obsd[] obs, int n, double[] rs, double[] dts,
                                double[] varRs, int[] svh, int[] exc,
                                Nav nav, double[] x, Rtk rtk, double[] v, double[] H,
                                double[] R, double[] azel, int nx, RtkConfig cfg) {
        int nv = PppCore.pppRes(post, obs, n, rs, dts, varRs, svh, exc, nav, x, rtk, v, H, R, azel, nx);

        if (!cfg.enableIsbIfcbIfb || H == null || nv == 0) return nv;

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