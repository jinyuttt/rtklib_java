package org.rtklib.java.ext.igs;

import java.util.List;

/**
 * IGS SSR IM201 电离层 VTEC 球谐模型的合成与单层映射（IGS SSR v1.00 §4.5.1）。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>球面地球模型，半径 {@link #EARTH_RADIUS_KM} km；纬度、经度、方位角、高度角均为该模型下的量（弧度）；</li>
 *   <li>完全归一化连带勒让德函数 P_nm 采用 Heiskanen &amp; Moritz 定义
 *       （P_00=1、P_10=√3·sinφ、P_11=√3·cosφ），与 IGS SSR 的 “fully normalized” 一致；</li>
 *   <li>负 VTEC 按标准置 0；</li>
 *   <li>STEC = VTEC / sin(E + ψ_PP)，逐层求和。</li>
 * </ul>
 */
public final class IgsSsrVtec {

    /** 球面地球半径（km） */
    public static final double EARTH_RADIUS_KM = 6370.0;

    private static final double PI = Math.PI;
    private static final double TWO_PI = 2.0 * PI;
    private static final double SECONDS_PER_DAY = 86400.0;
    /** VTEC 日峰值地方时对应秒数（14:00） */
    private static final double SUN_PEAK_SEC = 50400.0;
    /** 光速无关的电离层延迟常数 40.3 m·Hz²/TECU（TECU 转 10^16 el/m²） */
    private static final double IONO_CONSTANT = 40.3;
    private static final double TECU_TO_ELECTRONS = 1.0e16;
    private static final double SQRT3 = Math.sqrt(3.0);

    private static final double[] FACTORIAL = new double[33];

    static {
        FACTORIAL[0] = 1.0;
        for (int i = 1; i < FACTORIAL.length; i++) {
            FACTORIAL[i] = FACTORIAL[i - 1] * i;
        }
    }

    private IgsSsrVtec() {
    }

    /**
     * 单层 VTEC 合成（TECU）。
     *
     * <p>{@code VTEC = Σ_n Σ_m (C_nm cos(mλ_S) + S_nm sin(mλ_S))·P_nm(sin φ_PP)}。</p>
     *
     * @param layer 层模型
     * @param pierceLatRad 穿刺点地心纬度 φ_PP（弧度）
     * @param sunLongitudeRad 太阳固定系经度 λ_S（弧度）
     * @return VTEC（TECU），负值已置 0
     */
    public static double vtecTecu(IgsSsrVtecLayer layer, double pierceLatRad, double sunLongitudeRad) {
        double[][] p = normalizedLegendre(layer.degree, Math.sin(pierceLatRad));
        double sum = 0.0;
        for (int n = 0; n <= layer.degree; n++) {
            int mMax = Math.min(n, layer.order);
            for (int m = 0; m <= mMax; m++) {
                double angle = m * sunLongitudeRad;
                sum += p[n][m] * (layer.c(n, m) * Math.cos(angle) + layer.s(n, m) * Math.sin(angle));
            }
        }
        return Math.max(0.0, sum);
    }

    /**
     * 太阳固定系经度 λ_S（弧度，[0, 2π)）。
     *
     * <p>λ_S = (λ_PP + (t − 50400)·π/43200) mod 2π，t 为 SSR 历元（秒，模 86400）。</p>
     *
     * @param pierceLonRad 穿刺点经度 λ_PP（弧度）
     * @param epochTimeSec SSR 历元时间（秒）
     * @return λ_S（弧度）
     */
    public static double sunLongitude(double pierceLonRad, int epochTimeSec) {
        double t = epochTimeSec % SECONDS_PER_DAY;
        if (t < 0) t += SECONDS_PER_DAY;
        double lambda = pierceLonRad + (t - SUN_PEAK_SEC) * PI / 43200.0;
        lambda %= TWO_PI;
        if (lambda < 0) lambda += TWO_PI;
        return lambda;
    }

