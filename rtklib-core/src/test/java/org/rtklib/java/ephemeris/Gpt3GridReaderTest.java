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
    @DisplayName("重写后与参考实现数值等价（12 点 × P/T/Q/ah/aw）")
    void matchesReferenceBaseline() {
        // 期望值取自按官方参考实现对齐的旧版实现输出，重写后须逐点保持一致
        double[][] cases = {
                {22.4347, 114.3353, 95.0, 157, 995.9380478949, 299.5067444982, 27.7595352031, 0.0012793659, 0.0006460004},
                {22.4347, 114.3353, 95.0, 351, 1008.4941804750, 290.3794699321, 13.4946077799, 0.0012652563, 0.0005644065},
                {39.9, 116.4, 60.0, 100, 1008.3039926738, 286.3402027012, 5.4016898188, 0.0012272896, 0.0005407595},
                {64.1, -21.9, 40.0, 200, 1013.6751255207, 282.8944610731, 10.5899686815, 0.0012371256, 0.0005696536},
                {-33.9, 151.2, 30.0, 20, 1011.3986138882, 296.6088603539, 18.7895222581, 0.0012644818, 0.0005710884},
                {0.0, 0.0, 0.0, 1, 1012.7330167855, 299.6561270896, 27.2992089142, 0.0012752659, 0.0006144182},
                {51.5, -0.1, 30.0, 180, 1018.2693547703, 289.8855095490, 14.3665038935, 0.0012484387, 0.0005691400},
                {-77.8, 166.7, 20.0, 60, 982.3198826234, 261.5374241135, 1.6740856100, 0.0011793225, 0.0005475263},
                {35.7, 139.7, 50.0, 250, 1010.4805133820, 297.7434697839, 23.5973029060, 0.0012744077, 0.0006233582},
                {-1.3, 36.8, 1600.0, 300, 840.0158980518, 290.9963679023, 14.4511991875, 0.0012729441, 0.0005439599},
                {45.0, 359.9, 0.0, 365, 1025.9682665714, 280.8775181066, 8.8954714255, 0.0012212109, 0.0005341765},
                {45.0, -179.99, 0.0, 365, 1001.0064599390, 277.3634781368, 6.3447807578, 0.0012117609, 0.0005298873}
        };
        final double tolerance = 1.0E-8;
        for (double[] c : cases) {
            double[] v = Gpt3GridReader.interpolateGpt3(nav.gpt3Grid, c[0], c[1], c[2], c[3]);
            assertNotNull(v);
            assertEquals(c[4], v[0], tolerance, "P(hPa) 偏差 lat=" + c[0] + " lon=" + c[1]);
            assertEquals(c[5], v[1], tolerance, "T(K) 偏差 lat=" + c[0] + " lon=" + c[1]);
            assertEquals(c[6], v[2], tolerance, "Q(hPa) 偏差 lat=" + c[0] + " lon=" + c[1]);
            assertEquals(c[7], v[3], tolerance, "ah 偏差 lat=" + c[0] + " lon=" + c[1]);
            assertEquals(c[8], v[4], tolerance, "aw 偏差 lat=" + c[0] + " lon=" + c[1]);
        }
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