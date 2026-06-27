package org.rtklib.java.common;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Geph;

/**
 * Satellite system / PRN / sat number conversion utilities.
 * Aligned with RTKLIB rtkcmn.c satno/satsys/satid2no.
 */
public final class SatUtils {
    private SatUtils() {
        // Utility class
    }

    /**
     * Convert system code and PRN to satellite number.
     * @param sys System code (Constants.SYS_*)
     * @param prn PRN number
     * @return Satellite number (0: invalid)
     */
    public static int satno(int sys, int prn) {
        if (prn <= 0) return 0;
        switch (sys) {
            case Constants.SYS_GPS:
                if (prn < Constants.MINPRNGPS || prn > Constants.MAXPRNGPS) return 0;
                return prn - Constants.MINPRNGPS + 1;
            case Constants.SYS_GLO:
                if (prn < Constants.MINPRNGLO || prn > Constants.MAXPRNGLO) return 0;
                return Constants.NSATGPS + prn - Constants.MINPRNGLO + 1;
            case Constants.SYS_GAL:
                if (prn < Constants.MINPRNGAL || prn > Constants.MAXPRNGAL) return 0;
                return Constants.NSATGPS + Constants.NSATGLO + prn - Constants.MINPRNGAL + 1;
            case Constants.SYS_QZS:
                if (prn < Constants.MINPRNQZS || prn > Constants.MAXPRNQZS) return 0;
                return Constants.NSATGPS + Constants.NSATGLO + Constants.NSATGAL + prn - Constants.MINPRNQZS + 1;
            case Constants.SYS_CMP:
                if (prn < Constants.MINPRNCMP || prn > Constants.MAXPRNCMP) return 0;
                return Constants.NSATGPS + Constants.NSATGLO + Constants.NSATGAL + Constants.NSATQZS
                        + prn - Constants.MINPRNCMP + 1;
            case Constants.SYS_IRN:
                if (prn < Constants.MINPRNIRN || prn > Constants.MAXPRNIRN) return 0;
                return Constants.NSATGPS + Constants.NSATGLO + Constants.NSATGAL + Constants.NSATQZS
                        + Constants.NSATCMP + prn - Constants.MINPRNIRN + 1;
            case Constants.SYS_LEO:
                if (prn < Constants.MINPRNLEO || prn > Constants.MAXPRNLEO) return 0;
                return Constants.NSATGPS + Constants.NSATGLO + Constants.NSATGAL + Constants.NSATQZS
                        + Constants.NSATCMP + Constants.NSATIRN + prn - Constants.MINPRNLEO + 1;
            case Constants.SYS_SBS:
                if (prn < Constants.MINPRNSBS || prn > Constants.MAXPRNSBS) return 0;
                return Constants.NSATGPS + Constants.NSATGLO + Constants.NSATGAL + Constants.NSATQZS
                        + Constants.NSATCMP + Constants.NSATIRN + Constants.NSATLEO
                        + prn - Constants.MINPRNSBS + 1;
            default:
                return 0;
        }
    }

    /**
     * Convert satellite number to system code and PRN.
     * @param sat Satellite number
     * @param prn Output PRN (null to skip)
     * @return System code
     */
    public static int satsys(int sat, int[] prn) {
        int sys = Constants.SYS_NONE;
        if (sat <= 0 || sat > Constants.MAXSAT) {
            sat = 0;
        } else if (sat <= Constants.NSATGPS) {
            sys = Constants.SYS_GPS; sat += Constants.MINPRNGPS - 1;
        } else if ((sat -= Constants.NSATGPS) <= Constants.NSATGLO) {
            sys = Constants.SYS_GLO; sat += Constants.MINPRNGLO - 1;
        } else if ((sat -= Constants.NSATGLO) <= Constants.NSATGAL) {
            sys = Constants.SYS_GAL; sat += Constants.MINPRNGAL - 1;
        } else if ((sat -= Constants.NSATGAL) <= Constants.NSATQZS) {
            sys = Constants.SYS_QZS; sat += Constants.MINPRNQZS - 1;
        } else if ((sat -= Constants.NSATQZS) <= Constants.NSATCMP) {
            sys = Constants.SYS_CMP; sat += Constants.MINPRNCMP - 1;
        } else if ((sat -= Constants.NSATCMP) <= Constants.NSATIRN) {
            sys = Constants.SYS_IRN; sat += Constants.MINPRNIRN - 1;
        } else if ((sat -= Constants.NSATIRN) <= Constants.NSATLEO) {
            sys = Constants.SYS_LEO; sat += Constants.MINPRNLEO - 1;
        } else if ((sat -= Constants.NSATLEO) <= Constants.NSATSBS) {
            sys = Constants.SYS_SBS; sat += Constants.MINPRNSBS - 1;
        } else {
            sat = 0;
        }
        if (prn != null) prn[0] = sat;
        return sys;
    }

