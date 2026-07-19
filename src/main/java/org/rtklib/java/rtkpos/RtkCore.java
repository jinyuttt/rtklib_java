package org.rtklib.java.rtkpos;

import org.rtklib.java.ambiguity.Lambda;
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
import org.rtklib.java.rtkpos.Tides;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.troposphere.TroposphereModel;

import java.util.Arrays;

public final class RtkCore {
    private RtkCore() {
    }

    private static final int MAXITR = 8;
    private static final double STD_PREC_VAR_THRESH = 0;
    private static final double TTOL_MOVEB = 1.05;
    private static final int MIN_ND = 4;
    private static final double RNX2CLK = 299792458.0;

    public static int rtkpos(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        PrcOpt opt = rtk.opt;
        Sol solb = new Sol();
        int i, nu = 0, nr = 0;
        String[] msg = new String[1];
        double[] azel = new double[n * 2];

        for (nu = 0; nu < n && obs[nu].rcv == 1; nu++) ;
        for (nr = 0; nu + nr < n && obs[nu + nr].rcv == 2; nr++) ;

        GTime prevTime = new GTime();
        prevTime.time = rtk.sol.time.time;
        prevTime.sec = rtk.sol.time.sec;

        if (rtk.P[0] == 0 || rtk.P[0] > STD_PREC_VAR_THRESH) {
            if (PntPos.pntpos(obs, nu, nav, opt, rtk.sol, null, rtk.ssat) == 0) {
                return 0;
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

        return relpos(rtk, obs, nu, nr, nav);
    }

    private static int relpos(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav) {
        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int i, j, f, ns, nv = 0;
        int n = nu + nr;

        if (rtk.rtkConfig.enableIonoTropGradient && !opt.ionoGradient) {
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

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];
        double[] azel = new double[Constants.MAXSAT * 2];

        EphModel.satposs(obs[0].time, obs, n, nav, rs, dts, vare, svh, opt.sateph);

        double[] y = new double[nf * 2 * n];
        double[] e = new double[3 * n];
        double[] freq = new double[nf * n];

        if (!zdres(1, obs, nu, nr, rs, dts, vare, svh, nav, rtk.rb, opt,
                y, e, azel, freq)) {
            return 0;
        }

        double dt = TimeSystem.timediff(obs[0].time, obs[nu].time);
        rtk.sol.age = (float) dt;
        if (Math.abs(rtk.sol.age) > opt.maxtdiff) {
            return 1;
        }

        int[] sat = new int[Constants.MAXSAT];
        int[] iu = new int[Constants.MAXSAT];
        int[] ir = new int[Constants.MAXSAT];
        ns = selsat(obs, azel, nu, nr, opt, sat, iu, ir);
        if (ns <= 0) return 0;

        int nx_new = NR(rtk) + NB(rtk);
        rtk.na = NR(rtk);
        if (rtk.nx != nx_new) {
            rtk.nx = nx_new;
            rtk.x = new double[nx_new];
            rtk.P = new double[nx_new * nx_new];
            initx(rtk.x, rtk.P, nx_new, rtk.sol.rr[0] - rtk.rb[0], Constants.VAR_POS, 0);
            initx(rtk.x, rtk.P, nx_new, rtk.sol.rr[1] - rtk.rb[1], Constants.VAR_POS, 1);
            initx(rtk.x, rtk.P, nx_new, rtk.sol.rr[2] - rtk.rb[2], Constants.VAR_POS, 2);
            if (rtk.opt.dynamics != 0) {
                for (i = 3; i < 6; i++) initx(rtk.x, rtk.P, nx_new, 0.0, Constants.VAR_VEL, i);
                for (i = 6; i < 9; i++) initx(rtk.x, rtk.P, nx_new, 1E-6, Constants.VAR_ACC, i);
            }
        }
        int nx = rtk.nx;

        udstate(rtk, obs, nu, nr, nav, sat, ns, iu, ir);

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
            for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
            if (!zdres(0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                    y, e, azel, freq)) {
                stat = Constants.SOLQ_NONE;
                break;
            }
            if ((nv = ddres(rtk, obs, dt, xp, Pp, sat, y, e, azel, freq,
                    iu, ir, ns, nf, nav, v, H, R, vflg)) < 4) {
                stat = Constants.SOLQ_NONE;
                break;
            }

            RtkOptimizations.computeQScale(rtk, sat, ns);

            RtkOptimizations.applyIggiii(rtk, v, H, R, vflg, nv, nx, sat, ns,
                    obs, iu, azel, nf);

            int info = filter(rtk, xp, Pp, H, v, R, nx, nv);
            if (info != 0) {
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
            } else {
                stat = Constants.SOLQ_NONE;
            }
        }

        if (stat == Constants.SOLQ_FLOAT) {
            double[] bias_arr = new double[nx];
            double[] xa_arr = new double[nx];
            int nb = manage_amb_LAMBDA(rtk, bias_arr, xa_arr, sat, nf, ns);
            if (nb > 1) {
                for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xa_arr[j];
                if (zdres(0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                        y, e, azel, freq)) {
                    nv = ddres(rtk, obs, dt, xa_arr, rtk.P, sat, y, e, azel, freq,
                            iu, ir, ns, nf, nav, v, null, R, vflg);
                    if (nv > 0 && valpos(rtk, v, R, vflg, nv, 4.0)) {
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
                    }
                }
            }
        }

        if (stat == Constants.SOLQ_NONE) {
            System.arraycopy(xp, 0, rtk.x, 0, nx);
            System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);
        }

        if (stat == Constants.SOLQ_FIX) {
            for (i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.rb[i] + xa[i];
                rtk.sol.qr[i] = (float) rtk.Pa[i + i * rtk.na];
            }
            rtk.sol.qr[3] = (float) rtk.Pa[1];
            rtk.sol.qr[4] = (float) rtk.Pa[1 + 2 * rtk.na];
            rtk.sol.qr[5] = (float) rtk.Pa[2];

            if (opt.dynamics != 0) {
                for (i = 3; i < 6; i++) {
                    rtk.sol.rr[i] = xa[i];
                    rtk.sol.qv[i - 3] = (float) rtk.Pa[i + i * rtk.na];
                }
                rtk.sol.qv[3] = (float) rtk.Pa[4 + 3 * rtk.na];
                rtk.sol.qv[4] = (float) rtk.Pa[5 + 4 * rtk.na];
                rtk.sol.qv[5] = (float) rtk.Pa[5 + 3 * rtk.na];
            }
        } else {
            for (i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.rb[i] + rtk.x[i];
                rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
            }
            rtk.sol.qr[3] = (float) rtk.P[1];
            rtk.sol.qr[4] = (float) rtk.P[1 + 2 * nx];
            rtk.sol.qr[5] = (float) rtk.P[2];

            if (opt.dynamics != 0) {
                for (i = 3; i < 6; i++) {
                    rtk.sol.rr[i] = rtk.x[i];
                    rtk.sol.qv[i - 3] = (float) rtk.P[i + i * nx];
                }
                rtk.sol.qv[3] = (float) rtk.P[4 + 3 * nx];
                rtk.sol.qv[4] = (float) rtk.P[5 + 4 * nx];
                rtk.sol.qv[5] = (float) rtk.P[5 + 3 * nx];
            }
            rtk.nfix = 0;
        }

        rtk.sol.stat = (byte) stat;

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

    private static int NP(Rtk rtk) {
        return (rtk.opt.dynamics == 0) ? 3 : 9;
    }

    private static int NI(Rtk rtk) {
        if (rtk.opt.ionoopt == Constants.IONOOPT_EST) {
            if (rtk.opt.ionoGradient) {
                return Constants.MAXSAT * 3;
            }
            return Constants.MAXSAT;
        }
        return 0;
    }

    private static int NT(Rtk rtk) {
        if (rtk.opt.tropopt < Constants.TROPOPT_EST) return 0;
        if (rtk.opt.tropopt < Constants.TROPOPT_ESTG) return 2;
        return 6;
    }

    private static int NB(Rtk rtk) {
        int nf = (rtk.opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : rtk.opt.nf;
        if (rtk.opt.mode <= Constants.PMODE_DGPS) return 0;
        return Constants.MAXSAT * nf;
    }
    private static int NL(Rtk rtk) {
        if (rtk.opt.glomodear != Constants.GLO_ARMODE_AUTOCAL) return 0;
        return Constants.NFREQGLO;
    }
    private static int NR(Rtk rtk) {
        return NP(rtk) + NI(rtk) + NT(rtk) + NL(rtk);
    }
    private static int II(int sat, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        if (opt.ionoGradient) {
            return np + (sat - 1) * 3;
        }
        return np + (sat - 1);
    }
    private static int IT(int r, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        return np + ni + (nt / 2) * r;
    }
    private static int IL(int f, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        return np + ni + nt + f;
    }
    private static int IB(int sat, int f, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        int nl = (opt.glomodear != Constants.GLO_ARMODE_AUTOCAL) ? 0 : Constants.NFREQGLO;
        int nr = np + ni + nt + nl;
        return nr + Constants.MAXSAT * f + (sat - 1);
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
                udtrop(rtk, bl);
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
            for (int i = 0; i < 3; i++) initx(x, P, nx, rtk.sol.rr[i] - rtk.rb[i], Constants.VAR_POS, i);
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

        if (opt.dynamics != 0) {
            for (int i = 0; i < 3; i++) {
                x[i] += tt * x[i + 3];
            }
        }

        double qh = opt.prn[3] * opt.prn[3] * Math.abs(tt);
        double qv = opt.prn[4] * opt.prn[4] * Math.abs(tt);

        if (rtk.rtkConfig.enableAdaptiveQ && rtk.qScale != 1.0) {
            qh *= rtk.qScale * rtk.qScale;
            qv *= rtk.qScale * rtk.qScale;
        }

        for (int i = 0; i < 3; i++) {
            P[i * nx + i] += qh;
        }
        P[2 * nx + 2] += qv;

        if (opt.dynamics != 0) {
            double qv2 = opt.prn[5] * opt.prn[5] * Math.abs(tt);
            for (int i = 3; i < 6; i++) {
                P[i * nx + i] += qv2;
            }
        }
    }

    private static void udion(Rtk rtk, double bl, int[] sat, int ns) {
        PrcOpt opt = rtk.opt;
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;
        boolean ionoGrad = opt.ionoGradient;

        if (rtk.rtkConfig.atmFrozenNsThresh > 0 && ns < rtk.rtkConfig.atmFrozenNsThresh) {
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

        double gradInitVar = rtk.rtkConfig.gradientIonoInitVar;
        double gradPrn = rtk.rtkConfig.gradientIonoPrn;

        for (int i = 0; i < ns; i++) {
            int j = II(sat[i], opt);
            if (x[j] == 0.0) {
                initx(x, P, nx, 1E-6, SQR(opt.std[1] * bl / 1E4), j);
                if (ionoGrad) {
                    initx(x, P, nx, 0.0, gradInitVar, j + 1);
                    initx(x, P, nx, 0.0, gradInitVar, j + 2);
                }
            } else {
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

    private static void udtrop(Rtk rtk, double bl) {
        PrcOpt opt = rtk.opt;
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;

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
                initx(x, P, nx, rtk.xa[j], rtk.Pa[j + j * na], j);
            } else {
                P[j * nx + j] += SQR(rtk.opt.thresar[4]) * Math.abs(rtk.tt);
            }
        }
    }

    private static double baseline(double[] x, double[] rb, double[] dr) {
        if (dr == null) dr = new double[3];
        return Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
    }

    private static void tidedisp(GTime tutc, double[] rr, int opt, Erp erp,
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

    private static void antmodel(Pcv pcv, double[] del, double[] azel, int opt, double[] dant) {
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

    private static double interpvar(double eldeg, double[] var) {
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
            for (int f = 0; f < nf; f++) {
                rtk.ssat[s].slip[f] = 0;
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
                if (opt.ionoopt == Constants.IONOOPT_IFLC && nf > 1) {
                    int f2 = RtklibCommon.seliflc(opt.nf, rtk.ssat[s].sys);
                    if (f2 >= 0 && f2 < Constants.NFREQ) {
                        slip |= rtk.ssat[s].slip[f2];
                    }
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
                    if (obsR.L[0] == 0.0 || obsR.L[1] == 0.0 || obsB.L[0] == 0.0 || obsB.L[1] == 0.0) continue;
                    if (obsR.P[0] == 0.0 || obsR.P[1] == 0.0 || obsB.P[0] == 0.0 || obsB.P[1] == 0.0) continue;
                    double freq1 = SatUtils.sat2freq(sat[i], obsR.code[0], nav);
                    double freq2 = SatUtils.sat2freq(sat[i], obsR.code[1], nav);
                    if (freq1 <= 0.0 || freq2 <= 0.0) continue;
                    double C1 = SQR(freq1) / (SQR(freq1) - SQR(freq2));
                    double C2 = -SQR(freq2) / (SQR(freq1) - SQR(freq2));
                    double cp1 = (obsR.L[0] - obsB.L[0]) * Constants.CLIGHT / freq1;
                    double cp2 = (obsR.L[1] - obsB.L[1]) * Constants.CLIGHT / freq2;
                    double pr1 = obsR.P[0] - obsB.P[0];
                    double pr2 = obsR.P[1] - obsB.P[1];
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

            for (int i = 0; i < ns; i++) {
                if (bias[i] == 0.0) continue;
                int idx = IB(sat[i], f, opt);
                if (idx >= nx) continue;
                if (x[idx] != 0.0) continue;
                initx(x, P, nx, bias[i], SQR(opt.std[0]), idx);
                if (opt.modear != Constants.ARMODE_INST) {
                    rtk.ssat[sat[i] - 1].lock[f] = -opt.minlock;
                }
            }
        }
    }

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
            if (r <= 0.0) continue;

            double[] ae = new double[2];
            double el = RtklibCommon.satazel(pos, ei, ae);
            azel[idx * 2] = ae[0];
            azel[idx * 2 + 1] = el;
            if (el < opt.elmin) continue;

            if (RtklibCommon.satexclude(obs[idx].sat, vare[idx], svh[idx], opt) != 0) continue;

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
                double freq1 = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[0], nav);
                int f2 = RtklibCommon.seliflc(opt.nf, SatUtils.satsys(obs[idx].sat, null));
                double freq2 = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[f2], nav);
                if (freq1 == 0.0 || freq2 == 0.0) continue;

                if (RtklibCommon.testsnr(base, 0, el, obs[idx].SNR[0], opt.snrmask) != 0 ||
                    RtklibCommon.testsnr(base, f2, el, obs[idx].SNR[f2], opt.snrmask) != 0) continue;

                double C1 = SQR(freq1) / (SQR(freq1) - SQR(freq2));
                double C2 = -SQR(freq2) / (SQR(freq1) - SQR(freq2));
                double dant_if = C1 * dant[0] + C2 * dant[f2];

                freq[foff * nf + i * nf] = 1.0;

                if (obs[idx].L[0] != 0.0 && obs[idx].L[f2] != 0.0) {
                    y[off * nf * 2 + i * nf * 2] = C1 * obs[idx].L[0] * Constants.CLIGHT / freq1
                            + C2 * obs[idx].L[f2] * Constants.CLIGHT / freq2 - r - dant_if;
                }
                if (obs[idx].P[0] != 0.0 && obs[idx].P[f2] != 0.0) {
                    y[off * nf * 2 + i * nf * 2 + nf] = C1 * obs[idx].P[0]
                            + C2 * obs[idx].P[f2] - r - dant_if;
                }
            } else {
                for (int f = 0; f < nf; f++) {
                    double fq = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[f], nav);
                    freq[foff * nf + i * nf + f] = fq;
                    if (fq == 0.0) continue;

                    if (RtklibCommon.testsnr(base, f, el, obs[idx].SNR[f], opt.snrmask) != 0) continue;

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

    private static double varerr(int sat, int sys, double el, double snr_rover,
                                 double snr_base, double bl, double dt, int f,
                                 PrcOpt opt, Obsd obs) {
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
            var *= SQR(3.0);
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

    private static void prectrop(Rtk rtk, double[] rr, double[] azel, int i,
                                 double[] dtdx, int nx) {
        PrcOpt opt = rtk.opt;
        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr, pos);

        double[] mapWet = new double[1];
        TroposphereModel.tropmapf(rtk.sol.time, pos, azel, mapWet);

        for (int k = 0; k < nx; k++) dtdx[k] = 0.0;
        int it = NT(rtk);
        if (it > 0) {
            int idx = NP(rtk) + NI(rtk);
            if (idx < nx) dtdx[idx] = mapWet[0];
        }
    }

    private static int ddres(Rtk rtk, Obsd[] obs, double dt, double[] x, double[] P,
                             int[] sat, double[] y, double[] e, double[] azel,
                             double[] freq, int[] iu, int[] ir, int ns, int nf,
                             Nav nav, double[] v, double[] H, double[] R, int[] vflg) {
        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;
        double[] pos = new double[3];
        int i, j, k, m, f, nv = 0;
        int[] ref = new int[Constants.MAXSAT];
        int[] rp = new int[Constants.MAXSAT];
        int[] sysv = new int[Constants.MAXSAT];
        int syscount = 0;
        int[] nb_arr = new int[nf * Constants.MAXSAT];
        int b = 0;

        double[] Ri = null, Rj = null;
        if (R != null) {
            Ri = new double[ns * nf * 2];
            Rj = new double[ns * nf * 2];
        }

        for (m = 0; m < ns; m++) {
            int s = sat[m] - 1;
            sysv[m] = SatUtils.satsys(sat[m], null);
        }

        for (int sys = Constants.SYS_GPS; sys <= Constants.SYS_SBS; sys <<= 1) {
            if ((opt.navsys & sys) == 0) continue;
            double maxel = 0.0;
            int idx = -1;
            for (m = 0; m < ns; m++) {
                if (sysv[m] != sys) continue;
                double el = azel[iu[m] * 2 + 1];
                if (el <= 0.0) continue;
                boolean hasObs = false;
                for (f = 0; f < nf; f++) {
                    if (obs[iu[m]].L[f] != 0.0 && obs[ir[m]].L[f] != 0.0) hasObs = true;
                }
                if (!hasObs) continue;
                if (el > maxel) {
                    maxel = el;
                    idx = m;
                }
            }
            if (idx >= 0) {
                ref[syscount] = idx;
                rp[syscount] = idx;
                syscount++;
            }
        }

        if (syscount == 0) return 0;

        double[] rr_rover = new double[3];
        for (i = 0; i < 3; i++) rr_rover[i] = rtk.rb[i] + x[i];
        CoordTransform.ecef2pos(rr_rover, pos);

        double bl = RtklibCommon.norm(x, 3);

        b = 0;
        for (f = 0; f < nf; f++) {
            for (int si = 0; si < syscount; si++) {
                int ri = ref[si];
                int s_ref = sat[ri] - 1;
                int nb_count = 0;

                double el_ref = azel[iu[ri] * 2 + 1];
                int sys_ref = SatUtils.satsys(sat[ri], null);

                for (m = 0; m < ns; m++) {
                    if (m == ri) continue;
                    if (sysv[m] != sysv[ri]) continue;
                    int s = sat[m] - 1;

                    if (obs[iu[m]].L[f] == 0.0 || obs[ir[m]].L[f] == 0.0) continue;
                    if (obs[iu[ri]].L[f] == 0.0 || obs[ir[ri]].L[f] == 0.0) continue;

                    double lam = Constants.CLIGHT / freq[iu[m] * nf + f];
                    if (lam <= 0.0) continue;

                    if (H != null) {
                        for (j = 0; j < nx; j++) H[nv * nx + j] = 0.0;
                    }

                    double[] ei = new double[]{e[iu[m] * 3], e[iu[m] * 3 + 1], e[iu[m] * 3 + 2]};
                    double[] er = new double[]{e[iu[ri] * 3], e[iu[ri] * 3 + 1], e[iu[ri] * 3 + 2]};

                    if (H != null) {
                        for (j = 0; j < 3; j++) {
                            H[nv * nx + j] = -ei[j] + er[j];
                        }
                    }

                    double freq_m = freq[iu[m] * nf + f];
                    double freq_r = freq[iu[ri] * nf + f];

                    int ib_m = IB(sat[m], f, opt);
                    int ib_r = IB(sat[ri], f, opt);
                    if (opt.ionoopt != Constants.IONOOPT_IFLC) {
                        double lam_m = freq_m > 0.0 ? Constants.CLIGHT / freq_m : 0.0;
                        double lam_r = freq_r > 0.0 ? Constants.CLIGHT / freq_r : 0.0;
                        if (ib_m > 0 && ib_m < nx && H != null) H[nv * nx + ib_m] = lam_m;
                        if (ib_r > 0 && ib_r < nx && H != null) H[nv * nx + ib_r] = -lam_r;
                    } else {
                        if (ib_m > 0 && ib_m < nx && H != null) H[nv * nx + ib_m] = 1.0;
                        if (ib_r > 0 && ib_r < nx && H != null) H[nv * nx + ib_r] = -1.0;
                    }

                    double y_r_c = y[iu[ri] * nf * 2 + f];
                    double y_r_p = y[iu[ri] * nf * 2 + nf + f];
                    double y_b_r_c = y[ir[ri] * nf * 2 + f];
                    double y_b_r_p = y[ir[ri] * nf * 2 + nf + f];

                    double y_m_c = y[iu[m] * nf * 2 + f];
                    double y_m_p = y[iu[m] * nf * 2 + nf + f];
                    double y_b_m_c = y[ir[m] * nf * 2 + f];
                    double y_b_m_p = y[ir[m] * nf * 2 + nf + f];

                    if (y_m_c != 0.0 && y_r_c != 0.0 && y_b_m_c != 0.0 && y_b_r_c != 0.0) {
                        v[nv] = (y_m_c - y_b_m_c) - (y_r_c - y_b_r_c);

                        if (ib_m > 0 && ib_m < nx && ib_r > 0 && ib_r < nx) {
                            if (opt.ionoopt != Constants.IONOOPT_IFLC) {
                                double lam_m = freq_m > 0.0 ? Constants.CLIGHT / freq_m : 0.0;
                                double lam_r = freq_r > 0.0 ? Constants.CLIGHT / freq_r : 0.0;
                                v[nv] -= lam_m * x[ib_m] - lam_r * x[ib_r];
                            } else {
                                v[nv] -= x[ib_m] - x[ib_r];
                            }
                        }

                        if (opt.ionoopt == Constants.IONOOPT_EST) {
                            int ii_m = II(sat[m], opt);
                            int ii_r = II(sat[ri], opt);
                            if (ii_m >= 0 && ii_r >= 0 && ii_m < nx && ii_r < nx) {
                                double im_m = IonosphereModel.ionmapf(pos, new double[]{azel[iu[m] * 2], azel[iu[m] * 2 + 1]});
                                double im_r = IonosphereModel.ionmapf(pos, new double[]{azel[iu[ri] * 2], azel[iu[ri] * 2 + 1]});
                                double didx_m = im_m * SQR(Constants.FREQL1 / freq_m);
                                double didx_r = im_r * SQR(Constants.FREQL1 / freq_r);
                                v[nv] += didx_m * x[ii_m] - didx_r * x[ii_r];
                                if (H != null) {
                                    H[nv * nx + ii_m] += didx_m;
                                    H[nv * nx + ii_r] -= didx_r;
                                }
                            }
                        }

                        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
                            double[] dtdx_m = new double[nx];
                            double[] dtdx_r = new double[nx];
                            prectrop(rtk, rr_rover, new double[]{azel[iu[m] * 2], azel[iu[m] * 2 + 1]}, m, dtdx_m, nx);
                            prectrop(rtk, rr_rover, new double[]{azel[iu[ri] * 2], azel[iu[ri] * 2 + 1]}, ri, dtdx_r, nx);
                            for (j = 0; j < nx; j++) {
                                if (H != null) H[nv * nx + j] += dtdx_m[j] - dtdx_r[j];
                            }
                        }

                        if (opt.ionoopt == Constants.IONOOPT_EST && opt.ionoGradient) {
                            int ii_m = II(sat[m], opt);
                            int ii_r = II(sat[ri], opt);
                            if (ii_m >= 0 && ii_r >= 0 && ii_m + 2 < nx && ii_r + 2 < nx) {
                                double az_m = azel[iu[m] * 2], el_m = azel[iu[m] * 2 + 1];
                                double az_r = azel[iu[ri] * 2], el_r = azel[iu[ri] * 2 + 1];
                                double im_m = IonosphereModel.ionmapf(pos, new double[]{az_m, el_m});
                                double im_r = IonosphereModel.ionmapf(pos, new double[]{az_r, el_r});
                                double scale_m = SQR(Constants.FREQL1 / freq_m);
                                double scale_r = SQR(Constants.FREQL1 / freq_r);
                                double cot_m = 1.0 / Math.tan(el_m);
                                double cot_r = 1.0 / Math.tan(el_r);
                                v[nv] -= scale_m * im_m * cot_m * (Math.cos(az_m) * x[ii_m + 1] + Math.sin(az_m) * x[ii_m + 2]);
                                v[nv] += scale_r * im_r * cot_r * (Math.cos(az_r) * x[ii_r + 1] + Math.sin(az_r) * x[ii_r + 2]);
                                if (H != null) {
                                    H[nv * nx + ii_m + 1] -= scale_m * im_m * cot_m * Math.cos(az_m);
                                    H[nv * nx + ii_m + 2] -= scale_m * im_m * cot_m * Math.sin(az_m);
                                    H[nv * nx + ii_r + 1] += scale_r * im_r * cot_r * Math.cos(az_r);
                                    H[nv * nx + ii_r + 2] += scale_r * im_r * cot_r * Math.sin(az_r);
                                }
                            }
                        }

                        double threshadj = 1.0;
                        if (opt.mode > Constants.PMODE_DGPS) {
                            double std0sq = opt.std[0] * opt.std[0];
                            if (ib_m > 0 && ib_m < nx && ib_r > 0 && ib_r < nx) {
                                if (P[ib_m * nx + ib_m] == std0sq || P[ib_r * nx + ib_r] == std0sq) {
                                    threshadj = 10.0;
                                }
                            }
                        }
                        rtk.ssat[s].resc[f] = v[nv];
                        if (Math.abs(v[nv]) > opt.maxinno[0] * threshadj) {
                            rtk.ssat[s].vsat[f] = 0;
                            rtk.ssat[s].rejc[f]++;
                            continue;
                        }

                        if (Ri != null) {
                            double el_m = azel[iu[m] * 2 + 1];
                            int sys_m = SatUtils.satsys(sat[m], null);
                            Ri[nv] = varerr(sat[ri], sys_ref, el_ref,
                                    rtk.ssat[s_ref].snrRover[f], rtk.ssat[s_ref].snrBase[f],
                                    bl, dt, f, opt, obs[iu[ri]]);
                            Rj[nv] = varerr(sat[m], sys_m, el_m,
                                    rtk.ssat[s].snrRover[f], rtk.ssat[s].snrBase[f],
                                    bl, dt, f, opt, obs[iu[m]]);
                        }

                        vflg[nv] = (sat[m] << 16) | (sat[ri] << 8) | (1 << 4) | f;
                        rtk.ssat[s].vsat[f] = 1;
                        nv++;
                        nb_count++;
                    }

                    if (obs[iu[m]].P[f] != 0.0 && obs[ir[m]].P[f] != 0.0 &&
                        obs[iu[ri]].P[f] != 0.0 && obs[ir[ri]].P[f] != 0.0 &&
                        y_m_p != 0.0 && y_r_p != 0.0 && y_b_m_p != 0.0 && y_b_r_p != 0.0) {

                        if (H != null) {
                            for (j = 0; j < nx; j++) H[nv * nx + j] = 0.0;
                            for (j = 0; j < 3; j++) {
                                H[nv * nx + j] = -ei[j] + er[j];
                            }
                        }

                        if (opt.ionoopt == Constants.IONOOPT_EST) {
                            int ii_m = II(sat[m], opt);
                            int ii_r = II(sat[ri], opt);
                            if (ii_m >= 0 && ii_r >= 0 && ii_m < nx && ii_r < nx) {
                                double im_m = IonosphereModel.ionmapf(pos, new double[]{azel[iu[m] * 2], azel[iu[m] * 2 + 1]});
                                double im_r = IonosphereModel.ionmapf(pos, new double[]{azel[iu[ri] * 2], azel[iu[ri] * 2 + 1]});
                                double didx_m = -im_m * SQR(Constants.FREQL1 / freq_m);
                                double didx_r = -im_r * SQR(Constants.FREQL1 / freq_r);
                                if (H != null) {
                                    H[nv * nx + ii_m] += didx_m;
                                    H[nv * nx + ii_r] -= didx_r;
                                }
                            }
                        }

                        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
                            double[] dtdx_m = new double[nx];
                            double[] dtdx_r = new double[nx];
                            prectrop(rtk, rr_rover, new double[]{azel[iu[m] * 2], azel[iu[m] * 2 + 1]}, m, dtdx_m, nx);
                            prectrop(rtk, rr_rover, new double[]{azel[iu[ri] * 2], azel[iu[ri] * 2 + 1]}, ri, dtdx_r, nx);
                            for (j = 0; j < nx; j++) {
                                if (H != null) H[nv * nx + j] += dtdx_m[j] - dtdx_r[j];
                            }
                        }

                        v[nv] = (y_m_p - y_b_m_p) - (y_r_p - y_b_r_p);

                        if (opt.ionoopt == Constants.IONOOPT_EST) {
                            int ii_m = II(sat[m], opt);
                            int ii_r = II(sat[ri], opt);
                            if (ii_m >= 0 && ii_r >= 0 && ii_m < nx && ii_r < nx) {
                                double im_m = IonosphereModel.ionmapf(pos, new double[]{azel[iu[m] * 2], azel[iu[m] * 2 + 1]});
                                double im_r = IonosphereModel.ionmapf(pos, new double[]{azel[iu[ri] * 2], azel[iu[ri] * 2 + 1]});
                                double didx_m = -im_m * SQR(Constants.FREQL1 / freq_m);
                                double didx_r = -im_r * SQR(Constants.FREQL1 / freq_r);
                                v[nv] -= didx_m * x[ii_m] - didx_r * x[ii_r];
                            }
                        }

                        rtk.ssat[s].resp[f] = v[nv];
                        double threshadj_p = 1.0;
                        if (opt.mode > Constants.PMODE_DGPS) {
                            double std0sq = opt.std[0] * opt.std[0];
                            if (ib_m > 0 && ib_m < nx && ib_r > 0 && ib_r < nx) {
                                if (P[ib_m * nx + ib_m] == std0sq || P[ib_r * nx + ib_r] == std0sq) {
                                    threshadj_p = 10.0;
                                }
                            }
                        }
                        if (Math.abs(v[nv]) > opt.maxinno[1] * threshadj_p) {
                            continue;
                        }

                        if (Ri != null) {
                            double el_m = azel[iu[m] * 2 + 1];
                            int sys_m = SatUtils.satsys(sat[m], null);
                            Ri[nv] = varerr(sat[ri], sys_ref, el_ref,
                                    rtk.ssat[s_ref].snrRover[f], rtk.ssat[s_ref].snrBase[f],
                                    bl, dt, f + nf, opt, obs[iu[ri]]);
                            Rj[nv] = varerr(sat[m], sys_m, el_m,
                                    rtk.ssat[s].snrRover[f], rtk.ssat[s].snrBase[f],
                                    bl, dt, f + nf, opt, obs[iu[m]]);
                        }

                        vflg[nv] = (sat[m] << 16) | (sat[ri] << 8) | (0 << 4) | f;
                        nv++;
                        nb_count++;
                    }
                }

                if (nb_count > 0) {
                    nb_arr[b++] = nb_count;
                }
            }
        }

        if (R != null) {
            ddcov(nb_arr, b, Ri, Rj, nv, R);
        }

        return nv;
    }

    private static int filter(Rtk rtk, double[] xp, double[] Pp,
                              double[] H, double[] v, double[] R,
                              int nx, int nv) {
        return KalmanFilter.update(xp, Pp, H, v, R, nx, nv);
    }

    private static int ddidx(Rtk rtk, int[] ix, int gps, int glo, int sbs) {
        if (rtk.rtkConfig.enableParRefReselect) {
            return RtkOptimizations.buildParIndex(rtk, ix, gps, glo, sbs);
        }
        return RtkOptimizations.ddidxFallback(rtk, ix, gps, glo, sbs);
    }

    private static int resamb_LAMBDA(Rtk rtk, double[] bias, double[] xa,
                                     int gps, int glo, int sbs) {
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

        boolean enableAnchor = rtk.rtkConfig.enableAmbAnchor;

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
                    rtk.Pa[i + j * na] = rtk.P[i + j * nx];
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

        for (int j = 0; j < nx - na; j++) {
            for (int i = 0; i < nbLambda; i++) {
                DP[i + j * nbLambda] = rtk.P[ixUsed[i * 2] + (na + j) * nx] - rtk.P[ixUsed[i * 2 + 1] + (na + j) * nx];
            }
        }
        for (int j = 0; j < nbLambda; j++) {
            for (int i = 0; i < nbLambda; i++) {
                Qb[i + j * nbLambda] = DP[i + (ixUsed[j * 2] - na) * nbLambda] - DP[i + (ixUsed[j * 2 + 1] - na) * nbLambda];
            }
        }
        for (int j = 0; j < nbLambda; j++) {
            for (int i = 0; i < na; i++) {
                Qab[i + j * na] = rtk.P[i + ixUsed[j * 2] * nx] - rtk.P[i + ixUsed[j * 2 + 1] * nx];
            }
        }

        double[] s = new double[2];
        int info = Lambda.lambda(nbLambda, 2, y, Qb, b, s);

        if (info == 0) {
            rtk.sol.ratio = s[0] > 0 ? (float) (s[1] / s[0]) : 0.0f;
            if (rtk.sol.ratio > 999.9f) rtk.sol.ratio = 999.9f;

            if (opt.thresar[5] != opt.thresar[6]) {
                rtk.sol.thres = (float) opt.thresar[0];
            } else {
                rtk.sol.thres = (float) opt.thresar[0];
            }

            if (s[0] <= 0.0 || s[1] / s[0] >= rtk.sol.thres) {
                for (int i = 0; i < na; i++) {
                    rtk.xa[i] = rtk.x[i];
                    for (int j = 0; j < na; j++) {
                        rtk.Pa[i + j * na] = rtk.P[i + j * nx];
                    }
                }

                double[] biasFull = new double[nb];
                for (int i = 0; i < nbLambda; i++) {
                    biasFull[enableAnchor ? freeMap[i] : i] = b[i];
                    y[i] -= b[i];
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

                if (!matinv(Qb, nbLambda)) {
                    matmul("NN", nbLambda, 1, nbLambda, Qb, y, db);
                    matmulm("NN", na, 1, nbLambda, Qab, db, rtk.xa);

                    matmul("NN", na, nbLambda, nbLambda, Qab, Qb, QQ);
                    matmulm("NT", na, na, nbLambda, QQ, Qab, rtk.Pa);

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

        return nb;
    }

    private static void restamb(Rtk rtk, double[] bias, int nb, double[] xa) {
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
                                         int[] sat, int nf, int ns) {
        PrcOpt opt = rtk.opt;

        double posvar = 0.0;
        for (int i = 0; i < 3; i++) posvar += rtk.P[i + i * rtk.nx];
        posvar /= 3.0;

        if (opt.mode <= Constants.PMODE_DGPS || opt.modear == Constants.ARMODE_OFF ||
            opt.thresar[0] < 1.0 || posvar > opt.thresar[1]) {
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

        int nb = resamb_LAMBDA(rtk, bias, xa, gps1, glo1, sbas1);
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
                nb = resamb_LAMBDA(rtk, bias, xa, gps1, glo1, sbas1);
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
                nb = resamb_LAMBDA(rtk, bias, xa, gps2, glo2, sbas2);
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
            if (v[i] * v[i] <= fact * R[i + i * nv]) continue;
        }
        return true;
    }

    private static void holdamb(Rtk rtk, double[] xa) {
        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;
        int na = rtk.na;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nb = nx - na;
        boolean enableAnchor = rtk.rtkConfig.enableAmbAnchor;

        if (enableAnchor && rtk.sol.stat == Constants.SOLQ_FIX) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                for (int f = 0; f < nf; f++) {
                    int globalIdx = i * nf + f;
                    if (rtk.ssat[i].fix[f] > 0) {
                        rtk.ambAnchorCount[globalIdx]++;
                        if (rtk.ambAnchorCount[globalIdx] >= rtk.rtkConfig.ambAnchorMinFixCount) {
                            rtk.ambAnchored[globalIdx] = true;
                        }
                    }
                }
            }
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
                    H[index[0] + nv * nx] = 1.0;
                    H[index[i] + nv * nx] = -1.0;
                    nv++;
                }
            }
        }

        if (opt.modear == Constants.ARMODE_FIXHOLD && nv < opt.minholdsats) {
            return;
        }

        rtk.holdambFlag = 1;

        double[] Rh = new double[nv * nv];
        for (int i = 0; i < nv; i++) Rh[i + i * nv] = opt.varholdamb;

        if (nv > 0) {
            KalmanFilter.update(rtk.x, rtk.P, H, v, Rh, nx, nv);
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
    }

    private static void initx(double[] x, double[] P, int nx, double xi, double var, int i) {
        x[i] = xi;
        for (int j = 0; j < nx; j++) {
            P[i * nx + j] = 0.0;
            P[j * nx + i] = 0.0;
        }
        P[i * nx + i] = var;
    }

    private static double SQR(double x) {
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
}