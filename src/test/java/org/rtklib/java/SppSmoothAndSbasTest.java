package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.common.BitUtils;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ionosphere.SbasCorrection;
import org.rtklib.java.ionosphere.SbsMsgReader;
import org.rtklib.java.time.TimeSystem;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SPP平滑与SBAS改正测试")
public class SppSmoothAndSbasTest {

    @Test
    @DisplayName("SPP平滑：滑动窗口均值计算正确性")
    void testSppSmoothAverage() {
        int windowSize = 5;
        List<double[]> buf = new ArrayList<>();

        double[][] positions = {
            {-2267749.0, 5009154.0, 3221090.0},
            {-2267750.0, 5009155.0, 3221091.0},
            {-2267751.0, 5009156.0, 3221092.0},
            {-2267752.0, 5009157.0, 3221093.0},
            {-2267753.0, 5009158.0, 3221094.0},
        };

        for (double[] pos : positions) {
            buf.add(new double[]{pos[0], pos[1], pos[2]});
            if (buf.size() > windowSize) buf.remove(0);
        }

        double[] avg = computeAverage(buf);
        assertEquals(-2267751.0, avg[0], 0.01);
        assertEquals(5009156.0, avg[1], 0.01);
        assertEquals(3221092.0, avg[2], 0.01);
    }

    @Test
    @DisplayName("SPP平滑：滑动窗口溢出时移除最旧数据")
    void testSppSmoothWindowOverflow() {
        int windowSize = 3;
        List<double[]> buf = new ArrayList<>();

        double[][] positions = {
            {1.0, 2.0, 3.0},
            {4.0, 5.0, 6.0},
            {7.0, 8.0, 9.0},
            {10.0, 11.0, 12.0},
            {13.0, 14.0, 15.0},
        };

        for (double[] pos : positions) {
            buf.add(new double[]{pos[0], pos[1], pos[2]});
            if (buf.size() > windowSize) buf.remove(0);
        }

        assertEquals(3, buf.size());
        double[] avg = computeAverage(buf);
        assertEquals(10.0, avg[0], 0.01);
        assertEquals(11.0, avg[1], 0.01);
        assertEquals(12.0, avg[2], 0.01);
    }

    @Test
    @DisplayName("SPP平滑：窗口为1时等于最新值")
    void testSppSmoothWindowOne() {
        int windowSize = 1;
        List<double[]> buf = new ArrayList<>();

        double[][] positions = {
            {100.0, 200.0, 300.0},
            {400.0, 500.0, 600.0},
        };

        for (double[] pos : positions) {
            buf.add(new double[]{pos[0], pos[1], pos[2]});
            if (buf.size() > windowSize) buf.remove(0);
        }

        assertEquals(1, buf.size());
        double[] avg = computeAverage(buf);
        assertEquals(400.0, avg[0], 0.01);
        assertEquals(500.0, avg[1], 0.01);
        assertEquals(600.0, avg[2], 0.01);
    }

    @Test
    @DisplayName("SPP平滑：PrcOpt.sppsmooth配置传递正确")
    void testPrcOptSppSmoothConfig() {
        PrcOpt opt1 = new PrcOpt();
        assertEquals(0, opt1.sppsmooth);

        opt1.sppsmooth = 10;
        PrcOpt opt2 = new PrcOpt(opt1);
        assertEquals(10, opt2.sppsmooth);

        opt2.sppsmooth = 20;
        assertEquals(10, opt1.sppsmooth);
    }

    @Test
    @DisplayName("SBAS：SbsMsgReader解析.sbs文件格式")
    void testSbsMsgReaderParseFile(@TempDir Path tempDir) throws Exception {
        File sbsFile = tempDir.resolve("test.sbs").toFile();
        try (PrintWriter pw = new PrintWriter(new FileWriter(sbsFile))) {
            pw.println("# Test SBAS messages");
            pw.println("2300 345600 129 1 : 40000000000000000000000000000000000000000000000000000000000000000000");
            pw.println("2300 345600 129 2 : 1C800C3F000000000000000000000000000000000000000000000000000000000000");
            pw.println("2300 345630 129 6 : 00000000000000000000000000000000000000000000000000000000000000000000");
        }

        List<SbsMsg> msgs = SbsMsgReader.readsbsmsg(sbsFile.getAbsolutePath());
        assertFalse(msgs.isEmpty(), "Should parse at least one message");
        assertTrue(msgs.size() >= 1, "Should have messages");

        for (SbsMsg msg : msgs) {
            assertEquals(2300, msg.week);
            assertTrue(msg.tow >= 345600);
            assertEquals(129, msg.prn);
        }
    }

