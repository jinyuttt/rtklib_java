package org.rtklib.java.research.factorgraph;

import java.util.ArrayList;
import java.util.List;
import org.ejml.simple.SimpleMatrix;

/**
 * 残差块信息（移植自FE-GUT residual_block_info.h）。
 *
 * <p>封装一个因子（CostFunction）的残差和雅可比，支持鲁棒损失函数。
 * 边缘化时调用 {@link #evaluate()} 计算出 J 和 r，后续用于构造 Hessian。</p>
 */
public class ResidualBlockInfo {

    private final Factor factor;
    private final RobustLoss lossFunction;
    private final List<double[]> parameterBlocks;
    private final List<Integer> blockSizes;
    private final List<Integer> margParaIndex;

    private List<SimpleMatrix> jacobians;
    private SimpleMatrix residuals;

    public ResidualBlockInfo(Factor factor, RobustLoss lossFunction,
                             List<double[]> parameterBlocks, List<Integer> margParaIndex) {
        this.factor = factor;
        this.lossFunction = lossFunction;
        this.parameterBlocks = new ArrayList<>(parameterBlocks);
        this.margParaIndex = new ArrayList<>(margParaIndex);

        blockSizes = new ArrayList<>();
        for (double[] block : parameterBlocks) {
            blockSizes.add(block.length);
        }
    }

    public void evaluate() {
        int residualDim = factor.dim();

        residuals = new SimpleMatrix(residualDim, 1);

        SimpleMatrix[] rawJac = factor.computeJacobians(parameterBlocks);
        jacobians = new ArrayList<>();
        double[] resArr = factor.computeResiduals(parameterBlocks);

        if (resArr.length != residualDim) {
            throw new IllegalStateException(
                "Residual dimension mismatch: expected " + residualDim + ", got " + resArr.length);
        }
        for (int i = 0; i < residualDim; i++) {
            residuals.set(i, 0, resArr[i]);
        }

        List<Integer> paramSizes = factor.parameterBlockSizes();
        for (int i = 0; i < paramSizes.size(); i++) {
            if (rawJac[i].numRows() != residualDim || rawJac[i].numCols() != paramSizes.get(i)) {
                throw new IllegalStateException(
                    "Jacobian dimension mismatch at block " + i);
            }
            jacobians.add(rawJac[i]);
        }

        if (lossFunction != null) {
            applyLossFunction();
        }
    }

    private void applyLossFunction() {
        double sqNorm = 0;
        for (int i = 0; i < residuals.getNumElements(); i++) {
            double v = residuals.get(i, 0);
            sqNorm += v * v;
        }

        double rho0 = lossFunction.evaluate(sqNorm);
        double rho1 = lossFunction.weight(sqNorm);
        double rho2 = lossFunction.secondDerivative(sqNorm);

        double sqrtRho1 = Math.sqrt(Math.max(rho1, 1e-12));

        double residualScaling;
        double alphaSqNorm;

        if (sqNorm == 0.0 || rho2 <= 0.0) {
            residualScaling = sqrtRho1;
            alphaSqNorm = 0.0;
        } else {
            double D = 1.0 + 2.0 * sqNorm * rho2 / rho1;
            double alpha = 1.0 - Math.sqrt(Math.max(D, 0));
            residualScaling = sqrtRho1 / (1.0 - alpha);
            alphaSqNorm = alpha / sqNorm;
        }

        for (int k = 0; k < jacobians.size(); k++) {
            SimpleMatrix Jk = jacobians.get(k);
            SimpleMatrix r = residuals;

            SimpleMatrix rTJ = r.transpose().mult(Jk);
            SimpleMatrix correction = r.mult(rTJ);
            Jk = Jk.minus(correction.scale(alphaSqNorm));
            Jk = Jk.scale(sqrtRho1);
            jacobians.set(k, Jk);
        }

        residuals = residuals.scale(residualScaling);
    }

    public List<SimpleMatrix> jacobians() {
        return jacobians;
    }

    public SimpleMatrix residuals() {
        return residuals;
    }

    public List<Integer> parameterBlockSizes() {
        return blockSizes;
    }

    public List<double[]> parameterBlocks() {
        return parameterBlocks;
    }

    public List<Integer> marginalizationParametersIndex() {
        return margParaIndex;
    }

    public Factor factor() {
        return factor;
    }
}