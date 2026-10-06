package org.rtklib.java.research.factorgraph;

import java.util.Arrays;
import java.util.List;
import org.ejml.simple.SimpleMatrix;

/**
 * 紧耦合因子：GNSS伪距+伪距率+UWB联合观测因子（移植自FE-GUT tc_factor.h）。
 *
 * <p>残差维度 = 2*gnssNum + uwbNum（典型值16=12+4）。</p>
 * <p>参数块：state[11] = [r(3), v(3), a(3), tu, fu], tdk[1]</p>
 */
public class TcFactor extends Factor {

    private static final int STATE_DIM = 11;

    private final int gnssNum;
    private final int uwbNum;
    private final double[] pr;
    private final double[] prrate;
    private final double[][] satPos;
    private final double[] uwbRanges;
    private final double[][] uwbAnchorEcef;
    private final double stdPs;
    private final double stdPsrate;
    private final double stdUwb;

    private transient double[] cachedState;
    private transient double cachedTdk;
    private transient SimpleMatrix cachedResidual;
    private transient SimpleMatrix[] cachedJacobians;

    public TcFactor(double[] pr, double[] prrate, double[][] satPos,
                    double[] uwbRanges, double[][] uwbAnchorEcef,
                    Variable stateVar, Variable tdkVar) {
        this(pr, prrate, satPos, uwbRanges, uwbAnchorEcef,
             stateVar, tdkVar, 2.0, 0.1, 0.5);
    }

    public TcFactor(double[] pr, double[] prrate, double[][] satPos,
                    double[] uwbRanges, double[][] uwbAnchorEcef,
                    Variable stateVar, Variable tdkVar,
                    double stdPs, double stdPsrate, double stdUwb) {
        super("TcFactor", Arrays.asList(stateVar, tdkVar));
        this.gnssNum = Math.min(pr.length, satPos.length);
        this.uwbNum = Math.min(uwbRanges.length, uwbAnchorEcef.length);
        this.pr = pr.clone();
        this.prrate = prrate.clone();
        this.satPos = deepCopy2D(satPos);
        this.uwbRanges = uwbRanges.clone();
        this.uwbAnchorEcef = deepCopy2D(uwbAnchorEcef);
        this.stdPs = stdPs;
        this.stdPsrate = stdPsrate;
        this.stdUwb = stdUwb;
    }

    @Override
    public int residualDimension() {
        return 2 * gnssNum + uwbNum;
    }

    @Override
    public List<Integer> parameterBlockSizes() {
        return Arrays.asList(STATE_DIM, 1);
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
        int resDim = residualDimension();
        SimpleMatrix R = new SimpleMatrix(resDim, resDim);
        double psVar2 = stdPs * stdPs;
        double prrVar2 = stdPsrate * stdPsrate;
        double uwbVar2 = stdUwb * stdUwb;
        for (int i = 0; i < gnssNum; i++) {
            R.set(i, i, psVar2);
            R.set(gnssNum + i, gnssNum + i, prrVar2);
        }
        for (int i = 0; i < uwbNum; i++) {
            R.set(2 * gnssNum + i, 2 * gnssNum + i, uwbVar2);
        }
        return R;
    }

