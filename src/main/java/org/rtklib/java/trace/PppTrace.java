package org.rtklib.java.trace;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

import java.util.Locale;

public final class PppTrace {
    private PppTrace() {
    }

    public static final int PPP_STAGE_INPUT    = 1 << 0;
    public static final int PPP_STAGE_SATPOS   = 1 << 1;
    public static final int PPP_STAGE_UDSTATE  = 1 << 2;
    public static final int PPP_STAGE_RES      = 1 << 3;
    public static final int PPP_STAGE_FILTER   = 1 << 4;
    public static final int PPP_STAGE_RESULT   = 1 << 5;

    private static double gpst(GTime time) {
        if (time == null || time.time == 0) return 0.0;
        return TimeSystem.time2gpst(time, null);
    }

    private static String f3(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private static String f6(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    public static void tracePppInput(TraceControl ctrl, TraceCallback cb, int epoch,
                                     GTime time, Obsd[] obs, int n, Nav nav) {
        if (!RtkTrace.shouldTrace(ctrl, PPP_STAGE_INPUT, epoch)) return;

        double gpstVal = gpst(time);
        int validObs = 0;
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            if (obs[i].L[0] != 0.0 || obs[i].P[0] != 0.0) validObs++;
        }

        RtkTrace.safeCallback(cb, String.format(Locale.US,
                "TRACE|PPP_STAGE0|gpst=%.3f|epoch=%d|n_obs=%d|n_valid=%d",
                gpstVal, epoch, n, validObs));

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            if (sat < 1 || sat > Constants.MAXSAT) continue;
            if (!RtkTrace.isTargetSat(ctrl, sat)) continue;

            String satId = SatUtils.satno2id(sat);
            double freq1 = SatUtils.sat2freq(sat, obs[i].code[0], nav);
            double L1 = freq1 > 0 ? obs[i].L[0] * Constants.CLIGHT / freq1 : 0.0;
            double P1 = obs[i].P.length > 0 ? obs[i].P[0] : 0.0;
            float snr = obs[i].SNR.length > 0 ? obs[i].SNR[0] : 0;

            RtkTrace.safeCallback(cb, String.format(Locale.US,
                    "TRACE|PPP_STAGE0_SAT|gpst=%.3f|epoch=%d|sat=%s|L1=%s|P1=%s|SNR=%.0f",
                    gpstVal, epoch, satId, f3(L1), f3(P1), snr));
        }
    }

