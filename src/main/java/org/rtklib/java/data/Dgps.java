package org.rtklib.java.data;

/**
 * DGPS/GNSS correction class.
 * Aligned with RTKLIB dgps_t structure.
 */
public class Dgps {
    /** Correction time */
    public GTime t0;
    
    /** Pseudorange correction (PRC) (m) */
    public double prc;
    
    /** Range rate correction (RRC) (m/s) */
    public double rrc;
    
    /** Issue of data (IOD) */
    public int iod;
    
    /** UDRE */
    public double udre;

    /**
     * Default constructor.
     */
    public Dgps() {
        this.t0 = new GTime();
        this.prc = 0.0;
        this.rrc = 0.0;
        this.iod = 0;
        this.udre = 0.0;
    }

    /**
     * Copy constructor.
     * @param other Source Dgps object to copy from
     */
    public Dgps(Dgps other) {
        this.t0 = new GTime(other.t0);
        this.prc = other.prc;
        this.rrc = other.rrc;
        this.iod = other.iod;
        this.udre = other.udre;
    }
}