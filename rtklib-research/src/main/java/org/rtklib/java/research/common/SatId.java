package org.rtklib.java.research.common;

/**
 * 卫星编号工具（research模块自有）。
 *
 * <p>卫星号↔(系统,PRN)双向转换，逻辑对齐RTKLIB satno/satsys。</p>
 */
public final class SatId {
    private SatId() {}

    public static int satno(int sys, int prn) {
        if (prn <= 0) return 0;
        switch (sys) {
            case GnssConst.SYS_GPS:
                if (prn < GnssConst.MINPRNGPS || prn > GnssConst.MAXPRNGPS) return 0;
                return prn - GnssConst.MINPRNGPS + 1;
            case GnssConst.SYS_GLO:
                if (prn < GnssConst.MINPRNGLO || prn > GnssConst.MAXPRNGLO) return 0;
                return GnssConst.NSATGPS + prn - GnssConst.MINPRNGLO + 1;
            case GnssConst.SYS_GAL:
                if (prn < GnssConst.MINPRNGAL || prn > GnssConst.MAXPRNGAL) return 0;
                return GnssConst.NSATGPS + GnssConst.NSATGLO + prn - GnssConst.MINPRNGAL + 1;
            case GnssConst.SYS_QZS:
                if (prn < GnssConst.MINPRNQZS || prn > GnssConst.MAXPRNQZS) return 0;
                return GnssConst.NSATGPS + GnssConst.NSATGLO + GnssConst.NSATGAL + prn - GnssConst.MINPRNQZS + 1;
            case GnssConst.SYS_CMP:
                if (prn < GnssConst.MINPRNCMP || prn > GnssConst.MAXPRNCMP) return 0;
                return GnssConst.NSATGPS + GnssConst.NSATGLO + GnssConst.NSATGAL + GnssConst.NSATQZS
                        + prn - GnssConst.MINPRNCMP + 1;
            case GnssConst.SYS_IRN:
                if (prn < GnssConst.MINPRNIRN || prn > GnssConst.MAXPRNIRN) return 0;
                return GnssConst.NSATGPS + GnssConst.NSATGLO + GnssConst.NSATGAL + GnssConst.NSATQZS
                        + GnssConst.NSATCMP + prn - GnssConst.MINPRNIRN + 1;
            case GnssConst.SYS_LEO:
                if (prn < GnssConst.MINPRNLEO || prn > GnssConst.MAXPRNLEO) return 0;
                return GnssConst.NSATGPS + GnssConst.NSATGLO + GnssConst.NSATGAL + GnssConst.NSATQZS
                        + GnssConst.NSATCMP + GnssConst.NSATIRN + prn - GnssConst.MINPRNLEO + 1;
            case GnssConst.SYS_SBS:
                if (prn < GnssConst.MINPRNSBS || prn > GnssConst.MAXPRNSBS) return 0;
                return GnssConst.NSATGPS + GnssConst.NSATGLO + GnssConst.NSATGAL + GnssConst.NSATQZS
                        + GnssConst.NSATCMP + GnssConst.NSATIRN + GnssConst.NSATLEO
                        + prn - GnssConst.MINPRNSBS + 1;
            default:
                return 0;
        }
    }

    public static int[] satsys(int sat) {
        int sys = GnssConst.SYS_NONE;
        int prn = 0;
        int s = sat;
        if (s <= 0 || s > GnssConst.MAXSAT) {
            return new int[]{sys, 0};
        }
        if (s <= GnssConst.NSATGPS) {
            sys = GnssConst.SYS_GPS; prn = s + GnssConst.MINPRNGPS - 1;
        } else if ((s -= GnssConst.NSATGPS) <= GnssConst.NSATGLO) {
            sys = GnssConst.SYS_GLO; prn = s + GnssConst.MINPRNGLO - 1;
        } else if ((s -= GnssConst.NSATGLO) <= GnssConst.NSATGAL) {
            sys = GnssConst.SYS_GAL; prn = s + GnssConst.MINPRNGAL - 1;
        } else if ((s -= GnssConst.NSATGAL) <= GnssConst.NSATQZS) {
            sys = GnssConst.SYS_QZS; prn = s + GnssConst.MINPRNQZS - 1;
        } else if ((s -= GnssConst.NSATQZS) <= GnssConst.NSATCMP) {
            sys = GnssConst.SYS_CMP; prn = s + GnssConst.MINPRNCMP - 1;
        } else if ((s -= GnssConst.NSATCMP) <= GnssConst.NSATIRN) {
            sys = GnssConst.SYS_IRN; prn = s + GnssConst.MINPRNIRN - 1;
        } else if ((s -= GnssConst.NSATIRN) <= GnssConst.NSATLEO) {
            sys = GnssConst.SYS_LEO; prn = s + GnssConst.MINPRNLEO - 1;
        } else if ((s -= GnssConst.NSATLEO) <= GnssConst.NSATSBS) {
            sys = GnssConst.SYS_SBS; prn = s + GnssConst.MINPRNSBS - 1;
        }
        return new int[]{sys, prn};
    }

    public static String satIdString(int sat) {
        int[] sysPrn = satsys(sat);
        int sys = sysPrn[0], prn = sysPrn[1];
        char c;
        switch (sys) {
            case GnssConst.SYS_GPS: c = 'G'; break;
            case GnssConst.SYS_GLO: c = 'R'; break;
            case GnssConst.SYS_GAL: c = 'E'; break;
            case GnssConst.SYS_QZS: c = 'J'; break;
            case GnssConst.SYS_CMP: c = 'C'; break;
            case GnssConst.SYS_IRN: c = 'I'; break;
            case GnssConst.SYS_LEO: c = 'L'; break;
            case GnssConst.SYS_SBS: c = 'S'; break;
            default: c = '?'; break;
        }
        return String.format("%c%02d", c, prn);
    }

    public static double systemErrorFactor(int sys) {
        switch (sys) {
            case GnssConst.SYS_GPS: return GnssConst.EFACT_GPS;
            case GnssConst.SYS_GLO: return GnssConst.EFACT_GLO;
            case GnssConst.SYS_GAL: return GnssConst.EFACT_GAL;
            case GnssConst.SYS_QZS: return GnssConst.EFACT_QZS;
            case GnssConst.SYS_CMP: return GnssConst.EFACT_CMP;
            case GnssConst.SYS_IRN: return GnssConst.EFACT_IRN;
            case GnssConst.SYS_SBS: return GnssConst.EFACT_SBS;
            default: return 1.0;
        }
    }
}