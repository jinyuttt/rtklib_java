package org.rtklib.java.constants;

/**
 * RTKLIB constants mapped from rtklib.h.
 * These constants are aligned 1:1 with RTKLIB 2.5.0.
 */
public final class Constants {
    private Constants() {
        // Utility class
    }

    /** Library version */
    public static final String VER_RTKLIB = "EX";

    /** Patch level */
    public static final String PATCH_LEVEL = "2.5.0";

    /** Copyright */
    public static final String COPYRIGHT_RTKLIB =
            "Copyright (C) 2007-2020 T.Takasu\nAll rights reserved.";

    /** pi */
    public static final double PI = 3.1415926535897932;
    
    /** deg to rad */
    public static final double D2R = (PI / 180.0);
    
    /** rad to deg */
    public static final double R2D = (180.0 / PI);
    
    /** speed of light (m/s) */
    public static final double CLIGHT = 299792458.0;

    /** RTCM3 GLONASS pseudorange unit (m) */
    public static final double PRUNIT_GLO = 599584.916;

    /** SPP通过 - error of broadcast clock (m), aligned with RTKLIB */
    public static final double STD_BRDCCLK = 30.0;
    /** SPP通过 - squared error of broadcast clock (m^2) */
    public static final double SQR_STD_BRDCCLK = STD_BRDCCLK * STD_BRDCCLK;
    
    /** semi-circle to radian (IS-GPS) */
    public static final double SC2RAD = 3.1415926535898;
    
    /** 1 AU (m) */
    public static final double AU = 149597870691.0;
    
    /** arc sec to radian */
    public static final double AS2R = (D2R / 3600.0);
    
    /** earth angular velocity (IS-GPS) (rad/s) */
    public static final double OMGE = 7.2921151467E-5;
    
    /** earth semimajor axis (WGS84) (m) */
    public static final double RE_WGS84 = 6378137.0;
    
    /** earth flattening (WGS84) */
    public static final double FE_WGS84 = (1.0 / 298.257223563);
    
    /** ionosphere height (m) */
    public static final double HION = 350000.0;
    
    /** max NFREQ */
    public static final int MAXFREQ = 6;
    
    /** L1/E1 frequency (Hz) */
    public static final double FREQL1 = 1.57542E9;
    
    /** L2 frequency (Hz) */
    public static final double FREQL2 = 1.22760E9;
    
    /** E5b frequency (Hz) */
    public static final double FREQE5b = 1.20714E9;
    
    /** L5/E5a/B2a frequency (Hz) */
    public static final double FREQL5 = 1.17645E9;
    
    /** E6/L6 frequency (Hz) */
    public static final double FREQL6 = 1.27875E9;
    
    /** E5a+b frequency (Hz) */
    public static final double FREQE5ab = 1.191795E9;
    
    /** S frequency (Hz) */
    public static final double FREQs = 2.492028E9;
    
    /** GLONASS G1 base frequency (Hz) */
    public static final double FREQ1_GLO = 1.60200E9;
    
    /** GLONASS G1 bias frequency (Hz/n) */
    public static final double DFRQ1_GLO = 0.56250E6;
    
    /** GLONASS G2 base frequency (Hz) */
    public static final double FREQ2_GLO = 1.24600E9;
    
    /** GLONASS G2 bias frequency (Hz/n) */
    public static final double DFRQ2_GLO = 0.43750E6;
    
    /** GLONASS G3 frequency (Hz) */
    public static final double FREQ3_GLO = 1.202025E9;
    
    /** GLONASS G1a frequency (Hz) */
    public static final double FREQ1a_GLO = 1.600995E9;
    
    /** GLONASS G2a frequency (Hz) */
    public static final double FREQ2a_GLO = 1.248060E9;
    
    /** BDS B1I frequency (Hz) */
    public static final double FREQ1_CMP = 1.561098E9;
    
    /** BDS B2I/B2b frequency (Hz) */
    public static final double FREQ2_CMP = 1.20714E9;
    
    /** BDS B3 frequency (Hz) */
    public static final double FREQ3_CMP = 1.26852E9;
    
