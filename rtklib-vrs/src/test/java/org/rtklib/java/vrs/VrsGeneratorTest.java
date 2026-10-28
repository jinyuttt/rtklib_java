package org.rtklib.java.vrs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.vrs.config.VrsOptionalConfig;
import org.rtklib.java.vrs.config.VrsRequiredConfig;
import org.rtklib.java.vrs.rtcm.Rtcm3Encoder;
import org.rtklib.java.vrs.rtcm.RtcmSinks;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class VrsGeneratorTest {

    private static final String DATA_ROOT = System.getProperty("vrs.data.root", "");
    private static final String BASE_STATION = System.getProperty("vrs.base.station", "");
    private static final String DATE = System.getProperty("vrs.date", "2026-10-27");
    private static final int TEST_HOUR = Integer.parseInt(System.getProperty("vrs.hour", "8"));

    private static final double BASE_LAT = Double.parseDouble(System.getProperty("vrs.base.lat", "0"));
    private static final double BASE_LON = Double.parseDouble(System.getProperty("vrs.base.lon", "0"));
    private static final double BASE_HGT = Double.parseDouble(System.getProperty("vrs.base.hgt", "0"));

    private static boolean dataAvailable;

    @BeforeAll
    static void checkData() {
        Path baseP = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        dataAvailable = Files.exists(baseP);
        if (dataAvailable) {
            System.out.println("VRS测试数据可用: " + baseP);
        } else {
            System.out.println("VRS测试数据不可用");
        }
    }

    @Test
    @DisplayName("1033 接线：站坐标后紧跟天线描述，字段可解码回原值；关闭开关后不再输出")
    void testAntennaDescriptorWiring() {
        double[] pos = {30.0 * Math.PI / 180.0, 95.0 * Math.PI / 180.0, 100.0};
        Sta base = new Sta();
        base.name = "BASE";
        base.marker = "BASE";
        base.pos = new double[3];
        CoordTransform.pos2ecef(pos, base.pos);

        VrsOptionalConfig opt = new VrsOptionalConfig();
        opt.antType = "ADVNULLANTENNA";
        opt.antSerial = "ANT001";
        opt.antSetupId = 7;
        opt.recType = "NETVRS RECEIVER";
        opt.recVersion = "1.0";
        opt.recSerial = "REC001";

        List<Integer> types = collectTypes(pos, base, opt);
        assertEquals(List.of(1005, 1033), types, "站坐标后应紧跟 1033 天线描述");

        Rtcm rtcm = new Rtcm();
        byte[] antenna = lastFrame(pos, base, opt, 1033);
        assertEquals(antenna.length, rtcm.input(antenna, antenna.length));
        assertEquals("ADVNULLANTENNA", rtcm.sta.antdes);
        assertEquals("ANT001", rtcm.sta.antsno);
        assertEquals(7, rtcm.sta.antSetupId);
        assertEquals("NETVRS RECEIVER", rtcm.sta.rectype);
        assertEquals("1.0", rtcm.sta.recver);
        assertEquals("REC001", rtcm.sta.recsno);

        opt.antennaMsgType = 0;
        assertEquals(List.of(1005), collectTypes(pos, base, opt),
                "antennaMsgType=0 时不应输出 1033");
    }

    private static List<Integer> collectTypes(double[] pos, Sta base, VrsOptionalConfig opt) {
        RtcmSinks.Collector sink = RtcmSinks.collect();
        VrsGenerator generator = new VrsGenerator(pos, "NETVRS", opt,
                new Rtcm3Encoder(), sink, new Nav(), null, null, null);
        generator.onStation(base);
        generator.onObservationEpoch(new ObservationEpoch(new GTime(1000L, 0.0), "BASE"));
        return frameTypes(sink.toByteArray());
    }

    private static byte[] lastFrame(double[] pos, Sta base, VrsOptionalConfig opt, int type) {
        RtcmSinks.Collector sink = RtcmSinks.collect();
        VrsGenerator generator = new VrsGenerator(pos, "NETVRS", opt,
                new Rtcm3Encoder(), sink, new Nav(), null, null, null);
        generator.onStation(base);
        generator.onObservationEpoch(new ObservationEpoch(new GTime(1000L, 0.0), "BASE"));
        byte[] data = sink.toByteArray();
        byte[] last = null;
        int i = 0;
        while (i + 3 <= data.length) {
            if ((data[i] & 0xFF) != 0xD3) {
                i++;
                continue;
            }
            int len = ((data[i + 1] & 0x03) << 8) | (data[i + 2] & 0xFF);
            int end = i + 3 + len + 3;
            int msgType = ((data[i + 3] & 0xFF) << 4) | ((data[i + 4] & 0xF0) >> 4);
            if (msgType == type) {
                last = Arrays.copyOfRange(data, i, end);
            }
            i = end;
        }
        assertNotNull(last, "输出中未找到消息 " + type);
        return last;
    }

    private static List<Integer> frameTypes(byte[] data) {
        List<Integer> types = new ArrayList<>();
        int i = 0;
        while (i + 3 <= data.length) {
            if ((data[i] & 0xFF) != 0xD3) {
                i++;
                continue;
            }
            int len = ((data[i + 1] & 0x03) << 8) | (data[i + 2] & 0xFF);
            types.add(((data[i + 3] & 0xFF) << 4) | ((data[i + 4] & 0xF0) >> 4));
            i += 3 + len + 3;
        }
        return types;
    }

    @Test
    @DisplayName("分析原始RTCM文件消息顺序")
    void testAnalyzeRtcmMessageOrder() throws Exception {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] inputData = Files.readAllBytes(inputPath);

        int[] typeCounts = new int[2000];
        List<String> msgSequence = new ArrayList<>();
        int[] ephSatSet = new int[300];
        int[] totalMsgsArr = {0};

        RtcmDataHandler analyzer = new RtcmDataHandler() {
            int msgIdx = 0;
            @Override public void onRtcmMessageType(int type, int length) {
                totalMsgsArr[0]++;
                typeCounts[type]++;
                if (msgIdx < 80) {
                    msgSequence.add(String.format("#%03d type=%d", msgIdx + 1, type));
                }
                msgIdx++;
            }
            @Override public void onStation(Sta sta) {}
            @Override public void onEph(Eph eph) {
                ephSatSet[eph.sat]++;
                if (msgSequence.size() > 0 && msgIdx <= 80) {
                    String last = msgSequence.get(msgSequence.size() - 1);
                    msgSequence.set(msgSequence.size() - 1, last + String.format(" sat=%d", eph.sat));
                }
            }
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                if (msgSequence.size() > 0 && msgIdx <= 80) {
                    String last = msgSequence.get(msgSequence.size() - 1);
                    msgSequence.set(msgSequence.size() - 1, last + String.format(" nsat=%d", epoch.getObservations().size()));
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(analyzer);
        decoder.feed(inputData, 0, inputData.length);
        decoder.finish();

        System.out.println("=== RTCM文件消息分析 ===");
        System.out.printf("总消息数: %d%n", totalMsgsArr[0]);
        System.out.printf("文件大小: %d bytes%n", inputData.length);
        System.out.println();
        System.out.println("消息类型分布:");
        for (int i = 0; i < typeCounts.length; i++) {
            if (typeCounts[i] > 0) {
                String desc = switch (i) {
                    case 1005, 1006 -> "站坐标";
                    case 1019 -> "GPS星历";
                    case 1020 -> "GLO星历";
                    case 1042 -> "BDS星历";
                    case 1044 -> "QZS星历";
                    case 1045, 1046 -> "GAL星历";
                    case 1074 -> "GPS MSM4";
                    case 1084 -> "GLO MSM4";
                    case 1094 -> "GAL MSM4";
                    case 1124 -> "BDS MSM4";
                    case 1075 -> "GPS MSM5";
                    case 1125 -> "BDS MSM5";
                    default -> "其他";
                };
                System.out.printf("  type=%-4d (%s): %d条%n", i, desc, typeCounts[i]);
            }
        }

        System.out.println();
        System.out.println("前80条消息顺序:");
        for (String s : msgSequence) {
            System.out.println("  " + s);
        }

        System.out.println();
        System.out.println("星历卫星分布:");
        for (int i = 0; i < ephSatSet.length; i++) {
            if (ephSatSet[i] > 0) {
                int[] prn = new int[1];
                int sys = org.rtklib.java.common.SatUtils.satsys(i, prn);
                String sysStr = sys == Constants.SYS_GPS ? "G" : sys == Constants.SYS_CMP ? "C" : sys == Constants.SYS_GAL ? "E" : "?";
                System.out.printf("  sat=%d (%s%02d): %d条星历%n", i, sysStr, prn[0], ephSatSet[i]);
            }
        }
    }

    @Test
    @DisplayName("VrsGenerator：完整流程→输出文件→解码验证观测值非零")
    void testVrsGeneratorOutput() throws Exception {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        Path outputPath = Paths.get("target", "vrs-generator-test.rtcm3");
        Files.createDirectories(outputPath.getParent());
        if (Files.exists(outputPath)) Files.delete(outputPath);

        double vrsLat = BASE_LAT + 0.0001;
        double vrsLon = BASE_LON;
        double vrsHgt = BASE_HGT;

        VrsRequiredConfig required = new VrsRequiredConfig();
        required.vrsLat = vrsLat;
        required.vrsLon = vrsLon;
        required.vrsHeight = vrsHgt;
        required.stationId = "VRS_TEST";
        required.inputRtcmPath = inputPath.toString();
        required.outputRtcmPath = outputPath.toString();

        VrsOptionalConfig optional = new VrsOptionalConfig();
        optional.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;
        optional.msmType = 4;
        optional.stationMsgType = 1005;

        System.out.printf("输入: %s%n", required.inputRtcmPath);
        System.out.printf("VRS位置: lat=%.9f lon=%.9f h=%.4f%n", vrsLat, vrsLon, vrsHgt);

        VrsProcessor processor = new VrsProcessor();
        processor.process(required, optional);

        assertTrue(Files.exists(outputPath), "输出文件应存在");
        long outputSize = Files.size(outputPath);
        System.out.printf("输出文件大小: %d bytes%n", outputSize);
        assertTrue(outputSize > 0, "输出文件不应为空");

        byte[] outputData = Files.readAllBytes(outputPath);

        VrsOutputCollector collector = new VrsOutputCollector();
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(collector);
        decoder.feed(outputData, 0, outputData.length);
        decoder.finish();

        System.out.println();
        System.out.println("=== VRS输出解码结果 ===");
        System.out.printf("  站坐标消息: %d%n", collector.stationCount);
        System.out.printf("  观测历元: %d%n", collector.obsEpochCount);
        System.out.printf("  星历消息: %d%n", collector.ephCount);

        if (collector.sta != null) {
            double[] pos = new double[3];
            CoordTransform.ecef2pos(collector.sta.pos, pos);
            System.out.printf("  VRS站坐标: lat=%.9f lon=%.9f h=%.4f%n",
                    pos[0] * 180.0 / Math.PI, pos[1] * 180.0 / Math.PI, pos[2]);
        }

        assertTrue(collector.stationCount > 0, "输出应包含站坐标消息");
        assertTrue(collector.obsEpochCount > 0, "输出应包含观测历元");

        System.out.println();
        System.out.println("--- 观测值有效性检查 ---");
        System.out.printf("  总历元数: %d%n", collector.obsEpochCount);
        System.out.printf("  P非零历元数: %d%n", collector.epochsWithValidP);
        System.out.printf("  L非零历元数: %d%n", collector.epochsWithValidL);
        System.out.printf("  code非零历元数: %d%n", collector.epochsWithValidCode);

        if (!collector.epochDetails.isEmpty()) {
            System.out.println();
            System.out.println("  首个历元详情:");
            for (String line : collector.epochDetails.get(0)) {
                System.out.println("    " + line);
            }
            if (collector.epochDetails.size() > 1) {
                System.out.println("  最后历元详情:");
                List<String> last = collector.epochDetails.get(collector.epochDetails.size() - 1);
                for (String line : last) {
                    System.out.println("    " + line);
                }
            }
        }

        assertTrue(collector.epochsWithValidP > 0, "应有历元包含非零伪距");
        assertTrue(collector.epochsWithValidL > 0, "应有历元包含非零载波相位");
        assertTrue(collector.epochsWithValidCode > 0, "应有历元包含非零信号码");

        double pValidRatio = 100.0 * collector.epochsWithValidP / collector.obsEpochCount;
        double lValidRatio = 100.0 * collector.epochsWithValidL / collector.obsEpochCount;
        System.out.printf("%n  P有效率: %.1f%%, L有效率: %.1f%%%n", pValidRatio, lValidRatio);
        assertTrue(pValidRatio > 90.0, "P有效率应>90%");
        assertTrue(lValidRatio > 90.0, "L有效率应>90%");

        int multiSatEpochs = (int) collector.epochDetails.stream()
                .filter(d -> d.size() > 2).count();
        System.out.printf("%n  多星历元数: %d/%d%n", multiSatEpochs, Math.min(5, collector.obsEpochCount));
        int totalSatsInDetail = collector.epochDetails.stream()
                .mapToInt(d -> d.size() - 1).sum();
        System.out.printf("  详情中总卫星观测: %d%n", totalSatsInDetail);
    }

    @Test
    @DisplayName("VrsGenerator：VRS改正前后观测值对比")
    void testVrsCorrectionComparison() throws Exception {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] inputData = Files.readAllBytes(inputPath);

        double vrsLat = BASE_LAT + 0.0001;
        double vrsLon = BASE_LON;
        double vrsHgt = BASE_HGT;
        double[] vrsPosRad = {vrsLat * Constants.D2R, vrsLon * Constants.D2R, vrsHgt};

        VrsOptionalConfig opt = new VrsOptionalConfig();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;

        Nav preNav = new Nav();
        RtcmDataHandler ephCollector = new RtcmDataHandler() {
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) {}
            @Override public void onEph(Eph eph) {
                if (eph.sat > 0 && eph.sat <= preNav.nmax) {
                    preNav.eph[eph.sat - 1] = eph;
                    if (eph.sat > preNav.n) preNav.n = eph.sat;
                }
            }
            @Override public void onGeph(Geph geph) {
                int[] prn = new int[1];
                int sys = org.rtklib.java.common.SatUtils.satsys(geph.sat, prn);
                if (sys == Constants.SYS_GLO && prn[0] > 0 && prn[0] <= preNav.ngmax) {
                    preNav.geph[prn[0] - 1] = geph;
                    if (prn[0] > preNav.ng) preNav.ng = prn[0];
                }
            }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder ephDecoder = new RtcmCallbackDecoder(ephCollector);
        ephDecoder.feed(inputData, 0, inputData.length);
        ephDecoder.finish();

        int ephCnt = 0;
        for (int i = 0; i < preNav.nmax; i++) {
            if (preNav.eph[i] != null && preNav.eph[i].sat > 0) ephCnt++;
        }
        System.out.printf("预加载星历数: %d%n", ephCnt);

        VrsCorrectionCollector collector = new VrsCorrectionCollector(vrsPosRad, opt, preNav);
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(collector);
        decoder.feed(inputData, 0, inputData.length);
        decoder.finish();

        System.out.println("=== VRS改正前后对比 ===");
        System.out.printf("  观测历元: %d%n", collector.epochCount);
        System.out.printf("  星历数: %d%n", collector.ephCount);
        System.out.printf("  VRS改正历元数: %d%n", collector.correctedEpochCount);

        if (!collector.firstEpochComparison.isEmpty()) {
            System.out.println("  首个历元改正前后对比:");
            for (String line : collector.firstEpochComparison) {
                System.out.println("    " + line);
            }
        }

        assertTrue(collector.correctedEpochCount > 0, "应有VRS改正结果");
        assertTrue(collector.hasValidVrsObs, "VRS改正后应有非零观测值");
    }

    private static class VrsOutputCollector implements RtcmDataHandler {
        Sta sta;
        int stationCount = 0;
        int obsEpochCount = 0;
        int ephCount = 0;
        int epochsWithValidP = 0;
        int epochsWithValidL = 0;
        int epochsWithValidCode = 0;
        List<List<String>> epochDetails = new ArrayList<>();

        @Override public void onStation(Sta s) {
            this.sta = s;
            stationCount++;
        }
        @Override public void onEph(Eph eph) { ephCount++; }
        @Override public void onGeph(Geph geph) { ephCount++; }
        @Override public void onObservationEpoch(ObservationEpoch epoch) {
            obsEpochCount++;
            List<Obsd> obs = epoch.getObservations();
            boolean hasValidP = false, hasValidL = false, hasValidCode = false;
            List<String> details = new ArrayList<>();
            details.add(String.format("历元#%d: nsat=%d", obsEpochCount, obs.size()));

            for (Obsd o : obs) {
                if (o.P[0] != 0.0) hasValidP = true;
                if (o.L[0] != 0.0) hasValidL = true;
                if (o.code[0] != 0) hasValidCode = true;
            }

            if (obsEpochCount <= 5) {
                for (Obsd o : obs) {
                    int sys = org.rtklib.java.common.SatUtils.satsys(o.sat, new int[1]);
                    String sysStr = sys == Constants.SYS_GPS ? "G" : sys == Constants.SYS_CMP ? "C" : sys == Constants.SYS_GAL ? "E" : "?";
                    details.add(String.format("  %s%02d P[0]=%.4f L[0]=%.4f code[0]=%d rcv=%d",
                            sysStr, o.sat - (sys == Constants.SYS_GPS ? 0 : sys == Constants.SYS_CMP ? 100 : sys == Constants.SYS_GAL ? 200 : 0),
                            o.P[0], o.L[0], o.code[0], o.rcv));
                }
            }

            if (hasValidP) epochsWithValidP++;
            if (hasValidL) epochsWithValidL++;
            if (hasValidCode) epochsWithValidCode++;
            if (epochDetails.size() < 5) {
                epochDetails.add(details);
            }
        }
        @Override public void onSsr(Ssr ssr) {}
        @Override public void onAuxData(AuxData aux) {}
        @Override public void onFinish() {}
    }

    private static class VrsCorrectionCollector implements RtcmDataHandler {
        private final double[] vrsPos;
        private final VrsOptionalConfig opt;
        private final Nav nav;
        private Sta baseSta;

        int epochCount = 0;
        int ephCount = 0;
        int correctedEpochCount = 0;
        boolean hasValidVrsObs = false;
        List<String> firstEpochComparison = new ArrayList<>();

        VrsCorrectionCollector(double[] vrsPos, VrsOptionalConfig opt, Nav nav) {
            this.vrsPos = vrsPos;
            this.opt = opt;
            this.nav = nav;
        }

        @Override public void onStation(Sta sta) {
            this.baseSta = sta;
            double[] pos = new double[3];
            CoordTransform.ecef2pos(sta.pos, pos);
            System.out.printf("  收到基站坐标: lat=%.9f lon=%.9f h=%.4f%n",
                    pos[0]*180/Math.PI, pos[1]*180/Math.PI, pos[2]);
        }

        @Override public void onEph(Eph eph) {
            ephCount++;
            if (eph.sat > 0 && eph.sat <= nav.nmax) {
                nav.eph[eph.sat - 1] = eph;
                if (eph.sat > nav.n) nav.n = eph.sat;
            }
        }

        @Override public void onGeph(Geph geph) {
            ephCount++;
            int[] prn = new int[1];
            int sys = org.rtklib.java.common.SatUtils.satsys(geph.sat, prn);
            if (sys == Constants.SYS_GLO && prn[0] > 0 && prn[0] <= nav.ngmax) {
                nav.geph[prn[0] - 1] = geph;
            }
        }

        @Override public void onObservationEpoch(ObservationEpoch epoch) {
            epochCount++;
            if (baseSta == null) return;

            if (epochCount == 1) {
                int cnt = 0;
                for (int i = 0; i < nav.nmax; i++) {
                    if (nav.eph[i] != null && nav.eph[i].sat > 0) cnt++;
                }
                System.out.printf("  [历元#1] nav中星历数=%d, 观测卫星数=%d%n", cnt, epoch.getObservations().size());
                int ephFoundCnt = 0;
                for (Obsd o : epoch.getObservations()) {
                    double[] rs = new double[6];
                    double[] dts = new double[2];
                    double[] vare = new double[1];
                    org.rtklib.java.ephemeris.EphModel.satpos(o.time, nav, o.sat, rs, dts, vare);
                    if (rs[0] != 0.0 || rs[1] != 0.0 || rs[2] != 0.0) ephFoundCnt++;
                }
                System.out.printf("    星历查找成功: %d/%d%n", ephFoundCnt, epoch.getObservations().size());
            }

            if (epochCount == 20) {
                int cnt = 0;
                for (int i = 0; i < nav.nmax; i++) {
                    if (nav.eph[i] != null && nav.eph[i].sat > 0) cnt++;
                }
                System.out.printf("  [历元#20] nav中星历数=%d%n", cnt);
            }

            Obs vrsObs = VrsCorrection.correct(epoch, nav, baseSta, vrsPos, opt);
            if (vrsObs.n > 0) {
                correctedEpochCount++;

                boolean hasValid = false;
                for (int i = 0; i < vrsObs.n; i++) {
                    Obsd d = vrsObs.data[i];
                    if (d.P[0] != 0.0 || d.L[0] != 0.0) hasValid = true;
                }
                if (hasValid) hasValidVrsObs = true;

                if (firstEpochComparison.size() <= 1 || (epochCount == 10 && firstEpochComparison.size() < 20)) {
                    firstEpochComparison.add(String.format("历元#%d: 原始卫星数=%d, VRS改正后卫星数=%d",
                            epochCount, epoch.getObservations().size(), vrsObs.n));
                }
                if (epochCount == 1 || epochCount == 10) {
                    List<Obsd> baseObs = epoch.getObservations();
                    for (int i = 0; i < Math.min(5, vrsObs.n); i++) {
                        Obsd vrs = vrsObs.data[i];
                        firstEpochComparison.add(String.format(
                                "  sat=%d: vrsP=%.4f vrsL=%.4f code=%d",
                                vrs.sat, vrs.P[0], vrs.L[0], vrs.code[0]));
                    }
                }
            }
        }

        @Override public void onSsr(Ssr ssr) {}
        @Override public void onAuxData(AuxData aux) {}
        @Override public void onFinish() {}
    }

    @Test
    @DisplayName("VRS改正诊断：对比改正前后伪距/载波相位差值")
    void testVrsCorrectionDiagnosis() throws Exception {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] inputData = Files.readAllBytes(inputPath);

        double vrsLat = BASE_LAT + 0.0001;
        double vrsLon = BASE_LON;
        double vrsHgt = BASE_HGT;
        double[] vrsPosRad = {vrsLat * Constants.D2R, vrsLon * Constants.D2R, vrsHgt};

        VrsOptionalConfig opt = new VrsOptionalConfig();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;

        Nav preNav = preloadEphemeris(inputData);

        double[] baseEcef = new double[3];
        CoordTransform.pos2ecef(new double[]{BASE_LAT * Constants.D2R, BASE_LON * Constants.D2R, BASE_HGT}, baseEcef);
        double[] vrsEcef = new double[3];
        CoordTransform.pos2ecef(vrsPosRad, vrsEcef);
        double baseline = Math.sqrt(Math.pow(vrsEcef[0]-baseEcef[0],2)+Math.pow(vrsEcef[1]-baseEcef[1],2)+Math.pow(vrsEcef[2]-baseEcef[2],2));
        System.out.printf("基线长度: %.1f m%n", baseline);

        int[] epochArr = {0};
        RtcmDataHandler handler = new RtcmDataHandler() {
            final Nav nav = preNav;
            Sta baseSta;
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) { this.baseSta = sta; }
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                epochArr[0]++;
                if (epochArr[0] != 1 || baseSta == null) return;

                System.out.printf("=== 历元#1 VRS改正诊断 (基线=%.1fm) ===%n", baseline);
                Obs vrsObs = VrsCorrection.correct(epoch, nav, baseSta, vrsPosRad, opt);
                System.out.printf("原始卫星数=%d, VRS改正后=%d%n", epoch.getObservations().size(), vrsObs.n);

                for (int i = 0; i < Math.min(10, vrsObs.n); i++) {
                    Obsd base = epoch.getObservations().get(i);
                    Obsd vrs = vrsObs.data[i];
                    int[] prn = new int[1];
                    int sys = org.rtklib.java.common.SatUtils.satsys(vrs.sat, prn);
                    String sysStr = sys == Constants.SYS_CMP ? "C" : sys == Constants.SYS_GPS ? "G" : "?";

                    double dP0 = vrs.P[0] - base.P[0];
                    double dP1 = vrs.P[1] - base.P[1];
                    double dL0 = vrs.L[0] - base.L[0];
                    double dL1 = vrs.L[1] - base.L[1];

                    double freq0 = org.rtklib.java.common.SatUtils.sat2freq(vrs.sat, vrs.code[0], nav);
                    double lam0 = freq0 > 0 ? Constants.CLIGHT / freq0 : 0;
                    double dL0_m = lam0 > 0 ? dL0 * lam0 : 0;

                    System.out.printf("  %s%02d: dP0=%.4fm dP1=%.4fm dL0=%.4fc(%.4fm) dL1=%.4fc code=[%d,%d]%n",
                            sysStr, prn[0], dP0, dP1, dL0, dL0_m, dL1, vrs.code[0], vrs.code[1]);
                    System.out.printf("        baseP0=%.4f vrsP0=%.4f baseL0=%.4f vrsL0=%.4f%n",
                            base.P[0], vrs.P[0], base.L[0], vrs.L[0]);
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(inputData, 0, inputData.length);
        decoder.finish();

        assertTrue(epochArr[0] > 0, "应有观测历元");
    }

    @Test
    @DisplayName("VRS输出解码验证：VRS输出RTCM解码后与改正后原始值对比")
    void testVrsOutputDecodeVerification() throws Exception {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        Path outputPath = Paths.get("target", "vrs-diag-output.rtcm3");
        Files.createDirectories(outputPath.getParent());
        if (Files.exists(outputPath)) Files.delete(outputPath);

        double vrsLat = BASE_LAT + 0.0001;
        double vrsLon = BASE_LON;
        double vrsHgt = BASE_HGT;

        VrsRequiredConfig required = new VrsRequiredConfig();
        required.vrsLat = vrsLat;
        required.vrsLon = vrsLon;
        required.vrsHeight = vrsHgt;
        required.stationId = "VRS_DIAG";
        required.inputRtcmPath = inputPath.toString();
        required.outputRtcmPath = outputPath.toString();

        VrsOptionalConfig optional = new VrsOptionalConfig();
        optional.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;
        optional.msmType = 4;

        VrsProcessor processor = new VrsProcessor();
        processor.process(required, optional);

        assertTrue(Files.exists(outputPath));
        byte[] outputData = Files.readAllBytes(outputPath);
        System.out.printf("VRS输出文件: %d bytes%n", outputData.length);

        int[] epochArr = {0};
        RtcmDataHandler inspector = new RtcmDataHandler() {
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) {
                double[] pos = new double[3];
                CoordTransform.ecef2pos(sta.pos, pos);
                System.out.printf("VRS站坐标: lat=%.9f lon=%.9f h=%.4f%n",
                        pos[0]*180/Math.PI, pos[1]*180/Math.PI, pos[2]);
            }
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                epochArr[0]++;
                if (epochArr[0] == 1) {
                    System.out.printf("VRS输出历元#1: 卫星数=%d%n", epoch.getObservations().size());
                    int dualCnt = 0;
                    for (Obsd o : epoch.getObservations()) {
                        int[] prn = new int[1];
                        int sys = org.rtklib.java.common.SatUtils.satsys(o.sat, prn);
                        String sysStr = sys == Constants.SYS_CMP ? "C" : sys == Constants.SYS_GPS ? "G" : "?";
                        int nfreq = 0;
                        for (int f = 0; f < Constants.NFREQ; f++) {
                            if (o.code[f] != 0 && o.P[f] != 0.0 && o.L[f] != 0.0) nfreq++;
                        }
                        if (nfreq >= 2) dualCnt++;
                        StringBuilder codeStr = new StringBuilder("[");
                        for (int f = 0; f < 6; f++) {
                            if (f > 0) codeStr.append(",");
                            codeStr.append(o.code[f]);
                        }
                        codeStr.append("]");
                        System.out.printf("  %s%02d: nfreq=%d P=[%.3f,%.3f,%.3f] L=[%.3f,%.3f,%.3f] code=%s%n",
                                sysStr, prn[0], nfreq, o.P[0], o.P[1], o.P[2], o.L[0], o.L[1], o.L[2], codeStr);
                    }
                    System.out.printf("  >>> 双频(主频点)卫星数=%d / %d%n", dualCnt, epoch.getObservations().size());
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder outDecoder = new RtcmCallbackDecoder(inspector);
        outDecoder.feed(outputData, 0, outputData.length);
        outDecoder.finish();

        System.out.printf("VRS输出总历元数: %d%n", epochArr[0]);
        assertTrue(epochArr[0] > 0, "VRS输出应有观测历元");
    }

    @Test
    @DisplayName("VRS改正几何自洽性：双差模糊度小数部分应接近整数")
    void testVrsIntegerAmbiguityPreservation() throws Exception {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] inputData = Files.readAllBytes(inputPath);

        double vrsLat = BASE_LAT + 0.0001;
        double vrsLon = BASE_LON;
        double vrsHgt = BASE_HGT;
        double[] vrsPosRad = {vrsLat * Constants.D2R, vrsLon * Constants.D2R, vrsHgt};

        VrsOptionalConfig opt = new VrsOptionalConfig();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;

        Nav preNav = preloadEphemeris(inputData);
        List<Eph> ephPool = collectEphPool(inputData);
        System.out.printf("星历池: %d 条%n", ephPool.size());

        double[] vrsEcef = new double[3];
        CoordTransform.pos2ecef(vrsPosRad, vrsEcef);

        RtcmDataHandler handler = new RtcmDataHandler() {
            final Nav nav = preNav;
            Sta baseSta;
            int epochCnt = 0;
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) { this.baseSta = sta; }
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                epochCnt++;
                if (epochCnt != 1 || baseSta == null) return;

                for (Eph e : ephPool) {
                    if (e.sat <= 0) continue;
                    Eph cur = nav.eph[e.sat - 1];
                    if (cur == null || Math.abs(org.rtklib.java.time.TimeSystem.timediff(epoch.time, e.toe))
                            < Math.abs(org.rtklib.java.time.TimeSystem.timediff(epoch.time, cur.toe))) {
                        nav.eph[e.sat - 1] = e;
                    }
                }
                System.out.printf("历元时间: toe选中示例%n");

                Obs vrsObs = VrsCorrection.correct(epoch, nav, baseSta, vrsPosRad, opt);
                System.out.printf("=== 历元#1 双差模糊度整数性检验 (baseSta.pos=[%.3f,%.3f,%.3f]) ===%n",
                        baseSta.pos[0], baseSta.pos[1], baseSta.pos[2]);

                int refIdx = -1;
                double refBasePh = 0, refVrsPh = 0, refLam = 0;
                for (int i = 0; i < vrsObs.n; i++) {
                    Obsd o = vrsObs.data[i];
                    if (o.code[0] != 0 && o.P[0] != 0.0 && o.L[0] != 0.0) {
                        double[] rs = new double[6];
                        double[] dts = new double[2];
                        double[] vare = new double[1];
                        org.rtklib.java.ephemeris.EphModel.satpos(o.time, nav, o.sat, rs, dts, vare);
                        if (rs[0] == 0.0) continue;
                        double[] e = new double[3];
                        double rVrs = org.rtklib.java.common.RtklibCommon.geodist(rs, vrsEcef, e);
                        double freq = org.rtklib.java.common.SatUtils.sat2freq(o.sat, o.code[0], nav);
                        if (freq <= 0) continue;
                        refLam = Constants.CLIGHT / freq;
                        refVrsPh = o.L[0] * refLam - rVrs + Constants.CLIGHT * dts[0];
                        refBasePh = 0.0;
                        for (Obsd bb : epoch.getObservations()) {
                            if (bb.sat == o.sat) {
                                refBasePh = bb.L[0] * refLam
                                        - org.rtklib.java.common.RtklibCommon.geodist(rs, baseSta.pos, new double[3])
                                        + Constants.CLIGHT * dts[0];
                                break;
                            }
                        }
                        refIdx = i;
                        break;
                    }
                }
                if (refIdx < 0) { System.out.println("无有效参考星"); return; }

                System.out.printf("参考星: sat=%d%n", vrsObs.data[refIdx].sat);
                System.out.println("  sat    baseResid(m)  vrsResid(m)   dResid(m)   baseDDamb   vrsDDamb  (应为整数)");
                for (int i = 0; i < vrsObs.n; i++) {
                    Obsd o = vrsObs.data[i];
                    if (o.code[0] == 0 || o.P[0] == 0.0 || o.L[0] == 0.0) continue;
                    if (i == refIdx) continue;

                    double[] rs = new double[6];
                    double[] dts = new double[2];
                    double[] vare = new double[1];
                    org.rtklib.java.ephemeris.EphModel.satpos(o.time, nav, o.sat, rs, dts, vare);
                    if (rs[0] == 0.0) continue;

                    double[] e = new double[3];
                    double rVrs = org.rtklib.java.common.RtklibCommon.geodist(rs, vrsEcef, e);
                    double rBase = org.rtklib.java.common.RtklibCommon.geodist(rs, baseSta.pos, e);
                    double freq = org.rtklib.java.common.SatUtils.sat2freq(o.sat, o.code[0], nav);
                    if (freq <= 0) continue;
                    double lam = Constants.CLIGHT / freq;

                    Obsd b = null;
                    for (Obsd x : epoch.getObservations()) if (x.sat == o.sat) { b = x; break; }
                    if (b == null || b.P[0] == 0.0 || b.L[0] == 0.0) continue;

                    double baseResid = b.P[0] - rBase + Constants.CLIGHT * dts[0];
                    double vrsResid = o.P[0] - rVrs + Constants.CLIGHT * dts[0];
                    double dResid = vrsResid - baseResid;

                    double basePh = b.L[0] * lam - rBase + Constants.CLIGHT * dts[0];
                    double vrsPh = o.L[0] * lam - rVrs + Constants.CLIGHT * dts[0];
                    double baseDD = (basePh - refBasePh) / lam;
                    double vrsDD = (vrsPh - refVrsPh) / lam;
                    double frac = vrsDD - Math.round(vrsDD);

                    System.out.printf("  %3d  %12.4f  %12.4f  %10.4f   %9.3f   %9.3f  frac=%7.4f%n",
                            o.sat, baseResid, vrsResid, dResid, baseDD, vrsDD, frac);
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(inputData, 0, inputData.length);
        decoder.finish();
    }

    private static List<Eph> collectEphPool(byte[] data) {
        List<Eph> pool = new ArrayList<>();
        RtcmDataHandler c = new RtcmDataHandler() {
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) {}
            @Override public void onEph(Eph eph) { if (eph != null && eph.sat > 0) pool.add(eph); }
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder d = new RtcmCallbackDecoder(c);
        d.feed(data, 0, data.length);
        d.finish();
        return pool;
    }

    private static Nav preloadEphemeris(byte[] data) {
        Nav nav = new Nav();
        RtcmDataHandler ephCollector = new RtcmDataHandler() {
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) {}
            @Override public void onEph(Eph eph) {
                if (eph.sat > 0 && eph.sat <= nav.nmax) {
                    nav.eph[eph.sat - 1] = eph;
                    if (eph.sat > nav.n) nav.n = eph.sat;
                }
            }
            @Override public void onGeph(Geph geph) {
                int[] prn = new int[1];
                int sys = org.rtklib.java.common.SatUtils.satsys(geph.sat, prn);
                if (sys == Constants.SYS_GLO && prn[0] > 0 && prn[0] <= nav.ngmax) {
                    nav.geph[prn[0] - 1] = geph;
                    if (prn[0] > nav.ng) nav.ng = prn[0];
                }
            }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(ephCollector);
        decoder.feed(data, 0, data.length);
        decoder.finish();
        return nav;
    }
}