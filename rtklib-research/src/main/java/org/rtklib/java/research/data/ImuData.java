package org.rtklib.java.research.data;

import java.io.Serializable;
import org.rtklib.java.research.common.GTime;

/**
 * IMU 原始数据（加速度计 + 陀螺仪）。
 *
 * <p>参考 GREAT-MSF / Wheel-GINS 的 IMU 数据模型：
 * 加速度计输出比力 (specific force) 在载体坐标系 (body frame)，
 * 陀螺仪输出角速度在载体坐标系。</p>
 *
 * <p>典型 IMU 频率 100-200Hz，GNSS 频率 1Hz，需时间对齐。</p>
 */
public class ImuData implements Serializable {
    private static final long serialVersionUID = 1L;

    public GTime time;

    /** 加速度计输出 (m/s², body frame) */
    public double[] accel;

    /** 陀螺仪输出 (rad/s, body frame) */
    public double[] gyro;

    /** 距上一帧 IMU 的时间间隔 (s) */
    public double dt;

    public ImuData() {
        this.time = new GTime();
        this.accel = new double[3];
        this.gyro = new double[3];
        this.dt = 0.01;
    }

    public ImuData(GTime time, double[] accel, double[] gyro, double dt) {
        this.time = new GTime(time);
        this.accel = accel.clone();
        this.gyro = gyro.clone();
        this.dt = dt;
    }

    public ImuData(ImuData other) {
        this.time = new GTime(other.time);
        this.accel = other.accel.clone();
        this.gyro = other.gyro.clone();
        this.dt = other.dt;
    }

    @Override
    public String toString() {
        return String.format("Imu[%s, a=(%.3f,%.3f,%.3f), w=(%.4f,%.4f,%.4f), dt=%.4f]",
                time, accel[0], accel[1], accel[2],
                gyro[0], gyro[1], gyro[2], dt);
    }
}