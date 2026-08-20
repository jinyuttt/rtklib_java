package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * Processing options class.
 * Aligned with RTKLIB prcopt_t.
 */
public class PrcOpt {
    /** Position output format mask (bitwise OR of POS_ECEF/POS_LLH/POS_ENU) */
    public static final int POS_ECEF = 1;
    public static final int POS_LLH  = 2;
    public static final int POS_ENU  = 4;

    /** Positioning mode (PMODE_???) */
    public int mode;
    /** Solution type (SOLTYPE_???) */
    public int soltype;
    /** Number of frequencies (1:L1, 2:L1+L2, 3:L1+L2+L5) */
    public int nf;
    /** Navigation system */
    public int navsys;
    /** Elevation mask angle (rad) */
    public double elmin;
    /** SNR mask */
    public SnrMask snrmask;
    /** Satellite ephemeris/clock (EPHOPT_???) */
    public int sateph;
    /** AR mode */
    public int modear;
    /** GLONASS AR mode */
    public int glomodear;
    /** GPS AR mode (debug/learning) */
    public int gpsmodear;
    /** BeiDou AR mode */
    public int bdsmodear;
    /** AR filtering to reject bad sats (0:off,1:on) */
    public int arfilter;
    /** Obs outage count to reset bias */
    public int maxout;
    /** Min lock count to fix ambiguity */
    public int minlock;
    /** Min sats to fix integer ambiguities */
    public int minfixsats;
    /** Min sats to hold integer ambiguities */
    public int minholdsats;
    /** Min sats to drop sats in AR */
    public int mindropsats;
    /** Min fix count to hold ambiguity */
    public int minfix;
    /** Max iteration to resolve ambiguity */
    public int armaxiter;
    /** Ionosphere option (IONOOPT_???) */
    public int ionoopt;
    /** Troposphere option (TROPOPT_???) */
    public int tropopt;
    /** Dynamics model (0:none, 1:velocity, 2:accel) */
    public int dynamics;
    /** Earth tide correction */
    public int tidecorr;
    /** Number of filter iteration */
    public int niter;
    /** Code smoothing window size (0:none) */
    public int codesmooth;
    /** SPP position averaging window size (0:none, >0: sliding window epochs) */
    public int sppsmooth;
    /** Interpolate reference obs (post mission) */
    public int intpref;
    /** SBAS correction options */
    public int sbascorr;
    /** SBAS satellite selection (0:all) */
    public int sbassatsel;
    /** Rover position for fixed mode (0:pos in prcopt, 1:avg of single, 2:file, 3:rinex, 4:rtcm) */
    public int rovpos;
    /** Base position for relative mode (POSOPT_???) */
    public int refpos;
    /** Code/phase error ratio */
    public double[] eratio;
    /** Observation error terms (8 entries) */
    public double[] err;
    /** Carrier frequencies for each frequency band */
    public double[] freq;
    /** Initial-state std [0]bias,[1]iono [2]trop */
    public double[] std;
    /** Process-noise std [0]bias,[1]iono [2]trop [3]acch [4]accv [5] pos */
    public double[] prn;
    /** Satellite clock stability (sec/sec) */
    public double sclkstab;
    /** AR validation threshold (8 entries) */
    public double[] thresar;
    /** Elevation mask of AR for rising satellite (deg) */
    public double elmaskar;
    /** Elevation mask to hold ambiguity (deg) */
    public double elmaskhold;
    /** Slip threshold of geometry-free phase (m) */
    public double thresslip;
    /** Slip threshold of doppler (m) */
    public double thresdop;
    /** Variance for fix-and-hold pseudo measurements (cycle^2) */
    public double varholdamb;
    /** Gain used for GLO and SBAS sats to adjust ambiguity */
    public double gainholdamb;
    /** Max difference of time (sec) */
    public double maxtdiff;
    /** Reject threshold of innovation for phase and code (m) */
    public double[] maxinno;
    /** Baseline length constraint {const,sigma} (m) */
    public double[] baseline;
    /** Rover position for fixed mode {x,y,z} (ecef) (m) */
    public double[] ru;
    /** Base position for relative mode {x,y,z} (ecef) (m) */
    public double[] rb;
    /** Antenna types {rover,base} */
    public String[] anttype;
    /** Antenna delta {{rov_e,rov_n,rov_u},{ref_e,ref_n,ref_u}} */
    public Pcv[] pcvr;
    public double[][][][] odisp;
    public double[][] antdel;
    /** Excluded satellites (1:excluded, 2:included) */
    public int[] exsats;
    /** Max averaging epochs */
    public int maxaveep;
    /** Initialize by restart */
    public int initrst;
    /** Output single by dgps/float/fix/ppp outage */
    public int outsingle;
    /** RINEX options {rover,base} */
    public String[] rnxopt;
    /** Positioning options */
    public int[] posopt;
    /** Solution sync mode (0:off,1:on) */
    public int syncsol;
    /** Frequency option (disable L2-AR) */
    public int freqopt;
    /** PPP option string */
    public String pppopt;
    /** Output throttle interval (number of epochs, 0:disable) */
    public int outputThrottleInterval;
    /** Output throttle sleep time (ms) */
    public int outputThrottleSleepMs;

