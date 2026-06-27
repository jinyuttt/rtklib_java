package org.rtklib.java.common;

import org.rtklib.java.constants.Constants;

/**
 * Observation code / frequency conversion utilities.
 * Aligned with RTKLIB rtkcmn.c obs2code/code2obs/code2freq/code2idx/sat2freq.
 */
public final class ObsCode {
    private ObsCode() {
        // Utility class
    }

    /**
     * Obscode table, indexed by CODE_* (1..MAXCODE).
     * Aligned with RTKLIB obscodes[].
     */
    public static final String[] CODES = {
        "",   "1C", "1P", "1W", "1Y", "1M", "1N", "1S", "1L", "1E", /*  0- 9 */
        "1A", "1B", "1X", "1Z", "2C", "2D", "2S", "2L", "2X", "2P", /* 10-19 */
        "2W", "2Y", "2M", "2N", "5I", "5Q", "5X", "7I", "7Q", "7X", /* 20-29 */
        "6A", "6B", "6C", "6X", "6Z", "6S", "6L", "8I", "8Q", "8X", /* 30-39 */
        "2I", "2Q", "6I", "6Q", "3I", "3Q", "3X", "1I", "1Q", "5A", /* 40-49 */
        "5B", "5C", "9A", "9B", "9C", "9X", "1D", "5D", "5P", "5Z", /* 50-59 */
        "6E", "7D", "7P", "7Z", "8D", "8P", "4A", "4B", "4X", "6D", /* 60-69 */
        "6P"
    };

    /**
     * Code priority for each (system, frequency-index).
     * Aligned with RTKLIB rtkcmn.c codepris[][][].
     * Order: highest priority first (leftmost = highest).
     * L1/E1/B1  L2/E5b/B2b L5/E5a/B2a E6/LEX/B3  E5(a+b)    B2ab
     */
    public static final String[][] CODEPRIS = {
        {"CPYWMNSLX", "CPYWMNDLSX", "IQX",     "",       "",        ""},        // GPS
        {"CPABX",     "CPABX",      "IQX",     "",       "",        ""},        // GLO
        {"CABXZ",     "XIQ",        "XIQ",     "ABCXZ",  "IQX",     ""},        // GAL
        {"CLSXZBE",   "LSX",        "IQXDPZ",  "LSXEZ",  "",        ""},        // QZS
        {"C",         "IQX",        "",        "",       "",        ""},        // SBS
        {"IQX",       "IQXDPZ",     "DPX",     "IQXDPZA","DPXSLZAN","DPX"},     // BDS
        {"ABCX",      "ABCX",       "DPX",     "",       "",        ""}         // IRN
    };

