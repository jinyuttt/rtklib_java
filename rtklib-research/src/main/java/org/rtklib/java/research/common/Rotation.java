package org.rtklib.java.research.common;

import org.ejml.simple.SimpleMatrix;

/**
 * 旋转工具（移植自FE-GUT rotation.h Rotation类）。
 *
 * <p>提供欧拉角↔旋转矩阵↔四元数转换、反对称矩阵等。</p>
 */
public final class Rotation {
    private Rotation() {}

    public static SimpleMatrix euler2matrix(double roll, double pitch, double yaw) {
        double cr = Math.cos(roll), sr = Math.sin(roll);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        SimpleMatrix R = new SimpleMatrix(3, 3);
        R.set(0, 0, cy * cp);
        R.set(0, 1, cy * sp * sr - sy * cr);
        R.set(0, 2, cy * sp * cr + sy * sr);
        R.set(1, 0, sy * cp);
        R.set(1, 1, sy * sp * sr + cy * cr);
        R.set(1, 2, sy * sp * cr - cy * sr);
        R.set(2, 0, -sp);
        R.set(2, 1, cp * sr);
        R.set(2, 2, cp * cr);
        return R;
    }

    public static double[] matrix2euler(SimpleMatrix R) {
        double pitch = Math.atan(-R.get(2, 0) / Math.sqrt(R.get(2, 1) * R.get(2, 1) + R.get(2, 2) * R.get(2, 2)));
        double roll = Math.atan2(R.get(2, 1), R.get(2, 2));
        double yaw = Math.atan2(R.get(1, 0), R.get(0, 0));
        if (yaw < 0) yaw += 2 * GnssConst.PI;
        return new double[]{roll, pitch, yaw};
    }

    public static SimpleMatrix skewSymmetric(double x, double y, double z) {
        SimpleMatrix m = new SimpleMatrix(3, 3);
        m.set(0, 1, -z);
        m.set(0, 2, y);
        m.set(1, 0, z);
        m.set(1, 2, -x);
        m.set(2, 0, -y);
        m.set(2, 1, x);
        return m;
    }

    public static SimpleMatrix skewSymmetric(SimpleMatrix v) {
        return skewSymmetric(v.get(0, 0), v.get(1, 0), v.get(2, 0));
    }
}