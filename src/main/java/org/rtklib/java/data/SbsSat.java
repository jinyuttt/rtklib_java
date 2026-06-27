package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * SBAS satellite corrections class.
 * Aligned with RTKLIB sbssat_t structure.
 */
public class SbsSat {
    /** IODP (issue of date mask) */
    public int iodp;
    
    /** Number of satellites */
    public int nsat;
    
    /** System latency (s) */
    public int tlat;
    
    /** Satellite correction */
    public SbsSatP[] sat;

    /**
     * Default constructor.
     */
    public SbsSat() {
        this.iodp = 0;
        this.nsat = 0;
        this.tlat = 0;
        this.sat = new SbsSatP[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.sat[i] = new SbsSatP();
        }
    }
}

/**
 * SBAS satellite correction parameter class.
 * Aligned with RTKLIB sbssatp_t structure.
 */
class SbsSatP {
    /** Satellite number */
    public int sat;
    
    /** Fast correction */
    public SbsFCorr fcorr;
    
    /** Long term correction */
    public SbsLCorr lcorr;

    /**
     * Default constructor.
     */
    public SbsSatP() {
        this.sat = 0;
        this.fcorr = new SbsFCorr();
        this.lcorr = new SbsLCorr();
    }
}

/**
 * SBAS fast correction class.
 * Aligned with RTKLIB sbsfcorr_t structure.
 */
class SbsFCorr {
    /** Time of applicability (TOF) */
    public GTime t0;
    
    /** Pseudorange correction (PRC) (m) */
    public double prc;
    
    /** Range-rate correction (RRC) (m/s) */
    public double rrc;
    
    /** Range-rate correction delta-time (s) */
    public double dt;
    
    /** IODF (issue of date fast corr) */
    public int iodf;
    
    /** UDRE+1 */
    public short udre;
    
    /** Degradation factor indicator */
    public short ai;

    /**
     * Default constructor.
     */
    public SbsFCorr() {
        this.t0 = new GTime();
        this.prc = 0.0;
        this.rrc = 0.0;
        this.dt = 0.0;
        this.iodf = 0;
        this.udre = 0;
        this.ai = 0;
    }
}

/**
 * SBAS long term satellite error correction class.
 * Aligned with RTKLIB sbslcorr_t structure.
 */
class SbsLCorr {
    /** Correction time */
    public GTime t0;
    
    /** IODE (issue of date ephemeris) */
    public int iode;
    
    /** Delta position (m) (ECEF) */
    public double[] dpos;
    
    /** Delta velocity (m/s) (ECEF) */
    public double[] dvel;
    
    /** Delta clock-offset/drift (s, s/s) */
    public double daf0, daf1;

    /**
     * Default constructor.
     */
    public SbsLCorr() {
        this.t0 = new GTime();
        this.iode = 0;
        this.dpos = new double[3];
        this.dvel = new double[3];
        this.daf0 = 0.0;
        this.daf1 = 0.0;
    }
}