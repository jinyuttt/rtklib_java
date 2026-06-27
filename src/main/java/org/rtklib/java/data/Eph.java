package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * GPS/QZS/GAL/BDS/IRN broadcast ephemeris class.
 * Aligned with RTKLIB eph_t structure.
 */
public class Eph {
    /** Satellite number */
    public int sat;
    
    /** Issue of Data, Ephemeris */
    public int iode;
    
    /** Issue of Data, Clock */
    public int iodc;
    
    /** SV accuracy (URA index) */
    public int sva;
    
    /** SV health (0:ok) */
    public int svh;
    
    /** GPS/QZS: GPS week, GAL: Galileo week */
    public int week;
    
    /** 
     * GPS/QZS: code on L2
     * GAL: data source defined as RINEX 3.03
     * BDS: data source (0:unknown,1:B1I,2:B1Q,3:B2I,4:B2Q,5:B3I,6:B3Q)
     */
    public int code;
    
    /**
     * GPS/QZS: L2 P data flag
     * BDS: nav type (0:unknown,1:IGSO/MEO,2:GEO)
     */
    public int flag;
    
    /** Time of Ephemeris */
    public GTime toe;
    
    /** Time of Clock */
    public GTime toc;
    
    /** Time of Transmission */
    public GTime ttr;
    
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
    
    /** Mean motion difference from computed value (rad/s) */
    public double deln;
    
    /** Rate of right ascension (rad/s) */
    public double OMGd;
    
    /** Rate of inclination angle (rad/s) */
    public double idot;
    
    /** Amplitude of cosine harmonic correction term to argument of latitude (rad) */
    public double crc;
    
    /** Amplitude of sine harmonic correction term to argument of latitude (rad) */
    public double crs;
    
    /** Amplitude of cosine harmonic correction term to angle of inclination (rad) */
    public double cuc;
    
    /** Amplitude of sine harmonic correction term to angle of inclination (rad) */
    public double cus;
    
    /** Amplitude of cosine harmonic correction term to radius (m) */
    public double cic;
    
    /** Amplitude of sine harmonic correction term to radius (m) */
    public double cis;
    
    /** Toe (s) in week */
    public double toes;
    
    /** Fit interval (h) */
    public double fit;
    
    /** SV clock parameters (af0, af1, af2) (s, s/s, s/s^2) */
    public double f0, f1, f2;
    
    /** Group delay parameters */
    public double[] tgd;
    
    /** Time of transmission */
    public double ttot;

    /**
     * Default constructor.
     */
    public Eph() {
        this.sat = 0;
        this.iode = 0;
        this.iodc = 0;
        this.sva = 0;
        this.svh = 0;
        this.week = 0;
        this.code = 0;
        this.flag = 0;
        this.toe = new GTime();
        this.toc = new GTime();
        this.ttr = new GTime();
        this.A = 0.0;
        this.e = 0.0;
        this.i0 = 0.0;
        this.OMG0 = 0.0;
        this.omg = 0.0;
        this.M0 = 0.0;
        this.deln = 0.0;
        this.OMGd = 0.0;
        this.idot = 0.0;
        this.crc = 0.0;
        this.crs = 0.0;
        this.cuc = 0.0;
        this.cus = 0.0;
        this.cic = 0.0;
        this.cis = 0.0;
        this.toes = 0.0;
        this.fit = 0.0;
        this.f0 = 0.0;
        this.f1 = 0.0;
        this.f2 = 0.0;
        this.tgd = new double[6];
    }

    /**
     * Copy constructor.
     * @param other Source Eph object to copy from
     */
    public Eph(Eph other) {
        this.sat = other.sat;
        this.iode = other.iode;
        this.iodc = other.iodc;
        this.sva = other.sva;
        this.svh = other.svh;
        this.week = other.week;
        this.code = other.code;
        this.flag = other.flag;
        this.toe = new GTime(other.toe);
        this.toc = new GTime(other.toc);
        this.ttr = new GTime(other.ttr);
        this.A = other.A;
        this.e = other.e;
        this.i0 = other.i0;
        this.OMG0 = other.OMG0;
        this.omg = other.omg;
        this.M0 = other.M0;
        this.deln = other.deln;
        this.OMGd = other.OMGd;
        this.idot = other.idot;
        this.crc = other.crc;
        this.crs = other.crs;
        this.cuc = other.cuc;
        this.cus = other.cus;
        this.cic = other.cic;
        this.cis = other.cis;
        this.toes = other.toes;
        this.fit = other.fit;
        this.f0 = other.f0;
        this.f1 = other.f1;
        this.f2 = other.f2;
        this.tgd = other.tgd.clone();
    }
}