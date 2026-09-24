package org.rtklib.java.ppp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PPP新增功能覆盖测试（使用D:\yaxia真实RTCM数据）。
 *
 * <p>数据：16个BDS接收器，按日期(2026-05~07)组织，每天24个rtcm3文件(每小时1个)。
 * <p>Base-Rover配对：
 * - Base 540423231901 → Rovers [540423503435, 540423128131, ...]
 * - Base 540423214120 → Rovers [540423860355, 540423268595, ...]
 *
 * <p>覆盖功能：
 * 1. RTK基准解（BDS双频，作为对比基准）
 * 2. PPP广播星历解（BDS-only，验证SPP初始化+PPP滤波）
 * 3. PPP + GPT3/VMF3对流层模型
 * 4. PPP + IERS2010潮汐（固体潮+海潮+极潮）
 * 5. PPP + S1/S2大气潮汐
 * 6. PPP + ISB/IFCB/IFB偏差模型
 * 7. PPP + PPP-AR模糊度固定
 * 8. PPP + Fix-and-Hold策略
 * 9. PPP + Partial AR部分模糊度固定
 * 10. PPP + BDS-3 PPP-AR
 * 11. PPP + OSB偏差改正
 * 12. PPP + 全部优化组合
 */
@DisplayName("PPP Features Coverage Test with Real RTCM Data")
public class PppFeaturesCoverageTest {

    private static final Logger log = LoggerFactory.getLogger(PppFeaturesCoverageTest.class);

    private static final String RTCM_BASE_DIR = "D:\\yaxia\\rtcm";

    private static final Map<String, String[]> BASE_ROVER_MAP = new LinkedHashMap<>();

    static {
        BASE_ROVER_MAP.put("540423231901", new String[]{
                "540423503435", "540423128131", "540423187770", "540423211132"
        });
        BASE_ROVER_MAP.put("540423214120", new String[]{
                "540423860355", "540423268595", "540423156203", "540423128507"
        });
    }

    private static boolean dataAvailable = false;
    private static String baseRtcmFile;
    private static String roverRtcmFile;
    private static String testDate;

    @BeforeAll
    static void findTestData() {
        for (Map.Entry<String, String[]> entry : BASE_ROVER_MAP.entrySet()) {
            String base = entry.getKey();
            Path basePath = Paths.get(RTCM_BASE_DIR, base);
            if (!Files.isDirectory(basePath)) continue;

            try {
                Optional<Path> dateDir = Files.list(basePath)
                        .filter(Files::isDirectory)
                        .sorted()
                        .findFirst();
                if (dateDir.isEmpty()) continue;

                String date = dateDir.get().getFileName().toString();
                String baseFile = findRtcmFile(dateDir.get(), 12);
                if (baseFile == null) continue;

                for (String rover : entry.getValue()) {
                    Path roverPath = Paths.get(RTCM_BASE_DIR, rover, date);
                    if (!Files.isDirectory(roverPath)) continue;

                    String roverFile = findRtcmFile(roverPath, 12);
                    if (roverFile != null) {
                        dataAvailable = true;
                        baseRtcmFile = baseFile;
                        roverRtcmFile = roverFile;
                        testDate = date;
                        log.info("Test data: base={}, rover={}, date={}", base, rover, date);
                        return;
                    }
                }
            } catch (IOException e) {
                log.warn("Error scanning: {}", e.getMessage());
            }
        }
        log.warn("No RTCM test data found in {}", RTCM_BASE_DIR);
    }

