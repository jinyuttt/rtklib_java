package org.rtklib.java.ionosphere;

public class SbsIgpBand {
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