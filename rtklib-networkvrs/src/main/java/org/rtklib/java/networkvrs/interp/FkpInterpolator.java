package org.rtklib.java.networkvrs.interp;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.networkvrs.config.NetworkConfig;

import java.util.List;

/**
 * FKP 平面拟合插值（档 2）。
 *
 * <p>把各站改正量看作流动站局部水平面 (E, N) 上的平面 {@code v = c + a·E + b·N}，
 * 最小二乘拟合后在目标点（E=N=0）取值。最少 {@code fkpMinStations} 站；
 * 站点近共线（归一化面积低于阈值）时按配置回退 IDW 或抛异常。</p>
 */
public class FkpInterpolator implements CorrectionInterpolator {

    private final NetworkConfig config;
    private final IdwInterpolator fallback;

    public FkpInterpolator(NetworkConfig config) {
        this(config, new IdwInterpolator(config.idwPower));
    }

    public FkpInterpolator(NetworkConfig config, IdwInterpolator fallback) {
        this.config = config;
        this.fallback = fallback;
    }

    @Override
    public double interpolate(double[] targetPos, List<StationCorrection> corrections) {
        if (corrections == null || corrections.isEmpty()) {
            throw new IllegalArgumentException("corrections is empty");
        }
        if (corrections.size() < config.fkpMinStations) {
            if (config.fkpFallbackToIdw) {
                return fallback.interpolate(targetPos, corrections);
            }
            throw new IllegalStateException("FKP 需要至少 " + config.fkpMinStations + " 个站");
        }
        FkpCoefficient coefficient = fit(targetPos, corrections);
        if (coefficient.degenerate()) {
            if (config.fkpFallbackToIdw) {
                return fallback.interpolate(targetPos, corrections);
            }
            throw new IllegalStateException("FKP 站点近共线，几何退化");
        }
        return coefficient.intercept();
    }

    /**
     * 拟合平面并返回系数（供诊断与档 3 的 RTCM 网络消息使用）。
     */
    public FkpCoefficient fit(double[] targetPos, List<StationCorrection> corrections) {
        if (targetPos == null || targetPos.length != 3) {
            throw new IllegalArgumentException("targetPos must be length 3");
        }
        double[] targetEcef = new double[3];
        CoordTransform.pos2ecef(targetPos, targetEcef);

        int n = corrections.size();
        double[][] enu = new double[n][2];
        double[] values = new double[n];
        double maxEdge = 0.0;
        for (int i = 0; i < n; i++) {
            StationCorrection c = corrections.get(i);
            double[] stationEcef = c.ecef();
            double[] delta = {stationEcef[0] - targetEcef[0],
                    stationEcef[1] - targetEcef[1],
                    stationEcef[2] - targetEcef[2]};
            double[] local = new double[3];
            CoordTransform.ecef2enu(targetPos, delta, local);
            enu[i][0] = local[0];
            enu[i][1] = local[1];
            values[i] = c.value();
            if (Math.hypot(enu[i][0], enu[i][1]) < 1e-3) {
                return new FkpCoefficient(values[i], 0.0, 0.0, 0.0, n, 1.0, false);
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                maxEdge = Math.max(maxEdge,
                        Math.hypot(enu[i][0] - enu[j][0], enu[i][1] - enu[j][1]));
            }
        }
        double areaRatio = normalizedArea(enu, maxEdge);
        boolean degenerate = areaRatio < config.fkpMinAreaRatio;
        if (degenerate) {
            return new FkpCoefficient(values[0], 0.0, 0.0, Double.NaN, n, areaRatio, true);
        }

        try {
            SimpleMatrix ata = new SimpleMatrix(3, 3);
            SimpleMatrix atv = new SimpleMatrix(3, 1);
            for (int i = 0; i < n; i++) {
                double[] a = {1.0, enu[i][0], enu[i][1]};
                for (int r = 0; r < 3; r++) {
                    atv.set(r, 0, atv.get(r, 0) + a[r] * values[i]);
                    for (int c = 0; c < 3; c++) {
                        ata.set(r, c, ata.get(r, c) + a[r] * a[c]);
                    }
                }
            }
            SimpleMatrix sol = ata.solve(atv);
            double c0 = sol.get(0, 0);
            double c1 = sol.get(1, 0);
            double c2 = sol.get(2, 0);
            double sum = 0.0;
            for (int i = 0; i < n; i++) {
                double pred = c0 + c1 * enu[i][0] + c2 * enu[i][1];
                double r = values[i] - pred;
                sum += r * r;
            }
            double rms = Math.sqrt(sum / n);
            return new FkpCoefficient(c0, c1, c2, rms, n, areaRatio, false);
        } catch (RuntimeException e) {
            return new FkpCoefficient(values[0], 0.0, 0.0, Double.NaN, n, areaRatio, true);
        }
    }

    /**
     * 归一化面积：取所有三点组合的最大 2A/maxEdge²。
     */
    private static double normalizedArea(double[][] enu, double maxEdge) {
        if (maxEdge <= 0.0) {
            return 0.0;
        }
        int n = enu.length;
        double best = 0.0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    double area2 = Math.abs(
                            (enu[j][0] - enu[i][0]) * (enu[k][1] - enu[i][1])
                                    - (enu[k][0] - enu[i][0]) * (enu[j][1] - enu[i][1]));
                    best = Math.max(best, area2);
                }
            }
        }
        return best / (maxEdge * maxEdge);
    }
}
