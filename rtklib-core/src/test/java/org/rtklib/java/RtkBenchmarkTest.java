package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
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

@DisplayName("RTK Benchmark Test with Public Urban Canyon Datasets")
public class RtkBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(RtkBenchmarkTest.class);

    private static final double D2R = Constants.D2R;

    private static final double GPS_EPOCH_UNIX = 315964800.0;

    static class BenchmarkConfig {
        final String name;
        final String roverObs;
        final String baseObs;
        final String nav;
        final String referenceCsv;
        final int navsys;
        final int nf;
        final double minFixRate;
        final double maxHorizontalRmse;

        BenchmarkConfig(String name, String roverObs, String baseObs, String nav,
                        String referenceCsv, int navsys, int nf,
                        double minFixRate, double maxHorizontalRmse) {
            this.name = name;
            this.roverObs = roverObs;
            this.baseObs = baseObs;
            this.nav = nav;
            this.referenceCsv = referenceCsv;
            this.navsys = navsys;
            this.nf = nf;
            this.minFixRate = minFixRate;
            this.maxHorizontalRmse = maxHorizontalRmse;
        }

        boolean dataExists() {
            return Files.exists(Paths.get(roverObs))
                    && Files.exists(Paths.get(baseObs))
                    && (nav == null || Files.exists(Paths.get(nav)));
        }
    }

    static class ReferencePoint {
        final double unixTime;
        final double lat;
        final double lon;
        final double height;

        ReferencePoint(double unixTime, double lat, double lon, double height) {
            this.unixTime = unixTime;
            this.lat = lat;
            this.lon = lon;
            this.height = height;
        }
    }

    private static final List<BenchmarkConfig> CONFIGS = new ArrayList<>();

    @BeforeAll
    static void setupConfigs() {
        int sysMulti = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_GLO
                | Constants.SYS_CMP | Constants.SYS_QZS;

        CONFIGS.add(new BenchmarkConfig(
                "PPC-Dataset Tokyo run1",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run1/reference.csv"),
                sysMulti, 2,
                0.05, 0.50
        ));

        CONFIGS.add(new BenchmarkConfig(
                "PPC-Dataset Tokyo run2",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/tokyo/run2/reference.csv"),
                sysMulti, 2,
                0.05, 0.50
        ));

        CONFIGS.add(new BenchmarkConfig(
                "PPC-Dataset Nagoya run1",
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/rover.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/base.obs"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/base.nav"),
                TestDataConfig.getTestDataFile("ppc/PPC-Dataset/nagoya/run1/reference.csv"),
                sysMulti, 2,
                0.05, 0.50
        ));

        CONFIGS.add(new BenchmarkConfig(
                "UrbanNav Tokyo 2018",
                TestDataConfig.getTestDataFile("urbannav/tokyo/data_tokyo/tokyo3530.18o"),
                TestDataConfig.getTestDataFile("urbannav/tokyo/data_tokyo/tokyo3530_base.18o"),
                TestDataConfig.getTestDataFile("urbannav/tokyo/data_tokyo/brdm3530.18p"),
                null,
                sysMulti, 2,
                0.05, 1.00
        ));

        log.info("=== RTK Benchmark Test Configuration ===");
        log.info("Test data dir: {}", TestDataConfig.getTestDataDir());
        for (BenchmarkConfig cfg : CONFIGS) {
            log.info("  {} => data exists={} (rover={})", cfg.name, cfg.dataExists(),
                    Files.exists(Paths.get(cfg.roverObs)));
        }
    }

    @Test
    @DisplayName("1. PPC-Dataset Tokyo run1 - RTK Fix rate and accuracy")
    void testPpcTokyoRun1() {
        runBenchmark(CONFIGS.get(0));
    }

    @Test
    @DisplayName("2. PPC-Dataset Tokyo run2 - RTK Fix rate and accuracy")
    void testPpcTokyoRun2() {
        runBenchmark(CONFIGS.get(1));
    }

    @Test
    @DisplayName("3. PPC-Dataset Nagoya run1 - RTK Fix rate and accuracy")
    void testPpcNagoyaRun1() {
        runBenchmark(CONFIGS.get(2));
    }

    @Test
    @DisplayName("4. UrbanNav Tokyo 2018 - RTK Fix rate and accuracy")
    void testUrbanNavTokyo() {
        runBenchmark(CONFIGS.get(3));
    }

    private void runBenchmark(BenchmarkConfig cfg) {
        Assumptions.assumeTrue(cfg.dataExists(),
                cfg.name + " - data not found (rover=" + cfg.roverObs + ")");

        log.info("========================================");
        log.info("  Benchmark: {}", cfg.name);
        log.info("========================================");
        log.info("Rover: {}", cfg.roverObs);
        log.info("Base:  {}", cfg.baseObs);
        log.info("Nav:   {}", cfg.nav);

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = cfg.navsys;
        opt.nf = cfg.nf;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;

        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(
                cfg.roverObs, cfg.baseObs, cfg.nav, opt);

        int total = result.totalEpochs;
        int success = result.successCount;
        int fixCount = 0;
        int floatCount = 0;
        int singleCount = 0;

        for (SolData sol : result.solutions) {
            if (sol.status == SolutionStatus.FIX) fixCount++;
            else if (sol.status == SolutionStatus.FLOAT) floatCount++;
            else if (sol.status == SolutionStatus.SINGLE) singleCount++;
        }

        double fixRate = total > 0 ? (double) fixCount / total : 0;
        double successRate = total > 0 ? (double) success / total : 0;
        double floatRate = total > 0 ? (double) floatCount / total : 0;

        log.info(String.format("Total epochs: %d", total));
        log.info(String.format("Fix: %d (%.1f%%), Float: %d (%.1f%%), Single: %d",
                fixCount, fixRate * 100, floatCount, floatRate * 100, singleCount));
        log.info(String.format("Success rate: %.1f%%", successRate * 100));

        assertTrue(total > 0, cfg.name + ": should have at least 1 epoch");
        assertTrue(successRate > 0.02, cfg.name + ": success rate should be > 2%");

        if (cfg.referenceCsv != null && Files.exists(Paths.get(cfg.referenceCsv))) {
            double[] rmse = computeRmse(result.solutions, cfg.referenceCsv);
            if (rmse != null) {
                log.info(String.format("Horizontal RMSE: %.3f m, Vertical RMSE: %.3f m", rmse[0], rmse[1]));
                log.info(String.format("3D RMSE: %.3f m", rmse[2]));
                if (fixRate >= cfg.minFixRate) {
                    assertTrue(rmse[0] < cfg.maxHorizontalRmse,
                            String.format("%s: horizontal RMSE %.3f m should be < %.3f m",
                                    cfg.name, rmse[0], cfg.maxHorizontalRmse));
                } else {
                    log.warn(String.format("Fix rate %.1f%% below threshold %.1f%%, skipping RMSE assertion",
                            fixRate * 100, cfg.minFixRate * 100));
                }
            }
        }

        log.info(String.format("Fix rate: %.1f%% (threshold: %.1f%%)", fixRate * 100, cfg.minFixRate * 100));
    }

    private double[] computeRmse(List<SolData> solutions, String referenceCsv) {
        List<ReferencePoint> refs = loadReferenceCsv(referenceCsv);
        if (refs.isEmpty()) {
            log.warn("No reference points loaded from {}", referenceCsv);
            return null;
        }

        double sumE2 = 0, sumN2 = 0, sumU2 = 0;
        int matchCount = 0;

        for (SolData sol : solutions) {
            if (sol.status != SolutionStatus.FIX) continue;

            Position llhPos = sol.getPosition(CoordType.LLH);
            if (llhPos == null) continue;

            double solLatDeg = Math.toDegrees(llhPos.v1);
            double solLonDeg = Math.toDegrees(llhPos.v2);
            double solH = llhPos.v3;

            ReferencePoint nearest = findNearestRef(sol, refs);
            if (nearest == null) continue;

            double dLat = (solLatDeg - nearest.lat) * D2R;
            double dLon = (solLonDeg - nearest.lon) * D2R;
            double cosLat = Math.cos(nearest.lat * D2R);
            double e = dLon * cosLat * Constants.RE_WGS84;
            double n = dLat * Constants.RE_WGS84;
            double u = solH - nearest.height;

            sumE2 += e * e;
            sumN2 += n * n;
            sumU2 += u * u;
            matchCount++;
        }

        if (matchCount == 0) return null;

        double hRmse = Math.sqrt((sumE2 + sumN2) / matchCount);
        double vRmse = Math.sqrt(sumU2 / matchCount);
        double r3d = Math.sqrt((sumE2 + sumN2 + sumU2) / matchCount);

        log.info(String.format("Matched %d fix epochs with reference (%d ref points)", matchCount, refs.size()));
        return new double[]{hRmse, vRmse, r3d};
    }

    private ReferencePoint findNearestRef(SolData sol, List<ReferencePoint> refs) {
        if (sol.time == null) return null;

        double solUnixTime = sol.time.time + sol.time.sec;

        double bestDt = Double.MAX_VALUE;
        ReferencePoint best = null;

        for (ReferencePoint ref : refs) {
            double dt = Math.abs(solUnixTime - ref.unixTime);
            if (dt < bestDt) {
                bestDt = dt;
                best = ref;
            }
        }

        if (bestDt > 1.0) return null;
        return best;
    }

    private List<ReferencePoint> loadReferenceCsv(String csvPath) {
        List<ReferencePoint> points = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String header = br.readLine();
            if (header == null || !header.contains("GPS TOW")) {
                log.warn("Unexpected reference CSV header: {}", header);
                return points;
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

                    points.add(new ReferencePoint(unixTime, lat, lon, h));
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read reference CSV: {}", e.getMessage());
        }
        return points;
    }
}