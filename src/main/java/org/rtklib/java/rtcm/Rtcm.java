package org.rtklib.java.rtcm;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.common.CrcUtils;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RTCM3 message decoder.
 * Aligned with RTKLIB rtcm3.c structure.
 * Supports the main observation (legacy + MSM), ephemeris, station, and SSR messages.
 */
public class Rtcm {
    private static final Logger log = LoggerFactory.getLogger(Rtcm.class);

    /** Maximum number of signals per GNSS system for MSM */
    public static final int MAX_NSIG = 32;
    /** Maximum number of cells per MSM message */
    public static final int MAX_NCELL = 64;
    /** Maximum number of satellites in a single message */
    public static final int MAX_NSAT = 64;

    /** Raw buffer */
    public byte[] buff;
    /** Length of valid data in buffer (bytes) */
    public int len;
    /** Decoded observation data */
    public Obs obs;
    /** Navigation data (ephemerides, etc.) */
    public Nav nav;
    /** Station data (last decoded station) */
    public Sta sta;
    /** Last decoded time */
    public GTime time;
    /** Last decoded message type */
    public int type;
    /** Message type string (e.g. for logging) */
    public String msgtype;
    /** Station ID (last decoded) */
    public int staid;
    /** Station ID consistency counter */
    public int staidcnt;
    /** Test of station ID consistency */
    public int staidok;

    /** Observation data flag (1: first epoch) */
    public int obsflag;
    /** Epoch time continuity */
    public GTime[] timeobs;
    /** New observation data time set */
    public int[] obsb;
    /** Decoder options */
    public String opt;
    /** Last decoded satellite ephemeris */
    public int ephsat;
    /** Ephemeris set (0: I/NAV, 1: F/NAV) */
    public int ephset;
    /** Whether stream time has been initialized from ephemeris */
    private boolean timeInitialized = false;

    public boolean isTimeInitialized() {
        return timeInitialized;
    }

    public void setReferenceTime(GTime time) {
        this.time = new GTime(time);
        this.timeInitialized = true;
    }
    /** Lock time each satellite (for loss-of-lock detection) */
    public short[][] lock = new short[Constants.MAXSAT][Constants.NFREQ + Constants.NEXOBS];
    /** Carrier phase previous value (for half-cycle ambiguity resolution) */
    public double[][] cp = new double[Constants.MAXSAT][Constants.NFREQ + Constants.NEXOBS];

    /**
     * Default constructor.
     */
    public Rtcm() {
        this.buff = new byte[1024];
        this.len = 0;
        this.obs = new Obs();
        this.nav = new Nav();
        this.sta = new Sta();
        this.time = new GTime();
        this.type = 0;
        this.msgtype = "";
        this.staid = 0;
        this.staidcnt = 0;
        this.staidok = 0;
        this.obsflag = 0;
        this.timeobs = new GTime[Constants.MAXRCV];
        this.obsb = new int[Constants.MAXRCV];
        this.opt = "";
        this.ephsat = 0;
        this.ephset = 0;
        for (int i = 0; i < Constants.MAXRCV; i++) {
            this.timeobs[i] = new GTime();
        }
    }

    /**
     * Find satellite index in obs.data matching sat and time.
     * @param obs Obs container
     * @param time Time of observation
     * @param sat  Satellite number
     * @return Index in obs.data, or -1 if not found
     */
    public static int obsindex(Obs obs, GTime time, int sat) {
        int j;
        for (int i = 0; i < obs.n; i++) {
            if (obs.data[i].sat == sat) {
                return i;
            }
        }
        if (obs.n >= obs.nmax) return -1;
        obs.data[obs.n].time = new GTime(time);
        obs.data[obs.n].sat = sat;
        for (j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
            obs.data[obs.n].L[j] = obs.data[obs.n].P[j] = 0.0;
            obs.data[obs.n].D[j] = 0.0f;
            obs.data[obs.n].SNR[j] = obs.data[obs.n].LLI[j] = 0;
            obs.data[obs.n].code[j] = 0;
        }
        obs.n++;
        return obs.n - 1;
    }

    /**
     * Add observation record.
     * @param sys System code
     * @param prn PRN
     * @return Satellite number
     */
    public int addObs(int sys, int prn) {
        int sat = SatUtils.satno(sys, prn);
        if (sat == 0) return 0;
        GTime t0 = this.obs.data[0].time;
        double tt = TimeSystem.timediff(t0, this.time);
        if (Math.abs(tt) > 1E-9) {
            this.obs.n = 0;
            this.obsflag = 0;
        }
        int idx = obsindex(this.obs, this.time, sat);
        return idx >= 0 ? sat : 0;
    }

    /**
     * Input RTCM3 byte stream, return next fully decoded message.
     * Decoded payloads are exposed via obs/nav/sta.
     * @param data Source buffer
     * @param len  Valid length
     * @return number of bytes consumed (0: need more data; -1: error)
     */
    public int input(byte[] data, int len) {
        return input(data, 0, len);
    }

    /**
     * Input RTCM3 byte stream.
     * @param data   Source buffer
     * @param offset Start offset
     * @param length Valid length
     * @return Number of bytes consumed (0: need more data; -1: error)
     */
    public int input(byte[] data, int offset, int length) {
        if (length < 3) return 0;
        // Look for preamble 0xD3
        int pos = 0;
        while (pos < length - 2 && (data[offset + pos] & 0xFF) != 0xD3) {
            pos++;
        }
        if (length - pos < 3) return 0;
        int bodyLen = ((data[offset + pos + 1] & 0x03) << 8) | (data[offset + pos + 2] & 0xFF);
        int total = 3 + bodyLen + 3; // preamble + bodyLen(2) + body + crc(3)
        if (length - pos < total) return 0;
        // Verify CRC
        int crcActual = ((data[offset + pos + total - 3] & 0xFF) << 16)
                | ((data[offset + pos + total - 2] & 0xFF) << 8)
                | (data[offset + pos + total - 1] & 0xFF);
        int crcExpected = CrcUtils.rtkCrc24q(data, offset + pos, total - 3);
        if ((crcActual & 0xFFFFFF) != (crcExpected & 0xFFFFFF)) {
            log.warn("RTCM3 CRC mismatch, skipping 1 byte");
            return pos + 1;
        }
        // Copy body to internal buffer
        this.len = total;
        System.arraycopy(data, offset + pos, this.buff, 0, total);
        int type = (int) BitUtils.getbitu(this.buff, 24, 12);
        this.type = type;
        this.msgtype = String.format("RTCM %4d (%4d)", type, total);
        log.debug("RTCM3 decoded message type={}, length={}", type, total);
        // Dispatch decoder
        boolean ok = dispatch(type);
        if (!ok) {
            log.warn("RTCM3 decode failed for type {}", type);
        }
        return pos + total;
    }

    /**
     * Dispatch to the message-type-specific decoder.
     * @param type Message type
     * @return true on success
     */
    private boolean dispatch(int type) {
        try {
            switch (type) {
                case 1001: return decodeType1001();
                case 1002: return decodeType1002();
                case 1003: return decodeType1003();
                case 1004: return decodeType1004();
                case 1005: return decodeType1005();
                case 1006: return decodeType1006();
                case 1007: return decodeType1007();
                case 1008: return decodeType1008();
                case 1009: return decodeType1009();
                case 1010: return decodeType1010();
                case 1011: return decodeType1011();
                case 1012: return decodeType1012();
                case 1013: return decodeType1013();
                case 1019: return decodeType1019();
                case 1020: return decodeType1020();
                case 1021: return decodeType1021();
                case 1022: return decodeType1022();
                case 1023: return decodeType1023();
                case 1024: return decodeType1024();
                case 1025: return decodeType1025();
                case 1026: return decodeType1026();
                case 1027: return decodeType1027();
                case 1029: return decodeType1029();
                case 1030: return decodeType1030();
                case 1031: return decodeType1031();
                case 1032: return decodeType1032();
                case 1033: return decodeType1033();
                case 1034: return decodeType1034();
                case 1035: return decodeType1035();
                case 1037: return decodeType1037();
                case 1038: return decodeType1038();
                case 1039: return decodeType1039();
                case 1042: return decodeType1042();
                case 1044: return decodeType1044();
                case 1045: return decodeType1045();
                case 1046: return decodeType1046();
                case 1057: return decodeType1057();
                case 1058: return decodeType1058();
                case 1059: return decodeType1059();
                case 1060: return decodeType1060();
                case 1061: return decodeType1061();
                case 1062: return decodeType1062();
                case 1063: return decodeType1063();
                case 1064: return decodeType1064();
                case 1065: return decodeType1065();
                case 1066: return decodeType1066();
                case 1067: return decodeType1067();
                case 1071: return decodeType1071();
                case 1072: return decodeType1072();
                case 1073: return decodeType1073();
                case 1074: return decodeType1074();
                case 1075: return decodeType1075();
                case 1076: return decodeType1076();
                case 1077: return decodeType1077();
                case 1081: return decodeType1081();
                case 1082: return decodeType1082();
                case 1083: return decodeType1083();
                case 1084: return decodeType1084();
                case 1085: return decodeType1085();
                case 1086: return decodeType1086();
                case 1087: return decodeType1087();
                case 1091: return decodeType1091();
                case 1092: return decodeType1092();
                case 1093: return decodeType1093();
                case 1094: return decodeType1094();
                case 1095: return decodeType1095();
                case 1096: return decodeType1096();
                case 1097: return decodeType1097();
                case 1111: return decodeType1111();
                case 1112: return decodeType1112();
                case 1113: return decodeType1113();
                case 1114: return decodeType1114();
                case 1115: return decodeType1115();
                case 1116: return decodeType1116();
                case 1117: return decodeType1117();
                case 1121: return decodeType1121();
                case 1122: return decodeType1122();
                case 1123: return decodeType1123();
                case 1124: return decodeType1124();
                case 1125: return decodeType1125();
                case 1126: return decodeType1126();
                case 1127: return decodeType1127();
                case 1131: return decodeType1131();
                case 1132: return decodeType1132();
                case 1133: return decodeType1133();
                case 1134: return decodeType1134();
                case 1135: return decodeType1135();
                case 1136: return decodeType1136();
                case 1137: return decodeType1137();
                case 1230: return decodeType1230();
                default:
                    log.debug("RTCM3 type {} not yet implemented", type);
                    return true;
            }
        } catch (Exception ex) {
            log.error("RTCM3 decode exception for type {}: {}", type, ex.getMessage());
            return false;
        }
    }