    @Test
    @DisplayName("SBAS：SbsMsgReader按PRN筛选")
    void testSbsMsgReaderFilterByPrn(@TempDir Path tempDir) throws Exception {
        File sbsFile = tempDir.resolve("test_filter.sbs").toFile();
        try (PrintWriter pw = new PrintWriter(new FileWriter(sbsFile))) {
            pw.println("2300 345600 129 1 : 40000000000000000000000000000000000000000000000000000000000000000000");
            pw.println("2300 345600 137 1 : 40000000000000000000000000000000000000000000000000000000000000000000");
            pw.println("2300 345600 129 2 : 1C800C3F000000000000000000000000000000000000000000000000000000000000");
        }

        List<SbsMsg> allMsgs = SbsMsgReader.readsbsmsg(sbsFile.getAbsolutePath());
        List<SbsMsg> filtered = SbsMsgReader.readsbsmsg(sbsFile.getAbsolutePath(), 129);

        assertTrue(allMsgs.size() > filtered.size());
        for (SbsMsg msg : filtered) {
            assertEquals(129, msg.prn);
        }
    }

    @Test
    @DisplayName("SBAS：parseLine解析各字段正确")
    void testSbsMsgParseLine() {
        String line = "2300 345600 129 2 : 1C800C3F000000000000000000000000000000000000000000000000000000000000";
        SbsMsg msg = SbsMsgReader.parseLine(line);

        assertNotNull(msg);
        assertEquals(2300, msg.week);
        assertEquals(345600, msg.tow);
        assertEquals(129, msg.prn);
        assertEquals(0x1C, msg.msg[0] & 0xFF);
        assertEquals(0x80, msg.msg[1] & 0xFF);
    }

    @Test
    @DisplayName("SBAS：parseLine处理无效行返回null")
    void testSbsMsgParseInvalidLine() {
        assertNull(SbsMsgReader.parseLine(""));
        assertNull(SbsMsgReader.parseLine("# comment"));
        assertNull(SbsMsgReader.parseLine("no colon here"));
        assertNull(SbsMsgReader.parseLine("abc def : 00"));
    }

    @Test
    @DisplayName("SBAS：sbsupdatecorr拒绝week=0的消息")
    void testSbsUpdateCorrRejectsZeroWeek() {
        SbsMsg msg = new SbsMsg();
        msg.week = 0;
        msg.tow = 0;
        msg.prn = 129;
        Nav nav = new Nav();
        int result = SbasCorrection.sbsupdatecorr(msg, nav);
        assertEquals(-1, result);
    }

    @Test
    @DisplayName("SBAS：Type 1消息解码更新sbssat卫星列表")
    void testSbsType1Decode() {
        SbsMsg msg = createType1Msg(2300, 345600, 129);
        Nav nav = new Nav();
        int result = SbasCorrection.sbsupdatecorr(msg, nav);
        assertEquals(1, result, "Type 1 should return 1 on success");
        assertTrue(nav.sbssat.nsat > 0, "Should have satellites after Type 1 decode");
    }

    @Test
    @DisplayName("SBAS：Type 7消息解码设置tlat和ai参数")
    void testSbsType7Decode() {
        Nav nav = new Nav();

        SbsMsg msg1 = createType1Msg(2300, 345600, 129);
        SbasCorrection.sbsupdatecorr(msg1, nav);

        SbsMsg msg7 = createType7Msg(2300, 345600, 129);
        int result = SbasCorrection.sbsupdatecorr(msg7, nav);
        assertEquals(7, result, "Type 7 should return 7 on success");
        assertTrue(nav.sbssat.tlat > 0, "tlat should be set after Type 7");
    }

