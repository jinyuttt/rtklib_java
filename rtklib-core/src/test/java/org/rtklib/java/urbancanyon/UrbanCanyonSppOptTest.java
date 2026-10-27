package org.rtklib.java.urbancanyon;

import org.junit.jupiter.api.*;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.pntpos.SppEkfState;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("城市峡谷 SPP 优化测试")
@Tag("manual")
public class UrbanCanyonSppOptTest {

    private static final Logger log = LoggerFactory.getLogger(UrbanCanyonSppOptTest.class);
    private static final String BASE = "D:/code/rtklib_java/test-data";

    static class DataSet {
        final String name;
        final String roverObs;
        final String nav;
        final double refLat;
        final double refLon;

        DataSet(String name, String roverObs, String nav, double refLat, double refLon) {
            this.name = name;
            this.roverObs = roverObs;
            this.nav = nav;
            this.refLat = refLat;
            this.refLon = refLon;
        }
    }

    static final DataSet[] DATA_SETS = {
        new DataSet("Net_Diff Urban",
            BASE + "/rinex/urban_rover_20000719.17o",
            BASE + "/nav/urban_20000719.17p",
            35.668, 139.791),
        new DataSet("PPC Tokyo run1",
            BASE + "/ppc/PPC-Dataset/tokyo/run1/rover.obs",
            BASE + "/ppc/PPC-Dataset/tokyo/run1/base.nav",
            35.645, 139.784),
        new DataSet("PPC Tokyo run2",
            BASE + "/ppc/PPC-Dataset/tokyo/run2/rover.obs",
            BASE + "/ppc/PPC-Dataset/tokyo/run2/base.nav",
            35.645, 139.784),
        new DataSet("PPC Tokyo run3",
            BASE + "/ppc/PPC-Dataset/tokyo/run3/rover.obs",
            BASE + "/ppc/PPC-Dataset/tokyo/run3/base.nav",
            35.645, 139.784),
        new DataSet("PPC Nagoya run1",
            BASE + "/ppc/PPC-Dataset/nagoya/run1/rover.obs",
            BASE + "/ppc/PPC-Dataset/nagoya/run1/base.nav",
            35.169, 136.882),
        new DataSet("PPC Nagoya run2",
            BASE + "/ppc/PPC-Dataset/nagoya/run2/rover.obs",
            BASE + "/ppc/PPC-Dataset/nagoya/run2/base.nav",
            35.169, 136.882),
        new DataSet("PPC Nagoya run3",
            BASE + "/ppc/PPC-Dataset/nagoya/run3/rover.obs",
            BASE + "/ppc/PPC-Dataset/nagoya/run3/base.nav",
            35.169, 136.882),
        new DataSet("UrbanNav Tokyo",
            BASE + "/urbannav/tokyo/data_tokyo/tokyo3530.18o",
            BASE + "/urbannav/tokyo/data_tokyo/brdm3530.18p",
            35.626, 139.781),
    };

    static class ObsEpoch {
        final GTime time;
        final Obsd[] obsd;
        final int n;

        ObsEpoch(GTime time, Obsd[] obsd, int n) {
            this.time = new GTime(time);
            this.obsd = new Obsd[n];
            for (int i = 0; i < n; i++) this.obsd[i] = new Obsd(obsd[i]);
            this.n = n;
        }
    }

    static class SppResult {
        final GTime time;
        final double[] llh;
        final int stat;
        final int ns;
        final double[] sigma;

        SppResult(GTime time, double[] llh, int stat, int ns, double[] sigma) {
            this.time = time;
            this.llh = llh;
            this.stat = stat;
            this.ns = ns;
            this.sigma = sigma;
        }
    }

    private static List<ObsEpoch> loadObsEpochs(String obsPath) {
        RinexParser parser = new RinexParser();
        if (!parser.parseObs(obsPath)) return Collections.emptyList();

        List<ObsEpoch> epochs = new ArrayList<>();
        List<Obsd> current = new ArrayList<>();
        GTime currentTime = parser.obs.data[0].time;

        for (int i = 0; i < parser.obs.n; i++) {
            if (!parser.obs.data[i].time.equals(currentTime)) {
                Obsd[] arr = current.toArray(new Obsd[0]);
                epochs.add(new ObsEpoch(currentTime, arr, arr.length));
                current = new ArrayList<>();
                currentTime = parser.obs.data[i].time;
            }
            current.add(parser.obs.data[i]);
        }
        if (!current.isEmpty()) {
            Obsd[] arr = current.toArray(new Obsd[0]);
            epochs.add(new ObsEpoch(currentTime, arr, arr.length));
        }
        return epochs;
    }

