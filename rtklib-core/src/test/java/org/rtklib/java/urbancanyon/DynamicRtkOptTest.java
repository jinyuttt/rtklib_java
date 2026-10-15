package org.rtklib.java.urbancanyon;

import org.junit.jupiter.api.*;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.coord.CoordTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("动态RTK优化测试 - 8组城市峡谷数据")
public class DynamicRtkOptTest {

    private static final Logger log = LoggerFactory.getLogger(DynamicRtkOptTest.class);
    private static final double D2R = Constants.D2R;
    private static final double GPS_EPOCH_UNIX = 315964800.0;
    private static final int SYS_MULTI = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_GLO
            | Constants.SYS_CMP | Constants.SYS_QZS;

    static class DataSet {
        final String name;
        final String roverObs;
        final String baseObs;
        final String nav;
        final String referencePath;
        final RefType refType;

        DataSet(String name, String roverObs, String baseObs, String nav,
                String referencePath, RefType refType) {
            this.name = name;
            this.roverObs = roverObs;
            this.baseObs = baseObs;
            this.nav = nav;
            this.referencePath = referencePath;
            this.refType = refType;
        }

        boolean exists() {
            return Files.exists(Paths.get(roverObs))
                    && Files.exists(Paths.get(baseObs))
                    && (nav == null || Files.exists(Paths.get(nav)));
        }
    }

    enum RefType { PPC_CSV, URBANNAV_POS, NETDIFF_POS }

    static class RtkResult {
        final String name;
        final String configName;
        final int total;
        final int fix;
        final int floatN;
        final int single;
        final int none;
        final double fixRate;
        final double floatRate;
        final double hRmseFix;
        final double vRmseFix;
        final double hRmseFloat;
        final double vRmseFloat;
        final int fixMatch;
        final int floatMatch;
        final int fixHlt05;
        final int fixHgt1;
        final int fixHgt5;

        RtkResult(String name, String configName, int total, int fix, int floatN, int single, int none,
                  double fixRate, double floatRate,
                  double hRmseFix, double vRmseFix, double hRmseFloat, double vRmseFloat,
                  int fixMatch, int floatMatch,
                  int fixHlt05, int fixHgt1, int fixHgt5) {
            this.name = name;
            this.configName = configName;
            this.total = total;
            this.fix = fix;
            this.floatN = floatN;
            this.single = single;
            this.none = none;
            this.fixRate = fixRate;
            this.floatRate = floatRate;
            this.hRmseFix = hRmseFix;
            this.vRmseFix = vRmseFix;
            this.hRmseFloat = hRmseFloat;
            this.vRmseFloat = vRmseFloat;
            this.fixMatch = fixMatch;
            this.floatMatch = floatMatch;
            this.fixHlt05 = fixHlt05;
            this.fixHgt1 = fixHgt1;
            this.fixHgt5 = fixHgt5;
        }
    }

    private static final String BASE = "D:/code/rtklib_java/test-data";

    static final DataSet[] DATA_SETS = {
        new DataSet("Net_Diff Urban",
                BASE + "/rinex/urban_rover_20000719.17o",
                BASE + "/rinex/urban_base_20000719.17o",
                BASE + "/nav/urban_20000719.17p",
                BASE + "/reference/rtk_urban_fix.17.pos",
                RefType.NETDIFF_POS),
        new DataSet("PPC Tokyo run1",
                BASE + "/ppc/PPC-Dataset/tokyo/run1/rover.obs",
                BASE + "/ppc/PPC-Dataset/tokyo/run1/base.obs",
                BASE + "/ppc/PPC-Dataset/tokyo/run1/base.nav",
                BASE + "/ppc/PPC-Dataset/tokyo/run1/reference.csv",
                RefType.PPC_CSV),
        new DataSet("PPC Tokyo run2",
                BASE + "/ppc/PPC-Dataset/tokyo/run2/rover.obs",
                BASE + "/ppc/PPC-Dataset/tokyo/run2/base.obs",
                BASE + "/ppc/PPC-Dataset/tokyo/run2/base.nav",
                BASE + "/ppc/PPC-Dataset/tokyo/run2/reference.csv",
                RefType.PPC_CSV),
        new DataSet("PPC Tokyo run3",
                BASE + "/ppc/PPC-Dataset/tokyo/run3/rover.obs",
                BASE + "/ppc/PPC-Dataset/tokyo/run3/base.obs",
                BASE + "/ppc/PPC-Dataset/tokyo/run3/base.nav",
                BASE + "/ppc/PPC-Dataset/tokyo/run3/reference.csv",
                RefType.PPC_CSV),
        new DataSet("PPC Nagoya run1",
                BASE + "/ppc/PPC-Dataset/nagoya/run1/rover.obs",
                BASE + "/ppc/PPC-Dataset/nagoya/run1/base.obs",
                BASE + "/ppc/PPC-Dataset/nagoya/run1/base.nav",
                BASE + "/ppc/PPC-Dataset/nagoya/run1/reference.csv",
                RefType.PPC_CSV),
        new DataSet("PPC Nagoya run2",
                BASE + "/ppc/PPC-Dataset/nagoya/run2/rover.obs",
                BASE + "/ppc/PPC-Dataset/nagoya/run2/base.obs",
                BASE + "/ppc/PPC-Dataset/nagoya/run2/base.nav",
                BASE + "/ppc/PPC-Dataset/nagoya/run2/reference.csv",
                RefType.PPC_CSV),
        new DataSet("PPC Nagoya run3",
                BASE + "/ppc/PPC-Dataset/nagoya/run3/rover.obs",
                BASE + "/ppc/PPC-Dataset/nagoya/run3/base.obs",
                BASE + "/ppc/PPC-Dataset/nagoya/run3/base.nav",
                BASE + "/ppc/PPC-Dataset/nagoya/run3/reference.csv",
                RefType.PPC_CSV),
        new DataSet("UrbanNav Tokyo",
                BASE + "/urbannav/tokyo/data_tokyo/tokyo3530.18o",
                BASE + "/urbannav/tokyo/data_tokyo/tokyo3530_base.18o",
                BASE + "/urbannav/tokyo/data_tokyo/brdm3530.18p",
                BASE + "/urbannav/GINav/result/tokyo3530_PPK.pos",
                RefType.URBANNAV_POS),
    };

    @BeforeAll
    static void checkData() {
        log.info("══════════════════════════════════════════════════════════════");
        log.info("  动态RTK优化测试 - 数据集检查");
        log.info("══════════════════════════════════════════════════════════════");
        for (DataSet ds : DATA_SETS) {
            log.info("  {} : {}", ds.name, ds.exists() ? "OK" : "MISSING");
        }
    }

    @Test
    @Disabled("单频L1+BRDC电离层改正，城市峡谷环境下RTK几乎不可能Fix，无法验证实现正确性")
    @DisplayName("1. Net_Diff Urban (C-EX-Ref)")
    void testNetDiffUrban() {
        runOneAndPrint(DATA_SETS[0], CONFIGS[2]);
    }

    @Test
    @DisplayName("2. PPC Tokyo run1 (C-EX-Ref)")
    void testPpcTokyoRun1() {
        runOneAndPrint(DATA_SETS[1], CONFIGS[2]);
    }

    @Test
    @DisplayName("3. PPC Tokyo run2 (C-EX-Ref)")
    void testPpcTokyoRun2() {
        runOneAndPrint(DATA_SETS[2], CONFIGS[2]);
    }

