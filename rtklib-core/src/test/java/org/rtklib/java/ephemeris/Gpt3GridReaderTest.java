package org.rtklib.java.ephemeris;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.data.Nav;
import org.rtklib.java.TestDataConfig;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gpt3GridReader 对真实 gpt3_5.grd（TU Wien 官方文件）的解析与插值校验。
 * 校验点取香港 HKWS（lat≈22.4347°, lon≈114.3353°, 椭高≈95m），年积日 157（6月初），
 * 热带湿润气候的物理量级：p≈1005hPa, T≈28-30°C, e≈30-36hPa, ah≈0.00125, aw≈0.00055。
 */
class Gpt3GridReaderTest {

    private static final double HKWS_LAT = 22.4347;
    private static final double HKWS_LON = 114.3353;
    private static final double HKWS_HELL = 95.0;
    private static final int DOY = 157; // 2018-06-06

    private static Nav nav;

    @BeforeAll
    static void loadGrid() {
        String path = TestDataConfig.getTestDataDir() + "/product/gpt3/gpt3_5.grd";
        Assumptions.assumeTrue(Files.exists(Paths.get(path)), "gpt3_5.grd not available: " + path);
        nav = new Nav();
        assertTrue(Gpt3GridReader.readGpt3Grid(path, nav));
    }

    @Test
    @DisplayName("网格文件结构：2592点×37列")
    void gridStructure() {
        assertNotNull(nav.gpt3Grid);
        assertEquals(Gpt3GridReader.GPT3_NPOINTS, nav.gpt3Grid.length);
        assertTrue(nav.gpt3GridLoaded);
        // 首行应为 lat=87.5°N 格元：气压年均值量级 101325Pa 附近（极地冬夏季有差异，取宽松界）
        double p0 = nav.gpt3Grid[0][0]; // col 0 = p a0
        assertTrue(p0 > 90000 && p0 < 105000, "北极格点气压年均值应接近海平面 Pa, got " + p0);
    }

    @Test
    @DisplayName("HKWS 点插值：物理量级正确")
    void interpolateHkws() {
        double[] v = Gpt3GridReader.interpolateGpt3(nav.gpt3Grid, HKWS_LAT, HKWS_LON, HKWS_HELL, DOY);
        assertNotNull(v);
        System.out.printf("HKWS doy=%d: p=%.2f hPa, T=%.2f K (%.1f C), e=%.2f hPa, ah=%.6f, aw=%.6f%n",
                DOY, v[0], v[1], v[1] - 273.15, v[2], v[3], v[4]);

        assertTrue(v[0] > 980 && v[0] < 1030, "p(hPa) 越界: " + v[0]);
        assertTrue(v[1] > 295 && v[1] < 312, "T(K) 越界: " + v[1]);
        assertTrue(v[2] > 20 && v[2] < 45, "e(hPa) 越界: " + v[2]);
        assertTrue(v[3] > 1.0E-3 && v[3] < 1.6E-3, "ah 越界: " + v[3]);
        assertTrue(v[4] > 4.0E-4 && v[4] < 8.0E-4, "aw 越界: " + v[4]);
    }

    @Test
    @DisplayName("年周期变化：6月与12月水汽压显著不同")
    void annualVariation() {
        double[] jun = Gpt3GridReader.interpolateGpt3(nav.gpt3Grid, HKWS_LAT, HKWS_LON, HKWS_HELL, 157);
        double[] dec = Gpt3GridReader.interpolateGpt3(nav.gpt3Grid, HKWS_LAT, HKWS_LON, HKWS_HELL, 351);
        assertTrue(dec[2] < jun[2] * 0.6, "冬季 e 应显著低于夏季: jun=" + jun[2] + " dec=" + dec[2]);
        assertTrue(Math.abs(dec[1] - jun[1]) > 5.0, "季节温差应超过 5K: jun=" + jun[1] + " dec=" + dec[1]);
    }

    @Test
    @DisplayName("极区最近邻不越界")
    void polarNearest() {
        double[] north = Gpt3GridReader.interpolateGpt3(nav.gpt3Grid, 89.9, 45.0, 0.0, DOY);
        double[] south = Gpt3GridReader.interpolateGpt3(nav.gpt3Grid, -89.9, -179.0, 0.0, DOY);
        assertNotNull(north);
        assertNotNull(south);
        assertTrue(north[0] > 900 && north[0] < 1050, "北极 p(hPa): " + north[0]);
        assertTrue(south[0] > 900 && south[0] < 1050, "南极 p(hPa): " + south[0]);
    }
}
