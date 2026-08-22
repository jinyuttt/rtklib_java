package org.rtklib.java.data;

import java.io.Serializable;

public class SbsFCorr implements Serializable {
    private static final long serialVersionUID = 1L;
    public GTime t0;
    public double prc;
    public double rrc;
    public double dt;
    public int iodf;
    public short udre;
    public short ai;

    public SbsFCorr() {
        this.t0 = new GTime();
        this.prc = 0.0;
        this.rrc = 0.0;
        this.dt = 0.0;
        this.iodf = 0;
        this.udre = 0;
        this.ai = 0;
    }
}