package org.rtklib.java.data;

import java.io.Serializable;

public class SbsLCorr implements Serializable {
    private static final long serialVersionUID = 1L;
    public GTime t0;
    public int iode;
    public double[] dpos;
    public double[] dvel;
    public double daf0, daf1;

    public SbsLCorr() {
        this.t0 = new GTime();
        this.iode = 0;
        this.dpos = new double[3];
        this.dvel = new double[3];
        this.daf0 = 0.0;
        this.daf1 = 0.0;
    }
}