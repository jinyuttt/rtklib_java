package org.rtklib.java.test;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Locale;

public class PppRtkDemo {

    private static final Logger log = LoggerFactory.getLogger(PppRtkDemo.class);

    private static final String RTCM_DIR = "D:\\rtcm3";
    private static final String PRODUCT_DIR = "D:\\rtcm3\\product";

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);

        String station = "540423124124";
        String date = "2026-06-29";
        int hour = 0;

        log.info("============================================================");
        log.info("  PPP-RTK Demo");
        log.info("============================================================");

        String rtcmFile = RTCM_DIR + "\\" + station + "\\" + date + "\\" + hour + ".rtcm3";
        log.info("Station: {}  Date: {}  Hour: {}", station, date, hour);
        log.info("RTCM file: {}", rtcmFile);

        if (!new File(rtcmFile).exists()) {
            log.error("RTCM file NOT found: {}", rtcmFile);
            return;
        }
        log.info("RTCM file size: {} bytes", new File(rtcmFile).length());

        String sp3File = PRODUCT_DIR + "\\sp3\\WUM0MGXRAP_20261800000_01D_05M_ORB.SP3";
        String sp3FilePrev = PRODUCT_DIR + "\\sp3\\WUM0MGXRAP_20261790000_01D_05M_ORB.SP3";
        String clkFile = PRODUCT_DIR + "\\clk\\WUM0MGXRAP_20261800000_01D_30S_CLK.CLK";
        String clkFilePrev = PRODUCT_DIR + "\\clk\\WUM0MGXRAP_20261790000_01D_30S_CLK.CLK";
        String erpFile = PRODUCT_DIR + "\\erp\\WUM0MGXRAP_20261800000_01D_01D_ERP.ERP";
        String erpFilePrev = PRODUCT_DIR + "\\erp\\WUM0MGXRAP_20261790000_01D_01D_ERP.ERP";
        String dcbFile = PRODUCT_DIR + "\\dcb\\CAS0MGXRTS_20261800_01D_01D_DCB.BSX";

        log.info("");
        log.info("--- Test 1: PPP (IFLC, broadcast eph) ---");
        runPpp("PPP-BRDC", rtcmFile, null, null, null, null, null, null,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_EST, Constants.EPHOPT_BRDC, false, false);

        log.info("");
        log.info("--- Test 2: PPP (IFLC, precise eph) ---");
        runPpp("PPP-PREC", rtcmFile, sp3FilePrev, sp3File, clkFilePrev, clkFile, erpFilePrev, dcbFile,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_EST, Constants.EPHOPT_PREC, false, false);

        log.info("");
        log.info("--- Test 3: PPP-RTK (IFLC + ESTG trop) ---");
        runPpp("PPP-RTK", rtcmFile, sp3FilePrev, sp3File, clkFilePrev, clkFile, erpFilePrev, dcbFile,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_ESTG, Constants.EPHOPT_PREC, true, false);

        log.info("");
        log.info("--- Test 4: PPP-RTK + AR ---");
        runPpp("PPP-RTK-AR", rtcmFile, sp3FilePrev, sp3File, clkFilePrev, clkFile, erpFilePrev, dcbFile,
                Constants.IONOOPT_IFLC, Constants.TROPOPT_ESTG, Constants.EPHOPT_PREC, true, true);

        log.info("");
        log.info("--- Test 5: PPP-RTK (dual-freq, EST iono) ---");
        runPpp("PPP-RTK-IONO", rtcmFile, sp3FilePrev, sp3File, clkFilePrev, clkFile, erpFilePrev, dcbFile,
                Constants.IONOOPT_EST, Constants.TROPOPT_ESTG, Constants.EPHOPT_PREC, true, true);

        log.info("");
        log.info("============================================================");
        log.info("  Demo Complete");
        log.info("============================================================");
    }

    private static void runPpp(String label, String rtcmFile,
                               String sp3Prev, String sp3, String clkPrev, String clk,
                               String erpPrev, String dcb,
                               int ionoopt, int tropopt, int sateph,
                               boolean enablePppRtk, boolean enablePppRtkAR) {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.ionoopt = ionoopt;
        opt.tropopt = tropopt;
        opt.nf = 2;
        opt.sateph = sateph;
        opt.mode = Constants.PMODE_PPP_KINEMA;

        PppProcessor p = new PppProcessor(opt);

        if (sp3Prev != null && new File(sp3Prev).exists()) p.loadSp3(sp3Prev);
        if (sp3 != null && new File(sp3).exists()) p.loadSp3(sp3);
        if (clkPrev != null && new File(clkPrev).exists()) p.loadClk(clkPrev);
        if (clk != null && new File(clk).exists()) p.loadClk(clk);
        if (erpPrev != null && new File(erpPrev).exists()) p.loadErp(erpPrev);
        if (dcb != null && new File(dcb).exists()) p.loadDcb(dcb);

        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = enablePppRtk;
        cfg.enablePppRtkAR = enablePppRtkAR;
        p.setRtkConfig(cfg);

        try {
            PppProcessor.PppResult r = p.process(rtcmFile);

            int fixCount = 0, floatCount = 0, pppCount = 0, singleCount = 0;
            double lastLat = 0, lastLon = 0, lastH = 0;
            String lastTimeStr = "";
            SolutionStatus lastStatus = SolutionStatus.NONE;

            for (SolData s : r.solutions) {
                if (s.status == SolutionStatus.FIX) fixCount++;
                else if (s.status == SolutionStatus.FLOAT) floatCount++;
                else if (s.status == SolutionStatus.PPP) pppCount++;
                else if (s.status == SolutionStatus.SINGLE) singleCount++;
            }

            if (!r.solutions.isEmpty()) {
                SolData last = r.solutions.get(r.solutions.size() - 1);
                lastStatus = last.status;
                lastTimeStr = last.timeStr;
                Position llh = last.getPosition(CoordType.LLH);
                if (llh != null) {
                    lastLat = Math.toDegrees(llh.v1);
                    lastLon = Math.toDegrees(llh.v2);
                    lastH = llh.v3;
                }
            }

            double rate = r.totalEpochs > 0 ? 100.0 * r.successCount / r.totalEpochs : 0;
            log.info(String.format(
                    "[%s] total=%d, success=%d, rate=%.1f%%, fix=%d, float=%d, ppp=%d, spp=%d",
                    label, r.totalEpochs, r.successCount, rate,
                    fixCount, floatCount, pppCount, singleCount));
            if (!r.solutions.isEmpty()) {
                log.info(String.format(
                        "[%s] last: time=%s, status=%s, lat=%.8f, lon=%.8f, h=%.4f",
                        label, lastTimeStr, lastStatus, lastLat, lastLon, lastH));
            }

            if (r.solutions.size() > 10) {
                int startIdx = Math.max(0, r.solutions.size() - 20);
                log.info("[{}] === Last {} solutions ===", label, r.solutions.size() - startIdx);
                for (int i = startIdx; i < r.solutions.size(); i++) {
                    SolData s = r.solutions.get(i);
                    Position llh = s.getPosition(CoordType.LLH);
                    if (llh != null) {
                        log.info(String.format("  [%s] %s  %s  lat=%.8f  lon=%.8f  h=%.4f  ns=%d",
                                label, s.timeStr, s.status,
                                Math.toDegrees(llh.v1), Math.toDegrees(llh.v2), llh.v3, s.numSat));
                    }
                }
            }
        } catch (Exception e) {
            log.error("[{}] FAILED: {}", label, e.getMessage(), e);
        }
    }
}