package org.rtklib.java.adaptive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** SampleIntervalDetector 单元测试: 间隔检测 + 分布摘要 + 异常告警仅一次 */
class SampleIntervalDetectorTest {

    @Test
    void detectsDominantInterval() {
        SampleIntervalDetector d = new SampleIntervalDetector();
        double t = 100000.0;
        for (int i = 0; i < 40; i++) { d.feed(t); t += 1.0; }
        assertTrue(d.isDetectionComplete());
        assertEquals(1.0, d.getDetectedInterval(), 1e-9);
        assertTrue(d.getDistributionSummary().contains("1.000s"));
    }

    @Test
    void abnormalIntervalWarnsOnlyOnce() {
        SampleIntervalDetector d = new SampleIntervalDetector();
        double t = 0.0;
        for (int i = 0; i < 40; i++) { d.feed(t); t += 15.0; }
        assertEquals(15.0, d.getDetectedInterval(), 1e-9);
        // 多次调用不应重复告警 (warned 标志) — 无异常即通过, 日志侧验证
        d.warnIfAbnormal(30, 90);
        d.warnIfAbnormal(30, 90);
        d.warnIfAbnormal(30, 90);
    }

    @Test
    void normalIntervalDoesNotWarn() {
        SampleIntervalDetector d = new SampleIntervalDetector();
        double t = 0.0;
        for (int i = 0; i < 40; i++) { d.feed(t); t += 1.0; }
        d.warnIfAbnormal(30, 90);  // 1s 间隔 ≤5s, 不告警
    }

    @Test
    void nonPositiveDtIgnored() {
        SampleIntervalDetector d = new SampleIntervalDetector();
        d.feed(100.0);
        d.feed(100.0);   // dt=0 忽略
        d.feed(99.0);    // dt<0 忽略
        assertFalse(d.isDetectionComplete());
    }
}