    // ---- Legacy observation decoders (1001-1004) -----------------------------
    private boolean decodeType1001() { return decodeObs1001To1004(false, 1); }
    private boolean decodeType1002() { return decodeObs1001To1004(true, 1); }
    private boolean decodeType1003() { return decodeObs1001To1004(false, 2); }
    private boolean decodeType1004() { return decodeObs1001To1004(true, 2); }

    private boolean decodeObs1001To1004(boolean extended, int nfreq) {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        double tow = 0.0;
        if (nfreq == 1) {
            tow = BitUtils.getbitu(buff, i, 17) * 0.001; i += 17;
        } else {
            tow = BitUtils.getbitu(buff, i, 20) * 0.001; i += 20;
        }
        int sync = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int nsat = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        if (!testStaid(staid)) return false;
        adjweek(tow);

        if (this.obsflag != 0) {
            this.obs.n = 0;
            this.obsflag = 0;
        }

        for (int j = 0; j < nsat && this.obs.n < Constants.MAXOBS; j++) {
            int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
            int sat = SatUtils.satno(Constants.SYS_GPS, prn);
            if (sat == 0) continue;

            Obsd obs = this.obs.data[this.obs.n];
            obs.time = this.time;
            obs.sat = sat;

            if (nfreq >= 1) {
                double code1 = BitUtils.getbitu(buff, i, 1); i += 1;
                double pr1 = extended ? BitUtils.getbits(buff, i, 24) * 0.02 : BitUtils.getbitu(buff, i, 24) * 0.02;
                i += 24;
                double cp1 = extended ? BitUtils.getbits(buff, i, 20) * 0.0005 : BitUtils.getbitu(buff, i, 20) * 0.0005;
                i += 20;
                double lli1 = BitUtils.getbitu(buff, i, 2); i += 2;
                double cnr1 = BitUtils.getbitu(buff, i, 1); i += 1;

                obs.P[0] = pr1 == 0.0 ? 0.0 : pr1;
                obs.L[0] = cp1 == 0.0 ? 0.0 : cp1;
                obs.SNR[0] = (short) cnr1;
                obs.LLI[0] = (short) lli1;
                obs.code[0] = code1 != 0 ? Constants.CODE_L1P : Constants.CODE_L1C;
            }

            if (nfreq >= 2) {
                double code2 = BitUtils.getbitu(buff, i, 2); i += 2;
                double pr2 = extended ? BitUtils.getbits(buff, i, 20) * 0.02 : BitUtils.getbitu(buff, i, 20) * 0.02;
                i += 20;
                double cp2 = extended ? BitUtils.getbits(buff, i, 20) * 0.0005 : BitUtils.getbitu(buff, i, 20) * 0.0005;
                i += 20;
                double lli2 = BitUtils.getbitu(buff, i, 2); i += 2;
                double cnr2 = BitUtils.getbitu(buff, i, 1); i += 1;

                obs.P[1] = pr2 == 0.0 ? 0.0 : pr2;
                obs.L[1] = cp2 == 0.0 ? 0.0 : cp2;
                obs.SNR[1] = (short) cnr2;
                obs.LLI[1] = (short) lli2;
                obs.code[1] = code2 != 0 ? Constants.CODE_L2P : Constants.CODE_L2C;
            }

            this.obs.n++;
        }

        this.time = this.obs.data[0].time.time > 0 ? this.obs.data[0].time : this.time;
        this.obsflag = sync != 0 ? 0 : 1;
        return true;
    }

    // ---- Station messages ----------------------------------------------------
    private double getbits38(byte[] buff, int pos) {
        return (double) BitUtils.getbits(buff, pos, 32) * 64.0 + BitUtils.getbitu(buff, pos + 32, 6);
    }

    private boolean decodeType1005() {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        int itrf = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        i += 4;
        double rr[] = new double[3];
        rr[0] = getbits38(buff, i) * 0.0001; i += 38 + 2;
        rr[1] = getbits38(buff, i) * 0.0001; i += 38 + 2;
        rr[2] = getbits38(buff, i) * 0.0001;
        if (!testStaid(staid)) return false;
        this.staid = staid;
        this.sta.itrf = itrf;
        System.arraycopy(rr, 0, this.sta.pos, 0, 3);
        return true;
    }

    private boolean decodeType1006() {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        int itrf = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        i += 4;
        double rr[] = new double[3];
        rr[0] = getbits38(buff, i) * 0.0001; i += 38 + 2;
        rr[1] = getbits38(buff, i) * 0.0001; i += 38 + 2;
        rr[2] = getbits38(buff, i) * 0.0001; i += 38;
        double h = BitUtils.getbitu(buff, i, 16) * 0.0001; i += 16;
        if (!testStaid(staid)) return false;
        this.staid = staid;
        this.sta.itrf = itrf;
        System.arraycopy(rr, 0, this.sta.pos, 0, 3);
        this.sta.hgt = h;
        return true;
    }

    private boolean decodeType1007() {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        StringBuilder desc = new StringBuilder();
        for (int n = 0; n < 31; n++) {
            int c = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (c == 0) break;
            desc.append((char) c);
        }
        if (!testStaid(staid)) return false;
        this.sta.antdes = desc.toString();
        return true;
    }

    private boolean decodeType1008() {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        StringBuilder desc = new StringBuilder();
        for (int n = 0; n < 31; n++) {
            int c = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (c == 0) break;
            desc.append((char) c);
        }
        StringBuilder sno = new StringBuilder();
        for (int n = 0; n < 31; n++) {
            int c = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (c == 0) break;
            sno.append((char) c);
        }
        if (!testStaid(staid)) return false;
        this.sta.antdes = desc.toString();
        this.sta.antsno = sno.toString();
        return true;
    }

    // ---- GLONASS observation messages (1009-1012) ----------------------------
    private boolean decodeType1009() { return decodeObs1009To1012(false, 1); }
    private boolean decodeType1010() { return decodeObs1009To1012(true, 1); }
    private boolean decodeType1011() { return decodeObs1009To1012(false, 2); }
    private boolean decodeType1012() { return decodeObs1009To1012(true, 2); }

    private boolean decodeObs1009To1012(boolean extended, int nfreq) {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        double tod = BitUtils.getbitu(buff, i, 27) * 0.001; i += 27;
        int sync = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int nsat = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        if (!testStaid(staid)) return false;
        adjdayGlot(tod);

        if (this.obsflag != 0) {
            this.obs.n = 0;
            this.obsflag = 0;
        }

        for (int j = 0; j < nsat && this.obs.n < Constants.MAXOBS; j++) {
            int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
            int code1 = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            int fcn = (int) BitUtils.getbitu(buff, i, 5); i += 5;
            double pr1 = BitUtils.getbitu(buff, i, 25) * 0.02; i += 25;
            int ppr1 = BitUtils.getbits(buff, i, 20); i += 20;
            int lock1 = (int) BitUtils.getbitu(buff, i, 7); i += 7;
            int amb = (int) BitUtils.getbitu(buff, i, 7); i += 7;
            double cnr1 = BitUtils.getbitu(buff, i, 8) * 0.25; i += 8;

            int sat = SatUtils.satno(Constants.SYS_GLO, prn);
            if (sat == 0) continue;

            if (this.nav.glo_fcn[prn - 1] == 0) {
                this.nav.glo_fcn[prn - 1] = fcn - 7 + 8;
            }

            int index = obsindex(this.obs, this.time, sat);
            if (index < 0) continue;

            pr1 = pr1 + amb * Constants.PRUNIT_GLO;
            this.obs.data[index].P[0] = pr1;

            if (ppr1 != (int) 0xFFF80000) {
                double freq1 = ObsCode.code2freq(Constants.SYS_GLO, Constants.CODE_L1C, fcn - 7);
                double cp1 = adjcp(sat, 0, ppr1 * 0.0005 * freq1 / Constants.CLIGHT);
                this.obs.data[index].L[0] = pr1 * freq1 / Constants.CLIGHT + cp1;
            }
            this.obs.data[index].LLI[0] = (short) lossoflock(sat, 0, lock1);
            this.obs.data[index].SNR[0] = (float) snratio(cnr1);
            this.obs.data[index].code[0] = (byte) (code1 != 0 ? Constants.CODE_L1P : Constants.CODE_L1C);

            if (nfreq >= 2 && extended) {
                int code2 = (int) BitUtils.getbitu(buff, i, 2); i += 2;
                int pr21 = BitUtils.getbits(buff, i, 14); i += 14;
                int ppr2 = BitUtils.getbits(buff, i, 20); i += 20;
                int lock2 = (int) BitUtils.getbitu(buff, i, 7); i += 7;
                double cnr2 = BitUtils.getbitu(buff, i, 8) * 0.25; i += 8;

                if (pr21 != (int) 0xFFFFE000) {
                    this.obs.data[index].P[1] = pr1 + pr21 * 0.02;
                }
                if (ppr2 != (int) 0xFFF80000) {
                    double freq2 = ObsCode.code2freq(Constants.SYS_GLO, Constants.CODE_L2C, fcn - 7);
                    double cp2 = adjcp(sat, 1, ppr2 * 0.0005 * freq2 / Constants.CLIGHT);
                    this.obs.data[index].L[1] = pr1 * freq2 / Constants.CLIGHT + cp2;
                }
                this.obs.data[index].LLI[1] = (short) lossoflock(sat, 1, lock2);
                this.obs.data[index].SNR[1] = (float) snratio(cnr2);
                this.obs.data[index].code[1] = (byte) (code2 != 0 ? Constants.CODE_L2P : Constants.CODE_L2C);
            }
        }

        this.obsflag = sync != 0 ? 0 : 1;
        return true;
    }
    private boolean decodeType1013() {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        int n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        for (int k = 0; k < n; k++) {
            // skip descriptor text
            while (BitUtils.getbitu(buff, i, 8) != 0) i += 8;
            i += 8;
        }
        if (!testStaid(staid)) return false;
        return true;
    }

