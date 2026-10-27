package org.rtklib.java.adjust.engine;

import org.rtklib.java.coord.CoordTransform;

import java.util.List;

/**
 * 位移时间序列分析：从逐历元坐标序列估计位移速率、判断显著性。
 *
 * <p>静态监测项目的最终成果不是"每个历元的坐标"，而是：</p>
 * <ul>
 *   <li>该点位的位移速率（mm/yr）及其 1σ 不确定度</li>
 *   <li>速率是否显著（区别于噪声）</li>
 *   <li>拟合残差与异常历元（突变检测）</li>
 * </ul>
 *
 * <p>采用逐轴加权最小二乘线性拟合（等权）：
 * {@code x(t) = x0 + v·(t − t̄)}，斜率方差 {@code σ_v² = σ²/Sxx}，
 * 其中 {@code σ² = Σres²/(n−2)}。显著性判据 {@code |v|/σ_v > 1.96}（95%）。</p>
 *
 * <p>长时间序列的平均本身就是最强的降噪手段：速率的不确定度按
 * {@code 1/sqrt(Sxx)} 收缩，比单历元协方差改进有效得多。</p>
 */
public final class DisplacementSeries {

    /** 95% 双侧正态分位。 */
    private static final double Z_95 = 1.96;

    /** 秒/年（儒略年）。 */
    private static final double SEC_PER_YEAR = 365.25 * 86400.0;

    /** 拟合残差超过该倍数 σ 视为异常历元。 */
    private static final double OUTLIER_SIGMA = 3.0;

    private DisplacementSeries() {
    }

    /**
     * 一个时间采样点。
     *
     * @param timeSec 时间（秒，任意起算点）
     * @param xyz     ECEF 坐标 (m)
     */
    public record Sample(double timeSec, double[] xyz) {
    }

    /**
     * 拟合结果。速率单位为 mm/yr，便于直接用于监测报告。
     *
     * @param success      是否成功（样本不足或时间跨度为零时为 false）
     * @param message      说明
     * @param samples      参与拟合的样本数
     * @param spanDays     时间跨度（天）
     * @param rateEcef     ECEF 速率 (m/s)
     * @param sigmaEcef    ECEF 速率 1σ (m/s)
     * @param rateEnu      ENU 速率 (mm/yr)
     * @param sigmaEnu     ENU 速率 1σ (mm/yr)
     * @param significant  各方向速率是否显著（E/N/U）
     * @param rmsMm        三维拟合残差 RMS (mm)
     * @param outlierCount 超过 3σ 的历元数
     */
    public record Result(
            boolean success,
            String message,
            int samples,
            double spanDays,
            double[] rateEcef,
            double[] sigmaEcef,
            double[] rateEnu,
            double[] sigmaEnu,
            boolean[] significant,
            double rmsMm,
            int outlierCount
    ) {
        public static Result failure(String message, int samples, double spanDays) {
            return new Result(false, message, samples, spanDays,
                    null, null, null, null, null, Double.NaN, 0);
        }
    }

