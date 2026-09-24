package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

class RtkOptimizationsPartialARTest {

    @Test
    void testPartialAmbFixDefaultOff() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enablePartialAR = false;
        double[] bias = new double[10];
        double[] xa = new double[10];
        int[] ix = new int[10];
        int result = RtkOptimizationsPartialAR.partialAmbFix(rtk, bias, xa, ix, 5, 1, 0, 0);
        assertEquals(-1, result, "When disabled, should return -1");
    }

    @Test
    void testPartialAmbFixTooFewAmbiguities() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enablePartialAR = true;
        rtk.rtkConfig.partialArMinSats = 4;
        double[] bias = new double[10];
        double[] xa = new double[10];
        int[] ix = new int[10];
        int result = RtkOptimizationsPartialAR.partialAmbFix(rtk, bias, xa, ix, 3, 1, 0, 0);
        assertEquals(-1, result, "Too few ambiguities should return -1");
    }

    @Test
    void testPartialAmbFixZeroAmbiguities() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enablePartialAR = true;
        double[] bias = new double[10];
        double[] xa = new double[10];
        int[] ix = new int[10];
        int result = RtkOptimizationsPartialAR.partialAmbFix(rtk, bias, xa, ix, 0, 1, 0, 0);
        assertEquals(-1, result, "Zero ambiguities should return -1");
    }
}