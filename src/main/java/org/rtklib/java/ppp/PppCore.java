package org.rtklib.java.ppp;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.trace.PppTrace;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.rtklib.java.troposphere.TroposphereModel;

public final class PppCore {
    private PppCore() {
    }

    private static final int MAX_ITER = 8;
    private static final int MIN_NSAT_SOL = 4;

    private static final double VAR_POS = 60.0 * 60.0;
    private static final double VAR_CLK = 60.0 * 60.0;
    private static final double VAR_ZTD = 0.6 * 0.6;
    private static final double VAR_GRA = 0.01 * 0.01;
    private static final double VAR_DCB = 30.0 * 30.0;
    private static final double VAR_BIAS = 60.0 * 60.0;
    private static final double VAR_IONO = 60.0 * 60.0;
    private static final double VAR_GLO_IFB = 0.6 * 0.6;

    private static final double ERR_SAAS = 0.3;
    private static final double REL_HUMI = 0.7;

    private static final double EFACT_GPS = 1.0;
    private static final double EFACT_GLO = 1.5;
    private static final double EFACT_GAL = 1.0;
    private static final double EFACT_QZS = 1.0;
    private static final double EFACT_CMP = 1.5;
    private static final double EFACT_IRN = 1.5;
    private static final double EFACT_SBS = 3.0;
    private static final double EFACT_GPS_L5 = 10.0;

    private static int NF(PrcOpt opt) {
        return opt.ionoopt == Constants.IONOOPT_IFLC ? 1 : opt.nf;
    }

    private static int NP(PrcOpt opt) {
        return opt.dynamics == 0 ? 3 : 9;
    }

    private static int NC() {
        return Constants.NSYS;
    }

    private static int NT(PrcOpt opt) {
        if (opt.tropopt < Constants.TROPOPT_EST) return 0;
        if (opt.tropopt == Constants.TROPOPT_EST) return 1;
        return 3;
    }

    private static int NI(PrcOpt opt) {
        return opt.ionoopt == Constants.IONOOPT_EST ? Constants.MAXSAT : 0;
    }

    private static int ND(PrcOpt opt) {
        return opt.nf >= 3 ? 1 : 0;
    }

    private static int NR(PrcOpt opt) {
        return NP(opt) + NC() + NT(opt) + NI(opt) + ND(opt);
    }

    private static int NB(PrcOpt opt) {
        return NF(opt) * Constants.MAXSAT;
    }

    public static int pppnx(PrcOpt opt) {
        return NR(opt) + NB(opt);
    }

    private static int IC(int s, PrcOpt opt) {
        return NP(opt) + s;
    }

    private static int IT(PrcOpt opt) {
        return NP(opt) + NC();
    }

    private static int II(int sat, PrcOpt opt) {
        return NP(opt) + NC() + NT(opt) + sat - 1;
    }

    private static int ID(PrcOpt opt) {
        return NP(opt) + NC() + NT(opt) + NI(opt);
    }

    private static int IB(int sat, int f, PrcOpt opt) {
        return NR(opt) + Constants.MAXSAT * f + sat - 1;
    }

    private static void initx(double[] x, double[] P, int nx, double xi, double var, int i) {
        x[i] = xi;
        for (int j = 0; j < nx; j++) {
            P[i * nx + j] = 0.0;
            P[j * nx + i] = 0.0;
        }
        P[i * nx + i] = var;
    }

