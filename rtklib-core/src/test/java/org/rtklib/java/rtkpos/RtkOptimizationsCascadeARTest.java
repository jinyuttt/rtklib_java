package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

class RtkOptimizationsCascadeARTest {

    @Test
    void testCascadeAmbFixDefaultOff() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableCascadeAR = false;
        double[] bias = new double[10];
        double[] xa = new double[10];
        int result = RtkOptimizationsCascadeAR.cascadeAmbFix(rtk, bias, xa, 1, 0, 0, null);
        assertEquals(-1, result, "When disabled, should return -1");
    }

    @Test
    void testCascadeAmbFixSingleFreqSkips() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableCascadeAR = true;
        rtk.opt.nf = 1;
        rtk.opt.ionoopt = Constants.IONOOPT_BRDC;
        double[] bias = new double[10];
        double[] xa = new double[10];
        int result = RtkOptimizationsCascadeAR.cascadeAmbFix(rtk, bias, xa, 1, 0, 0, null);
        assertEquals(-1, result, "Single frequency should skip cascade AR");
    }

    @Test
    void testLevelConstants() {
        assertEquals(0, RtkOptimizationsCascadeAR.LEVEL_NONE);
        assertEquals(1, RtkOptimizationsCascadeAR.LEVEL_EWL);
        assertEquals(2, RtkOptimizationsCascadeAR.LEVEL_WL);
        assertEquals(3, RtkOptimizationsCascadeAR.LEVEL_NL);
    }

    @Test
    void testEwlWavelength() {
        double wlGps = RtkOptimizationsCascadeAR.ewlWavelength(Constants.SYS_GPS, null);
        assertTrue(wlGps > 0, "GPS EWL wavelength should be positive");
        assertTrue(wlGps > 0.5, "EWL wavelength should be > 0.5m (wide lane)");

        double wlBds = RtkOptimizationsCascadeAR.ewlWavelength(Constants.SYS_CMP, null);
        assertTrue(wlBds > 0, "BDS EWL wavelength should be positive");
    }

    @Test
    void testWlWavelength() {
        double wlGps = RtkOptimizationsCascadeAR.wlWavelength(Constants.SYS_GPS, null);
        assertTrue(wlGps > 0, "GPS WL wavelength should be positive");

        double wlGal = RtkOptimizationsCascadeAR.wlWavelength(Constants.SYS_GAL, null);
        assertTrue(wlGal > 0, "Galileo WL wavelength should be positive");
    }

    @Test
    void testNlWavelength() {
        double nlGps = RtkOptimizationsCascadeAR.nlWavelength(Constants.SYS_GPS, 0, null);
        assertTrue(nlGps > 0, "GPS NL wavelength should be positive");
        assertTrue(nlGps < 0.3, "NL wavelength should be < 0.3m (narrow lane)");

        double nlBds = RtkOptimizationsCascadeAR.nlWavelength(Constants.SYS_CMP, 0, null);
        assertTrue(nlBds > 0, "BDS NL wavelength should be positive");
    }
}