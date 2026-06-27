package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * SNR mask class.
 * Aligned with RTKLIB snrmask_t.
 */
public class SnrMask {
    public int ena0;
    public int ena1;
    public double[] mask;
    public static final int NFREQ = Constants.MAXFREQ;
    public static final int NROW = 9;

    public SnrMask() {
        this.ena0 = 0;
        this.ena1 = 0;
        this.mask = new double[NFREQ * NROW];
    }
}