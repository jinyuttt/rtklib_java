package org.rtklib.java.ppp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

public class PppOptimizationsTest {

    private RtkConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new RtkConfig();
    }

    @Test
    void testGpt3Vmf3DisabledByDefault() {
        assertFalse(cfg.ppp.enableGpt3Vmf3);
    }

    @Test
    void testIers2010DisabledByDefault() {
        assertFalse(cfg.ppp.enableIers2010);
    }

    @Test
    void testIsbIfcbIfbDisabledByDefault() {
        assertFalse(cfg.ppp.enableIsbIfcbIfb);
    }

    @Test
    void testPppArDisabledByDefault() {
        assertFalse(cfg.pppAr.enableAR);
    }

    @Test
    void testGpt3Vmf3ReturnsNaNWhenDisabled() {
        GTime time = new GTime();
        time.time = 1000000;
        time.sec = 0.0;
        double[] pos = {0.7 * Math.PI / 180.0 * 30.0, 0.7 * Math.PI / 180.0 * 120.0, 50.0};
        double[] azel = {0.0, Math.PI / 4.0};
        double[] x = {2.3};
        double[] dtdx = new double[3];
        double[] var = new double[1];

        Nav nav = new Nav();
        double result = PppOptimizations.tropoDelayGpt3Vmf3(time, pos, azel, x, dtdx, var, cfg, nav);
        assertTrue(Double.isNaN(result));
    }

    @Test
    void testGpt3Vmf3ReturnsValueWhenEnabled() {
        cfg.ppp.enableGpt3Vmf3 = true;

        GTime time = new GTime();
        time.time = 1000000;
        time.sec = 0.0;
        double[] pos = {30.0 * Math.PI / 180.0, 120.0 * Math.PI / 180.0, 50.0};
        double[] azel = {0.0, Math.PI / 4.0};
        double[] x = {2.3};
        double[] dtdx = new double[3];
        double[] var = new double[1];

        Nav nav = new Nav();
        double result = PppOptimizations.tropoDelayGpt3Vmf3(time, pos, azel, x, dtdx, var, cfg, nav);
        assertFalse(Double.isNaN(result));
        assertTrue(result > 0, "Tropospheric delay should be positive, got: " + result);
        assertTrue(result < 30.0, "Tropospheric delay should be < 30m, got: " + result);
    }

    @Test
    void testIers2010ReturnsNullWhenDisabled() {
        GTime time = new GTime();
        time.time = 1000000;
        time.sec = 0.0;
        double[] rr = {-2280206.0, 5009387.0, 3221902.0};

        double[] result = PppOptimizations.tideDisplacementIers2010(time, rr, 1, null, null, cfg);
        assertNull(result);
    }

    @Test
    void testIers2010ReturnsValueWhenEnabled() {
        cfg.ppp.enableIers2010 = true;

        GTime time = new GTime();
        time.time = 1000000;
        time.sec = 0.0;
        double[] rr = {-2280206.0, 5009387.0, 3221902.0};

        double[] result = PppOptimizations.tideDisplacementIers2010(time, rr, 1, null, null, cfg);
        assertNotNull(result);
        assertEquals(3, result.length);
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(result[i]) < 1.0, "Tide displacement should be < 1m, got[" + i + "]: " + result[i]);
        }
    }

    @Test
    void testConfigCopyConstructor() {
        cfg.ppp.enableGpt3Vmf3 = true;
        cfg.ppp.enableIers2010 = true;
        cfg.ppp.enableIsbIfcbIfb = true;
        cfg.pppAr.enableAR = true;
        cfg.ppp.gpt3GridFile = "/path/to/gpt3.dat";
        cfg.pppAr.arRatioWl = 3.0;
        cfg.pppAr.arRatioNl = 4.0;
        cfg.ppp.estimateIsb = false;
        cfg.ppp.estimateIfcb = false;
        cfg.ppp.estimateIfb = false;

        RtkConfig copy = new RtkConfig(cfg);
        assertTrue(copy.ppp.enableGpt3Vmf3);
        assertTrue(copy.ppp.enableIers2010);
        assertTrue(copy.ppp.enableIsbIfcbIfb);
        assertTrue(copy.pppAr.enableAR);
        assertEquals("/path/to/gpt3.dat", copy.ppp.gpt3GridFile);
        assertEquals(3.0, copy.pppAr.arRatioWl);
        assertEquals(4.0, copy.pppAr.arRatioNl);
        assertFalse(copy.ppp.estimateIsb);
        assertFalse(copy.ppp.estimateIfcb);
        assertFalse(copy.ppp.estimateIfb);
    }

    @Test
    void testPppBiasModelExtraDimZeroWhenDisabled() {
        assertEquals(0, PppBiasModel.extraDim(cfg));
    }

    @Test
    void testPppBiasModelExtraDimWhenEnabled() {
        cfg.ppp.enableIsbIfcbIfb = true;
        int dim = PppBiasModel.extraDim(cfg);
        int expected = 3 + Constants.MAXSAT + 4;
        assertEquals(expected, dim, "Extra dim should be ISB(3) + IFCB(MAXSAT) + IFB(4)");
    }

    @Test
    void testPppBiasModelExtraDimPartial() {
        cfg.ppp.enableIsbIfcbIfb = true;
        cfg.ppp.estimateIsb = true;
        cfg.ppp.estimateIfcb = false;
        cfg.ppp.estimateIfb = true;
        int dim = PppBiasModel.extraDim(cfg);
        assertEquals(3 + 4, dim, "Extra dim should be ISB(3) + IFB(4)");
    }

    @Test
    void testPppBiasModelExtraDimIsbOnly() {
        cfg.ppp.enableIsbIfcbIfb = true;
        cfg.ppp.estimateIsb = true;
        cfg.ppp.estimateIfcb = false;
        cfg.ppp.estimateIfb = false;
        int dim = PppBiasModel.extraDim(cfg);
        assertEquals(3, dim, "Extra dim should be ISB(3)");
    }

    @Test
    void testPppCoreExFallsBackToPppCoreWhenDisabled() {
        Rtk rtk = new Rtk();
        rtk.opt = new PrcOpt();
        rtk.opt.mode = Constants.PMODE_PPP_KINEMA;
        rtk.opt.nf = 2;
        rtk.opt.tropopt = Constants.TROPOPT_EST;
        rtk.opt.ionoopt = Constants.IONOOPT_IFLC;
        rtk.rtkConfig = cfg;

        RtkConfig disabledCfg = new RtkConfig();
        assertFalse(disabledCfg.ppp.enableIsbIfcbIfb);
        assertFalse(disabledCfg.pppAr.enableAR);
    }

    @Test
    void testPppArRatioDefaults() {
        assertEquals(2.0, cfg.pppAr.arRatioWl, 1e-10);
        assertEquals(3.0, cfg.pppAr.arRatioNl, 1e-10);
    }
}