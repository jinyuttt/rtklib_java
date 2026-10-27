package org.rtklib.java.aggregate;

/**
 * 聚合参数公共接口。
 *
 * <p>{@link RobustParams} 与 {@link KalmanParams} 分别实现，
 * 调用方传参时模式与参数类型必须匹配。</p>
 */
public interface AggregationParams {
    /** 最少 FIX 历元数，低于此值返回 {@link AggregationStatus#INSUFFICIENT_FIX}。 */
    int minFixEpochs();

    /** FIX 不足时是否允许 FLOAT 历元降级参与融合。 */
    boolean allowFloatFallback();
}
