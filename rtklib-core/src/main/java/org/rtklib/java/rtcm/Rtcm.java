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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * RTCM3 message decoder.
 * Aligned with RTKLIB rtcm3.c structure.
 * Supports the main observation (legacy + MSM), ephemeris, station, and SSR messages.
 */
public class Rtcm implements Serializable {
    private static final long serialVersionUID = 1L;
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
    /**
     * If true, relax the obsflag==1 check when extracting observations from RTCM stream.
     * When a single-message epoch has non-standard sync=1 (MMI=1), setting this to true
     * treats the observations as valid instead of discarding them.
     * Default is false (strict standard behavior, same as C version).
     */
    public boolean relaxSyncFlag;
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
    /**
     * 外部注入的专有消息解码器（如 IGS 4076）。
     * transient：不参与序列化，避免把扩展实现写进核心状态。
     */
    private transient List<CustomMessageDecoder> customDecoders;

    public boolean isTimeInitialized() {
        return timeInitialized;
    }

    public void setReferenceTime(GTime time) {
        this.time = new GTime(time);
        this.timeInitialized = true;
    }

    /**
     * 注册专有消息解码器（如 IGS 4076）。
     * 未内置支持的消息号会依次委托给已注册的解码器。
     * @param decoder 解码器实现
     */
    public void registerCustomDecoder(CustomMessageDecoder decoder) {
        if (decoder == null) return;
        customDecoderList().add(decoder);
    }

