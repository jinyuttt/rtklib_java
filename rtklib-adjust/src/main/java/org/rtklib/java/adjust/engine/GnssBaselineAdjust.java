package org.rtklib.java.adjust.engine;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.decomposition.chol.CholeskyDecompositionInner_DDRM;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.BaselineEpoch;
import org.rtklib.java.adjust.model.MultiBaselineConfig;
import org.rtklib.java.adjust.model.ReliabilityGrade;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.SolutionStatus;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [JAVA-ONLY 原创] 单历元基线平差：多基线交叉验证、协方差膨胀、可靠性分级与保护级计算。
 *
 * <p><b>归属声明</b>：本项目原创实现，未移植任何外部开源项目代码。</p>
 */
public class GnssBaselineAdjust {

    /**
     * 交叉验证与保护级策略。
     *
     * <p>所有阈值与系数均来自 {@link MultiBaselineConfig.CrossValidationConfig}，
     * 不再硬编码，使配置项真正生效。</p>
     *
     * @param verifiedThreshold        基线间较差低于此值判为一致 (m)
     * @param crossCheckedThreshold    低于此值判为可交叉验证 (m)
     * @param suspectThreshold         达到此值判为可疑 (m)
     * @param plKVerified              一致时的保护级系数
     * @param plKCrossChecked          可交叉验证时的保护级系数
     * @param plKUnverified            未验证时的保护级系数；
     *                                 {@code SINGLE_VERIFIED}（1 FIX + FLOAT 佐证）同样取该系数
     *                                 —— FLOAT 佐证不足以降低保护级，二者共享同一保守取值
     * @param plKSuspect               可疑时的保护级系数
     * @param crossCheckedCovInflate   可交叉验证档的协方差膨胀倍数
     * @param floatAgreementThreshold  FLOAT 与 FIX 位置较差的一致性判定阈值 (m)
     */
    public record Policy(
            double verifiedThreshold,
            double crossCheckedThreshold,
            double suspectThreshold,
            double plKVerified,
            double plKCrossChecked,
            double plKUnverified,
            double plKSuspect,
            double crossCheckedCovInflate,
            double floatAgreementThreshold
    ) {
        public static Policy defaults() {
            return new Policy(0.02, 0.05, 0.05, 2.0, 3.0, 6.0, 10.0, 4.0, 0.20);
        }

        public static Policy fromConfig(MultiBaselineConfig config) {
            if (config == null || config.crossValidation() == null) return defaults();
            MultiBaselineConfig.CrossValidationConfig c = config.crossValidation();
            double floatThreshold = config.solutionAdmission() != null
                    ? config.solutionAdmission().floatAgreementThreshold()
                    : 0.20;
            return new Policy(c.verifiedThreshold(), c.crossCheckedThreshold(),
                    c.suspectThreshold(), c.plKVerified(), c.plKCrossChecked(),
                    c.plKUnverified(), c.plKSuspect(), c.crossCheckedCovInflate(),
                    floatThreshold);
        }

        public double plK(ReliabilityGrade grade) {
            return switch (grade) {
                case VERIFIED -> plKVerified;
                case CROSS_CHECKED -> plKCrossChecked;
                case SUSPECT -> plKSuspect;
                case SINGLE_VERIFIED -> plKUnverified;
                case UNVERIFIED -> plKUnverified;
            };
        }
    }

    public static AdjustResult adjust(BaselineEpoch epoch) {
        return adjust(epoch, false, Policy.defaults());
    }

    public static AdjustResult adjust(BaselineEpoch epoch, boolean qualityWeight) {
        return adjust(epoch, qualityWeight, Policy.defaults());
    }

    public static AdjustResult adjust(BaselineEpoch epoch, boolean qualityWeight, Policy policy) {
        Policy p = policy != null ? policy : Policy.defaults();
        int k = epoch.count;
        if (k == 0) {
            return AdjustResult.failure(epoch.epochTag, 0, "无有效FIX基线");
        }

        if (epoch.floatCount > 0) {
            return adjustWithFloat(epoch, qualityWeight, p);
        }

        if (k == 1) {
            return adjustSingle(epoch, qualityWeight, p);
        }

        CrossValidation cv = crossValidateEarly(epoch, p);

        return switch (cv.grade) {
            case VERIFIED, CROSS_CHECKED -> adjustWithGrade(epoch, qualityWeight, cv, p);
            case SUSPECT -> adjustSuspect(epoch, qualityWeight, cv, p);
            default -> adjustSingle(epoch, qualityWeight, p);
        };
    }

