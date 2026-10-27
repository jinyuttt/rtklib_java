package org.rtklib.java.aggregate;

/**
 * 卡尔曼平滑模式（{@link AggregationMode#KALMAN_SMOOTH}）参数。
 *
 * <p>采用常速度模型，过程噪声由单一参数 {@link #q}（加速度白噪声谱密度）控制。</p>
 */
public class KalmanParams implements AggregationParams {

    /** 加速度过程噪声谱密度 (m/s²/√Hz)。 */
    public double q = 1e-4;

    /** 速度初始方差 (m/s)²。 */
    public double initVelVar = 1.0;

    /** 最少 FIX 历元数。 */
    public int minFixEpochs = 3;

    /** 连续缺失观测保护阈值，超过则返回 {@link AggregationStatus#KALMAN_OBS_LOST}。 */
    public int maxConsecutiveMiss = 10;

    /** FIX 不足时是否允许 FLOAT 历元降级参与融合。 */
    public boolean allowFloatFallback = false;

    @Override
    public int minFixEpochs() {
        return minFixEpochs;
    }

    @Override
    public boolean allowFloatFallback() {
        return allowFloatFallback;
    }
}