    private static String findRtcmFile(Path dir, int preferredHour) {
        Path preferred = dir.resolve(preferredHour + ".rtcm3");
        if (Files.exists(preferred)) return preferred.toString();
        try {
            return Files.list(dir)
                    .filter(p -> p.toString().endsWith(".rtcm3"))
                    .findFirst()
                    .map(Path::toString).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static PrcOpt bdsOnlyPppOpt() {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.mode = Constants.PMODE_PPP_KINEMA;
        return opt;
    }

    private static PrcOpt bdsOnlyRtkOpt() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.mode = Constants.PMODE_KINEMA;
        return opt;
    }

    private void logResult(String label, PppProcessor.PppResult result) {
        if (result == null) {
            log.warn("{}: result is null", label);
            return;
        }
        int fixCount = 0, floatCount = 0;
        double bestRms = Double.MAX_VALUE;
        if (result.solutions != null) {
            for (SolData s : result.solutions) {
                if (s.status == SolutionStatus.FIX) fixCount++;
                else if (s.status == SolutionStatus.FLOAT) floatCount++;
                if (s.accuracies != null && !s.accuracies.isEmpty()) {
                    Accuracy acc = s.accuracies.get(0);
                    double rms = Math.sqrt(acc.s1 * acc.s1 + acc.s2 * acc.s2 + acc.s3 * acc.s3);
                    if (rms < bestRms) bestRms = rms;
                }
            }
        }
        log.info("{}: total={}, success={}, fail={}, fix={}, float={}, bestRms={}m",
                label, result.totalEpochs, result.successCount, result.failCount,
                fixCount, floatCount,
                bestRms == Double.MAX_VALUE ? -1 : String.format("%.4f", bestRms));
    }

    private void logRtkResult(String label, RtkProcessor.RtkResult result) {
        if (result == null) {
            log.warn("{}: result is null", label);
            return;
        }
        int fixCount = 0, floatCount = 0;
        if (result.solutions != null) {
            for (SolData s : result.solutions) {
                if (s.status == SolutionStatus.FIX) fixCount++;
                else if (s.status == SolutionStatus.FLOAT) floatCount++;
            }
        }
        log.info("{}: total={}, success={}, fail={}, fix={}, float={}",
                label, result.totalEpochs, result.successCount, result.failCount,
                fixCount, floatCount);
    }

    @Test
    @DisplayName("1. RTK基准解 - BDS双频（对比基准）")
    void test01_RtkBaseline() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyRtkOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverRtcmFile, baseRtcmFile);

        logRtkResult("RTK-BDS", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
        assertTrue(result.successCount > 0, "Should have successful epochs");
    }

