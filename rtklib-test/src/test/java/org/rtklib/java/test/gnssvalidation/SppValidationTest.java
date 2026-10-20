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
            + File.separator + "gnss-validation" + File.separator + "spp" + File.separator + "bjfs";

    private static final String REFERENCE_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "reference";

    private static String obsFile;
    private static String navFile;
    private static boolean dataAvailable;

    static boolean dataAvailable() {
        return dataAvailable;
    }

    @BeforeAll
    static void checkData() {
        File dir = new File(DATA_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            dataAvailable = false;
            log.info("SPP数据目录不存在: {}", DATA_DIR);
            return;
        }

        File[] obsFiles = dir.listFiles((d, n) -> n.endsWith(".25o") || n.endsWith(".24o") || n.endsWith(".23o") || n.endsWith(".22o") || n.endsWith(".21o") || n.endsWith(".18o") || n.endsWith(".obs"));
        File[] navFiles = dir.listFiles((d, n) -> n.endsWith(".25n") || n.endsWith(".24n") || n.endsWith(".23n") || n.endsWith(".22n") || n.endsWith(".21n") || n.endsWith(".18n") || n.endsWith(".nav"));

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
            log.info("跳过: 数据不可用，请将BJFS RINEX观测+导航文件放入 {}", DATA_DIR);
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

            double lat = llh.val1 * Constants.R2D;
            double lon = llh.val2 * Constants.R2D;
            double h = llh.val3;

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
        log.info("SPP坐标时序: 有效历元={}, 平均纬度={:.4f}°, 平均经度={:.4f}°, 平均高度={:.1f}m",
                validCount, avgLat, avgLon, avgH);
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
        log.info("SPP成功率: {}/{} = {:.1f}%", result.successCount, result.totalEpochs, successRate);
        assertTrue(successRate > 50, "SPP成功率应>50%（单站广播星历）");
    }
}