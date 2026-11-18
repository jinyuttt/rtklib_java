package org.rtklib.java.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [Java扩展] `.pos` 文本输出语义测试。
 *
 * 钉死三条不变量：
 *
 *   - 表头列集合随 {@code posMask} 切换（LLH / ECEF / ENU）；
 *   - 行与表头同源：LLH 行取 {@code covenu} 后的 ENU 标准差，ECEF 行取 {@code soltocov} 后的 ECEF 标准差；
 *   - 互协方差列顺序与 C 版 {@code outpos()} 一致：{@code sdne, sdeu, sdun} = {@code c12, c31, c23}。
 */
class SolDataPosFileTest {

    private static Sol solAtLat0Lon0() {
        Sol sol = new Sol();
        sol.time = new GTime();
        sol.rr = new double[]{6378137.0, 0.0, 0.0, 0, 0, 0};
        sol.stat = (byte) SolutionStatus.FIX.code;
        sol.ns = (byte) 12;
        sol.qr = new float[]{1.0e-4f, 4.0e-4f, 9.0e-4f, 1.6e-5f, 2.5e-5f, 3.6e-5f};
        return sol;
    }

    /**
     * 解析一行 .pos 记录的数值列。
     *
     * 列序：date, time, v1, v2, v3, Q, ns, sdn, sde, sdu, sdne, sdeu, sdun, age, ratio, gdop, pdop, hdop, vdop
     */
    private static double[] parseStdColumns(String line) {
        String[] tokens = line.trim().split("\\s+");
        assertEquals(19, tokens.length, "列数应为 19: " + line);
        double[] std = new double[6];
        for (int i = 0; i < 6; i++) {
            std[i] = Double.parseDouble(tokens[7 + i]);
        }
        return std;
    }

    @Test
    void headerFollowsPosMask() {
        String llh = SolData.posFileHeader("T", PrcOpt.POS_LLH);
        assertTrue(llh.startsWith("# T\n"));
        assertTrue(llh.contains("lat(deg)"));
        assertTrue(llh.contains("sdn(m)"));
        assertFalse(llh.contains("x-ecef"));

        String ecef = SolData.posFileHeader(null, PrcOpt.POS_ECEF);
        assertTrue(ecef.contains("x-ecef"));
        assertTrue(ecef.contains("sdx(m)"));
        assertFalse(ecef.contains("lat(deg)"));

        String enu = SolData.posFileHeader(null, PrcOpt.POS_ENU);
        assertTrue(enu.contains("e(m)"));
        assertTrue(enu.contains("sde(m)"));
    }

    @Test
    void llhLineUsesEnuCovarianceInCVersionOrder() {
        SolData solData = new SolData(solAtLat0Lon0(), PrcOpt.POS_LLH);
        double[] std = parseStdColumns(SolData.posFileLine(solData));

        /* lat=0, lon=0 时 E=(0,1,0), N=(0,0,1), U=(1,0,0)：
         * Q[4]=σy²、Q[0]=σz²、Q[8]=σx²、Q[1]=σyz、Q[2]=σxy、Q[5]=σzx */
        assertEquals(0.03, std[0], 1e-4, "sdn=σn=sqrt(σy²)");
        assertEquals(0.02, std[1], 1e-4, "sde=σe=sqrt(σz²)");
        assertEquals(0.01, std[2], 1e-4, "sdu=σu=sqrt(σx²)");
        assertEquals(0.005, std[3], 1e-4, "sdne=sqrt(|σyz|)");
        assertEquals(0.004, std[4], 1e-4, "sdeu=sqrt(|σxy|)=c31，不得与 sdun 互换");
        assertEquals(0.006, std[5], 1e-4, "sdun=sqrt(|σzx|)=c23，不得与 sdeu 互换");
    }

    @Test
    void ecefLineUsesEcefCovariance() {
        SolData solData = new SolData(solAtLat0Lon0(), PrcOpt.POS_ECEF);
        double[] std = parseStdColumns(SolData.posFileLine(solData));

        assertEquals(0.01, std[0], 1e-4, "sdx=sqrt(σx²)");
        assertEquals(0.02, std[1], 1e-4, "sdy=sqrt(σy²)");
        assertEquals(0.03, std[2], 1e-4, "sdz=sqrt(σz²)");
        assertEquals(0.004, std[3], 1e-4, "sdxy=sqrt(|σxy|)");
        assertEquals(0.005, std[4], 1e-4, "sdyz=sqrt(|σyz|)");
        assertEquals(0.006, std[5], 1e-4, "sdzx=sqrt(|σzx|)");
    }

    @Test
    void llhLineTakesPrecedenceOverEcef() {
        String llhLine = SolData.posFileLine(new SolData(solAtLat0Lon0(), PrcOpt.POS_ECEF | PrcOpt.POS_LLH));
        String ecefLine = SolData.posFileLine(new SolData(solAtLat0Lon0(), PrcOpt.POS_ECEF));

        assertNotEquals(ecefLine, llhLine, "默认 mask 应输出 LLH 行");
        String[] llhTokens = llhLine.trim().split("\\s+");
        String[] ecefTokens = ecefLine.trim().split("\\s+");
        assertEquals(0.0, Double.parseDouble(llhTokens[2]), 1e-6, "LLH 行纬度单位为度");
        assertEquals(6378137.0, Double.parseDouble(ecefTokens[2]), 1e-3, "ECEF 行 x 单位为米");
    }

    @Test
    void missingPositionYieldsEmptyLine() {
        SolData solData = new SolData(solAtLat0Lon0(), PrcOpt.POS_ENU);
        assertEquals("", SolData.posFileLine(solData), "ENU 无基站位置时不得输出残行");
        assertEquals("", SolData.posFileLine(null));
    }
}