package org.rtklib.java.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.product.ProductDownloader.ProductType;
import org.rtklib.java.product.ProductDownloader.DownloadResult;
import org.rtklib.java.product.ProductDownloader.ProductFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProductDownloader Test")
class ProductDownloaderTest {

    @Test
    @DisplayName("GPS week calculation (UTC date based)")
    void testDateToGpsWeek() {
        assertEquals(2264, ProductDownloader.dateToGpsWeek(LocalDate.of(2023, 6, 3)));
        assertEquals(2304, ProductDownloader.dateToGpsWeek(LocalDate.of(2024, 3, 9)));
        assertEquals(2297, ProductDownloader.dateToGpsWeek(LocalDate.of(2024, 1, 15)));
        assertEquals(0, ProductDownloader.dateToGpsWeek(LocalDate.of(1980, 1, 6)));
        assertEquals(1, ProductDownloader.dateToGpsWeek(LocalDate.of(1980, 1, 13)));
    }

    @Test
    @DisplayName("FTP/FTPS to HTTP/HTTPS URL conversion")
    void testFtpToHttp() {
        assertEquals("https://igs.ign.fr/pub/igs/products/mgex/2260/test.sp3.gz",
                ProductDownloader.ftpToHttp("ftp://igs.ign.fr/pub/igs/products/mgex/2260/test.sp3.gz"));
        assertEquals("https://bdspride.com/wum/2260/test.sp3.gz",
                ProductDownloader.ftpToHttp("ftps://bdspride.com/wum/2260/test.sp3.gz"));
        assertEquals("https://example.com/file.gz",
                ProductDownloader.ftpToHttp("https://example.com/file.gz"));
    }

    @Test
    @DisplayName("Default cache dir is user.dir/product")
    void testDefaultCacheDir() {
        assertNotNull(ProductDownloader.DEFAULT_CACHE_DIR);
        assertTrue(ProductDownloader.DEFAULT_CACHE_DIR.endsWith("product"));
        ProductDownloader dl = new ProductDownloader();
        assertTrue(Files.exists(dl.getCacheDir()));
    }

    @Test
    @DisplayName("Default URL constants")
    void testDefaultUrlConstants() {
        assertEquals("ftps://bdspride.com", ProductDownloader.URL_BDSPRIDE);
        assertEquals("ftp://igs.ign.fr", ProductDownloader.URL_IGN);
        assertEquals("ftp://igs.gnsswhu.cn", ProductDownloader.URL_WHU);
    }

    @Test
    @DisplayName("Custom URL configuration")
    void testCustomUrls(@TempDir Path tempDir) {
        ProductDownloader dl = new ProductDownloader(
                tempDir.toString(), true, true,
                "ftps://custom1.com", "ftp://custom2.com", "ftp://custom3.com");
        LocalDate date = LocalDate.of(2024, 1, 15);
        List<String> urls = dl.generateUrls(date, ProductType.SP3);
        assertTrue(urls.get(0).contains("custom1.com"));
        assertTrue(urls.get(1).contains("custom2.com"));
        assertTrue(urls.get(2).contains("custom3.com"));
    }

    @Test
    @DisplayName("URL generation for SP3 - bdspride first")
    void testGenerateUrlsSp3() {
        ProductDownloader dl = new ProductDownloader("dummy");
        LocalDate date = LocalDate.of(2024, 1, 15);
        List<String> urls = dl.generateUrls(date, ProductType.SP3);

        assertTrue(urls.size() >= 5);
        assertTrue(urls.get(0).contains("bdspride.com"));
        assertTrue(urls.get(0).contains("WUM0MGXRAP_20240150000_01D_05M_ORB.SP3.gz"));
        assertTrue(urls.get(1).contains("igs.ign.fr"));
        assertTrue(urls.get(2).contains("igs.gnsswhu.cn"));
    }

    @Test
    @DisplayName("URL generation for CLK - bdspride first")
    void testGenerateUrlsClk() {
        ProductDownloader dl = new ProductDownloader("dummy");
        LocalDate date = LocalDate.of(2024, 3, 1);
        List<String> urls = dl.generateUrls(date, ProductType.CLK);

        assertTrue(urls.size() >= 5);
        assertTrue(urls.get(0).contains("bdspride.com"));
        assertTrue(urls.get(0).contains("30S_CLK.CLK.gz"));
    }

    @Test
    @DisplayName("URL generation for ERP - bdspride first")
    void testGenerateUrlsErp() {
        ProductDownloader dl = new ProductDownloader("dummy");
        LocalDate date = LocalDate.of(2024, 3, 1);
        List<String> urls = dl.generateUrls(date, ProductType.ERP);

        assertTrue(urls.size() >= 3);
        assertTrue(urls.get(0).contains("bdspride.com"));
        assertTrue(urls.get(0).contains("01D_ERP.ERP.gz"));
    }

