package org.rtklib.java.cssr;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.common.BitUtils;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Ssr;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CompactSsrDecoder Test")
public class CompactSsrDecoderTest {

    private static CompactSsrDecoder decoder;
    private static Nav nav;

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] data = new byte[len];
        for (int i = 0; i < len; i++) {
            data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return data;
    }

    @BeforeAll
    static void setup() {
        decoder = new CompactSsrDecoder();
        decoder.setWeek(2200);
        nav = new Nav();

        String maskHex = "fe9100e105008400000000040000";
        byte[] maskData = hexToBytes(maskHex);
        int ret1 = decoder.decodeCssr(maskData, 0, nav);
        assertTrue(ret1 > 0, "Mask decode should succeed");

        String orbHex = "fe92000503d8064fe70320";
        byte[] orbData = hexToBytes(orbHex);
        int ret2 = decoder.decodeCssr(orbData, 0, nav);
        assertTrue(ret2 > 0, "Orbit decode should succeed");

        String clkHex = "fe93000507ed40";
        byte[] clkData = hexToBytes(clkHex);
        int ret3 = decoder.decodeCssr(clkData, 0, nav);
        assertTrue(ret3 > 0, "Clock decode should succeed");

        String cbiasHex = "fe9400050032";
        byte[] cbiasData = hexToBytes(cbiasHex);
        int ret4 = decoder.decodeCssr(cbiasData, 0, nav);
        assertTrue(ret4 > 0, "Cbias decode should succeed");
    }

    @Test
    @DisplayName("1. Mask: satellite and signal count")
    void testMaskSatSig() {
        assertEquals(1, decoder.nsatN, "nsatN should be 1");
        assertEquals(1, decoder.satN.length, "satN length should be 1");
        assertEquals(1, decoder.satN[0], "satN[0] should be 1 (GPS PRN1)");
        assertEquals(1, decoder.nsigTotal, "nsigTotal should be 1");
        assertEquals(0, decoder.iodssr, "iodssr should be 0");
    }

    @Test
    @DisplayName("2. Mask: tow")
    void testMaskTow() {
        assertEquals(3600.0, decoder.tow0, 1.0, "tow0 should be 3600");
        assertEquals(3600.0, decoder.tow, 1.0, "tow should be 3600");
    }

    @Test
    @DisplayName("3. Orbit: delta orbit values")
    void testOrbitDorb() {
        int sat = 1;
        double[] dorb = decoder.lc[0].dorb.get(sat);
        assertNotNull(dorb, "dorb should not be null for sat 1");
        assertEquals(3, dorb.length, "dorb length should be 3");
        assertEquals(0.16, dorb[0], 0.001, "dorb[0] (radial) should be ~0.16");
        assertEquals(-0.32, dorb[1], 0.01, "dorb[1] (along) should be ~-0.32");
        assertEquals(1.28, dorb[2], 0.01, "dorb[2] (cross) should be ~1.28");
    }

    @Test
    @DisplayName("4. Orbit: IODE")
    void testOrbitIode() {
        Integer iode = decoder.lc[0].iode.get(1);
        assertNotNull(iode, "iode should not be null for sat 1");
        assertEquals(123, iode, "iode should be 123");
    }

    @Test
    @DisplayName("5. Clock: delta clock value")
    void testClockDclk() {
        Double dclk = decoder.lc[0].dclk.get(1);
        assertNotNull(dclk, "dclk should not be null for sat 1");
        assertEquals(-0.48, dclk, 0.001, "dclk should be ~-0.48");
    }

    @Test
    @DisplayName("6. Code bias: cbias value")
    void testCbias() {
        var cbiasMap = decoder.lc[0].cbias.get(1);
        assertNotNull(cbiasMap, "cbias map should not be null for sat 1");
        assertFalse(cbiasMap.isEmpty(), "cbias map should not be empty");
    }

    @Test
    @DisplayName("7. Correction status: mask+orbit+clock+cbias bits set")
    void testCstat() {
        int cstat = decoder.lc[0].cstat;
        assertEquals(0x0F, cstat & 0x0F, "cstat lower 4 bits should be 0x0F (mask+orb+clk+cbias)");
    }

    @Test
    @DisplayName("8. Nav.ssr: orbit written to Nav")
    void testNavSsrOrbit() {
        Ssr ssr = nav.ssr[0];
        assertNotNull(ssr, "nav.ssr[0] should not be null after orbit decode");
        assertEquals(0.16, ssr.deph[0], 0.001, "ssr.deph[0] should be ~0.16");
        assertEquals(-0.32, ssr.deph[1], 0.01, "ssr.deph[1] should be ~-0.32");
        assertEquals(1.28, ssr.deph[2], 0.01, "ssr.deph[2] should be ~1.28");
    }

    @Test
    @DisplayName("9. Nav.ssr: clock written to Nav")
    void testNavSsrClock() {
        Ssr ssr = nav.ssr[0];
        assertNotNull(ssr, "nav.ssr[0] should not be null after clock decode");
        assertEquals(-0.48, ssr.dclk[0], 0.001, "ssr.dclk[0] should be ~-0.48");
    }

    @Test
    @DisplayName("10. CssrMessageType enum")
    void testCssrMessageType() {
        assertEquals(CssrMessageType.MASK, CssrMessageType.fromValue(1));
        assertEquals(CssrMessageType.ORBIT, CssrMessageType.fromValue(2));
        assertEquals(CssrMessageType.CLOCK, CssrMessageType.fromValue(3));
        assertEquals(CssrMessageType.CBIAS, CssrMessageType.fromValue(4));
        assertEquals(CssrMessageType.PBIAS, CssrMessageType.fromValue(5));
        assertEquals(CssrMessageType.STEC, CssrMessageType.fromValue(8));
        assertEquals(CssrMessageType.GRID, CssrMessageType.fromValue(9));
        assertNull(CssrMessageType.fromValue(99), "Unknown value should return null");
    }

    @Test
    @DisplayName("11. GridDefinition: find grid index")
    void testGridDefinition() {
        GridDefinition gd = new GridDefinition();
        gd.addGridPoint(0, 1, 35.0, 135.0, 0.0);
        gd.addGridPoint(0, 2, 35.5, 135.5, 0.0);
        gd.addGridPoint(1, 3, 40.0, 140.0, 0.0);

        double[] pos = {Math.toRadians(35.01), Math.toRadians(135.01), 0.0};
        int inet = gd.findGridIndex(pos);
        assertEquals(0, inet, "Should find network 0");
        assertTrue(gd.ngrid >= 1, "Should find at least 1 grid point");
        assertEquals(1, gd.gridIndex[0], "Nearest grid index should be 1");
    }

    @Test
    @DisplayName("12. LocalCorr: set/get T0")
    void testLocalCorrT0() {
        LocalCorr lc = new LocalCorr();
        org.rtklib.java.data.GTime t = new org.rtklib.java.data.GTime();
        lc.setT0(1, 0, t);
        org.rtklib.java.data.GTime t0 = lc.getT0(1, 0);
        assertNotNull(t0, "T0 should be set");
        assertNull(lc.getT0(99, 0), "Non-existent T0 should be null");
    }

    @Test
    @DisplayName("13. BitUtils: getbitu/getbits roundtrip")
    void testBitUtilsRoundtrip() {
        byte[] buf = new byte[10];
        BitUtils.setbitu(buf, 0, 12, 4073);
        assertEquals(4073, BitUtils.getbitu(buf, 0, 12));

        BitUtils.setbitu(buf, 12, 4, 1);
        assertEquals(1, BitUtils.getbitu(buf, 12, 4));

        BitUtils.setbits(buf, 16, 15, -300);
        assertEquals(-300, BitUtils.getbits(buf, 16, 15));

        BitUtils.setbits(buf, 31, 11, 50);
        assertEquals(50, BitUtils.getbits(buf, 31, 11));
    }

    @Test
    @DisplayName("14. L6 frame: preamble check")
    void testL6Preamble() {
        CompactSsrDecoder dec2 = new CompactSsrDecoder();
        byte[] badMsg = new byte[250];
        int ret = dec2.decodeL6Msg(badMsg, 0);
        assertEquals(-1, ret, "Bad preamble should return -1");
    }

    @Test
    @DisplayName("15. chkStat: incomplete corrections")
    void testChkStatIncomplete() {
        CompactSsrDecoder dec3 = new CompactSsrDecoder();
        assertFalse(dec3.chkStat(), "Empty decoder should fail chkStat");
    }
}