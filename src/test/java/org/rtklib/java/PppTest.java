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
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexPppProcessor;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PPP Test (Experimental)")
public class PppTest {

    private static final Logger log = LoggerFactory.getLogger(PppTest.class);

    private static final String BASE_DIR = System.getProperty("user.dir");
    private static final String RTCM_ROVER = BASE_DIR + "\\RTKLIB_EX_2.5.0\\rover_0608.rtcm3";
    private static final String RTCM_BASE = BASE_DIR + "\\RTKLIB_EX_2.5.0\\base_0608.rtcm3";
    private static final String SP3_FILE = BASE_DIR + "\\RTKLIB-2.5.0\\test\\data\\sp3\\igs15904.sp3";
    private static final String CLK_FILE = BASE_DIR + "\\RTKLIB-2.5.0\\test\\data\\sp3\\igs15904.clk";

    private static String roverObsFile;
    private static String roverNavFile;
    private static boolean rtcmFilesExist;

    static boolean filesExist() {
        return rtcmFilesExist;
    }

    @BeforeAll
    static void setup() {
        rtcmFilesExist = new File(RTCM_ROVER).exists();
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
        File f = new File(SP3_FILE);
        log.info("SP3 file: {} exists={}", SP3_FILE, f.exists());
        assumeTrue(f.exists(), "SP3 file not found: " + SP3_FILE);
        Nav nav = new Nav();
        Sp3Reader.readsp3(SP3_FILE, nav, 0);
        log.info("SP3 loaded: ne={}", nav.ne);
        assertTrue(nav.ne > 0, "Should have loaded SP3 ephemerides");
    }

    @Test
    @DisplayName("3. CLK file reading")
    void testClkRead() {
        File f = new File(CLK_FILE);
        log.info("CLK file: {} exists={}", CLK_FILE, f.exists());
        assumeTrue(f.exists(), "CLK file not found: " + CLK_FILE);
        Nav nav = new Nav();
        ClkReader.readclk(CLK_FILE, nav);
        log.info("CLK loaded: nc={}", nav.nc);
        assertTrue(nav.nc > 0, "Should have loaded CLK records");
    }

    @Test
    @DisplayName("4. PPP core with RTCM-converted RINEX (BDS dual-freq)")
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
    @DisplayName("5. RinexPppProcessor end-to-end")
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