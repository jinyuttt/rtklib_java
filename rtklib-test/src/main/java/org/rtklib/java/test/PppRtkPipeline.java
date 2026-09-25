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

public class PppRtkPipeline {

    private static final Logger log = LoggerFactory.getLogger(PppRtkPipeline.class);

    private static final String YAXIA_DIR = "D:\\rtcm3";
    private static final String PRODUCT_DIR = "D:\\rtcm3\\product";

    private static String sp3File;
    private static String clkFile;
    private static String erpFile;
    private static String dcbFile;
    private static String gpt3File;
    private static String vmf3File;
    private static String fcbFile;
    private static ProductDownloader downloader;

    static class TestResult {
        String label;
        boolean pass;
        boolean skip;
        String reason;
        int total;
        int success;
        double lat, lon, h;
        int fixCount;
        int floatCount;
        int pppCount;
        int singleCount;

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
        log.info("  PPP-RTK Pipeline Test");
        log.info("============================================================");

        String station = args.length > 0 ? args[0] : "540423124124";
        String dateStr = args.length > 1 ? args[1] : "2026-06-29";

        String rtcmDir = YAXIA_DIR + "\\" + station + "\\" + dateStr;
        String rtcmFile = rtcmDir + "\\0.rtcm3";

        log.info("Station: {}  Date: {}  RTCM: {}", station, dateStr, rtcmFile);

        if (!new File(rtcmFile).exists()) {
            log.error("RTCM file NOT found: {}", rtcmFile);
            System.exit(1);
        }
        log.info("RTCM file: {} ({} bytes)", rtcmFile, new File(rtcmFile).length());

        String tempDir = PRODUCT_DIR + "\\rinex\\" + station + "\\" + dateStr;
        new File(tempDir).mkdirs();
        String obsFile = tempDir + "\\" + station + ".obs";
        String navFile = tempDir + "\\" + station + ".nav";

        log.info("Converting RTCM to RINEX...");
        try {
            RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, tempDir, station);
            converter.convert(rtcmFile);
            log.info("RINEX: obs={} nav={}", obsFile, navFile);
        } catch (Exception e) {
            log.error("RTCM conversion error: {}", e.getMessage());
            System.exit(1);
        }

        // 文件夹日期是北京时间，RTCM内时间戳是UTC
        // 北京2026-06-29 = UTC 2026-06-28T16:00 ~ 2026-06-29T15:59
        // 用UTC日期（与文件夹同日）下载产品，覆盖大部分观测数据
        LocalDate obsDateUtc = LocalDate.parse(dateStr);
        log.info("Folder date (Beijing): {} => UTC obs date: {}", dateStr, obsDateUtc);
        downloader = new ProductDownloader(PRODUCT_DIR);

        ensureProducts(obsDateUtc);

        test1_pppBaseline(obsFile, navFile);
        test2_pppRtkIflc(obsFile, navFile);
        test3_pppRtkDualFreq(obsFile, navFile);
        test4_pppRtkMultiSys(obsFile, navFile);
        test5_pppRtkWithAR(obsFile, navFile);
        test6_pppRtkFullCombo(obsFile, navFile);
        test7_pppRtkSsrIono(obsFile, navFile);

        testRtcmDirect(rtcmFile, station);

