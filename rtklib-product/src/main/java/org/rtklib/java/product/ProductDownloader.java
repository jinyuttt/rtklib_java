package org.rtklib.java.product;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * IGS精密产品自动下载器，下载规则完全对齐PRIDE-PPPAR v3.2 pdp3.sh。
 *
 * <p>URL优先级（wcc_use=0，默认WUM产品）：
 * <ol>
 *   <li>ftps://bdspride.com/wum/{GPSW}/  （PRIDE主源，FTPS匿名）</li>
 *   <li>ftp://igs.ign.fr/pub/igs/products/mgex/{GPSW}/  （IGS官方）</li>
 *   <li>ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/{sub}/  （WHU镜像）</li>
 *   <li>ftps://bdspride.com/wcc/{GPSW}/  （WCC产品，回退）</li>
 *   <li>ftp://igs.gnsswhu.cn/.../IGS2R03FIN_...  （IGS2最终产品，回退）</li>
 *   <li>ftp://igs.gnsswhu.cn/.../WUM0MGXRTS_...  （RTS实时产品，近3天回退）</li>
 * </ol>
 *
 * <p>所有日期/时间均基于UTC：
 * <ul>
 *   <li>输入的LocalDate视为UTC日期</li>
 *   <li>DOY（年积日）按UTC日计算</li>
 *   <li>GPS周从1980-01-06(UTC)起算</li>
 *   <li>IGS产品文件名中的YYYYDDD均为UTC日期</li>
 * </ul>
 *
 * <p>缓存目录结构（每类数据一个子文件夹，v2.2.2+）：
 * <pre>
 *   product/
 *   ├── sp3/        精密星历（.SP3）
 *   ├── clk/        精密钟差（.CLK）
 *   ├── erp/        地球自转参数（.ERP）
 *   ├── bia/        观测偏差（.BIA）
 *   ├── obx/        姿态参数（.OBX）
 *   ├── fcb/        宽窄巷FCB（.FCB，WHU/CNES）
 *   ├── upd/        宽窄巷UPD（.UPD，WHU）
 *   ├── osb/        观测特定偏差（.BIA/.OSB，CAS）
 *   ├── dcb/        差分码偏差（.DCB/.BSX，CODE/CAS）
 *   ├── vmf3/       VMF3映射函数系数（.OP，6h间隔）
 *   ├── gpt3/       GPT3网格数据（gpt3_5deg.dat，~5MB）
 *   └── table/      静态表文件（ANTEX + leap.sec + sat_parameters）
 * </pre>
 *
 * <p>各目录文件命名规则：
 * <pre>
 *   sp3/     WUM0MGXRAP_YYYYDDD0000_01D_05M_ORB.SP3
 *   clk/     WUM0MGXRAP_YYYYDDD0000_01D_30S_CLK.CLK
 *   erp/     WUM0MGXRAP_YYYYDDD0000_01D_01D_ERP.ERP
 *   bia/     WUM0MGXRAP_YYYYDDD0000_01D_01D_OSB.BIA
 *   obx/     WUM0MGXRAP_YYYYDDD0000_01D_30S_ATT.OBX
 *   fcb/     WUM0MGXRTS_WWWW0_01D_05M_FCB.FCB
 *   upd/     YYYYDDD0.UPD
 *   osb/     CAS0MGXRTS_WWWW0_01D_01D_OSB.BIA
 *   dcb/     CAS0MGXRTS_YYYYDDD0_01D_01D_DCB.BSX
 *   vmf3/    VMF3_YYYYMMDD.HHH
 *   gpt3/    gpt3_5deg.dat
 *   table/   igs20_2317.atx / leap.sec / sat_parameters.txt
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
    public static final String URL_VMF = "http://vmf.geo.tuwien.ac.at";

    public enum ProductType {
        SP3, CLK, ERP, BIA, OBX, FCB, UPD, OSB, DCB, VMF3, GPT3
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
    private final boolean useRts;
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
        this(cacheDir, useCache, offline, true, URL_BDSPRIDE, URL_IGN, URL_WHU);
    }

    public ProductDownloader(String cacheDir, boolean useCache, boolean offline,
                             boolean useRts, String urlBdspride, String urlIgn, String urlWhu) {
        this.cacheDir = Paths.get(cacheDir).toAbsolutePath();
        this.useCache = useCache;
        this.offline = offline;
        this.useRts = useRts;
        this.urlBdspride = urlBdspride != null ? urlBdspride : URL_BDSPRIDE;
        this.urlIgn = urlIgn != null ? urlIgn : URL_IGN;
        this.urlWhu = urlWhu != null ? urlWhu : URL_WHU;
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException e) {
            log.warn("Cannot create cache directory: {}", this.cacheDir);
        }
        log.info("ProductDownloader cacheDir={} bdspride={} ign={} whu={} useRts={}",
                this.cacheDir, this.urlBdspride, this.urlIgn, this.urlWhu, this.useRts);
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public Path getCommonDir() {
        Path dir = cacheDir.resolve("common");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Cannot create common directory: {}", dir);
        }
        return dir;
    }

    public Path getProductDir(ProductType type) {
        String subDir = switch (type) {
            case SP3  -> "sp3";
            case CLK  -> "clk";
            case ERP  -> "erp";
            case BIA  -> "bia";
            case OBX  -> "obx";
            case FCB  -> "fcb";
            case UPD  -> "upd";
            case OSB  -> "osb";
            case DCB  -> "dcb";
            case VMF3 -> "vmf3";
            case GPT3 -> "gpt3";
        };
        Path dir = cacheDir.resolve(subDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Cannot create {} directory: {}", subDir, dir);
        }
        return dir;
    }

    public DownloadResult download(LocalDate date, ProductType... types) {
        return download(date, date, types);
    }

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
        Path productDir = getProductDir(type);
        Path localFile = productDir.resolve(uncompressedName);

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
            log.info("Trying {} for {} from {}", type, date, url);
            try {
                String downloadedName = downloadFromUrl(url, productDir);
                if (downloadedName != null) {
                    Path downloadedPath = productDir.resolve(downloadedName);
                    Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                    log.info("Downloaded {} -> {}", type, finalPath);
                    return new ProductFile(type, finalPath.toString(), false);
                }
            } catch (Exception e) {
                log.warn("Failed to download from {}: {}", url, e.getMessage());
            }
        }

        return null;
    }

    /**
     * 根据日期和产品类型生成候选URL列表，完全对齐PRIDE-PPPAR pdp3.sh PrepareProducts()。
     *
     * <p>URL优先级（wcc_use=0）：
     * <ol>
     *   <li>bdspride.com/wum/{GPSW}/WUM0MGXRAP_... （首选）</li>
     *   <li>igs.ign.fr/pub/igs/products/mgex/{GPSW}/WUM0MGXRAP_...</li>
     *   <li>igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/{sub}/WUM0MGXRAP_...</li>
     *   <li>bdspride.com/wcc/{GPSW}/WCC0OPSRAP_... （WCC回退）</li>
     *   <li>igs.gnsswhu.cn/.../IGS2R03FIN_... / COD0R03FIN_... （最终产品回退）</li>
     *   <li>igs.gnsswhu.cn/.../WUM0MGXRTS_... （RTS实时产品，近3天回退）</li>
     * </ol>
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
                String wccSp3 = "WCC0OPSRAP_" + year + doyStr + "0000_01D_05M_ORB.SP3.gz";
                urls.add(urlBdspride + "/wcc/" + weekStr + "/" + wccSp3);
                String igs2Sp3 = "IGS2R03FIN_" + year + doyStr + "0000_01D_05M_ORB.SP3.gz";
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + igs2Sp3);
                if (useRts) {
                    String rtsSp3 = "WUM0MGXRTS_" + year + doyStr + "0000_01D_05M_ORB.SP3.gz";
                    urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + rtsSp3);
                }
                break;

            case CLK:
                String wumClk = "WUM0MGXRAP_" + year + doyStr + "0000_01D_30S_CLK.CLK.gz";
                urls.add(urlBdspride + "/wum/" + weekStr + "/" + wumClk);
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + wumClk);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/clock/" + wumClk);
                String wccClk = "WCC0OPSRAP_" + year + doyStr + "0000_01D_30S_CLK.CLK.gz";
                urls.add(urlBdspride + "/wcc/" + weekStr + "/" + wccClk);
                String igs2Clk = "IGS2R03FIN_" + year + doyStr + "0000_01D_30S_CLK.CLK.gz";
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/clock/" + igs2Clk);
                if (useRts) {
                    String rtsClk = "WUM0MGXRTS_" + year + doyStr + "0000_01D_05S_CLK.CLK.gz";
                    urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/clock/" + rtsClk);
                }
                break;

            case ERP:
                String wumErp = "WUM0MGXRAP_" + year + doyStr + "0000_01D_01D_ERP.ERP.gz";
                urls.add(urlBdspride + "/wum/" + weekStr + "/" + wumErp);
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + wumErp);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + wumErp);
                String wccErp = "WCC0OPSRAP_" + year + doyStr + "0000_01D_01D_ERP.ERP.gz";
                urls.add(urlBdspride + "/wcc/" + weekStr + "/" + wccErp);
                String codErp = "COD0R03FIN_" + year + doyStr + "0000_01D_01D_ERP.ERP.gz";
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + codErp);
                if (useRts) {
                    String rtsErp = "WUM0MGXRTS_" + year + doyStr + "0000_01D_01D_ERP.ERP.gz";
                    urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + rtsErp);
                }
                break;

            case BIA:
                String wumBia = "WUM0MGXRAP_" + year + doyStr + "0000_01D_01D_OSB.BIA.gz";
                urls.add(urlBdspride + "/wum/" + weekStr + "/" + wumBia);
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + wumBia);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/bias/" + wumBia);
                String wccBia = "WCC0OPSRAP_" + year + doyStr + "0000_01D_01D_OSB.BIA.gz";
                urls.add(urlBdspride + "/wcc/" + weekStr + "/" + wccBia);
                String igs2Bia = "IGS2R03FIN_" + year + doyStr + "0000_01D_01D_OSB.BIA.gz";
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/bias/" + igs2Bia);
                if (useRts) {
                    String rtsBia = "WUM0MGXRTS_" + year + doyStr + "0000_01D_05M_OSB.BIA.gz";
                    urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/bias/" + rtsBia);
                }
                break;

            case OBX:
                String wumObx = "WUM0MGXRAP_" + year + doyStr + "0000_01D_30S_ATT.OBX.gz";
                urls.add(urlBdspride + "/wum/" + weekStr + "/" + wumObx);
                urls.add(urlIgn + "/pub/igs/products/mgex/" + weekStr + "/" + wumObx);
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + wumObx);
                String wccObx = "WCC0OPSRAP_" + year + doyStr + "0000_01D_30S_ATT.OBX.gz";
                urls.add(urlBdspride + "/wcc/" + weekStr + "/" + wccObx);
                String igs2Obx = "IGS2R03FIN_" + year + doyStr + "0000_01D_30S_ATT.OBX.gz";
                urls.add(urlWhu + "/pub/whu/phasebias/" + year + "/orbit/" + igs2Obx);
                break;
        }

        return urls;
    }

    public String generateLocalFileName(LocalDate date, ProductType type) {
        int year = date.getYear();
        int doy = date.getDayOfYear();
        String doyStr = String.format("%03d", doy);

        return switch (type) {
            case SP3 -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_05M_ORB.SP3";
            case CLK -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_30S_CLK.CLK";
            case ERP -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_01D_ERP.ERP";
            case BIA -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_01D_OSB.BIA";
            case OBX -> "WUM0MGXRAP_" + year + doyStr + "0000_01D_30S_ATT.OBX";
            case FCB -> "WUM0MGXRTS_" + year + doyStr + "0000_01D_05M_FCB.FCB";
            case UPD -> year + doyStr + "0.UPD";
            case OSB -> "CAS0MGXRTS_" + year + doyStr + "0000_01D_01D_OSB.BIA";
            case DCB -> "CAS0MGXRTS_" + year + doyStr + "0000_01D_01D_DCB.BSX";
            case VMF3 -> "VMF3_" + year + doyStr + ".OP";
            case GPT3 -> "gpt3_5deg.dat";
        };
    }

    public static int dateToGpsWeek(LocalDate date) {
        LocalDate gpsEpoch = LocalDate.of(1980, 1, 6);
        long days = java.time.temporal.ChronoUnit.DAYS.between(gpsEpoch, date);
        return (int) (days / 7);
    }

    private String downloadFromUrl(String url, Path targetDir) throws IOException {
        if (url.startsWith("ftps://")) {
            return downloadFtps(url, targetDir);
        } else if (url.startsWith("ftp://")) {
            return downloadFtp(url, targetDir);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            return downloadHttp(url, targetDir);
        } else {
            throw new IOException("Unsupported protocol: " + url);
        }
    }

    private String downloadFtps(String urlStr, Path targetDir) throws IOException {
        String fileName = urlStr.substring(urlStr.lastIndexOf('/') + 1);
        String path = urlStr.substring(urlStr.indexOf('/', 8));
        String host = urlStr.substring(8, urlStr.indexOf('/', 8));

        Path targetPath = targetDir.resolve(fileName);
        if (useCache && Files.exists(targetPath)) {
            try {
                if (Files.size(targetPath) > 0) {
                    log.debug("FTPS cached: {}", fileName);
                    return fileName;
                }
            } catch (IOException ignored) {
            }
        }

        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};

        SSLContext sc;
        try {
            sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, null);
        } catch (Exception e) {
            throw new IOException("Failed to init SSL context: " + e.getMessage(), e);
        }

        FTPSClient ftps = new FTPSClient(true, sc);
        try {
            ftps.setConnectTimeout(CONNECT_TIMEOUT);
            ftps.setDefaultTimeout(READ_TIMEOUT);
            ftps.setDataTimeout(READ_TIMEOUT);

            ftps.connect(host, 21);
            if (!ftps.login("anonymous", "")) {
                throw new IOException("FTPS login failed");
            }
            ftps.execPBSZ(0);
            ftps.execPROT("P");
            ftps.enterLocalPassiveMode();
            ftps.setFileType(FTP.BINARY_FILE_TYPE);

            Path tempFile = targetDir.resolve(fileName + ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                boolean retrieved = ftps.retrieveFile(path, out);
                if (!retrieved) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("FTPS retrieve failed for " + path);
                }
            }

            long size = Files.size(tempFile);
            if (size == 0) {
                Files.deleteIfExists(tempFile);
                throw new IOException("FTPS empty response");
            }

            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("FTPS downloaded {} ({} bytes)", fileName, size);
            return fileName;

        } finally {
            try { ftps.disconnect(); } catch (Exception ignored) {}
        }
    }

    private String downloadFtp(String urlStr, Path targetDir) throws IOException {
        String fileName = urlStr.substring(urlStr.lastIndexOf('/') + 1);
        String path = urlStr.substring(urlStr.indexOf('/', 6));
        String host = urlStr.substring(6, urlStr.indexOf('/', 6));

        Path targetPath = targetDir.resolve(fileName);
        if (useCache && Files.exists(targetPath)) {
            try {
                if (Files.size(targetPath) > 0) {
                    log.debug("FTP cached: {}", fileName);
                    return fileName;
                }
            } catch (IOException ignored) {
            }
        }

        FTPClient ftp = new FTPClient();
        try {
            ftp.setConnectTimeout(CONNECT_TIMEOUT);
            ftp.setDefaultTimeout(READ_TIMEOUT);
            ftp.setDataTimeout(READ_TIMEOUT);

            ftp.connect(host, 21);
            if (!ftp.login("anonymous", "")) {
                throw new IOException("FTP login failed");
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            Path tempFile = targetDir.resolve(fileName + ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                boolean retrieved = ftp.retrieveFile(path, out);
                if (!retrieved) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("FTP retrieve failed for " + path);
                }
            }

            long size = Files.size(tempFile);
            if (size == 0) {
                Files.deleteIfExists(tempFile);
                throw new IOException("FTP empty response");
            }

            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("FTP downloaded {} ({} bytes)", fileName, size);
            return fileName;

        } finally {
            try { ftp.disconnect(); } catch (Exception ignored) {}
        }
    }

    private String downloadHttp(String urlStr, Path targetDir) throws IOException {
        String fileName = urlStr.substring(urlStr.lastIndexOf('/') + 1);
        Path targetPath = targetDir.resolve(fileName);

        if (useCache && Files.exists(targetPath)) {
            try {
                if (Files.size(targetPath) > 0) {
                    log.debug("HTTP cached: {}", fileName);
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
                log.debug("HTTP downloaded {} ({} bytes)", fileName, totalBytes);
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
            log.info("Unix compress format (.Z) detected: {}, keeping as-is", inputPath);
            return inputPath;

        } else {
            if (!inputPath.equals(targetUncompressed)) {
                Files.move(inputPath, targetUncompressed, StandardCopyOption.REPLACE_EXISTING);
            }
            return targetUncompressed;
        }
    }

    /**
     * 下载VMF3对流层网格文件（Vienna TU）。
     *
     * <p>对齐PRIDE-PPPAR pdp3.sh：下载前一天H18、当天H00/H06/H12/H18、次日H00。
     *
     * @param date UTC日期
     * @return 合并后的VMF3网格文件路径，失败返回null
     */
    public String downloadVmf3Grid(LocalDate date) {
        Path vmfDir = cacheDir.resolve("vmf");
        try {
            Files.createDirectories(vmfDir);
        } catch (IOException e) {
            log.warn("Cannot create vmf directory: {}", vmfDir);
            return null;
        }

        List<String> vmfFiles = new ArrayList<>();
        List<LocalDate> days = Arrays.asList(date.minusDays(1), date, date.plusDays(1));

        for (LocalDate d : days) {
            int y = d.getYear();
            String mm = String.format("%02d", d.getMonthValue());
            String dd = String.format("%02d", d.getDayOfMonth());

            if (d.equals(date.minusDays(1))) {
                vmfFiles.add(downloadSingleVmf3(d, "18", vmfDir));
            } else if (d.equals(date.plusDays(1))) {
                vmfFiles.add(downloadSingleVmf3(d, "00", vmfDir));
            } else {
                for (String hour : new String[]{"00", "06", "12", "18"}) {
                    vmfFiles.add(downloadSingleVmf3(d, hour, vmfDir));
                }
            }
        }

        vmfFiles.removeIf(Objects::isNull);
        if (vmfFiles.isEmpty()) {
            log.warn("No VMF3 grid files downloaded for {}", date);
            return null;
        }

        String mergedName = String.format("vmf_%d%03d", date.getYear(), date.getDayOfYear());
        Path mergedPath = vmfDir.resolve(mergedName);
        try (OutputStream out = Files.newOutputStream(mergedPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (String f : vmfFiles) {
                Path p = vmfDir.resolve(f);
                if (Files.exists(p)) {
                    Files.copy(p, out);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to merge VMF3 files: {}", e.getMessage());
            return null;
        }

        log.info("Merged VMF3 grid -> {}", mergedPath);
        return mergedPath.toString();
    }

    private String downloadSingleVmf3(LocalDate date, String hour, Path vmfDir) {
        int y = date.getYear();
        String mm = String.format("%02d", date.getMonthValue());
        String dd = String.format("%02d", date.getDayOfMonth());
        String vmfName = String.format("VMF3_%d%s%s.H%s", y, mm, dd, hour);
        String url = URL_VMF + "/trop_products/GRID/1x1/VMF3/VMF3_OP/" + y + "/" + vmfName;

        Path localFile = vmfDir.resolve(vmfName);
        if (useCache && Files.exists(localFile)) {
            try {
                if (Files.size(localFile) > 0) {
                    return vmfName;
                }
            } catch (IOException ignored) {
            }
        }

        if (offline) return null;

        try {
            String downloaded = downloadHttp(url, vmfDir);
            if (downloaded != null) {
                return downloaded;
            }
        } catch (Exception e) {
            log.warn("Failed to download VMF3 {}: {}", vmfName, e.getMessage());
        }
        return null;
    }

    /**
     * 下载静态表文件（leap.sec、sat_parameters等）。
     *
     * <p>对齐PRIDE-PPPAR pdp3.sh：首选bdspride.com/table/，回退igs.gnsswhu.cn。
     *
     * @param tableName 表文件名（如"leap.sec"、"sat_parameters"）
     * @return 本地文件路径，失败返回null
     */
    public String downloadTable(String tableName) {
        Path tableDir = cacheDir.resolve("table");
        try {
            Files.createDirectories(tableDir);
        } catch (IOException e) {
            log.warn("Cannot create table directory: {}", tableDir);
        }
        Path localFile = tableDir.resolve(tableName);

        if (useCache && Files.exists(localFile)) {
            try {
                if (Files.size(localFile) > 0) {
                    log.info("Table {} already cached: {}", tableName, localFile);
                    return localFile.toString();
                }
            } catch (IOException ignored) {
            }
        }

        if (offline) return null;

        String[] urls = {
            urlBdspride + "/table/" + tableName,
            urlWhu + "/pub/whu/phasebias/table/" + tableName
        };

        for (String url : urls) {
            log.info("Trying table {} from {}", tableName, url);
            try {
                String downloaded = downloadFromUrl(url, tableDir);
                if (downloaded != null) {
                    Path downloadedPath = tableDir.resolve(downloaded);
                    if (!downloadedPath.equals(localFile)) {
                        Files.move(downloadedPath, localFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    log.info("Downloaded table {} -> {}", tableName, localFile);
                    return localFile.toString();
                }
            } catch (Exception e) {
                log.warn("Failed to download table {} from {}: {}", tableName, url, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 下载FCB（Fractional Cycle Bias）产品，用于PPP-AR宽巷/窄巷模糊度固定。
     * 存储目录：product/fcb/
     * 来源：PRIDE-WUM(WHU) / WHU / IGS-MGEX
     */
    public String downloadFcb(LocalDate date) {
        Path fcbDir = cacheDir.resolve("fcb");
        try { Files.createDirectories(fcbDir); } catch (IOException e) { return null; }

        int gpsw = dateToGpsWeek(date);
        int doy = date.getDayOfYear();
        int y = date.getYear();
        String wwww = String.format("%04d", gpsw);

        String[] fcbNames = {
            String.format("WUM0MGXRTS_%04d%03d0000_01D_05M_OSB.BIA", y, doy),
            String.format("WUM0MGXRAP_%04d%03d0000_01D_01D_OSB.BIA", y, doy),
            String.format("CAS0MGXRTS_%04d%03d0000_01D_05M_OSB.BIA", y, doy),
            String.format("WUM0MGXRTS_%04d%03d00000_01D_05M_OSB.BIA", y, doy),
            String.format("WUM0MGXRAP_%04d%03d00000_01D_01D_OSB.BIA", y, doy),
            String.format("WUM0MGXRTS_%04d%03d000_01D_05M_OSB.BIA", y, doy),
            String.format("WUM0MGXRAP_%04d%03d000_01D_01D_OSB.BIA", y, doy),
            String.format("WUM0MGXRTS_%s0_01D_05M_FCB.FCB", wwww),
            String.format("CAS0MGXRTS_%s0_01D_05M_FCB.FCB", wwww),
            String.format("%d%03d0.FCB", y, doy)
        };

        for (String name : fcbNames) {
            Path localFile = fcbDir.resolve(name);
            if (useCache && Files.exists(localFile)) {
                try { if (Files.size(localFile) > 0) { log.info("FCB cached: {}", localFile); return localFile.toString(); } } catch (IOException ignored) {}
            }
            if (offline) continue;

            String[] urls = {
                urlBdspride + "/wum/" + wwww + "/" + name,
                urlWhu + "/pub/whu/phasebias/" + y + "/bias/" + name,
                urlIgn + "/pub/igs/products/mgex/" + wwww + "/" + name
            };

            for (String url : urls) {
                log.info("Trying FCB {} from {}", name, url);
                try {
                    String downloaded = downloadFromUrl(url, fcbDir);
                    if (downloaded != null) {
                        Path downloadedPath = fcbDir.resolve(downloaded);
                        Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                        log.info("Downloaded FCB -> {}", finalPath);
                        return finalPath.toString();
                    }
                } catch (Exception e) {
                    log.warn("FCB download failed from {}: {}", url, e.getMessage());
                }
            }
        }
        log.warn("No FCB product found for {}", date);
        return null;
    }

    /**
     * 下载UPD（Uncalibrated Phase Delay）产品，用于PPP-AR窄巷模糊度固定。
     * 存储目录：product/upd/
     * 来源：PRIDE-WUM / WHU
     */
    public String downloadUpd(LocalDate date) {
        Path updDir = cacheDir.resolve("upd");
        try { Files.createDirectories(updDir); } catch (IOException e) { return null; }

        int gpsw = dateToGpsWeek(date);
        int doy = date.getDayOfYear();
        int y = date.getYear();
        String wwww = String.format("%04d", gpsw);

        // UPD候选文件名：简化命名 / WHU(WUM)
        String[] updNames = {
            String.format("%d%03d0.UPD", y, doy),
            String.format("WUM0MGXFIN_%s0_01D_15M_UPD.UPD", wwww)
        };

        for (String name : updNames) {
            Path localFile = updDir.resolve(name);
            if (useCache && Files.exists(localFile)) {
                try { if (Files.size(localFile) > 0) { log.info("UPD cached: {}", localFile); return localFile.toString(); } } catch (IOException ignored) {}
            }
            if (offline) continue;

            // UPD候选URL：PRIDE-WUM / WHU
            String[] urls = {
                urlBdspride + "/wum/" + wwww + "/" + name,
                urlWhu + "/pub/whu/phasebias/" + y + "/" + (doy / 50 + 1) + "/" + name
            };

            for (String url : urls) {
                log.info("Trying UPD {} from {}", name, url);
                try {
                    String downloaded = downloadFromUrl(url, updDir);
                    if (downloaded != null) {
                        Path downloadedPath = updDir.resolve(downloaded);
                        Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                        log.info("Downloaded UPD -> {}", finalPath);
                        return finalPath.toString();
                    }
                } catch (Exception e) {
                    log.warn("UPD download failed from {}: {}", url, e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 下载OSB（Observable-Specific Bias）产品，用于v2.3.0 OSB偏差模型。
     * 存储目录：product/osb/
     * 来源：IGS-MGEX(CAS) / WHU
     */
    public String downloadOsb(LocalDate date) {
        Path osbDir = cacheDir.resolve("osb");
        try { Files.createDirectories(osbDir); } catch (IOException e) { return null; }

        int gpsw = dateToGpsWeek(date);
        int doy = date.getDayOfYear();
        int y = date.getYear();
        String wwww = String.format("%04d", gpsw);

        // OSB候选文件名：CAS(MGEX) / 简化命名
        String[] osbNames = {
            String.format("CAS0MGXRTS_%s0_01D_01D_OSB.BIA", wwww),
            String.format("%d%03d0.BIA", y, doy)
        };

        for (String name : osbNames) {
            Path localFile = osbDir.resolve(name);
            if (useCache && Files.exists(localFile)) {
                try { if (Files.size(localFile) > 0) { log.info("OSB cached: {}", localFile); return localFile.toString(); } } catch (IOException ignored) {}
            }
            if (offline) continue;

            // OSB候选URL：IGS-MGEX / WHU
            String[] urls = {
                urlIgn + "/pub/igs/products/mgex/" + wwww + "/" + name,
                urlWhu + "/pub/whu/phasebias/" + y + "/"+ (doy / 50 + 16) + "/" + name
            };

            for (String url : urls) {
                log.info("Trying OSB {} from {}", name, url);
                try {
                    String downloaded = downloadFromUrl(url, osbDir);
                    if (downloaded != null) {
                        Path downloadedPath = osbDir.resolve(downloaded);
                        Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                        log.info("Downloaded OSB -> {}", finalPath);
                        return finalPath.toString();
                    }
                } catch (Exception e) {
                    log.warn("OSB download failed from {}: {}", url, e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 下载DCB（Differential Code Bias）产品，用于伪距偏差改正。
     * 存储目录：product/dcb/
     * 来源：CODE(AIUB) / IGS-MGEX(CAS)
     * 格式：.BSX(BIAS-SINEX) / .DCB(传统格式)
     */
    public String downloadDcb(LocalDate date) {
        Path dcbDir = cacheDir.resolve("dcb");
        try { Files.createDirectories(dcbDir); } catch (IOException e) { return null; }

        int y = date.getYear();
        int doy = date.getDayOfYear();

        String localName = String.format("CAS0MGXRTS_%04d%03d0_01D_01D_DCB.BSX", y, doy);
        Path localFile = dcbDir.resolve(localName);
        if (useCache && Files.exists(localFile)) {
            try { if (Files.size(localFile) > 0) { log.info("DCB cached:D {}", localFile); return localFile.toString(); } } catch (IOException ignored) {}
        }

        if (offline) return null;

        String[] remoteNames = {
            String.format("CAS0MGXRAP_%04d%03d0000_01D_01D_DCB.BSX", y, doy),
            String.format("GFZ0OPSRAP_%04d%03d0000_01D_01D_DCB.BIA", y, doy),
            String.format("CAS0MGXRTS_%04d%03d0000_01D_01D_DCB.BSX", y, doy),
            String.format("CAS0MGXRAP_%04d%03d0000_01D_01D_DCB.BIA", y, doy),
            String.format("CODE_%d%03d0.DCB", y, doy)
        };

        for (String remoteName : remoteNames) {
            String[] urls = {
                urlIgn + "/pub/igs/products/bias/" + y + "/" + remoteName + ".gz",
                urlIgn + "/pub/igs/products/mgex/" + dateToGpsWeek(date) + "/" + remoteName + ".gz",
                urlWhu + "/pub/whu/phasebias/" + y + "/bias/" + remoteName + ".gz",
                "ftp://ftp.aiub.unibe.ch/CODE/" + y + "/" + remoteName + ".gz",
                urlIgn + "/pub/igs/products/bias/" + y + "/" + remoteName,
                urlIgn + "/pub/igs/products/mgex/" + dateToGpsWeek(date) + "/" + remoteName,
                urlWhu + "/pub/whu/phasebias/" + y + "/bias/" + remoteName
            };
            for (String url : urls) {
                log.info("Trying DCB {} from {}", remoteName, url);
                try {
                    String downloaded = downloadFromUrl(url, dcbDir);
                    if (downloaded != null) {
                        Path downloadedPath = dcbDir.resolve(downloaded);
                        Path finalPath = decompressIfNeeded(downloadedPath, localFile);
                        log.info("Downloaded DCB -> {}", finalPath);
                        return finalPath.toString();
                    }
                } catch (Exception e) {
                    log.warn("DCB download failed from {}: {}", url, e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 下载GPT3 5°×5°网格数据（~5MB），用于GPT3+VMF3对流层模型。
     * 存储目录：product/gpt3/
     * 来源：TU Wien VMF服务器 / PRIDE / WHU
     * 文件为静态表，只需下载一次。
     */
    public String downloadGpt3Grid() {
        Path gpt3Dir = cacheDir.resolve("gpt3");
        try { Files.createDirectories(gpt3Dir); } catch (IOException e) { return null; }

        String gpt3Name = "gpt3_5deg.dat";
        Path localFile = gpt3Dir.resolve(gpt3Name);

        if (useCache && Files.exists(localFile)) {
            try { if (Files.size(localFile) > 0) { log.info("GPT3 grid cached: {}", localFile); return localFile.toString(); } } catch (IOException ignored) {}
        }
        if (offline) return null;

        // GPT3候选URL：TU Wien(HTTPS) / PRIDE(FTP) / WHU(FTP)
        String[] urls = {
            "https://vmf.geo.tuwien.ac.at/trop_products/GPT3/gpt3_5deg.dat",
            urlBdspride + "/table/" + gpt3Name,
            urlWhu + "/pub/whu/phasebias/table/" + gpt3Name
        };

        for (String url : urls) {
            log.info("Trying GPT3 grid from {}", url);
            try {
                String downloaded;
                if (url.startsWith("http")) {
                    downloaded = downloadHttp(url, gpt3Dir);
                } else {
                    downloaded = downloadFromUrl(url, gpt3Dir);
                }
                if (downloaded != null) {
                    Path downloadedPath = gpt3Dir.resolve(downloaded);
                    log.info("Downloaded GPT3 grid -> {}", downloadedPath);
                    return downloadedPath.toString();
                }
            } catch (Exception e) {
                log.warn("GPT3 grid download failed from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

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
            "https://files.igs.org/pub/station/general/" + atxFileName,
            "https://files.igs.org/pub/station/general/pcv_archive/" + atxFileName,
            "https://files.igs.org/pub/station/general/pcv_archive/" + atxFileName + ".gz",
            urlBdspride + "/table/" + atxFileName,
            urlWhu + "/pub/whu/phasebias/table/" + atxFileName
        };

        for (String url : atxUrls) {
            log.info("Trying ANTEX {} from {}", atxFileName, url);
            try {
                String downloaded;
                if (url.startsWith("http")) {
                    downloaded = downloadHttp(url, tableDir);
                } else {
                    downloaded = downloadFromUrl(url, tableDir);
                }
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

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: ProductDownloader [cache-dir] <yyyy-mm-dd> [yyyy-mm-dd] [SP3|CLK|ERP|BIA|OBX|TABLE|VMF3|ANTEX]...");
            System.out.println("  cache-dir defaults to: " + DEFAULT_CACHE_DIR);
            System.out.println("  Example: ProductDownloader ./product 2024-01-15 2024-01-15 SP3 CLK ERP");
            System.out.println("  Example: ProductDownloader 2024-01-15 SP3 CLK");
            System.out.println("  Example: ProductDownloader ./product TABLE  (downloads leap.sec, sat_parameters)");
            System.out.println("  Example: ProductDownloader ./product 2024-01-15 VMF3");
            System.out.println("  Example: ProductDownloader ./product ANTEX");
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

        ProductDownloader dl = new ProductDownloader(cacheDir);

        if (argIdx < args.length && "TABLE".equals(args[argIdx])) {
            System.out.println("=== Downloading Table Files ===");
            String leap = dl.downloadTable("leap.sec");
            System.out.println("leap.sec: " + leap);
            String satpara = dl.downloadTable("sat_parameters");
            System.out.println("sat_parameters: " + satpara);
            return;
        }

        if (argIdx < args.length && "ANTEX".equals(args[argIdx])) {
            System.out.println("=== Downloading ANTEX ===");
            String atx = dl.downloadAntex("igs20_2317.atx");
            System.out.println("ANTEX: " + atx);
            return;
        }

        if (argIdx >= args.length) {
            System.err.println("Error: date argument required");
            return;
        }

        LocalDate startDate = LocalDate.parse(args[argIdx++], DateTimeFormatter.ISO_LOCAL_DATE);
        LocalDate endDate = argIdx < args.length && !args[argIdx].startsWith("SP3") && !args[argIdx].startsWith("CLK") && !args[argIdx].startsWith("ERP") && !args[argIdx].startsWith("BIA") && !args[argIdx].startsWith("OBX") && !args[argIdx].startsWith("VMF3")
                ? LocalDate.parse(args[argIdx++], DateTimeFormatter.ISO_LOCAL_DATE)
                : startDate;

        if (argIdx < args.length && "VMF3".equals(args[argIdx])) {
            System.out.println("=== Downloading VMF3 Grid for " + startDate + " ===");
            String vmf = dl.downloadVmf3Grid(startDate);
            System.out.println("VMF3: " + vmf);
            return;
        }

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