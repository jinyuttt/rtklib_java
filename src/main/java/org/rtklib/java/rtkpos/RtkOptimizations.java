package org.rtklib.java.rtkpos;

import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import java.util.Arrays;

final class RtkOptimizations {

    private RtkOptimizations() {
    }

    static void computeSnrMedian(Rtk rtk, Obsd[] obs, int nu, int nr, int[] sat, int ns, int nf, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableSnrMedian) return;

        for (int f = 0; f < nf; f++) {
            double[] snrVals = new double[ns];
            int cnt = 0;
            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                double el = rtk.ssat[s].azel[1];
                if (el < cfg.snrMedianMinEl) continue;
                if (rtk.ssat[s].lock[f] < 0) continue;
                double lockTime = rtk.ssat[s].lock[f] * Math.abs(rtk.tt);
                if (lockTime < cfg.snrMedianMinLockTime && rtk.epoch > 1) continue;
                double snr = 0.0;
                for (int r = 0; r < nu; r++) {
                    if (obs[r].sat == sat[i] && obs[r].rcv == 1) {
                        snr = obs[r].SNR[f];
                        break;
                    }
                }
                if (snr <= 0.0) continue;
                snrVals[cnt++] = snr;
            }

            if (cnt >= cfg.snrMedianMinSatsForFallback) {
                Arrays.sort(snrVals, 0, cnt);
                double median;
                if (cnt % 2 == 1) {
                    median = snrVals[cnt / 2];
                } else {
                    median = (snrVals[cnt / 2 - 1] + snrVals[cnt / 2]) / 2.0;
                }

                int wSize = cfg.snrMedianWindowSize;
                int histIdx = rtk.snrMedianHistoryCount % wSize;
                rtk.snrMedianHistory[f][histIdx] = median;
                rtk.snrMedianHistoryCount++;

                int histCnt = Math.min(rtk.snrMedianHistoryCount, wSize);
                double[] histVals = new double[histCnt];
                for (int j = 0; j < histCnt; j++) {
                    histVals[j] = rtk.snrMedianHistory[f][j];
                }
                Arrays.sort(histVals);
                if (histCnt % 2 == 1) {
                    rtk.snrMedian[f] = histVals[histCnt / 2];
                } else {
                    rtk.snrMedian[f] = (histVals[histCnt / 2 - 1] + histVals[histCnt / 2]) / 2.0;
                }

                if (rtk.snrMedian[f] < cfg.snrMedianAbsMin) {
                    rtk.snrMedian[f] = cfg.snrMedianAbsMin;
                }
            } else {
                rtk.snrMedian[f] = f < cfg.snrMedianMinSatsForFallback
                        ? cfg.snrMedianFallbackPhaseRef
                        : cfg.snrMedianFallbackCodeRef;
            }
        }
    }

    static double varerrWithSnrMedian(double originalVar, int sat, int sys, double el,
                                       double snr_rover, double snr_base,
                                       int f, PrcOpt opt, Rtk rtk) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableSnrMedian) return originalVar;

        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int frq = f % nf;
        boolean code = f >= nf;

        double snrSat = (snr_rover + snr_base) / 2.0;
        if (snrSat < cfg.snrMedianMinSnr) {
            return cfg.snrMedianInvalidVar;
        }

        double snrMed = rtk.snrMedian[frq];
        if (snrMed <= 0.0) return originalVar;

        double k = code ? cfg.snrMedianKCode : cfg.snrMedianKPhase;
        double ratio = snrMed / snrSat;
        double snrFactor = Math.pow(ratio, k);

        double sigmaBase = Math.sqrt(originalVar / 2.0);
        double sigmaNew = sigmaBase * Math.sqrt(snrFactor);

        return 2.0 * sigmaNew * sigmaNew;
    }

    static void applyIggiii(Rtk rtk, double[] v, double[] H, double[] R,
                            int[] vflg, int nv, int nx, int[] sat, int ns,
                            Obsd[] obs, int[] iu, double[] azel, int nf) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableIggiii) return;
        if (nv <= 0) return;

        double[] Sdiag = new double[nv];
        double[] vNorm = new double[nv];
        double[] w = new double[nv];

        double[] HPHt_diag = computeHPHtDiag(H, rtk.P, nv, nx);
        for (int i = 0; i < nv; i++) {
            Sdiag[i] = R[i * nv + i] + HPHt_diag[i];
            if (Sdiag[i] <= 0.0) Sdiag[i] = 1e-10;
            vNorm[i] = v[i] / Math.sqrt(Sdiag[i]);
        }

        double[][] satW = new double[Constants.MAXSAT][nf * 2];
        for (int s = 0; s < Constants.MAXSAT; s++) {
            for (int j = 0; j < nf * 2; j++) {
                satW[s][j] = 1.0;
            }
        }

        for (int i = 0; i < nv; i++) {
            int sat2 = (vflg[i] >> 8) & 0xFF;
            int type = (vflg[i] >> 4) & 0xF;
            int frq = vflg[i] & 0xF;
            int targetSat = sat2 - 1;
            int freqTypeIdx = frq + (type >= 1 ? nf : 0);

            double absVn = Math.abs(vNorm[i]);

            double el = 0.0;
            for (int j = 0; j < ns; j++) {
                if (sat[j] == sat2) {
                    el = azel[1 + iu[j] * 2];
                    break;
                }
            }

            if (el < cfg.iggiiiLowElMask && absVn > cfg.iggiiiLowElNormThresh) {
                w[i] = cfg.iggiiiLowElW;
            } else if (absVn <= cfg.iggiiiK0) {
                w[i] = 1.0;
            } else if (absVn <= cfg.iggiiiK1) {
                w[i] = cfg.iggiiiK0 / absVn * Math.pow((cfg.iggiiiK1 - absVn) / (cfg.iggiiiK1 - cfg.iggiiiK0), 2);
            } else {
                w[i] = cfg.iggiiiMinW;
            }

            if (w[i] < cfg.iggiiiMinW) w[i] = cfg.iggiiiMinW;

            if (targetSat >= 0 && targetSat < Constants.MAXSAT && freqTypeIdx < nf * 2) {
                if (w[i] < satW[targetSat][freqTypeIdx]) {
                    satW[targetSat][freqTypeIdx] = w[i];
                }
            }
        }

        for (int i = 0; i < nv; i++) {
            int sat2 = (vflg[i] >> 8) & 0xFF;
            int type = (vflg[i] >> 4) & 0xF;
            int frq = vflg[i] & 0xF;
            int targetSat = sat2 - 1;
            int freqTypeIdx = frq + (type >= 1 ? nf : 0);

            double minSatW = 1.0;
            if (targetSat >= 0 && targetSat < Constants.MAXSAT) {
                for (int j = 0; j < nf * 2; j++) {
                    if (satW[targetSat][j] < minSatW) {
                        minSatW = satW[targetSat][j];
                    }
                }
            }

            if (minSatW < cfg.iggiiiMultiFreqW) {
                w[i] = Math.min(w[i], cfg.iggiiiMultiFreqW);
            }
        }

        for (int i = 0; i < nv; i++) {
            if (w[i] < 1.0) {
                for (int j = 0; j < nv; j++) {
                    if (R[i * nv + j] != 0.0) {
                        R[i * nv + j] /= w[i];
                    }
                    if (i != j && R[j * nv + i] != 0.0) {
                        R[j * nv + i] /= w[i];
                    }
                }
            }
        }
    }

    private static double[] computeHPHtDiag(double[] H, double[] P, int nv, int nx) {
        SimpleMatrix Hmat = MatrixUtil.createMatrix(H, nv, nx);
        SimpleMatrix Pmat = MatrixUtil.createMatrix(P, nx, nx);
        SimpleMatrix HPHt = Hmat.mult(Pmat).mult(Hmat.transpose());
        double[] diag = new double[nv];
        for (int i = 0; i < nv; i++) {
            diag[i] = HPHt.get(i, i);
        }
        return diag;
    }

    private static double[] computeHPHtDiagNative(double[] H, double[] P, int nv, int nx) {
        double[] diag = new double[nv];
        double[] PH = new double[nx * nv];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nv; j++) {
                double sum = 0.0;
                for (int k = 0; k < nx; k++) {
                    sum += P[i * nx + k] * H[j * nx + k];
                }
                PH[i * nv + j] = sum;
            }
        }
        for (int i = 0; i < nv; i++) {
            double sum = 0.0;
            for (int k = 0; k < nx; k++) {
                sum += H[i * nx + k] * PH[k * nv + i];
            }
            diag[i] = sum;
        }
        return diag;
    }

    static boolean isZeroVelocity(Rtk rtk) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableAdaptiveQ) return false;

        double speed = 0.0;
        if (rtk.opt.dynamics != 0 && rtk.nx >= 6) {
            speed = Math.sqrt(rtk.x[3] * rtk.x[3] + rtk.x[4] * rtk.x[4] + rtk.x[5] * rtk.x[5]);
        }

        if (speed >= cfg.zeroVelSpeedThresh) {
            rtk.consecutiveZeroVelEpochs = 0;
            return false;
        }

        double[] curPos = new double[3];
        for (int i = 0; i < 3; i++) curPos[i] = rtk.x[i] + rtk.rb[i];
        double posDiff = 0.0;
        if (rtk.prevPosForZeroVel[0] != 0.0 || rtk.prevPosForZeroVel[1] != 0.0) {
            for (int i = 0; i < 3; i++) {
                posDiff += (curPos[i] - rtk.prevPosForZeroVel[i]) * (curPos[i] - rtk.prevPosForZeroVel[i]);
            }
            posDiff = Math.sqrt(posDiff);
        }

        System.arraycopy(curPos, 0, rtk.prevPosForZeroVel, 0, 3);

        if (posDiff >= cfg.zeroVelPosDiffThresh) {
            rtk.consecutiveZeroVelEpochs = 0;
            return false;
        }

        rtk.consecutiveZeroVelEpochs++;
        return rtk.consecutiveZeroVelEpochs >= cfg.zeroVelConsecutiveEpochs;
    }

    static void computeQScale(Rtk rtk, int[] sat, int ns) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableAdaptiveQ) {
            rtk.qScale = 1.0;
            return;
        }

        double nsFactor = Math.min(ns / cfg.adaptiveQNsRef, 1.5);

        double[] dop = new double[4];
        double[] azelCopy = new double[ns * 2];
        for (int i = 0; i < ns; i++) {
            azelCopy[i * 2] = rtk.ssat[sat[i] - 1].azel[0];
            azelCopy[i * 2 + 1] = rtk.ssat[sat[i] - 1].azel[1];
        }
        RtklibCommon.dops(ns, azelCopy, rtk.opt.elmin, dop);
        double pdop = dop[1];
        double pdopFactor = cfg.adaptiveQPdopRef / Math.max(pdop, 1.0);

        double rawScale = nsFactor * pdopFactor;

        boolean zeroVel = isZeroVelocity(rtk);

        double scaleMin = zeroVel ? cfg.adaptiveQScaleMinZeroVel : cfg.adaptiveQScaleMinMoving;
        double scale = clamp(rawScale, scaleMin, cfg.adaptiveQScaleMax);

        double pTrace = 0.0;
        for (int i = 0; i < Math.min(9, rtk.nx); i++) {
            pTrace += rtk.P[i * rtk.nx + i];
        }
        if (pTrace > cfg.adaptiveQTraceThresh) {
            scale = 1.0;
        }

        rtk.qScale = scale;
    }

    static int buildParIndex(Rtk rtk, int[] sat, int ns, int nf,
                             double[] azel, int[] iu, double[] y) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableParRefReselect) return 0;

        rtk.parExcludedSatCount = 0;

        for (int i = 0; i < ns; i++) {
            for (int f = 0; f < nf; f++) {
                double el = azel[1 + iu[i] * 2];
                if (el < cfg.parElMask * Constants.D2R) {
                    boolean alreadyExcluded = false;
                    for (int e = 0; e < rtk.parExcludedSatCount; e++) {
                        if (rtk.parExcludedSats[e] == sat[i]) {
                            alreadyExcluded = true;
                            break;
                        }
                    }
                    if (!alreadyExcluded && rtk.parExcludedSatCount < Constants.MAXSAT) {
                        rtk.parExcludedSats[rtk.parExcludedSatCount++] = sat[i];
                    }
                }

                if ((rtk.ssat[sat[i] - 1].slip[f] & Constants.LLI_SLIP) != 0) {
                    boolean alreadyExcluded = false;
                    for (int e = 0; e < rtk.parExcludedSatCount; e++) {
                        if (rtk.parExcludedSats[e] == sat[i]) {
                            alreadyExcluded = true;
                            break;
                        }
                    }
                    if (!alreadyExcluded && rtk.parExcludedSatCount < Constants.MAXSAT) {
                        rtk.parExcludedSats[rtk.parExcludedSatCount++] = sat[i];
                    }
                }

                if (rtk.ssat[sat[i] - 1].rejc[f] >= 2) {
                    boolean alreadyExcluded = false;
                    for (int e = 0; e < rtk.parExcludedSatCount; e++) {
                        if (rtk.parExcludedSats[e] == sat[i]) {
                            alreadyExcluded = true;
                            break;
                        }
                    }
                    if (!alreadyExcluded && rtk.parExcludedSatCount < Constants.MAXSAT) {
                        rtk.parExcludedSats[rtk.parExcludedSatCount++] = sat[i];
                    }
                }
            }
        }

        if (rtk.parExcludedSatCount >= ns - 1) {
            rtk.parExcludedSatCount = 0;
            return 0;
        }

        return rtk.parExcludedSatCount;
    }

    static int ddidxPar(Rtk rtk, int[] ix, int gps, int glo, int sbs,
                        int[] sat, int ns, int nf, double[] azel, int[] iu, double[] y) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableParRefReselect) return 0;

        int nb = 0;
        PrcOpt opt = rtk.opt;
        int na = rtk.na;
        int gpsMode = gps >= 0 ? gps : opt.gpsmodear;
        int gloMode = glo >= 0 ? glo : opt.glomodear;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < Constants.NFREQ; j++) {
                rtk.ssat[i].fix[j] = 0;
            }
        }

        boolean anyRefReselect = false;

        for (int m = 0; m < 6; m++) {
            boolean nofix = (m == 0 && gpsMode == 0) || (m == 1 && gloMode == 0) || (m == 3 && opt.bdsmodear == 0);

            for (int f = 0, k = na; f < nf; f++, k += Constants.MAXSAT) {
                int prevRefSatId = rtk.parPrevRefSat[f];

                boolean prevRefExcluded = false;
                if (prevRefSatId >= 1) {
                    for (int e = 0; e < rtk.parExcludedSatCount; e++) {
                        if (rtk.parExcludedSats[e] == prevRefSatId) {
                            prevRefExcluded = true;
                            break;
                        }
                    }
                }

                if (prevRefExcluded) {
                    anyRefReselect = true;
                }

                int refI = -1;
                double refEl = -1.0;

                for (int i = k; i < k + Constants.MAXSAT; i++) {
                    int si = i - k;
                    if (rtk.x[i] == 0.0 || !RtkCore.testSys(rtk.ssat[si].sys, m) || rtk.ssat[si].vsat[f] == 0) {
                        continue;
                    }

                    boolean excluded = false;
                    for (int e = 0; e < rtk.parExcludedSatCount; e++) {
                        if (rtk.parExcludedSats[e] == si + 1) {
                            excluded = true;
                            break;
                        }
                    }
                    if (excluded) continue;

                    if (rtk.ssat[si].lock[f] >= 0 && (rtk.ssat[si].slip[f] & Constants.LLI_HALFC) == 0
                            && rtk.ssat[si].azel[1] >= opt.elmaskar && !nofix) {
                        if (rtk.ssat[si].azel[1] > refEl) {
                            refEl = rtk.ssat[si].azel[1];
                            refI = i;
                        }
                    }
                }

                if (refI < 0) {
                    for (int i = k; i < k + Constants.MAXSAT; i++) {
                        int si = i - k;
                        if (rtk.x[i] == 0.0 || !RtkCore.testSys(rtk.ssat[si].sys, m) || rtk.ssat[si].vsat[f] == 0) {
                            continue;
                        }
                        if (rtk.ssat[si].lock[f] >= 0 && (rtk.ssat[si].slip[f] & Constants.LLI_HALFC) == 0
                                && rtk.ssat[si].azel[1] >= opt.elmaskar && !nofix) {
                            refI = i;
                            break;
                        }
                    }
                }

                if (refI < 0) continue;
                rtk.ssat[refI - k].fix[f] = 2;
                rtk.parPrevRefSat[f] = (refI - k) + 1;

                int n = 0;
                for (int j = k; j < k + Constants.MAXSAT; j++) {
                    int sj = j - k;
                    if (refI == j || rtk.x[j] == 0.0 || !RtkCore.testSys(rtk.ssat[sj].sys, m) || rtk.ssat[sj].vsat[f] == 0) {
                        continue;
                    }
                    if (sbs == 0 && SatUtils.satsys(sj + 1, null) == Constants.SYS_SBS) continue;

                    boolean excluded = false;
                    for (int e = 0; e < rtk.parExcludedSatCount; e++) {
                        if (rtk.parExcludedSats[e] == sj + 1) {
                            excluded = true;
                            break;
                        }
                    }
                    if (excluded) {
                        rtk.ssat[sj].fix[f] = 1;
                        continue;
                    }

                    if (rtk.ssat[sj].lock[f] >= 0 && (rtk.ssat[sj].slip[f] & Constants.LLI_HALFC) == 0
                            && rtk.ssat[sj].azel[1] >= opt.elmaskar && !nofix) {
                        ix[nb * 2] = refI;
                        ix[nb * 2 + 1] = j;
                        rtk.ssat[sj].fix[f] = 2;
                        nb++;
                        n++;
                    } else {
                        rtk.ssat[sj].fix[f] = 1;
                    }
                }
                if (n == 0) {
                    rtk.ssat[refI - k].fix[f] = 1;
                }
            }
        }

        if (anyRefReselect) {
            rtk.parConsecutiveReselectCount++;
            if (rtk.parConsecutiveReselectCount > cfg.parMaxConsecutiveReselect) {
                rtk.parExcludedSatCount = 0;
                rtk.parConsecutiveReselectCount = 0;
                return ddidxFallback(rtk, ix, gps, glo, sbs);
            }
        } else {
            rtk.parConsecutiveReselectCount = 0;
        }

        return nb;
    }

    private static int ddidxFallback(Rtk rtk, int[] ix, int gps, int glo, int sbs) {
        int nb = 0;
        PrcOpt opt = rtk.opt;
        int na = rtk.na;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int gpsMode = gps >= 0 ? gps : opt.gpsmodear;
        int gloMode = glo >= 0 ? glo : opt.glomodear;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < Constants.NFREQ; j++) {
                rtk.ssat[i].fix[j] = 0;
            }
        }

        for (int m = 0; m < 6; m++) {
            boolean nofix = (m == 0 && gpsMode == 0) || (m == 1 && gloMode == 0) || (m == 3 && opt.bdsmodear == 0);

            for (int f = 0, k = na; f < nf; f++, k += Constants.MAXSAT) {
                int refI = -1;
                for (int i = k; i < k + Constants.MAXSAT; i++) {
                    int si = i - k;
                    if (rtk.x[i] == 0.0 || !RtkCore.testSys(rtk.ssat[si].sys, m) || rtk.ssat[si].vsat[f] == 0) {
                        continue;
                    }
                    if (rtk.ssat[si].lock[f] >= 0 && (rtk.ssat[si].slip[f] & Constants.LLI_HALFC) == 0
                            && rtk.ssat[si].azel[1] >= opt.elmaskar && !nofix) {
                        rtk.ssat[si].fix[f] = 2;
                        refI = i;
                        break;
                    } else {
                        rtk.ssat[si].fix[f] = 1;
                    }
                }
                if (refI < 0 || rtk.ssat[refI - k].fix[f] != 2) continue;

                int n = 0;
                for (int j = k; j < k + Constants.MAXSAT; j++) {
                    int sj = j - k;
                    if (refI == j || rtk.x[j] == 0.0 || !RtkCore.testSys(rtk.ssat[sj].sys, m) || rtk.ssat[sj].vsat[f] == 0) {
                        continue;
                    }
                    if (sbs == 0 && SatUtils.satsys(sj + 1, null) == Constants.SYS_SBS) continue;
                    if (rtk.ssat[sj].lock[f] >= 0 && (rtk.ssat[sj].slip[f] & Constants.LLI_HALFC) == 0
                            && rtk.ssat[sj].azel[1] >= opt.elmaskar && !nofix) {
                        ix[nb * 2] = refI;
                        ix[nb * 2 + 1] = j;
                        rtk.ssat[sj].fix[f] = 2;
                        nb++;
                        n++;
                    } else {
                        rtk.ssat[sj].fix[f] = 1;
                    }
                }
                if (n == 0) {
                    rtk.ssat[refI - k].fix[f] = 1;
                }
            }
        }
        return nb;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}