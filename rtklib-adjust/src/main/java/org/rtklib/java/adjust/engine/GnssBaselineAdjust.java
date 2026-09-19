package org.rtklib.java.adjust.engine;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.decomposition.chol.CholeskyDecompositionInner_DDRM;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.BaselineEpoch;
import org.rtklib.java.adjust.model.ReliabilityGrade;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;

public class GnssBaselineAdjust {

    private static final double VERIFIED_THRESHOLD = 0.02;
    private static final double CROSS_CHECKED_THRESHOLD = 0.05;
    private static final double SUSPECT_THRESHOLD = 0.05;
    private static final double PL_K_VERIFIED = 2.0;
    private static final double PL_K_CROSS_CHECKED = 3.0;
    private static final double PL_K_UNVERIFIED = 6.0;
    private static final double PL_K_SUSPECT = 10.0;
    private static final double CROSS_CHECKED_COV_INFLATE = 4.0;

    public static AdjustResult adjust(BaselineEpoch epoch) {
        return adjust(epoch, false);
    }

    public static AdjustResult adjust(BaselineEpoch epoch, boolean qualityWeight) {
        int k = epoch.count;
        if (k == 0) {
            return AdjustResult.failure(epoch.epochTag, 0, "无有效FIX基线");
        }

        if (k == 1) {
            return adjustSingle(epoch, qualityWeight);
        }

        CrossValidation cv = crossValidateEarly(epoch);

        return switch (cv.grade) {
            case VERIFIED -> adjustVerified(epoch, qualityWeight, cv);
            case CROSS_CHECKED -> adjustCrossChecked(epoch, qualityWeight, cv);
            case SUSPECT -> adjustSuspect(epoch, qualityWeight, cv);
            default -> adjustSingle(epoch, qualityWeight);
        };
    }

    private static AdjustResult adjustSingle(BaselineEpoch epoch, boolean qualityWeight) {
        int k = epoch.count;
        if (k == 0) {
            return AdjustResult.failure(epoch.epochTag, 0, "无有效FIX基线");
        }

        int m = 3 * k;
        int n = 3;
        int df = m - n;

        try {
            SimpleMatrix l = CovAssembler.assembleObservationVector(epoch);
            SimpleMatrix H = CovAssembler.assembleDesignMatrix(epoch);
            SimpleMatrix R = CovAssembler.assembleGlobalR(epoch, qualityWeight);

            if (!isPositiveDefinite(R)) {
                return AdjustResult.failure(epoch.epochTag, k,
                        "全局协方差矩阵R非正定，det=" + R.determinant());
            }

            SimpleMatrix RInv = R.invert();
            SimpleMatrix Ht = H.transpose();
            SimpleMatrix N = Ht.mult(RInv).mult(H);

            if (!isPositiveDefinite(N)) {
                return AdjustResult.failure(epoch.epochTag, k,
                        "法方程系数矩阵N非正定，det=" + N.determinant());
            }

            SimpleMatrix NInv = N.invert();
            SimpleMatrix u = Ht.mult(RInv).mult(l);
            SimpleMatrix dxMat = NInv.mult(u);

            double[] dx = new double[n];
            for (int i = 0; i < n; i++) dx[i] = dxMat.get(i, 0);

            double[] p01Xyz = new double[]{dx[0], dx[1], dx[2]};

            double sigma0;
            double[] baardaT;
            SimpleMatrix Qv;
            double[][] Dx;

            if (df == 0) {
                sigma0 = Double.NaN;
                baardaT = null;
                Qv = null;
                Dx = simpleMatrixTo2d(NInv);
            } else {
                SimpleMatrix vMat = H.mult(dxMat).minus(l);
                double[] v = new double[m];
                for (int i = 0; i < m; i++) v[i] = vMat.get(i, 0);

                double vRv = vMat.transpose().mult(RInv).mult(vMat).get(0, 0);
                double sigma0Sq = vRv / df;
                sigma0 = Math.sqrt(Math.max(sigma0Sq, 0.0));

                Qv = R.minus(H.mult(NInv).mult(Ht));
                baardaT = new double[m];
                for (int i = 0; i < m; i++) {
                    double qvii = Qv.get(i, i);
                    if (qvii > 0 && sigma0 > 0) {
                        baardaT[i] = Math.abs(v[i]) / (sigma0 * Math.sqrt(qvii));
                    } else {
                        baardaT[i] = 0.0;
                    }
                }
                Dx = simpleMatrixTo2d(NInv.scale(sigma0Sq));
            }

            double pl = computeProtectionLevel(Dx, PL_K_UNVERIFIED);

            return AdjustResult.success(epoch.epochTag, dx, Dx, null,
                    sigma0, df, Qv, baardaT, p01Xyz, k,
                    ReliabilityGrade.UNVERIFIED, 0.0, pl, null);

        } catch (RuntimeException e) {
            return AdjustResult.failure(epoch.epochTag, k, "平差计算异常: " + e.getMessage());
        }
    }

    private static AdjustResult adjustVerified(BaselineEpoch epoch, boolean qualityWeight,
                                                CrossValidation cv) {
        return adjustWithGrade(epoch, qualityWeight, cv, 1.0);
    }

