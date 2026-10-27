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

    private static final String ROVER_PATH = resolveLocalPath(TestDataConfig.getRoverFile());
    private static final String BASE_PATH = resolveLocalPath(TestDataConfig.getBaseFile());

    private static String resolveLocalPath(String path) {
        if (path != null && !path.isEmpty() && new File(path).exists()) return path;
        return null;
    }
    private static final String RESULT_DIR = TestDataConfig.getResultDir();

    private static byte[] roverData;
    private static byte[] baseData;
    private static boolean dataAvailable;

    @BeforeAll
    static void loadData() throws IOException {
        dataAvailable = ROVER_PATH != null && BASE_PATH != null
                && new File(ROVER_PATH).exists() && new File(BASE_PATH).exists();
        if (!dataAvailable) {
            log.warn("RTCM3 base+rover data not available, skipping data-dependent tests");
            return;
        }
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        }
        new File(RESULT_DIR).mkdirs();
        log.info("Loaded Rover: {} bytes, Base: {} bytes", roverData.length, baseData.length);
    }

    private void skipIfNoData() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "No RTCM3 base+rover data");
    }

    @Test
    @DisplayName("1. RTK from byte[] with callback")
    void testRtkFromBytesWithCallback() {
        skipIfNoData();
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
        skipIfNoData();
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("RtkResult: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertFalse(result.solutions.isEmpty(), "Should have solutions");

        SolData first = result.solutions.get(0);
        Position llh = first.getPosition(CoordType.LLH);
        if (llh != null) {
            log.info("First solution: lat={}, lon={}, h={}, ns={}, stat={}",
                    String.format("%.9f", llh.v1),
                    String.format("%.9f", llh.v2),
                    String.format("%.3f", llh.v3), first.numSat, first.status);
        }
    }

    @Test
    @DisplayName("3. RTK from file path")
    void testRtkFromFilePath() throws Exception {
        skipIfNoData();
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(ROVER_PATH, BASE_PATH);

        log.info("RtkResult from file: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs from file");
        assertTrue(result.successCount > 0, "Should have successful solutions from file");
    }

    @Test
    @DisplayName("4. RTK single stream (rover only, fallback to SPP)")
    void testRtkSingleStream() {
        skipIfNoData();
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData);

        log.info("Single stream: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(result.successCount > 0, "Should have successful solutions in single stream");
    }

    @Test
    @DisplayName("5. Write .pos file")
    void testWritePosFile() throws Exception {
        skipIfNoData();
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
        skipIfNoData();
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
        skipIfNoData();
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;

        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("Custom config: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(result.successCount > 0, "Should have successful solutions with custom config");
    }

    @Test
    @DisplayName("8. RTK with manual base position")
    void testRtkManualBasePosition() {
        skipIfNoData();
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.refpos = Constants.POSOPT_POS_XYZ;

        RtkProcessor rtk = new RtkProcessor(opt);
        double[] basePos = {-493099.65, 5551400.76, 3092551.49};
        rtk.setBasePosition(basePos);

        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
        log.info("Manual base pos: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(result.successCount > 0, "Should have successful solutions with manual base position");
    }

    @Test
    @DisplayName("9. RTK streaming mode (feedRover/feedBase)")
    void testRtkStreamingMode() {
        skipIfNoData();
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
        skipIfNoData();
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        int fixCount = 0, floatCount = 0, singleCount = 0;
        for (SolData sd : result.solutions) {
            if (sd.status == SolutionStatus.FIX) fixCount++;
            else if (sd.status == SolutionStatus.FLOAT) floatCount++;
            else if (sd.status == SolutionStatus.SINGLE) singleCount++;
        }

        log.info("Solution types [SYS_ALL]: Fix={}, Float={}, Single={}", fixCount, floatCount, singleCount);
        log.info("Total: {}, Success: {}, Fail: {}", result.totalEpochs, result.successCount, result.failCount);

        PrcOpt optCmp = RtkProcessor.createDefaultOpt();
        optCmp.navsys = Constants.SYS_CMP;
        RtkProcessor rtkCmp = new RtkProcessor(optCmp);
        RtkProcessor.RtkResult resultCmp = rtkCmp.process(roverData, baseData);
        int fixC = 0, floatC = 0, singleC = 0;
        for (SolData sd : resultCmp.solutions) {
            if (sd.status == SolutionStatus.FIX) fixC++;
            else if (sd.status == SolutionStatus.FLOAT) floatC++;
            else if (sd.status == SolutionStatus.SINGLE) singleC++;
        }
        log.info("Solution types [SYS_CMP]: Fix={}, Float={}, Single={}", fixC, floatC, singleC);

        assertEquals(result.successCount, fixCount + floatCount + singleCount, "Solution type counts should sum to success count");

        if (!result.solutions.isEmpty()) {
            SolData last = result.solutions.get(result.solutions.size() - 1);
            Position llh2 = last.getPosition(CoordType.LLH);
            if (llh2 != null) {
                log.info("Last solution: lat={}, lon={}, h={}, stat={}, ns={}",
                        String.format("%.9f", llh2.v1),
                        String.format("%.9f", llh2.v2),
                        String.format("%.3f", llh2.v3), last.status, last.numSat);
            }
        }
    }

    @Test
    @DisplayName("7. GPS week rollover analysis")
    void testGpsWeekRolloverAnalysis() {
        int dataGpsWeek = 2419;
        GTime dataStart = TimeSystem.gpst2time(dataGpsWeek, 0);
        double[] dataYmd = TimeSystem.time2ymdhms(dataStart);
        log.info("Data GPS week {} -> {} (week start)", dataGpsWeek,
                String.format("%04d/%02d/%02d", (int)dataYmd[0], (int)dataYmd[1], (int)dataYmd[2]));

        double dataTow = 11.0 * 3600 + 0.0 * 60 + 30.0;
        GTime dataTime = TimeSystem.gpst2time(dataGpsWeek, dataTow);
        double[] dataTimeYmd = TimeSystem.time2ymdhms(dataTime);
        log.info("Data time: GPS_week={} TOW={}s -> {}", dataGpsWeek, (long)dataTow,
                String.format("%04d/%02d/%02d %02d:%02d:%02d UTC",
                        (int)dataTimeYmd[0], (int)dataTimeYmd[1], (int)dataTimeYmd[2],
                        (int)dataTimeYmd[3], (int)dataTimeYmd[4], (int)dataTimeYmd[5]));

        GTime fileTime = TimeSystem.epoch2time(new double[]{2026, 9, 16, 0, 0, 0});
        GTime fileGps = TimeSystem.utc2gpst(fileTime);
        int[] fileWkArr = new int[1];
        double fileTowP = TimeSystem.time2gpst(fileGps, fileWkArr);
        int fileGpsWeek = fileWkArr[0];
        log.info("C adjweek: timeget()=2026-09-16 -> GPS_week={} TOW_P={}s", fileGpsWeek, (long)fileTowP);

        double adjTow = dataTow;
        if (adjTow < fileTowP - 302400.0) adjTow += 604800.0;
        else if (adjTow > fileTowP + 302400.0) adjTow -= 604800.0;
        GTime cResult = TimeSystem.gpst2time(fileGpsWeek, adjTow);
        double[] cYmd = TimeSystem.time2ymdhms(cResult);
        log.info("C adjweek result: GPS_week={} TOW={}s -> {} (WRONG!)",
                fileGpsWeek, (long)adjTow,
                String.format("%04d/%02d/%02d %02d:%02d:%02d UTC",
                        (int)cYmd[0], (int)cYmd[1], (int)cYmd[2],
                        (int)cYmd[3], (int)cYmd[4], (int)cYmd[5]));

        GTime javaResult = TimeSystem.gpst2time(dataGpsWeek, dataTow);
        double[] javaYmd = TimeSystem.time2ymdhms(javaResult);
        log.info("Java result: GPS_week={} TOW={}s -> {} (CORRECT)",
                dataGpsWeek, (long)dataTow,
                String.format("%04d/%02d/%02d %02d:%02d:%02d UTC",
                        (int)javaYmd[0], (int)javaYmd[1], (int)javaYmd[2],
                        (int)javaYmd[3], (int)javaYmd[4], (int)javaYmd[5]));

        int weekDiff = fileGpsWeek - dataGpsWeek;
        log.info("Week diff: {} weeks = {} days -> C version time offset = {} days",
                weekDiff, weekDiff * 7, weekDiff * 7);
    }

    @Test
    @DisplayName("8. RTCM3 time diagnostic")
    void testRtcm3TimeDiagnostic() throws Exception {
        skipIfNoData();
        org.rtklib.java.rtcm.Rtcm rtcm = new org.rtklib.java.rtcm.Rtcm();
        java.util.List<String> timeLog = new java.util.ArrayList<>();
        int[] msgCount = {0};
        int[] obsCount = {0};
        int[] ephCount = {0};

        byte[][] dataArr = {roverData, baseData};
        String[] label = {"ROVER", "BASE"};
        for (int d = 0; d < 2; d++) {
            rtcm = new org.rtklib.java.rtcm.Rtcm();
            byte[] data = dataArr[d];
            int pos = 0;
            while (pos < data.length) {
                int consumed = rtcm.input(data, pos, data.length - pos);
                if (consumed > 0) {
                    msgCount[0]++;
                    int type = rtcm.type;
                    if (type >= 1071 && type <= 1077 || type >= 1124 && type <= 1127) {
                        obsCount[0]++;
                        if (rtcm.obs.n > 0 && rtcm.obs.data[0].sat != 0) {
                            GTime t = rtcm.obs.data[0].time;
                            double[] ymdhms = TimeSystem.time2ymdhms(t);
                            int[] weekArr = new int[1];
                            double tow = TimeSystem.time2gpst(t, weekArr);
                            String entry = String.format("%s MSM%d obs[%d] time: %04d/%02d/%02d %02d:%02d:%06.3f  GPS_week=%d tow=%.3f  timeInit=%b",
                                    label[d], type, obsCount[0],
                                    (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                                    (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                                    weekArr[0], tow, rtcm.isTimeInitialized());
                            timeLog.add(entry);
                            if (obsCount[0] <= 3) log.info(entry);
                        }
                    }
                    if (type == 1019 || type == 1020 || type == 1042 || type == 1044 || type == 1045 || type == 1046) {
                        ephCount[0]++;
                        if (rtcm.ephsat != 0) {
                            Eph e = rtcm.nav.eph[rtcm.ephsat - 1];
                            if (e != null && e.sat == rtcm.ephsat) {
                                double[] ymdhms = TimeSystem.time2ymdhms(e.toe);
                                int[] weekArr = new int[1];
                                double tow = TimeSystem.time2gpst(e.toe, weekArr);
                                String entry = String.format("%s EPH(type=%d sat=%d) toe: %04d/%02d/%02d %02d:%02d:%06.3f  GPS_week=%d  timeInit=%b",
                                        label[d], type, rtcm.ephsat,
                                        (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                                        (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                                        weekArr[0], rtcm.isTimeInitialized());
                                timeLog.add(entry);
                                if (ephCount[0] <= 3) log.info(entry);
                            }
                        }
                    }
                    if (type == 1005 || type == 1006) {
                        double[] ymdhms = TimeSystem.time2ymdhms(rtcm.time);
                        String entry = String.format("%s STA1005/6 rtcm.time: %04d/%02d/%02d %02d:%02d:%06.3f  timeInit=%b",
                                label[d],
                                (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                                rtcm.isTimeInitialized());
                        timeLog.add(entry);
                        if (timeLog.size() <= 6) log.info(entry);
                    }
                    pos += consumed;
                } else if (consumed == 0) {
                    break;
                } else {
                    pos++;
                }
            }
        }

        log.info("Total messages: {}, obs: {}, eph: {}", msgCount[0], obsCount[0], ephCount[0]);
        if (!timeLog.isEmpty()) {
            log.info("=== First 5 time entries ===");
            for (int i = 0; i < Math.min(5, timeLog.size()); i++) {
                log.info("  {}", timeLog.get(i));
            }
            log.info("=== Last 5 time entries ===");
            for (int i = Math.max(0, timeLog.size() - 5); i < timeLog.size(); i++) {
                log.info("  {}", timeLog.get(i));
            }
        }
        assertTrue(obsCount[0] > 0, "Should have decoded MSM observations");
    }
}