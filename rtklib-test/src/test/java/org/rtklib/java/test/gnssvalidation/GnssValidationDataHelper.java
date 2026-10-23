package org.rtklib.java.test.gnssvalidation;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.rtklib.java.product.ProductDownloader;
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

public class GnssValidationDataHelper {

    private static final Logger log = LoggerFactory.getLogger(GnssValidationDataHelper.class);

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 120000;

    public static final String SATREF_HOST = "rinex.geodetic.gov.hk";
    public static final int SATREF_FTPS_PORT = 990;
    public static final String WHU_FTP_HOST = "igs.gnsswhu.cn";
    public static final String BKG_HTTPS_BASE = "https://igs.bkg.bund.de/root_ftp/IGS";

    private final String testDataDir;
    private final boolean offline;

    public GnssValidationDataHelper(String testDataDir) {
        this(testDataDir, false);
    }

    public GnssValidationDataHelper(String testDataDir, boolean offline) {
        this.testDataDir = testDataDir;
        this.offline = offline;
    }

    public String getGnssValidationDir() {
        return testDataDir + File.separator + "gnss-validation";
    }

    // ========== IGS Station RINEX (BKG HTTPS首选 → WHU FTP回退) ==========

    public boolean ensureBjfsRinex(String subDir, int year, int doy) {
        String targetDir = getGnssValidationDir() + File.separator + subDir + File.separator + "bjfs";
        return ensureIgsRinex(targetDir, "bjfs", year, doy);
    }

    public boolean ensureIgsStationRinex(String subDir, String station, int year, int doy) {
        String targetDir = getGnssValidationDir() + File.separator + subDir + File.separator + station.toLowerCase();
        return ensureIgsRinex(targetDir, station, year, doy);
    }

    public boolean ensureIgsRinex(String targetDir, String station, int year, int doy) {
        Path dir = Paths.get(targetDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Cannot create dir: {}", dir);
            return false;
        }

        if (hasRinexInDir(dir)) {
            log.info("{} RINEX already exists in {}", station.toUpperCase(), dir);
            return true;
        }

        if (offline) {
            log.warn("Offline mode: cannot download {} RINEX", station.toUpperCase());
            return false;
        }

        String ddd = String.format("%03d", doy);
        String st = station.toLowerCase();
        String stUpper = st.toUpperCase();

        boolean obsOk = false, navOk = false;

        // ===== Phase 1: BKG HTTPS (RINEX 3 长文件名) =====
        log.info("Trying BKG HTTPS for {} RINEX 3...", stUpper);

        // OBS: 列出 BKG obs 目录，匹配站名开头的 .crx.gz
        if (!obsOk) {
            try {
                String obsDirUrl = String.format("%s/obs/%d/%s/", BKG_HTTPS_BASE, year, ddd);
                List<String> obsMatches = listHttpDir(obsDirUrl, stUpper + "00*_R_" + year + ddd + "0000_01D_30S_MO.crx.gz");
                for (String match : obsMatches) {
                    try {
                        Path downloaded = downloadHttp(obsDirUrl + match, dir);
                        if (downloaded != null) {
                            Path decompressed = decompressGz(downloaded);
                            decompressed = hatanakaDecompress(decompressed);
                            if (decompressed != null) { obsOk = true; break; }
                        }
                    } catch (Exception e) { log.warn("BKG obs download failed: {}", e.getMessage()); }
                }
            } catch (Exception e) { log.warn("BKG obs listing failed: {}", e.getMessage()); }
        }

        // NAV: 直接拼 BRDC 合并广播星历 URL
        if (!navOk) {
            String brdcUrl = String.format("%s/BRDC/%d/%s/BRDC00IGS_R_%d%s0000_01D_MN.rnx.gz",
                    BKG_HTTPS_BASE, year, ddd, year, ddd);
            try {
                Path downloaded = downloadHttp(brdcUrl, dir);
                if (downloaded != null) {
                    Path decompressed = decompressGz(downloaded);
                    if (decompressed != null) navOk = true;
                }
            } catch (Exception e) { log.warn("BKG BRDC download failed: {}", e.getMessage()); }
        }

        // ===== Phase 2: WHU FTP (RINEX 2 回退) =====
        if (!obsOk || !navOk) {
            log.info("BKG incomplete, trying WHU FTP fallback for {}", stUpper);
            String yy = String.format("%02d", year % 100);

            String[][] whuCandidates = {
                    {String.format("/pub/gps/data/daily/%d/%s/%so/%s%s0.%so.gz", year, ddd, yy, st, ddd, yy), "obs_rnx2"},
                    {String.format("/pub/gps/data/daily/%d/%s/%sd/%s%s0.%sd.gz", year, ddd, yy, st, ddd, yy), "obs_hat_rnx2"},
                    {String.format("/pub/gps/data/daily/%d/%s/%sn/%s%s0.%sn.gz", year, ddd, yy, st, ddd, yy), "nav"},
                    {String.format("/pub/gps/data/daily/%d/%s/%sn/%s%s0.%sn.Z", year, ddd, yy, st, ddd, yy), "nav_z"},
                    {String.format("/pub/gps/data/daily/%d/%sn/brdc%s0.%sn.gz", year, yy, ddd, yy), "brdc_nav"},
            };

            for (String[] c : whuCandidates) {
                String remotePath = c[0];
                String type = c[1];
                boolean isObs = type.startsWith("obs");
                boolean isNav = type.startsWith("nav") || type.startsWith("brdc");
                if ((isObs && obsOk) || (isNav && navOk)) continue;
                try {
                    Path downloaded = downloadFtp(WHU_FTP_HOST, 21, remotePath, dir);
                    if (downloaded != null) {
                        Path decompressed = decompressGz(downloaded);
                        if ("obs_hat_rnx2".equals(type)) {
                            decompressed = hatanakaDecompress(decompressed);
                        }
                        if ("nav_z".equals(type) && decompressed != null) {
                            decompressed = decompressUnixZ(decompressed);
                        }
                        if (decompressed != null) {
                            if (isObs) obsOk = true;
                            if (isNav) navOk = true;
                        }
                    }
                } catch (Exception e) {
                    log.warn("WHU fallback failed for {}: {}", type, e.getMessage());
                }
            }
        }

        boolean ok = obsOk && navOk;
        if (ok) {
            log.info("{} RINEX ready in {}", station.toUpperCase(), dir);
        } else {
            log.warn("{} RINEX download incomplete (obs={}, nav={})", station.toUpperCase(), obsOk, navOk);
        }
        return ok;
    }