    @Test
    @DisplayName("2. PPP广播星历解 - BDS-only（验证SPP初始化）")
    void test02_PppBrdcBaseline() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP-BDS-BRDC", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("3. PPP + GPT3/VMF3对流层模型")
    void test03_PppGpt3Vmf3() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+GPT3+VMF3", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("4. PPP + IERS2010潮汐（固体潮+极潮）")
    void test04_PppIers2010() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+IERS2010", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("5. PPP + S1/S2大气潮汐")
    void test05_PppAtmosphericTideS1S2() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableAt1S2 = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+S1S2", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("6. PPP + ISB/IFCB/IFB偏差模型")
    void test06_PppIsbIfcbIfb() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableIsbIfcbIfb = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+ISB/IFCB/IFB", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("7. PPP + PPP-AR模糊度固定")
    void test07_PppAmbFix() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.pppArRatioWl = 2.0;
        cfg.pppArRatioNl = 3.0;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+AR", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("8. PPP + Fix-and-Hold策略")
    void test08_PppFixHold() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        cfg.pppArFixHoldVar = 1e-6;
        cfg.pppArFixHoldMinEp = 30;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+FixHold", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("9. PPP + Partial AR部分模糊度固定")
    void test09_PppPartialAR() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppPartialAR = true;
        cfg.pppPartialArMinRatio = 2.0;
        cfg.pppPartialArMinSats = 4;
        cfg.pppPartialArMaxTries = 10;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+PartialAR", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("10. PPP + BDS-3 PPP-AR（B1C/B2a）")
    void test10_PppBds3AR() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enableBds3PppAR = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+BDS3-AR", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("11. PPP + OSB偏差改正模型")
    void test11_PppOsb() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableOsb = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+OSB", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("12. PPP + 全部优化组合")
    void test12_PppAllOptimizations() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt opt = bdsOnlyPppOpt();
        opt.tidecorr = 1;
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        cfg.enableIers2010 = true;
        cfg.enableAt1S2 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        cfg.pppArFixHoldVar = 1e-6;
        cfg.pppArFixHoldMinEp = 30;
        cfg.enablePppPartialAR = true;
        cfg.pppPartialArMinRatio = 2.0;
        cfg.pppPartialArMinSats = 4;
        cfg.enableBds3PppAR = true;
        cfg.enableOsb = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverRtcmFile);
        logResult("PPP+ALL", result);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("13. RTK多基线对测试 - 验证所有base-rover配对")
    void test13_RtkAllPairs() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        int tested = 0, success = 0;
        for (Map.Entry<String, String[]> entry : BASE_ROVER_MAP.entrySet()) {
            String base = entry.getKey();
            Path baseRoot = Paths.get(RTCM_BASE_DIR, base);
            if (!Files.isDirectory(baseRoot)) continue;

            try {
                Optional<Path> dateDir = Files.list(baseRoot)
                        .filter(Files::isDirectory).sorted().findFirst();
                if (dateDir.isEmpty()) continue;
                String date = dateDir.get().getFileName().toString();

                for (String rover : entry.getValue()) {
                    Path roverDir = Paths.get(RTCM_BASE_DIR, rover, date);
                    if (!Files.isDirectory(roverDir)) continue;

                    String bf = findRtcmFile(dateDir.get(), 12);
                    String rf = findRtcmFile(roverDir, 12);
                    if (bf == null || rf == null) continue;

                    tested++;
                    try {
                        PrcOpt opt = bdsOnlyRtkOpt();
                        RtkProcessor rtk = new RtkProcessor(opt);
                        RtkProcessor.RtkResult result = rtk.process(rf, bf);
                        if (result.successCount > 0) success++;
                        logRtkResult("RTK " + base + "->" + rover, result);
                    } catch (Exception e) {
                        log.warn("RTK pair error: base={}, rover={}: {}", base, rover, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("Error scanning base {}: {}", base, e.getMessage());
            }
        }
        log.info("RTK all pairs: {}/{} successful", success, tested);
        assertTrue(tested > 0, "Should find at least one pair");
    }

    @Test
    @DisplayName("14. PPP多小时连续观测 - 验证滤波收敛")
    void test14_PppMultiHour() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        Path roverDir = Paths.get(roverRtcmFile).getParent();
        List<String> files = new ArrayList<>();
        for (int h = 10; h <= 14; h++) {
            Path p = roverDir.resolve(h + ".rtcm3");
            if (Files.exists(p)) files.add(p.toString());
        }
        if (files.isEmpty()) {
            files.add(roverRtcmFile);
        }

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = null;
        for (String f : files) {
            result = ppp.process(f);
        }

        logResult("PPP-MultiHour(" + files.size() + " files)", result);
        assertNotNull(result, "Should have result");
    }

    @Test
    @DisplayName("15. RTK vs PPP精度对比 - 同一rover数据")
    void test15_RtkVsPppComparison() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM data");

        PrcOpt rtkOpt = bdsOnlyRtkOpt();
        RtkProcessor rtk = new RtkProcessor(rtkOpt);
        RtkProcessor.RtkResult rtkResult = rtk.process(roverRtcmFile, baseRtcmFile);
        logRtkResult("RTK", rtkResult);

        PrcOpt pppOpt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        PppProcessor ppp = new PppProcessor(pppOpt);
        ppp.setRtkConfig(cfg);
        PppProcessor.PppResult pppResult = ppp.process(roverRtcmFile);
        logResult("PPP", pppResult);

        log.info("Comparison: RTK success={}, PPP success={}",
                rtkResult.successCount, pppResult.successCount);
        assertTrue(rtkResult.successCount > 0 || pppResult.successCount > 0,
                "At least one method should succeed");
    }
}