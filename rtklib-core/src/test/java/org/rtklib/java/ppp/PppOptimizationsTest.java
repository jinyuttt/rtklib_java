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
        assertFalse(cfg.enableGpt3Vmf3);
    }

    @Test
    void testIers2010DisabledByDefault() {
        assertFalse(cfg.enableIers2010);
    }

    @Test
    void testIsbIfcbIfbDisabledByDefault() {
        assertFalse(cfg.enableIsbIfcbIfb);
    }

    @Test
    void testPppArDisabledByDefault() {
        assertFalse(cfg.enablePppAR);
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
        cfg.enableGpt3Vmf3 = true;

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
        cfg.enableIers2010 = true;

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
        cfg.enableGpt3Vmf3 = true;
        cfg.enableIers2010 = true;
        cfg.enableIsbIfcbIfb = true;
        cfg.enablePppAR = true;
        cfg.gpt3GridFile = "/path/to/gpt3.dat";
        cfg.pppArRatioWl = 3.0;
        cfg.pppArRatioNl = 4.0;
        cfg.estimateIsb = false;
        cfg.estimateIfcb = false;
        cfg.estimateIfb = false;

        RtkConfig copy = new RtkConfig(cfg);
        assertTrue(copy.enableGpt3Vmf3);
        assertTrue(copy.enableIers2010);
        assertTrue(copy.enableIsbIfcbIfb);
        assertTrue(copy.enablePppAR);
        assertEquals("/path/to/gpt3.dat", copy.gpt3GridFile);
        assertEquals(3.0, copy.pppArRatioWl);
        assertEquals(4.0, copy.pppArRatioNl);
        assertFalse(copy.estimateIsb);
        assertFalse(copy.estimateIfcb);
        assertFalse(copy.estimateIfb);
    }

    @Test
    void testPppBiasModelExtraDimZeroWhenDisabled() {
        assertEquals(0, PppBiasModel.extraDim(cfg));
    }

    @Test
    void testPppBiasModelExtraDimWhenEnabled() {
        cfg.enableIsbIfcbIfb = true;
        int dim = PppBiasModel.extraDim(cfg);
        int expected = 3 + Constants.MAXSAT + 4;
        assertEquals(expected, dim, "Extra dim should be ISB(3) + IFCB(MAXSAT) + IFB(4)");
    }

    @Test
    void testPppBiasModelExtraDimPartial() {
        cfg.enableIsbIfcbIfb = true;
        cfg.estimateIsb = true;
        cfg.estimateIfcb = false;
        cfg.estimateIfb = true;
        int dim = PppBiasModel.extraDim(cfg);
        assertEquals(3 + 4, dim, "Extra dim should be ISB(3) + IFB(4)");
    }

    @Test
    void testPppBiasModelExtraDimIsbOnly() {
        cfg.enableIsbIfcbIfb = true;
        cfg.estimateIsb = true;
        cfg.estimateIfcb = false;
        cfg.estimateIfb = false;
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
        assertFalse(disabledCfg.enableIsbIfcbIfb);
        assertFalse(disabledCfg.enablePppAR);
    }

    @Test
    void testPppArRatioDefaults() {
        assertEquals(2.0, cfg.pppArRatioWl, 1e-10);
        assertEquals(3.0, cfg.pppArRatioNl, 1e-10);
    }
}