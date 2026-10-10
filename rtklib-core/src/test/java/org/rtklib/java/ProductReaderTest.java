package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Pcv;
import org.rtklib.java.ephemeris.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Product file reader tests (SP3/CLK/IONEX/OSB/ATX)")
public class ProductReaderTest {

    private static final Logger log = LoggerFactory.getLogger(ProductReaderTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir();

    private static final String SP3_FILE = TestDataConfig.getTestDataFile("product/igs15904.sp3");
    private static final String CLK_FILE = TestDataConfig.getTestDataFile("product/igs15904.clk");
    private static final String IONEX_FILE = TestDataConfig.getTestDataFile("product/igsg1570.18i");
    private static final String OSB_FILE = TestDataConfig.getTestDataFile("product/cod_osb_2021265.bia");
    private static final String ATX_FILE = TestDataConfig.getTestDataFile("product/test.atx");

    @BeforeAll
    static void checkData() {
        log.info("Data dir: {}", DATA_DIR);
        log.info("SP3 exists: {}", Files.exists(Paths.get(SP3_FILE)));
        log.info("CLK exists: {}", Files.exists(Paths.get(CLK_FILE)));
        log.info("IONEX exists: {}", Files.exists(Paths.get(IONEX_FILE)));
        log.info("OSB exists: {}", Files.exists(Paths.get(OSB_FILE)));
        log.info("ATX exists: {}", Files.exists(Paths.get(ATX_FILE)));
    }

    @Test
    @DisplayName("SP3 precise ephemeris loading")
    void testSp3Loading() {
        Assumptions.assumeTrue(Files.exists(Paths.get(SP3_FILE)), "SP3 file not available");

        Nav nav = new Nav();
        Sp3Reader.readsp3(SP3_FILE, nav, 0);

        log.info("SP3: ne={}", nav.ne);
        assertTrue(nav.ne > 0, "Nav.ne should be > 0 after SP3 loading");
    }

    @Test
    @DisplayName("CLK precise clock loading")
    void testClkLoading() {
        Assumptions.assumeTrue(Files.exists(Paths.get(CLK_FILE)), "CLK file not available");

        Nav nav = new Nav();
        ClkReader.readclk(CLK_FILE, nav);

        log.info("CLK: nc={}", nav.nc);
        assertTrue(nav.nc > 0, "Nav.nc should be > 0 after CLK loading");
    }

    @Test
    @DisplayName("IONEX ionosphere map loading (T5)")
    void testIonexLoading() {
        Assumptions.assumeTrue(Files.exists(Paths.get(IONEX_FILE)), "IONEX file not available");

        Nav nav = new Nav();
        boolean ok = IonexReader.readIonex(IONEX_FILE, nav);

        log.info("IONEX: ok={}, ionexGrid={}", ok, nav.ionexGrid != null);
        assertTrue(ok, "IONEX loading should succeed");
        assertNotNull(nav.ionexGrid, "Nav.ionexGrid should not be null after IONEX loading");
    }

    @Test
    @DisplayName("OSB bias file loading (P5/T8)")
    void testOsbLoading() {
        Assumptions.assumeTrue(Files.exists(Paths.get(OSB_FILE)), "OSB file not available");

        Nav nav = new Nav();
        boolean ok = OsbReader.readOsb(OSB_FILE, nav);

        log.info("OSB: ok={}", ok);
        assertTrue(ok, "OSB loading should succeed");
    }

    @Test
    @DisplayName("ATX antenna file loading")
    void testAtxLoading() {
        Assumptions.assumeTrue(Files.exists(Paths.get(ATX_FILE)), "ATX file not available");

        List<Pcv> pcvs = PcvReader.readpcv(ATX_FILE);

        log.info("ATX: n={}", pcvs != null ? pcvs.size() : 0);
        assertNotNull(pcvs, "ATX should return non-null list");
        assertTrue(pcvs.size() > 0, "ATX should load at least one antenna");
    }

    @Test
    @DisplayName("SP3+CLK combined loading for PPP")
    void testSp3ClkCombined() {
        Assumptions.assumeTrue(
                Files.exists(Paths.get(SP3_FILE)) && Files.exists(Paths.get(CLK_FILE)),
                "SP3 or CLK file not available");

        Nav nav = new Nav();
        Sp3Reader.readsp3(SP3_FILE, nav, 0);
        ClkReader.readclk(CLK_FILE, nav);

        log.info("SP3+CLK combined: ne={}, nc={}", nav.ne, nav.nc);
        assertTrue(nav.ne > 0, "SP3 should load");
        assertTrue(nav.nc > 0, "CLK should load");
    }
}