    public static void pppos(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        PrcOpt opt = rtk.opt;
        int nx = pppnx(opt);
        TraceControl ctrl = rtk.traceControl;
        TraceCallback cb = rtk.traceCallback;
        rtk.epoch++;

        if (rtk.nx == 0 || rtk.nx != nx) {
            rtk.nx = nx;
            rtk.x = new double[nx];
            rtk.P = new double[nx * nx];
            rtk.xa = new double[nx];
            rtk.Pa = new double[nx * nx];
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

        udstate_ppp(rtk, obs, n, nav, nx);

        PppTrace.tracePppInput(ctrl, cb, rtk.epoch, obs[0].time, obs, n, nav);

        EphModel.satposs(obs[0].time, obs, n, nav, rs, dts, var, svh, opt.sateph);

        PppTrace.tracePppUdstate(ctrl, cb, rtk.epoch, obs[0].time, rtk, nx);

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

            int nv = pppRes(0, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, v, H, R, azel, nx);

            if (iter == 0) {
                PppTrace.tracePppSatPos(ctrl, cb, rtk.epoch, obs[0].time, obs, n, rs, dts, var, svh, azel, exc);
            }

            PppTrace.tracePppRes(ctrl, cb, rtk.epoch, obs[0].time, nv, v, R, nx, H, opt);

            if (nv == 0) break;

            double[] xpPrev = new double[nx];
            System.arraycopy(xp, 0, xpPrev, 0, nx);

            int info = KalmanFilter.update(xp, Pp, H, v, R, nx, nv);

            PppTrace.tracePppFilter(ctrl, cb, rtk.epoch, obs[0].time, info, xp, xpPrev, Pp, nx);

            if (info != 0) break;

            if (pppRes(iter + 1, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, null, null, null, azel, nx) != 0) {
                System.arraycopy(xp, 0, rtk.x, 0, nx);
                System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);
                stat = Constants.SOLQ_PPP;
                break;
            }
        }

        if (stat == Constants.SOLQ_PPP) {
            updateStat(rtk, obs, n, stat, nx);
        }

