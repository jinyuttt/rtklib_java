package org.rtklib.java.data;

import java.io.Serializable;

public class SbsSatP implements Serializable {
    private static final long serialVersionUID = 1L;
    public int sat;
    public SbsFCorr fcorr;
    public SbsLCorr lcorr;

    public SbsSatP() {
        this.sat = 0;
        this.fcorr = new SbsFCorr();
        this.lcorr = new SbsLCorr();
    }
}