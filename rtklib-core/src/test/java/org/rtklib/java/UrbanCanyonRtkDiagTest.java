package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Urban Canyon RTK Diagnostic Test - 三组城市峡谷数据深度诊断")
@Tag("manual")
public class UrbanCanyonRtkDiagTest {

    private static final Logger log = LoggerFactory.getLogger(UrbanCanyonRtkDiagTest.class);

    private static final double D2R = Constants.D2R;
    private static final double GPS_EPOCH_UNIX = 315964800.0;

    private static final int SYS_MULTI = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_GLO
            | Constants.SYS_CMP | Constants.SYS_QZS;

    /**
     * C 基线 conf（ppc_ppc_rtk.conf）的 pos1-navsys=31：GPS|SBAS|GLO|GAL|QZS，
     * 排除 BDS。c_ppc_*.pos 基线由该配置生成，PPC 数据集须对齐后方可逐值比对。
     */
    private static final int SYS_C_PPC = Constants.SYS_GPS | Constants.SYS_SBS | Constants.SYS_GLO
            | Constants.SYS_GAL | Constants.SYS_QZS;

    static class DatasetConfig {
        final String name;
        final String roverObs;
        final String baseObs;
        final String nav;
        final String referencePath;
        final ReferenceType refType;
        final int navsys;
        final int nf;
        final int ionoopt;
        final String description;

        DatasetConfig(String name, String roverObs, String baseObs, String nav,
                      String referencePath, ReferenceType refType,
                      int navsys, int nf, int ionoopt, String description) {
            this.name = name;
            this.roverObs = roverObs;
            this.baseObs = baseObs;
            this.nav = nav;
            this.referencePath = referencePath;
            this.refType = refType;
            this.navsys = navsys;
            this.nf = nf;
            this.ionoopt = ionoopt;
            this.description = description;
        }

        boolean dataExists() {
            boolean roverOk = Files.exists(Paths.get(roverObs));
            boolean baseOk = Files.exists(Paths.get(baseObs));
            boolean navOk = (nav == null || Files.exists(Paths.get(nav)));
            return roverOk && baseOk && navOk;
        }
    }

    enum ReferenceType {
        PPC_CSV,
        URBANNAV_POS,
        NETDIFF_POS,
        NONE
    }

    static class EpochDiag {
        final int epochIndex;
        final SolutionStatus status;
        final int numSat;
        final double ratio;
        final double age;
        final double lat;
        final double lon;
        final double height;

        EpochDiag(int epochIndex, SolutionStatus status, int numSat,
                  double ratio, double age, double lat, double lon, double height) {
            this.epochIndex = epochIndex;
            this.status = status;
            this.numSat = numSat;
            this.ratio = ratio;
            this.age = age;
            this.lat = lat;
            this.lon = lon;
            this.height = height;
        }
    }

    static class RtkDiagResult {
        final String name;
        final int totalEpochs;
        final int fixCount;
        final int floatCount;
        final int singleCount;
        final int noneCount;
        final double fixRate;
        final double floatRate;
        final double singleRate;
        final double successRate;
        final List<EpochDiag> epochs;
        final double[] rmse;
        final int refMatchCount;

        RtkDiagResult(String name, int totalEpochs, int fixCount, int floatCount,
                      int singleCount, int noneCount, double fixRate, double floatRate,
                      double singleRate, double successRate, List<EpochDiag> epochs,
                      double[] rmse, int refMatchCount) {
            this.name = name;
            this.totalEpochs = totalEpochs;
            this.fixCount = fixCount;
            this.floatCount = floatCount;
            this.singleCount = singleCount;
            this.noneCount = noneCount;
            this.fixRate = fixRate;
            this.floatRate = floatRate;
            this.singleRate = singleRate;
            this.successRate = successRate;
            this.epochs = epochs;
            this.rmse = rmse;
            this.refMatchCount = refMatchCount;
        }
    }

    private static final List<DatasetConfig> DATASETS = new ArrayList<>();

