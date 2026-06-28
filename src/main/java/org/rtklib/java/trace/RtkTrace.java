package org.rtklib.java.trace;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;

import java.util.Locale;

public final class RtkTrace {
    private RtkTrace() {
    }

    public static boolean shouldTrace(TraceControl ctrl, int stageBit, int epoch) {
        if (ctrl == null || !ctrl.enabled) return false;
        if (ctrl.maxEpochs > 0 && epoch >= ctrl.maxEpochs) return false;
        if (ctrl.samplerate > 1 && epoch % ctrl.samplerate != 0) return false;
        if ((ctrl.stages & stageBit) == 0) return false;
        return true;
    }

    public static boolean isTargetSat(TraceControl ctrl, int sat) {
        if (ctrl == null || ctrl.targetSats == null || ctrl.targetSats.length == 0) return true;
        for (int ts : ctrl.targetSats) {
            if (ts == sat) return true;
        }
        return false;
    }

    public static void safeCallback(TraceCallback cb, String content) {
        if (cb == null) return;
        try {
            cb.onTrace(content);
        } catch (Exception ignored) {
        }
    }

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

    private static String f1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String f4(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    public static void traceStage0(TraceControl ctrl, TraceCallback cb, int epoch,
                                   Obsd[] obs, int nu, int nr, Nav nav, Ssat[] ssat) {
        if (!shouldTrace(ctrl, TraceControl.STAGE_INPUT, epoch)) return;

        int commonNs = 0;
        int roverValid = 0;
        int baseValid = 0;

        for (int i = 0; i < nu && i < obs.length; i++) {
            if (obs[i].L[0] != 0.0 || obs[i].P[0] != 0.0) roverValid = 1;
        }
        for (int i = nu; i < nu + nr && i < obs.length; i++) {
            if (obs[i].L[0] != 0.0 || obs[i].P[0] != 0.0) baseValid = 1;
        }

        boolean[] commonSat = new boolean[Constants.MAXSAT];
        for (int i = 0; i < nu && i < obs.length; i++) {
            int sat = obs[i].sat;
            if (sat < 1 || sat > Constants.MAXSAT) continue;
            for (int j = nu; j < nu + nr && j < obs.length; j++) {
                if (obs[j].sat == sat) {
                    commonSat[sat - 1] = true;
                    break;
                }
            }
        }
        for (int i = 0; i < Constants.MAXSAT; i++) {
            if (commonSat[i]) commonNs++;
        }

        int ephValid = commonNs > 0 ? 1 : 0;
        double gpstVal = (obs != null && obs.length > 0) ? gpst(obs[0].time) : 0.0;

        safeCallback(cb, String.format(Locale.US,
                "TRACE|STAGE0|gpst=%.3f|epoch=%d|rover_ns=%d|base_ns=%d|common_ns=%d|rover_valid=%d|base_valid=%d|eph_valid=%d",
                gpstVal, epoch, nu, nr, commonNs, roverValid, baseValid, ephValid));

        for (int i = 0; i < nu && i < obs.length; i++) {
            int sat = obs[i].sat;
            if (sat < 1 || sat > Constants.MAXSAT) continue;
            if (!commonSat[sat - 1]) continue;
            if (!isTargetSat(ctrl, sat)) continue;

            int baseIdx = -1;
            for (int j = nu; j < nu + nr && j < obs.length; j++) {
                if (obs[j].sat == sat) { baseIdx = j; break; }
            }

            String satId = SatUtils.satno2id(sat);
            double freq1 = SatUtils.sat2freq(sat, obs[i].code[0], nav);
            double roverL1 = freq1 > 0 ? obs[i].L[0] * Constants.CLIGHT / freq1 : 0.0;
            double roverL2 = 0.0;
            if (Constants.NFREQ > 1 && obs[i].code.length > 1) {
                double freq2 = SatUtils.sat2freq(sat, obs[i].code[1], nav);
                roverL2 = freq2 > 0 ? obs[i].L[1] * Constants.CLIGHT / freq2 : 0.0;
            }
            double roverP1 = obs[i].P.length > 0 ? obs[i].P[0] : 0.0;
            double roverP2 = 0.0;
            if (Constants.NFREQ > 1 && obs[i].P.length > 1) roverP2 = obs[i].P[1];
            double roverSNR = obs[i].SNR.length > 0 ? obs[i].SNR[0] : 0.0;

            double baseL1 = 0.0, baseL2 = 0.0, baseP1 = 0.0, baseP2 = 0.0, baseSNR = 0.0;
            if (baseIdx >= 0) {
                double bfreq1 = SatUtils.sat2freq(sat, obs[baseIdx].code[0], nav);
                baseL1 = bfreq1 > 0 ? obs[baseIdx].L[0] * Constants.CLIGHT / bfreq1 : 0.0;
                if (Constants.NFREQ > 1 && obs[baseIdx].code.length > 1) {
                    double bfreq2 = SatUtils.sat2freq(sat, obs[baseIdx].code[1], nav);
                    baseL2 = bfreq2 > 0 ? obs[baseIdx].L[1] * Constants.CLIGHT / bfreq2 : 0.0;
                }
                baseP1 = obs[baseIdx].P.length > 0 ? obs[baseIdx].P[0] : 0.0;
                if (Constants.NFREQ > 1 && obs[baseIdx].P.length > 1) baseP2 = obs[baseIdx].P[1];
                baseSNR = obs[baseIdx].SNR.length > 0 ? obs[baseIdx].SNR[0] : 0.0;
            }

            double el = 0.0, az = 0.0;
            if (ssat != null && sat - 1 < ssat.length) {
                el = ssat[sat - 1].azel[1] * Constants.R2D;
                az = ssat[sat - 1].azel[0] * Constants.R2D;
            }

            safeCallback(cb, String.format(Locale.US,
                    "TRACE|STAGE0_SAT|gpst=%.3f|epoch=%d|sat=%s|rover_L1=%s|rover_P1=%s|rover_SNR=%s|base_L1=%s|base_P1=%s|base_SNR=%s|el=%s|az=%s",
                    gpstVal, epoch, satId,
                    f3(roverL1), f3(roverP1), f1(roverSNR),
                    f3(baseL1), f3(baseP1), f1(baseSNR),
                    f1(el), f1(az)));
        }
    }

    public static void traceStage1(TraceControl ctrl, TraceCallback cb, int epoch,
                                   GTime time, int[] sat, int ns, int nf,
                                   double[] rs, double[] dts, Nav nav,
                                   double[] rr, int[] iu) {
        if (!shouldTrace(ctrl, TraceControl.STAGE_SATPOS, epoch)) return;

        double gpstVal = gpst(time);

        for (int i = 0; i < ns; i++) {
            int s = sat[i];
            if (!isTargetSat(ctrl, s)) continue;

            int obsIdx = (iu != null && i < iu.length) ? iu[i] : i;
            String satId = SatUtils.satno2id(s);
            double posX = rs[obsIdx * 6];
            double posY = rs[obsIdx * 6 + 1];
            double posZ = rs[obsIdx * 6 + 2];
            double clock = -Constants.CLIGHT * dts[obsIdx * 2];

            double trop = 0.0, iono = 0.0, geomDist = 0.0, pcv = 0.0;

            if (rr != null) {
                double[] rsi = {posX, posY, posZ};
                double[] ei = new double[3];
                geomDist = RtklibCommon.geodist(rsi, rr, ei);
                if (geomDist > 0.0) {
                    double[] pos = new double[3];
                    CoordTransform.ecef2pos(rr, pos);
                    double[] ae = new double[2];
                    double el = RtklibCommon.satazel(pos, ei, ae);
                    if (el > 0.0) {
                        double[] zazel = {0.0, Constants.PI / 2.0};
                        double zhd = TroposphereModel.saastamoinen(pos, zazel, 0.0, 293.15);
                        double mapfh = TroposphereModel.tropmapf(time, pos, ae, null);
                        trop = mapfh * zhd;
                    }
                }
            }

            safeCallback(cb, String.format(Locale.US,
                    "TRACE|STAGE1|gpst=%.3f|epoch=%d|sat=%s|pos_x=%s|pos_y=%s|pos_z=%s|clock=%s|trop=%s|iono=%s|geom_dist=%s|pcv=%s",
                    gpstVal, epoch, satId,
                    f3(posX), f3(posY), f3(posZ), f6(clock),
                    f3(trop), f3(iono), f3(geomDist), f3(pcv)));
        }
    }

    public static void traceStage2(TraceControl ctrl, TraceCallback cb, int epoch,
                                   GTime time, Rtk rtk, int[] sat, int ns, int nf) {
        if (!shouldTrace(ctrl, TraceControl.STAGE_UDSTATE, epoch)) return;

        double gpstVal = gpst(time);
        boolean summaryOnly = (ctrl.contentFlags & TraceControl.CONTENT_SUMMARY_ONLY) != 0;

        safeCallback(cb, String.format(Locale.US,
                "TRACE|STAGE2|gpst=%.3f|epoch=%d|pos_x=%s|pos_y=%s|pos_z=%s",
                gpstVal, epoch,
                f3(rtk.x[0] + rtk.rb[0]),
                f3(rtk.x[1] + rtk.rb[1]),
                f3(rtk.x[2] + rtk.rb[2])));

        if (!summaryOnly) {
            for (int i = 0; i < ns; i++) {
                int s = sat[i];
                if (!isTargetSat(ctrl, s)) continue;

                String satId = SatUtils.satno2id(s);
                for (int frq = 0; frq < nf; frq++) {
                    int idx = IB(s, frq, nf, rtk.opt);
                    double bias = (idx < rtk.nx) ? rtk.x[idx] : 0.0;
                    double var = (idx < rtk.nx) ? rtk.P[idx * rtk.nx + idx] : 0.0;
                    int slip = (s - 1 < rtk.ssat.length && frq < rtk.ssat[s - 1].slip.length)
                            ? (rtk.ssat[s - 1].slip[frq] != 0 ? 1 : 0) : 0;
                    int rejc = (s - 1 < rtk.ssat.length && frq < rtk.ssat[s - 1].rejc.length)
                            ? (int) rtk.ssat[s - 1].rejc[frq] : 0;

                    safeCallback(cb, String.format(Locale.US,
                            "TRACE|STAGE2_BIAS|gpst=%.3f|epoch=%d|sat=%s|freq=%d|bias=%s|var=%s|slip=%d|rejc=%d",
                            gpstVal, epoch, satId, frq, f3(bias), f3(var), slip, rejc));
                }
            }
        }

        int globalReset = 0;
        StringBuilder partialSats = new StringBuilder();
        for (int i = 0; i < ns; i++) {
            int s = sat[i];
            if (s - 1 >= rtk.ssat.length) continue;
            boolean hasSlip = false;
            for (int frq = 0; frq < nf; frq++) {
                if (rtk.ssat[s - 1].slip[frq] != 0) { hasSlip = true; break; }
            }
            if (hasSlip) {
                if (partialSats.length() > 0) partialSats.append(",");
                partialSats.append(SatUtils.satno2id(s));
            }
        }

        safeCallback(cb, String.format(Locale.US,
                "TRACE|STAGE2_RESET|gpst=%.3f|epoch=%d|global=%d|partial=%s",
                gpstVal, epoch, globalReset, partialSats.toString()));
    }

    public static void traceStage3(TraceControl ctrl, TraceCallback cb, int epoch,
                                   GTime time, int refSat, int[] sat, int ns, int nf,
                                   double[] v, int nv, double[] R, double[] H,
                                   int nx, PrcOpt opt) {
        if (!shouldTrace(ctrl, TraceControl.STAGE_DDRES, epoch)) return;

        double gpstVal = gpst(time);
        boolean outputH = (ctrl.contentFlags & TraceControl.CONTENT_H_MATRIX) != 0;

        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < ns; i++) {
            if (sat[i] == refSat) continue;
            if (pairs.length() > 0) pairs.append(",");
            pairs.append(SatUtils.satno2id(refSat)).append("-").append(SatUtils.satno2id(sat[i]));
        }

        safeCallback(cb, String.format(Locale.US,
                "TRACE|STAGE3|gpst=%.3f|epoch=%d|ref=%s|pairs=%s|nv=%d",
                gpstVal, epoch, SatUtils.satno2id(refSat), pairs.toString(), nv));

        if (nv <= 0) return;

        StringBuilder vBuf = new StringBuilder();
        for (int i = 0; i < nv; i++) {
            if (i > 0) vBuf.append("|");
            vBuf.append(String.format(Locale.US, "v%d=%s", i, f3(v[i])));
        }
        safeCallback(cb, String.format(Locale.US, "TRACE|STAGE3_V|gpst=%.3f|epoch=%d|%s",
                gpstVal, epoch, vBuf.toString()));

        StringBuilder rBuf = new StringBuilder();
        for (int i = 0; i < nv; i++) {
            if (i > 0) rBuf.append("|");
            rBuf.append(String.format(Locale.US, "r%d=%s", i, f4(R[i * nv + i])));
        }
        safeCallback(cb, String.format(Locale.US, "TRACE|STAGE3_R|gpst=%.3f|epoch=%d|%s",
                gpstVal, epoch, rBuf.toString()));

        if (outputH && H != null) {
            for (int row = 0; row < nv; row++) {
                StringBuilder rowBuf = new StringBuilder();
                rowBuf.append(String.format(Locale.US, "row%d=", row));
                for (int col = 0; col < 3; col++) {
                    if (col > 0) rowBuf.append(",");
                    rowBuf.append(f3(H[row * nx + col]));
                }
                safeCallback(cb, String.format(Locale.US, "TRACE|STAGE3_H_POS|gpst=%.3f|epoch=%d|%s",
                        gpstVal, epoch, rowBuf.toString()));
            }

            StringBuilder biasBuf = new StringBuilder();
            for (int i = 0; i < ns; i++) {
                for (int frq = 0; frq < nf; frq++) {
                    int idx = IB(sat[i], frq, nf, opt);
                    if (idx >= nx) continue;
                    double coeff = 0.0;
                    for (int row = 0; row < nv; row++) {
                        double val = H[row * nx + idx];
                        if (val != 0.0) { coeff = val; break; }
                    }
                    if (biasBuf.length() > 0) biasBuf.append("|");
                    biasBuf.append(String.format(Locale.US, "%s=%s", SatUtils.satno2id(sat[i]), f3(coeff)));
                }
            }
            if (biasBuf.length() > 0) {
                safeCallback(cb, String.format(Locale.US, "TRACE|STAGE3_H_BIAS|gpst=%.3f|epoch=%d|%s",
                        gpstVal, epoch, biasBuf.toString()));
            }
        }
    }