    /**
     * 惰性初始化；同时兼容反序列化后 transient 字段为 null 的情况。
     */
    private List<CustomMessageDecoder> customDecoderList() {
        if (customDecoders == null) {
            customDecoders = new ArrayList<>();
        }
        return customDecoders;
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
        this.relaxSyncFlag = false;
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
        log.trace("RTCM3 decoded message type={}, length={}", type, total);
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
                case 1043: return decodeType1043();
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
                case 1068: return decodeType1068();
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
                case 1240: return decodeSsrOrbit(Constants.SYS_GAL);
                case 1241: return decodeSsrClock(Constants.SYS_GAL);
                case 1242: return decodeSsrCodeBias(Constants.SYS_GAL);
                case 1243: return decodeSsrCombOrbClk(Constants.SYS_GAL);
                case 1244: return decodeSsrUra(Constants.SYS_GAL);
                case 1245: return decodeSsrHrClock(Constants.SYS_GAL);
                case 1246: return decodeSsrOrbit(Constants.SYS_QZS);
                case 1247: return decodeSsrClock(Constants.SYS_QZS);
                case 1248: return decodeSsrCodeBias(Constants.SYS_QZS);
                case 1249: return decodeSsrCombOrbClk(Constants.SYS_QZS);
                case 1250: return decodeSsrUra(Constants.SYS_QZS);
                case 1251: return decodeSsrHrClock(Constants.SYS_QZS);
                case 1252: return decodeSsrOrbit(Constants.SYS_SBS);
                case 1253: return decodeSsrClock(Constants.SYS_SBS);
                case 1254: return decodeSsrCodeBias(Constants.SYS_SBS);
                case 1255: return decodeSsrCombOrbClk(Constants.SYS_SBS);
                case 1256: return decodeSsrUra(Constants.SYS_SBS);
                case 1257: return decodeSsrHrClock(Constants.SYS_SBS);
                case 1258: return decodeSsrOrbit(Constants.SYS_CMP);
                case 1259: return decodeSsrClock(Constants.SYS_CMP);
                case 1260: return decodeSsrCodeBias(Constants.SYS_CMP);
                case 1261: return decodeSsrCombOrbClk(Constants.SYS_CMP);
                case 1262: return decodeSsrUra(Constants.SYS_CMP);
                case 1263: return decodeSsrHrClock(Constants.SYS_CMP);
                /* RTKLIB 2.5.1 rtcm3.c L2866-2872: 1264=SSR VTEC(暂不支持,消费跳过),
                 * 1265-1270 相位偏差依次对应 GPS/GLO/GAL/QZS/SBS/BDS */
                case 1264: return true;
                case 1265: return decodeSsrPhaseBias(Constants.SYS_GPS);
                case 1266: return decodeSsrPhaseBias(Constants.SYS_GLO);
                case 1267: return decodeSsrPhaseBias(Constants.SYS_GAL);
                case 1268: return decodeSsrPhaseBias(Constants.SYS_QZS);
                case 1269: return decodeSsrPhaseBias(Constants.SYS_SBS);
                case 1270: return decodeSsrPhaseBias(Constants.SYS_CMP);
                default:
                    return decodeCustom(type);
            }
        } catch (Exception ex) {
            log.error("RTCM3 decode exception for type {}: {}", type, ex.getMessage());
            return false;
        }
    }

    /**
     * 委托给外部注册的专有消息解码器。
     * 无解码器认领时保持原有行为：消费消息、不报错。
     * @param type 消息号
     * @return true（消息已被消费）
     */
    private boolean decodeCustom(int type) {
        List<CustomMessageDecoder> decoders = customDecoderList();
        if (decoders.isEmpty()) {
            log.trace("RTCM3 type {} not yet implemented", type);
            return true;
        }
        // 消息体：去掉 3 字节帧头与 3 字节 CRC
        int payloadLen = this.len - 6;
        if (payloadLen <= 0) {
            log.warn("RTCM3 type {} has invalid length {}", type, this.len);
            return true;
        }
        byte[] payload = new byte[payloadLen];
        System.arraycopy(this.buff, 3, payload, 0, payloadLen);
        for (CustomMessageDecoder decoder : decoders) {
            if (decoder.messageType() != type) continue;
            try {
                if (decoder.decode(this, type, payload, payloadLen)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                log.warn("custom RTCM decoder failed for type {}: {}", type, ex.getMessage());
            }
        }
        log.trace("RTCM3 type {} not handled by any custom decoder", type);
        return true;
    }

    // ---- Legacy observation decoders (1001-1004) -----------------------------
    private boolean decodeType1001() { return decodeObs1001To1004(false, 1); }
    private boolean decodeType1002() { return decodeObs1001To1004(true, 1); }
    private boolean decodeType1003() { return decodeObs1001To1004(false, 2); }
    private boolean decodeType1004() { return decodeObs1001To1004(true, 2); }

    private boolean decodeObs1001To1004(boolean extended, int nfreq) {
        int i = 24 + 12;
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        double tow = BitUtils.getbitu(buff, i, 30) * 0.001; i += 30;
        int sync = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int nsat = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        i += 1; // divergence-free smoothing indicator
        i += 3; // smoothing interval
        if (!testStaid(staid)) return false;
        adjweek(tow);

        if (!extended) {
            this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
            return sync == 0 || this.relaxSyncFlag;
        }

        if (this.obsflag != 0) {
            this.obs.n = 0;
            this.obsflag = 0;
        }

        int bitsPerSat = (nfreq >= 2) ? 125 : 74;

        for (int j = 0; j < nsat && this.obs.n < Constants.MAXOBS && i + bitsPerSat <= buff.length * 8; j++) {
            int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
            int sys = (prn < 40) ? Constants.SYS_GPS : Constants.SYS_SBS;
            if (sys == Constants.SYS_SBS) prn += 80;
            int sat = SatUtils.satno(sys, prn);
            if (sat == 0) continue;

            double tt = TimeSystem.timediff(this.obs.data[0].time, this.time);
            if (Math.abs(tt) > 1E-9) {
                this.obs.n = 0;
                this.obsflag = 0;
            }

            int index = obsindex(this.obs, this.time, sat);
            if (index < 0) continue;

            int code1 = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            int pr1Raw = (int) BitUtils.getbitu(buff, i, 24); i += 24;
            int ppr1 = BitUtils.getbits(buff, i, 20); i += 20;
            int lock1 = (int) BitUtils.getbitu(buff, i, 7); i += 7;
            int amb = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            double cnr1 = BitUtils.getbitu(buff, i, 8) * 0.25; i += 8;

            double pr1 = pr1Raw * 0.02 + amb * Constants.PRUNIT_GPS;
            this.obs.data[index].P[0] = pr1;

            if (ppr1 != (int) 0xFFF80000) {
                double cp1 = adjcp(sat, 0, ppr1 * 0.0005 * Constants.FREQL1 / Constants.CLIGHT);
                this.obs.data[index].L[0] = pr1 * Constants.FREQL1 / Constants.CLIGHT + cp1;
            }
            this.obs.data[index].LLI[0] = (short) lossoflock(sat, 0, lock1);
            this.obs.data[index].SNR[0] = (float) snratio(cnr1);
            this.obs.data[index].code[0] = (byte) (code1 != 0 ? Constants.CODE_L1P : Constants.CODE_L1C);

            if (nfreq >= 2) {
                final int[] L2codes = {Constants.CODE_L2X, Constants.CODE_L2P, Constants.CODE_L2D, Constants.CODE_L2W};
                int code2 = (int) BitUtils.getbitu(buff, i, 2); i += 2;
                int pr21 = BitUtils.getbits(buff, i, 14); i += 14;
                int ppr2 = BitUtils.getbits(buff, i, 20); i += 20;
                int lock2 = (int) BitUtils.getbitu(buff, i, 7); i += 7;
                double cnr2 = BitUtils.getbitu(buff, i, 8) * 0.25; i += 8;

                if (pr21 != (int) 0xFFFFE000) {
                    this.obs.data[index].P[1] = pr1 + pr21 * 0.02;
                }
                if (ppr2 != (int) 0xFFF80000) {
                    double cp2 = adjcp(sat, 1, ppr2 * 0.0005 * Constants.FREQL2 / Constants.CLIGHT);
                    this.obs.data[index].L[1] = pr1 * Constants.FREQL2 / Constants.CLIGHT + cp2;
                }
                this.obs.data[index].LLI[1] = (short) lossoflock(sat, 1, lock2);
                this.obs.data[index].SNR[1] = (float) snratio(cnr2);
                this.obs.data[index].code[1] = (byte) L2codes[code2];
            }
        }

        this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
        return sync == 0 || this.relaxSyncFlag;
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

        this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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
                this.ephsat = sat;
                this.ephset = 0;
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
                this.ephsat = sat;
                this.ephset = 0;
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
                this.ephsat = sat;
                this.ephset = 0;
                return true;
            }
        }
        
        this.nav.eph[sat - 1] = eph;
        this.ephset = 0;
        this.ephsat = sat;
        log.trace("RTCM 1042 eph stored: sat={} prn={} week={} toe={} A={}",
                sat, prn, eph.week, eph.toes, String.format("%.1f", eph.A));
        return true;
    }
    /**
     * RTCM 1043 — SBAS 卫星星历（非 BDS-3 B-CNAV1！）
     *
     * <p>布局依据 cssrlib/rtcm.py 的 {@code decode_sbs_eph}（RTCM 3.x 标准字段表），
     * 218 bit 单帧完整星历。RTKLIB 2.5.0/2.5.1/EX 均未实现此类型。</p>
     *
     * <p>字段：u6 svid | u8 iodn | u13 toc(×16s) | u4 ura |
     *         s30 x(×0.08m) | s30 y(×0.08m) | s25 z(×0.4m) |
     *         s17 vx(×0.000625m/s) | s17 vy(×0.000625m/s) | s18 vz(×0.004m/s) |
     *         s10 ax(×1.25e-5m/s²) | s10 ay(×1.25e-5m/s²) | s10 az(×6.25e-5m/s²) |
     *         s12 af0(×2^-31s) | s8 af1(×2^-40s/s)</p>
     *
     * <p>实际验证：WUH20 流 2026-10-26 抓到 6 帧 1043，按此布局解出
     * PRN=121/128/131/136（SBAS 标准号段），xyz 量级 4.2 万 km（GEO 轨道典型），
     * af0 ≈ 5.6e-9 s，与 SBAS 广播星历特征完全一致。</p>
     */
    private boolean decodeType1043() {
        int i = 24 + 12;
        if (i + 218 > this.len * 8) {
            log.warn("rtcm3 1043 length error: len={} need 218 bit payload", this.len);
            return false;
        }

        int svid = (int) BitUtils.getbitu(buff, i, 6);   i += 6;
        int iodn = (int) BitUtils.getbitu(buff, i, 8);   i += 8;
        double toc = BitUtils.getbitu(buff, i, 13) * 16.0; i += 13;
        int ura    = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        double x  = BitUtils.getbits(buff, i, 30) * 0.08;    i += 30;
        double y  = BitUtils.getbits(buff, i, 30) * 0.08;    i += 30;
        double z  = BitUtils.getbits(buff, i, 25) * 0.4;     i += 25;
        double vx = BitUtils.getbits(buff, i, 17) * 0.000625; i += 17;
        double vy = BitUtils.getbits(buff, i, 17) * 0.000625; i += 17;
        double vz = BitUtils.getbits(buff, i, 18) * 0.004;    i += 18;
        double ax = BitUtils.getbits(buff, i, 10) * 0.0000125; i += 10;
        double ay = BitUtils.getbits(buff, i, 10) * 0.0000125; i += 10;
        double az = BitUtils.getbits(buff, i, 10) * 0.0000625; i += 10;
        double af0 = BitUtils.getbits(buff, i, 12) * Constants.P2_31; i += 12;
        double af1 = BitUtils.getbits(buff, i, 8) * Constants.P2_40;  i += 8;

        // svid=0 保留，1-39 对应 SBAS PRN 120-158（RTCM 约定 svid+119）
        if (svid <= 0 || svid > 39) {
            log.debug("rtcm3 1043 invalid svid={}", svid);
            return false;
        }
        int sat = SatUtils.satno(Constants.SYS_SBS, svid + 119);
        if (sat == 0) {
            log.debug("rtcm3 1043 satellite number error: svid={}", svid);
            return false;
        }

        if (this.time.time == 0) {
            this.time = TimeSystem.utc2gpst(TimeSystem.timeget());
        }

        Seph seph = new Seph();
        seph.sat = sat;
        seph.sva = ura;
        seph.svh = 0;                    // 1043 不携带健康标志，默认 0=ok
        seph.pos[0] = x;
        seph.pos[1] = y;
        seph.pos[2] = z;
        seph.vel[0] = vx;
        seph.vel[1] = vy;
        seph.vel[2] = vz;
        seph.acc[0] = ax;
        seph.acc[1] = ay;
        seph.acc[2] = az;
        seph.af0 = af0;
        seph.af1 = af1;
        seph.tof = new GTime(this.time); // 报文接收时刻

        // t0 = 当日 GPST 的 toc 对齐（cssrlib: tod + toc）
        int[] wk = new int[1];
        double tow = TimeSystem.time2gpst(this.time, wk);
        double tod = Math.floor(tow / 86400.0) * 86400.0;
        seph.t0 = TimeSystem.gpst2time(wk[0], tod + toc);

        if (this.nav.seph != null && sat - 1 < this.nav.seph.length) {
            this.nav.seph[sat - 1] = seph;
        }
        this.ephset = 0;
        this.ephsat = sat;
        log.trace("RTCM 1043 SBAS eph stored: sat=S{} svid={} iodn={} toc={} af0={}",
                svid + 119, svid, iodn, toc, String.format("%.3e", af0));
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
                this.ephsat = sat;
                this.ephset = 0;
                return true;
            }
        }

        this.nav.eph[sat - 1] = eph;
        this.ephsat = sat;
        this.ephset = 0;
        log.trace("RTCM 1044 QZSS eph stored: sat={} prn={}", sat, prn);
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
    /**
     * 1033 Receiver and Antenna Descriptors。
     *
     * <p>字段顺序：站 ID(12) → 天线描述(8+8n) → 天线设置 ID(8) → 天线序列号(8+8n)
     * → 接收机类型(8+8n) → 接收机固件版本(8+8n) → 接收机序列号(8+8n)，
     * 全部为变长字符串，无保留位/扩展字段。</p>
     */
    private boolean decodeType1033() {
        int i = 24 + 12;
        if (i + 20 > this.len * 8) {
            log.warn("rtcm3 1033 length error: len={}", this.len);
            return false;
        }
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;

        int n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        String antdes = readDescriptor(i, n); i += n * 8;

        int antSetupId = (int) BitUtils.getbitu(buff, i, 8); i += 8;

        n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        String antsno = readDescriptor(i, n); i += n * 8;

        n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        String rectype = readDescriptor(i, n); i += n * 8;

        n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        String recver = readDescriptor(i, n); i += n * 8;

        n = (int) BitUtils.getbitu(buff, i, 8); i += 8;
        String recsno = readDescriptor(i, n); i += n * 8;

        if (i > this.len * 8) {
            log.warn("rtcm3 1033 truncated: len={}, used={} bits", this.len, i);
            return false;
        }
        if (!testStaid(staid)) return false;

        this.sta.antdes = antdes;
        this.sta.antSetupId = antSetupId;
        this.sta.antsno = antsno;
        this.sta.rectype = rectype;
        this.sta.recver = recver;
        this.sta.recsno = recsno;
        return true;
    }

    /** 读取 RTCM 变长描述符（n 个 8 bit 字符，遇 NUL 提前结束）。 */
    private String readDescriptor(int pos, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int k = 0; k < n && pos + (k + 1) * 8 <= this.len * 8; k++) {
            int c = (int) BitUtils.getbitu(buff, pos + k * 8, 8);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

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
                this.ephsat = sat;
                this.ephset = 1;
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
                this.ephsat = sat;
                this.ephset = 0;
                return true;
            }
        }
        
        this.nav.eph[sat - 1] = eph;
        this.ephsat = sat;
        this.ephset = 0;
        return true;
    }

    // ---- SSR messages (1057-1068, 1240-1270) ---------------------------------
    private boolean decodeType1057() { return decodeSsrOrbit(Constants.SYS_GPS); }
    private boolean decodeType1058() { return decodeSsrClock(Constants.SYS_GPS); }
    private boolean decodeType1059() { return decodeSsrCodeBias(Constants.SYS_GPS); }
    private boolean decodeType1060() { return decodeSsrCombOrbClk(Constants.SYS_GPS); }
    private boolean decodeType1061() { return decodeSsrUra(Constants.SYS_GPS); }
    private boolean decodeType1062() { return decodeSsrHrClock(Constants.SYS_GPS); }
    private boolean decodeType1063() { return decodeSsrOrbit(Constants.SYS_GLO); }
    private boolean decodeType1064() { return decodeSsrClock(Constants.SYS_GLO); }
    private boolean decodeType1065() { return decodeSsrCodeBias(Constants.SYS_GLO); }
    private boolean decodeType1066() { return decodeSsrCombOrbClk(Constants.SYS_GLO); }
    private boolean decodeType1067() { return decodeSsrUra(Constants.SYS_GLO); }
    private boolean decodeType1068() { return decodeSsrHrClock(Constants.SYS_GLO); }

    private int ssrIod = 0;
    private double ssrUdi = 0.0;
    private int ssrMi = 0;
    private int ssrNsat = 0;
    private int ssrProviderId = 0;
    private int ssrSolutionId = 0;
    private int ssrRefd = 0;

    /**
     * 解码 SSR 历元时间（对齐 RTKLIB C {@code decode_ssr_epoch}）并写入 {@link #time}。
     *
     * <p>RTCM SSR 报文在 12 位电文号之后紧跟历元字段：GLONASS 为 17 位日内秒，
     * 其余系统为 20 位周内秒。该字段此前被整体漏读，导致 {@code nav.ssr[].t0}
     * 全部为 0、SSR 改正因超龄被全部拒绝。</p>
     *
     * @return 历元字段结束后的位位置
     */
    private int decodeSsrEpoch(int sys) {
        int i = 24 + 12;
        if (sys == Constants.SYS_GLO) {
            double tod = BitUtils.getbitu(buff, i, 17);
            i += 17;
            adjdayGlot(tod);
        } else {
            double tow = BitUtils.getbitu(buff, i, 20);
            i += 20;
            adjweek(tow);
        }
        return i;
    }

    /* RTKLIB 2.5.1 rtcm3.c decode_ssr1_head/ssr2_head: datum bit 仅存在于
     * orbit(SSR1) 与 combined(SSR4) 头部; clock/codeBias/ura/hrClock 头部无 datum 位。
     * nsat 位宽 QZS=4，其余系统=6。 */
    private int decodeSsrHead(int i, int sys) {
        return decodeSsrHead(i, sys, false);
    }

    private int decodeSsrHead(int i, int sys, boolean hasDatum) {
        ssrUdi = BitUtils.getbitu(buff, i, 4);
        i += 4;
        ssrMi = (int) BitUtils.getbitu(buff, i, 1);
        i += 1;
        if (hasDatum) {
            ssrRefd = (int) BitUtils.getbitu(buff, i, 1); /* satellite reference datum */
            i += 1;
        }
        ssrIod = (int) BitUtils.getbitu(buff, i, 4);
        i += 4;
        ssrProviderId = (int) BitUtils.getbitu(buff, i, 16);
        i += 16;
        ssrSolutionId = (int) BitUtils.getbitu(buff, i, 4);
        i += 4;
        int ns = (sys == Constants.SYS_QZS) ? 4 : 6;
        ssrNsat = (int) BitUtils.getbitu(buff, i, ns);
        i += ns;
        return i;
    }

    private int decodeSsrSatId(int i, int sys) {
        int blen;
        if (sys == Constants.SYS_GLO) blen = 5;
        else if (sys == Constants.SYS_QZS) blen = 4;
        else blen = 6;
        int svid = (int) BitUtils.getbitu(buff, i, blen); i += blen;
        /* RTKLIB 2.5.1 decode_ssr1..7: prn=getbitu(...)+offp; QZS offp=192, SBS offp=120 */
        int offp = (sys == Constants.SYS_QZS) ? 192 : (sys == Constants.SYS_SBS) ? 120 : 0;
        this.ephsat = SatUtils.satno(sys, svid + offp);
        return i;
    }

    /* RTKLIB 2.5.1 rtcm3.c decode_ssr1: subtype=0 per-sat iode 位宽 ni 与 iodcrc 位宽 nj
     * 按系统区分: GPS(8/0) GLO(8/0) GAL(10/0) QZS(8/0) BDS(10/8) SBS(9/24)。
     * BDS 的有效 iode 取 iodcrc（C: if (sys==SYS_CMP && subtype==0) iode=iodcrc）。 */
    private boolean decodeSsrOrbit(int sys) {
        int i = decodeSsrHead(decodeSsrEpoch(sys), sys, true);
        GTime time = this.time != null ? this.time : new GTime();
        final int ni = (sys == Constants.SYS_GAL || sys == Constants.SYS_CMP) ? 10
                : (sys == Constants.SYS_SBS) ? 9 : 8;
        final int nj = (sys == Constants.SYS_CMP) ? 8 : (sys == Constants.SYS_SBS) ? 24 : 0;
        for (int k = 0; k < ssrNsat; k++) {
            i = decodeSsrSatId(i, sys);
            int sat = this.ephsat;
            int iode = (int) BitUtils.getbitu(buff, i, ni); i += ni;
            int iodcrc = (int) BitUtils.getbitu(buff, i, nj); i += nj;
            int dx = BitUtils.getbits(buff, i, 22); i += 22;
            int dy = BitUtils.getbits(buff, i, 20); i += 20;
            int dz = BitUtils.getbits(buff, i, 20); i += 20;
            int ddx = BitUtils.getbits(buff, i, 21); i += 21;
            int ddy = BitUtils.getbits(buff, i, 19); i += 19;
            int ddz = BitUtils.getbits(buff, i, 19); i += 19;
            if (sat <= 0 || sat > Constants.MAXSAT) {
                continue;
            }
            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[0] = new GTime(time);
            ssr.iod[0] = ssrIod;
            ssr.udi[0] = ssrUdi;
            ssr.iode = (sys == Constants.SYS_CMP) ? iodcrc : iode;
            ssr.iodcrc = iodcrc;
            ssr.refd = ssrRefd;
            ssr.deph[0] = ssrSigned(dx, 22, 0.1e-3);
            ssr.deph[1] = ssrSigned(dy, 20, 0.4e-3);
            ssr.deph[2] = ssrSigned(dz, 20, 0.4e-3);
            ssr.ddeph[0] = ssrSigned(ddx, 21, 1e-6);
            ssr.ddeph[1] = ssrSigned(ddy, 19, 4e-6);
            ssr.ddeph[2] = ssrSigned(ddz, 19, 4e-6);
            ssr.update = 1;
        }
        return true;
    }

    private boolean decodeSsrClock(int sys) {
        int i = decodeSsrHead(decodeSsrEpoch(sys), sys);
        GTime time = this.time != null ? this.time : new GTime();
        for (int k = 0; k < ssrNsat; k++) {
            i = decodeSsrSatId(i, sys);
            int sat = this.ephsat;
            if (sat <= 0 || sat > Constants.MAXSAT) { i += 70; continue; }
            int dclk0 = BitUtils.getbits(buff, i, 22); i += 22;
            int dclk1 = BitUtils.getbits(buff, i, 21); i += 21;
            int dclk2 = BitUtils.getbits(buff, i, 27); i += 27;
            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[1] = new GTime(time);
            ssr.iod[1] = ssrIod;
            ssr.udi[1] = ssrUdi;
            ssr.dclk[0] = ssrSigned(dclk0, 22, 0.1e-3);
            ssr.dclk[1] = ssrSigned(dclk1, 21, 0.4e-3);
            ssr.dclk[2] = ssrSigned(dclk2, 27, 4e-6);
            ssr.update = 1;
        }
        return true;
    }

    private boolean decodeSsrHrClock(int sys) {
        int i = decodeSsrHead(decodeSsrEpoch(sys), sys);
        GTime time = this.time != null ? this.time : new GTime();
        for (int k = 0; k < ssrNsat; k++) {
            i = decodeSsrSatId(i, sys);
            int sat = this.ephsat;
            if (sat <= 0 || sat > Constants.MAXSAT) { i += 22; continue; }
            int hclk = BitUtils.getbits(buff, i, 22); i += 22;
            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[2] = new GTime(time);
            ssr.iod[2] = ssrIod;
            ssr.udi[2] = ssrUdi;
            ssr.hrclk = ssrSigned(hclk, 22, 0.1e-3);
            ssr.update = 1;
        }
        return true;
    }

    private boolean decodeSsrUra(int sys) {
        int i = decodeSsrHead(decodeSsrEpoch(sys), sys);
        GTime time = this.time != null ? this.time : new GTime();
        for (int k = 0; k < ssrNsat; k++) {
            i = decodeSsrSatId(i, sys);
            int sat = this.ephsat;
            if (sat <= 0 || sat > Constants.MAXSAT) { i += 6; continue; }
            int cls = (int) BitUtils.getbitu(buff, i, 3); i += 3;
            int val = (int) BitUtils.getbitu(buff, i, 3); i += 3;
            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[3] = new GTime(time);
            ssr.iod[3] = ssrIod;
            ssr.udi[3] = ssrUdi;
            ssr.ura = ssrQualityIndex(cls, val);
            ssr.update = 1;
        }
        return true;
    }

    private boolean decodeSsrCodeBias(int sys) {
        int i = decodeSsrHead(decodeSsrEpoch(sys), sys);
        GTime time = this.time != null ? this.time : new GTime();
        int[] sigs = ssrSigTable(sys);
        for (int k = 0; k < ssrNsat; k++) {
            i = decodeSsrSatId(i, sys);
            int sat = this.ephsat;
            if (sat <= 0 || sat > Constants.MAXSAT) {
                int nsig = (int) BitUtils.getbitu(buff, i, 5); i += 5;
                i += nsig * 19;
                continue;
            }
            int nsig = (int) BitUtils.getbitu(buff, i, 5); i += 5;
            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[4] = new GTime(time);
            ssr.iod[4] = ssrIod;
            ssr.udi[4] = ssrUdi;
            for (int j = 0; j < nsig; j++) {
                int mode = (int) BitUtils.getbitu(buff, i, 5); i += 5;
                int cb = BitUtils.getbits(buff, i, 14); i += 14;
                /* RTKLIB 2.5.1 decode_ssr3: cbias[sigs[mode]-1] = bias*0.01 (m) */
                int code = (mode < sigs.length) ? sigs[mode] : 0;
                if (code > 0 && code <= Constants.MAXCODE) {
                    ssr.cbias[code - 1] = (float) (cb * 0.01);
                }
            }
            ssr.update = 1;
        }
        return true;
    }

    /* RTKLIB 2.5.1 rtcm3.c ssr_sig_* 表: SSR 5bit 信号/跟踪模式 ID -> CODE_* 索引(1-based, 0=无效) */
    private static final int[] SSR_SIG_GPS = {
        Constants.CODE_L1C, Constants.CODE_L1P, Constants.CODE_L1W, Constants.CODE_L1S,
        Constants.CODE_L1L, Constants.CODE_L2C, Constants.CODE_L2D, Constants.CODE_L2S,
        Constants.CODE_L2L, Constants.CODE_L2X, Constants.CODE_L2P, Constants.CODE_L2W,
        Constants.CODE_L2Y, Constants.CODE_L2M, Constants.CODE_L5I, Constants.CODE_L5Q,
        Constants.CODE_L5X, Constants.CODE_L1S, Constants.CODE_L1L, Constants.CODE_L1X
    };
    private static final int[] SSR_SIG_GLO = {
        Constants.CODE_L1C, Constants.CODE_L1P, Constants.CODE_L2C, Constants.CODE_L2P,
        Constants.CODE_L4A, Constants.CODE_L4B, Constants.CODE_L4X, Constants.CODE_L6A,
        Constants.CODE_L6B, Constants.CODE_L6X, Constants.CODE_L3I, Constants.CODE_L3Q,
        Constants.CODE_L3X
    };
    private static final int[] SSR_SIG_GAL = {
        Constants.CODE_L1A, Constants.CODE_L1B, Constants.CODE_L1C, Constants.CODE_L1X,
        Constants.CODE_L1Z, Constants.CODE_L5I, Constants.CODE_L5Q, Constants.CODE_L5X,
        Constants.CODE_L7I, Constants.CODE_L7Q, Constants.CODE_L7X, Constants.CODE_L8I,
        Constants.CODE_L8Q, Constants.CODE_L8X, Constants.CODE_L6A, Constants.CODE_L6B,
        Constants.CODE_L6C, Constants.CODE_L6X, Constants.CODE_L6Z
    };
    private static final int[] SSR_SIG_QZS = {
        Constants.CODE_L1C, Constants.CODE_L1S, Constants.CODE_L1L, Constants.CODE_L2S,
        Constants.CODE_L2L, Constants.CODE_L2X, Constants.CODE_L5I, Constants.CODE_L5Q,
        Constants.CODE_L5X, Constants.CODE_L6S, Constants.CODE_L6L, Constants.CODE_L6X,
        Constants.CODE_L1X, Constants.CODE_L1Z, Constants.CODE_L5D, Constants.CODE_L5P,
        Constants.CODE_L5Z, Constants.CODE_L6E, Constants.CODE_L6Z
    };
    /* RTKLIB 2.5.1 ssr_sig_bds: mode 16/17 原版为 0 (标注"不支持")。
     * 实测 CAS 每颗 BDS 卫星固定播发 mode=16 码偏差（B1I, 1561.098GHz, 与 GPS L1 同频），
     * 硬缺陷：mode=16 被过滤导致 BDS osbL1/osbP1 全为 0，MW WL 组合 sig 放大。
     * JAVA-ONLY 增强：mode 16/17 均映射到 CODE_L1C（频率一致，硬件延迟改正可直接复用）。 */
    private static final int[] SSR_SIG_BDS = {
        Constants.CODE_L2I, Constants.CODE_L2Q, Constants.CODE_L2X, Constants.CODE_L6I,
        Constants.CODE_L6Q, Constants.CODE_L6X, Constants.CODE_L7I, Constants.CODE_L7Q,
        Constants.CODE_L7X, Constants.CODE_L1D, Constants.CODE_L1P, Constants.CODE_L1X,
        Constants.CODE_L5D, Constants.CODE_L5P, Constants.CODE_L5X, Constants.CODE_L1A,
        Constants.CODE_L1C, Constants.CODE_L1C, Constants.CODE_L6A
    };
    private static final int[] SSR_SIG_SBS = {
        Constants.CODE_L1C, Constants.CODE_L5I, Constants.CODE_L5Q
    };

    private static int[] ssrSigTable(int sys) {
        switch (sys) {
            case Constants.SYS_GPS: return SSR_SIG_GPS;
            case Constants.SYS_GLO: return SSR_SIG_GLO;
            case Constants.SYS_GAL: return SSR_SIG_GAL;
            case Constants.SYS_QZS: return SSR_SIG_QZS;
            case Constants.SYS_CMP: return SSR_SIG_BDS;
            case Constants.SYS_SBS: return SSR_SIG_SBS;
            default: return SSR_SIG_GPS;
        }
    }

    /* RTKLIB 2.5.1 rtcm3.c decode_ssr7(subtype=0) + decode_ssr7_head 对齐:
     * head(无 datum 位): epoch + udi(4) + sync(1) + iod(4) + provid(16) + solid(4)
     *                    + dispe(1) + mw(1) + nsat(QZS=4, 其他=6)
     * per-sat: prn(GLO=5/QZS=4/其他=6, offp: QZS+192/SBS+120) + nbias(5)
     *          + yaw_ang(u9) + yaw_rate(s8)
     * per-bias(32bit): mode(5) + sii(1) + swl(2) + sdc(4) + bias(s20 × 0.0001 m) */
    private boolean decodeSsrPhaseBias(int sys) {
        int i = decodeSsrEpoch(sys);
        ssrUdi = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        ssrMi = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        ssrIod = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        ssrProviderId = (int) BitUtils.getbitu(buff, i, 16); i += 16;
        ssrSolutionId = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        i += 1; /* dispe: dispersive bias consistency indicator */
        i += 1; /* mw: MW consistency indicator */
        int ns = (sys == Constants.SYS_QZS) ? 4 : 6;
        ssrNsat = (int) BitUtils.getbitu(buff, i, ns); i += ns;
        int np = (sys == Constants.SYS_GLO) ? 5 : (sys == Constants.SYS_QZS) ? 4 : 6;
        int offp = (sys == Constants.SYS_QZS) ? 192 : (sys == Constants.SYS_SBS) ? 120 : 0;
        int[] sigs = ssrSigTable(sys);
        GTime time = this.time != null ? this.time : new GTime();
        for (int k = 0; k < ssrNsat; k++) {
            int prn = (int) BitUtils.getbitu(buff, i, np) + offp; i += np;
            int sat = SatUtils.satno(sys, prn);
            this.ephsat = sat;
            if (sat <= 0 || sat > Constants.MAXSAT) {
                int nsig = (int) BitUtils.getbitu(buff, i, 5); i += 5;
                i += 17 + nsig * 32;
                continue;
            }
            int nsig = (int) BitUtils.getbitu(buff, i, 5); i += 5;
            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[5] = new GTime(time);
            ssr.iod[5] = ssrIod;
            ssr.udi[5] = ssrUdi;
            int yaw = (int) BitUtils.getbitu(buff, i, 9); i += 9;
            int dyaw = BitUtils.getbits(buff, i, 8); i += 8;
            ssr.yaw_ang = yaw / 256.0 * 180.0;    /* (deg)  对齐 RTKLIB 2.5.1 */
            ssr.yaw_rate = dyaw / 8192.0 * 180.0; /* (deg/s) */
            for (int j = 0; j < nsig; j++) {
                int mode = (int) BitUtils.getbitu(buff, i, 5); i += 5;
                i += 1; /* sii: signal integer indicator */
                i += 2; /* swl: signal wide-lane integer indicator */
                int di = (int) BitUtils.getbitu(buff, i, 4); i += 4;
                int pb = BitUtils.getbits(buff, i, 20); i += 20;
                int code = (mode < sigs.length) ? sigs[mode] : 0;
                if (code > 0 && code <= Constants.MAXCODE) {
                    ssr.pbias[code - 1] = pb * 0.0001;
                    ssr.dispInd[code - 1] = di;
                    if (di != 0) {
                        ssr.dispBias[code - 1] = ssr.pbias[code - 1];
                    } else {
                        ssr.dispBias[code - 1] = 0.0;
                    }
                }
            }
            ssr.update = 1;
        }
        return true;
    }

    /* RTKLIB 2.5.1 decode_ssr4 (combined orbit+clock): per-sat iode 位宽 ni 与 iodcrc 位宽 nj
     * 与 decode_ssr1 相同；每星总码元位 = np + ni + nj + 191（轨道 121 + 钟差 70）。
     * BDS subtype=0 时 ssr.iode = iodcrc（同 decode_ssr1）。 */
    private boolean decodeSsrCombOrbClk(int sys) {
        int i = decodeSsrHead(decodeSsrEpoch(sys), sys, true);
        GTime time = this.time != null ? this.time : new GTime();
        final int ni = (sys == Constants.SYS_GAL || sys == Constants.SYS_CMP) ? 10
                : (sys == Constants.SYS_SBS) ? 9 : 8;
        final int nj = (sys == Constants.SYS_CMP) ? 8 : (sys == Constants.SYS_SBS) ? 24 : 0;
        for (int k = 0; k < ssrNsat; k++) {
            i = decodeSsrSatId(i, sys);
            int sat = this.ephsat;
            int iode = (int) BitUtils.getbitu(buff, i, ni); i += ni;
            int iodcrc = (int) BitUtils.getbitu(buff, i, nj); i += nj;
            int dx = BitUtils.getbits(buff, i, 22); i += 22;
            int dy = BitUtils.getbits(buff, i, 20); i += 20;
            int dz = BitUtils.getbits(buff, i, 20); i += 20;
            int ddx = BitUtils.getbits(buff, i, 21); i += 21;
            int ddy = BitUtils.getbits(buff, i, 19); i += 19;
            int ddz = BitUtils.getbits(buff, i, 19); i += 19;
            int dclk0 = BitUtils.getbits(buff, i, 22); i += 22;
            int dclk1 = BitUtils.getbits(buff, i, 21); i += 21;
            int dclk2 = BitUtils.getbits(buff, i, 27); i += 27;
            if (sat <= 0 || sat > Constants.MAXSAT) {
                continue;
            }
            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[0] = new GTime(time);
            ssr.t0[1] = new GTime(time);
            ssr.iod[0] = ssrIod;
            ssr.iod[1] = ssrIod;
            ssr.udi[0] = ssrUdi;
            ssr.udi[1] = ssrUdi;
            ssr.iode = (sys == Constants.SYS_CMP) ? iodcrc : iode;
            ssr.iodcrc = iodcrc;
            ssr.refd = ssrRefd;
            ssr.deph[0] = ssrSigned(dx, 22, 0.1e-3);
            ssr.deph[1] = ssrSigned(dy, 20, 0.4e-3);
            ssr.deph[2] = ssrSigned(dz, 20, 0.4e-3);
            ssr.ddeph[0] = ssrSigned(ddx, 21, 1e-6);
            ssr.ddeph[1] = ssrSigned(ddy, 19, 4e-6);
            ssr.ddeph[2] = ssrSigned(ddz, 19, 4e-6);
            ssr.dclk[0] = ssrSigned(dclk0, 22, 0.1e-3);
            ssr.dclk[1] = ssrSigned(dclk1, 21, 0.4e-3);
            ssr.dclk[2] = ssrSigned(dclk2, 27, 4e-6);
            ssr.update = 1;
        }
        return true;
    }

    private Ssr getOrCreateSsr(int sat) {
        if (this.nav.ssr[sat - 1] == null) {
            this.nav.ssr[sat - 1] = new Ssr();
        }
        return this.nav.ssr[sat - 1];
    }

    private static double ssrSigned(int raw, int nbits, double scale) {
        int sign = 1 << (nbits - 1);
        if (raw < 0) raw += (1 << nbits);
        if (raw >= sign) raw -= (1 << nbits);
        return raw * scale;
    }

    private static int ssrQualityIndex(int cls, int val) {
        if (cls == 0) return (val + 1);
        if (cls == 1) return (1 << (val + 2));
        if (cls == 2) return (1 << (val + 4));
        if (cls == 3) return (1 << (val + 6));
        if (cls == 4) return (1 << (val + 8));
        if (cls == 5) return (1 << (val + 10));
        if (cls == 6) return (1 << (val + 12));
        if (cls == 7) return -1;
        return 0;
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
                log.trace("MSM BDS TOW: raw={}, tow={}", towRaw, tow);
                adjweek(tow);
                log.trace("MSM BDS after adjweek: time.time={}, time.sec={}", this.time.time, this.time.sec);
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

        log.trace("decode_msm_head: sys={} staid={} nsat={} nsig={} sync={} iod={} ncell={}",
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
        this.obsflag = (sync[0] != 0 && !this.relaxSyncFlag) ? 0 : 1;
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
            this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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

        this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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
            this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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

        this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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
            this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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

        this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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
            this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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

        this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
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
        int i, j, k, prn, sat, fcn, index = 0;
        int[] idx = new int[32];

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
        }

        ObsCode.sigindex(sys, code, h.nsig, idx);

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
                if (h.cellmask[k + i * h.nsig] == 0) {
                    continue;
                }

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
            if (sat != 0 && index >= 0) {
                promoteExtSig(this.obs.data[index], sys);
            }
        }
    }

    private static void promoteExtSig(Obsd obs, int sys) {
        for (int f = 0; f < Constants.NFREQ; f++) {
            if (obs.code[f] != 0 && (obs.L[f] != 0.0 || obs.P[f] != 0.0)) continue;
            int freqIdx = f;
            for (int ex = Constants.NFREQ; ex < Constants.NFREQ + Constants.NEXOBS; ex++) {
                if (obs.code[ex] == 0) continue;
                int sigFreqIdx = ObsCode.code2idx(sys, obs.code[ex]);
                if (sigFreqIdx != freqIdx) continue;
                if (obs.L[ex] == 0.0 && obs.P[ex] == 0.0) continue;
                obs.L[f] = obs.L[ex];
                obs.P[f] = obs.P[ex];
                obs.D[f] = obs.D[ex];
                obs.LLI[f] = obs.LLI[ex];
                obs.SNR[f] = obs.SNR[ex];
                obs.code[f] = obs.code[ex];
                obs.L[ex] = 0.0;
                obs.P[ex] = 0.0;
                obs.D[ex] = 0.0f;
                obs.LLI[ex] = 0;
                obs.SNR[ex] = 0.0f;
                obs.code[ex] = 0;
                break;
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
        if (this.time.time == 0) {
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
        if (this.time.time == 0) {
            this.time = TimeSystem.utc2gpst(TimeSystem.timeget());
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