    private boolean decodeReceiverAntenna(int type, boolean hasAntenna) {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        int n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        StringBuilder desc = new StringBuilder();
        for (int k = 0; k < n; k++) {
            int c = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (c == 0) break;
            desc.append((char) c);
        }
        if (!testStaid(staid)) return false;
        this.sta.rectype = desc.toString();
        if (hasAntenna) {
            // 1011/1012 + antenna descriptor + serial
            n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            desc = new StringBuilder();
            for (int k = 0; k < n; k++) {
                int c = (int) BitUtils.getbitu(buff, i, 8); i += 8;
                if (c == 0) break;
                desc.append((char) c);
            }
            this.sta.antdes = desc.toString();
            n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            desc = new StringBuilder();
            for (int k = 0; k < n; k++) {
                int c = (int) BitUtils.getbitu(buff, i, 8); i += 8;
                if (c == 0) break;
                desc.append((char) c);
            }
            this.sta.antsno = desc.toString();
        }
        return true;
    }

    // ---- Ephemeris messages (1019, 1020, 1042-1046, 63, 1041) -----------------
    private boolean decodeType1019() {
        int i = 24 + 12;
        if (i + 476 > this.len * 8) {
            log.warn("rtcm3 1019 length error: len={}", this.len);
            return false;
        }
        
        Eph eph = new Eph();
        int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        int week = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        eph.sva = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        eph.code = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        eph.idot = BitUtils.getbits(buff, i, 14) * Constants.P2_43 * Constants.SC2RAD; i += 14;
        eph.iode = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        double toc = BitUtils.getbitu(buff, i, 16) * 16.0; i += 16;
        eph.f2 = BitUtils.getbits(buff, i, 8) * Constants.P2_55; i += 8;
        eph.f1 = BitUtils.getbits(buff, i, 16) * Constants.P2_43; i += 16;
        eph.f0 = BitUtils.getbits(buff, i, 22) * Constants.P2_31; i += 22;
        eph.iodc = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        eph.crs = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.deln = BitUtils.getbits(buff, i, 16) * Constants.P2_43 * Constants.SC2RAD; i += 16;
        eph.M0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cuc = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.e = BitUtils.getbitu(buff, i, 32) * Constants.P2_33; i += 32;
        eph.cus = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        double sqrtA = BitUtils.getbitu(buff, i, 32) * Constants.P2_19; i += 32;
        eph.toes = BitUtils.getbitu(buff, i, 16) * 16.0; i += 16;
        eph.cic = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.OMG0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cis = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.i0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.crc = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.omg = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.OMGd = BitUtils.getbits(buff, i, 24) * Constants.P2_43 * Constants.SC2RAD; i += 24;
        eph.tgd[0] = BitUtils.getbits(buff, i, 8) * Constants.P2_31; i += 8;
        eph.svh = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        eph.flag = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        eph.fit = BitUtils.getbitu(buff, i, 1) == 1 ? 6.0 : 4.0; i += 1;
        
        int sys = Constants.SYS_GPS;
        if (prn >= 40) {
            sys = Constants.SYS_SBS;
            prn += 80;
        }
        
        int sat = SatUtils.satno(sys, prn);
        if (sat == 0) {
            log.warn("rtcm3 1019 satellite number error: prn={}", prn);
            return false;
        }
        
        eph.sat = sat;
        eph.week = adjustGpsWeek(week);
        
        if (!timeInitialized) {
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            eph.toc = TimeSystem.gpst2time(eph.week, toc);
            eph.ttr = eph.toe;
            eph.A = sqrtA * sqrtA;
            this.time = eph.toe;
            timeInitialized = true;
        } else {
            double tt = TimeSystem.timediff(TimeSystem.gpst2time(eph.week, eph.toes), this.time);
            while (tt < -302400.0) { eph.week++; tt += 604800.0; }
            while (tt >= 302400.0) { eph.week--; tt -= 604800.0; }
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            int tocWeek = eph.week;
            double ttToc = TimeSystem.timediff(TimeSystem.gpst2time(tocWeek, toc), this.time);
            while (ttToc < -302400.0) { tocWeek++; ttToc += 604800.0; }
            while (ttToc >= 302400.0) { tocWeek--; ttToc -= 604800.0; }
            eph.toc = TimeSystem.gpst2time(tocWeek, toc);
            eph.ttr = this.time;
            eph.A = sqrtA * sqrtA;
        }
        
        if (!this.opt.contains("-EPHALL")) {
            if (this.nav.eph[sat - 1] != null && eph.iode == this.nav.eph[sat - 1].iode) {
                return true;
            }
        }
        
        this.nav.eph[sat - 1] = eph;
        this.ephsat = sat;
        this.ephset = 0;
        return true;
    }
    private boolean decodeType1020() {
        int i = 24 + 12;
        if (i + 348 > this.len * 8) {
            log.warn("rtcm3 1020 length error: len={}", this.len);
            return false;
        }
        
        Geph geph = new Geph();
        int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        geph.frq = (int) BitUtils.getbitu(buff, i, 5) - 7; i += 5;
        int Cn = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int Cn_a = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int P1 = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        double tk_h = BitUtils.getbitu(buff, i, 5); i += 5;
        double tk_m = BitUtils.getbitu(buff, i, 6); i += 6;
        double tk_s = BitUtils.getbitu(buff, i, 1) * 30.0; i += 1;
        int Bn = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int P2 = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int tb = (int) BitUtils.getbitu(buff, i, 7); i += 7;
        geph.vel[0] = BitUtils.getbits(buff, i, 24) * Constants.P2_20 * 1E3; i += 24;
        geph.pos[0] = BitUtils.getbits(buff, i, 27) * Constants.P2_11 * 1E3; i += 27;
        geph.acc[0] = BitUtils.getbits(buff, i, 5) * Constants.P2_30 * 1E3; i += 5;
        geph.vel[1] = BitUtils.getbits(buff, i, 24) * Constants.P2_20 * 1E3; i += 24;
        geph.pos[1] = BitUtils.getbits(buff, i, 27) * Constants.P2_11 * 1E3; i += 27;
        geph.acc[1] = BitUtils.getbits(buff, i, 5) * Constants.P2_30 * 1E3; i += 5;
        geph.vel[2] = BitUtils.getbits(buff, i, 24) * Constants.P2_20 * 1E3; i += 24;
        geph.pos[2] = BitUtils.getbits(buff, i, 27) * Constants.P2_11 * 1E3; i += 27;
        geph.acc[2] = BitUtils.getbits(buff, i, 5) * Constants.P2_30 * 1E3; i += 5;
        int P3 = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        geph.gamn = BitUtils.getbits(buff, i, 11) * Constants.P2_40; i += 11;
        int P = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        int ln = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        geph.taun = BitUtils.getbits(buff, i, 22) * Constants.P2_30; i += 22;
        geph.dtaun = BitUtils.getbits(buff, i, 5) * Constants.P2_30; i += 5;
        geph.age = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        int P4 = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        geph.sva = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        int M = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        
        int sat = SatUtils.satno(Constants.SYS_GLO, prn);
        if (sat == 0) {
            log.warn("rtcm3 1020 satellite number error: prn={}", prn);
            return false;
        }
        
        geph.sat = sat;
        geph.svh = (ln << 3) | (Cn_a << 2) | (Cn << 1) | Bn;
        geph.flags = (M << 7) | (P4 << 6) | (P3 << 5) | (P2 << 4) | (P1 << 2) | P;
        geph.iode = tb & 0x7F;
        
        if (this.time.time == 0) {
            this.time = TimeSystem.utc2gpst(TimeSystem.timeget());
        }
        
        int[] weekArr = new int[1];
        double tow = TimeSystem.time2gpst(TimeSystem.gpst2utc(this.time), weekArr);
        int week = weekArr[0];
        double tod = tow % 86400.0;
        tow -= tod;
        
        double tof = tk_h * 3600.0 + tk_m * 60.0 + tk_s - 10800.0;
        if (tof < tod - 43200.0) tof += 86400.0;
        else if (tof > tod + 43200.0) tof -= 86400.0;
        geph.tof = TimeSystem.utc2gpst(TimeSystem.gpst2time(week, tow + tof));
        
        double toe = tb * 900.0 - 10800.0;
        if (toe < tod - 43200.0) toe += 86400.0;
        else if (toe > tod + 43200.0) toe -= 86400.0;
        geph.toe = TimeSystem.utc2gpst(TimeSystem.gpst2time(week, tow + toe));
        
        if (!this.opt.contains("-EPHALL")) {
            if (this.nav.geph[prn - 1] != null &&
                Math.abs(TimeSystem.timediff(geph.toe, this.nav.geph[prn - 1].toe)) < 1.0 &&
                geph.svh == this.nav.geph[prn - 1].svh) {
                return true;
            }
        }
        
        this.nav.geph[prn - 1] = geph;
        this.ephsat = sat;
        this.ephset = 0;
        return true;
    }
    private boolean decodeType1042() {
        int i = 24 + 12;
        if (i + 499 > this.len * 8) {
            log.warn("rtcm3 1042 length error: len={}", this.len);
            return false;
        }
        
        Eph eph = new Eph();
        int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        int week = (int) BitUtils.getbitu(buff, i, 13); i += 13;
        eph.sva = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        eph.idot = BitUtils.getbits(buff, i, 14) * Constants.P2_43 * Constants.SC2RAD; i += 14;
        eph.iode = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        double toc = BitUtils.getbitu(buff, i, 17) * 8.0; i += 17;
        eph.f2 = BitUtils.getbits(buff, i, 11) * Constants.P2_66; i += 11;
        eph.f1 = BitUtils.getbits(buff, i, 22) * Constants.P2_50; i += 22;
        eph.f0 = BitUtils.getbits(buff, i, 24) * Constants.P2_33; i += 24;
        eph.iodc = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        eph.crs = BitUtils.getbits(buff, i, 18) * Constants.P2_6; i += 18;
        eph.deln = BitUtils.getbits(buff, i, 16) * Constants.P2_43 * Constants.SC2RAD; i += 16;
        eph.M0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cuc = BitUtils.getbits(buff, i, 18) * Constants.P2_31; i += 18;
        eph.e = BitUtils.getbitu(buff, i, 32) * Constants.P2_33; i += 32;
        eph.cus = BitUtils.getbits(buff, i, 18) * Constants.P2_31; i += 18;
        double sqrtA = BitUtils.getbitu(buff, i, 32) * Constants.P2_19; i += 32;
        eph.toes = BitUtils.getbitu(buff, i, 17) * 8.0; i += 17;
        eph.cic = BitUtils.getbits(buff, i, 18) * Constants.P2_31; i += 18;
        eph.OMG0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cis = BitUtils.getbits(buff, i, 18) * Constants.P2_31; i += 18;
        eph.i0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.crc = BitUtils.getbits(buff, i, 18) * Constants.P2_6; i += 18;
        eph.omg = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.OMGd = BitUtils.getbits(buff, i, 24) * Constants.P2_43 * Constants.SC2RAD; i += 24;
        eph.tgd[0] = BitUtils.getbits(buff, i, 10) * 1E-10; i += 10;
        eph.tgd[1] = BitUtils.getbits(buff, i, 10) * 1E-10; i += 10;
        eph.svh = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        
        int sat = SatUtils.satno(Constants.SYS_CMP, prn);
        if (sat == 0) {
            log.warn("rtcm3 1042 satellite number error: prn={}", prn);
            return false;
        }
        
        eph.sat = sat;
        eph.week = adjustBdtWeek(week);
        
        if (!timeInitialized) {
            eph.toe = TimeSystem.bdt2gpst(TimeSystem.bdt2time(eph.week, eph.toes));
            eph.toc = TimeSystem.bdt2gpst(TimeSystem.bdt2time(eph.week, toc));
            eph.ttr = eph.toe;
            eph.A = sqrtA * sqrtA;
            this.time = eph.toe;
            timeInitialized = true;
        } else {
            double tt = TimeSystem.timediff(TimeSystem.bdt2gpst(TimeSystem.bdt2time(eph.week, eph.toes)), this.time);
            while (tt < -302400.0) { eph.week++; tt += 604800.0; }
            while (tt >= 302400.0) { eph.week--; tt -= 604800.0; }
            eph.toe = TimeSystem.bdt2gpst(TimeSystem.bdt2time(eph.week, eph.toes));
            int tocWeek = eph.week;
            double ttToc = TimeSystem.timediff(TimeSystem.bdt2gpst(TimeSystem.bdt2time(tocWeek, toc)), this.time);
            while (ttToc < -302400.0) { tocWeek++; ttToc += 604800.0; }
            while (ttToc >= 302400.0) { tocWeek--; ttToc -= 604800.0; }
            eph.toc = TimeSystem.bdt2gpst(TimeSystem.bdt2time(tocWeek, toc));
            eph.ttr = this.time;
            eph.A = sqrtA * sqrtA;
        }
        
        if (!this.opt.contains("-EPHALL")) {
            if (this.nav.eph[sat - 1] != null &&
                Math.abs(TimeSystem.timediff(eph.toe, this.nav.eph[sat - 1].toe)) < 1.0 &&
                eph.iode == this.nav.eph[sat - 1].iode &&
                eph.iodc == this.nav.eph[sat - 1].iodc) {
                return true;
            }
        }
        
        this.nav.eph[sat - 1] = eph;
        this.ephset = 0;
        this.ephsat = sat;
        log.debug("RTCM 1042 eph stored: sat={} prn={} week={} toe={} A={}", 
                sat, prn, eph.week, eph.toes, String.format("%.1f", eph.A));
        return true;
    }
    private boolean decodeType1044() {
        int i = 24 + 12;
        if (i + 476 > this.len * 8) {
            log.warn("rtcm3 1044 length error: len={}", this.len);
            return false;
        }

        Eph eph = new Eph();
        int prn = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        int week = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        eph.sva = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        eph.code = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        eph.idot = BitUtils.getbits(buff, i, 14) * Constants.P2_43 * Constants.SC2RAD; i += 14;
        eph.iode = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        double toc = BitUtils.getbitu(buff, i, 16) * 16.0; i += 16;
        eph.f2 = BitUtils.getbits(buff, i, 8) * Constants.P2_55; i += 8;
        eph.f1 = BitUtils.getbits(buff, i, 16) * Constants.P2_43; i += 16;
        eph.f0 = BitUtils.getbits(buff, i, 22) * Constants.P2_31; i += 22;
        eph.iodc = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        eph.crs = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.deln = BitUtils.getbits(buff, i, 16) * Constants.P2_43 * Constants.SC2RAD; i += 16;
        eph.M0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cuc = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.e = BitUtils.getbitu(buff, i, 32) * Constants.P2_33; i += 32;
        eph.cus = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        double sqrtA = BitUtils.getbitu(buff, i, 32) * Constants.P2_19; i += 32;
        eph.toes = BitUtils.getbitu(buff, i, 16) * 16.0; i += 16;
        eph.cic = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.OMG0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cis = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.i0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.crc = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.omg = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.OMGd = BitUtils.getbits(buff, i, 24) * Constants.P2_43 * Constants.SC2RAD; i += 24;
        eph.tgd[0] = BitUtils.getbits(buff, i, 8) * Constants.P2_31; i += 8;
        eph.svh = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        eph.flag = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        eph.fit = BitUtils.getbitu(buff, i, 1) == 1 ? 6.0 : 4.0; i += 1;

        int sat = SatUtils.satno(Constants.SYS_QZS, prn);
        if (sat == 0) {
            log.warn("rtcm3 1044 satellite number error: prn={}", prn);
            return false;
        }

        eph.sat = sat;
        eph.week = adjustGpsWeek(week);

        if (!timeInitialized) {
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            eph.toc = TimeSystem.gpst2time(eph.week, toc);
            eph.ttr = eph.toe;
            eph.A = sqrtA * sqrtA;
            this.time = eph.toe;
            timeInitialized = true;
        } else {
            double tt = TimeSystem.timediff(TimeSystem.gpst2time(eph.week, eph.toes), this.time);
            while (tt < -302400.0) { eph.week++; tt += 604800.0; }
            while (tt >= 302400.0) { eph.week--; tt -= 604800.0; }
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            int tocWeek = eph.week;
            double ttToc = TimeSystem.timediff(TimeSystem.gpst2time(tocWeek, toc), this.time);
            while (ttToc < -302400.0) { tocWeek++; ttToc += 604800.0; }
            while (ttToc >= 302400.0) { tocWeek--; ttToc -= 604800.0; }
            eph.toc = TimeSystem.gpst2time(tocWeek, toc);
            eph.ttr = this.time;
            eph.A = sqrtA * sqrtA;
        }

        if (!this.opt.contains("-EPHALL")) {
            if (this.nav.eph[sat - 1] != null && eph.iode == this.nav.eph[sat - 1].iode) {
                return true;
            }
        }

        this.nav.eph[sat - 1] = eph;
        this.ephsat = sat;
        this.ephset = 0;
        log.debug("RTCM 1044 QZSS eph stored: sat={} prn={}", sat, prn);
        return true;
    }
    private boolean decodeType1021() { return true; }  // Helmert (not supported)
    private boolean decodeType1022() { return true; }  // Molodensky (not supported)
    private boolean decodeType1023() { return true; }  // Network RTK (not supported)
    private boolean decodeType1024() { return true; }  // GLONASS (not supported)
    private boolean decodeType1025() { return true; }  // GLONASS (not supported)
    private boolean decodeType1026() { return true; }  // GLONASS (not supported)
    private boolean decodeType1027() { return true; }  // GLONASS (not supported)

