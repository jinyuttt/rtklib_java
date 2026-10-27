package org.rtklib.java.ppp;

import org.junit.jupiter.api.*;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PPP 优化项逐项对照测试")
@Tag("manual")
public class PppIndividualOptTest {

    private static final Logger log = LoggerFactory.getLogger(PppIndividualOptTest.class);

    private static final String BASE;
    static {
        String dir = TestDataConfig.getTestDataDir();
        BASE = Files.exists(Paths.get(dir, "rinex", "ppp_wtzr_20180606.18o"))
                ? dir
                : "D:/code/rtklib_java/test-data";
    }

    static class Station {
        final String name;
        final String obsFile;
        final int navsys;
        final double[] refEcef;

        Station(String name, String obsFile, int navsys, double[] refEcef) {
            this.name = name;
            this.obsFile = obsFile;
            this.navsys = navsys;
            this.refEcef = refEcef;
        }
    }

    private static final String NAV_FILE = BASE + "/nav/ppp_20180606.18p";
    private static final String SP3_FILE = BASE + "/product/gbm20043.sp3";
    private static final String CLK_FILE = BASE + "/product/gbm20043.clk";
    private static final String ATX_FILE = BASE + "/product/test.atx";
    private static final String ION_FILE = BASE + "/product/igsg1570.18i";

    static final Station[] STATIONS = {
        new Station("WTZR",
            BASE + "/rinex/ppp_wtzr_20180606.18o",
            Constants.SYS_GPS | Constants.SYS_GLO,
            new double[]{4075578.4000, 931852.7500, 4801569.9800}),
        new Station("WTZA",
            BASE + "/rinex/ppp_wtza_20180606.18o",
            Constants.SYS_GPS | Constants.SYS_GLO,
            new double[]{4075578.4000, 931852.7500, 4801569.9800}),
        new Station("HKWS",
            BASE + "/rinex/ppp_hkws_20180606.18o",
            Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP | Constants.SYS_QZS,
            new double[]{-2430579.0108, 5374285.6738, 2418956.3331}),
        new Station("HKSL",
            BASE + "/rinex/ppp_hksl_20180606.18o",
            Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP | Constants.SYS_QZS,
            new double[]{-2393382.4157, 5393861.1745, 2412592.4105}),
    };

    static class PppRunResult {
        final String configName;
        final List<double[]> positions;
        final int totalEpochs;
        final int successCount;

        PppRunResult(String configName, List<double[]> positions, int totalEpochs, int successCount) {
            this.configName = configName;
            this.positions = positions;
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
        }
    }

    private static PrcOpt createBaseOpt(int navsys) {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = navsys;
        opt.elmin = 10.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.tidecorr = 7;
        opt.dynamics = 0;
        opt.posopt = new int[6];
        return opt;
    }

    private static PppRunResult runPpp(Station station, PrcOpt opt, RtkConfig cfg, int maxEpochs) {
        Assumptions.assumeTrue(Files.exists(Paths.get(station.obsFile)),
                station.name + " obs文件不存在: " + station.obsFile);
        Assumptions.assumeTrue(Files.exists(Paths.get(SP3_FILE)),
                "SP3文件不存在: " + SP3_FILE);
        Assumptions.assumeTrue(Files.exists(Paths.get(CLK_FILE)),
                "CLK文件不存在: " + CLK_FILE);

        try (BufferedReader br = new BufferedReader(new FileReader(station.obsFile))) {
            String headerLine = br.readLine();
            if (headerLine != null && headerLine.trim().startsWith("2.")) {
                Assumptions.assumeTrue(false, station.name + " RINEX 2.x 不支持，需要RINEX 3.x格式");
            }
        } catch (IOException e) {
            // ignore
        }

        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        try {
            PppProcessor.PppResult result = ppp.processRinex(station.obsFile, NAV_FILE, SP3_FILE, CLK_FILE);

            List<double[]> positions = new ArrayList<>();
            if (result.solutions != null) {
                for (SolData solData : result.solutions) {
                    Position ecefPos = solData.getPosition(CoordType.ECEF);
                    if (ecefPos != null && solData.status != SolutionStatus.NONE) {
                        positions.add(new double[]{ecefPos.v1, ecefPos.v2, ecefPos.v3});
                    }
                }
            }

            int limit = maxEpochs > 0 ? Math.min(maxEpochs, result.totalEpochs) : result.totalEpochs;
            return new PppRunResult("", positions, result.totalEpochs, positions.size());
        } catch (Exception e) {
            log.warn("{} PPP运行异常: {}", station.name, e.getMessage());
            return new PppRunResult("", Collections.emptyList(), 0, 0);
        }
    }

