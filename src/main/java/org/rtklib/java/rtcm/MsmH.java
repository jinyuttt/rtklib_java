package org.rtklib.java.rtcm;

/**
 * MSM (Multiple Signal Message) header structure.
 * Aligned with RTKLIB's msm_h_t struct.
 */
public class MsmH {
    public int nsat;
    public int nsig;
    public int[] sats = new int[64];
    public int[] sigs = new int[32];
    public int[] cellmask = new int[64];
    public int time_s;
    public int clk_str;
    public int clk_ext;
    public int smooth;
    public int tint_s;

    public MsmH() {
        nsat = 0;
        nsig = 0;
        time_s = 0;
        clk_str = 0;
        clk_ext = 0;
        smooth = 0;
        tint_s = 0;
    }
}