    /**
     * Convert obs code string to numeric code.
     * @param obs Obs code string (e.g. "1C", "2P")
     * @return CODE_* constant (0 if not found)
     */
    public static int obs2code(String obs) {
        if (obs == null) return 0;
        for (int i = 1; i <= Constants.MAXCODE; i++) {
            if (CODES[i].equals(obs)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Convert obs code numeric to string.
     * @param code CODE_* constant
     * @return Obs code string
     */
    public static String code2obs(int code) {
        if (code <= 0 || code > Constants.MAXCODE) return "";
        return CODES[code];
    }

    /**
     * Get frequency index for system + obs code.
     * @param sys  System code
     * @param code Obs code
     * @return Frequency index (-1: error)
     */
    public static int code2idx(int sys, int code) {
        double freq = code2freq(sys, code, 0);
        if (freq <= 0.0) return -1;
        switch (sys) {
            case Constants.SYS_GPS: return code2freqGps(code);
            case Constants.SYS_GLO: return code2freqGlo(code);
            case Constants.SYS_GAL: return code2freqGal(code);
            case Constants.SYS_QZS: return code2freqQzs(code);
            case Constants.SYS_SBS: return code2freqSbs(code);
            case Constants.SYS_CMP: return code2freqBds(code);
            case Constants.SYS_IRN: return code2freqIrn(code);
        }
        return -1;
    }

    /**
     * Get carrier frequency for system + obs code (GLONASS uses fcn).
     * @param sys  System code
     * @param code Obs code
     * @param fcn  GLONASS frequency channel number (-7..6)
     * @return Frequency in Hz (0.0 on error)
     */
    public static double code2freq(int sys, int code, int fcn) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return 0.0;
        switch (sys) {
            case Constants.SYS_GPS: {
                char c = obs.charAt(0);
                if (c == '1') return Constants.FREQL1;
                if (c == '2') return Constants.FREQL2;
                if (c == '5') return Constants.FREQL5;
                return 0.0;
            }
            case Constants.SYS_GLO: {
                char c = obs.charAt(0);
                // fcn range check removed to match RTKLIB C behavior
                if (c == '1') return Constants.FREQ1_GLO + Constants.DFRQ1_GLO * fcn;
                if (c == '2') return Constants.FREQ2_GLO + Constants.DFRQ2_GLO * fcn;
                if (c == '3') return Constants.FREQ3_GLO;
                if (c == '4') return Constants.FREQ1a_GLO;
                if (c == '6') return Constants.FREQ2a_GLO;
                return 0.0;
            }
            case Constants.SYS_GAL: {
                char c = obs.charAt(0);
                if (c == '1') return Constants.FREQL1;
                if (c == '5') return Constants.FREQL5;
                if (c == '6') return Constants.FREQL6;
                if (c == '7') return Constants.FREQE5b;
                if (c == '8') return Constants.FREQE5ab;
                return 0.0;
            }
            case Constants.SYS_QZS: {
                char c = obs.charAt(0);
                if (c == '1') return Constants.FREQL1;
                if (c == '2') return Constants.FREQL2;
                if (c == '5') return Constants.FREQL5;
                if (c == '6') return Constants.FREQL6;
                return 0.0;
            }
            case Constants.SYS_SBS: {
                char c = obs.charAt(0);
                if (c == '1') return Constants.FREQL1;
                if (c == '5') return Constants.FREQL5;
                return 0.0;
            }
            case Constants.SYS_CMP: {
                char c = obs.charAt(0);
                if (c == '1') return Constants.FREQL1;
                if (c == '2') return Constants.FREQ1_CMP;
                if (c == '5') return Constants.FREQL5;
                if (c == '6') return Constants.FREQ3_CMP;
                if (c == '7') return Constants.FREQ2_CMP;
                if (c == '8') return Constants.FREQE5ab;
                return 0.0;
            }
            case Constants.SYS_IRN: {
                char c = obs.charAt(0);
                if (c == '1') return Constants.FREQL1;
                if (c == '5') return Constants.FREQL5;
                if (c == '9') return Constants.FREQs;
                return 0.0;
            }
        }
        return 0.0;
    }

    private static int code2freqGps(int code) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return -1;
        char c = obs.charAt(0);
        if (c == '1') return 0;
        if (c == '2') return 1;
        if (c == '5') return 2;
        return -1;
    }

    private static int code2freqGlo(int code) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return -1;
        char c = obs.charAt(0);
        if (c == '1') return 0;
        if (c == '2') return 1;
        if (c == '3') return 2;
        if (c == '4') return 3;
        if (c == '6') return 4;
        return -1;
    }

