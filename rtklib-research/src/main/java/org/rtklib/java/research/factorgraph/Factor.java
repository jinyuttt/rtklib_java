package org.rtklib.java.research.factorgraph;

import org.ejml.simple.SimpleMatrix;
import java.util.ArrayList;
import java.util.List;

/**
 * 因子图因子节点（research模块因子图框架）。
 *
 * <p>代表一个约束/观测模型，连接若干变量节点。</p>
 *
 * <h3>数学形式</h3>
 * <pre>
 *   z = h(x) + v,  v ~ N(0, R)
 *   残差 r = z - h(x)
 *   雅可比 J = dh/dx
 *   信息矩阵 Λ = J^T R^{-1} J
 *   信息向量 η = J^T R^{-1} r
 * </pre>
 */
public abstract class Factor {
    public final String name;
    public final List<Variable> connectedVariables;
    public SimpleMatrix information;

    protected Factor(String name, List<Variable> connectedVariables) {
        this.name = name;
        this.connectedVariables = connectedVariables;
    }

    public abstract int residualDimension();

    public int dim() {
        return residualDimension();
    }

    public abstract SimpleMatrix residual();

    public abstract SimpleMatrix jacobian(int varIndex);

    public abstract SimpleMatrix noiseCovariance();

    /**
     * 返回每个参数块的维度列表。
     * 默认实现：返回每个 connectedVariable 的 dimension。
     */
    public List<Integer> parameterBlockSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (Variable v : connectedVariables) {
            sizes.add(v.dimension);
        }
        return sizes;
    }

    /**
     * 从原始参数数组计算残差向量。
     * 子类应覆写此方法以支持 ResidualBlockInfo 边缘化评估。
     */
    public double[] computeResiduals(List<double[]> paramBlocks) {
        SimpleMatrix r = residual();
        double[] result = new double[r.getNumElements()];
        for (int i = 0; i < result.length; i++) {
            result[i] = r.get(i, 0);
        }
        return result;
    }

    /**
     * 从原始参数数组计算雅可比矩阵列表。
     * 子类应覆写此方法以支持 ResidualBlockInfo 边缘化评估。
     */
    public SimpleMatrix[] computeJacobians(List<double[]> paramBlocks) {
        SimpleMatrix[] jacs = new SimpleMatrix[connectedVariables.size()];
        for (int i = 0; i < connectedVariables.size(); i++) {
            jacs[i] = jacobian(i);
        }
        return jacs;
    }

    public double error() {
        SimpleMatrix r = residual();
        SimpleMatrix R = noiseCovariance();
        SimpleMatrix Rinv = R.invert();
        SimpleMatrix rT = r.transpose();
        SimpleMatrix weighted = rT.mult(Rinv).mult(r);
        return 0.5 * weighted.get(0, 0);
    }

    public SimpleMatrix computeInformation() {
        int totalDim = 0;
        for (Variable v : connectedVariables) totalDim += v.dimension;
        SimpleMatrix info = new SimpleMatrix(totalDim, totalDim);
        SimpleMatrix Rinv = noiseCovariance().invert();
        int colOff = 0;
        for (int j = 0; j < connectedVariables.size(); j++) {
            SimpleMatrix Jj = jacobian(j);
            int rowOff = 0;
            for (int i = 0; i < connectedVariables.size(); i++) {
                SimpleMatrix Ji = jacobian(i);
                SimpleMatrix block = Ji.transpose().mult(Rinv).mult(Jj);
                info.insertIntoThis(rowOff, colOff, block);
                rowOff += connectedVariables.get(i).dimension;
            }
            colOff += connectedVariables.get(j).dimension;
        }
        this.information = info;
        return info;
    }

    @Override
    public String toString() {
        return String.format("Factor[%s, nVar=%d, resDim=%d]", name, connectedVariables.size(), residualDimension());
    }
}