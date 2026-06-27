package org.rtklib.java.data;

/**
 * GLONASS broadcast ephemeris class.
 * Aligned with RTKLIB geph_t structure.
 */
public class Geph {
    /** Satellite number */
    public int sat;
    
    /** Issue of Data, Ephemeris (0-6 bit of tb field) */
    public int iode;
    
    /** Satellite frequency number (-7 to 13) */
    public int frq;
    
    /** Extended SVH (bit 3:ln, bit 2:Cn_a, bit 1:Cn, bit 0:Bn) */
    public int svh;
    
    /** Status flags (bits 7-8:M, bit 6:P4, bit 5:P3, bit 4:P2, bits 2-3:P1, bits 0-1:P) */
    public int flags;
    
    /** Accuracy, age of operation */
    public int sva, age;
    
    /** Epoch of ephemerides (GPST) */
    public GTime toe;
    
    /** Message frame time (GPST) */
    public GTime tof;
    
    /** Satellite position (ECEF) (m) */
    public double[] pos;
    
    /** Satellite velocity (ECEF) (m/s) */
    public double[] vel;
    
    /** Satellite acceleration (ECEF) (m/s^2) */
    public double[] acc;
    
    /** SV clock bias (s) / relative frequency bias */
    public double taun, gamn;
    
    /** Delay between L1 and L2 (s) */
    public double dtaun;

    /**
     * Default constructor.
     */
    public Geph() {
        this.sat = 0;
        this.iode = 0;
        this.frq = 0;
        this.svh = 0;
        this.flags = 0;
        this.sva = 0;
        this.age = 0;
        this.toe = new GTime();
        this.tof = new GTime();
        this.pos = new double[3];
        this.vel = new double[3];
        this.acc = new double[3];
        this.taun = 0.0;
        this.gamn = 0.0;
        this.dtaun = 0.0;
    }

    /**
     * Copy constructor.
     * @param other Source Geph object to copy from
     */
    public Geph(Geph other) {
        this.sat = other.sat;
        this.iode = other.iode;
        this.frq = other.frq;
        this.svh = other.svh;
        this.flags = other.flags;
        this.sva = other.sva;
        this.age = other.age;
        this.toe = new GTime(other.toe);
        this.tof = new GTime(other.tof);
        this.pos = other.pos.clone();
        this.vel = other.vel.clone();
        this.acc = other.acc.clone();
        this.taun = other.taun;
        this.gamn = other.gamn;
        this.dtaun = other.dtaun;
    }
}