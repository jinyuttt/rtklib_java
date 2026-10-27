package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.engine.DisplacementSeries;
import org.rtklib.java.coord.CoordTransform;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位移时间序列拟合：速率恢复、显著性判定、样本不足的失败分支。
 */
class DisplacementSeriesTest {

    private static final double[] REF_ECEF = {-2.2e6, 4.4e6, 4.6e6};
    private static final double SEC_PER_YEAR = 365.25 * 86400.0;

    @Test
    @DisplayName("已知东向 2 mm/yr 趋势可被恢复且显著")
    void testRecoversEastRate() {
        List<DisplacementSeries.Sample> samples =
                buildSeries(2.0, 0.0, 0.0, 60, 30 * 86400.0, 0.005);

        DisplacementSeries.Result r = DisplacementSeries.fit(samples);

        assertTrue(r.success(), r.message());
        assertEquals(60, r.samples());
        assertEquals(2.0, r.rateEnu()[0], 0.10, "东向速率应恢复为 2 mm/yr");
        assertTrue(r.significant()[0], "东向速率应判为显著");
        assertTrue(Math.abs(r.rateEnu()[2]) < 3.0 * r.sigmaEnu()[2],
                "垂向速率应在 3σ 噪声范围内，实际速率=" + r.rateEnu()[2]
                        + " σ=" + r.sigmaEnu()[2]);
    }

    @Test
    @DisplayName("无趋势时速率不显著")
    void testNoTrendIsNotSignificant() {
        List<DisplacementSeries.Sample> samples =
                buildSeries(0.0, 0.0, 0.0, 80, 86400.0, 0.003);

        DisplacementSeries.Result r = DisplacementSeries.fit(samples);

        assertTrue(r.success());
        for (int i = 0; i < 3; i++) {
            assertFalse(r.significant()[i], "方向 " + i + " 不应显著，实际速率=" + r.rateEnu()[i]);
        }
    }

    @Test
    @DisplayName("样本不足或时间跨度为 0 时返回失败")
    void testInsufficientSamples() {
        List<DisplacementSeries.Sample> two = List.of(
                new DisplacementSeries.Sample(0, REF_ECEF.clone()),
                new DisplacementSeries.Sample(100, REF_ECEF.clone()));
        assertFalse(DisplacementSeries.fit(two).success());

        List<DisplacementSeries.Sample> sameTime = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sameTime.add(new DisplacementSeries.Sample(1000, REF_ECEF.clone()));
        }
        assertFalse(DisplacementSeries.fit(sameTime).success(), "时间跨度为 0 应失败");
    }

    /**
     * 构造"ENU 线性趋势 + 白噪声"的 ECEF 序列。
     *
     * @param rateE 东向速率 (mm/yr)
     * @param rateN 北向速率 (mm/yr)
     * @param rateU 垂向速率 (mm/yr)
     */
    private static List<DisplacementSeries.Sample> buildSeries(
            double rateE, double rateN, double rateU,
            int count, double stepSec, double noiseM) {

        double[] llh = new double[3];
        CoordTransform.ecef2pos(REF_ECEF.clone(), llh);
        double lat = llh[0];
        double lon = llh[1];
        double sinLat = Math.sin(lat), cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon), cosLon = Math.cos(lon);

        Random rnd = new Random(42);
        List<DisplacementSeries.Sample> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double t = i * stepSec;
            double e = rateE * 1e-3 / SEC_PER_YEAR * t + rnd.nextGaussian() * noiseM;
            double n = rateN * 1e-3 / SEC_PER_YEAR * t + rnd.nextGaussian() * noiseM;
            double u = rateU * 1e-3 / SEC_PER_YEAR * t + rnd.nextGaussian() * noiseM;

            double dx = -sinLon * e - sinLat * cosLon * n + cosLat * cosLon * u;
            double dy = cosLon * e - sinLat * sinLon * n + cosLat * sinLon * u;
            double dz = cosLat * n + sinLat * u;

            samples.add(new DisplacementSeries.Sample(t,
                    new double[]{REF_ECEF[0] + dx, REF_ECEF[1] + dy, REF_ECEF[2] + dz}));
        }
        return samples;
    }
}
