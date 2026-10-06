package org.rtklib.java.research.common;

import org.ejml.simple.SimpleMatrix;

/**
 * SO(3) 旋转矩阵工具（用于 IMU 预积分）。
 *
 * <p>提供 Rodrigues 指数映射和对数映射，支持预积分中的
 * ΔR 计算和 bias 雅可比推导。</p>
 */
public final class RotationUtils {

    private RotationUtils() {}

    /**
     * 从 3 维向量构造反对称矩阵。
     * <pre>
     *   [  0  -v3   v2 ]
     *   [  v3   0  -v1 ]
     *   [ -v2  v1   0  ]
     * </pre>
     */
    public static SimpleMatrix skew(double v1, double v2, double v3) {
        SimpleMatrix S = new SimpleMatrix(3, 3);
        S.set(0, 1, -v3); S.set(0, 2,  v2);
        S.set(1, 0,  v3); S.set(1, 2, -v1);
        S.set(2, 0, -v2); S.set(2, 1,  v1);
        return S;
    }

    public static SimpleMatrix skew(double[] v) {
        return skew(v[0], v[1], v[2]);
    }

    /**
     * SO(3) 指数映射 (Rodrigues 公式)。
     *
     * @param omega 轴角向量 (rad)，ω = θ·u
     * @return 旋转矩阵 R = exp(ω^) ∈ SO(3)
     */
    public static SimpleMatrix exp(double[] omega) {
        double theta = Math.sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2]);

        if (theta < 1e-10) {
            SimpleMatrix R = SimpleMatrix.identity(3);
            R.set(0, 1, -omega[2]); R.set(0, 2,  omega[1]);
            R.set(1, 0,  omega[2]); R.set(1, 2, -omega[0]);
            R.set(2, 0, -omega[1]); R.set(2, 1,  omega[0]);
            return R;
        }

        SimpleMatrix W = skew(omega);
        SimpleMatrix W2 = W.mult(W);

        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);

        SimpleMatrix R = SimpleMatrix.identity(3);
        double a = sinTheta / theta;
        double b = (1.0 - cosTheta) / (theta * theta);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                R.set(i, j, R.get(i, j) + a * W.get(i, j) + b * W2.get(i, j));
            }
        }
        return R;
    }

    /**
     * SO(3) 对数映射。
     *
     * @param R 旋转矩阵 ∈ SO(3)
     * @return 轴角向量 ω = log(R)^∨
     */
    public static double[] log(SimpleMatrix R) {
        double trace = R.get(0, 0) + R.get(1, 1) + R.get(2, 2);
        double cosTheta = (trace - 1.0) / 2.0;
        cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));
        double theta = Math.acos(cosTheta);

        if (theta < 1e-10) {
            return new double[]{
                0.5 * (R.get(2, 1) - R.get(1, 2)),
                0.5 * (R.get(0, 2) - R.get(2, 0)),
                0.5 * (R.get(1, 0) - R.get(0, 1))
            };
        }

        double factor = theta / (2.0 * Math.sin(theta));
        return new double[]{
            factor * (R.get(2, 1) - R.get(1, 2)),
            factor * (R.get(0, 2) - R.get(2, 0)),
            factor * (R.get(1, 0) - R.get(0, 1))
        };
    }

    /**
     * 右雅可比 (right Jacobian of SO(3))。
     * <pre>
     *   Jr(ω) = I - (1-cosθ)/θ²·ω^ + (θ-sinθ)/θ³·ω^²
     * </pre>
     */
    public static SimpleMatrix rightJacobian(double[] omega) {
        double theta = Math.sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2]);

        if (theta < 1e-10) {
            SimpleMatrix Jr = SimpleMatrix.identity(3);
            SimpleMatrix W = skew(omega);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    Jr.set(i, j, Jr.get(i, j) - 0.5 * W.get(i, j));
                }
            }
            return Jr;
        }

        SimpleMatrix W = skew(omega);
        SimpleMatrix W2 = W.mult(W);

        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);
        double a = (1.0 - cosTheta) / (theta * theta);
        double b = (theta - sinTheta) / (theta * theta * theta);

        SimpleMatrix Jr = SimpleMatrix.identity(3);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Jr.set(i, j, Jr.get(i, j) - a * W.get(i, j) + b * W2.get(i, j));
            }
        }
        return Jr;
    }

    /**
     * 右雅可比的逆。
     * <pre>
     *   Jr⁻¹(ω) = I + 0.5·ω^ + (1/θ² - (1+cosθ)/(2θ·sinθ))·ω^²
     * </pre>
     */
    public static SimpleMatrix rightJacobianInverse(double[] omega) {
        double theta = Math.sqrt(omega[0] * omega[0] + omega[1] * omega[1] + omega[2] * omega[2]);

        if (theta < 1e-10) {
            SimpleMatrix invJr = SimpleMatrix.identity(3);
            SimpleMatrix W = skew(omega);
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    invJr.set(i, j, invJr.get(i, j) + 0.5 * W.get(i, j));
                }
            }
            return invJr;
        }

        SimpleMatrix W = skew(omega);
        SimpleMatrix W2 = W.mult(W);

        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);
        double a = 1.0 / (theta * theta) - (1.0 + cosTheta) / (2.0 * theta * sinTheta);

        SimpleMatrix invJr = SimpleMatrix.identity(3);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                invJr.set(i, j, invJr.get(i, j) + 0.5 * W.get(i, j) + a * W2.get(i, j));
            }
        }
        return invJr;
    }
}