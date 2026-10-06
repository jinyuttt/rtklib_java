package org.rtklib.java.research.integration;

import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.data.ImuData;
import org.rtklib.java.research.data.ImuBias;

/**
 * IMU 预积分结果。
 *
 * <p>在时间区间 [t_k, t_{k+1}] 内对 IMU 测量进行预积分，
 * 得到 ΔR、Δv、Δp 及对应的 bias 雅可比和噪声协方差。</p>
 *
 * <p>参考 Forster et al., "On-Manifold Preintegration for Real-Time
 * Visual-Inertial Odometry", IEEE TRO 2017。</p>
 */
public class PreintegrationResult {

    /** 预积分旋转增量 ΔR ∈ SO(3) (3×3) */
    public SimpleMatrix deltaR;

    /** 预积分速度增量 Δv (3×1) in body frame */
    public SimpleMatrix deltaV;

    /** 预积分位置增量 Δp (3×1) in body frame */
    public SimpleMatrix deltaP;

    /** Jacobian of ΔR w.r.t. gyro bias (3×3) */
    public SimpleMatrix jacobianDRbw;

    /** Jacobian of Δv w.r.t. accel bias (3×3) */
    public SimpleMatrix jacobianDVba;

    /** Jacobian of Δv w.r.t. gyro bias (3×3) */
    public SimpleMatrix jacobianDVbw;

    /** Jacobian of Δp w.r.t. accel bias (3×3) */
    public SimpleMatrix jacobianDPba;

    /** Jacobian of Δp w.r.t. gyro bias (3×3) */
    public SimpleMatrix jacobianDPbw;

    /** 预积分噪声协方差 (15×15: R, v, p, ba, bg) */
    public SimpleMatrix covariance;

    /** 积分起始时间 (s) */
    public double startTime;

    /** 积分结束时间 (s) */
    public double endTime;

    /** 总积分时间 Δt = endTime - startTime (s) */
    public double totalDt;

    private static final double GRAVITY = 9.81;

    public PreintegrationResult() {
        this.deltaR = SimpleMatrix.identity(3);
        this.deltaV = new SimpleMatrix(3, 1);
        this.deltaP = new SimpleMatrix(3, 1);
        this.jacobianDRbw = new SimpleMatrix(3, 3);
        this.jacobianDVba = new SimpleMatrix(3, 3);
        this.jacobianDVbw = new SimpleMatrix(3, 3);
        this.jacobianDPba = new SimpleMatrix(3, 3);
        this.jacobianDPbw = new SimpleMatrix(3, 3);
        this.covariance = new SimpleMatrix(15, 15);
    }

