package org.rtklib.java.aggregate;

/**
 * 历元聚合结果状态码。
 *
 * <p>{@link AggregatedSol} 对象永不为 null，失败时通过本枚举区分原因，
 * {@code posEcef}/{@code covEcef} 为 null。</p>
 */
public enum AggregationStatus {
    /** 成功 */
    OK,
    /** 输入窗口为空 */
    EMPTY_WINDOW,
    /** FIX 历元数 < minFixEpochs（含降级后仍不足） */
    INSUFFICIENT_FIX,
    /** 全 covEcef == null 且样本不足以估计经验协方差 */
    COV_UNAVAILABLE,
    /** 卡尔曼连续缺失观测超过阈值 */
    KALMAN_OBS_LOST,
    /** 模式与参数类型不匹配 */
    PARAM_MISMATCH
}
