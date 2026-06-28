package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

public class PepH {
    public GTime time;
    public int index;
    public double[][] pos;
    public float[][] std;
    public double[][] vel;
    public float[][] vst;
    public float[][] cov;
    public float[][] vco;

    public PepH() {
        this.time = new GTime();
        this.index = 0;
        this.pos = new double[Constants.MAXSAT][4];
        this.std = new float[Constants.MAXSAT][4];
        this.vel = new double[Constants.MAXSAT][4];
        this.vst = new float[Constants.MAXSAT][4];
        this.cov = new float[Constants.MAXSAT][3];
        this.vco = new float[Constants.MAXSAT][3];
    }
}