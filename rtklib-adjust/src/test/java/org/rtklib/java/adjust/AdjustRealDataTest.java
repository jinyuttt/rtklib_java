package org.rtklib.java.adjust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.engine.GnssBaselineAdjust;
import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.BaselineEpoch;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.pntpos.SppProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rtklib-adjust 实测数据测试：2基站1测站多基线间接平差。
 *
 * <p>数据配置：src/test/resources/test-data.properties（不提交到仓库）</p>
 * <p>模板文件：test-data.properties.template</p>
 *
 * <p>流程：</p>
 * <ol>
 *   <li>对每条基线（A→测站, B→测站）独立RTK解算，得到逐历元SolData</li>
 *   <li>按历元时间对齐两条基线的SolData</li>
 *   <li>构建BaselineEpoch，执行GnssBaselineAdjust.adjust()</li>
 *   <li>输出平差结果统计：FIX比例、σ₀分布、Baarda T、平差坐标</li>
 * </ol>
 */
public class AdjustRealDataTest {

    private static final TestDataConfig cfg = new TestDataConfig();

    private static final String DATA_ROOT = cfg.getDataRoot();
    private static final String BASE_A = cfg.getBaseA();
    private static final String BASE_B = cfg.getBaseB();
    private static final String ROVER = cfg.getRover();
    private static final String ROVER2 = cfg.getRover2();
    private static final String DATE = cfg.getDate();

    private static final boolean DATA_AVAILABLE = cfg.isAvailable() && Files.isDirectory(Paths.get(DATA_ROOT));

    static class EpochPair {
        final String epochTag;
        final SolData solA;
        final SolData solB;
        EpochPair(String epochTag, SolData solA, SolData solB) {
            this.epochTag = epochTag;
            this.solA = solA;
            this.solB = solB;
        }
    }

    static class AdjustStats {
        int totalEpochs = 0;
        int bothFix = 0;
        int onlyAFix = 0;
        int onlyBFix = 0;
        int neitherFix = 0;
        int adjustSuccess = 0;
        int adjustFail = 0;
        double sigma0Sum = 0;
        double sigma0Max = 0;
        double sigma0Min = Double.MAX_VALUE;
        double maxBaardaT = 0;
        final List<double[]> adjustedPositions = new ArrayList<>();
        final List<double[]> singleABaselinePositions = new ArrayList<>();
        final List<double[]> singleBBaselinePositions = new ArrayList<>();
    }

    @BeforeAll
    static void checkData() {
        if (!DATA_AVAILABLE) {
            System.out.println("⚠ 数据目录不存在，跳过实测数据测试: " + DATA_ROOT);
        }
    }

    @Test
    @DisplayName("2基站1测站多基线平差：连续9小时RTCM数据")
    void testMultiBaselineAdjust9Hours() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        int startHour = 0;
        int endHour = 8;

        System.out.println("========================================");
        System.out.println("  rtklib-adjust 实测数据测试");
        System.out.println("  基站A: " + BASE_A);
        System.out.println("  基站B: " + BASE_B);
        System.out.println("  测站:  " + ROVER);
        System.out.println("  日期:  " + DATE);
        System.out.printf("  时段:  %02d:00 ~ %02d:59%n", startHour, endHour);
        System.out.println("========================================");

        PrcOpt opt = createRtkOpt();

        RtkProcessor rtkA = new RtkProcessor(opt);
        RtkProcessor rtkB = new RtkProcessor(opt);

        Map<String, RtkProcessor.RtkResult> resultA = new LinkedHashMap<>();
        Map<String, RtkProcessor.RtkResult> resultB = new LinkedHashMap<>();

        for (int hour = startHour; hour <= endHour; hour++) {
            String baseAFile = buildFilePath(BASE_A, DATE, hour);
            String baseBFile = buildFilePath(BASE_B, DATE, hour);
            String roverFile = buildFilePath(ROVER, DATE, hour);

            if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                    || !Files.exists(Paths.get(roverFile))) {
                System.out.printf("  Hour %02d: 文件缺失，跳过%n", hour);
                continue;
            }

            System.out.printf("  Hour %02d: 处理中...", hour);

            RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);
            resultA.put(String.format("%02d", hour), resA);

            rtkA.resetForNextBatch();

            RtkProcessor.RtkResult resB = rtkB.process(roverFile, baseBFile);
            resultB.put(String.format("%02d", hour), resB);

            rtkB.resetForNextBatch();

            int fixA = countFix(resA);
            int fixB = countFix(resB);
            System.out.printf(" 基线A: total=%d FIX=%d, 基线B: total=%d FIX=%d%n",
                    resA.solutions.size(), fixA, resB.solutions.size(), fixB);
        }

        List<SolData> allSolA = new ArrayList<>();
        List<SolData> allSolB = new ArrayList<>();
        for (var e : resultA.entrySet()) allSolA.addAll(e.getValue().solutions);
        for (var e : resultB.entrySet()) allSolB.addAll(e.getValue().solutions);

        System.out.printf("%n=== RTK解算汇总 ===%n");
        System.out.printf("基线A (%s→%s): 总历元=%d, FIX=%d (%.1f%%)%n",
                BASE_A, ROVER, allSolA.size(), countFixSol(allSolA),
                allSolA.isEmpty() ? 0 : 100.0 * countFixSol(allSolA) / allSolA.size());
        System.out.printf("基线B (%s→%s): 总历元=%d, FIX=%d (%.1f%%)%n",
                BASE_B, ROVER, allSolB.size(), countFixSol(allSolB),
                allSolB.isEmpty() ? 0 : 100.0 * countFixSol(allSolB) / allSolB.size());

        List<EpochPair> aligned = alignByEpoch(allSolA, allSolB);
        System.out.printf("%n历元对齐: %d 个共同历元%n", aligned.size());

        printBaselineCoordinateStats(allSolA, "基线A");
        printBaselineCoordinateStats(allSolB, "基线B");

        printBaseStationCoordinates(DATE, 0, 8);

        AdjustStats stats = runAdjust(aligned);

        printStats(stats);

        assertTrue(stats.totalEpochs > 0, "应有共同历元");
        assertTrue(stats.bothFix > 0, "应有双FIX历元");
        assertTrue(stats.adjustSuccess > 0, "应有平差成功历元");
    }

    @Test
    @DisplayName("SPP定位基站坐标，对比RTCM 1005")
    void testSppBaseStationPosition() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("  SPP定位基站坐标 vs RTCM 1005");
        System.out.println("========================================");

        String[] baseIds = {BASE_A, BASE_B};
        int startHour = 0;
        int endHour = 8;

        for (String baseId : baseIds) {
            PrcOpt sppOpt = SppProcessor.createDefaultOpt();
            SppProcessor spp = new SppProcessor(sppOpt);

            for (int hour = startHour; hour <= endHour; hour++) {
                String filePath = buildFilePath(baseId, DATE, hour);
                if (!Files.exists(Paths.get(filePath))) continue;
                byte[] data = Files.readAllBytes(Paths.get(filePath));
                spp.feed(data);
            }
            SppProcessor.SppResult result = spp.finish();

            List<SolData> solList = result.solutions;
            if (solList.isEmpty()) {
                System.out.printf("  %s: SPP无有效解%n", baseId);
                continue;
            }

            double sumX = 0, sumY = 0, sumZ = 0;
            int n = solList.size();
            for (SolData sd : solList) {
                Position p = sd.getPosition(CoordType.ECEF);
                if (p != null) { sumX += p.v1; sumY += p.v2; sumZ += p.v3; }
            }
            double meanX = sumX / n, meanY = sumY / n, meanZ = sumZ / n;

            double varX = 0, varY = 0, varZ = 0;
            for (SolData sd : solList) {
                Position p = sd.getPosition(CoordType.ECEF);
                if (p != null) {
                    varX += Math.pow(p.v1 - meanX, 2);
                    varY += Math.pow(p.v2 - meanY, 2);
                    varZ += Math.pow(p.v3 - meanZ, 2);
                }
            }
            double stdX = Math.sqrt(varX / n);
            double stdY = Math.sqrt(varY / n);
            double stdZ = Math.sqrt(varZ / n);

            double[] sppLlh = new double[3];
            CoordTransform.ecef2pos(new double[]{meanX, meanY, meanZ}, sppLlh);

            double[] rtcm1005Pos = null;
            String filePath0 = buildFilePath(baseId, DATE, 0);
            if (Files.exists(Paths.get(filePath0))) {
                rtcm1005Pos = extractStationPos(Files.readAllBytes(Paths.get(filePath0)));
            }

            System.out.printf("%n  %s SPP定位结果:%n", baseId);
            System.out.printf("    有效历元: %d/%d%n", solList.size(), result.totalEpochs);
            System.out.printf("    SPP ECEF均值: X=%.3f Y=%.3f Z=%.3f%n", meanX, meanY, meanZ);
            System.out.printf("    SPP ECEF标准差: σX=%.4f σY=%.4f σZ=%.4f (m)%n", stdX, stdY, stdZ);
            System.out.printf("    SPP LLH: Lat=%.9f Lon=%.9f H=%.4f%n",
                    Math.toDegrees(sppLlh[0]), Math.toDegrees(sppLlh[1]), sppLlh[2]);

            if (rtcm1005Pos != null) {
                double[] rtcmLlh = new double[3];
                CoordTransform.ecef2pos(rtcm1005Pos, rtcmLlh);
                System.out.printf("    RTCM 1005 ECEF: X=%.3f Y=%.3f Z=%.3f%n", rtcm1005Pos[0], rtcm1005Pos[1], rtcm1005Pos[2]);
                System.out.printf("    RTCM 1005 LLH: Lat=%.9f Lon=%.9f H=%.4f%n",
                        Math.toDegrees(rtcmLlh[0]), Math.toDegrees(rtcmLlh[1]), rtcmLlh[2]);

                double dX = meanX - rtcm1005Pos[0];
                double dY = meanY - rtcm1005Pos[1];
                double dZ = meanZ - rtcm1005Pos[2];
                double dist3d = Math.sqrt(dX * dX + dY * dY + dZ * dZ);
                double dN = (sppLlh[0] - rtcmLlh[0]) * 6371000;
                double dE = (sppLlh[1] - rtcmLlh[1]) * 6371000 * Math.cos(rtcmLlh[0]);
                double dU = sppLlh[2] - rtcmLlh[2];

                System.out.printf("    SPP - RTCM1005 差异:%n");
                System.out.printf("      ECEF: dX=%.3f dY=%.3f dZ=%.3f (3D=%.3f m)%n", dX, dY, dZ, dist3d);
                System.out.printf("      NEU:  dN=%.3f dE=%.3f dU=%.3f (m)%n", dN, dE, dU);
            } else {
                System.out.printf("    RTCM 1005: 未找到%n");
            }
        }
    }

    @Test
    @DisplayName("静态模式解算基站A精确坐标")
    void testStaticSolveBaseA() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("  静态模式解算基站A精确坐标");
        System.out.println("  基站B作为参考站(假设B坐标正确)");
        System.out.println("========================================");

        int startHour = 0;
        int endHour = 8;

        double[] rtcmPosA = extractStationPos(Files.readAllBytes(Paths.get(buildFilePath(BASE_A, DATE, 0))));
        double[] rtcmPosB = extractStationPos(Files.readAllBytes(Paths.get(buildFilePath(BASE_B, DATE, 0))));

        PrcOpt staticOpt = new PrcOpt();
        staticOpt.mode = Constants.PMODE_STATIC;
        staticOpt.nf = 3;
        staticOpt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        staticOpt.elmin = 15.0 * Constants.D2R;
        staticOpt.soltype = 0;
        staticOpt.modear = Constants.ARMODE_FIXHOLD;
        staticOpt.glomodear = Constants.GLO_ARMODE_AUTOCAL;
        staticOpt.ionoopt = Constants.IONOOPT_BRDC;
        staticOpt.tropopt = Constants.TROPOPT_SAAS;
        staticOpt.posMask = 0xFFFF;

        RtkProcessor rtk = new RtkProcessor(staticOpt);

        List<SolData> allSol = new ArrayList<>();
        for (int hour = startHour; hour <= endHour; hour++) {
            String baseAFile = buildFilePath(BASE_A, DATE, hour);
            String baseBFile = buildFilePath(BASE_B, DATE, hour);

            if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))) continue;

            System.out.printf("  处理 Hour %02d...%n", hour);
            RtkProcessor.RtkResult res = rtk.process(baseAFile, baseBFile);
            rtk.resetForNextBatch();
            allSol.addAll(res.solutions);
        }

        List<SolData> fixSol = allSol.stream().filter(sd -> sd.status == SolutionStatus.FIX).toList();
        List<SolData> floatSol = allSol.stream().filter(sd -> sd.status == SolutionStatus.FLOAT).toList();

        System.out.printf("%n静态解算结果 (基站A作为Rover, 基站B作为Base):%n");
        System.out.printf("  总历元: %d, FIX: %d (%.1f%%), FLOAT: %d%n",
                allSol.size(), fixSol.size(), fixSol.size() * 100.0 / allSol.size(), floatSol.size());

        if (fixSol.isEmpty()) {
            System.out.println("  无FIX解，无法得到精确坐标");
            return;
        }

        Position meanEcef = computeMeanEcef(fixSol);
        double[] meanArr = {meanEcef.v1, meanEcef.v2, meanEcef.v3};
        double[] meanLlh = new double[3];
        CoordTransform.ecef2pos(meanArr, meanLlh);

        double varX = 0, varY = 0, varZ = 0;
        for (SolData sd : fixSol) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) {
                varX += Math.pow(p.v1 - meanEcef.v1, 2);
                varY += Math.pow(p.v2 - meanEcef.v2, 2);
                varZ += Math.pow(p.v3 - meanEcef.v3, 2);
            }
        }
        double stdX = Math.sqrt(varX / fixSol.size());
        double stdY = Math.sqrt(varY / fixSol.size());
        double stdZ = Math.sqrt(varZ / fixSol.size());

        System.out.printf("%n  静态FIX ECEF均值: X=%.4f Y=%.4f Z=%.4f%n", meanEcef.v1, meanEcef.v2, meanEcef.v3);
        System.out.printf("  静态FIX ECEF标准差: σX=%.4f σY=%.4f σZ=%.4f (m)%n", stdX, stdY, stdZ);
        System.out.printf("  静态FIX LLH: Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(meanLlh[0]), Math.toDegrees(meanLlh[1]), meanLlh[2]);

        double[] rtcmLlhA = new double[3];
        CoordTransform.ecef2pos(rtcmPosA, rtcmLlhA);

        System.out.printf("%n  RTCM 1005 LLH: Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(rtcmLlhA[0]), Math.toDegrees(rtcmLlhA[1]), rtcmLlhA[2]);

        double dN = (meanLlh[0] - rtcmLlhA[0]) * 6371000;
        double dE = (meanLlh[1] - rtcmLlhA[1]) * 6371000 * Math.cos(rtcmLlhA[0]);
        double dU = meanLlh[2] - rtcmLlhA[2];
        double dX = meanEcef.v1 - rtcmPosA[0];
        double dY = meanEcef.v2 - rtcmPosA[1];
        double dZ = meanEcef.v3 - rtcmPosA[2];
        double dist3d = Math.sqrt(dX * dX + dY * dY + dZ * dZ);

        System.out.printf("%n  静态FIX - RTCM1005 (基站A):%n");
        System.out.printf("    ECEF: dX=%.4f dY=%.4f dZ=%.4f (3D=%.4f m)%n", dX, dY, dZ, dist3d);
        System.out.printf("    NEU:  dN=%.4f dE=%.4f dU=%.4f (m)%n", dN, dE, dU);

        System.out.printf("%n  结论: 基站A的RTCM 1005坐标偏差约 %.1f m%n", dist3d);
        if (dist3d > 1.0) {
            System.out.println("  → 基站A的RTCM 1005坐标确实存在显著偏差!");
            System.out.println("  → 用静态解算坐标替代RTCM 1005坐标，重新做RTK+平差验证");

            PrcOpt optA = createRtkOpt();
            optA.rb[0] = meanEcef.v1; optA.rb[1] = meanEcef.v2; optA.rb[2] = meanEcef.v3;
            optA.refpos = Constants.POSOPT_POS_XYZ;
            optA.intpref = 1;

            PrcOpt optB = createRtkOpt();

            RtkProcessor rtkA2 = new RtkProcessor(optA);
            RtkProcessor rtkB2 = new RtkProcessor(optB);

            List<SolData> solA2 = new ArrayList<>();
            List<SolData> solB2 = new ArrayList<>();

            for (int hour = startHour; hour <= endHour; hour++) {
                String baseAFile = buildFilePath(BASE_A, DATE, hour);
                String baseBFile = buildFilePath(BASE_B, DATE, hour);
                String roverFile = buildFilePath(ROVER, DATE, hour);
                if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                        || !Files.exists(Paths.get(roverFile))) continue;

                RtkProcessor.RtkResult resA = rtkA2.process(roverFile, baseAFile);
                rtkA2.resetForNextBatch();
                solA2.addAll(resA.solutions);

                RtkProcessor.RtkResult resB = rtkB2.process(roverFile, baseBFile);
                rtkB2.resetForNextBatch();
                solB2.addAll(resB.solutions);
            }

            System.out.printf("%n  === 用静态坐标替代基站A后的RTK+平差 ===%n");
            System.out.printf("  基线A(静态坐标): total=%d, FIX=%d%n", solA2.size(), countFixSol(solA2));
            System.out.printf("  基线B(RTCM1005): total=%d, FIX=%d%n", solB2.size(), countFixSol(solB2));

            printBaselineCoordinateStats(solA2, "基线A(静态坐标)");
            printBaselineCoordinateStats(solB2, "基线B(RTCM1005)");

            List<SolData> fixA2 = solA2.stream().filter(sd -> sd.status == SolutionStatus.FIX).toList();
            List<SolData> fixB2 = solB2.stream().filter(sd -> sd.status == SolutionStatus.FIX).toList();
            if (!fixA2.isEmpty() && !fixB2.isEmpty()) {
                Position meanA2 = computeMeanEcef(fixA2);
                Position meanB2 = computeMeanEcef(fixB2);
                double dx2 = meanA2.v1 - meanB2.v1;
                double dy2 = meanA2.v2 - meanB2.v2;
                double dz2 = meanA2.v3 - meanB2.v3;
                double dist2 = Math.sqrt(dx2 * dx2 + dy2 * dy2 + dz2 * dz2);
                System.out.printf("%n  基线A-B Rover坐标差异: %.4f m (原始: 4.89m)%n", dist2);
            }

            List<EpochPair> aligned2 = alignByEpoch(solA2, solB2);
            if (!aligned2.isEmpty()) {
                AdjustStats stats2 = runAdjust(aligned2);
                printStats(stats2);
            }
        } else {
            System.out.println("  → 基站A的RTCM 1005坐标偏差在1m以内");
        }
    }

    @Test
    @DisplayName("仅用SPP中位数替换基站A坐标，基站B保持RTCM1005")
    void testAdjustWithSppBaseAOnly() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("  仅用SPP中位数替换基站A坐标");
        System.out.println("  基站B保持RTCM 1005不变");
        System.out.println("========================================");

        int startHour = 0;
        int endHour = 8;

        double[] rtcmPosA = extractStationPos(Files.readAllBytes(Paths.get(buildFilePath(BASE_A, DATE, 0))));
        double[] rtcmPosB = extractStationPos(Files.readAllBytes(Paths.get(buildFilePath(BASE_B, DATE, 0))));

        double[][] sppStatsA = sppPositionDetailed(BASE_A, startHour, endHour);
        if (sppStatsA == null) {
            System.out.println("跳过：基站A SPP定位失败");
            return;
        }
        double[] sppMedianA = sppStatsA[0];
        double[] sppStdA = sppStatsA[1];

        double[] rtcmLlhA = new double[3], rtcmLlhB = new double[3], sppLlhA = new double[3];
        CoordTransform.ecef2pos(rtcmPosA, rtcmLlhA);
        CoordTransform.ecef2pos(rtcmPosB, rtcmLlhB);
        CoordTransform.ecef2pos(sppMedianA, sppLlhA);

        System.out.printf("%n基站A: RTCM 1005 → SPP中位数%n");
        System.out.printf("  RTCM1005 ECEF: X=%.3f Y=%.3f Z=%.3f%n", rtcmPosA[0], rtcmPosA[1], rtcmPosA[2]);
        System.out.printf("  RTCM1005 LLH:  Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(rtcmLlhA[0]), Math.toDegrees(rtcmLlhA[1]), rtcmLlhA[2]);
        System.out.printf("  SPP中位 ECEF:  X=%.3f Y=%.3f Z=%.3f%n", sppMedianA[0], sppMedianA[1], sppMedianA[2]);
        System.out.printf("  SPP中位 LLH:   Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(sppLlhA[0]), Math.toDegrees(sppLlhA[1]), sppLlhA[2]);
        System.out.printf("  SPP精度(σ):    X=%.4f Y=%.4f Z=%.4f m%n", sppStdA[0], sppStdA[1], sppStdA[2]);
        double dNa = (sppLlhA[0] - rtcmLlhA[0]) * 6371000;
        double dEa = (sppLlhA[1] - rtcmLlhA[1]) * 6371000 * Math.cos(rtcmLlhA[0]);
        double dUa = sppLlhA[2] - rtcmLlhA[2];
        System.out.printf("  SPP-RTCM1005:  dN=%.3f dE=%.3f dU=%.3f m%n", dNa, dEa, dUa);

        System.out.printf("%n基站B: 使用RTCM 1005 (不变)%n");
        System.out.printf("  ECEF: X=%.3f Y=%.3f Z=%.3f%n", rtcmPosB[0], rtcmPosB[1], rtcmPosB[2]);
        System.out.printf("  LLH:  Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(rtcmLlhB[0]), Math.toDegrees(rtcmLlhB[1]), rtcmLlhB[2]);

        PrcOpt optA = createRtkOpt();
        optA.rb[0] = sppMedianA[0]; optA.rb[1] = sppMedianA[1]; optA.rb[2] = sppMedianA[2];
        optA.refpos = Constants.POSOPT_POS_XYZ;
        optA.intpref = 1;

        PrcOpt optB = createRtkOpt();

        RtkProcessor rtkA = new RtkProcessor(optA);
        RtkProcessor rtkB = new RtkProcessor(optB);

        List<SolData> allSolA = new ArrayList<>();
        List<SolData> allSolB = new ArrayList<>();

        for (int hour = startHour; hour <= endHour; hour++) {
            String baseAFile = buildFilePath(BASE_A, DATE, hour);
            String baseBFile = buildFilePath(BASE_B, DATE, hour);
            String roverFile = buildFilePath(ROVER, DATE, hour);

            if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                    || !Files.exists(Paths.get(roverFile))) continue;

            RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);
            rtkA.resetForNextBatch();
            allSolA.addAll(resA.solutions);

            RtkProcessor.RtkResult resB = rtkB.process(roverFile, baseBFile);
            rtkB.resetForNextBatch();
            allSolB.addAll(resB.solutions);
        }

        System.out.printf("%n基线A(SPP基站):       total=%d, FIX=%d%n", allSolA.size(), countFixSol(allSolA));
        System.out.printf("基线B(RTCM1005基站): total=%d, FIX=%d%n", allSolB.size(), countFixSol(allSolB));

        printBaselineCoordinateStats(allSolA, "基线A(SPP)");
        printBaselineCoordinateStats(allSolB, "基线B(RTCM1005)");

        List<SolData> fixA = allSolA.stream().filter(sd -> sd.status == SolutionStatus.FIX).toList();
        List<SolData> fixB = allSolB.stream().filter(sd -> sd.status == SolutionStatus.FIX).toList();
        if (!fixA.isEmpty() && !fixB.isEmpty()) {
            Position meanA = computeMeanEcef(fixA);
            Position meanB = computeMeanEcef(fixB);
            if (meanA != null && meanB != null) {
                double dx = meanA.v1 - meanB.v1;
                double dy = meanA.v2 - meanB.v2;
                double dz = meanA.v3 - meanB.v3;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                System.out.printf("%n基线A(SPP)-B(RTCM) Rover坐标差异: dist=%.4f m%n", dist);
            }
        }

        List<EpochPair> aligned = alignByEpoch(allSolA, allSolB);
        System.out.printf("历元对齐: %d 个共同历元%n", aligned.size());

        if (!aligned.isEmpty()) {
            AdjustStats stats = runAdjust(aligned);
            printStats(stats);
        }
    }

    @Test
    @DisplayName("仅用SPP中位数替换基站B坐标，基站A保持RTCM1005")
    void testAdjustWithSppBaseBOnly() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("  仅用SPP中位数替换基站B坐标");
        System.out.println("  基站A保持RTCM 1005不变");
        System.out.println("========================================");

        int startHour = 0;
        int endHour = 8;

        double[] rtcmPosA = extractStationPos(Files.readAllBytes(Paths.get(buildFilePath(BASE_A, DATE, 0))));
        double[] rtcmPosB = extractStationPos(Files.readAllBytes(Paths.get(buildFilePath(BASE_B, DATE, 0))));

        double[][] sppStatsB = sppPositionDetailed(BASE_B, startHour, endHour);
        if (sppStatsB == null) {
            System.out.println("跳过：基站B SPP定位失败");
            return;
        }
        double[] sppMedianB = sppStatsB[0];
        double[] sppStdB = sppStatsB[1];

        double[] rtcmLlhA = new double[3], rtcmLlhB = new double[3], sppLlhB = new double[3];
        CoordTransform.ecef2pos(rtcmPosA, rtcmLlhA);
        CoordTransform.ecef2pos(rtcmPosB, rtcmLlhB);
        CoordTransform.ecef2pos(sppMedianB, sppLlhB);

        System.out.printf("%n基站A: 使用RTCM 1005 (不变)%n");
        System.out.printf("  ECEF: X=%.3f Y=%.3f Z=%.3f%n", rtcmPosA[0], rtcmPosA[1], rtcmPosA[2]);
        System.out.printf("  LLH:  Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(rtcmLlhA[0]), Math.toDegrees(rtcmLlhA[1]), rtcmLlhA[2]);

        System.out.printf("%n基站B: RTCM 1005 → SPP中位数%n");
        System.out.printf("  RTCM1005 ECEF: X=%.3f Y=%.3f Z=%.3f%n", rtcmPosB[0], rtcmPosB[1], rtcmPosB[2]);
        System.out.printf("  RTCM1005 LLH:  Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(rtcmLlhB[0]), Math.toDegrees(rtcmLlhB[1]), rtcmLlhB[2]);
        System.out.printf("  SPP中位 ECEF:  X=%.3f Y=%.3f Z=%.3f%n", sppMedianB[0], sppMedianB[1], sppMedianB[2]);
        System.out.printf("  SPP中位 LLH:   Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(sppLlhB[0]), Math.toDegrees(sppLlhB[1]), sppLlhB[2]);
        System.out.printf("  SPP精度(σ):    X=%.4f Y=%.4f Z=%.4f m%n", sppStdB[0], sppStdB[1], sppStdB[2]);

        double dN = (sppLlhB[0] - rtcmLlhB[0]) * 6371000;
        double dE = (sppLlhB[1] - rtcmLlhB[1]) * 6371000 * Math.cos(rtcmLlhB[0]);
        double dU = sppLlhB[2] - rtcmLlhB[2];
        System.out.printf("  SPP-RTCM1005:  dN=%.3f dE=%.3f dU=%.3f m%n", dN, dE, dU);

        PrcOpt optA = createRtkOpt();

        PrcOpt optB = createRtkOpt();
        optB.rb[0] = sppMedianB[0]; optB.rb[1] = sppMedianB[1]; optB.rb[2] = sppMedianB[2];
        optB.refpos = Constants.POSOPT_POS_XYZ;
        optB.intpref = 1;

        RtkProcessor rtkA = new RtkProcessor(optA);
        RtkProcessor rtkB = new RtkProcessor(optB);

        List<SolData> allSolA = new ArrayList<>();
        List<SolData> allSolB = new ArrayList<>();

        for (int hour = startHour; hour <= endHour; hour++) {
            String baseAFile = buildFilePath(BASE_A, DATE, hour);
            String baseBFile = buildFilePath(BASE_B, DATE, hour);
            String roverFile = buildFilePath(ROVER, DATE, hour);

            if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                    || !Files.exists(Paths.get(roverFile))) continue;

            RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);
            rtkA.resetForNextBatch();
            allSolA.addAll(resA.solutions);

            RtkProcessor.RtkResult resB = rtkB.process(roverFile, baseBFile);
            rtkB.resetForNextBatch();
            allSolB.addAll(resB.solutions);
        }

        System.out.printf("%n基线A(RTCM1005基站): total=%d, FIX=%d%n", allSolA.size(), countFixSol(allSolA));
        System.out.printf("基线B(SPP基站):       total=%d, FIX=%d%n", allSolB.size(), countFixSol(allSolB));

        printBaselineCoordinateStats(allSolA, "基线A(RTCM1005)");
        printBaselineCoordinateStats(allSolB, "基线B(SPP)");

        List<EpochPair> aligned = alignByEpoch(allSolA, allSolB);
        System.out.printf("历元对齐: %d 个共同历元%n", aligned.size());

        if (!aligned.isEmpty()) {
            AdjustStats stats = runAdjust(aligned);
            printStats(stats);
        }
    }

    private double[][] sppPositionDetailed(String deviceId, int startHour, int endHour) throws IOException {
        PrcOpt sppOpt = SppProcessor.createDefaultOpt();
        SppProcessor spp = new SppProcessor(sppOpt);

        for (int hour = startHour; hour <= endHour; hour++) {
            String filePath = buildFilePath(deviceId, DATE, hour);
            if (!Files.exists(Paths.get(filePath))) continue;
            byte[] data = Files.readAllBytes(Paths.get(filePath));
            spp.feed(data);
        }
        SppProcessor.SppResult result = spp.finish();

        List<SolData> solList = result.solutions;
        if (solList.size() < 10) return null;

        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), zs = new ArrayList<>();
        for (SolData sd : solList) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) { xs.add(p.v1); ys.add(p.v2); zs.add(p.v3); }
        }
        if (xs.isEmpty()) return null;

        Collections.sort(xs);
        Collections.sort(ys);
        Collections.sort(zs);
        double medianX = xs.get(xs.size() / 2);
        double medianY = ys.get(ys.size() / 2);
        double medianZ = zs.get(zs.size() / 2);

        double varX = 0, varY = 0, varZ = 0;
        int n = xs.size();
        for (int i = 0; i < n; i++) {
            varX += Math.pow(xs.get(i) - medianX, 2);
            varY += Math.pow(ys.get(i) - medianY, 2);
            varZ += Math.pow(zs.get(i) - medianZ, 2);
        }

        System.out.printf("  %s SPP: %d 有效历元, 中位数 ECEF X=%.3f Y=%.3f Z=%.3f%n",
                deviceId, n, medianX, medianY, medianZ);

        return new double[][]{
                {medianX, medianY, medianZ},
                {Math.sqrt(varX / n), Math.sqrt(varY / n), Math.sqrt(varZ / n)}
        };
    }

    @Test
    @DisplayName("交叉验证：测站0002的基线A/B坐标差异")
    void testCrossValidationStation0002() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        String rover2 = ROVER2;
        int hour = 8;

        String baseAFile = buildFilePath(BASE_A, DATE, hour);
        String baseBFile = buildFilePath(BASE_B, DATE, hour);
        String roverFile = buildFilePath(rover2, DATE, hour);

        if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                || !Files.exists(Paths.get(roverFile))) {
            System.out.println("跳过：文件不存在");
            return;
        }

        System.out.printf("%n=== 交叉验证: 测站%s Hour %02d ===%n", rover2, hour);

        PrcOpt opt = createRtkOpt();

        RtkProcessor rtkA = new RtkProcessor(opt);
        RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);
        rtkA.resetForNextBatch();

        RtkProcessor rtkB = new RtkProcessor(opt);
        RtkProcessor.RtkResult resB = rtkB.process(roverFile, baseBFile);

        List<SolData> fixA = resA.solutions.stream().filter(sd -> sd.status == SolutionStatus.FIX).toList();
        List<SolData> fixB = resB.solutions.stream().filter(sd -> sd.status == SolutionStatus.FIX).toList();

        System.out.printf("基线A FIX: %d/%d, 基线B FIX: %d/%d%n",
                fixA.size(), resA.solutions.size(), fixB.size(), resB.solutions.size());

        printBaselineCoordinateStats(resA.solutions, "基线A (" + BASE_A + "→" + rover2 + ")");
        printBaselineCoordinateStats(resB.solutions, "基线B (" + BASE_B + "→" + rover2 + ")");

        if (!fixA.isEmpty() && !fixB.isEmpty()) {
            Position meanA = computeMeanEcef(fixA);
            Position meanB = computeMeanEcef(fixB);
            double dx = meanA.v1 - meanB.v1;
            double dy = meanA.v2 - meanB.v2;
            double dz = meanA.v3 - meanB.v3;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            System.out.printf("%n基线A-B Rover坐标差异: dX=%.4f dY=%.4f dZ=%.4f dist=%.4f m%n", dx, dy, dz, dist);
            System.out.printf("→ 若基站0020坐标修正 dY=+%.4f dZ=+%.4f, 两条基线将一致%n", dy, dz);
        }
    }

    private Position computeMeanEcef(List<SolData> fixSol) {
        double sumX = 0, sumY = 0, sumZ = 0;
        int n = 0;
        for (SolData sd : fixSol) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) { sumX += p.v1; sumY += p.v2; sumZ += p.v3; n++; }
        }
        return new Position(CoordType.ECEF, sumX / n, sumY / n, sumZ / n);
    }

    @Test
    @DisplayName("修正基站0020坐标后的平差验证")
    void testAdjustWithCorrectedBaseB() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        int startHour = 0;
        int endHour = 8;

        PrcOpt optA = createRtkOpt();

        PrcOpt optB = createRtkOpt();
        String baseBFile0 = buildFilePath(BASE_B, DATE, 0);
        if (!Files.exists(Paths.get(baseBFile0))) {
            System.out.println("跳过：基站B文件不存在");
            return;
        }
        byte[] baseBData = Files.readAllBytes(Paths.get(baseBFile0));
        double[] baseBPos = extractStationPos(baseBData);
        if (baseBPos == null) {
            System.out.println("跳过：无法提取基站B坐标");
            return;
        }

        double[] correction = {0.0, 2.80, 3.80};
        optB.rb[0] = baseBPos[0] + correction[0];
        optB.rb[1] = baseBPos[1] + correction[1];
        optB.rb[2] = baseBPos[2] + correction[2];
        optB.refpos = Constants.POSOPT_POS_XYZ;
        optB.intpref = 1;

        System.out.printf("%n=== 修正基站0020坐标后平差 ===%n");
        System.out.printf("基站B原始ECEF: X=%.3f Y=%.3f Z=%.3f%n", baseBPos[0], baseBPos[1], baseBPos[2]);
        double[] llhOrig = new double[3];
        CoordTransform.ecef2pos(baseBPos, llhOrig);
        System.out.printf("  LLH: Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(llhOrig[0]), Math.toDegrees(llhOrig[1]), llhOrig[2]);
        System.out.printf("基站B修正ECEF: X=%.3f Y=%.3f Z=%.3f%n", optB.rb[0], optB.rb[1], optB.rb[2]);
        double[] llhCorr = new double[3];
        CoordTransform.ecef2pos(optB.rb, llhCorr);
        System.out.printf("  LLH: Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(llhCorr[0]), Math.toDegrees(llhCorr[1]), llhCorr[2]);
        System.out.printf("  修正量(NEU): dN=%.2fm dE=%.2fm dU=%.2fm%n",
                (llhCorr[0] - llhOrig[0]) * 6371000,
                (llhCorr[1] - llhOrig[1]) * 6371000 * Math.cos(llhOrig[0]),
                llhCorr[2] - llhOrig[2]);

        RtkProcessor rtkA = new RtkProcessor(optA);
        RtkProcessor rtkB = new RtkProcessor(optB);

        List<SolData> allSolA = new ArrayList<>();
        List<SolData> allSolB = new ArrayList<>();

        for (int hour = startHour; hour <= endHour; hour++) {
            String baseAFile = buildFilePath(BASE_A, DATE, hour);
            String baseBFile = buildFilePath(BASE_B, DATE, hour);
            String roverFile = buildFilePath(ROVER, DATE, hour);

            if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                    || !Files.exists(Paths.get(roverFile))) continue;

            RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);
            rtkA.resetForNextBatch();
            allSolA.addAll(resA.solutions);

            RtkProcessor.RtkResult resB = rtkB.process(roverFile, baseBFile);
            rtkB.resetForNextBatch();
            allSolB.addAll(resB.solutions);
        }

        System.out.printf("基线A: total=%d, FIX=%d%n", allSolA.size(), countFixSol(allSolA));
        System.out.printf("基线B(修正): total=%d, FIX=%d%n", allSolB.size(), countFixSol(allSolB));

        printBaselineCoordinateStats(allSolA, "基线A");
        printBaselineCoordinateStats(allSolB, "基线B(修正)");

        List<EpochPair> aligned = alignByEpoch(allSolA, allSolB);
        System.out.printf("历元对齐: %d 个共同历元%n", aligned.size());

        AdjustStats stats = runAdjust(aligned);
        printStats(stats);
    }

    @Test
    @DisplayName("2基站1测站单小时快速测试")
    void testMultiBaselineAdjustSingleHour() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据目录不存在");
            return;
        }

        int hour = 8;
        String baseAFile = buildFilePath(BASE_A, DATE, hour);
        String baseBFile = buildFilePath(BASE_B, DATE, hour);
        String roverFile = buildFilePath(ROVER, DATE, hour);

        if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                || !Files.exists(Paths.get(roverFile))) {
            System.out.println("跳过：文件不存在");
            return;
        }

        System.out.printf("%n=== 单小时快速测试 (Hour %02d) ===%n", hour);

        PrcOpt opt = createRtkOpt();

        RtkProcessor rtkA = new RtkProcessor(opt);
        RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);

        RtkProcessor rtkB = new RtkProcessor(opt);
        RtkProcessor.RtkResult resB = rtkB.process(roverFile, baseBFile);

        System.out.printf("基线A: total=%d, FIX=%d%n", resA.solutions.size(), countFix(resA));
        System.out.printf("基线B: total=%d, FIX=%d%n", resB.solutions.size(), countFix(resB));

        List<EpochPair> aligned = alignByEpoch(resA.solutions, resB.solutions);
        System.out.printf("历元对齐: %d 个共同历元%n", aligned.size());

        AdjustStats stats = runAdjust(aligned);
        printStats(stats);

        if (!stats.adjustedPositions.isEmpty()) {
            System.out.println("\n--- 平差坐标样本（前10个FIX历元）---");
            int count = 0;
            for (int i = 0; i < aligned.size() && count < 10; i++) {
                EpochPair ep = aligned.get(i);
                if (ep.solA.status == SolutionStatus.FIX && ep.solB.status == SolutionStatus.FIX) {
                    double[] xyz = stats.adjustedPositions.get(count);
                    double[] llh = new double[3];
                    CoordTransform.ecef2pos(xyz, llh);
                    System.out.printf("  %s  Lat=%.9f Lon=%.9f H=%.4f%n",
                            ep.epochTag,
                            Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2]);
                    count++;
                }
            }
        }
    }

    private void printBaseStationCoordinates(String date, int startHour, int endHour) {
        System.out.println("\n--- 基站RTCM 1005天线坐标 ---");
        String[] baseIds = {BASE_A, BASE_B};
        for (String baseId : baseIds) {
            double[] firstPos = null;
            for (int hour = startHour; hour <= endHour; hour++) {
                String filePath = buildFilePath(baseId, date, hour);
                if (!Files.exists(Paths.get(filePath))) continue;

                try {
                    byte[] data = Files.readAllBytes(Paths.get(filePath));
                    double[] pos = extractStationPos(data);
                    if (pos != null) {
                        if (firstPos == null) {
                            firstPos = pos;
                            double[] llh = new double[3];
                            CoordTransform.ecef2pos(pos, llh);
                            System.out.printf("  %s (Hour %02d): ECEF X=%.3f Y=%.3f Z=%.3f%n",
                                    baseId, hour, pos[0], pos[1], pos[2]);
                            System.out.printf("    LLH: Lat=%.9f Lon=%.9f H=%.4f%n",
                                    Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2]);
                        } else {
                            double dx = pos[0] - firstPos[0];
                            double dy = pos[1] - firstPos[1];
                            double dz = pos[2] - firstPos[2];
                            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (dist > 0.01) {
                                System.out.printf("    ⚠ Hour %02d 坐标偏移: dX=%.4f dY=%.4f dZ=%.4f dist=%.4f m%n",
                                        hour, dx, dy, dz, dist);
                            }
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
            if (firstPos == null) {
                System.out.printf("  %s: 未找到1005/1006消息%n", baseId);
            }
        }
    }

    private double[] extractStationPos(byte[] data) {
        final double[][] result = {null};
        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onStation(Sta sta) {
                if (result[0] == null && sta.pos != null
                        && (sta.pos[0] != 0.0 || sta.pos[1] != 0.0 || sta.pos[2] != 0.0)) {
                    result[0] = sta.pos.clone();
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(data, 0, data.length);
        decoder.finish();
        return result[0];
    }

    private void printBaselineCoordinateStats(List<SolData> solutions, String label) {
        List<SolData> fixSol = solutions.stream()
                .filter(sd -> sd.status == SolutionStatus.FIX)
                .toList();
        if (fixSol.isEmpty()) {
            System.out.printf("%s: 无FIX解%n", label);
            return;
        }

        double sumX = 0, sumY = 0, sumZ = 0;
        for (SolData sd : fixSol) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) { sumX += p.v1; sumY += p.v2; sumZ += p.v3; }
        }
        double meanX = sumX / fixSol.size();
        double meanY = sumY / fixSol.size();
        double meanZ = sumZ / fixSol.size();

        double varX = 0, varY = 0, varZ = 0;
        for (SolData sd : fixSol) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) {
                varX += Math.pow(p.v1 - meanX, 2);
                varY += Math.pow(p.v2 - meanY, 2);
                varZ += Math.pow(p.v3 - meanZ, 2);
            }
        }
        double stdX = Math.sqrt(varX / fixSol.size());
        double stdY = Math.sqrt(varY / fixSol.size());
        double stdZ = Math.sqrt(varZ / fixSol.size());

        double[] llh = new double[3];
        CoordTransform.ecef2pos(new double[]{meanX, meanY, meanZ}, llh);

        System.out.printf("%s FIX坐标: N=%d%n", label, fixSol.size());
        System.out.printf("  ECEF均值: X=%.3f Y=%.3f Z=%.3f%n", meanX, meanY, meanZ);
        System.out.printf("  ECEF标准差: σX=%.4f σY=%.4f σZ=%.4f (m)%n", stdX, stdY, stdZ);
        System.out.printf("  LLH均值: Lat=%.9f Lon=%.9f H=%.4f%n",
                Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2]);
    }

    private PrcOpt createRtkOpt() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 3;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_RTCM;
        opt.intpref = 1;
        opt.maxtdiff = 30.0;
        opt.outsingle = 1;
        return opt;
    }

    private String buildFilePath(String station, String date, int hour) {
        return String.format("%s\\%s\\%s\\%d.rtcm3", DATA_ROOT, station, date, hour);
    }

    private int countFix(RtkProcessor.RtkResult result) {
        return (int) result.solutions.stream()
                .filter(sd -> sd.status == SolutionStatus.FIX)
                .count();
    }

    private int countFixSol(List<SolData> solutions) {
        return (int) solutions.stream()
                .filter(sd -> sd.status == SolutionStatus.FIX)
                .count();
    }

    private List<EpochPair> alignByEpoch(List<SolData> solA, List<SolData> solB) {
        Map<String, SolData> mapA = new LinkedHashMap<>();
        for (SolData sd : solA) {
            mapA.put(sd.timeStr, sd);
        }

        List<EpochPair> aligned = new ArrayList<>();
        for (SolData sdB : solB) {
            SolData sdA = mapA.get(sdB.timeStr);
            if (sdA != null) {
                aligned.add(new EpochPair(sdB.timeStr, sdA, sdB));
            }
        }
        return aligned;
    }

    private AdjustStats runAdjust(List<EpochPair> aligned) {
        AdjustStats stats = new AdjustStats();
        stats.totalEpochs = aligned.size();
        int diagCount = 0;

        for (EpochPair ep : aligned) {
            boolean fixA = ep.solA.status == SolutionStatus.FIX;
            boolean fixB = ep.solB.status == SolutionStatus.FIX;

            if (fixA && fixB) stats.bothFix++;
            else if (fixA) stats.onlyAFix++;
            else if (fixB) stats.onlyBFix++;
            else stats.neitherFix++;

            if (!fixA || !fixB) continue;

            try {
                BaselineEpoch.BaselineEntry entryA = buildBaselineEntry("A", ep.solA);
                BaselineEpoch.BaselineEntry entryB = buildBaselineEntry("B", ep.solB);

                if (entryA == null || entryB == null) continue;

                BaselineEpoch epoch = new BaselineEpoch(ep.epochTag,
                        new BaselineEpoch.BaselineEntry[]{entryA, entryB});

                AdjustResult result = GnssBaselineAdjust.adjust(epoch, true);

                if (result.success) {
                    stats.adjustSuccess++;

                    if (!Double.isNaN(result.sigma0)) {
                        stats.sigma0Sum += result.sigma0;
                        stats.sigma0Max = Math.max(stats.sigma0Max, result.sigma0);
                        stats.sigma0Min = Math.min(stats.sigma0Min, result.sigma0);
                    }

                    if (result.baardaT != null) {
                        for (double t : result.baardaT) {
                            stats.maxBaardaT = Math.max(stats.maxBaardaT, t);
                        }
                    }

                    stats.adjustedPositions.add(result.p01Xyz.clone());

                    Position posA = ep.solA.getPosition(CoordType.ECEF);
                    Position posB = ep.solB.getPosition(CoordType.ECEF);
                    if (posA != null) stats.singleABaselinePositions.add(new double[]{posA.v1, posA.v2, posA.v3});
                    if (posB != null) stats.singleBBaselinePositions.add(new double[]{posB.v1, posB.v2, posB.v3});

                    if (diagCount < 3 && posA != null && posB != null) {
                        diagCount++;
                        System.out.printf("%n  [诊断 %d] %s%n", diagCount, ep.epochTag);
                        System.out.printf("    基线A Rover ECEF: X=%.3f Y=%.3f Z=%.3f%n", posA.v1, posA.v2, posA.v3);
                        System.out.printf("    基线B Rover ECEF: X=%.3f Y=%.3f Z=%.3f%n", posB.v1, posB.v2, posB.v3);
                        System.out.printf("    平差后 P01 ECEF:  X=%.3f Y=%.3f Z=%.3f%n", result.p01Xyz[0], result.p01Xyz[1], result.p01Xyz[2]);
                        System.out.printf("    A-B差异(m): dX=%.4f dY=%.4f dZ=%.4f dist=%.4f%n",
                                posA.v1 - posB.v1, posA.v2 - posB.v2, posA.v3 - posB.v3,
                                Math.sqrt(Math.pow(posA.v1-posB.v1,2)+Math.pow(posA.v2-posB.v2,2)+Math.pow(posA.v3-posB.v3,2)));
                        System.out.printf("    σ₀=%.4f, BaardaT=%s%n", result.sigma0,
                                result.baardaT != null ? Arrays.toString(result.baardaT) : "N/A");
                    }
                } else {
                    stats.adjustFail++;
                }
            } catch (Exception e) {
                stats.adjustFail++;
            }
        }

        return stats;
    }

    private BaselineEpoch.BaselineEntry buildBaselineEntry(String baseId, SolData solData) {
        double[] dXyz = CovAssembler.extractBaselineDxyz(solData);
        double[][] cBaseline = CovAssembler.extractCovariance3x3(solData);
        double[][] pRover = CovAssembler.extractCovariance3x3(solData);

        if (dXyz == null || cBaseline == null || pRover == null) return null;

        double qf = BaselineEpoch.BaselineEntry.computeQualityFactor(solData);
        return new BaselineEpoch.BaselineEntry(baseId, solData, dXyz, cBaseline, pRover, qf);
    }

    private void printStats(AdjustStats stats) {
        System.out.println("\n========================================");
        System.out.println("  多基线间接平差结果统计");
        System.out.println("========================================");
        System.out.printf("共同历元总数: %d%n", stats.totalEpochs);
        System.out.printf("双FIX历元:    %d (%.1f%%)%n", stats.bothFix,
                stats.totalEpochs == 0 ? 0 : 100.0 * stats.bothFix / stats.totalEpochs);
        System.out.printf("仅A FIX:      %d%n", stats.onlyAFix);
        System.out.printf("仅B FIX:      %d%n", stats.onlyBFix);
        System.out.printf("均非FIX:      %d%n", stats.neitherFix);
        System.out.println("----------------------------------------");
        System.out.printf("平差成功:     %d%n", stats.adjustSuccess);
        System.out.printf("平差失败:     %d%n", stats.adjustFail);

        if (stats.adjustSuccess > 0) {
            double avgSigma0 = stats.sigma0Sum / stats.adjustSuccess;
            System.out.printf("σ₀ 平均:     %.4f%n", avgSigma0);
            System.out.printf("σ₀ 最小:     %.4f%n", stats.sigma0Min);
            System.out.printf("σ₀ 最大:     %.4f%n", stats.sigma0Max);
            System.out.printf("Baarda T最大: %.4f%n", stats.maxBaardaT);
        }

        if (!stats.adjustedPositions.isEmpty()) {
            System.out.println("----------------------------------------");
            System.out.println("平差后坐标统计 (LLH):");

            double sumLat = 0, sumLon = 0, sumH = 0;
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            double minH = Double.MAX_VALUE, maxH = -Double.MAX_VALUE;

            for (double[] xyz : stats.adjustedPositions) {
                double[] llh = new double[3];
                CoordTransform.ecef2pos(xyz, llh);
                double lat = Math.toDegrees(llh[0]);
                double lon = Math.toDegrees(llh[1]);
                double h = llh[2];
                sumLat += lat; sumLon += lon; sumH += h;
                minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat);
                minLon = Math.min(minLon, lon); maxLon = Math.max(maxLon, lon);
                minH = Math.min(minH, h); maxH = Math.max(maxH, h);
            }

            int n = stats.adjustedPositions.size();
            System.out.printf("  纬度: %.9f ± %.9f (范围: %.9f ~ %.9f)%n",
                    sumLat / n, (maxLat - minLat) / 2, minLat, maxLat);
            System.out.printf("  经度: %.9f ± %.9f (范围: %.9f ~ %.9f)%n",
                    sumLon / n, (maxLon - minLon) / 2, minLon, maxLon);
            System.out.printf("  高程: %.4f ± %.4f (范围: %.4f ~ %.4f)%n",
                    sumH / n, (maxH - minH) / 2, minH, maxH);
        }

        if (!stats.adjustedPositions.isEmpty() && !stats.singleABaselinePositions.isEmpty()) {
            System.out.println("----------------------------------------");
            System.out.println("平差 vs 单基线 坐标差异 (ECEF, mm):");

            double sumDiff = 0, maxDiff = 0;
            int cmpCount = Math.min(stats.adjustedPositions.size(), stats.singleABaselinePositions.size());

            for (int i = 0; i < cmpCount; i++) {
                double[] adj = stats.adjustedPositions.get(i);
                double[] single = stats.singleABaselinePositions.get(i);
                double dx = adj[0] - single[0];
                double dy = adj[1] - single[1];
                double dz = adj[2] - single[2];
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) * 1000;
                sumDiff += dist;
                maxDiff = Math.max(maxDiff, dist);
            }

            System.out.printf("  vs 基线A: 平均=%.2f mm, 最大=%.2f mm%n",
                    sumDiff / cmpCount, maxDiff);

            sumDiff = 0; maxDiff = 0;
            cmpCount = Math.min(stats.adjustedPositions.size(), stats.singleBBaselinePositions.size());

            for (int i = 0; i < cmpCount; i++) {
                double[] adj = stats.adjustedPositions.get(i);
                double[] single = stats.singleBBaselinePositions.get(i);
                double dx = adj[0] - single[0];
                double dy = adj[1] - single[1];
                double dz = adj[2] - single[2];
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) * 1000;
                sumDiff += dist;
                maxDiff = Math.max(maxDiff, dist);
            }

            System.out.printf("  vs 基线B: 平均=%.2f mm, 最大=%.2f mm%n",
                    sumDiff / cmpCount, maxDiff);
        }

        System.out.println("========================================");
    }
}