package org.rtklib.java.research.factorgraph;

import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;

/**
 * 状态预测因子（移植自FE-GUT predict_factor.h）。
 *
 * <p>支持任意维度：连接相邻历元的两个同维变量节点。
 * 残差 = state1 - F_g * state0，约束状态转移关系。</p>
 */
public class PredictFactor extends Factor {

    private static final int STATE_DIM = 11;
    private final int dim;
    private final double dt;

    private transient SimpleMatrix Fg;
    private transient double[] cachedState0;
    private transient double[] cachedState1;
    private transient SimpleMatrix cachedResidual;
    private transient SimpleMatrix[] cachedJacobians;

    public PredictFactor(Variable prevState, Variable nextState, double dt) {
        this(prevState, nextState, dt, STATE_DIM);
    }

    public PredictFactor(Variable prevState, Variable nextState, double dt, int dim) {
        super("PredictFactor", Arrays.asList(prevState, nextState));
        this.dt = dt;
        this.dim = dim;
    }

    @Override
    public int residualDimension() {
        return dim;
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        return Arrays.asList(dim, dim);
    }

    private SimpleMatrix getFg() {
        if (Fg == null) {
            Fg = SimpleMatrix.identity(dim);
            if (dim == STATE_DIM) {
                double dt2 = 0.5 * dt * dt;
                Fg.set(0, 3, dt); Fg.set(1, 4, dt); Fg.set(2, 5, dt);
                Fg.set(0, 6, dt2); Fg.set(1, 7, dt2); Fg.set(2, 8, dt2);
                Fg.set(3, 6, dt); Fg.set(4, 7, dt); Fg.set(5, 8, dt);
                Fg.set(9, 10, dt);
            }
        }
        return Fg;
    }

    @Override
    public SimpleMatrix residual() {
        updateCaches();
        return cachedResidual;
    }

    @Override
    public SimpleMatrix jacobian(int varIndex) {
        updateCaches();
        return cachedJacobians[varIndex];
    }

    @Override
    public SimpleMatrix noiseCovariance() {
        SimpleMatrix Qg = new SimpleMatrix(dim, dim);
        if (dim == STATE_DIM) {
            double Sj = 0.4;
            double dt2 = dt * dt, dt3 = dt * dt2, dt4 = dt * dt3, dt5 = dt * dt4;
            Qg.set(0,0, 1.0/20*Sj*dt5); Qg.set(1,1, 1.0/20*Sj*dt5); Qg.set(2,2, 1.0/20*Sj*dt5);
            Qg.set(0,3, 1.0/8*Sj*dt4);  Qg.set(3,0, 1.0/8*Sj*dt4);
            Qg.set(1,4, 1.0/8*Sj*dt4);  Qg.set(4,1, 1.0/8*Sj*dt4);
            Qg.set(2,5, 1.0/8*Sj*dt4);  Qg.set(5,2, 1.0/8*Sj*dt4);
            Qg.set(0,6, 1.0/6*Sj*dt3);  Qg.set(6,0, 1.0/6*Sj*dt3);
            Qg.set(1,7, 1.0/6*Sj*dt3);  Qg.set(7,1, 1.0/6*Sj*dt3);
            Qg.set(2,8, 1.0/6*Sj*dt3);  Qg.set(8,2, 1.0/6*Sj*dt3);
            Qg.set(3,3, 1.0/3*Sj*dt3);  Qg.set(4,4, 1.0/3*Sj*dt3);  Qg.set(5,5, 1.0/3*Sj*dt3);
            Qg.set(3,6, 1.0/2*Sj*dt2);  Qg.set(6,3, 1.0/2*Sj*dt2);
            Qg.set(4,7, 1.0/2*Sj*dt2);  Qg.set(7,4, 1.0/2*Sj*dt2);
            Qg.set(5,8, 1.0/2*Sj*dt2);  Qg.set(8,5, 1.0/2*Sj*dt2);
            Qg.set(6,6, Sj*dt);  Qg.set(7,7, Sj*dt);  Qg.set(8,8, Sj*dt);
            Qg.set(9,9, 36*dt+1.0/3*0.01*dt3);
            Qg.set(9,10, 1.0/2*0.01*dt2); Qg.set(10,9, 1.0/2*0.01*dt2);
            Qg.set(10,10, 0.01*dt);
        } else {
            double noiseFloor = 1e-6;
            for (int i = 0; i < dim; i++) {
                Qg.set(i, i, noiseFloor);
            }
        }
        return Qg;
    }

    @Override
    public double[] computeResiduals(List<double[]> paramBlocks) {
        double[] state0 = paramBlocks.get(0);
        double[] state1 = paramBlocks.get(1);
        SimpleMatrix s0 = vecToMatrix(state0, dim);
        SimpleMatrix s1 = vecToMatrix(state1, dim);
        SimpleMatrix pred = getFg().mult(s0);
        SimpleMatrix res = s1.minus(pred);
        double[] result = new double[dim];
        for (int i = 0; i < dim; i++) {
            result[i] = res.get(i, 0);
        }
        return result;
    }

    @Override
    public SimpleMatrix[] computeJacobians(List<double[]> paramBlocks) {
        SimpleMatrix J0 = getFg().scale(-1);
        SimpleMatrix J1 = SimpleMatrix.identity(dim);
        return new SimpleMatrix[]{J0, J1};
    }

    private void updateCaches() {
        double[] s0 = new double[dim];
        double[] s1 = new double[dim];
        if (connectedVariables.get(0).value != null) {
            for (int i = 0; i < Math.min(dim, connectedVariables.get(0).dimension); i++) s0[i] = connectedVariables.get(0).value.get(i, 0);
        }
        if (connectedVariables.get(1).value != null) {
            for (int i = 0; i < Math.min(dim, connectedVariables.get(1).dimension); i++) s1[i] = connectedVariables.get(1).value.get(i, 0);
        }

        if (cachedState0 != null && Arrays.equals(cachedState0, s0) && Arrays.equals(cachedState1, s1)) {
            return;
        }

        SimpleMatrix J0 = getFg().scale(-1);
        SimpleMatrix J1 = SimpleMatrix.identity(dim);
        cachedJacobians = new SimpleMatrix[]{J0, J1};

        double[] res = computeResiduals(Arrays.asList(s0, s1));
        cachedResidual = new SimpleMatrix(dim, 1);
        for (int i = 0; i < dim; i++) cachedResidual.set(i, 0, res[i]);

        cachedState0 = s0;
        cachedState1 = s1;
    }

    private static SimpleMatrix vecToMatrix(double[] vec, int n) {
        SimpleMatrix m = new SimpleMatrix(n, 1);
        for (int i = 0; i < n; i++) m.set(i, 0, vec[i]);
        return m;
    }
}