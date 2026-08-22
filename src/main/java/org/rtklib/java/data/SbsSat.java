package org.rtklib.java.data;

import java.io.Serializable;
import org.rtklib.java.constants.Constants;

public class SbsSat implements Serializable {
    private static final long serialVersionUID = 1L;
    public int iodp;
    public int nsat;
    public int tlat;
    public SbsSatP[] sat;

    public SbsSat() {
        this.iodp = 0;
        this.nsat = 0;
        this.tlat = 0;
        this.sat = new SbsSatP[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.sat[i] = new SbsSatP();
        }
    }
}