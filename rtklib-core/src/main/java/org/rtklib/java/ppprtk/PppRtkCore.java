package org.rtklib.java.ppprtk;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.ionosphere.SbasCorrection;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.ppp.PppOptimizations;
import org.rtklib.java.rtkpos.Tides;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PppRtkCore {
    private PppRtkCore() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppRtkCore.class);
    private static final int MAX_ITER = 8;
    private static final int MIN_NSAT_SOL = 4;

    private static final double VAR_POS = PppCore.VAR_POS;
    private static final double VAR_VEL = PppCore.VAR_VEL;
    private static final double VAR_CLK = PppCore.VAR_CLK;
    private static final double VAR_ZTD = PppCore.VAR_ZTD;
    private static final double VAR_IONO = PppCore.VAR_IONO;
    private static final double VAR_BIAS = PppCore.VAR_BIAS;
    private static final double VAR_GRA = PppCore.VAR_GRA;
    private static final double VAR_GLO_IFB = PppCore.VAR_GLO_IFB;

    public static int NF(PrcOpt opt) {
        return PppCore.NF(opt);
    }

    public static int NP(PrcOpt opt) {
        return PppCore.NP(opt);
    }

    public static int NC() {
        return PppCore.NC();
    }

    public static int NT(PrcOpt opt) {
        return PppCore.NT(opt);
    }

    public static int NI(PrcOpt opt) {
        return PppCore.NI(opt);
    }

    public static int NB(PrcOpt opt) {
        return PppCore.NB(opt);
    }

    public static int ppprtknx(PrcOpt opt) {
        return PppCore.pppnx(opt);
    }

    public static int IC(int s, PrcOpt opt) {
        return PppCore.IC(s, opt);
    }

    public static int IT(PrcOpt opt) {
        return PppCore.IT(opt);
    }

    public static int II(int sat, PrcOpt opt) {
        return PppCore.II(sat, opt);
    }

    public static int IB(int sat, int f, PrcOpt opt) {
        return PppCore.IB(sat, f, opt);
    }

    public static void ppprtkos(Rtk rtk, Obsd[] obs, int n, Nav nav) {
        PrcOpt opt = rtk.opt;
        RtkConfig cfg = rtk.rtkConfig;
        int nx = ppprtknx(opt);
        double ssrMaxAge = cfg != null ? cfg.ssrMaxAge : 60.0;
        boolean hasSsr = SsrCorrector.hasSsrData(nav.ssr);

        rtk.epoch++;

        if (rtk.epoch <= 3) {
            LOG.info("ppprtkos ENTRY epoch={} n={} nx={} hasSsr={} ionoopt={} tropopt={} sateph={}",
                rtk.epoch, n, nx, hasSsr, opt.ionoopt, opt.tropopt, opt.sateph);
        }

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

        if (rtk.nx == 0 || rtk.nx != nx) {
            rtk.nx = nx;
            rtk.x = new double[nx];
            rtk.P = new double[nx * nx];
            rtk.xa = new double[nx];
            rtk.Pa = new double[nx * nx];
            for (int i = 0; i < 3 && i < nx; i++) {
                rtk.x[i] = rtk.sol.rr[i];
            }
        }

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] var = new double[n];
        int[] svh = new int[n];
        double[] azel = new double[n * 2];
        int[] exc = new int[n];

        udstate(rtk, obs, n, nav, nx);

        EphModel.satposs(obs[0].time, obs, n, nav, rs, dts, var, svh, opt.sateph);

        if (hasSsr) {
            SsrCorrector.applyOrbitCorrection(nav.ssr, rs, obs, n);
            SsrCorrector.applyClockCorrection(nav.ssr, dts, obs, n);
            SsrCorrector.applyHrClockCorrection(nav.ssr, dts, obs, n);
        }

        if (hasSsr) {
            for (int i = 0; i < n; i++) {
                int sat = obs[i].sat;
                if (!SsrCorrector.isSsrValid(nav.ssr, sat, obs[i].time, ssrMaxAge)) {
                    exc[i] = 1;
                }
            }
        }

        if (opt.posopt[3] != 0) {
            PppCore.testeclipse(obs, n, nav, rs);
        }

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

            int nv = ppprtkRes(0, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, v, H, R, azel, nx);

            if (rtk.epoch <= 5 && iter == 0) {
                double maxRes = 0;
                for (int iv = 0; iv < nv; iv++) maxRes = Math.max(maxRes, Math.abs(v[iv]));
                double[] posD = new double[3];
                CoordTransform.ecef2pos(new double[]{xp[0], xp[1], xp[2]}, posD);
                LOG.info(String.format("ppprtkos epoch=%d iter=0 nv=%d nx=%d maxRes=%.3f pos=(%.6f,%.6f,%.1f)",
                    rtk.epoch, nv, nx, maxRes,
                    posD[0]*Constants.R2D, posD[1]*Constants.R2D, posD[2]));
            }

            if (nv == 0) {
                LOG.debug("ppprtkos: epoch={} iter={} nv=0", rtk.epoch, iter);
                break;
            }

            int info = KalmanFilter.update(xp, Pp, H, v, R, nx, nv);
            if (info != 0) {
                LOG.debug("ppprtkos: epoch={} iter={} KF update failed info={}", rtk.epoch, iter, info);
                break;
            }

            if (ppprtkRes(iter + 1, obs, n, rs, dts, var, svh, exc, nav, xp, rtk, null, null, null, azel, nx) != 0) {
                System.arraycopy(xp, 0, rtk.x, 0, nx);
                System.arraycopy(Pp, 0, rtk.P, 0, nx * nx);
                stat = Constants.SOLQ_PPP;
                if (rtk.epoch <= 5) {
                    double[] posC = new double[3];
                    CoordTransform.ecef2pos(new double[]{xp[0], xp[1], xp[2]}, posC);
                    LOG.info(String.format("ppprtkos epoch=%d CONVERGED iter=%d pos=(%.6f,%.6f,%.1f)",
                        rtk.epoch, iter, posC[0]*Constants.R2D, posC[1]*Constants.R2D, posC[2]));
                }
                break;
            }
        }

        if (stat == Constants.SOLQ_PPP) {
            if (cfg != null && cfg.enablePppRtkAR) {
                PppRtkAmbFix.ppprtkAmbFix(rtk, nav);
            }
            updateStat(rtk, obs, n, stat, nx);
        }
    }

    private static void udstate(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        PrcOpt opt = rtk.opt;
        double tt = rtk.tt;

        udpos(rtk, nx);
        udclk(rtk, nx);
        if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG
            || opt.tropopt == Constants.TROPOPT_SSR) {
            udtrop(rtk, nx);
        }
        if (opt.ionoopt == Constants.IONOOPT_EST || opt.ionoopt == Constants.IONOOPT_SSR) {
            udiono(rtk, obs, n, nav, nx);
        }
        udbias(rtk, obs, n, nav, nx);
    }

    private static void udpos(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        double norm = 0.0;
        for (int i = 0; i < 3; i++) norm += x[i] * x[i];
        norm = Math.sqrt(norm);

        if (norm <= 0.0) {
            for (int i = 0; i < 3; i++) {
                PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_POS, i);
            }
            if (opt.dynamics != 0) {
                for (int i = 3; i < 6; i++) PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_VEL, i);
                for (int i = 6; i < 9; i++) PppCore.initx(x, P, nx, 1E-6, PppCore.VAR_ACC, i);
            }
            return;
        }

        if (opt.mode == Constants.PMODE_PPP_STATIC) {
            for (int i = 0; i < 3; i++) {
                P[i * (nx + 1)] += PppCore.SQR(opt.prn[5]) * Math.abs(rtk.tt);
            }
            return;
        }

        if (opt.dynamics == 0) {
            for (int i = 0; i < 3; i++) {
                PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_POS, i);
            }
            return;
        }

        double var = 0.0;
        for (int i = 0; i < 3; i++) var += P[i * (nx + 1)];
        var /= 3.0;

        if (var > VAR_POS) {
            for (int i = 0; i < 3; i++) PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_POS, i);
            for (int i = 3; i < 6; i++) PppCore.initx(x, P, nx, rtk.sol.rr[i], VAR_VEL, i);
            for (int i = 6; i < 9; i++) PppCore.initx(x, P, nx, 1E-6, PppCore.VAR_ACC, i);
            return;
        }

        if (rtk.tt != 0.0) {
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

    private static void udclk(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;
        double dtr;

        for (int i = 0; i < NC(); i++) {
            if (opt.sateph == Constants.EPHOPT_PREC) {
                dtr = rtk.sol.dtr[0];
            } else {
                dtr = i == 0 ? rtk.sol.dtr[0] : rtk.sol.dtr[0] + rtk.sol.dtr[i];
            }
            PppCore.initx(x, P, nx, Constants.CLIGHT * dtr, VAR_CLK, IC(i, opt));
        }
    }

    private static void udtrop(Rtk rtk, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;
        double[] pos = new double[3];
        double[] azel = {0.0, Constants.PI / 2.0};

        int i = IT(opt);

        if (rtk.x[i] == 0.0) {
            CoordTransform.ecef2pos(rtk.sol.rr, pos);
            double[] varT = new double[1];
            double ztd = SbasCorrection.sbstropcorr(rtk.sol.time, pos, azel, varT);
            PppCore.initx(x, P, nx, ztd, varT[0], i);

            if (opt.tropopt >= Constants.TROPOPT_ESTG) {
                for (int j = i + 1; j < i + 3; j++) PppCore.initx(x, P, nx, 1E-6, VAR_GRA, j);
            }
        } else {
            P[i * nx + i] += PppCore.SQR(opt.prn[2]) * Math.abs(rtk.tt);

            if (opt.tropopt >= Constants.TROPOPT_ESTG) {
                for (int j = i + 1; j < i + 3; j++) {
                    P[j * nx + j] += PppCore.SQR(opt.prn[2] * 0.1) * Math.abs(rtk.tt);
                }
            }
        }
    }

    private static void udiono(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            int idx = II(i + 1, opt);
            if (rtk.sol.stat == Constants.SOLQ_NONE) {
                PppCore.initx(x, P, nx, 0.0, VAR_IONO, idx);
            }
        }

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            int sat = obs[i].sat;
            int idx = II(sat, opt);
            double sinel = Math.sin(rtk.ssat[sat - 1].azel[1]);
            P[idx * nx + idx] += PppCore.SQR(opt.prn[1] / Math.max(sinel, 0.1)) * Math.abs(rtk.tt);
        }
    }

    private static void udbias(Rtk rtk, Obsd[] obs, int n, Nav nav, int nx) {
        double[] x = rtk.x;
        double[] P = rtk.P;
        PrcOpt opt = rtk.opt;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < opt.nf; j++) {
                rtk.ssat[i].slip[j] = 0;
            }
        }

        PppCore.detslpLl(rtk, obs, n);
        PppCore.detslpGf(rtk, obs, n, nav);
        PppCore.detslpMw(rtk, obs, n, nav);

        for (int f = 0; f < NF(opt); f++) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (++rtk.ssat[i].outc[f] > opt.maxout) {
                    PppCore.initx(x, P, nx, 0.0, 0.0, IB(i + 1, f, opt));
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

                PppCore.corrMeas(obs[i], nav, rtk.ssat[sat - 1].azel, opt, dantr, dants, 0.0, L, P_arr, Lc, Pc);

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
                            ion = (obs[i].P[0] - obs[i].P[f]) / (1.0 - PppCore.SQR(freq1 / freq2));
                        }
                        bias[i] = L[f] - P_arr[f] + 2.0 * ion * PppCore.SQR(freq1 / freq2);
                    }
                }

                if (x[j] == 0.0 || slip[i] != 0 || bias[i] == 0.0) continue;

                offset += bias[i] - x[j];
                k++;
            }

            if (k >= 2 && Math.abs(offset / k) > 0.0005 * Constants.CLIGHT) {
                for (int i = 0; i < Constants.MAXSAT; i++) {
                    int j2 = IB(i + 1, f, opt);
                    if (x[j2] != 0.0) x[j2] += offset / k;
                }
            }

            for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
                int sat = obs[i].sat;
                int j = IB(sat, f, opt);

                P[j * nx + j] += PppCore.SQR(opt.prn[0]) * Math.abs(rtk.tt);

                if (bias[i] == 0.0 || (x[j] != 0.0 && slip[i] == 0)) continue;

                PppCore.initx(x, P, nx, bias[i], VAR_BIAS, IB(sat, f, opt));
            }
        }
    }

    private static int ppprtkRes(int post, Obsd[] obs, int n, double[] rs, double[] dts,
                                  double[] varRs, int[] svh, int[] exc,
                                  Nav nav, double[] x, Rtk rtk,
                                  double[] v, double[] H, double[] R,
                                  double[] azel, int nx) {
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
        double[] dion_vari = new double[2];
        double[] varr = new double[n * 2 * NF(opt)];
        int nv = 0;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < opt.nf; j++) rtk.ssat[i].vsat[j] = 0;
        }

        for (int i = 0; i < 3; i++) rr[i] = x[i];

        if (opt.tidecorr != 0) {
            double[] disp = new double[3];
            if (rtk.rtkConfig != null && rtk.rtkConfig.enableIers2010) {
                double[] disp2010 = PppOptimizations.tideDisplacementIers2010(
                        TimeSystem.gpst2utc(obs[0].time), rr, opt.tidecorr, nav.erp, opt.odisp[0], rtk.rtkConfig);
                if (disp2010 != null) {
                    for (int ii = 0; ii < 3; ii++) disp[ii] = disp2010[ii];
                } else {
                    PppCore.tidedisp(TimeSystem.gpst2utc(obs[0].time), rr, opt.tidecorr, nav.erp, opt.odisp[0], disp);
                }
            } else {
                PppCore.tidedisp(TimeSystem.gpst2utc(obs[0].time), rr, opt.tidecorr, nav.erp, opt.odisp[0], disp);
            }
            for (int i = 0; i < 3; i++) rr[i] += disp[i];
        }

        CoordTransform.ecef2pos(rr, pos);

        boolean hasSsr = SsrCorrector.hasSsrData(nav.ssr);

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
                continue;
            }

            double dtrp = 0.0;
            double vart = 0.0;
            double[] dtrpArr = new double[1];
            double[] vartArr = new double[1];
            if (!PppCore.modelTrop(obs[i].time, pos, azelI, opt, x, dtdx, nav, dtrpArr, vartArr, rtk)) continue;
            dtrp = dtrpArr[0];
            vart = vartArr[0];

            double dion = 0.0;
            double vari = 0.0;
            if (!PppCore.modelIono(obs[i].time, pos, azelI, opt, sat, x, nav, dion_vari)) continue;
            dion = dion_vari[0];
            vari = dion_vari[1];

            if (opt.ionoopt == Constants.IONOOPT_SSR && hasSsr) {
                if (SsrIono.hasSsrIono(nav.ssr, sat)) {
                    double freq0 = SatUtils.sat2freq(sat, obs[i].code[0], nav);
                    double lam0 = freq0 > 0 ? Constants.CLIGHT / freq0 : 0.0;
                    if (lam0 > 0.0) {
                        dion = SsrIono.ssrIonoDelay(obs[i].time, nav, sat, pos, azelI, 0, lam0);
                        vari = 0.0;
                    }
                }
            }

            for (int j = 0; j < Constants.NFREQ; j++) dantr[j] = dants[j] = 0.0;

            if (opt.posopt[0] != 0) {
                PppCore.satantpcv(rsi, rr, nav.pcvs[sat - 1], dants);
            }

            PppCore.antmodel(opt.pcvr[0], opt.antdel[0], azelI, opt.posopt[1], dantr);

            if (opt.posopt[2] != 0) {
                PppCore.windupcorr(rtk.sol.time, rsi, rr, rtk.ssat[sat - 1]);
            }

            double phw = rtk.ssat[sat - 1].phw;
            PppCore.corrMeas(obs[i], nav, azelI, opt, dantr, dants, phw, L, P_arr, Lc, Pc);

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
                    C = PppCore.SQR(Constants.FREQL1 / freq) * (code == 0 ? -1.0 : 1.0);
                }

                if (H != null) {
                    for (int kk = 0; kk < nx; kk++) H[nv * nx + kk] = 0.0;
                    for (int kk = 0; kk < 3; kk++) H[nv * nx + kk] = -e[kk];
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
                    H[nv * nx + IC(k, opt)] = 1.0;
                    if (opt.tropopt == Constants.TROPOPT_EST || opt.tropopt == Constants.TROPOPT_ESTG
                        || opt.tropopt == Constants.TROPOPT_SSR) {
                        int nt = opt.tropopt >= Constants.TROPOPT_ESTG ? 3 : 1;
                        for (int kk = 0; kk < nt; kk++) {
                            H[nv * nx + IT(opt) + kk] = dtdx[kk];
                        }
                    }
                }

                if (opt.ionoopt == Constants.IONOOPT_EST || opt.ionoopt == Constants.IONOOPT_SSR) {
                    if (H != null) H[nv * nx + II(sat, opt)] = C * PppCore.ionmapf(pos, azelI);
                }

                if (code == 0) {
                    bias = x[IB(sat, frq, opt)];
                    if (bias == 0.0) continue;
                    if (H != null) H[nv * nx + IB(sat, frq, opt)] = 1.0;
                }

                double ssrCb = 0.0;
                if (code == 1 && hasSsr) {
                    ssrCb = SsrCorrector.getCodeBias(nav.ssr, sat, obs[i].code[frq]);
                }

                double ssrPb = 0.0;
                if (code == 0 && hasSsr) {
                    ssrPb = SsrCorrector.getPhaseBias(nav.ssr, sat, obs[i].code[frq]);
                }

                double res = y - (r + cdtr - Constants.CLIGHT * dts[i * 2] + dtrp + C * dion + dcb + bias + ssrCb - ssrPb);
                if (v != null) v[nv] = res;

                if (code == 0) rtk.ssat[sat - 1].resc[frq] = res;
                else rtk.ssat[sat - 1].resp[frq] = res;

                varr[nv] = PppCore.varerr(sat, sys, azelI[1], rtk.ssat[sat - 1].snrRover[frq], j, opt);
                varr[nv] += vart + PppCore.SQR(C) * vari + varRs[i];
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
                for (int ii = 0; ii < nv; ii++) R[j * nv + ii] = 0.0;
                R[j * nv + j] = varr[j];
            }
        }

        if (post == 0 && nv == 0 && rtk.epoch <= 2) {
            LOG.warn("ppprtkRes: nv=0! n={} hasSsr={}", n, hasSsr);
        }

        return post != 0 ? 1 : nv;
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
        rtk.sol.qr[3] = (float) rtk.P[0 * nx + 1];
        rtk.sol.qr[4] = (float) rtk.P[1 * nx + 2];
        rtk.sol.qr[5] = (float) rtk.P[0 * nx + 2];

        rtk.sol.dtr[0] = rtk.x[IC(0, opt)] / Constants.CLIGHT;
        rtk.sol.dtr[1] = (rtk.x[IC(1, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;
        rtk.sol.dtr[2] = (rtk.x[IC(2, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;
        rtk.sol.dtr[3] = (rtk.x[IC(3, opt)] - rtk.x[IC(0, opt)]) / Constants.CLIGHT;

        double[] dopAzel = new double[Constants.MAXSAT * 2];
        int dopNs = 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
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
    }
}