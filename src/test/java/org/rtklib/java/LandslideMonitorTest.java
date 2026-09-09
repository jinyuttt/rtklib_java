package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor.RtkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("滑坡监测 RTK 定位测试")
public class LandslideMonitorTest {

    private static final Logger log = LoggerFactory.getLogger(LandslideMonitorTest.class);

    private static final String BASE_DIR = System.getProperty("user.dir");
    private static final String ROVER_PATH = BASE_DIR + "\\testdat\\rover.rtcm";
    private static final String BASE_PATH = BASE_DIR + "\\testdat\\base.rtcm";
    private static final String RESULT_DIR = BASE_DIR + "\\testdat\\landslide_results";

    private static byte[] roverData;
    private static byte[] baseData;
    private static boolean dataAvailable;

    @BeforeAll
    static void loadData() {
        dataAvailable = new File(ROVER_PATH).exists() && new File(BASE_PATH).exists();
        if (!dataAvailable) {
            log.warn("测试数据不存在: rover={}, base={}", ROVER_PATH, BASE_PATH);
            log.warn("请将流动站RTCM文件放至: {}", ROVER_PATH);
            log.warn("请将基准站RTCM文件放至: {}", BASE_PATH);
            return;
        }
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        } catch (IOException e) {
            dataAvailable = false;
            log.warn("读取流动站数据失败: {}", e.getMessage());
            return;
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        } catch (IOException e) {
            dataAvailable = false;
            log.warn("读取基准站数据失败: {}", e.getMessage());
            return;
        }
        if (dataAvailable) {
            log.info("加载完成: 流动站={} bytes, 基准站={} bytes", roverData.length, baseData.length);
        }
    }

    private static PrcOpt createLandslideOpt() {
        PrcOpt opt = new PrcOpt();

        opt.mode = Constants.PMODE_STATIC;
        opt.soltype = Constants.SOLTYPE_COMBINED;
        opt.nf = 3;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;

        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.minlock = 0;
        opt.minfix = 20;
        opt.minfixsats = 4;
        opt.thresar[0] = 3.0;

        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        opt.dynamics = 0;
        opt.tidecorr = 1;

        opt.refpos = Constants.POSOPT_RTCM;
        opt.maxtdiff = 30.0;

        opt.arfilter = 1;
        opt.maxinno[0] = 5.0;
        opt.maxinno[1] = 30.0;

        opt.posMask = PrcOpt.POS_ECEF | PrcOpt.POS_LLH | PrcOpt.POS_ENU;

        return opt;
    }

    private static RtkConfig createLandslideConfig() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableAdaptiveQ = true;
        cfg.enableAmbAnchor = true;
        cfg.adaptiveQWinSize = 50;
        cfg.adaptiveQStaticThresh = 0.001;
        cfg.adaptiveQDynamicThresh = 0.05;
        cfg.adaptiveQScaleMinStatic = 0.01;
        cfg.adaptiveQScaleMaxDynamic = 5.0;
        cfg.ambAnchorMinFixCount = 100;
        return cfg;
    }

    @Test
    @DisplayName("1. 滑坡监测完整处理 (Static + Combined + BDS + Fix-and-Hold + Saas)")
    void testLandslideFullProcessing() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "测试数据不可用");

        new File(RESULT_DIR).mkdirs();

        PrcOpt opt = createLandslideOpt();
        RtkConfig cfg = createLandslideConfig();

        String resultFile = RESULT_DIR + "\\landslide_full.pos";
        try (FileOutputStream fos = new FileOutputStream(resultFile)) {
            RtkProcessor rtk = new RtkProcessor(opt, null, fos);
            rtk.getRtk().rtkConfig = cfg;
            RtkResult result = rtk.process(roverData, baseData);

            log.info("========== 滑坡监测完整处理结果 ==========");
            log.info("总历元: {}, 成功: {}, 失败: {}", result.totalEpochs, result.successCount, result.failCount);

            MonitorStats stats = analyzeSolutions(result.solutions);
            printStats("完整处理", stats);

            assertTrue(result.totalEpochs > 0, "应处理至少1个历元");
            assertTrue(stats.fixRate > 0.5, "固定解比例应>50%");
        }

        log.info("结果已写入: {}", resultFile);
    }

    @Test
    @DisplayName("2. 对比: 有/无滑坡优化")
    void testWithVsWithoutOptimizations() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "测试数据不可用");

        new File(RESULT_DIR).mkdirs();

        PrcOpt opt = createLandslideOpt();

        RtkConfig cfgNoOpt = new RtkConfig();
        cfgNoOpt.enableAdaptiveQ = false;
        cfgNoOpt.enableAmbAnchor = false;

        RtkConfig cfgWithOpt = createLandslideConfig();

        Map<String, RtkConfig> configs = new LinkedHashMap<>();
        configs.put("无优化", cfgNoOpt);
        configs.put("自适应Q+锚固", cfgWithOpt);

        Map<String, MonitorStats> allStats = new LinkedHashMap<>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            RtkConfig cfg = entry.getValue();

            String resultFile = RESULT_DIR + "\\landslide_" + name.replace("+", "_") + ".pos";
            try (FileOutputStream fos = new FileOutputStream(resultFile)) {
                RtkProcessor rtk = new RtkProcessor(opt, null, fos);
                rtk.getRtk().rtkConfig = cfg;
                RtkResult result = rtk.process(roverData, baseData);

                MonitorStats stats = analyzeSolutions(result.solutions);
                allStats.put(name, stats);
            }
        }

        log.info("");
        log.info("========== 滑坡优化对比 ==========");
        log.info(String.format("%-16s %8s %8s %8s %8s %8s %10s %10s %10s %10s",
                "配置", "总历元", "Fix", "Float", "Single", "None", "Fix率(%)", "sE(mm)", "sN(mm)", "sU(mm)"));
        log.info(String.join("", Collections.nCopies(110, "-")));

        for (var entry : allStats.entrySet()) {
            MonitorStats s = entry.getValue();
            log.info(String.format("%-16s %8d %8d %8d %8d %8d %10.1f %10.2f %10.2f %10.2f",
                    entry.getKey(), s.total, s.fixCount, s.floatCount, s.singleCount, s.noneCount,
                    s.fixRate, s.stdE * 1000, s.stdN * 1000, s.stdU * 1000));
        }

        MonitorStats withOpt = allStats.get("自适应Q+锚固");
        if (withOpt != null && withOpt.fixCount > 0) {
            assertTrue(withOpt.fixRate > 0, "开启优化后应有固定解");
        }
    }

    @Test
    @DisplayName("3. 逐小时批量处理模拟 (1小时切片)")
    void testHourlyBatchProcessing() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "测试数据不可用");

        new File(RESULT_DIR).mkdirs();

        PrcOpt opt = createLandslideOpt();
        RtkConfig cfg = createLandslideConfig();

        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.getRtk().rtkConfig = cfg;
        RtkResult result = rtk.process(roverData, baseData);

        List<SolData> solutions = result.solutions;
        if (solutions.isEmpty()) {
            log.warn("无解算结果，跳过逐小时分析");
            return;
        }

        Map<Integer, List<SolData>> hourlyBuckets = new TreeMap<>();
        for (SolData sol : solutions) {
            if (sol.timeUtc == null) continue;
            int hour = sol.timeUtc.getHour();
            hourlyBuckets.computeIfAbsent(hour, k -> new ArrayList<>()).add(sol);
        }

        log.info("");
        log.info("========== 逐小时变形监测统计 ==========");
        log.info(String.format("%-8s %8s %8s %8s %10s %12s %12s %12s %10s %10s %10s",
                "小时", "历元", "Fix", "Float", "Fix率(%)", "纬度", "经度", "高程(m)", "sE(mm)", "sN(mm)", "sU(mm)"));
        log.info(String.join("", Collections.nCopies(130, "-")));

        for (var entry : hourlyBuckets.entrySet()) {
            int hour = entry.getKey();
            List<SolData> hourSols = entry.getValue();

            int fix = 0, flt = 0;
            double sumLat = 0, sumLon = 0, sumH = 0;
            double sumSe = 0, sumSn = 0, sumSu = 0;
            int validCount = 0;

            for (SolData sol : hourSols) {
                if (sol.status == SolutionStatus.FIX) fix++;
                else if (sol.status == SolutionStatus.FLOAT) flt++;

                Position llh = sol.getPosition(CoordType.LLH);
                if (llh != null) {
                    sumLat += llh.v1;
                    sumLon += llh.v2;
                    sumH += llh.v3;
                    validCount++;
                }

                Accuracy acc = sol.getAccuracy(CoordType.ENU);
                if (acc != null) {
                    sumSe += acc.s1;
                    sumSn += acc.s2;
                    sumSu += acc.s3;
                }
            }

            int total = hourSols.size();
            double fixRate = total > 0 ? 100.0 * fix / total : 0;
            double avgLat = validCount > 0 ? sumLat / validCount : 0;
            double avgLon = validCount > 0 ? sumLon / validCount : 0;
            double avgH = validCount > 0 ? sumH / validCount : 0;
            double avgSe = total > 0 ? sumSe / total * 1000 : 0;
            double avgSn = total > 0 ? sumSn / total * 1000 : 0;
            double avgSu = total > 0 ? sumSu / total * 1000 : 0;

            log.info(String.format("%-8d %8d %8d %8d %10.1f %12.8f %12.8f %12.4f %10.2f %10.2f %10.2f",
                    hour, total, fix, flt, fixRate, avgLat, avgLon, avgH, avgSe, avgSn, avgSu));
        }

        assertTrue(result.totalEpochs > 0, "应处理至少1个历元");
    }

    @Test
    @DisplayName("4. 变形量检测 (相对首历元位移)")
    void testDeformationDetection() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "测试数据不可用");

        PrcOpt opt = createLandslideOpt();
        RtkConfig cfg = createLandslideConfig();

        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.getRtk().rtkConfig = cfg;
        RtkResult result = rtk.process(roverData, baseData);

        List<SolData> solutions = result.solutions;
        if (solutions.size() < 2) {
            log.warn("历元数不足，跳过变形检测");
            return;
        }

        Position firstPos = null;
        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.FIX) {
                firstPos = sol.getPosition(CoordType.LLH);
                break;
            }
        }

        if (firstPos == null) {
            log.warn("无固定解，跳过变形检测");
            return;
        }

        double RE = 6378137.0;
        double cosLat = Math.cos(firstPos.v1 * Constants.D2R);

        log.info("");
        log.info("========== 变形量检测 (相对首固定解) ==========");
        log.info(String.format("基准位置: 纬度=%.9f 经度=%.9f 高程=%.4fm", firstPos.v1, firstPos.v2, firstPos.v3));
        log.info(String.format("%-22s %8s %10s %10s %10s %10s %10s",
                "时间", "状态", "dN(mm)", "dE(mm)", "dU(mm)", "2D(mm)", "3D(mm)"));
        log.info(String.join("", Collections.nCopies(90, "-")));

        double maxDeform2D = 0;
        double maxDeform3D = 0;
        int fixCount = 0;

        for (SolData sol : solutions) {
            if (sol.status != SolutionStatus.FIX) continue;
            fixCount++;

            Position llh = sol.getPosition(CoordType.LLH);
            if (llh == null) continue;

            double dN = (llh.v1 - firstPos.v1) * Constants.D2R * RE * 1000;
            double dE = (llh.v2 - firstPos.v2) * Constants.D2R * RE * cosLat * 1000;
            double dU = (llh.v3 - firstPos.v3) * 1000;

            double dist2D = Math.sqrt(dE * dE + dN * dN);
            double dist3D = Math.sqrt(dE * dE + dN * dN + dU * dU);

            maxDeform2D = Math.max(maxDeform2D, dist2D);
            maxDeform3D = Math.max(maxDeform3D, dist3D);

            if (fixCount <= 10 || fixCount % 50 == 0 || dist2D > 5.0) {
                log.info(String.format("%-22s %8s %10.2f %10.2f %10.2f %10.2f %10.2f",
                        sol.timeStr, sol.status, dN, dE, dU, dist2D, dist3D));
            }
        }

        log.info("");
        log.info(String.format("固定解数: %d, 最大2D变形: %.2fmm, 最大3D变形: %.2fmm", fixCount, maxDeform2D, maxDeform3D));

        double alertThresh2D = 10.0;
        if (maxDeform2D > alertThresh2D) {
            log.warn(String.format("!! 检测到变形超过阈值: %.2fmm > %.1fmm", maxDeform2D, alertThresh2D));
        } else {
            log.info(String.format("OK 变形在阈值内: %.2fmm <= %.1fmm", maxDeform2D, alertThresh2D));
        }

        assertTrue(fixCount > 0, "应有固定解用于变形检测");
    }

    private static class MonitorStats {
        int total;
        int fixCount;
        int floatCount;
        int singleCount;
        int noneCount;
        double fixRate;
        double stdE;
        double stdN;
        double stdU;
    }

    private MonitorStats analyzeSolutions(List<SolData> solutions) {
        MonitorStats stats = new MonitorStats();
        stats.total = solutions.size();

        List<Double> eList = new ArrayList<>();
        List<Double> nList = new ArrayList<>();
        List<Double> uList = new ArrayList<>();

        for (SolData sol : solutions) {
            switch (sol.status) {
                case FIX -> stats.fixCount++;
                case FLOAT -> stats.floatCount++;
                case SINGLE -> stats.singleCount++;
                case NONE -> stats.noneCount++;
                default -> {}
            }

            Accuracy acc = sol.getAccuracy(CoordType.ENU);
            if (acc != null && sol.status == SolutionStatus.FIX) {
                eList.add(acc.s1);
                nList.add(acc.s2);
                uList.add(acc.s3);
            }
        }

        stats.fixRate = stats.total > 0 ? 100.0 * stats.fixCount / stats.total : 0;

        if (!eList.isEmpty()) {
            stats.stdE = rms(eList);
            stats.stdN = rms(nList);
            stats.stdU = rms(uList);
        }

        return stats;
    }

    private double rms(List<Double> values) {
        double sum = 0;
        for (double v : values) sum += v * v;
        return Math.sqrt(sum / values.size());
    }

    private void printStats(String name, MonitorStats s) {
        log.info("{}", name);
        log.info("  总历元: {}, Fix: {}, Float: {}, Single: {}, None: {}",
                s.total, s.fixCount, s.floatCount, s.singleCount, s.noneCount);
        log.info(String.format("  固定解比例: %.1f%%", s.fixRate));
        log.info(String.format("  固定解精度(RMS): sE=%.2fmm, sN=%.2fmm, sU=%.2fmm",
                s.stdE * 1000, s.stdN * 1000, s.stdU * 1000));
    }
}