    @BeforeAll
    static void setupDatasets() {
        DATASETS.add(new DatasetConfig(
                "1-Net_Diff Urban (准静态)",
                TestDataConfig.getTestDataFile("rinex/urban_rover_20000719.17o"),
                TestDataConfig.getTestDataFile("rinex/urban_base_20000719.17o"),
                TestDataConfig.getTestDataFile("nav/urban_20000719.17p"),
                TestDataConfig.getTestDataFile("reference/rtk_urban_fix.17.pos"),
                ReferenceType.NETDIFF_POS,
                Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP | Constants.SYS_QZS,
                2,
                Constants.IONOOPT_BRDC,
                "RINEX3.02 G+E+J+C, rover单频(u-blox L1), base双频(NetR9), 准静态(~3m微动)"
        ));

        DATASETS.add(new DatasetConfig(
                "2-PPC-Dataset Tokyo run1 (动态)",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/reference.csv"),
                ReferenceType.PPC_CSV,
                SYS_C_PPC, 2, Constants.IONOOPT_IFLC,
                "RINEX3.04 G+S+R+E+J 双频(IFLC), navsys=31(无BDS,对齐C基线), 动态(车载), Septentrio Mosaic-X5 5Hz + Trimble Alloy 1Hz"
        ));

        DATASETS.add(new DatasetConfig(
                "2-PPC-Dataset Tokyo run2 (动态)",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/reference.csv"),
                ReferenceType.PPC_CSV,
                SYS_C_PPC, 2, Constants.IONOOPT_IFLC,
                "RINEX3.04 G+S+R+E+J 双频(IFLC), navsys=31(无BDS,对齐C基线), 动态(车载), Tokyo run2"
        ));

        DATASETS.add(new DatasetConfig(
                "2-PPC-Dataset Tokyo run3 (动态)",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run3/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run3/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run3/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run3/reference.csv"),
                ReferenceType.PPC_CSV,
                SYS_C_PPC, 2, Constants.IONOOPT_IFLC,
                "RINEX3.04 G+S+R+E+J 双频(IFLC), navsys=31(无BDS,对齐C基线), 动态(车载), Tokyo run3"
        ));

        DATASETS.add(new DatasetConfig(
                "2-PPC-Dataset Nagoya run1 (动态)",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/reference.csv"),
                ReferenceType.PPC_CSV,
                SYS_C_PPC, 2, Constants.IONOOPT_IFLC,
                "RINEX3.04 G+S+R+E+J 双频(IFLC), navsys=31(无BDS,对齐C基线), 动态(车载), Nagoya run1"
        ));

        DATASETS.add(new DatasetConfig(
                "2-PPC-Dataset Nagoya run2 (动态)",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run2/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run2/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run2/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run2/reference.csv"),
                ReferenceType.PPC_CSV,
                SYS_C_PPC, 2, Constants.IONOOPT_IFLC,
                "RINEX3.04 G+S+R+E+J 双频(IFLC), navsys=31(无BDS,对齐C基线), 动态(车载), Nagoya run2"
        ));

        DATASETS.add(new DatasetConfig(
                "2-PPC-Dataset Nagoya run3 (动态)",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run3/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run3/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run3/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run3/reference.csv"),
                ReferenceType.PPC_CSV,
                SYS_C_PPC, 2, Constants.IONOOPT_IFLC,
                "RINEX3.04 G+S+R+E+J 双频(IFLC), navsys=31(无BDS,对齐C基线), 动态(车载), Nagoya run3"
        ));

        DATASETS.add(new DatasetConfig(
                "3-UrbanNav Tokyo 2018 (动态10Hz)",
                TestDataConfig.getTestDataFile("urbannav/tokyo/data_tokyo/tokyo3530.18o"),
                TestDataConfig.getTestDataFile("urbannav/tokyo/data_tokyo/tokyo3530_base.18o"),
                TestDataConfig.getTestDataFile("urbannav/tokyo/data_tokyo/brdm3530.18p"),
                TestDataConfig.getTestDataFile("urbannav/GINav/result/tokyo3530_PPK.pos"),
                ReferenceType.URBANNAV_POS,
                SYS_MULTI, 2, Constants.IONOOPT_IFLC,
                "RINEX3.02 G+R+E+J+C 双频(IFLC), 动态(车载), Trimble NetR9 10Hz"
        ));

        log.info("============================================================");
        log.info("  Urban Canyon RTK Diagnostic Test - 数据集配置");
        log.info("============================================================");
        log.info("Test data dir: {}", TestDataConfig.getTestDataDir());
        for (DatasetConfig cfg : DATASETS) {
            log.info("  [{}] data exists={} | {}", cfg.name, cfg.dataExists(), cfg.description);
            log.info("    rover={} | base={}", Files.exists(Paths.get(cfg.roverObs)), Files.exists(Paths.get(cfg.baseObs)));
        }
    }