    private static double[] computeRms(List<double[]> positions, double[] refEcef, int skipEpochs) {
        double[] refLlh = new double[3];
        CoordTransform.ecef2pos(refEcef, refLlh);

        double sumN = 0, sumE = 0, sumU = 0;
        int count = 0;

        for (int i = skipEpochs; i < positions.size(); i++) {
            double[] pos = positions.get(i);
            double[] llh = new double[3];
            CoordTransform.ecef2pos(pos, llh);

            double dN = (Constants.RE_WGS84 + llh[2]) * (llh[0] - refLlh[0]);
            double dE = (Constants.RE_WGS84 + llh[2]) * Math.cos(refLlh[0]) * (llh[1] - refLlh[1]);
            double dU = llh[2] - refLlh[2];

            sumN += dN * dN;
            sumE += dE * dE;
            sumU += dU * dU;
            count++;
        }

        if (count == 0) return new double[]{0, 0, 0, 0, 0};
        double rmsN = Math.sqrt(sumN / count);
        double rmsE = Math.sqrt(sumE / count);
        double rmsU = Math.sqrt(sumU / count);
        double rms2D = Math.sqrt((sumN + sumE) / count);
        double rms3D = Math.sqrt((sumN + sumE + sumU) / count);
        return new double[]{rmsN, rmsE, rmsU, rms2D, rms3D};
    }

