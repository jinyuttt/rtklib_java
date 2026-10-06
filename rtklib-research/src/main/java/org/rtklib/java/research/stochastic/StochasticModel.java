package org.rtklib.java.research.stochastic;

import org.ejml.simple.SimpleMatrix;

/**
 * 随机模型接口（research模块自有）。
 *
 * <p>定义观测噪声矩阵 R 和过程噪声矩阵 Q 的构建方式，支持传统经验模型和学习型模型。
 * 返回 {@link SimpleMatrix} 而非标量，支持非对角协方差结构。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 *   StochasticModel model = new ElevationSnrModel(0.3, 0.003, 0.1);
 *   SimpleMatrix R = model.observationCovariance(elevations, sys, 1.0);
 *   SimpleMatrix Q = model.processNoiseCovariance(12, 0.1);
 * }</pre>
 */
public interface StochasticModel {

    /** 模型名称。 */
    String name();

    /**
     * 批量观测噪声协方差矩阵（对角）。
     *
     * @param elevations 各卫星高度角 (rad)，长度 = nSat
     * @param sys        各卫星系统标识 (GPS/GLO/GAL/BDS/QZS)
     * @param scaleFactor 外部缩放因子（如 RobustLoss 补偿）
     * @return nSat × nSat 对角协方差矩阵
     */
    SimpleMatrix observationCovariance(double[] elevations, int[] sys, double scaleFactor);

    /**
     * 过程噪声协方差矩阵（用于状态转移预测步）。
     *
     * @param dim 状态维度（11 = state, 12 = integration, n = ambiguity）
     * @param dt  时间间隔 (s)
     * @return dim × dim 过程噪声协方差矩阵
     */
    SimpleMatrix processNoiseCovariance(int dim, double dt);
}