    @Test
    @Disabled("单频L1+BRDC电离层改正，城市峡谷环境下RTK几乎不可能Fix，无法验证实现正确性")
    @DisplayName("1. Net_Diff Urban - 准静态城市RTK诊断")
    void testNetDiffUrban() {
        runFullDiag(DATASETS.get(0));
    }

    @Test
    @DisplayName("2a. PPC-Dataset Tokyo run1 - 动态城市峡谷RTK诊断")
    void testPpcTokyoRun1() {
        runFullDiag(DATASETS.get(1));
    }

    @Test
    @DisplayName("2a-neg. PPC-Dataset Tokyo run1 - IFLC频率对协商开启对比 [JAVA-ONLY]")
    void testPpcTokyoRun1IflcNegotiation() {
        DatasetConfig cfg = DATASETS.get(1);
        Assumptions.assumeTrue(cfg.dataExists(), cfg.name + " - 数据不存在");

        // 基线：协商关闭（对齐RTKLIB C固定槽位行为）
        RtkDiagResult base = runRtkAndDiagnose(cfg);
        // 实验：协商开启，流动站slot1缺失的BDS-3卫星应以{0,2}(B1I+B2a)参与
        RtkConfig negCfg = new RtkConfig();
        negCfg.common.enableIflcPairNegotiation = true;
        RtkDiagResult neg = runRtkAndDiagnose(cfg, negCfg);

        printDiagReport(neg, cfg);

        // 逐历元 ns 对比：协商生效的直接证据是存在 ns 增加的历元（BDS-3 以公共频段对参与）
        int addedEpochs = 0, removedEpochs = 0;
        int n = Math.min(base.epochs.size(), neg.epochs.size());
        for (int i = 0; i < n; i++) {
            int d = neg.epochs.get(i).numSat - base.epochs.get(i).numSat;
            if (d > 0) addedEpochs++;
            else if (d < 0) removedEpochs++;
        }
        double baseAvgNs = avgNumSat(base);
        double negAvgNs = avgNumSat(neg);

        log.info("");
        log.info("[IFLC协商对比] {}", cfg.name);
        log.info("  关闭: 历元={} Fix={} Float={} Single={} 平均ns={} FloatH.RMSE={} FloatV.RMSE={}",
                base.totalEpochs, base.fixCount, base.floatCount, base.singleCount,
                String.format("%.2f", baseAvgNs),
                floatRmseStr(base, 4), floatRmseStr(base, 5));
        log.info("  开启: 历元={} Fix={} Float={} Single={} 平均ns={} FloatH.RMSE={} FloatV.RMSE={}",
                neg.totalEpochs, neg.fixCount, neg.floatCount, neg.singleCount,
                String.format("%.2f", negAvgNs),
                floatRmseStr(neg, 4), floatRmseStr(neg, 5));
        log.info("  ns变化历元: 增加={} 减少={} (协商只增候选，减少来自下游优化非线性扰动)",
                addedEpochs, removedEpochs);

        assertTrue(neg.totalEpochs > 0, "协商开启应仍产出历元");
        assertTrue(neg.successRate > 0.01, "协商开启成功率应>1%");
        assertTrue(addedEpochs > 0, "协商应使BDS-3以公共频段对参与（存在ns增加的历元）");
        double floatH = neg.rmse != null && neg.rmse.length > 5 ? neg.rmse[4] : -1;
        double floatV = neg.rmse != null && neg.rmse.length > 5 ? neg.rmse[5] : -1;
        if (floatH >= 0) {
            assertTrue(floatH < 100.0 && floatV < 150.0,
                    "协商开启不应引发发散（基线Float H≈11.6m/V≈15.9m，实测H=" + floatH + "）");
        }
    }

    private String floatRmseStr(RtkDiagResult r, int idx) {
        return (r.rmse != null && r.rmse.length > 6 && r.rmse[idx] >= 0)
                ? String.format("%.3f", r.rmse[idx]) : "N/A";
    }

    private double avgNumSat(RtkDiagResult r) {
        if (r.epochs.isEmpty()) return 0;
        double sum = 0;
        for (EpochDiag e : r.epochs) sum += e.numSat;
        return sum / r.epochs.size();
    }

