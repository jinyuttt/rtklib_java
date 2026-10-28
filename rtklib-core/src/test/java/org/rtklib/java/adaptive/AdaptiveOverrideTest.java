package org.rtklib.java.adaptive;

import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.data.PrcOpt;

import static org.junit.jupiter.api.Assertions.*;

/** AdaptiveOverride / OptimizationMonitor 单元测试 */
class AdaptiveOverrideTest {

    @Test
    void snapshotSetApplyRestore() {
        RtkConfig cfg = new RtkConfig();
        PrcOpt opt = new PrcOpt();
        double origThresslip = opt.thresslip;
        boolean origIggiii = cfg.rtk.enableIggiii;

        AdaptiveOverride ov = new AdaptiveOverride();
        ov.snapshot(cfg, opt);
        ov.activate();

        ov.set("thresslip", 0.10);
        ov.set("enableIggiii", true);
        ov.applyTo(cfg, opt);
        assertEquals(0.10, opt.thresslip, 1e-12);
        assertTrue(cfg.rtk.enableIggiii);
        assertTrue(ov.getBoolean("enableIggiii", false));
        assertEquals(0.10, ov.getDouble("thresslip", 0), 1e-12);

        ov.restoreAll(cfg, opt);
        assertEquals(origThresslip, opt.thresslip, 1e-12);
        assertEquals(origIggiii, cfg.rtk.enableIggiii);
    }

    @Test
    void inactiveOverridePassesThroughUserValue() {
        AdaptiveOverride ov = new AdaptiveOverride();  // 未 activate
        ov.set("thresslip", 0.10);  // 未激活应拒绝写入
        assertEquals(0.05, ov.getDouble("thresslip", 0.05), 1e-12, "未激活时应直接返回用户值");
        assertTrue(ov.getBoolean("enableIggiii", true));
    }

    @Test
    void diffStringShowsChanges() {
        RtkConfig cfg = new RtkConfig();
        PrcOpt opt = new PrcOpt();
        AdaptiveOverride ov = new AdaptiveOverride();
        ov.snapshot(cfg, opt);
        ov.activate();
        ov.set("thresslip", 0.10);
        String diff = ov.diffString();
        assertTrue(diff.contains("thresslip"), "diff 应含变更参数: " + diff);
        assertTrue(diff.contains("→"), "diff 应含变更箭头: " + diff);
    }

    @Test
    void divergenceDetection() {
        OptimizationMonitor mon = new OptimizationMonitor();
        assertFalse(mon.checkDivergence(1.0));
        assertFalse(mon.checkDivergence(2.0));
        assertFalse(mon.checkDivergence(4.0));
        // 连续3次增长 + 超过基线3倍 → 发散
        assertTrue(mon.checkDivergence(8.0));
    }

    @Test
    void stableTraceNotDiverging() {
        OptimizationMonitor mon = new OptimizationMonitor();
        for (int i = 0; i < 120; i++) assertFalse(mon.checkDivergence(1.0 + 0.001 * (i % 3)));
    }
}
