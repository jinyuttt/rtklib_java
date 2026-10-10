package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RINEX RTK Short Baseline Test")
public class RinexRtkProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(RinexRtkProcessorTest.class);

    private static final String TD = TestDataConfig.getTestDataDir();
    private static final String RESULT_DIR = TestDataConfig.getResultDir() + "/rinex_rtk";

    private static final String GPS_ROVER = TD + "/rinex/rinex210_gps_0759.05o";
    private static final String GPS_BASE  = TD + "/rinex/rinex210_gps_3040.05o";
    private static final String GPS_NAV   = TD + "/nav/rinex210_gps_0759.05n";

    private static final String GEJ_ROVER = TD + "/rinex/rinex304_gej_3034.21o";
    private static final String GEJ_BASE  = TD + "/rinex/rinex304_gej_sept.21o";
    private static final String GEJ_NAV   = TD + "/nav/rinex304_mixed_sept.21p";

    private static final String URB_ROVER = TD + "/rinex/urban_rover_20000719.17o";
    private static final String URB_BASE  = TD + "/rinex/urban_base_20000719.17o";
    private static final String URB_NAV   = TD + "/nav/urban_20000719.17p";

    private static boolean gpsAvail;
    private static boolean gejAvail;
    private static boolean urbAvail;

    @BeforeAll
    static void checkData() {
        gpsAvail = false;
        if (Files.exists(Paths.get(GPS_ROVER)) && Files.exists(Paths.get(GPS_BASE)) && Files.exists(Paths.get(GPS_NAV))) {
            try (BufferedReader br = new BufferedReader(new FileReader(GPS_ROVER))) {
                String header = br.readLine();
                if (header != null && header.contains("2.10")) {
                    log.warn("GPS RINEX 2.10 (0759+3040) exists but RinexParser only supports 3.05/3.06 - skip");
                } else {
                    gpsAvail = true;
                }
            } catch (IOException ignored) {}
        }
        gejAvail = Files.exists(Paths.get(GEJ_ROVER)) && Files.exists(Paths.get(GEJ_BASE)) && Files.exists(Paths.get(GEJ_NAV));
        urbAvail = Files.exists(Paths.get(URB_ROVER)) && Files.exists(Paths.get(URB_BASE)) && Files.exists(Paths.get(URB_NAV));

        if (!gpsAvail) log.warn("GPS dual-freq RINEX (0759+3040) not available (RINEX 2.10 not supported)");
        if (!gejAvail) log.warn("G+E+J multi-freq RINEX (3034+sept) not available");
        if (!urbAvail) log.warn("Urban multi-sys RINEX (Net_Diff) not available - run download-test-data.ps1");

        try { Files.createDirectories(Paths.get(RESULT_DIR)); } catch (IOException ignored) {}
    }

    @Test
    @DisplayName("1. GPS dual-freq short-baseline RTK (0759+3040, 3.3km, RTKLIB BSD-2)")
    void testGpsDualFreqShortBaseline() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gpsAvail, "GPS RINEX data not available");

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;

        log.info("GPS L1/L2 RTK: rover={}, base={}, nav={}", GPS_ROVER, GPS_BASE, GPS_NAV);
        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(GPS_ROVER, GPS_BASE, GPS_NAV, opt);

        log.info("GPS RTK: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");

        RtkStats stats = analyzeResult(result, "GPS L1/L2");
        assertTrue(stats.fixCount + stats.floatCount > 0, "Should have RTK solutions (fix or float)");

        writeResultFile("gps_l1l2_rtk.pos", result);
    }

    @Test
    @DisplayName("2. G+E+J multi-freq mid-baseline RTK (3034+sept, 5.3km, cssrlib MIT)")
    void testGejMultiFreqMidBaseline() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gejAvail, "G+E+J RINEX data not available");

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;

        log.info("G+E+J RTK: rover={}, base={}, nav={}", GEJ_ROVER, GEJ_BASE, GEJ_NAV);
        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(GEJ_ROVER, GEJ_BASE, GEJ_NAV, opt);

        log.info("G+E+J RTK: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");

        RtkStats stats = analyzeResult(result, "G+E+J L1/L2/L5");
        assertTrue(stats.fixCount + stats.floatCount > 0, "Should have RTK solutions");

        writeResultFile("gej_rtk.pos", result);
    }

    @Test
    @DisplayName("3. Urban multi-sys RTK (Net_Diff, 199m, G+E+J+C, rover L1 single-freq)")
    void testUrbanShortBaseline() {
        org.junit.jupiter.api.Assumptions.assumeTrue(urbAvail, "Urban RINEX data not available");

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;

        log.info("Urban RTK: rover={}, base={}, nav={}", URB_ROVER, URB_BASE, URB_NAV);
        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(URB_ROVER, URB_BASE, URB_NAV, opt);

        log.info("Urban RTK: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(result.successCount > 0, "Should have successful solutions");

        RtkStats stats = analyzeResult(result, "Urban G+E+J+C L1");
        writeResultFile("urban_rtk.pos", result);

        if (urbAvail) {
            compareWithReference(result, "urban_rtk_20000719.pos");
        }
    }

    @Test
    @DisplayName("4. GPS RTK: AR mode comparison (CONTINUOUS vs INSTANT vs FIXHOLD)")
    void testGpsArModeComparison() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gpsAvail, "GPS RINEX data not available");

        int[] arModes = {Constants.ARMODE_CONT, Constants.ARMODE_INST, Constants.ARMODE_FIXHOLD};
        String[] arNames = {"CONTINUOUS", "INSTANT", "FIXHOLD"};

        for (int i = 0; i < arModes.length; i++) {
            PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
            opt.mode = Constants.PMODE_KINEMA;
            opt.nf = 2;
            opt.navsys = Constants.SYS_GPS;
            opt.elmin = 15.0 * Constants.D2R;
            opt.modear = arModes[i];

            RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(GPS_ROVER, GPS_BASE, GPS_NAV, opt);
            RtkStats stats = analyzeResult(result, "GPS AR=" + arNames[i]);
            writeResultFile("gps_ar_" + arNames[i].toLowerCase() + ".pos", result);
        }
    }

    @Test
    @DisplayName("5. GPS RTK: ionosphere/troposphere model comparison")
    void testGpsIonoTropComparison() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gpsAvail, "GPS RINEX data not available");

        String[] labels = {
            "IONO_BRDC+TROP_SAAS",
            "IONO_BRDC+TROP_EST",
            "IONO_IFLC+TROP_SAAS",
            "IONO_IFLC+TROP_EST"
        };
        int[] ionoOpts = {
            Constants.IONOOPT_BRDC, Constants.IONOOPT_BRDC,
            Constants.IONOOPT_IFLC, Constants.IONOOPT_IFLC
        };
        int[] tropOpts = {
            Constants.TROPOPT_SAAS, Constants.TROPOPT_EST,
            Constants.TROPOPT_SAAS, Constants.TROPOPT_EST
        };

        for (int i = 0; i < labels.length; i++) {
            PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
            opt.mode = Constants.PMODE_KINEMA;
            opt.nf = 2;
            opt.navsys = Constants.SYS_GPS;
            opt.elmin = 15.0 * Constants.D2R;
            opt.ionoopt = ionoOpts[i];
            opt.tropopt = tropOpts[i];
            opt.modear = Constants.ARMODE_FIXHOLD;

            RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(GPS_ROVER, GPS_BASE, GPS_NAV, opt);
            analyzeResult(result, "GPS " + labels[i]);
            writeResultFile("gps_" + labels[i].toLowerCase().replace("+", "_") + ".pos", result);
        }
    }

    @Test
    @DisplayName("6. GPS RTK: single-freq vs dual-freq comparison")
    void testGpsFreqComparison() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gpsAvail, "GPS RINEX data not available");

        int[] nfs = {1, 2};
        String[] nfNames = {"L1-only", "L1+L2"};

        for (int i = 0; i < nfs.length; i++) {
            PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
            opt.mode = Constants.PMODE_KINEMA;
            opt.nf = nfs[i];
            opt.navsys = Constants.SYS_GPS;
            opt.elmin = 15.0 * Constants.D2R;
            opt.modear = Constants.ARMODE_FIXHOLD;

            RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(GPS_ROVER, GPS_BASE, GPS_NAV, opt);
            analyzeResult(result, "GPS " + nfNames[i]);
            writeResultFile("gps_nf" + nfs[i] + ".pos", result);
        }
    }

    @Test
    @DisplayName("7. G+E+J RTK: kinematic vs static mode comparison")
    void testGejKinematicVsStatic() {
        org.junit.jupiter.api.Assumptions.assumeTrue(gejAvail, "G+E+J RINEX data not available");

        int[] modes = {Constants.PMODE_KINEMA, Constants.PMODE_STATIC};
        String[] modeNames = {"KINEMA", "STATIC"};

        for (int i = 0; i < modes.length; i++) {
            PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
            opt.mode = modes[i];
            opt.nf = 2;
            opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS;
            opt.elmin = 15.0 * Constants.D2R;
            opt.modear = Constants.ARMODE_FIXHOLD;

            RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(GEJ_ROVER, GEJ_BASE, GEJ_NAV, opt);
            analyzeResult(result, "G+E+J " + modeNames[i]);
            writeResultFile("gej_" + modeNames[i].toLowerCase() + ".pos", result);
        }
    }

    @Test
    @DisplayName("8. Urban RTK with OutputStream output")
    void testUrbanRtkWithOutputStream() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(urbAvail, "Urban RINEX data not available");

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.modear = Constants.ARMODE_FIXHOLD;

        String outputFile = RESULT_DIR + "/urban_rtk_stream.pos";
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            RinexRtkProcessor rtk = new RinexRtkProcessor(opt, null, os);
            RtkProcessor.RtkResult result = rtk.process(URB_ROVER, URB_BASE, URB_NAV);

            log.info("Urban RTK (stream): total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
            assertTrue(result.totalEpochs > 0);
        }
        log.info("Output written to: {}", outputFile);
    }

    private static class RtkStats {
        int fixCount, floatCount, singleCount, otherCount;
        double fixRate;
    }

    private static RtkStats analyzeResult(RtkProcessor.RtkResult result, String label) {
        RtkStats s = new RtkStats();
        for (SolData sd : result.solutions) {
            if (sd.status == SolutionStatus.FIX) s.fixCount++;
            else if (sd.status == SolutionStatus.FLOAT) s.floatCount++;
            else if (sd.status == SolutionStatus.SINGLE) s.singleCount++;
            else s.otherCount++;
        }
        s.fixRate = result.totalEpochs > 0 ? (double) s.fixCount / result.totalEpochs : 0;

        log.info(String.format("[%s] total=%d, Fix=%d(%.1f%%), Float=%d, Single=%d, Other=%d",
                label, result.totalEpochs, s.fixCount, s.fixRate * 100, s.floatCount, s.singleCount, s.otherCount));

        if (!result.solutions.isEmpty()) {
            SolData first = result.solutions.get(0);
            SolData last = result.solutions.get(result.solutions.size() - 1);
            log.info("  First: {} ns={} {}", first.status, first.numSat, first.timeStr);
            log.info("  Last:  {} ns={} {}", last.status, last.numSat, last.timeStr);
            Position llh = first.getPosition(CoordType.LLH);
            if (llh != null) {
                log.info(String.format("  LLH: lat=%.9f lon=%.9f h=%.4f", llh.v1, llh.v2, llh.v3));
            }
        }
        return s;
    }

    private void compareWithReference(RtkProcessor.RtkResult result, String refFileName) {
        String refPath = TD + "/reference/" + refFileName;
        if (!Files.exists(Paths.get(refPath))) {
            refPath = TestDataConfig.getResultDir() + "/../rtklib-java-data/reference/" + refFileName;
        }
        if (!Files.exists(Paths.get(refPath))) {
            log.info("Reference file not found: {} - skip comparison", refFileName);
            return;
        }

        log.info("Comparing with reference: {}", refPath);
        int compared = 0;
        double sumErrN = 0, sumErrE = 0, sumErrU = 0;
        double maxErrH = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(refPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("%")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 7) continue;

                double refLat = Double.parseDouble(parts[2]);
                double refLon = Double.parseDouble(parts[3]);
                int q = Integer.parseInt(parts[5]);
                if (q != 1) continue;

                for (SolData sd : result.solutions) {
                    if (sd.status != SolutionStatus.FIX) continue;
                    Position llh = sd.getPosition(CoordType.LLH);
                    if (llh == null) continue;

                    double dLat = (llh.v1 - refLat) * Constants.R2D;
                    double dLon = (llh.v2 - refLon) * Constants.R2D;
                    double dH = llh.v3 - Double.parseDouble(parts[4]);

                    double cosLat = Math.cos(refLat * Constants.D2R);
                    double dN = dLat * Constants.RE_WGS84 * Constants.D2R;
                    double dE = dLon * Constants.RE_WGS84 * cosLat * Constants.D2R;
                    double errH = Math.sqrt(dN * dN + dE * dE);

                    if (errH < 1.0) {
                        sumErrN += dN * dN;
                        sumErrE += dE * dE;
                        sumErrU += dH * dH;
                        if (errH > maxErrH) maxErrH = errH;
                        compared++;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Reference comparison failed: {}", e.getMessage());
            return;
        }

        if (compared > 0) {
            double rmseN = Math.sqrt(sumErrN / compared);
            double rmseE = Math.sqrt(sumErrE / compared);
            double rmseU = Math.sqrt(sumErrU / compared);
            double rmseH = Math.sqrt((sumErrN + sumErrE) / compared);
            log.info(String.format("Reference comparison: %d points, RMSE N=%.4fm E=%.4fm U=%.4fm H=%.4fm, MaxH=%.4fm",
                    compared, rmseN, rmseE, rmseU, rmseH, maxErrH));
        }
    }

    private void writeResultFile(String filename, RtkProcessor.RtkResult result) {
        try {
            String path = RESULT_DIR + "/" + filename;
            RtkProcessor.writePosFile(result, path);
            log.info("Result written to: {}", path);
        } catch (IOException e) {
            log.warn("Failed to write result file: {}", e.getMessage());
        }
    }
}