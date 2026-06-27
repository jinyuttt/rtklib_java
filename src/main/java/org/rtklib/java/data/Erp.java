package org.rtklib.java.data;

public class Erp {
    public int n, nmax;
    public Erpd[] data;

    public Erp() {
        this.n = 0;
        this.nmax = 0;
        this.data = new Erpd[0];
    }

    public static class Erpd {
        public GTime time;
        public double xp, yp, ut1_utc, lod, xr, yr;
        public double[] eop = new double[14];

        public Erpd() {
            this.time = new GTime();
        }
    }
}