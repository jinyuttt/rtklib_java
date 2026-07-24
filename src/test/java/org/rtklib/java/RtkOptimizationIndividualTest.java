package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTK 优化项逐项测试")
public class RtkOptimizationIndividualTest {

    private static final Logger log = LoggerFactory.getLogger(RtkOptimizationIndividualTest.class);

    private static final String ROVER_PATH = "C:\\Users\\Admin\\Desktop\\over.rtcm3";
    private static final String BASE_PATH = "C:\\Users\\Admin\\Desktop\\base.rtcm3";
    private static final String RESULT_DIR = "D:\\code\\rtklib_java\\rtk_compare\\optimization_tests";

    private static byte[] roverData;
    private static byte[] baseData;

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        }
        new File(RESULT_DIR).mkdirs();
        log.info("=== Loaded Rover: {} bytes, Base: {} bytes ===", roverData.length, baseData.length);
    }

    @Test
    @Order(0)
    @DisplayName("0. 基线配置（无任何优化）")
    void testBaseline() {
        runTestWithConfig("0_Baseline", createBaseConfig(), null);
    }

    @Test
    @Order(1)
    @DisplayName("1. 滑动窗自适应Q矩阵 (enableAdaptiveQ)")
    void testAdaptiveQ() {
        RtkConfig config = createBaseConfig();
        config.enableAdaptiveQ = true;
        runTestWithConfig("1_AdaptiveQ", config, "enableAdaptiveQ");
    }

    @Test
    @Order(2)
    @DisplayName("2. IGGIII抗差估计 (enableIggiii)")
    void testIggiii() {
        RtkConfig config = createBaseConfig();
        config.enableIggiii = true;
        runTestWithConfig("2_Iggiii", config, "enableIggiii");
    }

    @Test
    @Order(3)
    @DisplayName("3. SNR中值参考星选择 (enableSnrMedian)")
    void testSnrMedian() {
        RtkConfig config = createBaseConfig();
        config.enableSnrMedian = true;
        runTestWithConfig("3_SnrMedian", config, "enableSnrMedian");
    }

    @Test
    @Order(4)
    @DisplayName("4. PAR参考星重选 (enableParRefReselect)")
    void testParRefReselect() {
        RtkConfig config = createBaseConfig();
        config.enableParRefReselect = true;
        runTestWithConfig("4_ParRefReselect", config, "enableParRefReselect");
    }

    @Test
    @Order(5)
    @DisplayName("5. 电离层/对流层梯度参数估计 (enableIonoTropGradient)")
    void testIonoTropGradient() {
        RtkConfig config = createBaseConfig();
        config.enableIonoTropGradient = true;
        runTestWithConfig("5_IonoTropGradient", config, "enableIonoTropGradient");
    }

    @Test
    @Order(6)
    @DisplayName("6. 模糊度子集锚固 (enableAmbAnchor)")
    void testAmbAnchor() {
        RtkConfig config = createBaseConfig();
        config.enableAmbAnchor = true;
        config.ambAnchorMinFixCount = 50;  // 降低阈值以便在1小时内看到效果
        runTestWithConfig("6_AmbAnchor", config, "enableAmbAnchor");
    }

    @Test
    @Order(7)
    @DisplayName("7. 大气参数自适应冻结 (atmFrozenNsThresh=5)")
    void testAtmFrozen() {
        RtkConfig config = createBaseConfig();
        config.atmFrozenNsThresh = 5;  // 5颗卫星以下冻结大气参数
        runTestWithConfig("7_AtmFrozen", config, "atmFrozenNsThresh=5");
    }

    @Test
    @Order(8)
    @DisplayName("8. AdaptiveQ + IGGIII 组合")
    void testAdaptiveQPlusIggiii() {
        RtkConfig config = createBaseConfig();
        config.enableAdaptiveQ = true;
        config.enableIggiii = true;
        runTestWithConfig("8_AdaptiveQ+Iggiii", config, "AdaptiveQ+Iggiii");
    }

    @Test
    @Order(9)
    @DisplayName("9. 全部优化项组合")
    void testAllOptimizations() {
        RtkConfig config = createBaseConfig();
        config.enableAdaptiveQ = true;
        config.enableIggiii = true;
        config.enableSnrMedian = true;
        config.enableParRefReselect = true;
        config.enableIonoTropGradient = true;
        config.enableAmbAnchor = true;
        config.atmFrozenNsThresh = 5;
        config.ambAnchorMinFixCount = 50;
        runTestWithConfig("9_All_Optimizations", config, "ALL");
    }

    private PrcOpt createBaseOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL
                | Constants.SYS_CMP | Constants.SYS_QZS | Constants.SYS_SBS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.bdsmodear = 1;
        opt.gpsmodear = 1;
        opt.glomodear = Constants.GLO_ARMODE_FIXHOLD;
        opt.arfilter = 1;
        opt.dynamics = 0;
        opt.thresar[0] = 3.0;
        opt.thresar[1] = 0.1;
        opt.elmaskar = 25.0 * Constants.D2R;
        opt.minfix = 20;
        opt.minfixsats = 4;
        opt.minholdsats = 5;
        opt.mindropsats = 10;
        opt.varholdamb = 0.1;
        opt.gainholdamb = 0.01;
        opt.intpref = 1;
        opt.maxtdiff = 30.0;
        opt.outsingle = 0;
        opt.prn[0] = 0;
        opt.prn[1] = 0.001;
        opt.prn[2] = 0.0001;
        opt.prn[3] = 3;
        opt.prn[4] = 1;
        opt.eratio[0] = 300;
        opt.eratio[1] = 300;
        opt.err[1] = 0.005;
        opt.err[2] = 0.005;
        opt.err[3] = 0.0;
        opt.std[0] = 30.0;
        opt.std[1] = 0.03;
        opt.std[2] = 0.3;
        opt.refposmode = Constants.REFPOS_RTCM;
        return opt;
    }

    private RtkConfig createBaseConfig() {
        return new RtkConfig();
    }

    private List<Sol> runRtk(String testName, PrcOpt baseOpt, RtkConfig rtkConfig) {
        List<Sol> solutions = Collections.synchronizedList(new ArrayList<>());

        RtkProcessor rtk = new RtkProcessor(baseOpt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                solutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {}
            @Override public void onFinish(int total, int success, int fail) {}
        });
        if (rtkConfig != null) {
            rtk.getRtk().rtkConfig = rtkConfig;
        }

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("[{}] Total epochs: {}, Success: {}, Fail: {}, Solutions: {}",
                testName, result.totalEpochs, result.successCount, result.failCount, solutions.size());

        return solutions;
    }

    private double[] avgLastN(List<Sol> solutions, int n) {
        if (solutions.isEmpty()) return new double[]{0, 0, 0};

        int start = Math.max(0, solutions.size() - n);
        double sumLat = 0, sumLon = 0, sumH = 0;

        for (int i = start; i < solutions.size(); i++) {
            double[] llh = new double[3];
            CoordTransform.ecef2pos(solutions.get(i).rr, llh);
            sumLat += llh[0];
            sumLon += llh[1];
            sumH += llh[2];
        }

        int count = solutions.size() - start;
        return new double[]{sumLat / count, sumLon / count, sumH / count};
    }

    private void printSolutionStats(List<Sol> solutions, String label) {
        if (solutions.isEmpty()) {
            log.warn("[{}] No solutions!", label);
            return;
        }

        int fixCount = 0, floatCount = 0, singleCount = 0, otherCount = 0;
        for (Sol sol : solutions) {
            if (sol.stat == Constants.SOLQ_FIX) fixCount++;
            else if (sol.stat == Constants.SOLQ_FLOAT) floatCount++;
            else if (sol.stat == Constants.SOLQ_SINGLE) singleCount++;
            else otherCount++;
        }

        int total = solutions.size();
        log.info("[{}] Solution stats: Fix={} ({:.1f}%), Float={}, Single={}, Other={}",
                label, fixCount, total > 0 ? 100.0*fixCount/total : 0,
                floatCount, singleCount, otherCount);

        if (!solutions.isEmpty()) {
            double[] lastN = avgLastN(solutions, Math.min(20, solutions.size()));
            log.info("[{}] Last-{} avg position: lat={:.9f}°, lon={:.9f}°, h={:.3f}m",
                    label, Math.min(20, solutions.size()),
                    Math.toDegrees(lastN[0]), Math.toDegrees(lastN[1]), lastN[2]);
        }
    }

    private void writePosFile(String testName, List<Sol> solutions) {
        String filePath = RESULT_DIR + "\\" + testName + ".pos";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath))) {
            pw.printf("%% RTK Optimization Test: %s%n", testName);
            pw.printf("%% Generated: %s%n", new Date());
            pw.println("% GPST                  latitude(deg) longitude(deg)  height(m)   Q  ns");

            for (Sol sol : solutions) {
                double[] llh = new double[3];
                CoordTransform.ecef2pos(sol.rr, llh);
                pw.printf("%10d %10.3f   %14.9f  %14.9f  %10.4f   %d  %d%n",
                        sol.time.time, sol.time.sec,
                        Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2],
                        sol.stat, sol.ns);
            }
        } catch (Exception e) {
            log.error("[{}] Failed to write pos file: {}", testName, e.getMessage());
        }
        log.info("[{}] Results written to: {}", testName, filePath);
    }

    private void runTestWithConfig(String testName, RtkConfig rtkConfig, String enabledOpt) {
        log.info("========== Test: {} ({}) ==========", testName, enabledOpt);

        long startTime = System.currentTimeMillis();

        PrcOpt opt = createBaseOpt();
        List<Sol> solutions = runRtk(testName, opt, rtkConfig);

        long elapsed = System.currentTimeMillis() - startTime;

        printSolutionStats(solutions, testName);
        writePosFile(testName, solutions);

        log.info("[{}] Processing time: {} ms", testName, elapsed);

        assertTrue(solutions.size() > 0, testName + " should produce solutions");
    }
}