    /** error factor: GPS */
    public static final double EFACT_GPS = 1.0;
    
    /** error factor: GLONASS */
    public static final double EFACT_GLO = 1.5;
    
    /** error factor: Galileo */
    public static final double EFACT_GAL = 1.0;
    
    /** error factor: QZSS */
    public static final double EFACT_QZS = 1.0;
    
    /** error factor: BeiDou */
    public static final double EFACT_CMP = 1.0;
    
    /** error factor: IRNSS */
    public static final double EFACT_IRN = 1.5;
    
    /** error factor: SBAS */
    public static final double EFACT_SBS = 3.0;

    // Navigation systems
    public static final int SYS_NONE = 0x00;
    public static final int SYS_GPS = 0x01;
    public static final int SYS_SBS = 0x02;
    public static final int SYS_GLO = 0x04;
    public static final int SYS_GAL = 0x08;
    public static final int SYS_QZS = 0x10;
    public static final int SYS_CMP = 0x20;
    public static final int SYS_IRN = 0x40;
    public static final int SYS_LEO = 0x80;
    public static final int SYS_ALL = 0xFF;

    // RINEX system codes
    public static final int RNX_SYS_GPS = 0;
    public static final int RNX_SYS_GLO = 1;
    public static final int RNX_SYS_GAL = 2;
    public static final int RNX_SYS_QZS = 3;
    public static final int RNX_SYS_SBS = 4;
    public static final int RNX_SYS_CMP = 5;
    public static final int RNX_SYS_IRN = 6;
    public static final int RNX_NUMSYS = 7;

    // Time systems
    public static final int TSYS_GPS = 0;
    public static final int TSYS_UTC = 1;
    public static final int TSYS_GLO = 2;
    public static final int TSYS_GAL = 3;
    public static final int TSYS_QZS = 4;
    public static final int TSYS_CMP = 5;
    public static final int TSYS_IRN = 6;

    /** Number of carrier frequencies */
    public static final int NFREQ = 6;
    
    /** Number of carrier frequencies of GLONASS */
    public static final int NFREQGLO = 2;

    /** Number of extended obs codes */
    public static final int NEXOBS = 2;

    /** Wavelengths (m) */
    public static final double[] WAVELENGTHS = {
        CLIGHT / FREQL1,
        CLIGHT / FREQL2,
        CLIGHT / FREQL5,
        CLIGHT / FREQL6,
        CLIGHT / FREQE5b,
        CLIGHT / FREQE5ab
    };

    /** Frequency ratio squared (L1/L2)^2 */
    public static final double GAMMA = Math.pow(FREQL1 / FREQL2, 2);

    /** Frequency ratio squared for L5 */
    public static final double GAMMA5 = Math.pow(FREQL1 / FREQL5, 2);


    // Satellite PRN ranges
    public static final int MINPRNGPS = 1;
    public static final int MAXPRNGPS = 32;
    public static final int NSATGPS = (MAXPRNGPS - MINPRNGPS + 1);
    public static final int NSYSGPS = 1;

    public static final int MINPRNGLO = 1;
    public static final int MAXPRNGLO = 27;
    public static final int NSATGLO = (MAXPRNGLO - MINPRNGLO + 1);
    public static final int NSYSGLO = 1;

    public static final int MINPRNGAL = 1;
    public static final int MAXPRNGAL = 36;
    public static final int NSATGAL = (MAXPRNGAL - MINPRNGAL + 1);
    public static final int NSYSGAL = 1;

    public static final int MINPRNQZS = 193;
    public static final int MAXPRNQZS = 202;
    public static final int MINPRNQZS_S = 183;
    public static final int MAXPRNQZS_S = 191;
    public static final int NSATQZS = (MAXPRNQZS - MINPRNQZS + 1);
    public static final int NSYSQZS = 1;

    public static final int MINPRNCMP = 1;
    public static final int MAXPRNCMP = 46;
    public static final int NSATCMP = (MAXPRNCMP - MINPRNCMP + 1);
    public static final int NSYSCMP = 1;