    @Test
    @DisplayName("SBAS：applySbsMessages批量应用改正")
    void testApplySbsMessagesBatch() {
        Nav nav = new Nav();
        List<SbsMsg> msgs = new ArrayList<>();
        msgs.add(createType1Msg(2300, 345600, 129));
        msgs.add(createType7Msg(2300, 345630, 129));

        int count = SbsMsgReader.applySbsMessages(msgs, nav);
        assertTrue(count >= 1, "At least one correction should be applied");
    }

    @Test
    @DisplayName("SBAS：readsbsmsgAndApply完整流程")
    void testReadsbsmsgAndApply(@TempDir Path tempDir) throws Exception {
        File sbsFile = tempDir.resolve("test_apply.sbs").toFile();
        try (PrintWriter pw = new PrintWriter(new FileWriter(sbsFile))) {
            pw.println("2300 345600 129 1 : " + buildType1Hex());
            pw.println("2300 345630 129 7 : " + buildType7Hex());
        }

        Nav nav = new Nav();
        int count = SbsMsgReader.readsbsmsgAndApply(sbsFile.getAbsolutePath(), nav);
        assertTrue(count >= 1, "At least one correction should be applied from file");
    }

    @Test
    @DisplayName("SBAS：按历元时间逐步应用（PostPosProcessor模式）")
    void testEpochByEpochSbsApplication() {
        Nav nav = new Nav();
        List<SbsMsg> msgs = new ArrayList<>();
        msgs.add(createType1Msg(2300, 345600, 129));
        msgs.add(createType7Msg(2300, 345630, 129));

        GTime t1 = TimeSystem.gpst2time(2300, 345590.0);
        GTime t2 = TimeSystem.gpst2time(2300, 345610.0);
        GTime t3 = TimeSystem.gpst2time(2300, 345640.0);

        int idx = 0;

        while (idx < msgs.size()) {
            SbsMsg msg = msgs.get(idx);
            GTime msgTime = TimeSystem.gpst2time(msg.week, msg.tow);
            if (TimeSystem.timediff(msgTime, t1) > 0.0) break;
            SbasCorrection.sbsupdatecorr(msg, nav);
            idx++;
        }
        assertEquals(0, idx, "No messages should be applied before their time");

        while (idx < msgs.size()) {
            SbsMsg msg = msgs.get(idx);
            GTime msgTime = TimeSystem.gpst2time(msg.week, msg.tow);
            if (TimeSystem.timediff(msgTime, t2) > 0.0) break;
            SbasCorrection.sbsupdatecorr(msg, nav);
            idx++;
        }
        assertEquals(1, idx, "First message should be applied at t2");

        while (idx < msgs.size()) {
            SbsMsg msg = msgs.get(idx);
            GTime msgTime = TimeSystem.gpst2time(msg.week, msg.tow);
            if (TimeSystem.timediff(msgTime, t3) > 0.0) break;
            SbasCorrection.sbsupdatecorr(msg, nav);
            idx++;
        }
        assertEquals(2, idx, "All messages should be applied by t3");
    }

    @Test
    @DisplayName("SBAS：sbstropcorr对流层改正计算")
    void testSbsTropCorr() {
        GTime time = TimeSystem.gpst2time(2300, 345600.0);
        double[] pos = {0.6018, 1.0458, 100.0};
        double[] azel = {0.0, Math.PI / 4.0};
        double[] var = {0.0};

        double ztd = SbasCorrection.sbstropcorr(time, pos, azel, var);
        assertTrue(ztd > 0.0, "Tropospheric delay should be positive");
        assertTrue(var[0] > 0.0, "Variance should be positive");
    }