    private static AdjustResult adjustCrossChecked(BaselineEpoch epoch, boolean qualityWeight,
                                                     CrossValidation cv) {
        return adjustWithGrade(epoch, qualityWeight, cv, CROSS_CHECKED_COV_INFLATE);
    }

    private static AdjustResult adjustSuspect(BaselineEpoch epoch, boolean qualityWeight,
                                                CrossValidation cv) {
        if (cv.suspectBaseId != null && epoch.count >= 2) {
            BaselineEpoch.BaselineEntry[] filtered =
                    new BaselineEpoch.BaselineEntry[epoch.count - 1];
            int n = 0;
            for (BaselineEpoch.BaselineEntry b : epoch.baselines) {
                if (!b.baseId.equals(cv.suspectBaseId)) {
                    filtered[n++] = b;
                }
            }
            if (n > 0) {
                BaselineEpoch cleanEpoch = new BaselineEpoch(epoch.epochTag,
                        java.util.Arrays.copyOf(filtered, n));

                AdjustResult cleanResult = adjustSingle(cleanEpoch, qualityWeight);

                if (cleanResult.success) {
                    double pl = Math.max(cleanResult.protectionLevel, cv.maxDist);
                    return AdjustResult.success(cleanResult.epochTag, cleanResult.dx, cleanResult.Dx,
                            cleanResult.v, cleanResult.sigma0, cleanResult.dof,
                            cleanResult.Qv, cleanResult.baardaT, cleanResult.p01Xyz,
                            cleanResult.usedBaselineCount,
                            ReliabilityGrade.SUSPECT, cv.maxDist, pl, cv.suspectBaseId);
                }
            }
        }

        return adjustWithGrade(epoch, qualityWeight, cv, CROSS_CHECKED_COV_INFLATE * 4);
    }

    private static AdjustResult adjustWithGrade(BaselineEpoch epoch, boolean qualityWeight,
                                                  CrossValidation cv, double covInflateFactor) {
        int k = epoch.count;
        int m = 3 * k;
        int n = 3;
        int df = m - n;

        try {
            SimpleMatrix l = CovAssembler.assembleObservationVector(epoch);
            SimpleMatrix H = CovAssembler.assembleDesignMatrix(epoch);
            SimpleMatrix R = CovAssembler.assembleGlobalR(epoch, qualityWeight);

            if (covInflateFactor > 1.0 && cv.suspectBaseId != null && k >= 2) {
                int suspectIdx = -1;
                for (int i = 0; i < k; i++) {
                    if (epoch.baselines[i].baseId.equals(cv.suspectBaseId)) {
                        suspectIdx = i;
                        break;
                    }
                }
                if (suspectIdx >= 0) {
                    int base = 3 * suspectIdx;
                    for (int i = 0; i < 3; i++) {
                        for (int j = 0; j < 3; j++) {
                            R.set(base + i, base + j, R.get(base + i, base + j) * covInflateFactor);
                        }
                    }
                }
            } else if (covInflateFactor > 1.0 && k >= 2) {
                int weakIdx = findWeakestBaseline(epoch);
                if (weakIdx >= 0) {
                    int base = 3 * weakIdx;
                    for (int i = 0; i < 3; i++) {
                        for (int j = 0; j < 3; j++) {
                            R.set(base + i, base + j, R.get(base + i, base + j) * covInflateFactor);
                        }
                    }
                }
            }

            if (!isPositiveDefinite(R)) {
                return AdjustResult.failure(epoch.epochTag, k,
                        "全局协方差矩阵R非正定，det=" + R.determinant());
            }

            SimpleMatrix RInv = R.invert();
            SimpleMatrix Ht = H.transpose();
            SimpleMatrix N = Ht.mult(RInv).mult(H);

            if (!isPositiveDefinite(N)) {
                return AdjustResult.failure(epoch.epochTag, k,
                        "法方程系数矩阵N非正定，det=" + N.determinant());
            }

            SimpleMatrix NInv = N.invert();
            SimpleMatrix u = Ht.mult(RInv).mult(l);
            SimpleMatrix dxMat = NInv.mult(u);

            SimpleMatrix vMat = H.mult(dxMat).minus(l);

            double[] dx = new double[n];
            for (int i = 0; i < n; i++) dx[i] = dxMat.get(i, 0);

            double[] v = new double[m];
            for (int i = 0; i < m; i++) v[i] = vMat.get(i, 0);

            double[] p01Xyz = new double[]{dx[0], dx[1], dx[2]};

            double sigma0;
            double[] baardaT;
            SimpleMatrix Qv;
            double[][] Dx;

            if (df == 0) {
                sigma0 = Double.NaN;
                baardaT = null;
                Qv = null;
                Dx = simpleMatrixTo2d(NInv);
            } else {
                double vRv = vMat.transpose().mult(RInv).mult(vMat).get(0, 0);
                double sigma0Sq = vRv / df;
                sigma0 = Math.sqrt(Math.max(sigma0Sq, 0.0));

                Qv = R.minus(H.mult(NInv).mult(Ht));

                baardaT = new double[m];
                for (int i = 0; i < m; i++) {
                    double qvii = Qv.get(i, i);
                    if (qvii > 0 && sigma0 > 0) {
                        baardaT[i] = Math.abs(v[i]) / (sigma0 * Math.sqrt(qvii));
                    } else {
                        baardaT[i] = 0.0;
                    }
                }

                SimpleMatrix DxMat = NInv.scale(sigma0Sq);
                Dx = simpleMatrixTo2d(DxMat);
            }

            double plK;
            switch (cv.grade) {
                case VERIFIED -> plK = PL_K_VERIFIED;
                case CROSS_CHECKED -> plK = PL_K_CROSS_CHECKED;
                case UNVERIFIED -> plK = PL_K_UNVERIFIED;
                case SUSPECT -> plK = PL_K_SUSPECT;
                default -> plK = PL_K_UNVERIFIED;
            }

            double pl = computeProtectionLevel(Dx, plK);
            if (cv.grade == ReliabilityGrade.SUSPECT) {
                pl = Math.max(pl, cv.maxDist);
            }

            return AdjustResult.success(epoch.epochTag, dx, Dx, v,
                    sigma0, df, Qv, baardaT, p01Xyz, k,
                    cv.grade, cv.maxDist, pl, cv.suspectBaseId);

        } catch (RuntimeException e) {
            return AdjustResult.failure(epoch.epochTag, k, "平差计算异常: " + e.getMessage());
        }
    }