    public static final int MINPRNIRN = 1;
    public static final int MAXPRNIRN = 14;
    public static final int NSATIRN = (MAXPRNIRN - MINPRNIRN + 1);
    public static final int NSYSIRN = 1;

    /** State index offsets for RTK */
    public static final int NX_RTK = 3 + 3 + NSATGPS + NSATGLO + NSATGAL + NSATCMP + NSATQZS + NSATIRN + 1 + NSATGPS + NSATGLO + NSATGAL + NSATCMP + NSATQZS + NSATIRN;

    public static final int MINPRNLEO = 1;
    public static final int MAXPRNLEO = 10;
    public static final int NSATLEO = (MAXPRNLEO - MINPRNLEO + 1);
    public static final int NSYSLEO = 1;

    public static final int NSYS = (NSYSGPS + NSYSGLO + NSYSGAL + NSYSQZS + NSYSCMP + NSYSIRN + NSYSLEO);

    public static final int MINPRNSBS = 120;
    public static final int MAXPRNSBS = 158;
    public static final int NSATSBS = (MAXPRNSBS - MINPRNSBS + 1);

    public static final int MAXSAT = (NSATGPS + NSATGLO + NSATGAL + NSATQZS + NSATCMP + NSATIRN + NSATSBS + NSATLEO);
    public static final int GAP_RESION = 120;
    public static final int MAXSTA = 255;
    public static final int MAXOBS = 96;
    public static final int MAXRCV = 64;
    public static final int MAXOBSTYPE = 64;
    
    /** tolerance of time difference (s) */
    public static final double DTTOL = 0.025;
    
    public static final double MAXDTOE = 7200.0;
    public static final double MAXDTOE_QZS = 7200.0;
    public static final double MAXDTOE_GAL = 14400.0;
    public static final double MAXDTOE_CMP = 21600.0;
    public static final double MAXDTOE_GLO = 1800.0;
    public static final double MAXDTOE_IRN = 7200.0;
    public static final double MAXDTOE_SBS = 360.0;
    public static final double MAXDTOE_S = 86400.0;
    public static final double MAXGDOP = 300.0;

    public static final double INT_SWAP_TRAC = 86400.0;
    public static final double INT_SWAP_STAT = 86400.0;

    public static final int MAXEXFILE = 1024;
    public static final double MAXSBSAGEF = 30.0;
    public static final double MAXSBSAGEL = 1800.0;
    public static final int MAXSBSURA = 8;
    public static final int MAXBAND = 10;
    public static final int MAXNIGP = 201;
    public static final int MAXNGEO = 4;
    public static final int MAXCOMMENT = 100;
    public static final int MAXSTRPATH = 1024;
    public static final int MAXSTRMSG = 1024;
    public static final int MAXSTRRTK = 8;
    public static final int MAXSBSMSG = 32;
    public static final int MAXSOLLEN = 512;
    public static final int MAXSOLMSG = 32768;
    public static final int MAXRAWLEN = 16384;
    public static final int MAXERRMSG = 4096;
    public static final int MAXANT = 64;
    public static final int MAXSOLBUF = 256;
    public static final int MAXOBSBUF = 128;

    /** max variance eph to reject satellite (m^2) = SQR(300.0) */
    public static final double MAX_VAR_EPH = 90000.0;
    public static final int MAXNRPOS = 16;
    public static final int MAXLEAPS = 64;
    public static final int MAXGISLAYER = 32;
    public static final int MAXRCVCMD = 4096;
    public static final int MAX_CODE_BIASES = 3;
    public static final int MAX_CODE_BIAS_FREQS = 2;

    public static final double RNX2VER = 2.10;
    public static final double RNX3VER = 3.00;

    // Observation types
    public static final int OBSTYPE_PR = 0x01;
    public static final int OBSTYPE_CP = 0x02;
    public static final int OBSTYPE_DOP = 0x04;
    public static final int OBSTYPE_SNR = 0x08;
    public static final int OBSTYPE_ALL = 0x0F;

