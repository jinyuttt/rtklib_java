package org.rtklib.java.benchmarks;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.benchmarks.GCMonitor.GCMonitorSnapshot;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rinex.RinexSppProcessor;
import org.rtklib.java.rinex.RinexSppProcessor.SppResult;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end timing test for SPP/RTK processing pipelines.
 *
 * <p>Measures whole-program epoch throughput, complementing JMH micro-benchmarks
 * for Tier 0 baseline establishment. Uses existing test-data RINEX 3.x files.</p>
 *
 * <p><b>Important: Kalman implementation varies by processing mode:</b></p>
 * <table>
 *   <tr><th>Mode</th><th>Kalman Backend</th><th>Typical State Dim (nx)</th></tr>
 *   <tr><td>SPP</td><td>SppCore internal LSQ (not KalmanFilter.update)</td><td>~4-5 (pos + clock)</td></tr>
 *   <tr><td>RTK</td><td>KalmanFilter.update()</td><td>~5-15 (GPS L1+L2), ~50-200 (multi-GNSS)</td></tr>
 *   <tr><td>PPP</td><td>KalmanFilter.update()</td><td>~50-300</td></tr>
 *   <tr><td>PPP-RTK</td><td>KalmanFilter.update()</td><td>~150-300</td></tr>
 * </table>
 *
 * <p>JMH KalmanFilter.update() results map to RTK/PPP/PPP-RTK modes, NOT SPP.</p>
 *
 * <p>Run with:
 * <pre>mvn test -pl rtklib-core "-Dtest=E2ETimingTest#testSppTiming+testRtkTiming" -DfailIfNoTests=false</pre>
 *
 * <p>GC data is captured programmatically via GarbageCollectorMXBean snapshots,
 * providing the Tier 0 end-to-end GC baseline and Tier 1 3B acceptance criteria.</p>
 */
@DisplayName("E2E Timing Baseline")
public class E2ETimingTest {

    private static final Logger log = LoggerFactory.getLogger(E2ETimingTest.class);

    private static final String SPP_OBS = "rinex/rinex304_gej_3034.21o";
    private static final String SPP_NAV = "nav/rinex304_mixed_sept.21p";
    private static final String RTK_ROVER = "rinex/rinex304_gej_3034.21o";
    private static final String RTK_BASE = "rinex/rinex304_gej_sept.21o";
    private static final String RTK_NAV = "nav/rinex304_mixed_sept.21p";

    private static boolean sppDataAvailable;
    private static boolean rtkDataAvailable;

    @BeforeAll
    static void checkData() {
        sppDataAvailable = TestDataConfig.hasTestDataFile(SPP_OBS)
                && TestDataConfig.hasTestDataFile(SPP_NAV);
        rtkDataAvailable = TestDataConfig.hasTestDataFile(RTK_ROVER)
                && TestDataConfig.hasTestDataFile(RTK_BASE)
                && TestDataConfig.hasTestDataFile(RTK_NAV);

        log.info("SPP data available: {} (dir={})", sppDataAvailable, TestDataConfig.getTestDataDir());
        log.info("RTK data available: {} (dir={})", rtkDataAvailable, TestDataConfig.getTestDataDir());
    }

    @Test
    @DisplayName("SPP end-to-end timing + GC")
    void testSppTiming() {
        Assumptions.assumeTrue(sppDataAvailable, "SPP test data not found in " + TestDataConfig.getTestDataDir());

        String obsPath = TestDataConfig.getTestDataFile(SPP_OBS);
        String navPath = TestDataConfig.getTestDataFile(SPP_NAV);

        PrcOpt opt = new PrcOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        log.info("=== SPP E2E Timing + GC ===");
        log.info("Obs: {}", obsPath);
        log.info("Nav: {}", navPath);

        System.gc();
        GCMonitor gcBefore = GCMonitor.snapshot();

        long start = System.nanoTime();
        SppResult result = RinexSppProcessor.processRinex(obsPath, navPath, opt);
        long elapsed = System.nanoTime() - start;

        GCMonitor gcAfter = GCMonitor.snapshot();
        GCMonitorSnapshot gcDelta = GCMonitor.diff(gcBefore, gcAfter);

        double msTotal = elapsed / 1_000_000.0;
        double msPerEpoch = result.totalEpochs > 0 ? msTotal / result.totalEpochs : 0;

        log.info(String.format("SPP total: %d epochs, %d success, %d fail",
                result.totalEpochs, result.successCount, result.failCount));
        log.info(String.format("SPP timing: total=%.1fms, avg=%.2fms/epoch", msTotal, msPerEpoch));
        log.info("SPP GC: {}", gcDelta.toString());
    }

    @Test
    @DisplayName("RTK end-to-end timing + GC")
    void testRtkTiming() {
        Assumptions.assumeTrue(rtkDataAvailable, "RTK test data not found in " + TestDataConfig.getTestDataDir());

        String roverPath = TestDataConfig.getTestDataFile(RTK_ROVER);
        String basePath = TestDataConfig.getTestDataFile(RTK_BASE);
        String navPath = TestDataConfig.getTestDataFile(RTK_NAV);

        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS;
        opt.nf = 1;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_CONT;
        opt.refpos = Constants.POSOPT_POS_XYZ;

        log.info("=== RTK E2E Timing + GC ===");
        log.info("Rover: {}", roverPath);
        log.info("Base:  {}", basePath);
        log.info("Nav:   {}", navPath);

        System.gc();
        GCMonitor gcBefore = GCMonitor.snapshot();

        long start = System.nanoTime();
        RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(roverPath, basePath, navPath, opt);
        long elapsed = System.nanoTime() - start;

        GCMonitor gcAfter = GCMonitor.snapshot();
        GCMonitorSnapshot gcDelta = GCMonitor.diff(gcBefore, gcAfter);

        double msTotal = elapsed / 1_000_000.0;
        double msPerEpoch = result.totalEpochs > 0 ? msTotal / result.totalEpochs : 0;

        log.info(String.format("RTK total: %d epochs, %d success",
                result.totalEpochs, result.successCount));
        log.info(String.format("RTK timing: total=%.1fms, avg=%.2fms/epoch", msTotal, msPerEpoch));
        log.info("RTK GC: {}", gcDelta.toString());
    }
}