    /**
     * Convert satellite ID string to satellite number.
     * Format examples: "G01", "R12", "E11", "J01", "C06", "I01", "S23".
     * @param id Satellite ID string
     * @return Satellite number (0: invalid)
     */
    public static int satid2no(String id) {
        if (id == null || id.length() < 3) return 0;
        char code = id.charAt(0);
        int sys;
        switch (code) {
            case 'G': sys = Constants.SYS_GPS; break;
            case 'R': sys = Constants.SYS_GLO; break;
            case 'E': sys = Constants.SYS_GAL; break;
            case 'J': sys = Constants.SYS_QZS; break;
            case 'C': sys = Constants.SYS_CMP; break;
            case 'I': sys = Constants.SYS_IRN; break;
            case 'L': sys = Constants.SYS_LEO; break;
            case 'S': sys = Constants.SYS_SBS; break;
            default: return 0;
        }
        int prn;
        try {
            prn = Integer.parseInt(id.substring(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
        return satno(sys, prn);
    }

    /**
     * Convert satellite number to satellite ID string (e.g., "G01").
     * @param sat Satellite number
     * @return ID string
     */
    public static String satno2id(int sat) {
        int[] prn = new int[1];
        int sys = satsys(sat, prn);
        if (sys == 0) return "";
        char code;
        switch (sys) {
            case Constants.SYS_GPS: code = 'G'; break;
            case Constants.SYS_GLO: code = 'R'; break;
            case Constants.SYS_GAL: code = 'E'; break;
            case Constants.SYS_QZS: code = 'J'; break;
            case Constants.SYS_CMP: code = 'C'; break;
            case Constants.SYS_IRN: code = 'I'; break;
            case Constants.SYS_LEO: code = 'L'; break;
            case Constants.SYS_SBS: code = 'S'; break;
            default: return "";
        }
        return String.format("%c%02d", code, prn[0]);
    }

    /**
     * Convert system code to system index (0..6).
     * @param sys System code
     * @return System index (0:GPS, 1:GLO, 2:GAL, 3:QZS, 4:CMP, 5:IRN, 6:SBS)
     */
    public static int sys2idx(int sys) {
        switch (sys) {
            case Constants.SYS_GPS: return 0;
            case Constants.SYS_GLO: return 1;
            case Constants.SYS_GAL: return 2;
            case Constants.SYS_QZS: return 3;
            case Constants.SYS_CMP: return 4;
            case Constants.SYS_IRN: return 5;
            case Constants.SYS_SBS: return 6;
            default: return -1;
        }
    }

    /**
     * Convert system index to system code.
     * @param idx System index
     * @return System code
     */
    public static int idx2sys(int idx) {
        switch (idx) {
            case 0: return Constants.SYS_GPS;
            case 1: return Constants.SYS_GLO;
            case 2: return Constants.SYS_GAL;
            case 3: return Constants.SYS_QZS;
            case 4: return Constants.SYS_CMP;
            case 5: return Constants.SYS_IRN;
            case 6: return Constants.SYS_SBS;
            default: return 0;
        }
    }

        public static double sat2freq(int sat, int code, org.rtklib.java.data.Nav nav) {
        int[] prn = new int[1];
        int sys = satsys(sat, prn);
        int fcn = 0;
        
        if (sys == Constants.SYS_GLO && nav != null) {
            fcn = -8;
            if (nav.geph != null) {
                for (int i = 0; i < nav.ng; i++) {
                    if (nav.geph[i] != null && nav.geph[i].sat == sat) {
                        fcn = nav.geph[i].frq;
                        break;
                    }
                }
                if (fcn == -8 && nav.glo_fcn != null && prn[0] > 0 && prn[0] <= nav.glo_fcn.length) {
                    if (nav.glo_fcn[prn[0] - 1] > 0) {
                        fcn = nav.glo_fcn[prn[0] - 1] - 8;
                    }
                }
            }
        }
        return ObsCode.code2freq(sys, code, fcn);
    }
}