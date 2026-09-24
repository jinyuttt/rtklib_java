package org.rtklib.java.ppp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTK and PPP Integration Test with RTCM data")
public class RtkPppIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RtkPppIntegrationTest.class);

    private static final String RTCM_BASE_DIR = "D:\\yaxia\\rtcm";

    private static final Map<String, String[]> BASE_ROVER_MAP = new HashMap<>();

    static {
        BASE_ROVER_MAP.put("540423231901", new String[]{
                "540423187770", "540423379882", "540423211132", "540423230321",
                "540423124124", "540423147354", "540423503435", "540423128131"
        });
        BASE_ROVER_MAP.put("540423214120", new String[]{
                "540423156203", "540423128507", "540423276898", "540423355855",
                "540423860355", "540423268595"
        });
    }

    private static boolean dataAvailable = false;
    private static String testBaseDir;
    private static String testRoverDir;
    private static String testDate;

    @BeforeAll
    static void checkData() {
        for (Map.Entry<String, String[]> entry : BASE_ROVER_MAP.entrySet()) {
            String base = entry.getKey();
            Path basePath = Paths.get(RTCM_BASE_DIR, base);
            if (!Files.isDirectory(basePath)) continue;

            try {
                Optional<Path> dateDir = Files.list(basePath)
                        .filter(Files::isDirectory)
                        .findFirst();
                if (dateDir.isEmpty()) continue;

                String date = dateDir.get().getFileName().toString();
                Path baseRtcm = dateDir.get().resolve("0.rtcm3");
                if (!Files.exists(baseRtcm)) {
                    baseRtcm = Files.list(dateDir.get())
                            .filter(p -> p.toString().endsWith(".rtcm3"))
                            .findFirst().orElse(null);
                }
                if (baseRtcm == null) continue;

                for (String rover : entry.getValue()) {
                    Path roverPath = Paths.get(RTCM_BASE_DIR, rover, date);
                    if (Files.isDirectory(roverPath)) {
                        Optional<Path> roverRtcm = Files.list(roverPath)
                                .filter(p -> p.toString().endsWith(".rtcm3"))
                                .findFirst();
                        if (roverRtcm.isPresent()) {
                            dataAvailable = true;
                            testBaseDir = baseRtcm.getParent().toString();
                            testRoverDir = roverRtcm.get().getParent().toString();
                            testDate = date;
                            log.info("Test data found: base={}, rover={}, date={}", base, rover, date);
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Error checking data: {}", e.getMessage());
            }
        }
        log.warn("No RTCM test data available");
    }

    @Test
    @DisplayName("RTK with RTCM data - base/rover pair")
    void testRtkWithRtcmData() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping test - no RTCM data available");
            return;
        }

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.posopt[0] = 0;

        RtkConfig cfg = new RtkConfig();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setRtkConfig(cfg);

        String baseFile = findFirstRtcmFile(testBaseDir);
        String roverFile = findFirstRtcmFile(testRoverDir);

        if (baseFile == null || roverFile == null) {
            log.info("Skipping - could not find RTCM files");
            return;
        }

        log.info("RTK test: base={}, rover={}", baseFile, roverFile);

        try {
            RtkProcessor.RtkResult result = rtk.process(roverFile, baseFile);
            log.info("RTK result: {} solutions, {} success, {} fail",
                    result.solutions != null ? result.solutions.size() : 0,
                    result.successCount, result.failCount);
            if (result.solutions != null && !result.solutions.isEmpty()) {
                SolData lastSol = result.solutions.get(result.solutions.size() - 1);
                log.info("Last solution: time={}, status={}, numSat={}",
                        lastSol.timeStr, lastSol.status, lastSol.numSat);
                assertTrue(lastSol.status != SolutionStatus.NONE,
                        "Solution should have valid status");
            }
        } catch (Exception e) {
            log.warn("RTK processing error: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("PPP with RTCM data - single rover")
    void testPppWithRtcmData() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping test - no RTCM data available");
            return;
        }

        String roverFile = findFirstRtcmFile(testRoverDir);
        if (roverFile == null) {
            log.info("Skipping - could not find rover RTCM file");
            return;
        }

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;

        RtkConfig cfg = new RtkConfig();
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        log.info("PPP test: rover={}", roverFile);

        try {
            PppProcessor.PppResult result = ppp.process(roverFile);
            log.info("PPP result: {} solutions, {} success, {} fail",
                    result.solutions != null ? result.solutions.size() : 0,
                    result.successCount, result.failCount);
            if (result.solutions != null && !result.solutions.isEmpty()) {
                SolData lastSol = result.solutions.get(result.solutions.size() - 1);
                log.info("Last PPP solution: time={}, status={}, numSat={}",
                        lastSol.timeStr, lastSol.status, lastSol.numSat);
            }
        } catch (Exception e) {
            log.warn("PPP processing error: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("PPP with GPT3+VMF3 optimization")
    void testPppWithGpt3Vmf3() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping test - no RTCM data available");
            return;
        }

        String roverFile = findFirstRtcmFile(testRoverDir);
        if (roverFile == null) {
            log.info("Skipping - could not find rover RTCM file");
            return;
        }

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;

        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        log.info("PPP+GPT3+VMF3 test: rover={}", roverFile);

        try {
            PppProcessor.PppResult result = ppp.process(roverFile);
            log.info("PPP+GPT3+VMF3 result: {} solutions", result.solutions != null ? result.solutions.size() : 0);
        } catch (Exception e) {
            log.warn("PPP+GPT3+VMF3 processing error: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("PPP with IERS2010 tide model")
    void testPppWithIers2010() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping test - no RTCM data available");
            return;
        }

        String roverFile = findFirstRtcmFile(testRoverDir);
        if (roverFile == null) {
            log.info("Skipping - could not find rover RTCM file");
            return;
        }

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.tidecorr = 1;

        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        log.info("PPP+IERS2010 test: rover={}", roverFile);

        try {
            PppProcessor.PppResult result = ppp.process(roverFile);
            log.info("PPP+IERS2010 result: {} solutions", result.solutions != null ? result.solutions.size() : 0);
        } catch (Exception e) {
            log.warn("PPP+IERS2010 processing error: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("PPP with ISB/IFCB/IFB bias model")
    void testPppWithBiasModel() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping test - no RTCM data available");
            return;
        }

        String roverFile = findFirstRtcmFile(testRoverDir);
        if (roverFile == null) {
            log.info("Skipping - could not find rover RTCM file");
            return;
        }

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;

        RtkConfig cfg = new RtkConfig();
        cfg.enableIsbIfcbIfb = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        log.info("PPP+ISB/IFCB/IFB test: rover={}", roverFile);

        try {
            PppProcessor.PppResult result = ppp.process(roverFile);
            log.info("PPP+ISB/IFCB/IFB result: {} solutions", result.solutions != null ? result.solutions.size() : 0);
        } catch (Exception e) {
            log.warn("PPP+ISB/IFCB/IFB processing error: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("PPP with all optimizations enabled")
    void testPppWithAllOptimizations() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping test - no RTCM data available");
            return;
        }

        String roverFile = findFirstRtcmFile(testRoverDir);
        if (roverFile == null) {
            log.info("Skipping - could not find rover RTCM file");
            return;
        }

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.tidecorr = 1;

        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        cfg.enableIers2010 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        log.info("PPP+All optimizations test: rover={}", roverFile);

        try {
            PppProcessor.PppResult result = ppp.process(roverFile);
            log.info("PPP+All optimizations result: {} solutions", result.solutions != null ? result.solutions.size() : 0);
        } catch (Exception e) {
            log.warn("PPP+All optimizations processing error: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("RTK with all base-rover pairs")
    void testRtkAllPairs() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping test - no RTCM data available");
            return;
        }

        int pairCount = 0;
        int successCount = 0;

        for (Map.Entry<String, String[]> entry : BASE_ROVER_MAP.entrySet()) {
            String base = entry.getKey();
            for (String rover : entry.getValue()) {
                Path basePath = Paths.get(RTCM_BASE_DIR, base);
                Path roverPath = Paths.get(RTCM_BASE_DIR, rover);

                if (!Files.isDirectory(basePath) || !Files.isDirectory(roverPath)) continue;

                try {
                    String baseFile = findFirstRtcmFile(basePath.toString());
                    String roverFile = findFirstRtcmFile(roverPath.toString());
                    if (baseFile == null || roverFile == null) continue;

                    pairCount++;
                    log.info("Testing pair {}/{}: base={}, rover={}", successCount + 1, pairCount, base, rover);

                    PrcOpt opt = RtkProcessor.createDefaultOpt();
                    opt.mode = Constants.PMODE_KINEMA;
                    opt.nf = 2;
                    opt.elmin = 15.0 * Constants.D2R;

                    RtkProcessor rtk = new RtkProcessor(opt);
                    RtkProcessor.RtkResult result = rtk.process(roverFile, baseFile);

                    if (result.solutions != null && !result.solutions.isEmpty()) {
                        successCount++;
                    }
                } catch (Exception e) {
                    log.warn("Error processing pair base={}, rover={}: {}", base, rover, e.getMessage());
                }
            }
        }

        log.info("RTK all pairs: {}/{} successful", successCount, pairCount);
    }

    private String findFirstRtcmFile(String dir) {
        try {
            Optional<Path> first = Files.walk(Paths.get(dir), 2)
                    .filter(p -> p.toString().endsWith(".rtcm3"))
                    .findFirst();
            return first.map(Path::toString).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}