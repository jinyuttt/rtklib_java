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
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Verify SPP/RTK/PPP after PppCore fixes")
public class VerifyPositioningTest {

    private static final Logger log = LoggerFactory.getLogger(VerifyPositioningTest.class);

    private static final String ROVER_OBS = "D:/code/rtklib_java/rtcm_conv/rover/1.obs";
    private static final String ROVER_NAV = "D:/code/rtklib_java/rtcm_conv/rover/1.nav";
    private static final String BASE_OBS  = "D:/code/rtklib_java/rtcm_conv/base/1.obs";
    private static final String BASE_NAV  = "D:/code/rtklib_java/rtcm_conv/base/1.nav";

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
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
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
    @DisplayName("RTK positioning with RINEX data")
    void testRtkPositioning() {
        Nav nav = roverParser.nav;
        if (baseParser.nav != null && baseParser.nav.n > 0) {
            if (baseParser.nav.eph != null) {
                for (Eph e : baseParser.nav.eph) {
                    if (e == null) continue;
                    boolean found = false;
                    for (Eph re : nav.eph) {
                        if (re != null && re.sat == e.sat) { found = true; break; }
                    }
                    if (!found) nav.eph[nav.n++] = e;
                }
            }
        }

        Obsd[] roverObs = roverParser.obs.data;
        int roverTotal = roverParser.obs.n;
        Obsd[] baseObs = baseParser.obs.data;
        int baseTotal = baseParser.obs.n;

        java.util.List<GTime> roverEpochs = new java.util.ArrayList<>();
        for (int i = 0; i < roverTotal; i++) {
            if (i == 0 || !roverObs[i].time.equals(roverObs[i - 1].time)) {
                roverEpochs.add(roverObs[i].time);
            }
        }
        java.util.List<GTime> baseEpochs = new java.util.ArrayList<>();
        for (int i = 0; i < baseTotal; i++) {
            if (i == 0 || !baseObs[i].time.equals(baseObs[i - 1].time)) {
                baseEpochs.add(baseObs[i].time);
            }
        }
        log.info("Rover epochs: {}, Base epochs: {}", roverEpochs.size(), baseEpochs.size());

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.rb = new double[]{-493099.6505, 5551400.7556, 3092551.4870};

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int solvedCount = 0;
        int fixCount = 0;
        int floatCount = 0;
        int singleCount = 0;

        int maxEpochs = Math.min(roverEpochs.size(), 30);
        for (int ei = 0; ei < maxEpochs; ei++) {
            GTime epochTime = roverEpochs.get(ei);

            int rStart = -1, rEnd = -1;
            for (int i = 0; i < roverTotal; i++) {
                if (roverObs[i].time.equals(epochTime)) {
                    if (rStart < 0) rStart = i;
                    rEnd = i + 1;
                }
            }
            int bStart = -1, bEnd = -1;
            for (int i = 0; i < baseTotal; i++) {
                if (baseObs[i].time.equals(epochTime)) {
                    if (bStart < 0) bStart = i;
                    bEnd = i + 1;
                }
            }
            if (rStart < 0 || bStart < 0) {
                log.info("RTK epoch {}: no match (rStart={}, bStart={})", ei, rStart, bStart);
                continue;
            }

            Obsd[] rObs = java.util.Arrays.copyOfRange(roverObs, rStart, rEnd);
            Obsd[] bObs = java.util.Arrays.copyOfRange(baseObs, bStart, bEnd);
            log.info("RTK epoch {}: rover={} sats, base={} sats", ei, rObs.length, bObs.length);

            Obsd[] comb = new Obsd[rObs.length + bObs.length];
            System.arraycopy(rObs, 0, comb, 0, rObs.length);
            System.arraycopy(bObs, 0, comb, rObs.length, bObs.length);
            for (int i = 0; i < rObs.length; i++) comb[i].rcv = 1;
            for (int i = 0; i < bObs.length; i++) comb[rObs.length + i].rcv = 2;

            int info = RtkCore.rtkpos(rtk, comb, comb.length, nav);

            double[] llh = new double[3];
            CoordTransform.ecef2pos(rtk.sol.rr, llh);
            String statStr = rtk.sol.stat == Constants.SOLQ_FIX ? "FIX" :
                             rtk.sol.stat == Constants.SOLQ_FLOAT ? "FLOAT" :
                             rtk.sol.stat == Constants.SOLQ_SINGLE ? "SINGLE" : "NONE";
            log.info(String.format("RTK epoch %d: stat=%s ns=%d LLH=(%.8f,%.8f,%.2f)",
                    ei, statStr, rtk.sol.ns,
                    llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2]));

            if (rtk.sol.stat != Constants.SOLQ_NONE) solvedCount++;
            if (rtk.sol.stat == Constants.SOLQ_FIX) fixCount++;
            else if (rtk.sol.stat == Constants.SOLQ_FLOAT) floatCount++;
            else if (rtk.sol.stat == Constants.SOLQ_SINGLE) singleCount++;
        }

        log.info("RTK summary: solved={}, fix={}, float={}, single={}", solvedCount, fixCount, floatCount, singleCount);
        assertTrue(solvedCount > 0, "RTK should solve at least one epoch");
    }

    @Test
    @DisplayName("PPP positioning with RINEX data")
    void testPppPositioning() {
        Nav nav = roverParser.nav;
        Obsd[] allObs = roverParser.obs.data;
        int totalObs = roverParser.obs.n;

        java.util.List<GTime> epochs = new java.util.ArrayList<>();
        java.util.List<Integer> epochStarts = new java.util.ArrayList<>();
        for (int i = 0; i < totalObs; i++) {
            if (i == 0 || !allObs[i].time.equals(allObs[i - 1].time)) {
                epochs.add(allObs[i].time);
                epochStarts.add(i);
            }
        }
        epochStarts.add(totalObs);
        log.info("PPP: {} epochs total", epochs.size());

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int solvedCount = 0;
        int maxEpochs = Math.min(epochs.size(), 30);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;

            RtkCore.rtkpos(rtk, epochObs, epochObs.length, nav);

            double[] llh = new double[3];
            CoordTransform.ecef2pos(rtk.sol.rr, llh);
            String statStr = rtk.sol.stat == Constants.SOLQ_PPP ? "PPP" :
                             rtk.sol.stat == Constants.SOLQ_SINGLE ? "SINGLE" : "NONE(" + rtk.sol.stat + ")";
            log.info(String.format("PPP epoch %d: stat=%s ns=%d LLH=(%.8f,%.8f,%.2f)",
                    ei, statStr, rtk.sol.ns,
                    llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2]));

            if (rtk.sol.stat == Constants.SOLQ_PPP || rtk.sol.stat == Constants.SOLQ_SINGLE) solvedCount++;
        }

        log.info("PPP summary: solved={}/{}", solvedCount, Math.min(epochs.size(), 10));
        assertTrue(solvedCount > 0, "PPP should solve at least one epoch");
    }
}