package org.rtklib.java.adaptive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** FeatureMetrics 单元测试: 滑动平均 + 进入||/退出&& 判定 + 滞后区间 */
class FeatureMetricsTest {

    @Test
    void slidingAverageFirstValueInitializes() {
        FeatureMetrics m = new FeatureMetrics();
        m.update(20, 40.0);
        assertEquals(20.0, m.getSlidingNSat(), 1e-9);
        assertEquals(40.0, m.getSlidingSnr(), 1e-9);
    }

    @Test
    void slidingAverageConverges() {
        FeatureMetrics m = new FeatureMetrics();
        m.update(0, 0.0);
        for (int i = 0; i < 200; i++) m.update(20, 40.0);
        assertEquals(20.0, m.getSlidingNSat(), 0.5);
        assertEquals(40.0, m.getSlidingSnr(), 0.5);
    }

    @Test
    void enterCanyonEitherMetricTriggers() {
        FeatureMetrics m = new FeatureMetrics();
        // nSat 低但 SNR 高 → 仍应切 CANYON (||)
        for (int i = 0; i < 100; i++) m.update(5, 45.0);
        assertEquals(GnssScene.URBAN_CANYON, m.detect(GnssScene.OPEN_SKY, 8, 30.0, 14, 38.0));
        // SNR 低但 nSat 高 → 仍应切 CANYON (||)
        FeatureMetrics m2 = new FeatureMetrics();
        for (int i = 0; i < 100; i++) m2.update(20, 25.0);
        assertEquals(GnssScene.URBAN_CANYON, m2.detect(GnssScene.OPEN_SKY, 8, 30.0, 14, 38.0));
    }

    @Test
    void exitCanyonRequiresBothMetrics() {
        // 只有 nSat 达标 → 不切回 (&&)
        FeatureMetrics m = new FeatureMetrics();
        for (int i = 0; i < 100; i++) m.update(20, 35.0);
        assertEquals(GnssScene.URBAN_CANYON, m.detect(GnssScene.URBAN_CANYON, 8, 30.0, 14, 38.0));
        // 只有 SNR 达标 → 不切回
        FeatureMetrics m2 = new FeatureMetrics();
        for (int i = 0; i < 100; i++) m2.update(10, 45.0);
        assertEquals(GnssScene.URBAN_CANYON, m2.detect(GnssScene.URBAN_CANYON, 8, 30.0, 14, 38.0));
        // 双达标 → 切回
        FeatureMetrics m3 = new FeatureMetrics();
        for (int i = 0; i < 100; i++) m3.update(20, 45.0);
        assertEquals(GnssScene.OPEN_SKY, m3.detect(GnssScene.URBAN_CANYON, 8, 30.0, 14, 38.0));
    }

    @Test
    void hysteresisMiddleZoneKeepsScene() {
        // 中间地带 8<nSat<14 且 30<SNR<38: OPEN 不切 CANYON, CANYON 不切 OPEN
        FeatureMetrics m = new FeatureMetrics();
        for (int i = 0; i < 100; i++) m.update(11, 34.0);
        assertEquals(GnssScene.OPEN_SKY, m.detect(GnssScene.OPEN_SKY, 8, 30.0, 14, 38.0));
        assertEquals(GnssScene.URBAN_CANYON, m.detect(GnssScene.URBAN_CANYON, 8, 30.0, 14, 38.0));
    }
}
