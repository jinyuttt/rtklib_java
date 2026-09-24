package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.OtlReader;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PPP BDS Comprehensive Test")
public class PppBdsComprehensiveTest {

    private static final Logger log = LoggerFactory.getLogger(PppBdsComprehensiveTest.class);

    private static final String YAXIA_DIR = "D:\\yaxia\\rtcm";
    private static final String STATION = "540423124124";
    private static final String DATE = "2026-06-29";
    private static final String RTCM_FILE = YAXIA_DIR + "\\" + STATION + "\\" + DATE + "\\0.rtcm3";

    private static final String SP3_FILE = "D:\\yaxia\\product\\sp3\\WUM0MGXNRT_20261801500_02D_05M_ORB.SP3";
    private static final String CLK_FILE = "D:\\yaxia\\product\\clk\\WUM0MGXNRT_20261801500_02D_05M_CLK.CLK";
    private static final String ERP_FILE = "D:\\yaxia\\product\\erp\\WUM0MGXNRT_20261801500_02D_05M_ERP.ERP";
    private static final String DCB_FILE = "D:\\yaxia\\product\\dcb\\CAS0MGXRAP_20261800000_01D_30S_DCB.BIA";
    private static final String GPT3_FILE = "D:\\yaxia\\product\\gpt3\\gpt3_5deg.txt";
    private static final String VMF3_FILE = "D:\\yaxia\\product\\vmf3\\VMF3_5x5.GRID";
    private static final String FCB_FILE = "D:\\yaxia\\product\\fcb\\WHU0MGXFCB_20261800000_01D_05M_FCB.fcb";
    private static final String BLQ_FILE = "D:\\yaxia\\product\\blq\\540423124124.blq";

    private static String obsFile;
    private static String navFile;
    private static boolean rtcmExists;
    private static boolean sp3Exists;
    private static boolean gpt3Exists;
    private static boolean fcbExists;

    static boolean dataReady() { return rtcmExists; }
    static boolean preciseReady() { return rtcmExists && sp3Exists; }
    static boolean gpt3Ready() { return rtcmExists && gpt3Exists; }
    static boolean fcbReady() { return rtcmExists && fcbExists; }