    // ========== SatRef RINEX (香港) ==========

    public boolean ensureSatRefRinex(String targetDir, String station, int year, int doy, String sampling) {
        Path dir = Paths.get(targetDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Cannot create dir: {}", dir);
            return false;
        }

        if (hasStationRinexInDir(dir, station)) {
            log.info("SatRef {} RINEX already exists in {}", station, dir);
            return true;
        }

        if (offline) {
            log.warn("Offline mode: cannot download SatRef {} RINEX", station);
            return false;
        }

        String st = station.toLowerCase();
        String ST = station.toUpperCase();
        String ddd = String.format("%03d", doy);
        String sampDir = sampling.toLowerCase();

        String rinex3Obs = String.format("/rinex3/%d/%s/%s/%s/%s00HKG_R_%d%s0000_01D_%s_MO.crx.gz",
                year, ddd, st, sampDir, ST, year, ddd, sampling.toUpperCase());

        String[] remotePaths = new String[]{rinex3Obs};

        boolean downloaded = false;
        for (String remotePath : remotePaths) {
            try {
                Path file = downloadSatRefFtps(remotePath, dir);
                if (file != null) {
                    Path decompressed = decompressGz(file);
                    if (decompressed != null) {
                        Path rinex = hatanakaDecompress(decompressed);
                        if (rinex != null) {
                            downloaded = true;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("SatRef FTPS download failed for {}: {}", station, e.getMessage());
            }
        }

        if (!downloaded) {
            log.info("FTPS failed, trying HTTPS fallback for {}", station);
            for (String remotePath : remotePaths) {
                try {
                    String httpsUrl = String.format("https://%s%s", SATREF_HOST, remotePath);
                    Path file = downloadHttp(httpsUrl, dir);
                    if (file != null) {
                        Path decompressed = decompressGz(file);
                        if (decompressed != null) {
                            Path rinex = hatanakaDecompress(decompressed);
                            if (rinex != null) {
                                downloaded = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("SatRef HTTPS fallback failed for {}: {}", station, e.getMessage());
                }
            }
        }

        if (downloaded) {
            log.info("SatRef {} RINEX ready in {}", station, dir);
        }
        return downloaded;
    }

    // ========== MGEX精密产品 (via ProductDownloader) ==========

    public boolean ensureMgexProducts(int year, int doy, ProductDownloader.ProductType... types) {
        String productDir = getGnssValidationDir() + File.separator + "product";
        LocalDate date = LocalDate.ofYearDay(year, doy);

        ProductDownloader downloader = new ProductDownloader(productDir, true, offline);
        ProductDownloader.DownloadResult result = downloader.download(date, types);

        if (result.isSuccess()) {
            log.info("MGEX products ready: {} files", result.files.size());
            for (ProductDownloader.ProductFile pf : result.files) {
                log.info("  {}", pf);
            }
            return true;
        } else {
            log.warn("MGEX product download issues: {}", result.errors);
            return !result.files.isEmpty();
        }
    }

    // ========== Hatanaka 解压 (Compact RINEX → RINEX) ==========

    public static Path hatanakaDecompress(Path compactRinex) {
        if (compactRinex == null || !Files.exists(compactRinex)) return compactRinex;

        String name = compactRinex.getFileName().toString();
        String nameLower = name.toLowerCase();

        boolean isCompactObs = nameLower.matches(".*\\.\\d+d") || nameLower.endsWith(".crx") || nameLower.endsWith(".cobs")
                || (nameLower.contains("_mo.crx") || nameLower.contains("_so.crx") || nameLower.contains("_mo.rnx"));
        boolean isCompactNav = nameLower.matches(".*\\.\\d+g") || nameLower.endsWith(".cnav")
                || (nameLower.contains("_mn.crx") || nameLower.contains("_gn.crx"));

        if (!isCompactObs && !isCompactNav) {
            return compactRinex;
        }

        log.info("Hatanaka decompress: {}", compactRinex.getFileName());

        String outName = compactRinex.getFileName().toString();
        if (isCompactObs) {
            if (outName.toLowerCase().endsWith(".crx")) {
                outName = outName.substring(0, outName.length() - 4) + ".rnx";
            } else {
                outName = outName.replaceAll("\\.\\d+d$", ".0" + outName.substring(outName.length() - 2, outName.length() - 1) + "o");
                if (outName.equals(compactRinex.getFileName().toString())) {
                    outName = outName.replace(".cobs", ".obs");
                }
            }
        } else {
            if (outName.toLowerCase().endsWith(".crx")) {
                outName = outName.substring(0, outName.length() - 4) + ".rnx";
            } else {
                outName = outName.replaceAll("\\.\\d+g$", ".0" + outName.substring(outName.length() - 2, outName.length() - 1) + "n");
                if (outName.equals(compactRinex.getFileName().toString())) {
                    outName = outName.replace(".cnav", ".nav");
                }
            }
        }
        Path outFile = compactRinex.getParent().resolve(outName);

        try {
            if (Files.exists(outFile) && Files.size(outFile) > 0) {
                Files.deleteIfExists(compactRinex);
                return outFile;
            }
        } catch (IOException ignored) {}

        // Try external crx2rnx tool first
        Path crx2rnx = findCrx2rnx();
        if (crx2rnx != null) {
            try {
                ProcessBuilder pb = new ProcessBuilder(crx2rnx.toString(), "-f", compactRinex.toString());
                pb.directory(compactRinex.getParent().toFile());
                Process proc = pb.start();
                int exitCode = proc.waitFor();
                if (exitCode == 0 && Files.exists(outFile) && Files.size(outFile) > 0) {
                    Files.deleteIfExists(compactRinex);
                    log.info("Hatanaka decompressed (crx2rnx): {} -> {}", compactRinex.getFileName(), outName);
                    return outFile;
                } else {
                    log.warn("crx2rnx exited with code {}, output exists={}", exitCode, Files.exists(outFile));
                }
            } catch (Exception e) {
                log.warn("crx2rnx execution failed: {}", e.getMessage());
            }
        }

        // Fallback: pure Java Hatanaka decompression (limited - may not work for RINEX 3)
        log.warn("No crx2rnx tool available, trying pure Java Hatanaka decompression (may be incorrect for RINEX 3)");

        try {
            List<String> lines = Files.readAllLines(compactRinex);
            if (lines.isEmpty()) return compactRinex;

            List<String> expanded = new ArrayList<>();
            boolean inBody = false;
            int nSat = 0;
            int epochLineCount = 0;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                if (!inBody) {
                    expanded.add(line);

                    if (isCompactObs && line.trim().startsWith("END OF HEADER")) {
                        inBody = true;
                        continue;
                    }
                    if (isCompactNav && line.trim().startsWith("END OF HEADER")) {
                        inBody = true;
                        continue;
                    }

                    if (isCompactObs && i > 0) {
                        String trimmed = lines.get(i - 1).trim();
                        if (trimmed.matches("\\d+.*")) {
                            try {
                                String[] parts = trimmed.split("\\s+");
                                nSat = Integer.parseInt(parts[parts.length - 1].trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    continue;
                }

                if (isCompactObs) {
                    if (line.trim().isEmpty()) {
                        expanded.add(line);
                        continue;
                    }

                    if (i > 0 && lines.get(i - 1).trim().isEmpty() || line.matches("^\\s*\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\.?\\d*\\s+\\d+.*")) {
                        expanded.add(line);
                        if (line.matches(".*\\s+\\d+\\s*$")) {
                            try {
                                String[] parts = line.trim().split("\\s+");
                                nSat = Integer.parseInt(parts[parts.length - 1]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        continue;
                    }

                    expanded.add(decodeDifferentialLine(line));
                } else {
                    expanded.add(decodeDifferentialLine(line));
                }
            }

            Files.write(outFile, expanded);
            log.info("Hatanaka decompressed (Java): {} -> {}", compactRinex.getFileName(), outName);
            Files.deleteIfExists(compactRinex);
            return outFile;

        } catch (Exception e) {
            log.warn("Hatanaka decompression failed, keeping compact file: {}", e.getMessage());
            return compactRinex;
        }
    }

    private static Path findCrx2rnx() {
        String projectRoot = System.getProperty("user.dir");
        while (projectRoot != null && !new File(projectRoot, "pom.xml").exists()) {
            File parent = new File(projectRoot).getParentFile();
            projectRoot = parent != null ? parent.getAbsolutePath() : null;
        }
        if (projectRoot != null) {
            Path p = Paths.get(projectRoot, "tools", "CRX2RNX.exe");
            if (Files.exists(p)) return p;
            p = Paths.get(projectRoot, "tools", "crx2rnx");
            if (Files.exists(p)) return p;
        }
        String[] candidates = {
                "D:\\pro\\RTKLIB_EX_2.5.0\\crx2rnx.exe",
                "C:\\pro\\RTKLIB_EX_2.5.0\\crx2rnx.exe",
                "/usr/bin/crx2rnx",
                "/usr/local/bin/crx2rnx",
        };
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.exists(p)) return p;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                Path p = Paths.get(dir, "CRX2RNX.exe");
                if (Files.exists(p)) return p;
                p = Paths.get(dir, "crx2rnx.exe");
                if (Files.exists(p)) return p;
                p = Paths.get(dir, "crx2rnx");
                if (Files.exists(p)) return p;
            }
        }
        return null;
    }

    static String decodeDifferentialLine(String line) {
        if (line == null || line.isEmpty()) return line;

        if (!line.contains("&") && !line.contains("#")) {
            return line;
        }

        StringBuilder result = new StringBuilder();
        StringBuilder currentNum = new StringBuilder();
        boolean inNumber = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '&') {
                if (inNumber && currentNum.length() > 0) {
                    long val = Long.parseLong(currentNum.toString());
                    result.append(val + 1);
                    currentNum.setLength(0);
                    inNumber = false;
                } else {
                    result.append('1');
                }
                continue;
            }

            if (c == '#') {
                if (inNumber && currentNum.length() > 0) {
                    long val = Long.parseLong(currentNum.toString());
                    result.append(val - 1);
                    currentNum.setLength(0);
                    inNumber = false;
                } else {
                    result.append("-1");
                }
                continue;
            }

            if (Character.isDigit(c) || (c == '-' && !inNumber)) {
                inNumber = true;
                currentNum.append(c);
            } else {
                if (inNumber) {
                    result.append(currentNum);
                    currentNum.setLength(0);
                    inNumber = false;
                }
                result.append(c);
            }
        }

        if (inNumber) {
            result.append(currentNum);
        }

        return result.toString();
    }

    // ========== 下载工具方法 ==========

    private Path downloadFtp(String host, int port, String remotePath, Path targetDir) throws IOException {
        String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        Path targetPath = targetDir.resolve(fileName);

        if (Files.exists(targetPath) && Files.size(targetPath) > 0) {
            log.debug("FTP cached: {}", fileName);
            return targetPath;
        }

        FTPClient ftp = new FTPClient();
        try {
            ftp.setConnectTimeout(CONNECT_TIMEOUT);
            ftp.setDefaultTimeout(READ_TIMEOUT);
            ftp.setDataTimeout(READ_TIMEOUT);

            ftp.connect(host, port);
            if (!ftp.login("anonymous", "rtklib-java@test")) {
                throw new IOException("FTP login failed: " + host);
            }
            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            Path tempFile = targetDir.resolve(fileName + ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                boolean retrieved = ftp.retrieveFile(remotePath, out);
                if (!retrieved) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("FTP retrieve failed: " + remotePath);
                }
            }

            long size = Files.size(tempFile);
            if (size == 0) {
                Files.deleteIfExists(tempFile);
                throw new IOException("FTP empty response");
            }

            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("FTP downloaded {} ({} bytes) from {}", fileName, size, host);
            return targetPath;

        } finally {
            try { ftp.disconnect(); } catch (Exception ignored) {}
        }
    }

    private Path downloadSatRefFtps(String remotePath, Path targetDir) throws IOException {
        String fileName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        Path targetPath = targetDir.resolve(fileName);

        if (Files.exists(targetPath) && Files.size(targetPath) > 0) {
            log.debug("SatRef FTPS cached: {}", fileName);
            return targetPath;
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

            ftps.connect(SATREF_HOST, SATREF_FTPS_PORT);
            if (!ftps.login("anonymous", "rtklib-java@test")) {
                throw new IOException("SatRef FTPS login failed");
            }
            ftps.execPBSZ(0);
            ftps.execPROT("P");
            ftps.enterLocalPassiveMode();
            ftps.setFileType(FTP.BINARY_FILE_TYPE);

            Path tempFile = targetDir.resolve(fileName + ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                boolean retrieved = ftps.retrieveFile(remotePath, out);
                if (!retrieved) {
                    Files.deleteIfExists(tempFile);
                    throw new IOException("SatRef FTPS retrieve failed: " + remotePath);
                }
            }

            long size = Files.size(tempFile);
            if (size == 0) {
                Files.deleteIfExists(tempFile);
                throw new IOException("SatRef FTPS empty response");
            }

            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("SatRef FTPS downloaded {} ({} bytes)", fileName, size);
            return targetPath;

        } finally {
            try { ftps.disconnect(); } catch (Exception ignored) {}
        }
    }

    private List<String> listHttpDir(String dirUrl, String globPattern) throws IOException {
        List<String> result = new ArrayList<>();
        String regex = globPattern.replace(".", "\\.").replace("*", ".*");
        HttpURLConnection conn = null;
        try {
            URL url = new URL(dirUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "rtklib-java/2.3");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " listing " + dirUrl);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    for (String token : line.split("[\"<>\\s]+")) {
                        if (token.matches(regex)) {
                            result.add(token);
                        }
                    }
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    private Path downloadHttp(String urlStr, Path targetDir) throws IOException {
        String fileName = urlStr.substring(urlStr.lastIndexOf('/') + 1);
        Path targetPath = targetDir.resolve(fileName);

        if (Files.exists(targetPath) && Files.size(targetPath) > 0) {
            log.debug("HTTP cached: {}", fileName);
            return targetPath;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "rtklib-java/2.3");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP) {
                String location = conn.getHeaderField("Location");
                if (location != null) {
                    conn.disconnect();
                    return downloadHttp(location, targetDir);
                }
            }

            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + urlStr);
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
                throw new IOException("HTTP empty response");
            }

            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("HTTP downloaded {} ({} bytes)", fileName, totalBytes);
            return targetPath;

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ========== 解压工具 ==========

    public static Path decompressGz(Path gzFile) throws IOException {
        if (gzFile == null || !Files.exists(gzFile)) return gzFile;
        String name = gzFile.getFileName().toString();
        if (!name.toLowerCase().endsWith(".gz")) return gzFile;

        String outName = name.substring(0, name.length() - 3);
        Path outFile = gzFile.getParent().resolve(outName);

        if (Files.exists(outFile) && Files.size(outFile) > 0) {
            Files.deleteIfExists(gzFile);
            return outFile;
        }

        try (InputStream fis = Files.newInputStream(gzFile);
             InputStream gis = new GZIPInputStream(fis);
             OutputStream fos = Files.newOutputStream(outFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            gis.transferTo(fos);
        }

        Files.deleteIfExists(gzFile);
        log.info("Decompressed {} -> {}", gzFile.getFileName(), outName);
        return outFile;
    }

    public static Path decompressUnixZ(Path zFile) throws IOException {
        if (zFile == null || !Files.exists(zFile)) return zFile;
        String name = zFile.getFileName().toString();
        if (!name.toLowerCase().endsWith(".z")) return zFile;

        String outName = name.substring(0, name.length() - 2);
        Path outFile = zFile.getParent().resolve(outName);

        if (Files.exists(outFile) && Files.size(outFile) > 0) {
            Files.deleteIfExists(zFile);
            return outFile;
        }

        log.info("Unix compress .Z detected: {}, attempting decompression via ProcessBuilder", zFile.getFileName());
        try {
            ProcessBuilder pb = new ProcessBuilder("gzip", "-dc", zFile.toString());
            pb.redirectOutput(outFile.toFile());
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode == 0 && Files.exists(outFile) && Files.size(outFile) > 0) {
                Files.deleteIfExists(zFile);
                log.info("Decompressed .Z -> {}", outName);
                return outFile;
            }
        } catch (Exception e) {
            log.debug("gzip -dc failed: {}", e.getMessage());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "gzip", "-dc", zFile.toString());
            pb.redirectOutput(outFile.toFile());
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode == 0 && Files.exists(outFile) && Files.size(outFile) > 0) {
                Files.deleteIfExists(zFile);
                log.info("Decompressed .Z -> {}", outName);
                return outFile;
            }
        } catch (Exception e) {
            log.debug("cmd gzip -dc failed: {}", e.getMessage());
        }

        log.warn("Cannot decompress .Z file (no gzip command available), keeping as-is: {}", zFile.getFileName());
        return zFile;
    }

    // ========== 辅助方法 ==========

    private boolean hasRinexInDir(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try {
            return Files.list(dir).anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".obs") || n.endsWith(".25o") || n.endsWith(".24o") || n.endsWith(".23o")
                        || n.endsWith(".22o") || n.endsWith(".21o") || n.endsWith(".18o") || n.endsWith(".rnx");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasStationRinexInDir(Path dir, String station) {
        if (!Files.isDirectory(dir)) return false;
        String stUpper = station.toUpperCase();
        try {
            return Files.list(dir).anyMatch(p -> {
                String n = p.getFileName().toString().toUpperCase();
                return n.startsWith(stUpper) && (n.endsWith(".RNX") || n.endsWith(".O") || n.endsWith(".OBS"));
            });
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isDataAvailable(String dir) {
        Path p = Paths.get(dir);
        if (!Files.isDirectory(p)) return false;
        try {
            return Files.list(p).anyMatch(f -> {
                String n = f.getFileName().toString().toLowerCase();
                return n.endsWith(".obs") || n.endsWith(".25o") || n.endsWith(".24o") || n.endsWith(".23o")
                        || n.endsWith(".22o") || n.endsWith(".21o") || n.endsWith(".18o") || n.endsWith(".rnx");
            });
        } catch (IOException e) {
            return false;
        }
    }
}