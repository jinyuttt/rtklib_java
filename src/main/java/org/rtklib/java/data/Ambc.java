package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * Ambiguity control class.
 * Aligned with RTKLIB ambc_t.
 */
public class Ambc {
    public GTime[] epoch;
    public int[] n;
    public double[] LC;
    public double[] LCv;
    public int fixcnt;
    public char[] flags;

    public Ambc() {
        this.epoch = new GTime[4];
        for (int i = 0; i < 4; i++) this.epoch[i] = new GTime();
        this.n = new int[4];
        this.LC = new double[4];
        this.LCv = new double[4];
        this.fixcnt = 0;
        this.flags = new char[Constants.MAXSAT];
    }
}