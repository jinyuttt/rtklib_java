package org.rtklib.java.research.factorgraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;

/**
 * 开关包装因子 — 为任意 GNSS 观测因子添加 SwitchVariable。
 *
 * <p>参考 gtsam_gnss 的 SwitchVariable 机制（PLANS 2025, Suzuki）：
 * 每个因子连接一个开关变量 s ∈ [0, 1]，其残差缩放因子为 s。
 * 当观测质量差时，优化器自动将 s → 0，等效于"关闭"该因子。
 * SwitchPriorFactor 提供先验约束 s ~ 1，只有数据强烈反对时才偏离。</p>
 *
 * <h3>数学形式</h3>
 * <pre>
 *   原残差: r(x) = h(x) - z
 *   开关残差: r'(x, s) = s · r(x)
 *
 *   雅可比:
 *     ∂r'/∂x_j = s · ∂r/∂x_j          (状态/参数变量)
 *     ∂r'/∂s   = r(x)                  (开关变量)
 *
 *   Hessian 贡献（W = R^{-1} 为信息矩阵）:
 *     J^T W J → s² · J_orig^T W J_orig（状态块，被 s 衰减）
 *     r^T W r → 驱动 s 偏离 1 的梯度
 * </pre>
 *
 * <p>使用方式：
 * <pre>
 *   TcFactor tcFactor = new TcFactor(...);
 *   Variable switchVar = new Variable("sw_tc_0", 1);   // 初始值 = 1.0
 *   SwitchWrapperFactor swFactor = new SwitchWrapperFactor(tcFactor, switchVar);
 *   graph.addVariable(switchVar);
 *   graph.addFactor(swFactor);
 *   graph.addFactor(new SwitchPriorFactor(switchVar, 0.1));  // s ~ N(1.0, 0.1²)
 * </pre>
 */
public class SwitchWrapperFactor extends Factor {

    private final Factor baseFactor;
    private final Variable switchVar;
    private final int baseVarCount;

    /**
     * 构造开关包装因子。
     *
     * @param baseFactor 原始观测因子（TcFactor, PhaseFactor, PseudorangeFactor 等）
     * @param switchVar  开关变量（维度 1），初始值应设为 1.0
     */
    public SwitchWrapperFactor(Factor baseFactor, Variable switchVar) {
        super(baseFactor.name + "_SC", buildCombinedVars(baseFactor, switchVar));
        this.baseFactor = baseFactor;
        this.switchVar = switchVar;
        this.baseVarCount = baseFactor.connectedVariables.size();
    }

    private static List<Variable> buildCombinedVars(Factor base, Variable sw) {
        List<Variable> vars = new ArrayList<>(base.connectedVariables);
        vars.add(sw);
        return vars;
    }

    private double clampedSwitch() {
        if (switchVar.value == null) return 1.0;
        double s = switchVar.value.get(0, 0);
        return Math.max(1e-8, Math.min(1.0, s));
    }

    @Override
    public int residualDimension() {
        return baseFactor.residualDimension();
    }

    @Override
    public int dim() {
        return baseFactor.dim();
    }

    @Override
    public SimpleMatrix residual() {
        double s = clampedSwitch();
        return baseFactor.residual().scale(s);
    }

    @Override
    public SimpleMatrix jacobian(int varIndex) {
        double s = clampedSwitch();

        if (varIndex < baseVarCount) {
            return baseFactor.jacobian(varIndex).scale(s);
        } else {
            SimpleMatrix rBase = baseFactor.residual();
            SimpleMatrix jac = new SimpleMatrix(rBase.getNumElements(), 1);
            for (int i = 0; i < rBase.getNumElements(); i++) {
                jac.set(i, 0, rBase.get(i, 0));
            }
            return jac;
        }
    }

    @Override
    public SimpleMatrix noiseCovariance() {
        return baseFactor.noiseCovariance();
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        List<Integer> sizes = new ArrayList<>(baseFactor.parameterBlockSizes());
        sizes.add(1);
        return sizes;
    }

    @Override
    public double[] computeResiduals(List<double[]> paramBlocks) {
        int n = paramBlocks.size();
        if (n != baseVarCount + 1) {
            throw new IllegalArgumentException("SwitchWrapperFactor: expected "
                + (baseVarCount + 1) + " param blocks, got " + n);
        }

        double s = clampParam(paramBlocks.get(n - 1)[0]);

        List<double[]> baseParams = paramBlocks.subList(0, baseVarCount);
        double[] baseRes = baseFactor.computeResiduals(baseParams);

        double[] result = new double[baseRes.length];
        for (int i = 0; i < baseRes.length; i++) {
            result[i] = s * baseRes[i];
        }
        return result;
    }

    @Override
    public SimpleMatrix[] computeJacobians(List<double[]> paramBlocks) {
        int n = paramBlocks.size();
        if (n != baseVarCount + 1) {
            throw new IllegalArgumentException("SwitchWrapperFactor: expected "
                + (baseVarCount + 1) + " param blocks, got " + n);
        }

        double s = clampParam(paramBlocks.get(n - 1)[0]);

        List<double[]> baseParams = paramBlocks.subList(0, baseVarCount);
        SimpleMatrix[] baseJacs = baseFactor.computeJacobians(baseParams);
        double[] baseRes = baseFactor.computeResiduals(baseParams);

        SimpleMatrix[] result = new SimpleMatrix[baseJacs.length + 1];

        for (int i = 0; i < baseJacs.length; i++) {
            result[i] = baseJacs[i].scale(s);
        }

        int resDim = baseRes.length;
        SimpleMatrix switchJac = new SimpleMatrix(resDim, 1);
        for (int i = 0; i < resDim; i++) {
            switchJac.set(i, 0, baseRes[i]);
        }
        result[baseJacs.length] = switchJac;

        return result;
    }

    @Override
    public double error() {
        double s = clampedSwitch();
        SimpleMatrix r = baseFactor.residual();
        SimpleMatrix R = baseFactor.noiseCovariance();
        SimpleMatrix Rinv = R.invert();
        SimpleMatrix rT = r.transpose();
        SimpleMatrix weighted = rT.mult(Rinv).mult(r);
        return 0.5 * s * s * weighted.get(0, 0);
    }

    private static double clampParam(double val) {
        return Math.max(1e-8, Math.min(1.0, val));
    }
}