    // Frequency types
    public static final int FREQTYPE_L1 = 0x01;
    public static final int FREQTYPE_L2 = 0x02;
    public static final int FREQTYPE_L3 = 0x04;
    public static final int FREQTYPE_L4 = 0x08;
    public static final int FREQTYPE_L5 = 0x10;
    public static final int FREQTYPE_L6 = 0x20;
    public static final int FREQTYPE_ALL = 0x3F;

    // Observation codes
    public static final int CODE_NONE = 0;
    public static final int CODE_L1C = 1;
    public static final int CODE_L1P = 2;
    public static final int CODE_L1W = 3;
    public static final int CODE_L1Y = 4;
    public static final int CODE_L1M = 5;
    public static final int CODE_L1N = 6;
    public static final int CODE_L1S = 7;
    public static final int CODE_L1L = 8;
    public static final int CODE_L1E = 9;
    public static final int CODE_L1A = 10;
    public static final int CODE_L1B = 11;
    public static final int CODE_L1X = 12;
    public static final int CODE_L1Z = 13;
    public static final int CODE_L2C = 14;
    public static final int CODE_L2D = 15;
    public static final int CODE_L2S = 16;
    public static final int CODE_L2L = 17;
    public static final int CODE_L2X = 18;
    public static final int CODE_L2P = 19;
    public static final int CODE_L2W = 20;
    public static final int CODE_L2Y = 21;
    public static final int CODE_L2M = 22;
    public static final int CODE_L2N = 23;
    public static final int CODE_L5I = 24;
    public static final int CODE_L5Q = 25;
    public static final int CODE_L5X = 26;
    public static final int CODE_L7I = 27;
    public static final int CODE_L7Q = 28;
    public static final int CODE_L7X = 29;
    public static final int CODE_L6A = 30;
    public static final int CODE_L6B = 31;
    public static final int CODE_L6C = 32;
    public static final int CODE_L6X = 33;
    public static final int CODE_L6Z = 34;
    public static final int CODE_L6S = 35;
    public static final int CODE_L6L = 36;
    public static final int CODE_L8I = 37;
    public static final int CODE_L8Q = 38;
    public static final int CODE_L8X = 39;
    public static final int CODE_L2I = 40;
    public static final int CODE_L2Q = 41;
    public static final int CODE_L6I = 42;
    public static final int CODE_L6Q = 43;
    public static final int CODE_L3I = 44;
    public static final int CODE_L3Q = 45;
    public static final int CODE_L3X = 46;
    public static final int CODE_L1I = 47;
    public static final int CODE_L1Q = 48;
    public static final int CODE_L5A = 49;
    public static final int CODE_L5B = 50;
    public static final int CODE_L5C = 51;
    public static final int CODE_L9A = 52;
    public static final int CODE_L9B = 53;
    public static final int CODE_L9C = 54;
    public static final int CODE_L9X = 55;
    public static final int CODE_L1D = 56;
    public static final int CODE_L5D = 57;
    public static final int CODE_L5P = 58;
    public static final int CODE_L5Z = 59;
    public static final int CODE_L6E = 60;
    public static final int CODE_L7D = 61;
    public static final int CODE_L7P = 62;
    public static final int CODE_L7Z = 63;
    public static final int CODE_L8D = 64;
    public static final int CODE_L8P = 65;
    public static final int CODE_L4A = 66;
    public static final int CODE_L4B = 67;
    public static final int CODE_L4X = 68;
    public static final int CODE_L6D = 69;
    public static final int CODE_L6P = 70;
    public static final int MAXCODE = 70;

    // Positioning modes
    public static final int PMODE_SINGLE = 0;
    public static final int PMODE_DGPS = 1;
    public static final int PMODE_KINEMA = 2;
    public static final int PMODE_STATIC = 3;
    public static final int PMODE_STATIC_START = 4;
    public static final int PMODE_MOVEB = 5;
    public static final int PMODE_FIXED = 6;
    public static final int PMODE_PPP_KINEMA = 7;
    public static final int PMODE_PPP_STATIC = 8;
    public static final int PMODE_PPP_FIXED = 9;