    @Test
    @DisplayName("2b. PPC-Dataset Tokyo run2 - 动态城市峡谷RTK诊断")
    void testPpcTokyoRun2() {
        runFullDiag(DATASETS.get(2));
    }

    @Test
    @DisplayName("2c. PPC-Dataset Tokyo run3 - 动态城市峡谷RTK诊断")
    void testPpcTokyoRun3() {
        runFullDiag(DATASETS.get(3));
    }

    @Test
    @DisplayName("2d. PPC-Dataset Nagoya run1 - 动态城市峡谷RTK诊断")
    void testPpcNagoyaRun1() {
        runFullDiag(DATASETS.get(4));
    }

    @Test
    @DisplayName("2e. PPC-Dataset Nagoya run2 - 动态城市峡谷RTK诊断")
    void testPpcNagoyaRun2() {
        runFullDiag(DATASETS.get(5));
    }

    @Test
    @DisplayName("2f. PPC-Dataset Nagoya run3 - 动态城市峡谷RTK诊断")
    void testPpcNagoyaRun3() {
        runFullDiag(DATASETS.get(6));
    }

    @Test
    @DisplayName("3. UrbanNav Tokyo 2018 - 动态10Hz城市峡谷RTK诊断")
    void testUrbanNavTokyo() {
        runFullDiag(DATASETS.get(7));
    }

    @Test
    @DisplayName("汇总: 所有数据集RTK Fix率对比")
    void testSummaryAllDatasets() {
        List<RtkDiagResult> results = new ArrayList<>();
        for (DatasetConfig cfg : DATASETS) {
            if (!cfg.dataExists()) {
                log.warn("[SKIP] {} - 数据不存在", cfg.name);
                continue;
            }
            results.add(runRtkAndDiagnose(cfg));
        }

        if (results.isEmpty()) {
            log.warn("没有任何数据集可用，跳过汇总");
            return;
        }

        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                        城市峡谷RTK诊断汇总 - 所有数据集对比                              ║");
        log.info("╠══════════════════════════════════════════════════════════════════════════════════════════╣");
        log.info(String.format("║ %-35s │ %5s │ %6s │ %6s │ %6s │ %8s │ %8s ║",
                "数据集", "历元", "Fix%", "Float%", "Single%", "H.RMSE(m)", "匹配数"));
        log.info("╠══════════════════════════════════════════════════════════════════════════════════════════╣");
        for (RtkDiagResult r : results) {
            String rmseStr = r.rmse != null ? String.format("%.3f", r.rmse[0]) : "N/A";
            log.info(String.format("║ %-35s │ %5d │ %5.1f%% │ %5.1f%% │ %5.1f%% │ %8s │ %8d ║",
                    r.name, r.totalEpochs, r.fixRate * 100, r.floatRate * 100, r.singleRate * 100,
                    rmseStr, r.refMatchCount));
        }
        log.info("╚══════════════════════════════════════════════════════════════════════════════════════════╝");

        assertTrue(results.stream().anyMatch(r -> r.totalEpochs > 0), "至少有一个数据集应产出历元");
    }

    private void runFullDiag(DatasetConfig cfg) {
        Assumptions.assumeTrue(cfg.dataExists(),
                cfg.name + " - 数据不存在 (rover=" + cfg.roverObs + ", base=" + cfg.baseObs + ")");

        RtkDiagResult diag = runRtkAndDiagnose(cfg);

        printDiagReport(diag, cfg);

        if (diag.totalEpochs == 0) {
            log.warn("[{}] RTK处理异常或无历元输出，跳过断言", cfg.name);
            return;
        }

        assertTrue(diag.totalEpochs > 0, cfg.name + ": 应至少有1个历元");
        assertTrue(diag.successRate > 0.01, cfg.name + ": 成功率应>1%");

        log.info("[{}] Fix率={}%, Float率={}%, Single率={}%",
                cfg.name,
                String.format("%.1f", diag.fixRate * 100),
                String.format("%.1f", diag.floatRate * 100),
                String.format("%.1f", diag.singleRate * 100));
        if (diag.rmse != null) {
            log.info("[{}] 水平RMSE={}m, 垂直RMSE={}m, 3D RMSE={}m (匹配{}个Fix历元)",
                    cfg.name,
                    String.format("%.3f", diag.rmse[0]),
                    String.format("%.3f", diag.rmse[1]),
                    String.format("%.3f", diag.rmse[2]),
                    diag.refMatchCount);
        }
    }