    /** Ionosphere gradient estimation (false:VTEC only, true:VTEC+Gn+Ge per sat) */
    public boolean ionoGradient = false;
    /** Position output format mask (bitwise OR of POS_ECEF/POS_LLH/POS_ENU) */
    public int posMask;

    /** Max cached epochs per source (0:no cache/forward-only, >0:cache+backward trigger) */
    public int cacheMaxEpochs = 0;

    /**
     * Default constructor with RTKLIB default values.
     */
    public PrcOpt() {
        this.mode = Constants.PMODE_SINGLE;
        this.soltype = Constants.SOLTYPE_FORWARD;
        this.nf = 2;
        this.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        this.elmin = 15.0 * Constants.D2R;
        this.snrmask = new SnrMask();
        this.sateph = Constants.EPHOPT_BRDC;
        this.modear = Constants.ARMODE_FIXHOLD;
        this.glomodear = Constants.GLO_ARMODE_AUTOCAL;
        this.gpsmodear = 1;
        this.bdsmodear = 1;
        this.arfilter = 1;
        this.maxout = 20;
        this.minlock = 0;
        this.minfixsats = 4;
        this.minholdsats = 5;
        this.mindropsats = 10;
        this.minfix = 20;
        this.armaxiter = 1;
        this.ionoopt = Constants.IONOOPT_BRDC;
        this.tropopt = Constants.TROPOPT_SAAS;
        this.dynamics = 0;
        this.tidecorr = 0;
        this.niter = 1;
        this.codesmooth = 0;
        this.sppsmooth = 0;
        this.intpref = 0;
        this.sbascorr = 0;
        this.sbassatsel = 0;
        this.rovpos = 0;
        this.refpos = Constants.POSOPT_POS_XYZ;
        this.eratio = new double[Constants.MAXFREQ];
        for (int i = 0; i < Constants.MAXFREQ; i++) this.eratio[i] = 300.0;
        this.err = new double[8];
        this.err[0] = 100.0;
        this.err[1] = 0.003;
        this.err[2] = 0.003;
        this.err[3] = 0.0;
        this.err[4] = 1.0;
        this.err[5] = 52.0;
        this.err[6] = 0.0;
        this.err[7] = 0.0;
        this.freq = new double[Constants.MAXFREQ];
        this.freq[0] = Constants.FREQL1;
        this.freq[1] = Constants.FREQL2;
        this.freq[2] = Constants.FREQL5;
        this.std = new double[]{30.0, 0.03, 0.3};
        this.prn = new double[6];
        this.prn[0] = 1E-4;
        this.prn[1] = 1E-3;
        this.prn[2] = 1E-4;
        this.prn[3] = 1E-1;
        this.prn[4] = 1E-2;
        this.prn[5] = 0.0;
        this.sclkstab = 5E-12;
        this.thresar = new double[]{3.0, 0.25, 0.0, 1E-9, 1E-5, 3.0, 3.0, 0.0};
        this.elmaskar = 0.0;
        this.elmaskhold = 0.0;
        this.thresslip = 0.05;
        this.thresdop = 0.0;
        this.varholdamb = 0.1;
        this.gainholdamb = 0.01;
        this.maxtdiff = 30.0;
        this.maxinno = new double[]{5.0, 30.0};
        this.baseline = new double[2];
        this.ru = new double[3];
        this.rb = new double[3];
        this.anttype = new String[2];
        this.anttype[0] = "";
        this.anttype[1] = "";
        this.pcvr = new Pcv[2];
        this.pcvr[0] = new Pcv();
        this.pcvr[1] = new Pcv();
        this.odisp = new double[2][2][11][3];
        this.antdel = new double[2][3];
        this.exsats = new int[Constants.MAXSAT];
        this.maxaveep = 1;
        this.initrst = 0;
        this.outsingle = 0;
        this.rnxopt = new String[2];
        this.rnxopt[0] = "";
        this.rnxopt[1] = "";
        this.posopt = new int[6];
        this.syncsol = 0;
        this.freqopt = 0;
        this.pppopt = "";
        this.outputThrottleInterval = 100;
        this.outputThrottleSleepMs = 10;

        this.posMask = POS_ECEF | POS_LLH;
    }

