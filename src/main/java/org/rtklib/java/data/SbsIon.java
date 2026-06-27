package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * SBAS ionospheric corrections class.
 * Aligned with RTKLIB sbsion_t structure.
 */
public class SbsIon {
    /** IODI (issue of date ionos corr) */
    public int iodi;
    
    /** Number of IGPs */
    public int nigp;
    
    /** Ionospheric correction */
    public SbsIgp[] igp;

    /**
     * Default constructor.
     */
    public SbsIon() {
        this.iodi = 0;
        this.nigp = 0;
        this.igp = new SbsIgp[Constants.MAXNIGP];
        for (int i = 0; i < Constants.MAXNIGP; i++) {
            this.igp[i] = new SbsIgp();
        }
    }
}

/**
 * SBAS ionospheric correction class.
 * Aligned with RTKLIB sbsigp_t structure.
 */
class SbsIgp {
    /** Correction time */
    public GTime t0;
    
    /** Latitude/longitude (deg) */
    public short lat, lon;
    
    /** GIVI+1 */
    public short give;
    
    /** Vertical delay estimate (m) */
    public float delay;

    /**
     * Default constructor.
     */
    public SbsIgp() {
        this.t0 = new GTime();
        this.lat = 0;
        this.lon = 0;
        this.give = 0;
        this.delay = 0.0f;
    }
}