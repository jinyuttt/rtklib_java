package org.rtklib.java.data;

import java.io.Serializable;

public class SbsIgp implements Serializable {
    private static final long serialVersionUID = 1L;
    public GTime t0;
    public short lat;
    public short lon;
    public short give;
    public float delay;

    public SbsIgp() {
        this.t0 = new GTime();
        this.lat = 0;
        this.lon = 0;
        this.give = 0;
        this.delay = 0.0f;
    }
}