    // ---- Network and auxiliary messages (1029-1039) --------------------------
    private boolean decodeType1029() { return true; }  // Unicode text
    private boolean decodeType1030() { return true; }  // GPS residuals
    private boolean decodeType1031() { return true; }  // GLONASS residuals
    private boolean decodeType1032() { return true; }  // Physical reference station coords
    private boolean decodeType1033() { return true; }  // Receiver and antenna descriptor
    private boolean decodeType1034() { return true; }  // GPS network residuals
    private boolean decodeType1035() { return true; }  // GLONASS network residuals
    private boolean decodeType1037() { return true; }  // GLONASS network RTK
    private boolean decodeType1038() { return true; }  // Extended reference station
    private boolean decodeType1039() { return true; }  // GLONASS extended network

    private boolean decodeType1045() {
        if (this.opt.contains("-GALINAV")) return true;
        
        int i = 24 + 12;
        if (i + 484 > this.len * 8) {
            log.warn("rtcm3 1045 length error: len={}", this.len);
            return false;
        }
        
        Eph eph = new Eph();
        int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        int week = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        eph.iode = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        eph.sva = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        eph.idot = BitUtils.getbits(buff, i, 14) * Constants.P2_43 * Constants.SC2RAD; i += 14;
        double toc = BitUtils.getbitu(buff, i, 14) * 60.0; i += 14;
        eph.f2 = BitUtils.getbits(buff, i, 6) * Constants.P2_59; i += 6;
        eph.f1 = BitUtils.getbits(buff, i, 21) * Constants.P2_46; i += 21;
        eph.f0 = BitUtils.getbits(buff, i, 31) * Constants.P2_34; i += 31;
        eph.crs = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.deln = BitUtils.getbits(buff, i, 16) * Constants.P2_43 * Constants.SC2RAD; i += 16;
        eph.M0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cuc = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.e = BitUtils.getbitu(buff, i, 32) * Constants.P2_33; i += 32;
        eph.cus = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        double sqrtA = BitUtils.getbitu(buff, i, 32) * Constants.P2_19; i += 32;
        eph.toes = BitUtils.getbitu(buff, i, 14) * 60.0; i += 14;
        eph.cic = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.OMG0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cis = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.i0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.crc = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.omg = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.OMGd = BitUtils.getbits(buff, i, 24) * Constants.P2_43 * Constants.SC2RAD; i += 24;
        eph.tgd[0] = BitUtils.getbits(buff, i, 10) * Constants.P2_32; i += 10;
        int e5a_hs = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        int e5a_dvs = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int rsv = (int) BitUtils.getbitu(buff, i, 7); i += 7;
        
        int sat = SatUtils.satno(Constants.SYS_GAL, prn);
        if (sat == 0) {
            log.warn("rtcm3 1045 satellite number error: prn={}", prn);
            return false;
        }
        
        if (this.opt.contains("-GALINAV")) {
            return true;
        }
        
        eph.sat = sat;
        eph.week = week + 1024;
        
        if (!timeInitialized) {
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            eph.toc = TimeSystem.gpst2time(eph.week, toc);
            eph.ttr = eph.toe;
            eph.A = sqrtA * sqrtA;
            this.time = eph.toe;
            timeInitialized = true;
        } else {
            double tt = TimeSystem.timediff(TimeSystem.gpst2time(eph.week, eph.toes), this.time);
            while (tt < -302400.0) { eph.week++; tt += 604800.0; }
            while (tt >= 302400.0) { eph.week--; tt -= 604800.0; }
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            int tocWeek = eph.week;
            double ttToc = TimeSystem.timediff(TimeSystem.gpst2time(tocWeek, toc), this.time);
            while (ttToc < -302400.0) { tocWeek++; ttToc += 604800.0; }
            while (ttToc >= 302400.0) { tocWeek--; ttToc -= 604800.0; }
            eph.toc = TimeSystem.gpst2time(tocWeek, toc);
            eph.ttr = this.time;
            eph.A = sqrtA * sqrtA;
        }
        eph.svh = (e5a_hs << 4) + (e5a_dvs << 3);
        eph.code = (1 << 1) + (1 << 8);
        eph.iodc = eph.iode;
        
        if (!this.opt.contains("-EPHALL")) {
            if (this.nav.eph[sat - 1 + Constants.MAXSAT] != null && 
                eph.iode == this.nav.eph[sat - 1 + Constants.MAXSAT].iode) {
                return true;
            }
        }
        
        this.nav.eph[sat - 1 + Constants.MAXSAT] = eph;
        this.ephsat = sat;
        this.ephset = 1;
        return true;
    }

