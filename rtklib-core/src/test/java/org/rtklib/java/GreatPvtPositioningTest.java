package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.ephemeris.Sp3Reader;
import org.rtklib.java.ephemeris.UpdReader;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.ppp.PppCoreEx;
import org.rtklib.java.rinex.PostPosProcessor;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Multi-GNSS positioning with GREAT-PVT data (G+E+C+R)")
public class GreatPvtPositioningTest {

    private static final Logger log = LoggerFactory.getLogger(GreatPvtPositioningTest.class);

    private static final String PPP_DIR = TestDataConfig.getGreatPvtPppDir();
    private static final String RTK_DIR = TestDataConfig.getGreatPvtRtkDir();

    private static final String PPP_OBS = PPP_DIR + "\\obs\\godn3050.23o";
    private static final String PPP_NAV = PPP_DIR + "\\gnss\\brdc3050.23p";
    private static final String PPP_SP3 = PPP_DIR + "\\gnss\\COD0MGXFIN_20233050000_01D_05M_ORB.SP3";
    private static final String PPP_CLK = PPP_DIR + "\\gnss\\COD0MGXFIN_20233050000_01D_30S_CLK.CLK";

    private static final String PPP_UPD_WL_G = PPP_DIR + "\\upd\\upd_wl_2023305_G";
    private static final String PPP_UPD_NL_G = PPP_DIR + "\\upd\\upd_nl_2023305_G";
    private static final String PPP_UPD_WL_E = PPP_DIR + "\\upd\\upd_wl_2023305_E";
    private static final String PPP_UPD_NL_E = PPP_DIR + "\\upd\\upd_nl_2023305_E";
    private static final String PPP_UPD_WL_C = PPP_DIR + "\\upd\\upd_wl_2023305_C";
    private static final String PPP_UPD_NL_C = PPP_DIR + "\\upd\\upd_nl_2023305_C";

    private static final String RTK_ROVER = RTK_DIR + "\\obs\\SEPT3510.20O";
    private static final String RTK_BASE = RTK_DIR + "\\obs\\WUDA3510.20O";
    private static final String RTK_NAV = RTK_DIR + "\\gnss\\brdm3510.20p";

    static boolean pppDataAvailable() {
        return Files.exists(Paths.get(PPP_OBS))
                && Files.exists(Paths.get(PPP_SP3))
                && Files.exists(Paths.get(PPP_CLK));
    }

    static boolean rtkDataAvailable() {
        return Files.exists(Paths.get(RTK_ROVER))
                && Files.exists(Paths.get(RTK_BASE))
                && Files.exists(Paths.get(RTK_NAV));
    }

    private static RinexParser pppParser;
    private static boolean pppOk;

    @BeforeAll
    static void loadPppData() {
        pppOk = pppDataAvailable();
        if (!pppOk) return;

        pppParser = new RinexParser();
        boolean obsOk = pppParser.parseObs(PPP_OBS);
        if (!obsOk) { pppOk = false; return; }

        if (Files.exists(Paths.get(PPP_NAV))) {
            pppParser.parseNav(PPP_NAV);
        }
        Sp3Reader.readsp3(PPP_SP3, pppParser.nav, 0);
        ClkReader.readclk(PPP_CLK, pppParser.nav);

        loadUpdIfExists(PPP_UPD_WL_G);
        loadUpdIfExists(PPP_UPD_NL_G);
        loadUpdIfExists(PPP_UPD_WL_E);
        loadUpdIfExists(PPP_UPD_NL_E);
        loadUpdIfExists(PPP_UPD_WL_C);
        loadUpdIfExists(PPP_UPD_NL_C);

        log.info("PPP data loaded: obs={}, navEph={}, SP3ne={}, CLKnc={}, updWl={}, updNl={}",
                pppParser.obs.n, pppParser.nav.n, pppParser.nav.ne, pppParser.nav.nc,
                pppParser.nav.updWl != null ? pppParser.nav.updWl.length : 0,
                pppParser.nav.updNl != null ? pppParser.nav.updNl.length : 0);
    }