        PppTrace.tracePppResult(ctrl, cb, rtk.epoch, obs[0].time, rtk.sol, MAX_ITER);
    }

    private static void udstate_ppp(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        udpos_ppp(rtk, nx);
        udclk_ppp(rtk, nx);
        if (rtk.opt.tropopt == Constants.TROPOPT_EST || rtk.opt.tropopt == Constants.TROPOPT_ESTG) {
            udtrop_ppp(rtk, nx);
        }
        if (rtk.opt.ionoopt == Constants.IONOOPT_EST) {
            udiono_ppp(rtk, obs, n, nav, nx);
        }
        if (rtk.opt.nf >= 3) {
            uddcb_ppp(rtk, nx);
        }
        udbias_ppp(rtk, obs, n, nav, nx);
    }

    private static void udpos_ppp(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        if (rtk.sol.stat == Constants.SOLQ_NONE) {
            for (int i = 0; i < 3; i++) {
                initx(x, P, nx, rtk.sol.rr[i], VAR_POS, i);
            }
            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) initx(x, P, nx, 0.0, 10.0 * 10.0, i);
                for (int i = 6; i < 9; i++) initx(x, P, nx, 0.0, 10.0 * 10.0, i);
            }
        } else if (rtk.tt != 0.0) {
            if (opt.dynamics != 0) {
                for (int i = 0; i < 6; i++) x[i] += rtk.tt * x[i + 3];
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
    }

    private static void udclk_ppp(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;
        double dtr = 0.0;

        if (rtk.sol.stat == Constants.SOLQ_NONE) {
            for (int i = 0; i < NC(); i++) {
                initx(x, P, nx, 0.0, VAR_CLK, IC(i, opt));
            }
        } else {
            dtr = x[IC(0, opt)];
        }

        for (int i = 0; i < NC(); i++) {
            int idx = IC(i, opt);
            x[idx] = dtr;
            P[idx * nx + idx] += SQR(opt.sclkstab) * Math.abs(rtk.tt);
        }
    }

    private static void udtrop_ppp(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;
        double[] pos = new double[3];
        double[] zazel = {0.0, Constants.PI / 2.0};

        CoordTransform.ecef2pos(rtk.x, pos);
        double zhd = TroposphereModel.saastamoinen(pos, zazel, REL_HUMI, 293.15);

        if (rtk.opt.tropopt == Constants.TROPOPT_EST) {
            if (rtk.sol.stat == Constants.SOLQ_NONE) {
                initx(x, P, nx, zhd, VAR_ZTD, IT(opt));
            }
            int idx = IT(opt);
            P[idx * nx + idx] += SQR(opt.prn[2]) * Math.abs(rtk.tt);
        } else if (rtk.opt.tropopt == Constants.TROPOPT_ESTG) {
            if (rtk.sol.stat == Constants.SOLQ_NONE) {
                initx(x, P, nx, zhd, VAR_ZTD, IT(opt));
                initx(x, P, nx, 1E-6, VAR_GRA, IT(opt) + 1);
                initx(x, P, nx, 1E-6, VAR_GRA, IT(opt) + 2);
            }
            for (int i = 0; i < 3; i++) {
                int idx = IT(opt) + i;
                P[idx * nx + idx] += SQR(opt.prn[2]) * Math.abs(rtk.tt);
            }
        }
    }

    private static void udiono_ppp(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            int idx = II(i + 1, opt);
            if (rtk.sol.stat == Constants.SOLQ_NONE) {
                initx(x, P, nx, 0.0, VAR_IONO, idx);
            }
        }

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            int idx = II(sat, opt);
            double sinel = Math.sin(rtk.ssat[sat - 1].azel[1]);
            P[idx * nx + idx] += SQR(opt.prn[1] / Math.max(sinel, 0.1)) * Math.abs(rtk.tt);
        }
    }

    private static void uddcb_ppp(Rtk rtk, int nx) {
        int idx = ID(rtk.opt);
        if (rtk.x[idx] == 0.0) {
            initx(rtk.x, rtk.P, nx, 1E-6, VAR_DCB, idx);
        }
    }

    private static void udbias_ppp(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < opt.nf; j++) {
                rtk.ssat[i].slip[j] = 0;
            }
        }

        detslpLl(rtk, obs, n);
        detslpGf(rtk, obs, n, nav);

        for (int f = 0; f < NF(opt); f++) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (++rtk.ssat[i].outc[f] > opt.maxout) {
                    initx(x, P, nx, 0.0, 0.0, IB(i + 1, f, opt));
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

                corrMeas(obs[i], nav, rtk.ssat[sat - 1].azel, opt, dantr, dants, 0.0, L, P_arr, Lc, Pc);

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
                            ion = (obs[i].P[0] - obs[i].P[f]) / (1.0 - SQR(freq1 / freq2));
                        }
                        bias[i] = L[f] - P_arr[f] + 2.0 * ion * SQR(freq1 / freq2);
                    }
                }

                if (x[j] == 0.0 || slip[i] != 0 || bias[i] == 0.0) continue;

                offset += bias[i] - x[j];
                k++;
            }

            if (k >= 2 && Math.abs(offset / k) > 0.0005 * Constants.CLIGHT) {
                for (int i = 0; i < Constants.MAXSAT; i++) {
                    int j = IB(i + 1, f, opt);
                    if (x[j] != 0.0) x[j] += offset / k;
                }
            }

            for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
                int sat = obs[i].sat;
                int j = IB(sat, f, opt);

                P[j * nx + j] += SQR(opt.prn[0]) * Math.abs(rtk.tt);

                if (bias[i] == 0.0 || (x[j] != 0.0 && slip[i] == 0)) continue;

                initx(x, P, nx, bias[i], VAR_BIAS, IB(sat, f, opt));
            }
        }
    }

    private static void detslpLl(Rtk rtk, Obsd[] obs, int n) {
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            for (int j = 0; j < rtk.opt.nf; j++) {
                if (obs[i].L[j] == 0.0) continue;
                if ((obs[i].LLI[j] & 1) != 0 || (obs[i].LLI[j] & 2) != 0) {
                    rtk.ssat[obs[i].sat - 1].slip[j] = 1;
                }
            }
        }
    }

    private static void detslpGf(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            double g1 = gfmeas(obs[i], nav);
            if (g1 == 0.0) continue;

            double g0 = rtk.ssat[obs[i].sat - 1].gf[0];
            rtk.ssat[obs[i].sat - 1].gf[0] = g1;

            if (g0 != 0.0 && Math.abs(g1 - g0) > rtk.opt.thresslip) {
                for (int j = 0; j < rtk.opt.nf; j++) {
                    rtk.ssat[obs[i].sat - 1].slip[j] |= 1;
                }
            }
        }
    }

    private static double gfmeas(Obsd obs, Nav nav) {
        double freq1 = SatUtils.sat2freq(obs.sat, obs.code[0], nav);
        double freq2 = SatUtils.sat2freq(obs.sat, obs.code[1], nav);
        if (freq1 == 0.0 || freq2 == 0.0 || obs.L[0] == 0.0 || obs.L[1] == 0.0) return 0.0;
        return (obs.L[0] / freq1 - obs.L[1] / freq2) * Constants.CLIGHT;
    }

    private static void corrMeas(Obsd obs, Nav nav, double[] azel, PrcOpt opt,
                                 double[] dantr, double[] dants, double phw,
                                 double[] L, double[] P, double[] Lc, double[] Pc) {
        double[] freq = new double[Constants.NFREQ];
        Lc[0] = Pc[0] = 0.0;

        for (int i = 0; i < opt.nf; i++) {
            L[i] = P[i] = 0.0;
            freq[i] = SatUtils.sat2freq(obs.sat, obs.code[i], nav);
            if (freq[i] == 0.0 || obs.L[i] == 0.0 || obs.P[i] == 0.0) continue;

            L[i] = obs.L[i] * Constants.CLIGHT / freq[i] - dants[i] - dantr[i] - phw * Constants.CLIGHT / freq[i];
            P[i] = obs.P[i] - dants[i] - dantr[i];
        }

        int frq2 = L[1] == 0.0 ? 2 : 1;
        if (freq[0] == 0.0 || freq[frq2] == 0.0) return;

        double C1 = SQR(freq[0]) / (SQR(freq[0]) - SQR(freq[frq2]));
        double C2 = -SQR(freq[frq2]) / (SQR(freq[0]) - SQR(freq[frq2]));

        if (L[0] != 0.0 && L[frq2] != 0.0) Lc[0] = C1 * L[0] + C2 * L[frq2];
        if (P[0] != 0.0 && P[frq2] != 0.0) Pc[0] = C1 * P[0] + C2 * P[frq2];
    }

    private static int pppRes(int post, Obsd[] obs, int n, double[] rs, double[] dts,
                              double[] varRs, int[] svh, int[] exc,
                              Nav nav, double[] x, Rtk rtk, double[] v, double[] H,
                              double[] R, double[] azel, int nx) {
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
        double[] varr = new double[n * 2 * NF(opt)];
        int nv = 0;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < opt.nf; j++) rtk.ssat[i].vsat[j] = 0;
        }

        for (int i = 0; i < 3; i++) rr[i] = x[i];
        CoordTransform.ecef2pos(rr, pos);

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            int sys = SatUtils.satsys(sat, null);

            double[] rsi = new double[3];
            rsi[0] = rs[i * 6];
            rsi[1] = rs[i * 6 + 1];
            rsi[2] = rs[i * 6 + 2];
            double r = RtklibCommon.geodist(rsi, rr, e);
            if (r <= 0.0) {
                exc[i] = 1;
                continue;
            }

            double[] azelI = new double[2];
            double el = RtklibCommon.satazel(pos, e, azelI);
            azel[i * 2] = azelI[0];
            azel[i * 2 + 1] = azelI[1];
            if (el < opt.elmin) {
                exc[i] = 1;
                continue;
            }

            rtk.ssat[sat - 1].azel[0] = azelI[0];
            rtk.ssat[sat - 1].azel[1] = azelI[1];

            if (sys == 0 || rtk.ssat[sat - 1].vs == 0 ||
                    RtklibCommon.satexclude(sat, varRs[i], svh[i], opt) != 0 || exc[i] != 0) {
                exc[i] = 1;
                continue;
            }

            double dtrp = 0.0;
            double vart = 0.0;
            if (!modelTrop(obs[i].time, pos, azelI, opt, x, dtdx, nav)) continue;
            dtrp = dtdx[0];

            double dion = 0.0;
            double vari = 0.0;
            modelIono(obs[i].time, pos, azelI, opt, sat, x, nav);

            for (int j = 0; j < Constants.NFREQ; j++) dantr[j] = dants[j] = 0.0;

            double phw = rtk.ssat[sat - 1].phw;
            corrMeas(obs[i], nav, azelI, opt, dantr, dants, phw, L, P_arr, Lc, Pc);

            for (int j = 0; j < 2 * NF(opt); j++) {
                int code = j % 2;
                int frq = j / 2;
                double y;
                double C = 0.0;
                double dcb = 0.0;
                double bias = 0.0;

                if (opt.ionoopt == Constants.IONOOPT_IFLC) {
                    y = code == 0 ? Lc[0] : Pc[0];
                    if (y == 0.0) continue;
                } else {
                    y = code == 0 ? L[frq] : P_arr[frq];
                    if (y == 0.0) continue;
                    double freq = SatUtils.sat2freq(sat, obs[i].code[frq], nav);
                    if (freq == 0.0) continue;
                    C = SQR(Constants.FREQL1 / freq) * (code == 0 ? -1.0 : 1.0);
                }

                if (H != null) {
                    for (int kk = 0; kk < nx; kk++) H[kk + nx * nv] = 0.0;
                    for (int kk = 0; kk < 3; kk++) H[kk + nx * nv] = -e[kk];
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
                    H[IC(k, opt) + nx * nv] = 1.0;
                    if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
                        int nt = opt.tropopt >= Constants.TROPOPT_ESTG ? 3 : 1;
                        for (int kk = 0; kk < nt; kk++) {
                            H[IT(opt) + kk + nx * nv] = dtdx[kk];
                        }
                    }
                }

                if (opt.ionoopt == Constants.IONOOPT_EST) {
                    if (x[II(sat, opt)] == 0.0) continue;
                    if (H != null) H[II(sat, opt) + nx * nv] = C * ionmapf(pos, azelI);
                }

                if (frq == 2 && code == 1) {
                    dcb = x[ID(opt)];
                    if (H != null) H[ID(opt) + nx * nv] = 1.0;
                }

                if (code == 0) {
                    bias = x[IB(sat, frq, opt)];
                    if (bias == 0.0) continue;
                    if (H != null) H[IB(sat, frq, opt) + nx * nv] = 1.0;
                }

                double res = y - (r + cdtr - Constants.CLIGHT * dts[i * 2] + dtrp + C * dion + dcb + bias);
                if (v != null) v[nv] = res;

                if (code == 0) rtk.ssat[sat - 1].resc[frq] = res;
                else rtk.ssat[sat - 1].resp[frq] = res;

                varr[nv] = varerr(sat, sys, azelI[1], rtk.ssat[sat - 1].snrRover[frq], j, opt);
                varr[nv] += vart + SQR(C) * vari + varRs[i];
                if (sys == Constants.SYS_GLO && code == 1) varr[nv] += VAR_GLO_IFB;

                if (post == 0 && opt.maxinno[code] > 0.0 && Math.abs(res) > opt.maxinno[code]) {
                    exc[i] = 1;
                    rtk.ssat[sat - 1].rejc[frq]++;
                    continue;
                }

                if (code == 0) rtk.ssat[sat - 1].vsat[frq] = 1;
                nv++;
            }
        }

        if (R != null) {
            for (int j = 0; j < nv; j++) {
                for (int i = 0; i < nv; i++) R[i + j * nv] = 0.0;
                R[j + j * nv] = varr[j];
            }
        }

        return post != 0 ? 1 : nv;
    }

    private static boolean modelTrop(GTime time, double[] pos, double[] azel,
                                     PrcOpt opt, double[] x, double[] dtdx, Nav nav) {
        if (opt.tropopt == Constants.TROPOPT_SAAS) {
            dtdx[0] = TroposphereModel.saastamoinen(pos, azel, REL_HUMI, 293.15);
            dtdx[1] = 0.0;
            dtdx[2] = 0.0;
            return true;
        }
        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
            double[] trp = new double[3];
            int nt = opt.tropopt == Constants.TROPOPT_EST ? 1 : 3;
            for (int i = 0; i < nt; i++) trp[i] = x[IT(opt) + i];
            tropModelPrec(time, pos, azel, trp, dtdx);
            return true;
        }
        dtdx[0] = 0.0;
        dtdx[1] = 0.0;
        dtdx[2] = 0.0;
        return true;
    }

    private static void tropModelPrec(GTime time, double[] pos, double[] azel,
                                      double[] x, double[] dtdx) {
        double[] zazel = {0.0, Constants.PI / 2.0};
        double zhd = TroposphereModel.saastamoinen(pos, zazel, REL_HUMI, 293.15);

        double[] mapfw = new double[1];
        double mH = TroposphereModel.tropmapf(time, pos, azel, mapfw);
        double mW = mapfw[0];

        double el = azel[1];
        dtdx[1] = 0.0;
        dtdx[2] = 0.0;
        if (el > 0.0) {
            double cotz = 1.0 / Math.tan(el);
            double gradN = mW * cotz * Math.cos(azel[0]);
            double gradE = mW * cotz * Math.sin(azel[0]);
            mW += gradN * x[1] + gradE * x[2];
            dtdx[1] = gradN * (x[0] - zhd);
            dtdx[2] = gradE * (x[0] - zhd);
        }
        dtdx[0] = mH * zhd + mW * (x[0] - zhd);
    }

    private static void modelIono(GTime time, double[] pos, double[] azel,
                                  PrcOpt opt, int sat, double[] x, Nav nav) {
    }

    private static double ionmapf(double[] pos, double[] azel) {
        double el = azel[1];
        if (el <= 0.0) return 0.0;
        return 1.0 / Math.cos(Math.max(Constants.PI / 2.0 - el, 0.1));
    }

    private static double varerr(int sat, int sys, double el, float snr, int j,
                                 PrcOpt opt) {
        double fact = 1.0;
        int frq = j / 2;
        int code = j % 2;

        if (code != 0) {
            fact = opt.eratio[frq < opt.eratio.length ? frq : 0];
        }
        if (fact <= 0.0) fact = opt.eratio[0];

        switch (sys) {
            case Constants.SYS_GPS: fact *= EFACT_GPS; break;
            case Constants.SYS_GLO: fact *= EFACT_GLO; break;
            case Constants.SYS_GAL: fact *= EFACT_GAL; break;
            case Constants.SYS_SBS: fact *= EFACT_SBS; break;
            case Constants.SYS_QZS: fact *= EFACT_QZS; break;
            case Constants.SYS_CMP: fact *= EFACT_CMP; break;
            case Constants.SYS_IRN: fact *= EFACT_IRN; break;
            default: fact *= EFACT_GPS; break;
        }

        if ((sys == Constants.SYS_GPS || sys == Constants.SYS_QZS) && frq == 2) {
            fact *= EFACT_GPS_L5;
        }

        double a = fact * opt.err[1];
        double b = fact * opt.err[2];
        double sinel = Math.sin(el);
        double var = a * a + b * b / (sinel * sinel);

        if (opt.ionoopt == Constants.IONOOPT_IFLC) var *= 9.0;

        return var;
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

        rtk.sol.stat = (byte)(rtk.sol.ns < MIN_NSAT_SOL ? Constants.SOLQ_NONE : stat);

        for (int i = 0; i < 3; i++) {
            rtk.sol.rr[i] = rtk.x[i];
            rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
        }
        rtk.sol.qr[3] = (float) rtk.P[1];
        rtk.sol.qr[4] = (float) rtk.P[2 * nx + 1];
        rtk.sol.qr[5] = (float) rtk.P[2];

        rtk.sol.dtr[0] = rtk.x[IC(0, opt)] / Constants.CLIGHT;
        rtk.sol.dtr[1] = (rtk.x[IC(1, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;
        rtk.sol.dtr[2] = (rtk.x[IC(2, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;
        rtk.sol.dtr[3] = (rtk.x[IC(3, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;
    }

    private static double SQR(double x) {
        return x * x;
    }
}