package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.ephemeris.Sp3Reader;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexPppProcessor;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PPP Test (Experimental)")
public class PppTest {

    private static final Logger log = LoggerFactory.getLogger(PppTest.class);

    private static final String BASE_DIR = System.getProperty("user.dir");

    private static final String RTCM_ROVER = BASE_DIR + "\\testdat\\rover.rtcm";
    private static final String RTCM_BASE = BASE_DIR + "\\testdat\\base.rtcm";

    private static final String SP3_FILE = BASE_DIR + "\\WUM0MGXNRT_20261581500_02D_05M_ORB.SP3\\WUM0MGXNRT_20261581500_02D_05M_ORB.SP3";
    private static final String CLK_FILE = BASE_DIR + "\\WUM0MGXNRT_20261581500_02D_05M_CLK.CLK\\WUM0MGXNRT_20261581500_02D_05M_CLK.CLK";

    private static String roverObsFile;
    private static String roverNavFile;
    private static boolean rtcmFilesExist;
    private static boolean sp3FilesExist;

    static boolean filesExist() {
        return rtcmFilesExist;
    }

    static boolean sp3Exists() {
        return sp3FilesExist;
    }

    @BeforeAll
    static void setup() {
        rtcmFilesExist = new File(RTCM_ROVER).exists();
        sp3FilesExist = new File(SP3_FILE).exists() && new File(CLK_FILE).exists();

        log.info("RTCM rover: {} exists={}", RTCM_ROVER, rtcmFilesExist);
        log.info("SP3: {} exists={}", SP3_FILE, new File(SP3_FILE).exists());
        log.info("CLK: {} exists={}", CLK_FILE, new File(CLK_FILE).exists());

        if (rtcmFilesExist) {
            try {
                String tempDir = System.getProperty("java.io.tmpdir") + "\\ppp_test_" + System.currentTimeMillis();
                new File(tempDir).mkdirs();
                RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, tempDir, "ROVER");
                boolean ok = converter.convert(RTCM_ROVER);
                if (ok) {
                    roverObsFile = tempDir + "\\ROVER.obs";
                    roverNavFile = tempDir + "\\ROVER.nav";
                    log.info("RTCM converted: obs={}, nav={}", roverObsFile, roverNavFile);
                } else {
                    rtcmFilesExist = false;
                    log.warn("RTCM conversion failed");
                }
            } catch (Exception e) {
                rtcmFilesExist = false;
                log.warn("RTCM conversion error: {}", e.getMessage());
            }
        } else {
            log.warn("RTCM files not found at {}", RTCM_ROVER);
        }
    }

    @Test
    @DisplayName("1. PppCore.pppnx() state vector size")
    void testPppnx() {
        PrcOpt opt = RinexPppProcessor.createDefaultOpt();
        int nx = PppCore.pppnx(opt);
        log.info("pppnx with IFLC+EST: nx={}", nx);
        assertTrue(nx > 0, "pppnx should be positive");

        int expectedNP = 3;
        int expectedNC = Constants.NSYS;
        int expectedNT = 1;
        int expectedNI = 0;
        int expectedNB = 1 * Constants.MAXSAT;
        int expected = expectedNP + expectedNC + expectedNT + expectedNI + expectedNB;
        assertEquals(expected, nx, "pppnx should match expected layout");
    }

    @Test
    @DisplayName("2. SP3 file reading")
    void testSp3Read() {
        assumeTrue(new File(SP3_FILE).exists(), "SP3 file not found: " + SP3_FILE);
        Nav nav = new Nav();
        Sp3Reader.readsp3(SP3_FILE, nav, 0);
        log.info("SP3 loaded: ne={}, peph={}", nav.ne, nav.peph != null ? nav.peph.length : "null");
        if (nav.ne > 0) {
            log.info("First peph time: {}", nav.peph[0].time);
        }
        assertTrue(nav.ne > 0, "Should have loaded SP3 ephemerides");
    }

    @Test
    @DisplayName("3. CLK file reading")
    void testClkRead() {
        assumeTrue(new File(CLK_FILE).exists(), "CLK file not found: " + CLK_FILE);
        Nav nav = new Nav();
        ClkReader.readclk(CLK_FILE, nav);
        log.info("CLK loaded: nc={}", nav.nc);
        assertTrue(nav.nc > 0, "Should have loaded CLK records");
    }

    @Test
    @DisplayName("4. PPP core with RTCM-converted RINEX (BDS dual-freq, broadcast eph)")
    @EnabledIf("filesExist")
    void testPppCoreWithRinex() {
        RinexParser parser = new RinexParser();
        boolean obsOk = parser.parseObs(roverObsFile);
        assertTrue(obsOk, "Should parse RINEX observation file");

        boolean navOk = parser.parseNav(roverNavFile);
        assertTrue(navOk, "Should parse RINEX navigation file");

        assertTrue(parser.obs.n > 0, "Should have observation data");

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.dynamics = 0;
        opt.posopt = new int[6];

        Rtk rtk = new Rtk();
        rtk.opt = opt;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i] = new Ssat();
        }

        List<List<Obsd>> epochGroups = groupObsByEpoch(parser.obs.data, parser.obs.n);
        log.info("Total epochs: {}", epochGroups.size());

        int maxEpochs = Math.min(10, epochGroups.size());
        int successCount = 0;

        for (int e = 0; e < maxEpochs; e++) {
            List<Obsd> epochObs = epochGroups.get(e);
            Obsd[] obsArray = epochObs.toArray(new Obsd[0]);
            int n = obsArray.length;

            for (int i = 0; i < n; i++) obsArray[i].rcv = 1;

            GTime prevTime = new GTime(rtk.sol.time);
            if (rtk.sol.stat == Constants.SOLQ_NONE) {
                PntPos.pntpos(obsArray, n, parser.nav, opt, rtk.sol, null, rtk.ssat);
            } else {
                rtk.sol.time = obsArray[0].time;
            }

            if (prevTime.time != 0) {
                rtk.tt = TimeSystem.timediff(rtk.sol.time, prevTime);
            } else {
                rtk.tt = 0.0;
            }

            PppCore.pppos(rtk, obsArray, n, parser.nav);

            if (rtk.sol.stat != Constants.SOLQ_NONE) {
                successCount++;
                double[] pos = new double[3];
                CoordTransform.ecef2pos(rtk.sol.rr, pos);
                log.info("  Epoch {}: lat={} lon={} h={} ns={} stat={}",
                        e, Math.toDegrees(pos[0]), Math.toDegrees(pos[1]),
                        pos[2], rtk.sol.ns, rtk.sol.stat);
            } else {
                log.info("  Epoch {}: PPP no fix", e);
            }
        }

        log.info("PPP core test: {} epochs, {} success", maxEpochs, successCount);
    }

    @Test
    @DisplayName("5. RinexPppProcessor end-to-end (broadcast eph)")
    @EnabledIf("filesExist")
    void testRinexPppProcessor() {
        PrcOpt opt = RinexPppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_BRDC;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RinexPppProcessor processor = new RinexPppProcessor(opt, baos);

        RinexPppProcessor.PppResult result = processor.process(roverObsFile, roverNavFile, null, null);

        log.info("RinexPppProcessor: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");

        String output = baos.toString();
        log.info("Output preview:\n{}", output.substring(0, Math.min(500, output.length())));
    }

    @Test
    @DisplayName("6. PppProcessor with RTCM file (broadcast eph)")
    @EnabledIf("filesExist")
    void testPppProcessorWithRtcm() {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_BRDC;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PppProcessor processor = new PppProcessor(opt, null, baos);

        try {
            PppProcessor.PppResult result = processor.process(RTCM_ROVER);
            log.info("PppProcessor RTCM: total={}, success={}, fail={}",
                    result.totalEpochs, result.successCount, result.failCount);
            assertTrue(result.totalEpochs > 0, "Should process epochs");
        } catch (IOException e) {
            fail("IO error: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("7. PppProcessor with RINEX file (broadcast eph)")
    @EnabledIf("filesExist")
    void testPppProcessorWithRinex() {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_BRDC;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PppProcessor processor = new PppProcessor(opt, null, baos);

        PppProcessor.PppResult result = processor.processRinex(roverObsFile, roverNavFile, null, null);
        log.info("PppProcessor RINEX: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("8. RinexPppProcessor with SP3+CLK precise ephemeris")
    @EnabledIf("filesExist")
    void testPppWithPreciseEph() {
        assumeTrue(sp3FilesExist, "SP3/CLK files not found");

        PrcOpt opt = RinexPppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RinexPppProcessor processor = new RinexPppProcessor(opt, baos);

        RinexPppProcessor.PppResult result = processor.process(roverObsFile, roverNavFile, SP3_FILE, CLK_FILE);

        log.info("PPP with precise eph: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");

        if (!result.solutions.isEmpty()) {
            Sol firstSol = result.solutions.get(0);
            double[] pos = new double[3];
            CoordTransform.ecef2pos(firstSol.rr, pos);
            log.info(String.format("First solution: lat=%.9f lon=%.9f h=%.3f ns=%d stat=%d",
                    Math.toDegrees(pos[0]), Math.toDegrees(pos[1]),
                    pos[2], firstSol.ns, firstSol.stat));

            Sol lastSol = result.solutions.get(result.solutions.size() - 1);
            CoordTransform.ecef2pos(lastSol.rr, pos);
            log.info(String.format("Last solution: lat=%.9f lon=%.9f h=%.3f ns=%d stat=%d",
                    Math.toDegrees(pos[0]), Math.toDegrees(pos[1]),
                    pos[2], lastSol.ns, lastSol.stat));
        }

        String output = baos.toString();
        log.info("Output lines: {}", output.split("\n").length);
    }

    @Test
    @DisplayName("9. PppProcessor with RTCM + SP3+CLK precise ephemeris")
    @EnabledIf("filesExist")
    void testPppProcessorRtcmWithPreciseEph() {
        assumeTrue(sp3FilesExist, "SP3/CLK files not found");

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PppProcessor processor = new PppProcessor(opt, null, baos);
        processor.loadSp3(SP3_FILE);
        processor.loadClk(CLK_FILE);

        try {
            PppProcessor.PppResult result = processor.process(RTCM_ROVER);
            log.info("PppProcessor RTCM+SP3: total={}, success={}, fail={}",
                    result.totalEpochs, result.successCount, result.failCount);
            assertTrue(result.totalEpochs > 0, "Should process epochs");

            if (!result.solutions.isEmpty()) {
                Sol lastSol = result.solutions.get(result.solutions.size() - 1);
                double[] pos = new double[3];
                CoordTransform.ecef2pos(lastSol.rr, pos);
                log.info("Last solution: lat={} lon={} h={} ns={} stat={}",
                        Math.toDegrees(pos[0]), Math.toDegrees(pos[1]),
                        pos[2], lastSol.ns, lastSol.stat);
            }
        } catch (IOException e) {
            fail("IO error: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("10. PppProcessor RINEX with SP3+CLK precise ephemeris")
    @EnabledIf("filesExist")
    void testPppProcessorRinexWithPreciseEph() {
        assumeTrue(sp3FilesExist, "SP3/CLK files not found");

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PppProcessor processor = new PppProcessor(opt, null, baos);

        PppProcessor.PppResult result = processor.processRinex(roverObsFile, roverNavFile, SP3_FILE, CLK_FILE);
        log.info("PppProcessor RINEX+SP3: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");

        String output = baos.toString();
        String[] lines = output.split("\n");
        int solLines = 0;
        for (String line : lines) {
            if (!line.startsWith("#") && line.trim().length() > 0) solLines++;
        }
        log.info("Solution lines in output: {}", solLines);
    }

    private static void assumeTrue(boolean condition, String message) {
        if (!condition) {
            throw new org.opentest4j.TestAbortedException(message);
        }
    }

    private List<List<Obsd>> groupObsByEpoch(Obsd[] data, int n) {
        List<List<Obsd>> groups = new ArrayList<>();
        if (n == 0) return groups;

        List<Obsd> current = new ArrayList<>();
        GTime currentTime = new GTime(data[0].time);

        for (int i = 0; i < n; i++) {
            if (!data[i].time.equals(currentTime)) {
                groups.add(current);
                current = new ArrayList<>();
                currentTime = new GTime(data[i].time);
            }
            current.add(data[i]);
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }
}