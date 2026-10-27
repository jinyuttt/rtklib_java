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
    private static final String DATE = "2026-10-27";
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