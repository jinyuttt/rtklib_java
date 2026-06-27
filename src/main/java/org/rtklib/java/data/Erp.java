package org.rtklib.java.data;

/**
 * Earth Rotation Parameter (ERP) data.
 * Aligned with RTKLIB erp_t / erpd_t structures.
 */
public class Erp {

    /** Single ERP record */
    public static class Erpd {
        /** Modified Julian Date (days) */
        public double mjd;
        /** Pole offset x (rad) */
        public double xp;
        /** Pole offset y (rad) */
        public double yp;
        /** Pole offset rate x (rad/day) */
        public double xpr;
        /** Pole offset rate y (rad/day) */
        public double ypr;
        /** UT1-UTC (s) */
        public double ut1_utc;
        /** Length of day (s/day) */
        public double lod;
    }

    /** Number of ERP data records */
    public int n;
    /** Maximum number of ERP data records */
    public int nmax;
    /** ERP data array */
    public Erpd[] data;

    public Erp() {
        this.n = 0;
        this.nmax = 0;
        this.data = null;
    }
}