        printSummary();
    }

    private static String sp3FilePrev, clkFilePrev, erpFilePrev;

    private static void ensureProducts(LocalDate obsDate) {
        log.info("--- Ensuring precise products for UTC {} and {} ---", obsDate, obsDate.minusDays(1));
        // 前一天产品（覆盖北京日期前8小时UTC数据）
        ProductDownloader.DownloadResult rSp3p = downloader.download(obsDate.minusDays(1), ProductDownloader.ProductType.SP3);
        for (ProductDownloader.ProductFile f : rSp3p.files) {
            if (f.type == ProductDownloader.ProductType.SP3) { sp3FilePrev = f.localPath; break; }
        }
        ProductDownloader.DownloadResult rClkp = downloader.download(obsDate.minusDays(1), ProductDownloader.ProductType.CLK);
        for (ProductDownloader.ProductFile f : rClkp.files) {
            if (f.type == ProductDownloader.ProductType.CLK) { clkFilePrev = f.localPath; break; }
        }
        ProductDownloader.DownloadResult rErpp = downloader.download(obsDate.minusDays(1), ProductDownloader.ProductType.ERP);
        for (ProductDownloader.ProductFile f : rErpp.files) {
            if (f.type == ProductDownloader.ProductType.ERP) { erpFilePrev = f.localPath; break; }
        }
        // 当天产品
        ProductDownloader.DownloadResult rSp3 = downloader.download(obsDate, ProductDownloader.ProductType.SP3);
        for (ProductDownloader.ProductFile f : rSp3.files) {
            if (f.type == ProductDownloader.ProductType.SP3) { sp3File = f.localPath; break; }
        }
        ProductDownloader.DownloadResult rClk = downloader.download(obsDate, ProductDownloader.ProductType.CLK);
        for (ProductDownloader.ProductFile f : rClk.files) {
            if (f.type == ProductDownloader.ProductType.CLK) { clkFile = f.localPath; break; }
        }
        ProductDownloader.DownloadResult rErp = downloader.download(obsDate, ProductDownloader.ProductType.ERP);
        for (ProductDownloader.ProductFile f : rErp.files) {
            if (f.type == ProductDownloader.ProductType.ERP) { erpFile = f.localPath; break; }
        }
        dcbFile = downloader.downloadDcb(obsDate);
        // Skip downloads that hang - use only cached products
        // gpt3File = downloader.downloadGpt3Grid();
        // vmf3File = downloader.downloadVmf3Grid(obsDate);
        // fcbFile = downloader.downloadFcb(obsDate);

        log.info("SP3={} CLK={} ERP={} DCB={} GPT3={} VMF3={} FCB={}",
                sp3File != null, clkFile != null, erpFile != null,
                dcbFile != null, gpt3File != null, vmf3File != null, fcbFile != null);
    }

    private static PppProcessor createProcessor(int navsys, int ionoopt, int tropopt, int nf) {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = navsys;
        opt.ionoopt = ionoopt;
        opt.tropopt = tropopt;
        opt.nf = nf;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.mode = Constants.PMODE_PPP_KINEMA;

        PppProcessor p = new PppProcessor(opt);

        // 加载前一天产品（覆盖北京日期前8小时UTC数据）
        if (sp3FilePrev != null) p.loadSp3(sp3FilePrev);
        if (clkFilePrev != null) p.loadClk(clkFilePrev);
        if (erpFilePrev != null) p.loadErp(erpFilePrev);
        // 加载当天产品
        if (sp3File != null) p.loadSp3(sp3File);
        if (clkFile != null) p.loadClk(clkFile);
        if (erpFile != null) p.loadErp(erpFile);
        if (dcbFile != null) p.loadDcb(dcbFile);

        return p;
    }

    private static void countStatuses(PppProcessor.PppResult r, TestResult tr) {
        tr.fixCount = 0;
        tr.floatCount = 0;
        tr.pppCount = 0;
        tr.singleCount = 0;
        if (!r.solutions.isEmpty()) {
            SolData last = r.solutions.get(r.solutions.size() - 1);
            Position llh = last.getPosition(CoordType.LLH);
            if (llh != null) {
                tr.lat = llh.v1;
                tr.lon = llh.v2;
                tr.h = llh.v3;
            }
            for (SolData s : r.solutions) {
                if (s.status == SolutionStatus.FIX) tr.fixCount++;
                else if (s.status == SolutionStatus.FLOAT) tr.floatCount++;
                else if (s.status == SolutionStatus.PPP) tr.pppCount++;
                else if (s.status == SolutionStatus.SINGLE) tr.singleCount++;
            }
        }
    }

    private static TestResult runRinexTest(String label, PppProcessor processor, String obsFile, String navFile) {
        TestResult tr = new TestResult();
        tr.label = label;
        try {
            PppProcessor.PppResult r = processor.processRinex(obsFile, navFile, null, null);
            tr.total = r.totalEpochs;
            tr.success = r.successCount;
            countStatuses(r, tr);
            tr.pass = r.successCount > 0;
            logResult(tr);
        } catch (Exception e) {
            tr.pass = false;
            log.error("[FAIL] {} => {}", label, e.getMessage());
        }
        results.add(tr);
        return tr;
    }

    private static void logResult(TestResult tr) {
        if (tr.pass) {
            log.info(String.format("[PASS] %s => total=%d, success=%d, rate=%.1f%%, fix=%d, float=%d, ppp=%d, spp=%d, pos=(%s)",
                    tr.label, tr.total, tr.success,
                    tr.total > 0 ? 100.0 * tr.success / tr.total : 0,
                    tr.fixCount, tr.floatCount, tr.pppCount, tr.singleCount, tr.posStr()));
        } else {
            log.warn("[FAIL] {} => total={}, success=0", tr.label, tr.total);
        }
    }

    private static void test1_pppBaseline(String obsFile, String navFile) {
        log.info("--- Test 1: PPP baseline (IFLC, EST trop) ---");
        PppProcessor p = createProcessor(
                Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_EST, 2);
        runRinexTest("PPP-BL", p, obsFile, navFile);
    }

    private static void test2_pppRtkIflc(String obsFile, String navFile) {
        log.info("--- Test 2: PPP-RTK IFLC mode ---");
        PppProcessor p = createProcessor(
                Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_ESTG, 2);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        p.setRtkConfig(cfg);
        runRinexTest("PPRTK-IF", p, obsFile, navFile);
    }

    private static void test3_pppRtkDualFreq(String obsFile, String navFile) {
        log.info("--- Test 3: PPP-RTK dual-freq EST iono ---");
        PppProcessor p = createProcessor(
                Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP,
                Constants.IONOOPT_EST, Constants.TROPOPT_ESTG, 2);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        p.setRtkConfig(cfg);
        runRinexTest("PPRTK-DF", p, obsFile, navFile);
    }

    private static void test4_pppRtkMultiSys(String obsFile, String navFile) {
        log.info("--- Test 4: PPP-RTK multi-GNSS (GPS+GAL+BDS) ---");
        PppProcessor p = createProcessor(
                Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_ESTG, 2);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        p.setRtkConfig(cfg);
        if (gpt3File != null) p.loadGpt3Grid(gpt3File);
        if (vmf3File != null) p.loadVmf3Op(vmf3File);
        if (erpFile != null) p.loadErp(erpFile);
        runRinexTest("PPRTK-MS", p, obsFile, navFile);
    }

    private static void test5_pppRtkWithAR(String obsFile, String navFile) {
        log.info("--- Test 5: PPP-RTK + AR ---");
        PppProcessor p = createProcessor(
                Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_ESTG, 2);
        if (fcbFile != null) p.loadOsb(fcbFile);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        cfg.enablePppRtkAR = true;
        p.setRtkConfig(cfg);
        runRinexTest("PPRTK-AR", p, obsFile, navFile);
    }

    private static void test6_pppRtkFullCombo(String obsFile, String navFile) {
        log.info("--- Test 6: PPP-RTK full combo ---");
        PppProcessor p = createProcessor(
                Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_ESTG, 2);
        if (fcbFile != null) p.loadOsb(fcbFile);
        if (dcbFile != null) p.loadDcb(dcbFile);
        if (gpt3File != null) p.loadGpt3Grid(gpt3File);
        if (vmf3File != null) p.loadVmf3Op(vmf3File);
        if (erpFile != null) p.loadErp(erpFile);

        String blqFile = PRODUCT_DIR + "\\blq\\540423124124.blq";
        if (new File(blqFile).exists()) {
            OtlReader.readblq(blqFile, "540423124124", p.getRtk().opt.odisp[0]);
            log.info("BLQ loaded: {}", blqFile);
        }

        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        cfg.enablePppRtkAR = true;
        cfg.enablePppRtkFixHold = true;
        cfg.enableIers2010 = true;
        p.setRtkConfig(cfg);
        p.getRtk().opt.tidecorr = 7;
        runRinexTest("PPRTK-FULL", p, obsFile, navFile);
    }

    private static void test7_pppRtkSsrIono(String obsFile, String navFile) {
        log.info("--- Test 7: PPP-RTK with IONOOPT_SSR ---");
        PppProcessor p = createProcessor(
                Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP,
                Constants.IONOOPT_SSR, Constants.TROPOPT_SSR, 2);
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        cfg.enablePppRtkAR = true;
        p.setRtkConfig(cfg);
        if (fcbFile != null) p.loadOsb(fcbFile);
        if (dcbFile != null) p.loadDcb(dcbFile);
        if (gpt3File != null) p.loadGpt3Grid(gpt3File);
        if (vmf3File != null) p.loadVmf3Op(vmf3File);
        if (erpFile != null) p.loadErp(erpFile);
        runRinexTest("PPRTK-SSR", p, obsFile, navFile);
    }

    private static void testRtcmDirect(String rtcmFile, String station) {
        log.info("--- Test 8: PPP-RTK direct RTCM stream ---");
        try {
            PrcOpt opt = PppProcessor.createDefaultOpt();
            opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
            opt.ionoopt = Constants.IONOOPT_IFLC;
            opt.tropopt = Constants.TROPOPT_ESTG;
            opt.nf = 2;
            opt.sateph = Constants.EPHOPT_PREC;

            PppProcessor p = new PppProcessor(opt);
            if (sp3File != null) p.loadSp3(sp3File);
            if (clkFile != null) p.loadClk(clkFile);
            if (erpFile != null) p.loadErp(erpFile);
            if (dcbFile != null) p.loadDcb(dcbFile);

            RtkConfig cfg = new RtkConfig();
            cfg.enablePppRtk = true;
            p.setRtkConfig(cfg);

            PppProcessor.PppResult r = p.process(rtcmFile);

            TestResult tr = new TestResult();
            tr.label = "PPRTK-RTCM";
            tr.total = r.totalEpochs;
            tr.success = r.successCount;
            countStatuses(r, tr);
            tr.pass = r.successCount > 0;
            logResult(tr);
            results.add(tr);
        } catch (Exception e) {
            log.error("[FAIL] PPRTK-RTCM => {}", e.getMessage());
            TestResult tr = new TestResult();
            tr.label = "PPRTK-RTCM";
            tr.pass = false;
            results.add(tr);
        }
    }

    private static void printSummary() {
        int pass = 0, fail = 0, skip = 0;
        for (TestResult r : results) {
            if (r.skip) skip++;
            else if (r.pass) pass++;
            else fail++;
        }

        TestResult ref = null;
        for (TestResult r : results) {
            if (r.pass && r.label.equals("PPP-BL")) { ref = r; break; }
        }
        if (ref == null) {
            for (TestResult r : results) {
                if (r.pass) { ref = r; break; }
            }
        }

        log.info("");
        log.info("+=====================================================================================================================================+");
        log.info("|                          PPP-RTK COMPARISON TABLE                                                                                   |");
        log.info("+------------+---------+---------+---------+---------+---------+-----------------------------------------+------------------+");
        log.info("|  Mode      |  Rate   |  Fix    |  Float  |  PPP    |  SPP    |  Position (lat, lon, h)                |  dh vs BL        |");
        log.info("+------------+---------+---------+---------+---------+---------+-----------------------------------------+------------------+");

        for (TestResult r : results) {
            String rate = r.rateStr();
            String fix = r.skip ? "-" : String.valueOf(r.fixCount);
            String flt = r.skip ? "-" : String.valueOf(r.floatCount);
            String ppp = r.skip ? "-" : String.valueOf(r.pppCount);
            String spp = r.skip ? "-" : String.valueOf(r.singleCount);
            String pos = r.skip ? r.reason : r.posStr();
            String dh;
            if (r.skip || ref == null) {
                dh = "-";
            } else {
                dh = String.format("%+.3f m", r.h - ref.h);
            }
            log.info(String.format("| %-10s | %6s  | %5s   | %5s   | %5s   | %5s   | %-39s | %14s   |", r.label, rate, fix, flt, ppp, spp, pos, dh));
        }

        log.info("+------------+---------+---------+---------+---------+---------+-----------------------------------------+------------------+");
        log.info(String.format("| PASS=%-3d    | FAIL=%-2d | SKIP=%-2d |  Ref: %-30s  |                  |", pass, fail, skip,
                ref != null ? ref.label : "N/A"));
        log.info("+=====================================================================================================================================+");
    }
}