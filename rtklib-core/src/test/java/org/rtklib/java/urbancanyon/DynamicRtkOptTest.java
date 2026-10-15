package org.rtklib.java.urbancanyon;

import org.junit.jupiter.api.*;
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

        RtkResult(String name, String configName, int total, int fix, int floatN, int single, int none,
                  double fixRate, double floatRate,
                  double hRmseFix, double vRmseFix, double hRmseFloat, double vRmseFloat,
                  int fixMatch, int floatMatch) {
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
    @DisplayName("1. Net_Diff Urban")
    void testNetDiffUrban() {
        runWithAllConfigs(DATA_SETS[0]);
    }

    @Test
    @DisplayName("2. PPC Tokyo run1")
    void testPpcTokyoRun1() {
        runWithAllConfigs(DATA_SETS[1]);
    }

    @Test
    @DisplayName("3. PPC Tokyo run2")
    void testPpcTokyoRun2() {
        runWithAllConfigs(DATA_SETS[2]);
    }

    @Test
    @DisplayName("4. PPC Tokyo run3")
    void testPpcTokyoRun3() {
        runWithAllConfigs(DATA_SETS[3]);
    }

    @Test
    @DisplayName("5. PPC Nagoya run1")
    void testPpcNagoyaRun1() {
        runWithAllConfigs(DATA_SETS[4]);
    }

    @Test
    @DisplayName("6. PPC Nagoya run2")
    void testPpcNagoyaRun2() {
        runWithAllConfigs(DATA_SETS[5]);
    }

    @Test
    @DisplayName("7. PPC Nagoya run3")
    void testPpcNagoyaRun3() {
        runWithAllConfigs(DATA_SETS[6]);
    }

    @Test
    @DisplayName("8. UrbanNav Tokyo")
    void testUrbanNavTokyo() {
        runWithAllConfigs(DATA_SETS[7]);
    }

    @Test
    @DisplayName("汇总: 8组数据 × 多配置对比")
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
                opt.thresar[1] = 0.1;
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
                opt.thresar[1] = 0.1;
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
                opt.thresar[1] = 0.1;
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
                opt.thresar[1] = 0.1;
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
                opt.thresar[1] = 0.1;
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
                opt.thresar[1] = 0.1;
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
                opt.thresar[1] = 0.1;
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
                opt.thresar[1] = 0.1;
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

    private RtkResult runOne(DataSet ds, ConfigVariant cv) {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = SYS_MULTI;
        opt.nf = 2;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        cv.apply(opt);

        RtkProcessor.RtkResult result;
        try {
            RtkConfig cfg = cv.createRtkConfig();
            result = RinexRtkProcessor.processRinex(ds.roverObs, ds.baseObs, ds.nav, opt, cfg);
        } catch (Exception e) {
            log.error("[{}][{}] RTK异常: {}", ds.name, cv.name, e.getMessage());
            return new RtkResult(ds.name, cv.name, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1, 0, 0);
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
        if (rmse != null) {
            hRmseFix = rmse[0]; vRmseFix = rmse[1];
            hRmseFloat = rmse[4]; vRmseFloat = rmse[5];
            fixMatch = (int) rmse[3]; floatMatch = (int) rmse[7];
        }

        return new RtkResult(ds.name, cv.name, total, fix, floatN, single, none,
                fixRate, floatRate, hRmseFix, vRmseFix, hRmseFloat, vRmseFloat, fixMatch, floatMatch);
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

            if (sol.status == SolutionStatus.FIX) {
                sumE2Fix += e * e; sumN2Fix += n * n; sumU2Fix += u * u;
                fixCount++;
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

        return new double[]{hRmseFix, vRmseFix, 0, fixCount, hRmseFloat, vRmseFloat, floatCount, floatCount};
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
                        double lat = Double.parseDouble(cols[2].trim());
                        double lon = Double.parseDouble(cols[3].trim());
                        double h = Double.parseDouble(cols[4].trim());
                        int q = Integer.parseInt(cols[5].trim());
                        if (q != 1) continue;
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