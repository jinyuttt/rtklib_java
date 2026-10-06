package org.rtklib.java.research.factorgraph;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.MatrixOps;

/**
 * 因子图变量节点（research模块因子图框架）。
 *
 * <p>代表待估计的状态量，如位置、速度、钟差、模糊度等。</p>
 */
public class Variable {
    public final String name;
    public final int dimension;
    public SimpleMatrix value;
    public SimpleMatrix covariance;
    public boolean fixed;

    public Variable(String name, int dimension) {
        this.name = name;
        this.dimension = dimension;
        this.value = new SimpleMatrix(dimension, 1);
        this.covariance = SimpleMatrix.identity(dimension);
        this.fixed = false;
    }

    public Variable(String name, SimpleMatrix initialValue) {
        this.name = name;
        this.dimension = MatrixOps.rows(initialValue);
        this.value = initialValue.copy();
        this.covariance = SimpleMatrix.identity(this.dimension);
        this.fixed = false;
    }

    public void setValue(SimpleMatrix v) {
        this.value = v.copy();
    }

    public void setCovariance(SimpleMatrix cov) {
        this.covariance = cov.copy();
    }

    @Override
    public String toString() {
        return String.format("Var[%s, dim=%d, fixed=%b]", name, dimension, fixed);
    }
}