    @Test
    @DisplayName("4. PPC Tokyo run3 (C-EX-Ref)")
    void testPpcTokyoRun3() {
        runOneAndPrint(DATA_SETS[3], CONFIGS[2]);
    }

    @Test
    @DisplayName("5. PPC Nagoya run1 (C-EX-Ref)")
    void testPpcNagoyaRun1() {
        runOneAndPrint(DATA_SETS[4], CONFIGS[2]);
    }

    @Test
    @DisplayName("6. PPC Nagoya run2 (C-EX-Ref)")
    void testPpcNagoyaRun2() {
        runOneAndPrint(DATA_SETS[5], CONFIGS[2]);
    }

    @Test
    @DisplayName("7. PPC Nagoya run3 (C-EX-Ref)")
    void testPpcNagoyaRun3() {
        runOneAndPrint(DATA_SETS[6], CONFIGS[2]);
    }

    @Test
    @DisplayName("8. UrbanNav Tokyo (C-EX-Ref)")
    void testUrbanNavTokyo() {
        runOneAndPrint(DATA_SETS[7], CONFIGS[2]);
    }

    @Test
    @DisplayName("优化对比: Nagoya run1 C-EX-Ref vs 各优化(逐个)")
    void testOptComparisonNagoyaRun1() {
        DataSet ds = DATA_SETS[4];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : CONFIGS) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("优化对比: Tokyo run2 C-EX-Ref vs 各优化(逐个)")
    void testOptComparisonTokyoRun2() {
        DataSet ds = DATA_SETS[2];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : CONFIGS) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("优化对比: Tokyo run1 C-EX-Ref vs 各优化(逐个)")
    void testOptComparisonTokyoRun1() {
        DataSet ds = DATA_SETS[1];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : CONFIGS) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("补充对比: Nagoya run1 - M-ParRef & N-ParamNoise")
    void testSupplementNagoyaRun1() {
        DataSet ds = DATA_SETS[4];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] subset = {CONFIGS[2], CONFIGS[12], CONFIGS[13]};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : subset) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("优化对比: Nagoya run2 C-EX-Ref vs 各优化(逐个)")
    void testOptComparisonNagoyaRun2() {
        DataSet ds = DATA_SETS[5];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : CONFIGS) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("补充对比: Tokyo run2 - M-ParRef & N-ParamNoise")
    void testSupplementTokyoRun2() {
        DataSet ds = DATA_SETS[2];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] subset = {CONFIGS[2], CONFIGS[12], CONFIGS[13]};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : subset) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("优化对比: Tokyo run3 C-EX-Ref vs 各优化(逐个)")
    void testOptComparisonTokyoRun3() {
        DataSet ds = DATA_SETS[3];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : CONFIGS) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("关键对比: Tokyo run3 - C/F/G/H (反转验证)")
    void testKeyComparisonTokyoRun3() {
        DataSet ds = DATA_SETS[3];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] subset = {CONFIGS[2], CONFIGS[5], CONFIGS[6], CONFIGS[7]};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : subset) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    @Test
    @DisplayName("优化对比: UrbanNav Tokyo C-EX-Ref vs 各优化(逐个)")
    void testOptComparisonUrbanNavTokyo() {
        DataSet ds = DATA_SETS[7];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : CONFIGS) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    // ══════════════════════════════════════════════════════════════
    // IFLC 归因验证：脱离 IFLC 后重跑关键变体
    // ══════════════════════════════════════════════════════════════

    static final ConfigVariant CV_EST_REF = new ConfigVariant("C-EST-Ref", "EST电离层+EX参数") {
        void apply(PrcOpt opt) {
            opt.dynamics = 1;
            opt.thresar[1] = 0.9999;
            opt.prn[3] = 3.0;
            opt.prn[4] = 1.0;
            opt.glomodear = Constants.GLO_ARMODE_OFF;
            opt.bdsmodear = 0;
            opt.elmaskhold = 15.0 * D2R;
            opt.thresar[6] = 10.0;
        }
    };

    static final ConfigVariant CV_OFF_REF = new ConfigVariant("C-OFF-Ref", "OFF电离层+EX参数") {
        void apply(PrcOpt opt) {
            opt.dynamics = 1;
            opt.thresar[1] = 0.9999;
            opt.prn[3] = 3.0;
            opt.prn[4] = 1.0;
            opt.glomodear = Constants.GLO_ARMODE_OFF;
            opt.bdsmodear = 0;
            opt.elmaskhold = 15.0 * D2R;
            opt.thresar[6] = 10.0;
        }
    };

    static final ConfigVariant CV_EST_ADAPQ = new ConfigVariant("F-EST-AdapQ", "EST+自适应Q") {
        void apply(PrcOpt opt) { CV_EST_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableAdaptiveQ = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_EST_IGGIII = new ConfigVariant("G-EST-IGGIII", "EST+抗差IGGIII") {
        void apply(PrcOpt opt) { CV_EST_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableIggiii = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_EST_RESEDIT = new ConfigVariant("H-EST-ResEdit", "EST+残差编辑") {
        void apply(PrcOpt opt) { CV_EST_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableResidualEdit = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_EST_SNMED = new ConfigVariant("K-EST-SnrMed", "EST+SNR中值定权") {
        void apply(PrcOpt opt) { CV_EST_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableSnrMedian = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_EST_PARAMNOISE = new ConfigVariant("N-EST-ParamNoise", "EST+参数类型级过程噪声") {
        void apply(PrcOpt opt) { CV_EST_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableParamTypeNoise = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_EST_IONOTROP = new ConfigVariant("O-EST-IonoTrop", "EST+电离层/对流层梯度") {
        void apply(PrcOpt opt) { CV_EST_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableIonoTropGradient = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_OFF_ADAPQ = new ConfigVariant("F-OFF-AdapQ", "OFF+自适应Q") {
        void apply(PrcOpt opt) { CV_OFF_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableAdaptiveQ = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_OFF_IGGIII = new ConfigVariant("G-OFF-IGGIII", "OFF+抗差IGGIII") {
        void apply(PrcOpt opt) { CV_OFF_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableIggiii = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_OFF_RESEDIT = new ConfigVariant("H-OFF-ResEdit", "OFF+残差编辑") {
        void apply(PrcOpt opt) { CV_OFF_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableResidualEdit = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_OFF_SNMED = new ConfigVariant("K-OFF-SnrMed", "OFF+SNR中值定权") {
        void apply(PrcOpt opt) { CV_OFF_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableSnrMedian = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_OFF_BOOTSTRAP = new ConfigVariant("B-OFF-Boot", "OFF+Bootstrapping") {
        void apply(PrcOpt opt) { CV_OFF_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableBootstrapping = true;
            cfg.bootstrappingMinSuccess = 0.99;
            cfg.bootstrappingWithRatio = true;
            return cfg;
        }
    };

    static final ConfigVariant CV_OFF_ADAPQ_BOOT = new ConfigVariant("FB-OFF-AdQ+Boot", "OFF+AdapQ+Bootstrapping") {
        void apply(PrcOpt opt) { CV_OFF_REF.apply(opt); }
        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableAdaptiveQ = true;
            cfg.enableBootstrapping = true;
            cfg.bootstrappingMinSuccess = 0.99;
            cfg.bootstrappingWithRatio = true;
            return cfg;
        }
    };

    @Test
    @DisplayName("IFLC归因: Nagoya run1 - EST基准+关键变体")
    void testIflcAttributionNagoyaRun1() {
        DataSet ds = DATA_SETS[4];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] variants = {CV_EST_REF, CV_EST_ADAPQ, CV_EST_IGGIII,
                CV_EST_RESEDIT, CV_EST_SNMED, CV_EST_PARAMNOISE, CV_EST_IONOTROP};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : variants) {
            results.add(runOneWithIono(ds, cv, Constants.IONOOPT_EST));
        }
        printDatasetComparison(ds.name + " [EST]", results);
    }

    @Test
    @DisplayName("IFLC归因: Nagoya run1 - EST thresar[1]扫描(0.25/0.5/0.9999)")
    void testIflcAttributionNagoyaRun1ThresarScan() {
        DataSet ds = DATA_SETS[4];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        double[] thresars = {0.25, 0.5, 0.9999};
        List<RtkResult> results = new ArrayList<>();
        for (double t : thresars) {
            final double thv = t;
            ConfigVariant cv = new ConfigVariant("C-EST-t" + t, "EST thresar1=" + t) {
                void apply(PrcOpt opt) {
                    opt.dynamics = 1;
                    opt.thresar[1] = thv;
                    opt.prn[3] = 3.0;
                    opt.prn[4] = 1.0;
                    opt.glomodear = Constants.GLO_ARMODE_OFF;
                    opt.bdsmodear = 0;
                    opt.elmaskhold = 15.0 * D2R;
                    opt.thresar[6] = 10.0;
                }
            };
            results.add(runOneWithIono(ds, cv, Constants.IONOOPT_EST));
        }
        printDatasetComparison(ds.name + " [EST thresar扫描]", results);
    }

    @Test
    @DisplayName("IFLC归因: Tokyo run2 - EST基准+关键变体")
    void testIflcAttributionTokyoRun2() {
        DataSet ds = DATA_SETS[2];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] variants = {CV_EST_REF, CV_EST_ADAPQ, CV_EST_IGGIII,
                CV_EST_RESEDIT, CV_EST_SNMED, CV_EST_PARAMNOISE, CV_EST_IONOTROP};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : variants) {
            results.add(runOneWithIono(ds, cv, Constants.IONOOPT_EST));
        }
        printDatasetComparison(ds.name + " [EST]", results);
    }

    @Test
    @DisplayName("IFLC归因: Tokyo run1 - EST基准+关键变体")
    void testIflcAttributionTokyoRun1() {
        DataSet ds = DATA_SETS[1];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] variants = {CV_EST_REF, CV_EST_ADAPQ, CV_EST_IGGIII,
                CV_EST_RESEDIT, CV_EST_SNMED, CV_EST_PARAMNOISE, CV_EST_IONOTROP};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : variants) {
            results.add(runOneWithIono(ds, cv, Constants.IONOOPT_EST));
        }
        printDatasetComparison(ds.name + " [EST]", results);
    }

    @Test
    @DisplayName("IFLC归因: UrbanNav - OFF基准+关键变体")
    void testIflcAttributionUrbanNav() {
        DataSet ds = DATA_SETS[7];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] variants = {CV_OFF_REF, CV_OFF_ADAPQ, CV_OFF_IGGIII,
                CV_OFF_RESEDIT, CV_OFF_SNMED};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : variants) {
            results.add(runOneWithIono(ds, cv, Constants.IONOOPT_OFF));
        }
        printDatasetComparison(ds.name + " [OFF]", results);
    }

    @Test
    @DisplayName("UrbanNav false fix诊断: IFLC vs OFF Fix精度分布")
    void testUrbanNavFalseFixDiagnosis() {
        DataSet ds = DATA_SETS[7];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] variants = {CV_OFF_REF, CV_OFF_ADAPQ, CV_OFF_IGGIII, CV_OFF_SNMED};
        int[] ionoOpts = {Constants.IONOOPT_IFLC, Constants.IONOOPT_OFF};
        String[] ionoNames = {"IFLC", "OFF"};
        for (int j = 0; j < ionoOpts.length; j++) {
            List<RtkResult> results = new ArrayList<>();
            for (ConfigVariant cv : variants) {
                results.add(runOneWithIono(ds, cv, ionoOpts[j]));
            }
            System.out.println();
            System.out.println("═══ " + ds.name + " [" + ionoNames[j] + "] Fix精度诊断 ═══");
            for (RtkResult r : results) {
                System.out.printf("  %s: Fix=%d(%.1f%%), H<0.5m=%d(%.1f%%), H>1m=%d(%.1f%%), H>5m=%d(%.1f%%), H.RMSE=%.3fm%n",
                        r.configName, r.fix, r.fixRate * 100,
                        r.fixHlt05, r.fix > 0 ? r.fixHlt05 * 100.0 / r.fix : 0,
                        r.fixHgt1, r.fix > 0 ? r.fixHgt1 * 100.0 / r.fix : 0,
                        r.fixHgt5, r.fix > 0 ? r.fixHgt5 * 100.0 / r.fix : 0,
                        r.hRmseFix > 0 ? r.hRmseFix : Double.NaN);
            }
        }
    }

    @Test
    @DisplayName("Bootstrapping验证: UrbanNav OFF false fix抑制")
    void testBootstrappingFalseFixReduction() {
        DataSet ds = DATA_SETS[7];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        ConfigVariant[] variants = {CV_OFF_REF, CV_OFF_BOOTSTRAP, CV_OFF_ADAPQ, CV_OFF_ADAPQ_BOOT};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : variants) {
            results.add(runOneWithIono(ds, cv, Constants.IONOOPT_OFF));
        }
        System.out.println();
        System.out.println("═══ " + ds.name + " [OFF] Bootstrapping false fix抑制验证 ═══");
        System.out.println("  对照: 无Bootstrapping vs 有Bootstrapping, 看H>1m比例是否显著下降");
        for (RtkResult r : results) {
            double trueFixPct = r.fix > 0 ? r.fixHlt05 * 100.0 / r.fix : 0;
            double falseFixPct = r.fix > 0 ? r.fixHgt1 * 100.0 / r.fix : 0;
            System.out.printf("  %s: Fix=%d(%.1f%%), H<0.5m=%d(%.1f%%), H>1m=%d(%.1f%%), H>5m=%d(%.1f%%), H.RMSE=%.3fm%n",
                    r.configName, r.fix, r.fixRate * 100,
                    r.fixHlt05, trueFixPct,
                    r.fixHgt1, falseFixPct,
                    r.fixHgt5, r.fix > 0 ? r.fixHgt5 * 100.0 / r.fix : 0,
                    r.hRmseFix > 0 ? r.hRmseFix : Double.NaN);
        }
    }

    @Test
    @DisplayName("thresar扫描: UrbanNav OFF - Ratio阈值(thresar[0])和posvar阈值(thresar[1])对false fix的影响")
    void testUrbanNavOffThresarScan() {
        DataSet ds = DATA_SETS[7];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        System.out.println();
        System.out.println("═══ " + ds.name + " [OFF] thresar[0] (Ratio阈值) 扫描 ═══");
        double[] ratioValues = {3.0, 5.0, 7.0, 10.0, 15.0, 20.0};
        for (double r : ratioValues) {
            ConfigVariant cv = new ConfigVariant("C-OFF-R" + (int) r, "OFF ratio=" + r) {
                void apply(PrcOpt opt) {
                    CV_OFF_REF.apply(opt);
                    opt.thresar[0] = r;
                    opt.thresar[5] = r;
                    opt.thresar[6] = r;
                }
            };
            RtkResult res = runOneWithIono(ds, cv, Constants.IONOOPT_OFF);
            double trueFixPct = res.fix > 0 ? res.fixHlt05 * 100.0 / res.fix : 0;
            double falseFixPct = res.fix > 0 ? res.fixHgt1 * 100.0 / res.fix : 0;
            System.out.printf("  %s: Fix=%d(%.1f%%), H<0.5m=%d(%.1f%%), H>1m=%d(%.1f%%), H>5m=%d(%.1f%%), H.RMSE=%.3fm%n",
                    res.configName, res.fix, res.fixRate * 100,
                    res.fixHlt05, trueFixPct,
                    res.fixHgt1, falseFixPct,
                    res.fixHgt5, res.fix > 0 ? res.fixHgt5 * 100.0 / res.fix : 0,
                    res.hRmseFix > 0 ? res.hRmseFix : Double.NaN);
        }
        System.out.println();
        System.out.println("═══ " + ds.name + " [OFF] thresar[1] (posvar阈值) 扫描 ═══");
        double[] posvarValues = {0.01, 0.1, 0.5, 0.9999};
        for (double pv : posvarValues) {
            ConfigVariant cv = new ConfigVariant("C-OFF-PV" + pv, "OFF posvar<" + pv) {
                void apply(PrcOpt opt) {
                    CV_OFF_REF.apply(opt);
                    opt.thresar[1] = pv;
                }
            };
            RtkResult res = runOneWithIono(ds, cv, Constants.IONOOPT_OFF);
            double trueFixPct = res.fix > 0 ? res.fixHlt05 * 100.0 / res.fix : 0;
            double falseFixPct = res.fix > 0 ? res.fixHgt1 * 100.0 / res.fix : 0;
            System.out.printf("  %s: Fix=%d(%.1f%%), H<0.5m=%d(%.1f%%), H>1m=%d(%.1f%%), H>5m=%d(%.1f%%), H.RMSE=%.3fm%n",
                    res.configName, res.fix, res.fixRate * 100,
                    res.fixHlt05, trueFixPct,
                    res.fixHgt1, falseFixPct,
                    res.fixHgt5, res.fix > 0 ? res.fixHgt5 * 100.0 / res.fix : 0,
                    res.hRmseFix > 0 ? res.hRmseFix : Double.NaN);
        }
    }

    @Test
    @DisplayName("连续性判据验证: UrbanNav OFF - 连续N历元Fix过滤对false fix的影响")
    void testContinuityFilterFalseFixReduction() {
        DataSet ds = DATA_SETS[7];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = SYS_MULTI;
        opt.nf = 2;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * D2R;
        opt.ionoopt = Constants.IONOOPT_OFF;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        CV_OFF_REF.apply(opt);
        RtkConfig cfg = CV_OFF_REF.createRtkConfig();

        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(
                ds.roverObs, ds.baseObs, ds.nav, opt, cfg);

        List<double[]> refs = loadReference(ds.referencePath, ds.refType);
        if (refs.isEmpty()) {
            log.warn("参考真值不可用，跳过连续性判据验证");
            return;
        }

        List<SolData> solutions = result.solutions;
        int total = solutions.size();

        boolean[] isFix = new boolean[total];
        double[] hErr = new double[total];
        double[] posE = new double[total];
        double[] posN = new double[total];
        for (int i = 0; i < total; i++) {
            SolData sol = solutions.get(i);
            isFix[i] = sol.status == SolutionStatus.FIX;
            Position llhPos = sol.getPosition(CoordType.LLH);
            if (llhPos != null) {
                posE[i] = llhPos.v2 * D2R * Math.cos(llhPos.v1 * D2R) * Constants.RE_WGS84;
                posN[i] = llhPos.v1 * D2R * Constants.RE_WGS84;
                double[] nearest = findNearestRef(sol.time.time + sol.time.sec, refs);
                if (nearest != null) {
                    double dLat = (llhPos.v1 - nearest[1]) * D2R;
                    double dLon = (llhPos.v2 - nearest[2]) * D2R;
                    double cosLat = Math.cos(nearest[1] * D2R);
                    double e = dLon * cosLat * Constants.RE_WGS84;
                    double n = dLat * Constants.RE_WGS84;
                    hErr[i] = Math.sqrt(e * e + n * n);
                } else {
                    hErr[i] = -1;
                }
            } else {
                hErr[i] = -1;
            }
        }

        List<int[]> segments = identifyFixSegments(isFix, total);

        System.out.println();
        System.out.println("═══ " + ds.name + " [OFF] 连续性判据验证 ═══");

        System.out.println();
        System.out.println("  ── Fix段分析 ──");
        System.out.printf("  总Fix历元=%d, Fix段数=%d%n",
                (int) IntStream.range(0, total).filter(i -> isFix[i]).count(), segments.size());
        int trueSeg = 0, falseSeg = 0, mixedSeg = 0;
        for (int[] seg : segments) {
            int start = seg[0], end = seg[1];
            int lt05 = 0, gt1 = 0, valid = 0;
            for (int j = start; j <= end; j++) {
                if (hErr[j] >= 0) { valid++; if (hErr[j] < 0.5) lt05++; if (hErr[j] > 1.0) gt1++; }
            }
            if (valid > 0) {
                if (gt1 == 0) trueSeg++;
                else if (lt05 == 0) falseSeg++;
                else mixedSeg++;
            }
        }
        System.out.printf("  段分类: true-fix段=%d(全H<0.5m), false-fix段=%d(全H>1m), 混合段=%d%n",
                trueSeg, falseSeg, mixedSeg);

        System.out.println();
        System.out.println("  ── Fix段长度分布 ──");
        System.out.println("  长度范围     | 段数 | true-fix | false-fix | 混合 | 总历元 | true% | false%");
        int[][] lenRanges = {{1,1},{2,2},{3,5},{6,10},{11,20},{21,50},{51,100},{101,500},{501,10000}};
        for (int[] range : lenRanges) {
            int cnt = 0, tCnt = 0, fCnt = 0, mCnt = 0, epochCnt = 0;
            for (int[] seg : segments) {
                int len = seg[1] - seg[0] + 1;
                if (len >= range[0] && len <= range[1]) {
                    cnt++;
                    epochCnt += len;
                    int lt05 = 0, gt1 = 0, valid = 0;
                    for (int j = seg[0]; j <= seg[1]; j++) {
                        if (hErr[j] >= 0) { valid++; if (hErr[j] < 0.5) lt05++; if (hErr[j] > 1.0) gt1++; }
                    }
                    if (valid > 0) {
                        if (gt1 == 0) tCnt++;
                        else if (lt05 == 0) fCnt++;
                        else mCnt++;
                    }
                }
            }
            if (cnt > 0) {
                String rangeStr = range[0] == range[1] ? String.format("%d", range[0]) : String.format("%d-%d", range[0], range[1]);
                System.out.printf("  %-12s | %4d | %8d | %9d | %4d | %6d | %5.1f%% | %5.1f%%%n",
                        rangeStr, cnt, tCnt, fCnt, mCnt, epochCnt,
                        cnt > 0 ? tCnt * 100.0 / cnt : 0,
                        cnt > 0 ? fCnt * 100.0 / cnt : 0);
            }
        }

        System.out.println();
        System.out.println("  ── 段级H误差分布(每段平均H误差) ──");
        System.out.println("  段平均H范围  | 段数 | 总历元 | 占Fix历元%");
        double[][] hRanges = {{0, 0.5}, {0.5, 1.0}, {1.0, 2.0}, {2.0, 5.0}, {5.0, 10.0}, {10.0, 1000.0}};
        int totalFixEpochs = 0;
        for (int i = 0; i < total; i++) if (isFix[i]) totalFixEpochs++;
        for (double[] range : hRanges) {
            int cnt = 0, epochCnt = 0;
            for (int[] seg : segments) {
                double sumH = 0; int valid = 0;
                for (int j = seg[0]; j <= seg[1]; j++) {
                    if (hErr[j] >= 0) { sumH += hErr[j]; valid++; }
                }
                double avgH = valid > 0 ? sumH / valid : -1;
                if (avgH >= range[0] && avgH < range[1]) {
                    cnt++;
                    epochCnt += seg[1] - seg[0] + 1;
                }
            }
            if (cnt > 0) {
                System.out.printf("  [%.1f,%.1f)m  | %4d | %6d | %5.1f%%%n",
                        range[0], range[1], cnt, epochCnt,
                        totalFixEpochs > 0 ? epochCnt * 100.0 / totalFixEpochs : 0);
            }
        }

        System.out.println();
        System.out.println("  ── 策略1: 仅信任连续≥N个历元的Fix段 ──");
        int[] minLenValues = {1, 2, 3, 5, 10, 20, 50};
        for (int minLen : minLenValues) {
            boolean[] trusted = applyContinuityFilter(isFix, posE, posN, total, minLen, Double.MAX_VALUE);
            int fixCount = 0, fixHlt05 = 0, fixHgt1 = 0, fixHgt5 = 0;
            double sumH2 = 0;
            for (int i = 0; i < total; i++) {
                if (trusted[i]) {
                    fixCount++;
                    if (hErr[i] >= 0) {
                        sumH2 += hErr[i] * hErr[i];
                        if (hErr[i] < 0.5) fixHlt05++;
                        if (hErr[i] > 1.0) fixHgt1++;
                        if (hErr[i] > 5.0) fixHgt5++;
                    }
                }
            }
            double fixRate = total > 0 ? (double) fixCount / total : 0;
            double hRmse = fixCount > 0 ? Math.sqrt(sumH2 / fixCount) : -1;
            double trueFixPct = fixCount > 0 ? fixHlt05 * 100.0 / fixCount : 0;
            double falseFixPct = fixCount > 0 ? fixHgt1 * 100.0 / fixCount : 0;
            System.out.printf("  N≥%2d: Fix=%d(%.1f%%), H<0.5m=%.1f%%, H>1m=%.1f%%, H.RMSE=%.3fm%n",
                    minLen, fixCount, fixRate * 100, trueFixPct, falseFixPct,
                    hRmse > 0 ? hRmse : Double.NaN);
        }

        System.out.println();
        System.out.println("  ── 策略2: 连续≥N历元 + 位置跳变<阈值 ──");
        double[] jumpThreshValues = {0.05, 0.02, 0.01};
        for (double jumpThresh : jumpThreshValues) {
            System.out.printf("  jump<%.2fm/epoch:%n", jumpThresh);
            for (int minLen : new int[]{1, 5, 10, 20}) {
                boolean[] trusted = applyContinuityFilter(isFix, posE, posN, total, minLen, jumpThresh);
                int fixCount = 0, fixHlt05 = 0, fixHgt1 = 0, fixHgt5 = 0;
                double sumH2 = 0;
                for (int i = 0; i < total; i++) {
                    if (trusted[i]) {
                        fixCount++;
                        if (hErr[i] >= 0) {
                            sumH2 += hErr[i] * hErr[i];
                            if (hErr[i] < 0.5) fixHlt05++;
                            if (hErr[i] > 1.0) fixHgt1++;
                            if (hErr[i] > 5.0) fixHgt5++;
                        }
                    }
                }
                double fixRate = total > 0 ? (double) fixCount / total : 0;
                double hRmse = fixCount > 0 ? Math.sqrt(sumH2 / fixCount) : -1;
                double trueFixPct = fixCount > 0 ? fixHlt05 * 100.0 / fixCount : 0;
                double falseFixPct = fixCount > 0 ? fixHgt1 * 100.0 / fixCount : 0;
                System.out.printf("    N≥%2d: Fix=%d(%.1f%%), H<0.5m=%.1f%%, H>1m=%.1f%%, H.RMSE=%.3fm%n",
                        minLen, fixCount, fixRate * 100, trueFixPct, falseFixPct,
                        hRmse > 0 ? hRmse : Double.NaN);
            }
        }

        System.out.println();
        System.out.println("  ── 策略3: 段内一致性 - 段内位置标准差<阈值 ──");
        System.out.println("  段# | 长度 | 段内σ(m) | 段平均H(m) | 分类");
        for (int si = 0; si < segments.size(); si++) {
            int[] seg = segments.get(si);
            int start = seg[0], end = seg[1];
            int n = end - start + 1;
            double sumE = 0, sumN = 0, sumH = 0;
            int validH = 0;
            for (int j = start; j <= end; j++) { sumE += posE[j]; sumN += posN[j]; if (hErr[j] >= 0) { sumH += hErr[j]; validH++; } }
            double meanE = sumE / n, meanN = sumN / n;
            double varE = 0, varN = 0;
            for (int j = start; j <= end; j++) {
                varE += (posE[j] - meanE) * (posE[j] - meanE);
                varN += (posN[j] - meanN) * (posN[j] - meanN);
            }
            double std = Math.sqrt((varE + varN) / n);
            double avgH = validH > 0 ? sumH / validH : -1;
            int lt05 = 0, gt1 = 0;
            for (int j = start; j <= end; j++) { if (hErr[j] >= 0) { if (hErr[j] < 0.5) lt05++; if (hErr[j] > 1.0) gt1++; } }
            String cls = validH > 0 ? (gt1 == 0 ? "TRUE" : (lt05 == 0 ? "FALSE" : "MIXED")) : "?";
            System.out.printf("  %2d  | %4d | %8.3f | %9.3f  | %s%n", si + 1, n, std, avgH, cls);
        }

        double[] stdThreshValues = {0.5, 1.0, 2.0, 5.0, 10.0, 20.0};
        for (double stdThresh : stdThreshValues) {
            int fixCount = 0, fixHlt05 = 0, fixHgt1 = 0, fixHgt5 = 0;
            double sumH2 = 0;
            for (int[] seg : segments) {
                int start = seg[0], end = seg[1];
                double sumE = 0, sumN = 0;
                int n = end - start + 1;
                for (int j = start; j <= end; j++) { sumE += posE[j]; sumN += posN[j]; }
                double meanE = sumE / n, meanN = sumN / n;
                double varE = 0, varN = 0;
                for (int j = start; j <= end; j++) {
                    varE += (posE[j] - meanE) * (posE[j] - meanE);
                    varN += (posN[j] - meanN) * (posN[j] - meanN);
                }
                double std = Math.sqrt((varE + varN) / n);
                if (std < stdThresh) {
                    for (int j = start; j <= end; j++) {
                        fixCount++;
                        if (hErr[j] >= 0) {
                            sumH2 += hErr[j] * hErr[j];
                            if (hErr[j] < 0.5) fixHlt05++;
                            if (hErr[j] > 1.0) fixHgt1++;
                            if (hErr[j] > 5.0) fixHgt5++;
                        }
                    }
                }
            }
            double fixRate = total > 0 ? (double) fixCount / total : 0;
            double hRmse = fixCount > 0 ? Math.sqrt(sumH2 / fixCount) : -1;
            double trueFixPct = fixCount > 0 ? fixHlt05 * 100.0 / fixCount : 0;
            double falseFixPct = fixCount > 0 ? fixHgt1 * 100.0 / fixCount : 0;
            System.out.printf("  σ<%.1fm: Fix=%d(%.1f%%), H<0.5m=%.1f%%, H>1m=%.1f%%, H.RMSE=%.3fm%n",
                    stdThresh, fixCount, fixRate * 100, trueFixPct, falseFixPct,
                    hRmse > 0 ? hRmse : Double.NaN);
        }
    }

    private static boolean[] applyContinuityFilter(boolean[] isFix, double[] posE, double[] posN,
                                                    int total, int minConsecutive, double jumpThresh) {
        boolean[] trusted = new boolean[total];
        int segStart = -1;
        for (int i = 0; i <= total; i++) {
            boolean fix = i < total && isFix[i];
            if (fix) {
                if (segStart < 0) segStart = i;
            } else {
                if (segStart >= 0) {
                    int segLen = i - segStart;
                    if (segLen >= minConsecutive) {
                        for (int j = segStart; j < i; j++) {
                            trusted[j] = true;
                        }
                    }
                    segStart = -1;
                }
            }
        }
        if (jumpThresh < Double.MAX_VALUE) {
            for (int i = 1; i < total; i++) {
                if (trusted[i] && trusted[i - 1]) {
                    double dE = posE[i] - posE[i - 1];
                    double dN = posN[i] - posN[i - 1];
                    double jump = Math.sqrt(dE * dE + dN * dN);
                    if (jump > jumpThresh) {
                        trusted[i] = false;
                    }
                }
            }
        }
        return trusted;
    }

    private static List<int[]> identifyFixSegments(boolean[] isFix, int total) {
        List<int[]> segments = new ArrayList<>();
        int segStart = -1;
        for (int i = 0; i <= total; i++) {
            boolean fix = i < total && isFix[i];
            if (fix) {
                if (segStart < 0) segStart = i;
            } else {
                if (segStart >= 0) {
                    segments.add(new int[]{segStart, i - 1});
                    segStart = -1;
                }
            }
        }
        return segments;
    }

    @Test
    @DisplayName("在线段内σ过滤验证: UrbanNav OFF - enableSegmentSigmaFilter在线效果")
    void testOnlineSegmentSigmaFilter() {
        DataSet ds = DATA_SETS[7];
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");

        List<double[]> refs = loadReference(ds.referencePath, ds.refType);
        if (refs.isEmpty()) {
            log.warn("参考真值不可用，跳过在线段内σ过滤验证");
            return;
        }

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = SYS_MULTI;
        opt.nf = 2;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * D2R;
        opt.ionoopt = Constants.IONOOPT_OFF;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        CV_OFF_REF.apply(opt);

        System.out.println();
        System.out.println("═══ " + ds.name + " [OFF] 在线段内σ过滤验证 ═══");
        System.out.println("  基准(OFF无过滤): Fix=6690(55.0%), H<0.5m=109, H>1m=2515(54.5%), H.RMSE=2.827m");

        double[] sigmaThreshValues = {5.0, 10.0, 20.0};
        int[] minLenValues = {5, 10};
        for (double sigmaThresh : sigmaThreshValues) {
            for (int minLen : minLenValues) {
                RtkConfig cfg = CV_OFF_REF.createRtkConfig();
                cfg.enableSegmentSigmaFilter = true;
                cfg.segmentSigmaThreshold = sigmaThresh;
                cfg.segmentSigmaMinLen = minLen;

                RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(
                        ds.roverObs, ds.baseObs, ds.nav, opt, cfg);

                int total = result.solutions.size();
                int fix = 0, fixHlt05 = 0, fixHgt1 = 0;
                double sumH2 = 0;
                for (SolData sol : result.solutions) {
                    if (sol.status == SolutionStatus.FIX) {
                        fix++;
                        Position llhPos = sol.getPosition(CoordType.LLH);
                        if (llhPos != null) {
                            double[] nearest = findNearestRef(sol.time.time + sol.time.sec, refs);
                            if (nearest != null) {
                                double dLat = (llhPos.v1 - nearest[1]) * D2R;
                                double dLon = (llhPos.v2 - nearest[2]) * D2R;
                                double cosLat = Math.cos(nearest[1] * D2R);
                                double e = dLon * cosLat * Constants.RE_WGS84;
                                double n = dLat * Constants.RE_WGS84;
                                double hErr = Math.sqrt(e * e + n * n);
                                sumH2 += hErr * hErr;
                                if (hErr < 0.5) fixHlt05++;
                                if (hErr > 1.0) fixHgt1++;
                            }
                        }
                    }
                }
                double fixRate = total > 0 ? (double) fix / total * 100 : 0;
                double hRmse = fix > 0 ? Math.sqrt(sumH2 / fix) : -1;
                double trueFixPct = fix > 0 ? fixHlt05 * 100.0 / fix : 0;
                double falseFixPct = fix > 0 ? fixHgt1 * 100.0 / fix : 0;
                System.out.printf("  σ<%.0fm,N≥%d: Fix=%d(%.1f%%), H<0.5m=%.1f%%, H>1m=%.1f%%, H.RMSE=%.3fm%n",
                        sigmaThresh, minLen, fix, fixRate, trueFixPct, falseFixPct,
                        hRmse > 0 ? hRmse : Double.NaN);
            }
        }
    }

    @Test
    @DisplayName("ParamNoise修复验证: Tokyo run1/run2 EST - 替换式过程噪声")
    void testParamNoiseFixVerification() {
        DataSet ds1 = DATA_SETS[1];
        DataSet ds2 = DATA_SETS[2];
        Assumptions.assumeTrue(ds1.exists(), ds1.name + " 数据不存在");
        Assumptions.assumeTrue(ds2.exists(), ds2.name + " 数据不存在");
        ConfigVariant[] variants = {CV_EST_REF, CV_EST_PARAMNOISE};
        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : variants) {
            results.add(runOneWithIono(ds1, cv, Constants.IONOOPT_EST));
        }
        printDatasetComparison(ds1.name + " [EST ParamNoise修复]", results);
        results.clear();
        for (ConfigVariant cv : variants) {
            results.add(runOneWithIono(ds2, cv, Constants.IONOOPT_EST));
        }
        printDatasetComparison(ds2.name + " [EST ParamNoise修复]", results);
    }

    @Test
    @DisplayName("IGGIII诊断: Tokyo run1/run2 EST - 逐卫星降权统计")
    void testIggiiiDiagnosis() {
        DataSet[] dss = {DATA_SETS[1], DATA_SETS[2]};
        for (DataSet ds : dss) {
            Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
            PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
            opt.navsys = SYS_MULTI;
            opt.nf = 2;
            opt.modear = Constants.ARMODE_FIXHOLD;
            opt.elmin = 15.0 * D2R;
            opt.ionoopt = Constants.IONOOPT_EST;
            opt.tropopt = Constants.TROPOPT_SAAS;
            opt.refpos = Constants.POSOPT_POS_XYZ;
            CV_EST_IGGIII.apply(opt);
            RtkConfig cfg = CV_EST_IGGIII.createRtkConfig();
            RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(
                    ds.roverObs, ds.baseObs, ds.nav, opt, cfg);
            System.out.println();
            System.out.println("═══ " + ds.name + " [EST+IGGIII] 降权统计 ═══");
            System.out.println("IGGIII调用次数: " + result.diagIggCallCount);
            System.out.println("总降权次数: " + result.diagIggDownWeightCount);
            if (result.diagIggDownWeightPerSat != null) {
                int[] perSat = result.diagIggDownWeightPerSat;
                System.out.println("逐卫星降权次数 (satId: count):");
                for (int i = 1; i < perSat.length; i++) {
                    if (perSat[i] > 0) {
                        String label = SatUtils.satno2id(i);
                        System.out.printf("  %s(%2d): %6d%n", label, i, perSat[i]);
                    }
                }
                int total = 0;
                for (int c : perSat) total += c;
                System.out.println("验证总和: " + total);
            }
        }
    }

    @Test
    @Disabled("全量汇总: 8组数据 × 14配置, 耗时极长, 需手动启用")
    @DisplayName("汇总: 8组数据 × 12配置对比")
    void testSummaryAll() {
        List<RtkResult> allResults = new ArrayList<>();
        for (DataSet ds : DATA_SETS) {
            Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
            for (ConfigVariant cv : CONFIGS) {
                allResults.add(runOne(ds, cv));
            }
        }
        printComparisonTable(allResults);
    }

    static class ConfigVariant {
        final String name;
        final String desc;

        ConfigVariant(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        void apply(PrcOpt opt) {}

        RtkConfig createRtkConfig() {
            RtkConfig cfg = new RtkConfig();
            cfg.enableIflcPairNegotiation = true;
            return cfg;
        }
    }

    static final ConfigVariant[] CONFIGS;

    static {
        ArrayList<ConfigVariant> list = new ArrayList<>();

        list.add(new ConfigVariant("A-Default", "Java默认(dynamics=0)") {
            void apply(PrcOpt opt) {
                opt.dynamics = 0;
                opt.thresar[1] = 0.25;
                opt.prn[3] = 0.1;
                opt.prn[4] = 0.01;
                opt.glomodear = Constants.GLO_ARMODE_AUTOCAL;
                opt.bdsmodear = 1;
                opt.elmaskhold = 0.0;
                opt.thresar[6] = 3.0;
            }
        });

        list.add(new ConfigVariant("B-Dyn1", "dynamics=1, 默认prn") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.25;
                opt.prn[3] = 0.1;
                opt.prn[4] = 0.01;
                opt.glomodear = Constants.GLO_ARMODE_AUTOCAL;
                opt.bdsmodear = 1;
                opt.elmaskhold = 0.0;
                opt.thresar[6] = 3.0;
            }
        });

        list.add(new ConfigVariant("C-EX-Ref", "对齐RTKLIB-EX配置") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
        });

        list.add(new ConfigVariant("D-LooseAR", "宽松AR(thresar1=1.0)") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 1.0;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
        });

        list.add(new ConfigVariant("E-NoDyn", "dynamics=0, EX参数") {
            void apply(PrcOpt opt) {
                opt.dynamics = 0;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
        });

        list.add(new ConfigVariant("F-AdapQ", "EX-Ref + 自适应Q") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableAdaptiveQ = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("G-IGGIII", "EX-Ref + 抗差IGGIII") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableIggiii = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("H-ResEdit", "EX-Ref + 残差编辑") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableResidualEdit = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("I-PartAR", "EX-Ref + 部分模糊度固定") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enablePartialAR = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("J-CascadeAR", "EX-Ref + 级联AR") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableCascadeAR = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("K-SnrMed", "EX-Ref + SNR中值定权") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableSnrMedian = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("L-AllOpt", "EX-Ref + 全部RTK优化") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableAdaptiveQ = true;
                cfg.enableIggiii = true;
                cfg.enableResidualEdit = true;
                cfg.enablePartialAR = true;
                cfg.enableCascadeAR = true;
                cfg.enableSnrMedian = true;
                cfg.enableParRefReselect = true;
                cfg.enableParamTypeNoise = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("M-ParRef", "EX-Ref + PAR参考星重选") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableParRefReselect = true;
                return cfg;
            }
        });

        list.add(new ConfigVariant("N-ParamNoise", "EX-Ref + 参数类型级过程噪声") {
            void apply(PrcOpt opt) {
                opt.dynamics = 1;
                opt.thresar[1] = 0.9999;
                opt.prn[3] = 3.0;
                opt.prn[4] = 1.0;
                opt.glomodear = Constants.GLO_ARMODE_OFF;
                opt.bdsmodear = 0;
                opt.elmaskhold = 15.0 * D2R;
                opt.thresar[6] = 10.0;
            }
            RtkConfig createRtkConfig() {
                RtkConfig cfg = new RtkConfig();
                cfg.enableIflcPairNegotiation = true;
                cfg.enableParamTypeNoise = true;
                return cfg;
            }
        });

        CONFIGS = list.toArray(new ConfigVariant[0]);
    }

    private void runWithAllConfigs(DataSet ds) {
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");

        List<RtkResult> results = new ArrayList<>();
        for (ConfigVariant cv : CONFIGS) {
            results.add(runOne(ds, cv));
        }
        printDatasetComparison(ds.name, results);
    }

    private void runOneAndPrint(DataSet ds, ConfigVariant cv) {
        Assumptions.assumeTrue(ds.exists(), ds.name + " 数据不存在");
        RtkResult r = runOne(ds, cv);
        System.out.println();
        System.out.printf("=== %s [%s] ===%n", ds.name, cv.name);
        System.out.printf("  历元=%d, Fix=%d (%.1f%%), Float=%d (%.1f%%), Single=%d, None=%d%n",
                r.total, r.fix, r.fixRate * 100, r.floatN, r.floatRate * 100, r.single, r.none);
        System.out.printf("  H.RMSE: Fix=%.3fm, Float=%.3fm%n",
                r.hRmseFix > 0 ? r.hRmseFix : Double.NaN,
                r.hRmseFloat > 0 ? r.hRmseFloat : Double.NaN);
        if (r.fix > 0 && r.fixHlt05 + r.fixHgt1 > 0) {
            System.out.printf("  Fix精度分布: H<0.5m=%d(%.1f%%), H>1m=%d(%.1f%%), H>5m=%d(%.1f%%)%n",
                    r.fixHlt05, r.fixHlt05 * 100.0 / r.fix,
                    r.fixHgt1, r.fixHgt1 * 100.0 / r.fix,
                    r.fixHgt5, r.fixHgt5 * 100.0 / r.fix);
        }
    }

    private RtkResult runOne(DataSet ds, ConfigVariant cv) {
        return runOneWithIono(ds, cv, Constants.IONOOPT_IFLC);
    }

    private RtkResult runOneWithIono(DataSet ds, ConfigVariant cv, int ionoopt) {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = SYS_MULTI;
        opt.nf = 2;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * D2R;
        opt.ionoopt = ionoopt;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        cv.apply(opt);

        RtkProcessor.RtkResult result;
        try {
            RtkConfig cfg = cv.createRtkConfig();
            result = RinexRtkProcessor.processRinex(ds.roverObs, ds.baseObs, ds.nav, opt, cfg);
        } catch (Exception e) {
            log.error("[{}][{}] RTK异常: {}", ds.name, cv.name, e.getMessage());
            return new RtkResult(ds.name, cv.name, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, 0, 0, 0, 0, 0);
        }

        int total = result.totalEpochs;
        int fix = 0, floatN = 0, single = 0, none = 0;
        for (SolData sol : result.solutions) {
            if (sol.status == SolutionStatus.FIX) fix++;
            else if (sol.status == SolutionStatus.FLOAT) floatN++;
            else if (sol.status == SolutionStatus.SINGLE) single++;
            else none++;
        }
        double fixRate = total > 0 ? (double) fix / total : 0;
        double floatRate = total > 0 ? (double) floatN / total : 0;

        double[] rmse = computeRmse(result.solutions, ds.referencePath, ds.refType);
        double hRmseFix = -1, vRmseFix = -1, hRmseFloat = -1, vRmseFloat = -1;
        int fixMatch = 0, floatMatch = 0;
        int fixHlt05 = 0, fixHgt1 = 0, fixHgt5 = 0;
        if (rmse != null) {
            hRmseFix = rmse[0]; vRmseFix = rmse[1];
            hRmseFloat = rmse[4]; vRmseFloat = rmse[5];
            fixMatch = (int) rmse[3]; floatMatch = (int) rmse[7];
            if (rmse.length >= 11) {
                fixHlt05 = (int) rmse[8]; fixHgt1 = (int) rmse[9]; fixHgt5 = (int) rmse[10];
            }
        }

        return new RtkResult(ds.name, cv.name, total, fix, floatN, single, none,
                fixRate, floatRate, hRmseFix, vRmseFix, hRmseFloat, vRmseFloat, fixMatch, floatMatch,
                fixHlt05, fixHgt1, fixHgt5);
    }

    private void printDatasetComparison(String dsName, List<RtkResult> results) {
        System.out.println();
        System.out.println("┌──────────────────────────────────────────────────────────────────────────────────────────┐");
        System.out.printf("│  %s - 配置对比%n", dsName);
        System.out.println("├──────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┤");
        System.out.println("│ 配置     │ 历元     │ Fix%     │ Float%   │ Fix(个)  │ Float(个) │ H.RMSE   │ F.H.RMSE │");
        System.out.println("│          │          │          │          │          │          │ Float(m) │ Fix(m)   │");
        System.out.println("├──────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┤");
        for (RtkResult r : results) {
            String fixPct = String.format("%5.1f%%", r.fixRate * 100);
            String floatPct = String.format("%5.1f%%", r.floatRate * 100);
            String hFloat = r.hRmseFloat > 0 ? String.format("%.3f", r.hRmseFloat) : "N/A";
            String hFix = r.hRmseFix > 0 ? String.format("%.3f", r.hRmseFix) : "N/A";
            System.out.printf("│ %-8s │ %8d │ %8s │ %8s │ %8d │ %8d │ %8s │ %8s │%n",
                    r.configName, r.total, fixPct, floatPct, r.fix, r.floatN, hFloat, hFix);
        }
        System.out.println("└──────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘");
    }

    private void printComparisonTable(List<RtkResult> results) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                           动态RTK优化测试 - 全数据集 × 全配置对比汇总                                              ║");
        System.out.println("╠══════════════════════╪════════════╪════════╪════════╪══════════╪══════════╪══════════╣");
        System.out.println("║ 数据集               │ 配置       │ Fix%   │ Float% │ Fix(个)  │ H.RMSE   │ F.H.RMSE ║");
        System.out.println("║                      │            │        │        │ Float(m) │ Fix(m)   │          ║");
        System.out.println("╠══════════════════════╪════════════╪════════╪════════╪══════════╪══════════╪══════════╣");

        String lastName = "";
        for (RtkResult r : results) {
            String dsName = r.name.equals(lastName) ? "" : r.name;
            lastName = r.name;
            String fixPct = String.format("%5.1f%%", r.fixRate * 100);
            String floatPct = String.format("%5.1f%%", r.floatRate * 100);
            String hFloat = r.hRmseFloat > 0 ? String.format("%.3f", r.hRmseFloat) : "N/A";
            String hFix = r.hRmseFix > 0 ? String.format("%.3f", r.hRmseFix) : "N/A";
            String fixN = String.format("%d", r.fix);
            System.out.printf("║ %-20s │ %-10s │ %6s │ %6s │ %8s │ %8s │ %8s ║%n",
                    dsName, r.configName, fixPct, floatPct, fixN, hFloat, hFix);
        }
        System.out.println("╚══════════════════════╧════════════╧════════╧════════╧══════════╧══════════╧══════════╝");

        System.out.println();
        System.out.println("配置说明:");
        for (ConfigVariant cv : CONFIGS) {
            System.out.printf("  %s: %s%n", cv.name, cv.desc);
        }
    }

    private double[] computeRmse(List<SolData> solutions, String referencePath, RefType refType) {
        if (referencePath == null || !Files.exists(Paths.get(referencePath))) return null;

        List<double[]> refs = loadReference(referencePath, refType);
        if (refs.isEmpty()) return null;

        double sumE2Fix = 0, sumN2Fix = 0, sumU2Fix = 0;
        double sumE2Float = 0, sumN2Float = 0, sumU2Float = 0;
        int fixCount = 0, floatCount = 0;
        int fixHlt05 = 0, fixHgt1 = 0, fixHgt5 = 0;

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
            double hErr = Math.sqrt(e * e + n * n);

            if (sol.status == SolutionStatus.FIX) {
                sumE2Fix += e * e; sumN2Fix += n * n; sumU2Fix += u * u;
                fixCount++;
                if (hErr < 0.5) fixHlt05++;
                if (hErr > 1.0) fixHgt1++;
                if (hErr > 5.0) fixHgt5++;
            } else if (sol.status == SolutionStatus.FLOAT) {
                sumE2Float += e * e; sumN2Float += n * n; sumU2Float += u * u;
                floatCount++;
            }
        }

        if (fixCount == 0 && floatCount == 0) return null;

        double hRmseFix = fixCount > 0 ? Math.sqrt((sumE2Fix + sumN2Fix) / fixCount) : -1;
        double vRmseFix = fixCount > 0 ? Math.sqrt(sumU2Fix / fixCount) : -1;
        double hRmseFloat = floatCount > 0 ? Math.sqrt((sumE2Float + sumN2Float) / floatCount) : -1;
        double vRmseFloat = floatCount > 0 ? Math.sqrt(sumU2Float / floatCount) : -1;

        return new double[]{hRmseFix, vRmseFix, 0, fixCount, hRmseFloat, vRmseFloat, floatCount, floatCount,
                fixHlt05, fixHgt1, fixHgt5};
    }

    private List<double[]> loadReference(String path, RefType refType) {
        List<double[]> refs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            if (refType == RefType.PPC_CSV) {
                String header = br.readLine();
                if (header == null || !header.contains("GPS TOW")) return refs;
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
                    } catch (NumberFormatException e) { /* skip */ }
                }
            } else {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("%")) continue;
                    String[] cols = line.split("\\s+");
                    if (cols.length < 6) continue;
                    try {
                        int week = Integer.parseInt(cols[0].trim());
                        double tow = Double.parseDouble(cols[1].trim());
                        double x = Double.parseDouble(cols[2].trim());
                        double y = Double.parseDouble(cols[3].trim());
                        double z = Double.parseDouble(cols[4].trim());
                        int q = Integer.parseInt(cols[5].trim());
                        if (q != 1) continue;
                        double[] llh = new double[3];
                        CoordTransform.ecef2pos(new double[]{x, y, z}, llh);
                        double lat = llh[0] / D2R;
                        double lon = llh[1] / D2R;
                        double h = llh[2];
                        double unixTime = GPS_EPOCH_UNIX + week * 604800.0 + tow;
                        refs.add(new double[]{unixTime, lat, lon, h});
                    } catch (NumberFormatException e) { /* skip */ }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read reference: {}", e.getMessage());
        }
        return refs;
    }

    private double[] findNearestRef(double solUnixTime, List<double[]> refs) {
        double bestDt = Double.MAX_VALUE;
        double[] best = null;
        for (double[] ref : refs) {
            double dt = Math.abs(solUnixTime - ref[0]);
            if (dt < bestDt) { bestDt = dt; best = ref; }
        }
        return bestDt > 1.0 ? null : best;
    }
}