package org.rtklib.java.research.factorgraph;

import java.util.ArrayList;
import java.util.List;
import org.ejml.simple.SimpleMatrix;

/**
 * 边缘化因子（移植自FE-GUT marginalization_factor.h）。
 *
 * <p>将边缘化后的先验信息作为因子加入图优化中。
 * 残差 = e0 + J0·(x - x0)，雅可比 = J0。</p>
 */
public class MarginalizationFactor extends Factor {

    private final MarginalizationInfo margInfo;
    private final List<Variable> keptVariables;

    public MarginalizationFactor(MarginalizationInfo margInfo, List<Variable> keptVariables) {
        super("MargFactor", keptVariables);
        this.margInfo = margInfo;
        this.keptVariables = keptVariables;
    }

    @Override
    public int residualDimension() {
        return margInfo.remainedSize();
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (int size : margInfo.remainedBlockSize()) {
            sizes.add(size);
        }
        return sizes;
    }

    @Override
    public SimpleMatrix residual() {
        int marginalizedSz = margInfo.marginalizedSize();
        int remainedSz = margInfo.remainedSize();

        List<Integer> blockIndex = margInfo.remainedBlockIndex();
        List<Integer> blockSizes = margInfo.remainedBlockSize();
        List<double[]> blockData = margInfo.remainedBlockData();

        SimpleMatrix dx = new SimpleMatrix(remainedSz, 1);

        for (int i = 0; i < blockSizes.size(); i++) {
            int size = blockSizes.get(i);
            int index = blockIndex.get(i) - marginalizedSz;

            SimpleMatrix x = new SimpleMatrix(size, 1);
            SimpleMatrix x0 = new SimpleMatrix(size, 1);

            if (i < connectedVariables.size() && connectedVariables.get(i).value != null) {
                for (int j = 0; j < Math.min(size, connectedVariables.get(i).value.getNumElements()); j++) {
                    x.set(j, 0, connectedVariables.get(i).value.get(j, 0));
                }
            }
            for (int j = 0; j < size; j++) {
                x0.set(j, 0, blockData.get(i)[j]);
            }

            for (int j = 0; j < size; j++) {
                dx.set(index + j, 0, x.get(j, 0) - x0.get(j, 0));
            }
        }

        SimpleMatrix linRes = margInfo.linearizedResiduals();
        SimpleMatrix linJac = margInfo.linearizedJacobians();
        SimpleMatrix e = linRes.plus(linJac.mult(dx));

        return e;
    }

    @Override
    public SimpleMatrix jacobian(int varIndex) {
        int marginalizedSz = margInfo.marginalizedSize();
        int remainedSz = margInfo.remainedSize();

        List<Integer> blockSizes = margInfo.remainedBlockSize();
        List<Integer> blockIndex = margInfo.remainedBlockIndex();

        if (varIndex >= blockSizes.size()) {
            return new SimpleMatrix(remainedSz, 1);
        }

        int size = blockSizes.get(varIndex);
        int index = blockIndex.get(varIndex) - marginalizedSz;
        int localSz = MarginalizationInfo.localSize(size);

        SimpleMatrix jac = new SimpleMatrix(remainedSz, size);
        SimpleMatrix linJac = margInfo.linearizedJacobians();

        for (int i = 0; i < remainedSz; i++) {
            for (int j = 0; j < localSz; j++) {
                jac.set(i, j, linJac.get(i, index + j));
            }
        }

        return jac;
    }

    @Override
    public SimpleMatrix noiseCovariance() {
        return SimpleMatrix.identity(residualDimension());
    }

    @Override
    public double[] computeResiduals(List<double[]> paramBlocks) {
        int marginalizedSz = margInfo.marginalizedSize();
        int remainedSz = margInfo.remainedSize();

        List<Integer> blockIndex = margInfo.remainedBlockIndex();
        List<Integer> blockSizes = margInfo.remainedBlockSize();
        List<double[]> blockData = margInfo.remainedBlockData();

        SimpleMatrix dx = new SimpleMatrix(remainedSz, 1);

        for (int i = 0; i < blockSizes.size(); i++) {
            int size = blockSizes.get(i);
            int index = blockIndex.get(i) - marginalizedSz;

            double[] x = paramBlocks.get(i);
            double[] x0 = blockData.get(i);

            for (int j = 0; j < size; j++) {
                dx.set(index + j, 0, x[j] - x0[j]);
            }
        }

        SimpleMatrix e = margInfo.linearizedResiduals().plus(margInfo.linearizedJacobians().mult(dx));
        double[] result = new double[remainedSz];
        for (int i = 0; i < remainedSz; i++) {
            result[i] = e.get(i, 0);
        }
        return result;
    }

    @Override
    public SimpleMatrix[] computeJacobians(List<double[]> paramBlocks) {
        int marginalizedSz = margInfo.marginalizedSize();
        int remainedSz = margInfo.remainedSize();

        List<Integer> blockSizes = margInfo.remainedBlockSize();
        List<Integer> blockIndex = margInfo.remainedBlockIndex();

        SimpleMatrix[] jacs = new SimpleMatrix[blockSizes.size()];

        for (int k = 0; k < blockSizes.size(); k++) {
            int size = blockSizes.get(k);
            int index = blockIndex.get(k) - marginalizedSz;
            int localSz = MarginalizationInfo.localSize(size);

            SimpleMatrix jac = new SimpleMatrix(remainedSz, size);
            SimpleMatrix linJac = margInfo.linearizedJacobians();

            for (int i = 0; i < remainedSz; i++) {
                for (int j = 0; j < localSz; j++) {
                    jac.set(i, j, linJac.get(i, index + j));
                }
            }
            jacs[k] = jac;
        }

        return jacs;
    }
}