    public static void traceStage4(TraceControl ctrl, TraceCallback cb, int epoch,
                                   GTime time, int info, double[] xp, double[] xpPrev,
                                   int nx, double[] Pp) {
        if (!shouldTrace(ctrl, TraceControl.STAGE_FILTER, epoch)) return;

        double gpstVal = gpst(time);

        safeCallback(cb, String.format(Locale.US,
                "TRACE|STAGE4|gpst=%.3f|epoch=%d|info=%d",
                gpstVal, epoch, info));

        if (xp != null && xp.length > 0) {
            int len = Math.min(xp.length, nx);
            StringBuilder dxBuf = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) dxBuf.append("|");
                double dx = (xpPrev != null && i < xpPrev.length) ? xp[i] - xpPrev[i] : 0.0;
                dxBuf.append(String.format(Locale.US, "dx%d=%s", i, f3(dx)));
            }
            safeCallback(cb, String.format(Locale.US, "TRACE|STAGE4_DXP|gpst=%.3f|epoch=%d|%s",
                    gpstVal, epoch, dxBuf.toString()));
        }

        if (Pp != null && Pp.length > 0) {
            int len = Math.min(nx, (int) Math.sqrt(Pp.length));
            StringBuilder pBuf = new StringBuilder();
            for (int i = 0; i < len; i++) {
                if (i > 0) pBuf.append("|");
                pBuf.append(String.format(Locale.US, "p%d=%s", i, f3(Pp[i * nx + i])));
            }
            safeCallback(cb, String.format(Locale.US, "TRACE|STAGE4_PDIAG|gpst=%.3f|epoch=%d|%s",
                    gpstVal, epoch, pBuf.toString()));
        }
    }

    public static void traceStage5(TraceControl ctrl, TraceCallback cb, int epoch,
                                   GTime time, int fixed, double ratio,
                                   int[] sat, int ns, int nf,
                                   Rtk rtk, double[] xa, double[] dxShift) {
        if (!shouldTrace(ctrl, TraceControl.STAGE_LAMBDA, epoch)) return;

        double gpstVal = gpst(time);
        boolean summaryOnly = (ctrl.contentFlags & TraceControl.CONTENT_SUMMARY_ONLY) != 0;

        safeCallback(cb, String.format(Locale.US,
                "TRACE|STAGE5|gpst=%.3f|epoch=%d|fixed=%d|ratio=%s",
                gpstVal, epoch, fixed, f3(ratio)));

        if (summaryOnly) return;

        StringBuilder floatBuf = new StringBuilder();
        for (int i = 0; i < ns; i++) {
            if (!isTargetSat(ctrl, sat[i])) continue;
            for (int frq = 0; frq < nf; frq++) {
                int idx = IB(sat[i], frq, nf, rtk.opt);
                if (idx >= rtk.nx) continue;
                if (floatBuf.length() > 0) floatBuf.append("|");
                floatBuf.append(String.format(Locale.US, "%s_L%d=%s",
                        SatUtils.satno2id(sat[i]), frq, f3(rtk.x[idx])));
            }
        }
        safeCallback(cb, String.format(Locale.US, "TRACE|STAGE5_FLOAT|gpst=%.3f|epoch=%d|%s",
                gpstVal, epoch, floatBuf.toString()));

        if (fixed == 1 && xa != null) {
            StringBuilder fixedBuf = new StringBuilder();
            for (int i = 0; i < ns; i++) {
                if (!isTargetSat(ctrl, sat[i])) continue;
                for (int frq = 0; frq < nf; frq++) {
                    int idx = IB(sat[i], frq, nf, rtk.opt);
                    if (idx >= rtk.nx) continue;
                    if (fixedBuf.length() > 0) fixedBuf.append("|");
                    fixedBuf.append(String.format(Locale.US, "%s_L%d=%s",
                            SatUtils.satno2id(sat[i]), frq, f3(xa[idx])));
                }
            }
            safeCallback(cb, String.format(Locale.US, "TRACE|STAGE5_FIXED|gpst=%.3f|epoch=%d|%s",
                    gpstVal, epoch, fixedBuf.toString()));
        } else {
            safeCallback(cb, "TRACE|STAGE5_FIXED|null");
        }

        if (dxShift != null && dxShift.length >= 3) {
            safeCallback(cb, String.format(Locale.US,
                    "TRACE|STAGE5_SHIFT|gpst=%.3f|epoch=%d|dx=%s|dy=%s|dz=%s",
                    gpstVal, epoch, f3(dxShift[0]), f3(dxShift[1]), f3(dxShift[2])));
        } else {
            safeCallback(cb, String.format(Locale.US,
                    "TRACE|STAGE5_SHIFT|dx=%s|dy=%s|dz=%s",
                    f3(0.0), f3(0.0), f3(0.0)));
        }
    }

    public static void traceStage6(TraceControl ctrl, TraceCallback cb, int epoch,
                                   GTime time, Sol sol, int iter) {
        if (!shouldTrace(ctrl, TraceControl.STAGE_RESULT, epoch)) return;

        double gpstVal = gpst(time);

        double[] pos = new double[3];
        if (sol.rr[0] != 0.0 || sol.rr[1] != 0.0 || sol.rr[2] != 0.0) {
            CoordTransform.ecef2pos(sol.rr, pos);
        }

        double sdn = sol.qr.length > 0 ? sol.qr[0] : 0.0;
        double sde = sol.qr.length > 1 ? sol.qr[1] : 0.0;
        double sdu = sol.qr.length > 2 ? sol.qr[2] : 0.0;

        safeCallback(cb, String.format(Locale.US,
                "TRACE|STAGE6|gpst=%.3f|epoch=%d|Q=%d|lat=%s|lon=%s|h=%s|sdn=%s|sde=%s|sdu=%s|ns=%d|iter=%d",
                gpstVal, epoch, (int) sol.stat,
                f6(pos[0] * Constants.R2D), f6(pos[1] * Constants.R2D), f3(pos[2]),
                f3(sdn), f3(sde), f3(sdu),
                (int) sol.ns, iter));
    }

    private static int IB(int sat, int f, int nf, PrcOpt opt) {
        int nr;
        if (opt != null) {
            int np = opt.dynamics == 0 ? 3 : 9;
            int ni = opt.ionoopt != Constants.IONOOPT_EST ? 0 : Constants.MAXSAT;
            int nt;
            if (opt.tropopt < Constants.TROPOPT_EST) nt = 0;
            else if (opt.tropopt < Constants.TROPOPT_ESTG) nt = 2;
            else nt = 6;
            int nl = opt.glomodear != Constants.GLO_ARMODE_AUTOCAL ? 0 : Constants.NFREQGLO;
            nr = np + ni + nt + nl;
        } else {
            nr = 3;
        }
        int nfreq = (nf > 0) ? nf : Constants.NFREQ;
        return nr + Constants.MAXSAT * f + (sat - 1);
    }
}

