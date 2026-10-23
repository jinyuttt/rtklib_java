package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexSppProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("阶段1: SPP单站单天链路验证")
public class SppValidationTest {

    private static final Logger log = LoggerFactory.getLogger(SppValidationTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "spp" + File.separator + "algo";

    private static String obsFile;
    private static String navFile;
    private static boolean dataAvailable;

    static boolean dataAvailable() {
        return dataAvailable;
    }

    private static final int TEST_YEAR = 2024;
    private static final int TEST_DOY = 60;

    private static final double ALGO_X = 918129.4000;
    private static final double ALGO_Y = -4346071.2000;
    private static final double ALGO_Z = 4561977.8000;

    @BeforeAll
    static void checkData() {
        if (!GnssValidationDataHelper.isDataAvailable(DATA_DIR)) {
            log.info("SPP数据不在本地，尝试自动下载ALGO RINEX 3...");
            GnssValidationDataHelper helper = new GnssValidationDataHelper(TestDataConfig.getTestDataDir());
            helper.ensureIgsStationRinex("spp", "algo", TEST_YEAR, TEST_DOY);
        }

        File dir = new File(DATA_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            dataAvailable = false;
            log.info("SPP数据目录不存在: {}", DATA_DIR);
            return;
        }

        File[] obsFiles = dir.listFiles((d, n) ->
                n.contains("_MO.rnx") || n.endsWith(".obs")
                        || n.endsWith(".25o") || n.endsWith(".24o") || n.endsWith(".23o"));
        File[] navFiles = dir.listFiles((d, n) ->
                n.contains("_MN.rnx") || n.contains("_GN.rnx") || n.endsWith(".nav")
                        || n.endsWith(".25n") || n.endsWith(".24n") || n.endsWith(".23n"));

        if (obsFiles != null && obsFiles.length > 0 && navFiles != null && navFiles.length > 0) {
            obsFile = obsFiles[0].getAbsolutePath();
            navFile = navFiles[0].getAbsolutePath();
            dataAvailable = true;
            log.info("SPP数据: obs={}, nav={}", obsFile, navFile);
        } else {
            dataAvailable = false;
            log.info("SPP数据文件未找到于: {}", DATA_DIR);
        }
    }

    @Test
    @DisplayName("1.1 RINEX解析与SPP解算链路通")
    void testSppPipeline() {
        if (!dataAvailable) {
            log.info("跳过: 数据不可用，请将ALGO RINEX 3观测+导航文件放入 {}", DATA_DIR);
            return;
        }

        PrcOpt opt = RinexSppProcessor.createDefaultOpt();
        RinexSppProcessor spp = new RinexSppProcessor(opt);
        RinexSppProcessor.SppResult result = spp.process(obsFile, navFile);

        log.info("SPP结果: 总历元={}, 成功={}, 失败={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "应有历元被处理");
        assertTrue(result.successCount > 0, "应有成功解算的历元");
    }

    @Test
    @DisplayName("1.2 坐标时序输出与合理性检查")
    void testSppCoordinateTimeSeries() {
        if (!dataAvailable) {
            log.info("跳过: 数据不可用");
            return;
        }

        PrcOpt opt = RinexSppProcessor.createDefaultOpt();
        RinexSppProcessor spp = new RinexSppProcessor(opt);
        RinexSppProcessor.SppResult result = spp.process(obsFile, navFile);

        List<SolData> solutions = result.solutions;
        assertNotNull(solutions, "解算结果不应为null");
        assertFalse(solutions.isEmpty(), "解算结果不应为空");

        int validCount = 0;
        double sumLat = 0, sumLon = 0, sumH = 0;
        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.NONE) continue;
            Position llh = sol.getPosition(CoordType.LLH);
            if (llh == null) continue;

            double lat = llh.v1;
            double lon = llh.v2;
            double h = llh.v3;

            assertTrue(Math.abs(lat) > 1 && Math.abs(lat) < 89, "纬度应在合理范围: " + lat);
            assertTrue(Math.abs(lon) > 1 && Math.abs(lon) < 180, "经度应在合理范围: " + lon);
            assertTrue(Math.abs(h) < 9000, "高度应在合理范围: " + h);

            sumLat += lat;
            sumLon += lon;
            sumH += h;
            validCount++;
        }

        assertTrue(validCount > 0, "应有有效坐标解");
        double avgLat = sumLat / validCount;
        double avgLon = sumLon / validCount;
        double avgH = sumH / validCount;
        log.info(String.format("SPP坐标时序: 有效历元=%d, 平均纬度=%.4f°, 平均经度=%.4f°, 平均高度=%.1fm",
                validCount, avgLat, avgLon, avgH));
    }

