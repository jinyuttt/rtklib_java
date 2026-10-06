package org.rtklib.java.research.data;

import java.io.Serializable;

/**
 * IMU 零偏状态。
 *
 * <p>包含加速度计零偏 (3维) 和陀螺仪零偏 (3维)，共 6 维。
 * 在预积分因子中作为变量节点连接相邻关键帧。</p>
 */
public class ImuBias implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 加速度计零偏 (m/s², body frame) */
    public double[] accelBias;

    /** 陀螺仪零偏 (rad/s, body frame) */
    public double[] gyroBias;

    public ImuBias() {
        this.accelBias = new double[3];
        this.gyroBias = new double[3];
    }

    public ImuBias(double[] accelBias, double[] gyroBias) {
        this.accelBias = accelBias.clone();
        this.gyroBias = gyroBias.clone();
    }

    public ImuBias(ImuBias other) {
        this.accelBias = other.accelBias.clone();
        this.gyroBias = other.gyroBias.clone();
    }

    public int dimension() {
        return 6;
    }

    @Override
    public String toString() {
        return String.format("Bias[ba=(%.4f,%.4f,%.4f), bg=(%.6f,%.6f,%.6f)]",
                accelBias[0], accelBias[1], accelBias[2],
                gyroBias[0], gyroBias[1], gyroBias[2]);
    }
}