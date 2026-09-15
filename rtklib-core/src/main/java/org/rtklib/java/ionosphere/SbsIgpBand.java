package org.rtklib.java.ionosphere;

import java.io.Serializable;

public class SbsIgpBand implements Serializable {
    private static final long serialVersionUID = 1L;
    public short x;
    public short[] y;
    public short bits;
    public short bite;

    public SbsIgpBand(short x, short[] y, short bits, short bite) {
        this.x = x;
        this.y = y;
        this.bits = bits;
        this.bite = bite;
    }
}