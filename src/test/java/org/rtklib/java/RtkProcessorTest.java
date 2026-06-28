package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtkProcessor Test")
public class RtkProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(RtkProcessorTest.class);

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
    @DisplayName("1. RTK from byte[] with callback")
    void testRtkFromBytesWithCallback() {
        List<Sol> callbackSolutions = Collections.synchronizedList(new ArrayList<>());
        List<String> failMsgs = Collections.synchronizedList(new ArrayList<>());
        int[] finishInfo = {0, 0, 0};

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt, new PosHandler() {
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

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Callback solutions: {}, fail msgs: {}", callbackSolutions.size(), failMsgs.size());
        log.info("RtkResult: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        log.info("Finish info: total={}, success={}, fail={}", finishInfo[0], finishInfo[1], finishInfo[2]);

        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(callbackSolutions.size() > 0, "Should have callback solutions");
        assertEquals(callbackSolutions.size(), result.successCount, "Callback count should match result success count");
        assertEquals(finishInfo[1], result.successCount, "Finish success should match result success count");

        for (int i = 0; i < Math.min(5, callbackSolutions.size()); i++) {
            Sol s = callbackSolutions.get(i);
            double[] llh = new double[3];
            CoordTransform.ecef2pos(s.rr, llh);
            log.info("  Sol[{}]: lat={} lon={} h={} ns={} stat={}", i,
                    String.format("%.9f", Math.toDegrees(llh[0])),
                    String.format("%.9f", Math.toDegrees(llh[1])),
                    String.format("%.3f", llh[2]), s.ns, s.stat);
        }
    }

    @Test
    @DisplayName("2. RTK from byte[] without callback")
    void testRtkFromBytesNoCallback() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("RtkResult: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertFalse(result.solutions.isEmpty(), "Should have solutions");

        Sol first = result.solutions.get(0);
        double[] llh = new double[3];
        CoordTransform.ecef2pos(first.rr, llh);
        log.info("First solution: lat={}, lon={}, h={}, ns={}, stat={}",
                String.format("%.9f", Math.toDegrees(llh[0])),
                String.format("%.9f", Math.toDegrees(llh[1])),
                String.format("%.3f", llh[2]), first.ns, first.stat);
    }

    @Test
    @DisplayName("3. RTK from file path")
    void testRtkFromFilePath() throws Exception {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(ROVER_PATH, BASE_PATH);

        log.info("RtkResult from file: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs from file");
    }

    @Test
    @DisplayName("4. RTK single stream (rover only, fallback to SPP)")
    void testRtkSingleStream() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData);

        log.info("Single stream: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("5. Write .pos file")
    void testWritePosFile() throws Exception {
        String posFile = RESULT_DIR + "\\rtk_processor_output.pos";

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
        RtkProcessor.writePosFile(result, posFile);

        File f = new File(posFile);
        log.info("Pos file: {} bytes", f.length());
        assertTrue(f.length() > 0, "Output file should not be empty");

        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(posFile))) {
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        log.info("Pos file lines: {}", lines.size());
        assertTrue(lines.size() > 5, "Pos file should have more than 5 lines");

        boolean hasData = false;
        for (String line : lines) {
            if (line.startsWith("  20") && line.contains("/") && line.contains(":")) hasData = true;
        }
        assertTrue(hasData, "Should have data lines");

        log.info("First 3 data lines:");
        int count = 0;
        for (String line : lines) {
            if (line.startsWith("  20") && count < 3) {
                log.info("  {}", line.trim());
                count++;
            }
        }
    }

    @Test
    @DisplayName("6. RTK with OutputStream")
    void testRtkWithOutputStream() throws Exception {
        String posFile = RESULT_DIR + "\\rtk_processor_stream.pos";
        PrcOpt opt = RtkProcessor.createDefaultOpt();

        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(posFile))) {
            RtkProcessor rtk = new RtkProcessor(opt, null, os);
            RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
            log.info("OutputStream result: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        }

        File f = new File(posFile);
        assertTrue(f.length() > 0, "Output file should not be empty");
        log.info("Stream output file: {} bytes", f.length());
    }

    @Test
    @DisplayName("7. RTK with custom configuration")
    void testRtkCustomConfig() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;

        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Custom config: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("8. RTK with manual base position")
    void testRtkManualBasePosition() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.refposmode = Constants.REFPOS_FIXED;

        RtkProcessor rtk = new RtkProcessor(opt);
        double[] basePos = {-493099.65, 5551400.76, 3092551.49};
        rtk.setBasePosition(basePos);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
        log.info("Manual base pos: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
    }

    @Test
    @DisplayName("9. RTK streaming mode (feedRover/feedBase)")
    void testRtkStreamingMode() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        List<Sol> solutions = new ArrayList<>();

        RtkProcessor rtk = new RtkProcessor(opt, new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) { solutions.add(sol); }
            @Override public void onPosFail(GTime time, String msg) { }
            @Override public void onFinish(int total, int success, int fail) {
                log.info("Streaming onFinish: total={}, success={}, fail={}", total, success, fail);
            }
        });

        int chunkSize = 1024;
        int minLen = Math.min(roverData.length, baseData.length);
        for (int offset = 0; offset < minLen; offset += chunkSize) {
            int roverEnd = Math.min(offset + chunkSize, roverData.length);
            int baseEnd = Math.min(offset + chunkSize, baseData.length);
            rtk.feedRover(roverData, offset, roverEnd - offset);
            rtk.feedBase(baseData, offset, baseEnd - offset);
        }
        if (roverData.length > minLen) {
            rtk.feedRover(roverData, minLen, roverData.length - minLen);
        }
        if (baseData.length > minLen) {
            rtk.feedBase(baseData, minLen, baseData.length - minLen);
        }

        RtkProcessor.RtkResult result = rtk.finish();

        log.info("Streaming result: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs in streaming mode");
        assertTrue(solutions.size() > 0, "Should have callback solutions in streaming mode");
    }

    @Test
    @DisplayName("10. RTK result statistics")
    void testRtkResultStatistics() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        int fixCount = 0, floatCount = 0, singleCount = 0;
        for (Sol sol : result.solutions) {
            if (sol.stat == Constants.SOLQ_FIX) fixCount++;
            else if (sol.stat == Constants.SOLQ_FLOAT) floatCount++;
            else if (sol.stat == Constants.SOLQ_SINGLE) singleCount++;
        }

        log.info("Solution types: Fix={}, Float={}, Single={}", fixCount, floatCount, singleCount);
        log.info("Total: {}, Success: {}, Fail: {}", result.totalEpochs, result.successCount, result.failCount);
        assertEquals(result.successCount, fixCount + floatCount + singleCount, "Solution type counts should sum to success count");

        if (!result.solutions.isEmpty()) {
            Sol last = result.solutions.get(result.solutions.size() - 1);
            double[] llh = new double[3];
            CoordTransform.ecef2pos(last.rr, llh);
            log.info("Last solution: lat={}, lon={}, h={}, stat={}, ns={}",
                    String.format("%.9f", Math.toDegrees(llh[0])),
                    String.format("%.9f", Math.toDegrees(llh[1])),
                    String.format("%.3f", llh[2]), last.stat, last.ns);
        }
    }
}