    /**
     * 穿刺点地心角 ψ_PP（弧度）。
     *
     * <p>ψ_PP = π/2 − E − arcsin((R_e+h_R)/(R_e+h_I)·cos E)。</p>
     *
     * @param roverHeightKm 测站高 h_R（km）
     * @param layerHeightKm 电离层高 h_I（km）
     * @param elevationRad 卫星高度角 E（弧度）
     * @return ψ_PP（弧度）
     */
    public static double centralAngle(double roverHeightKm, double layerHeightKm, double elevationRad) {
        double ratio = (EARTH_RADIUS_KM + roverHeightKm) / (EARTH_RADIUS_KM + layerHeightKm);
        return PI / 2.0 - elevationRad - Math.asin(ratio * Math.cos(elevationRad));
    }

    /**
     * 穿刺点纬度 φ_PP（弧度）。
     *
     * <p>φ_PP = arcsin(sin φ_R·cos ψ_PP + cos φ_R·sin ψ_PP·cos A)。</p>
     *
     * @param roverLatRad 测站地心纬度 φ_R（弧度）
     * @param centralAngleRad ψ_PP（弧度）
     * @param azimuthRad 卫星方位角 A（弧度）
     * @return φ_PP（弧度）
     */
    public static double piercePointLatitude(double roverLatRad, double centralAngleRad, double azimuthRad) {
        return Math.asin(Math.sin(roverLatRad) * Math.cos(centralAngleRad)
                + Math.cos(roverLatRad) * Math.sin(centralAngleRad) * Math.cos(azimuthRad));
    }

    /**
     * 穿刺点经度 λ_PP（弧度，未归一化到 [0, 2π)）。
     *
     * <p>当 φ_R ≥ 0 且 tan ψ_PP·cos A &gt; tan(π/2 − φ_R)，或
     * φ_R &lt; 0 且 −tan ψ_PP·cos A &gt; tan(π/2 + φ_R) 时，取 λ_R + π − arcsin(...)；
     * 其余情况取 λ_R + arcsin(...)。</p>
     *
     * @param roverLatRad 测站地心纬度 φ_R（弧度）
     * @param roverLonRad 测站经度 λ_R（弧度）
     * @param pierceLatRad 穿刺点纬度 φ_PP（弧度）
     * @param centralAngleRad ψ_PP（弧度）
     * @param azimuthRad 卫星方位角 A（弧度）
     * @return λ_PP（弧度）
     */
    public static double piercePointLongitude(double roverLatRad, double roverLonRad, double pierceLatRad,
                                              double centralAngleRad, double azimuthRad) {
        double arcSin = Math.asin(Math.sin(centralAngleRad) * Math.sin(azimuthRad) / Math.cos(pierceLatRad));
        double tanPsiCosA = Math.tan(centralAngleRad) * Math.cos(azimuthRad);
        boolean wrap = roverLatRad >= 0.0
                ? tanPsiCosA > Math.tan(PI / 2.0 - roverLatRad)
                : -tanPsiCosA > Math.tan(PI / 2.0 + roverLatRad);
        return wrap ? roverLonRad + PI - arcSin : roverLonRad + arcSin;
    }

    /**
     * 单层 STEC（TECU）。
     *
     * <p>STEC_i = VTEC_i / sin(E + ψ_PP)；VTEC 为负时先置 0。</p>
     *
     * @param layer 层模型
     * @param roverLatRad 测站地心纬度（弧度）
     * @param roverLonRad 测站经度（弧度）
     * @param roverHeightKm 测站高（km）
     * @param azimuthRad 卫星方位角（弧度）
     * @param elevationRad 卫星高度角（弧度）
     * @param epochTimeSec SSR 历元时间（秒）
     * @return STEC（TECU）
     */
    public static double stecTecu(IgsSsrVtecLayer layer, double roverLatRad, double roverLonRad,
                                  double roverHeightKm, double azimuthRad, double elevationRad,
                                  int epochTimeSec) {
        double psi = centralAngle(roverHeightKm, layer.heightKm, elevationRad);
        double phiPp = piercePointLatitude(roverLatRad, psi, azimuthRad);
        double lamPp = piercePointLongitude(roverLatRad, roverLonRad, phiPp, psi, azimuthRad);
        double vtec = vtecTecu(layer, phiPp, sunLongitude(lamPp, epochTimeSec));
        return vtec / Math.sin(elevationRad + psi);
    }

