package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LogTrace Test")
public class LogTraceTest {

    private static final Logger log = LoggerFactory.getLogger(LogTraceTest.class);

    private static final String ROVER_PATH =
            "C:\\Users\\admin\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";
    private static final String BASE_PATH =
            "C:\\Users\\admin\\Desktop\\540423496360\\2026-06-08\\1.rtcm3";
    private static final String RESULT_DIR = "C:\\Users\\admin\\Desktop\\rtklib_java_results";

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
        log.info("Loaded Rover: {} bytes, Base: {} bytes", roverData.length, baseData.length);
    }

    @Test
    @DisplayName("1. Trace all stages")
    void testTraceAllStages() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_INPUT | TraceControl.STAGE_SATPOS
                | TraceControl.STAGE_UDSTATE | TraceControl.STAGE_DDRES
                | TraceControl.STAGE_FILTER | TraceControl.STAGE_LAMBDA
                | TraceControl.STAGE_RESULT;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Trace lines: {}", traceLines.size());
        log.info("RtkResult: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);

        assertTrue(traceLines.size() > 0, "Should have trace output");

        Map<String, Integer> stageCounts = new LinkedHashMap<>();
        for (String line : traceLines) {
            if (line.startsWith("TRACE|")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 2) {
                    String tag = parts[1];
                    stageCounts.merge(tag, 1, Integer::sum);
                }
            }
        }

        log.info("Stage counts:");
        for (Map.Entry<String, Integer> e : stageCounts.entrySet()) {
            log.info("  {}: {}", e.getKey(), e.getValue());
        }

        assertTrue(stageCounts.containsKey("STAGE0") || stageCounts.containsKey("STAGE6"),
                "Should have at least STAGE0 or STAGE6 output");

        for (int i = 0; i < Math.min(10, traceLines.size()); i++) {
            log.info("  Trace[{}]: {}", i, traceLines.get(i));
        }
    }

    @Test
    @DisplayName("2. Trace only STAGE_INPUT and STAGE_RESULT")
    void testTraceInputAndResultOnly() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_INPUT | TraceControl.STAGE_RESULT;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Trace lines (INPUT+RESULT): {}", traceLines.size());

        boolean hasStage0 = false, hasStage6 = false;
        boolean hasStage1 = false, hasStage2 = false, hasStage3 = false;
        for (String line : traceLines) {
            if (line.contains("STAGE0")) hasStage0 = true;
            if (line.contains("STAGE6")) hasStage6 = true;
            if (line.contains("STAGE1")) hasStage1 = true;
            if (line.contains("STAGE2")) hasStage2 = true;
            if (line.contains("STAGE3")) hasStage3 = true;
        }

        assertTrue(hasStage0, "Should have STAGE0 output");
        assertTrue(hasStage6, "Should have STAGE6 output");
        assertFalse(hasStage1, "Should NOT have STAGE1 output");
        assertFalse(hasStage2, "Should NOT have STAGE2 output");
        assertFalse(hasStage3, "Should NOT have STAGE3 output");
    }

    @Test
    @DisplayName("3. Trace with samplerate")
    void testTraceWithSamplerate() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_RESULT;
        ctrl.samplerate = 10;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Trace lines (samplerate=10): {}", traceLines.size());
        log.info("Total epochs: {}", result.totalEpochs);

        Set<Integer> epochs = new HashSet<>();
        for (String line : traceLines) {
            if (line.contains("STAGE6")) {
                String epochStr = extractField(line, "epoch=");
                if (epochStr != null) {
                    epochs.add(Integer.parseInt(epochStr));
                }
            }
        }

        log.info("Traced epochs: {}", epochs);
        for (int e : epochs) {
            assertEquals(0, e % 10, "Traced epoch should be multiple of samplerate (10), got: " + e);
        }
    }

    @Test
    @DisplayName("4. Trace with maxEpochs limit")
    void testTraceWithMaxEpochs() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_RESULT;
        ctrl.maxEpochs = 5;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Trace lines (maxEpochs=5): {}", traceLines.size());

        int maxEpoch = 0;
        for (String line : traceLines) {
            if (line.contains("STAGE6")) {
                String epochStr = extractField(line, "epoch=");
                if (epochStr != null) {
                    maxEpoch = Math.max(maxEpoch, Integer.parseInt(epochStr));
                }
            }
        }

        log.info("Max traced epoch: {}", maxEpoch);
        assertTrue(maxEpoch <= 5, "Max traced epoch should not exceed maxEpochs (5)");
    }

    @Test
    @DisplayName("5. Trace disabled (default)")
    void testTraceDisabled() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = false;
        ctrl.stages = 0x7F;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Trace lines (disabled): {}", traceLines.size());
        assertEquals(0, traceLines.size(), "Should have no trace output when disabled");
    }

    @Test
    @DisplayName("6. Trace with null control (no output)")
    void testTraceNullControl() {
        List<String> traceLines = new ArrayList<>();

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceCallback(traceLines::add);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Trace lines (null control): {}", traceLines.size());
        assertEquals(0, traceLines.size(), "Should have no trace output with null control");
    }

    @Test
    @DisplayName("7. Trace with null callback (no crash)")
    void testTraceNullCallback() {
        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = 0x7F;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(null);

        assertDoesNotThrow(() -> {
            RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
            log.info("Result with null callback: total={}, success={}, fail={}",
                    result.totalEpochs, result.successCount, result.failCount);
        }, "Should not crash with null callback");
    }

    @Test
    @DisplayName("8. Trace STAGE6 format validation")
    void testTraceStage6Format() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_RESULT;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        List<String> stage6Lines = new ArrayList<>();
        for (String line : traceLines) {
            if (line.contains("STAGE6|")) stage6Lines.add(line);
        }

        assertTrue(stage6Lines.size() > 0, "Should have STAGE6 output");

        String first = stage6Lines.get(0);
        log.info("First STAGE6 line: {}", first);

        assertTrue(first.startsWith("TRACE|STAGE6|"), "STAGE6 should start with TRACE|STAGE6|");
        assertNotNull(extractField(first, "gpst="), "Should have gpst field");
        assertNotNull(extractField(first, "epoch="), "Should have epoch field");
        assertNotNull(extractField(first, "Q="), "Should have Q field");
        assertNotNull(extractField(first, "lat="), "Should have lat field");
        assertNotNull(extractField(first, "lon="), "Should have lon field");
        assertNotNull(extractField(first, "h="), "Should have h field");
        assertNotNull(extractField(first, "ns="), "Should have ns field");

        String qStr = extractField(first, "Q=");
        int q = Integer.parseInt(qStr);
        assertTrue(q >= 0 && q <= 6, "Q should be between 0 and 6, got: " + q);
    }

    @Test
    @DisplayName("9. Trace with CONTENT_H_MATRIX flag")
    void testTraceWithHMatrix() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_DDRES;
        ctrl.contentFlags = TraceControl.CONTENT_H_MATRIX;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Trace lines (H_MATRIX): {}", traceLines.size());

        boolean hasHPos = false, hasHBias = false;
        for (String line : traceLines) {
            if (line.contains("STAGE3_H_POS")) hasHPos = true;
            if (line.contains("STAGE3_H_BIAS")) hasHBias = true;
        }

        log.info("Has H_POS: {}, Has H_BIAS: {}", hasHPos, hasHBias);

        for (int i = 0; i < Math.min(5, traceLines.size()); i++) {
            log.info("  Trace[{}]: {}", i, traceLines.get(i));
        }
    }

    @Test
    @DisplayName("10. Trace with CONTENT_SUMMARY_ONLY flag")
    void testTraceWithSummaryOnly() {
        List<String> traceLinesFull = Collections.synchronizedList(new ArrayList<>());
        List<String> traceLinesSummary = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrlFull = new TraceControl();
        ctrlFull.enabled = true;
        ctrlFull.stages = TraceControl.STAGE_UDSTATE | TraceControl.STAGE_LAMBDA;

        PrcOpt opt = RtkProcessor.createDefaultOpt();

        RtkProcessor rtk1 = new RtkProcessor(opt);
        rtk1.setTraceControl(ctrlFull);
        rtk1.setTraceCallback(traceLinesFull::add);
        rtk1.process(roverData, baseData);

        TraceControl ctrlSummary = new TraceControl();
        ctrlSummary.enabled = true;
        ctrlSummary.stages = TraceControl.STAGE_UDSTATE | TraceControl.STAGE_LAMBDA;
        ctrlSummary.contentFlags = TraceControl.CONTENT_SUMMARY_ONLY;

        RtkProcessor rtk2 = new RtkProcessor(opt);
        rtk2.setTraceControl(ctrlSummary);
        rtk2.setTraceCallback(traceLinesSummary::add);
        rtk2.process(roverData, baseData);

        log.info("Full trace lines: {}, Summary-only trace lines: {}", traceLinesFull.size(), traceLinesSummary.size());
        assertTrue(traceLinesSummary.size() <= traceLinesFull.size(),
                "Summary-only should have fewer or equal lines than full");

        boolean hasBiasInSummary = false;
        for (String line : traceLinesSummary) {
            if (line.contains("STAGE2_BIAS") || line.contains("STAGE5_FLOAT") || line.contains("STAGE5_FIXED")) {
                hasBiasInSummary = true;
            }
        }
        assertFalse(hasBiasInSummary, "Summary-only should not have detail sub-lines");
    }

    @Test
    @DisplayName("11. Trace async with BlockingQueue")
    void testTraceAsyncWithBlockingQueue() throws Exception {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(10000);

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_INPUT | TraceControl.STAGE_RESULT;

        TraceCallback cb = queue::offer;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        List<String> collected = new ArrayList<>();
        queue.drainTo(collected);

        log.info("Async trace lines: {}", collected.size());
        assertTrue(collected.size() > 0, "Should have trace output via BlockingQueue");

        boolean hasStage0 = false, hasStage6 = false;
        for (String line : collected) {
            if (line.contains("STAGE0")) hasStage0 = true;
            if (line.contains("STAGE6")) hasStage6 = true;
        }
        assertTrue(hasStage0, "Should have STAGE0 in async output");
        assertTrue(hasStage6, "Should have STAGE6 in async output");
    }

    @Test
    @DisplayName("12. Trace write to file")
    void testTraceWriteToFile() throws Exception {
        String traceFile = RESULT_DIR + "\\rtk_trace_output.log";

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = 0x7F;

        PrcOpt opt = RtkProcessor.createDefaultOpt();

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(traceFile)))) {
            TraceCallback cb = pw::println;

            RtkProcessor rtk = new RtkProcessor(opt);
            rtk.setTraceControl(ctrl);
            rtk.setTraceCallback(cb);
            rtk.process(roverData, baseData);
        }

        File f = new File(traceFile);
        log.info("Trace file: {} bytes", f.length());
        assertTrue(f.length() > 0, "Trace file should not be empty");

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(traceFile))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        log.info("Trace file lines: {}", lines.size());

        Map<String, Integer> stageCounts = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.startsWith("TRACE|")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 2) {
                    stageCounts.merge(parts[1], 1, Integer::sum);
                }
            }
        }
        log.info("Stage distribution in file:");
        for (Map.Entry<String, Integer> e : stageCounts.entrySet()) {
            log.info("  {}: {}", e.getKey(), e.getValue());
        }
    }

    @Test
    @DisplayName("13. Trace STAGE0 satellite detail lines")
    void testTraceStage0SatelliteDetails() {
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_INPUT;

        TraceCallback cb = traceLines::add;

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(cb);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        boolean hasStage0Main = false, hasStage0Sat = false;
        for (String line : traceLines) {
            if (line.contains("STAGE0|")) hasStage0Main = true;
            if (line.contains("STAGE0_SAT|")) hasStage0Sat = true;
        }

        log.info("Has STAGE0 main: {}, Has STAGE0_SAT: {}", hasStage0Main, hasStage0Sat);
        assertTrue(hasStage0Main, "Should have STAGE0 main lines");

        if (hasStage0Sat) {
            String satLine = traceLines.stream()
                    .filter(l -> l.contains("STAGE0_SAT|"))
                    .findFirst().orElse("");
            log.info("Sample STAGE0_SAT line: {}", satLine);
            assertNotNull(extractField(satLine, "sat="), "STAGE0_SAT should have sat field");
        }
    }

    @Test
    @DisplayName("14. Trace callback exception safety")
    void testTraceCallbackExceptionSafety() {
        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = 0x7F;

        TraceCallback badCb = content -> {
            throw new RuntimeException("Simulated callback error");
        };

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setTraceControl(ctrl);
        rtk.setTraceCallback(badCb);

        assertDoesNotThrow(() -> {
            RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
            log.info("Result with bad callback: total={}, success={}, fail={}",
                    result.totalEpochs, result.successCount, result.failCount);
            assertTrue(result.totalEpochs > 0, "Should still process epochs despite callback exceptions");
        }, "Should not crash even when callback throws exceptions");
    }

    @Test
    @DisplayName("15. Trace combined with RtkProcessor callback and OutputStream")
    void testTraceWithCallbackAndStream() throws Exception {
        String posFile = RESULT_DIR + "\\rtk_trace_combined.pos";
        List<String> traceLines = Collections.synchronizedList(new ArrayList<>());
        List<Sol> solutions = new ArrayList<>();

        TraceControl ctrl = new TraceControl();
        ctrl.enabled = true;
        ctrl.stages = TraceControl.STAGE_INPUT | TraceControl.STAGE_RESULT;

        PrcOpt opt = RtkProcessor.createDefaultOpt();

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(posFile))) {
            RtkProcessor rtk = new RtkProcessor(opt, new PosHandler() {
                @Override public void onSolution(Sol sol, Ssat[] ssat) { solutions.add(sol); }
                @Override public void onPosFail(GTime time, String msg) { }
                @Override public void onFinish(int total, int success, int fail) {
                    log.info("Combined test onFinish: total={}, success={}, fail={}", total, success, fail);
                }
            }, os);

            rtk.setTraceControl(ctrl);
            rtk.setTraceCallback(traceLines::add);

            RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

            log.info("Combined: solutions={}, traceLines={}", solutions.size(), traceLines.size());
            assertTrue(solutions.size() > 0, "Should have callback solutions");
            assertTrue(traceLines.size() > 0, "Should have trace output");
        }

        File f = new File(posFile);
        assertTrue(f.length() > 0, "Pos file should not be empty");
    }

    private static String extractField(String line, String fieldName) {
        int idx = line.indexOf(fieldName);
        if (idx < 0) return null;
        int start = idx + fieldName.length();
        int end = line.indexOf('|', start);
        if (end < 0) end = line.length();
        return line.substring(start, end);
    }
}