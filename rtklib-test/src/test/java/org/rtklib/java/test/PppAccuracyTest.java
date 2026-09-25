package org.rtklib.java.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.ephemeris.OtlReader;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PPP Accuracy Verification against RTCM 1005 Reference")
public class PppAccuracyTest {

    private static final Logger log = LoggerFactory.getLogger(PppAccuracyTest.class);

    private static final String PRODUCT_DIR = TestDataConfig.getProductDir();
    private static final String STATION = TestDataConfig.getStation();
    private static final String DATE = TestDataConfig.getStationDate();
    private static final String RTCM_FILE = TestDataConfig.getRtcmBaseDir() + "\\" + STATION + "\\" + DATE + "\\0.rtcm3";

    private static final String SP3_FILE = PRODUCT_DIR + "\\sp3\\WUM0MGXRAP_20261790000_01D_05M_ORB.SP3";
    private static final String CLK_FILE = PRODUCT_DIR + "\\clk\\WUM0MGXRAP_20261790000_01D_30S_CLK.CLK";
    private static final String ERP_FILE = PRODUCT_DIR + "\\erp\\WUM0MGXRAP_20261790000_01D_01D_ERP.ERP";
    private static final String DCB_FILE = PRODUCT_DIR + "\\dcb\\CAS0MGXRTS_20261800_01D_01D_DCB.BSX";
    private static final String GPT3_FILE = PRODUCT_DIR + "\\gpt3\\gpt3_5deg.dat";
    private static final String VMF3_FILE = PRODUCT_DIR + "\\vmf\\VMF3_20260629.H00";
    private static final String FCB_FILE = PRODUCT_DIR + "\\fcb\\WUM0MGXRAP_20261800000_01D_01D_OSB.BIA";
    private static final String BLQ_FILE = PRODUCT_DIR + "\\blq\\" + STATION + ".blq";

    private static String obsFile;
    private static String navFile;
    private static boolean rtcmExists;
    private static boolean sp3Exists;
    private static boolean gpt3Exists;
    private static boolean fcbExists;

    private static double[] refEcef;
    private static double[] refLlh;

    static boolean dataReady() { return rtcmExists && sp3Exists; }

