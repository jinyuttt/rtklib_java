package org.rtklib.java.networkvrs.interp;

import org.rtklib.java.coord.CoordTransform;

import java.util.List;

/**
 * 反距离加权（IDW）插值：w = 1 / d^p，d 为流动站在各站局部水平面内的距离。
 *
 * <p>流动站与某站水平距离小于 {@code exactDistance} 时直接取该站值；
 * 否则按归一化权重加权平均。仅用水平距离，不做高程外推（与 RTKLIB 网络 RTK 一致）。</p>
 */
public class IdwInterpolator implements CorrectionInterpolator {

    /** 视为与站点重合的水平距离阈值（米）。 */
    private static final double DEFAULT_EXACT_DISTANCE = 1e-3;

    private final double power;
    private final double exactDistance;

    public IdwInterpolator(double power) {
        this(power, DEFAULT_EXACT_DISTANCE);
    }

    public IdwInterpolator(double power, double exactDistance) {
        if (!(power > 0)) {
            throw new IllegalArgumentException("power must be > 0");
        }
        if (exactDistance < 0) {
            throw new IllegalArgumentException("exactDistance must be >= 0");
        }
        this.power = power;
        this.exactDistance = exactDistance;
    }

    @Override
    public double interpolate(double[] targetPos, List<StationCorrection> corrections) {
        if (corrections == null || corrections.isEmpty()) {
            throw new IllegalArgumentException("corrections is empty");
        }
        if (targetPos == null || targetPos.length != 3) {
            throw new IllegalArgumentException("targetPos must be length 3");
        }

        double[] targetEcef = new double[3];
        CoordTransform.pos2ecef(targetPos, targetEcef);

        double weightSum = 0.0;
        double valueSum = 0.0;
        for (StationCorrection c : corrections) {
            double[] stationEcef = c.ecef();
            double[] stationPos = new double[3];
            CoordTransform.ecef2pos(stationEcef, stationPos);
            // ecef2enu 输入为 ECEF 差值向量（RTKLIB ecef2enu 约定）
            double[] delta = new double[]{
                    targetEcef[0] - stationEcef[0],
                    targetEcef[1] - stationEcef[1],
                    targetEcef[2] - stationEcef[2]};
            double[] enu = new double[3];
            CoordTransform.ecef2enu(stationPos, delta, enu);
            double distance = Math.hypot(enu[0], enu[1]);

            if (distance < exactDistance) {
                return c.value();
            }
            double weight = 1.0 / Math.pow(distance, power);
            weightSum += weight;
            valueSum += weight * c.value();
        }
        if (weightSum == 0.0) {
            throw new IllegalStateException("IDW weight sum is zero");
        }
        return valueSum / weightSum;
    }
}