    /**
     * Copy constructor.
     * @param other Source PrcOpt
     */
    public PrcOpt(PrcOpt other) {
        this.mode = other.mode;
        this.soltype = other.soltype;
        this.nf = other.nf;
        this.navsys = other.navsys;
        this.elmin = other.elmin;
        this.snrmask = new SnrMask();
        this.snrmask.ena0 = other.snrmask.ena0;
        this.snrmask.ena1 = other.snrmask.ena1;
        System.arraycopy(other.snrmask.mask, 0, this.snrmask.mask, 0, this.snrmask.mask.length);
        this.sateph = other.sateph;
        this.modear = other.modear;
        this.glomodear = other.glomodear;
        this.gpsmodear = other.gpsmodear;
        this.bdsmodear = other.bdsmodear;
        this.arfilter = other.arfilter;
        this.maxout = other.maxout;
        this.minlock = other.minlock;
        this.minfixsats = other.minfixsats;
        this.minholdsats = other.minholdsats;
        this.mindropsats = other.mindropsats;
        this.minfix = other.minfix;
        this.armaxiter = other.armaxiter;
        this.ionoopt = other.ionoopt;
        this.tropopt = other.tropopt;
        this.dynamics = other.dynamics;
        this.tidecorr = other.tidecorr;
        this.niter = other.niter;
        this.codesmooth = other.codesmooth;
        this.sppsmooth = other.sppsmooth;
        this.intpref = other.intpref;
        this.sbascorr = other.sbascorr;
        this.sbassatsel = other.sbassatsel;
        this.rovpos = other.rovpos;
        this.refpos = other.refpos;
        this.eratio = other.eratio.clone();
        this.err = other.err.clone();
        this.freq = other.freq.clone();
        this.std = other.std.clone();
        this.prn = other.prn.clone();
        this.sclkstab = other.sclkstab;
        this.thresar = other.thresar.clone();
        this.elmaskar = other.elmaskar;
        this.elmaskhold = other.elmaskhold;
        this.thresslip = other.thresslip;
        this.thresdop = other.thresdop;
        this.varholdamb = other.varholdamb;
        this.gainholdamb = other.gainholdamb;
        this.maxtdiff = other.maxtdiff;
        this.maxinno = other.maxinno.clone();
        this.baseline = other.baseline.clone();
        this.ru = other.ru.clone();
        this.rb = other.rb.clone();
        this.anttype = other.anttype.clone();
        this.pcvr = new Pcv[2];
        this.pcvr[0] = new Pcv();
        this.pcvr[1] = new Pcv();
        this.odisp = new double[2][2][11][3];
        this.antdel = new double[2][3];
        for (int i = 0; i < 2; i++) this.antdel[i] = other.antdel[i].clone();
        this.exsats = other.exsats.clone();
        this.maxaveep = other.maxaveep;
        this.initrst = other.initrst;
        this.outsingle = other.outsingle;
        this.rnxopt = other.rnxopt.clone();
        this.posopt = other.posopt.clone();
        this.syncsol = other.syncsol;
        this.freqopt = other.freqopt;
        this.pppopt = other.pppopt;
        this.outputThrottleInterval = other.outputThrottleInterval;
        this.outputThrottleSleepMs = other.outputThrottleSleepMs;
        this.ionoGradient = other.ionoGradient;
        this.posMask = other.posMask;
    }
}