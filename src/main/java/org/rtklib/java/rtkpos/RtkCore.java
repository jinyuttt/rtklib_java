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

        for (nu = 0; nu < n && obs[nu].rcv == 1; nu++) ;
        for (nr = 0; nu + nr < n && obs[nu + nr].rcv == 2; nr++) ;

        GTime prevTime = new GTime();
        prevTime.time = rtk.sol.time.time;
        prevTime.sec = rtk.sol.time.sec;

        if (rtk.P[0] == 0 || rtk.P[0] > STD_PREC_VAR_THRESH) {
            int sppResult = PntPos.pntpos(obs, nu, nav, opt, rtk.sol, null, rtk.ssat);
            if (sppResult == 0) {
                LOG.info("rtkpos: SPP failed (P[0]={}, nu={}, nr={})", rtk.P[0], nu, nr);
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
                y, e, azel, freq, rtk.epoch)) {
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
        if (ns <= 0) {
            return 0;
        }

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

        RtkOptimizations.computeSnrMedian(rtk, obs, nu, nr, sat, ns, nf, nav);

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
                    y, e, azel, freq, rtk.epoch)) {
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
                    obs, iu, azel, nf, Pp);

            if (rtk.epoch == 1) {
                int na_dbg = rtk.na;
                int cnt_dbg = 0;
                for (int kk = na_dbg; kk < nx && cnt_dbg < 15; kk++) {
                    if (Pp[kk * nx + kk] > 0.0) {
                        cnt_dbg++;
                    }
                }
            }

            int info = filter(rtk, xp, Pp, H, v, R, nx, nv);

            if (info != 0) {
                stat = Constants.SOLQ_NONE;
                break;
            }

            if (rtk.epoch <= 5 || rtk.epoch == 10 || rtk.epoch == 22) {
            }

            if (rtk.epoch == 1 || rtk.epoch == 2) {
            }

            if (rtk.epoch == 3 || rtk.epoch == 22) {
            }
        }

        if (stat != Constants.SOLQ_NONE) {
            for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
            if (zdres(0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                    y, e, azel, freq, rtk.epoch)) {
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
            if (rtk.epoch == 31 || rtk.epoch == 32) {
                int na_d = rtk.na;
                int nx_d = rtk.nx;
                int cntAmb = 0;
                double sumAmbVar = 0, minAmbVar = Double.MAX_VALUE, maxAmbVar = 0;
                for (int ai = na_d; ai < nx_d; ai++) {
                    double pv = rtk.P[ai * nx_d + ai];
                    double xv = rtk.x[ai];
                    if (xv != 0.0 && pv > 0.0) {
                        cntAmb++;
                        sumAmbVar += pv;
                        if (pv < minAmbVar) minAmbVar = pv;
                        if (pv > maxAmbVar) maxAmbVar = pv;
                    }
                }
                int printCnt = 0;
                for (int ai = na_d; ai < nx_d && printCnt < 20; ai++) {
                    double pv = rtk.P[ai * nx_d + ai];
                    double xv = rtk.x[ai];
                    if (xv != 0.0 && pv > 0.0) {
                        printCnt++;
                    }
                }
            }
            double[] bias_arr = new double[nx];
            double[] xa_arr = new double[nx];
            int nb = manage_amb_LAMBDA(rtk, bias_arr, xa_arr, sat, nf, ns);

            if (nb > 1) {
                for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xa_arr[j];
                boolean zdOk = zdres(0, obs, nu, nr, rs, dts, vare, svh, nav, rr_rover, opt,
                        y, e, azel, freq, rtk.epoch);

                if (zdOk) {
                    nv = ddres(rtk, obs, dt, xa_arr, rtk.P, sat, y, e, azel, freq,
                            iu, ir, ns, nf, nav, v, null, R, vflg);
                    boolean valOk = nv > 0 && valpos(rtk, v, R, vflg, nv, 4.0);

                    if (valOk) {
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
                    } else {
                    }
                } else {
                }
            } else {
            }
        }



        if (stat == Constants.SOLQ_FIX) {
            for (i = 0; i < 3; i++) {
                rtk.sol.rr[i] = rtk.rb[i] + xa[i];
                rtk.sol.qr[i] = (float) rtk.Pa[i * rtk.na + i];
            }
            rtk.sol.qr[3] = (float) rtk.Pa[0 * rtk.na + 1];
            rtk.sol.qr[4] = (float) rtk.Pa[1 * rtk.na + 2];
            rtk.sol.qr[5] = (float) rtk.Pa[0 * rtk.na + 2];

            if (rtk.epoch >= 62 && rtk.epoch <= 80) {
            }

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

        if (rtk.epoch <= 30) {
            double posvar_diag = (rtk.P[0 * nx + 0] + rtk.P[1 * nx + 1] + rtk.P[2 * nx + 2]) / 3.0;
            StringBuilder sbAmbVar = new StringBuilder(String.format("[AMB-CONV] epoch=%d amb_var=", rtk.epoch));
            int ambCnt = 0;
            for (int ai = rtk.na; ai < nx && ambCnt < 5; ai++) {
                double pv = rtk.P[ai * nx + ai];
                double xv = rtk.x[ai];
                if (xv != 0.0 && pv > 0.0) {
                    sbAmbVar.append(String.format("[%d]=%.2f ", ai, pv));
                    ambCnt++;
                }
            }
            if (stat == Constants.SOLQ_FIX) {
            }
        }

        rtk.sol.stat = (byte) stat;

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

        if (rtk.rtkConfig.enableAdaptiveQ && rtk.qScale != 1.0) {
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
                initx(x, P, nx, rtk.xa[j], rtk.Pa[j * na + j], j);
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
            for (int f = 0; f < opt.nf; f++) {
                rtk.ssat[s].slip[f] &= 0xFC;
            }
        }

        detslpDop(rtk, obs, iu, ns, 1, nav);
        detslpDop(rtk, obs, ir, ns, 2, nav);

        for (int i = 0; i < ns; i++) {
            detslpCode(rtk, obs, iu[i], 1);
            detslpCode(rtk, obs, ir[i], 2);

            detslpLl(rtk, obs, iu[i], 1);
            detslpLl(rtk, obs, ir[i], 2);

            detslpGf(rtk, obs, iu[i], ir[i], nav);

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
            if (rtk.epoch <= 5) {
                int firstIdx = IB(sat[0], f, opt);
                if (firstIdx < nx) {
                }
            }
        }
        if (rtk.epoch <= 5) {
            int ambCount = 0;
            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                for (int f = 0; f < nf; f++) {
                    int idx = IB(sat[i], f, opt);
                    if (idx < nx && x[idx] != 0.0) ambCount++;
                }
            }
            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                for (int f = 0; f < nf; f++) {
                    int idx = IB(sat[i], f, opt);
                    boolean biasInit = (idx < nx && x[idx] != 0.0);
                }
            }
        }
    }

    private static boolean zdres(int base, Obsd[] obs, int nu, int nr,
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
                    if (fq == 0.0) {
                        if (epoch == 4 && f == 1 && base == 0 && i < 3) {
                        }
                        continue;
                    }

                    int snrRet = RtklibCommon.testsnr(base, f, el, obs[idx].SNR[f], opt.snrmask);
                    if (snrRet != 0) {
                        if (epoch == 4 && f == 1 && base == 0 && i < 3) {
                        }
                        continue;
                    }

                    double lam = Constants.CLIGHT / fq;

                    if (obs[idx].L[f] != 0.0) {
                        y[off * nf * 2 + i * nf * 2 + f] = obs[idx].L[f] * lam - r - dant[f];
                    } else {
                        if (epoch == 4 && f == 1 && base == 0 && i < 3) {
                        }
                    }
                    if (obs[idx].P[f] != 0.0) {
                        y[off * nf * 2 + i * nf * 2 + nf + f] = obs[idx].P[f] - r - dant[f];
                    } else {
                        if (epoch == 4 && f == 1 && base == 0 && i < 3) {
                        }
                    }
                    if (epoch <= 3 && f == 0 && obs[idx].L[f] != 0.0) {
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
        int nx = rtk.nx;int i, j, k, m, f, nv = 0;
        int[] nb = new int[Constants.NFREQ * 7 * 2 + 2];
        int b = 0;

        double[] rr_f = new double[3];
        for (i = 0; i < 3; i++) rr_f[i] = rtk.rb[i] + x[i];
        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr_f, pos);

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
                for (j = 0; j < ns; j++) {
                    int sysj = SatUtils.satsys(sat[j], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    if (sysj == Constants.SYS_SBS) continue;
                    if (!validobs(iu[j], ir[j], f, nf, y)) continue;
                    if (refIdx >= 0 && (rtk.ssat[sat[j] - 1].slip[frq] & Constants.LLI_SLIP) != 0) continue;
                    if (refIdx < 0 || azel[1 + iu[j] * 2] >= azel[1 + iu[refIdx] * 2]) {
                        refIdx = j;
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
                            double im_m = IonosphereModel.ionmapf(pos, new double[]{azel[iu[j] * 2], azel[iu[j] * 2 + 1]});
                            double im_r = IonosphereModel.ionmapf(pos, new double[]{azel[iu[refIdx] * 2], azel[iu[refIdx] * 2 + 1]});
                            double didx_m = im_m * SQR(Constants.FREQL1 / freqj);
                            double didx_r = im_r * SQR(Constants.FREQL1 / freqi);
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
                        prectrop(rtk, rr_f, new double[]{azel[iu[j] * 2], azel[iu[j] * 2 + 1]}, j, dtdx_m, nx);
                        prectrop(rtk, rr_f, new double[]{azel[iu[refIdx] * 2], azel[iu[refIdx] * 2 + 1]}, refIdx, dtdx_r, nx);
                        for (k = 0; k < nx; k++) {
                            if (H != null) H[nv * nx + k] += dtdx_m[k] - dtdx_r[k];
                        }
                    }

                    if (code) {
                        rtk.ssat[sat[j] - 1].resp[frq] = v[nv];
                    } else {
                        rtk.ssat[sat[j] - 1].resc[frq] = v[nv];
                    }

                    double threshadj = 1.0;
                    if (opt.mode > Constants.PMODE_DGPS && !code) {
                        int ii = IB(sat[refIdx], frq, opt);
                        int jj = IB(sat[j], frq, opt);
                        double Pii = P[ii * nx + ii];
                        double Pjj = P[jj * nx + jj];
                        double std0sq = opt.std[0] * opt.std[0];
                        if (Pii == std0sq || Pjj == std0sq) {
                            threshadj = 10.0;
                        }
                    }
                    if (Math.abs(v[nv]) > opt.maxinno[code ? 1 : 0] * threshadj) {
                        rtk.ssat[sat[j] - 1].vsat[frq] = 0;
                        rtk.ssat[sat[j] - 1].rejc[frq]++;
                        continue;
                    }

                    double eli = azel[1 + iu[refIdx] * 2];
                    double elj = azel[1 + iu[j] * 2];
                    int sysRef = SatUtils.satsys(sat[refIdx], null);
                    int sysJ = SatUtils.satsys(sat[j], null);
                    if (Ri != null) {
                        Ri[nv] = varerr(sat[refIdx], sysRef, eli, rtk.ssat[sat[refIdx]-1].snrRover[frq], rtk.ssat[sat[refIdx]-1].snrBase[frq], bl, dt, f, opt, obs[iu[refIdx]]);
                        Rj[nv] = varerr(sat[j], sysJ, elj, rtk.ssat[sat[j]-1].snrRover[frq], rtk.ssat[sat[j]-1].snrBase[frq], bl, dt, f, opt, obs[iu[j]]);
                        if (!code) {
                            if ((obs[iu[refIdx]].LLI[frq] & Constants.LLI_HALFC) != 0) Ri[nv] += 0.01;
                            if ((obs[iu[j]].LLI[frq] & Constants.LLI_HALFC) != 0) Rj[nv] += 0.01;
                        }
                        if (rtk.epoch <= 3 && nv < 3 && !code) {
                        }
                        if (rtk.epoch <= 3 && nv >= 12 && nv < 15 && code) {
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
                    if (rtk.epoch <= 3 && !code && frq == 0 && H != null) {
                    }
                    nv++;
                    nb[b]++;
                }
                if (rtk.epoch <= 10) {
                    if (rtk.epoch <= 10) {
                    }
                }
                b++;
            }
        }

        if (rtk.epoch <= 10) {
        }

        if (rtk.epoch == 31 && H != null) {
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
        KalmanFilter.debugEpoch = (rtk.epoch <= 3 || rtk.epoch == 31);
        int ret = KalmanFilter.update(xp, Pp, H, v, R, nx, nv);
        return ret;
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

        boolean diagResamb = (rtk.epoch <= 10 || rtk.epoch == 22 || rtk.epoch == 50 || rtk.epoch == 100);

        if (diagResamb) {
        }

        int[] ix = new int[nx * 2];
        int nb = ddidx(rtk, ix, gps, glo, sbs);

        if (diagResamb) {
            for (int i = na; i < nx; i++) {
                int localIdx = i - na;
                int sat = (localIdx % Constants.MAXSAT) + 1;
                int f = localIdx / Constants.MAXSAT;
                if (f >= nf) continue;
                int si = sat - 1;

                double xVal = rtk.x[i];
                double pVal = rtk.P[i * nx + i];

                if (xVal == 0.0) continue;
            }
        }

        if (nb < opt.minfixsats - 1) {
            if (diagResamb) {
            }
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

        if (rtk.epoch == 32 || rtk.epoch == 33) {
        }

        if (rtk.epoch <= 5 || rtk.epoch == 10 || rtk.epoch == 22) {
            StringBuilder sbIx = new StringBuilder(String.format("[LAMBDA-IX] epoch=%d nbLambda=%d na=%d nAmb=%d ixUsed=", rtk.epoch, nbLambda, na, nAmb));
            for (int i = 0; i < Math.min(nbLambda, 11); i++) {
            }

            int[] usedAmbIdx = new int[Math.min(5, nbLambda * 2)];
            int usedAmbCnt = 0;
            boolean[] seen = new boolean[nAmb];
            for (int i = 0; i < nbLambda && usedAmbCnt < 5; i++) {
                int local_i = ixUsed[i * 2] - na;
                int local_j = ixUsed[i * 2 + 1] - na;
                if (local_i >= 0 && local_i < nAmb && !seen[local_i] && QcMat.get(local_i, local_i) > 0.0) {
                    seen[local_i] = true;
                    usedAmbIdx[usedAmbCnt++] = local_i;
                }
                if (local_j >= 0 && local_j < nAmb && !seen[local_j] && QcMat.get(local_j, local_j) > 0.0) {
                    seen[local_j] = true;
                    usedAmbIdx[usedAmbCnt++] = local_j;
                }
            }
            if (usedAmbCnt >= 2) {
                for (int ii = 0; ii < usedAmbCnt; ii++) {
                    for (int jj = 0; jj < usedAmbCnt; jj++) {
                        double dii = QcMat.get(usedAmbIdx[ii], usedAmbIdx[ii]);
                        double djj = QcMat.get(usedAmbIdx[jj], usedAmbIdx[jj]);
                        double c = (dii > 0 && djj > 0) ? QcMat.get(usedAmbIdx[ii], usedAmbIdx[jj]) / Math.sqrt(dii * djj) : 0;
                    }
                }
            }
        }

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

        if (rtk.epoch == 21 && nbLambda <= 12) {
            for (int li = 0; li < nbLambda; li++) {
            }
            for (int li = 0; li < nbLambda; li++) {
            }
        }

        int info = Lambda.lambda(nbLambda, 2, y, Qb, b, s);

        if (info == 0) {
            double ratio = s[0] > 0 ? s[1]/s[0] : 0;
            if (rtk.epoch <= 80 || ratio >= 3.0) {
                for (int li = 0; li < nbLambda; li++) {
                }
            }
        }

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
                        rtk.Pa[i * na + j] = rtk.P[i * nx + j];
                    }
                }

                if (rtk.epoch <= 30 || rtk.epoch == 32 || rtk.epoch == 33 || (rtk.epoch >= 62 && rtk.epoch <= 65)) {
                    for (int ii = 0; ii < 3; ii++) {
                        for (int jj = 0; jj < 3; jj++) {
                        }
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

                    if (rtk.epoch <= 80 || (rtk.epoch >= 100 && rtk.epoch <= 105)) {
                        for (int qi = 0; qi < Math.min(5, nbLambda); qi++) {
                        }
                        for (int qi = 0; qi < Math.min(5, nbLambda); qi++) {
                        }
                        for (int qi = 0; qi < Math.min(5, nbLambda); qi++) {
                        }
                        for (int qi = 0; qi < Math.min(5, nb); qi++) {
                        }
                        for (int qi = 0; qi < Math.min(5, nbLambda); qi++) {
                        }
                    }
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
            int ret = KalmanFilter.update(rtk.x, rtk.P, H, v, Rh, nx, nv);
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

    private static double gfobs(Obsd[] obs, int i, int j, int k, Nav nav) {
        double freq1 = SatUtils.sat2freq(obs[i].sat, obs[i].code[0], nav);
        double freq2 = SatUtils.sat2freq(obs[i].sat, obs[i].code[k], nav);
        double L1 = sdobs(obs, i, j, 0);
        double L2 = sdobs(obs, i, j, k);
        if (freq1 == 0.0 || freq2 == 0.0 || L1 == 0.0 || L2 == 0.0) return 0.0;
        return L1 * Constants.CLIGHT / freq1 - L2 * Constants.CLIGHT / freq2;
    }

    private static void detslpLl(Rtk rtk, Obsd[] obs, int i, int rcv) {
        int sat = obs[i].sat;
        for (int f = 0; f < rtk.opt.nf; f++) {
            if ((obs[i].L[f] == 0.0 && obs[i].LLI[f] == 0) ||
                    Math.abs(TimeSystem.timediff(obs[i].time, rtk.ssat[sat - 1].pt[rcv - 1][f])) < Constants.DTTOL) {
                continue;
            }
            int LLI;
            if (rcv == 1) { LLI = rtk.ssat[sat - 1].slip[f] & 0x03; }
            else { LLI = (rtk.ssat[sat - 1].slip[f] >> 2) & 0x03; }
            int slip;
            if (rtk.tt >= 0.0) { slip = obs[i].LLI[f]; }
            else { slip = LLI; }
            if (((LLI & Constants.LLI_HALFC) != 0 && (obs[i].LLI[f] & Constants.LLI_HALFC) == 0) ||
                    ((LLI & Constants.LLI_HALFC) == 0 && (obs[i].LLI[f] & Constants.LLI_HALFC) != 0)) {
                slip |= Constants.LLI_SLIP;
            }
            if (rcv == 1) { rtk.ssat[sat - 1].slip[f] = (rtk.ssat[sat - 1].slip[f] & 0xFC) | (obs[i].LLI[f] & 0x03); }
            else { rtk.ssat[sat - 1].slip[f] = (rtk.ssat[sat - 1].slip[f] & 0xF3) | ((obs[i].LLI[f] & 0x03) << 2); }
            rtk.ssat[sat - 1].slip[f] |= slip;
            rtk.ssat[sat - 1].half[f] = (obs[i].LLI[f] & Constants.LLI_HALFC) != 0 ? 0 : 1;
        }
    }

    private static void detslpGf(Rtk rtk, Obsd[] obs, int i, int j, Nav nav) {
        int sat = obs[i].sat;
        if (rtk.opt.thresslip == 0) return;
        for (int k = 0; k < rtk.opt.nf; k++) {
            if ((rtk.ssat[sat - 1].slip[k] & Constants.LLI_SLIP) != 0) return;
        }
        for (int k = 1; k < rtk.opt.nf; k++) {
            double gf1 = gfobs(obs, i, j, k, nav);
            if (gf1 == 0.0) continue;
            double gf0 = rtk.ssat[sat - 1].gf[k - 1];
            rtk.ssat[sat - 1].gf[k - 1] = gf1;
            if (gf0 != 0.0 && Math.abs(gf1 - gf0) > rtk.opt.thresslip) {
                rtk.ssat[sat - 1].slip[0] |= Constants.LLI_SLIP;
                rtk.ssat[sat - 1].slip[k] |= Constants.LLI_SLIP;
            }
        }
    }

    private static void detslpCode(Rtk rtk, Obsd[] obs, int i, int rcv) {
        int sat = obs[i].sat;
        for (int f = 0; f < rtk.opt.nf; f++) {
            int code = obs[i].code[f];
            if (code == Constants.CODE_NONE) continue;
            int ccode = rtk.ssat[sat - 1].code[f][rcv - 1];
            if (code != ccode) {
                rtk.ssat[sat - 1].code[f][rcv - 1] = code;
                if (ccode != Constants.CODE_NONE) {
                    rtk.ssat[sat - 1].slip[f] |= Constants.LLI_SLIP;
                }
            }
        }
    }

    private static void detslpDop(Rtk rtk, Obsd[] obs, int[] ix, int ns, int rcv, Nav nav) {
        if (rtk.opt.thresdop <= 0) return;
        double[] dopdif = new double[Constants.MAXSAT * Constants.NFREQ];
        double[] tt = new double[Constants.MAXSAT * Constants.NFREQ];
        int ndop = 0;
        double meanDop = 0.0;
        for (int i = 0; i < ns; i++) {
            int ii = ix[i];
            int sat = obs[ii].sat;
            for (int f = 0; f < rtk.opt.nf; f++) {
                int idx = i * rtk.opt.nf + f;
                dopdif[idx] = 0.0;
                tt[idx] = 0.0;
                if (obs[ii].L[f] == 0.0 || obs[ii].D[f] == 0.0 || rtk.ssat[sat - 1].ph[rcv - 1][f] == 0.0) continue;
                double dt = TimeSystem.timediff(obs[ii].time, rtk.ssat[sat - 1].pt[rcv - 1][f]);
                tt[idx] = dt;
                if (Math.abs(dt) < Constants.DTTOL) continue;
                double dph = (obs[ii].L[f] - rtk.ssat[sat - 1].ph[rcv - 1][f]) / dt;
                double dpt = -obs[ii].D[f];
                dopdif[idx] = dph - dpt;
                if (Math.abs(dopdif[idx]) < 3 * rtk.opt.thresdop) { meanDop += dopdif[idx]; ndop++; }
            }
        }
        if (ndop == 0) return;
        meanDop /= ndop;
        for (int i = 0; i < ns; i++) {
            int sat = obs[ix[i]].sat;
            for (int f = 0; f < rtk.opt.nf; f++) {
                int idx = i * rtk.opt.nf + f;
                if (dopdif[idx] == 0.0) continue;
                if (Math.abs(dopdif[idx] - meanDop) > rtk.opt.thresdop) {
                    rtk.ssat[sat - 1].slip[f] |= Constants.LLI_SLIP;
                }
            }
        }
    }
}