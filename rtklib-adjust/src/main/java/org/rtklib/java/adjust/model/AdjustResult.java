package org.rtklib.java.adjust.model;

import org.ejml.simple.SimpleMatrix;

import java.util.List;

public class AdjustResult {

    public final String epochTag;

    public final double[] dx;

    public final double[][] Dx;

    public final double[] v;

    public final double sigma0;

    public final int dof;

    public final SimpleMatrix Qv;

    public final double[] baardaT;

    public final double[] p01Xyz;

    public final int usedBaselineCount;

    public final boolean success;

    public final String errorMessage;

    public final ReliabilityGrade reliabilityGrade;

    public final double crossValidationDist;

    public final double protectionLevel;

    /**
     * 假固定嫌疑基站标识（仅当 {@link #reliabilityGrade} == SUSPECT 时非 null）。
     *
     * <p>由基线间较差超过阈值、再按 {@code ratio × numSat} 取弱者得到，
     * <b>只表示该基线的模糊度固定结果可疑</b>，不代表该基站发生了位移。</p>
     *
     * <p>基站位移的判定在独立通道：{@code BaselineConsistencyChecker.Report#baseDrifts}，
     * 由跨历元漂移反算，与本字段不可混用。</p>
     */
    public final String suspectBaseId;

    /**
     * 准入阶段被拒绝进入 WLS 的 FLOAT 基线（默认空列表）。
     *
     * <p>拒绝条件是"本历元恰好 1 条 FIX 且 FLOAT 与 FIX 的位置较差超过
     * {@code floatAgreementThreshold}"。被拒绝的基线<b>不参与</b>平差，
     * 因此 {@link #crossValidationDist} 对它而言不存在（为 {@link Double#NaN}），
     * 该基线的位置较差只记录在本字段。</p>
     *
     * <p>本字段存在的唯一目的：让"拦截粗差 FLOAT"不变成"静默丢弃"。
     * 若下游只看 {@link #crossValidationDist}，被拒绝的 FLOAT 会表现为
     * "无较差"而非"有 4.8 km 较差"，异常检测能力反而下降。</p>
     */
    public final List<RejectedFloat> rejectedFloats;

    /**
     * 一条被准入规则拒绝的 FLOAT 基线。
     *
     * @param baseId       基站标识
     * @param distanceToFix 该 FLOAT 相对本历元唯一 FIX 的位置较差 (m)
     * @param threshold    当时生效的准入阈值 (m)，用于事后复盘"差多少被拒"
     */
    public record RejectedFloat(String baseId, double distanceToFix, double threshold) {}

    private AdjustResult(String epochTag, double[] dx, double[][] Dx, double[] v,
                         double sigma0, int dof, SimpleMatrix Qv, double[] baardaT,
                         double[] p01Xyz, int usedBaselineCount) {
        this(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount,
                null, Double.NaN, Double.NaN, null);
    }

    private AdjustResult(String epochTag, double[] dx, double[][] Dx, double[] v,
                         double sigma0, int dof, SimpleMatrix Qv, double[] baardaT,
                         double[] p01Xyz, int usedBaselineCount,
                         ReliabilityGrade reliabilityGrade, double crossValidationDist,
                         double protectionLevel, String suspectBaseId) {
        this(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount,
                reliabilityGrade, crossValidationDist, protectionLevel, suspectBaseId,
                List.of());
    }

    private AdjustResult(String epochTag, double[] dx, double[][] Dx, double[] v,
                         double sigma0, int dof, SimpleMatrix Qv, double[] baardaT,
                         double[] p01Xyz, int usedBaselineCount,
                         ReliabilityGrade reliabilityGrade, double crossValidationDist,
                         double protectionLevel, String suspectBaseId,
                         List<RejectedFloat> rejectedFloats) {
        this.epochTag = epochTag;
        this.dx = dx;
        this.Dx = Dx;
        this.v = v;
        this.sigma0 = sigma0;
        this.dof = dof;
        this.Qv = Qv;
        this.baardaT = baardaT;
        this.p01Xyz = p01Xyz;
        this.usedBaselineCount = usedBaselineCount;
        this.success = true;
        this.errorMessage = null;
        this.reliabilityGrade = reliabilityGrade;
        this.crossValidationDist = crossValidationDist;
        this.protectionLevel = protectionLevel;
        this.suspectBaseId = suspectBaseId;
        this.rejectedFloats = rejectedFloats == null || rejectedFloats.isEmpty()
                ? List.of() : List.copyOf(rejectedFloats);
    }

    private AdjustResult(String epochTag, int usedBaselineCount, String errorMessage) {
        this.epochTag = epochTag;
        this.dx = null;
        this.Dx = null;
        this.v = null;
        this.sigma0 = Double.NaN;
        this.dof = 0;
        this.Qv = null;
        this.baardaT = null;
        this.p01Xyz = null;
        this.usedBaselineCount = usedBaselineCount;
        this.success = false;
        this.errorMessage = errorMessage;
        this.reliabilityGrade = null;
        this.crossValidationDist = Double.NaN;
        this.protectionLevel = Double.NaN;
        this.suspectBaseId = null;
        this.rejectedFloats = List.of();
    }

    public static AdjustResult success(String epochTag, double[] dx, double[][] Dx,
                                       double[] v, double sigma0, int dof,
                                       SimpleMatrix Qv, double[] baardaT,
                                       double[] p01Xyz, int usedBaselineCount) {
        return new AdjustResult(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount);
    }

    public static AdjustResult success(String epochTag, double[] dx, double[][] Dx,
                                       double[] v, double sigma0, int dof,
                                       SimpleMatrix Qv, double[] baardaT,
                                       double[] p01Xyz, int usedBaselineCount,
                                       ReliabilityGrade reliabilityGrade, double crossValidationDist,
                                       double protectionLevel, String suspectBaseId) {
        return new AdjustResult(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount,
                reliabilityGrade, crossValidationDist, protectionLevel, suspectBaseId);
    }

    public static AdjustResult failure(String epochTag, int usedBaselineCount, String errorMessage) {
        return new AdjustResult(epochTag, usedBaselineCount, errorMessage);
    }

    /**
     * 附加"准入阶段被拒绝的 FLOAT"记录。
     *
     * <p>拒绝发生在 {@code GnssBaselineAdjust} 之前（候选集装配阶段），
     * 而 {@code AdjustResult} 由平差内部构造，故用本方法在返回后补齐。</p>
     *
     * @param rejected 被拒绝的 FLOAT；null 或空时原样返回 {@code this}
     * @return 携带 rejected 记录的新实例
     */
    public AdjustResult withRejectedFloats(List<RejectedFloat> rejected) {
        if (rejected == null || rejected.isEmpty()) return this;
        return new AdjustResult(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz,
                usedBaselineCount, reliabilityGrade, crossValidationDist, protectionLevel,
                suspectBaseId, rejected);
    }
}