    @Test
    @DisplayName("SBAS：sbstropcorr对无效位置返回0")
    void testSbsTropCorrInvalidPos() {
        GTime time = TimeSystem.gpst2time(2300, 345600.0);
        double[] pos = {0.6018, 1.0458, -200.0};
        double[] azel = {0.0, Math.PI / 4.0};
        double[] var = {0.0};

        double ztd = SbasCorrection.sbstropcorr(time, pos, azel, var);
        assertEquals(0.0, ztd, 0.001, "Should return 0 for invalid height");
    }

    @Test
    @DisplayName("SBAS：sbsioncorr无电离层数据时返回0")
    void testSbsIonCorrNoData() {
        GTime time = TimeSystem.gpst2time(2300, 345600.0);
        Nav nav = new Nav();
        double[] pos = {0.6018, 1.0458, 100.0};
        double[] azel = {0.0, Math.PI / 4.0};
        double[] delay = {0.0};
        double[] var = {0.0};

        int result = SbasCorrection.sbsioncorr(time, nav, pos, azel, delay, var);
        assertEquals(0, result, "Should return 0 when no iono data available");
    }

    @Test
    @DisplayName("SBAS：sbssatcorr无改正数据时返回0")
    void testSbsSatCorrNoData() {
        GTime time = TimeSystem.gpst2time(2300, 345600.0);
        Nav nav = new Nav();
        int sat = SatUtils.satno(Constants.SYS_GPS, 1);
        double[] rs = {-2267749.0, 5009154.0, 3221090.0};
        double[] dts = {0.0};
        double[] var = {0.0};

        int result = SbasCorrection.sbssatcorr(time, sat, nav, rs, dts, var);
        assertEquals(0, result, "Should return 0 when no SBAS sat corrections available");
    }

    @Test
    @DisplayName("SBAS：.sbs文件不存在时返回空列表")
    void testSbsMsgReaderNonExistentFile() {
        List<SbsMsg> msgs = SbsMsgReader.readsbsmsg("/nonexistent/path/test.sbs");
        assertNotNull(msgs);
        assertTrue(msgs.isEmpty(), "Should return empty list for non-existent file");
    }

    @Test
    @DisplayName("SBAS：applySbsMessages处理null输入")
    void testApplySbsMessagesNull() {
        assertEquals(0, SbsMsgReader.applySbsMessages(null, new Nav()));
        assertEquals(0, SbsMsgReader.applySbsMessages(new ArrayList<>(), null));
        assertEquals(0, SbsMsgReader.applySbsMessages(null, null));
    }

    @Test
    @DisplayName("SBAS：SbsMsg默认值正确")
    void testSbsMsgDefaults() {
        SbsMsg msg = new SbsMsg();
        assertEquals(0, msg.week);
        assertEquals(0, msg.tow);
        assertEquals(0, msg.prn);
        assertNotNull(msg.msg);
        assertEquals(29, msg.msg.length);
    }

    @Test
    @DisplayName("SBAS：Nav中SBAS数据结构初始化正确")
    void testNavSbsInitialization() {
        Nav nav = new Nav();
        assertNotNull(nav.sbssat);
        assertNotNull(nav.sbsion);
        assertEquals(Constants.MAXBAND + 1, nav.sbsion.length);
        assertNotNull(nav.sbssat.sat);
    }

    @Test
    @DisplayName("SBAS：读取项目data目录下的.sbs测试文件")
    void testReadProjectSbsFile() {
        String sbsPath = "data/rtcm3_test/test.sbs";
        java.io.File f = new java.io.File(sbsPath);
        if (!f.exists()) {
            sbsPath = "D:/code/rtklib_java/data/rtcm3_test/test.sbs";
            f = new java.io.File(sbsPath);
        }
        if (!f.exists()) {
            return;
        }

        List<SbsMsg> msgs = SbsMsgReader.readsbsmsg(sbsPath);
        assertFalse(msgs.isEmpty(), "Should parse messages from test.sbs");

        Nav nav = new Nav();
        int count = SbsMsgReader.applySbsMessages(msgs, nav);
        assertTrue(count >= 1, "At least one correction should be applied");
    }