    private static Nav loadNav(String navPath) {
        RinexParser parser = new RinexParser();
        if (!parser.parseNav(navPath)) return null;
        return parser.nav;
    }

    private static PrcOpt createOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_QZS | Constants.SYS_CMP;
        opt.elmin = 10.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.dynamics = 1;
        return opt;
    }

    private static List<SppResult> runSpp(List<ObsEpoch> epochs, Nav nav, PrcOpt opt, RtkConfig cfg) {
        List<SppResult> results = new ArrayList<>();
        SppEkfState ekfState = null;

        if (cfg != null && cfg.enableSppEkf) {
            int nxF = SppEkfState.computeNxF(opt);
            ekfState = new SppEkfState(nxF);
        }

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        Sol sol = new Sol();

        for (ObsEpoch epoch : epochs) {
            int n = epoch.n;
            double[] azel = new double[n * 2];
            int stat = PntPos.pntpos(epoch.obsd, n, nav, opt, sol, azel, ssat, cfg, ekfState);

            if (stat == 1) {
                double[] llh = new double[3];
                CoordTransform.ecef2pos(sol.rr, llh);
                double[] sig = new double[]{sol.qr[0], sol.qr[1], sol.qr[2]};
                results.add(new SppResult(new GTime(epoch.time), llh, sol.stat, sol.ns, sig));
            } else {
                results.add(new SppResult(new GTime(epoch.time), null, 0, 0, null));
            }
        }
        return results;
    }

    private static double[] computeRms(List<SppResult> results, double refLat, double refLon) {
        double sumN = 0, sumE = 0, sumU = 0;
        int count = 0;

        for (SppResult r : results) {
            if (r.llh == null) continue;
            double dN = (Constants.RE_WGS84 + r.llh[2]) * (r.llh[0] - refLat * Constants.D2R);
            double dE = (Constants.RE_WGS84 + r.llh[2]) * Math.cos(refLat * Constants.D2R) * (r.llh[1] - refLon * Constants.D2R);
            double dH = r.llh[2];
            sumN += dN * dN;
            sumE += dE * dE;
            sumU += dH * dH;
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

    private static int countSuccess(List<SppResult> results) {
        return (int) results.stream().filter(r -> r.llh != null).count();
    }

    private static double avgSats(List<SppResult> results) {
        return results.stream().filter(r -> r.llh != null).mapToInt(r -> r.ns).average().orElse(0);
    }

    private static double avgSigma2D(List<SppResult> results) {
        return results.stream().filter(r -> r.sigma != null).mapToDouble(r -> Math.sqrt(r.sigma[0] + r.sigma[1])).average().orElse(0);
    }

    private void runSppComparison(DataSet ds) {
        Assumptions.assumeTrue(Files.exists(Paths.get(ds.roverObs)), ds.name + " rover数据不存在");
        Assumptions.assumeTrue(Files.exists(Paths.get(ds.nav)), ds.name + " nav数据不存在");

        log.info("========================================");
        log.info("数据集: {}", ds.name);
        log.info("========================================");

        List<ObsEpoch> epochs = loadObsEpochs(ds.roverObs);
        Nav nav = loadNav(ds.nav);
        log.info("历元数: {}, 导航星历: {}", epochs.size(), nav.n);

        PrcOpt opt = createOpt();

        log.info("--- 基准 LS-SPP ---");
        List<SppResult> baseline = runSpp(epochs, nav, opt, null);

        RtkConfig cfgEkf = new RtkConfig();
        cfgEkf.enableSppEkf = true;
        log.info("--- EKF-SPP ---");
        List<SppResult> ekf = runSpp(epochs, nav, opt, cfgEkf);

        RtkConfig cfgEkfRobust = new RtkConfig();
        cfgEkfRobust.enableSppEkf = true;
        cfgEkfRobust.enableSppRobust = true;
        log.info("--- EKF-SPP + 抗差 ---");
        List<SppResult> ekfRobust = runSpp(epochs, nav, opt, cfgEkfRobust);

        RtkConfig cfgFull = new RtkConfig();
        cfgFull.enableSppEkf = true;
        cfgFull.enableSppRobust = true;
        cfgFull.enableSppZeroVel = true;
        cfgFull.enableSppDopplerSnr = true;
        log.info("--- EKF + 抗差 + 零速 + SNR ---");
        List<SppResult> full = runSpp(epochs, nav, opt, cfgFull);

        double[] sBase = computeRms(baseline, ds.refLat, ds.refLon);
        double[] sEkf = computeRms(ekf, ds.refLat, ds.refLon);
        double[] sEkfR = computeRms(ekfRobust, ds.refLat, ds.refLon);
        double[] sFull = computeRms(full, ds.refLat, ds.refLon);

        int cBase = countSuccess(baseline);
        int cEkf = countSuccess(ekf);
        int cEkfR = countSuccess(ekfRobust);
        int cFull = countSuccess(full);

        double avgSatBase = avgSats(baseline);
        double avgSatFull = avgSats(full);
        double avgSigBase = avgSigma2D(baseline);
        double avgSigFull = avgSigma2D(full);

        log.info("");
        log.info("====== {} SPP 优化对比 ======", ds.name);
        log.info(String.format("%-28s 成功率  N(m)    E(m)    U(m)   2D(m)   3D(m)", "模式"));
        log.info(String.format("%-28s %5.1f%% %6.2f  %6.2f  %6.2f  %6.2f  %6.2f",
                "基准 LS-SPP", 100.0 * cBase / epochs.size(), sBase[0], sBase[1], sBase[2], sBase[3], sBase[4]));
        log.info(String.format("%-28s %5.1f%% %6.2f  %6.2f  %6.2f  %6.2f  %6.2f",
                "EKF-SPP", 100.0 * cEkf / epochs.size(), sEkf[0], sEkf[1], sEkf[2], sEkf[3], sEkf[4]));
        log.info(String.format("%-28s %5.1f%% %6.2f  %6.2f  %6.2f  %6.2f  %6.2f",
                "EKF + 抗差", 100.0 * cEkfR / epochs.size(), sEkfR[0], sEkfR[1], sEkfR[2], sEkfR[3], sEkfR[4]));
        log.info(String.format("%-28s %5.1f%% %6.2f  %6.2f  %6.2f  %6.2f  %6.2f",
                "EKF + 抗差 + 零速 + SNR", 100.0 * cFull / epochs.size(), sFull[0], sFull[1], sFull[2], sFull[3], sFull[4]));
        log.info("");
        log.info(String.format("平均卫星数: 基准=%.1f, 全优化=%.1f", avgSatBase, avgSatFull));
        log.info(String.format("平均2D sigma: 基准=%.3fm, 全优化=%.3fm", avgSigBase, avgSigFull));

        double imp2d = sBase[3] > 0 ? (sBase[3] - sFull[3]) / sBase[3] * 100.0 : 0;
        double imp3d = sBase[4] > 0 ? (sBase[4] - sFull[4]) / sBase[4] * 100.0 : 0;
        double impRate = cBase > 0 ? (double) (cFull - cBase) / cBase * 100.0 : 0;
        log.info(String.format("2D RMS 改善: %.1f%%, 3D RMS 改善: %.1f%%, 成功率变化: %+.1f%%", imp2d, imp3d, impRate));
        log.info("");

        assertTrue(cBase > 0, ds.name + " 基准SPP至少应有一个历元成功");
    }

    @Test
    @Disabled("单频L1+BRDC，城市峡谷下SPP参考价值有限")
    @DisplayName("1. Net_Diff Urban SPP优化")
    void testNetDiffUrban() {
        runSppComparison(DATA_SETS[0]);
    }

    @Test
    @DisplayName("2. PPC Tokyo run1 SPP优化")
    void testPpcTokyoRun1() {
        runSppComparison(DATA_SETS[1]);
    }

    @Test
    @DisplayName("3. PPC Tokyo run2 SPP优化")
    void testPpcTokyoRun2() {
        runSppComparison(DATA_SETS[2]);
    }

    @Test
    @DisplayName("4. PPC Tokyo run3 SPP优化")
    void testPpcTokyoRun3() {
        runSppComparison(DATA_SETS[3]);
    }

    @Test
    @DisplayName("5. PPC Nagoya run1 SPP优化")
    void testPpcNagoyaRun1() {
        runSppComparison(DATA_SETS[4]);
    }

    @Test
    @DisplayName("6. PPC Nagoya run2 SPP优化")
    void testPpcNagoyaRun2() {
        runSppComparison(DATA_SETS[5]);
    }

    @Test
    @DisplayName("7. PPC Nagoya run3 SPP优化")
    void testPpcNagoyaRun3() {
        runSppComparison(DATA_SETS[6]);
    }

    @Test
    @DisplayName("8. UrbanNav Tokyo SPP优化")
    void testUrbanNavTokyo() {
        runSppComparison(DATA_SETS[7]);
    }

    private void runIndividualOptComparison(DataSet ds) {
        Assumptions.assumeTrue(Files.exists(Paths.get(ds.roverObs)), ds.name + " rover数据不存在");
        Assumptions.assumeTrue(Files.exists(Paths.get(ds.nav)), ds.name + " nav数据不存在");

        log.info("========================================");
        log.info("数据集: {} (逐项对照)", ds.name);
        log.info("========================================");

        List<ObsEpoch> epochs = loadObsEpochs(ds.roverObs);
        Nav nav = loadNav(ds.nav);
        PrcOpt opt = createOpt();

        RtkConfig cfgBase = new RtkConfig();
        cfgBase.enableSppEkf = true;
        cfgBase.enableSppRobust = true;
        List<SppResult> base = runSpp(epochs, nav, opt, cfgBase);

        RtkConfig cfgNoInnov = new RtkConfig();
        cfgNoInnov.enableSppEkf = true;
        cfgNoInnov.enableSppRobust = true;
        cfgNoInnov.enableSppInnovChisq = false;
        List<SppResult> noInnov = runSpp(epochs, nav, opt, cfgNoInnov);

        RtkConfig cfgZeroVel = new RtkConfig();
        cfgZeroVel.enableSppEkf = true;
        cfgZeroVel.enableSppRobust = true;
        cfgZeroVel.enableSppZeroVel = true;
        List<SppResult> withZeroVel = runSpp(epochs, nav, opt, cfgZeroVel);

        RtkConfig cfgDopplerSnr = new RtkConfig();
        cfgDopplerSnr.enableSppEkf = true;
        cfgDopplerSnr.enableSppRobust = true;
        cfgDopplerSnr.enableSppDopplerSnr = true;
        List<SppResult> withDopplerSnr = runSpp(epochs, nav, opt, cfgDopplerSnr);

        double[] sBase = computeRms(base, ds.refLat, ds.refLon);
        double[] sNoInnov = computeRms(noInnov, ds.refLat, ds.refLon);
        double[] sZeroVel = computeRms(withZeroVel, ds.refLat, ds.refLon);
        double[] sDopplerSnr = computeRms(withDopplerSnr, ds.refLat, ds.refLon);

        int cBase = countSuccess(base);
        int cNoInnov = countSuccess(noInnov);
        int cZeroVel = countSuccess(withZeroVel);
        int cDopplerSnr = countSuccess(withDopplerSnr);

        log.info("");
        log.info("====== {} 逐项对照 ======", ds.name);
        log.info(String.format("%-30s 成功率  2D(m)   3D(m)", "模式"));
        log.info(String.format("%-30s %5.1f%%  %6.2f  %6.2f",
                "EKF+抗差 (基准)", 100.0 * cBase / epochs.size(), sBase[3], sBase[4]));
        log.info(String.format("%-30s %5.1f%%  %6.2f  %6.2f  Δ2D=%+.1f%%",
                "- 新息检验", 100.0 * cNoInnov / epochs.size(), sNoInnov[3], sNoInnov[4],
                sBase[3] > 0 ? (sNoInnov[3] - sBase[3]) / sBase[3] * 100 : 0));
        log.info(String.format("%-30s %5.1f%%  %6.2f  %6.2f  Δ2D=%+.1f%%",
                "+ 零速约束", 100.0 * cZeroVel / epochs.size(), sZeroVel[3], sZeroVel[4],
                sBase[3] > 0 ? (sZeroVel[3] - sBase[3]) / sBase[3] * 100 : 0));
        log.info(String.format("%-30s %5.1f%%  %6.2f  %6.2f  Δ2D=%+.1f%%",
                "+ 多普勒SNR", 100.0 * cDopplerSnr / epochs.size(), sDopplerSnr[3], sDopplerSnr[4],
                sBase[3] > 0 ? (sDopplerSnr[3] - sBase[3]) / sBase[3] * 100 : 0));
        log.info("");

        assertTrue(cBase > 0, ds.name + " 基准至少应有一个历元成功");
    }

    @Test
    @DisplayName("9. SPP逐项对照 - PPC Tokyo run1")
    void testIndividualOptPpcTokyoRun1() {
        runIndividualOptComparison(DATA_SETS[1]);
    }

    @Test
    @DisplayName("10. SPP逐项对照 - PPC Nagoya run1")
    void testIndividualOptPpcNagoyaRun1() {
        runIndividualOptComparison(DATA_SETS[4]);
    }

    @Test
    @DisplayName("11. SPP逐项对照 - UrbanNav Tokyo")
    void testIndividualOptUrbanNavTokyo() {
        runIndividualOptComparison(DATA_SETS[7]);
    }
}