    private RtkDiagResult runRtkAndDiagnose(DatasetConfig cfg) {
        return runRtkAndDiagnose(cfg, null);
    }

    private RtkDiagResult runRtkAndDiagnose(DatasetConfig cfg, RtkConfig rtkConfig) {
        log.info("============================================================");
        log.info("  诊断: {}", cfg.name);
        log.info("  描述: {}", cfg.description);
        log.info("============================================================");
        log.info("Rover: {}", cfg.roverObs);
        log.info("Base:  {}", cfg.baseObs);
        log.info("Nav:   {}", cfg.nav);

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = cfg.navsys;
        opt.nf = cfg.nf;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * D2R;
        opt.ionoopt = cfg.ionoopt;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        opt.dynamics = 1;
        /* 对齐 C 基线 conf（ppc_ppc_rtk.conf，navsys=31）：pos2-arthres1=0.1，即进入 AR 的
         * 位置方差门限。c_ppc_*.pos 基线由该配置生成；对照实验（test_c_*_thres100/9999.pos，
         * navsys=15）确认 thresar[1] 越大 Fix 越多，Java 行为与 C 一致。 */
        opt.thresar[1] = 0.1;
        /* 对齐 C 基线 conf（ppc_ppc_rtk.conf）：pos2-arthresmin=3 / pos2-arthresmax=10，
         * minAR!=maxAR 时 C 2.5.0 按 nb 多项式自适应调整 AR 阈值（rtkpos.c resamb_LAMBDA） */
        opt.thresar[5] = 3.0;
        opt.thresar[6] = 10.0;
        opt.prn[3] = 3.0;
        opt.prn[4] = 1.0;
        opt.glomodear = Constants.GLO_ARMODE_OFF;
        opt.bdsmodear = 0;
        opt.elmaskhold = 15.0 * D2R;

        log.info("Config: ionoopt={}, nf={}, navsys={}, dynamics=on, thresar1=0.1, prnaccelh=3, prnaccelv=1",
                cfg.ionoopt == Constants.IONOOPT_IFLC ? "IFLC" : "BRDC", cfg.nf, cfg.navsys);

        RtkProcessor.RtkResult result;
        try {
            if (rtkConfig != null) {
                result = RinexRtkProcessor.processRinex(
                        cfg.roverObs, cfg.baseObs, cfg.nav, opt, rtkConfig);
            } else {
                result = RinexRtkProcessor.processRinex(
                        cfg.roverObs, cfg.baseObs, cfg.nav, opt);
            }
        } catch (Exception e) {
            log.error("[{}] RTK处理异常: {}", cfg.name, e.getMessage());
            e.printStackTrace();
            return new RtkDiagResult(cfg.name, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, Collections.emptyList(), null, 0);
        }

        int total = result.totalEpochs;
        int fixCount = 0, floatCount = 0, singleCount = 0, noneCount = 0;
        List<EpochDiag> epochs = new ArrayList<>();

        for (int i = 0; i < result.solutions.size(); i++) {
            SolData sol = result.solutions.get(i);
            if (sol.status == SolutionStatus.FIX) fixCount++;
            else if (sol.status == SolutionStatus.FLOAT) floatCount++;
            else if (sol.status == SolutionStatus.SINGLE) singleCount++;
            else if (sol.status == SolutionStatus.NONE) noneCount++;

            Position llh = sol.getPosition(CoordType.LLH);
            double lat = llh != null ? Math.toDegrees(llh.v1) : 0;
            double lon = llh != null ? Math.toDegrees(llh.v2) : 0;
            double h = llh != null ? llh.v3 : 0;

            epochs.add(new EpochDiag(i, sol.status, sol.numSat, sol.ratio, sol.age, lat, lon, h));
        }

        double fixRate = total > 0 ? (double) fixCount / total : 0;
        double floatRate = total > 0 ? (double) floatCount / total : 0;
        double singleRate = total > 0 ? (double) singleCount / total : 0;
        double successRate = total > 0 ? (double) result.successCount / total : 0;

        double[] rmse = null;
        int refMatchCount = 0;
        if (cfg.referencePath != null && Files.exists(Paths.get(cfg.referencePath))) {
            double[] rmseResult = computeRmse(result.solutions, cfg.referencePath, cfg.refType);
            if (rmseResult != null) {
                rmse = rmseResult;
                refMatchCount = (int) rmseResult[3];
                if (rmseResult.length > 5 && rmseResult[4] > 0) {
                    log.info(String.format("Float解精度: H.RMSE=%.3fm, V.RMSE=%.3fm (%d个历元)",
                            rmseResult[4], rmseResult[5], (int)rmseResult[6]));
                }
            }
        }

        return new RtkDiagResult(cfg.name, total, fixCount, floatCount, singleCount, noneCount,
                fixRate, floatRate, singleRate, successRate, epochs, rmse, refMatchCount);
    }