    @Test
    @DisplayName("URL generation for DCB")
    void testGenerateUrlsDcb() {
        ProductDownloader dl = new ProductDownloader("dummy");
        LocalDate date = LocalDate.of(2024, 3, 1);
        List<String> urls = dl.generateUrls(date, ProductType.DCB);

        assertFalse(urls.isEmpty());
        assertTrue(urls.get(0).contains("CAS0MGXRAP"));
        assertTrue(urls.get(0).contains("01D_BIA.BIA.gz"));
    }

    @Test
    @DisplayName("Sub directory structure")
    void testSubDirStructure(@TempDir Path tempDir) {
        ProductDownloader dl = new ProductDownloader(tempDir.toString());
        assertEquals(tempDir.resolve("orbit"), dl.getSubDir(ProductType.SP3));
        assertEquals(tempDir.resolve("clock"), dl.getSubDir(ProductType.CLK));
        assertEquals(tempDir.resolve("erp"), dl.getSubDir(ProductType.ERP));
        assertEquals(tempDir.resolve("erp"), dl.getSubDir(ProductType.ERP_IGS));
        assertEquals(tempDir.resolve("dcb"), dl.getSubDir(ProductType.DCB));
        assertTrue(Files.exists(tempDir.resolve("orbit")));
        assertTrue(Files.exists(tempDir.resolve("clock")));
        assertTrue(Files.exists(tempDir.resolve("erp")));
        assertTrue(Files.exists(tempDir.resolve("dcb")));
    }

    @Test
    @DisplayName("Local file name generation")
    void testGenerateLocalFileName() {
        ProductDownloader dl = new ProductDownloader("dummy");
        LocalDate date = LocalDate.of(2024, 1, 15);

        assertEquals("WUM0MGXRAP_20240150000_01D_05M_ORB.SP3",
                dl.generateLocalFileName(date, ProductType.SP3));
        assertEquals("WUM0MGXRAP_20240150000_01D_30S_CLK.CLK",
                dl.generateLocalFileName(date, ProductType.CLK));
        assertEquals("WUM0MGXRAP_20240150000_01D_01D_ERP.ERP",
                dl.generateLocalFileName(date, ProductType.ERP));
        assertEquals("CAS0MGXRAP_20240150000_01D_01D_BIA.BIA",
                dl.generateLocalFileName(date, ProductType.DCB));
    }

    @Test
    @DisplayName("Cache directory creation")
    void testCacheDirCreation(@TempDir Path tempDir) {
        Path cachePath = tempDir.resolve("product_cache");
        ProductDownloader dl = new ProductDownloader(cachePath.toString());
        assertTrue(Files.exists(dl.getCacheDir()));
    }

    @Test
    @DisplayName("Offline mode returns null")
    void testOfflineMode(@TempDir Path tempDir) {
        ProductDownloader dl = new ProductDownloader(tempDir.toString(), true, true);
        LocalDate date = LocalDate.of(2024, 1, 15);
        DownloadResult result = dl.download(date, ProductType.SP3);

        assertTrue(result.files.isEmpty());
        assertFalse(result.errors.isEmpty());
    }

    @Test
    @DisplayName("GZ decompression")
    void testGzDecompression(@TempDir Path tempDir) throws IOException {
        Path inputFile = tempDir.resolve("test.txt.gz");
        Path outputFile = tempDir.resolve("test.txt");

        String content = "Hello SP3 test content";
        byte[] data = content.getBytes();

        try (var fos = Files.newOutputStream(inputFile);
             var gzos = new java.util.zip.GZIPOutputStream(fos)) {
            gzos.write(data);
        }

        ProductDownloader dl = new ProductDownloader(tempDir.toString());
        Path result = dl.decompressIfNeeded(inputFile, outputFile);

        assertEquals(outputFile, result);
        assertTrue(Files.exists(outputFile));
        assertFalse(Files.exists(inputFile));
        assertEquals(content, Files.readString(outputFile));
    }

    @Test
    @DisplayName("Non-compressed file passthrough")
    void testNonCompressedPassthrough(@TempDir Path tempDir) throws IOException {
        Path inputFile = tempDir.resolve("test.sp3");
        Path outputFile = tempDir.resolve("test_output.sp3");

        String content = "SP3 test content";
        Files.writeString(inputFile, content);

        ProductDownloader dl = new ProductDownloader(tempDir.toString());
        Path result = dl.decompressIfNeeded(inputFile, outputFile);

        assertEquals(outputFile, result);
        assertTrue(Files.exists(outputFile));
        assertEquals(content, Files.readString(outputFile));
    }

    @Test
    @DisplayName("DownloadResult toString")
    void testDownloadResultToString() {
        DownloadResult result = new DownloadResult();
        assertEquals("No products downloaded", result.toString());

        result.files.add(new ProductFile(ProductType.SP3, "/path/to/sp3", true));
        result.files.add(new ProductFile(ProductType.CLK, "/path/to/clk", false));
        String str = result.toString();
        assertTrue(str.contains("SP3"));
        assertTrue(str.contains("cached"));
        assertTrue(str.contains("CLK"));
        assertTrue(str.contains("downloaded"));
    }
}