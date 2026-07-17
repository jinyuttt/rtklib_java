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

        if (rtk.P[0] == 0 || rtk.P[0] > STD_PREC_VAR_THRESH) {
            if (PntPos.pntpos(obs, nu, nav, opt, rtk.sol, null, rtk.ssat) == 0) {
                return 0;
            }
        } else {
            rtk.sol.time = obs[0].time;
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

            double[] xp = new double[nx];
            double[] Pp = new double[nx * nx];
            System.arraycopy(rtk.x, 0, xp, 0, nx);
            System.arraycopy(rtk.P, 0, Pp, 0, nx * nx);

            int[] vflg = new int[ns * nf * 2];
            double[] azel = new double[ns * 2];
            double[] y = new double[ns * nf * 2];
            int nv = zdres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel, vflg, nf, y);

            if (nv >= 4) {
                double[] H = new double[nx * ns * nf * 2];
                double[] v = new double[ns * nf * 2];
                double[] R = new double[ns * nf * 2 * ns * nf * 2];
                int nvOut = ddres(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel,
                        vflg, nf, H, v, R, y);

                if (nvOut >= 3) {
                    RtkOptimizations.computeQScale(rtk, sat, ns);

                    RtkOptimizations.applyIggiii(rtk, v, H, R, vflg, nvOut, nx, sat, ns,
                            obs, iu, azel, nf);

                    int info = filter(rtk, xp, Pp, H, v, R, nx, nvOut);

                    if (info == 0) {
                        System.arraycopy(xp, 0, rtk.x, 0, nx);
                        System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);

                        if (opt.modear == Constants.ARMODE_CONT ||
                            opt.modear == Constants.ARMODE_INST ||
                            opt.modear == Constants.ARMODE_FIXHOLD) {
                            int arStat = resamb_LAMBDA(rtk, obs, nu, nr, nav, sat, ns, iu, ir, azel);
                            if (arStat == Constants.SOLQ_FIX) {
                                stat = Constants.SOLQ_FIX;
                            }
                        }

                        holdamb(rtk, xp, Pp, nx);
                    }
                }
            }

            if (stat == Constants.SOLQ_NONE) {
                System.arraycopy(xp, 0, rtk.x, 0, nx);
                System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);
            }

            rtk.sol.ns = 0;
            for (int i = 0; i < ns; i++) {
                for (int f = 0; f < nf; f++) {
                    if (rtk.ssat[sat[i] - 1].vsat[f] == 0) continue;
                    if (f == 0) rtk.sol.ns++;
                }
            }
            if (rtk.sol.ns < 4) stat = Constants.SOLQ_DGPS;
        }

        rtk.sol.stat = (byte) stat;

        for (int i = 0; i < Math.min(3, nx); i++) {
            rtk.sol.rr[i] = rtk.rb[i] + rtk.x[i];
        }
        for (int i = 0; i < Math.min(3, nx); i++) {
            rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
        }

        return 1;
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
        return (rtk.opt.dynamics != 0) ? 6 : 3;
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
        int np = (opt.dynamics != 0) ? 6 : 3;
        if (opt.ionoGradient) {
            return np + (sat - 1) * 3;
        }
        return np + (sat - 1);
    }
    private static int IT(int r, PrcOpt opt) {
        int np = (opt.dynamics != 0) ? 6 : 3;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        return np + ni + (nt / 2) * r;
    }
    private static int IL(int f, PrcOpt opt) {
        int np = (opt.dynamics != 0) ? 6 : 3;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        return np + ni + nt + f;
    }
    private static int IB(int sat, int f, PrcOpt opt) {
        int np = (opt.dynamics != 0) ? 6 : 3;
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

        if (opt.ionoopt == Constants.IONOOPT_EST) {
            udion(rtk, obs, nu, nr, nav, sat, ns);
        }

        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG) {
            udtrop(rtk, ns);
        }

        udbias(rtk, obs, nu, nr, nav, sat, ns, iu, ir);
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

    private static void udion(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav,
                              int[] sat, int ns) {
        PrcOpt opt = rtk.opt;
        double[] x = rtk.x;
        double[] P = rtk.P;
        int nx = rtk.nx;
        int ni = NI(rtk);
        int np = NP(rtk);

        if (rtk.rtkConfig.atmFrozenNsThresh > 0 && ns < rtk.rtkConfig.atmFrozenNsThresh) {
            return;
        }

        for (int i = 0; i < ns; i++) {
            int s = sat[i] - 1;
            int idx = np + s;
            double sinel = Math.sin(rtk.ssat[s].azel[1]);
            P[idx * nx + idx] += SQR(opt.prn[1] / Math.max(sinel, 0.1)) * Math.abs(rtk.tt);
        }
    }

    private static void udtrop(Rtk rtk, int ns) {
        PrcOpt opt = rtk.opt;
        double[] P = rtk.P;
        int nx = rtk.nx;
        int nt = NT(rtk);
        int np = NP(rtk);
        int ni = NI(rtk);

        if (rtk.rtkConfig.atmFrozenNsThresh > 0 && ns < rtk.rtkConfig.atmFrozenNsThresh) {
            return;
        }

        int idx = np + ni;
        P[idx * nx + idx] += SQR(opt.prn[2]) * Math.abs(rtk.tt);

        if (opt.tropopt >= Constants.TROPOPT_ESTG) {
            for (int i = idx + 1; i < idx + 3; i++) {
                P[i * nx + i] += SQR(opt.prn[2] * 0.1) * Math.abs(rtk.tt);
            }
        }
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
                             double[] azel, int[] vflg, int nf, double[] y) {
        PrcOpt opt = rtk.opt;
        double[] pos = new double[3];
        double[] rrRov = new double[3];
        double[] rrBas = new double[3];

        for (int i = 0; i < 3; i++) {
            rrBas[i] = rtk.rb[i];
            rrRov[i] = rtk.rb[i] + rtk.x[i];
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
                             double[] H, double[] v, double[] R, double[] y) {
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
            for (int k = 0; k < 3; k++) rrRov[k] = rtk.rb[k] + rtk.x[k];

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
                            v[nvOut] -= didxI * rtk.x[iiI] - didxJ * rtk.x[iiJ];
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
                            v[nvOut] -= scaleI * cotElI * Math.cos(azI) * rtk.x[iiI + 1]
                                      + scaleJ * cotElJ * Math.cos(azJ) * rtk.x[iiJ + 1];
                            v[nvOut] -= scaleI * cotElI * Math.sin(azI) * rtk.x[iiI + 2]
                                      + scaleJ * cotElJ * Math.sin(azJ) * rtk.x[iiJ + 2];
                            H[nvOut * nx + iiI + 1] += scaleI * cotElI * Math.cos(azI);
                            H[nvOut * nx + iiI + 2] += scaleI * cotElI * Math.sin(azI);
                            H[nvOut * nx + iiJ + 1] -= scaleJ * cotElJ * Math.cos(azJ);
                            H[nvOut * nx + iiJ + 2] -= scaleJ * cotElJ * Math.sin(azJ);
                        }
                    }
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

    private static int ddidx(Rtk rtk, int[] sat, int ns, int[] ix, int nf) {
        if (rtk.rtkConfig.enableParRefReselect) {
            return RtkOptimizations.buildParIndex(rtk, sat, ns, nf, ix, -1, -1, 0);
        }
        return RtkOptimizations.ddidxFallback(rtk, ix, -1, -1, 0);
    }

    private static int resamb_LAMBDA(Rtk rtk, Obsd[] obs, int nu, int nr, Nav nav,
                                     int[] sat, int ns, int[] iu, int[] ir, double[] azel) {
        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nx = rtk.nx;
        int na = ns * nf;

        int[] ambIdx = new int[na];
        for (int i = 0; i < ns; i++) {
            for (int f = 0; f < nf; f++) {
                ambIdx[i * nf + f] = IB(sat[i], f, opt);
            }
        }

        int anchoredCount = 0;
        int[] anchoredMap = new int[na];
        int[] freeMap = new int[na];
        int freeCount = 0;

        if (rtk.rtkConfig.enableAmbAnchor) {
            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                for (int f = 0; f < nf; f++) {
                    int localIdx = i * nf + f;
                    int globalIdx = s * nf + f;
                    if (rtk.ambAnchored[globalIdx]) {
                        anchoredMap[anchoredCount++] = localIdx;
                    } else {
                        freeMap[freeCount++] = localIdx;
                    }
                }
            }
        }

        if (freeCount == 0 && anchoredCount > 0) {
            for (int i = 0; i < na; i++) {
                rtk.xa[ambIdx[i]] = rtk.x[ambIdx[i]];
            }
            rtk.sol.ratio = (float) 999.9;
            return Constants.SOLQ_FIX;
        }

        int arNa = (freeCount > 0) ? freeCount : na;

        double[] a = new double[arNa];
        double[] Qa = new double[arNa * arNa];

        for (int i = 0; i < arNa; i++) {
            int srcLocal = (freeCount > 0) ? freeMap[i] : i;
            int srcIdx = ambIdx[srcLocal];
            a[i] = rtk.x[srcIdx];
            for (int j = 0; j < arNa; j++) {
                int srcJLocal = (freeCount > 0) ? freeMap[j] : j;
                int srcJ = ambIdx[srcJLocal];
                Qa[i * arNa + j] = rtk.P[srcIdx * nx + srcJ];
            }
        }

        double[] F = new double[arNa * 2];
        double[] s = new double[2];
        int info = Lambda.lambda(arNa, 2, a, Qa, F, s);

        if (info == 0) {
            double ratio = s[0] > 0 ? s[1] / s[0] : 0.0;
            if (ratio > opt.thresar[0]) {
                for (int i = 0; i < arNa; i++) {
                    int dstLocal = (freeCount > 0) ? freeMap[i] : i;
                    rtk.xa[ambIdx[dstLocal]] = F[i * 2];
                }
                for (int i = 0; i < anchoredCount; i++) {
                    int dstLocal = anchoredMap[i];
                    rtk.xa[ambIdx[dstLocal]] = rtk.x[ambIdx[dstLocal]];
                }

                rtk.sol.ratio = (float) ratio;
                return Constants.SOLQ_FIX;
            }
        }

        return Constants.SOLQ_FLOAT;
    }

    private static void holdamb(Rtk rtk, double[] xp, double[] Pp, int nx) {
        PrcOpt opt = rtk.opt;
        if (opt.modear != Constants.ARMODE_FIXHOLD) return;

        if (rtk.sol.stat == Constants.SOLQ_FIX) {
            rtk.nfix++;
            if (rtk.nfix >= opt.minfix) {
                rtk.holdambFlag = 1;
            }
        } else {
            rtk.nfix = 0;
        }

        if (rtk.holdambFlag == 0) return;

        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;

        if (rtk.rtkConfig.enableAmbAnchor && rtk.sol.stat == Constants.SOLQ_FIX) {
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

        int nsEst = 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int f = 0; f < nf; f++) {
                if (rtk.ssat[i].fix[f] > 0) {
                    nsEst++;
                    break;
                }
            }
        }
        if (nsEst == 0) return;

        double[] Hh = new double[nx * nsEst];
        double[] vh = new double[nsEst];
        double[] Rh = new double[nsEst * nsEst];
        int nh = 0;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int f = 0; f < nf; f++) {
                if (rtk.ssat[i].fix[f] > 0) {
                    int idx = IB(i + 1, f, opt);
                    if (idx < nx) {
                        Hh[nh * nx + idx] = 1.0;
                        vh[nh] = rtk.xa[idx] - xp[idx];

                        int globalIdx = i * nf + f;
                        if (rtk.rtkConfig.enableAmbAnchor && rtk.ambAnchored[globalIdx]) {
                            Rh[nh * nsEst + nh] = rtk.rtkConfig.ambAnchorVar;
                        } else {
                            Rh[nh * nsEst + nh] = opt.varholdamb;
                        }
                        nh++;
                    }
                }
            }
        }

        if (nh > 0) {
            KalmanFilter.update(xp, Pp, Hh, vh, Rh, nx, nh);
        }

        if (rtk.rtkConfig.enableAmbAnchor && rtk.sol.stat != Constants.SOLQ_FIX) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                for (int f = 0; f < nf; f++) {
                    if (rtk.ssat[i].fix[f] <= 0) {
                        int globalIdx = i * nf + f;
                        rtk.ambAnchorCount[globalIdx] = 0;
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
}