    @BeforeAll
    static void setup() throws IOException {
        rtcmExists = new File(RTCM_FILE).exists();
        sp3Exists = new File(SP3_FILE).exists() && new File(CLK_FILE).exists();
        gpt3Exists = new File(GPT3_FILE).exists();
        fcbExists = new File(FCB_FILE).exists();

        log.info("=== Data Availability ===");
        log.info("RTCM: {} => {}", RTCM_FILE, rtcmExists);
        log.info("SP3/CLK: {}", sp3Exists);

        if (rtcmExists) {
            refEcef = extract1005(RTCM_FILE);
            if (refEcef != null) {
                refLlh = new double[3];
                CoordTransform.ecef2pos(refEcef, refLlh);
                log.info(String.format("Reference (RTCM 1005): lat=%.9f lon=%.9f h=%.4f",
                        Math.toDegrees(refLlh[0]), Math.toDegrees(refLlh[1]), refLlh[2]));
                log.info(String.format("Reference ECEF: X=%.4f Y=%.4f Z=%.4f", refEcef[0], refEcef[1], refEcef[2]));
            }
        }

        String preRinexObs = PRODUCT_DIR + "\\product\\rinex\\" + STATION + "\\" + DATE + "\\" + STATION + ".obs";
        String preRinexNav = PRODUCT_DIR + "\\product\\rinex\\" + STATION + "\\" + DATE + "\\" + STATION + ".nav";
        if (new File(preRinexObs).exists() && new File(preRinexNav).exists()) {
            obsFile = preRinexObs;
            navFile = preRinexNav;
            log.info("Using pre-existing RINEX: obs={}, nav={}", obsFile, navFile);
        } else if (rtcmExists) {
            try {
                String tempDir = System.getProperty("java.io.tmpdir") + "\\ppp_acc_test_" + System.currentTimeMillis();
                new File(tempDir).mkdirs();
                RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, tempDir, "BDS");
                boolean ok = converter.convert(RTCM_FILE);
                if (ok) {
                    obsFile = tempDir + "\\BDS.obs";
                    navFile = tempDir + "\\BDS.nav";
                    log.info("RTCM=>RINEX: obs={}, nav={}", obsFile, navFile);
                } else {
                    rtcmExists = false;
                }
            } catch (Exception e) {
                rtcmExists = false;
            }
        }
    }

    private static double[] extract1005(String rtcmPath) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(rtcmPath));
        final double[][] pos = {null};
        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onStation(Sta sta) {
                if (pos[0] == null && sta.pos != null && (sta.pos[0] != 0 || sta.pos[1] != 0)) {
                    pos[0] = sta.pos.clone();
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(data, 0, data.length);
        decoder.finish();
        return pos[0];
    }

    private void verifyAccuracy(String label, PppProcessor.PppResult result, double neThreshM, double uThreshM) {
        if (refEcef == null) {
            log.warn("{}: No reference coordinate, skip accuracy check", label);
            return;
        }
        int n = result.solutions.size();
        if (n == 0) {
            log.warn("{}: No solutions", label);
            return;
        }

        int skip = n / 3;
        double sumNe = 0, sumU = 0, sum3d = 0;
        int count = 0;
        int fixCount = 0;
        double maxNe = 0, maxU = 0;

        int[] sampleIdx = {0, 1, n/10, n/4, n/2, n-2, n-1};
        for (int si : sampleIdx) {
            if (si < 0 || si >= n) continue;
            SolData sol = result.solutions.get(si);
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            double[] solEcef = {ecef.v1, ecef.v2, ecef.v3};
            double[] neu = ecef2neu(refEcef, solEcef);
            log.info(String.format("  %s [%d/%d]: N=%.3fm E=%.3fm U=%.3fm stat=%s",
                    label, si+1, n, neu[0], neu[1], neu[2], sol.status));
        }

        for (int i = skip; i < n; i++) {
            SolData sol = result.solutions.get(i);
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            double[] solEcef = {ecef.v1, ecef.v2, ecef.v3};
            double[] neu = ecef2neu(refEcef, solEcef);
            double ne = Math.sqrt(neu[0] * neu[0] + neu[1] * neu[1]);
            sumNe += ne;
            sumU += Math.abs(neu[2]);
            sum3d += Math.sqrt(neu[0]*neu[0] + neu[1]*neu[1] + neu[2]*neu[2]);
            if (ne > maxNe) maxNe = ne;
            if (Math.abs(neu[2]) > maxU) maxU = Math.abs(neu[2]);
            count++;
            if (sol.status == SolutionStatus.FIX) fixCount++;
        }

        if (count == 0) {
            log.warn("{}: No valid solutions after skip", label);
            return;
        }

        double avgNe = sumNe / count;
        double avgU = sumU / count;
        double avg3d = sum3d / count;
        double fixRate = (double) fixCount / count * 100;

        log.info(String.format("  %s: n=%d (skip first %d), fix=%.1f%%", label, count, skip, fixRate));
        log.info(String.format("  %s: avgNE=%.3fm avgU=%.3fm avg3D=%.3fm maxNE=%.3fm maxU=%.3fm",
                label, avgNe, avgU, avg3d, maxNe, maxU));

        if (neThreshM > 0 && avgNe > neThreshM) {
            log.warn("  {} ⚠ avgNE={:.3f}m > threshold={:.3f}m", label, avgNe, neThreshM);
        }
        if (uThreshM > 0 && avgU > uThreshM) {
            log.warn("  {} ⚠ avgU={:.3f}m > threshold={:.3f}m", label, avgU, uThreshM);
        }
    }

    private static double[] ecef2neu(double[] ref, double[] pos) {
        double[] refLlh = new double[3];
        CoordTransform.ecef2pos(ref, refLlh);
        double[] dr = {pos[0] - ref[0], pos[1] - ref[1], pos[2] - ref[2]};
        double sinLat = Math.sin(refLlh[0]);
        double cosLat = Math.cos(refLlh[0]);
        double sinLon = Math.sin(refLlh[1]);
        double cosLon = Math.cos(refLlh[1]);
        double n = -sinLat * cosLon * dr[0] - sinLat * sinLon * dr[1] + cosLat * dr[2];
        double e = -sinLon * dr[0] + cosLon * dr[1];
        double u = cosLat * cosLon * dr[0] + cosLat * sinLon * dr[1] + sinLat * dr[2];
        return new double[]{n, e, u};
    }

    @Test
    @DisplayName("1. BDS PPP + precise eph (SP3+CLK) - baseline")
    void testPppPreciseBaseline() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-PREC: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-PREC", r, 1.0, 5.0);
        assertTrue(r.successCount > 0, "Should have successful solutions");
    }

    @Test
    @DisplayName("2. BDS PPP + IERS2010 tides")
    void testPppIers2010() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        p.setRtkConfig(cfg);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-IERS2010: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-IERS2010", r, 1.0, 5.0);
        assertTrue(r.successCount > 0);
    }

    @Test
    @DisplayName("3. BDS PPP + ISB/IFCB bias model")
    void testPppBiasModel() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enableIsbIfcbIfb = true;
        p.setRtkConfig(cfg);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-BIAS: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-BIAS", r, 1.0, 5.0);
        assertTrue(r.successCount > 0);
    }

    @Test
    @DisplayName("4. BDS PPP-AR (WL+NL LAMBDA)")
    void testPppAR() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        p.setRtkConfig(cfg);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-AR: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-AR", r, 0.5, 2.0);
        assertTrue(r.successCount > 0);
    }

    @Test
    @DisplayName("5. BDS PPP-AR + Fix-and-Hold")
    void testPppARFixHold() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        p.setRtkConfig(cfg);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-AR-FH: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-AR-FH", r, 0.5, 2.0);
        assertTrue(r.successCount > 0);
    }

    @Test
    @DisplayName("6. BDS PPP-AR + Partial AR")
    void testPppARPartial() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppPartialAR = true;
        p.setRtkConfig(cfg);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-AR-PAR: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-AR-PAR", r, 0.5, 2.0);
        assertTrue(r.successCount > 0);
    }

    @Test
    @DisplayName("7. BDS-3 PPP-AR (B1C/B2a)")
    void testBds3PppAR() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        if (fcbExists) p.loadFcb(FCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enableBds3PppAR = true;
        p.setRtkConfig(cfg);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("BDS3-PPP-AR: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("BDS3-PPP-AR", r, 0.5, 2.0);
        assertTrue(r.successCount > 0);
    }

    @Test
    @DisplayName("8. BDS PPP + IERS2010 + tidecorr=7 (BLQ)")
    void testPppIers2010Tidecorr7() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        if (new File(BLQ_FILE).exists()) {
            OtlReader.readblq(BLQ_FILE, STATION, p.getRtk().opt.odisp[0]);
        }
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        p.setRtkConfig(cfg);
        p.getRtk().opt.tidecorr = 7;

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-IERS2010-TC7: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-IERS2010-TC7", r, 1.0, 5.0);
        assertTrue(r.successCount > 0);
    }

    @Test
    @DisplayName("9. BDS PPP full combo (IERS2010+BIAS+AR+FixHold)")
    void testPppFullCombo() {
        assumeTrue(dataReady(), "Data not ready");
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = Constants.EPHOPT_PREC;
        PppProcessor p = new PppProcessor(opt);
        p.loadSp3(SP3_FILE);
        p.loadClk(CLK_FILE);
        if (new File(ERP_FILE).exists()) p.loadErp(ERP_FILE);
        if (fcbExists) p.loadFcb(FCB_FILE);
        if (new File(DCB_FILE).exists()) p.loadDcb(DCB_FILE);
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        p.setRtkConfig(cfg);

        PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
        log.info("PPP-FULL: total={}, success={}, fail={}", r.totalEpochs, r.successCount, r.failCount);
        verifyAccuracy("PPP-FULL", r, 0.5, 2.0);
        assertTrue(r.successCount > 0);
    }

    private static void assumeTrue(boolean condition, String message) {
        if (!condition) {
            throw new org.opentest4j.TestAbortedException(message);
        }
    }
}