    @Test
    @DisplayName("SBAS：按历元逐步应用.sbs文件中的改正")
    void testEpochByEpochSbsFromFile() {
        String sbsPath = "data/rtcm3_test/test.sbs";
        java.io.File f = new java.io.File(sbsPath);
        if (!f.exists()) {
            sbsPath = "D:/code/rtklib_java/data/rtcm3_test/test.sbs";
            f = new java.io.File(sbsPath);
        }
        if (!f.exists()) {
            return;
        }

        List<SbsMsg> msgs = SbsMsgReader.readsbsmsg(sbsPath);
        Nav nav = new Nav();
        int idx = 0;

        GTime t1 = TimeSystem.gpst2time(2300, 345590.0);
        while (idx < msgs.size()) {
            SbsMsg msg = msgs.get(idx);
            GTime msgTime = TimeSystem.gpst2time(msg.week, msg.tow);
            if (TimeSystem.timediff(msgTime, t1) > 0.0) break;
            SbasCorrection.sbsupdatecorr(msg, nav);
            idx++;
        }
        int afterT1 = idx;

        GTime t2 = TimeSystem.gpst2time(2300, 345700.0);
        while (idx < msgs.size()) {
            SbsMsg msg = msgs.get(idx);
            GTime msgTime = TimeSystem.gpst2time(msg.week, msg.tow);
            if (TimeSystem.timediff(msgTime, t2) > 0.0) break;
            SbasCorrection.sbsupdatecorr(msg, nav);
            idx++;
        }
        int afterT2 = idx;

        assertTrue(afterT2 > afterT1, "More messages should be applied at later epoch time");
    }

    private static double[] computeAverage(List<double[]> buf) {
        double[] avg = new double[3];
        for (double[] pos : buf) {
            avg[0] += pos[0];
            avg[1] += pos[1];
            avg[2] += pos[2];
        }
        int n = buf.size();
        avg[0] /= n;
        avg[1] /= n;
        avg[2] /= n;
        return avg;
    }

    private static SbsMsg createType1Msg(int week, int tow, int prn) {
        SbsMsg msg = new SbsMsg();
        msg.week = week;
        msg.tow = tow;
        msg.prn = prn;
        byte[] data = new byte[29];
        BitUtils.setbitu(data, 8, 6, 1);
        BitUtils.setbitu(data, 14, 2, 0);
        for (int i = 1; i <= 37; i++) {
            BitUtils.setbitu(data, 13 + i, 1, 1);
        }
        BitUtils.setbitu(data, 224, 2, 0);
        System.arraycopy(data, 0, msg.msg, 0, 29);
        msg.msg[28] &= (byte) 0xC0;
        return msg;
    }

    private static SbsMsg createType7Msg(int week, int tow, int prn) {
        SbsMsg msg = new SbsMsg();
        msg.week = week;
        msg.tow = tow;
        msg.prn = prn;
        byte[] data = new byte[29];
        BitUtils.setbitu(data, 8, 6, 7);
        BitUtils.setbitu(data, 14, 4, 2);
        BitUtils.setbitu(data, 18, 2, 0);
        for (int i = 0; i < 51; i++) {
            BitUtils.setbitu(data, 22 + i * 4, 4, 1);
        }
        System.arraycopy(data, 0, msg.msg, 0, 29);
        msg.msg[28] &= (byte) 0xC0;
        return msg;
    }

    private static String buildType1Hex() {
        byte[] data = new byte[29];
        BitUtils.setbitu(data, 8, 6, 1);
        BitUtils.setbitu(data, 14, 2, 0);
        for (int i = 1; i <= 37; i++) {
            BitUtils.setbitu(data, 13 + i, 1, 1);
        }
        BitUtils.setbitu(data, 224, 2, 0);
        data[28] &= (byte) 0xC0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 29; i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String buildType7Hex() {
        byte[] data = new byte[29];
        BitUtils.setbitu(data, 8, 6, 7);
        BitUtils.setbitu(data, 14, 4, 2);
        BitUtils.setbitu(data, 18, 2, 0);
        for (int i = 0; i < 51; i++) {
            BitUtils.setbitu(data, 22 + i * 4, 4, 1);
        }
        data[28] &= (byte) 0xC0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 29; i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}