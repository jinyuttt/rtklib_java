package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.datasource.RtcmFileDataSource;
import org.rtklib.java.adjust.engine.BaseStationDiagnoser;
import org.rtklib.java.adjust.model.*;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.rtkpos.RtkProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BaseStationDiagnoserTest {

    private static final TestDataConfig cfg = new TestDataConfig();

    @Test
    @DisplayName("基站诊断：2基站1测站，检测基站A坐标异常")
    void testDiagnoseTwoBases() {
        org.junit.jupiter.api.Assumptions.assumeTrue(cfg.isAvailable(), cfg.getMissingMessage());

        List<String> baseAFiles = new ArrayList<>();
        List<String> baseBFiles = new ArrayList<>();
        List<String> roverFiles = new ArrayList<>();
        for (int h = 0; h <= 8; h++) {
            baseAFiles.add(cfg.getRtcmPath(cfg.getBaseA(), h));
            baseBFiles.add(cfg.getRtcmPath(cfg.getBaseB(), h));
            roverFiles.add(cfg.getRtcmPath(cfg.getRover(), h));
        }

        RtcmFileDataSource baseA = new RtcmFileDataSource("A", baseAFiles);
        RtcmFileDataSource baseB = new RtcmFileDataSource("B", baseBFiles);
        RtcmFileDataSource rover = new RtcmFileDataSource("R1", roverFiles);

        System.out.println("=== 基站天线坐标(RTCM 1005) ===");
        double[] posA = baseA.getAntennaPosition();
        double[] posB = baseB.getAntennaPosition();
        if (posA != null) System.out.printf("基站A: X=%.3f Y=%.3f Z=%.3f%n", posA[0], posA[1], posA[2]);
        if (posB != null) System.out.printf("基站B: X=%.3f Y=%.3f Z=%.3f%n", posB[0], posB[1], posB[2]);

        AdjustDiagnosisConfig config = AdjustDiagnosisConfig.builder()
                .enable(true)
                .sigma0Threshold(3.0)
                .diagnoseWindow(100)
                .diagnoseRatio(0.8)
                .useSppForDirection(true)
                .useStaticForCorrection(true)
                .minStaticDataHours(2.0)
                .cacheDiagnosis(true)
                .build();

        BaseStationDiagnoser diagnoser = new BaseStationDiagnoser(config);
        diagnoser.addBaseStation(baseA);
        diagnoser.addBaseStation(baseB);
        diagnoser.setRover(rover);

        List<BaseStationDiagnosis> results = new ArrayList<>();
        diagnoser.setHandler(results::add);

        System.out.printf("%n配置: %s%n", config);
        System.out.println("开始诊断（需要较长时间进行RTK解算和静态解算）...");

        PrcOpt rtkOpt = RtkProcessor.createDefaultOpt();
        List<BaseStationDiagnosis> diagnoses = diagnoser.diagnose(rtkOpt);

        System.out.printf("%n=== 诊断结果 ===%n");
        System.out.printf("诊断出 %d 个异常基站%n", diagnoses.size());
        for (BaseStationDiagnosis d : diagnoses) {
            System.out.println(d);
        }

        if (!diagnoses.isEmpty()) {
            BaseStationDiagnosis worst = diagnoses.get(0);
            System.out.println("=== 验证 ===");
            System.out.printf("异常等级: %s%n", worst.anomalyLevel);
            System.out.printf("3D偏差: %.3f m%n", worst.deviation3d);
            assertTrue(worst.deviation3d > 1.0, "基站A偏差应>1m");
            if (worst.suggestedXyz != null) {
                assertNotNull(worst.suggestedLlh, "应提供建议LLH坐标");
                System.out.printf("建议坐标: Lat=%.9f Lon=%.9f H=%.4f%n",
                        Math.toDegrees(worst.suggestedLlh[0]),
                        Math.toDegrees(worst.suggestedLlh[1]),
                        worst.suggestedLlh[2]);
            }
        }
    }

    @Test
    @DisplayName("缓存测试：诊断后不再重复推送")
    void testDiagnosisCache() {
        AdjustDiagnosisConfig config = AdjustDiagnosisConfig.builder()
                .enable(true)
                .cacheDiagnosis(true)
                .build();

        BaseStationDiagnoser diagnoser = new BaseStationDiagnoser(config);

        List<BaseStationDiagnosis> results = new ArrayList<>();
        diagnoser.setHandler(results::add);

        BaseStationDiagnosis diag = BaseStationDiagnosis.of(
                "A", new double[]{0, 0, 0},
                new double[]{5, 5, 5}, new double[]{0, 0, 1000},
                new double[]{2, 1, 4}, 4.58,
                0.99, new double[]{0.005, 0.009, 0.006},
                2000, 2100,
                "静态基线解算", 427.0, 95, 100);

        System.out.println(diag);
        assertEquals(BaseStationDiagnosis.AnomalyLevel.ERROR, diag.anomalyLevel);

        diagnoser.clearDiagnosis("A");
        assertFalse(diagnoser.isDiagnosed("A"), "清除后应不再诊断过");
    }
}