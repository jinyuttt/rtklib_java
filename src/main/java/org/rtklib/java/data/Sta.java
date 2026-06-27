package org.rtklib.java.data;

/**
 * Station information class.
 * Aligned with RTKLIB sta_t structure.
 */
public class Sta {
    /** Station marker name */
    public String name;
    
    /** Station marker number */
    public String marker;
    
    /** Antenna descriptor */
    public String antdes;
    
    /** Antenna serial number */
    public String antsno;
    
    /** Receiver type descriptor */
    public String rectype;
    
    /** Receiver serial number */
    public String recsno;
    
    /** Antenna phase center offsets (H, E, N) */
    public double[] del;
    
    /** Antenna phase center offsets (X, Y, Z) */
    public double[] del2;
    
    /** Approximate position (ECEF) (m) */
    public double[] pos;
    
    /** Height of antenna reference point (m) */
    public double hgt;
    
    /** Station coordinates (ECEF) (m) */
    public double[] xyz;
    
    /** ITRF realization */
    public int itrf;

    /**
     * Default constructor.
     */
    public Sta() {
        this.name = "";
        this.marker = "";
        this.antdes = "";
        this.antsno = "";
        this.rectype = "";
        this.recsno = "";
        this.del = new double[6];
        this.del2 = new double[3];
        this.pos = new double[3];
        this.hgt = 0.0;
        this.xyz = new double[3];
        this.itrf = 0;
    }

    /**
     * Copy constructor.
     * @param other Source Sta object to copy from
     */
    public Sta(Sta other) {
        this.name = other.name;
        this.marker = other.marker;
        this.antdes = other.antdes;
        this.antsno = other.antsno;
        this.rectype = other.rectype;
        this.recsno = other.recsno;
        this.del = other.del.clone();
        this.del2 = other.del2.clone();
        this.pos = other.pos.clone();
        this.hgt = other.hgt;
        this.xyz = other.xyz.clone();
    }
}