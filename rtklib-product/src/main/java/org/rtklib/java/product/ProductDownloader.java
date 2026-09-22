package org.rtklib.java.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * IGS精密产品自动下载器。
 *
 * <p>所有日期/时间均基于UTC：
 * <ul>
 *   <li>输入的LocalDate视为UTC日期（GPS时与UTC差≤18s，对按日产品无影响）</li>
 *   <li>DOY（年积日）按UTC日计算</li>
 *   <li>GPS周从1980-01-06(UTC)起算</li>
 *   <li>IGS产品文件名中的YYYYDDD均为UTC日期</li>
 * </ul>
 *
 * <p>默认缓存目录：{user.dir}/product/（与PRIDE-PPPAR默认路径一致）
 * <p>默认URL基础地址（按PRIDE优先级排列）：
 * <ol>
 *   <li>bdspride.com（PRIDE主源，国内高速）</li>
 *   <li>igs.ign.fr（IGS官方，国际源）</li>
 *   <li>igs.gnsswhu.cn（WHU镜像，国内备选）</li>
 * </ol>
 *
 * <p>缓存目录结构（与PRIDE一致）：
 * <pre>
 *   product/
 *   ├── orbit/     SP3精密轨道
 *   ├── clock/     CLK精密钟差
 *   ├── erp/       ERP地球自转参数
 *   ├── dcb/       DCB/BIA码偏差
 *   └── table/     ANTEX/leapsec等静态表文件
 * </pre>
 */
public class ProductDownloader {

    private static final Logger log = LoggerFactory.getLogger(ProductDownloader.class);

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 120000;
    private static final int MAX_RETRIES = 3;

    public static final String DEFAULT_CACHE_DIR = System.getProperty("user.dir") + File.separator + "product";

    public static final String URL_BDSPRIDE = "ftps://bdspride.com";
    public static final String URL_IGN = "ftp://igs.ign.fr";
    public static final String URL_WHU = "ftp://igs.gnsswhu.cn";

    public enum ProductType {
        SP3, CLK, ERP, DCB, ERP_IGS
    }

    public static class ProductFile {
        public final ProductType type;
        public final String localPath;
        public final boolean fromCache;

        public ProductFile(ProductType type, String localPath, boolean fromCache) {
            this.type = type;
            this.localPath = localPath;
            this.fromCache = fromCache;
        }

        @Override
        public String toString() {
            return type + ": " + localPath + (fromCache ? " (cached)" : " (downloaded)");
        }
    }

    public static class DownloadResult {
        public final List<ProductFile> files = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();

        public boolean isSuccess() {
            return errors.isEmpty();
        }

        @Override
        public String toString() {
            if (files.isEmpty()) return "No products downloaded";
            StringBuilder sb = new StringBuilder();
            for (ProductFile f : files) sb.append(f.toString()).append("\n");
            return sb.toString().trim();
        }
    }

    private final Path cacheDir;
    private final boolean useCache;
    private final boolean offline;
    private final String urlBdspride;
    private final String urlIgn;
    private final String urlWhu;

    public ProductDownloader() {
        this(DEFAULT_CACHE_DIR);
    }

    public ProductDownloader(String cacheDir) {
        this(cacheDir, true, false);
    }

    public ProductDownloader(String cacheDir, boolean useCache, boolean offline) {
        this(cacheDir, useCache, offline, URL_BDSPRIDE, URL_IGN, URL_WHU);
    }

