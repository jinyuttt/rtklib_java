package org.rtklib.java.test;

import org.rtklib.java.common.CompatFileIO;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rtcm.Rtcm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class PppRtkRealtimeDemo {

    private static final Logger log = LoggerFactory.getLogger(PppRtkRealtimeDemo.class);

    private static final String RTCM_DIR = "D:\\rtcm3";
    private static final String PRODUCT_DIR = "D:\\rtcm3\\product";

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);

        log.info("============================================================");
        log.info("  PPP-RTK Realtime Demo (Local RTCM + NTRIP SSR)");
        log.info("============================================================");

        String station = "540423124124";
        String date = "2026-06-29";
        int hour = 0;

        String rtcmFile = RTCM_DIR + "\\" + station + "\\" + date + "\\" + hour + ".rtcm3";
        log.info("Station: {}  Date: {}  Hour: {}", station, date, hour);
        log.info("RTCM file: {}", rtcmFile);
        if (!new File(rtcmFile).exists()) {
            log.error("RTCM file NOT found: {}", rtcmFile);
            return;
        }

        NtripConfig ntripCfg = new NtripConfig();
        ntripCfg.host = "igs.bdsmart.cn";
        ntripCfg.port = 2101;
        ntripCfg.mountpoint = "IGS03";
        ntripCfg.username = "";
        ntripCfg.password = "";

        if (args.length >= 3) {
            ntripCfg.username = args[0];
            ntripCfg.password = args[1];
            ntripCfg.mountpoint = args[2];
        }
        if (args.length >= 4) ntripCfg.host = args[3];
        if (args.length >= 5) ntripCfg.port = Integer.parseInt(args[4]);

        log.info("NTRIP: {}:{}/{}", ntripCfg.host, ntripCfg.port, ntripCfg.mountpoint);

        String sp3File = PRODUCT_DIR + "\\sp3\\WUM0MGXRAP_20261800000_01D_05M_ORB.SP3";
        String sp3FilePrev = PRODUCT_DIR + "\\sp3\\WUM0MGXRAP_20261790000_01D_05M_ORB.SP3";
        String clkFile = PRODUCT_DIR + "\\clk\\WUM0MGXRAP_20261800000_01D_30S_CLK.CLK";
        String clkFilePrev = PRODUCT_DIR + "\\clk\\WUM0MGXRAP_20261790000_01D_30S_CLK.CLK";
        String erpFile = PRODUCT_DIR + "\\erp\\WUM0MGXRAP_20261800000_01D_01D_ERP.ERP";
        String erpFilePrev = PRODUCT_DIR + "\\erp\\WUM0MGXRAP_20261790000_01D_01D_ERP.ERP";
        String dcbFile = PRODUCT_DIR + "\\dcb\\CAS0MGXRTS_20261800_01D_01D_DCB.BSX";

        log.info("");
        log.info("--- Mode 1: PPP-RTK with NTRIP SSR (realtime simulation) ---");
        runPppRtkWithSsr("PPP-RTK-SSR", rtcmFile, ntripCfg,
                sp3FilePrev, sp3File, clkFilePrev, clkFile, erpFilePrev, dcbFile);

        log.info("");
        log.info("--- Mode 2: PPP-RTK without SSR (offline, for comparison) ---");
        runPppRtkOffline("PPP-RTK-OFFLINE", rtcmFile,
                sp3FilePrev, sp3File, clkFilePrev, clkFile, erpFilePrev, dcbFile);

        log.info("");
        log.info("--- Mode 3: List NTRIP sourcetable ---");
        listSourcetable(ntripCfg);

        log.info("");
        log.info("============================================================");
        log.info("  Demo Complete");
        log.info("============================================================");
    }

    private static void runPppRtkWithSsr(String label, String rtcmFile, NtripConfig ntripCfg,
                                          String sp3Prev, String sp3, String clkPrev, String clk,
                                          String erpPrev, String dcb) {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.ionoopt = Constants.IONOOPT_EST;
        opt.tropopt = Constants.TROPOPT_ESTG;
        opt.nf = 2;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.mode = Constants.PMODE_PPP_KINEMA;

        PppProcessor p = new PppProcessor(opt);
        loadProducts(p, sp3Prev, sp3, clkPrev, clk, erpPrev, dcb);

        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        cfg.enablePppRtkAR = true;
        cfg.pppRtkArRatio = 3.0;
        cfg.ssrMaxAge = 60.0;
        p.setRtkConfig(cfg);

        ConcurrentLinkedQueue<byte[]> ssrQueue = new ConcurrentLinkedQueue<>();
        AtomicInteger ssrFrameCount = new AtomicInteger(0);
        AtomicReference<CountDownLatch> connectLatch = new AtomicReference<>(new CountDownLatch(1));

        NtripClient ntrip = new NtripClient(ntripCfg.host, ntripCfg.port, ntripCfg.mountpoint,
                ntripCfg.username, ntripCfg.password, new NtripClient.DataListener() {
            @Override
            public void onData(byte[] data, int offset, int length) {
                byte[] copy = new byte[length];
                System.arraycopy(data, offset, copy, 0, length);
                ssrQueue.add(copy);
            }
            @Override
            public void onSourcetable(String sourcetable) {
                log.info("Sourcetable received ({} chars)", sourcetable.length());
            }
            @Override
            public void onError(Exception e) {
                log.warn("NTRIP error: {}", e.getMessage());
                connectLatch.get().countDown();
            }
        });

        boolean ntripConnected = false;
        try {
            ntrip.connect();
            ntrip.start();
            ntripConnected = true;
            log.info("NTRIP SSR stream connected, feeding SSR data into processor...");
            connectLatch.get().countDown();
        } catch (Exception e) {
            log.warn("NTRIP connection failed: {}. Running without SSR.", e.getMessage());
        }

        try {
            byte[] rtcmData = CompatFileIO.readAllBytes(rtcmFile);
            log.info("Local RTCM data: {} bytes", rtcmData.length);

            int chunkSize = 1500;
            int pos = 0;
            int epochCount = 0;
            long lastSsrFeed = 0;

            while (pos < rtcmData.length) {
                int len = Math.min(chunkSize, rtcmData.length - pos);
                byte[] chunk = new byte[len];
                System.arraycopy(rtcmData, pos, chunk, 0, len);
                pos += len;

                while (!ssrQueue.isEmpty()) {
                    byte[] ssrData = ssrQueue.poll();
                    p.feed(ssrData);
                    ssrFrameCount.incrementAndGet();
                    lastSsrFeed = System.currentTimeMillis();
                }

                p.feed(chunk);

                Rtk rtk = p.getRtk();
                if (rtk.sol != null && rtk.sol.stat != Constants.SOLQ_NONE) {
                    epochCount++;
                    if (epochCount % 30 == 0) {
                        printSol(label, rtk.sol, rtk.epoch, ssrFrameCount.get());
                    }
                }
            }

            PppProcessor.PppResult r = p.finish();
            printSummary(label, r, ssrFrameCount.get());

        } catch (Exception e) {
            log.error("[{}] FAILED: {}", label, e.getMessage(), e);
        } finally {
            if (ntripConnected) ntrip.stop();
        }
    }

    private static void runPppRtkOffline(String label, String rtcmFile,
                                          String sp3Prev, String sp3, String clkPrev, String clk,
                                          String erpPrev, String dcb) {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.ionoopt = Constants.IONOOPT_EST;
        opt.tropopt = Constants.TROPOPT_ESTG;
        opt.nf = 2;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.mode = Constants.PMODE_PPP_KINEMA;

        PppProcessor p = new PppProcessor(opt);
        loadProducts(p, sp3Prev, sp3, clkPrev, clk, erpPrev, dcb);

        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        cfg.enablePppRtkAR = true;
        p.setRtkConfig(cfg);

        try {
            PppProcessor.PppResult r = p.process(rtcmFile);
            printSummary(label, r, 0);
        } catch (Exception e) {
            log.error("[{}] FAILED: {}", label, e.getMessage(), e);
        }
    }

    private static void listSourcetable(NtripConfig cfg) {
        if (cfg.username.isEmpty()) {
            log.info("NTRIP credentials not provided. Skipping sourcetable listing.");
            log.info("Usage: PppRtkRealtimeDemo <username> <password> <mountpoint> [host] [port]");
            log.info("Example: PppRtkRealtimeDemo myuser mypass IGS03 igs.bdsmart.cn 2101");
            return;
        }

        try {
            StringBuilder table = new StringBuilder();
            NtripClient ntrip = new NtripClient(cfg.host, cfg.port, null,
                    cfg.username, cfg.password, new NtripClient.DataListener() {
                @Override
                public void onData(byte[] data, int offset, int length) {}
                @Override
                public void onSourcetable(String sourcetable) {
                    table.append(sourcetable);
                }
                @Override
                public void onError(Exception e) {
                    log.warn("Sourcetable error: {}", e.getMessage());
                }
            });

            ntrip.connect();
            String result = table.toString();

            if (result.isEmpty()) {
                log.info("No sourcetable received");
                return;
            }

            Map<String, String> mounts = NtripClient.parseSourcetable(result);
            log.info("Found {} mountpoints on {}:{}:", mounts.size(), cfg.host, cfg.port);

            List<String> ssrMounts = new ArrayList<>();
            List<String> obsMounts = new ArrayList<>();
            List<String> otherMounts = new ArrayList<>();

            for (Map.Entry<String, String> entry : mounts.entrySet()) {
                String mp = entry.getKey();
                String desc = entry.getValue();
                if (mp.contains("SSR") || mp.contains("ssr") || desc.contains("SSR")) {
                    ssrMounts.add(mp);
                } else if (desc.contains("RTCM") || desc.contains("RTCM3")) {
                    obsMounts.add(mp);
                } else {
                    otherMounts.add(mp);
                }
            }

            if (!ssrMounts.isEmpty()) {
                log.info("  === SSR Mountpoints (recommended for PPP-RTK) ===");
                for (String mp : ssrMounts) {
                    log.info("    /{} - {}", mp, mounts.get(mp));
                }
            }

            if (!obsMounts.isEmpty()) {
                log.info("  === Observation Mountpoints ===");
                for (String mp : obsMounts) {
                    log.info("    /{} - {}", mp, mounts.get(mp));
                }
            }

            if (!otherMounts.isEmpty()) {
                log.info("  === Other Mountpoints ===");
                int limit = Math.min(20, otherMounts.size());
                for (int i = 0; i < limit; i++) {
                    String mp = otherMounts.get(i);
                    log.info("    /{} - {}", mp, mounts.get(mp));
                }
                if (otherMounts.size() > limit) {
                    log.info("    ... and {} more", otherMounts.size() - limit);
                }
            }

        } catch (Exception e) {
            log.warn("Failed to get sourcetable: {}", e.getMessage());
        }
    }

    private static void loadProducts(PppProcessor p, String sp3Prev, String sp3,
                                     String clkPrev, String clk, String erpPrev, String dcb) {
        if (sp3Prev != null && new File(sp3Prev).exists()) p.loadSp3(sp3Prev);
        if (sp3 != null && new File(sp3).exists()) p.loadSp3(sp3);
        if (clkPrev != null && new File(clkPrev).exists()) p.loadClk(clkPrev);
        if (clk != null && new File(clk).exists()) p.loadClk(clk);
        if (erpPrev != null && new File(erpPrev).exists()) p.loadErp(erpPrev);
        if (dcb != null && new File(dcb).exists()) p.loadDcb(dcb);
    }

    private static void printSol(String label, Sol sol, int epoch, int ssrCount) {
        double[] llh = new double[3];
        CoordTransform.ecef2pos(sol.rr, llh);
        String status = solStatStr(sol.stat);
        log.info(String.format("[%s] epoch=%d  %s  lat=%.8f  lon=%.8f  h=%.4f  ns=%d  ssr=%d",
                label, epoch, status,
                Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2],
                sol.ns, ssrCount));
    }

    private static void printSummary(String label, PppProcessor.PppResult r, int ssrCount) {
        int fixCount = 0, floatCount = 0, pppCount = 0, singleCount = 0;
        for (SolData s : r.solutions) {
            if (s.status == SolutionStatus.FIX) fixCount++;
            else if (s.status == SolutionStatus.FLOAT) floatCount++;
            else if (s.status == SolutionStatus.PPP) pppCount++;
            else if (s.status == SolutionStatus.SINGLE) singleCount++;
        }
        double rate = r.totalEpochs > 0 ? 100.0 * r.successCount / r.totalEpochs : 0;
        log.info(String.format(
                "[%s] total=%d, success=%d, rate=%.1f%%, fix=%d, float=%d, ppp=%d, spp=%d, ssr=%d",
                label, r.totalEpochs, r.successCount, rate,
                fixCount, floatCount, pppCount, singleCount, ssrCount));

        if (!r.solutions.isEmpty()) {
            int startIdx = Math.max(0, r.solutions.size() - 10);
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
    }

    private static String solStatStr(int stat) {
        switch (stat) {
            case Constants.SOLQ_FIX: return "Fix";
            case Constants.SOLQ_FLOAT: return "Float";
            case Constants.SOLQ_PPP: return "PPP";
            case Constants.SOLQ_SINGLE: return "Single";
            default: return "None(" + stat + ")";
        }
    }

    private static class NtripConfig {
        String host;
        int port;
        String mountpoint;
        String username;
        String password;
    }
}