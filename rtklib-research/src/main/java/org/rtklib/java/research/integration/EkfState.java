package org.rtklib.java.research.integration;

import org.ejml.simple.SimpleMatrix;

/**
 * EKF 状态估计器（移植自FE-GUT ekfstate.cc/.h）。
 *
 * <p>执行 EKF 预测+量测更新循环：
 * <ol>
 *   <li>Predict: x = F·x, P = F·P·Fᵀ + Q</li>
 *   <li>GNSS Update: 伪距+伪距率观测 → 量测残差 → 卡尔曼增益 → 后验</li>
 *   <li>UWB Update: UWB测距 → 量测残差 → 卡尔曼增益 → 后验</li>
 * </ol>
 *
 * <p>状态向量(12维): [r(3), v(3), a(3), tu, fu, tdk]</p>
 */
public class EkfState {

    private final int gnssNum;
    private final int uwbNum;
    private final int yNum;
    private final double stdPs;
    private final double stdPsrate;
    private final double stdUwb;

    private double dt;
    private IntegrationState preState;
    private IntegrationState curState;

    private SimpleMatrix x;
    private SimpleMatrix P;

    private SimpleMatrix F;
    private SimpleMatrix Q;
    private SimpleMatrix R;
    private SimpleMatrix RUwb;

    private SimpleMatrix H;
    private SimpleMatrix meaVector;
    private SimpleMatrix meaPredict;

    public EkfState(IntegrationState initState) {
        this(initState, 6, 4, 0.005, 2.0, 0.1, 0.5);
    }

    public EkfState(IntegrationState initState, int gnssNum, int uwbNum,
                    double dt, double stdPs, double stdPsrate, double stdUwb) {
        this.gnssNum = gnssNum;
        this.uwbNum = uwbNum;
        this.yNum = 2 * gnssNum + uwbNum;
        this.dt = dt;
        this.stdPs = stdPs;
        this.stdPsrate = stdPsrate;
        this.stdUwb = stdUwb;

        this.preState = initState.copy();
        this.curState = new IntegrationState();

        x = new SimpleMatrix(IntegrationState.DIM_STATE, 1);
        P = new SimpleMatrix(IntegrationState.DIM_STATE, IntegrationState.DIM_STATE);
        H = new SimpleMatrix(yNum, IntegrationState.DIM_STATE);
        meaVector = new SimpleMatrix(yNum, 1);
        meaPredict = new SimpleMatrix(yNum, 1);

        buildMatrices();
        initX();
    }