    private static void loadUpdIfExists(String path) {
        if (Files.exists(Paths.get(path))) {
            UpdReader.readUpd(path, pppParser.nav);
        }
    }

    @Test
    @DisplayName("RINEX 3.04 multi-GNSS OBS parsing")
    void testRinex304MultiGnssParsing() {
        Assumptions.assumeTrue(pppDataAvailable(),
                "GREAT-PVT PPP data not available");
        assertTrue(pppOk, "PPP RINEX OBS parse should succeed");

        int totalObs = pppParser.obs.n;
        assertTrue(totalObs > 0, "Should have observation records");

        int sysG = 0, sysE = 0, sysC = 0, sysR = 0;
        for (int i = 0; i < totalObs; i++) {
            int sat = pppParser.obs.data[i].sat;
            int sys = SatUtils.satsys(sat, new int[1]);
            if (sys == Constants.SYS_GPS) sysG++;
            else if (sys == Constants.SYS_GAL) sysE++;
            else if (sys == Constants.SYS_CMP) sysC++;
            else if (sys == Constants.SYS_GLO) sysR++;
        }
        log.info("Satellite systems in data: G={}, E={}, C={}, R={}", sysG, sysE, sysC, sysR);
        assertTrue(sysG > 0, "Should have GPS satellites");
        assertTrue(sysE > 0 || sysC > 0, "Should have Galileo or BeiDou satellites");
    }

    @Test
    @DisplayName("SP3/CLK precise product loading for RINEX 3.04")
    void testPreciseProductLoading() {
        Assumptions.assumeTrue(pppOk, "GREAT-PVT PPP data not loaded");
        assertTrue(pppParser.nav.ne > 0, "SP3 precise ephemeris should be loaded");
        assertTrue(pppParser.nav.nc > 0, "CLK precise clock should be loaded");
        log.info("Precise products: SP3 epochs={}, CLK epochs={}", pppParser.nav.ne, pppParser.nav.nc);
    }

    @Test
    @DisplayName("SPP multi-GNSS (G+E+C) with RINEX 3.04")
    void testSppMultiGnss() {
        Assumptions.assumeTrue(pppOk, "GREAT-PVT PPP data not loaded");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        GTime firstTime = allObs[0].time;
        int epochEnd = totalObs;
        for (int i = 1; i < totalObs; i++) {
            if (!allObs[i].time.equals(firstTime)) {
                epochEnd = i;
                break;
            }
        }
        int n = epochEnd;
        log.info("SPP multi-GNSS first epoch: {} observations", n);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 10.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.sateph = Constants.EPHOPT_PREC;

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];

