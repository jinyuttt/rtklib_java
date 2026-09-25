package org.rtklib.java.test;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.product.ProductDownloader;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;

public class PppArDiag {

    private static final Logger log = LoggerFactory.getLogger(PppArDiag.class);

    private static final String YAXIA_DIR = "D:\\rtcm3";
    private static final String STATION = "540423124124";
    private static final String DATE_STR = "2026-06-29";
    private static final String RTCM_FILE = YAXIA_DIR + "\\" + STATION + "\\" + DATE_STR + "\\0.rtcm3";
    private static final String PRODUCT_DIR = "D:\\rtcm3\\product";

    public static void main(String[] args) {
        log.info("=== PPP-AR Diagnostic Test ===");

        if (!new File(RTCM_FILE).exists()) {
            log.error("RTCM file NOT found: {}", RTCM_FILE);
            System.exit(1);
        }
        log.info("RTCM file: {} ({} bytes)", RTCM_FILE, new File(RTCM_FILE).length());

        String tempDir = PRODUCT_DIR + "\\rinex\\" + STATION;
        new File(tempDir).mkdirs();
        String obsFile = tempDir + "\\" + STATION + ".obs";
        String navFile = tempDir + "\\" + STATION + ".nav";

        if (!new File(obsFile).exists()) {
            log.info("Converting RTCM to RINEX...");
            try {
                RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, tempDir, STATION);
                converter.convert(RTCM_FILE);
                log.info("RINEX: obs={} nav={}", obsFile, navFile);
            } catch (Exception e) {
                log.error("RTCM conversion error: {}", e.getMessage());
                System.exit(1);
            }
        } else {
            log.info("RINEX already exists: obs={}", obsFile);
        }

        LocalDate obsDateUtc = LocalDate.parse(DATE_STR);
        ProductDownloader downloader = new ProductDownloader(PRODUCT_DIR);

        String sp3Prev = null, clkPrev = null, erpPrev = null;
        String sp3File = null, clkFile = null, erpFile = null, fcbFile = null;

        log.info("Downloading products...");
        for (ProductDownloader.ProductFile f : downloader.download(obsDateUtc.minusDays(1), ProductDownloader.ProductType.SP3).files) {
            if (f.type == ProductDownloader.ProductType.SP3) { sp3Prev = f.localPath; break; }
        }
        for (ProductDownloader.ProductFile f : downloader.download(obsDateUtc.minusDays(1), ProductDownloader.ProductType.CLK).files) {
            if (f.type == ProductDownloader.ProductType.CLK) { clkPrev = f.localPath; break; }
        }
        for (ProductDownloader.ProductFile f : downloader.download(obsDateUtc.minusDays(1), ProductDownloader.ProductType.ERP).files) {
            if (f.type == ProductDownloader.ProductType.ERP) { erpPrev = f.localPath; break; }
        }
        for (ProductDownloader.ProductFile f : downloader.download(obsDateUtc, ProductDownloader.ProductType.SP3).files) {
            if (f.type == ProductDownloader.ProductType.SP3) { sp3File = f.localPath; break; }
        }
        for (ProductDownloader.ProductFile f : downloader.download(obsDateUtc, ProductDownloader.ProductType.CLK).files) {
            if (f.type == ProductDownloader.ProductType.CLK) { clkFile = f.localPath; break; }
        }
        for (ProductDownloader.ProductFile f : downloader.download(obsDateUtc, ProductDownloader.ProductType.ERP).files) {
            if (f.type == ProductDownloader.ProductType.ERP) { erpFile = f.localPath; break; }
        }
        fcbFile = downloader.downloadFcb(obsDateUtc);
        if (fcbFile == null) {
            String cachedBia = PRODUCT_DIR + "\\bia\\WUM0MGXRAP_20261800000_01D_01D_OSB.BIA";
            if (new File(cachedBia).exists()) {
                log.info("Using cached BIA file: {}", cachedBia);
                fcbFile = cachedBia;
            }
        }

        log.info("SP3={} CLK={} ERP={} FCB={}", sp3File != null, clkFile != null, erpFile != null, fcbFile != null);

        log.info("--- Test 1: BDS PPP-AR (WL+NL) ---");
        {
            PrcOpt opt = PppProcessor.createDefaultOpt();
            opt.navsys = Constants.SYS_CMP;
            opt.sateph = Constants.EPHOPT_PREC;
            PppProcessor p = new PppProcessor(opt);
            if (sp3Prev != null) p.loadSp3(sp3Prev);
            if (clkPrev != null) p.loadClk(clkPrev);
            if (erpPrev != null) p.loadErp(erpPrev);
            if (sp3File != null) p.loadSp3(sp3File);
            if (clkFile != null) p.loadClk(clkFile);
            if (erpFile != null) p.loadErp(erpFile);
            if (fcbFile != null) p.loadOsb(fcbFile);
            RtkConfig cfg = new RtkConfig();
            cfg.enablePppAR = true;
            p.setRtkConfig(cfg);
            runAndReport("BDS-AR", p, obsFile, navFile);
        }

        log.info("--- Test 2: Multi-GNSS PPP-AR (GPS+GAL+BDS) ---");
        {
            PrcOpt opt = PppProcessor.createDefaultOpt();
            opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
            opt.sateph = Constants.EPHOPT_PREC;
            PppProcessor p = new PppProcessor(opt);
            if (sp3Prev != null) p.loadSp3(sp3Prev);
            if (clkPrev != null) p.loadClk(clkPrev);
            if (erpPrev != null) p.loadErp(erpPrev);
            if (sp3File != null) p.loadSp3(sp3File);
            if (clkFile != null) p.loadClk(clkFile);
            if (erpFile != null) p.loadErp(erpFile);
            if (fcbFile != null) p.loadOsb(fcbFile);
            RtkConfig cfg = new RtkConfig();
            cfg.enablePppAR = true;
            p.setRtkConfig(cfg);
            runAndReport("MGNSS-AR", p, obsFile, navFile);
        }

        log.info("=== Diagnostic Complete ===");
    }

    private static void runAndReport(String label, PppProcessor p, String obsFile, String navFile) {
        try {
            PppProcessor.PppResult r = p.processRinex(obsFile, navFile, null, null);
            int fixCount = 0, pppCount = 0;
            for (SolData s : r.solutions) {
                if (s.status == SolutionStatus.FIX) fixCount++;
                else if (s.status == SolutionStatus.PPP) pppCount++;
            }
            log.info(String.format("[%s] total=%d success=%d fix=%d ppp=%d rate=%.1f%%",
                    label, r.totalEpochs, r.successCount, fixCount, pppCount,
                    r.totalEpochs > 0 ? 100.0 * r.successCount / r.totalEpochs : 0));
            if (!r.solutions.isEmpty()) {
                SolData last = r.solutions.get(r.solutions.size() - 1);
                Position llh = last.getPosition(CoordType.LLH);
                if (llh != null) {
                    log.info(String.format("[%s] last pos: %.6f, %.6f, %.3f", label, llh.v1, llh.v2, llh.v3));
                }
            }
        } catch (Exception e) {
            log.error("[{}] FAILED: {}", label, e.getMessage());
        }
    }
}