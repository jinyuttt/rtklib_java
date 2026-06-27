package org.rtklib.java.data;

/**
 * SBAS ephemeris class.
 * Aligned with RTKLIB seph_t structure.
 */
public class Seph {
    /** Satellite number */
    public int sat;
    
    /** Reference epoch time (GPST) */
    public GTime t0;
    
    /** Time of message frame (GPST) */
    public GTime tof;
    
    /** SV accuracy (URA index) */
    public int sva;
    
    /** SV health (0:ok) */
    public int svh;
    
    /** Satellite position (ECEF) (m) */
    public double[] pos;
    
    /** Satellite velocity (ECEF) (m/s) */
    public double[] vel;
    
    /** Satellite acceleration (ECEF) (m/s^2) */
    public double[] acc;
    
    /** Satellite clock-offset/drift (s, s/s) */
    public double af0, af1;

    /**
     * Default constructor.
     */
    public Seph() {
        this.sat = 0;
        this.t0 = new GTime();
        this.tof = new GTime();
        this.sva = 0;
        this.svh = 0;
        this.pos = new double[3];
        this.vel = new double[3];
        this.acc = new double[3];
        this.af0 = 0.0;
        this.af1 = 0.0;
    }

    /**
     * Copy constructor.
     * @param other Source Seph object to copy from
     */
    public Seph(Seph other) {
        this.sat = other.sat;
        this.t0 = new GTime(other.t0);
        this.tof = new GTime(other.tof);
        this.sva = other.sva;
        this.svh = other.svh;
        this.pos = other.pos.clone();
        this.vel = other.vel.clone();
        this.acc = other.acc.clone();
        this.af0 = other.af0;
        this.af1 = other.af1;
    }
}