    /**
     * 多层总 STEC（TECU），逐层求和。
     *
     * @param layers 层模型列表
     * @param roverLatRad 测站地心纬度（弧度）
     * @param roverLonRad 测站经度（弧度）
     * @param roverHeightKm 测站高（km）
     * @param azimuthRad 卫星方位角（弧度）
     * @param elevationRad 卫星高度角（弧度）
     * @param epochTimeSec SSR 历元时间（秒）
     * @return 总 STEC（TECU）
     */
    public static double stecTecu(List<IgsSsrVtecLayer> layers, double roverLatRad, double roverLonRad,
                                  double roverHeightKm, double azimuthRad, double elevationRad,
                                  int epochTimeSec) {
        double sum = 0.0;
        for (IgsSsrVtecLayer layer : layers) {
            sum += stecTecu(layer, roverLatRad, roverLonRad, roverHeightKm, azimuthRad, elevationRad,
                    epochTimeSec);
        }
        return sum;
    }

    /**
     * STEC 对应的伪距电离层延迟（m）：δ = 40.3/f² · STEC · 10^16；相位为同值反号（本方法只给幅度）。
     *
     * @param stecTecu STEC（TECU）
     * @param frequencyHz 频率（Hz）
     * @return 伪距延迟（m，正）
     */
    public static double rangeDelayMeters(double stecTecu, double frequencyHz) {
        return IONO_CONSTANT / (frequencyHz * frequencyHz) * stecTecu * TECU_TO_ELECTRONS;
    }

    /**
     * 完全归一化连带勒让德函数 P_nm(sin φ)（Heiskanen &amp; Moritz 定义）。
     *
     * @param degree 最大阶 N
     * @param x sin φ
     * @return [n][m] 数组，尺寸 (N+1)×(N+1)，未定义项为 0
     */
    public static double[][] normalizedLegendre(int degree, double x) {
        if (degree < 0) {
            throw new IllegalArgumentException("degree must be >= 0: " + degree);
        }
        double[][] p = new double[degree + 1][degree + 1];
        double u = Math.sqrt(Math.max(0.0, 1.0 - x * x));
        p[0][0] = 1.0;

        for (int m = 0; m <= degree; m++) {
            if (m == 1) {
                p[1][0] = SQRT3 * x;
                p[1][1] = SQRT3 * u;
            } else if (m >= 2) {
                p[m][m] = Math.sqrt((2.0 * m + 1.0) / (2.0 * m)) * u * p[m - 1][m - 1];
            }
            for (int n = m + 1; n <= degree; n++) {
                double a = normalization(n, m) * (2.0 * n - 1.0)
                        / ((n - m) * normalization(n - 1, m));
                p[n][m] = a * x * p[n - 1][m];
                if (n - 2 >= m) {
                    double b = normalization(n, m) * (n + m - 1.0)
                            / ((n - m) * normalization(n - 2, m));
                    p[n][m] -= b * p[n - 2][m];
                }
            }
        }
        return p;
    }

    /** 归一化因子 √((2−δ_0m)(2n+1)(n−m)!/(n+m)!) */
    private static double normalization(int n, int m) {
        int factor = (m == 0) ? 1 : 2;
        return Math.sqrt(factor * (2.0 * n + 1.0) * FACTORIAL[n - m] / FACTORIAL[n + m]);
    }
}
