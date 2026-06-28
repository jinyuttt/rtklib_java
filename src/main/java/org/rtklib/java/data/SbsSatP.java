package org.rtklib.java.data;

public class SbsSatP {
    public int sat;
    public SbsFCorr fcorr;
    public SbsLCorr lcorr;

    public SbsSatP() {
        this.sat = 0;
        this.fcorr = new SbsFCorr();
        this.lcorr = new SbsLCorr();
    }
}