package org.rtklib.java.research.factorgraph;

import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.RotationUtils;

/**
 * IMU 预积分因子。
 *
 * <p>连接两个关键帧的状态节点（位姿+速度）和零偏节点。
 * 残差 = 15 维：r_ΔR(3) + r_Δv(3) + r_Δp(3) + r_Δb_a(3) + r_Δb_ω(3)</p>
 *
 * <p>连接变量 (5 个)：</p>
 * <pre>
 *   varIndex 0: state_k [r, v, a, tu, fu, R, ba, bg]  (20 dim)
 *   varIndex 1: state_{k+1}                              (20 dim)
 *   varIndex 2: bias_k  [ba, bg]                          (6 dim)
 *   varIndex 3: bias_{k+1}                                (6 dim)
 *   varIndex 4: delta (preintegration) [dR(9), dv(3), dp(3)] (15 dim)
 * </pre>
 *
 * <p>或者简化版本 (2 个变量)：</p>
 * <pre>
 *   varIndex 0: state_k   [r(3), v(3), R(9), ba(3), bg(3)]  (21 dim)
 *   varIndex 1: state_{k+1}                                    (21 dim)
 * </pre>
 */
public class PreintegFactor extends Factor {

    private final SimpleMatrix deltaR;
    private final SimpleMatrix deltaV;
    private final SimpleMatrix deltaP;
    private final SimpleMatrix JrDRbw;
    private final SimpleMatrix JrDVba;
    private final SimpleMatrix JrDVbw;
    private final SimpleMatrix JrDPba;
    private final SimpleMatrix JrDPbw;
    private final SimpleMatrix noiseCov;

    private final double[] gravity;

    private static final double GRAVITY = 9.81;

    public PreintegFactor(SimpleMatrix deltaR, SimpleMatrix deltaV, SimpleMatrix deltaP,
                          SimpleMatrix JrDRbw, SimpleMatrix JrDVba, SimpleMatrix JrDVbw,
                          SimpleMatrix JrDPba, SimpleMatrix JrDPbw,
                          SimpleMatrix noiseCov, double[] gravity,
                          Variable... variables) {
        super("PreintegFactor", Arrays.asList(variables));
        this.deltaR = deltaR;
        this.deltaV = deltaV;
        this.deltaP = deltaP;
        this.JrDRbw = JrDRbw;
        this.JrDVba = JrDVba;
        this.JrDVbw = JrDVbw;
        this.JrDPba = JrDPba;
        this.JrDPbw = JrDPbw;
        this.noiseCov = noiseCov;
        this.gravity = gravity != null ? gravity.clone() : new double[]{0, 0, GRAVITY};
    }

    @Override
    public SimpleMatrix residual() {
        double[] stateK = new double[21];
        double[] stateK1 = new double[21];

        if (connectedVariables.get(0).value != null) {
            int dim = connectedVariables.get(0).dimension;
            for (int i = 0; i < Math.min(21, dim); i++) {
                stateK[i] = connectedVariables.get(0).value.get(i, 0);
            }
        }
        if (connectedVariables.size() > 1 && connectedVariables.get(1).value != null) {
            int dim = connectedVariables.get(1).dimension;
            for (int i = 0; i < Math.min(21, dim); i++) {
                stateK1[i] = connectedVariables.get(1).value.get(i, 0);
            }
        }

        double[] rKw = {stateK[0], stateK[1], stateK[2]};
        double[] vKw = {stateK[3], stateK[4], stateK[5]};

        SimpleMatrix Rk = extractRotation(stateK, 6);

        double[] rK1w = {stateK1[0], stateK1[1], stateK1[2]};
        double[] vK1w = {stateK1[3], stateK1[4], stateK1[5]};
        SimpleMatrix Rk1 = extractRotation(stateK1, 6);

        SimpleMatrix RkTrans = Rk.transpose();

        SimpleMatrix dPred = RkTrans.mult(Rk1);
        SimpleMatrix dMeasT = deltaR.transpose();
        double[] deltaLog = RotationUtils.log(dMeasT.mult(dPred));

        SimpleMatrix rDR = new SimpleMatrix(3, 1);
        for (int i = 0; i < 3; i++) rDR.set(i, 0, deltaLog[i]);

        double[] drW = new double[3];
        for (int i = 0; i < 3; i++) {
            drW[i] = rK1w[i] - rKw[i];
        }

        double[] drInt = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                drInt[i] += RkTrans.get(i, j) * drW[j];
            }
        }

        double[] dV = new double[3];
        for (int i = 0; i < 3; i++) {
            dV[i] = vK1w[i] - vKw[i] - gravity[i] * 1.0;
        }

        double[] dvInt = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                dvInt[i] += RkTrans.get(i, j) * dV[j];
            }
        }

        SimpleMatrix r = new SimpleMatrix(15, 1);
        for (int i = 0; i < 3; i++) {
            r.set(i,     0, rDR.get(i, 0));
            r.set(3 + i, 0, dvInt[i] - deltaV.get(i, 0));
            r.set(6 + i, 0, drInt[i] - deltaP.get(i, 0));
        }

        double[] baK  = {stateK[15],  stateK[16],  stateK[17]};
        double[] bgK  = {stateK[18],  stateK[19],  stateK[20]};
        double[] baK1 = {stateK1[15], stateK1[16], stateK1[17]};
        double[] bgK1 = {stateK1[18], stateK1[19], stateK1[20]};

        for (int i = 0; i < 3; i++) {
            r.set(9  + i, 0, baK1[i] - baK[i]);
            r.set(12 + i, 0, bgK1[i] - bgK[i]);
        }

        return r;
    }

    @Override
    public SimpleMatrix jacobian(int varIndex) {
        return new SimpleMatrix(15, connectedVariables.get(varIndex).dimension);
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

    @Override
    public int residualDimension() {
        return 15;
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        return Arrays.asList(21, 21);
    }

    @Override
    public SimpleMatrix noiseCovariance() {
        if (noiseCov != null) return noiseCov;
        SimpleMatrix I = SimpleMatrix.identity(15);
        return I.scale(0.01);
    }

    private SimpleMatrix extractRotation(double[] state, int offset) {
        SimpleMatrix R = new SimpleMatrix(3, 3);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                R.set(i, j, state[offset + i * 3 + j]);
            }
        }
        return R;
    }
}