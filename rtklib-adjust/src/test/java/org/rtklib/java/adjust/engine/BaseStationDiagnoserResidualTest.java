package org.rtklib.java.adjust.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.model.AdjustDiagnosisConfig;
import org.rtklib.java.adjust.model.AdjustResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BaseStationDiagnoser#identifyByResidual()} 测试：k ≥ 3 时按 Baarda 残差定位异常基站。
 */
public class BaseStationDiagnoserResidualTest {

    private static final String[] IDS = {"A", "B", "C"};

    private static AdjustResult result(double[] baarda) {
        return AdjustResult.success("ep", null, null, null, 1.0, 10, null,
                baarda, null, 3);
    }

    private static BaseStationDiagnoser diagnoser() {
        // enable = false：隔离残差累积逻辑，不触发深度诊断（深度诊断依赖 GnssDataSource）
        return new BaseStationDiagnoser(
                AdjustDiagnosisConfig.builder().enable(false).build());
    }

    private static void feed(BaseStationDiagnoser d, double baselineWorst, double singleWorst) {
        double[] w = new double[9];
        for (int i = 0; i < 3; i++) {
            double v = (i == 1) ? singleWorst : baselineWorst;
            w[3 * i] = v;
            w[3 * i + 1] = v * 0.5;
            w[3 * i + 2] = v * 0.3;
        }
        d.feedAdjustResult(result(w), IDS);
    }

    @Test
    @DisplayName("单基站残差显著偏大：定位到该基站")
    void testLocatesSingleSuspect() {
        BaseStationDiagnoser d = diagnoser();
        for (int ep = 0; ep < 20; ep++) {
            feed(d, 1.2, 6.0);
        }
        assertEquals(List.of("B"), d.identifyByResidual());
    }

    @Test
    @DisplayName("全网残差一致偏大：不误判单站")
    void testAllBadDoesNotAccuse() {
        BaseStationDiagnoser d = diagnoser();
        for (int ep = 0; ep < 20; ep++) {
            feed(d, 5.0, 5.0);
        }
        assertTrue(d.identifyByResidual().isEmpty());
    }

    @Test
    @DisplayName("残差低于临界值：不告警")
    void testBelowCritical() {
        BaseStationDiagnoser d = diagnoser();
        for (int ep = 0; ep < 20; ep++) {
            feed(d, 1.0, 2.0);
        }
        assertTrue(d.identifyByResidual().isEmpty());
    }

    @Test
    @DisplayName("基站数不足 3：无法定位，返回空")
    void testTooFewBases() {
        BaseStationDiagnoser d = diagnoser();
        for (int ep = 0; ep < 20; ep++) {
            d.feedAdjustResult(result(new double[]{1.0, 0.5, 0.3, 9.0, 0.5, 0.3}),
                    new String[]{"A", "B"});
        }
        assertTrue(d.identifyByResidual().isEmpty());
    }

    @Test
    @DisplayName("清空诊断后残差统计同步失效")
    void testClearResetsStats() {
        BaseStationDiagnoser d = diagnoser();
        for (int ep = 0; ep < 20; ep++) {
            feed(d, 1.2, 6.0);
        }
        assertEquals(List.of("B"), d.identifyByResidual());

        d.clearDiagnosis("B");
        assertTrue(d.identifyByResidual().isEmpty());

        for (int ep = 0; ep < 20; ep++) {
            feed(d, 1.2, 6.0);
        }
        d.clearAllDiagnosis();
        assertTrue(d.identifyByResidual().isEmpty());
    }
}