    // Solution formats
    public static final int SOLF_LLH = 0;
    public static final int SOLF_XYZ = 1;
    public static final int SOLF_ENU = 2;
    public static final int SOLF_NMEA = 3;
    public static final int SOLF_STAT = 4;
    public static final int SOLF_GSIF = 5;

    // Solution status
    public static final int SOLQ_NONE = 0;
    public static final int SOLQ_FIX = 1;
    public static final int SOLQ_FLOAT = 2;
    public static final int SOLQ_SBAS = 3;
    public static final int SOLQ_DGPS = 4;
    public static final int SOLQ_SINGLE = 5;
    public static final int SOLQ_PPP = 6;
    public static final int SOLQ_DR = 7;
    public static final int MAXSOLQ = 7;

    // Solution type
    public static final int SOLTYPE_FORWARD = 0;
    public static final int SOLTYPE_BACKWARD = 1;
    public static final int SOLTYPE_COMBINED = 2;
    public static final int SOLTYPE_COMBINED_NORESET = 3;
    public static final int SOLMODE_SINGLE_DIR = 0;
    public static final int SOLMODE_COMBINED = 1;

    // Time format
    public static final int TIMES_GPST = 0;
    public static final int TIMES_UTC = 1;
    public static final int TIMES_JST = 2;

    // Ionosphere options
    public static final int IONOOPT_OFF = 0;
    public static final int IONOOPT_BRDC = 1;
    public static final int IONOOPT_SBAS = 2;
    public static final int IONOOPT_IFLC = 3;
    public static final int IONOOPT_EST = 4;
    public static final int IONOOPT_TEC = 5;
    public static final int IONOOPT_QZS = 6;

    // Troposphere options
    public static final int TROPOPT_OFF = 0;
    public static final int TROPOPT_SAAS = 1;
    public static final int TROPOPT_SBAS = 2;
    public static final int TROPOPT_EST = 3;
    public static final int TROPOPT_ESTG = 4;

    // Ephemeris options
    public static final int EPHOPT_BRDC = 0;
    public static final int EPHOPT_PREC = 1;
    public static final int EPHOPT_SBAS = 2;
    public static final int EPHOPT_SSRAPC = 3;
    public static final int EPHOPT_SSRCOM = 4;

    // AR mode
    public static final int ARMODE_OFF = 0;
    public static final int ARMODE_CONT = 1;
    public static final int ARMODE_INST = 2;
    public static final int ARMODE_FIXHOLD = 3;

    // GLO AR mode
    public static final int GLO_ARMODE_OFF = 0;
    public static final int GLO_ARMODE_ON = 1;
    public static final int GLO_ARMODE_AUTOCAL = 2;
    public static final int GLO_ARMODE_FIXHOLD = 3;

    // SBAS options
    public static final int SBSOPT_LCORR = 1;
    public static final int SBSOPT_FCORR = 2;
    public static final int SBSOPT_ICORR = 4;
    public static final int SBSOPT_RANGE = 8;

    // Position options
    public static final int POSOPT_POS_LLH = 0;
    public static final int POSOPT_POS_XYZ = 1;
    public static final int POSOPT_SINGLE = 2;
    public static final int POSOPT_FILE = 3;
    public static final int POSOPT_RINEX = 4;
    public static final int POSOPT_RTCM = 5;

    // Processing mode (library-level)
    public static final int PROCMODE_REALTIME = 0;
    public static final int PROCMODE_POST = 1;

    // Reference station position mode (library-level)
    public static final int REFPOS_FIXED = 0;
    public static final int REFPOS_SPP_AVERAGE = 1;
    public static final int REFPOS_RTCM = 2;

    // Stream types
    public static final int STR_NONE = 0;
    public static final int STR_SERIAL = 1;
    public static final int STR_FILE = 2;
    public static final int STR_TCPSVR = 3;
    public static final int STR_TCPCLI = 4;
    public static final int STR_NTRIPSVR = 5;
    public static final int STR_NTRIPCLI = 6;
    public static final int STR_FTP = 7;
    public static final int STR_HTTP = 8;
    public static final int STR_NTRIPCAS = 9;
    public static final int STR_UDPSVR = 10;
    public static final int STR_UDPCLI = 11;
    public static final int STR_MEMBUF = 12;

