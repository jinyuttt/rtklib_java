package org.rtklib.java.pntpos;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SppCore {
    private SppCore() {}
    private static final Logger LOG = LoggerFactory.getLogger(SppCore.class);

    private static final int MAXITR = 10;
    private static final double MIN_EL = 5.0 * Constants.D2R;
    private static final double ERR_CBIAS = 0.3;

    /* [DIFF-C] C version uses fixed NX=4+4 (or 4+5 with QZSDT), always estimating
       all inter-system clock biases. Java version dynamically computes NX based on
       navsys, only including enabled systems. This is more efficient but requires
       consistent sysIdx() and mask[] handling. */
    public static int nx(PrcOpt opt) {
        int nx = 4;
        if ((opt.navsys & Constants.SYS_GLO) != 0) nx++;
        if ((opt.navsys & Constants.SYS_GAL) != 0) nx++;
        if ((opt.navsys & Constants.SYS_CMP) != 0) nx++;
        if ((opt.navsys & Constants.SYS_IRN) != 0) nx++;
        return nx;
    }

    public static int sysIdx(int sys, PrcOpt opt) {
        int idx = 4;
        if ((opt.navsys & Constants.SYS_GLO) != 0) {
            if (sys == Constants.SYS_GLO) return idx;
            idx++;
        }
        if ((opt.navsys & Constants.SYS_GAL) != 0) {
            if (sys == Constants.SYS_GAL) return idx;
            idx++;
        }
        if ((opt.navsys & Constants.SYS_CMP) != 0) {
            if (sys == Constants.SYS_CMP) return idx;
            idx++;
        }
        if ((opt.navsys & Constants.SYS_IRN) != 0) {
            if (sys == Constants.SYS_IRN) return idx;
            idx++;
        }
        return -1;
    }

    public static double varerr(PrcOpt opt, Ssat ssat, Obsd obs, double el, int sys) {
        double fact;
        switch (sys) {
            case Constants.SYS_GPS: fact = 1.0; break;
            case Constants.SYS_GLO: fact = 1.5; break;
            case Constants.SYS_GAL: fact = 1.0; break;
            case Constants.SYS_SBS: fact = 3.0; break;
            case Constants.SYS_CMP: fact = 1.0; break;
            case Constants.SYS_QZS: fact = 1.0; break;
            case Constants.SYS_IRN: fact = 1.5; break;
            default: fact = 1.0; break;
        }
        if (el < MIN_EL) el = MIN_EL;
        double varr = RtklibCommon.sqr(opt.err[1]) + RtklibCommon.sqr(opt.err[2]) / Math.sin(el);
        if (opt.err[6] > 0.0) {
            double snrRover = (ssat != null) ? ssat.snrRover[0] : opt.err[5];
            varr += RtklibCommon.sqr(opt.err[6]) * Math.pow(10, 0.1 * Math.max(opt.err[5] - snrRover, 0));
        }
        varr *= RtklibCommon.sqr(opt.eratio[0]);
        if (opt.err[7] > 0.0) {
            varr += RtklibCommon.sqr(opt.err[7] * obs.Pstd[0]);
        }
        if (opt.ionoopt == Constants.IONOOPT_IFLC) varr *= 9.0;
        return RtklibCommon.sqr(fact) * varr;
    }

    public static double gettgd(int sat, Nav nav, int type) {
        int sys = SatUtils.satsys(sat, null);
        if (sys == Constants.SYS_GLO) {
            if (sat > 0 && sat <= nav.geph.length && nav.geph[sat - 1] != null && nav.geph[sat - 1].sat != 0) {
                return -nav.geph[sat - 1].dtaun * Constants.CLIGHT;
            }
            return 0.0;
        } else {
            int idx = sat - 1;
            if (idx >= 0 && idx < nav.eph.length && nav.eph[idx] != null && nav.eph[idx].A > 0) {
                return nav.eph[idx].tgd[type] * Constants.CLIGHT;
            }
            idx = sat - 1 + Constants.MAXSAT;
            if (idx < nav.eph.length && nav.eph[idx] != null && nav.eph[idx].A > 0) {
                return nav.eph[idx].tgd[type] * Constants.CLIGHT;
            }
            return 0.0;
        }
    }

    private static int getseleph(int sys) {
        return 0;
    }

    public static double prange(Obsd obs, Nav nav, PrcOpt opt, double[] var) {
        double P1, P2, gamma, b1, b2;
        int sat = obs.sat, sys = SatUtils.satsys(sat, null);
        int f2 = RtklibCommon.seliflc(opt.nf, sys);
        P1 = obs.P[0];
        P2 = obs.P[f2];
        var[0] = 0.0;
        if (P1 == 0.0 || (opt.ionoopt == Constants.IONOOPT_IFLC && P2 == 0.0)) return 0.0;
        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            if (sys == Constants.SYS_GPS || sys == Constants.SYS_QZS) {
                gamma = (f2 == 1) ? RtklibCommon.sqr(Constants.FREQL1 / Constants.FREQL2)
                                  : RtklibCommon.sqr(Constants.FREQL1 / Constants.FREQL5);
                return (P2 - gamma * P1) / (1.0 - gamma);
            } else if (sys == Constants.SYS_GLO) {
                gamma = (f2 == 1) ? RtklibCommon.sqr(Constants.FREQ1_GLO / Constants.FREQ2_GLO)
                                  : RtklibCommon.sqr(Constants.FREQ1_GLO / Constants.FREQ3_GLO);
                return (P2 - gamma * P1) / (1.0 - gamma);
            } else if (sys == Constants.SYS_GAL) {
                gamma = (f2 == 1) ? RtklibCommon.sqr(Constants.FREQL1 / Constants.FREQE5b)
                                  : RtklibCommon.sqr(Constants.FREQL1 / Constants.FREQL5);
                if (f2 == 1 && getseleph(Constants.SYS_GAL) != 0) {
                    P2 -= gettgd(sat, nav, 0) - gettgd(sat, nav, 1);
                }
                return (P2 - gamma * P1) / (1.0 - gamma);
            } else if (sys == Constants.SYS_CMP) { /* B1-B2 IFLC */
                /* [NOTE] When code[0]=0 (no B1I/B1C data), P1=0 and prange returns 0,
                   the satellite is skipped. This is consistent with C version behavior. */
                gamma = (obs.code[0] == Constants.CODE_L2I) ?
                        RtklibCommon.sqr(Constants.FREQ1_CMP / Constants.FREQ2_CMP) :
                        RtklibCommon.sqr(Constants.FREQL1 / Constants.FREQ2_CMP);
                if (obs.code[0] == Constants.CODE_L2I) b1 = gettgd(sat, nav, 0);
                else if (obs.code[0] == Constants.CODE_L1P) b1 = gettgd(sat, nav, 2);
                else b1 = gettgd(sat, nav, 2) + gettgd(sat, nav, 4);
                b2 = gettgd(sat, nav, 1);
                return ((P2 - gamma * P1) - (b2 - gamma * b1)) / (1.0 - gamma);
            } else if (sys == Constants.SYS_IRN) {
                gamma = RtklibCommon.sqr(Constants.FREQL5 / Constants.FREQs);
                return (P2 - gamma * P1) / (1.0 - gamma);
            }
        } else {
            var[0] = RtklibCommon.sqr(ERR_CBIAS);
            if (sys == Constants.SYS_GPS || sys == Constants.SYS_QZS) {
                b1 = gettgd(sat, nav, 0);
                return P1 - b1;
            } else if (sys == Constants.SYS_GLO) {
                gamma = RtklibCommon.sqr(Constants.FREQ1_GLO / Constants.FREQ2_GLO);
                b1 = gettgd(sat, nav, 0);
                return P1 - b1 / (gamma - 1.0);
            } else if (sys == Constants.SYS_GAL) {
                if (getseleph(Constants.SYS_GAL) != 0) b1 = gettgd(sat, nav, 0);
                else b1 = gettgd(sat, nav, 1);
                return P1 - b1;
            } else if (sys == Constants.SYS_CMP) { /* B1I/B1Cp/B1Cd single-freq */
                /* [NOTE] code[0]=CODE_L2I means B1I (freq0), CODE_L1P means B1Cp (freq4).
                   When code[0]=0 (e.g. BDS-3 satellite without B1I in first epoch),
                   P1=0 and prange returns 0, satellite is skipped. */
                if (obs.code[0] == Constants.CODE_L2I) b1 = gettgd(sat, nav, 0);
                else if (obs.code[0] == Constants.CODE_L1P) b1 = gettgd(sat, nav, 2);
                else b1 = gettgd(sat, nav, 2) + gettgd(sat, nav, 4);
                return P1 - b1;
            } else if (sys == Constants.SYS_IRN) {
                gamma = RtklibCommon.sqr(Constants.FREQs / Constants.FREQL5);
                b1 = gettgd(sat, nav, 0);
                return P1 - gamma * b1;
            }
        }
        return P1;
    }

    private static int snrmask(Obsd obs, double[] azel, int idx, PrcOpt opt) {
        if (RtklibCommon.testsnr(0, 0, azel[1 + idx * 2], obs.SNR[0], opt.snrmask) != 0) {
            return 0;
        }
        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            int f2 = RtklibCommon.seliflc(opt.nf, SatUtils.satsys(obs.sat, null));
            if (RtklibCommon.testsnr(0, f2, azel[1 + idx * 2], obs.SNR[f2], opt.snrmask) != 0) return 0;
        }
        return 1;
    }

    public static int rescode(int iter, Obsd[] obs, int n, double[] rs, double[] dts,
                               double[] vare, int[] svh, Nav nav, double[] x,
                               PrcOpt opt, Ssat[] ssat, double[] v, double[] H,
                               double[] var, double[] azel, int[] vsat,
                               double[] resp, int[] ns) {
        int NX = nx(opt);
        double[] rr = new double[3];
        double[] pos = new double[3];
        double[] e = new double[3];
        for (int i = 0; i < 3; i++) rr[i] = x[i];
        double dtr = x[3];
        CoordTransform.ecef2pos(rr, pos);
        int nv = 0;
        ns[0] = 0;
        int nClock = NX - 3;
        int[] mask = new int[nClock];

        
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            vsat[i] = 0;
            azel[i * 2] = azel[1 + i * 2] = resp[i] = 0.0;
            int sat = obs[i].sat;
            int sys = SatUtils.satsys(sat, null);
            if (sys == 0) {

                continue;
            }
            if (i < n - 1 && i < Constants.MAXOBS - 1 && sat == obs[i + 1].sat) {

                i++;
                continue;
            }
            double vareVal = (vare != null) ? vare[i] : 0.0;
            int svhVal = (svh != null) ? svh[i] : 0;
            if (RtklibCommon.satexclude(sat, vareVal, svhVal, opt) != 0) {

                continue;
            }
            double[] rsi = new double[]{rs[i * 6], rs[i * 6 + 1], rs[i * 6 + 2]};
            double r = RtklibCommon.geodist(rsi, rr, e);
            if (r <= 0.0) {
                continue;
            }
            double[] ae = new double[2];
            double el = RtklibCommon.satazel(pos, e, ae);
            azel[i * 2] = ae[0];
            azel[1 + i * 2] = ae[1];
            if (el < opt.elmin) {
                continue;
            }
            double dion = 0.0, vion = 0.0, dtrp = 0.0, vtrp = 0.0;
            if (iter > 0) {
                if (snrmask(obs[i], azel, i, opt) == 0) {

                    continue;
                }
                double[] ionOut = new double[2];
                if (!IonosphereModel.ionocorr(obs[i].time, nav, sat, pos, ae, opt.ionoopt, ionOut)) {

                    continue;
                }
                dion = ionOut[0];
                vion = ionOut[1];
                /* [NOTE] code[0]=0 occurs when a satellite has no B1I/B1C data in the
                   first frequency index (e.g. BDS-3 C10 in first epoch without B1I).
                   sat2freq returns 0 for code=0, causing the satellite to be skipped.
                   This is consistent with C version behavior. */
                double freq = SatUtils.sat2freq(sat, obs[i].code[0], nav);
                if (freq == 0.0) {

                    continue;
                }
                dion *= RtklibCommon.sqr(Constants.FREQL1 / freq);
                vion *= RtklibCommon.sqr(RtklibCommon.sqr(Constants.FREQL1 / freq));
                double[] trpOut = new double[2];
                if (!TroposphereModel.tropcorr(obs[i].time, nav, pos, ae, opt.tropopt, trpOut)) {

                    continue;
                }
                dtrp = trpOut[0];
                vtrp = trpOut[1];
            }
            double[] vmeas = new double[1];
            double P = prange(obs[i], nav, opt, vmeas);
            if (P == 0.0) {
                continue;
            }
            v[nv] = P - (r + dtr - Constants.CLIGHT * dts[i * 2] + dion + dtrp);
            if (iter <= 1) {
                LOG.debug(String.format("SPP sat=%2d sys=%d P=%.3f r=%.3f dtr=%.6f dts=%.6f dion=%.3f dtrp=%.3f v=%.3f",
                    sat, sys, P, r, dtr, dts[i * 2], dion, dtrp, v[nv]));
            }
            for (int j = 0; j < NX; j++) {
                H[j + nv * NX] = (j < 3) ? -e[j] : (j == 3 ? 1.0 : 0.0);
            }
            /* [DIFF-C] In C version, x[3] is GPS-specific clock bias. GPS satellites set
               mask[0]=1 via else branch, other systems set mask[si-3]=1. In Java version,
               x[3] is the common receiver clock bias. When si>0 (non-GPS system), both
               x[3] and x[si] have coefficient 1.0 in H matrix, causing rank deficiency.
               The constraint loop (mask[i]==0) constrains x[3]=0 to resolve this, which
               effectively makes x[si] absorb the full clock bias. This is equivalent to
               C version where x[3] (GPS) is constrained to 0 when only BDS is used. */
            int si = sysIdx(sys, opt);
            if (si > 0) {
                v[nv] -= x[si];
                H[si + nv * NX] = 1.0;
                mask[si - 3] = 1;
            } else {
                mask[0] = 1;
            }
            vsat[i] = 1;
            resp[i] = v[nv];
            ns[0]++;
            var[nv] = vareVal + vmeas[0] + vion + vtrp;
            if (ssat != null) var[nv] += varerr(opt, ssat[i], obs[i], ae[1], sys);
            else var[nv] += varerr(opt, null, obs[i], ae[1], sys);
            nv++;
        }
        for (int i = 0; i < nClock; i++) {
            if (mask[i] != 0) continue;
            v[nv] = 0.0;
            for (int j = 0; j < NX; j++) H[j + nv * NX] = (j == i + 3) ? 1.0 : 0.0;
            var[nv++] = 0.01;
        }
        return nv;
    }

    public static int estpos(Obsd[] obs, int n, double[] rs, double[] dts,
                              double[] vare, int[] svh, Nav nav, PrcOpt opt,
                              Ssat[] ssat, Sol sol, double[] azel, int[] vsat,
                              double[] resp, String[] msg) {
        int NX = nx(opt);
        double[] x = new double[NX];
        double[] dx = new double[NX];
        double[] Q = new double[NX * NX];
        int m = n + NX - 3;
        double[] v = new double[m];
        double[] H = new double[NX * m];
        double[] var = new double[m];
        for (int i = 0; i < 3; i++) x[i] = sol.rr[i];
        for (int it = 0; it < MAXITR; it++) {
            int[] ns = new int[1];
            int nv = rescode(it, obs, n, rs, dts, vare, svh, nav, x, opt, ssat,
                              v, H, var, azel, vsat, resp, ns);
            if (nv < NX) {
                msg[0] = "lack of valid sats ns=" + ns[0] + " nv=" + nv + " NX=" + NX;
                return 0;
            }
            if (it <= 1) {
                for (int j = 0; j < nv; j++) {
                }
            }
            for (int j = 0; j < nv; j++) {
                double sig = Math.sqrt(var[j]);
                v[j] /= sig;
                for (int k = 0; k < NX; k++) H[k + j * NX] /= sig;
            }
            if (RtklibCommon.lsq(H, v, NX, nv, dx, Q) != 0) {
                msg[0] = "lsq error";
                return 0;
            }
            for (int j = 0; j < NX; j++) x[j] += dx[j];
            double normDx = RtklibCommon.norm(dx, NX);
            LOG.debug(String.format("SPP iter=%d dx_norm=%.6f x=(%.3f,%.3f,%.3f) clk=%.6f",
                it, normDx, x[0], x[1], x[2], x[3]));
            if (normDx < 1E-4) {
                sol.type = 0;
                sol.time = TimeSystem.timeadd(obs[0].time, -x[3] / Constants.CLIGHT);
                sol.dtr[0] = x[3] / Constants.CLIGHT;
                int dtrIdx = 1;
                if ((opt.navsys & Constants.SYS_GLO) != 0) sol.dtr[dtrIdx++] = x[4] / Constants.CLIGHT;
                if ((opt.navsys & Constants.SYS_GAL) != 0) sol.dtr[dtrIdx++] = x[sysIdx(Constants.SYS_GAL, opt)] / Constants.CLIGHT;
                if ((opt.navsys & Constants.SYS_CMP) != 0) sol.dtr[dtrIdx++] = x[sysIdx(Constants.SYS_CMP, opt)] / Constants.CLIGHT;
                if ((opt.navsys & Constants.SYS_IRN) != 0) sol.dtr[dtrIdx++] = x[sysIdx(Constants.SYS_IRN, opt)] / Constants.CLIGHT;
                for (int j = 0; j < 6; j++) sol.rr[j] = (j < 3) ? x[j] : 0.0;
                for (int j = 0; j < 3; j++) sol.qr[j] = (float) Q[j + j * NX];
                sol.qr[3] = (float) Q[1];
                sol.qr[4] = (float) Q[2 + NX];
                sol.qr[5] = (float) Q[2];
                sol.ns = (byte) ns[0];
                sol.age = sol.ratio = 0.0f;
                sol.stat = Constants.SOLQ_SINGLE;
                return 1;
            }
        }
        msg[0] = "iteration divergent";
        return 0;
    }
}