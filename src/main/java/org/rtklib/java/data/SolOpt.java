package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * Solution output options class.
 * Aligned with RTKLIB solopt_t.
 */
public class SolOpt {
    /** Solution format (SOLF_???) */
    public int posf;
    /** Time system (TIMES_???) */
    public int times;
    /** Time format (0:sssss.s, 1:yyyy/mm/dd hh:mm:ss.s) */
    public int timef;
    /** Time digits under decimal point */
    public int timeu;
    /** Latitude/longitude format (0:ddd.ddd, 1:ddd mm ss) */
    public int degf;
    /** Output header (0:no, 1:yes) */
    public int outhead;
    /** Output processing options (0:no, 1:yes) */
    public int outopt;
    /** Output velocity options (0:no, 1:yes) */
    public int outvel;
    /** Datum (0:WGS84, 1:Tokyo) */
    public int datum;
    /** Height (0:ellipsoidal, 1:geodetic) */
    public int height;
    /** Geoid model (GEOID_???) */
    public int geoid;
    /** Solution of static mode (0:all, 1:single) */
    public int solstatic;
    /** Solution statistics level (0:off, 1:states, 2:residuals) */
    public int sstat;
    /** Debug trace level (0:off, 1-5:debug) */
    public int trace;
    /** NMEA output interval {gprmc,gpgga,gpgsv} */
    public double[] nmeaintv;
    /** Field separator */
    public String sep;
    /** Program name */
    public String prog;
    /** Max std-dev for solution output (m) (0:all) */
    public double maxsolstd;

    /**
     * Default constructor with RTKLIB default values.
     */
    public SolOpt() {
        this.posf = Constants.SOLF_LLH;
        this.times = Constants.TIMES_GPST;
        this.timef = 1;
        this.timeu = 3;
        this.degf = 0;
        this.outhead = 0;
        this.outopt = 0;
        this.outvel = 0;
        this.datum = 0;
        this.height = 0;
        this.geoid = 0;
        this.solstatic = 0;
        this.sstat = 0;
        this.trace = 0;
        this.nmeaintv = new double[]{0.0, 0.0};
        this.sep = "";
        this.prog = "";
        this.maxsolstd = 0.0;
    }

    /**
     * Copy constructor.
     * @param other Source SolOpt
     */
    public SolOpt(SolOpt other) {
        this.posf = other.posf;
        this.times = other.times;
        this.timef = other.timef;
        this.timeu = other.timeu;
        this.degf = other.degf;
        this.outhead = other.outhead;
        this.outopt = other.outopt;
        this.outvel = other.outvel;
        this.datum = other.datum;
        this.height = other.height;
        this.geoid = other.geoid;
        this.solstatic = other.solstatic;
        this.sstat = other.sstat;
        this.trace = other.trace;
        this.nmeaintv = other.nmeaintv.clone();
        this.sep = other.sep;
        this.prog = other.prog;
        this.maxsolstd = other.maxsolstd;
    }
}