package org.rtklib.java.rtkpos;

import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.ejml.simple.SimpleMatrix;

public final class RtkOptimizations {
    private RtkOptimizations() {
    }

    public static void computeSnrMedian(Rtk rtk, Obsd[] obs, int nu, int nr,
                                        int[] sat, int ns, int nf, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableSnrMedian) {
            for (int f = 0; f < nf; f++) {
                rtk.snrMedian[f] = Double.NEGATIVE_INFINITY;
            }
            return;
        }

        for (int f = 0; f < nf; f++) {
            double[] validSnrs = new double[ns];
            int validCount = 0;
            for (int i = 0; i < ns; i++) {
                int s = sat[i] - 1;
                double el = rtk.ssat[s].azel[1];
                if (el < cfg.snrMedianMinEl) continue;
                double lockTime = rtk.ssat[s].lock[f] >= 0 ? rtk.ssat[s].lock[f] : 0;
                if (lockTime < cfg.snrMedianMinLockTime) continue;
                double snr = Math.max(rtk.ssat[s].snrRover[f], rtk.ssat[s].snrBase[f]);
                if (snr < cfg.snrMedianAbsMin) continue;
                validSnrs[validCount++] = snr;
            }
            if (validCount >= cfg.snrMedianMinSatsForFallback) {
                java.util.Arrays.sort(validSnrs, 0, validCount);
                rtk.snrMedian[f] = validSnrs[validCount / 2];
            } else {
                rtk.snrMedian[f] = cfg.snrMedianFallbackPhaseRef;
            }
        }
    }

    static void computeQScale(Rtk rtk, int[] sat, int ns) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableAdaptiveQ) {
            rtk.qScale = 1.0;
            return;
        }

        double[] curPos = new double[3];
        for (int i = 0; i < 3; i++) {
            curPos[i] = rtk.x[i] + rtk.rb[i];
        }

        if (rtk.xOld[0] != 0.0 || rtk.xOld[1] != 0.0 || rtk.xOld[2] != 0.0) {
            double dx = curPos[0] - rtk.xOld[0];
            double dy = curPos[1] - rtk.xOld[1];
            double dz = curPos[2] - rtk.xOld[2];
            double posInc = Math.sqrt(dx * dx + dy * dy + dz * dz);

            int winSize = cfg.adaptiveQWinSize;
            if (winSize > rtk.posWin.length) {
                winSize = rtk.posWin.length;
            }

            rtk.posWin[rtk.winIdx] = posInc;
            rtk.winIdx = (rtk.winIdx + 1) % winSize;
            if (rtk.winCnt < winSize) {
                rtk.winCnt++;
            }
        }

        System.arraycopy(curPos, 0, rtk.xOld, 0, 3);

        double sigmaPos;
        if (rtk.winCnt < 2) {
            sigmaPos = 0.0;
        } else {
            int winSize = Math.min(cfg.adaptiveQWinSize, rtk.posWin.length);
            double sum = 0.0;
            double sumSq = 0.0;
            int validCount = 0;
            int startIdx = (rtk.winCnt < winSize) ? 0 : (rtk.winIdx + winSize - rtk.winCnt) % winSize;
            for (int i = 0; i < rtk.winCnt; i++) {
                int idx = (startIdx + i) % winSize;
                double val = rtk.posWin[idx];
                sum += val;
                sumSq += val * val;
                validCount++;
            }
            double mean = sum / validCount;
            double variance = sumSq / validCount - mean * mean;
            sigmaPos = Math.sqrt(Math.max(variance, 0.0));
        }

        double scale;
        if (sigmaPos <= cfg.adaptiveQStaticThresh) {
            scale = cfg.adaptiveQScaleMinStatic;
        } else if (sigmaPos >= cfg.adaptiveQDynamicThresh) {
            scale = cfg.adaptiveQScaleMaxDynamic;
        } else {
            double t = (sigmaPos - cfg.adaptiveQStaticThresh) /
                       (cfg.adaptiveQDynamicThresh - cfg.adaptiveQStaticThresh);
            double sigmoid = 1.0 / (1.0 + Math.exp(-10.0 * (t - 0.5)));
            scale = cfg.adaptiveQScaleMinStatic +
                    sigmoid * (cfg.adaptiveQScaleMaxDynamic - cfg.adaptiveQScaleMinStatic);
        }

        double nsFactor = Math.min(ns / cfg.adaptiveQNsRef, 1.5);

        double[] dop = new double[4];
        double[] azelCopy = new double[ns * 2];
        for (int i = 0; i < ns; i++) {
            azelCopy[i * 2] = rtk.ssat[sat[i] - 1].azel[0];
            azelCopy[i * 2 + 1] = rtk.ssat[sat[i] - 1].azel[1];
        }
        org.rtklib.java.common.RtklibCommon.dops(ns, azelCopy, rtk.opt.elmin, dop);
        double pdop = dop[1];
        double pdopFactor = Math.min(cfg.adaptiveQPdopRef / Math.max(pdop, 1.0), 2.0);

        double rawScale = scale * nsFactor * pdopFactor;

        boolean zeroVel = isZeroVelocity(rtk);
        double scaleMin = zeroVel ? cfg.adaptiveQScaleMinZeroVel : cfg.adaptiveQScaleMinMoving;
        double scaleMax = cfg.adaptiveQScaleMax;
        double finalScale = clamp(rawScale, scaleMin, scaleMax);

        double pTrace = 0.0;
        for (int i = 0; i < Math.min(9, rtk.nx); i++) {
            pTrace += rtk.P[i * rtk.nx + i];
        }
        if (pTrace > cfg.adaptiveQTraceThresh) {
            finalScale = 1.0;
        }

        rtk.qScale = finalScale;
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

    public static void applyIggiii(Rtk rtk, double[] v, double[] H, double[] R,
                                   int[] vflg, int nv, int nx, int[] sat, int ns,
                                   Obsd[] obs, int[] iu, double[] azel, int nf) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableIggiii) return;

        double[] diag = computeHPHtDiagNative(H, rtk.P, nv, nx);

        double[] w = new double[nv];
        for (int i = 0; i < nv; i++) {
            w[i] = 1.0;
        }

        for (int i = 0; i < nv; i++) {
            double sigma = Math.sqrt(Math.max(R[i * nv + i], 1e-30));
            double predVar = Math.max(diag[i], 1e-30);
            double innovation = Math.abs(v[i]) / Math.sqrt(predVar + sigma * sigma);

            double wk;
            if (innovation <= cfg.iggiiiK0) {
                wk = 1.0;
            } else if (innovation <= cfg.iggiiiK1) {
                wk = cfg.iggiiiK0 / innovation;
            } else {
                wk = cfg.iggiiiMinW;
            }

            int sat2 = (vflg[i] >> 8) & 0xFF;
            double el = 0.0;
            if (sat2 > 0 && sat2 <= Constants.MAXSAT) {
                el = rtk.ssat[sat2 - 1].azel[1];
            }
            if (el < cfg.iggiiiLowElMask && innovation > cfg.iggiiiLowElNormThresh) {
                wk = Math.min(wk, cfg.iggiiiLowElW);
            }

            w[i] = wk;
        }

        double[][] satW = new double[Constants.MAXSAT][nf * 2];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int j = 0; j < nf * 2; j++) {
                satW[i][j] = 1.0;
            }
        }
        for (int i = 0; i < nv; i++) {
            int sat2 = (vflg[i] >> 8) & 0xFF;
            int type = (vflg[i] >> 4) & 0xF;
            int frq = vflg[i] & 0xF;
            int targetSat = sat2 - 1;
            int freqTypeIdx = frq + (type >= 1 ? nf : 0);

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

    static int buildParIndex(Rtk rtk, int[] sat, int ns, int nf,
                             int[] ix, int gps, int glo, int sbs) {
        RtkConfig cfg = rtk.rtkConfig;
        PrcOpt opt = rtk.opt;
        int na = rtk.na;
        int nb = 0;
        boolean anyRefReselect = false;

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
                    if (sbs == 0 && SatUtils.satsys(si + 1, null) == Constants.SYS_SBS) continue;
                    if (rtk.ssat[si].lock[f] >= 0 && (rtk.ssat[si].slip[f] & Constants.LLI_HALFC) == 0
                            && rtk.ssat[si].azel[1] >= opt.elmaskar && !nofix) {
                        refI = i;
                        break;
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