package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

public class Pcv {
    public int sat;
    public String type;
    public String code;
    public GTime ts;
    public GTime te;
    public double[][] off;
    public double[][] var;

    public Pcv() {
        this.sat = 0;
        this.type = "";
        this.code = "";
        this.ts = new GTime();
        this.te = new GTime();
        this.off = new double[Constants.NFREQ][3];
        this.var = new double[Constants.NFREQ][19];
    }

    public Pcv(Pcv other) {
        this.sat = other.sat;
        this.type = other.type;
        this.code = other.code;
        this.ts = new GTime(other.ts);
        this.te = new GTime(other.te);
        this.off = new double[Constants.NFREQ][3];
        this.var = new double[Constants.NFREQ][19];
        for (int i = 0; i < Constants.NFREQ; i++) {
            System.arraycopy(other.off[i], 0, this.off[i], 0, 3);
            System.arraycopy(other.var[i], 0, this.var[i], 0, 19);
        }
    }
}