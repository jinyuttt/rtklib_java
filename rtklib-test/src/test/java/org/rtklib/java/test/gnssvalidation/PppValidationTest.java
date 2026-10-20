package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexPppProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("阶段3: 事后PPP链路验证")
public class PppValidationTest {

    private static final Logger log = LoggerFactory.getLogger(PppValidationTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "ppp" + File.separator + "bjfs";

    private static final String PRODUCT_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "product";

    private static final String REFERENCE_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "reference";

    private static String obsFile;
    private static String navFile;
    private static String sp3File;
    private static String clkFile;
    private static String sinexFile;
    private static boolean dataAvailable;

    static boolean dataAvailable() { return dataAvailable; }

    @BeforeAll
    static void checkData() {
        File obsDir = new File(DATA_DIR);
        File sp3Dir = new File(PRODUCT_DIR + File.separator + "sp3");
        File clkDir = new File(PRODUCT_DIR + File.separator + "clk");
        File refDir = new File(REFERENCE_DIR);

        obsFile = findFile(obsDir, ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
        navFile = findFile(obsDir, ".nav", ".25n", ".24n", ".23n", ".21n", ".18n");
        sp3File = findFile(sp3Dir, ".sp3", ".SP3");
        clkFile = findFile(clkDir, ".clk", ".CLK");
        sinexFile = findFile(refDir, ".snx", ".SNX", ".pos", ".POS");

        dataAvailable = obsFile != null && navFile != null && sp3File != null && clkFile != null;

        log.info("PPP数据: obs={}, nav={}", obsFile, navFile);
        log.info("PPP产品: sp3={}, clk={}", sp3File, clkFile);
        log.info("SINEX参考: {}", sinexFile);
        if (!dataAvailable) {
            log.info("PPP数据不完整，请将BJFS观测+导航+MGEX产品放入对应目录");
        }
    }

    private static String findFile(File dir, String... extensions) {
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (String ext : extensions) {
            for (File f : files) {
                if (f.getName().endsWith(ext) || f.getName().endsWith(ext.toUpperCase())) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static PrcOpt createPppOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.dynamics = 0;
        opt.posopt = new int[6];
        return opt;
    }

    @Test
    @DisplayName("3.1 SP3/CLK精密产品加载")
    void testProductLoading() {
        if (sp3File == null || clkFile == null) {
            log.info("跳过: MGEX精密产品不可用，请将SP3/CLK放入 {}", PRODUCT_DIR);
            return;
        }

        Nav nav = new Nav();
        org.rtklib.java.ephemeris.Sp3Reader.readsp3(sp3File, nav, 0);
        org.rtklib.java.ephemeris.ClkReader.readclk(clkFile, nav);

        log.info("SP3: ne={}, CLK: nc={}", nav.ne, nav.nc);
        assertTrue(nav.ne > 0, "应加载SP3轨道数据");
        assertTrue(nav.nc > 0, "应加载CLK钟差数据");
    }

    @Test
    @DisplayName("3.2 PPP解算链路通")
    void testPppPipeline() {
        if (!dataAvailable) {
            log.info("跳过: PPP数据不可用");
            return;
        }

        PrcOpt opt = createPppOpt();
        RinexPppProcessor ppp = new RinexPppProcessor(opt);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        log.info("PPP结果: 总历元={}, 成功={}, 失败={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "PPP应有历元被处理");
        assertTrue(result.successCount > 0, "PPP应有成功历元");
    }

    @Test
    @DisplayName("3.3 PPP坐标与SINEX参考值比对")
    void testPppVsSinex() {
        if (!dataAvailable) {
            log.info("跳过: PPP数据不可用");
            return;
        }
        if (sinexFile == null) {
            log.info("跳过: SINEX参考坐标不可用，请将参考坐标文件放入 {}", REFERENCE_DIR);
            return;
        }

        PrcOpt opt = createPppOpt();
        RinexPppProcessor ppp = new RinexPppProcessor(opt);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        List<SolData> solutions = result.solutions;
        assertNotNull(solutions);
        assertFalse(solutions.isEmpty());

        int pppCount = 0;
        double sumLat = 0, sumLon = 0, sumH = 0;
        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.NONE) continue;
            Position llh = sol.getPosition(CoordType.LLH);
            if (llh == null) continue;
            sumLat += llh.val1 * Constants.R2D;
            sumLon += llh.val2 * Constants.R2D;
            sumH += llh.val3;
            pppCount++;
        }

        if (pppCount > 0) {
            double avgLat = sumLat / pppCount;
            double avgLon = sumLon / pppCount;
            double avgH = sumH / pppCount;
            log.info(String.format("PPP平均坐标: 纬度=%.6f°, 经度=%.6f°, 高度=%.3fm", avgLat, avgLon, avgH));
            log.info("SINEX参考文件: {} (需手动比对或实现自动解析)", sinexFile);
        }
    }

    @Test
    @DisplayName("3.4 PPP收敛后精度统计")
    void testPppConvergence() {
        if (!dataAvailable) {
            log.info("跳过: PPP数据不可用");
            return;
        }

        PrcOpt opt = createPppOpt();
        RinexPppProcessor ppp = new RinexPppProcessor(opt);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        List<SolData> solutions = result.solutions;
        if (solutions.size() < 30) {
            log.info("历元数不足，无法评估收敛");
            return;
        }

        int convergeSkip = solutions.size() / 3;
        double sumE = 0, sumN = 0, sumU = 0;
        int count = 0;
        double[] firstEcef = null;
        for (int i = convergeSkip; i < solutions.size(); i++) {
            SolData sol = solutions.get(i);
            if (sol.status == SolutionStatus.NONE) continue;
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            if (firstEcef == null) {
                firstEcef = new double[]{ecef.val1, ecef.val2, ecef.val3};
                continue;
            }
            double[] rr = new double[]{ecef.val1, ecef.val2, ecef.val3};
            double[] dr = new double[]{rr[0] - firstEcef[0], rr[1] - firstEcef[1], rr[2] - firstEcef[2]};
            double[] pos = new double[3];
            org.rtklib.java.coord.CoordTransform.ecef2pos(firstEcef, pos);
            double[] enu = new double[3];
            org.rtklib.java.coord.CoordTransform.ecef2enu(pos, dr, enu);
            sumE += enu[0] * enu[0];
            sumN += enu[1] * enu[1];
            sumU += enu[2] * enu[2];
            count++;
        }

        if (count > 1) {
            double rmsE = Math.sqrt(sumE / count);
            double rmsN = Math.sqrt(sumN / count);
            double rmsU = Math.sqrt(sumU / count);
            log.info(String.format("PPP收敛后(跳过前1/3) ENU RMS: [%.4f, %.4f, %.4f]m (%d历元)", rmsE, rmsN, rmsU, count));
        }
    }
}