    /**
     * 平差，并附带"准入阶段被拒绝的 FLOAT 基线"。
     *
     * <p>被拒绝的 FLOAT 已不在 {@code epoch} 内，平差本身看不到它们，
     * 因此 FIX/FLOAT 较差无法由 {@link #adjustWithFloat} 产生。若不在结果上
     * 显式携带，粗差 FLOAT 会被<b>静默丢弃</b>——比"报 0.0 较差"更糟。
     * 本重载把该信息透传到 {@link AdjustResult#rejectedFloats}。</p>
     *
     * @param rejected 准入阶段被拒绝的 FLOAT；null 或空表示无拒绝
     */
    public static AdjustResult adjust(BaselineEpoch epoch, boolean qualityWeight,
                                      Policy policy, List<AdjustResult.RejectedFloat> rejected) {
        AdjustResult r = adjust(epoch, qualityWeight, policy);
        return r == null ? null : r.withRejectedFloats(rejected);
    }

    private static AdjustResult adjustSingle(BaselineEpoch epoch, boolean qualityWeight, Policy p) {
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
            double[] v = null;

            if (df == 0) {
                sigma0 = Double.NaN;
                baardaT = null;
                Qv = null;
                Dx = simpleMatrixTo2d(NInv);
            } else {
                SimpleMatrix vMat = H.mult(dxMat).minus(l);
                v = new double[m];
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

            double pl = computeProtectionLevel(Dx, p.plKUnverified());

            return AdjustResult.success(epoch.epochTag, dx, Dx, v,
                    sigma0, df, Qv, baardaT, p01Xyz, k,
                    ReliabilityGrade.UNVERIFIED, Double.NaN, pl, null);

        } catch (RuntimeException e) {
            return AdjustResult.failure(epoch.epochTag, k, "平差计算异常: " + e.getMessage());
        }
    }

    /**
     * 含 FLOAT 基线历元的平差。
     *
     * <p>FLOAT 基线的协方差已在装配阶段按 {@code floatCovInflate} 膨胀，
     * 因此在 WLS 中只占极小权重，融合位置基本由 FIX 决定，不会把坐标明显拉偏。</p>
     *
     * <p>等级判定：</p>
     * <ul>
     *   <li>恰好 1 条 FIX + FLOAT，且 FLOAT 与 FIX 位置较差 ≤ {@code floatAgreementThreshold}
     *       → {@link ReliabilityGrade#SINGLE_VERIFIED}（FLOAT 作佐证）；</li>
     *   <li>其余含 FLOAT 的情形 → {@link ReliabilityGrade#UNVERIFIED}：
     *       FLOAT 只能补位，不能自证。</li>
     * </ul>
     *
     * <p>这里不做 FIX/FLOAT 之间的交叉验证：两者精度量级差一个数量级，
     * 用同一阈值必然判 SUSPECT，反而会错误剔除真 FIX。</p>
     */
    private static AdjustResult adjustWithFloat(BaselineEpoch epoch, boolean qualityWeight, Policy p) {
        AdjustResult base = adjustSingle(epoch, qualityWeight, p);
        if (!base.success) return base;

        ReliabilityGrade grade = ReliabilityGrade.UNVERIFIED;
        double maxDist = Double.NaN;

        if (epoch.fixCount == 1 && epoch.floatCount >= 1) {
            maxDist = maxDistanceToFix(epoch);
            if (!Double.isNaN(maxDist) && maxDist <= p.floatAgreementThreshold()) {
                grade = ReliabilityGrade.SINGLE_VERIFIED;
            }
        }

        double pl = computeProtectionLevel(base.Dx, p.plK(grade));
        return AdjustResult.success(epoch.epochTag, base.dx, base.Dx, base.v,
                base.sigma0, base.dof, base.Qv, base.baardaT, base.p01Xyz,
                base.usedBaselineCount, grade, maxDist, pl, null);
    }

