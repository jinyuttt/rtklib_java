package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

class RtkOptimizationsBdsBiasTest {

    private static int bdsSat(int prn) {
        return SatUtils.satno(Constants.SYS_CMP, prn);
    }

    @Test
    void testComputeBdsCodeBiasGeo() {
        int sat = bdsSat(1);
        double[] bias = RtkOptimizationsBdsBias.computeBdsCodeBias(sat, 30.0 * Math.PI / 180.0, 3, null);
        assertEquals(3, bias.length);
        assertTrue(bias[0] < 0, "GEO code bias should be negative, got " + bias[0]);
        assertEquals(-0.58, bias[0], 0.01, "GEO bias should match Wanninger model");
    }

    @Test
    void testComputeBdsCodeBiasIgsoLowElevation() {
        int sat = bdsSat(6);
        double[] bias = RtkOptimizationsBdsBias.computeBdsCodeBias(sat, 10.0 * Math.PI / 180.0, 3, null);
        assertTrue(bias[0] < -0.3, "IGSO at low elevation should have significant bias, got " + bias[0]);
    }

    @Test
    void testComputeBdsCodeBiasIgsoHighElevation() {
        int sat = bdsSat(6);
        double[] bias = RtkOptimizationsBdsBias.computeBdsCodeBias(sat, 60.0 * Math.PI / 180.0, 3, null);
        assertTrue(bias[0] > -0.15, "IGSO at high elevation should have small bias, got " + bias[0]);
    }

    @Test
    void testComputeBdsCodeBiasMeo() {
        int sat = bdsSat(15);
        double[] bias = RtkOptimizationsBdsBias.computeBdsCodeBias(sat, 45.0 * Math.PI / 180.0, 3, null);
        assertTrue(bias[0] < 0, "MEO bias should be negative, got " + bias[0]);
    }

    @Test
    void testComputeBdsCodeBiasNonBdsReturnsZero() {
        int gpsSat = SatUtils.satno(Constants.SYS_GPS, 1);
        double[] zeroBias = new double[3];
        double[] bias = RtkOptimizationsBdsBias.computeBdsCodeBias(gpsSat, 30.0 * Math.PI / 180.0, 3, null);
        assertArrayEquals(zeroBias, bias, 1e-10, "Non-BDS satellite should return zero bias");
    }

    @Test
    void testApplyBdsCodeBiasDefaultOff() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableBdsCodeBias = false;
        Obsd[] obs = new Obsd[1];
        obs[0] = new Obsd();
        obs[0].P = new double[]{100.0, 100.0, 100.0};
        obs[0].L = new double[]{500.0, 500.0, 500.0};
        obs[0].SNR = new float[6];
        obs[0].code = new int[6];
        obs[0].LLI = new int[6];
        obs[0].sat = 0;

        int[] iu = {0};
        int[] ir = {-1};
        double origP = obs[0].P[0];
        RtkOptimizationsBdsBias.applyBdsCodeBias(rtk, obs, iu, ir, 1, 3, null);
        assertEquals(origP, obs[0].P[0], 1e-10, "When disabled, P should not be modified");
    }
}