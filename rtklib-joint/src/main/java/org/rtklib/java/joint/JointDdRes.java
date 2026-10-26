package org.rtklib.java.joint;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.RtkState;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.troposphere.TroposphereModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JointDdRes {
    private static final Logger log = LoggerFactory.getLogger(JointDdRes.class);
    private JointDdRes() {
    }

    public static int ddres(JointRtk rtk, BaselineGroup group,
                            BaselineObs[] blObs, Nav nav,
                            double dt, double[] x, double[] P,
                            double[] v, double[] H, double[] R, int[] vflg) {
        if (rtk.opt == null || group.roverObs == null) return 0;

        int nx = rtk.nx;
        int nf = (rtk.opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : rtk.opt.nf;
        int nv = 0;

        RtkState state = rtk.toState();

        double[] rr_f = new double[3];
        for (int i = 0; i < 3; i++) rr_f[i] = rtk.rb[i] + x[i];
        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr_f, pos);

        double bl = 0.0;
        for (int i = 0; i < 3; i++) bl += x[i] * x[i];
        bl = Math.sqrt(bl);

        int[] sysMap = new int[]{
                Constants.SYS_GPS | Constants.SYS_SBS, Constants.SYS_GLO, Constants.SYS_GAL,
                Constants.SYS_CMP, Constants.SYS_QZS, Constants.SYS_IRN
        };

        int maxNv = 0;
        for (int b = 0; b < group.nBaseline; b++) {
            if (blObs[b] != null) maxNv += blObs[b].ns * nf * 2;
        }
        if (maxNv == 0) return 0;

        double[] Ri = (R != null) ? new double[maxNv] : null;
        double[] Rj = (R != null) ? new double[maxNv] : null;
        int[] nb = new int[maxNv + 2];
        int bCnt = 0;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < Constants.NFREQ; j++) {
                rtk.ssat[i].resp[j] = 0.0;
                rtk.ssat[i].resc[j] = 0.0;
            }
        }

        for (int b = 0; b < group.nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;

            BaseStation bs = group.bases[b];
            double[] rr_r = bs.rb;
            double[] pos_r = new double[3];
            CoordTransform.ecef2pos(rr_r, pos_r);

            int blOffset = rtk.baselineOffset[b];

            for (int m = 0; m < 6; m++) {
                for (int f = (rtk.opt.mode > Constants.PMODE_DGPS ? 0 : nf); f < nf * 2; f++) {
                    int frq = f % nf;
                    boolean code = f >= nf;

                    int refIdx = selectRefSat(rtk, bo, m, f, nf, sysMap, group);
                    if (refIdx < 0) {
                        if (b == 0 && f == 0) {
                            log.debug("baseline[{}] sys={} f={} no ref sat, bo.ns={}", b, m, f, bo.ns);
                        }
                        continue;
                    }

                    int cntBefore = nv;

                    for (int j = 0; j < bo.ns; j++) {
                        if (j == refIdx) continue;
                        int sysj = SatUtils.satsys(bo.sat[j], null);
                        if ((sysj & sysMap[m]) == 0) continue;

                        if (!validobs(bo.iu[j], bo.ir[j], f, nf, bo.y)) {
                            if (nv == 0 && b == 0) {
                                log.debug("baseline[{}] sat={} validobs failed: iu={}, ir={}, f={}, y[iu]={}, y[ir]={}",
                                        b, bo.sat[j], bo.iu[j], bo.ir[j], f,
                                        bo.y[f + bo.iu[j] * nf * 2], bo.y[f + bo.ir[j] * nf * 2]);
                            }
                            continue;
                        }

                        double freqi = SatUtils.sat2freq(bo.sat[refIdx],
                                group.roverObs[bo.iu[refIdx]].code[frq], nav);
                        double freqj = SatUtils.sat2freq(bo.sat[j],
                                group.roverObs[bo.iu[j]].code[frq], nav);
                        if (freqi <= 0.0 || freqj <= 0.0) continue;

                        if (H != null) {
                            for (int k = 0; k < nx; k++) H[nv * nx + k] = 0.0;
                        }

                        int idx_i = bo.iu[refIdx];
                        int idx_ir = bo.ir[refIdx];
                        int idx_j = bo.iu[j];
                        int idx_jr = bo.ir[j];

                        v[nv] = (bo.y[f + idx_i * nf * 2] - bo.y[f + idx_ir * nf * 2])
                                - (bo.y[f + idx_j * nf * 2] - bo.y[f + idx_jr * nf * 2]);

                        if (H != null) {
                            for (int k = 0; k < 3; k++) {
                                H[nv * nx + k] = -bo.e[k + idx_i * 3] + bo.e[k + idx_j * 3];
                            }
                        }

                        if (rtk.opt.mode > Constants.PMODE_DGPS && !code) {
                            int ii = rtk.IB(b, bo.sat[refIdx], frq);
                            int jj = rtk.IB(b, bo.sat[j], frq);
                            if (rtk.opt.ionoopt != Constants.IONOOPT_IFLC) {
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

                        if (rtk.opt.ionoopt == Constants.IONOOPT_EST && !code) {
                            addIonoContribution(rtk, b, bo, j, refIdx, pos, x, v, H, nv, nx, frq, nav);
                        }

                        if (rtk.opt.tropopt == Constants.TROPOPT_EST || rtk.opt.tropopt == Constants.TROPOPT_ESTG) {
                            addTropContribution(rtk, b, rr_f, rr_r, bo, j, refIdx, x, H, nv, nx);
                        }

                        if (code) {
                            rtk.ssat[bo.sat[j] - 1].resp[frq] = v[nv];
                        } else {
                            rtk.ssat[bo.sat[j] - 1].resc[frq] = v[nv];
                        }

                        double threshadj = 1.0;
                        if (rtk.opt.mode > Constants.PMODE_DGPS) {
                            int ii = rtk.IB(b, bo.sat[refIdx], frq);
                            int jj = rtk.IB(b, bo.sat[j], frq);
                            double Pii = P[ii * nx + ii];
                            double Pjj = P[jj * nx + jj];
                            double std0sq = rtk.opt.std[0] * rtk.opt.std[0];
                            if (Pii == std0sq || Pjj == std0sq) {
                                threshadj = 10.0;
                            }
                        }
                        double maxinno_val = rtk.opt.maxinno[code ? 1 : 0] * threshadj;
                        if (Math.abs(v[nv]) > maxinno_val) {
                            rtk.ssat[bo.sat[j] - 1].vsat[frq] = 0;
                            rtk.ssat[bo.sat[j] - 1].rejc[frq]++;
                            continue;
                        }

                        double eli = bo.azel[1 + bo.iu[refIdx] * 2];
                        double elj = bo.azel[1 + bo.iu[j] * 2];
                        int sysRef = SatUtils.satsys(bo.sat[refIdx], null);
                        int sysJ = SatUtils.satsys(bo.sat[j], null);

                        if (Ri != null) {
                            Ri[nv] = RtkCore.varerr(bo.sat[refIdx], sysRef, eli,
                                    rtk.ssat[bo.sat[refIdx] - 1].snrRover[frq],
                                    rtk.ssat[bo.sat[refIdx] - 1].snrBase[frq],
                                    bl, dt, f, rtk.opt, group.roverObs[bo.iu[refIdx]], state);
                            Rj[nv] = RtkCore.varerr(bo.sat[j], sysJ, elj,
                                    rtk.ssat[bo.sat[j] - 1].snrRover[frq],
                                    rtk.ssat[bo.sat[j] - 1].snrBase[frq],
                                    bl, dt, f, rtk.opt, group.roverObs[bo.iu[j]], state);
                            if (!code) {
                                if ((group.roverObs[bo.iu[refIdx]].LLI[frq] & Constants.LLI_HALFC) != 0)
                                    Ri[nv] += 0.01;
                                if ((group.roverObs[bo.iu[j]].LLI[frq] & Constants.LLI_HALFC) != 0)
                                    Rj[nv] += 0.01;
                            }
                        }

                        if (rtk.opt.mode > Constants.PMODE_DGPS) {
                            if (!code) {
                                rtk.ssat[bo.sat[refIdx] - 1].vsat[frq] = 1;
                                rtk.ssat[bo.sat[j] - 1].vsat[frq] = 1;
                            }
                        } else {
                            rtk.ssat[bo.sat[refIdx] - 1].vsat[frq] = 1;
                            rtk.ssat[bo.sat[j] - 1].vsat[frq] = 1;
                        }

                        vflg[nv] = (bo.sat[refIdx] << 16) | (bo.sat[j] << 8) | ((code ? 1 : 0) << 4) | frq;
                        nv++;
                        nb[bCnt]++;
                    }
                    bCnt++;
                }
            }
        }

        if (R != null) {
            ddcov(nb, bCnt, Ri, Rj, nv, R);
        }

        return nv;
    }

    private static int selectRefSat(JointRtk rtk, BaselineObs bo, int m, int f, int nf,
                                    int[] sysMap, BaselineGroup group) {
        int frq = f % nf;
        int refIdx = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int sysMatchCount = 0;
        int validCount = 0;

        for (int j = 0; j < bo.ns; j++) {
            int sysj = SatUtils.satsys(bo.sat[j], null);
            if ((sysj & sysMap[m]) == 0) continue;
            sysMatchCount++;
            if (sysj == Constants.SYS_SBS) continue;
            if (!validobs(bo.iu[j], bo.ir[j], f, nf, bo.y)) continue;
            validCount++;

            if (group.globalRefSat > 0 && bo.sat[j] == group.globalRefSat) {
                return j;
            }

            if (refIdx >= 0 && (rtk.ssat[bo.sat[j] - 1].slip[frq] & Constants.LLI_SLIP) != 0) continue;

            double el_j = bo.azel[1 + bo.iu[j] * 2];
            if (refIdx < 0 || el_j >= bo.azel[1 + bo.iu[refIdx] * 2]) {
                refIdx = j;
            }
        }
        if (refIdx < 0 && sysMatchCount > 0) {
            log.debug("selectRefSat: m={} sysMatch={} valid={} f={} nf={} bo.ns={}", m, sysMatchCount, validCount, f, nf, bo.ns);
        }
        if (refIdx < 0 && sysMatchCount == 0 && bo.ns > 0) {
            int sys0 = SatUtils.satsys(bo.sat[0], null);
            log.debug("selectRefSat: m={} sysMatch=0 sys0={} sysMap={} bo.ns={}", m, sys0, sysMap[m], bo.ns);
        }
        if (false && refIdx < 0 && sysMatchCount > 0 && validCount == 0) {
            int j0 = -1;
            for (int jj = 0; jj < bo.ns; jj++) {
                int sysj = SatUtils.satsys(bo.sat[jj], null);
                if ((sysj & sysMap[m]) != 0 && sysj != Constants.SYS_SBS) { j0 = jj; break; }
            }
            if (j0 >= 0) {
                log.debug("selectRefSat: m={} sysMatch={} valid=0 f={} nf={} bo.ns={} sat={} iu={} ir={} yRover={} yBase={} azel_el={}",
                        m, sysMatchCount, f, nf, bo.ns, bo.sat[j0], bo.iu[j0], bo.ir[j0],
                        bo.y[f + bo.iu[j0] * nf * 2], bo.y[f + bo.ir[j0] * nf * 2],
                        bo.azel[1 + bo.iu[j0] * 2]);
            }
        }
        return refIdx;
    }

    private static void addIonoContribution(JointRtk rtk, int baseline, BaselineObs bo,
                                            int j, int refIdx, double[] pos,
                                            double[] x, double[] v, double[] H,
                                            int nv, int nx, int frq, Nav nav) {
        int ii_m = rtk.II(baseline, bo.sat[j]);
        int ii_r = rtk.II(baseline, bo.sat[refIdx]);
        if (ii_m < 0 || ii_r < 0 || ii_m >= nx || ii_r >= nx) return;

        double az_m = bo.azel[bo.iu[j] * 2];
        double el_m = bo.azel[bo.iu[j] * 2 + 1];
        double az_r = bo.azel[bo.iu[refIdx] * 2];
        double el_r = bo.azel[bo.iu[refIdx] * 2 + 1];

        double im_m = IonosphereModel.ionmapf(pos, new double[]{az_m, el_m});
        double im_r = IonosphereModel.ionmapf(pos, new double[]{az_r, el_r});

        double freqj = bo.freq[frq + bo.iu[j] * rtk.opt.nf];
        double freqi = bo.freq[frq + bo.iu[refIdx] * rtk.opt.nf];
        if (freqj <= 0.0 || freqi <= 0.0) return;

        double freqRatio_m = RtkCore.SQR(Constants.FREQL1 / freqj);
        double freqRatio_r = RtkCore.SQR(Constants.FREQL1 / freqi);
        double didx_m = im_m * freqRatio_m;
        double didx_r = im_r * freqRatio_r;

        v[nv] += didx_m * x[ii_m] - didx_r * x[ii_r];
        if (H != null) {
            H[nv * nx + ii_m] += didx_m;
            H[nv * nx + ii_r] -= didx_r;

            if (rtk.opt.ionoGradient && el_m > 0.0 && el_r > 0.0) {
                double cotz_m = 1.0 / Math.tan(el_m);
                double grad_n_m = im_m * cotz_m * Math.cos(az_m);
                double grad_e_m = im_m * cotz_m * Math.sin(az_m);
                double didxGn_m = x[ii_m] * grad_n_m * freqRatio_m;
                double didxGe_m = x[ii_m] * grad_e_m * freqRatio_m;
                if (ii_m + 1 < nx) H[nv * nx + ii_m + 1] += didxGn_m;
                if (ii_m + 2 < nx) H[nv * nx + ii_m + 2] += didxGe_m;

                double cotz_r = 1.0 / Math.tan(el_r);
                double grad_n_r = im_r * cotz_r * Math.cos(az_r);
                double grad_e_r = im_r * cotz_r * Math.sin(az_r);
                double didxGn_r = x[ii_r] * grad_n_r * freqRatio_r;
                double didxGe_r = x[ii_r] * grad_e_r * freqRatio_r;
                if (ii_r + 1 < nx) H[nv * nx + ii_r + 1] -= didxGn_r;
                if (ii_r + 2 < nx) H[nv * nx + ii_r + 2] -= didxGe_r;
            }
        }
    }

    private static void addTropContribution(JointRtk rtk, int baseline,
                                            double[] rr_f, double[] rr_r,
                                            BaselineObs bo, int j, int refIdx,
                                            double[] x, double[] H, int nv, int nx) {
        double[] dtdx_m = new double[nx];
        double[] dtdx_r = new double[nx];
        jointPrecTrop(rtk, rr_f, baseline, 0,
                new double[]{bo.azel[bo.iu[j] * 2], bo.azel[bo.iu[j] * 2 + 1]}, dtdx_m, nx);
        jointPrecTrop(rtk, rr_r, baseline, 1,
                new double[]{bo.azel[bo.iu[refIdx] * 2], bo.azel[bo.iu[refIdx] * 2 + 1]}, dtdx_r, nx);
        if (H != null) {
            for (int k = 0; k < nx; k++) {
                H[nv * nx + k] += dtdx_m[k] - dtdx_r[k];
            }
        }
    }

    private static void jointPrecTrop(JointRtk rtk, double[] rr, int baseline, int r,
                                      double[] azel, double[] dtdx, int nx) {
        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr, pos);

        double[] mapWet = new double[1];
        TroposphereModel.tropmapf(rtk.sol.time, pos, azel, mapWet);

        for (int k = 0; k < nx; k++) dtdx[k] = 0.0;

        int it = rtk.IT(baseline, r);
        if (it >= 0 && it < nx) {
            dtdx[it] = mapWet[0];
            if (rtk.opt.tropopt >= Constants.TROPOPT_ESTG && azel[1] > 0.0) {
                double cotz = 1.0 / Math.tan(azel[1]);
                double grad_n = mapWet[0] * cotz * Math.cos(azel[0]);
                double grad_e = mapWet[0] * cotz * Math.sin(azel[0]);
                if (it + 1 < nx) dtdx[it + 1] = grad_n * rtk.x[it];
                if (it + 2 < nx) dtdx[it + 2] = grad_e * rtk.x[it];
            }
        }
    }

    private static void ddcov(int[] nb, int b, double[] Ri, double[] Rj, int nv,
                              double[] R) {
        for (int i = 0; i < nv * nv; i++) R[i] = 0.0;

        int k = 0;
        for (int bi = 0; bi < b; bi++) {
            int nbi = nb[bi];
            for (int i = 0; i < nbi; i++) {
                for (int j = 0; j < nbi; j++) {
                    R[(k + i) * nv + (k + j)] = Ri[k + i] + (i == j ? Rj[k + i] : 0.0);
                }
            }
            k += nbi;
        }
    }

    static boolean validobs(int iu, int ir, int f, int nf, double[] y) {
        return y[f + iu * nf * 2] != 0.0 && y[f + ir * nf * 2] != 0.0;
    }

    static void initx(double[] x, double[] P, int nx, double xi, double var, int i) {
        x[i] = xi;
        for (int j = 0; j < nx; j++) {
            P[i * nx + j] = 0.0;
            P[j * nx + i] = 0.0;
        }
        P[i * nx + i] = var;
    }

    static double SQR(double x) {
        return x * x;
    }
}