package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.pntpos.SppProcessor;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SppProcessor Test")
public class SppProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(SppProcessorTest.class);
    private static final String ROVER_PATH =
            "C:\\Users\\admin\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";
    private static final String RESULT_DIR = "C:\\Users\\admin\\Desktop\\rtklib_java_results";
    private static byte[] roverData;

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        new File(RESULT_DIR).mkdirs();
        log.info("Loaded RTCM data: {} bytes", roverData.length);
    }

    @Test
    @DisplayName("1. SPP from byte[] with callback")
    void testSppFromBytesWithCallback() {
        List<Sol> callbackSolutions = new ArrayList<>();
        List<String> failMsgs = new ArrayList<>();
        int[] finishInfo = {0, 0, 0};

        PrcOpt opt = SppProcessor.createDefaultOpt();
        SppProcessor spp = new SppProcessor(opt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                callbackSolutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {
                failMsgs.add(msg);
            }
            @Override public void onFinish(int total, int success, int fail) {
                finishInfo[0] = total;
                finishInfo[1] = success;
                finishInfo[2] = fail;
                log.info("onFinish: total={}, success={}, fail={}", total, success, fail);
            }
        });

        SppProcessor.SppResult result = spp.process(roverData);

        log.info("Callback solutions: {}, fail msgs: {}", callbackSolutions.size(), failMsgs.size());
        log.info("SppResult: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        log.info("Finish info: total={}, success={}, fail={}", finishInfo[0], finishInfo[1], finishInfo[2]);

        assertTrue(callbackSolutions.size() > 0, "Should have callback solutions");
        assertEquals(callbackSolutions.size(), result.successCount, "Callback count should match result success count");
        assertEquals(finishInfo[1], result.successCount, "Finish success should match result success count");

        for (int i = 0; i < Math.min(5, callbackSolutions.size()); i++) {
            Sol s = callbackSolutions.get(i);
            double[] llh = new double[3];
            CoordTransform.ecef2pos(s.rr, llh);
            log.info("  Sol[{}]: lat={} lon={} h={} ns={}", i,
                    String.format("%.9f", Math.toDegrees(llh[0])),
                    String.format("%.9f", Math.toDegrees(llh[1])),
                    String.format("%.3f", llh[2]), s.ns);
        }
    }

    @Test
    @DisplayName("2. SPP from byte[] without callback")
    void testSppFromBytesNoCallback() {
        SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt(), null);
        SppProcessor.SppResult result = spp.process(roverData);

        log.info("SppResult: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.successCount > 0, "Should have successful SPP");
        assertFalse(result.solutions.isEmpty(), "Should have solutions");

        Sol first = result.solutions.get(0);
        double[] llh = new double[3];
        CoordTransform.ecef2pos(first.rr, llh);
        log.info("First solution: lat={}, lon={}, h={}, ns={}",
                String.format("%.9f", Math.toDegrees(llh[0])),
                String.format("%.9f", Math.toDegrees(llh[1])),
                String.format("%.3f", llh[2]), first.ns);
    }

    @Test
    @DisplayName("3. SPP from file path")
    void testSppFromFilePath() throws Exception {
        SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt(), null);
        SppProcessor.SppResult result = spp.process(ROVER_PATH);

        log.info("SppResult from file: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.successCount > 0, "Should have successful SPP from file");
    }

    @Test
    @DisplayName("4. Write .pos file")
    void testWritePosFile() throws Exception {
        String posFile = RESULT_DIR + "\\spp_processor_output.pos";

        SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt(), null);
        SppProcessor.SppResult result = spp.process(roverData);
        SppProcessor.writePosFile(result, posFile);

        File f = new File(posFile);
        log.info("Pos file: {} bytes", f.length());
        assertTrue(f.length() > 0, "Output file should not be empty");

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(posFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        log.info("Pos file lines: {}", lines.size());
        assertTrue(lines.size() > 5, "Pos file should have more than 5 lines");

        boolean hasData = false;
        for (String line : lines) {
            if (line.startsWith("  20") && line.contains("/") && line.contains(":")) hasData = true;
        }
        assertTrue(hasData, "Should have data lines");

        log.info("First 3 lines:");
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            log.info("  {}", lines.get(i));
        }
    }

    @Test
    @DisplayName("5. SPP with custom configuration")
    void testSppCustomConfig() {
        PrcOpt opt = SppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP | Constants.SYS_GPS;
        opt.nf = 3;

        SppProcessor spp = new SppProcessor(opt, null);
        SppProcessor.SppResult result = spp.process(roverData);

        log.info("Custom config: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("6. Compare with rtklib C SPP result")
    void testCompareWithRtklibC() throws Exception {
        String rtklibCFile = "D:\\code\\rtklib_java\\540423494727\\2026-06-08\\spp_c_full.pos";
        assertTrue(new File(rtklibCFile).exists(), "rtklib C result file should exist");

        Map<String, double[]> rtklibResult = parsePosFile(rtklibCFile);
        log.info("rtklib C result epochs: {}", rtklibResult.size());

        SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt(), null);
        SppProcessor.SppResult result = spp.process(roverData);

        log.info("Java SPP: {} epochs, {} success, {} fail",
                result.totalEpochs, result.successCount, result.failCount);

        int matchCount = 0;
        int totalCompared = 0;
        double sumLatDiff = 0, sumLonDiff = 0, sumHDiff = 0;
        double maxLatDiff = 0, maxLonDiff = 0, maxHDiff = 0;
        int nsDiffCount = 0;
        int unmatchedCount = 0;

        for (Sol sol : result.solutions) {
            String timeKey = formatTimeKey(sol.time);
            double[] rtklibData = rtklibResult.get(timeKey);
            if (rtklibData == null) {
                if (unmatchedCount < 5) {
                    log.warn("  Java solution not in C result: {}", timeKey);
                    unmatchedCount++;
                }
                continue;
            }

            totalCompared++;
            double rtklibLat = rtklibData[0];
            double rtklibLon = rtklibData[1];
            double rtklibH = rtklibData[2];
            int rtklibNs = (int) rtklibData[3];

            double[] llh = new double[3];
            CoordTransform.ecef2pos(sol.rr, llh);
            double javaLat = Math.toDegrees(llh[0]);
            double javaLon = Math.toDegrees(llh[1]);
            double javaH = llh[2];

            double latDiff = Math.abs(javaLat - rtklibLat);
            double lonDiff = Math.abs(javaLon - rtklibLon);
            double hDiff = Math.abs(javaH - rtklibH);

            sumLatDiff += latDiff;
            sumLonDiff += lonDiff;
            sumHDiff += hDiff;
            maxLatDiff = Math.max(maxLatDiff, latDiff);
            maxLonDiff = Math.max(maxLonDiff, lonDiff);
            maxHDiff = Math.max(maxHDiff, hDiff);

            int nsDiff = sol.ns - rtklibNs;
            if (nsDiff != 0) nsDiffCount++;

            matchCount++;

            if (matchCount <= 5 || latDiff > 0.0000001 || hDiff > 0.01) {
                log.info("  [{}] Java: lat={} lon={} h={} ns={} | C: lat={} lon={} h={} ns={} | dLat={} dLon={} dH={}",
                        timeKey,
                        String.format("%.9f", javaLat), String.format("%.9f", javaLon), String.format("%.1f", javaH), sol.ns,
                        String.format("%.9f", rtklibLat), String.format("%.9f", rtklibLon), String.format("%.1f", rtklibH), rtklibNs,
                        String.format("%.9f", latDiff), String.format("%.9f", lonDiff), String.format("%.3f", hDiff));
            }
        }

        log.info("=== Comparison Summary ===");
        log.info("rtklib C epochs: {}, Java solutions: {}, Matched: {}, Unmatched: {}", rtklibResult.size(), result.solutions.size(), totalCompared, unmatchedCount);
        if (matchCount > 0) {
            log.info("Avg lat diff: {} deg ({} m), max: {} deg ({} m)",
                    String.format("%.9f", sumLatDiff / matchCount), String.format("%.3f", sumLatDiff / matchCount * 111000),
                    String.format("%.9f", maxLatDiff), String.format("%.3f", maxLatDiff * 111000));
            log.info("Avg lon diff: {} deg ({} m), max: {} deg ({} m)",
                    String.format("%.9f", sumLonDiff / matchCount), String.format("%.3f", sumLonDiff / matchCount * 111000 * Math.cos(Math.toRadians(29.19))),
                    String.format("%.9f", maxLonDiff), String.format("%.3f", maxLonDiff * 111000 * Math.cos(Math.toRadians(29.19))));
            log.info("Avg h diff: {} m, max: {} m",
                    String.format("%.3f", sumHDiff / matchCount), String.format("%.3f", maxHDiff));
            log.info("Ns diff count: {}/{}", nsDiffCount, matchCount);
        }

        assertTrue(totalCompared > 0, "Should have matched epochs");
        assertTrue(sumLatDiff / matchCount < 0.0001, "Avg lat diff should be < 0.0001 deg");
    }

    @Test
    @DisplayName("7. SPP with OutputStream")
    void testSppWithOutputStream() throws Exception {
        String posFile = RESULT_DIR + "\\spp_stream_output.pos";

        try (FileOutputStream fos = new FileOutputStream(posFile)) {
            SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt(), null, fos);
            SppProcessor.SppResult result = spp.process(roverData);

            log.info("SppResult with stream: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
            assertTrue(result.successCount > 0, "Should have successful SPP");
        }

        File f = new File(posFile);
        assertTrue(f.length() > 0, "Stream output file should not be empty");

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(posFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
        }
        log.info("Stream output lines: {}", lines.size());
        assertTrue(lines.size() > 5, "Stream output should have more than 5 lines");

        boolean hasData = false;
        for (String line : lines) {
            if (line.startsWith("  20") && line.contains("/") && line.contains(":")) hasData = true;
        }
        assertTrue(hasData, "Should have data lines in stream output");
    }

    @Test
    @DisplayName("8. SPP streaming mode with feed+finish")
    void testSppStreamingMode() throws Exception {
        List<Sol> callbackSolutions = new ArrayList<>();
        int[] finishInfo = {0, 0, 0};

        PrcOpt opt = SppProcessor.createDefaultOpt();
        SppProcessor spp = new SppProcessor(opt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                callbackSolutions.add(sol);
            }
            @Override public void onPosFail(GTime time, String msg) {}
            @Override public void onFinish(int total, int success, int fail) {
                finishInfo[0] = total;
                finishInfo[1] = success;
                finishInfo[2] = fail;
            }
        });

        int chunkSize = roverData.length / 4;
        spp.feed(roverData, 0, chunkSize);
        spp.feed(roverData, chunkSize, chunkSize);
        spp.feed(roverData, chunkSize * 2, chunkSize);
        spp.feed(roverData, chunkSize * 3, roverData.length - chunkSize * 3);

        SppProcessor.SppResult result = spp.finish();

        log.info("Streaming mode: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        log.info("Callback solutions: {}, finish info: total={}, success={}, fail={}",
                callbackSolutions.size(), finishInfo[0], finishInfo[1], finishInfo[2]);

        assertTrue(result.successCount > 0, "Should have successful SPP in streaming mode");
        assertEquals(callbackSolutions.size(), result.successCount, "Callback count should match");
        assertEquals(finishInfo[1], result.successCount, "Finish success should match");
    }

    @Test
    @DisplayName("9. SPP with both OutputStream and callback")
    void testSppWithStreamAndCallback() throws Exception {
        String posFile = RESULT_DIR + "\\spp_stream_callback.pos";
        List<Sol> callbackSolutions = new ArrayList<>();
        int[] finishInfo = {0, 0, 0};

        try (FileOutputStream fos = new FileOutputStream(posFile)) {
            SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt(), new PosHandler() {
                @Override public void onSolution(Sol sol, Ssat[] ssat) {
                    callbackSolutions.add(sol);
                }
                @Override public void onPosFail(GTime time, String msg) {}
                @Override public void onFinish(int total, int success, int fail) {
                    finishInfo[0] = total;
                    finishInfo[1] = success;
                    finishInfo[2] = fail;
                }
            }, fos);

            SppProcessor.SppResult result = spp.process(roverData);

            assertTrue(result.successCount > 0, "Should have successful SPP");
            assertEquals(callbackSolutions.size(), result.successCount, "Callback count should match");
            assertEquals(finishInfo[1], result.successCount, "Finish success should match");
        }

        File f = new File(posFile);
        assertTrue(f.length() > 0, "Stream output should not be empty");
        log.info("Stream+callback output: {} bytes, {} callback solutions", f.length(), callbackSolutions.size());
    }

    @Test
    @DisplayName("10. SPP processor not reusable after finish without reset")
    void testSppNotReusableWithoutReset() {
        SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt());
        spp.process(roverData);

        assertThrows(IllegalStateException.class, () -> spp.feed(new byte[10]),
                "feed() after finish should throw");
        assertThrows(IllegalStateException.class, () -> spp.finish(),
                "finish() after finish should throw");
        assertThrows(IllegalStateException.class, () -> spp.process(roverData),
                "process() after finish should throw");
    }

    @Test
    @DisplayName("11. SPP processor reusable after reset")
    void testSppReusableAfterReset() {
        SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt());

        SppProcessor.SppResult result1 = spp.process(roverData);
        log.info("First run: total={}, success={}, fail={}", result1.totalEpochs, result1.successCount, result1.failCount);
        assertTrue(result1.successCount > 0, "First run should have successful SPP");

        spp.reset();

        SppProcessor.SppResult result2 = spp.process(roverData);
        log.info("Second run after reset: total={}, success={}, fail={}", result2.totalEpochs, result2.successCount, result2.failCount);
        assertTrue(result2.successCount > 0, "Second run should have successful SPP");
        assertEquals(result1.totalEpochs, result2.totalEpochs, "Same data should produce same epoch count");
        assertEquals(result1.successCount, result2.successCount, "Batch mode should produce identical results after reset");
    }

    @Test
    @DisplayName("12. SPP reset preserves ephemeris for immediate positioning")
    void testResetPreservesEphemeris() {
        SppProcessor spp = new SppProcessor(SppProcessor.createDefaultOpt());

        SppProcessor.SppResult result1 = spp.process(roverData);
        assertTrue(result1.successCount > 0, "First run should succeed");

        spp.reset();

        spp.feed(roverData);
        SppProcessor.SppResult result2 = spp.finish();
        log.info("Batch: {}/{}, Pipeline after reset: {}/{}",
                result1.successCount, result1.totalEpochs,
                result2.successCount, result2.totalEpochs);
        assertTrue(result2.successCount > 0, "Should succeed after reset with preserved ephemeris");
    }

    private Map<String, double[]> parsePosFile(String filePath) throws IOException {
        Map<String, double[]> result = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("%") || line.trim().isEmpty()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 7) continue;
                try {
                    String dateStr = parts[0];
                    String timeStr = parts[1];
                    double lat = Double.parseDouble(parts[2]);
                    double lon = Double.parseDouble(parts[3]);
                    double h = Double.parseDouble(parts[4]);
                    int q = Integer.parseInt(parts[5]);
                    int ns = Integer.parseInt(parts[6]);
                    String key = dateStr + " " + timeStr;
                    result.put(key, new double[]{lat, lon, h, ns, q});
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        }
        return result;
    }

    private String formatTimeKey(GTime time) {
        double[] ymd = TimeSystem.time2ymdhms(time);
        int secInt = (int) ymd[5];
        double secFrac = ymd[5] - secInt;
        String secStr = String.format("%02d.%03d", secInt, (int) Math.round(secFrac * 1000));
        return String.format("%04d/%02d/%02d %02d:%02d:%s",
                (int) ymd[0], (int) ymd[1], (int) ymd[2],
                (int) ymd[3], (int) ymd[4], secStr);
    }
}