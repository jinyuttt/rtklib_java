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

        if (rtk.rtkConfig.enableIonoTropGradient && !opt.ionoGradient) {
            opt.ionoGradient = true;
        }

        for (int i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i].sys = SatUtils.satsys(i + 1, null);
            for (int j = 0; j < Constants.NFREQ; j++) {
                rtk.ssat[i].vsat[j] = 0;
                rtk.ssat[i].snrRover[j] = 0;
                rtk.ssat[i].snrBase[j] = 0;
            }
        }

        int[] sat = new int[Constants.MAXSAT];
        int[] iu = new int[Constants.MAXSAT];
        int[] ir = new int[Constants.MAXSAT];
        int ns = selsat(obs, nu, nr, opt, sat, iu, ir);
        if (ns <= 0) return 0;

        rtk.nx = NR(rtk) + NB(rtk);
        rtk.na = NR(rtk);
        int nx = rtk.nx;

        int stat = (opt.mode <= Constants.PMODE_DGPS) ? Constants.SOLQ_DGPS : Constants.SOLQ_FLOAT;

        if (stat != Constants.SOLQ_NONE) {
            RtkOptimizations.computeSnrMedian(rtk, obs, nu, nr, sat, ns, nf, nav);

            udstate(rtk, obs, nu, nr, nav, sat, ns, iu, ir);

            for (int i = 0; i < ns; i++) {
                for (int j = 0; j < nf; j++) {
                    rtk.ssat[sat[i] - 1].snrRover[j] = obs[iu[i]].SNR[j];
                    rtk.ssat[sat[i] - 1].snrBase[j] = obs[ir[i]].SNR[j];
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
            double[] azel = new double[ns * 2];
            double[] y = new double[ns * nf * 2];

            for (int iter = 0; iter < opt.niter; iter++) {
                int nv = zdres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel, vflg, nf, y, xp);
                if (nv < 4) {
                    stat = Constants.SOLQ_NONE;
                    break;
                }
                int nvOut = ddres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel,
                        vflg, nf, H, v, R, y, xp);
                if (nvOut < 4) {
                    stat = Constants.SOLQ_NONE;
                    break;
                }

                RtkOptimizations.computeQScale(rtk, sat, ns);

                RtkOptimizations.applyIggiii(rtk, v, H, R, vflg, nvOut, nx, sat, ns,
                        obs, iu, azel, nf);

                int info = filter(rtk, xp, Pp, H, v, R, nx, nvOut);
                if (info != 0) {
                    stat = Constants.SOLQ_NONE;
                    break;
                }
            }

            if (stat != Constants.SOLQ_NONE) {
                int nv = zdres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel, vflg, nf, y, xp);
                if (nv > 0) {
                    int nvOut = ddres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel,
                            vflg, nf, H, v, R, y, xp);

                    if (valpos(rtk, v, R, vflg, nvOut, 4.0)) {
                        System.arraycopy(xp, 0, rtk.x, 0, nx);
                        System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);

                        rtk.sol.ns = 0;
                        for (int i = 0; i < ns; i++) {
                            for (int f = 0; f < nf; f++) {
                                if (rtk.ssat[sat[i] - 1].vsat[f] == 0) continue;
                                if (rtk.ssat[sat[i] - 1].rejc[f] == 0) {
                                    rtk.ssat[sat[i] - 1].outc[f] = 0;
                                }
                                if (f == 0) rtk.sol.ns++;
                            }
                        }
                        if (rtk.sol.ns < 4) stat = Constants.SOLQ_DGPS;
                    } else {
                        stat = Constants.SOLQ_NONE;
                    }
                }
            }

            if (stat == Constants.SOLQ_FLOAT) {
                double[] bias = new double[nx];
                double[] xa = new double[nx];
                int nb = manage_amb_LAMBDA(rtk, bias, xa, sat, nf, ns);
                if (nb > 1) {
                    int nv = zdres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel, vflg, nf, y, xa);
                    if (nv > 0) {
                        int nvOut = ddres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel,
                                vflg, nf, H, v, R, y, xa);

                        if (valpos(rtk, v, R, vflg, nvOut, 4.0)) {
                            if (++rtk.nfix >= opt.minfix) {
                                if (opt.modear == Constants.ARMODE_FIXHOLD) {
                                    holdamb(rtk, xa);
                                }
                                if (opt.mode == Constants.PMODE_STATIC_START) {
                                    opt.mode = Constants.PMODE_KINEMA;
                                }
                            }
                            stat = Constants.SOLQ_FIX;
                        }
                    }
                }
            }

            if (stat == Constants.SOLQ_NONE) {
                System.arraycopy(xp, 0, rtk.x, 0, nx);
                System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);
            }
        }

        if (stat == Constants.SOLQ_FIX) {
            for (int i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.rb[i] + rtk.xa[i];
                rtk.sol.qr[i] = (float) rtk.Pa[i + i * rtk.na];
            }
            rtk.sol.qr[3] = (float) rtk.Pa[1];
            rtk.sol.qr[4] = (float) rtk.Pa[1 + 2 * rtk.na];
            rtk.sol.qr[5] = (float) rtk.Pa[2];

            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) {
                    rtk.sol.rr[i] = rtk.xa[i];
                    rtk.sol.qv[i - 3] = (float) rtk.Pa[i + i * rtk.na];
                }
                rtk.sol.qv[3] = (float) rtk.Pa[4 + 3 * rtk.na];
                rtk.sol.qv[4] = (float) rtk.Pa[5 + 4 * rtk.na];
                rtk.sol.qv[5] = (float) rtk.Pa[5 + 3 * rtk.na];
            }
        } else {
            for (int i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.rb[i] + rtk.x[i];
                rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
            }
            rtk.sol.qr[3] = (float) rtk.P[1];
            rtk.sol.qr[4] = (float) rtk.P[1 + 2 * nx];
            rtk.sol.qr[5] = (float) rtk.P[2];

            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) {
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

        int n = nu + nr;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < nf; j++) {
                if (obs[i].L[j] == 0.0) continue;
                int s = obs[i].sat - 1;
                rtk.ssat[s].pt[obs[i].rcv - 1][j] = obs[i].time;
                rtk.ssat[s].ph[obs[i].rcv - 1][j] = obs[i].L[j];
            }
        }
        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < nf; j++) {
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

    private static int selsat(Obsd[] obs, int nu, int nr, PrcOpt opt,
                              int[] sat, int[] iu, int[] ir) {
        int ns = 0;
        for (int i = 0; i < nu && ns < Constants.MAXSAT; i++) {
            for (int j = 0; j < nr && ns < Constants.MAXSAT; j++) {
                if (obs[i].sat == obs[nu + j].sat) {
                    sat[ns] = obs[i].sat;
                    iu[ns] = i;
                    ir[ns] = nu + j;
                    ns++;
                    break;
                }
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
            for (int i = 0; i < 3; i++) initx(x, P, nx, opt.ru[i], Constants.VAR_POS_FIX, i);
            return;
        }

        double normPos = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
        if (normPos <= Constants.RE_WGS84 / 2.0) {
            for (int i = 0; i < 3; i++) initx(x, P, nx, rtk.sol.rr[i], Constants.VAR_POS, i);
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
            for (int i = 0; i < 3; i++) initx(x, P, nx, rtk.sol.rr[i], Constants.VAR_POS, i);
            return;
        }

        double var = 0.0;
        for (int i = 0; i < 3; i++) var += P[i * nx + i];
        var /= 3.0;

        if (var > Constants.VAR_POS) {
            for (int i = 0; i < 3; i++) initx(x, P, nx, rtk.sol.rr[i], Constants.VAR_POS, i);
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

    private static double baseline(double[] ru, double[] rb, double[] dr) {
        for (int i = 0; i < 3; i++) dr[i] = ru[i] - rb[i];
        return Math.sqrt(dr[0] * dr[0] + dr[1] * dr[1] + dr[2] * dr[2]);
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
            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                if (++rtk.ssat[s].outc[f] > opt.maxout) {
                    int idx = IB(sat[i], f, opt);
                    initx(x, P, nx, 0.0, 0.0, idx);
                }
            }

            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                int idx = IB(sat[i], f, opt);
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

            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                Obsd obsR = obs[iu[i]];
                Obsd obsB = obs[ir[i]];

                if (obsR.P[f] == 0.0 || obsB.P[f] == 0.0) continue;
                if (obsR.L[f] == 0.0 || obsB.L[f] == 0.0) continue;

                double freq = SatUtils.sat2freq(sat[i], obsR.code[f], nav);
                if (freq == 0.0) continue;
                double lam = Constants.CLIGHT / freq;

                double bias = (obsR.L[f] - obsB.L[f]) * lam -
                             (obsR.P[f] - obsB.P[f]);

                int idx = IB(sat[i], f, opt);
                if (x[idx] == 0.0) {
                    initx(x, P, nx, bias, SQR(opt.prn[0]), idx);
                }
            }
        }
    }

    private static int zdres(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav,
                             int[] sat, int ns, int[] iu, int[] ir,
                             double[] azel, int[] vflg, int nf, double[] y,
                             double[] xState) {
        PrcOpt opt = rtk.opt;
        double[] pos = new double[3];
        double[] rrRov = new double[3];
        double[] rrBas = new double[3];

        for (int i = 0; i < 3; i++) {
            rrBas[i] = rtk.rb[i];
            rrRov[i] = rtk.rb[i] + xState[i];
        }
        CoordTransform.ecef2pos(rrRov, pos);

        double[] rs = new double[ns * 6];
        double[] dts = new double[ns * 2];
        double[] var = new double[ns];
        int[] svh = new int[ns];

        Obsd[] satObs = new Obsd[ns];
        for (int i = 0; i < ns; i++) {
            satObs[i] = obs[iu[i]];
        }
        EphModel.satposs(obs[0].time, satObs, ns, nav, rs, dts, var, svh, opt.sateph);

        int nv = 0;
    
        double[] e = new double[3];

        for (int f = 0; f < nf; f++) {
            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                Obsd obsR = obs[iu[i]];
                Obsd obsB = obs[ir[i]];

                double rRov = RtklibCommon.geodist(
                        new double[]{rs[i * 6], rs[i * 6 + 1], rs[i * 6 + 2]},
                        rrRov, e);
                double rBas = RtklibCommon.geodist(
                        new double[]{rs[i * 6], rs[i * 6 + 1], rs[i * 6 + 2]},
                        rrBas, e);

                RtklibCommon.satazel(pos, e, new double[]{azel[i * 2], azel[i * 2 + 1]});
                azel[i * 2] = Math.atan2(e[0], e[1]);
                if (azel[i * 2] < 0) azel[i * 2] += 2.0 * Constants.PI;
                azel[i * 2 + 1] = Math.asin(e[2]);

                rtk.ssat[s].azel[0] = azel[i * 2];
                rtk.ssat[s].azel[1] = azel[i * 2 + 1];

                double[] tropRov = new double[1];
                double[] tropBas = new double[1];
                SbasCorrection.sbstropcorr(obsR.time, pos, new double[]{azel[i * 2], azel[i * 2 + 1]}, tropRov);
                SbasCorrection.sbstropcorr(obsB.time, rrBas, new double[]{azel[i * 2], azel[i * 2 + 1]}, tropBas);

                double ion = 0.0;
                if (opt.ionoopt == Constants.IONOOPT_BRDC) {
                    double[] ionArr = new double[2];
                    IonosphereModel.ionocorr(obsR.time, nav, sat[i], pos,
                        new double[]{azel[i * 2], azel[i * 2 + 1]}, opt.ionoopt, ionArr);
                    ion = ionArr[0];
                }

                double dtsVal = dts[i * 2] * Constants.CLIGHT;

                double freq = SatUtils.sat2freq(sat[i], obsR.code[f], nav);
                if (freq == 0.0) continue;
                double lam = Constants.CLIGHT / freq;

                if (obsR.P[f] != 0.0 && obsB.P[f] != 0.0) {
                    double prRov = obsR.P[f] - (rRov + dtsVal - ion + tropRov[0]);
                    double prBas = obsB.P[f] - (rBas + dtsVal - ion + tropBas[0]);
                    y[nv] = prRov - prBas;
                    vflg[nv] = (sat[i] << 8) | (0 << 4) | f;
                    rtk.ssat[s].vsat[f] = 1;
                    nv++;
                }

                if (obsR.L[f] != 0.0 && obsB.L[f] != 0.0) {
                    double cpRov = obsR.L[f] * lam - (rRov + dtsVal + ion + tropRov[0]);
                    double cpBas = obsB.L[f] * lam - (rBas + dtsVal + ion + tropBas[0]);
                    y[nv] = cpRov - cpBas;
                    vflg[nv] = (sat[i] << 8) | (1 << 4) | f;
                    rtk.ssat[s].vsat[f] = 1;
                    nv++;
                }
            }
        }

        return nv;
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

    private static int ddres(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav,
                             int[] sat, int ns, int[] iu, int[] ir,
                             double[] azel, int[] vflg, int nf,
                             double[] H, double[] v, double[] R, double[] y,
                             double[] xState) {
        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;

        int nvIn = 0;
        for (int i = 0; i < ns * nf * 2; i++) {
            if (vflg[i] != 0) nvIn++;
        }

        int[] refIdx = new int[nf * 2];
        for (int i = 0; i < nf * 2; i++) refIdx[i] = -1;

        for (int f = 0; f < nf * 2; f++) {
            double maxEl = 0.0;
            for (int i = 0; i < nvIn; i++) {
                int satIdx = (vflg[i] >> 8) & 0xFF;
                int type = (vflg[i] >> 4) & 0xF;
                int frq = vflg[i] & 0xF;
                int ft = frq + (type >= 1 ? nf : 0);
                if (ft == f && satIdx > 0 && satIdx <= Constants.MAXSAT) {
                    double el = rtk.ssat[satIdx - 1].azel[1];
                    if (el > maxEl) {
                        maxEl = el;
                        refIdx[f] = i;
                    }
                }
            }
        }

        int nvOut = 0;
        double[] refY = new double[nf * 2];
        double[] refV = new double[nf * 2];
        int[] refSat = new int[nf * 2];

        for (int f = 0; f < nf * 2; f++) {
            if (refIdx[f] < 0) continue;
            int satIdx = (vflg[refIdx[f]] >> 8) & 0xFF;
            refSat[f] = satIdx;
        }

        for (int i = 0; i < nvIn; i++) {
            int satIdx = (vflg[i] >> 8) & 0xFF;
            int type = (vflg[i] >> 4) & 0xF;
            int frq = vflg[i] & 0xF;
            int ft = frq + (type >= 1 ? nf : 0);

            if (refIdx[ft] < 0 || refIdx[ft] == i) continue;

            int refI = refIdx[ft];

            for (int k = 0; k < nx; k++) {
                H[nvOut * nx + k] = 0.0;
            }

            double[] e = new double[3];
            double[] rrRov = new double[3];
            for (int k = 0; k < 3; k++) rrRov[k] = rtk.rb[k] + xState[k];

            double rRov = RtklibCommon.geodist(
                    new double[]{0, 0, 0}, rrRov, e);

            for (int k = 0; k < 3; k++) {
                H[nvOut * nx + k] = -e[k];
            }

            int freqIdx = type >= 1 ? IB(satIdx, frq, opt) : 0;
            int refFreqIdx = type >= 1 ? IB(refSat[ft], frq, opt) : 0;

            if (freqIdx > 0 && freqIdx < nx) {
                H[nvOut * nx + freqIdx] = 1.0;
            }
            if (refFreqIdx > 0 && refFreqIdx < nx) {
                H[nvOut * nx + refFreqIdx] = -1.0;

                v[nvOut] = y[i] - y[refI];

                if (opt.ionoopt == Constants.IONOOPT_EST) {
                    double[] ionMapI = new double[1];
                    double[] ionMapJ = new double[1];
                    double[] posI = new double[3];
                    double[] posJ = new double[3];
                    CoordTransform.ecef2pos(rrRov, posI);
                    CoordTransform.ecef2pos(rrRov, posJ);
                    double imI = IonosphereModel.ionmapf(posI, new double[]{azel[i * 2], azel[i * 2 + 1]});
                    double imJ = IonosphereModel.ionmapf(posJ, new double[]{azel[refI * 2], azel[refI * 2 + 1]});
                    double freqI = SatUtils.sat2freq(satIdx, obs[0].code[frq], nav);
                    double freqJ = SatUtils.sat2freq(refSat[ft], obs[0].code[frq], nav);
                    if (freqI > 0.0 && freqJ > 0.0) {
                        double sign = (type == 0) ? -1.0 : 1.0;
                        double didxI = sign * imI * SQR(Constants.FREQL1 / freqI);
                        double didxJ = sign * imJ * SQR(Constants.FREQL1 / freqJ);
                        int iiI = II(satIdx, opt);
                        int iiJ = II(refSat[ft], opt);
                        if (iiI >= 0 && iiI < nx && iiJ >= 0 && iiJ < nx) {
                            v[nvOut] -= didxI * xState[iiI] - didxJ * xState[iiJ];
                            H[nvOut * nx + iiI] += didxI;
                            H[nvOut * nx + iiJ] -= didxJ;
                        }
                    }
                }

                if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
                    double[] dtdxI = new double[nx];
                    double[] dtdxJ = new double[nx];
                    prectrop(rtk, rrRov, new double[]{azel[i * 2], azel[i * 2 + 1]}, i, dtdxI, nx);
                    prectrop(rtk, rrRov, new double[]{azel[refI * 2], azel[refI * 2 + 1]}, refI, dtdxJ, nx);
                    for (int k = 0; k < nx; k++) {
                        H[nvOut * nx + k] += dtdxI[k] - dtdxJ[k];
                    }
                }

                if (opt.ionoopt == Constants.IONOOPT_EST && opt.ionoGradient) {
                    double[] posI = new double[3];
                    CoordTransform.ecef2pos(rrRov, posI);
                    double elI = azel[i * 2 + 1];
                    double elJ = azel[refI * 2 + 1];
                    double azI = azel[i * 2];
                    double azJ = azel[refI * 2];
                    double cotElI = 1.0 / Math.tan(elI);
                    double cotElJ = 1.0 / Math.tan(elJ);
                    double freqI = SatUtils.sat2freq(satIdx, obs[0].code[frq], nav);
                    double freqJ = SatUtils.sat2freq(refSat[ft], obs[0].code[frq], nav);
                    if (freqI > 0.0 && freqJ > 0.0) {
                        double sign = (type == 0) ? -1.0 : 1.0;
                        double scaleI = sign * SQR(Constants.FREQL1 / freqI);
                        double scaleJ = sign * SQR(Constants.FREQL1 / freqJ);
                        int iiI = II(satIdx, opt);
                        int iiJ = II(refSat[ft], opt);
                        if (iiI + 2 < nx && iiJ + 2 < nx) {
                            v[nvOut] -= scaleI * cotElI * Math.cos(azI) * xState[iiI + 1]
                                      + scaleJ * cotElJ * Math.cos(azJ) * xState[iiJ + 1];
                            v[nvOut] -= scaleI * cotElI * Math.sin(azI) * xState[iiI + 2]
                                      + scaleJ * cotElJ * Math.sin(azJ) * xState[iiJ + 2];
                            H[nvOut * nx + iiI + 1] += scaleI * cotElI * Math.cos(azI);
                            H[nvOut * nx + iiI + 2] += scaleI * cotElI * Math.sin(azI);
                            H[nvOut * nx + iiJ + 1] -= scaleJ * cotElJ * Math.cos(azJ);
                            H[nvOut * nx + iiJ + 2] -= scaleJ * cotElJ * Math.sin(azJ);
                        }
                    }
                }
            }

            if (opt.maxinno != null && opt.maxinno.length > type
                    && opt.maxinno[type] > 0.0) {
                double threshAdj = 1.0;
                if (opt.mode > Constants.PMODE_DGPS && type == 0) {
                    int ii = IB(satIdx, frq, opt);
                    int jj = IB(refSat[ft], frq, opt);
                    if (ii > 0 && ii < nx && jj > 0 && jj < nx) {
                        double[] Pp = rtk.P;
                        if (Pp[ii + ii * nx] == SQR(opt.std[0]) ||
                            Pp[jj + jj * nx] == SQR(opt.std[0])) {
                            threshAdj = 10.0;
                        }
                    }
                }
                if (Math.abs(v[nvOut]) > opt.maxinno[type] * threshAdj) {
                    rtk.ssat[satIdx - 1].vsat[frq] = 0;
                    rtk.ssat[satIdx - 1].rejc[frq]++;
                    continue;
                }
            }

            for (int k = 0; k < nvOut; k++) {
                R[nvOut * nvIn + k] = 0.0;
                R[k * nvIn + nvOut] = 0.0;
            }

            double varFact = (type == 0) ? 1.0 : 0.01;
            double baseVar = (type == 0) ? SQR(opt.prn[4]) : SQR(opt.prn[3]);
            R[nvOut * nvIn + nvOut] = 2.0 * baseVar * varFact;

            nvOut++;
        }

        return nvOut;
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
        int stat = 1;
        int nvFail = 0;
        for (int i = 0; i < nv; i++) {
            if (v[i] * v[i] <= fact * R[i + i * nv]) continue;
            int satIdx = (vflg[i] >> 8) & 0xFF;
            int type = (vflg[i] >> 4) & 0xF;
            int freq = vflg[i] & 0xF;
            String stype = type == 0 ? "L" : (type == 1 ? "P" : "C");
            if (satIdx > 0 && satIdx <= Constants.MAXSAT) {
                rtk.ssat[satIdx - 1].rejc[freq]++;
            }
            nvFail++;
        }
        if (nvFail > nv / 2) {
            stat = 0;
        }
        return stat == 1;
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