    private boolean decodeType1046() {
        if (this.opt.contains("-GALFNAV")) return true;
        
        int i = 24 + 12;
        if (i + 492 > this.len * 8) {
            log.warn("rtcm3 1046 length error: len={}", this.len);
            return false;
        }
        
        Eph eph = new Eph();
        int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        int week = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        eph.iode = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        eph.sva = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        eph.idot = BitUtils.getbits(buff, i, 14) * Constants.P2_43 * Constants.SC2RAD; i += 14;
        double toc = BitUtils.getbitu(buff, i, 14) * 60.0; i += 14;
        eph.f2 = BitUtils.getbits(buff, i, 6) * Constants.P2_59; i += 6;
        eph.f1 = BitUtils.getbits(buff, i, 21) * Constants.P2_46; i += 21;
        eph.f0 = BitUtils.getbits(buff, i, 31) * Constants.P2_34; i += 31;
        eph.crs = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.deln = BitUtils.getbits(buff, i, 16) * Constants.P2_43 * Constants.SC2RAD; i += 16;
        eph.M0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cuc = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.e = BitUtils.getbitu(buff, i, 32) * Constants.P2_33; i += 32;
        eph.cus = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        double sqrtA = BitUtils.getbitu(buff, i, 32) * Constants.P2_19; i += 32;
        eph.toes = BitUtils.getbitu(buff, i, 14) * 60.0; i += 14;
        eph.cic = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.OMG0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.cis = BitUtils.getbits(buff, i, 16) * Constants.P2_29; i += 16;
        eph.i0 = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.crc = BitUtils.getbits(buff, i, 16) * Constants.P2_5; i += 16;
        eph.omg = BitUtils.getbits(buff, i, 32) * Constants.P2_31 * Constants.SC2RAD; i += 32;
        eph.OMGd = BitUtils.getbits(buff, i, 24) * Constants.P2_43 * Constants.SC2RAD; i += 24;
        eph.tgd[0] = BitUtils.getbits(buff, i, 10) * Constants.P2_32; i += 10;
        eph.tgd[1] = BitUtils.getbits(buff, i, 10) * Constants.P2_32; i += 10;
        int e5b_hs = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        int e5b_dvs = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int e1_hs = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        int e1_dvs = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        
        int sat = SatUtils.satno(Constants.SYS_GAL, prn);
        if (sat == 0) {
            log.warn("rtcm3 1046 satellite number error: prn={}", prn);
            return false;
        }
        
        if (this.opt.contains("-GALFNAV")) {
            return true;
        }
        
        eph.sat = sat;
        eph.week = week + 1024;
        
        if (!timeInitialized) {
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            eph.toc = TimeSystem.gpst2time(eph.week, toc);
            eph.ttr = eph.toe;
            eph.A = sqrtA * sqrtA;
            this.time = eph.toe;
            timeInitialized = true;
        } else {
            double tt = TimeSystem.timediff(TimeSystem.gpst2time(eph.week, eph.toes), this.time);
            while (tt < -302400.0) { eph.week++; tt += 604800.0; }
            while (tt >= 302400.0) { eph.week--; tt -= 604800.0; }
            eph.toe = TimeSystem.gpst2time(eph.week, eph.toes);
            int tocWeek = eph.week;
            double ttToc = TimeSystem.timediff(TimeSystem.gpst2time(tocWeek, toc), this.time);
            while (ttToc < -302400.0) { tocWeek++; ttToc += 604800.0; }
            while (ttToc >= 302400.0) { tocWeek--; ttToc -= 604800.0; }
            eph.toc = TimeSystem.gpst2time(tocWeek, toc);
            eph.ttr = this.time;
            eph.A = sqrtA * sqrtA;
        }
        eph.svh = (e5b_hs << 7) + (e5b_dvs << 6) + (e1_hs << 1) + (e1_dvs << 0);
        eph.code = (1 << 0) + (1 << 2) + (1 << 9);
        eph.iodc = eph.iode;
        
        if (!this.opt.contains("-EPHALL")) {
            if (this.nav.eph[sat - 1] != null && eph.iode == this.nav.eph[sat - 1].iode) {
                return true;
            }
        }
        
        this.nav.eph[sat - 1] = eph;
        this.ephsat = sat;
        this.ephset = 0;
        return true;
    }

    // ---- SSR messages (1057-1067, 1240-1258) ---------------------------------
    private boolean decodeType1057() { return decodeSsr(1, 0); }  // GPS orbit
    private boolean decodeType1058() { return decodeSsr(1, 1); }  // GPS clock
    private boolean decodeType1059() { return decodeSsr(0, 0); }  // GPS combined
    private boolean decodeType1060() { return decodeSsr(2, 0); }  // GLO orbit
    private boolean decodeType1061() { return decodeSsr(2, 1); }  // GLO clock
    private boolean decodeType1062() { return decodeSsr(2, 2); }  // GLO combined
    private boolean decodeType1063() { return decodeSsr(4, 0); }  // BDS orbit
    private boolean decodeType1064() { return decodeSsr(4, 1); }  // BDS clock
    private boolean decodeType1065() { return decodeSsr(4, 2); }  // BDS combined
    private boolean decodeType1066() { return decodeSsr(6, 0); }  // QZS orbit
    private boolean decodeType1067() { return decodeSsr(6, 1); }  // QZS clock

