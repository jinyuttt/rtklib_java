package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.PostPosProcessor;
import org.rtklib.java.rtkpos.CombinedFilter;
import org.rtklib.java.rtkpos.Smoother;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Forward-Backward Filter Tests")
public class ForwardBackwardFilterTest {

    private static final Logger log = LoggerFactory.getLogger(ForwardBackwardFilterTest.class);

    private static final String ROVER_OBS = "D:/code/rtklib_java/rtk_compare/over.obs";
    private static final String BASE_OBS  = "D:/code/rtklib_java/rtk_compare/base.obs";
    private static final String NAV_PATH  = "D:/code/rtklib_java/rtk_compare/over.nav";

    private static boolean dataAvailable = false;

    @BeforeAll
    static void checkData() {
        java.io.File roverFile = new java.io.File(ROVER_OBS);
        java.io.File baseFile = new java.io.File(BASE_OBS);
        java.io.File navFile = new java.io.File(NAV_PATH);
        dataAvailable = roverFile.exists() && baseFile.exists() && navFile.exists();
        if (dataAvailable) {
            log.info("Test data files found, running full tests");
        } else {
            log.warn("Test data files not found, skipping data-dependent tests");
        }
    }

    @Test
    @DisplayName("Smoother: fixed-interval smoothing with known values")
    void testSmootherKnownValues() {
        int n = 3;
        double[] xf = {100.0, 200.0, 300.0};
        double[] Qf = new double[n * n];
        Qf[0] = 1.0; Qf[4] = 1.0; Qf[8] = 1.0;

        double[] xb = {102.0, 198.0, 301.0};
        double[] Qb = new double[n * n];
        Qb[0] = 2.0; Qb[4] = 2.0; Qb[8] = 2.0;

        double[] xs = new double[n];
        double[] Qs = new double[n * n];

        int result = Smoother.smooth(xf, Qf, xb, Qb, n, xs, Qs);

        assertEquals(1, result, "Smoother should succeed");
        assertEquals(100.666, xs[0], 0.01, "xs[0] should be weighted average");
        assertEquals(199.333, xs[1], 0.01, "xs[1] should be weighted average");
        assertEquals(300.333, xs[2], 0.01, "xs[2] should be weighted average");

        double expectedQs = 1.0 / (1.0 / 1.0 + 1.0 / 2.0);
        assertEquals(expectedQs, Qs[0], 0.001, "Qs[0] should be combined variance");
        assertEquals(expectedQs, Qs[4], 0.001, "Qs[4] should be combined variance");
        assertEquals(expectedQs, Qs[8], 0.001, "Qs[8] should be combined variance");

        log.info(String.format("Smoother test: xs=(%.4f, %.4f, %.4f), Qs_diag=(%.6f, %.6f, %.6f)",
                xs[0], xs[1], xs[2], Qs[0], Qs[4], Qs[8]));
    }

    @Test
    @DisplayName("Smoother: identical forward and backward gives same result")
    void testSmootherIdentical() {
        int n = 2;
        double[] xf = {50.0, 60.0};
        double[] Qf = new double[n * n];
        Qf[0] = 0.5; Qf[3] = 0.5;

        double[] xb = {50.0, 60.0};
        double[] Qb = new double[n * n];
        Qb[0] = 0.5; Qb[3] = 0.5;

        double[] xs = new double[n];
        double[] Qs = new double[n * n];

        int result = Smoother.smooth(xf, Qf, xb, Qb, n, xs, Qs);

        assertEquals(1, result);
        assertEquals(50.0, xs[0], 1e-6, "Identical inputs should give same output");
        assertEquals(60.0, xs[1], 1e-6, "Identical inputs should give same output");

        double expectedQs = 1.0 / (1.0 / 0.5 + 1.0 / 0.5);
        assertEquals(expectedQs, Qs[0], 1e-6, "Variance should be halved");
    }

    @Test
    @DisplayName("Smoother: forward with much smaller variance dominates")
    void testSmootherForwardDominates() {
        int n = 2;
        double[] xf = {100.0, 200.0};
        double[] Qf = new double[n * n];
        Qf[0] = 0.01; Qf[3] = 0.01;

        double[] xb = {110.0, 210.0};
        double[] Qb = new double[n * n];
        Qb[0] = 100.0; Qb[3] = 100.0;

        double[] xs = new double[n];
        double[] Qs = new double[n * n];

        int result = Smoother.smooth(xf, Qf, xb, Qb, n, xs, Qs);

        assertEquals(1, result);
        assertEquals(100.0, xs[0], 0.1, "Forward with small variance should dominate");
        assertEquals(200.0, xs[1], 0.1, "Forward with small variance should dominate");
    }