        for (int i = 0; i < n; i++) {
            double[] rs_i = new double[6], dts_i = new double[2], vare_i = new double[1];
            EphModel.satpos(firstTime, pppParser.nav, allObs[i].sat, rs_i, dts_i, vare_i);
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

        int result = SppCore.estpos(allObs, n, rs, dts, vare, svh, pppParser.nav, opt, ssat, sol, azel, vsat, resp, msg);

        if (result == 1) {
            double[] llh = new double[3];
            CoordTransform.ecef2pos(sol.rr, llh);
            log.info(String.format("SPP multi-GNSS: LLH=(%.8f, %.8f, %.2f), ns=%d",
                    llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2], sol.ns));
        } else {
            log.warn("SPP multi-GNSS failed: {}", msg[0]);
        }
        assertTrue(result == 1, "SPP multi-GNSS should succeed");
    }

    @Test
    @DisplayName("PPP kinematic (G+E+C) with CODE precise products")
    void testPppKinematic() {
        Assumptions.assumeTrue(pppOk && pppParser.nav.ne > 0 && pppParser.nav.nc > 0,
                "GREAT-PVT PPP data or precise products not available");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        List<GTime> epochs = new ArrayList<>();
        List<Integer> epochStarts = new ArrayList<>();
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
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int solvedCount = 0;
        int maxEpochs = Math.min(epochs.size(), 30);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;

            RtkCore.rtkpos(rtk, epochObs, epochObs.length, pppParser.nav);

            double[] llh = new double[3];
            CoordTransform.ecef2pos(rtk.sol.rr, llh);
            String statStr = rtk.sol.stat == Constants.SOLQ_PPP ? "PPP" :
                             rtk.sol.stat == Constants.SOLQ_SINGLE ? "SINGLE" : "NONE(" + rtk.sol.stat + ")";
            log.info(String.format("PPP epoch %d/%d: stat=%s ns=%d LLH=(%.8f,%.8f,%.2f)",
                    ei + 1, maxEpochs, statStr, rtk.sol.ns,
                    llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2]));

            if (rtk.sol.stat == Constants.SOLQ_PPP || rtk.sol.stat == Constants.SOLQ_SINGLE) solvedCount++;
        }

        log.info("PPP summary: solved={}/{}", solvedCount, maxEpochs);
        assertTrue(solvedCount > 0, "PPP should solve at least one epoch");
    }

    @Test
    @DisplayName("PPP-AR kinematic (G) with UPD products, WL+NL ambiguity resolution")
    void testPppArKinematic() {
        boolean hasUpd = pppOk
                && pppParser.nav.updWl != null && pppParser.nav.updWl.length > 0
                && pppParser.nav.updNl != null && pppParser.nav.updNl.length > 0;
        Assumptions.assumeTrue(hasUpd,
                "GREAT-PVT PPP data with UPD products not available");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        List<GTime> epochs = new ArrayList<>();
        List<Integer> epochStarts = new ArrayList<>();
        for (int i = 0; i < totalObs; i++) {
            if (i == 0 || !allObs[i].time.equals(allObs[i - 1].time)) {
                epochs.add(allObs[i].time);
                epochStarts.add(i);
            }
        }
        epochStarts.add(totalObs);
        log.info("PPP-AR: {} epochs total, WL={}, NL={}",
                epochs.size(), pppParser.nav.updWl.length, pppParser.nav.updNl.length);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;

        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.pppArRatioWl = 2.0;
        cfg.pppArRatioNl = 3.0;
        cfg.enablePppArFixHold = true;
        cfg.pppArFixHoldMinEp = 10;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int floatCount = 0, fixCount = 0;
        int maxEpochs = Math.min(epochs.size(), 60);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;
            RtklibCommon.compactObsFreq(epochObs, epochObs.length, opt.nf, pppParser.nav);

            if (ei == 0) {
                Sol sppSol = new Sol();
                PntPos.pntpos(epochObs, epochObs.length, pppParser.nav, opt, sppSol, null, rtk.ssat);
                if (sppSol.stat != Constants.SOLQ_NONE) {
                    System.arraycopy(sppSol.rr, 0, rtk.sol.rr, 0, 3);
                }
            }

            PppCoreEx.ppos(rtk, epochObs, epochObs.length, pppParser.nav, cfg);

            String statStr;
            if (rtk.sol.stat == Constants.SOLQ_FIX) { statStr = "FIX"; fixCount++; }
            else if (rtk.sol.stat == Constants.SOLQ_PPP) { statStr = "FLOAT"; floatCount++; }
            else { statStr = "NONE(" + rtk.sol.stat + ")"; }

            double[] llh = new double[3];
            CoordTransform.ecef2pos(rtk.sol.rr, llh);
            log.info(String.format("PPP-AR epoch %d/%d: stat=%s ns=%d LLH=(%.8f,%.8f,%.2f)",
                    ei + 1, maxEpochs, statStr, rtk.sol.ns,
                    llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2]));
        }

        log.info("PPP-AR summary: float={}/{}, fix={}/{}", floatCount, maxEpochs, fixCount, maxEpochs);
        assertTrue(floatCount + fixCount > 0, "PPP-AR should solve at least one epoch");
    }

    @Test
    @DisplayName("RTK multi-GNSS (G+E+C) with base+rover RINEX 3.03")
    void testRtkMultiGnss() {
        Assumptions.assumeTrue(rtkDataAvailable(),
                "GREAT-PVT RTK data not available");

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        PostPosProcessor proc = new PostPosProcessor(opt);
        PostPosProcessor.PostPosResult result = proc.process(RTK_ROVER, RTK_BASE, RTK_NAV);

        log.info("RTK multi-GNSS: totalEpochs={}, successCount={}, failCount={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.successCount > 0, "RTK multi-GNSS should solve at least one epoch");
    }

    @Test
    @DisplayName("RTK GPS-only dual-frequency as baseline comparison")
    void testRtkGpsOnly() {
        Assumptions.assumeTrue(rtkDataAvailable(),
                "GREAT-PVT RTK data not available");

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        PostPosProcessor proc = new PostPosProcessor(opt);
        PostPosProcessor.PostPosResult result = proc.process(RTK_ROVER, RTK_BASE, RTK_NAV);

        log.info("RTK GPS-only: totalEpochs={}, successCount={}, failCount={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.successCount > 0, "RTK GPS-only should solve at least one epoch");
    }

    @Test
    @DisplayName("compactObsFreq correctness with multi-GNSS multi-frequency data")
    void testCompactObsFreqMultiGnss() {
        Assumptions.assumeTrue(pppOk, "GREAT-PVT PPP data not loaded");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        GTime firstTime = allObs[0].time;
        int epochEnd = totalObs;
        for (int i = 1; i < totalObs; i++) {
            if (!allObs[i].time.equals(firstTime)) {
                epochEnd = i;
                break;
            }
        }

        int nf = 2;
        int nonZeroBefore = 0, nonZeroAfter = 0;
        for (int i = 0; i < epochEnd; i++) {
            Obsd obs = allObs[i];
            for (int f = 0; f < nf; f++) {
                if (obs.P[f] != 0.0) nonZeroBefore++;
            }
        }

        RtklibCommon.compactObsFreq(allObs, epochEnd, nf, pppParser.nav);

        for (int i = 0; i < epochEnd; i++) {
            Obsd obs = allObs[i];
            for (int f = 0; f < nf; f++) {
                if (obs.P[f] != 0.0) nonZeroAfter++;
            }
        }

        log.info("compactObsFreq: nonZeroP before={}, after={}", nonZeroBefore, nonZeroAfter);
        assertEquals(nonZeroBefore, nonZeroAfter,
                "compactObsFreq should preserve total non-zero pseudorange count");
    }

    @Test
    @DisplayName("PPP static (G+E+C) with CODE precise products")
    void testPppStatic() {
        Assumptions.assumeTrue(pppOk && pppParser.nav.ne > 0 && pppParser.nav.nc > 0,
                "GREAT-PVT PPP data or precise products not available");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        List<GTime> epochs = new ArrayList<>();
        List<Integer> epochStarts = new ArrayList<>();
        for (int i = 0; i < totalObs; i++) {
            if (i == 0 || !allObs[i].time.equals(allObs[i - 1].time)) {
                epochs.add(allObs[i].time);
                epochStarts.add(i);
            }
        }
        epochStarts.add(totalObs);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int solvedCount = 0;
        int maxEpochs = Math.min(epochs.size(), 30);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;

            RtkCore.rtkpos(rtk, epochObs, epochObs.length, pppParser.nav);

            if (rtk.sol.stat == Constants.SOLQ_PPP || rtk.sol.stat == Constants.SOLQ_SINGLE) solvedCount++;
        }

        log.info("PPP Static summary: solved={}/{}", solvedCount, maxEpochs);
        assertTrue(solvedCount > 0, "PPP Static should solve at least one epoch");
    }

    @Test
    @DisplayName("PPP kinematic with GPT3+VMF3 troposphere (P1)")
    void testPppGpt3Vmf3() {
        Assumptions.assumeTrue(pppOk && pppParser.nav.ne > 0 && pppParser.nav.nc > 0,
                "GREAT-PVT PPP data or precise products not available");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        List<GTime> epochs = new ArrayList<>();
        List<Integer> epochStarts = new ArrayList<>();
        for (int i = 0; i < totalObs; i++) {
            if (i == 0 || !allObs[i].time.equals(allObs[i - 1].time)) {
                epochs.add(allObs[i].time);
                epochStarts.add(i);
            }
        }
        epochStarts.add(totalObs);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;

        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int solvedCount = 0;
        int maxEpochs = Math.min(epochs.size(), 30);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;
            RtklibCommon.compactObsFreq(epochObs, epochObs.length, opt.nf, pppParser.nav);

            if (ei == 0) {
                Sol sppSol = new Sol();
                PntPos.pntpos(epochObs, epochObs.length, pppParser.nav, opt, sppSol, null, rtk.ssat);
                if (sppSol.stat != Constants.SOLQ_NONE) {
                    System.arraycopy(sppSol.rr, 0, rtk.sol.rr, 0, 3);
                }
            }

            PppCoreEx.ppos(rtk, epochObs, epochObs.length, pppParser.nav, cfg);

            if (rtk.sol.stat == Constants.SOLQ_PPP || rtk.sol.stat == Constants.SOLQ_SINGLE) solvedCount++;
        }

        log.info("PPP+GPT3+VMF3 summary: solved={}/{}", solvedCount, maxEpochs);
        assertTrue(solvedCount > 0, "PPP+GPT3+VMF3 should solve at least one epoch");
    }

    @Test
    @DisplayName("PPP kinematic with IERS2010 tides (P3)")
    void testPppIers2010() {
        Assumptions.assumeTrue(pppOk && pppParser.nav.ne > 0 && pppParser.nav.nc > 0,
                "GREAT-PVT PPP data or precise products not available");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        List<GTime> epochs = new ArrayList<>();
        List<Integer> epochStarts = new ArrayList<>();
        for (int i = 0; i < totalObs; i++) {
            if (i == 0 || !allObs[i].time.equals(allObs[i - 1].time)) {
                epochs.add(allObs[i].time);
                epochStarts.add(i);
            }
        }
        epochStarts.add(totalObs);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;

        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int solvedCount = 0;
        int maxEpochs = Math.min(epochs.size(), 30);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;
            RtklibCommon.compactObsFreq(epochObs, epochObs.length, opt.nf, pppParser.nav);

            if (ei == 0) {
                Sol sppSol = new Sol();
                PntPos.pntpos(epochObs, epochObs.length, pppParser.nav, opt, sppSol, null, rtk.ssat);
                if (sppSol.stat != Constants.SOLQ_NONE) {
                    System.arraycopy(sppSol.rr, 0, rtk.sol.rr, 0, 3);
                }
            }

            PppCoreEx.ppos(rtk, epochObs, epochObs.length, pppParser.nav, cfg);

            if (rtk.sol.stat == Constants.SOLQ_PPP || rtk.sol.stat == Constants.SOLQ_SINGLE) solvedCount++;
        }

        log.info("PPP+IERS2010 summary: solved={}/{}", solvedCount, maxEpochs);
        assertTrue(solvedCount > 0, "PPP+IERS2010 should solve at least one epoch");
    }

    @Test
    @DisplayName("PPP kinematic with ISB/IFCB/IFB (P4)")
    void testPppIsbIfcbIfb() {
        Assumptions.assumeTrue(pppOk && pppParser.nav.ne > 0 && pppParser.nav.nc > 0,
                "GREAT-PVT PPP data or precise products not available");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        List<GTime> epochs = new ArrayList<>();
        List<Integer> epochStarts = new ArrayList<>();
        for (int i = 0; i < totalObs; i++) {
            if (i == 0 || !allObs[i].time.equals(allObs[i - 1].time)) {
                epochs.add(allObs[i].time);
                epochStarts.add(i);
            }
        }
        epochStarts.add(totalObs);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;

        RtkConfig cfg = new RtkConfig();
        cfg.enableIsbIfcbIfb = true;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int solvedCount = 0;
        int maxEpochs = Math.min(epochs.size(), 30);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;
            RtklibCommon.compactObsFreq(epochObs, epochObs.length, opt.nf, pppParser.nav);

            if (ei == 0) {
                Sol sppSol = new Sol();
                PntPos.pntpos(epochObs, epochObs.length, pppParser.nav, opt, sppSol, null, rtk.ssat);
                if (sppSol.stat != Constants.SOLQ_NONE) {
                    System.arraycopy(sppSol.rr, 0, rtk.sol.rr, 0, 3);
                }
            }

            PppCoreEx.ppos(rtk, epochObs, epochObs.length, pppParser.nav, cfg);

            if (rtk.sol.stat == Constants.SOLQ_PPP || rtk.sol.stat == Constants.SOLQ_SINGLE) solvedCount++;
        }

        log.info("PPP+ISB summary: solved={}/{}", solvedCount, maxEpochs);
        assertTrue(solvedCount > 0, "PPP+ISB should solve at least one epoch");
    }

    @Test
    @DisplayName("PPP-AR with Fix-and-Hold (P6+P7)")
    void testPppArFixHold() {
        boolean hasUpd = pppOk
                && pppParser.nav.updWl != null && pppParser.nav.updWl.length > 0
                && pppParser.nav.updNl != null && pppParser.nav.updNl.length > 0;
        Assumptions.assumeTrue(hasUpd,
                "GREAT-PVT PPP data with UPD products not available");

        Obsd[] allObs = pppParser.obs.data;
        int totalObs = pppParser.obs.n;

        List<GTime> epochs = new ArrayList<>();
        List<Integer> epochStarts = new ArrayList<>();
        for (int i = 0; i < totalObs; i++) {
            if (i == 0 || !allObs[i].time.equals(allObs[i - 1].time)) {
                epochs.add(allObs[i].time);
                epochStarts.add(i);
            }
        }
        epochStarts.add(totalObs);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;

        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        cfg.pppArRatioWl = 2.0;
        cfg.pppArRatioNl = 3.0;
        cfg.pppArFixHoldMinEp = 10;

        Rtk rtk = new Rtk();
        rtk.opt = opt;

        int floatCount = 0, fixCount = 0;
        int maxEpochs = Math.min(epochs.size(), 60);
        for (int ei = 0; ei < maxEpochs; ei++) {
            int start = epochStarts.get(ei);
            int end = epochStarts.get(ei + 1);
            Obsd[] epochObs = java.util.Arrays.copyOfRange(allObs, start, end);
            for (Obsd o : epochObs) o.rcv = 1;
            RtklibCommon.compactObsFreq(epochObs, epochObs.length, opt.nf, pppParser.nav);

            if (ei == 0) {
                Sol sppSol = new Sol();
                PntPos.pntpos(epochObs, epochObs.length, pppParser.nav, opt, sppSol, null, rtk.ssat);
                if (sppSol.stat != Constants.SOLQ_NONE) {
                    System.arraycopy(sppSol.rr, 0, rtk.sol.rr, 0, 3);
                }
            }

            PppCoreEx.ppos(rtk, epochObs, epochObs.length, pppParser.nav, cfg);

            if (rtk.sol.stat == Constants.SOLQ_FIX) fixCount++;
            else if (rtk.sol.stat == Constants.SOLQ_PPP) floatCount++;
        }

        log.info("PPP-AR+FixHold summary: float={}/{}, fix={}/{}", floatCount, maxEpochs, fixCount, maxEpochs);
        assertTrue(floatCount + fixCount > 0, "PPP-AR+FixHold should solve at least one epoch");
    }

    @Test
    @DisplayName("RTK Static multi-GNSS (G+E+C)")
    void testRtkStaticMultiGnss() {
        Assumptions.assumeTrue(rtkDataAvailable(),
                "GREAT-PVT RTK data not available");

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        PostPosProcessor proc = new PostPosProcessor(opt);
        PostPosProcessor.PostPosResult result = proc.process(RTK_ROVER, RTK_BASE, RTK_NAV);

        log.info("RTK Static multi-GNSS: totalEpochs={}, successCount={}, failCount={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.successCount > 0, "RTK Static multi-GNSS should solve at least one epoch");
    }
}