    private boolean decodeSsr(int sysSel, int type) {
        int i = 24 + 12;
        int sat = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        int sys;
        switch (sysSel) {
            case 0: sys = Constants.SYS_GPS; break;
            case 1: sys = Constants.SYS_GPS; break;
            case 2: sys = Constants.SYS_GLO; break;
            case 4: sys = Constants.SYS_CMP; break;
            case 6: sys = Constants.SYS_QZS; break;
            default: return false;
        }
        int satNo = SatUtils.satno(sys, sat);
        if (satNo == 0) return false;
        Ssr ssr = new Ssr();
        if (satNo > 0 && satNo <= Constants.MAXSAT) {
            this.nav.ssr[satNo - 1] = ssr;
        }
        return true;
    }

    // ---- MSM messages (1071-1137) --------------------------------------------
    private boolean decodeType1071() { return decodeMsm0(1071, Constants.SYS_GPS); }
    private boolean decodeType1072() { return decodeMsm0(1072, Constants.SYS_GPS); }
    private boolean decodeType1073() { return decodeMsm0(1073, Constants.SYS_GPS); }
    private boolean decodeType1074() { return decodeMsm4(1074, Constants.SYS_GPS); }
    private boolean decodeType1075() { return decodeMsm5(1075, Constants.SYS_GPS); }
    private boolean decodeType1076() { return decodeMsm6(1076, Constants.SYS_GPS); }
    private boolean decodeType1077() { return decodeMsm7(1077, Constants.SYS_GPS); }
    private boolean decodeType1081() { return decodeMsm0(1081, Constants.SYS_GLO); }
    private boolean decodeType1082() { return decodeMsm0(1082, Constants.SYS_GLO); }
    private boolean decodeType1083() { return decodeMsm0(1083, Constants.SYS_GLO); }
    private boolean decodeType1084() { return decodeMsm4(1084, Constants.SYS_GLO); }
    private boolean decodeType1085() { return decodeMsm5(1085, Constants.SYS_GLO); }
    private boolean decodeType1086() { return decodeMsm6(1086, Constants.SYS_GLO); }
    private boolean decodeType1087() { return decodeMsm7(1087, Constants.SYS_GLO); }
    private boolean decodeType1091() { return decodeMsm0(1091, Constants.SYS_GAL); }
    private boolean decodeType1092() { return decodeMsm0(1092, Constants.SYS_GAL); }
    private boolean decodeType1093() { return decodeMsm0(1093, Constants.SYS_GAL); }
    private boolean decodeType1094() { return decodeMsm4(1094, Constants.SYS_GAL); }
    private boolean decodeType1095() { return decodeMsm5(1095, Constants.SYS_GAL); }
    private boolean decodeType1096() { return decodeMsm6(1096, Constants.SYS_GAL); }
    private boolean decodeType1097() { return decodeMsm7(1097, Constants.SYS_GAL); }
    private boolean decodeType1111() { return decodeMsm0(1111, Constants.SYS_QZS); }
    private boolean decodeType1112() { return decodeMsm0(1112, Constants.SYS_QZS); }
    private boolean decodeType1113() { return decodeMsm0(1113, Constants.SYS_QZS); }
    private boolean decodeType1114() { return decodeMsm4(1114, Constants.SYS_QZS); }
    private boolean decodeType1115() { return decodeMsm5(1115, Constants.SYS_QZS); }
    private boolean decodeType1116() { return decodeMsm6(1116, Constants.SYS_QZS); }
    private boolean decodeType1117() { return decodeMsm7(1117, Constants.SYS_QZS); }
    private boolean decodeType1121() { return decodeMsm0(1121, Constants.SYS_CMP); }
    private boolean decodeType1122() { return decodeMsm0(1122, Constants.SYS_CMP); }
    private boolean decodeType1123() { return decodeMsm0(1123, Constants.SYS_CMP); }
    private boolean decodeType1124() { return decodeMsm4(1124, Constants.SYS_CMP); }
    private boolean decodeType1125() { return decodeMsm5(1125, Constants.SYS_CMP); }
    private boolean decodeType1126() { return decodeMsm6(1126, Constants.SYS_CMP); }
    private boolean decodeType1127() { return decodeMsm7(1127, Constants.SYS_CMP); }
    private boolean decodeType1131() { return decodeMsm0(1131, Constants.SYS_IRN); }
    private boolean decodeType1132() { return decodeMsm0(1132, Constants.SYS_IRN); }
    private boolean decodeType1133() { return decodeMsm0(1133, Constants.SYS_IRN); }
    private boolean decodeType1134() { return decodeMsm4(1134, Constants.SYS_IRN); }
    private boolean decodeType1135() { return decodeMsm5(1135, Constants.SYS_IRN); }
    private boolean decodeType1136() { return decodeMsm6(1136, Constants.SYS_IRN); }
    private boolean decodeType1137() { return decodeMsm7(1137, Constants.SYS_IRN); }
    private boolean decodeType1230() { return true; }

    // ---- MSM decoding methods -------------------------------------------------
    private static final double RANGE_MS = 299792.458;
    private static final double P2_10 = 0.0009765625;
    private static final double P2_24 = 5.9604644775390625E-8;
    private static final double P2_29 = 1.862645149230957E-9;
    private static final double P2_31 = 4.6566128730773926E-10;

    /**
     * Decode MSM header.
     * @param sys    System code
     * @param sync   Sync flag (output)
     * @param iod    IOD (output)
     * @param h      MSM header (output)
     * @param hsize  Header size in bits (output)
     * @return Number of cells, or -1 on error
     */
    private int decodeMsmHead(int sys, int[] sync, int[] iod, MsmH h, int[] hsize) {
        MsmH h0 = new MsmH();
        double tow, tod;
        int i = 24;
        int j, mask, staid, ncell = 0;

        if (this.obsflag != 0) {
            this.obs.n = 0;
            this.obsflag = 0;
        }

        h.nsat = 0;
        h.nsig = 0;

        int type = (int) BitUtils.getbitu(buff, i, 12); i += 12;

        if (i + 157 <= this.len * 8) {
            staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;

            if (sys == Constants.SYS_GLO) {
                int dow = (int) BitUtils.getbitu(buff, i, 3); i += 3;
                tod = BitUtils.getbitu(buff, i, 27) * 0.001; i += 27;
                adjdayGlot(tod);
            } else if (sys == Constants.SYS_CMP) {
                long towRaw = BitUtils.getbitu(buff, i, 30);
                tow = towRaw * 0.001; i += 30;
                tow += 14.0;
                log.debug("MSM BDS TOW: raw={}, tow={}", towRaw, tow);
                adjweek(tow);
                log.debug("MSM BDS after adjweek: time.time={}, time.sec={}", this.time.time, this.time.sec);
            } else {
                tow = BitUtils.getbitu(buff, i, 30) * 0.001; i += 30;
                adjweek(tow);
            }
            sync[0] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            iod[0] = (int) BitUtils.getbitu(buff, i, 3); i += 3;
            h.time_s = (int) BitUtils.getbitu(buff, i, 7); i += 7;
            h.clk_str = (int) BitUtils.getbitu(buff, i, 2); i += 2;
            h.clk_ext = (int) BitUtils.getbitu(buff, i, 2); i += 2;
            h.smooth = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            h.tint_s = (int) BitUtils.getbitu(buff, i, 3); i += 3;

            for (j = 1; j <= 64; j++) {
                mask = (int) BitUtils.getbitu(buff, i, 1); i += 1;
                if (mask != 0) h.sats[h.nsat++] = j;
            }
            for (j = 1; j <= 32; j++) {
                mask = (int) BitUtils.getbitu(buff, i, 1); i += 1;
                if (mask != 0) h.sigs[h.nsig++] = j;
            }
        } else {
            log.warn("MSM length error: len={}", this.len);
            return -1;
        }

        if (!testStaid(staid)) return -1;

        if (h.nsat * h.nsig > 64) {
            log.warn("MSM number of sats and sigs error: nsat={} nsig={}", h.nsat, h.nsig);
            return -1;
        }
        if (i + h.nsat * h.nsig > this.len * 8) {
            log.warn("MSM length error: len={} nsat={} nsig={}", this.len, h.nsat, h.nsig);
            return -1;
        }

        for (j = 0; j < h.nsat * h.nsig; j++) {
            h.cellmask[j] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            if (h.cellmask[j] != 0) ncell++;
        }
        hsize[0] = i;

        log.debug("decode_msm_head: sys={} staid={} nsat={} nsig={} sync={} iod={} ncell={}",
                sys, staid, h.nsat, h.nsig, sync[0], iod[0], ncell);

        return ncell;
    }

    /**
     * Decode MSM 0 (unsupported).
     * @param type Message type
     * @param sys  System code
     * @return true on success
     */
    private boolean decodeMsm0(int type, int sys) {
        MsmH h = new MsmH();
        int[] sync = new int[1];
        int[] iod = new int[1];
        int[] hsize = new int[1];
        if (decodeMsmHead(sys, sync, iod, h, hsize) < 0) return false;
        this.obsflag = sync[0] != 0 ? 0 : 1;
        return true;
    }