    @Test
    @DisplayName("1.3 SPP解算成功率统计")
    void testSppSuccessRate() {
        if (!dataAvailable) {
            log.info("跳过: 数据不可用");
            return;
        }

        PrcOpt opt = RinexSppProcessor.createDefaultOpt();
        RinexSppProcessor spp = new RinexSppProcessor(opt);
        RinexSppProcessor.SppResult result = spp.process(obsFile, navFile);

        double successRate = (double) result.successCount / result.totalEpochs * 100;
        log.info(String.format("SPP成功率: %d/%d = %.1f%%", result.successCount, result.totalEpochs, successRate));
        assertTrue(successRate > 50, "SPP成功率应>50%（单站广播星历）");
    }

    @Test
    @DisplayName("1.4 SPP解算ENU RMS精度验证（对比ALGO站ITRF已知坐标）")
    void testSppEnuRmsAccuracy() {
        if (!dataAvailable) {
            log.info("跳过: 数据不可用");
            return;
        }

        double[] refPos = new double[3];
        CoordTransform.ecef2pos(new double[]{ALGO_X, ALGO_Y, ALGO_Z}, refPos);

        PrcOpt opt = RinexSppProcessor.createDefaultOpt();
        RinexSppProcessor spp = new RinexSppProcessor(opt);
        RinexSppProcessor.SppResult result = spp.process(obsFile, navFile);

        List<SolData> solutions = result.solutions;
        assertNotNull(solutions, "解算结果不应为null");

        int validCount = 0;
        double sumE = 0, sumN = 0, sumU = 0;
        double sumE2 = 0, sumN2 = 0, sumU2 = 0;
        double maxE = 0, maxN = 0, maxU = 0;

        double[] dr = new double[3];
        double[] enu = new double[3];

        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.NONE) continue;
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;

            dr[0] = ecef.v1 - ALGO_X;
            dr[1] = ecef.v2 - ALGO_Y;
            dr[2] = ecef.v3 - ALGO_Z;

            CoordTransform.ecef2enu(refPos, dr, enu);

            double e = enu[0], n = enu[1], u = enu[2];
            sumE += e;  sumN += n;  sumU += u;
            sumE2 += e * e;  sumN2 += n * n;  sumU2 += u * u;
            maxE = Math.max(maxE, Math.abs(e));
            maxN = Math.max(maxN, Math.abs(n));
            maxU = Math.max(maxU, Math.abs(u));
            validCount++;
        }

        assertTrue(validCount > 0, "应有有效SPP解算历元");

        double meanE = sumE / validCount;
        double meanN = sumN / validCount;
        double meanU = sumU / validCount;

        double rmsE = Math.sqrt(sumE2 / validCount);
        double rmsN = Math.sqrt(sumN2 / validCount);
        double rmsU = Math.sqrt(sumU2 / validCount);
        double rms2D = Math.sqrt((sumE2 + sumN2) / validCount);

        double stdE = Math.sqrt(sumE2 / validCount - meanE * meanE);
        double stdN = Math.sqrt(sumN2 / validCount - meanN * meanN);
        double stdU = Math.sqrt(sumU2 / validCount - meanU * meanU);

        log.info("====== SPP ENU RMS 精度评估 ======");
        log.info(String.format("参考站: ALGO (ITRF)  X=%.4f  Y=%.4f  Z=%.4f", ALGO_X, ALGO_Y, ALGO_Z));
        log.info(String.format("参考LLH: lat=%.6f° lon=%.6f° h=%.3fm",
                refPos[0] / Constants.D2R, refPos[1] / Constants.D2R, refPos[2]));
        log.info(String.format("有效历元: %d / %d", validCount, result.totalEpochs));
        log.info("--- 偏差(bias) / 标准差(std) / RMS / 最大值 ---");
        log.info(String.format("  E(东): bias=%+7.3f m  std=%6.3f m  RMS=%6.3f m  max=%6.3f m", meanE, stdE, rmsE, maxE));
        log.info(String.format("  N(北): bias=%+7.3f m  std=%6.3f m  RMS=%6.3f m  max=%6.3f m", meanN, stdN, rmsN, maxN));
        log.info(String.format("  U(天): bias=%+7.3f m  std=%6.3f m  RMS=%6.3f m  max=%6.3f m", meanU, stdU, rmsU, maxU));
        log.info(String.format("  2D水平RMS = %6.3f m", rms2D));
        log.info("--- SPP物理极限判据 ---");
        log.info(String.format("  水平(E/N) RMS 1~2m: E=%.3f %s  N=%.3f %s",
                rmsE, (rmsE >= 0.5 && rmsE <= 3.0) ? "✓" : "✗",
                rmsN, (rmsN >= 0.5 && rmsN <= 3.0) ? "✓" : "✗"));
        log.info(String.format("  高程(U)   RMS 3~5m: U=%.3f %s",
                rmsU, (rmsU >= 1.0 && rmsU <= 8.0) ? "✓" : "✗"));
        log.info("=================================");

        assertTrue(rmsE < 5.0, "E方向RMS应<5m（SPP广播星历）: " + rmsE);
        assertTrue(rmsN < 5.0, "N方向RMS应<5m（SPP广播星历）: " + rmsN);
        assertTrue(rmsU < 10.0, "U方向RMS应<10m（SPP广播星历）: " + rmsU);
    }
}