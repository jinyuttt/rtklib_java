package org.rtklib.java.research.factorgraph;

import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;

/**
 * 开关变量先验因子 — 约束开关变量 s 倾向于 1.0。
 *
 * <p>与 SwitchWrapperFactor 配对使用：
 * SwitchWrapperFactor 让优化器可以根据观测质量自动调整开关，
 * SwitchPriorFactor 则提供先验约束防止开关无理由偏离 1.0。</p>
 *
 * <h3>数学形式</h3>
 * <pre>
 *   残差: r = (s - 1.0) / σ
 *   雅可比: J = 1/σ
 *   噪声协方差: R = 1×1 = 1.0（信息矩阵 W = 1/σ²）
 *
 *   σ 越小 → 先验越强 → 开关越难偏离 1.0 → 只有非常差的观测才被关闭
 * </pre>
 *
 * <h3>推荐 σ 取值</h3>
 * <pre>
 *   σ=0.1  强先验（默认）：只有残差 > 3σ_maha 的观测才被关闭
 *   σ=0.3  中等先验：适用于已知有中等多径的环境
 *   σ=0.5  弱先验：允许较多观测被关闭，适用于重度城市峡谷
 * </pre>
 */
public class SwitchPriorFactor extends Factor {

    private final double sigma;

    /**
     * 构造开关先验因子。
     *
     * @param switchVar 开关变量（维度 1）
     * @param sigma     先验标准差（越小先验越强），推荐 0.1
     */
    public SwitchPriorFactor(Variable switchVar, double sigma) {
        super("SwitchPrior", Arrays.asList(switchVar));
        this.sigma = sigma;
    }

    @Override
    public int residualDimension() {
        return 1;
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        return Arrays.asList(1);
    }

    @Override
    public SimpleMatrix residual() {
        double s = connectedVariables.get(0).value != null
            ? connectedVariables.get(0).value.get(0, 0) : 1.0;
        SimpleMatrix r = new SimpleMatrix(1, 1);
        r.set(0, 0, (s - 1.0) / sigma);
        return r;
    }

    @Override
    public SimpleMatrix jacobian(int varIndex) {
        SimpleMatrix J = new SimpleMatrix(1, 1);
        J.set(0, 0, 1.0 / sigma);
        return J;
    }

    @Override
    public SimpleMatrix noiseCovariance() {
        SimpleMatrix R = new SimpleMatrix(1, 1);
        R.set(0, 0, 1.0);
        return R;
    }

    @Override
    public double error() {
        double s = connectedVariables.get(0).value != null
            ? connectedVariables.get(0).value.get(0, 0) : 1.0;
        double r = (s - 1.0) / sigma;
        return 0.5 * r * r;
    }

    @Override
    public double[] computeResiduals(List<double[]> paramBlocks) {
        double s = paramBlocks.get(0)[0];
        return new double[]{(s - 1.0) / sigma};
    }

    @Override
    public SimpleMatrix[] computeJacobians(List<double[]> paramBlocks) {
        SimpleMatrix J = new SimpleMatrix(1, 1);
        J.set(0, 0, 1.0 / sigma);
        return new SimpleMatrix[]{J};
    }
}