    @Override
    public double[] computeResiduals(List<double[]> paramBlocks) {
        int resDim = residualDimension();
        double[] result = new double[resDim];
        double[] state = paramBlocks.get(0);
        double tdk = paramBlocks.get(1)[0];

        double[] rm = new double[]{state[0], state[1], state[2]};
        double[] vm = new double[]{state[3], state[4], state[5]};
        double[] am = new double[]{state[6], state[7], state[8]};
        double tu = state[9];
        double fu = state[10];

        double infoPs = 1.0 / stdPs;
        double infoPrr = 1.0 / stdPsrate;
        double infoUwb = 1.0 / stdUwb;

        for (int i = 0; i < gnssNum; i++) {
            double dx = satPos[i][0] - rm[0];
            double dy = satPos[i][1] - rm[1];
            double dz = satPos[i][2] - rm[2];
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
            result[i] = infoPs * ((range + tu) - pr[i]);
        }

        double[] hgnVel = new double[gnssNum];
        for (int i = 0; i < gnssNum; i++) {
            double dx = satPos[i][0] - rm[0];
            double dy = satPos[i][1] - rm[1];
            double dz = satPos[i][2] - rm[2];
            double invR = 1.0 / Math.sqrt(Math.max(dx * dx + dy * dy + dz * dz, 1e-12));
            hgnVel[i] = (-dx * invR) * vm[0] + (-dy * invR) * vm[1] + (-dz * invR) * vm[2];
        }
        for (int i = 0; i < gnssNum; i++) {
            result[gnssNum + i] = infoPrr * ((hgnVel[i] + fu) - prrate[i]);
        }

        for (int i = 0; i < uwbNum; i++) {
            double drx = rm[0] - tdk * vm[0] - 0.5 * am[0] * tdk * tdk;
            double dry = rm[1] - tdk * vm[1] - 0.5 * am[1] * tdk * tdk;
            double drz = rm[2] - tdk * vm[2] - 0.5 * am[2] * tdk * tdk;
            double dx = drx - uwbAnchorEcef[i][0];
            double dy = dry - uwbAnchorEcef[i][1];
            double dz = drz - uwbAnchorEcef[i][2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            result[2 * gnssNum + i] = infoUwb * (dist - uwbRanges[i]);
        }

        return result;
    }

    @Override
    public SimpleMatrix[] computeJacobians(List<double[]> paramBlocks) {
        int resDim = residualDimension();
        double[] state = paramBlocks.get(0);
        double tdk = paramBlocks.get(1)[0];

        double[] rm = new double[]{state[0], state[1], state[2]};
        double[] vm = new double[]{state[3], state[4], state[5]};
        double[] am = new double[]{state[6], state[7], state[8]};

        double infoPs = 1.0 / stdPs;
        double infoPrr = 1.0 / stdPsrate;
        double infoUwb = 1.0 / stdUwb;

        SimpleMatrix jState = new SimpleMatrix(resDim, STATE_DIM);
        SimpleMatrix jTdk = new SimpleMatrix(resDim, 1);

        for (int i = 0; i < gnssNum; i++) {
            double dx = satPos[i][0] - rm[0];
            double dy = satPos[i][1] - rm[1];
            double dz = satPos[i][2] - rm[2];
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double invR = 1.0 / Math.max(range, 1e-12);
            double e1 = -dx * invR;
            double e2 = -dy * invR;
            double e3 = -dz * invR;

            jState.set(i, 0, infoPs * e1);
            jState.set(i, 1, infoPs * e2);
            jState.set(i, 2, infoPs * e3);
            jState.set(i, 9, infoPs);

            jState.set(gnssNum + i, 3, infoPrr * e1);
            jState.set(gnssNum + i, 4, infoPrr * e2);
            jState.set(gnssNum + i, 5, infoPrr * e3);
            jState.set(gnssNum + i, 10, infoPrr);
        }

        for (int i = 0; i < uwbNum; i++) {
            double drx = rm[0] - tdk * vm[0] - 0.5 * am[0] * tdk * tdk;
            double dry = rm[1] - tdk * vm[1] - 0.5 * am[1] * tdk * tdk;
            double drz = rm[2] - tdk * vm[2] - 0.5 * am[2] * tdk * tdk;
            double dx = drx - uwbAnchorEcef[i][0];
            double dy = dry - uwbAnchorEcef[i][1];
            double dz = drz - uwbAnchorEcef[i][2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double invDist = 1.0 / Math.max(dist, 1e-12);
            double e1 = -dx * invDist;
            double e2 = -dy * invDist;
            double e3 = -dz * invDist;

            int r = 2 * gnssNum + i;
            jState.set(r, 0, infoUwb * e1);
            jState.set(r, 1, infoUwb * e2);
            jState.set(r, 2, infoUwb * e3);
            jState.set(r, 3, infoUwb * (-tdk * e1));
            jState.set(r, 4, infoUwb * (-tdk * e2));
            jState.set(r, 5, infoUwb * (-tdk * e3));
            jState.set(r, 6, infoUwb * (-0.5 * tdk * tdk * e1));
            jState.set(r, 7, infoUwb * (-0.5 * tdk * tdk * e2));
            jState.set(r, 8, infoUwb * (-0.5 * tdk * tdk * e3));

            double vu1 = vm[0] + am[0] * tdk;
            double vu2 = vm[1] + am[1] * tdk;
            double vu3 = vm[2] + am[2] * tdk;
            jTdk.set(r, 0, infoUwb * (e1 * vu1 + e2 * vu2 + e3 * vu3));
        }

        return new SimpleMatrix[]{jState, jTdk};
    }

    private void updateCaches() {
        double[] state = new double[]{0,0,0,0,0,0,0,0,0,0,0};
        if (connectedVariables.get(0).value != null) {
            for (int i = 0; i < Math.min(STATE_DIM, connectedVariables.get(0).dimension); i++) {
                state[i] = connectedVariables.get(0).value.get(i, 0);
            }
        }
        double tdk = 0;
        if (connectedVariables.get(1).value != null && connectedVariables.get(1).value.getNumElements() > 0) {
            tdk = connectedVariables.get(1).value.get(0, 0);
        }

        if (cachedState != null && Arrays.equals(cachedState, state) && cachedTdk == tdk) {
            return;
        }

        List<double[]> pb = Arrays.asList(state, new double[]{tdk});
        cachedResidual = new SimpleMatrix(residualDimension(), 1);
        double[] res = computeResiduals(pb);
        for (int i = 0; i < res.length; i++) {
            cachedResidual.set(i, 0, res[i]);
        }
        cachedJacobians = computeJacobians(pb);
        cachedState = state;
        cachedTdk = tdk;
    }

    private static double[][] deepCopy2D(double[][] src) {
        double[][] dst = new double[src.length][];
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i].clone();
        }
        return dst;
    }
}