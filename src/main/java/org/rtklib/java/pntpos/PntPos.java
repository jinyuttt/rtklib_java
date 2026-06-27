package org.rtklib.java.pntpos;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

/**
 * Single Point Positioning.
 * Aligned with RTKLIB pntpos.c.
 * Public entry point: pntpos().
 */
public final class PntPos {
    private PntPos() {
        // utility class
    }

    /**
     * Compute single point positioning solution.
     * @param obs  Observation data (n)
     * @param n    Number of observations
     * @param nav  Navigation data
     * @param opt  Processing options
     * @param sol  Output solution
     * @param azel Output azimuth/elevation (n*2)
     * @param ssat Output satellite status
     * @return status (1: ok, 0: failure)
     */
    public static int pntpos(Obsd[] obs, int n, Nav nav, PrcOpt opt, Sol sol, double[] azel, Ssat[] ssat) {
        return 0; // placeholder for full SPP implementation
    }
}