    private void buildMatrices() {
        double dt2 = dt * dt;

        F = new SimpleMatrix(IntegrationState.DIM_STATE, IntegrationState.DIM_STATE);
        for (int i = 0; i < IntegrationState.DIM_STATE; i++) F.set(i, i, 1);
        F.set(0, 3, dt); F.set(1, 4, dt); F.set(2, 5, dt);
        F.set(0, 6, 0.5 * dt2); F.set(1, 7, 0.5 * dt2); F.set(2, 8, 0.5 * dt2);
        F.set(3, 6, dt); F.set(4, 7, dt); F.set(5, 8, dt);
        F.set(9, 10, dt);

        double Sj = 0.4, St = 36, Sf = 0.01, Sdt = 0.01;

        Q = new SimpleMatrix(IntegrationState.DIM_STATE, IntegrationState.DIM_STATE);
        Q.set(0, 0, 1.0 / 20 * Sj * Math.pow(dt, 5));
        Q.set(1, 1, 1.0 / 20 * Sj * Math.pow(dt, 5));
        Q.set(2, 2, 1.0 / 20 * Sj * Math.pow(dt, 5));
        Q.set(0, 3, 1.0 / 8 * Sj * Math.pow(dt, 4)); Q.set(3, 0, 1.0 / 8 * Sj * Math.pow(dt, 4));
        Q.set(1, 4, 1.0 / 8 * Sj * Math.pow(dt, 4)); Q.set(4, 1, 1.0 / 8 * Sj * Math.pow(dt, 4));
        Q.set(2, 5, 1.0 / 8 * Sj * Math.pow(dt, 4)); Q.set(5, 2, 1.0 / 8 * Sj * Math.pow(dt, 4));
        Q.set(0, 6, 1.0 / 6 * Sj * Math.pow(dt, 3)); Q.set(6, 0, 1.0 / 6 * Sj * Math.pow(dt, 3));
        Q.set(1, 7, 1.0 / 6 * Sj * Math.pow(dt, 3)); Q.set(7, 1, 1.0 / 6 * Sj * Math.pow(dt, 3));
        Q.set(2, 8, 1.0 / 6 * Sj * Math.pow(dt, 3)); Q.set(8, 2, 1.0 / 6 * Sj * Math.pow(dt, 3));
        Q.set(3, 3, 1.0 / 3 * Sj * Math.pow(dt, 3));
        Q.set(4, 4, 1.0 / 3 * Sj * Math.pow(dt, 3));
        Q.set(5, 5, 1.0 / 3 * Sj * Math.pow(dt, 3));
        Q.set(3, 6, 1.0 / 2 * Sj * dt2); Q.set(6, 3, 1.0 / 2 * Sj * dt2);
        Q.set(4, 7, 1.0 / 2 * Sj * dt2); Q.set(7, 4, 1.0 / 2 * Sj * dt2);
        Q.set(5, 8, 1.0 / 2 * Sj * dt2); Q.set(8, 5, 1.0 / 2 * Sj * dt2);
        Q.set(6, 6, Sj * dt); Q.set(7, 7, Sj * dt); Q.set(8, 8, Sj * dt);
        Q.set(9, 9, St * dt + 1.0 / 3 * Sf * Math.pow(dt, 3));
        Q.set(9, 10, 1.0 / 2 * Sf * dt2); Q.set(10, 9, 1.0 / 2 * Sf * dt2);
        Q.set(10, 10, Sf * dt);
        Q.set(11, 11, Sdt * dt);

        double psVar = stdPs * stdPs;
        double psrateVar = stdPsrate * stdPsrate;
        double uwbVar = stdUwb * stdUwb;

        R = new SimpleMatrix(yNum, yNum);
        for (int i = 0; i < gnssNum; i++) {
            R.set(i, i, psVar);
            R.set(gnssNum + i, gnssNum + i, psrateVar);
        }
        for (int i = 0; i < uwbNum; i++) {
            R.set(2 * gnssNum + i, 2 * gnssNum + i, uwbVar);
        }

        RUwb = new SimpleMatrix(uwbNum, uwbNum);
        for (int i = 0; i < uwbNum; i++) RUwb.set(i, i, uwbVar);
    }

    private void initX() {
        for (int i = 0; i < IntegrationState.DIM_STATE; i++) {
            x.set(i, 0, preState.xdata[i]);
        }
        P = preState.P.copy();
    }

    /**
     * 执行完整EKF更新（预测+GNSS量测+UWB量测）。
     *
     * @param gnssPr         伪距观测 [gnssNum] (m)
     * @param gnssPrrate     伪距率观测 [gnssNum] (m/s)
     * @param gnssSatPos     卫星ECEF位置 [gnssNum][3] (m)
     * @param gnssSatVel     卫星ECEF速度 [gnssNum][3] (m/s)
     * @param uwbRanges      UWB测距 [uwbNum] (m)
     * @param uwbAnchorPos   UWB锚点ECEF位置 [uwbNum][3] (m)
     * @return 更新后的状态
     */
    public IntegrationState ekfUpdate(double[] gnssPr, double[] gnssPrrate,
                                      double[][] gnssSatPos, double[][] gnssSatVel,
                                      double[] uwbRanges, double[][] uwbAnchorPos) {
        predict();

        buildGnssMeasurements(gnssPr, gnssPrrate);
        measurePredict(gnssSatPos, gnssSatVel, uwbRanges, uwbAnchorPos);

        SimpleMatrix I = SimpleMatrix.identity(IntegrationState.DIM_STATE);
        SimpleMatrix S = H.mult(P).mult(H.transpose()).plus(R);
        SimpleMatrix K = P.mult(H.transpose()).mult(S.invert());
        SimpleMatrix residual = meaVector.minus(meaPredict);
        x = x.plus(K.mult(residual));
        P = I.minus(K.mult(H)).mult(P);

        curState.time = preState.time + dt;
        for (int i = 0; i < IntegrationState.DIM_STATE; i++) {
            curState.xdata[i] = x.get(i, 0);
        }
        curState.P = P.copy();
        preState = curState.copy();

        return preState;
    }

