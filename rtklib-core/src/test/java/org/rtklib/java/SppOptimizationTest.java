package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.common.SatUtils;
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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SPP 优化功能测试（基于 MobileGNSS-SPP 数据）")
public class SppOptimizationTest {

    private static final Logger log = LoggerFactory.getLogger(SppOptimizationTest.class);
    private static final String DATA_BASE =
            TestDataConfig.hasTestDataFile("rinex/rinex304_gejc_elevated.25o")
                ? TestDataConfig.getTestDataDir()
                : "D:\\rtklib\\rtklib_java\\reference-projects\\MobileGNSS-SPP\\data";

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

        SppResult(GTime time, double[] llh, int stat, int ns) {
            this.time = time;
            this.llh = llh;
            this.stat = stat;
            this.ns = ns;
        }
    }

    static class RefPosition {
        final GTime time;
        final double[] llh;

        RefPosition(GTime time, double[] llh) {
            this.time = time;
            this.llh = llh;
        }
    }

    private static List<ObsEpoch> obsEpochs;
    private static Nav nav;
    private static List<RefPosition> refPositions;
    private static String currentDataset;

    private static List<ObsEpoch> groupObsByEpoch(Obsd[] data, int n) {
        List<ObsEpoch> groups = new ArrayList<>();
        if (n == 0) return groups;

        List<Obsd> current = new ArrayList<>();
        GTime currentTime = data[0].time;

        for (int i = 0; i < n; i++) {
            if (!data[i].time.equals(currentTime)) {
                Obsd[] arr = current.toArray(new Obsd[0]);
                groups.add(new ObsEpoch(currentTime, arr, arr.length));
                current = new ArrayList<>();
                currentTime = data[i].time;
            }
            current.add(data[i]);
        }
        if (!current.isEmpty()) {
            Obsd[] arr = current.toArray(new Obsd[0]);
            groups.add(new ObsEpoch(currentTime, arr, arr.length));
        }
        return groups;
    }

    private static List<RefPosition> parseBaselineNmea(String filePath) {
        List<RefPosition> refs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("NMEA,$GPGGA") && !line.startsWith("$GPGGA")) continue;

                String[] fields = line.substring(line.indexOf("$GPGGA")).split(",");
                if (fields.length < 15) continue;

                String utcStr = fields[1];
                String latStr = fields[2];
                String latNS = fields[3];
                String lonStr = fields[4];
                String lonEW = fields[5];
                double hgt = parseDouble(fields[9]);

                if (utcStr.isEmpty() || latStr.isEmpty() || lonStr.isEmpty()) continue;

                int hh = Integer.parseInt(utcStr.substring(0, 2));
                int mm = Integer.parseInt(utcStr.substring(2, 4));
                double ss = Double.parseDouble(utcStr.substring(4));

                int alat = (int)(Double.parseDouble(latStr) / 100.0);
                double blat = Double.parseDouble(latStr) - alat * 100.0;
                double lat = alat + blat / 60.0;
                if (latNS.equals("S")) lat = -lat;

                int alon = (int)(Double.parseDouble(lonStr) / 100.0);
                double blon = Double.parseDouble(lonStr) - alon * 100.0;
                double lon = alon + blon / 60.0;
                if (lonEW.equals("W")) lon = -lon;

                GTime time = new GTime();
                time.time = 0;
                time.sec = hh * 3600.0 + mm * 60.0 + ss;
                refs.add(new RefPosition(time, new double[]{lat * Constants.D2R, lon * Constants.D2R, hgt}));
            }
        } catch (IOException e) {
            log.warn("无法解析 baseline NMEA: {}", e.getMessage());
        }
        return refs;
    }

    private static RefPosition findClosestRef(GTime time) {
        if (refPositions.isEmpty()) return null;
        RefPosition best = refPositions.get(0);
        double bestDt = Math.abs(TimeSystem.timediff(time, best.time));
        for (RefPosition r : refPositions) {
            double dt = Math.abs(TimeSystem.timediff(time, r.time));
            if (dt < bestDt) {
                bestDt = dt;
                best = r;
            }
        }
        return bestDt < 5.0 ? best : null;
    }

    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static boolean loadData(String scenario, String dataId) {
        boolean useTestData = TestDataConfig.hasTestDataFile("rinex/rinex304_gejc_elevated.25o");
        String obsFile, navFile, baselineFile;
        if (useTestData) {
            String scenarioName = scenario.replace("01-opensky", "opensky")
                    .replace("02-street", "street")
                    .replace("03-downtown", "downtown")
                    .replace("04-elevated", "elevated");
            obsFile = TestDataConfig.getTestDataFile("rinex/rinex304_gejc_" + scenarioName + ".25o");
            navFile = TestDataConfig.getTestDataFile("nav/rinex304_gejc_" + scenarioName + ".25n");
            baselineFile = TestDataConfig.getTestDataFile("nmea/phone_" + scenarioName + ".nmea");
            if (!new java.io.File(obsFile).exists()) {
                log.warn("公共测试数据不存在: {}", obsFile);
                return false;
            }
        } else {
            obsFile = DATA_BASE + "\\" + scenario + "\\" + dataId + "\\rover.obs";
            navFile = DATA_BASE + "\\" + scenario + "\\" + dataId + "\\rover.nav";
            baselineFile = DATA_BASE + "\\" + scenario + "\\" + dataId + "\\baseline.nmea";
        }

        RinexParser parser = new RinexParser();
        if (!parser.parseObs(obsFile)) {
            log.warn("无法解析 OBS 文件: {}", obsFile);
            return false;
        }
        if (!parser.parseNav(navFile)) {
            log.warn("无法解析 NAV 文件: {}", navFile);
            return false;
        }

        nav = parser.nav;
        obsEpochs = groupObsByEpoch(parser.obs.data, parser.obs.n);
        refPositions = parseBaselineNmea(baselineFile);
        currentDataset = scenario + "/" + dataId;

        log.info("数据集 {}: {} 历元, eph={}, geph={}, seph={}, ref点={}",
                currentDataset, obsEpochs.size(), nav.n, nav.ng, nav.ns, refPositions.size());
        return true;
    }

    private static PrcOpt createOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 1;
        opt.navsys = 57;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.dynamics = 1;
        return opt;
    }

    private static List<SppResult> runSpp(PrcOpt opt, RtkConfig cfg) {
        List<SppResult> results = new ArrayList<>();
        SppEkfState ekfState = null;

        if (cfg != null && cfg.enableSppEkf) {
            int nxF = SppEkfState.computeNxF(opt);
            ekfState = new SppEkfState(nxF);
        }

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        Sol sol = new Sol();
        int successCount = 0;
        int firstFailEpoch = -1;
        int firstFailStat = 0;

        for (int ep = 0; ep < obsEpochs.size(); ep++) {
            ObsEpoch epoch = obsEpochs.get(ep);
            int n = epoch.n;

            double[] azel = new double[n * 2];
            int stat = PntPos.pntpos(epoch.obsd, n, nav, opt, sol, azel, ssat, cfg, ekfState);

            if (firstFailEpoch < 0 && stat != 1 && ep < 5) {
                firstFailEpoch = ep;
                firstFailStat = stat;
                log.warn("历元 {} 失败: stat={}, nsat={}, sol.stat={}", ep, stat, n, sol.stat);
            }

            if (stat == 1) {
                successCount++;
                double[] llh = new double[3];
                CoordTransform.ecef2pos(sol.rr, llh);
                results.add(new SppResult(new GTime(epoch.time), llh, sol.stat, sol.ns));
            } else {
                results.add(new SppResult(new GTime(epoch.time), null, sol.stat, 0));
            }
        }

        log.info("  成功/总历元: {}/{}", successCount, obsEpochs.size());
        return results;
    }

    private static double[] computeStats(List<SppResult> results) {
        if (refPositions.isEmpty() || results.isEmpty()) return new double[]{0, 0, 0, 0};

        double sumE = 0, sumN = 0, sumU = 0;
        int count = 0;

        for (SppResult r : results) {
            if (r.llh == null) continue;
            RefPosition ref = findClosestRef(r.time);
            if (ref == null) continue;

            double lat = r.llh[0];
            double dN = Constants.RE_WGS84 + ref.llh[2];
            double dE = (Constants.RE_WGS84 + ref.llh[2]) * Math.cos(ref.llh[0]);

            double dLat = (lat - ref.llh[0]) * dN;
            double dLon = (r.llh[1] - ref.llh[1]) * dE;
            double dH = r.llh[2] - ref.llh[2];

            sumN += dLat * dLat;
            sumE += dLon * dLon;
            sumU += dH * dH;
            count++;
        }

        if (count == 0) return new double[]{0, 0, 0, 0};

        double rmsN = Math.sqrt(sumN / count);
        double rmsE = Math.sqrt(sumE / count);
        double rmsU = Math.sqrt(sumU / count);
        double rms3D = Math.sqrt((sumN + sumE + sumU) / count);

        return new double[]{rmsN, rmsE, rmsU, rms3D};
    }

    private static void runFullComparison(String scenario, String dataId) {
        if (!loadData(scenario, dataId)) {
            log.warn("跳过数据集 {}/{}", scenario, dataId);
            return;
        }

        PrcOpt opt = createOpt();

        log.info("--- 基准 LS-SPP ---");
        List<SppResult> baseline = runSpp(opt, null);

        RtkConfig cfgEkf = new RtkConfig();
        cfgEkf.enableSppEkf = true;

        log.info("--- EKF-SPP ---");
        List<SppResult> ekf = runSpp(opt, cfgEkf);

        RtkConfig cfgEkfRobust = new RtkConfig();
        cfgEkfRobust.enableSppEkf = true;
        cfgEkfRobust.enableSppRobust = true;

        log.info("--- EKF-SPP + 抗差估计 ---");
        List<SppResult> ekfRobust = runSpp(opt, cfgEkfRobust);

        RtkConfig cfgFull = new RtkConfig();
        cfgFull.enableSppEkf = true;
        cfgFull.enableSppRobust = true;
        cfgFull.enableSppZeroVel = true;
        cfgFull.enableSppDopplerSnr = true;

        log.info("--- EKF-SPP + 抗差 + 零速 + 多普勒SNR ---");
        List<SppResult> full = runSpp(opt, cfgFull);

        double[] sBaseline = computeStats(baseline);
        double[] sEkf = computeStats(ekf);
        double[] sEkfRobust = computeStats(ekfRobust);
        double[] sFull = computeStats(full);

        log.info("");
        log.info("====== {} RMS 精度对比 (m) ======", currentDataset);
        log.info(String.format("%-30s  N(%6.2f)  E(%6.2f)  U(%6.2f)  3D(%6.2f)",
                "基准 LS-SPP", sBaseline[0], sBaseline[1], sBaseline[2], sBaseline[3]));
        log.info(String.format("%-30s  N(%6.2f)  E(%6.2f)  U(%6.2f)  3D(%6.2f)",
                "EKF-SPP", sEkf[0], sEkf[1], sEkf[2], sEkf[3]));
        log.info(String.format("%-30s  N(%6.2f)  E(%6.2f)  U(%6.2f)  3D(%6.2f)",
                "EKF + 抗差", sEkfRobust[0], sEkfRobust[1], sEkfRobust[2], sEkfRobust[3]));
        log.info(String.format("%-30s  N(%6.2f)  E(%6.2f)  U(%6.2f)  3D(%6.2f)",
                "EKF + 抗差 + 零速 + SNR", sFull[0], sFull[1], sFull[2], sFull[3]));

        double improvement3d = sBaseline[3] > 0 ? (sBaseline[3] - sFull[3]) / sBaseline[3] * 100.0 : 0;
        log.info(String.format("3D RMS 改善: %.1f%%", improvement3d));
        log.info("");

        assertTrue(baseline.stream().filter(r -> r.llh != null).count() > 0,
                "基准 SPP 至少应有一个历元成功");
    }

    @Test
    @DisplayName("01-opensky/data01: 开阔天空场景")
    void testOpenSkyData01() {
        runFullComparison("01-opensky", "data01");
    }

    @Test
    @DisplayName("01-opensky/data02: 开阔天空场景2")
    void testOpenSkyData02() {
        runFullComparison("01-opensky", "data02");
    }

    @Test
    @DisplayName("02-street/data01: 街道场景")
    void testStreetData01() {
        runFullComparison("02-street", "data01");
    }

    @Test
    @DisplayName("02-street/data02: 街道场景2")
    void testStreetData02() {
        runFullComparison("02-street", "data02");
    }

    @Test
    @DisplayName("03-downtown/data01: 城市峡谷场景")
    void testDowntownData01() {
        runFullComparison("03-downtown", "data01");
    }

    @Test
    @DisplayName("03-downtown/data02: 城市峡谷场景2")
    void testDowntownData02() {
        runFullComparison("03-downtown", "data02");
    }

    @Test
    @DisplayName("04-elevated/data01: 高架场景")
    void testElevatedData01() {
        runFullComparison("04-elevated", "data01");
    }

    @Test
    @DisplayName("04-elevated/data02: 高架场景2")
    void testElevatedData02() {
        runFullComparison("04-elevated", "data02");
    }
}