    public ProductDownloader(String cacheDir, boolean useCache, boolean offline,
                             String urlBdspride, String urlIgn, String urlWhu) {
        this.cacheDir = Paths.get(cacheDir).toAbsolutePath();
        this.useCache = useCache;
        this.offline = offline;
        this.urlBdspride = urlBdspride != null ? urlBdspride : URL_BDSPRIDE;
        this.urlIgn = urlIgn != null ? urlIgn : URL_IGN;
        this.urlWhu = urlWhu != null ? urlWhu : URL_WHU;
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            log.warn("Cannot create cache directory: {}", this.cacheDir);
        }
        log.info("ProductDownloader cacheDir={} bdspride={} ign={} whu={}",
                this.cacheDir, this.urlBdspride, this.urlIgn, this.urlWhu);
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public Path getSubDir(ProductType type) {
        String sub = switch (type) {
            case SP3 -> "orbit";
            case CLK -> "clock";
            case ERP, ERP_IGS -> "erp";
            case DCB -> "dcb";
        };
        Path dir = cacheDir.resolve(sub);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Cannot create sub directory: {}", dir);
        }
        return dir;
    }

    public DownloadResult download(LocalDate date, ProductType... types) {
        return download(date, date, types);
    }

    /**
     * 批量下载精密产品。
     *
     * @param startDate 起始日期（UTC）
     * @param endDate   结束日期（UTC，含）
     * @param types     产品类型，为空时下载全部类型
     * @return 下载结果
     */
    public DownloadResult download(LocalDate startDate, LocalDate endDate, ProductType... types) {
        DownloadResult result = new DownloadResult();
        if (types == null || types.length == 0) {
            types = ProductType.values();
        }

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            for (ProductType type : types) {
                try {
                    ProductFile pf = downloadProduct(date, type);
                    if (pf != null) {
                        result.files.add(pf);
                    } else {
                        result.errors.add("Failed to download " + type + " for " + date);
                    }
                } catch (Exception e) {
                    result.errors.add("Error downloading " + type + " for " + date + ": " + e.getMessage());
                    log.error("Error downloading {} for {}: {}", type, date, e.getMessage());
                }
            }
        }
        return result;
    }

    private ProductFile downloadProduct(LocalDate date, ProductType type) {
        List<String> urls = generateUrls(date, type);

        String uncompressedName = generateLocalFileName(date, type);
        Path subDir = getSubDir(type);
        Path localFile = subDir.resolve(uncompressedName);

        if (useCache && Files.exists(localFile)) {
            try {
                if (Files.size(localFile) > 0) {
                    log.info("{} already cached: {}", type, localFile);
                    return new ProductFile(type, localFile.toString(), true);
                }
            } catch (IOException ignored) {
            }
        }

        if (offline) {
            log.warn("Offline mode: cannot download {} for {}", type, date);
            return null;
        }

        for (String url : urls) {
            String httpUrl = ftpToHttp(url);
            log.info("Trying {} for {} from {}", type, date, httpUrl);
            try {
                String downloadedName = downloadHttp(httpUrl, subDir);
                if (downloadedName != null) {
                    Path downloadedPath = subDir.resolve(downloadedName);
                    Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                    log.info("Downloaded {} -> {}", type, finalPath);
                    return new ProductFile(type, finalPath.toString(), false);
                }
            } catch (Exception e) {
                log.warn("Failed to download from {}: {}", httpUrl, e.getMessage());
            }
        }

        return null;
    }

    /**
     * 根据日期和产品类型生成候选URL列表（按优先级排列）。
     *
     * <p>URL优先级参考PRIDE-PPPAR pdp3.sh PrepareProducts()：
     * <ol>
     *   <li>bdspride.com（PRIDE主源，国内高速）</li>
     *   <li>igs.ign.fr（IGS官方MGEX）</li>
     *   <li>igs.gnsswhu.cn（WHU镜像）</li>
     *   <li>RTS实时产品（RAP失败后的回退）</li>
     * </ol>
     *
     * @param date UTC日期
     * @param type 产品类型
     * @return 候选URL列表
     */
    public List<String> generateUrls(LocalDate date, ProductType type) {
        List<String> urls = new ArrayList<>();
        int year = date.getYear();
        int doy = date.getDayOfYear();
        String doyStr = String.format("%03d", doy);

        int gpsWeek = dateToGpsWeek(date);
        String weekStr = String.format("%04d", gpsWeek);

        switch (type) {
            case SP3:
                String wumSp3 = "WUM0MGXRAP_" + year + doyStr + "0000_01D_05M_ORB.SP3.gz";
                urls.add(urlBdspride + "/wum/" + weekStr + "/" + wumSp3);
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + wumSp3);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + wumSp3);
                String igsSp3 = "IGS0MGXRAP_" + year + doyStr + "0000_01D_05M_ORB.SP3.gz";
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + igsSp3);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/IGS2R03FIN_" + year + doyStr + "0000_01D_05M_ORB.SP3.gz");
                String rtsSp3 = "WUM0MGXRTS_" + year + doyStr + "0000_01D_05M_ORB.SP3.gz";
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + rtsSp3);
                break;

            case CLK:
                String wumClk = "WUM0MGXRAP_" + year + doyStr + "0000_01D_30S_CLK.CLK.gz";
                urls.add(urlBdspride + "/wum/" + weekStr + "/" + wumClk);
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + wumClk);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/clock/" + wumClk);
                String igsClk = "IGS0MGXRAP_" + year + doyStr + "0000_01D_30S_CLK.CLK.gz";
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + igsClk);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/clock/IGS2R03FIN_" + year + doyStr + "0000_01D_30S_CLK.CLK.gz");
                String rtsClk = "WUM0MGXRTS_" + year + doyStr + "0000_01D_05S_CLK.CLK.gz";
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/clock/" + rtsClk);
                break;

            case ERP:
                String wumErp = "WUM0MGXRAP_" + year + doyStr + "0000_01D_01D_ERP.ERP.gz";
                urls.add(urlBdspride + "/wum/" + weekStr + "/" + wumErp);
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + wumErp);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/erp/" + wumErp);
                break;

            case DCB:
                String casDcb = "CAS0MGXRAP_" + year + doyStr + "0000_01D_01D_BIA.BIA.gz";
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + casDcb);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/dcb/" + casDcb);
                break;

            case ERP_IGS:
                String igsErp = "igs" + weekStr + ".erp.Z";
                urls.add(urlIgn + "/pub/igs/products/" + weekStr + "/" + igsErp);
                break;
        }

        return urls;
    }

    /**
     * 生成本地缓存文件名（未压缩）。
     *
     * @param date UTC日期
     * @param type 产品类型
     * @return 本地文件名（不含目录）
     */
    public String generateLocalFileName(LocalDate date, ProductType type) {
        int year = date.getYear();
        int doy = date.getDayOfYear();
        String doyStr = String.format("%03d", doy);

        return switch (type) {
            case SP3 -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_05M_ORB.SP3";
            case CLK -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_30S_CLK.CLK";
            case ERP -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_01D_ERP.ERP";
            case DCB -> "CAS0MGXRAP_" + year + doyStr + "0000_01D_01D_BIA.BIA";
            case ERP_IGS -> "igs" + dateToGpsWeek(date) + ".erp";
        };
    }

    /**
     * UTC日期转GPS周号。
     *
     * <p>GPS时与UTC之差为闰秒（当前≈18s），对按日产品无影响。
     * GPS周从1980-01-06(UTC)起算。
     *
     * @param date UTC日期
     * @return GPS周号
     */
    public static int dateToGpsWeek(LocalDate date) {
        LocalDate gpsEpoch = LocalDate.of(1980, 1, 6);
        long days = java.time.temporal.ChronoUnit.DAYS.between(gpsEpoch, date);
        return (int) (days / 7);
    }

    /**
     * FTP/FTPS URL转HTTP/HTTPS。
     *
     * <p>IGS和WHU的FTP服务器均支持HTTPS访问，bdspride.com使用FTPS→HTTPS。
     * 避免JDK FTP客户端的兼容性问题。
     *
     * @param url 原始FTP/FTPS/HTTP/HTTPS URL
     * @return HTTP/HTTPS URL
     */
    public static String ftpToHttp(String url) {
        if (url.startsWith("ftps://")) {
            return "https://" + url.substring(7);
        }
        if (url.startsWith("ftp://")) {
            return "https://" + url.substring(6);
        }
        return url;
    }

    private String downloadHttp(String urlStr, Path targetDir) throws IOException {
        String fileName = urlStr.substring(urlStr.lastIndexOf('/') + 1);
        Path targetPath = targetDir.resolve(fileName);

        if (useCache && Files.exists(targetPath)) {
            try {
                if (Files.size(targetPath) > 0) {
                    log.debug("Cached compressed file: {}", fileName);
                    return fileName;
                }
            } catch (IOException ignored) {
            }
        }

        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestProperty("User-Agent", "rtklib-java/2.1");
                conn.setInstanceFollowRedirects(true);

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    String location = conn.getHeaderField("Location");
                    if (location != null) {
                        conn.disconnect();
                        urlStr = location;
                        continue;
                    }
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    conn.disconnect();
                    if (retry < MAX_RETRIES - 1) {
                        Thread.sleep(1000L * (retry + 1));
                        continue;
                    }
                    throw new IOException("HTTP " + responseCode);
                }

                Path tempFile = targetDir.resolve(fileName + ".tmp");
                long totalBytes = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[16384];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }
                }

                if (totalBytes == 0) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("Empty response");
                }

                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Downloaded {} ({} bytes)", fileName, totalBytes);
                return fileName;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return null;
    }

    /**
     * 按需解压下载文件。
     *
     * @param inputPath         下载的原始文件路径
     * @param targetUncompressed 解压后的目标路径
     * @return 最终可用的文件路径
     */
    public Path decompressIfNeeded(Path inputPath, Path targetUncompressed) throws IOException {
        String name = inputPath.getFileName().toString().toLowerCase();

        if (name.endsWith(".gz")) {
            log.debug("Decompressing GZ: {} -> {}", inputPath, targetUncompressed);
            try (InputStream fis = Files.newInputStream(inputPath);
                 InputStream gis = new GZIPInputStream(fis);
                 OutputStream fos = Files.newOutputStream(targetUncompressed, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                gis.transferTo(fos);
            }
            Files.deleteIfExists(inputPath);
            return targetUncompressed;

        } else if (name.endsWith(".z")) {
            log.info("Unix compress format (.Z) detected: {}, keeping as-is (use external gunzip)", inputPath);
            return inputPath;

        } else {
            if (!inputPath.equals(targetUncompressed)) {
                Files.move(inputPath, targetUncompressed, StandardCopyOption.REPLACE_EXISTING);
            }
            return targetUncompressed;
        }
    }

    /**
     * 下载ANTEX天线相位中心改正文件。
     *
     * @param atxFileName 本地缓存文件名（如"igs14.atx"）
     * @return 本地文件路径，失败返回null
     */
    public String downloadAntex(String atxFileName) {
        Path tableDir = cacheDir.resolve("table");
        try {
            Files.createDirectories(tableDir);
        } catch (IOException e) {
            log.warn("Cannot create table directory: {}", tableDir);
        }
        Path localFile = tableDir.resolve(atxFileName);

        if (useCache && Files.exists(localFile)) {
            try {
                if (Files.size(localFile) > 0) {
                    log.info("ANTEX already cached: {}", localFile);
                    return localFile.toString();
                }
            } catch (IOException ignored) {
            }
        }

        if (offline) return null;

        String[] atxUrls = {
            urlBdspride + "/table/" + atxFileName,
            "https://files.igs.org/pub/station/general/" + atxFileName,
            urlIgn + "/pub/igs/station/general/" + atxFileName,
            urlWhu + "/pub/whu/phasebias/table/" + atxFileName
        };

        for (String url : atxUrls) {
            try {
                String downloaded = downloadHttp(ftpToHttp(url), tableDir);
                if (downloaded != null) {
                    Path downloadedPath = tableDir.resolve(downloaded);
                    Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                    log.info("Downloaded ANTEX -> {}", finalPath);
                    return finalPath.toString();
                }
            } catch (Exception e) {
                log.warn("Failed to download ANTEX from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 下载闰秒表文件。
     *
     * @return 本地文件路径，失败返回null
     */
    public String downloadLeapSecond() {
        Path tableDir = cacheDir.resolve("table");
        try {
            Files.createDirectories(tableDir);
        } catch (IOException e) {
            log.warn("Cannot create table directory: {}", tableDir);
        }
        String leapsecFile = "leapsecond.txt";
        Path localFile = tableDir.resolve(leapsecFile);

        if (useCache && Files.exists(localFile)) {
            try {
                if (Files.size(localFile) > 0) {
                    log.info("Leap second already cached: {}", localFile);
                    return localFile.toString();
                }
            } catch (IOException ignored) {
            }
        }

        if (offline) return null;

        String[] urls = {
            urlBdspride + "/table/" + leapsecFile,
            urlWhu + "/pub/whu/phasebias/table/" + leapsecFile
        };

        for (String url : urls) {
            try {
                String downloaded = downloadHttp(ftpToHttp(url), tableDir);
                if (downloaded != null) {
                    Path downloadedPath = tableDir.resolve(downloaded);
                    Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                    log.info("Downloaded leap second -> {}", finalPath);
                    return finalPath.toString();
                }
            } catch (Exception e) {
                log.warn("Failed to download leap second from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: ProductDownloader [cache-dir] <yyyy-mm-dd> [yyyy-mm-dd] [SP3|CLK|ERP|DCB|ERP_IGS]...");
            System.out.println("  cache-dir defaults to: " + DEFAULT_CACHE_DIR);
            System.out.println("  Example: ProductDownloader ./product 2024-01-15 2024-01-15 SP3 CLK ERP");
            System.out.println("  Example: ProductDownloader 2024-01-15 SP3 CLK");
            return;
        }

        int argIdx = 0;
        String cacheDir;
        if (args[0].contains("-") && args[0].length() == 10) {
            cacheDir = DEFAULT_CACHE_DIR;
        } else {
            cacheDir = args[0];
            argIdx = 1;
        }

        if (argIdx >= args.length) {
            System.err.println("Error: date argument required");
            return;
        }

        LocalDate startDate = LocalDate.parse(args[argIdx++], DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate endDate = argIdx < args.length && !args[argIdx].startsWith("SP3") && !args[argIdx].startsWith("CLK") && !args[argIdx].startsWith("ERP") && !args[argIdx].startsWith("DCB")
                ? LocalDate.parse(args[argIdx++], DateTimeFormatter.ISO_LOCAL_DATE)
                : startDate;

        List<ProductType> types = new ArrayList<>();
        while (argIdx < args.length) {
            try {
                types.add(ProductType.valueOf(args[argIdx]));
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown product type: " + args[argIdx]);
            }
            argIdx++;
        }
        if (types.isEmpty()) {
            types.addAll(Arrays.asList(ProductType.SP3, ProductType.CLK, ProductType.ERP));
        }

        ProductDownloader dl = new ProductDownloader(cacheDir);
        DownloadResult result = dl.download(startDate, endDate, types.toArray(new ProductType[0]));

        System.out.println("=== Download Result ===");
        for (ProductFile f : result.files) {
            System.out.println(f);
        }
        for (String err : result.errors) {
            System.err.println("ERROR: " + err);
        }
        System.out.printf("Total: %d files, %d errors%n", result.files.size(), result.errors.size());
    }
}