package org.rtklib.java.pntpos;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SppOptimizations {
    private static final Logger LOG = LoggerFactory.getLogger(SppOptimizations.class);
    private static final int MAXITR = 10;

    private static final int ROBUST_POS = 1;
    private static final int ROBUST_VEL = 2;

    private SppOptimizations() {
    }

    static double robustWeight(double resid, double sig, RtkConfig cfg, int mode) {
        resid = Math.abs(resid);
        if (sig == 0.0) sig = 1.0;
        double k = (mode == ROBUST_POS) ? cfg.sppRobustKPos : cfg.sppRobustKVel;
        double outlierThresh = (mode == ROBUST_POS) ? cfg.sppRobustPosOutlier : cfg.sppRobustVelOutlier;
        double z = resid / sig;
        if (resid >= outlierThresh) {
            return cfg.sppRobustMinW / sig;
        }
        if (z < k) {
            return 1.0 / sig;
        } else {
            return k / resid;
        }
    }

    static int robustLsq(double[] H, double[] v, int nx, int nv, double[] dx, double[] Q,
                          double[] var, RtkConfig cfg, int mode) {
        double[] vNew = new double[nv];
        double[] HNew = new double[nx * nv];
        double[] dxPrev = new double[nx];
        double[] ddx = new double[nx];
        System.arraycopy(dx, 0, dxPrev, 0, nx);
        for (int i = 0; i < nx; i++) dx[i] = 0.0;

        for (int iter = 0; iter < cfg.sppRobustMaxIter; iter++) {
            for (int i = 0; i < nv; i++) {
                double sig = Math.sqrt(var[i]);
                double hdx = 0.0;
                for (int j = 0; j < nx; j++) {
                    hdx += H[i * nx + j] * dx[j];
                }
                double resid = Math.abs(v[i] - hdx);
                double weight = robustWeight(resid, sig, cfg, mode);
                vNew[i] = v[i] * weight;
                for (int j = 0; j < nx; j++) {
                    HNew[i * nx + j] = H[i * nx + j] * weight;
                }
            }
            if (RtklibCommon.lsq(HNew, vNew, nx, nv, dx, Q) != 0) {
                return -1;
            }
            for (int i = 0; i < nx; i++) ddx[i] = dx[i] - dxPrev[i];
            System.arraycopy(dx, 0, dxPrev, 0, nx);
            if (RtklibCommon.norm(ddx, nx) < 1e-2) break;
        }
        return 0;
    }

    static void robustFilter(double[] H, double[] v, int nx, int nc, int nd,
                             double[] var, RtkConfig cfg) {
        int nv = nc + nd;
        double[] vNew = new double[nv];
        double[] HNew = new double[nx * nv];
        double[] dx = new double[nx];
        double[] dxPrev = new double[nx];
        double[] ddx = new double[nx];
        double[] Q = new double[nx * nx];

        for (int iter = 0; iter < cfg.sppRobustMaxIter; iter++) {
            for (int i = 0; i < nv; i++) {
                double sig = Math.sqrt(var[i]);
                double hdx = 0.0;
                for (int j = 0; j < nx; j++) {
                    hdx += H[i * nx + j] * dx[j];
                }
                double resid = Math.abs(v[i] - hdx);
                int mode = (i >= nc) ? ROBUST_VEL : ROBUST_POS;
                double weight = robustWeight(resid, sig, cfg, mode);
                vNew[i] = v[i] * weight;
                for (int j = 0; j < nx; j++) {
                    HNew[i * nx + j] = H[i * nx + j] * weight;
                }
            }
            for (int i = 0; i < nc; i++) {
                vNew[i] -= 1.0;
            }
            if (RtklibCommon.lsq(HNew, vNew, nx, nv, dx, Q) != 0) {
                break;
            }
            for (int i = 0; i < nx; i++) ddx[i] = dx[i] - dxPrev[i];
            System.arraycopy(dx, 0, dxPrev, 0, nx);
            if (RtklibCommon.norm(ddx, nx) < 1e-2) break;
        }
        for (int i = 0; i < nv; i++) {
            double sig = Math.sqrt(var[i]);
            double hdx = 0.0;
            for (int j = 0; j < nx; j++) {
                hdx += H[i * nx + j] * dx[j];
            }
            double resid = Math.abs(v[i] - hdx);
            int mode = (i >= nc) ? ROBUST_VEL : ROBUST_POS;
            double w = robustWeight(resid, sig, cfg, mode);
            var[i] = 1.0 / (w * w);
        }
    }

    static void udposSpp(SppEkfState state, Sol solLsq, double tt, PrcOpt opt, RtkConfig cfg) {
        int nxF = state.nxF;
        double[] x = state.x;
        double[] P = state.P;

        if (RtklibCommon.norm(x, 3) <= 0.0) {
            for (int i = 0; i < 3; i++) initx(state, solLsq.rr[i], cfg.sppEkfVarPos, i);
            for (int i = 3; i < 6; i++) initx(state, solLsq.rr[i < 6 ? i : 0], cfg.sppEkfVarVel, i);
            for (int i = 6; i < 9; i++) initx(state, 1e-6, cfg.sppEkfVarAcc, i);
            return;
        }

        double var = 0.0;
        for (int i = 0; i < 3; i++) var += P[i * nxF + i];
        var /= 3.0;

        if (var > cfg.sppEkfVarResetThresh && solLsq.stat > Constants.SOLQ_NONE) {
            for (int i = 0; i < 3; i++) initx(state, solLsq.rr[i], cfg.sppEkfVarPos, i);
            for (int i = 3; i < 6; i++) initx(state, solLsq.rr[i < 6 ? i : 0], cfg.sppEkfVarVel, i);
            for (int i = 6; i < 9; i++) initx(state, 1e-6, cfg.sppEkfVarAcc, i);
            LOG.trace("UdposSPP: reset position due to large variance: var={}", var);
            return;
        }

        int nx = 9;
        double[] F = new double[nx * nx];
        for (int i = 0; i < nx; i++) F[i * nx + i] = 1.0;
        for (int i = 0; i < 6; i++) F[i * nx + (i + 3)] = tt;
        for (int i = 0; i < 3; i++) F[i * nx + (i + 6)] = tt * tt / 2.0;

        double[] xc = new double[nx];
        double[] Pc = new double[nx * nx];
        for (int i = 0; i < nx; i++) {
            xc[i] = x[i];
            for (int j = 0; j < nx; j++) {
                Pc[i * nx + j] = P[i * nxF + j];
            }
        }

        double[] xp = new double[nx];
        for (int i = 0; i < nx; i++) {
            xp[i] = 0.0;
            for (int j = 0; j < nx; j++) {
                xp[i] += F[i * nx + j] * xc[j];
            }
        }

        double[] FP = new double[nx * nx];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nx; j++) {
                double sum = 0.0;
                for (int k = 0; k < nx; k++) {
                    sum += F[i * nx + k] * Pc[k * nx + j];
                }
                FP[i * nx + j] = sum;
            }
        }
        double[] Pnew = new double[nx * nx];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nx; j++) {
                double sum = 0.0;
                for (int k = 0; k < nx; k++) {
                    sum += FP[i * nx + k] * F[j * nx + k];
                }
                Pnew[i * nx + j] = sum;
            }
        }

        for (int i = 0; i < nx; i++) {
            x[i] = xp[i];
            for (int j = 0; j < nx; j++) {
                P[i * nxF + j] = Pnew[i * nx + j];
            }
        }

        double[] Q = new double[9];
        Q[0] = RtklibCommon.sqr(cfg.sppEkfAccPrn1) * Math.abs(tt);
        Q[4] = RtklibCommon.sqr(cfg.sppEkfAccPrn1) * Math.abs(tt);
        Q[8] = RtklibCommon.sqr(cfg.sppEkfAccPrn2) * Math.abs(tt);
        double[] pos = new double[3];
        CoordTransform.ecef2pos(x, pos);
        double[] Qv = covecef(pos, Q);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                P[(i + 6) * nxF + (j + 6)] += Qv[i * 3 + j];
            }
        }
    }

    private static double[] covecef(double[] pos, double[] Q) {
        double[] enu = new double[]{pos[0], pos[1], pos[2]};
        double sp = Math.sin(enu[0]), cp = Math.cos(enu[0]);
        double sl = Math.sin(enu[1]), cl = Math.cos(enu[1]);
        double[] R = new double[]{
            -sp * cl, -sp * sl, cp,
            -sl, cl, 0,
            cp * cl, cp * sl, sp
        };
        double[] RQ = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0.0;
                for (int k = 0; k < 3; k++) {
                    sum += R[i * 3 + k] * Q[k * 3 + j];
                }
                RQ[i * 3 + j] = sum;
            }
        }
        double[] result = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0.0;
                for (int k = 0; k < 3; k++) {
                    sum += RQ[i * 3 + k] * R[j * 3 + k];
                }
                result[i * 3 + j] = sum;
            }
        }
        return result;
    }

    private static void initx(SppEkfState state, double xi, double var, int i) {
        int nxF = state.nxF;
        state.x[i] = xi;
        for (int j = 0; j < nxF; j++) {
            state.P[i * nxF + j] = state.P[j * nxF + i] = (i == j) ? var : 0.0;
        }
    }

    static int resdopFilter(Obsd[] obs, int n, double[] rs, double[] dts,
                            Nav nav, double[] rr, double[] x, PrcOpt opt,
                            Ssat[] ssat, double[] azel, int[] vsat,
                            double[] v, double[] var, double[] H, int nxF,
                            RtkConfig cfg) {
        double[] pos = new double[3];
        double[] E = new double[9];
        double[] a = new double[3];
        double[] e = new double[3];
        double[] vs = new double[3];
        int nv = 0;

        CoordTransform.ecef2pos(rr, pos);
        CoordTransform.xyz2enu(pos, E);

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            double freq = SatUtils.sat2freq(obs[i].sat, obs[i].code[0], nav);
            int sys = SatUtils.satsys(obs[i].sat, null);

            if (sys == 0 || obs[i].D[0] == 0.0 || freq == 0.0 || vsat[i] == 0 ||
                    Math.sqrt(rs[3 + i * 6] * rs[3 + i * 6] + rs[4 + i * 6] * rs[4 + i * 6] + rs[5 + i * 6] * rs[5 + i * 6]) <= 0.0) {
                continue;
            }

            double cosel = Math.cos(azel[1 + i * 2]);
            a[0] = Math.sin(azel[i * 2]) * cosel;
            a[1] = Math.cos(azel[i * 2]) * cosel;
            a[2] = Math.sin(azel[1 + i * 2]);

            for (int j = 0; j < 3; j++) {
                e[j] = E[j] * a[0] + E[j + 3] * a[1] + E[j + 6] * a[2];
            }

            for (int j = 0; j < 3; j++) {
                vs[j] = rs[j + 3 + i * 6] - x[j + 3];
            }

            double rate = e[0] * vs[0] + e[1] * vs[1] + e[2] * vs[2] +
                    Constants.OMGE / Constants.CLIGHT *
                    (rs[4 + i * 6] * rr[0] + rs[1 + i * 6] * x[3] -
                     rs[3 + i * 6] * rr[1] - rs[i * 6] * x[4]);

            v[nv] = (-obs[i].D[0] * Constants.CLIGHT / freq - (rate + x[nxF - 1] - Constants.CLIGHT * dts[1 + i * 2]));

            if (cfg != null && cfg.enableSppDopplerSnr) {
                PrcOpt optDop = new PrcOpt(opt);
                optDop.eratio[0] = 30.0;
                if (ssat != null) {
                    var[nv] = SppCore.varerr(optDop, ssat[obs[i].sat - 1], obs[i], azel[1 + i * 2], sys);
                } else {
                    var[nv] = SppCore.varerr(optDop, null, obs[i], azel[1 + i * 2], sys);
                }
            } else {
                double err = opt.err[4];
                double sig = (err <= 0.0) ? 1.0 : err * Constants.CLIGHT / freq;
                var[nv] = sig * sig;
            }

            for (int j = 0; j < 3; j++) {
                H[nv * nxF + (j + 3)] = -e[j];
            }
            H[nv * nxF + (nxF - 1)] = 1.0;

            if (Math.abs(H[nv * nxF + 3]) < 1e-8) {
                continue;
            }
            nv++;
        }
        return nv;
    }

    static int estposFilter(Obsd[] obs, int n, double[] rs, double[] dts,
                            double[] vare, int[] svh, Nav nav, PrcOpt opt,
                            Ssat[] ssat, Sol sol, double[] azel, int[] vsat,
                            double[] resp, SppEkfState state, RtkConfig cfg) {
        int NX = SppCore.nx(opt);
        int nxF = state.nxF;
        int nClock = NX - 3;

        sol.stat = Constants.SOLQ_NONE;

        double[] xLast = new double[3];
        for (int i = 0; i < 3; i++) xLast[i] = state.x[i];

        Sol solLsq = new Sol();
        System.arraycopy(sol.rr, 0, solLsq.rr, 0, sol.rr.length);
        solLsq.stat = sol.stat;
        solLsq.time = sol.time;

        double dt = 0.0;
        if (state.prevTime != null) {
            dt = TimeSystem.timediff(obs[0].time, state.prevTime);
        }

        int statLsq;
        if (!state.initialized || RtklibCommon.norm(state.x, 3) < 1000 || Math.abs(dt) >= cfg.sppEkfDtGap) {
            statLsq = SppCore.estpos(obs, n, rs, dts, vare, svh, nav, opt, ssat, sol, azel, vsat, resp, new String[1]);
            if (statLsq > Constants.SOLQ_NONE) {
                double[] initVel = new double[3];
                for (int i = 0; i < 3; i++) initVel[i] = 1e-2;
                state.init(sol.rr, initVel, cfg);
                state.prevTime = new GTime(obs[0].time);
            } else {
                return -1;
            }
        } else {
            statLsq = SppCore.estpos(obs, n, rs, dts, vare, svh, nav, opt, ssat, solLsq, azel, vsat, resp, new String[1]);
            udposSpp(state, solLsq, dt, opt, cfg);

            if (statLsq > Constants.SOLQ_NONE) {
                double[] y = new double[6];
                for (int i = 0; i < 6; i++) {
                    y[i] = state.x[i] - solLsq.rr[i];
                }
                double scale = cfg.sppEkfVelResidScale;
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        state.P[(i + 3) * nxF + (j + 3)] += scale * y[i + 3] * y[j + 3];
                    }
                }
            }

            for (int i = 9; i < nxF; i++) {
                state.P[i * nxF + i] += cfg.sppEkfClkProcessNoise * cfg.sppEkfClkProcessNoise;
            }
        }

        int maxObs = n + nClock + n;
        double[] v = new double[maxObs];
        double[] H = new double[nxF * maxObs];
        double[] var = new double[maxObs];

        double[] x = new double[nxF];
        double[] P = new double[nxF * nxF];
        System.arraycopy(state.x, 0, x, 0, nxF);
        System.arraycopy(state.P, 0, P, 0, nxF * nxF);

        double[] x_ = new double[NX];
        for (int j = 0; j < NX; j++) {
            if (j >= 3) {
                x_[j] = x[j + 6];
            } else {
                x_[j] = x[j];
            }
        }

        int[] nsArr = new int[1];
        double[] Htmp = new double[NX * maxObs];
        int nc = SppCore.rescode(0, obs, n, rs, dts, vare, svh, nav, x_, opt, ssat,
                v, Htmp, var, azel, vsat, resp, nsArr);

        expandHMatrix(nc, Htmp, NX, H, nxF);

        double[] vDop = new double[n];
        double[] varDop = new double[n];
        double[] HDop = new double[nxF * n];
        int nd = resdopFilter(obs, n, rs, dts, nav, x, x, opt, ssat, azel, vsat,
                vDop, varDop, HDop, nxF, cfg);
        for (int j = 0; j < nd; j++) {
            v[nc + j] = vDop[j];
            var[nc + j] = varDop[j];
            for (int k = 0; k < nxF; k++) {
                H[(nc + j) * nxF + k] = HDop[j * nxF + k];
            }
        }

        int nv = nc + nd;

        int nxEff = nxF - 3;
        double[] HEff = new double[nxEff * nv];
        for (int j = 0; j < nv; j++) {
            for (int k = 0; k < nxEff; k++) {
                if (k >= 6) {
                    HEff[j * nxEff + k] = H[j * nxF + (k + 3)];
                } else {
                    HEff[j * nxEff + k] = H[j * nxF + k];
                }
            }
        }

        if (cfg.enableSppRobust) {
            robustFilter(HEff, v, nxEff, nc, nd, var, cfg);
        }

        double[] R = new double[nv * nv];
        for (int j = 0; j < nv; j++) {
            R[j * nv + j] = var[j];
        }

        double[] vOrig = new double[nv];
        double[] HOrig = new double[nv * nxF];
        System.arraycopy(v, 0, vOrig, 0, nv);
        for (int j = 0; j < nv; j++) {
            for (int k = 0; k < nxF; k++) {
                HOrig[j * nxF + k] = H[j * nxF + k];
            }
        }

        if (state.initialized && !cfg.enableSppRobust) {
            double PPosPre = Math.sqrt(P[0*nxF+0]+P[1*nxF+1]+P[2*nxF+2]);
            double PVelPre = Math.sqrt(P[3*nxF+3]+P[4*nxF+4]+P[5*nxF+5]);
            double PClkPre = Math.sqrt(P[9*nxF+9]);
            double PClkDPre = Math.sqrt(P[(nxF-1)*nxF+(nxF-1)]);
            
            double vmPRPre = 0, vmDPPre = 0;
            for (int j = 0; j < nc; j++) vmPRPre += Math.abs(v[j]);
            for (int j = nc; j < nv; j++) vmDPPre += Math.abs(v[j]);
            if (nc>0) vmPRPre/=nc; if (nd>0) vmDPPre/=nd;
            
            LOG.info(String.format("EKF pre-upd ep: x=(%.1f,%.1f,%.1f) dt=%.3f",
                    x[0], x[1], x[2], dt));
            LOG.info(String.format("  sqrt(P_pos)=%.1f P_vel=%.1f P_clk=%.1f P_clkd=%.1f nv=%d nc=%d nd=%d",
                    PPosPre, PVelPre, PClkPre, PClkDPre, nv, nc, nd));
            LOG.info(String.format("  innov: mean|PR|=%.3f mean|DP|=%.4f R_PR=%.2f R_DP=%.5f",
                    vmPRPre, vmDPPre, var[0], nd>0?var[nc]:0));
        }

        if (KalmanFilter.update(x, P, HOrig, vOrig, R, nxF, nv) != 0) {
            return -2;
        }

        if (state.initialized && !cfg.enableSppRobust) {
            double[] posLlh = new double[3];
            CoordTransform.ecef2pos(x, posLlh);
            double PVel = Math.sqrt(P[3*nxF+3]+P[4*nxF+4]+P[5*nxF+5]);
            LOG.info(String.format("EKF post-upd: x=(%.1f,%.1f,%.1f) v=(%.3f,%.3f,%.3f) h=%.1f P_vel=%.3f",
                    x[0], x[1], x[2],
                    x[3], x[4], x[5],
                    posLlh[2], PVel));
            if (Math.abs(x[0]) > 1e8 || Double.isNaN(x[0])) {
                LOG.warn("EKF diverged at epoch, returning stat=NONE");
                return -2;
            }
        }

        if (cfg.enableSppZeroVel) {
            double[] posEnu = new double[3];
            double[] enu = new double[3];
            CoordTransform.ecef2pos(x, posEnu);
            double[] velEcef = new double[]{x[3], x[4], x[5]};
            CoordTransform.ecef2enu(posEnu, velEcef, enu);
            if (Math.abs(enu[0]) < cfg.sppZeroVelSpeedThresh &&
                Math.abs(enu[1]) < cfg.sppZeroVelSpeedThresh &&
                RtklibCommon.norm(xLast, 3) >= 1000) {
                state.consecutiveZeroVelEpochs++;
                int nzupt = state.consecutiveZeroVelEpochs;
                double rate = 1.0 / nzupt;
                if (rate >= 1.0) {
                    for (int j = 0; j < 3; j++) {
                        x[j] = xLast[j];
                        x[j + 3] = 1e-4;
                    }
                } else {
                    for (int j = 0; j < 3; j++) {
                        x[j] = xLast[j] * (1.0 - rate) + x[j] * rate;
                        x[j + 3] = 1e-4;
                    }
                }
            } else {
                state.consecutiveZeroVelEpochs = 0;
            }
        }

        System.arraycopy(x, 0, state.x, 0, nxF);
        System.arraycopy(P, 0, state.P, 0, nxF * nxF);
        state.prevTime = new GTime(obs[0].time);

        sol.type = 0;
        sol.time = TimeSystem.timeadd(obs[0].time, -x[9] / Constants.CLIGHT);
        sol.dtr[0] = x[9] / Constants.CLIGHT;
        int gloIdx = SppCore.sysIdx(Constants.SYS_GLO, opt);
        int galIdx = SppCore.sysIdx(Constants.SYS_GAL, opt);
        int qzsIdx = SppCore.sysIdx(Constants.SYS_QZS, opt);
        int cmpIdx = SppCore.sysIdx(Constants.SYS_CMP, opt);
        int irnIdx = SppCore.sysIdx(Constants.SYS_IRN, opt);
        sol.dtr[1] = gloIdx > 0 ? x[9 + (gloIdx - 3)] / Constants.CLIGHT : 0.0;
        sol.dtr[2] = galIdx > 0 ? x[9 + (galIdx - 3)] / Constants.CLIGHT : 0.0;
        sol.dtr[3] = cmpIdx > 0 ? x[9 + (cmpIdx - 3)] / Constants.CLIGHT : 0.0;
        sol.dtr[4] = irnIdx > 0 ? x[9 + (irnIdx - 3)] / Constants.CLIGHT : 0.0;
        sol.dtr[5] = qzsIdx > 0 ? x[9 + (qzsIdx - 3)] / Constants.CLIGHT : 0.0;

        for (int j = 0; j < 6; j++) sol.rr[j] = x[j];
        for (int j = 0; j < 3; j++) sol.qr[j] = (float) P[j * nxF + j];
        sol.qr[3] = (float) P[1 * nxF + 0];
        sol.qr[4] = (float) P[2 * nxF + 1];
        sol.qr[5] = (float) P[2 * nxF + 0];
        for (int j = 0; j < 3; j++) sol.qv[j] = (float) P[(j + 3) * nxF + (j + 3)];
        sol.qv[3] = (float) P[4 * nxF + 3];
        sol.qv[4] = (float) P[5 * nxF + 4];
        sol.qv[5] = (float) P[5 * nxF + 3];

        sol.ns = (byte) nsArr[0];
        sol.age = sol.ratio = 0.0f;
        sol.stat = Constants.SOLQ_SINGLE;

        double[] dop = new double[4];
        RtklibCommon.dops(nsArr[0], azel, opt.elmin, dop);
        sol.gdop = (float) dop[0];
        sol.pdop = (float) dop[1];
        sol.hdop = (float) dop[2];
        sol.vdop = (float) dop[3];

        return 1;
    }

    private static void expandHMatrix(int nc, double[] Hsrc, int NX, double[] Hdst, int nxF) {
        for (int i = 0; i < nc; i++) {
            for (int j = 0; j < 3; j++) {
                Hdst[i * nxF + j] = Hsrc[i * NX + j];
            }
            for (int j = 3; j < NX; j++) {
                Hdst[i * nxF + (j + 6)] = Hsrc[i * NX + j];
            }
        }
    }
}