    public static void tracePppSatPos(TraceControl ctrl, TraceCallback cb, int epoch,
                                      GTime time, Obsd[] obs, int n,
                                      double[] rs, double[] dts, double[] var,
                                      int[] svh, double[] azel, int[] exc) {
        if (!RtkTrace.shouldTrace(ctrl, PPP_STAGE_SATPOS, epoch)) return;

        double gpstVal = gpst(time);

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            if (sat < 1 || sat > Constants.MAXSAT) continue;
            if (!RtkTrace.isTargetSat(ctrl, sat)) continue;
            if (exc != null && exc[i] != 0) continue;

            String satId = SatUtils.satno2id(sat);
            double sx = rs[i * 6], sy = rs[i * 6 + 1], sz = rs[i * 6 + 2];
            double clk = -Constants.CLIGHT * dts[i * 2];
            double el = azel[i * 2 + 1] * Constants.R2D;
            double az = azel[i * 2] * Constants.R2D;

            RtkTrace.safeCallback(cb, String.format(Locale.US,
                    "TRACE|PPP_STAGE1|gpst=%.3f|epoch=%d|sat=%s|x=%s|y=%s|z=%s|clk=%s|var=%.4f|svh=%d|el=%s|az=%s",
                    gpstVal, epoch, satId, f3(sx), f3(sy), f3(sz), f3(clk),
                    var[i], svh[i], f3(el), f3(az)));
        }
    }

    public static void tracePppUdstate(TraceControl ctrl, TraceCallback cb, int epoch,
                                       GTime time, Rtk rtk, int nx) {
        if (!RtkTrace.shouldTrace(ctrl, PPP_STAGE_UDSTATE, epoch)) return;

        double gpstVal = gpst(time);
        PrcOpt opt = rtk.opt;

        double posX = rtk.x[0], posY = rtk.x[1], posZ = rtk.x[2];
        double clk0 = rtk.x.length > NP(opt) + 0 ? rtk.x[IC(0, opt)] : 0.0;
        double ztd = 0.0;
        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
            ztd = rtk.x[IT(opt)];
        }

        RtkTrace.safeCallback(cb, String.format(Locale.US,
                "TRACE|PPP_STAGE2|gpst=%.3f|epoch=%d|x=%s|y=%s|z=%s|clk=%s|ztd=%s",
                gpstVal, epoch, f3(posX), f3(posY), f3(posZ), f3(clk0), f3(ztd)));

        boolean summaryOnly = (ctrl.contentFlags & TraceControl.CONTENT_SUMMARY_ONLY) != 0;
        if (summaryOnly) return;

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (!RtkTrace.isTargetSat(ctrl, sat)) continue;
            int nf = opt.ionoopt == Constants.IONOOPT_IFLC ? 1 : opt.nf;
            for (int f = 0; f < nf; f++) {
                int idx = IB(sat, f, opt);
                if (idx >= nx) continue;
                double bias = rtk.x[idx];
                double pvar = rtk.P[idx * nx + idx];
                if (bias == 0.0 && pvar == 0.0) continue;

                String satId = SatUtils.satno2id(sat);
                int slip = rtk.ssat[sat - 1].slip[f];
                long rejc = rtk.ssat[sat - 1].rejc[f];

                RtkTrace.safeCallback(cb, String.format(Locale.US,
                        "TRACE|PPP_STAGE2_BIAS|gpst=%.3f|epoch=%d|sat=%s|freq=%d|bias=%s|var=%s|slip=%d|rejc=%d",
                        gpstVal, epoch, satId, f, f3(bias), f3(pvar), slip, rejc));
            }
        }
    }

    public static void tracePppRes(TraceControl ctrl, TraceCallback cb, int epoch,
                                   GTime time, int nv, double[] v, double[] R,
                                   int nx, double[] H, PrcOpt opt) {
        if (!RtkTrace.shouldTrace(ctrl, PPP_STAGE_RES, epoch)) return;
        if (nv <= 0) return;

        double gpstVal = gpst(time);

        RtkTrace.safeCallback(cb, String.format(Locale.US,
                "TRACE|PPP_STAGE3|gpst=%.3f|epoch=%d|nv=%d", gpstVal, epoch, nv));

        StringBuilder vBuf = new StringBuilder();
        int maxV = Math.min(nv, 20);
        for (int i = 0; i < maxV; i++) {
            if (vBuf.length() > 0) vBuf.append("|");
            vBuf.append(String.format(Locale.US, "v%d=%s", i, f3(v[i])));
        }
        RtkTrace.safeCallback(cb, String.format(Locale.US,
                "TRACE|PPP_STAGE3_V|gpst=%.3f|epoch=%d|%s", gpstVal, epoch, vBuf.toString()));

        if (R != null) {
            StringBuilder rBuf = new StringBuilder();
            for (int i = 0; i < maxV; i++) {
                if (rBuf.length() > 0) rBuf.append("|");
                rBuf.append(String.format(Locale.US, "r%d=%.4f", i, R[i * nv + i]));
            }
            RtkTrace.safeCallback(cb, String.format(Locale.US,
                    "TRACE|PPP_STAGE3_R|gpst=%.3f|epoch=%d|%s", gpstVal, epoch, rBuf.toString()));
        }

        if (H != null && (ctrl.contentFlags & TraceControl.CONTENT_H_MATRIX) != 0) {
            StringBuilder hBuf = new StringBuilder();
            for (int i = 0; i < Math.min(nv, 10); i++) {
                if (hBuf.length() > 0) hBuf.append("|");
                hBuf.append(String.format(Locale.US, "row%d=%s,%s,%s", i,
                        f3(H[0 + nx * i]), f3(H[1 + nx * i]), f3(H[2 + nx * i])));
            }
            RtkTrace.safeCallback(cb, String.format(Locale.US,
                    "TRACE|PPP_STAGE3_H_POS|gpst=%.3f|epoch=%d|%s", gpstVal, epoch, hBuf.toString()));
        }
    }

    public static void tracePppFilter(TraceControl ctrl, TraceCallback cb, int epoch,
                                      GTime time, int info, double[] xp,
                                      double[] xpPrev, double[] Pp, int nx) {
        if (!RtkTrace.shouldTrace(ctrl, PPP_STAGE_FILTER, epoch)) return;

        double gpstVal = gpst(time);

        RtkTrace.safeCallback(cb, String.format(Locale.US,
                "TRACE|PPP_STAGE4|gpst=%.3f|epoch=%d|info=%d", gpstVal, epoch, info));

        if (xp != null && xpPrev != null) {
            StringBuilder dxBuf = new StringBuilder();
            int maxDx = Math.min(nx, 10);
            for (int i = 0; i < maxDx; i++) {
                if (dxBuf.length() > 0) dxBuf.append("|");
                dxBuf.append(String.format(Locale.US, "dx%d=%s", i, f3(xp[i] - xpPrev[i])));
            }
            RtkTrace.safeCallback(cb, String.format(Locale.US,
                    "TRACE|PPP_STAGE4_DXP|gpst=%.3f|epoch=%d|%s", gpstVal, epoch, dxBuf.toString()));
        }

        if (Pp != null) {
            StringBuilder pBuf = new StringBuilder();
            int maxP = Math.min(nx, 10);
            for (int i = 0; i < maxP; i++) {
                if (pBuf.length() > 0) pBuf.append("|");
                pBuf.append(String.format(Locale.US, "p%d=%s", i, f3(Pp[i * nx + i])));
            }
            RtkTrace.safeCallback(cb, String.format(Locale.US,
                    "TRACE|PPP_STAGE4_PDIAG|gpst=%.3f|epoch=%d|%s", gpstVal, epoch, pBuf.toString()));
        }
    }

    public static void tracePppResult(TraceControl ctrl, TraceCallback cb, int epoch,
                                      GTime time, Sol sol, int iter) {
        if (!RtkTrace.shouldTrace(ctrl, PPP_STAGE_RESULT, epoch)) return;

        double gpstVal = gpst(time);

        double[] pos = new double[3];
        if (sol.rr[0] != 0.0 || sol.rr[1] != 0.0 || sol.rr[2] != 0.0) {
            CoordTransform.ecef2pos(sol.rr, pos);
        }

        double sdn = sol.qr.length > 0 ? sol.qr[0] : 0.0;
        double sde = sol.qr.length > 1 ? sol.qr[1] : 0.0;
        double sdu = sol.qr.length > 2 ? sol.qr[2] : 0.0;

        RtkTrace.safeCallback(cb, String.format(Locale.US,
                "TRACE|PPP_STAGE5|gpst=%.3f|epoch=%d|Q=%d|lat=%s|lon=%s|h=%s|sdn=%s|sde=%s|sdu=%s|ns=%d|iter=%d",
                gpstVal, epoch, (int) sol.stat,
                f6(pos[0] * Constants.R2D), f6(pos[1] * Constants.R2D), f3(pos[2]),
                f3(sdn), f3(sde), f3(sdu),
                (int) sol.ns, iter));
    }

    private static int NP(PrcOpt opt) {
        return opt.dynamics == 0 ? 3 : 9;
    }

    private static int NC() {
        return Constants.NSYS;
    }

    private static int IC(int s, PrcOpt opt) {
        return NP(opt) + s;
    }

    private static int IT(PrcOpt opt) {
        int nt;
        if (opt.tropopt < Constants.TROPOPT_EST) nt = 0;
        else if (opt.tropopt < Constants.TROPOPT_ESTG) nt = 1;
        else nt = 3;
        return NP(opt) + NC() + nt;
    }

    private static int IB(int sat, int f, PrcOpt opt) {
        int nf = opt.ionoopt == Constants.IONOOPT_IFLC ? 1 : opt.nf;
        int np = opt.dynamics == 0 ? 3 : 9;
        int nc = Constants.NSYS;
        int ni = opt.ionoopt == Constants.IONOOPT_EST ? Constants.MAXSAT : 0;
        int nt;
        if (opt.tropopt < Constants.TROPOPT_EST) nt = 0;
        else if (opt.tropopt < Constants.TROPOPT_ESTG) nt = 1;
        else nt = 3;
        int nd = opt.nf >= 3 ? 1 : 0;
        int nr = np + nc + nt + ni + nd;
        return nr + Constants.MAXSAT * f + sat - 1;
    }
}