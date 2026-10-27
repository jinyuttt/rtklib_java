package org.rtklib.java.aggregate;

import org.rtklib.java.data.GTime;

/**
 * 历元聚合输出结果。
 *
 * <p>对象永不为 null。失败时 {@link #status} 为非 OK 枚举值，
 * {@link #posEcef}/{@link #covEcef} 为 null。</p>
 */
public final class AggregatedSol {

    /** 聚合状态。 */
    public final AggregationStatus status;

    /** 输出时刻（ROBUST=时间加权中心，KALMAN_SMOOTH=窗口末时刻）。 */
    public final GTime time;

    /** 窗口末历元时刻（诊断用）。 */
    public final GTime lastEpochTime;

    /** 融合坐标 ECEF [3] (m)。 */
    public final double[] posEcef;

    /** 融合协方差 ECEF [9]，行优先 (m²)。默认输出经验协方差（不偏乐观）。 */
    public final double[] covEcef;

    /** 理论加权协方差 Ĉ (m²)，诊断用（偏乐观）。仅 ROBUST 模式有值。 */
    public final double[] covEcefTheoretical;

    /** 实际参与融合的 FIX 历元数。 */
    public final int usedEpochs;

    /** 被过滤的非 FIX + IGG3 置零的 FIX 数。 */
    public final int rejectedEpochs;

    /** 窗口内坐标经验标准差 [3] (m)，诊断用。 */
    public final double[] windowStdEcef;

    /** 实际使用的模式。 */
    public final AggregationMode mode;

    public AggregatedSol(AggregationStatus status, GTime time, GTime lastEpochTime,
                         double[] posEcef, double[] covEcef, double[] covEcefTheoretical,
                         int usedEpochs, int rejectedEpochs, double[] windowStdEcef,
                         AggregationMode mode) {
        this.status = status;
        this.time = time;
        this.lastEpochTime = lastEpochTime;
        this.posEcef = posEcef;
        this.covEcef = covEcef;
        this.covEcefTheoretical = covEcefTheoretical;
        this.usedEpochs = usedEpochs;
        this.rejectedEpochs = rejectedEpochs;
        this.windowStdEcef = windowStdEcef;
        this.mode = mode;
    }

    /** 构造失败结果（posEcef/covEcef 为 null）。 */
    public static AggregatedSol failure(AggregationStatus status, GTime lastEpochTime, AggregationMode mode) {
        return new AggregatedSol(status, null, lastEpochTime, null, null, null, 0, 0, null, mode);
    }
}