    @BeforeAll
    static void setup() {
        rtcmExists = new File(RTCM_FILE).exists();
        sp3Exists = new File(SP3_FILE).exists() && new File(CLK_FILE).exists();
        gpt3Exists = new File(GPT3_FILE).exists();
        fcbExists = new File(FCB_FILE).exists();

        log.info("=== Data Availability ===");
        log.info("RTCM: {} => {}", RTCM_FILE, rtcmExists);
        log.info("SP3:  {} => {}", SP3_FILE, new File(SP3_FILE).exists());
        log.info("CLK:  {} => {}", CLK_FILE, new File(CLK_FILE).exists());
        log.info("ERP:  {} => {}", ERP_FILE, new File(ERP_FILE).exists());
        log.info("DCB:  {} => {}", DCB_FILE, new File(DCB_FILE).exists());
        log.info("GPT3: {} => {}", GPT3_FILE, gpt3Exists);
        log.info("VMF3: {} => {}", VMF3_FILE, new File(VMF3_FILE).exists());
        log.info("FCB:  {} => {}", FCB_FILE, fcbExists);
        log.info("BLQ:  {} => {}", BLQ_FILE, new File(BLQ_FILE).exists());

        if (rtcmExists) {
            try {
                String tempDir = System.getProperty("java.io.tmpdir") + "\\ppp_bds_test_" + System.currentTimeMillis();
                new File(tempDir).mkdirs();
                RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, tempDir, "BDS");
                boolean ok = converter.convert(RTCM_FILE);
                if (ok) {
                    obsFile = tempDir + "\\BDS.obs";
                    navFile = tempDir + "\\BDS.nav";
                    log.info("RTCM=>RINEX: obs={}, nav={}", obsFile, navFile);
                } else {
                    rtcmExists = false;
                    log.warn("RTCM conversion failed");
                }
            } catch (Exception e) {
                rtcmExists = false;
                log.warn("RTCM conversion error: {}", e.getMessage());
            }
        }
    }

    private PppProcessor createBdsPppProcessor() {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_BRDC;
        return new PppProcessor(opt);
    }

    private PppProcessor createBdsPrecPppProcessor() {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        if (new File(SP3_FILE).exists()) p.loadSp3(SP3_FILE);
        if (new File(CLK_FILE).exists()) p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        return p;
    }

    private void logResult(String label, PppProcessor.PppResult result) {
        log.info("{} => total={}, success={}, fail={}", label, result.totalEpochs, result.successCount, result.failCount);
        if (!result.solutions.isEmpty()) {
            SolData last = result.solutions.get(result.solutions.size() - 1);
            Position llh = last.getPosition(CoordType.LLH);
            if (llh != null) {
                log.info(String.format("%s  last: lat=%.6f lon=%.6f h=%.3f ns=%d stat=%s",
                        label, llh.v1, llh.v2, llh.v3, last.numSat, last.status));
            }
        }
    }

    @Test
    @DisplayName("1. BDS PPP basic (broadcast eph)")
    void testBdsPppBasic() {
        assumeTrue(rtcmExists, "RTCM data not found");
        PppProcessor p = createBdsPppProcessor();
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-BRDC", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("2. BDS PPP + precise eph (SP3+CLK)")
    void testBdsPppPrecise() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-PREC", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("3. BDS PPP + GPT3+VMF3 troposphere")
    void testBdsPppGpt3Vmf3() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        assumeTrue(gpt3Exists, "GPT3 grid not found");
        PppProcessor p = createBdsPrecPppProcessor();
        p.loadGpt3Grid(GPT3_FILE);
        if (new File(VMF3_FILE).exists()) p.loadVmf3Op(VMF3_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-GPT3VMF3", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("4. BDS PPP + IERS2010 tides")
    void testBdsPppIers2010() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-IERS2010", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("5. BDS PPP + ISB/IFCB bias model")
    void testBdsPppBiasModel() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        RtkConfig cfg = new RtkConfig();
        cfg.enableIsbIfcbIfb = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-BIAS", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("6. BDS PPP-AR (WL+NL LAMBDA)")
    void testBdsPppAR() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-AR", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("7. BDS PPP-AR + Fix-and-Hold")
    void testBdsPppARFixHold() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-AR-FH", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("8. BDS PPP-AR + Partial AR")
    void testBdsPppARPartial() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppPartialAR = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-AR-PAR", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("9. BDS-3 PPP-AR (B1C/B2a)")
    void testBds3PppAR() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enableBds3PppAR = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS3-PPP-AR", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("10. BDS PPP full combo (GPT3+IERS2010+BIAS+AR)")
    void testBdsPppFullCombo() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        if (gpt3Exists) { p.loadGpt3Grid(GPT3_FILE); }
        if (new File(VMF3_FILE).exists()) p.loadVmf3Op(VMF3_FILE);
        if (fcbExists) p.loadFcb(FCB_FILE);
        if (new File(DCB_FILE).exists()) p.loadDcb(DCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = gpt3Exists;
        cfg.enableIers2010 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        p.setRtkConfig(cfg);
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-FULL", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("11. BDS PPP + IERS2010 tides (tidecorr=7 with BLQ)")
    void testBdsPppIers2010Tidecorr7() {
        assumeTrue(sp3Exists, "SP3/CLK not found");
        PppProcessor p = createBdsPrecPppProcessor();
        if (new File(BLQ_FILE).exists()) {
            OtlReader.readblq(BLQ_FILE, STATION, p.getRtk().opt.odisp[0]);
        }
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        p.setRtkConfig(cfg);
        p.getRtk().opt.tidecorr = 7;
        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        logResult("BDS-PPP-IERS2010-TC7", r);
        assertTrue(r.totalEpochs > 0, "Should process epochs");
    }

    private static void assumeTrue(boolean condition, String message) {
        if (!condition) {
            throw new org.opentest4j.TestAbortedException(message);
        }
    }
}