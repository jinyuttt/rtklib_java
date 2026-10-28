package org.rtklib.java.networkvrs.interp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.networkvrs.config.NetworkConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FkpInterpolatorTest {

    private static final double D2R = Math.PI / 180.0;
    private static final double[] TARGET = pos(30.05, 95.05);

    @Test
    @DisplayName("三点确定平面：目标点取到解析值")
    void testPlaneExact() {
        NetworkConfig config = new NetworkConfig();
        config.fkpMinStations = 3;
        List<StationCorrection> corrections = planeCorrections();

        FkpCoefficient coefficient = new FkpInterpolator(config).fit(TARGET, corrections);
        assertFalse(coefficient.degenerate());
        assertEquals(2.0, coefficient.intercept(), 1e-6);
        assertEquals(0.001, coefficient.eastSlope(), 1e-9);
        assertEquals(0.002, coefficient.northSlope(), 1e-9);
        assertEquals(0.0, coefficient.rms(), 1e-6);
        assertEquals(2.0, new FkpInterpolator(config).interpolate(TARGET, corrections), 1e-6);
    }

    @Test
    @DisplayName("站点近共线时标记退化并回退 IDW")
    void testCollinearFallback() {
        NetworkConfig config = new NetworkConfig();
        config.fkpMinAreaRatio = 0.05;
        config.fkpFallbackToIdw = true;
        List<StationCorrection> corrections = List.of(
                new StationCorrection("A", ecef(pos(30.00, 95.04)), 1.0),
                new StationCorrection("B", ecef(pos(30.04, 95.04)), 2.0),
                new StationCorrection("C", ecef(pos(30.08, 95.04)), 3.0));

        FkpCoefficient coefficient = new FkpInterpolator(config).fit(TARGET, corrections);
        assertTrue(coefficient.degenerate());
        assertTrue(coefficient.areaRatio() < config.fkpMinAreaRatio);

        double value = new FkpInterpolator(config).interpolate(TARGET, corrections);
        assertTrue(Double.isFinite(value));
        assertTrue(value > 1.0 && value < 3.0);
    }

    @Test
    @DisplayName("站数不足时回退 IDW")
    void testMinStationsFallback() {
        NetworkConfig config = new NetworkConfig();
        config.fkpMinStations = 3;
        config.fkpFallbackToIdw = true;
        List<StationCorrection> corrections = List.of(
                new StationCorrection("A", ecef(pos(30.00, 95.00)), 10.0),
                new StationCorrection("B", ecef(pos(30.10, 95.00)), 20.0));

        double value = new FkpInterpolator(config).interpolate(pos(30.05, 95.00), corrections);
        assertEquals(15.0, value, 0.05);
    }

    private static List<StationCorrection> planeCorrections() {
        double[] targetEcef = ecef(TARGET);
        double[][] stations = {
                pos(30.00, 95.00), pos(30.00, 95.10), pos(30.10, 95.05)};
        StationCorrection[] result = new StationCorrection[stations.length];
        for (int i = 0; i < stations.length; i++) {
            double[] stationEcef = ecef(stations[i]);
            double[] delta = {stationEcef[0] - targetEcef[0],
                    stationEcef[1] - targetEcef[1], stationEcef[2] - targetEcef[2]};
            double[] enu = new double[3];
            CoordTransform.ecef2enu(TARGET, delta, enu);
            result[i] = new StationCorrection("S" + i, stationEcef,
                    2.0 + 0.001 * enu[0] + 0.002 * enu[1]);
        }
        return List.of(result);
    }

    private static double[] pos(double latDeg, double lonDeg) {
        return new double[]{latDeg * D2R, lonDeg * D2R, 100.0};
    }

    private static double[] ecef(double[] pos) {
        double[] r = new double[3];
        CoordTransform.pos2ecef(pos, r);
        return r;
    }
}
