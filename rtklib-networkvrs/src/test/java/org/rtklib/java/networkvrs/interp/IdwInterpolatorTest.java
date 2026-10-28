package org.rtklib.java.networkvrs.interp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.coord.CoordTransform;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdwInterpolatorTest {

    private static final double D2R = Math.PI / 180.0;

    @Test
    @DisplayName("流动站与站点重合时直接取该站值")
    void testExactStation() {
        double[] a = pos(30.00, 95.00);
        List<StationCorrection> corrections = List.of(
                new StationCorrection("A", ecef(a), 10.0),
                new StationCorrection("B", ecef(pos(30.09, 95.00)), 20.0),
                new StationCorrection("C", ecef(pos(30.00, 95.10)), 30.0));

        assertEquals(10.0, new IdwInterpolator(2.0).interpolate(a, corrections), 1e-9);
    }

    @Test
    @DisplayName("等距两站插值为算术平均")
    void testEqualWeights() {
        double[] a = pos(30.00, 95.00);
        double[] b = pos(30.00, 95.09);
        double[] target = pos(30.00, 95.045);

        double value = new IdwInterpolator(2.0).interpolate(target, List.of(
                new StationCorrection("A", ecef(a), 10.0),
                new StationCorrection("B", ecef(b), 20.0)));

        assertEquals(15.0, value, 0.05);
    }

    @Test
    @DisplayName("站值相同时插值保持该值")
    void testConstantField() {
        double[] target = pos(30.05, 95.05);
        List<StationCorrection> corrections = List.of(
                new StationCorrection("A", ecef(pos(30.00, 95.00)), 7.0),
                new StationCorrection("B", ecef(pos(30.09, 95.00)), 7.0),
                new StationCorrection("C", ecef(pos(30.00, 95.10)), 7.0));

        assertEquals(7.0, new IdwInterpolator(2.0).interpolate(target, corrections), 1e-12);
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