    /**
     * 预积分核心算法。
     *
     * <p>对 IMU buffer 逐帧积分，累积 ΔR/Δv/Δp 及 bias 雅可比。</p>
     *
     * @param imuBuffer IMU 数据列表（按时间排序）
     * @param bias      初始零偏估计
     * @param accelNoise 加速度计噪声密度 (m/s²/√Hz)
     * @param gyroNoise  陀螺仪噪声密度 (rad/s/√Hz)
     * @param accelBiasNoise 加速度计零偏随机游走 (m/s³/√Hz)
     * @param gyroBiasNoise  陀螺仪零偏随机游走 (rad/s²/√Hz)
     * @param gravity    重力矢量 (world frame, 向下为正)
     * @return 预积分结果
     */
    public static PreintegrationResult integrate(
            List<ImuData> imuBuffer, ImuBias bias,
            double accelNoise, double gyroNoise,
            double accelBiasNoise, double gyroBiasNoise,
            double[] gravity) {

        PreintegrationResult result = new PreintegrationResult();

        if (imuBuffer.isEmpty()) return result;

        result.startTime = imuBuffer.get(0).time.toSeconds();
        result.endTime = imuBuffer.get(imuBuffer.size() - 1).time.toSeconds();
        result.totalDt = result.endTime - result.startTime;

        if (result.totalDt <= 0) return result;

        SimpleMatrix dR = SimpleMatrix.identity(3);
        SimpleMatrix dV = new SimpleMatrix(3, 1);
        SimpleMatrix dP = new SimpleMatrix(3, 1);

        SimpleMatrix JrDRbw = new SimpleMatrix(3, 3);
        SimpleMatrix JrDVba = new SimpleMatrix(3, 3);
        SimpleMatrix JrDVbw = new SimpleMatrix(3, 3);
        SimpleMatrix JrDPba = new SimpleMatrix(3, 3);
        SimpleMatrix JrDPbw = new SimpleMatrix(3, 3);

        SimpleMatrix cov = new SimpleMatrix(15, 15);

        double[] grav = gravity != null ? gravity.clone() : new double[]{0, 0, GRAVITY};

        for (int idx = 0; idx < imuBuffer.size() - 1; idx++) {
            ImuData imu0 = imuBuffer.get(idx);
            ImuData imu1 = imuBuffer.get(idx + 1);

            double dt = imu1.time.toSeconds() - imu0.time.toSeconds();
            if (dt <= 0 || dt > 0.1) continue;

            double[] wMid = new double[3];
            double[] aMid = new double[3];
            for (int i = 0; i < 3; i++) {
                wMid[i] = 0.5 * (imu0.gyro[i] + imu1.gyro[i]) - bias.gyroBias[i];
                aMid[i] = 0.5 * (imu0.accel[i] + imu1.accel[i]) - bias.accelBias[i];
            }

            double[] wDt = {wMid[0] * dt, wMid[1] * dt, wMid[2] * dt};

            SimpleMatrix dRNext = dR.mult(org.rtklib.java.research.common.RotationUtils.exp(wDt));

            double[] aWorld = new double[3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    aWorld[i] += dR.get(i, j) * aMid[j];
                }
            }

            SimpleMatrix dVNext = new SimpleMatrix(3, 1);
            SimpleMatrix dPNext = new SimpleMatrix(3, 1);
            for (int i = 0; i < 3; i++) {
                dVNext.set(i, 0, dV.get(i, 0) + aWorld[i] * dt);
                dPNext.set(i, 0, dP.get(i, 0) + dV.get(i, 0) * dt + 0.5 * aWorld[i] * dt * dt);
            }

            SimpleMatrix JrInv = org.rtklib.java.research.common.RotationUtils.rightJacobianInverse(wDt);
            SimpleMatrix dRTrans = dR.transpose();
            SimpleMatrix skewA = org.rtklib.java.research.common.RotationUtils.skew(aMid);

            JrDRbw = JrInv.mult(dRTrans).mult(JrDRbw);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    JrDRbw.set(i, j, JrDRbw.get(i, j) - JrInv.get(i, j) * dt);
                }
            }

            JrDVba = JrDVba.plus(dR.mult(SimpleMatrix.identity(3).scale(dt)).scale(-1));

            JrDVbw = JrDVbw.plus(skewA.mult(JrDRbw).scale(dt));

            JrDPba = JrDPba.plus(JrDVba.scale(dt)).minus(
                dR.mult(SimpleMatrix.identity(3).scale(0.5 * dt * dt)));

            JrDPbw = JrDPbw.plus(JrDVbw.scale(dt)).plus(
                skewA.mult(JrDRbw).scale(0.5 * dt * dt));

            SimpleMatrix A = computeTransition(dR, aMid, dt, wDt);
            SimpleMatrix B = computeNoiseJacobian(dR, dt);
            SimpleMatrix Q = computeNoiseCovariance(accelNoise, gyroNoise,
                                                    accelBiasNoise, gyroBiasNoise, dt);

            cov = A.mult(cov).mult(A.transpose()).plus(B.mult(Q).mult(B.transpose()));

            dR = dRNext;
            dV = dVNext;
            dP = dPNext;
        }

        result.deltaR = dR;
        result.deltaV = dV;
        result.deltaP = dP;
        result.jacobianDRbw = JrDRbw;
        result.jacobianDVba = JrDVba;
        result.jacobianDVbw = JrDVbw;
        result.jacobianDPba = JrDPba;
        result.jacobianDPbw = JrDPbw;
        result.covariance = cov;

        return result;
    }

    private static SimpleMatrix computeTransition(SimpleMatrix R, double[] a, double dt, double[] wDt) {
        SimpleMatrix F = SimpleMatrix.identity(15);
        SimpleMatrix JrInv = org.rtklib.java.research.common.RotationUtils.rightJacobianInverse(wDt);
        SimpleMatrix skewA = org.rtklib.java.research.common.RotationUtils.skew(a);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                F.set(i, j, JrInv.get(i, j));
            }
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                F.set(3 + i, j, -R.get(i, 0) * skewA.get(0, j) * dt
                                 - R.get(i, 1) * skewA.get(1, j) * dt
                                 - R.get(i, 2) * skewA.get(2, j) * dt);
                F.set(3 + i, 3 + j, F.get(3 + i, 3 + j));
            }
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                F.set(6 + i, 3 + j, F.get(6 + i, 3 + j) + ((i == j) ? dt : 0));
            }
        }

        return F;
    }

    private static SimpleMatrix computeNoiseJacobian(SimpleMatrix R, double dt) {
        SimpleMatrix G = new SimpleMatrix(15, 12);
        double dt2 = 0.5 * dt * dt;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                G.set(i, j, (i == j) ? dt : 0);
                G.set(i, 3 + j, (i == j) ? dt : 0);
            }
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                G.set(3 + i, j, (i == j) ? dt : 0);
                G.set(3 + i, 3 + j, (i == j) ? dt : 0);
            }
        }

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                G.set(6 + i, j, (i == j) ? dt2 : 0);
                G.set(6 + i, 3 + j, (i == j) ? dt2 : 0);
            }
        }

        for (int i = 0; i < 3; i++) {
            G.set(9 + i, 6 + i, dt);
            G.set(12 + i, 9 + i, dt);
        }

        return G;
    }

    private static SimpleMatrix computeNoiseCovariance(double an, double gn,
                                                        double abn, double gbn, double dt) {
        SimpleMatrix Q = new SimpleMatrix(12, 12);
        double invDt = 1.0 / dt;

        double an2 = an * an * invDt;
        double gn2 = gn * gn * invDt;
        double abn2 = abn * abn * dt;
        double gbn2 = gbn * gbn * dt;

        for (int i = 0; i < 3; i++) {
            Q.set(i, i, an2);
            Q.set(3 + i, 3 + i, gn2);
            Q.set(6 + i, 6 + i, abn2);
            Q.set(9 + i, 9 + i, gbn2);
        }

        return Q;
    }
}