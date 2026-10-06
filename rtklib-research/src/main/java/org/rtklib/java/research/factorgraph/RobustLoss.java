package org.rtklib.java.research.factorgraph;

/**
 * 鲁棒损失函数（research模块因子图框架）。
 *
 * <p>用于抑制非高斯噪声（多径、NLOS等），替代标准最小二乘。
 * 支持 Ceres 风格的 Evaluate(s, rho) 三值输出：
 * <ul>
 *   <li>rho[0] = ρ(s)</li>
 *   <li>rho[1] = ρ'(s)</li>
 *   <li>rho[2] = ρ''(s)</li>
 * </ul>
 */
public class RobustLoss {

    public enum Type {
        HUBER, CAUCHY, TUKEY, DCS
    }

    private final Type type;
    private final double threshold;

    public RobustLoss(Type type, double threshold) {
        this.type = type;
        this.threshold = threshold;
    }

    public double evaluate(double sqNorm) {
        double s = Math.sqrt(sqNorm);
        switch (type) {
            case HUBER:
                if (s <= threshold) return 0.5 * sqNorm;
                return threshold * (s - 0.5 * threshold);
            case CAUCHY: {
                double t2 = threshold * threshold;
                return t2 * Math.log(1.0 + sqNorm / t2);
            }
            case TUKEY: {
                double ratio = s / threshold;
                if (ratio <= 1.0) {
                    return (threshold * threshold / 6.0) * (1.0 - Math.pow(1.0 - ratio * ratio, 3));
                }
                return threshold * threshold / 6.0;
            }
            case DCS: {
                double t2 = threshold * threshold;
                if (sqNorm <= t2) return 0.5 * sqNorm;
                return 2.0 * threshold * s - 1.5 * t2;
            }
            default:
                return 0.5 * sqNorm;
        }
    }

    public double weight(double sqNorm) {
        double s = Math.sqrt(sqNorm);
        switch (type) {
            case HUBER:
                return (s <= threshold) ? 1.0 : threshold / s;
            case CAUCHY: {
                double t2 = threshold * threshold;
                return 1.0 / (1.0 + sqNorm / t2);
            }
            case TUKEY: {
                double ratio = s / threshold;
                return (ratio <= 1.0) ? Math.pow(1.0 - ratio * ratio, 2) : 0.0;
            }
            case DCS: {
                double t2 = threshold * threshold;
                return (sqNorm <= t2) ? 1.0 : (2.0 * threshold / s - 1.0);
            }
            default:
                return 1.0;
        }
    }

    public double secondDerivative(double sqNorm) {
        double s = Math.sqrt(sqNorm);
        switch (type) {
            case HUBER:
                return (s <= threshold) ? 0.0 : -0.5 * threshold / (s * s * s);
            case CAUCHY: {
                double t2 = threshold * threshold;
                return -1.0 * t2 / ((t2 + sqNorm) * (t2 + sqNorm));
            }
            case TUKEY: {
                double ratio = s / threshold;
                if (ratio <= 1.0) {
                    return -2.0 * (1.0 - ratio * ratio) / (threshold * threshold);
                }
                return 0.0;
            }
            case DCS:
                return (sqNorm <= threshold * threshold) ? 0.0 : -threshold / (s * s * s);
            default:
                return 0.0;
        }
    }

    public static double huber(double r, double threshold) {
        double absR = Math.abs(r);
        if (absR <= threshold) return 0.5 * r * r;
        return threshold * (absR - 0.5 * threshold);
    }

    public static double huberWeight(double r, double threshold) {
        return (Math.abs(r) <= threshold) ? 1.0 : threshold / Math.abs(r);
    }

    public static double cauchy(double r, double threshold) {
        double t2 = threshold * threshold;
        return t2 * Math.log(1.0 + (r * r) / t2);
    }

    public static double cauchyWeight(double r, double threshold) {
        double t2 = threshold * threshold;
        return 1.0 / (1.0 + (r * r) / t2);
    }

    public static double tukey(double r, double threshold) {
        double ratio = r / threshold;
        if (Math.abs(ratio) <= 1.0) {
            return (threshold * threshold / 6.0) * (1.0 - Math.pow(1.0 - ratio * ratio, 3));
        }
        return threshold * threshold / 6.0;
    }

    public static double tukeyWeight(double r, double threshold) {
        double ratio = r / threshold;
        return (Math.abs(ratio) <= 1.0) ? Math.pow(1.0 - ratio * ratio, 2) : 0.0;
    }

    public static double dcs(double r, double threshold) {
        double r2 = r * r;
        double t2 = threshold * threshold;
        return (r2 <= t2) ? 0.5 * r2 : 2.0 * threshold * Math.sqrt(r2) - 1.5 * t2;
    }

    public static double dcsWeight(double r, double threshold) {
        double r2 = r * r;
        double t2 = threshold * threshold;
        return (r2 <= t2) ? 1.0 : 2.0 * threshold / Math.sqrt(r2) - 1.0;
    }

    public Type type() {
        return type;
    }

    public double threshold() {
        return threshold;
    }

    @Override
    public String toString() {
        return String.format("%s(%.3f)", type, threshold);
    }
}