    private static CrossValidation crossValidateEarly(BaselineEpoch epoch) {
        int k = epoch.count;

        double maxDist = 0.0;
        int worstI = -1, worstJ = -1;
        for (int i = 0; i < k; i++) {
            Position pi = epoch.baselines[i].solData.getPosition(CoordType.ECEF);
            if (pi == null) continue;
            for (int j = i + 1; j < k; j++) {
                Position pj = epoch.baselines[j].solData.getPosition(CoordType.ECEF);
                if (pj == null) continue;
                double dist = ecefDistance(pi, pj);
                if (dist > maxDist) {
                    maxDist = dist;
                    worstI = i;
                    worstJ = j;
                }
            }
        }

        ReliabilityGrade grade;
        String suspectBaseId = null;

        if (maxDist < VERIFIED_THRESHOLD) {
            grade = ReliabilityGrade.VERIFIED;
        } else if (maxDist < CROSS_CHECKED_THRESHOLD) {
            grade = ReliabilityGrade.CROSS_CHECKED;
        } else if (maxDist < SUSPECT_THRESHOLD) {
            grade = ReliabilityGrade.CROSS_CHECKED;
        } else {
            grade = ReliabilityGrade.SUSPECT;
            suspectBaseId = identifySuspect(epoch, worstI, worstJ);
        }

        return new CrossValidation(grade, maxDist, 0.0, suspectBaseId);
    }

    private static String identifySuspect(BaselineEpoch epoch, int i, int j) {
        double ratioI = epoch.baselines[i].solData.ratio;
        double ratioJ = epoch.baselines[j].solData.ratio;
        int satI = epoch.baselines[i].solData.numSat;
        int satJ = epoch.baselines[j].solData.numSat;

        double scoreI = ratioI * satI;
        double scoreJ = ratioJ * satJ;

        if (scoreI < scoreJ) {
            return epoch.baselines[i].baseId;
        } else {
            return epoch.baselines[j].baseId;
        }
    }

    private static int findWeakestBaseline(BaselineEpoch epoch) {
        int weakIdx = 0;
        double weakScore = Double.MAX_VALUE;
        for (int i = 0; i < epoch.count; i++) {
            double score = epoch.baselines[i].solData.ratio * epoch.baselines[i].solData.numSat;
            if (score < weakScore) {
                weakScore = score;
                weakIdx = i;
            }
        }
        return weakIdx;
    }

    private static double computeProtectionLevel(double[][] Dx, double k) {
        if (Dx == null) return Double.NaN;
        double maxSigma = 0;
        for (int i = 0; i < 3; i++) {
            if (Dx[i][i] > 0) {
                maxSigma = Math.max(maxSigma, Math.sqrt(Dx[i][i]));
            }
        }
        return k * maxSigma;
    }

    private static double ecefDistance(Position a, Position b) {
        double dx = a.v1 - b.v1;
        double dy = a.v2 - b.v2;
        double dz = a.v3 - b.v3;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isPositiveDefinite(SimpleMatrix mat) {
        DMatrixRMaj drm = mat.getDDRM().copy();
        CholeskyDecompositionInner_DDRM chol = new CholeskyDecompositionInner_DDRM(true);
        return chol.decompose(drm);
    }

    private static double[][] simpleMatrixTo2d(SimpleMatrix sm) {
        double[][] arr = new double[sm.numRows()][sm.numCols()];
        for (int i = 0; i < sm.numRows(); i++) {
            for (int j = 0; j < sm.numCols(); j++) {
                arr[i][j] = sm.get(i, j);
            }
        }
        return arr;
    }

    private record CrossValidation(ReliabilityGrade grade, double maxDist,
                                    double protectionLevel, String suspectBaseId) {}
}