    private static double[] loadRefEcefFromPos(String posFile) {
        if (!Files.exists(Paths.get(posFile))) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(posFile))) {
            String line;
            List<double[]> positions = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                if (line.startsWith("%") || line.trim().isEmpty()) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 7) continue;
                try {
                    int q = Integer.parseInt(parts[6]);
                    if (q != 1 && q != 6) continue;
                    double x = Double.parseDouble(parts[3]);
                    double y = Double.parseDouble(parts[4]);
                    double z = Double.parseDouble(parts[5]);
                    positions.add(new double[]{x, y, z});
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            if (positions.size() < 100) return null;
            int skip = positions.size() / 2;
            double[] sum = new double[3];
            for (int i = skip; i < positions.size(); i++) {
                sum[0] += positions.get(i)[0];
                sum[1] += positions.get(i)[1];
                sum[2] += positions.get(i)[2];
            }
            int n = positions.size() - skip;
            return new double[]{sum[0] / n, sum[1] / n, sum[2] / n};
        } catch (IOException e) {
            return null;
        }
    }

    private void runIndividualOptComparison(Station station) {
        double[] refEcef = station.refEcef;

        String refPosFile = BASE + "/reference/ppp_" + station.name.toLowerCase() + "_20180606.pos";
        double[] refFromPos = loadRefEcefFromPos(refPosFile);
        if (refFromPos != null) {
            refEcef = refFromPos;
            log.info("{}: 使用参考解坐标 (后半段均值)", station.name);
        } else {
            log.info("{}: 使用RINEX头APPROX POSITION", station.name);
        }

        int maxEpochs = 120;

        log.info("========================================");
        log.info("站点: {} (逐项对照)", station.name);
        log.info("========================================");

        PrcOpt baseOpt = createBaseOpt(station.navsys);

        log.info("--- 基准 PPP ---");
        RtkConfig cfgBase = new RtkConfig();
        PppRunResult baseline = runPpp(station, baseOpt, cfgBase, maxEpochs);

        log.info("--- + GPT3+VMF3 ---");
        PrcOpt optGpt3 = createBaseOpt(station.navsys);
        RtkConfig cfgGpt3 = new RtkConfig();
        cfgGpt3.enableGpt3Vmf3 = true;
        PppRunResult gpt3 = runPpp(station, optGpt3, cfgGpt3, maxEpochs);

        // GPT3 网格对照：默认探测 test-data/product/gpt3/gpt3_5.grd，可用环境变量 PPP_GPT3_GRID 覆盖；
        // PPP_GPT3_STATE=1 时额外启用湿延迟状态估计（useGpt3Grid）
        String gpt3GridPath = resolveGpt3Grid();
        boolean gpt3GridStateOn = "1".equals(System.getenv("PPP_GPT3_STATE"));
        PppRunResult gpt3Grid = null;
        PppRunResult gpt3GridState = null;
        if (gpt3GridPath != null) {
            log.info("--- + GPT3+VMF3+GRID ---");
            PrcOpt optG3 = createBaseOpt(station.navsys);
            RtkConfig cfgG3 = new RtkConfig();
            cfgG3.enableGpt3Vmf3 = true;
            cfgG3.gpt3GridFile = gpt3GridPath;
            gpt3Grid = runPpp(station, optG3, cfgG3, maxEpochs);

            if (gpt3GridStateOn) {
                log.info("--- + GPT3+VMF3+GRID+WETSTATE ---");
                PrcOpt optG3s = createBaseOpt(station.navsys);
                RtkConfig cfgG3s = new RtkConfig();
                cfgG3s.enableGpt3Vmf3 = true;
                cfgG3s.gpt3GridFile = gpt3GridPath;
                cfgG3s.useGpt3Grid = true;
                gpt3GridState = runPpp(station, optG3s, cfgG3s, maxEpochs);
            }
        }

        log.info("--- + IERS2010 ---");
        PrcOpt optIers = createBaseOpt(station.navsys);
        RtkConfig cfgIers = new RtkConfig();
        cfgIers.enableIers2010 = true;
        PppRunResult iers = runPpp(station, optIers, cfgIers, maxEpochs);

        log.info("--- + IERS2010 + S1/S2大气潮 ---");
        PrcOpt optAt1s2 = createBaseOpt(station.navsys);
        RtkConfig cfgAt1s2 = new RtkConfig();
        cfgAt1s2.enableIers2010 = true;
        cfgAt1s2.enableAt1S2 = true;
        PppRunResult at1s2 = runPpp(station, optAt1s2, cfgAt1s2, maxEpochs);

        log.info("--- + ISB/IFCB/IFB ---");
        PrcOpt optIsb = createBaseOpt(station.navsys);
        RtkConfig cfgIsb = new RtkConfig();
        cfgIsb.enableIsbIfcbIfb = true;
        PppRunResult isb = runPpp(station, optIsb, cfgIsb, maxEpochs);

        log.info("--- + 非组合PPP(EST) ---");
        PrcOpt optUc = createBaseOpt(station.navsys);
        optUc.ionoopt = Constants.IONOOPT_EST;
        RtkConfig cfgUc = new RtkConfig();
        PppRunResult uc = runPpp(station, optUc, cfgUc, maxEpochs);

        int skip = 30;
        double[] sBase = computeRms(baseline.positions, refEcef, skip);
        double[] sGpt3 = computeRms(gpt3.positions, refEcef, skip);
        double[] sIers = computeRms(iers.positions, refEcef, skip);
        double[] sAt1s2 = computeRms(at1s2.positions, refEcef, skip);
        double[] sIsb = computeRms(isb.positions, refEcef, skip);
        double[] sUc = computeRms(uc.positions, refEcef, skip);
        double[] sGpt3Grid = gpt3Grid != null ? computeRms(gpt3Grid.positions, refEcef, skip) : null;
        double[] sGpt3GridState = gpt3GridState != null ? computeRms(gpt3GridState.positions, refEcef, skip) : null;

        log.info("");
        log.info("====== {} PPP 逐项对照 ======", station.name);
        log.info(String.format("跳过前%d历元(收敛期), 参考ECEF=(%.3f, %.3f, %.3f)",
                skip, refEcef[0], refEcef[1], refEcef[2]));
        log.info(String.format("%-30s 成功/总  N(m)    E(m)    U(m)   2D(m)   3D(m)", "模式"));
        log.info(String.format("%-30s %3d/%-3d %6.3f  %6.3f  %6.3f  %6.3f  %6.3f",
                "基准 PPP", baseline.successCount, baseline.totalEpochs,
                sBase[0], sBase[1], sBase[2], sBase[3], sBase[4]));

        printOptLine("+ GPT3+VMF3", gpt3, sGpt3, sBase);
        if (sGpt3Grid != null) printOptLine("+ GPT3+VMF3+GRID", gpt3Grid, sGpt3Grid, sBase);
        if (sGpt3GridState != null) printOptLine("+ GPT3+VMF3+GRID+WETSTATE", gpt3GridState, sGpt3GridState, sBase);
        printOptLine("+ IERS2010", iers, sIers, sBase);
        printOptLine("+ IERS2010+S1/S2", at1s2, sAt1s2, sBase);
        printOptLine("+ ISB/IFCB/IFB", isb, sIsb, sBase);
        printOptLine("+ 非组合PPP(EST)", uc, sUc, sBase);

        log.info("");

        System.out.println("");
        System.out.println("====== " + station.name + " PPP 逐项对照 ======");
        System.out.printf("跳过前%d历元(收敛期)%n", skip);
        System.out.printf("%-30s 成功/总  N(m)    E(m)    U(m)   2D(m)   3D(m)%n", "模式");
        System.out.printf("%-30s %3d/%-3d %6.3f  %6.3f  %6.3f  %6.3f  %6.3f%n",
                "基准 PPP", baseline.successCount, baseline.totalEpochs,
                sBase[0], sBase[1], sBase[2], sBase[3], sBase[4]);

        printOptLineStdout("+ GPT3+VMF3", gpt3, sGpt3, sBase);
        if (sGpt3Grid != null) printOptLineStdout("+ GPT3+VMF3+GRID", gpt3Grid, sGpt3Grid, sBase);
        if (sGpt3GridState != null) printOptLineStdout("+ GPT3+VMF3+GRID+WETSTATE", gpt3GridState, sGpt3GridState, sBase);
        printOptLineStdout("+ IERS2010", iers, sIers, sBase);
        printOptLineStdout("+ IERS2010+S1/S2", at1s2, sAt1s2, sBase);
        printOptLineStdout("+ ISB/IFCB/IFB", isb, sIsb, sBase);
        printOptLineStdout("+ 非组合PPP(EST)", uc, sUc, sBase);

        System.out.println("");

        assertTrue(baseline.successCount > 0, station.name + " 基准PPP至少应有一个历元成功");
    }

    private void printOptLine(String label, PppRunResult result, double[] rms, double[] baseRms) {
        double dN = baseRms[0] > 0 ? (rms[0] - baseRms[0]) / baseRms[0] * 100 : 0;
        double dE = baseRms[1] > 0 ? (rms[1] - baseRms[1]) / baseRms[1] * 100 : 0;
        double dU = baseRms[2] > 0 ? (rms[2] - baseRms[2]) / baseRms[2] * 100 : 0;
        log.info(String.format("%-30s %3d/%-3d %6.3f  %6.3f  %6.3f  %6.3f  %6.3f  ΔN=%+.1f%% ΔE=%+.1f%% ΔU=%+.1f%%",
                label, result.successCount, result.totalEpochs,
                rms[0], rms[1], rms[2], rms[3], rms[4], dN, dE, dU));
    }

    private void printOptLineStdout(String label, PppRunResult result, double[] rms, double[] baseRms) {
        double dN = baseRms[0] > 0 ? (rms[0] - baseRms[0]) / baseRms[0] * 100 : 0;
        double dE = baseRms[1] > 0 ? (rms[1] - baseRms[1]) / baseRms[1] * 100 : 0;
        double dU = baseRms[2] > 0 ? (rms[2] - baseRms[2]) / baseRms[2] * 100 : 0;
        System.out.printf("%-30s %3d/%-3d %6.3f  %6.3f  %6.3f  %6.3f  %6.3f  ΔN=%+.1f%% ΔE=%+.1f%% ΔU=%+.1f%%%n",
                label, result.successCount, result.totalEpochs,
                rms[0], rms[1], rms[2], rms[3], rms[4], dN, dE, dU);
    }

    /** GPT3 网格路径：环境变量 PPP_GPT3_GRID 优先，否则探测 test-data/product/gpt3/gpt3_5.grd */
    private static String resolveGpt3Grid() {
        String env = System.getenv("PPP_GPT3_GRID");
        if (env != null && !env.isEmpty()) return env;
        String def = TestDataConfig.getTestDataDir() + "/product/gpt3/gpt3_5.grd";
        return Files.exists(Paths.get(def)) ? def : null;
    }

    @Test
    @DisplayName("1. PPP逐项对照 - WTZR")
    void testIndividualOptWtzr() {
        runIndividualOptComparison(STATIONS[0]);
    }

    @Test
    @DisplayName("2. PPP逐项对照 - WTZA")
    void testIndividualOptWtza() {
        runIndividualOptComparison(STATIONS[1]);
    }

    @Test
    @DisplayName("3. PPP逐项对照 - HKWS (多系统)")
    void testIndividualOptHkws() {
        runIndividualOptComparison(STATIONS[2]);
    }

    @Test
    @DisplayName("4. PPP逐项对照 - HKSL (多系统)")
    void testIndividualOptHksl() {
        runIndividualOptComparison(STATIONS[3]);
    }
}