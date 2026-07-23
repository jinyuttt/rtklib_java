package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Real Data RTK Test (base.rtcm3 + over.rtcm3)")
public class RealDataRtkTest {

    private static final Logger log = LoggerFactory.getLogger(RealDataRtkTest.class);

    private static final String ROVER_PATH = "C:\\Users\\jinyu\\Desktop\\over.rtcm3";
    private static final String BASE_PATH = "C:\\Users\\jinyu\\Desktop\\base.rtcm3";
    private static final String RESULT_DIR = "D:\\rtklib\\rtklib_java\\test_results";

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
    @DisplayName("1. RTK定位 - 默认配置")
    void testRtkDefaultConfig() {
        log.info("========== Test 1: RTK BDS-only Config ==========");

        List<Sol> solutions = Collections.synchronizedList(new ArrayList<>());
        List<String> failMsgs = Collections.synchronizedList(new ArrayList<>());

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP | Constants.SYS_QZS | Constants.SYS_SBS;
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
        log.info("Config: mode={}, nf={}, navsys={}({}), elmin={}deg, modear={}, ionoopt={}, tropopt={}",
                opt.mode, opt.nf, opt.navsys, "BDS",
                String.format("%.1f", opt.elmin / Constants.D2R),
                opt.modear, opt.ionoopt, opt.tropopt);

        RtkProcessor rtk = new RtkProcessor(opt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                solutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {
                failMsgs.add(msg);
                if (failMsgs.size() <= 5) {
                    log.warn("FAIL: {} - {}", formatTime(time), msg);
                }
            }
            @Override public void onFinish(int total, int success, int fail) {
                log.info("onFinish: total={}, success={}, fail={}", total, success, fail);
            }
        });

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        double[] rbLlh = new double[3];
        CoordTransform.ecef2pos(rtk.getRtk().rb, rbLlh);
        log.info("Base station pos (rb): lat={}, lon={}, h={}",
                String.format("%.9f", Math.toDegrees(rbLlh[0])),
                String.format("%.9f", Math.toDegrees(rbLlh[1])),
                String.format("%.4f", rbLlh[2]));
        log.info("Base station pos (ECEF): X={}, Y={}, Z={}",
                String.format("%.4f", rtk.getRtk().rb[0]),
                String.format("%.4f", rtk.getRtk().rb[1]),
                String.format("%.4f", rtk.getRtk().rb[2]));