    /**
     * 仅执行UWB量测更新（无GNSS观测时）。
     *
     * @param uwbRanges     UWB测距 [uwbNum] (m)
     * @param uwbAnchorPos  UWB锚点ECEF位置 [uwbNum][3] (m)
     * @return 更新后的状态
     */
    public IntegrationState ekfUwbUpdate(double[] uwbRanges, double[][] uwbAnchorPos) {
        predict();

        SimpleMatrix meaUwb = new SimpleMatrix(uwbNum, 1);
        for (int i = 0; i < uwbNum; i++) meaUwb.set(i, 0, uwbRanges[i]);

        SimpleMatrix HUwb = new SimpleMatrix(uwbNum, IntegrationState.DIM_STATE);
        SimpleMatrix uwbPredict = new SimpleMatrix(uwbNum, 1);

        double[] r = position();
        double[] v = velocity();
        double[] a = acceleration();
        double tu = x.get(9, 0);
        double fu = x.get(10, 0);
        double tdk = x.get(11, 0);

        for (int i = 0; i < uwbNum; i++) {
            double drx = r[0] - tdk * v[0] - 0.5 * a[0] * tdk * tdk;
            double dry = r[1] - tdk * v[1] - 0.5 * a[1] * tdk * tdk;
            double drz = r[2] - tdk * v[2] - 0.5 * a[2] * tdk * tdk;
            double dx = drx - uwbAnchorPos[i][0];
            double dy = dry - uwbAnchorPos[i][1];
            double dz = drz - uwbAnchorPos[i][2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double invDist = 1.0 / Math.max(dist, 1e-12);

            double e1 = -dx * invDist;
            double e2 = -dy * invDist;
            double e3 = -dz * invDist;

            HUwb.set(i, 0, e1); HUwb.set(i, 1, e2); HUwb.set(i, 2, e3);
            HUwb.set(i, 3, -tdk * e1); HUwb.set(i, 4, -tdk * e2); HUwb.set(i, 5, -tdk * e3);
            HUwb.set(i, 6, -0.5 * tdk * tdk * e1);
            HUwb.set(i, 7, -0.5 * tdk * tdk * e2);
            HUwb.set(i, 8, -0.5 * tdk * tdk * e3);

            uwbPredict.set(i, 0, dist);
        }

        SimpleMatrix I = SimpleMatrix.identity(IntegrationState.DIM_STATE);
        SimpleMatrix S = HUwb.mult(P).mult(HUwb.transpose()).plus(RUwb);
        SimpleMatrix K = P.mult(HUwb.transpose()).mult(S.invert());
        SimpleMatrix residual = meaUwb.minus(uwbPredict);
        x = x.plus(K.mult(residual));
        P = I.minus(K.mult(HUwb)).mult(P);

        curState.time = preState.time + dt;
        for (int i = 0; i < IntegrationState.DIM_STATE; i++) {
            curState.xdata[i] = x.get(i, 0);
        }
        curState.P = P.copy();
        preState = curState.copy();

        return preState;
    }

    private void predict() {
        for (int i = 0; i < IntegrationState.DIM_STATE; i++) {
            x.set(i, 0, preState.xdata[i]);
        }
        P = preState.P.copy();

        x = F.mult(x);
        P = F.mult(P).mult(F.transpose()).plus(Q);
    }

    private void buildGnssMeasurements(double[] gnssPr, double[] gnssPrrate) {
        meaVector.zero();
        for (int i = 0; i < gnssNum && i < gnssPr.length; i++) {
            meaVector.set(i, 0, gnssPr[i]);
        }
        for (int i = 0; i < gnssNum && i < gnssPrrate.length; i++) {
            meaVector.set(gnssNum + i, 0, gnssPrrate[i]);
        }
    }

    private void measurePredict(double[][] satPos, double[][] satVel,
                                 double[] uwbRanges, double[][] uwbAnchorPos) {
        meaPredict.zero();
        H.zero();

        double[] r = position();
        double[] v = velocity();
        double[] a = acceleration();
        double tu = x.get(9, 0);
        double fu = x.get(10, 0);
        double tdk = x.get(11, 0);

        for (int i = 0; i < gnssNum && i < satPos.length; i++) {
            double dx = satPos[i][0] - r[0];
            double dy = satPos[i][1] - r[1];
            double dz = satPos[i][2] - r[2];
            double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double invR = 1.0 / Math.max(range, 1e-12);

            double e1 = -dx * invR;
            double e2 = -dy * invR;
            double e3 = -dz * invR;

            H.set(i, 0, e1); H.set(i, 1, e2); H.set(i, 2, e3);
            H.set(i, 9, 1);
            meaPredict.set(i, 0, range + tu);

            double vg = e1 * v[0] + e2 * v[1] + e3 * v[2];
            H.set(gnssNum + i, 3, e1);
            H.set(gnssNum + i, 4, e2);
            H.set(gnssNum + i, 5, e3);
            H.set(gnssNum + i, 10, 1);
            meaPredict.set(gnssNum + i, 0, vg + fu);
        }

        for (int i = 0; i < uwbNum && i < uwbAnchorPos.length; i++) {
            double drx = r[0] - tdk * v[0] - 0.5 * a[0] * tdk * tdk;
            double dry = r[1] - tdk * v[1] - 0.5 * a[1] * tdk * tdk;
            double drz = r[2] - tdk * v[2] - 0.5 * a[2] * tdk * tdk;
            double dx = drx - uwbAnchorPos[i][0];
            double dy = dry - uwbAnchorPos[i][1];
            double dz = drz - uwbAnchorPos[i][2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double invDist = 1.0 / Math.max(dist, 1e-12);

            double e1 = -dx * invDist;
            double e2 = -dy * invDist;
            double e3 = -dz * invDist;

            int row = 2 * gnssNum + i;
            H.set(row, 0, e1); H.set(row, 1, e2); H.set(row, 2, e3);
            H.set(row, 3, -tdk * e1); H.set(row, 4, -tdk * e2); H.set(row, 5, -tdk * e3);
            H.set(row, 6, -0.5 * tdk * tdk * e1);
            H.set(row, 7, -0.5 * tdk * tdk * e2);
            H.set(row, 8, -0.5 * tdk * tdk * e3);

            meaPredict.set(row, 0, dist);
        }
    }

    public double[] position() {
        return new double[]{x.get(0, 0), x.get(1, 0), x.get(2, 0)};
    }

    public double[] velocity() {
        return new double[]{x.get(3, 0), x.get(4, 0), x.get(5, 0)};
    }

    public double[] acceleration() {
        return new double[]{x.get(6, 0), x.get(7, 0), x.get(8, 0)};
    }

    public IntegrationState currentState() {
        return preState.copy();
    }

    public void reset(IntegrationState state, double newDt) {
        this.preState = state.copy();
        this.dt = newDt;
        buildMatrices();
        initX();
    }

    public void setDt(double dt) {
        this.dt = dt;
        buildMatrices();
    }

    public void setNoiseMatrices(SimpleMatrix Qext, SimpleMatrix Rext, SimpleMatrix RUwbExt) {
        if (Qext != null) this.Q = Qext;
        if (Rext != null) this.R = Rext;
        if (RUwbExt != null) this.RUwb = RUwbExt;
    }
}