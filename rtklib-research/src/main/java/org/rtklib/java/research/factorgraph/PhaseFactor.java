package org.rtklib.java.research.factorgraph;

import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.GnssConst;

/**
 * 载波相位观测因子（FGO 核心因子之一）。
 *
 * <p>观测量：L_i * λ_i = ρ_i + c*(t_r - t_s^i) + λ_i*N_i + T_i - I_i + ε
 *
 * <p>残差维度 = 卫星数，参数块：stateVar[11] + ambVar[nSat]</p>
 *
 * <p>策略 A：FGO 出浮点解 → LAMBDA 固定。此因子不强制整约束，
 * 浮点模糊度保存在 ambVar 中，优化后由 LambdaSolver 固定。</p>
 */
public class PhaseFactor extends Factor {

    private static final int STATE_DIM = 11;

    private final int numSat;
    private final double[] phaseObs;
    private final double[] wavelengths;
    private final double[][] satPos;
    private final double[] satClkBias;
    private final double sigmaPhase;

    private transient double[] cachedState;
    private transient double[] cachedAmb;
    private transient SimpleMatrix cachedResidual;
    private transient SimpleMatrix[] cachedJacobians;

    public PhaseFactor(Variable stateVar, Variable ambVar,
                       double[] phaseObs, double[] wavelengths,
                       double[][] satPos, double[] satClkBias) {
        this(stateVar, ambVar, phaseObs, wavelengths, satPos, satClkBias, 0.003);
    }

    public PhaseFactor(Variable stateVar, Variable ambVar,
                       double[] phaseObs, double[] wavelengths,
                       double[][] satPos, double[] satClkBias,
                       double sigmaPhase) {
        super("PhaseFactor", Arrays.asList(stateVar, ambVar));
        this.numSat = Math.min(phaseObs.length, Math.min(wavelengths.length, satPos.length));
        this.phaseObs = phaseObs.clone();
        this.wavelengths = wavelengths.clone();
        this.satPos = deepCopy2D(satPos);
        this.satClkBias = satClkBias.clone();
        this.sigmaPhase = sigmaPhase;
    }

    @Override
    public int residualDimension() {
        return numSat;
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        return Arrays.asList(STATE_DIM, numSat);
    }

    @Override
    public SimpleMatrix noiseCovariance() {
        double var = sigmaPhase * sigmaPhase;
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
        int n = Math.min(numSat, connectedVariables.get(1).dimension);
        double[] amb = new double[n];
        if (connectedVariables.get(1).value != null) {
            for (int i = 0; i < n; i++) {
                amb[i] = connectedVariables.get(1).value.get(i, 0);
            }
        }

        if (cachedState != null && cachedAmb != null &&
            Arrays.equals(state, cachedState) && Arrays.equals(amb, cachedAmb)) {
            return;
        }

        cachedState = state.clone();
        cachedAmb = amb.clone();

        double rx = state[0], ry = state[1], rz = state[2];
        double clkBias = state[9];

        cachedResidual = new SimpleMatrix(numSat, 1);
        cachedJacobians = new SimpleMatrix[2];
        cachedJacobians[0] = new SimpleMatrix(numSat, STATE_DIM);
        cachedJacobians[1] = new SimpleMatrix(numSat, numSat);

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
                           - GnssConst.CLIGHT * satClkBias[i]
                           + wavelengths[i] * amb[i];

            cachedResidual.set(i, 0, phaseObs[i] * wavelengths[i] - modeled);

            cachedJacobians[0].set(i, 0, -ex);
            cachedJacobians[0].set(i, 1, -ey);
            cachedJacobians[0].set(i, 2, -ez);
            cachedJacobians[0].set(i, 9, -GnssConst.CLIGHT);

            cachedJacobians[1].set(i, i, -wavelengths[i]);
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