        try {
            PrintWriter pw = new PrintWriter(new FileWriter(RESULT_DIR + "\\java_rtk.pos"));
            for (Sol sol : solutions) {
                double[] llh = new double[3];
                CoordTransform.ecef2pos(sol.rr, llh);
                pw.printf("%10d %10.3f   %14.9f  %14.9f  %10.4f   %d  %d%n",
                        sol.time.time, sol.time.sec,
                        Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2],
                        sol.stat, sol.ns);
            }
            pw.close();
        } catch (Exception e) {
            log.warn("Failed to write pos file: {}", e.getMessage());
        }
        log.info("Java RTK solutions written to {}/java_rtk.pos", RESULT_DIR);

        log.info("--- Result Summary ---");
        log.info("Total epochs: {}, Success: {}, Fail: {}", result.totalEpochs, result.successCount, result.failCount);
        log.info("Callback solutions: {}, Fail msgs: {}", solutions.size(), failMsgs.size());

        printSolutionStats(solutions, "Default Config");

        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("2. RTK定位 - 启用模糊度固定")
    void testRtkWithArFix() {
        log.info("========== Test 2: RTK with AR Fix ==========");

        List<Sol> solutions = Collections.synchronizedList(new ArrayList<>());

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.modear = Constants.ARMODE_CONT;
        opt.outsingle = 1;
        log.info("Config: mode={}, nf={}, navsys={}, modear=CONT, elmin={}deg",
                opt.mode, opt.nf, opt.navsys,
                String.format("%.1f", opt.elmin / Constants.D2R));

        RtkProcessor rtk = new RtkProcessor(opt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                solutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {}
            @Override public void onFinish(int total, int success, int fail) {
                log.info("onFinish: total={}, success={}, fail={}", total, success, fail);
            }
        });

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("--- Result Summary ---");
        log.info("Total epochs: {}, Success: {}, Fail: {}", result.totalEpochs, result.successCount, result.failCount);

        printSolutionStats(solutions, "AR CONT");

        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("3. RTK定位 - 静态模式 + 连续模糊度固定")
    void testRtkStaticArCont() {
        log.info("========== Test 3: RTK Static + AR CONT ==========");

        List<Sol> solutions = Collections.synchronizedList(new ArrayList<>());

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.modear = Constants.ARMODE_CONT;
        opt.outsingle = 1;
        log.info("Config: mode=STATIC, nf={}, navsys={}, modear=CONT, elmin={}deg",
                opt.nf, opt.navsys,
                String.format("%.1f", opt.elmin / Constants.D2R));

        RtkProcessor rtk = new RtkProcessor(opt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                solutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {}
            @Override public void onFinish(int total, int success, int fail) {
                log.info("onFinish: total={}, success={}, fail={}", total, success, fail);
            }
        });

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("--- Result Summary ---");
        log.info("Total epochs: {}, Success: {}, Fail: {}", result.totalEpochs, result.successCount, result.failCount);

        printSolutionStats(solutions, "Static AR CONT");

        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("4. SPP单点定位 - 仅流动站")
    void testSppOnly() {
        log.info("========== Test 4: SPP (Rover Only) ==========");

        List<Sol> solutions = Collections.synchronizedList(new ArrayList<>());

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_SINGLE;
        log.info("Config: mode=SINGLE, navsys={}", opt.navsys);

        RtkProcessor rtk = new RtkProcessor(opt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                solutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {}
            @Override public void onFinish(int total, int success, int fail) {
                log.info("onFinish: total={}, success={}, fail={}", total, success, fail);
            }
        });

        RtkProcessor.RtkResult result = rtk.process(roverData);

        log.info("--- Result Summary ---");
        log.info("Total epochs: {}, Success: {}, Fail: {}", result.totalEpochs, result.successCount, result.failCount);

        printSolutionStats(solutions, "SPP");

        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("5. 输出.pos文件")
    void testWritePosFile() throws Exception {
        log.info("========== Test 5: Write .pos File ==========");

        String posFile = RESULT_DIR + "\\real_data_rtk.pos";

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.modear = Constants.ARMODE_CONT;
        opt.outsingle = 1;

        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
        RtkProcessor.writePosFile(result, posFile);

        File f = new File(posFile);
        log.info("Pos file written: {} bytes", f.length());
        assertTrue(f.length() > 0, "Output file should not be empty");

        try (BufferedReader br = new BufferedReader(new FileReader(posFile))) {
            String line;
            int dataLines = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("  20") && line.contains("/")) {
                    dataLines++;
                    if (dataLines <= 5) {
                        log.info("  {}", line.trim());
                    }
                }
            }
            log.info("Total data lines: {}", dataLines);
        }
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

        log.info("[{}] Solution types: Fix={}, Float={}, Single={}, Other={}",
                label, fixCount, floatCount, singleCount, otherCount);

        double sumLat = 0, sumLon = 0, sumH = 0;
        double fixSumLat = 0, fixSumLon = 0, fixSumH = 0;
        int fixPosCount = 0;

        for (Sol sol : solutions) {
            double[] llh = new double[3];
            CoordTransform.ecef2pos(sol.rr, llh);
            double lat = Math.toDegrees(llh[0]);
            double lon = Math.toDegrees(llh[1]);
            double h = llh[2];
            sumLat += lat;
            sumLon += lon;
            sumH += h;

            if (sol.stat == Constants.SOLQ_FIX) {
                fixSumLat += lat;
                fixSumLon += lon;
                fixSumH += h;
                fixPosCount++;
            }
        }

        int n = solutions.size();
        log.info("[{}] All solutions avg: lat={}, lon={}, h={}",
                label,
                String.format("%.9f", sumLat / n),
                String.format("%.9f", sumLon / n),
                String.format("%.3f", sumH / n));

        if (fixPosCount > 0) {
            log.info("[{}] Fix solutions avg: lat={}, lon={}, h={}",
                    label,
                    String.format("%.9f", fixSumLat / fixPosCount),
                    String.format("%.9f", fixSumLon / fixPosCount),
                    String.format("%.3f", fixSumH / fixPosCount));
        }

        log.info("[{}] First 3 solutions:", label);
        for (int i = 0; i < Math.min(3, solutions.size()); i++) {
            Sol s = solutions.get(i);
            double[] llh = new double[3];
            CoordTransform.ecef2pos(s.rr, llh);
            String statStr = solStatStr(s.stat);
            log.info("  [{}] {} : lat={} lon={} h={} ns={} stat={}",
                    i, formatTime(s.time),
                    String.format("%.9f", Math.toDegrees(llh[0])),
                    String.format("%.9f", Math.toDegrees(llh[1])),
                    String.format("%.3f", llh[2]),
                    s.ns, statStr);
        }

        log.info("[{}] Last 3 solutions:", label);
        int start = Math.max(0, solutions.size() - 3);
        for (int i = start; i < solutions.size(); i++) {
            Sol s = solutions.get(i);
            double[] llh = new double[3];
            CoordTransform.ecef2pos(s.rr, llh);
            String statStr = solStatStr(s.stat);
            log.info("  [{}] {} : lat={} lon={} h={} ns={} stat={}",
                    i, formatTime(s.time),
                    String.format("%.9f", Math.toDegrees(llh[0])),
                    String.format("%.9f", Math.toDegrees(llh[1])),
                    String.format("%.3f", llh[2]),
                    s.ns, statStr);
        }

        int lastN = Math.min(10, solutions.size());
        double lastSumLat = 0, lastSumLon = 0, lastSumH = 0;
        for (int i = solutions.size() - lastN; i < solutions.size(); i++) {
            double[] llh = new double[3];
            CoordTransform.ecef2pos(solutions.get(i).rr, llh);
            lastSumLat += Math.toDegrees(llh[0]);
            lastSumLon += Math.toDegrees(llh[1]);
            lastSumH += llh[2];
        }
        log.info("[{}] Last {} avg: lat={}, lon={}, h={}",
                label, lastN,
                String.format("%.9f", lastSumLat / lastN),
                String.format("%.9f", lastSumLon / lastN),
                String.format("%.3f", lastSumH / lastN));
    }

    private static String solStatStr(int stat) {
        return switch (stat) {
            case Constants.SOLQ_FIX -> "Fix";
            case Constants.SOLQ_FLOAT -> "Float";
            case Constants.SOLQ_SINGLE -> "Single";
            case Constants.SOLQ_NONE -> "None";
            default -> "Unknown(" + stat + ")";
        };
    }

    private static String formatTime(GTime time) {
        double[] ymd = TimeSystem.time2ymdhms(time);
        return String.format("%04d-%02d-%02d %02d:%02d:%06.3f",
                (int) ymd[0], (int) ymd[1], (int) ymd[2],
                (int) ymd[3], (int) ymd[4], ymd[5]);
    }

    @Test
    @DisplayName("6. 优化项逐项测试")
    void testOptimizations() {
        log.info("========== Test 6: Optimization Item-by-Item Test ==========");

        PrcOpt baseOpt = RtkProcessor.createDefaultOpt();
        baseOpt.navsys = Constants.SYS_CMP;
        baseOpt.modear = Constants.ARMODE_CONT;

        List<Sol> baselineSols = runRtk("Baseline", baseOpt, null);
        double[] baselinePos = avgLastN(baselineSols, 20);
        log.info("Baseline last-20 avg: lat={}, lon={}, h={}",
                String.format("%.9f", Math.toDegrees(baselinePos[0])),
                String.format("%.9f", Math.toDegrees(baselinePos[1])),
                String.format("%.3f", baselinePos[2]));

        String[] optNames = {
            "enableAdaptiveQ",
            "enableAmbAnchor",
            "enableIggiii",
            "enableSnrMedian",
            "enableParRefReselect",
            "enableIonoTropGradient",
            "atmFrozenNs=5"
        };

        String hdr = "┌──────────────────────────┬──────┬───────┬───────┬───────┬───────┬───────┬──────────┐";
        String col = "│ Optimization             │ Fix  │ Float │ Sing  │ dN(m) │ dE(m) │ dU(m) │ Note     │";
        String sep = "├──────────────────────────┼──────┼───────┼───────┼───────┼───────┼───────┼──────────┤";
        String ftr = "└──────────────────────────┴──────┴───────┴───────┴───────┴───────┴───────┴──────────┘";
        log.info(hdr); log.info(col); log.info(sep);

        for (String optName : optNames) {
            RtkConfig cfg = new RtkConfig();
            switch (optName) {
                case "enableAdaptiveQ" -> cfg.enableAdaptiveQ = true;
                case "enableAmbAnchor" -> cfg.enableAmbAnchor = true;
                case "enableIggiii" -> cfg.enableIggiii = true;
                case "enableSnrMedian" -> cfg.enableSnrMedian = true;
                case "enableParRefReselect" -> cfg.enableParRefReselect = true;
                case "enableIonoTropGradient" -> cfg.enableIonoTropGradient = true;
                case "atmFrozenNs=5" -> cfg.atmFrozenNsThresh = 5;
            }

            List<Sol> sols = runRtk(optName, baseOpt, cfg);
            int fix = 0, flt = 0, sng = 0;
            for (Sol s : sols) {
                if (s.stat == Constants.SOLQ_FIX) fix++;
                else if (s.stat == Constants.SOLQ_FLOAT) flt++;
                else if (s.stat == Constants.SOLQ_SINGLE) sng++;
            }

            double[] pos = avgLastN(sols, 20);
            double dN = (pos[0] - baselinePos[0]) * 111320;
            double dE = (pos[1] - baselinePos[1]) * 111320 * Math.cos(baselinePos[0]);
            double dU = pos[2] - baselinePos[2];

            String note = "";
            if (fix > 0) note = "FIX:" + fix;
            else if (Math.abs(dN) > 1 || Math.abs(dE) > 1 || Math.abs(dU) > 1) note = "LARGE";
            else if (Math.abs(dN) < 0.01 && Math.abs(dE) < 0.01 && Math.abs(dU) < 0.01) note = "~same";

            String row = String.format("│ %-24s │ %4d │ %5d │ %5d │ %5.2f │ %5.2f │ %5.2f │ %-8s │",
                    optName, fix, flt, sng, dN, dE, dU, note);
            log.info(row);
        }

        log.info(ftr);
        log.info("========== Optimization Test Complete ==========");
    }

    @Test
    @DisplayName("7. 优化项组合测试")
    void testOptimizationCombinations() {
        log.info("========== Test 7: Optimization Combinations ==========");

        PrcOpt baseOpt = RtkProcessor.createDefaultOpt();
        baseOpt.navsys = Constants.SYS_CMP;
        baseOpt.modear = Constants.ARMODE_CONT;

        String[][] combos = {
            {"AdaptiveQ+IGGIII", "enableAdaptiveQ", "enableIggiii"},
            {"AdaptiveQ+AmbAnchor", "enableAdaptiveQ", "enableAmbAnchor"},
            {"IGGIII+ParRefResel", "enableIggiii", "enableParRefReselect"},
            {"SnrMedian+ParRefResel", "enableSnrMedian", "enableParRefReselect"},
            {"ALL-5", "enableAdaptiveQ", "enableAmbAnchor", "enableIggiii", "enableSnrMedian", "enableParRefReselect"},
        };

        List<Sol> baselineSols = runRtk("Baseline", baseOpt, null);
        double[] baselinePos = avgLastN(baselineSols, 20);

        String hdr = "┌──────────────────────────┬──────┬───────┬───────┬───────┬───────┬───────┬──────────┐";
        String col = "│ Combination              │ Fix  │ Float │ Sing  │ dN(m) │ dE(m) │ dU(m) │ Note     │";
        String sep = "├──────────────────────────┼──────┼───────┼───────┼───────┼───────┼───────┼──────────┤";
        String ftr = "└──────────────────────────┴──────┴───────┴───────┴───────┴───────┴───────┴──────────┘";
        log.info(hdr); log.info(col); log.info(sep);

        for (String[] combo : combos) {
            String name = combo[0];
            RtkConfig cfg = new RtkConfig();
            for (int i = 1; i < combo.length; i++) {
                switch (combo[i]) {
                    case "enableAdaptiveQ" -> cfg.enableAdaptiveQ = true;
                    case "enableAmbAnchor" -> cfg.enableAmbAnchor = true;
                    case "enableIggiii" -> cfg.enableIggiii = true;
                    case "enableSnrMedian" -> cfg.enableSnrMedian = true;
                    case "enableParRefReselect" -> cfg.enableParRefReselect = true;
                    case "enableIonoTropGradient" -> cfg.enableIonoTropGradient = true;
                }
            }

            List<Sol> sols = runRtk(name, baseOpt, cfg);
            int fix = 0, flt = 0, sng = 0;
            for (Sol s : sols) {
                if (s.stat == Constants.SOLQ_FIX) fix++;
                else if (s.stat == Constants.SOLQ_FLOAT) flt++;
                else if (s.stat == Constants.SOLQ_SINGLE) sng++;
            }

            double[] pos = avgLastN(sols, 20);
            double dN = (pos[0] - baselinePos[0]) * 111320;
            double dE = (pos[1] - baselinePos[1]) * 111320 * Math.cos(baselinePos[0]);
            double dU = pos[2] - baselinePos[2];

            String note = "";
            if (fix > 0) note = "FIX:" + fix;
            else if (Math.abs(dN) > 1 || Math.abs(dE) > 1 || Math.abs(dU) > 1) note = "LARGE";
            else if (Math.abs(dN) < 0.01 && Math.abs(dE) < 0.01 && Math.abs(dU) < 0.01) note = "~same";

            String row = String.format("│ %-24s │ %4d │ %5d │ %5d │ %5.2f │ %5.2f │ %5.2f │ %-8s │",
                    name, fix, flt, sng, dN, dE, dU, note);
            log.info(row);
        }

        log.info(ftr);
        log.info("========== Combination Test Complete ==========");
    }

    @Test
    @DisplayName("8. IGGIII诊断测试")
    void testIggiiiDiagnosis() {
        log.info("========== Test 8: IGGIII Diagnosis ==========");

        PrcOpt baseOpt = RtkProcessor.createDefaultOpt();
        baseOpt.navsys = Constants.SYS_CMP;
        baseOpt.modear = Constants.ARMODE_OFF;

        List<Sol> baselineSols = runRtk("Baseline (AR OFF)", baseOpt, null);
        double[] baselinePos = avgLastN(baselineSols, 20);
        log.info("Baseline last-20 avg: lat={}, lon={}, h={}",
                String.format("%.9f", Math.toDegrees(baselinePos[0])),
                String.format("%.9f", Math.toDegrees(baselinePos[1])),
                String.format("%.3f", baselinePos[2]));

        RtkConfig cfg1 = new RtkConfig();
        cfg1.enableIggiii = true;
        cfg1.iggiiiLowElW = 1.0;
        cfg1.iggiiiMultiFreqW = 1.0;
        List<Sol> s1 = runRtk("IGGIII core-only", baseOpt, cfg1);
        double[] p1 = avgLastN(s1, 20);
        log.info(String.format("IGGIII core-only (no lowEl/multiFreq): dN=%.2fm dE=%.2fm dU=%.2fm",
                (p1[0]-baselinePos[0])*111320, (p1[1]-baselinePos[1])*111320*Math.cos(baselinePos[0]), p1[2]-baselinePos[2]));

        RtkConfig cfg2 = new RtkConfig();
        cfg2.enableIggiii = true;
        cfg2.iggiiiK0 = 3.0;
        cfg2.iggiiiK1 = 6.0;
        cfg2.iggiiiMinW = 0.5;
        cfg2.iggiiiLowElW = 1.0;
        cfg2.iggiiiMultiFreqW = 1.0;
        List<Sol> s2 = runRtk("IGGIII very-conservative", baseOpt, cfg2);
        double[] p2 = avgLastN(s2, 20);
        log.info(String.format("IGGIII very-conservative (K0=3,K1=6,minW=0.5): dN=%.2fm dE=%.2fm dU=%.2fm",
                (p2[0]-baselinePos[0])*111320, (p2[1]-baselinePos[1])*111320*Math.cos(baselinePos[0]), p2[2]-baselinePos[2]));

        RtkConfig cfg3 = new RtkConfig();
        cfg3.enableIggiii = true;
        cfg3.iggiiiK0 = 4.0;
        cfg3.iggiiiK1 = 8.0;
        cfg3.iggiiiMinW = 0.8;
        cfg3.iggiiiLowElW = 1.0;
        cfg3.iggiiiMultiFreqW = 1.0;
        List<Sol> s3 = runRtk("IGGIII minimal", baseOpt, cfg3);
        double[] p3 = avgLastN(s3, 20);
        log.info(String.format("IGGIII minimal (K0=4,K1=8,minW=0.8): dN=%.2fm dE=%.2fm dU=%.2fm",
                (p3[0]-baselinePos[0])*111320, (p3[1]-baselinePos[1])*111320*Math.cos(baselinePos[0]), p3[2]-baselinePos[2]));

        log.info("========== IGGIII Diagnosis Complete ==========");
    }

    private List<Sol> runRtk(String label, PrcOpt opt, RtkConfig cfg) {
        List<Sol> solutions = Collections.synchronizedList(new ArrayList<>());
        PrcOpt runOpt = new PrcOpt(opt);

        RtkProcessor rtk = new RtkProcessor(runOpt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                solutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {}
            @Override public void onFinish(int total, int success, int fail) {}
        });

        if (cfg != null) {
            rtk.getRtk().rtkConfig = new RtkConfig(cfg);
        }

        rtk.process(roverData, baseData);

        int fix = 0, flt = 0, sng = 0;
        for (Sol s : solutions) {
            if (s.stat == Constants.SOLQ_FIX) fix++;
            else if (s.stat == Constants.SOLQ_FLOAT) flt++;
            else if (s.stat == Constants.SOLQ_SINGLE) sng++;
        }
        log.info("[{}] epochs={}, Fix={}, Float={}, Single={}", label, solutions.size(), fix, flt, sng);

        return solutions;
    }

    private double[] avgLastN(List<Sol> sols, int n) {
        int start = Math.max(0, sols.size() - n);
        double sumLat = 0, sumLon = 0, sumH = 0;
        int count = 0;
        for (int i = start; i < sols.size(); i++) {
            double[] llh = new double[3];
            CoordTransform.ecef2pos(sols.get(i).rr, llh);
            sumLat += llh[0];
            sumLon += llh[1];
            sumH += llh[2];
            count++;
        }
        return new double[]{sumLat / count, sumLon / count, sumH / count};
    }
}