package org.rtklib.java.research.factorgraph;

import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.GnssConst;

/**
 * 伪距观测因子（独立因子，不绑定伪距率/UWB）。
 *
 * <p>观测量：P_i = ρ_i + c*(dt_r - dt_s^i) + T_i + I_i + ε
 *
 * <p>残差维度 = 卫星数，参数块：stateVar[11]</p>
 *
 * <p>与 TcFactor 的区别：本因子仅建模伪距，不与伪距率/UWB 紧耦合，
 * 适用于标准 PPP/RTK 实验中需要独立调权或单独开关伪距观测的场景。</p>
 */
public class PseudorangeFactor extends Factor {

    private static final int STATE_DIM = 11;

    private final int numSat;
    private final double[] prObs;
    private final double[][] satPos;
    private final double[] satClkBias;
    private final double sigmaPr;

    private transient double[] cachedState;
    private transient SimpleMatrix cachedResidual;
    private transient SimpleMatrix[] cachedJacobians;

    public PseudorangeFactor(Variable stateVar,
                             double[] prObs, double[][] satPos, double[] satClkBias) {
        this(stateVar, prObs, satPos, satClkBias, 2.0);
    }

    public PseudorangeFactor(Variable stateVar,
                             double[] prObs, double[][] satPos, double[] satClkBias,
                             double sigmaPr) {
        super("PseudorangeFactor", Arrays.asList(stateVar));
        this.numSat = Math.min(prObs.length, satPos.length);
        this.prObs = prObs.clone();
        this.satPos = deepCopy2D(satPos);
        this.satClkBias = satClkBias.clone();
        this.sigmaPr = sigmaPr;
    }

    @Override
    public int residualDimension() {
        return numSat;
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        return Arrays.asList(STATE_DIM);
    }

    @Override
    public SimpleMatrix noiseCovariance() {
        double var = sigmaPr * sigmaPr;
        SimpleMatrix R = new SimpleMatrix(numSat, numSat);
        for (int i = 0; i < numSat; i++) R.set(i, i, var);
        return R;
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
    public double error() {
        SimpleMatrix r = residual();
        double sum = 0;
        for (int i = 0; i < r.getNumElements(); i++) {
            sum += r.get(i, 0) * r.get(i, 0);
        }
        return sum;
    }

    private void updateCaches() {
        double[] state = new double[STATE_DIM];
        if (connectedVariables.get(0).value != null) {
            for (int i = 0; i < Math.min(STATE_DIM, connectedVariables.get(0).dimension); i++) {
                state[i] = connectedVariables.get(0).value.get(i, 0);
            }
        }

        if (cachedState != null && Arrays.equals(state, cachedState)) {
            return;
        }
        cachedState = state.clone();

        double rx = state[0], ry = state[1], rz = state[2];
        double clkBias = state[9];

        cachedResidual = new SimpleMatrix(numSat, 1);
        cachedJacobians = new SimpleMatrix[1];
        cachedJacobians[0] = new SimpleMatrix(numSat, STATE_DIM);

        double info = 1.0 / sigmaPr;

        for (int i = 0; i < numSat; i++) {
            double dx = rx - satPos[i][0];
            double dy = ry - satPos[i][1];
            double dz = rz - satPos[i][2];
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (range < 1.0) range = 1.0;

            double ex = dx / range;
            double ey = dy / range;
            double ez = dz / range;

            double modeled = range + GnssConst.CLIGHT * clkBias
                           - GnssConst.CLIGHT * satClkBias[i];

            cachedResidual.set(i, 0, info * (prObs[i] - modeled));

            cachedJacobians[0].set(i, 0, info * (-ex));
            cachedJacobians[0].set(i, 1, info * (-ey));
            cachedJacobians[0].set(i, 2, info * (-ez));
            cachedJacobians[0].set(i, 9, info * (-GnssConst.CLIGHT));
        }
    }

    private static double[][] deepCopy2D(double[][] src) {
        double[][] dst = new double[src.length][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i].clone();
        }
        return dst;
    }
}