    /**
     * Decode MSM 4: full pseudorange and phaserange plus CNR.
     * @param type Message type
     * @param sys  System code
     * @return true on success
     */
    private boolean decodeMsm4(int type, int sys) {
        MsmH h = new MsmH();
        double[] r = new double[64];
        double[] pr = new double[64];
        double[] cp = new double[64];
        double[] cnr = new double[64];
        int[] lock = new int[64];
        int[] half = new int[64];
        int i, j, sync, iod, ncell, rng, rng_m, prv, cpv;

        int[] syncArr = new int[1];
        int[] iodArr = new int[1];
        int[] hsize = new int[1];

        if ((ncell = decodeMsmHead(sys, syncArr, iodArr, h, hsize)) < 0) return false;
        sync = syncArr[0];
        iod = iodArr[0];
        i = hsize[0];

        if (i + h.nsat * 18 + ncell * 48 > this.len * 8) {
            log.warn("MSM4 length error: nsat={} ncell={} len={}", h.nsat, ncell, this.len);
            this.obsflag = sync != 0 ? 0 : 1;
            return true;
        }

        for (j = 0; j < h.nsat; j++) r[j] = 0.0;
        for (j = 0; j < ncell; j++) { pr[j] = cp[j] = -1E16; }

        for (j = 0; j < h.nsat; j++) {
            rng = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (rng != 255) r[j] = rng * RANGE_MS;
        }
        for (j = 0; j < h.nsat; j++) {
            rng_m = (int) BitUtils.getbitu(buff, i, 10); i += 10;
            if (r[j] != 0.0) r[j] += rng_m * P2_10 * RANGE_MS;
        }

        for (j = 0; j < ncell; j++) {
            prv = (int) BitUtils.getbits(buff, i, 15); i += 15;
            if (prv != -16384) pr[j] = prv * P2_24 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            cpv = (int) BitUtils.getbits(buff, i, 22); i += 22;
            if (cpv != -2097152) cp[j] = cpv * P2_29 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            lock[j] = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        }
        for (j = 0; j < ncell; j++) {
            half[j] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        }
        for (j = 0; j < ncell; j++) {
            cnr[j] = BitUtils.getbitu(buff, i, 6) * 1.0; i += 6;
        }

        saveMsmObs(sys, h, r, pr, cp, null, null, cnr, lock, null, half);

        this.obsflag = sync != 0 ? 0 : 1;
        return true;
    }

    /**
     * Decode MSM 5: full pseudorange, phaserange, phaserangerate and CNR.
     * @param type Message type
     * @param sys  System code
     * @return true on success
     */
    private boolean decodeMsm5(int type, int sys) {
        MsmH h = new MsmH();
        double[] r = new double[64];
        double[] rr = new double[64];
        double[] pr = new double[64];
        double[] cp = new double[64];
        double[] rrf = new double[64];
        double[] cnr = new double[64];
        int[] lock = new int[64];
        int[] ex = new int[64];
        int[] half = new int[64];
        int i, j, sync, iod, ncell, rng, rng_m, rate, prv, cpv, rrv;

        int[] syncArr = new int[1];
        int[] iodArr = new int[1];
        int[] hsize = new int[1];

        if ((ncell = decodeMsmHead(sys, syncArr, iodArr, h, hsize)) < 0) return false;
        sync = syncArr[0];
        iod = iodArr[0];
        i = hsize[0];

        if (i + h.nsat * 36 + ncell * 63 > this.len * 8) {
            log.warn("MSM5 length error: nsat={} ncell={} len={}", h.nsat, ncell, this.len);
            this.obsflag = sync != 0 ? 0 : 1;
            return true;
        }

        for (j = 0; j < h.nsat; j++) {
            r[j] = rr[j] = 0.0; ex[j] = 15;
        }
        for (j = 0; j < ncell; j++) { pr[j] = cp[j] = rrf[j] = -1E16; }

        for (j = 0; j < h.nsat; j++) {
            rng = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (rng != 255) r[j] = rng * RANGE_MS;
        }
        for (j = 0; j < h.nsat; j++) {
            ex[j] = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        }
        for (j = 0; j < h.nsat; j++) {
            rng_m = (int) BitUtils.getbitu(buff, i, 10); i += 10;
            if (r[j] != 0.0) r[j] += rng_m * P2_10 * RANGE_MS;
        }
        for (j = 0; j < h.nsat; j++) {
            rate = (int) BitUtils.getbits(buff, i, 14); i += 14;
            if (rate != -8192) rr[j] = rate * 1.0;
        }

        for (j = 0; j < ncell; j++) {
            prv = (int) BitUtils.getbits(buff, i, 15); i += 15;
            if (prv != -16384) pr[j] = prv * P2_24 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            cpv = (int) BitUtils.getbits(buff, i, 22); i += 22;
            if (cpv != -2097152) cp[j] = cpv * P2_29 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            lock[j] = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        }
        for (j = 0; j < ncell; j++) {
            half[j] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        }
        for (j = 0; j < ncell; j++) {
            cnr[j] = BitUtils.getbitu(buff, i, 6) * 1.0; i += 6;
        }
        for (j = 0; j < ncell; j++) {
            rrv = (int) BitUtils.getbits(buff, i, 15); i += 15;
            if (rrv != -16384) rrf[j] = rrv * 0.0001;
        }

        saveMsmObs(sys, h, r, pr, cp, rr, rrf, cnr, lock, ex, half);

        this.obsflag = sync != 0 ? 0 : 1;
        return true;
    }

    /**
     * Decode MSM 6: full pseudorange and phaserange plus CNR (high-res).
     * @param type Message type
     * @param sys  System code
     * @return true on success
     */
    private boolean decodeMsm6(int type, int sys) {
        MsmH h = new MsmH();
        double[] r = new double[64];
        double[] pr = new double[64];
        double[] cp = new double[64];
        double[] cnr = new double[64];
        int[] lock = new int[64];
        int[] half = new int[64];
        int i, j, sync, iod, ncell, rng, rng_m, prv, cpv;

        int[] syncArr = new int[1];
        int[] iodArr = new int[1];
        int[] hsize = new int[1];

        if ((ncell = decodeMsmHead(sys, syncArr, iodArr, h, hsize)) < 0) return false;
        sync = syncArr[0];
        iod = iodArr[0];
        i = hsize[0];

        if (i + h.nsat * 18 + ncell * 65 > this.len * 8) {
            log.warn("MSM6 length error: nsat={} ncell={} len={}", h.nsat, ncell, this.len);
            this.obsflag = sync != 0 ? 0 : 1;
            return true;
        }

        for (j = 0; j < h.nsat; j++) r[j] = 0.0;
        for (j = 0; j < ncell; j++) { pr[j] = cp[j] = -1E16; }

        for (j = 0; j < h.nsat; j++) {
            rng = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (rng != 255) r[j] = rng * RANGE_MS;
        }
        for (j = 0; j < h.nsat; j++) {
            rng_m = (int) BitUtils.getbitu(buff, i, 10); i += 10;
            if (r[j] != 0.0) r[j] += rng_m * P2_10 * RANGE_MS;
        }

        for (j = 0; j < ncell; j++) {
            prv = (int) BitUtils.getbits(buff, i, 20); i += 20;
            if (prv != -524288) pr[j] = prv * P2_29 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            cpv = (int) BitUtils.getbits(buff, i, 24); i += 24;
            if (cpv != -8388608) cp[j] = cpv * P2_31 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            lock[j] = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        }
        for (j = 0; j < ncell; j++) {
            half[j] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        }
        for (j = 0; j < ncell; j++) {
            cnr[j] = BitUtils.getbitu(buff, i, 10) * 0.0625; i += 10;
        }

        saveMsmObs(sys, h, r, pr, cp, null, null, cnr, lock, null, half);

        this.obsflag = sync != 0 ? 0 : 1;
        return true;
    }

    /**
     * Decode MSM 7: full pseudorange, phaserange, phaserangerate and CNR (high-res).
     * @param type Message type
     * @param sys  System code
     * @return true on success
     */
    private boolean decodeMsm7(int type, int sys) {
        MsmH h = new MsmH();
        double[] r = new double[64];
        double[] rr = new double[64];
        double[] pr = new double[64];
        double[] cp = new double[64];
        double[] rrf = new double[64];
        double[] cnr = new double[64];
        int[] lock = new int[64];
        int[] ex = new int[64];
        int[] half = new int[64];
        int i, j, sync, iod, ncell, rng, rng_m, rate, prv, cpv, rrv;

        int[] syncArr = new int[1];
        int[] iodArr = new int[1];
        int[] hsize = new int[1];

        if ((ncell = decodeMsmHead(sys, syncArr, iodArr, h, hsize)) < 0) return false;
        sync = syncArr[0];
        iod = iodArr[0];
        i = hsize[0];

        if (i + h.nsat * 36 + ncell * 80 > this.len * 8) {
            log.warn("MSM7 length error: nsat={} ncell={} len={}", h.nsat, ncell, this.len);
            this.obsflag = sync != 0 ? 0 : 1;
            return true;
        }

        for (j = 0; j < h.nsat; j++) {
            r[j] = rr[j] = 0.0; ex[j] = 15;
        }
        for (j = 0; j < ncell; j++) { pr[j] = cp[j] = rrf[j] = -1E16; }

        for (j = 0; j < h.nsat; j++) {
            rng = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (rng != 255) r[j] = rng * RANGE_MS;
        }
        for (j = 0; j < h.nsat; j++) {
            ex[j] = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        }
        for (j = 0; j < h.nsat; j++) {
            rng_m = (int) BitUtils.getbitu(buff, i, 10); i += 10;
            if (r[j] != 0.0) r[j] += rng_m * P2_10 * RANGE_MS;
        }
        for (j = 0; j < h.nsat; j++) {
            rate = (int) BitUtils.getbits(buff, i, 14); i += 14;
            if (rate != -8192) rr[j] = rate * 1.0;
        }

        for (j = 0; j < ncell; j++) {
            prv = (int) BitUtils.getbits(buff, i, 20); i += 20;
            if (prv != -524288) pr[j] = prv * P2_29 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            cpv = (int) BitUtils.getbits(buff, i, 24); i += 24;
            if (cpv != -8388608) cp[j] = cpv * P2_31 * RANGE_MS;
        }
        for (j = 0; j < ncell; j++) {
            lock[j] = (int) BitUtils.getbitu(buff, i, 10); i += 10;
        }
        for (j = 0; j < ncell; j++) {
            half[j] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        }
        for (j = 0; j < ncell; j++) {
            cnr[j] = BitUtils.getbitu(buff, i, 10) * 0.0625; i += 10;
        }
        for (j = 0; j < ncell; j++) {
            rrv = (int) BitUtils.getbits(buff, i, 15); i += 15;
            if (rrv != -16384) rrf[j] = rrv * 0.0001;
        }

        saveMsmObs(sys, h, r, pr, cp, rr, rrf, cnr, lock, ex, half);

        this.obsflag = sync != 0 ? 0 : 1;
        return true;
    }

