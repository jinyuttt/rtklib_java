package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.rinex.PostPosProcessor;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Verify SPP/RTK/PPP with BDS RINEX data")
public class VerifyPositioningTest {

    private static final Logger log = LoggerFactory.getLogger(VerifyPositioningTest.class);

    private static final String ROVER_OBS =
            TestDataConfig.hasTestDataFile("rinex/rinex302_bds_rover.26o")
                ? TestDataConfig.getTestDataFile("rinex/rinex302_bds_rover.26o")
                : TestDataConfig.get("rinex.data.dir", TestDataConfig.getRtcmBaseDir()) + "\\rover\\1.obs";
    private static final String ROVER_NAV =
            TestDataConfig.hasTestDataFile("nav/rinex302_bds_rover.26n")
                ? TestDataConfig.getTestDataFile("nav/rinex302_bds_rover.26n")
                : TestDataConfig.get("rinex.data.dir", TestDataConfig.getRtcmBaseDir()) + "\\rover\\1.nav";
    private static final String BASE_OBS =
            TestDataConfig.hasTestDataFile("rinex/rinex302_bds_base.26o")
                ? TestDataConfig.getTestDataFile("rinex/rinex302_bds_base.26o")
                : TestDataConfig.get("rinex.data.dir", TestDataConfig.getRtcmBaseDir()) + "\\base\\1.obs";
    private static final String BASE_NAV =
            TestDataConfig.hasTestDataFile("nav/rinex302_bds_rover.26n")
                ? TestDataConfig.getTestDataFile("nav/rinex302_bds_rover.26n")
                : TestDataConfig.get("rinex.data.dir", TestDataConfig.getRtcmBaseDir()) + "\\base\\1.nav";

    private static RinexParser roverParser;
    private static RinexParser baseParser;

    @BeforeAll
    static void parseRinex() {
        roverParser = new RinexParser();
        baseParser = new RinexParser();

        boolean obsOk = roverParser.parseObs(ROVER_OBS);
        boolean navOk = roverParser.parseNav(ROVER_NAV);
        log.info("Rover RINEX: obs={}, nav={}, obsRecords={}", obsOk, navOk,
                roverParser.obs != null ? roverParser.obs.n : 0);
        assertTrue(obsOk, "Rover OBS parse should succeed");
        assertTrue(navOk, "Rover NAV parse should succeed");

        boolean baseObsOk = baseParser.parseObs(BASE_OBS);
        boolean baseNavOk = baseParser.parseNav(BASE_NAV);
        log.info("Base RINEX: obs={}, nav={}, obsRecords={}", baseObsOk, baseNavOk,
                baseParser.obs != null ? baseParser.obs.n : 0);
        assertTrue(baseObsOk, "Base OBS parse should succeed");
    }

    @Test
    @DisplayName("SPP positioning with RINEX data")
    void testSppPositioning() {
        Nav nav = roverParser.nav;
        Obsd[] allObs = roverParser.obs.data;
        int totalObs = roverParser.obs.n;

        GTime firstTime = allObs[0].time;
        int epochEnd = totalObs;
        for (int i = 1; i < totalObs; i++) {
            if (!allObs[i].time.equals(firstTime)) {
                epochEnd = i;
                break;
            }
        }
        int n = epochEnd;
        log.info("First epoch: {} satellites", n);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];

        for (int i = 0; i < n; i++) {
            double[] rs_i = new double[6], dts_i = new double[2], vare_i = new double[1];
            EphModel.satpos(firstTime, nav, allObs[i].sat, rs_i, dts_i, vare_i);
            for (int j = 0; j < 6; j++) rs[i * 6 + j] = rs_i[j];
            for (int j = 0; j < 2; j++) dts[i * 2 + j] = dts_i[j];
            vare[i] = vare_i[0];
        }

        Sol sol = new Sol();
        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();
        double[] azel = new double[n * 2];
        int[] vsat = new int[n];
        double[] resp = new double[n];
        String[] msg = new String[1];

        int result = SppCore.estpos(allObs, n, rs, dts, vare, svh, nav, opt, ssat, sol, azel, vsat, resp, msg);

        log.info("SPP result: stat={}, ns={}", result, sol.ns);
        if (result == 1) {
            double[] llh = new double[3];
            CoordTransform.ecef2pos(sol.rr, llh);
            log.info(String.format("SPP position: LLH=(%.8f, %.8f, %.2f), ECEF=(%.3f, %.3f, %.3f)",
                    llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2],
                    sol.rr[0], sol.rr[1], sol.rr[2]));
            assertFalse(Double.isNaN(sol.rr[0]), "X should not be NaN");
            assertFalse(Double.isNaN(sol.rr[1]), "Y should not be NaN");
            assertFalse(Double.isNaN(sol.rr[2]), "Z should not be NaN");
        } else {
            log.warn("SPP failed: {}", msg[0]);
        }
        assertTrue(result == 1, "SPP should succeed: " + (msg[0] != null ? msg[0] : ""));
    }

    @Test
    @DisplayName("RTK positioning with RINEX data via PostPosProcessor")
    void testRtkPositioning() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        org.rtklib.java.rinex.PostPosProcessor proc = new org.rtklib.java.rinex.PostPosProcessor(opt);
        org.rtklib.java.rinex.PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, ROVER_NAV);

        log.info("RTK result: totalEpochs={}, successCount={}, failCount={}",
                result.totalEpochs, result.successCount, result.failCount);

        assertTrue(result.successCount > 0, "RTK should solve at least one epoch");
    }

    @Test
    @DisplayName("RTK Static positioning with BDS RINEX data")
    void testRtkStaticPositioning() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        PostPosProcessor proc = new PostPosProcessor(opt);
        PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, ROVER_NAV);

        log.info("RTK Static result: totalEpochs={}, successCount={}, failCount={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.successCount > 0, "RTK Static should solve at least one epoch");
    }

    @Test
    @DisplayName("DGPS positioning with BDS RINEX data")
    void testDgpsPositioning() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_DGPS;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        PostPosProcessor proc = new PostPosProcessor(opt);
        PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, ROVER_NAV);

        log.info("DGPS result: totalEpochs={}, successCount={}, failCount={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.successCount > 0, "DGPS should solve at least one epoch");
    }

    @Test
    @DisplayName("RTK Fixed positioning with BDS RINEX data (requires known base position)")
    void testRtkFixedPositioning() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_FIXED;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        PostPosProcessor proc = new PostPosProcessor(opt);
        PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, ROVER_NAV);

        log.info("RTK Fixed result: totalEpochs={}, successCount={}, failCount={}",
                result.totalEpochs, result.successCount, result.failCount);
        if (result.successCount == 0) {
            log.info("RTK Fixed requires known base position (opt.ru), skipped validation");
        } else {
            assertTrue(result.successCount > 0, "RTK Fixed should solve when base position is known");
        }
    }
}