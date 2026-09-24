package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

class RtkOptimizationsBootstrapTest {

    @Test
    void testComputeSuccessRateHighConfidence() {
        double[] a = {1.001, 2.002, 3.001};
        double[] Q = new double[9];
        Q[0] = 0.01; Q[4] = 0.01; Q[8] = 0.01;
        double rate = RtkOptimizationsBootstrap.computeSuccessRate(a, Q, 3);
        assertTrue(rate > 0.95, "Near-integer ambiguities should have high success rate, got " + rate);
    }

    @Test
    void testComputeSuccessRateLowConfidence() {
        double[] a = {1.45, 2.55, 3.35};
        double[] Q = new double[9];
        Q[0] = 0.25; Q[4] = 0.25; Q[8] = 0.25;
        double rate = RtkOptimizationsBootstrap.computeSuccessRate(a, Q, 3);
        assertTrue(rate < 0.5, "Far-from-integer ambiguities should have low success rate, got " + rate);
    }

    @Test
    void testComputeSuccessRateSingleAmbiguity() {
        double[] a = {5.01};
        double[] Q = {0.01};
        double rate = RtkOptimizationsBootstrap.computeSuccessRate(a, Q, 1);
        assertTrue(rate > 0.9, "Single near-integer ambiguity should have high success rate");
    }

    @Test
    void testComputeSuccessRateEmpty() {
        double rate = RtkOptimizationsBootstrap.computeSuccessRate(new double[0], new double[0], 0);
        assertEquals(0.0, rate, 1e-10);
    }

    @Test
    void testErfcApprox() {
        double val0 = RtkOptimizationsBootstrap.erfcApprox(0.0);
        assertEquals(1.0, val0, 1e-10, "erfc(0) should be 1");

        double valLarge = RtkOptimizationsBootstrap.erfcApprox(6.0);
        assertTrue(valLarge < 1e-15, "erfc(6) should be near 0");

        double val1 = RtkOptimizationsBootstrap.erfcApprox(1.0);
        assertTrue(val1 > 0.1 && val1 < 0.2, "erfc(1) should be ~0.157");
    }

    @Test
    void testValidateFixDefaultOff() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableBootstrapping = false;
        double[] bias = {1.0, 2.0};
        boolean result = RtkOptimizationsBootstrap.validateFix(rtk, bias, 2);
        assertTrue(result, "When bootstrapping disabled, validateFix should return true");
    }

    @Test
    void testValidateFixRejectsBadAmbiguities() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableBootstrapping = true;
        rtk.rtkConfig.bootstrappingMinSuccess = 0.99;

        double[] bias = new double[6];
        bias[0] = 1.45;
        bias[2] = 2.55;
        bias[4] = 3.35;

        boolean result = RtkOptimizationsBootstrap.validateFix(rtk, bias, 3);
        assertFalse(result, "Bad ambiguities should be rejected");
    }
}