    // Stream formats
    public static final int STRFMT_RTCM2 = 0;
    public static final int STRFMT_RTCM3 = 1;
    public static final int STRFMT_OEM4 = 2;
    public static final int STRFMT_UBX = 4;
    public static final int STRFMT_SBP = 5;
    public static final int STRFMT_CRES = 6;
    public static final int STRFMT_STQ = 7;
    public static final int STRFMT_JAVAD = 8;
    public static final int STRFMT_NVS = 9;
    public static final int STRFMT_BINEX = 10;
    public static final int STRFMT_RT17 = 11;
    public static final int STRFMT_SEPT = 12;
    public static final int STRFMT_UNICORE = 14;
    public static final int STRFMT_RINEX = 15;
    public static final int STRFMT_SP3 = 16;
    public static final int STRFMT_RNXCLK = 17;
    public static final int STRFMT_SBAS = 18;
    public static final int STRFMT_NMEA = 19;
    public static final int MAXRCVFMT = 14;

    // Stream modes
    public static final int STR_MODE_R = 0x1;
    public static final int STR_MODE_W = 0x2;
    public static final int STR_MODE_RW = 0x3;

    // Geoid models
    public static final int GEOID_EMBEDDED = 0;
    public static final int GEOID_EGM96_M150 = 1;
    public static final int GEOID_EGM2008_M25 = 2;
    public static final int GEOID_EGM2008_M10 = 3;
    public static final int GEOID_GSI2000_M15 = 4;
    public static final int GEOID_RAF09 = 5;

    // Download options
    public static final int DLOPT_FORCE = 0x01;
    public static final int DLOPT_KEEPCMP = 0x02;
    public static final int DLOPT_HOLDERR = 0x04;
    public static final int DLOPT_HOLDLST = 0x08;

    // LLI (Loss of Lock Indicator)
    public static final int LLI_SLIP = 0x01;
    public static final int LLI_HALFC = 0x02;
    public static final int LLI_BOCTRK = 0x04;
    public static final int LLI_HALFA = 0x40;
    public static final int LLI_HALFS = 0x80;

    // 2^-n constants
    public static final double P2_5 = 0.03125;
    public static final double P2_6 = 0.015625;
    public static final double P2_11 = 4.882812500000000E-04;
    public static final double P2_15 = 3.051757812500000E-05;
    public static final double P2_17 = 7.629394531250000E-06;
    public static final double P2_19 = 1.907348632812500E-06;
    public static final double P2_20 = 9.536743164062500E-07;
    public static final double P2_21 = 4.768371582031250E-07;
    public static final double P2_23 = 1.192092895507810E-07;
    public static final double P2_24 = 5.960464477539063E-08;
    public static final double P2_27 = 7.450580596923828E-09;
    public static final double P2_29 = 1.862645149230957E-09;
    public static final double P2_30 = 9.313225746154785E-10;
    public static final double P2_31 = 4.656612873077393E-10;
    public static final double P2_32 = 2.328306436538696E-10;
    public static final double P2_33 = 1.164153218269348E-10;
    public static final double P2_35 = 2.910383045673370E-11;
    public static final double P2_38 = 3.637978807091710E-12;
    public static final double P2_39 = 1.818989403545856E-12;
    public static final double P2_40 = 9.094947017729280E-13;
    public static final double P2_43 = 1.136868377216160E-13;
    public static final double P2_48 = 3.552713678800501E-15;
    public static final double P2_50 = 8.881784197001252E-16;
    public static final double P2_55 = 2.775557561562891E-17;
    public static final double P2_59 = 1.734723475976807E-18;
    public static final double P2_66 = 1.355252715606880E-20;
    public static final double P2_46 = 1.421085471520200E-14;
    public static final double P2_34 = 5.820766091346741E-11;
}