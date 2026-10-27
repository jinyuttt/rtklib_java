package org.rtklib.java.vrs.rtcm;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rtcm3MsmEncoder {
    private static final Logger log = LoggerFactory.getLogger(Rtcm3MsmEncoder.class);

    private static final double RANGE_MS = Constants.CLIGHT * 0.001;
    private static final double P2_10 = 1.0 / 1024.0;
    private static final double P2_24 = 1.0 / 16777216.0;
    private static final double P2_29 = 1.0 / 536870912.0;
    private static final double P2_31 = 1.0 / 2147483648.0;

    private static final String[][] MSM_SIG = {
        {"", "1C", "1P", "1W", "", "", "", "2C", "2P", "2W", "", "",
         "", "", "2S", "2L", "2X", "", "", "", "", "5I", "5Q", "5X",
         "", "", "", "", "", "1S", "1L", "1X"},
        {"", "1C", "1P", "", "", "", "", "2C", "2P", "", "", "",
         "", "3I", "3Q", "3X", "", "4A", "4B", "4X", "", "6A", "6B", "6X",
         "", "", "", "", "", "", "", ""},
        {"", "1C", "1A", "1B", "1X", "1Z", "", "6C", "6A", "6B", "6X", "6Z",
         "", "7I", "7Q", "7X", "", "8I", "8Q", "8X", "", "5I", "5Q", "5X",
         "", "", "", "", "", "", "", ""},
        {"", "1C", "1E", "", "1E", "1Z", "1B", "", "6S", "6L", "6X", "6E",
         "6Z", "", "2S", "2L", "2X", "", "", "", "", "5I", "5Q", "5X",
         "5D", "5P", "5Z", "", "", "1S", "1L", "1X"},
        {"", "1C", "", "", "", "", "", "", "", "", "", "",
         "", "", "", "", "", "", "", "", "", "5I", "5Q", "5X",
         "", "", "", "", "", "", "", ""},
        {"", "2I", "2Q", "2X", "1S", "1L", "1Z", "6I", "6Q", "6X", "6D", "6P",
         "6Z", "7I", "7Q", "7X", "", "8D", "8P", "8X", "", "5D", "5P", "5X",
         "7D", "7P", "7Z", "", "", "1D", "1P", "1X"},
        {"", "1D", "1P", "1X", "", "", "", "9A", "9B", "9C", "9X", "",
         "", "", "", "", "", "", "", "", "", "5A", "5B", "5C",
         "5X", "", "", "", "", "", "", ""},
    };

    private Rtcm3MsmEncoder() {
    }

    public static boolean encodeMsm(Rtcm3Encoder enc, int type, int sync) {
        int sys = msmSys(type);
        if (sys == 0) return false;

        int msmType = type % 10;
        if (msmType < 1 || msmType > 7) return false;
        if (msmType != 4 && msmType != 5) {
            log.warn("MSM type {} not yet supported, only MSM4/5 implemented", msmType);
            return false;
        }

        int sigIdx = sysToSigIdx(sys);

        int[] nsatArr = new int[1];
        int[] nsigArr = new int[1];
        int[] ncellArr = new int[1];
        int[] satInd = new int[64];
        int[] sigInd = new int[32];
        int[] cellInd = new int[64 * 32];

        genMsmIndex(enc, sys, sigIdx, nsatArr, nsigArr, ncellArr, satInd, sigInd, cellInd);
        int nsat = nsatArr[0];
        int nsig = nsigArr[0];
        int ncell = ncellArr[0];
        if (nsat == 0) return false;

        if (nsat * nsig > 64) {
            int maxSig = 64 / nsat;
            if (maxSig < 1) maxSig = 1;
            int removed = 0;
            for (int s = 31; s >= 0 && nsig - removed > maxSig; s--) {
                if (sigInd[s] != 0) {
                    sigInd[s] = 0;
                    removed++;
                }
            }
            nsig -= removed;
            nsigArr[0] = nsig;
            ncell = 0;
            for (int ci = 0; ci < 64 * 32; ci++) cellInd[ci] = 0;
            for (int ii = 0; ii < enc.obs.n; ii++) {
                int sat = toSatid(sys, enc.obs.data[ii].sat);
                if (sat == 0) continue;
                for (int jj = 0; jj < Constants.NFREQ + Constants.NEXOBS; jj++) {
                    int sig = toSigid(sys, sigIdx, enc.obs.data[ii].code[jj]);
                    if (sig == 0 || sigInd[sig - 1] == 0) continue;
                    int cell = sigInd[sig - 1] - 1 + (satInd[sat - 1] - 1) * nsig;
                    if (cell < 64 * 32) cellInd[cell] = 1;
                }
            }
            ncell = 0;
            for (int ci = 0; ci < nsat * nsig && ci < 64 * 32; ci++) {
                if (cellInd[ci] != 0 && ncell < 64) cellInd[ci] = ++ncell;
            }
            ncellArr[0] = ncell;
            log.debug("MSM nsat*nsig>64: reduced nsig from {} to {} for sys={}", nsig + removed, nsig, sys);
        }

        double[] rrng = new double[64];
        double[] rrate = new double[64];
        genMsmSat(enc, sys, sigIdx, satInd, rrng, rrate);

        double[] psrng = new double[64];
        double[] phrng = new double[64];
        double[] rate = new double[64];
        double[] lock = new double[64];
        int[] half = new int[64];
        float[] cnr = new float[64];
        genMsmSig(enc, sys, sigIdx, satInd, sigInd, cellInd, nsig,
                  rrng, rrate, psrng, phrng, rate, lock, half, cnr);

        int i = encodeMsmHead(enc, type, sys, sync, nsat, nsig, ncell,
                              satInd, sigInd, cellInd);
        if (i == 0) return false;

        if (msmType == 4) {
            i = encodeMsmIntRrng(enc, i, rrng, nsat);
            i = encodeMsmModRrng(enc, i, rrng, nsat);
            i = encodeMsmPsrng(enc, i, psrng, ncell);
            i = encodeMsmPhrng(enc, i, phrng, ncell);
            i = encodeMsmLock(enc, i, lock, ncell);
            i = encodeMsmHalfAmb(enc, i, half, ncell);
            i = encodeMsmCnr(enc, i, cnr, ncell);
        } else {
            int[] info = new int[64];
            i = encodeMsmIntRrng(enc, i, rrng, nsat);
            i = encodeMsmInfo(enc, i, info, nsat);
            i = encodeMsmModRrng(enc, i, rrng, nsat);
            i = encodeMsmRrate(enc, i, rrate, nsat);
            i = encodeMsmPsrngEx(enc, i, psrng, ncell);
            i = encodeMsmPhrngEx(enc, i, phrng, ncell);
            i = encodeMsmLockEx(enc, i, lock, ncell);
            i = encodeMsmHalfAmb(enc, i, half, ncell);
            i = encodeMsmCnrEx(enc, i, cnr, ncell);
            i = encodeMsmRate(enc, i, rate, ncell);
        }

        enc.nbit = i;
        return true;
    }

    private static int msmSys(int type) {
        if (type >= 1071 && type <= 1077) return Constants.SYS_GPS;
        if (type >= 1081 && type <= 1087) return Constants.SYS_GLO;
        if (type >= 1091 && type <= 1097) return Constants.SYS_GAL;
        if (type >= 1101 && type <= 1107) return Constants.SYS_SBS;
        if (type >= 1111 && type <= 1117) return Constants.SYS_QZS;
        if (type >= 1121 && type <= 1127) return Constants.SYS_CMP;
        if (type >= 1131 && type <= 1137) return Constants.SYS_IRN;
        return 0;
    }

    private static int sysToSigIdx(int sys) {
        if (sys == Constants.SYS_GPS) return 0;
        if (sys == Constants.SYS_GLO) return 1;
        if (sys == Constants.SYS_GAL) return 2;
        if (sys == Constants.SYS_QZS) return 3;
        if (sys == Constants.SYS_SBS) return 4;
        if (sys == Constants.SYS_CMP) return 5;
        if (sys == Constants.SYS_IRN) return 6;
        return 0;
    }

    private static int toSatid(int sys, int sat) {
        int[] prn = new int[1];
        if (SatUtils.satsys(sat, prn) != sys) return 0;
        if (sys == Constants.SYS_QZS) return prn[0] - (Constants.MINPRNQZS - 1);
        if (sys == Constants.SYS_SBS) return prn[0] - (Constants.MINPRNSBS - 1);
        return prn[0];
    }

    private static int toSigid(int sys, int sigIdx, int code) {
        String sig = code2obs(code);
        if (sig.isEmpty()) return 0;
        String[] table = MSM_SIG[sigIdx];
        for (int i = 0; i < 32; i++) {
            if (sig.equals(table[i])) return i + 1;
        }
        return 0;
    }

    private static String code2obs(int code) {
        if (code == Constants.CODE_L1C) return "1C";
        if (code == Constants.CODE_L1P) return "1P";
        if (code == Constants.CODE_L1W) return "1W";
        if (code == Constants.CODE_L1Y) return "1Y";
        if (code == Constants.CODE_L1M) return "1M";
        if (code == Constants.CODE_L1N) return "1N";
        if (code == Constants.CODE_L1S) return "1S";
        if (code == Constants.CODE_L1L) return "1L";
        if (code == Constants.CODE_L1E) return "1E";
        if (code == Constants.CODE_L1A) return "1A";
        if (code == Constants.CODE_L1B) return "1B";
        if (code == Constants.CODE_L1X) return "1X";
        if (code == Constants.CODE_L1Z) return "1Z";
        if (code == Constants.CODE_L1I) return "1I";
        if (code == Constants.CODE_L1Q) return "1Q";
        if (code == Constants.CODE_L1D) return "1D";
        if (code == Constants.CODE_L2C) return "2C";
        if (code == Constants.CODE_L2D) return "2D";
        if (code == Constants.CODE_L2S) return "2S";
        if (code == Constants.CODE_L2L) return "2L";
        if (code == Constants.CODE_L2X) return "2X";
        if (code == Constants.CODE_L2P) return "2P";
        if (code == Constants.CODE_L2W) return "2W";
        if (code == Constants.CODE_L2Y) return "2Y";
        if (code == Constants.CODE_L2M) return "2M";
        if (code == Constants.CODE_L2N) return "2N";
        if (code == Constants.CODE_L2I) return "2I";
        if (code == Constants.CODE_L2Q) return "2Q";
        if (code == Constants.CODE_L3I) return "3I";
        if (code == Constants.CODE_L3Q) return "3Q";
        if (code == Constants.CODE_L3X) return "3X";
        if (code == Constants.CODE_L4A) return "4A";
        if (code == Constants.CODE_L4B) return "4B";
        if (code == Constants.CODE_L4X) return "4X";
        if (code == Constants.CODE_L5I) return "5I";
        if (code == Constants.CODE_L5Q) return "5Q";
        if (code == Constants.CODE_L5X) return "5X";
        if (code == Constants.CODE_L5A) return "5A";
        if (code == Constants.CODE_L5B) return "5B";
        if (code == Constants.CODE_L5C) return "5C";
        if (code == Constants.CODE_L5D) return "5D";
        if (code == Constants.CODE_L5P) return "5P";
        if (code == Constants.CODE_L5Z) return "5Z";
        if (code == Constants.CODE_L6I) return "6I";
        if (code == Constants.CODE_L6Q) return "6Q";
        if (code == Constants.CODE_L6X) return "6X";
        if (code == Constants.CODE_L6A) return "6A";
        if (code == Constants.CODE_L6B) return "6B";
        if (code == Constants.CODE_L6C) return "6C";
        if (code == Constants.CODE_L6S) return "6S";
        if (code == Constants.CODE_L6L) return "6L";
        if (code == Constants.CODE_L6E) return "6E";
        if (code == Constants.CODE_L6Z) return "6Z";
        if (code == Constants.CODE_L6D) return "6D";
        if (code == Constants.CODE_L6P) return "6P";
        if (code == Constants.CODE_L7I) return "7I";
        if (code == Constants.CODE_L7Q) return "7Q";
        if (code == Constants.CODE_L7X) return "7X";
        if (code == Constants.CODE_L7D) return "7D";
        if (code == Constants.CODE_L7P) return "7P";
        if (code == Constants.CODE_L7Z) return "7Z";
        if (code == Constants.CODE_L8I) return "8I";
        if (code == Constants.CODE_L8Q) return "8Q";
        if (code == Constants.CODE_L8X) return "8X";
        if (code == Constants.CODE_L8D) return "8D";
        if (code == Constants.CODE_L8P) return "8P";
        if (code == Constants.CODE_L9A) return "9A";
        if (code == Constants.CODE_L9B) return "9B";
        if (code == Constants.CODE_L9C) return "9C";
        if (code == Constants.CODE_L9X) return "9X";
        return "";
    }

    private static int fcnGlo(int sat, Nav nav) {
        int[] prn = new int[1];
        if (SatUtils.satsys(sat, prn) != Constants.SYS_GLO) return -1;
        if (nav.geph == null) return -1;
        for (Geph geph : nav.geph) {
            if (geph != null && geph.sat == sat) {
                return geph.frq + 7;
            }
        }
        return -1;
    }

    private static void genMsmIndex(Rtcm3Encoder enc, int sys, int sigIdx,
                                     int[] nsatArr, int[] nsigArr, int[] ncellArr,
                                     int[] satInd, int[] sigInd, int[] cellInd) {
        int nsat = 0, nsig = 0, ncell = 0;

        for (int i = 0; i < enc.obs.n; i++) {
            int sat = toSatid(sys, enc.obs.data[i].sat);
            if (sat == 0) continue;
            for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                int sig = toSigid(sys, sigIdx, enc.obs.data[i].code[j]);
                if (sig == 0) continue;
                satInd[sat - 1] = 1;
                sigInd[sig - 1] = 1;
            }
        }
        for (int i = 0; i < 64; i++) {
            if (satInd[i] != 0) satInd[i] = ++nsat;
        }
        for (int i = 0; i < 32; i++) {
            if (sigInd[i] != 0) sigInd[i] = ++nsig;
        }
        for (int i = 0; i < enc.obs.n; i++) {
            int sat = toSatid(sys, enc.obs.data[i].sat);
            if (sat == 0) continue;
            for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                int sig = toSigid(sys, sigIdx, enc.obs.data[i].code[j]);
                if (sig == 0) continue;
                int cell = sigInd[sig - 1] - 1 + (satInd[sat - 1] - 1) * nsig;
                if (cell < 64 * 32) cellInd[cell] = 1;
            }
        }
        for (int i = 0; i < nsat * nsig && i < 64 * 32; i++) {
            if (cellInd[i] != 0 && ncell < 64) cellInd[i] = ++ncell;
        }
        nsatArr[0] = nsat;
        nsigArr[0] = nsig;
        ncellArr[0] = ncell;
    }

    private static void genMsmSat(Rtcm3Encoder enc, int sys, int sigIdx,
                                   int[] satInd, double[] rrng, double[] rrate) {
        for (int i = 0; i < 64; i++) { rrng[i] = 0.0; rrate[i] = 0.0; }

        for (int i = 0; i < enc.obs.n; i++) {
            Obsd data = enc.obs.data[i];
            int sat = toSatid(sys, data.sat);
            if (sat == 0) continue;

            for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                int sig = toSigid(sys, sigIdx, data.code[j]);
                if (sig == 0) continue;
                int k = satInd[sat - 1] - 1;
                double freq = SatUtils.sat2freq(data.sat, data.code[j], enc.nav);

                if (rrng[k] == 0.0 && data.P[j] != 0.0) {
                    rrng[k] = Rtcm3Encoder.round(data.P[j] / RANGE_MS / P2_10) * RANGE_MS * P2_10;
                }
                if (rrate[k] == 0.0 && data.D[j] != 0.0f && freq > 0.0) {
                    rrate[k] = Rtcm3Encoder.round(-data.D[j] * Constants.CLIGHT / freq);
                }
            }
        }
    }

    private static void genMsmSig(Rtcm3Encoder enc, int sys, int sigIdx,
                                   int[] satInd, int[] sigInd, int[] cellInd, int nsig,
                                   double[] rrng, double[] rrate,
                                   double[] psrng, double[] phrng, double[] rate,
                                   double[] lock, int[] half, float[] cnr) {
        for (int i = 0; i < enc.obs.n; i++) {
            Obsd data = enc.obs.data[i];
            int sat = toSatid(sys, data.sat);
            if (sat == 0) continue;

            for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                int sig = toSigid(sys, sigIdx, data.code[j]);
                if (sig == 0 || sigInd[sig - 1] == 0) continue;

                int k = satInd[sat - 1] - 1;
                int cellIdx = cellInd[sigInd[sig - 1] - 1 + k * nsig];
                if (cellIdx == 0 || cellIdx >= 64) continue;

                double freq = SatUtils.sat2freq(data.sat, data.code[j], enc.nav);
                double lambda = freq == 0.0 ? 0.0 : Constants.CLIGHT / freq;

                double psrngS = data.P[j] == 0.0 ? 0.0 : data.P[j] - rrng[k];
                double phrngS = (data.L[j] == 0.0 || lambda <= 0.0) ? 0.0 : data.L[j] * lambda - rrng[k];
                double rateS  = (data.D[j] == 0.0f || lambda <= 0.0) ? 0.0 : -data.D[j] * lambda - rrate[k];

                int ci = cellIdx - 1;
                if (psrngS != 0.0) psrng[ci] = psrngS;
                if (phrngS != 0.0) phrng[ci] = phrngS;
                if (rateS  != 0.0) rate[ci]  = rateS;
                lock[ci] = 0.0;
                half[ci] = (data.LLI[j] & 2) != 0 ? 1 : 0;
                cnr[ci]  = data.SNR[j];
            }
        }
    }

    private static int encodeMsmHead(Rtcm3Encoder enc, int type, int sys, int sync,
                                      int nsat, int nsig, int ncell,
                                      int[] satInd, int[] sigInd, int[] cellInd) {
        int i = 24;

        double tow = TimeSystem.time2gpst(enc.obs.data[0].time, null);
        long epoch;
        if (sys == Constants.SYS_GLO) {
            tow = TimeSystem.time2gpst(
                    TimeSystem.timeadd(TimeSystem.gpst2utc(enc.obs.data[0].time), 10800.0), null);
            long dow = (long) (tow / 86400.0);
            epoch = (dow << 27) + Rtcm3Encoder.roundU(tow % 86400.0 * 1E3);
        } else if (sys == Constants.SYS_CMP) {
            epoch = Rtcm3Encoder.roundU(
                    TimeSystem.time2gpst(TimeSystem.gpst2bdt(enc.obs.data[0].time), null) * 1E3);
        } else {
            epoch = Rtcm3Encoder.roundU(tow * 1E3);
        }

        BitUtils.setbitu(enc.buff, i, 12, type);          i += 12;
        BitUtils.setbitu(enc.buff, i, 12, enc.stationId); i += 12;
        BitUtils.setbitu(enc.buff, i, 30, epoch);         i += 30;
        BitUtils.setbitu(enc.buff, i,  1, sync);          i +=  1;
        BitUtils.setbitu(enc.buff, i,  3, enc.seqNo);     i +=  3;
        BitUtils.setbitu(enc.buff, i,  7, 0);             i +=  7;
        BitUtils.setbitu(enc.buff, i,  2, 0);             i +=  2;
        BitUtils.setbitu(enc.buff, i,  2, 0);             i +=  2;
        BitUtils.setbitu(enc.buff, i,  1, 0);             i +=  1;
        BitUtils.setbitu(enc.buff, i,  3, 0);             i +=  3;

        for (int j = 0; j < 64; j++) {
            BitUtils.setbitu(enc.buff, i, 1, satInd[j] != 0 ? 1 : 0); i += 1;
        }
        for (int j = 0; j < 32; j++) {
            BitUtils.setbitu(enc.buff, i, 1, sigInd[j] != 0 ? 1 : 0); i += 1;
        }
        for (int j = 0; j < nsat * nsig && j < 64; j++) {
            BitUtils.setbitu(enc.buff, i, 1, cellInd[j] != 0 ? 1 : 0); i += 1;
        }
        return i;
    }

    private static int encodeMsmIntRrng(Rtcm3Encoder enc, int i, double[] rrng, int nsat) {
        for (int j = 0; j < nsat; j++) {
            long intMs;
            if (rrng[j] == 0.0) {
                intMs = 255;
            } else if (rrng[j] < 0.0 || rrng[j] > RANGE_MS * 255.0) {
                intMs = 255;
            } else {
                intMs = Rtcm3Encoder.roundU(rrng[j] / RANGE_MS / P2_10) >> 10;
            }
            BitUtils.setbitu(enc.buff, i, 8, intMs); i += 8;
        }
        return i;
    }

    private static int encodeMsmModRrng(Rtcm3Encoder enc, int i, double[] rrng, int nsat) {
        for (int j = 0; j < nsat; j++) {
            long modMs;
            if (rrng[j] <= 0.0 || rrng[j] > RANGE_MS * 255.0) {
                modMs = 0;
            } else {
                modMs = Rtcm3Encoder.roundU(rrng[j] / RANGE_MS / P2_10) & 0x3FF;
            }
            BitUtils.setbitu(enc.buff, i, 10, modMs); i += 10;
        }
        return i;
    }

    private static int encodeMsmInfo(Rtcm3Encoder enc, int i, int[] info, int nsat) {
        for (int j = 0; j < nsat; j++) {
            BitUtils.setbitu(enc.buff, i, 4, info[j]); i += 4;
        }
        return i;
    }

    private static int encodeMsmRrate(Rtcm3Encoder enc, int i, double[] rrate, int nsat) {
        for (int j = 0; j < nsat; j++) {
            int val = Rtcm3Encoder.round(rrate[j] / 1.0);
            BitUtils.setbits(enc.buff, i, 14, val); i += 14;
        }
        return i;
    }

    private static int encodeMsmPsrng(Rtcm3Encoder enc, int i, double[] psrng, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(psrng[j] / RANGE_MS / P2_24);
            BitUtils.setbits(enc.buff, i, 15, val); i += 15;
        }
        return i;
    }

    private static int encodeMsmPsrngEx(Rtcm3Encoder enc, int i, double[] psrng, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(psrng[j] / RANGE_MS / P2_29);
            BitUtils.setbits(enc.buff, i, 22, val); i += 22;
        }
        return i;
    }

    private static int encodeMsmPhrng(Rtcm3Encoder enc, int i, double[] phrng, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(phrng[j] / RANGE_MS / P2_29);
            BitUtils.setbits(enc.buff, i, 22, val); i += 22;
        }
        return i;
    }

    private static int encodeMsmPhrngEx(Rtcm3Encoder enc, int i, double[] phrng, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(phrng[j] / RANGE_MS / P2_31);
            BitUtils.setbits(enc.buff, i, 29, val); i += 29;
        }
        return i;
    }

    private static int encodeMsmLock(Rtcm3Encoder enc, int i, double[] lock, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(lock[j]);
            BitUtils.setbitu(enc.buff, i, 4, val); i += 4;
        }
        return i;
    }

    private static int encodeMsmLockEx(Rtcm3Encoder enc, int i, double[] lock, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(lock[j]);
            BitUtils.setbitu(enc.buff, i, 10, val); i += 10;
        }
        return i;
    }

    private static int encodeMsmHalfAmb(Rtcm3Encoder enc, int i, int[] half, int ncell) {
        for (int j = 0; j < ncell; j++) {
            BitUtils.setbitu(enc.buff, i, 1, half[j]); i += 1;
        }
        return i;
    }

    private static int encodeMsmCnr(Rtcm3Encoder enc, int i, float[] cnr, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(cnr[j] / 1.0);
            BitUtils.setbitu(enc.buff, i, 6, val); i += 6;
        }
        return i;
    }

    private static int encodeMsmCnrEx(Rtcm3Encoder enc, int i, float[] cnr, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(cnr[j] * 2.0);
            BitUtils.setbitu(enc.buff, i, 10, val); i += 10;
        }
        return i;
    }

    private static int encodeMsmRate(Rtcm3Encoder enc, int i, double[] rate, int ncell) {
        for (int j = 0; j < ncell; j++) {
            int val = Rtcm3Encoder.round(rate[j] / 0.0001);
            BitUtils.setbits(enc.buff, i, 15, val); i += 15;
        }
        return i;
    }
}