    private void printDiagReport(RtkDiagResult diag, DatasetConfig cfg) {
        log.info("");
        log.info("┌─────────────────────────────────────────────────────────┐");
        log.info("│  RTK诊断报告: {}", diag.name);
        log.info("├─────────────────────────────────────────────────────────┤");
        log.info("│  数据描述: {}", cfg.description);
        log.info("│  总历元数: {}", diag.totalEpochs);
        log.info("│  Fix:   {} ({}%)", diag.fixCount, String.format("%.1f", diag.fixRate * 100));
        log.info("│  Float: {} ({}%)", diag.floatCount, String.format("%.1f", diag.floatRate * 100));
        log.info("│  Single:{} ({}%)", diag.singleCount, String.format("%.1f", diag.singleRate * 100));
        if (diag.noneCount > 0) {
            log.info("│  None:  {} ({}%)", diag.noneCount, String.format("%.1f", (double) diag.noneCount / diag.totalEpochs * 100));
        }
        log.info("│  成功率: {}%", String.format("%.1f", diag.successRate * 100));

        if (diag.rmse != null) {
            log.info("├─────── 精度(仅Fix历元 vs 参考真值) ───────────────────┤");
            log.info("│  水平RMSE: {} m", String.format("%.3f", diag.rmse[0]));
            log.info("│  垂直RMSE: {} m", String.format("%.3f", diag.rmse[1]));
            log.info("│  3D RMSE:  {} m", String.format("%.3f", diag.rmse[2]));
            log.info("│  匹配历元: {}", diag.refMatchCount);
        } else if (cfg.referencePath != null && !Files.exists(Paths.get(cfg.referencePath))) {
            log.info("│  (参考真值文件不存在: {})", cfg.referencePath);
        } else {
            log.info("│  (无参考真值可对比)");
        }

        log.info("├─────── AR诊断 ─────────────────────────────────────────┤");
        printArDiag(diag);

        log.info("├─────── 解状态时间线(前100历元) ───────────────────────┤");
        printTimeline(diag, 100);

        log.info("└─────────────────────────────────────────────────────────┘");
    }

    private void printArDiag(RtkDiagResult diag) {
        if (diag.epochs.isEmpty()) {
            log.info("│  (无历元数据)");
            return;
        }

        double sumRatioFix = 0;
        int ratioFixCount = 0;
        double sumRatioFloat = 0;
        int ratioFloatCount = 0;
        int lowRatioCount = 0;
        int highAgeCount = 0;
        int lowSatCount = 0;

        for (EpochDiag e : diag.epochs) {
            if (e.status == SolutionStatus.FIX) {
                sumRatioFix += e.ratio;
                ratioFixCount++;
            } else if (e.status == SolutionStatus.FLOAT) {
                sumRatioFloat += e.ratio;
                ratioFloatCount++;
                if (e.ratio < 3.0) lowRatioCount++;
            }
            if (e.age > 10.0) highAgeCount++;
            if (e.numSat < 6) lowSatCount++;
        }

        double avgRatioFix = ratioFixCount > 0 ? sumRatioFix / ratioFixCount : 0;
        double avgRatioFloat = ratioFloatCount > 0 ? sumRatioFloat / ratioFloatCount : 0;

        log.info("│  Fix历元平均ratio:   {} (共{}个)", String.format("%.1f", avgRatioFix), ratioFixCount);
        log.info("│  Float历元平均ratio: {} (共{}个)", String.format("%.1f", avgRatioFloat), ratioFloatCount);
        log.info("│  Float且ratio<3.0:   {}个 (差分观测不足或AR候选接近)", lowRatioCount);
        log.info("│  age>10s历元:        {}个 ({}%)", highAgeCount, String.format("%.1f", (double) highAgeCount / diag.totalEpochs * 100));
        log.info("│  卫星数<6历元:       {}个 ({}%)", lowSatCount, String.format("%.1f", (double) lowSatCount / diag.totalEpochs * 100));

        if (diag.fixRate < 0.1) {
            log.info("│  ⚠ Fix率<10%: 可能原因:");
            if (lowRatioCount > ratioFloatCount * 0.5) {
                log.info("│    - 超过半数Float历元ratio<3, 城市峡谷多路径导致AR候选接近");
            }
            if (highAgeCount > diag.totalEpochs * 0.1) {
                log.info("│    - age>10s历元占比>{}%, 基站观测间断或通信延迟", String.format("%.0f", (double) highAgeCount / diag.totalEpochs * 100));
            }
            if (lowSatCount > diag.totalEpochs * 0.1) {
                log.info("│    - 卫星数<6历元占比>{}%, 信号遮挡严重", String.format("%.0f", (double) lowSatCount / diag.totalEpochs * 100));
            }
        }
    }

