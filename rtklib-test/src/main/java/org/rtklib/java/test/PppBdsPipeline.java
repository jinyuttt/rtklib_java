package org.rtklib.java.test;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.OtlReader;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.product.ProductDownloader;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class PppBdsPipeline {

    private static final Logger log = LoggerFactory.getLogger(PppBdsPipeline.class);

    private static final String YAXIA_DIR = "D:\\yaxia\\rtcm";
    private static final String STATION = "540423124124";
    private static final String DATE_STR = "2026-06-29";
    private static final String RTCM_FILE = YAXIA_DIR + "\\" + STATION + "\\" + DATE_STR + "\\0.rtcm3";
    private static final String PRODUCT_DIR = "D:\\yaxia\\product";

    private static String obsFile;
    private static String navFile;
    private static String sp3File;
    private static String clkFile;
    private static String erpFile;
    private static String biaFile;
    private static String dcbFile;
    private static String gpt3File;
    private static String vmf3File;
    private static String fcbFile;
    private static ProductDownloader downloader;
    private static LocalDate obsDate;
    private static LocalDate obsDateUtc;

    static class TestResult {
        String label;
        boolean pass;
        boolean skip;
        String reason;
        int total;
        int success;
        double lat, lon, h;
        int fixCount;

        String posStr() {
            if (skip) return "SKIP";
            return String.format("%.6f, %.6f, %.3f", lat, lon, h);
        }

        String rateStr() {
            if (skip) return "-";
            return String.format("%.1f%%", total > 0 ? 100.0 * success / total : 0);
        }
    }

    private static final List<TestResult> results = new ArrayList<>();

    public static void main(String[] args) {
        log.info("============================================================");
        log.info("  PPP BDS Comprehensive Pipeline Test");
        log.info("  Station: {}  Date: {}", STATION, DATE_STR);
        log.info("============================================================");

        step1_checkRtcm();
        step2_convertRinex();
        step3_downloadProducts();
        step4_pppBasicBrdc();
        step5_pppPrecise();
        step6_pppGpt3Vmf3();
        step7_pppIers2010();
        step8_pppBiasModel();
        step9_pppAR();
        step10_pppARFixHold();
        step11_pppARPartial();
        step12_bds3PppAR();
        step13_pppIers2010Tidecorr7();
        step14_pppFullCombo();
        step15_allOnMax();

        printSummaryTable();
    }

    private static void step1_checkRtcm() {
        log.info("--- Step 1: Check RTCM data ---");
        if (new File(RTCM_FILE).exists()) {
            log.info("RTCM file: {} ({} bytes)", RTCM_FILE, new File(RTCM_FILE).length());
        } else {
            log.error("RTCM file NOT found: {}", RTCM_FILE);
            System.exit(1);
        }
    }

    private static void step2_convertRinex() {
        log.info("--- Step 2: Convert RTCM to RINEX ---");
        try {
            String tempDir = PRODUCT_DIR + "\\rinex\\" + STATION;
            new File(tempDir).mkdirs();
            RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, tempDir, STATION);
            boolean ok = converter.convert(RTCM_FILE);
            if (ok) {
                obsFile = tempDir + "\\" + STATION + ".obs";
                navFile = tempDir + "\\" + STATION + ".nav";
                log.info("RINEX: obs={} nav={}", obsFile, navFile);
            } else {
                log.error("RTCM conversion FAILED");
                System.exit(1);
            }
        } catch (Exception e) {
            log.error("RTCM conversion error: {}", e.getMessage());
            System.exit(1);
        }
    }

    private static void step3_downloadProducts() {
        log.info("--- Step 3: Init product downloader (cache-first) ---");
        obsDate = LocalDate.parse(DATE_STR);
        obsDateUtc = obsDate.minusDays(1);
        log.info("Beijing date: {}, UTC date for products: {}", obsDate, obsDateUtc);
        downloader = new ProductDownloader(PRODUCT_DIR);
        log.info("ProductDownloader ready: cacheDir={}", PRODUCT_DIR);
    }

    private static String ensureSp3() {
        if (sp3File != null) return sp3File;
        log.info("  -> downloading SP3...");
        ProductDownloader.DownloadResult r = downloader.download(obsDateUtc, ProductDownloader.ProductType.SP3);
        for (ProductDownloader.ProductFile f : r.files) {
            if (f.type == ProductDownloader.ProductType.SP3) { sp3File = f.localPath; break; }
        }
        return sp3File;
    }

    private static String ensureClk() {
        if (clkFile != null) return clkFile;
        log.info("  -> downloading CLK...");
        ProductDownloader.DownloadResult r = downloader.download(obsDateUtc, ProductDownloader.ProductType.CLK);
        for (ProductDownloader.ProductFile f : r.files) {
            if (f.type == ProductDownloader.ProductType.CLK) { clkFile = f.localPath; break; }
        }
        return clkFile;
    }

    private static String ensureErp() {
        if (erpFile != null) return erpFile;
        log.info("  -> downloading ERP...");
        ProductDownloader.DownloadResult r = downloader.download(obsDateUtc, ProductDownloader.ProductType.ERP);
        for (ProductDownloader.ProductFile f : r.files) {
            if (f.type == ProductDownloader.ProductType.ERP) { erpFile = f.localPath; break; }
        }
        return erpFile;
    }

    private static String ensureBia() {
        if (biaFile != null) return biaFile;
        log.info("  -> downloading BIA...");
        ProductDownloader.DownloadResult r = downloader.download(obsDateUtc, ProductDownloader.ProductType.BIA);
        for (ProductDownloader.ProductFile f : r.files) {
            if (f.type == ProductDownloader.ProductType.BIA) { biaFile = f.localPath; break; }
        }
        return biaFile;
    }

    private static String ensureFcb() {
        if (fcbFile != null) return fcbFile;
        log.info("  -> downloading FCB...");
        fcbFile = downloader.downloadFcb(obsDateUtc);
        return fcbFile;
    }

    private static String ensureDcb() {
        if (dcbFile != null) return dcbFile;
        log.info("  -> downloading DCB...");
        dcbFile = downloader.downloadDcb(obsDateUtc);
        return dcbFile;
    }

    private static String ensureGpt3() {
        if (gpt3File != null) return gpt3File;
        log.info("  -> downloading GPT3 grid...");
        gpt3File = downloader.downloadGpt3Grid();
        return gpt3File;
    }

    private static String ensureVmf3() {
        if (vmf3File != null) return vmf3File;
        log.info("  -> downloading VMF3...");
        vmf3File = downloader.downloadVmf3Grid(obsDateUtc);
        return vmf3File;
    }

    private static boolean ensurePreciseEph() {
        return ensureSp3() != null && ensureClk() != null;
    }

    private static PppProcessor createBdsPpp(boolean precise) {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.sateph = precise ? Constants.EPHOPT_PREC : Constants.EPHOPT_BRDC;
        PppProcessor p = new PppProcessor(opt);
        if (precise) {
            if (ensureSp3() != null) p.loadSp3(sp3File);
            if (ensureClk() != null) p.loadClk(clkFile);
            if (ensureErp() != null) p.loadErp(erpFile);
        }
        return p;
    }

    private static TestResult runTest(String label, PppProcessor processor) {
        TestResult tr = new TestResult();
        tr.label = label;
        try {
            PppProcessor.PppResult r = processor.processRinex(obsFile, navFile, null, null);
            tr.total = r.totalEpochs;
            tr.success = r.successCount;
            if (!r.solutions.isEmpty()) {
                SolData last = r.solutions.get(r.solutions.size() - 1);
                Position llh = last.getPosition(CoordType.LLH);
                if (llh != null) {
                    tr.lat = llh.v1;
                    tr.lon = llh.v2;
                    tr.h = llh.v3;
                }
                tr.fixCount = 0;
                for (SolData s : r.solutions) {
                    if (s.status == SolutionStatus.FIX) tr.fixCount++;
                }
            }
            tr.pass = r.successCount > 0;
            if (tr.pass) {
                log.info(String.format("[PASS] %s => total=%d, success=%d, rate=%.1f%%, fix=%d, pos=(%s)",
                        label, tr.total, tr.success,
                        tr.total > 0 ? 100.0 * tr.success / tr.total : 0,
                        tr.fixCount, tr.posStr()));
            } else {
                log.warn("[FAIL] {} => total={}, success=0", label, tr.total);
            }
        } catch (Exception e) {
            tr.pass = false;
            log.error("[FAIL] {} => {}", label, e.getMessage());
        }
        results.add(tr);
        return tr;
    }

    private static TestResult skipTest(String label, String reason) {
        log.info("[SKIP] {} => {}", label, reason);
        TestResult tr = new TestResult();
        tr.label = label;
        tr.skip = true;
        tr.reason = reason;
        results.add(tr);
        return tr;
    }

    private static void step4_pppBasicBrdc() {
        log.info("--- Step 4: BDS PPP basic (broadcast eph) ---");
        runTest("BRDC", createBdsPpp(false));
    }

    private static void step5_pppPrecise() {
        log.info("--- Step 5: BDS PPP + precise eph (SP3+CLK) ---");
        if (!ensurePreciseEph()) { skipTest("PREC", "SP3/CLK N/A"); return; }
        runTest("PREC", createBdsPpp(true));
    }

    private static void step6_pppGpt3Vmf3() {
        log.info("--- Step 6: BDS PPP + GPT3+VMF3 troposphere ---");
        if (!ensurePreciseEph()) { skipTest("GPT3", "SP3/CLK N/A"); return; }
        if (ensureGpt3() == null) { skipTest("GPT3", "GPT3 grid N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        p.loadGpt3Grid(gpt3File);
        if (ensureVmf3() != null) p.loadVmf3Op(vmf3File);
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        p.setRtkConfig(cfg);
        runTest("GPT3", p);
    }

    private static void step7_pppIers2010() {
        log.info("--- Step 7: BDS PPP + IERS2010 tides ---");
        if (!ensurePreciseEph()) { skipTest("IERS", "SP3/CLK N/A"); return; }
        if (ensureErp() == null) log.warn("ERP not available, IERS2010 may be incomplete");
        PppProcessor p = createBdsPpp(true);
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        p.setRtkConfig(cfg);
        runTest("IERS", p);
    }

    private static void step8_pppBiasModel() {
        log.info("--- Step 8: BDS PPP + ISB/IFCB bias model ---");
        if (!ensurePreciseEph()) { skipTest("BIAS", "SP3/CLK N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        if (ensureBia() != null) p.loadOsb(biaFile);
        if (ensureDcb() != null) p.loadDcb(dcbFile);
        RtkConfig cfg = new RtkConfig();
        cfg.enableIsbIfcbIfb = true;
        p.setRtkConfig(cfg);
        runTest("BIAS", p);
    }

    private static void step9_pppAR() {
        log.info("--- Step 9: BDS PPP-AR (WL+NL LAMBDA) ---");
        if (!ensurePreciseEph()) { skipTest("AR", "SP3/CLK N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        if (ensureFcb() != null) p.loadOsb(fcbFile);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        p.setRtkConfig(cfg);
        runTest("AR", p);
    }

    private static void step10_pppARFixHold() {
        log.info("--- Step 10: BDS PPP-AR + Fix-and-Hold ---");
        if (!ensurePreciseEph()) { skipTest("AR+FH", "SP3/CLK N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        if (ensureFcb() != null) p.loadOsb(fcbFile);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        p.setRtkConfig(cfg);
        runTest("AR+FH", p);
    }

    private static void step11_pppARPartial() {
        log.info("--- Step 11: BDS PPP-AR + Partial AR ---");
        if (!ensurePreciseEph()) { skipTest("AR+PAR", "SP3/CLK N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        if (ensureFcb() != null) p.loadOsb(fcbFile);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enablePppPartialAR = true;
        p.setRtkConfig(cfg);
        runTest("AR+PAR", p);
    }

    private static void step12_bds3PppAR() {
        log.info("--- Step 12: BDS-3 PPP-AR (B1C/B2a) ---");
        if (!ensurePreciseEph()) { skipTest("BDS3-AR", "SP3/CLK N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        if (ensureFcb() != null) p.loadOsb(fcbFile);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppAR = true;
        cfg.enableBds3PppAR = true;
        p.setRtkConfig(cfg);
        runTest("BDS3-AR", p);
    }

    private static void step13_pppIers2010Tidecorr7() {
        log.info("--- Step 13: BDS PPP + IERS2010 tides (tidecorr=7 with BLQ) ---");
        if (!ensurePreciseEph()) { skipTest("IERS-TC7", "SP3/CLK N/A"); return; }
        if (ensureErp() == null) log.warn("ERP not available, IERS2010 may be incomplete");
        PppProcessor p = createBdsPpp(true);
        String blqFile = PRODUCT_DIR + "\\blq\\" + STATION + ".blq";
        if (new File(blqFile).exists()) {
            OtlReader.readblq(blqFile, STATION, p.getRtk().opt.odisp[0]);
            log.info("BLQ loaded: {}", blqFile);
        } else {
            log.warn("BLQ file not found: {} (ocean tide loading disabled)", blqFile);
        }
        RtkConfig cfg = new RtkConfig();
        cfg.enableIers2010 = true;
        p.setRtkConfig(cfg);
        p.getRtk().opt.tidecorr = 7;
        runTest("IERS-TC7", p);
    }

    private static void step14_pppFullCombo() {
        log.info("--- Step 14: BDS PPP combo (IERS2010+BIAS+AR+FH) ---");
        if (!ensurePreciseEph()) { skipTest("COMBO", "SP3/CLK N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        boolean hasGpt3 = ensureGpt3() != null;
        if (hasGpt3) p.loadGpt3Grid(gpt3File);
        if (ensureVmf3() != null) p.loadVmf3Op(vmf3File);
        if (ensureFcb() != null) p.loadOsb(fcbFile);
        if (ensureDcb() != null) p.loadDcb(dcbFile);
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = hasGpt3;
        cfg.enableIers2010 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        p.setRtkConfig(cfg);
        runTest("COMBO", p);
    }

    private static void step15_allOnMax() {
        log.info("--- Step 15: ALL-ON MAX (every switch forced on) ---");
        if (!ensurePreciseEph()) { skipTest("ALL-ON", "SP3/CLK N/A"); return; }
        PppProcessor p = createBdsPpp(true);
        if (ensureGpt3() != null) p.loadGpt3Grid(gpt3File);
        if (ensureVmf3() != null) p.loadVmf3Op(vmf3File);
        if (ensureFcb() != null) p.loadOsb(fcbFile);
        if (ensureDcb() != null) p.loadDcb(dcbFile);
        String blqFile = PRODUCT_DIR + "\\blq\\" + STATION + ".blq";
        if (new File(blqFile).exists()) {
            OtlReader.readblq(blqFile, STATION, p.getRtk().opt.odisp[0]);
        }
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        cfg.enableIers2010 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;
        cfg.enablePppArFixHold = true;
        cfg.enablePppPartialAR = true;
        cfg.enableBds3PppAR = true;
        p.setRtkConfig(cfg);
        p.getRtk().opt.tidecorr = 7;
        runTest("ALL-ON", p);
    }

    private static void printSummaryTable() {
        int pass = 0, fail = 0, skip = 0;
        for (TestResult r : results) {
            if (r.skip) skip++;
            else if (r.pass) pass++;
            else fail++;
        }

        TestResult ref = null;
        for (TestResult r : results) {
            if (r.pass && r.label.equals("PREC")) { ref = r; break; }
        }
        if (ref == null) {
            for (TestResult r : results) {
                if (r.pass) { ref = r; break; }
            }
        }

        log.info("");
        log.info("+=====================================================================================================+");
        log.info("|                          PPP BDS COMPARISON TABLE                                                   |");
        log.info("+----------+---------+---------+-----------------------------------------+------------------+");
        log.info("|  Mode    |  Rate   |  Fix    |  Position (lat, lon, h)                |  dh vs PREC      |");
        log.info("+----------+---------+---------+-----------------------------------------+------------------+");

        for (TestResult r : results) {
            String rate = r.rateStr();
            String fix = r.skip ? "-" : String.valueOf(r.fixCount);
            String pos = r.skip ? r.reason : r.posStr();
            String dh;
            if (r.skip || ref == null) {
                dh = "-";
            } else {
                dh = String.format("%+.3f m", r.h - ref.h);
            }
            log.info(String.format("| %-8s | %6s  | %5s   | %-39s | %14s   |", r.label, rate, fix, pos, dh));
        }

        log.info("+----------+---------+---------+-----------------------------------------+------------------+");
        log.info(String.format("| PASS=%-3d  | FAIL=%-2d | SKIP=%-2d |  Ref: %-30s  |                  |", pass, fail, skip,
                ref != null ? ref.label : "N/A"));
        log.info("+=====================================================================================================|");
    }
}