package org.rtklib.java.research.factorgraph;

import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.GnssConst;

/**
 * 多普勒/伪距率观测因子。
 *
 * <p>观测量：D_i = -λ_i · f_d = ρ̇_i + c·(ṫ_r - ṫ_s^i) + ε
 *
 * <p>残差维度 = 卫星数，参数块：stateVar[11]</p>
 *
 * <p>是 GNSS/INS 紧耦合 FGO (3.1) 的前置条件——IMU 预积分需要
 * 速度约束，本因子提供来自 GNSS 多普勒的速度观测。</p>
 */
public class DopplerFactor extends Factor {

    private static final int STATE_DIM = 11;

    private final int numSat;
    private final double[] prRateObs;
    private final double[][] satPos;
    private final double[] satVel;
    private final double[] satClkDrift;
    private final double sigmaDop;

    private transient double[] cachedState;
    private transient SimpleMatrix cachedResidual;
    private transient SimpleMatrix[] cachedJacobians;

    public DopplerFactor(Variable stateVar,
                         double[] prRateObs, double[][] satPos,
                         double[] satVel, double[] satClkDrift) {
        this(stateVar, prRateObs, satPos, satVel, satClkDrift, 0.1);
    }

    public DopplerFactor(Variable stateVar,
                         double[] prRateObs, double[][] satPos,
                         double[] satVel, double[] satClkDrift,
                         double sigmaDop) {
        super("DopplerFactor", Arrays.asList(stateVar));
        this.numSat = Math.min(prRateObs.length, satPos.length);
        this.prRateObs = prRateObs.clone();
        this.satPos = deepCopy2D(satPos);
        this.satVel = satVel != null ? satVel.clone() : new double[3 * numSat];
        this.satClkDrift = satClkDrift != null ? satClkDrift.clone() : new double[numSat];
        this.sigmaDop = sigmaDop;
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
        double var = sigmaDop * sigmaDop;
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
        double vx = state[3], vy = state[4], vz = state[5];
        double clkDrift = state[10];

        double info = 1.0 / sigmaDop;

        cachedResidual = new SimpleMatrix(numSat, 1);
        cachedJacobians = new SimpleMatrix[1];
        cachedJacobians[0] = new SimpleMatrix(numSat, STATE_DIM);

        for (int i = 0; i < numSat; i++) {
            double dx = rx - satPos[i][0];
            double dy = ry - satPos[i][1];
            double dz = rz - satPos[i][2];
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (range < 1.0) range = 1.0;

            double ex = dx / range;
            double ey = dy / range;
            double ez = dz / range;

            double svx = (satVel.length > 3 * i + 2) ? satVel[3 * i] : 0;
            double svy = (satVel.length > 3 * i + 2) ? satVel[3 * i + 1] : 0;
            double svz = (satVel.length > 3 * i + 2) ? satVel[3 * i + 2] : 0;

            double relVx = vx - svx;
            double relVy = vy - svy;
            double relVz = vz - svz;

            double rangeRate = ex * relVx + ey * relVy + ez * relVz;

            double modeled = rangeRate + GnssConst.CLIGHT * clkDrift
                           - GnssConst.CLIGHT * satClkDrift[i];

            cachedResidual.set(i, 0, info * (prRateObs[i] - modeled));

            cachedJacobians[0].set(i, 3, info * ex);
            cachedJacobians[0].set(i, 4, info * ey);
            cachedJacobians[0].set(i, 5, info * ez);
            cachedJacobians[0].set(i, 10, info * GnssConst.CLIGHT);
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