    /**
     * 对坐标序列做线性趋势拟合。
     *
     * @param samples 按时间升序（乱序也可，内部会按时间排序）
     * @return 拟合结果；样本数 &lt; 3 或时间跨度为 0 时返回失败
     */
    public static Result fit(List<Sample> samples) {
        if (samples == null || samples.size() < 3) {
            return Result.failure("样本数不足（需 ≥ 3）", samples == null ? 0 : samples.size(), 0.0);
        }

        int n = samples.size();
        List<Sample> sorted = samples.stream()
                .filter(s -> s != null && s.xyz != null && s.xyz.length >= 3)
                .sorted((a, b) -> Double.compare(a.timeSec, b.timeSec))
                .toList();
        n = sorted.size();
        if (n < 3) {
            return Result.failure("有效样本数不足（需 ≥ 3）", n, 0.0);
        }

        double spanSec = sorted.get(n - 1).timeSec - sorted.get(0).timeSec;
        double spanDays = spanSec / 86400.0;
        if (spanSec <= 0) {
            return Result.failure("时间跨度为 0，无法估计速率", n, 0.0);
        }

        double tBar = 0.0;
        double[] xBar = new double[3];
        for (Sample s : sorted) {
            tBar += s.timeSec;
            for (int i = 0; i < 3; i++) xBar[i] += s.xyz[i];
        }
        tBar /= n;
        for (int i = 0; i < 3; i++) xBar[i] /= n;

        double sxx = 0.0;
        for (Sample s : sorted) {
            double dt = s.timeSec - tBar;
            sxx += dt * dt;
        }
        if (sxx <= 0) {
            return Result.failure("时间分布退化，无法估计速率", n, spanDays);
        }

        double[] slope = new double[3];
        double[] intercept = new double[3];
        double[] sse = new double[3];
        double[] sigmaSlope = new double[3];

        for (int i = 0; i < 3; i++) {
            double sxy = 0.0;
            for (Sample s : sorted) {
                sxy += (s.timeSec - tBar) * (s.xyz[i] - xBar[i]);
            }
            slope[i] = sxy / sxx;
            intercept[i] = xBar[i] - slope[i] * tBar;

            double sum = 0.0;
            for (Sample s : sorted) {
                double res = s.xyz[i] - (intercept[i] + slope[i] * s.timeSec);
                sum += res * res;
            }
            sse[i] = sum;
            double sigmaSq = sum / (n - 2);
            sigmaSlope[i] = Math.sqrt(sigmaSq / sxx);
        }

        double totalSq = 0.0;
        double[] outlierResid = new double[n];
        for (int j = 0; j < n; j++) {
            Sample s = sorted.get(j);
            double sq = 0.0;
            for (int i = 0; i < 3; i++) {
                double res = s.xyz[i] - (intercept[i] + slope[i] * s.timeSec);
                sq += res * res;
            }
            outlierResid[j] = Math.sqrt(sq);
            totalSq += sq;
        }
        double rmsMm = Math.sqrt(totalSq / n) * 1000.0;

        double[] ref = new double[3];
        for (int i = 0; i < 3; i++) ref[i] = xBar[i];
        double[] llh = new double[3];
        CoordTransform.ecef2pos(ref, llh);
        double lat = llh[0];
        double lon = llh[1];

        double[] rateEnu = new double[3];
        for (int i = 0; i < 3; i++) {
            rateEnu[i] = slope[i] * SEC_PER_YEAR * 1000.0;
        }
        rotateEcefToEnu(rateEnu, lat, lon);

        // 速率协方差在 ECEF 下按轴独立（逐轴最小二乘），旋转到 ENU 后再判显著性。
        // 若只旋转速率而保留 ECEF 方向的 σ，方向与不确定度不匹配，显著性判断会失真。
        double[][] g = rotationMatrix(lat, lon);
        double[] sigmaEnu = new double[3];
        boolean[] significant = new boolean[3];
        for (int i = 0; i < 3; i++) {
            double var = 0.0;
            for (int j = 0; j < 3; j++) {
                var += g[i][j] * g[i][j] * sigmaSlope[j] * sigmaSlope[j];
            }
            sigmaEnu[i] = Math.sqrt(var) * SEC_PER_YEAR * 1000.0;
            significant[i] = sigmaEnu[i] > 0 && Math.abs(rateEnu[i]) / sigmaEnu[i] > Z_95;
        }

        // 异常历元：残差超过 3 倍拟合噪声（按三维残差 RMS 计）
        int outlierCount = 0;
        double noise3d = Math.sqrt((sse[0] + sse[1] + sse[2]) / Math.max(n - 2, 1));
        for (int j = 0; j < n; j++) {
            if (noise3d > 0 && outlierResid[j] > OUTLIER_SIGMA * noise3d) {
                outlierCount++;
            }
        }

        return new Result(true, "OK", n, spanDays, slope, sigmaSlope,
                rateEnu, sigmaEnu, significant, rmsMm, outlierCount);
    }

    /**
     * ECEF → ENU 的旋转矩阵（作用在向量差上）。
     * 行依次为 E/N/U 三个方向在 ECEF 三轴上的分量。
     */
    private static double[][] rotationMatrix(double lat, double lon) {
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon);
        double cosLon = Math.cos(lon);
        return new double[][]{
                {-sinLon, cosLon, 0.0},
                {-sinLat * cosLon, -sinLat * sinLon, cosLat},
                {cosLat * cosLon, cosLat * sinLon, sinLat}
        };
    }

    /**
     * 把 ECEF 方向向量就地旋转到 ENU（比值/向量方向旋转，不是位置差）。
     */
    private static void rotateEcefToEnu(double[] v, double lat, double lon) {
        double[] ecef = v.clone();
        double[][] g = rotationMatrix(lat, lon);
        for (int i = 0; i < 3; i++) {
            v[i] = g[i][0] * ecef[0] + g[i][1] * ecef[1] + g[i][2] * ecef[2];
        }
    }
}