    /**
     * 每条 FLOAT 基线相对<b>唯一</b> FIX 基线的位置较差 (m)，按 {@code baseId} 索引。
     *
     * <p>这是 FLOAT 与 FIX 位置一致性的<b>唯一</b>口径：准入判定
     * （{@code MultiBaselineAdjust.selectAdmitted}）与事后判级
     * （{@link #adjustWithFloat}）都调用本方法，避免同一口径出现两份实现。</p>
     *
     * <p>返回空 Map 表示不存在参照物——FIX 条数不为 1（0 条无参照、≥2 条
     * 应以 FIX 间交叉验证为准）或 FLOAT 无 ECEF 位置。调用方须把"空"读作
     * "无法判定"，不能读作"较差为 0"。</p>
     *
     * @param entries 候选基线条目（含 FIX 与 FLOAT）
     * @return baseId → 相对唯一 FIX 的距离 (m)；无参照物时为空 Map
     */
    public static Map<String, Double> floatDistancesToFix(
            List<BaselineEpoch.BaselineEntry> entries) {
        if (entries == null || entries.isEmpty()) return Map.of();

        Position fixPos = null;
        int fixCount = 0;
        for (BaselineEpoch.BaselineEntry b : entries) {
            if (b.solData != null && b.solData.status == SolutionStatus.FIX) {
                fixCount++;
                if (fixPos == null) fixPos = b.solData.getPosition(CoordType.ECEF);
            }
        }
        if (fixCount != 1 || fixPos == null) return Map.of();

        Map<String, Double> out = new LinkedHashMap<>();
        for (BaselineEpoch.BaselineEntry b : entries) {
            if (b.solData == null || b.solData.status != SolutionStatus.FLOAT) continue;
            Position floatPos = b.solData.getPosition(CoordType.ECEF);
            if (floatPos == null) continue;
            out.put(b.baseId, ecefDistance(fixPos, floatPos));
        }
        return out;
    }

    /**
     * 本历元所有 FLOAT 基线中，相对唯一 FIX 基线位置的最大距离 (m)。
     *
     * @return 最大距离；无参照物（FIX 条数不为 1）时为 {@link Double#NaN}
     */
    private static double maxDistanceToFix(BaselineEpoch epoch) {
        Map<String, Double> dists = floatDistancesToFix(Arrays.asList(epoch.baselines));
        if (dists.isEmpty()) return Double.NaN;
        double maxDist = 0.0;
        for (double d : dists.values()) {
            maxDist = Math.max(maxDist, d);
        }
        return maxDist;
    }

    private static AdjustResult adjustSuspect(BaselineEpoch epoch, boolean qualityWeight,
                                               CrossValidation cv, Policy p) {
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

                AdjustResult cleanResult = adjustSingle(cleanEpoch, qualityWeight, p);

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

        return adjustWithGrade(epoch, qualityWeight, cv, p);
    }

    private static AdjustResult adjustWithGrade(BaselineEpoch epoch, boolean qualityWeight,
                                                 CrossValidation cv, Policy p) {
        int k = epoch.count;
        int m = 3 * k;
        int n = 3;
        int df = m - n;

        try {
            SimpleMatrix l = CovAssembler.assembleObservationVector(epoch);
            SimpleMatrix H = CovAssembler.assembleDesignMatrix(epoch);
            SimpleMatrix R = CovAssembler.assembleGlobalR(epoch, qualityWeight);

            if (cv.inflate > 1.0 && k >= 2) {
                int inflateIdx = -1;
                if (cv.suspectBaseId != null) {
                    for (int i = 0; i < k; i++) {
                        if (epoch.baselines[i].baseId.equals(cv.suspectBaseId)) {
                            inflateIdx = i;
                            break;
                        }
                    }
                }
                if (inflateIdx < 0) {
                    inflateIdx = findWeakestBaseline(epoch);
                }
                if (inflateIdx >= 0) {
                    int base = 3 * inflateIdx;
                    for (int i = 0; i < 3; i++) {
                        for (int j = 0; j < 3; j++) {
                            R.set(base + i, base + j, R.get(base + i, base + j) * cv.inflate);
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

            double pl = computeProtectionLevel(Dx, p.plK(cv.grade));
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

    private static CrossValidation crossValidateEarly(BaselineEpoch epoch, Policy p) {
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
        double inflate = 1.0;

        if (maxDist < p.verifiedThreshold()) {
            grade = ReliabilityGrade.VERIFIED;
        } else if (maxDist < p.crossCheckedThreshold()) {
            grade = ReliabilityGrade.CROSS_CHECKED;
            inflate = p.crossCheckedCovInflate();
        } else if (maxDist < p.suspectThreshold()) {
            // 可交叉验证的上限到可疑阈值之间的过渡带：仍可解，但协方差加倍膨胀
            grade = ReliabilityGrade.CROSS_CHECKED;
            inflate = p.crossCheckedCovInflate() * 4.0;
        } else {
            grade = ReliabilityGrade.SUSPECT;
            inflate = p.crossCheckedCovInflate() * 4.0;
            suspectBaseId = identifySuspect(epoch, worstI, worstJ);
        }

        return new CrossValidation(grade, maxDist, 0.0, suspectBaseId, inflate);
    }

    /**
     * 在两条差异最大的基线中挑出<b>假固定</b>嫌疑者：取 {@code ratio × numSat} 较小者。
     *
     * <p>注意这只是"哪条基线更可能是假固定"的启发式，不能用来判定基站位移。</p>
     */
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
                                   double protectionLevel, String suspectBaseId,
                                   double inflate) {}
}