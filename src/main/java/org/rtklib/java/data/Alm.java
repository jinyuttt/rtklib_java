package org.rtklib.java.data;

/**
 * Almanac data class.
 * Aligned with RTKLIB alm_t structure.
 */
public class Alm {
    /** Satellite number */
    public int sat;
    
    /** SV health (0:ok) */
    public int svh;
    
    /** AS and SV config */
    public int svconf;
    
    /** GPS/QZS: GPS week, GAL: Galileo week */
    public int week;
    
    /** Time of Almanac */
    public GTime toa;
    
    /** Semi-major axis (m^(1/2)) */
    public double A;
    
    /** Eccentricity */
    public double e;
    
    /** Inclination at reference time (rad) */
    public double i0;
    
    /** Longitude of ascending node at weekly epoch (rad) */
    public double OMG0;
    
    /** Argument of perigee (rad) */
    public double omg;
    
    /** Mean anomaly at reference time (rad) */
    public double M0;
    
    /** Rate of right ascension (rad/s) */
    public double OMGd;
    
    /** Toa (s) in week */
    public double toas;
    
    /** SV clock parameters (af0, af1) (s, s/s) */
    public double f0, f1;

    /**
     * Default constructor.
     */
    public Alm() {
        this.sat = 0;
        this.svh = 0;
        this.svconf = 0;
        this.week = 0;
        this.toa = new GTime();
        this.A = 0.0;
        this.e = 0.0;
        this.i0 = 0.0;
        this.OMG0 = 0.0;
        this.omg = 0.0;
        this.M0 = 0.0;
        this.OMGd = 0.0;
        this.toas = 0.0;
        this.f0 = 0.0;
        this.f1 = 0.0;
    }

    /**
     * Copy constructor.
     * @param other Source Alm object to copy from
     */
    public Alm(Alm other) {
        this.sat = other.sat;
        this.svh = other.svh;
        this.svconf = other.svconf;
        this.week = other.week;
        this.toa = new GTime(other.toa);
        this.A = other.A;
        this.e = other.e;
        this.i0 = other.i0;
        this.OMG0 = other.OMG0;
        this.omg = other.omg;
        this.M0 = other.M0;
        this.OMGd = other.OMGd;
        this.toas = other.toas;
        this.f0 = other.f0;
        this.f1 = other.f1;
    }
}