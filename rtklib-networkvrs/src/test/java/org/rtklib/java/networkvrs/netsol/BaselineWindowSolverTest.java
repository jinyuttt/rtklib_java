package org.rtklib.java.networkvrs.netsol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.networkvrs.config.NetworkConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineWindowSolverTest {

    private static final double F1 = 1561.098e6;
    private static final double F2 = 1268.52e6;
    private static final double WL = Constants.CLIGHT / (F1 - F2);

    @Test
    @DisplayName("合成数据：宽巷与窄巷均固定到真值")
    void testFixSynthetic() {
        int refSat = 100;
        int nAmb = 5;
        int[] n1True = new int[nAmb];
        int[] nwlTrue = new int[nAmb];
        List<DdEpoch> epochs = new ArrayList<>();
        for (int e = 0; e < 10; e++) {
            List<DdObservation> observations = new ArrayList<>();
            for (int i = 0; i < nAmb; i++) {
                int sat = 101 + i;
                n1True[i] = 20 + i;
                nwlTrue[i] = 7 + (i % 2);
                int n2 = n1True[i] - nwlTrue[i];
                double nif = BaselineWindowSolution.ifAmbiguityMeters(F1, F2, n1True[i], n2);
                double noise = 0.001 * Math.sin(e * 0.7 + i);
                observations.add(new DdObservation(
                        sat, refSat, Constants.SYS_CMP, F1, F2, WL,
                        0.0, nif + noise, 0.2 * Math.cos(e * 0.5 + i),
                        0.0, nwlTrue[i] + 0.01 * Math.sin(e + i),
                        0.0,
                        0.1 * (i + 1), 0.2, 0.3, 0.0, 0.0,
                        45.0, 60.0));
            }
            epochs.add(new DdEpoch(new GTime(2270000000L + e, 0.0), observations));
        }

        NetworkConfig config = new NetworkConfig();
        config.solutionWindowEpochs = 10;
        config.minFixedAmbiguities = 4;
        BaselineWindowSolver solver = new BaselineWindowSolver(config);
        BaselineWindowSolution solution = solver.solve("A", "B",
                new double[3], new double[3], epochs);

        assertNotNull(solution);
        assertTrue(solution.fixed(), "窄巷应固定");
        assertTrue(solution.ratio() >= config.arRatioThreshold);
        assertEquals(nAmb, solution.ambiguityCount());

        Map<Integer, Integer> fixed = solution.fixedN1();
        for (int i = 0; i < nAmb; i++) {
            assertEquals(n1True[i], fixed.get(101 + i), "N1 应固定到真值");
        }
        assertTrue(solution.phaseRms() < 0.1);
    }

    @Test
    @DisplayName("采样间隔取中位数：缺测占比过半时仍按标称间隔判间隙")
    void testSamplingIntervalUsesMedianNotMean() {
        // 11 个历元、10 个间隔，交替 30 s / 120 s（缺测占比 50%）。
        // 均值 = 75 s → 间隙阈值 3×75 = 225 s，所有 120 s 间隔都漏判；
        // 下中位数 = 30 s → 阈值 90 s，每个 120 s 间隔都被判为切弧。
        // 该用例能区分两种估计量，是「数据越稀越放宽」这一正反馈的回归护栏。
        int refSat = 100;
        int nAmb = 5;
        double[] dts = {30, 120, 30, 120, 30, 120, 30, 120, 30, 120};
        List<DdEpoch> epochs = new ArrayList<>();
        double time = 2270000000L;
        for (int e = 0; e <= dts.length; e++) {
            if (e > 0) {
                time += dts[e - 1];
            }
            List<DdObservation> observations = new ArrayList<>();
            for (int i = 0; i < nAmb; i++) {
                double nif = 1.0 + i;
                observations.add(new DdObservation(
                        101 + i, refSat, Constants.SYS_CMP, F1, F2, WL,
                        0.0, nif, 0.0, 0.0, 7.0 + (i % 2),
                        0.0,
                        0.1 * (i + 1), 0.2, 0.3, 0.0, 0.0,
                        45.0, 60.0));
            }
            epochs.add(new DdEpoch(new GTime((long) time, 0.0), observations));
        }

        BaselineWindowSolver solver = new BaselineWindowSolver(new NetworkConfig());
        BaselineWindowSolution solution = solver.solve("A", "B",
                new double[3], new double[3], epochs);

        assertNotNull(solution);
        BaselineWindowSolution.ArDiagnostics d = solution.diagnostics();
        assertNotNull(d, "应输出归因诊断量");
        assertEquals(5 * nAmb, d.gapCuts(),
                "10 个间隔中 5 个为 120 s，应按 30 s 标称间隔逐个切弧");
    }

    @Test
    @DisplayName("宽巷不连续（周跳）时不固定")
    void testWideLaneScatterRejectsFix() {
        int refSat = 100;
        int nAmb = 5;
        List<DdEpoch> epochs = new ArrayList<>();
        for (int e = 0; e < 10; e++) {
            List<DdObservation> observations = new ArrayList<>();
            for (int i = 0; i < nAmb; i++) {
                double nwl = 7 + (i % 2) + (e >= 5 ? 0.5 : 0.0);
                double nif = 1.0 + i;
                observations.add(new DdObservation(
                        101 + i, refSat, Constants.SYS_CMP, F1, F2, WL,
                        0.0, nif, 0.0, 0.0, nwl,
                        0.0,
                        0.1 * (i + 1), 0.2, 0.3, 0.0, 0.0,
                        45.0, 60.0));
            }
            epochs.add(new DdEpoch(new GTime(2270000000L + e, 0.0), observations));
        }

        BaselineWindowSolver solver = new BaselineWindowSolver(new NetworkConfig());
        BaselineWindowSolution solution = solver.solve("A", "B",
                new double[3], new double[3], epochs);
        assertNotNull(solution);
        assertTrue(!solution.fixed(), "宽巷散度超限时不应固定");
        assertTrue(solution.fixedN1().isEmpty());
    }
}