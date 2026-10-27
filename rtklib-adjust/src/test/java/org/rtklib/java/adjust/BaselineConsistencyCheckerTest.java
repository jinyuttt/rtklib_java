package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.engine.BaselineConsistencyChecker;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基线一致性检核测试：重复基线、基站间相对漂移、异步环闭合差。
 */
public class BaselineConsistencyCheckerTest {

    private static final double[] ROVER = {-2267749.0, 5009154.0, 3221290.0};

    private static final String[] IDS = {"A", "B", "C"};

    private static final double[][] BASE = {
            {-2267759.0, 5009144.0, 3221300.0},
            {-2267739.0, 5009164.0, 3221280.0},
            {-2267769.0, 5009134.0, 3221310.0}
    };

    /** 构造「Rover − 基站」基线向量；offset 为基站坐标的真实偏移（模拟基站位移）。 */
    private static Map<String, double[]> vectors(double[] rover, int n, Map<String, double[]> offset) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            double[] b = BASE[i];
            double[] off = (offset == null) ? null : offset.get(IDS[i]);
            double ox = (off == null) ? 0.0 : off[0];
            double oy = (off == null) ? 0.0 : off[1];
            double oz = (off == null) ? 0.0 : off[2];
            out.put(IDS[i], new double[]{
                    rover[0] - b[0] - ox,
                    rover[1] - b[1] - oy,
                    rover[2] - b[2] - oz
            });
        }
        return out;
    }

    @Test
    @DisplayName("全网稳定：无漂移、闭合差为零，检核通过")
    void testStableNetwork() {
        BaselineConsistencyChecker checker = new BaselineConsistencyChecker();
        for (int ep = 0; ep < 40; ep++) {
            checker.feed("ep" + ep, vectors(ROVER, 3, null));
        }

        BaselineConsistencyChecker.Report report = checker.report();
        assertTrue(report.pass());
        assertTrue(report.suspectBaseIds().isEmpty());
        assertEquals(3, report.repeats().size());
        assertEquals(3, report.drifts().size());
        assertEquals(3, report.baseDrifts().size());

        for (BaselineConsistencyChecker.PairDrift pd : report.drifts()) {
            assertEquals(0.0, pd.driftNorm(), 1e-9, "稳定网基站间无漂移");
        }
        for (BaselineConsistencyChecker.LoopClosure lc : report.loops()) {
            assertTrue(lc.pass(), "稳定网闭合差应为零");
            assertEquals(0.0, lc.closure(), 1e-9);
        }
    }

    @Test
    @DisplayName("Rover 移动而基站不动：基站间漂移仍为零，检核通过")
    void testRoverMotionDoesNotTrigger() {
        BaselineConsistencyChecker checker = new BaselineConsistencyChecker();
        for (int ep = 0; ep < 40; ep++) {
            double[] rover = {ROVER[0] + 0.05 * ep, ROVER[1], ROVER[2]};
            checker.feed("ep" + ep, vectors(rover, 3, null));
        }

        BaselineConsistencyChecker.Report report = checker.report();
        assertTrue(report.pass(), "Rover 位移被完全抵消，不应误判基站");
        for (BaselineConsistencyChecker.PairDrift pd : report.drifts()) {
            assertEquals(0.0, pd.driftNorm(), 1e-9);
        }
        for (BaselineConsistencyChecker.BaseDrift bd : report.baseDrifts()) {
            assertEquals(0.0, bd.norm(), 1e-9);
        }
    }

    @Test
    @DisplayName("单基站位移：由 drift_ij 反算定位到具体基站")
    void testSingleBaseDisplacement() {
        Map<String, double[]> offset = new LinkedHashMap<>();
        offset.put("B", new double[]{0.02, 0.0, 0.0});

        BaselineConsistencyChecker checker = new BaselineConsistencyChecker();
        for (int ep = 0; ep < 40; ep++) {
            Map<String, double[]> off = (ep < 20) ? null : offset;
            checker.feed("ep" + ep, vectors(ROVER, 3, off));
        }

        BaselineConsistencyChecker.Report report = checker.report();
        assertFalse(report.pass());
        assertTrue(report.suspectBaseIds().contains("B"), "应定位到基站 B");

        Map<String, Double> deltaX = new LinkedHashMap<>();
        for (BaselineConsistencyChecker.BaseDrift bd : report.baseDrifts()) {
            deltaX.put(bd.baseId(), bd.delta()[0]);
        }

        // 重心基准下真位移 d 被摊成 (k−1)/k · d，k = 3 时为 2/3
        assertEquals(2.0 / 3.0 * 0.02, deltaX.get("B"), 1e-6);
        assertEquals(-1.0 / 3.0 * 0.02, deltaX.get("A"), 1e-6);
        assertEquals(-1.0 / 3.0 * 0.02, deltaX.get("C"), 1e-6);

        for (BaselineConsistencyChecker.BaseDrift bd : report.baseDrifts()) {
            if ("B".equals(bd.baseId())) {
                assertTrue(bd.norm() > checker.config().driftThreshold());
            } else {
                assertTrue(bd.norm() < checker.config().driftThreshold());
            }
        }
    }

    @Test
    @DisplayName("仅两基站：只有一个方程，无法区分谁动了，整对标记")
    void testTwoBasesCannotLocalize() {
        Map<String, double[]> offset = new LinkedHashMap<>();
        offset.put("B", new double[]{0.02, 0.0, 0.0});

        BaselineConsistencyChecker checker = new BaselineConsistencyChecker();
        for (int ep = 0; ep < 40; ep++) {
            Map<String, double[]> off = (ep < 20) ? null : offset;
            checker.feed("ep" + ep, vectors(ROVER, 2, off));
        }

        BaselineConsistencyChecker.Report report = checker.report();
        assertFalse(report.pass());
        assertEquals(2, report.suspectBaseIds().size());
        assertTrue(report.baseDrifts().isEmpty(), "k = 2 时不给出单站位移");
    }
}