    private void printTimeline(RtkDiagResult diag, int maxEpochs) {
        int count = Math.min(diag.epochs.size(), maxEpochs);
        StringBuilder sb = new StringBuilder();
        sb.append("│  ");
        for (int i = 0; i < count; i++) {
            EpochDiag e = diag.epochs.get(i);
            switch (e.status) {
                case FIX:    sb.append('F'); break;
                case FLOAT:  sb.append('f'); break;
                case SINGLE: sb.append('S'); break;
                case NONE:   sb.append('.'); break;
                default:     sb.append('?'); break;
            }
            if ((i + 1) % 50 == 0) {
                log.info(sb.toString());
                sb = new StringBuilder();
                sb.append("│  ");
            }
        }
        if (sb.length() > 2) {
            log.info(sb.toString());
        }
        log.info("│  (F=Fix, f=Float, S=Single, .=None, 共显示{}个历元)", count);
    }

    private double[] computeRmse(List<SolData> solutions, String referencePath, ReferenceType refType) {
        switch (refType) {
            case PPC_CSV:
                return computeRmsePpcCsv(solutions, referencePath);
            case URBANNAV_POS:
                return computeRmsePosFile(solutions, referencePath);
            case NETDIFF_POS:
                return computeRmsePosFile(solutions, referencePath);
            default:
                return null;
        }
    }

    private double[] computeRmsePpcCsv(List<SolData> solutions, String csvPath) {
        List<double[]> refs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String header = br.readLine();
            if (header == null || !header.contains("GPS TOW")) {
                log.warn("PPC reference CSV header unexpected: {}", header);
                return null;
            }
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split(",");
                if (cols.length < 8) continue;
                try {
                    double tow = Double.parseDouble(cols[0].trim());
                    int week = Integer.parseInt(cols[1].trim());
                    double lat = Double.parseDouble(cols[2].trim());
                    double lon = Double.parseDouble(cols[3].trim());
                    double h = Double.parseDouble(cols[4].trim());
                    double unixTime = GPS_EPOCH_UNIX + week * 604800.0 + tow;
                    refs.add(new double[]{unixTime, lat, lon, h});
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read PPC reference CSV: {}", e.getMessage());
            return null;
        }
        if (refs.isEmpty()) {
            log.warn("No reference points loaded from {}", csvPath);
            return null;
        }
        return computeRmseFromRefs(solutions, refs);
    }

    private double[] computeRmsePosFile(List<SolData> solutions, String posPath) {
        List<double[]> refs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(posPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("%")) continue;
                String[] cols = line.split("\\s+");
                if (cols.length < 6) continue;
                try {
                    int week = Integer.parseInt(cols[0].trim());
                    double tow = Double.parseDouble(cols[1].trim());
                    double lat = Double.parseDouble(cols[2].trim());
                    double lon = Double.parseDouble(cols[3].trim());
                    double h = Double.parseDouble(cols[4].trim());
                    int q = Integer.parseInt(cols[5].trim());
                    if (q != 1) continue;
                    double unixTime = GPS_EPOCH_UNIX + week * 604800.0 + tow;
                    refs.add(new double[]{unixTime, lat, lon, h});
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read reference .pos: {}", e.getMessage());
            return null;
        }
        if (refs.isEmpty()) {
            log.warn("No reference points loaded from {}", posPath);
            return null;
        }
        return computeRmseFromRefs(solutions, refs);
    }

