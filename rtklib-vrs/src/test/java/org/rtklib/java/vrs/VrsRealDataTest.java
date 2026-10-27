package org.rtklib.java.vrs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.vrs.config.VrsOptionalConfig;
import org.rtklib.java.vrs.config.VrsRequiredConfig;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.CoordType;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class VrsRealDataTest {

    private static final String DATA_ROOT = "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage";
    private static final String BASE_STATION = "GS2025090017";
    private static final String DATE = System.getProperty("vrs.date", "2026-10-27");
    private static final int TEST_HOUR = 8;

    private static final double BASE_LAT = 28.910469588;
    private static final double BASE_LON = 101.176917113;
    private static final double BASE_HGT = 2165.3744;

    private static final String ROVER_STATION = "GS2025090003";
    private static final double ROVER_LAT = 28.909490107;
    private static final double ROVER_LON = 101.177305182;
    private static final double ROVER_HGT = 2156.2988;

    private static boolean dataAvailable;

    @BeforeAll
    static void checkData() {
        Path baseP = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        Path roverP = Paths.get(DATA_ROOT, ROVER_STATION, DATE, TEST_HOUR + ".rtcm3");
        dataAvailable = Files.exists(baseP) && Files.exists(roverP);
        if (dataAvailable) {
            System.out.println("VRS 测试数据可用: base=" + baseP + ", rover=" + roverP);
        } else {
            System.out.println("VRS 测试数据不可用");
        }
    }

    @Test
    @DisplayName("VRS改正：读取基站RTCM，生成VRS改正后观测值")
    void testVrsCorrectionWithRealData() throws IOException {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] data = Files.readAllBytes(inputPath);
        System.out.printf("输入文件: %s (%d bytes)%n", inputPath, data.length);

        double vrsLat = BASE_LAT + 0.05;
        double vrsLon = BASE_LON;
        double vrsHgt = BASE_HGT;

        System.out.printf("基站位置: lat=%.9f lon=%.9f h=%.4f%n", BASE_LAT, BASE_LON, BASE_HGT);
        System.out.printf("VRS位置:  lat=%.9f lon=%.9f h=%.4f%n", vrsLat, vrsLon, vrsHgt);

        double[] vrsPosRad = new double[]{vrsLat * Constants.D2R, vrsLon * Constants.D2R, vrsHgt};
        double[] vrsEcef = new double[3];
        CoordTransform.pos2ecef(vrsPosRad, vrsEcef);
        double[] baseEcef = new double[3];
        CoordTransform.pos2ecef(new double[]{BASE_LAT * Constants.D2R, BASE_LON * Constants.D2R, BASE_HGT}, baseEcef);
        double dist = Math.sqrt(Math.pow(vrsEcef[0]-baseEcef[0],2) + Math.pow(vrsEcef[1]-baseEcef[1],2) + Math.pow(vrsEcef[2]-baseEcef[2],2));
        System.out.printf("基站-VRS距离: %.1f 米%n", dist);

        VrsOptionalConfig opt = new VrsOptionalConfig();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;

        Nav preNav = preloadEphemeris(data);
        int ephCnt = countEphemeris(preNav);
        System.out.printf("预加载星历数: %d%n", ephCnt);

        VrsTestCollector collector = new VrsTestCollector(vrsPosRad, opt, preNav);
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(collector);
        decoder.feed(data, 0, data.length);
        decoder.finish();

        System.out.println();
        System.out.println("=== VRS 改正结果 ===");
        System.out.printf("  观测历元数: %d%n", collector.epochCount);
        System.out.printf("  星历数: %d (GPS/GAL/BDS), %d (GLO)%n", collector.ephCount, collector.gephCount);
        System.out.printf("  VRS改正历元数: %d%n", collector.correctedEpochCount);
        System.out.printf("  VRS改正卫星数(首个历元): %d%n", collector.firstEpochSatCount);
        System.out.printf("  VRS改正卫星数(最后历元): %d%n", collector.lastEpochSatCount);

        if (collector.firstEpochDetails != null) {
            System.out.println();
            System.out.println("  --- 首个历元改正详情 ---");
            for (String line : collector.firstEpochDetails) {
                System.out.println("  " + line);
            }
        }

        assertTrue(collector.epochCount > 0, "应有观测历元");
        assertTrue(collector.ephCount > 0, "应有星历数据");
        assertTrue(collector.correctedEpochCount > 0, "VRS改正应产生结果");
    }

    @Test
    @DisplayName("VRS完整流程：RTCM输入→VRS改正→RTCM编码输出")
    void testVrsFullPipeline() throws IOException {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path inputPath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        Path outputPath = Paths.get("target", "vrs-output-test.rtcm3");
        Files.createDirectories(outputPath.getParent());
        if (Files.exists(outputPath)) Files.delete(outputPath);

        VrsRequiredConfig required = new VrsRequiredConfig();
        required.vrsLat = BASE_LAT + 0.05;
        required.vrsLon = BASE_LON;
        required.vrsHeight = BASE_HGT;
        required.stationId = "VRS001";
        required.inputRtcmPath = inputPath.toString();
        required.outputRtcmPath = outputPath.toString();

        VrsOptionalConfig optional = new VrsOptionalConfig();
        optional.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;
        optional.msmType = 4;
        optional.stationMsgType = 1005;

        System.out.printf("输入: %s%n", required.inputRtcmPath);
        System.out.printf("输出: %s%n", required.outputRtcmPath);
        System.out.printf("VRS位置: lat=%.9f lon=%.9f h=%.4f%n",
                required.vrsLat, required.vrsLon, required.vrsHeight);

        VrsProcessor processor = new VrsProcessor();
        processor.process(required, optional);

        assertTrue(Files.exists(outputPath), "输出文件应存在");
        long outputSize = Files.size(outputPath);
        System.out.printf("输出文件大小: %d bytes%n", outputSize);
        assertTrue(outputSize > 0, "输出文件不应为空");

        System.out.println();
        System.out.println("=== 验证：解码输出RTCM文件 ===");
        VrsOutputInspector inspector = new VrsOutputInspector();
        byte[] outputData = Files.readAllBytes(outputPath);
        RtcmCallbackDecoder outDecoder = new RtcmCallbackDecoder(inspector);
        outDecoder.feed(outputData, 0, outputData.length);
        outDecoder.finish();

        System.out.printf("  站坐标消息: %d%n", inspector.stationCount);
        System.out.printf("  观测历元: %d%n", inspector.obsEpochCount);
        System.out.printf("  星历消息: %d%n", inspector.ephCount);

        if (inspector.sta != null) {
            double[] pos = new double[3];
            CoordTransform.ecef2pos(inspector.sta.pos, pos);
            System.out.printf("  VRS站坐标: lat=%.9f lon=%.9f h=%.4f%n",
                    pos[0] * 180.0 / Math.PI, pos[1] * 180.0 / Math.PI, pos[2]);
        }

        assertTrue(inspector.stationCount > 0, "输出应包含站坐标消息");
        assertTrue(inspector.obsEpochCount > 0, "输出应包含观测历元");
    }

    private static class VrsTestCollector implements RtcmDataHandler {
        private final double[] vrsPos;
        private final VrsOptionalConfig opt;
        private final Nav nav;
        private Sta baseSta;

        int epochCount = 0;
        int ephCount = 0;
        int gephCount = 0;
        int correctedEpochCount = 0;
        int firstEpochSatCount = 0;
        int lastEpochSatCount = 0;
        List<String> firstEpochDetails = null;

        VrsTestCollector(double[] vrsPos, VrsOptionalConfig opt, Nav nav) {
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
            }
        }

        @Override public void onGeph(Geph geph) {
            gephCount++;
            int[] prn = new int[1];
            int sys = org.rtklib.java.common.SatUtils.satsys(geph.sat, prn);
            if (sys == Constants.SYS_GLO && prn[0] > 0 && prn[0] <= nav.ngmax) {
                nav.geph[prn[0] - 1] = geph;
            }
        }

        @Override public void onObservationEpoch(ObservationEpoch epoch) {
            epochCount++;
            if (baseSta == null) return;

            Obs vrsObs = VrsCorrection.correct(epoch, nav, baseSta, vrsPos, opt);
            if (vrsObs.n > 0) {
                correctedEpochCount++;
                lastEpochSatCount = vrsObs.n;
                if (firstEpochDetails == null) {
                    firstEpochSatCount = vrsObs.n;
                    firstEpochDetails = new ArrayList<>();
                    firstEpochDetails.add(String.format("历元时间: %s", epoch.time));
                    firstEpochDetails.add(String.format("原始卫星数: %d, VRS改正后卫星数: %d",
                            epoch.getObservations().size(), vrsObs.n));
                    for (int i = 0; i < Math.min(5, vrsObs.n); i++) {
                        Obsd d = vrsObs.data[i];
                        firstEpochDetails.add(String.format("  sat=%d P[0]=%.4f L[0]=%.4f",
                                d.sat, d.P[0], d.L[0]));
                    }
                    if (vrsObs.n > 5) {
                        firstEpochDetails.add(String.format("  ... (共%d颗)", vrsObs.n));
                    }
                }
            }
        }

        @Override public void onSsr(Ssr ssr) {}
        @Override public void onAuxData(AuxData aux) {}
        @Override public void onFinish() {}
    }

    private static class VrsOutputInspector implements RtcmDataHandler {
        Sta sta;
        int stationCount = 0;
        int obsEpochCount = 0;
        int ephCount = 0;

        @Override public void onStation(Sta sta) {
            this.sta = sta;
            stationCount++;
        }
        @Override public void onEph(Eph eph) { ephCount++; }
        @Override public void onGeph(Geph geph) { ephCount++; }
        @Override public void onObservationEpoch(ObservationEpoch epoch) { obsEpochCount++; }
        @Override public void onSsr(Ssr ssr) {}
        @Override public void onAuxData(AuxData aux) {}
        @Override public void onFinish() {}
    }

    @Test
    @DisplayName("VRS反向验证：原始基站+测站RTK vs VRS+测站RTK")
    void testVrsReverseValidation() throws IOException {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path basePath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        Path roverPath = Paths.get(DATA_ROOT, ROVER_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] baseData = Files.readAllBytes(basePath);
        byte[] roverData = Files.readAllBytes(roverPath);

        double[] baseEcef = new double[3];
        CoordTransform.pos2ecef(new double[]{BASE_LAT * Constants.D2R, BASE_LON * Constants.D2R, BASE_HGT}, baseEcef);
        double[] roverEcef = new double[3];
        CoordTransform.pos2ecef(new double[]{ROVER_LAT * Constants.D2R, ROVER_LON * Constants.D2R, ROVER_HGT}, roverEcef);
        double baseRoverDist = Math.sqrt(
                Math.pow(roverEcef[0] - baseEcef[0], 2) +
                Math.pow(roverEcef[1] - baseEcef[1], 2) +
                Math.pow(roverEcef[2] - baseEcef[2], 2));

        System.out.println("========================================");
        System.out.println("  VRS 反向验证测试");
        System.out.printf("  基站: %s (%.9f, %.9f, %.4f)%n", BASE_STATION, BASE_LAT, BASE_LON, BASE_HGT);
        System.out.printf("  测站: %s (%.9f, %.9f, %.4f)%n", ROVER_STATION, ROVER_LAT, ROVER_LON, ROVER_HGT);
        System.out.printf("  基站-测站距离: %.1f 米%n", baseRoverDist);
        System.out.println("========================================");

        System.out.println();
        System.out.println("--- 1. 原始基站 + 测站 → RTK (长基线) ---");
        PrcOpt opt1 = createRtkOpt();
        RtkProcessor rtk1 = new RtkProcessor(opt1);
        RtkProcessor.RtkResult res1 = rtk1.process(roverData, baseData);
        RtkStats stats1 = analyzeRtkResult(res1, roverEcef);
        printRtkStats("原始基站-测站RTK", stats1, baseRoverDist);

        double vrsLat = ROVER_LAT + 0.0001;
        double vrsLon = ROVER_LON;
        double vrsHgt = ROVER_HGT;
        double[] vrsEcef = new double[3];
        CoordTransform.pos2ecef(new double[]{vrsLat * Constants.D2R, vrsLon * Constants.D2R, vrsHgt}, vrsEcef);
        double vrsRoverDist = Math.sqrt(
                Math.pow(roverEcef[0] - vrsEcef[0], 2) +
                Math.pow(roverEcef[1] - vrsEcef[1], 2) +
                Math.pow(roverEcef[2] - vrsEcef[2], 2));
        double vrsBaseDist = Math.sqrt(
                Math.pow(baseEcef[0] - vrsEcef[0], 2) +
                Math.pow(baseEcef[1] - vrsEcef[1], 2) +
                Math.pow(baseEcef[2] - vrsEcef[2], 2));

        System.out.println();
        System.out.println("--- 2. 生成VRS数据 (VRS设在测站附近) ---");
        System.out.printf("  VRS位置: lat=%.9f lon=%.9f h=%.4f%n", vrsLat, vrsLon, vrsHgt);
        System.out.printf("  VRS-测站距离: %.1f 米%n", vrsRoverDist);
        System.out.printf("  VRS-基站距离: %.1f 米%n", vrsBaseDist);

        Path vrsOutputPath = Paths.get("target", "vrs-reverse-validation.rtcm3");
        Files.createDirectories(vrsOutputPath.getParent());
        if (Files.exists(vrsOutputPath)) Files.delete(vrsOutputPath);

        VrsRequiredConfig vrsReq = new VrsRequiredConfig();
        vrsReq.vrsLat = vrsLat;
        vrsReq.vrsLon = vrsLon;
        vrsReq.vrsHeight = vrsHgt;
        vrsReq.stationId = "VRS_ROVER";
        vrsReq.inputRtcmPath = basePath.toString();
        vrsReq.outputRtcmPath = vrsOutputPath.toString();

        VrsOptionalConfig vrsOpt = new VrsOptionalConfig();
        vrsOpt.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;
        vrsOpt.msmType = 4;
        vrsOpt.stationMsgType = 1005;

        VrsProcessor vrsProc = new VrsProcessor();
        vrsProc.process(vrsReq, vrsOpt);

        assertTrue(Files.exists(vrsOutputPath), "VRS输出文件应存在");
        byte[] vrsData = Files.readAllBytes(vrsOutputPath);
        System.out.printf("  VRS输出文件: %d bytes%n", vrsData.length);

        System.out.println();
        System.out.println("--- 3. VRS + 测站 → RTK (短基线) ---");
        PrcOpt opt2 = createRtkOpt();
        RtkProcessor rtk2 = new RtkProcessor(opt2);
        RtkProcessor.RtkResult res2 = rtk2.process(roverData, vrsData);
        RtkStats stats2 = analyzeRtkResult(res2, roverEcef);
        printRtkStats("VRS-测站RTK", stats2, vrsRoverDist);

        System.out.println();
        System.out.println("--- 逐历元对比 (ns/ratio) ---");
        System.out.println("  idx   baseRun(ns,ratio,status)      vrsRun(ns,ratio,status)");
        int nCmp = Math.min(60, Math.min(res1.solutions.size(), res2.solutions.size()));
        for (int i = 15; i < nCmp; i++) {
            SolData s1 = res1.solutions.get(i);
            SolData s2 = res2.solutions.get(i);
            System.out.printf("  %3d   %2d / %7.3f / %-6s   %2d / %7.3f / %-6s%n",
                    i, s1.numSat, s1.ratio, s1.status, s2.numSat, s2.ratio, s2.status);
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("  对比总结");
        System.out.println("========================================");
        System.out.printf("  %-20s  基线=%.0fm  FIX率=%.1f%%  3D-RMS=%.4fm%n",
                "原始基站-测站", baseRoverDist, stats1.fixRatio, stats1.rms3d);
        System.out.printf("  %-20s  基线=%.0fm  FIX率=%.1f%%  3D-RMS=%.4fm%n",
                "VRS-测站", vrsRoverDist, stats2.fixRatio, stats2.rms3d);

        if (stats2.fixCount > 0) {
            System.out.println();
            System.out.println("  ✅ VRS短基线RTK获得FIX解，VRS改正有效");
        }
        if (stats2.rms3d < stats1.rms3d && stats2.fixCount > 0) {
            System.out.println("  ✅ VRS短基线精度优于原始长基线，VRS改正正确");
        }

        assertTrue(res2.totalEpochs > 0, "VRS RTK应有历元数据");
    }

    @Test
    @DisplayName("VRS编解码闭环：VRS设在基站位置时输出应与原始基站观测值一致")
    void testVrsRoundTripMatchesBase() throws IOException {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path basePath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] baseData = Files.readAllBytes(basePath);
        Map<String, List<Obsd>> baseEpochs = decodeEpochs(baseData);

        Path outPath = Paths.get("target", "vrs-roundtrip-base.rtcm3");
        Files.createDirectories(outPath.getParent());
        if (Files.exists(outPath)) Files.delete(outPath);

        VrsRequiredConfig req = new VrsRequiredConfig();
        req.vrsLat = BASE_LAT;
        req.vrsLon = BASE_LON;
        req.vrsHeight = BASE_HGT;
        req.stationId = "VRS_RT";
        req.inputRtcmPath = basePath.toString();
        req.outputRtcmPath = outPath.toString();

        VrsOptionalConfig opt = new VrsOptionalConfig();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;
        opt.msmType = 4;

        new VrsProcessor().process(req, opt);

        byte[] outData = Files.readAllBytes(outPath);
        Map<String, List<Obsd>> vrsEpochs = decodeEpochs(outData);

        System.out.printf("原始历元数=%d  VRS历元数=%d%n", baseEpochs.size(), vrsEpochs.size());
        List<String> baseKeys = new ArrayList<>(baseEpochs.keySet());
        List<String> vrsKeys = new ArrayList<>(vrsEpochs.keySet());
        System.out.println("原始历元时间(前3): " + baseKeys.subList(0, Math.min(3, baseKeys.size())));
        System.out.println("VRS历元时间(前3): " + vrsKeys.subList(0, Math.min(3, vrsKeys.size())));

        String key = baseKeys.get(0);
        List<Obsd> baseObs = baseEpochs.get(key);
        List<Obsd> vrsObs = vrsEpochs.get(key);
        if (vrsObs == null) {
            System.out.println("!! VRS中不存在该历元时间: " + key);
            return;
        }

        System.out.println();
        System.out.println("=== 全部历元载波相位跳变扫描 (VRS@基站位置，理论应全为0) ===");
        double maxDL = 0.0;
        int badEpochs = 0;
        String firstBad = null;
        for (String k : baseKeys) {
            List<Obsd> bl = baseEpochs.get(k);
            List<Obsd> vl = vrsEpochs.get(k);
            if (vl == null) continue;
            double epochMax = 0.0;
            for (Obsd b : bl) {
                int[] prn = new int[1];
                if (org.rtklib.java.common.SatUtils.satsys(b.sat, prn) != Constants.SYS_CMP) continue;
                Obsd v = null;
                for (Obsd x : vl) if (x.sat == b.sat) { v = x; break; }
                if (v == null) continue;
                if (b.L[0] != 0.0 && v.L[0] != 0.0) {
                    epochMax = Math.max(epochMax, Math.abs((v.L[0] - b.L[0]) * 0.19));
                }
            }
            if (epochMax > 0.001) {
                badEpochs++;
                if (firstBad == null) {
                    firstBad = String.format("%s maxDL0=%.4fm", k, epochMax);
                    System.out.println("  首个跳变历元: " + firstBad);
                }
            }
            maxDL = Math.max(maxDL, epochMax);
        }
        System.out.printf("  扫描历元=%d  含跳变历元=%d  max|dL0|=%.4fm%n",
                baseKeys.size(), badEpochs, maxDL);

        System.out.println();
        System.out.println("=== 历元 " + key + " 原始基站 vs VRS输出(基站位置) ===");
        System.out.println("  sat   dP0(m)      dL0(m)      dP1(m)      dL1(m)   baseLLI  vrsLLI");
        for (Obsd b : baseObs) {
            int[] prn = new int[1];
            if (org.rtklib.java.common.SatUtils.satsys(b.sat, prn) != Constants.SYS_CMP) continue;
            Obsd v = null;
            for (Obsd x : vrsObs) if (x.sat == b.sat) { v = x; break; }
            if (v == null) {
                System.out.printf("  %3d   --- 缺失 ---%n", b.sat);
                continue;
            }
            double dP0 = (b.P[0] != 0.0 && v.P[0] != 0.0) ? v.P[0] - b.P[0] : Double.NaN;
            double dL0 = (b.L[0] != 0.0 && v.L[0] != 0.0) ? (v.L[0] - b.L[0]) * 0.19 : Double.NaN;
            double dP1 = (b.P[1] != 0.0 && v.P[1] != 0.0) ? v.P[1] - b.P[1] : Double.NaN;
            double dL1 = (b.L[1] != 0.0 && v.L[1] != 0.0) ? (v.L[1] - b.L[1]) * 0.24 : Double.NaN;
            System.out.printf("  %3d  %9.4f  %9.4f  %9.4f  %9.4f    [%d,%d]   [%d,%d]%n",
                    b.sat, dP0, dL0, dP1, dL1, b.LLI[0], b.LLI[1], v.LLI[0], v.LLI[1]);
        }

        int baseSlip = 0, vrsSlip = 0;
        for (String k : baseKeys) {
            List<Obsd> bl = baseEpochs.get(k);
            List<Obsd> vl = vrsEpochs.get(k);
            if (vl == null) continue;
            for (Obsd b : bl) {
                int[] prn = new int[1];
                if (org.rtklib.java.common.SatUtils.satsys(b.sat, prn) != Constants.SYS_CMP) continue;
                if (b.L[0] != 0.0 && (b.LLI[0] & 1) != 0) baseSlip++;
                for (Obsd x : vl) {
                    if (x.sat != b.sat) continue;
                    if (x.L[0] != 0.0 && (x.LLI[0] & 1) != 0) vrsSlip++;
                    break;
                }
            }
        }
        System.out.printf("  全历元 LLI_SLIP 计数: 原始基站=%d  VRS输出=%d%n", baseSlip, vrsSlip);
    }

    @Test
    @DisplayName("VRS@基站位置：流级逐项对比 + RTK二分诊断（改正量=0，理论上流应等价）")
    void testVrsAtBaseStreamDiagnosis() throws IOException {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path basePath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        Path roverPath = Paths.get(DATA_ROOT, ROVER_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] baseData = Files.readAllBytes(basePath);
        byte[] roverData = Files.readAllBytes(roverPath);

        // --- A. 生成 VRS@基站位置（改正量≈0） ---
        Path outPath = Paths.get("target", "vrs-atbase-diag.rtcm3");
        Files.createDirectories(outPath.getParent());
        if (Files.exists(outPath)) Files.delete(outPath);

        VrsRequiredConfig req = new VrsRequiredConfig();
        req.vrsLat = BASE_LAT;
        req.vrsLon = BASE_LON;
        req.vrsHeight = BASE_HGT;
        req.stationId = "VRS_ATBASE";
        req.inputRtcmPath = basePath.toString();
        req.outputRtcmPath = outPath.toString();

        VrsOptionalConfig vopt = new VrsOptionalConfig();
        vopt.navsys = Constants.SYS_GPS | Constants.SYS_CMP | Constants.SYS_GAL;
        vopt.msmType = 4;
        vopt.stationMsgType = 1005;

        new VrsProcessor().process(req, vopt);
        byte[] vrsData = Files.readAllBytes(outPath);
        System.out.printf("原始基站: %d bytes,  VRS@基站输出: %d bytes%n", baseData.length, vrsData.length);

        // --- B. 消息类型直方图（内部 Rtcm，与 RTK 同路径） ---
        System.out.println();
        System.out.println("=== B. 消息类型直方图 ===");
        Map<Integer, Integer> histBase = msgTypeHist(baseData);
        Map<Integer, Integer> histVrs = msgTypeHist(vrsData);
        Set<Integer> allTypes = new TreeSet<>();
        allTypes.addAll(histBase.keySet());
        allTypes.addAll(histVrs.keySet());
        for (int t : allTypes) {
            System.out.printf("  type=%d  原始=%d  VRS=%d%n",
                    t, histBase.getOrDefault(t, 0), histVrs.getOrDefault(t, 0));
        }

        // --- C. 观测流对比（parseRtcmBatch 方式：obsflag==1 才提交） ---
        System.out.println();
        System.out.println("=== C. 观测流逐项对比 ===");
        List<Obsd[]> baseEp = decodeRawEpochs(baseData);
        List<Obsd[]> vrsEp = decodeRawEpochs(vrsData);
        System.out.printf("  历元数: 原始=%d  VRS=%d%n", baseEp.size(), vrsEp.size());

        int nCmp = Math.min(baseEp.size(), vrsEp.size());
        int epochSatDiff = 0, cellCnt = 0, codeDiff = 0, lliDiff = 0;
        double maxDP = 0, maxDL = 0, maxDD = 0, maxDSNR = 0;
        String firstDiff = null;
        List<String> topDiffs = new ArrayList<>();
        for (int e = 0; e < nCmp; e++) {
            Obsd[] be = baseEp.get(e);
            Obsd[] ve = vrsEp.get(e);
            double dt = org.rtklib.java.time.TimeSystem.timediff(be[0].time, ve[0].time);
            if (Math.abs(dt) > 1e-6 && firstDiff == null) {
                firstDiff = String.format("历元%d时间差 %.6fs", e, dt);
            }
            if (be.length != ve.length) {
                epochSatDiff++;
                if (firstDiff == null) firstDiff = String.format("历元%d卫星数: 原始=%d VRS=%d", e, be.length, ve.length);
            }
            for (Obsd b : be) {
                Obsd v = null;
                for (Obsd x : ve) if (x.sat == b.sat) { v = x; break; }
                if (v == null) {
                    if (firstDiff == null) firstDiff = String.format("历元%d sat=%d VRS缺失", e, b.sat);
                    continue;
                }
                for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                    if (b.code[j] == 0 && v.code[j] == 0) continue;
                    cellCnt++;
                    if (b.code[j] != v.code[j]) {
                        codeDiff++;
                        if (firstDiff == null) firstDiff = String.format(
                                "历元%d sat=%d slot%d code: 原始=%d VRS=%d", e, b.sat, j, b.code[j], v.code[j]);
                    }
                    double dP = (b.P[j] != 0 && v.P[j] != 0) ? Math.abs(v.P[j] - b.P[j]) : 0;
                    double dL = (b.L[j] != 0 && v.L[j] != 0) ? Math.abs(v.L[j] - b.L[j]) : 0;
                    if (dP > maxDP) maxDP = dP;
                    if (dL > maxDL) maxDL = dL;
                    if ((dP > 0.05 || dL > 0.5) && topDiffs.size() < 12) {
                        topDiffs.add(String.format("    e=%d sat=%d slot%d code=%d dP=%.4fm dL=%.4f周  P: %.4f vs %.4f  L: %.4f vs %.4f",
                                e, b.sat, j, b.code[j], dP, dL, b.P[j], v.P[j], b.L[j], v.L[j]));
                    }
                    if (b.D[j] != 0 && v.D[j] != 0) maxDD = Math.max(maxDD, Math.abs(v.D[j] - b.D[j]));
                    if (b.SNR[j] != 0 && v.SNR[j] != 0) maxDSNR = Math.max(maxDSNR, Math.abs(v.SNR[j] - b.SNR[j]));
                    if (b.LLI[j] != v.LLI[j]) lliDiff++;
                }
            }
        }
        System.out.printf("  对比历元=%d  卫星数不同历元=%d  cell=%d  code不同=%d  LLI不同=%d%n",
                nCmp, epochSatDiff, cellCnt, codeDiff, lliDiff);
        System.out.printf("  max|dP|=%.6fm  max|dL|=%.6f周  max|dD|=%.4fHz  max|dSNR|=%.4f%n",
                maxDP, maxDL, maxDD, maxDSNR);
        if (!topDiffs.isEmpty()) {
            System.out.println("  --- 前12个超阈差异cell ---");
            for (String s : topDiffs) System.out.println(s);
        }
        if (firstDiff != null) System.out.println("  首个差异: " + firstDiff);
        else System.out.println("  观测流完全一致（除LLI计数差异外）");

        // --- C2. epoch0 全槽位 code[] 对比（定位槽位交叉） ---
        if (!baseEp.isEmpty() && !vrsEp.isEmpty()) {
            System.out.println("  --- epoch0 code[]槽位对比 (sat: 原始code[0..7] | VRS code[0..7]) ---");
            for (Obsd b : baseEp.get(0)) {
                Obsd v = null;
                for (Obsd x : vrsEp.get(0)) if (x.sat == b.sat) { v = x; break; }
                StringBuilder sbB = new StringBuilder(), sbV = new StringBuilder();
                for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) sbB.append(b.code[j]).append(',');
                if (v != null) for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) sbV.append(v.code[j]).append(',');
                System.out.printf("    sat=%d  [%s] | [%s]%n", b.sat, sbB, sbV);
            }
        }

        // --- D. 星历对比 ---
        System.out.println();
        System.out.println("=== D. 星历对比 ===");
        Nav navB = preloadEphemeris(baseData);
        Nav navV = preloadEphemeris(vrsData);
        int ephB = countEphemeris(navB), ephV = countEphemeris(navV);
        System.out.printf("  星历数: 原始=%d  VRS=%d%n", ephB, ephV);
        int ephCompared = 0;
        double maxDA = 0, maxDM0 = 0, maxDf0 = 0, maxDtgd = 0, maxDtoe = 0;
        String firstEphDiff = null;
        for (int s = 0; s < navB.nmax; s++) {
            Eph eb = navB.eph[s];
            Eph ev = navV.eph[s];
            if (eb == null || eb.sat <= 0 || ev == null || ev.sat <= 0) continue;
            ephCompared++;
            maxDA = Math.max(maxDA, Math.abs(eb.A - ev.A));
            maxDM0 = Math.max(maxDM0, Math.abs(eb.M0 - ev.M0));
            maxDf0 = Math.max(maxDf0, Math.abs(eb.f0 - ev.f0));
            if (eb.tgd != null && ev.tgd != null) {
                for (int k = 0; k < Math.min(eb.tgd.length, ev.tgd.length); k++) {
                    maxDtgd = Math.max(maxDtgd, Math.abs(eb.tgd[k] - ev.tgd[k]));
                }
            }
            double dtoe = Math.abs(org.rtklib.java.time.TimeSystem.timediff(eb.toe, ev.toe));
            maxDtoe = Math.max(maxDtoe, dtoe);
            if (firstEphDiff == null) {
                if (eb.week != ev.week) firstEphDiff = String.format("sat=%d week: %d vs %d", eb.sat, eb.week, ev.week);
                else if (eb.iode != ev.iode) firstEphDiff = String.format("sat=%d iode: %d vs %d", eb.sat, eb.iode, ev.iode);
                else if (eb.svh != ev.svh) firstEphDiff = String.format("sat=%d svh: %d vs %d", eb.sat, eb.svh, ev.svh);
                else if (eb.flag != ev.flag) firstEphDiff = String.format("sat=%d flag: %d vs %d", eb.sat, eb.flag, ev.flag);
                else if (Math.abs(eb.A - ev.A) > 1e-4) firstEphDiff = String.format("sat=%d A: %.10f vs %.10f", eb.sat, eb.A, ev.A);
                else if (dtoe > 0.5) firstEphDiff = String.format("sat=%d toe差 %.3fs", eb.sat, dtoe);
            }
        }
        System.out.printf("  共同卫星=%d  max|dA|=%.6e  max|dM0|=%.6e  max|df0|=%.6e  max|dtgd|=%.6e  max|dtoe|=%.3fs%n",
                ephCompared, maxDA, maxDM0, maxDf0, maxDtgd, maxDtoe);
        if (firstEphDiff != null) System.out.println("  首个星历差异: " + firstEphDiff);

        // --- E. RTK 对比（同配置） ---
        double[] roverEcef = new double[3];
        CoordTransform.pos2ecef(new double[]{ROVER_LAT * Constants.D2R, ROVER_LON * Constants.D2R, ROVER_HGT}, roverEcef);

        System.out.println();
        System.out.println("=== E1. RTK: rover + 原始基站 ===");
        RtkProcessor rtk1 = new RtkProcessor(createRtkOpt());
        RtkProcessor.RtkResult res1 = rtk1.process(roverData, baseData);
        printRtkStats("原始基站", analyzeRtkResult(res1, roverEcef), 105.0);

        System.out.println();
        System.out.println("=== E2. RTK: rover + VRS@基站（改正量=0） ===");
        RtkProcessor rtk2 = new RtkProcessor(createRtkOpt());
        RtkProcessor.RtkResult res2 = rtk2.process(roverData, vrsData);
        RtkStats st2 = analyzeRtkResult(res2, roverEcef);
        printRtkStats("VRS@基站", st2, 105.0);

        System.out.println();
        System.out.println("=== 二分判定 ===");
        RtkStats st1 = analyzeRtkResult(res1, roverEcef);
        System.out.printf("  原始基站FIX=%.1f%%   VRS@基站FIX=%.1f%%%n", st1.fixRatio, st2.fixRatio);
        if (st2.fixRatio > 0.5 * st1.fixRatio) {
            System.out.println("  → VRS@基站能固定：流层(编码/星历/锁)正常，问题在【改正路径】(105m位移)");
        } else {
            System.out.println("  → VRS@基站仍无法固定：问题在【流层面】(编码/星历/锁/消息结构)，与改正无关");
        }

        assertTrue(res2.totalEpochs > 0, "VRS@基站 RTK应有历元数据");
    }

    private static Map<Integer, Integer> msgTypeHist(byte[] data) {
        Map<Integer, Integer> hist = new TreeMap<>();
        org.rtklib.java.rtcm.Rtcm rtcm = new org.rtklib.java.rtcm.Rtcm();
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            hist.merge(rtcm.type, 1, Integer::sum);
        }
        return hist;
    }

    /** 与 RtkProcessor.parseRtcmBatch 完全同方式解码（内部 Rtcm，obsflag==1 提交） */
    private static List<Obsd[]> decodeRawEpochs(byte[] data) {
        List<Obsd[]> out = new ArrayList<>();
        org.rtklib.java.rtcm.Rtcm rtcm = new org.rtklib.java.rtcm.Rtcm();
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            int type = rtcm.type;
            boolean isObs = (type >= 1001 && type <= 1004)
                    || (type >= 1074 && type <= 1077) || (type >= 1084 && type <= 1087)
                    || (type >= 1094 && type <= 1097) || (type >= 1104 && type <= 1107)
                    || (type >= 1114 && type <= 1117) || (type >= 1124 && type <= 1127)
                    || (type >= 1134 && type <= 1137);
            if (isObs && rtcm.obs.n > 0 && rtcm.obsflag == 1) {
                Obsd[] arr = new Obsd[rtcm.obs.n];
                for (int i = 0; i < rtcm.obs.n; i++) arr[i] = new Obsd(rtcm.obs.data[i]);
                out.add(arr);
            }
        }
        return out;
    }

    @Test
    @DisplayName("原始基站RTCM信号集分析：每系统实际输出多少个频点/信号")
    void testAnalyzeBaseSignalSet() throws IOException {
        if (!dataAvailable) {
            System.out.println("跳过：数据不可用");
            return;
        }

        Path basePath = Paths.get(DATA_ROOT, BASE_STATION, DATE, TEST_HOUR + ".rtcm3");
        byte[] baseData = Files.readAllBytes(basePath);
        Map<String, List<Obsd>> baseEpochs = decodeEpochs(baseData);

        String[] sysNames = {"GPS", "GLO", "GAL", "QZS", "SBS", "BDS", "IRN"};
        int[] sysKeys = {Constants.SYS_GPS, Constants.SYS_GLO, Constants.SYS_GAL,
                         Constants.SYS_QZS, Constants.SYS_SBS, Constants.SYS_CMP, Constants.SYS_IRN};

        System.out.println("=== 原始基站 RTCM 信号集分析 ===");
        System.out.printf("历元数: %d%n%n", baseEpochs.size());

        for (int si = 0; si < sysKeys.length; si++) {
            Map<Integer, Set<String>> slotSig = new TreeMap<>();
            Map<Integer, Integer> freqCount = new TreeMap<>();
            Map<String, Integer> sigEpochCount = new TreeMap<>();
            int satSeen = 0;
            int maxFreq = 0;

            for (List<Obsd> list : baseEpochs.values()) {
                for (Obsd o : list) {
                    int[] prn = new int[1];
                    if (org.rtklib.java.common.SatUtils.satsys(o.sat, prn) != sysKeys[si]) continue;
                    satSeen++;
                    Set<Integer> freqs = new TreeSet<>();
                    for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                        if (o.code[j] == 0) continue;
                        String obs = org.rtklib.java.common.ObsCode.code2obs(o.code[j]);
                        int fi = org.rtklib.java.common.ObsCode.code2idx(sysKeys[si], o.code[j]);
                        slotSig.computeIfAbsent(j, k -> new TreeSet<>()).add(obs + "(fi=" + fi + ")");
                        freqs.add(fi);
                        sigEpochCount.merge(obs, 1, Integer::sum);
                    }
                    int nf = freqs.size();
                    freqCount.merge(nf, 1, Integer::sum);
                    if (nf > maxFreq) maxFreq = nf;
                }
            }

            if (satSeen == 0) continue;
            System.out.printf("--- %s ---%n", sysNames[si]);
            System.out.printf("  观测样本: %d  最多频点数/卫星: %d%n", satSeen, maxFreq);
            System.out.print("  每频点数分布: ");
            for (Map.Entry<Integer, Integer> e : freqCount.entrySet()) {
                System.out.printf("%d频点=%d次  ", e.getKey(), e.getValue());
            }
            System.out.println();
            System.out.print("  信号集合: ");
            for (Map.Entry<String, Integer> e : sigEpochCount.entrySet()) {
                System.out.printf("%s(%d)  ", e.getKey(), e.getValue());
            }
            System.out.println();
            System.out.print("  槽位分布: ");
            for (Map.Entry<Integer, Set<String>> e : slotSig.entrySet()) {
                System.out.printf("slot%d=%s  ", e.getKey(), e.getValue());
            }
            System.out.println();
            System.out.println();
        }

        System.out.println("=== 逐历元 BDS nsat / 信号数 / nsat*ncode (MSM4 上限64) ===");
        System.out.println("  idx  nsat  ncode  nsat*ncode   signalList");
        int idx = 0;
        Map<String, Integer> comboCount = new TreeMap<>();
        for (List<Obsd> list : baseEpochs.values()) {
            Set<Integer> sats = new TreeSet<>();
            Set<Integer> codes = new TreeSet<>();
            for (Obsd o : list) {
                int[] prn = new int[1];
                if (org.rtklib.java.common.SatUtils.satsys(o.sat, prn) != Constants.SYS_CMP) continue;
                sats.add(o.sat);
                for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                    if (o.code[j] != 0) codes.add(o.code[j]);
                }
            }
            if (sats.isEmpty()) continue;
            int nsat = sats.size();
            int ncode = codes.size();
            comboCount.merge(nsat + "x" + ncode, 1, Integer::sum);
            if (idx < 8) {
                StringBuilder sb = new StringBuilder();
                for (int c : codes) sb.append(org.rtklib.java.common.ObsCode.code2obs(c)).append(" ");
                System.out.printf("  %3d  %4d  %5d  %10d   %s%n", idx, nsat, ncode, nsat * ncode, sb);
            }
            idx++;
        }
        System.out.println("  (nsat x ncode) 组合分布: " + comboCount);
    }

    private static String epochKey(org.rtklib.java.data.GTime t) {
        int[] wk = new int[1];
        double tow = org.rtklib.java.time.TimeSystem.time2gpst(t, wk);
        return String.format("W%d_%.3f", wk[0], tow);
    }

    private static Map<String, List<Obsd>> decodeEpochs(byte[] data) {
        Map<String, List<Obsd>> out = new LinkedHashMap<>();
        RtcmDataHandler h = new RtcmDataHandler() {
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                out.computeIfAbsent(epochKey(epoch.time), k -> new ArrayList<>())
                   .addAll(epoch.getObservations());
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(h);
        decoder.feed(data, 0, data.length);
        decoder.finish();
        return out;
    }

    private PrcOpt createRtkOpt() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.glomodear = Constants.GLO_ARMODE_OFF;
        opt.bdsmodear = 1;
        opt.refpos = Constants.POSOPT_RTCM;
        opt.intpref = 1;
        opt.maxtdiff = 30.0;
        opt.outsingle = 1;
        opt.elmaskar = 15.0 * Constants.D2R;
        opt.minlock = 20;
        opt.minfix = 20;
        opt.thresar[0] = 3.0;
        opt.tidecorr = 1;
        opt.err[1] = 0.003;
        opt.err[2] = 0.003;
        opt.err[3] = 0.0;
        opt.err[6] = 0.0;
        opt.err[7] = 0.0;
        return opt;
    }

    private static class RtkStats {
        int totalEpochs;
        int fixCount;
        int floatCount;
        int singleCount;
        double fixRatio;
        double rmsN;
        double rmsE;
        double rmsU;
        double rms3d;
        double meanN;
        double meanE;
        double meanU;
    }

    private RtkStats analyzeRtkResult(RtkProcessor.RtkResult result, double[] trueEcef) {
        RtkStats stats = new RtkStats();
        stats.totalEpochs = result.totalEpochs;
        stats.fixCount = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        stats.floatCount = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
        stats.singleCount = result.solutions.size() - stats.fixCount - stats.floatCount;
        stats.fixRatio = result.solutions.size() > 0 ? 100.0 * stats.fixCount / result.solutions.size() : 0;

        List<SolData> fixSols = result.solutions.stream()
                .filter(s -> s.status == SolutionStatus.FIX).toList();

        if (fixSols.isEmpty()) {
            stats.rmsN = stats.rmsE = stats.rmsU = stats.rms3d = Double.NaN;
            stats.meanN = stats.meanE = stats.meanU = Double.NaN;
            return stats;
        }

        double[] trueBlh = new double[3];
        CoordTransform.ecef2pos(trueEcef, trueBlh);

        double sumN = 0, sumE = 0, sumU = 0;
        double sumN2 = 0, sumE2 = 0, sumU2 = 0;
        int validCount = 0;
        for (SolData sol : fixSols) {
            Position ecefPos = sol.getPosition(CoordType.ECEF);
            if (ecefPos == null) continue;
            double[] solEcef = new double[]{ecefPos.v1, ecefPos.v2, ecefPos.v3};
            double[] enu = new double[3];
            double[] diff = new double[]{solEcef[0] - trueEcef[0], solEcef[1] - trueEcef[1], solEcef[2] - trueEcef[2]};
            CoordTransform.ecef2enu(trueBlh, diff, enu);
            sumN += enu[0]; sumE += enu[1]; sumU += enu[2];
            sumN2 += enu[0] * enu[0]; sumE2 += enu[1] * enu[1]; sumU2 += enu[2] * enu[2];
            validCount++;
        }
        int n = Math.max(1, validCount);
        stats.meanN = sumN / n;
        stats.meanE = sumE / n;
        stats.meanU = sumU / n;
        stats.rmsN = Math.sqrt(sumN2 / n);
        stats.rmsE = Math.sqrt(sumE2 / n);
        stats.rmsU = Math.sqrt(sumU2 / n);
        stats.rms3d = Math.sqrt((sumN2 + sumE2 + sumU2) / n);
        return stats;
    }

    private void printRtkStats(String label, RtkStats stats, double baseline) {
        System.out.printf("  [%s]%n", label);
        System.out.printf("    历元=%d  FIX=%d(%.1f%%)  FLOAT=%d  SINGLE=%d%n",
                stats.totalEpochs, stats.fixCount, stats.fixRatio, stats.floatCount, stats.singleCount);
        if (stats.fixCount > 0) {
            System.out.printf("    FIX精度: N=%.4fm  E=%.4fm  U=%.4fm  3D=%.4fm%n",
                    stats.rmsN, stats.rmsE, stats.rmsU, stats.rms3d);
            System.out.printf("    FIX偏差: N=%.4fm  E=%.4fm  U=%.4fm%n",
                    stats.meanN, stats.meanE, stats.meanU);
        } else {
            System.out.println("    无FIX解");
        }
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

    private static int countEphemeris(Nav nav) {
        int cnt = 0;
        for (int i = 0; i < nav.nmax; i++) {
            if (nav.eph[i] != null && nav.eph[i].sat > 0) cnt++;
        }
        return cnt;
    }
}