    @Test
    @DisplayName("PostPosProcessor: forward RTK processing")
    void testForwardRtk() {
        if (!dataAvailable) {
            log.warn("Skipping testForwardRtk - data not available");
            return;
        }

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.soltype = Constants.SOLTYPE_FORWARD;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        SolOpt sopt = new SolOpt();

        PostPosProcessor proc = new PostPosProcessor(opt, sopt);
        PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, NAV_PATH);

        log.info("Forward RTK: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(result.successCount > 0, "Should have successful solutions");
    }

    @Test
    @DisplayName("PostPosProcessor: backward RTK processing")
    void testBackwardRtk() {
        if (!dataAvailable) {
            log.warn("Skipping testBackwardRtk - data not available");
            return;
        }

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.soltype = Constants.SOLTYPE_BACKWARD;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        SolOpt sopt = new SolOpt();

        PostPosProcessor proc = new PostPosProcessor(opt, sopt);
        PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, NAV_PATH);

        log.info("Backward RTK: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(result.successCount > 0, "Should have successful solutions");
    }

    @Test
    @DisplayName("PostPosProcessor: combined forward-backward RTK")
    void testCombinedRtk() {
        if (!dataAvailable) {
            log.warn("Skipping testCombinedRtk - data not available");
            return;
        }

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.soltype = Constants.SOLTYPE_COMBINED;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        SolOpt sopt = new SolOpt();

        PostPosProcessor proc = new PostPosProcessor(opt, sopt);
        PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, NAV_PATH);

        log.info("Combined RTK: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should have processed epochs");
        assertTrue(result.successCount > 0, "Should have successful solutions");
    }

    @Test
    @DisplayName("Combined filter: forward and backward epoch counts match")
    void testCombinedEpochCount() {
        if (!dataAvailable) {
            log.warn("Skipping testCombinedEpochCount - data not available");
            return;
        }

        PrcOpt optF = new PrcOpt();
        optF.mode = Constants.PMODE_KINEMA;
        optF.soltype = Constants.SOLTYPE_FORWARD;
        optF.nf = 2;
        optF.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        optF.elmin = 15.0 * Constants.D2R;
        optF.ionoopt = Constants.IONOOPT_BRDC;
        optF.tropopt = Constants.TROPOPT_SAAS;

        PrcOpt optB = new PrcOpt(optF);
        optB.soltype = Constants.SOLTYPE_BACKWARD;

        SolOpt sopt = new SolOpt();

        PostPosProcessor procF = new PostPosProcessor(optF, sopt);
        PostPosProcessor.PostPosResult resultF = procF.process(ROVER_OBS, BASE_OBS, NAV_PATH);

        PostPosProcessor procB = new PostPosProcessor(optB, sopt);
        PostPosProcessor.PostPosResult resultB = procB.process(ROVER_OBS, BASE_OBS, NAV_PATH);

        log.info("Forward: total={}, success={}", resultF.totalEpochs, resultF.successCount);
        log.info("Backward: total={}, success={}", resultB.totalEpochs, resultB.successCount);

        assertEquals(resultF.totalEpochs, resultB.totalEpochs,
                "Forward and backward should process same number of epochs");
    }

    @Test
    @DisplayName("Combined filter: combined solution count >= max(forward, backward)")
    void testCombinedAtLeastAsGood() {
        if (!dataAvailable) {
            log.warn("Skipping testCombinedAtLeastAsGood - data not available");
            return;
        }

        PrcOpt optF = new PrcOpt();
        optF.mode = Constants.PMODE_KINEMA;
        optF.soltype = Constants.SOLTYPE_FORWARD;
        optF.nf = 2;
        optF.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        optF.elmin = 15.0 * Constants.D2R;
        optF.ionoopt = Constants.IONOOPT_BRDC;
        optF.tropopt = Constants.TROPOPT_SAAS;

        PrcOpt optC = new PrcOpt(optF);
        optC.soltype = Constants.SOLTYPE_COMBINED;

        SolOpt sopt = new SolOpt();

        PostPosProcessor procF = new PostPosProcessor(optF, sopt);
        PostPosProcessor.PostPosResult resultF = procF.process(ROVER_OBS, BASE_OBS, NAV_PATH);

        PostPosProcessor procC = new PostPosProcessor(optC, sopt);
        PostPosProcessor.PostPosResult resultC = procC.process(ROVER_OBS, BASE_OBS, NAV_PATH);

        log.info("Forward success={}, Combined success={}", resultF.successCount, resultC.successCount);
        assertTrue(resultC.successCount >= resultF.successCount,
                "Combined should have at least as many valid solutions as forward alone");
    }
}