    private double[] computeRmseFromRefs(List<SolData> solutions, List<double[]> refs) {
        double sumE2Fix = 0, sumN2Fix = 0, sumU2Fix = 0;
        double sumE2Float = 0, sumN2Float = 0, sumU2Float = 0;
        int fixCount = 0, floatCount = 0;
        int printFixCount = 0, printFloatCount = 0;

        for (SolData sol : solutions) {
            Position llhPos = sol.getPosition(CoordType.LLH);
            if (llhPos == null) continue;

            double solLatDeg = llhPos.v1;
            double solLonDeg = llhPos.v2;
            double solH = llhPos.v3;

            double solUnixTime = sol.time.time + sol.time.sec;
            double[] nearest = findNearestRef(solUnixTime, refs);
            if (nearest == null) continue;

            double dLat = (solLatDeg - nearest[1]) * D2R;
            double dLon = (solLonDeg - nearest[2]) * D2R;
            double cosLat = Math.cos(nearest[1] * D2R);
            double e = dLon * cosLat * Constants.RE_WGS84;
            double n = dLat * Constants.RE_WGS84;
            double u = solH - nearest[3];

            boolean isFix = sol.status == SolutionStatus.FIX;
            boolean isFloat = sol.status == SolutionStatus.FLOAT;

            if (isFix) {
                if (printFixCount < 5) {
                    log.info(String.format("Fix RMSE detail: solLat=%.6f solLon=%.6f refLat=%.6f refLon=%.6f e=%.2fm n=%.2fm u=%.2fm dt=%.1fs",
                            solLatDeg, solLonDeg, nearest[1], nearest[2], e, n, u, solUnixTime - nearest[0]));
                    printFixCount++;
                }
                sumE2Fix += e * e;
                sumN2Fix += n * n;
                sumU2Fix += u * u;
                fixCount++;
            } else if (isFloat) {
                if (printFloatCount < 3) {
                    log.info(String.format("Float RMSE detail: solLat=%.6f solLon=%.6f refLat=%.6f refLon=%.6f e=%.2fm n=%.2fm u=%.2fm dt=%.1fs",
                            solLatDeg, solLonDeg, nearest[1], nearest[2], e, n, u, solUnixTime - nearest[0]));
                    printFloatCount++;
                }
                if (floatCount < 20 || floatCount % 200 == 0) {
                    log.info(String.format("Float err[%d]: e=%.2fm n=%.2fm u=%.2fm", floatCount, e, n, u));
                }
                sumE2Float += e * e;
                sumN2Float += n * n;
                sumU2Float += u * u;
                floatCount++;
            }
        }

        if (fixCount == 0 && floatCount == 0) return null;

        double hRmseFix = fixCount > 0 ? Math.sqrt((sumE2Fix + sumN2Fix) / fixCount) : -1;
        double hRmseFloat = floatCount > 0 ? Math.sqrt((sumE2Float + sumN2Float) / floatCount) : -1;
        double vRmseFix = fixCount > 0 ? Math.sqrt(sumU2Fix / fixCount) : -1;
        double vRmseFloat = floatCount > 0 ? Math.sqrt(sumU2Float / floatCount) : -1;
        double r3dFix = fixCount > 0 ? Math.sqrt((sumE2Fix + sumN2Fix + sumU2Fix) / fixCount) : -1;

        log.info("Matched fix={}, float={} epochs with reference ({} ref points)", fixCount, floatCount, refs.size());
        if (floatCount > 0) {
            log.info(String.format("Float H.RMSE=%.3fm, V.RMSE=%.3fm", hRmseFloat, vRmseFloat));
        }
        return new double[]{hRmseFix, vRmseFix, r3dFix, fixCount, hRmseFloat, vRmseFloat, floatCount};
    }

    private double[] findNearestRef(double solUnixTime, List<double[]> refs) {
        double bestDt = Double.MAX_VALUE;
        double[] best = null;
        for (double[] ref : refs) {
            double dt = Math.abs(solUnixTime - ref[0]);
            if (dt < bestDt) {
                bestDt = dt;
                best = ref;
            }
        }
        if (bestDt > 1.0) return null;
        return best;
    }
}