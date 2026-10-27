package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.engine.MultiBaselineAdjust;
import org.rtklib.java.adjust.engine.NetworkAdjustment;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MultiBaselineAdjust 的整时段网平差接线：
 * 注册基站坐标后累积真基线向量，可执行约束平差得到 Rover 坐标。
 */
class MultiBaselineAdjustNetworkTest {

    private static final double[] BASE_A = {-2.2e6, 4.4e6, 4.6e6};
    private static final double[] BASE_B = {-2.199e6, 4.4e6, 4.6e6};
    private static final double[] ROVER = {-2.1995e6, 4.4005e6, 4.6001e6};

    @Test
    @DisplayName("未注册基站时不累积、网平差失败")
    void testNoRegisteredBase() {
        MultiBaselineAdjust adjust = MultiBaselineAdjust.create();

        adjust.feedBaseline("A", fixSol(ROVER), "t0");
        adjust.feedBaseline("B", fixSol(ROVER), "t0");
        adjust.feedBaseline("A", fixSol(ROVER), "t1");
        adjust.feedBaseline("B", fixSol(ROVER), "t1");

        assertEquals(0, adjust.getNetworkObsCount());
        assertTrue(!adjust.computeNetworkAdjustment(false).success());
    }

    @Test
    @DisplayName("注册基站后累积真基线向量，约束平差恢复 Rover 坐标")
    void testConstrainedRecoversRover() {
        MultiBaselineAdjust adjust = MultiBaselineAdjust.create();
        adjust.registerBasePosition("A", BASE_A);
        adjust.registerBasePosition("B", BASE_B);

        adjust.feedBaseline("A", fixSol(ROVER), "t0");
        adjust.feedBaseline("B", fixSol(ROVER), "t0");
        adjust.feedBaseline("A", fixSol(ROVER), "t1");
        adjust.feedBaseline("B", fixSol(ROVER), "t1");
        adjust.flush();   // 末历元在换历元/收尾时才处理

        assertEquals(2, adjust.getRegisteredBaseCount());
        assertEquals(4, adjust.getNetworkObsCount(), "2 历元 x 2 基站");

        NetworkAdjustment.Result r = adjust.computeNetworkAdjustment(false);

        assertTrue(r.success(), r.message());
        assertNotNull(r.roverXyz());
        assertEquals(ROVER[0], r.roverXyz()[0], 0.01);
        assertEquals(ROVER[1], r.roverXyz()[1], 0.01);
        assertEquals(ROVER[2], r.roverXyz()[2], 0.01);
    }

    @Test
    @DisplayName("自由网平差可执行并给出基站位移")
    void testFreeNetworkRuns() {
        MultiBaselineAdjust adjust = MultiBaselineAdjust.create();
        adjust.registerBasePosition("A", BASE_A);
        adjust.registerBasePosition("B", BASE_B);

        adjust.feedBaseline("A", fixSol(ROVER), "t0");
        adjust.feedBaseline("B", fixSol(ROVER), "t0");
        adjust.feedBaseline("A", fixSol(ROVER), "t1");
        adjust.feedBaseline("B", fixSol(ROVER), "t1");
        adjust.flush();

        NetworkAdjustment.Result r = adjust.computeNetworkAdjustment(true);

        assertTrue(r.success(), r.message());
        assertNotNull(r.baseDelta());
        assertEquals(2, r.baseDelta().size());
        assertTrue(adjust.getNetworkObsCount() > 0);
    }

    @Test
    @DisplayName("基站漂移触发一致性告警（定位到具体基站）")
    void testConsistencyListenerFires() {
        MultiBaselineAdjust adjust = MultiBaselineAdjust.create();
        java.util.List<String> alerts = new java.util.ArrayList<>();
        adjust.onConsistency((baseId, driftNorm, tag) -> alerts.add(baseId));

        double[] baseA = {-2.2e6, 4.4e6, 4.6e6};
        double[] baseB = {-2.199e6, 4.4e6, 4.6e6};
        double[] baseC = {-2.2e6, 4.401e6, 4.6e6};
        adjust.registerBasePosition("A", baseA);
        adjust.registerBasePosition("B", baseB);
        adjust.registerBasePosition("C", baseC);

        // 前 4 历元一致；后 4 历元 B 的解算结果整体偏移 3.6cm（等效基站移动）
        for (int i = 0; i < 8; i++) {
            double offset = i < 4 ? 0.0 : 0.036;
            adjust.feedBaseline("A", fixSol(ROVER), "e" + i);
            adjust.feedBaseline("B", fixSol(new double[]{
                    ROVER[0] + offset, ROVER[1], ROVER[2]}), "e" + i);
            adjust.feedBaseline("C", fixSol(ROVER), "e" + i);
        }
        adjust.flush();

        assertTrue(alerts.contains("B"), "应告警基站 B，实际=" + alerts);
    }

    private static SolData fixSol(double[] xyz) {
        Sol sol = new Sol();
        sol.time = new GTime();
        sol.rr = new double[]{xyz[0], xyz[1], xyz[2], 0, 0, 0};
        sol.qr = new float[]{1.0e-4f, 1.0e-4f, 1.0e-4f, 0f, 0f, 0f};
        sol.stat = (byte) SolutionStatus.FIX.code;
        sol.ns = (byte) 12;
        sol.ratio = 5.0f;
        sol.dtr = new double[]{0};
        sol.qv = new float[]{0, 0, 0, 0, 0, 0};
        return new SolData(sol, PrcOpt.POS_ECEF);
    }
}
