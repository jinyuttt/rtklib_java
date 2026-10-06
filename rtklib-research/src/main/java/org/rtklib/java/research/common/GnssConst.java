package org.rtklib.java.research.common;

/**
 * GNSS物理常数与系统参数（research模块自有）。
 *
 * <p>与rtklib-core的Constants完全独立，数值对齐RTKLIB 2.5.0。</p>
 */
public final class GnssConst {
    private GnssConst() {}

    public static final double PI = 3.1415926535897932;
    public static final double D2R = PI / 180.0;
    public static final double R2D = 180.0 / PI;

    public static final double CLIGHT = 299792458.0;

    public static final double OMGE = 7.2921151467E-5;
    public static final double RE_WGS84 = 6378137.0;
    public static final double FE_WGS84 = 1.0 / 298.257223563;
    public static final double HION = 350000.0;

    public static final int MAXFREQ = 6;

    public static final double FREQL1 = 1.57542E9;
    public static final double FREQL2 = 1.22760E9;
    public static final double FREQE5b = 1.20714E9;
    public static final double FREQL5 = 1.17645E9;
    public static final double FREQL6 = 1.27875E9;
    public static final double FREQE5ab = 1.191795E9;
    public static final double FREQE1 = 1.57542E9;
    public static final double FREQE5a = 1.17645E9;
    public static final double FREQB1I = 1.561098E9;
    public static final double FREQB2I = 1.20714E9;
    public static final double FREQB3I = 1.26852E9;
    public static final double FREQs = 2.492028E9;

    public static final double FREQ1_GLO = 1.60200E9;
    public static final double DFRQ1_GLO = 0.56250E6;
    public static final double FREQ2_GLO = 1.24600E9;
    public static final double DFRQ2_GLO = 0.43750E6;
    public static final double FREQ3_GLO = 1.202025E9;
    public static final double FREQ1a_GLO = 1.600995E9;
    public static final double FREQ2a_GLO = 1.248060E9;

    public static final double[] WAVELENGTHS = {
        CLIGHT / FREQL1,
        CLIGHT / FREQL2,
        CLIGHT / FREQL5,
        CLIGHT / FREQL6,
        CLIGHT / FREQE5b,
        CLIGHT / FREQE5ab
    };

    public static final double GAMMA_L1L2 = Math.pow(FREQL1 / FREQL2, 2);
    public static final double GAMMA_L1L5 = Math.pow(FREQL1 / FREQL5, 2);

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

    public static final int MINPRNGPS = 1, MAXPRNGPS = 32;
    public static final int MINPRNGLO = 1, MAXPRNGLO = 27;
    public static final int MINPRNGAL = 1, MAXPRNGAL = 36;
    public static final int MINPRNQZS = 193, MAXPRNQZS = 202;
    public static final int MINPRNCMP = 1, MAXPRNCMP = 46;
    public static final int MINPRNIRN = 1, MAXPRNIRN = 14;
    public static final int MINPRNLEO = 1, MAXPRNLEO = 96;
    public static final int MINPRNSBS = 120, MAXPRNSBS = 158;

    public static final int NSATGPS = MAXPRNGPS - MINPRNGPS + 1;
    public static final int NSATGLO = MAXPRNGLO - MINPRNGLO + 1;
    public static final int NSATGAL = MAXPRNGAL - MINPRNGAL + 1;
    public static final int NSATQZS = MAXPRNQZS - MINPRNQZS + 1;
    public static final int NSATCMP = MAXPRNCMP - MINPRNCMP + 1;
    public static final int NSATIRN = MAXPRNIRN - MINPRNIRN + 1;
    public static final int NSATLEO = MAXPRNLEO - MINPRNLEO + 1;
    public static final int NSATSBS = MAXPRNSBS - MINPRNSBS + 1;
    public static final int MAXSAT = NSATGPS + NSATGLO + NSATGAL + NSATQZS + NSATCMP + NSATIRN + NSATLEO + NSATSBS;

    public static final double EFACT_GPS = 1.0;
    public static final double EFACT_GLO = 1.5;
    public static final double EFACT_GAL = 1.0;
    public static final double EFACT_QZS = 1.0;
    public static final double EFACT_CMP = 1.0;
    public static final double EFACT_IRN = 1.5;
    public static final double EFACT_SBS = 3.0;

    public static final int NFREQ = 6;
    public static final int NEXOBS = 2;

    public static final int SOLQ_NONE = 0;
    public static final int SOLQ_FIX = 1;
    public static final int SOLQ_FLOAT = 2;
    public static final int SOLQ_SBAS = 3;
    public static final int SOLQ_DGPS = 4;
    public static final int SOLQ_SINGLE = 5;
    public static final int SOLQ_PPP = 6;
    public static final int SOLQ_DR = 7;
}