    private static int code2freqGal(int code) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return -1;
        char c = obs.charAt(0);
        if (c == '1') return 0;
        if (c == '7') return 1;
        if (c == '5') return 2;
        if (c == '6') return 3;
        if (c == '8') return 4;
        return -1;
    }

    private static int code2freqQzs(int code) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return -1;
        char c = obs.charAt(0);
        if (c == '1') return 0;
        if (c == '2') return 1;
        if (c == '5') return 2;
        if (c == '6') return 3;
        return -1;
    }

    private static int code2freqSbs(int code) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return -1;
        char c = obs.charAt(0);
        if (c == '1') return 0;
        if (c == '5') return 1;
        return -1;
    }

    private static int code2freqBds(int code) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return -1;
        char c = obs.charAt(0);
        if (c == '2') return 0;  /* B1I */
        if (c == '7') return 1;  /* B2b */
        if (c == '5') return 2;  /* B2a */
        if (c == '6') return 3;  /* B3  */
        if (c == '1') return 4;  /* B1C */
        if (c == '8') return 5;  /* B2ab */
        return -1;
    }

    private static int code2freqIrn(int code) {
        String obs = code2obs(code);
        if (obs.isEmpty()) return -1;
        char c = obs.charAt(0);
        if (c == '5') return 0;
        if (c == '9') return 1;
        if (c == '1') return 2;
        return -1;
    }

    /**
     * Convert satellite number to PRN.
     * @param sat Satellite number
     * @return PRN number
     */
    public static int satToPrn(int sat) {
        int[] prnArr = new int[1];
        SatUtils.satsys(sat, prnArr);
        return prnArr[0];
    }

    /**
     * Convert satellite number to system character.
     * @param sat Satellite number
     * @return System character (G, R, E, C, J, I, S)
     */
    public static String satToSysChar(int sat) {
        int sys = SatUtils.satsys(sat, null);
        switch (sys) {
            case Constants.SYS_GPS: return "G";
            case Constants.SYS_GLO: return "R";
            case Constants.SYS_GAL: return "E";
            case Constants.SYS_CMP: return "C";
            case Constants.SYS_QZS: return "J";
            case Constants.SYS_IRN: return "I";
            case Constants.SYS_SBS: return "S";
            default: return "";
        }
    }

    public static int charToSys(String c) {
        if (c == null || c.isEmpty()) return 0;
        switch (c.charAt(0)) {
            case 'G': return Constants.SYS_GPS;
            case 'R': return Constants.SYS_GLO;
            case 'E': return Constants.SYS_GAL;
            case 'C': return Constants.SYS_CMP;
            case 'J': return Constants.SYS_QZS;
            case 'I': return Constants.SYS_IRN;
            case 'S': return Constants.SYS_SBS;
            default: return 0;
        }
    }

    /**
     * Convert frequency index to code character.
     * @param freq Frequency index
     * @return Code character
     */
    public static String freqToCode(int freq) {
        switch (freq) {
            case 0: return "C";
            case 1: return "P";
            case 2: return "X";
            default: return "";
        }
    }

    /**
     * Get code priority for system + code.
     * Aligned with RTKLIB rtkcmn.c getcodepri().
     * @param sys  System code
     * @param code Obs code (CODE_* constant)
     * @return Priority value (0: not used, higher: higher priority)
     */
    public static int getcodepri(int sys, int code) {
        int i, j;
        switch (sys) {
            case Constants.SYS_GPS: i = 0; break;
            case Constants.SYS_GLO: i = 1; break;
            case Constants.SYS_GAL: i = 2; break;
            case Constants.SYS_QZS: i = 3; break;
            case Constants.SYS_SBS: i = 4; break;
            case Constants.SYS_CMP: i = 5; break;
            case Constants.SYS_IRN: i = 6; break;
            default: return 0;
        }
        j = code2idx(sys, code);
        if (j < 0) return 0;
        String obs = code2obs(code);
        if (obs.isEmpty()) return 0;
        char ch = obs.charAt(obs.length() - 1);
        String pris = CODEPRIS[i][j];
        int p = pris.indexOf(ch);
        return p >= 0 ? 14 - p : 0;
    }

    /**
     * Get signal index.
     * Aligned with RTKLIB rtcm3.c sigindex().
     * Handles code priority: highest priority signal per freq gets the main index,
     * others get extended indices (NFREQ+nex) or -1 if no space.
     * @param sys   System code
     * @param code  Code array
     * @param n     Number of signals
     * @param idx   Signal index array (input/output)
     */
    /* [DIFF-C] C version sigindex() accepts an "opt" parameter (rtcm->opt or rnx->opt)
       which allows overriding code priority via -CL, -GL etc. options. Java version
       omits this parameter, using default code priority only. This is acceptable for
       RTCM processing where opt is typically NULL, but may cause differences with RINEX
       processing when code priority options are specified. */
    public static void sigindex(int sys, int[] code, int n, int[] idx) {
        int i, nex, pri;
        int[] pri_h = new int[8];
        int[] index = new int[8];
        int[] ex = new int[32];

        for (i = 0; i < 8; i++) {
            pri_h[i] = 0;
            index[i] = 0;
        }
        for (i = 0; i < 32; i++) {
            ex[i] = 0;
        }

        for (i = 0; i < n; i++) {
            if (code[i] == 0) continue;

            if (idx[i] >= Constants.NFREQ) {
                ex[i] = 1;
                continue;
            }
            if (idx[i] < 0) continue;

            pri = getcodepri(sys, code[i]);

            if (pri > pri_h[idx[i]]) {
                if (index[idx[i]] != 0) ex[index[idx[i]] - 1] = 1;
                pri_h[idx[i]] = pri;
                index[idx[i]] = i + 1;
            } else {
                ex[i] = 1;
            }
        }

        nex = 0;
        for (i = 0; i < n; i++) {
            if (ex[i] == 0) {
                // keep idx[i] as is
            } else if (nex < Constants.NEXOBS) {
                idx[i] = Constants.NFREQ + nex;
                nex++;
            } else {
                idx[i] = -1;
            }
        }
    }
}