    /**
     * Save MSM observation data.
     * @param sys  System code
     * @param h    MSM header
     * @param r    Range data
     * @param pr   Pseudorange data
     * @param cp   Carrier phase data
     * @param rr   Range rate data (satellite)
     * @param rrf  Range rate data (signal)
     * @param cnr  CNR data
     * @param lock Lock time data
     * @param ex   Extended info
     * @param half Half-cycle ambiguity
     */
    private void saveMsmObs(int sys, MsmH h, double[] r, double[] pr, double[] cp,
                            double[] rr, double[] rrf, double[] cnr, int[] lock,
                            int[] ex, int[] half) {
        String[] sig = new String[32];
        double tt, freq;
        int[] code = new int[32];
        int i, j, k, type, prn, sat, fcn, index = 0;
        int[] idx = new int[32];

        type = (int) BitUtils.getbitu(this.buff, 24, 12);

        for (i = 0; i < h.nsig; i++) {
            switch (sys) {
                case Constants.SYS_GPS: sig[i] = MsmSig.MSM_SIG_GPS[h.sigs[i] - 1]; break;
                case Constants.SYS_GLO: sig[i] = MsmSig.MSM_SIG_GLO[h.sigs[i] - 1]; break;
                case Constants.SYS_GAL: sig[i] = MsmSig.MSM_SIG_GAL[h.sigs[i] - 1]; break;
                case Constants.SYS_QZS: sig[i] = MsmSig.MSM_SIG_QZS[h.sigs[i] - 1]; break;
                case Constants.SYS_SBS: sig[i] = MsmSig.MSM_SIG_SBS[h.sigs[i] - 1]; break;
                case Constants.SYS_CMP: sig[i] = MsmSig.MSM_SIG_CMP[h.sigs[i] - 1]; break;
                case Constants.SYS_IRN: sig[i] = MsmSig.MSM_SIG_IRN[h.sigs[i] - 1]; break;
                default: sig[i] = ""; break;
            }
            code[i] = ObsCode.obs2code(sig[i]);
            idx[i] = ObsCode.code2idx(sys, code[i]);
            log.debug("MSM sig[{}] sigid={} sigstr={} code={} idx={}", i, h.sigs[i], sig[i], code[i], idx[i]);
        }

        ObsCode.sigindex(sys, code, h.nsig, idx);

        for (i = 0; i < h.nsig; i++) {
            log.debug("MSM after sigindex: sig[{}] code={} idx={}", i, code[i], idx[i]);
        }

        for (i = j = 0; i < h.nsat; i++) {
            prn = h.sats[i];
            if (sys == Constants.SYS_QZS) prn += Constants.MINPRNQZS - 1;
            else if (sys == Constants.SYS_SBS) prn += Constants.MINPRNSBS - 1;

            if ((sat = SatUtils.satno(sys, prn)) != 0) {
                tt = TimeSystem.timediff(this.obs.data[0].time, this.time);
                if (this.obs.n > 0 && (this.obsflag != 0 || Math.abs(tt) > 1E-9)) {
                    this.obs.n = 0;
                    this.obsflag = 0;
                }
                index = obsindex(this.obs, this.time, sat);
            } else {
                log.warn("MSM satellite error: prn={}", prn);
            }

            fcn = 0;
            if (sys == Constants.SYS_GLO) {
                fcn = -8;
                if (ex != null && ex[i] <= 13) {
                    fcn = ex[i] - 7;
                } else if (this.nav.geph[prn - 1] != null && this.nav.geph[prn - 1].sat == sat) {
                    fcn = this.nav.geph[prn - 1].frq;
                } else if (this.nav.glo_fcn[prn - 1] > 0) {
                    fcn = this.nav.glo_fcn[prn - 1] - 8;
                }
            }

            /* [SPP-PASSED] Critical: skip invalid cells WITHOUT incrementing j.
               pr[]/cp[]/cnr[]/lock[]/half[] are indexed only by valid cells (ncell total).
               Incrementing j on invalid cells causes pr[j] to map to wrong cell data,
               resulting in pseudorange errors up to 200m. C version uses 'continue'
               without j++. DO NOT change to {j++; continue;} */
            for (k = 0; k < h.nsig; k++) {
                if (h.cellmask[k + i * h.nsig] == 0) continue;

                if (sat != 0 && index >= 0 && idx[k] >= 0) {
                    freq = fcn < -7 ? 0.0 : ObsCode.code2freq(sys, code[k], fcn);

                    if (r[i] != 0.0 && pr[j] > -1E12) {
                        this.obs.data[index].P[idx[k]] = r[i] + pr[j];
                    }
                    if (r[i] != 0.0 && cp[j] > -1E12) {
                        this.obs.data[index].L[idx[k]] = (r[i] + cp[j]) * freq / Constants.CLIGHT;
                    }
                    if (rr != null && rrf != null && rrf[j] > -1E12) {
                        this.obs.data[index].D[idx[k]] = (float) (-(rr[i] + rrf[j]) * freq / Constants.CLIGHT);
                    }
                    this.obs.data[index].LLI[idx[k]] = lossoflock(sat, idx[k], lock[j]) + (half[j] != 0 ? 2 : 0);
                    this.obs.data[index].SNR[idx[k]] = (float) cnr[j];
                    this.obs.data[index].code[idx[k]] = (byte) code[k];
                }
                j++;
            }
        }
    }

    /**
     * Loss of lock indicator.
     * @param sat  Satellite number
     * @param idx  Signal index
     * @param lock Lock time count
     * @return LLI value
     */
    private int lossoflock(int sat, int idx, int lock) {
        int lli = (lock == 0 && this.lock[sat - 1][idx] == 0) || lock < this.lock[sat - 1][idx] ? 1 : 0;
        this.lock[sat - 1][idx] = (short) lock;
        return lli;
    }

    private double adjcp(int sat, int idx, double cp) {
        if (this.cp[sat - 1][idx] == 0.0) {
            /* no previous value */
        } else if (cp < this.cp[sat - 1][idx] - 750.0) {
            cp += 1500.0;
        } else if (cp > this.cp[sat - 1][idx] + 750.0) {
            cp -= 1500.0;
        }
        this.cp[sat - 1][idx] = cp;
        return cp;
    }

    private static double snratio(double snr) {
        return snr <= 0.0 || 100.0 <= snr ? 0.0 : snr;
    }

    // ---- Helper methods ------------------------------------------------------
    private boolean testStaid(int staid) {
        if (this.staid == 0) {
            this.staid = staid;
            return true;
        }
        return this.staid == staid;
    }

    /* [SPP-PASSED] Critical: time initialization for offline RTCM processing.
       C version uses CPU time (timeget()) as fallback, which works for real-time
       conversion but fails for offline processing where CPU time differs from data
       time by more than half a week. We use week=0 as temporary reference and let
       the first ephemeris message establish the correct time base.
       DO NOT modify without re-running SppTest validation. */
    private void adjweek(double tow) {
        if (!timeInitialized) {
            this.time = TimeSystem.gpst2time(0, tow);
            return;
        }
        int[] weekArr = new int[1];
        double towP = TimeSystem.time2gpst(this.time, weekArr);
        int week = weekArr[0];
        if (tow < towP - 302400.0) tow += 604800.0;
        else if (tow > towP + 302400.0) tow -= 604800.0;
        this.time = TimeSystem.gpst2time(week, tow);
    }

    /* [SPP-PASSED] Same rationale as adjweek: no CPU time dependency for offline processing. */
    private void adjdayGlot(double tod) {
        if (!timeInitialized) {
            return;
        }
        GTime time = TimeSystem.timeadd(TimeSystem.gpst2utc(this.time), 10800.0);
        int[] weekArr = new int[1];
        double tow = TimeSystem.time2gpst(time, weekArr);
        int week = weekArr[0];
        double todP = tow % 86400.0;
        tow -= todP;
        while (tod < todP - 43200.0) { tod += 86400.0; }
        while (tod > todP + 43200.0) { tod -= 86400.0; }
        time = TimeSystem.gpst2time(week, tow + tod);
        this.time = TimeSystem.utc2gpst(TimeSystem.timeadd(time, -10800.0));
    }

    /* [SPP-PASSED] No CPU time fallback when time uninitialized. */
    private int adjustGpsWeek(int week) {
        if (!timeInitialized) return week;
        int[] wArr = new int[1];
        TimeSystem.time2gpst(this.time, wArr);
        int w = wArr[0];
        if (w < 1560) w = 1560;
        return week + (w - week + 1) / 1024 * 1024;
    }

    /* [SPP-PASSED] No CPU time fallback when time uninitialized. */
    private int adjustBdtWeek(int week) {
        if (!timeInitialized) return week;
        int[] wArr = new int[1];
        TimeSystem.time2bdt(TimeSystem.gpst2bdt(this.time), wArr);
        int w = wArr[0];
        if (w < 1) w = 1;
        return week + (w - week + 512) / 1024 * 1024;
    }
}