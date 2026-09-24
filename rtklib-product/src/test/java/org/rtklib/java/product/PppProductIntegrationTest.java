package org.rtklib.java.product;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨模塊PPP集成測試：ProductDownloader + PppProcessor。
 *
 * <h3>時間系統說明</h3>
 * <ul>
 *   <li>RTCM文件夾日期 = 北京時間日期（UTC+8）</li>
 *   <li>RTCM文件名 H.rtcm3 = 北京時間第H小時</li>
 *   <li>RTCM內部觀測時間 = UTC時間</li>
 *   <li>IGS精密產品按UTC日期命名</li>
 * </ul>
 *
 * <h3>北京時間→UTC日期映射</h3>
 * <pre>
 *   北京文件夾 2026-07-01:
 *     0~7.rtcm3  → UTC 2026-06-30 16:00~23:59  → 需UTC 06-30的產品
 *     8~23.rtcm3 → UTC 2026-07-01 00:00~15:59  → 需UTC 07-01的產品
 * </pre>
 * 因此一個北京日期的文件夾需要下載<strong>兩個UTC日期</strong>的精密產品：
 * folderDate - 1天 和 folderDate。
 */
@DisplayName("PPP Integration Test with ProductDownloader + PppProcessor")
public class PppProductIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PppProductIntegrationTest.class);

    private static final String RTCM_BASE_DIR = "D:\\yaxia\\rtcm";

    private static final LocalDate BJ_START_DATE = LocalDate.of(2026, 7, 1);
    private static final LocalDate BJ_END_DATE = LocalDate.of(2026, 7, 6);

    private static final int BJ_OFFSET_HOURS = 8;

    private static final Map<String, String[]> BASE_ROVER_MAP = new LinkedHashMap<>();

    static {
        BASE_ROVER_MAP.put("540423231901", new String[]{
                "540423187770", "540423379882", "540423211132", "540423230321",
                "540423124124", "540423147354", "540423503435", "540423128131"
        });
        BASE_ROVER_MAP.put("540423214120", new String[]{
                "540423156203", "540423128507", "540423276898", "540423355855",
                "540423860355", "540423268595"
        });
    }

    private static boolean dataAvailable = false;
    private static List<TestPair> testPairs = new ArrayList<>();

    static class TestPair {
        final LocalDate bjDate;
        final String baseSn;
        final String roverSn;
        final List<String> baseRtcmFiles;
        final List<String> roverRtcmFiles;

        TestPair(LocalDate bjDate, String baseSn, String roverSn,
                 List<String> baseRtcmFiles, List<String> roverRtcmFiles) {
            this.bjDate = bjDate;
            this.baseSn = baseSn;
            this.roverSn = roverSn;
            this.baseRtcmFiles = baseRtcmFiles;
            this.roverRtcmFiles = roverRtcmFiles;
        }

        Set<LocalDate> getRequiredUtcDates() {
            Set<LocalDate> utcDates = new TreeSet<>();
            utcDates.add(bjDate.minusDays(1));
            utcDates.add(bjDate);
            return utcDates;
        }
    }

    @BeforeAll
    static void scanData() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (LocalDate bjDate = BJ_START_DATE; !bjDate.isAfter(BJ_END_DATE); bjDate = bjDate.plusDays(1)) {
            String dateStr = bjDate.format(fmt);

            for (Map.Entry<String, String[]> entry : BASE_ROVER_MAP.entrySet()) {
                String base = entry.getKey();
                Path baseDateDir = Paths.get(RTCM_BASE_DIR, base, dateStr);
                if (!Files.isDirectory(baseDateDir)) continue;

                List<String> baseFiles = listRtcmFiles(baseDateDir);
                if (baseFiles.isEmpty()) continue;

                for (String rover : entry.getValue()) {
                    Path roverDateDir = Paths.get(RTCM_BASE_DIR, rover, dateStr);
                    if (!Files.isDirectory(roverDateDir)) continue;

                    List<String> roverFiles = listRtcmFiles(roverDateDir);
                    if (roverFiles.isEmpty()) continue;

                    dataAvailable = true;
                    testPairs.add(new TestPair(bjDate, base, rover, baseFiles, roverFiles));
                }
            }
        }

        if (dataAvailable) {
            log.info("Found {} test pairs for Beijing dates {} ~ {}", testPairs.size(), BJ_START_DATE, BJ_END_DATE);
            log.info("Time system: folder=date(BJ), filename=hour(BJ), RTCM content=UTC");
            log.info("Each BJ date needs UTC products for: (BJ-1day) and (BJ)");

            Set<LocalDate> allUtcDates = new TreeSet<>();
            for (TestPair tp : testPairs) allUtcDates.addAll(tp.getRequiredUtcDates());
            log.info("Required UTC product dates: {}", allUtcDates);

            Map<LocalDate, Long> byDate = testPairs.stream()
                    .collect(Collectors.groupingBy(d -> d.bjDate, Collectors.counting()));
            byDate.forEach((d, c) -> log.info("  BJ {}: {} pairs", d, c));
        } else {
            log.warn("No July 2026 RTCM data available");
        }
    }

    @Test
    @DisplayName("Download SP3/CLK/ERP for required UTC dates and run PPP")
    void testPppWithDownloadedProducts() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        Set<LocalDate> allUtcDates = new TreeSet<>();
        for (TestPair tp : testPairs) allUtcDates.addAll(tp.getRequiredUtcDates());

        log.info("Downloading precise products for {} UTC dates: {} ~ {}",
                allUtcDates.size(), Collections.min(allUtcDates), Collections.max(allUtcDates));

        ProductDownloader.DownloadResult dlResult = dl.download(
                Collections.min(allUtcDates), Collections.max(allUtcDates),
                ProductDownloader.ProductType.SP3,
                ProductDownloader.ProductType.CLK,
                ProductDownloader.ProductType.ERP
        );

        log.info("Download result: {} files, {} errors", dlResult.files.size(), dlResult.errors.size());
        for (ProductDownloader.ProductFile pf : dlResult.files) {
            log.info("  {} -> {}", pf.type, pf.localPath);
        }
        for (String err : dlResult.errors) {
            log.warn("  ERROR: {}", err);
        }

        Map<LocalDate, String> sp3Map = new TreeMap<>();
        Map<LocalDate, String> clkMap = new TreeMap<>();
        for (ProductDownloader.ProductFile pf : dlResult.files) {
            LocalDate productDate = extractUtcDateFromProduct(pf.localPath);
            switch (pf.type) {
                case SP3 -> sp3Map.put(productDate, pf.localPath);
                case CLK -> clkMap.put(productDate, pf.localPath);
            }
        }
        log.info("Available SP3 dates: {}", sp3Map.keySet());
        log.info("Available CLK dates: {}", clkMap.keySet());

        int pppSuccess = 0;
        int pppTotal = 0;

        List<TestPair> roverUnique = testPairs.stream()
                .collect(Collectors.groupingBy(d -> d.roverSn + "_" + d.bjDate))
                .values().stream()
                .map(list -> list.get(0))
                .toList();

        for (TestPair tp : roverUnique) {
            List<String> sp3Files = new ArrayList<>();
            List<String> clkFiles = new ArrayList<>();
            for (LocalDate utcDate : tp.getRequiredUtcDates()) {
                if (sp3Map.containsKey(utcDate)) sp3Files.add(sp3Map.get(utcDate));
                if (clkMap.containsKey(utcDate)) clkFiles.add(clkMap.get(utcDate));
            }

            if (sp3Files.isEmpty() && clkFiles.isEmpty()) {
                log.warn("No precise products for BJ {}, skipping PPP", tp.bjDate);
                continue;
            }

            pppTotal++;
            log.info("PPP [{}/{}] rover={} BJ={} UTC dates={} sp3={} clk={}",
                    pppTotal, roverUnique.size(), tp.roverSn, tp.bjDate,
                    tp.getRequiredUtcDates(),
                    sp3Files.stream().map(f -> Path.of(f).getFileName().toString()).toList(),
                    clkFiles.stream().map(f -> Path.of(f).getFileName().toString()).toList());

            try {
                PrcOpt opt = PppProcessor.createDefaultOpt();
                opt.mode = Constants.PMODE_PPP_KINEMA;
                opt.nf = 2;
                opt.elmin = 10.0 * Constants.D2R;
                opt.tropopt = Constants.TROPOPT_EST;
                opt.ionoopt = Constants.IONOOPT_IFLC;
                opt.sateph = Constants.EPHOPT_PREC;
                opt.tidecorr = 1;

                RtkConfig cfg = new RtkConfig();
                cfg.enableGpt3Vmf3 = true;
                cfg.enableIers2010 = true;

                PppProcessor ppp = new PppProcessor(opt);
                ppp.setRtkConfig(cfg);

                for (String sp3 : sp3Files) ppp.loadSp3(sp3);
                for (String clk : clkFiles) ppp.loadClk(clk);

                String roverFile = tp.roverRtcmFiles.get(0);
                PppProcessor.PppResult result = ppp.process(roverFile);

                log.info("  PPP result: {} solutions ({} success, {} fail)",
                        result.solutions != null ? result.solutions.size() : 0,
                        result.successCount, result.failCount);

                if (result.solutions != null && !result.solutions.isEmpty()) {
                    pppSuccess++;
                    SolData last = result.solutions.get(result.solutions.size() - 1);
                    log.info("  Last solution: time={} status={} numSat={}",
                            last.timeStr, last.status, last.numSat);
                }
            } catch (Exception e) {
                log.warn("  PPP error: {}", e.getMessage());
            }
        }

        log.info("PPP summary: {}/{} runs produced solutions", pppSuccess, pppTotal);
        if (pppTotal > 0 && !sp3Map.isEmpty()) {
            assertTrue(pppSuccess > 0,
                    "At least one PPP run should produce solutions when precise products are available");
        }
    }

    @Test
    @DisplayName("PPP with all optimizations + downloaded products")
    void testPppWithAllOptimizations() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        TestPair first = testPairs.get(0);
        Set<LocalDate> utcDates = first.getRequiredUtcDates();

        ProductDownloader.DownloadResult dlResult = dl.download(
                Collections.min(utcDates), Collections.max(utcDates),
                ProductDownloader.ProductType.SP3,
                ProductDownloader.ProductType.CLK
        );

        List<String> sp3Files = new ArrayList<>();
        List<String> clkFiles = new ArrayList<>();
        for (ProductDownloader.ProductFile pf : dlResult.files) {
            if (pf.type == ProductDownloader.ProductType.SP3) sp3Files.add(pf.localPath);
            if (pf.type == ProductDownloader.ProductType.CLK) clkFiles.add(pf.localPath);
        }

        if (sp3Files.isEmpty() && clkFiles.isEmpty()) {
            log.warn("No precise products downloaded, skipping");
            return;
        }

        log.info("PPP+AllOpt: rover={} BJ={} UTC={} sp3={} clk={}",
                first.roverSn, first.bjDate, utcDates,
                sp3Files.stream().map(f -> Path.of(f).getFileName().toString()).toList(),
                clkFiles.stream().map(f -> Path.of(f).getFileName().toString()).toList());

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.tidecorr = 1;

        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        cfg.enableIers2010 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;

        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);
        for (String sp3 : sp3Files) ppp.loadSp3(sp3);
        for (String clk : clkFiles) ppp.loadClk(clk);

        PppProcessor.PppResult result = ppp.process(first.roverRtcmFiles.get(0));
        log.info("PPP+AllOpt result: {} solutions", result.solutions != null ? result.solutions.size() : 0);
    }

    @Test
    @DisplayName("PPP position statistics - coordinate and fluctuation analysis")
    void testPppPositionStatistics() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        List<TestPair> roverUnique = testPairs.stream()
                .collect(Collectors.groupingBy(d -> d.roverSn + "_" + d.bjDate))
                .values().stream().map(list -> list.get(0)).toList();

        int runCount = 0;
        for (TestPair tp : roverUnique) {
            if (runCount >= 3) break;
            runCount++;

            Set<LocalDate> utcDates = tp.getRequiredUtcDates();
            ProductDownloader.DownloadResult dlResult = dl.download(
                    Collections.min(utcDates), Collections.max(utcDates),
                    ProductDownloader.ProductType.SP3,
                    ProductDownloader.ProductType.CLK
            );

            List<String> sp3Files = new ArrayList<>();
            List<String> clkFiles = new ArrayList<>();
            for (ProductDownloader.ProductFile pf : dlResult.files) {
                if (pf.type == ProductDownloader.ProductType.SP3) sp3Files.add(pf.localPath);
                if (pf.type == ProductDownloader.ProductType.CLK) clkFiles.add(pf.localPath);
            }
            if (sp3Files.isEmpty()) continue;

            PrcOpt opt = PppProcessor.createDefaultOpt();
            opt.mode = Constants.PMODE_PPP_STATIC;
            opt.nf = 2;
            opt.elmin = 10.0 * Constants.D2R;
            opt.tropopt = Constants.TROPOPT_EST;
            opt.ionoopt = Constants.IONOOPT_IFLC;
            opt.sateph = Constants.EPHOPT_PREC;
            opt.tidecorr = 1;

            RtkConfig cfg = new RtkConfig();
            cfg.enableGpt3Vmf3 = true;
            cfg.enableIers2010 = true;

            PppProcessor ppp = new PppProcessor(opt);
            ppp.setRtkConfig(cfg);
            for (String sp3 : sp3Files) ppp.loadSp3(sp3);
            for (String clk : clkFiles) ppp.loadClk(clk);

            String roverFile = tp.roverRtcmFiles.get(0);
            PppProcessor.PppResult result = ppp.process(roverFile);

            log.info("PPP raw result: total={} success={} fail={}",
                    result.totalEpochs, result.successCount, result.failCount);

            if (result.solutions == null || result.solutions.isEmpty()) continue;

            List<SolData> sols = result.solutions;
            int validLlh = 0, invalidLlh = 0;
            for (SolData s : sols) {
                Position p = s.getPosition(CoordType.LLH);
                if (p != null && Math.abs(p.v1) < 89.0) validLlh++;
                else invalidLlh++;
            }
            log.info("  Valid LLH: {}/{} (invalid={})", validLlh, sols.size(), invalidLlh);
            int n = sols.size();
            double sumLat = 0, sumLon = 0, sumH = 0;
            double minLat = 999, maxLat = -999, minLon = 999, maxLon = -999, minH = 99999, maxH = -99999;
            int validCount = 0;
            for (SolData s : sols) {
                Position p = s.getPosition(CoordType.LLH);
                if (p == null) continue;
                validCount++;
                double lat = p.v1, lon = p.v2, h = p.v3;
                sumLat += lat; sumLon += lon; sumH += h;
                if (lat < minLat) minLat = lat;
                if (lat > maxLat) maxLat = lat;
                if (lon < minLon) minLon = lon;
                if (lon > maxLon) maxLon = lon;
                if (h < minH) minH = h;
                if (h > maxH) maxH = h;
            }
            if (validCount == 0) continue;
            n = validCount;
            double meanLat = sumLat / n, meanLon = sumLon / n, meanH = sumH / n;
            double sumDLat2 = 0, sumDLon2 = 0, sumDH2 = 0;
            for (SolData s : sols) {
                Position p = s.getPosition(CoordType.LLH);
                if (p == null) continue;
                sumDLat2 += (p.v1 - meanLat) * (p.v1 - meanLat);
                sumDLon2 += (p.v2 - meanLon) * (p.v2 - meanLon);
                sumDH2 += (p.v3 - meanH) * (p.v3 - meanH);
            }
            double stdN = Math.sqrt(sumDLat2 / n) * 111000;
            double stdE = Math.sqrt(sumDLon2 / n) * 111000 * Math.cos(Math.toRadians(meanLat));
            double stdU = Math.sqrt(sumDH2 / n);

            log.info("PPP Position [{}] rover={} BJ={} file={}",
                    runCount, tp.roverSn, tp.bjDate, Path.of(roverFile).getFileName());
            log.info("  Epochs: {} | Mean: {}", n,
                    String.format("lat=%.8f lon=%.8f h=%.3f", meanLat, meanLon, meanH));
            log.info("  Std: {}", String.format("N=%.3fm E=%.3fm U=%.3fm", stdN, stdE, stdU));
            log.info("  Range N: {}", String.format("%.8f~%.8f (%.3fm)", minLat, maxLat, (maxLat - minLat) * 111000));
            log.info("  Range E: {}", String.format("%.8f~%.8f (%.3fm)", minLon, maxLon, (maxLon - minLon) * 111000 * Math.cos(Math.toRadians(meanLat))));
            log.info("  Range U: {}", String.format("%.3f~%.3f (%.3fm)", minH, maxH, maxH - minH));

            SolData first = sols.get(0);
            SolData last = sols.get(sols.size() - 1);
            Position fp = first.getPosition(CoordType.LLH);
            Position lp = last.getPosition(CoordType.LLH);
            if (fp != null) {
                log.info("  First: {} {}", first.timeStr,
                        String.format("lat=%.8f lon=%.8f h=%.3f ns=%d", fp.v1, fp.v2, fp.v3, first.numSat));
            } else {
                Position ecef = first.getPosition(CoordType.ECEF);
                log.info("  First (ECEF only): {} {}", first.timeStr,
                        ecef != null ? String.format("x=%.3f y=%.3f z=%.3f ns=%d", ecef.v1, ecef.v2, ecef.v3, first.numSat) : "null pos");
            }
            if (lp != null) {
                log.info("  Last:  {} {}", last.timeStr,
                        String.format("lat=%.8f lon=%.8f h=%.3f ns=%d", lp.v1, lp.v2, lp.v3, last.numSat));
            }
        }
    }

    @Test
    @DisplayName("RTK with July data - all base/rover pairs per date")
    void testRtkJulyAllPairs() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        int pairCount = 0;
        int successCount = 0;
        int totalSolutions = 0;

        List<TestPair> unique = testPairs.stream()
                .collect(Collectors.groupingBy(d -> d.baseSn + "_" + d.roverSn + "_" + d.bjDate))
                .values().stream()
                .map(list -> list.get(0))
                .toList();

        for (TestPair tp : unique) {
            pairCount++;
            log.info("RTK [{}/{}] base={} rover={} BJ={}",
                    pairCount, unique.size(), tp.baseSn, tp.roverSn, tp.bjDate);

            try {
                PrcOpt opt = RtkProcessor.createDefaultOpt();
                opt.mode = Constants.PMODE_KINEMA;
                opt.nf = 2;
                opt.elmin = 15.0 * Constants.D2R;
                opt.tropopt = Constants.TROPOPT_EST;
                opt.ionoopt = Constants.IONOOPT_BRDC;

                RtkProcessor rtk = new RtkProcessor(opt);

                String baseFile = tp.baseRtcmFiles.get(0);
                String roverFile = tp.roverRtcmFiles.get(0);

                RtkProcessor.RtkResult result = rtk.process(roverFile, baseFile);

                int solCount = result.solutions != null ? result.solutions.size() : 0;
                log.info("  RTK result: {} solutions ({} success, {} fail)",
                        solCount, result.successCount, result.failCount);

                if (solCount > 0) {
                    successCount++;
                    totalSolutions += solCount;
                }
            } catch (Exception e) {
                log.warn("  RTK error: {}", e.getMessage());
            }
        }

        log.info("RTK July summary: {}/{} pairs successful, {} total solutions",
                successCount, pairCount, totalSolutions);
        assertTrue(successCount > 0, "At least one RTK pair should produce solutions");
    }

    @Test
    @DisplayName("RTK with optimizations - compare default vs optimized")
    void testRtkOptimizationsComparison() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        TestPair tp = testPairs.get(0);
        String baseFile = tp.baseRtcmFiles.get(0);
        String roverFile = tp.roverRtcmFiles.get(0);

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_BRDC;

        RtkProcessor rtkDefault = new RtkProcessor(opt);
        RtkProcessor.RtkResult resultDefault = rtkDefault.process(roverFile, baseFile);
        int solDefault = resultDefault.solutions != null ? resultDefault.solutions.size() : 0;
        log.info("RTK default: {} solutions", solDefault);

        RtkConfig cfg = new RtkConfig();
        cfg.enableBootstrapping = true;
        cfg.enableBdsCodeBias = true;
        cfg.enableResidualEdit = true;

        RtkProcessor rtkOpt = new RtkProcessor(opt);
        rtkOpt.setRtkConfig(cfg);
        RtkProcessor.RtkResult resultOpt = rtkOpt.process(roverFile, baseFile);
        int solOpt = resultOpt.solutions != null ? resultOpt.solutions.size() : 0;
        log.info("RTK optimized (Bootstrap+BdsBias+ResEdit): {} solutions", solOpt);

        log.info("RTK comparison: default={} vs optimized={}", solDefault, solOpt);
    }

    @Test
    @DisplayName("ProductDownloader - verify download for required UTC dates")
    void testProductDownloadOnly() throws Exception {
        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        LocalDate utcStart = BJ_START_DATE.minusDays(1);
        LocalDate utcEnd = BJ_END_DATE;

        log.info("Downloading products for UTC {} ~ {} (BJ folder {} ~ {})",
                utcStart, utcEnd, BJ_START_DATE, BJ_END_DATE);

        ProductDownloader.DownloadResult result = dl.download(
                utcStart, utcEnd,
                ProductDownloader.ProductType.SP3,
                ProductDownloader.ProductType.CLK,
                ProductDownloader.ProductType.ERP
        );

        log.info("Product download: {} files, {} errors", result.files.size(), result.errors.size());
        for (ProductDownloader.ProductFile pf : result.files) {
            log.info("  {}", pf);
            assertTrue(Files.exists(Path.of(pf.localPath)),
                    "Downloaded file should exist: " + pf.localPath);
        }

        if (!result.errors.isEmpty()) {
            log.warn("Some products failed to download (network or future date issue?):");
            for (String err : result.errors) {
                log.warn("  {}", err);
            }
        }
    }

    @Test
    @DisplayName("PPP Kinematic 24h - baseline vs optimized comparison")
    void testPppKinematic24hComparison() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        List<TestPair> roverUnique = testPairs.stream()
                .collect(Collectors.groupingBy(d -> d.roverSn + "_" + d.bjDate))
                .values().stream().map(list -> list.get(0)).toList();

        int runCount = 0;
        for (TestPair tp : roverUnique) {
            if (runCount >= 2) break;
            runCount++;

            Set<LocalDate> utcDates = tp.getRequiredUtcDates();
            ProductDownloader.DownloadResult dlResult = dl.download(
                    Collections.min(utcDates), Collections.max(utcDates),
                    ProductDownloader.ProductType.SP3,
                    ProductDownloader.ProductType.CLK
            );

            List<String> sp3Files = new ArrayList<>();
            List<String> clkFiles = new ArrayList<>();
            for (ProductDownloader.ProductFile pf : dlResult.files) {
                if (pf.type == ProductDownloader.ProductType.SP3) sp3Files.add(pf.localPath);
                if (pf.type == ProductDownloader.ProductType.CLK) clkFiles.add(pf.localPath);
            }
            if (sp3Files.isEmpty()) continue;

            List<String> allRoverFiles = tp.roverRtcmFiles;
            log.info("========== PPP Kinematic 24h Comparison [{}] rover={} BJ={} files={} ==========",
                    runCount, tp.roverSn, tp.bjDate, allRoverFiles.size());

            PppStats baseline = runPppKinematicMultiFile(sp3Files, clkFiles, allRoverFiles, false);
            PppStats optimized = runPppKinematicMultiFile(sp3Files, clkFiles, allRoverFiles, true);

            log.info("--- Baseline (RTKLIB standard) 24h ---");
            baseline.log("Baseline", log);
            log.info("--- Optimized (GPT3+VMF3, IERS2010) 24h ---");
            optimized.log("Optimized", log);

            if (baseline.validCount > 0 && optimized.validCount > 0) {
                double dN = optimized.stdN - baseline.stdN;
                double dE = optimized.stdE - baseline.stdE;
                double dU = optimized.stdU - baseline.stdU;
                log.info("--- Difference (Optimized - Baseline) 24h ---");
                log.info(String.format("  Std: N=%+.3fm E=%+.3fm U=%+.3fm", dN, dE, dU));
                log.info("  Epochs: baseline={} optimized={}", baseline.validCount, optimized.validCount);
            }
        }
    }

    @Test
    @DisplayName("PPP Static 24h - baseline vs optimized comparison")
    void testPppStatic24hComparison() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        List<TestPair> roverUnique = testPairs.stream()
                .collect(Collectors.groupingBy(d -> d.roverSn + "_" + d.bjDate))
                .values().stream().map(list -> list.get(0)).toList();

        int runCount = 0;
        for (TestPair tp : roverUnique) {
            if (runCount >= 2) break;
            runCount++;

            Set<LocalDate> utcDates = tp.getRequiredUtcDates();
            ProductDownloader.DownloadResult dlResult = dl.download(
                    Collections.min(utcDates), Collections.max(utcDates),
                    ProductDownloader.ProductType.SP3,
                    ProductDownloader.ProductType.CLK
            );

            List<String> sp3Files = new ArrayList<>();
            List<String> clkFiles = new ArrayList<>();
            for (ProductDownloader.ProductFile pf : dlResult.files) {
                if (pf.type == ProductDownloader.ProductType.SP3) sp3Files.add(pf.localPath);
                if (pf.type == ProductDownloader.ProductType.CLK) clkFiles.add(pf.localPath);
            }
            if (sp3Files.isEmpty()) continue;

            List<String> allRoverFiles = tp.roverRtcmFiles;
            log.info("========== PPP Static 24h Comparison [{}] rover={} BJ={} files={} ==========",
                    runCount, tp.roverSn, tp.bjDate, allRoverFiles.size());

            PppStats baseline = runPppStaticMultiFile(sp3Files, clkFiles, allRoverFiles, false);
            PppStats optimized = runPppStaticMultiFile(sp3Files, clkFiles, allRoverFiles, true);

            log.info("--- Baseline (RTKLIB standard) 24h ---");
            baseline.log("Baseline", log);
            log.info("--- Optimized (GPT3+VMF3, IERS2010) 24h ---");
            optimized.log("Optimized", log);

            if (baseline.validCount > 0 && optimized.validCount > 0) {
                double dLat = (optimized.meanLat - baseline.meanLat) * 111000;
                double dLon = (optimized.meanLon - baseline.meanLon) * 111000 * Math.cos(Math.toRadians(baseline.meanLat));
                double dH = optimized.meanH - baseline.meanH;
                log.info("--- Position Difference (Optimized - Baseline) 24h ---");
                log.info(String.format("  dN=%.3fm dE=%.3fm dU=%.3fm", dLat, dLon, dH));
                log.info("  Epochs: baseline={} optimized={}", baseline.validCount, optimized.validCount);
            }
        }
    }

    private static PppStats runPppStaticMultiFile(List<String> sp3Files, List<String> clkFiles,
                                                   List<String> roverFiles, boolean optimized) throws Exception {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_STATIC;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.tidecorr = 1;

        RtkConfig cfg = new RtkConfig();
        if (optimized) {
            cfg.enableGpt3Vmf3 = true;
            cfg.enableIers2010 = true;
        }

        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);
        for (String sp3 : sp3Files) ppp.loadSp3(sp3);
        for (String clk : clkFiles) ppp.loadClk(clk);

        List<SolData> allSolutions = new ArrayList<>();
        int totalEpochs = 0, successCount = 0, failCount = 0;

        for (int i = 0; i < roverFiles.size(); i++) {
            String roverFile = roverFiles.get(i);
            log.info("  Processing file [{}/{}]: {}", i + 1, roverFiles.size(),
                    Path.of(roverFile).getFileName());

            PppProcessor.PppResult result = ppp.process(roverFile);
            totalEpochs += result.totalEpochs;
            successCount += result.successCount;
            failCount += result.failCount;
            if (result.solutions != null) {
                allSolutions.addAll(result.solutions);
            }

            if (i < roverFiles.size() - 1) {
                ppp.resetForNextBatch();
            }
        }

        PppProcessor.PppResult combined = new PppProcessor.PppResult(totalEpochs, successCount, failCount, allSolutions);
        return computePppStats(combined);
    }

    private static PppStats runPppKinematicMultiFile(List<String> sp3Files, List<String> clkFiles,
                                                      List<String> roverFiles, boolean optimized) throws Exception {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.tidecorr = 1;

        RtkConfig cfg = new RtkConfig();
        if (optimized) {
            cfg.enableGpt3Vmf3 = true;
            cfg.enableIers2010 = true;
        }

        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);
        for (String sp3 : sp3Files) ppp.loadSp3(sp3);
        for (String clk : clkFiles) ppp.loadClk(clk);

        List<SolData> allSolutions = new ArrayList<>();
        int totalEpochs = 0, successCount = 0, failCount = 0;

        for (int i = 0; i < roverFiles.size(); i++) {
            String roverFile = roverFiles.get(i);
            log.info("  Processing file [{}/{}]: {}", i + 1, roverFiles.size(),
                    Path.of(roverFile).getFileName());

            PppProcessor.PppResult result = ppp.process(roverFile);
            totalEpochs += result.totalEpochs;
            successCount += result.successCount;
            failCount += result.failCount;
            if (result.solutions != null) {
                allSolutions.addAll(result.solutions);
            }

            if (i < roverFiles.size() - 1) {
                ppp.resetForNextBatch();
            }
        }

        PppProcessor.PppResult combined = new PppProcessor.PppResult(totalEpochs, successCount, failCount, allSolutions);
        return computePppStats(combined);
    }

    @Test
    @DisplayName("PPP Kinematic - baseline vs optimized comparison")
    void testPppKinematicComparison() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        List<TestPair> roverUnique = testPairs.stream()
                .collect(Collectors.groupingBy(d -> d.roverSn + "_" + d.bjDate))
                .values().stream().map(list -> list.get(0)).toList();

        int runCount = 0;
        for (TestPair tp : roverUnique) {
            if (runCount >= 3) break;
            runCount++;

            Set<LocalDate> utcDates = tp.getRequiredUtcDates();
            ProductDownloader.DownloadResult dlResult = dl.download(
                    Collections.min(utcDates), Collections.max(utcDates),
                    ProductDownloader.ProductType.SP3,
                    ProductDownloader.ProductType.CLK
            );

            List<String> sp3Files = new ArrayList<>();
            List<String> clkFiles = new ArrayList<>();
            for (ProductDownloader.ProductFile pf : dlResult.files) {
                if (pf.type == ProductDownloader.ProductType.SP3) sp3Files.add(pf.localPath);
                if (pf.type == ProductDownloader.ProductType.CLK) clkFiles.add(pf.localPath);
            }
            if (sp3Files.isEmpty()) continue;

            String roverFile = tp.roverRtcmFiles.get(0);

            log.info("========== PPP Kinematic Comparison [{}] rover={} BJ={} ==========",
                    runCount, tp.roverSn, tp.bjDate);

            PppStats baseline = runPppKinematic(sp3Files, clkFiles, roverFile, false);
            PppStats optimized = runPppKinematic(sp3Files, clkFiles, roverFile, true);

            log.info("--- Baseline (RTKLIB standard) ---");
            baseline.log("Baseline", log);
            log.info("--- Optimized (GPT3+VMF3, IERS2010) ---");
            optimized.log("Optimized", log);

            if (baseline.validCount > 0 && optimized.validCount > 0) {
                double dN = optimized.stdN - baseline.stdN;
                double dE = optimized.stdE - baseline.stdE;
                double dU = optimized.stdU - baseline.stdU;
                log.info("--- Difference (Optimized - Baseline) ---");
                log.info(String.format("  Std: N=%+.3fm E=%+.3fm U=%+.3fm", dN, dE, dU));
                log.info("  Epochs: baseline={} optimized={}", baseline.validCount, optimized.validCount);
            }
        }
    }

    private static PppStats runPppKinematic(List<String> sp3Files, List<String> clkFiles,
                                             String roverFile, boolean optimized) throws Exception {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.tidecorr = 1;

        RtkConfig cfg = new RtkConfig();
        if (optimized) {
            cfg.enableGpt3Vmf3 = true;
            cfg.enableIers2010 = true;
        }

        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);
        for (String sp3 : sp3Files) ppp.loadSp3(sp3);
        for (String clk : clkFiles) ppp.loadClk(clk);

        PppProcessor.PppResult result = ppp.process(roverFile);
        return computePppStats(result);
    }

    private static PppStats computePppStats(PppProcessor.PppResult result) {
        PppStats stats = new PppStats();
        stats.totalEpochs = result.totalEpochs;
        stats.successCount = result.successCount;
        stats.failCount = result.failCount;

        if (result.solutions == null || result.solutions.isEmpty()) return stats;

        List<SolData> sols = result.solutions;
        double sumLat = 0, sumLon = 0, sumH = 0;
        double minLat = 999, maxLat = -999, minLon = 999, maxLon = -999, minH = 99999, maxH = -99999;
        int validCount = 0;
        for (SolData s : sols) {
            Position p = s.getPosition(CoordType.LLH);
            if (p == null || Math.abs(p.v1) >= 89.0) continue;
            validCount++;
            double lat = p.v1, lon = p.v2, h = p.v3;
            sumLat += lat; sumLon += lon; sumH += h;
            if (lat < minLat) minLat = lat;
            if (lat > maxLat) maxLat = lat;
            if (lon < minLon) minLon = lon;
            if (lon > maxLon) maxLon = lon;
            if (h < minH) minH = h;
            if (h > maxH) maxH = h;
        }
        stats.validCount = validCount;
        if (validCount == 0) return stats;

        double meanLat = sumLat / validCount, meanLon = sumLon / validCount, meanH = sumH / validCount;
        stats.meanLat = meanLat; stats.meanLon = meanLon; stats.meanH = meanH;
        stats.minLat = minLat; stats.maxLat = maxLat;
        stats.minLon = minLon; stats.maxLon = maxLon;
        stats.minH = minH; stats.maxH = maxH;

        double sumDLat2 = 0, sumDLon2 = 0, sumDH2 = 0;
        for (SolData s : sols) {
            Position p = s.getPosition(CoordType.LLH);
            if (p == null || Math.abs(p.v1) >= 89.0) continue;
            sumDLat2 += (p.v1 - meanLat) * (p.v1 - meanLat);
            sumDLon2 += (p.v2 - meanLon) * (p.v2 - meanLon);
            sumDH2 += (p.v3 - meanH) * (p.v3 - meanH);
        }
        stats.stdN = Math.sqrt(sumDLat2 / validCount) * 111000;
        stats.stdE = Math.sqrt(sumDLon2 / validCount) * 111000 * Math.cos(Math.toRadians(meanLat));
        stats.stdU = Math.sqrt(sumDH2 / validCount);

        SolData first = sols.get(0);
        SolData last = sols.get(sols.size() - 1);
        stats.firstTime = first.timeStr;
        stats.lastTime = last.timeStr;
        Position fp = first.getPosition(CoordType.LLH);
        Position lp = last.getPosition(CoordType.LLH);
        if (fp != null) {
            stats.firstLat = fp.v1; stats.firstLon = fp.v2; stats.firstH = fp.v3;
            stats.firstNs = first.numSat;
        }
        if (lp != null) {
            stats.lastLat = lp.v1; stats.lastLon = lp.v2; stats.lastH = lp.v3;
            stats.lastNs = last.numSat;
        }

        return stats;
    }

    @Test
    @DisplayName("PPP Static - baseline vs optimized comparison")
    void testPppStaticComparison() throws Exception {
        if (!dataAvailable) {
            log.info("Skipping - no RTCM data available");
            return;
        }

        String cacheDir = System.getProperty("user.dir") + File.separator + "product";
        ProductDownloader dl = new ProductDownloader(cacheDir);

        List<TestPair> roverUnique = testPairs.stream()
                .collect(Collectors.groupingBy(d -> d.roverSn + "_" + d.bjDate))
                .values().stream().map(list -> list.get(0)).toList();

        int runCount = 0;
        for (TestPair tp : roverUnique) {
            if (runCount >= 3) break;
            runCount++;

            Set<LocalDate> utcDates = tp.getRequiredUtcDates();
            ProductDownloader.DownloadResult dlResult = dl.download(
                    Collections.min(utcDates), Collections.max(utcDates),
                    ProductDownloader.ProductType.SP3,
                    ProductDownloader.ProductType.CLK
            );

            List<String> sp3Files = new ArrayList<>();
            List<String> clkFiles = new ArrayList<>();
            for (ProductDownloader.ProductFile pf : dlResult.files) {
                if (pf.type == ProductDownloader.ProductType.SP3) sp3Files.add(pf.localPath);
                if (pf.type == ProductDownloader.ProductType.CLK) clkFiles.add(pf.localPath);
            }
            if (sp3Files.isEmpty()) continue;

            String roverFile = tp.roverRtcmFiles.get(0);

            log.info("========== PPP Static Comparison [{}] rover={} BJ={} ==========",
                    runCount, tp.roverSn, tp.bjDate);

            PppStats baseline = runPppStatic(sp3Files, clkFiles, roverFile, false);
            PppStats optimized = runPppStatic(sp3Files, clkFiles, roverFile, true);

            log.info("--- Baseline (RTKLIB standard) ---");
            baseline.log("Baseline", log);
            log.info("--- Optimized (GPT3+VMF3, IERS2010) ---");
            optimized.log("Optimized", log);

            if (baseline.validCount > 0 && optimized.validCount > 0) {
                double dN = optimized.stdN - baseline.stdN;
                double dE = optimized.stdE - baseline.stdE;
                double dU = optimized.stdU - baseline.stdU;
                log.info("--- Difference (Optimized - Baseline) ---");
                log.info(String.format("  Std: N=%+.3fm E=%+.3fm U=%+.3fm", dN, dE, dU));
                log.info("  Epochs: baseline={} optimized={}", baseline.validCount, optimized.validCount);
            }
        }
    }

    private static PppStats runPppStatic(List<String> sp3Files, List<String> clkFiles,
                                          String roverFile, boolean optimized) throws Exception {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_STATIC;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.tidecorr = 1;

        RtkConfig cfg = new RtkConfig();
        if (optimized) {
            cfg.enableGpt3Vmf3 = true;
            cfg.enableIers2010 = true;
        }

        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);
        for (String sp3 : sp3Files) ppp.loadSp3(sp3);
        for (String clk : clkFiles) ppp.loadClk(clk);

        PppProcessor.PppResult result = ppp.process(roverFile);
        return computePppStats(result);
    }

    static class PppStats {
        int totalEpochs, successCount, failCount, validCount;
        double meanLat, meanLon, meanH;
        double stdN, stdE, stdU;
        double minLat, maxLat, minLon, maxLon, minH, maxH;
        String firstTime, lastTime;
        double firstLat, firstLon, firstH;
        double lastLat, lastLon, lastH;
        int firstNs, lastNs;

        void log(String label, Logger l) {
            l.info("  {} epochs: total={} success={} fail={} valid={}",
                    label, totalEpochs, successCount, failCount, validCount);
            if (validCount > 0) {
                l.info(String.format("  %s mean: lat=%.8f lon=%.8f h=%.3f", label, meanLat, meanLon, meanH));
                l.info(String.format("  %s std:  N=%.3fm E=%.3fm U=%.3fm", label, stdN, stdE, stdU));
                l.info(String.format("  %s range N: %.8f~%.8f (%.3fm)", label, minLat, maxLat, (maxLat - minLat) * 111000));
                l.info(String.format("  %s range E: %.8f~%.8f (%.3fm)", label, minLon, maxLon, (maxLon - minLon) * 111000 * Math.cos(Math.toRadians(meanLat))));
                l.info(String.format("  %s range U: %.3f~%.3f (%.3fm)", label, minH, maxH, maxH - minH));
                if (firstTime != null) {
                    l.info(String.format("  %s first: %s lat=%.8f lon=%.8f h=%.3f ns=%d", label, firstTime, firstLat, firstLon, firstH, firstNs));
                }
                if (lastTime != null) {
                    l.info(String.format("  %s last:  %s lat=%.8f lon=%.8f h=%.3f ns=%d", label, lastTime, lastLat, lastLon, lastH, lastNs));
                }
            }
        }
    }

    private static List<String> listRtcmFiles(Path dir) {
        try {
            return Files.list(dir)
                    .filter(p -> p.toString().endsWith(".rtcm3"))
                    .sorted()
                    .map(Path::toString)
                    .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static LocalDate extractUtcDateFromProduct(String localPath) {
        String name = Path.of(localPath).getFileName().toString();
        int usIdx = name.indexOf('_');
        if (usIdx >= 0 && name.length() >= usIdx + 8) {
            try {
                int year = Integer.parseInt(name.substring(usIdx + 1, usIdx + 5));
                int doy = Integer.parseInt(name.substring(usIdx + 5, usIdx + 8));
                return LocalDate.ofYearDay(year, doy);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}