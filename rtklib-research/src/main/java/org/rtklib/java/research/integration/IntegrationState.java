package org.rtklib.java.research.integration;

import org.ejml.simple.SimpleMatrix;

/**
 * 组合导航积分状态（移植自FE-GUT types.h IntegrationState/IntegrationStateData）。
 *
 * <p>12维状态向量：[r(3), v(3), a(3), tu, fu, tdk]
 * <ul>
 *   <li>r: ECEF位置 (m)</li>
 *   <li>v: ECEF速度 (m/s)</li>
 *   <li>a: ECEF加速度 (m/s²)</li>
 *   <li>tu: GNSS接收机钟差 (m)</li>
 *   <li>fu: GNSS接收机钟漂 (m/s)</li>
 *   <li>tdk: UWB时间偏移 (s)</li>
 * </ul>
 */
public class IntegrationState {

    public static final int DIM_STATE = 12;

    public double time;
    public double[] xdata;
    public SimpleMatrix P;

    public IntegrationState() {
        this.time = 0.0;
        this.xdata = new double[DIM_STATE];
        this.P = SimpleMatrix.identity(DIM_STATE);
    }

    public IntegrationState(double time, double[] xdata, SimpleMatrix P) {
        this.time = time;
        this.xdata = xdata.clone();
        this.P = P.copy();
    }

    public IntegrationState copy() {
        return new IntegrationState(time, xdata, P);
    }

    public double[] position() {
        return new double[]{xdata[0], xdata[1], xdata[2]};
    }

    public double[] velocity() {
        return new double[]{xdata[3], xdata[4], xdata[5]};
    }

    public double[] acceleration() {
        return new double[]{xdata[6], xdata[7], xdata[8]};
    }

    public double clockBias() {
        return xdata[9];
    }

    public double clockDrift() {
        return xdata[10];
    }

    public double uwbTimeOffset() {
        return xdata[11];
    }

    public void setPosition(double x, double y, double z) {
        xdata[0] = x; xdata[1] = y; xdata[2] = z;
    }

    public void setVelocity(double vx, double vy, double vz) {
        xdata[3] = vx; xdata[4] = vy; xdata[5] = vz;
    }

    public void setAcceleration(double ax, double ay, double az) {
        xdata[6] = ax; xdata[7] = ay; xdata[8] = az;
    }

    public void setClockBias(double tu) {
        xdata[9] = tu;
    }

    public void setClockDrift(double fu) {
        xdata[10] = fu;
    }

    public void setUwbTimeOffset(double tdk) {
        xdata[11] = tdk;
    }

    @Override
    public String toString() {
        return String.format("State[t=%.3f, r=[%.3f,%.3f,%.3f], v=[%.3f,%.3f,%.3f], tu=%.3f, fu=%.3f, td=%.6f]",
                time, xdata[0], xdata[1], xdata[2], xdata[3], xdata[4], xdata[5], xdata[9], xdata[10], xdata[11]);
    }
}