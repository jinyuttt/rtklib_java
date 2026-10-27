package org.rtklib.java.aggregate;

/**
 * 鲁棒加权模式（{@link AggregationMode#ROBUST}）参数。
 */
public class RobustParams implements AggregationParams {

    /** 是否用分量中位数作为初值（true=中位数，false=协方差加权平均）。 */
    public boolean useMedianInit = true;

    /** IGG3 降权阈值 k0（标准化残差 ≤ k0 权重为 1）。 */
    public double k0 = 1.5;

    /** IGG3 剔除阈值 k1（标准化残差 > k1 权重为 0）。 */
    public double k1 = 3.0;

    /** IGG3 最大迭代次数（迭代到权重稳定或达上限）。 */
    public int maxIter = 5;

    /** 最少 FIX 历元数。 */
    public int minFixEpochs = 3;

    /** 经验协方差估计的最小样本数，低于时对对角线施加下限。 */
    public int minEmpiricalEpochs = 5;

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
