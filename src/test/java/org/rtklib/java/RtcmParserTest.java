package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.common.BitUtils;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 RTCM3 解析。
 * 结果分三个文件输出：观测数据、星历数据、基站信息。
 */
@DisplayName("1. RTCM3 解析测试")
public class RtcmParserTest {

    private static final Logger log = LoggerFactory.getLogger(RtcmParserTest.class);

    private static final String ROVER_PATH =
            "C:\\Users\\admin\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";

    private static final String BASE_PATH =
            "C:\\Users\\admin\\Desktop\\540423496360\\2026-06-08\\1.rtcm3";

    private static final String RESULT_DIR = "C:\\Users\\admin\\Desktop\\rtklib_java_results";

    private static byte[] roverData;
    private static byte[] baseData;

    static boolean isObsType(int type) {
        return (type >= 1001 && type <= 1004)
                || (type >= 1074 && type <= 1077)
                || (type >= 1084 && type <= 1087)
                || (type >= 1094 && type <= 1097)
                || (type >= 1104 && type <= 1107)
                || (type >= 1114 && type <= 1117)
                || (type >= 1124 && type <= 1127)
                || (type >= 1134 && type <= 1137);
    }

    static boolean isNavType(int type) {
        return (type >= 1019 && type <= 1046);
    }

    static boolean isStaType(int type) {
        return (type >= 1005 && type <= 1008);
    }

    static String sysToStr(int sys) {
        if (sys == Constants.SYS_GPS) return "G";
        if (sys == Constants.SYS_GLO) return "R";
        if (sys == Constants.SYS_GAL) return "E";
        if (sys == Constants.SYS_CMP) return "C";
        if (sys == Constants.SYS_QZS) return "J";
        if (sys == Constants.SYS_SBS) return "S";
        if (sys == Constants.SYS_IRN) return "I";
        return "?";
    }

    static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        }
        new java.io.File(RESULT_DIR).mkdirs();
        log.info("========== 加载 RTCM3 数据 ==========");
        log.info("Rover: {} bytes, Base: {} bytes", roverData.length, baseData.length);
    }

    @Test
    @DisplayName("Rover RTCM3 解析（观测/星历/基站 分文件输出）")
    void testRoverRtcmParsing() throws IOException {
        String ts = timestamp();
        String obsFile = RESULT_DIR + "\\" + ts + "_rover_obs.txt";
        String navFile = RESULT_DIR + "\\" + ts + "_rover_nav.txt";
        String staFile = RESULT_DIR + "\\" + ts + "_rover_sta.txt";

        Rtcm rtcm = new Rtcm();
        int offset = 0;
        int msgCount = 0;
        int obsMsgCount = 0;
        int navMsgCount = 0;
        int staMsgCount = 0;
        int obsEpochs = 0;
        int[] typeCount = new int[4096];

        while (offset < roverData.length) {
            int consumed = rtcm.input(roverData, offset, roverData.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            msgCount++;
            int type = rtcm.type;
            if (type >= 0 && type < 4096) typeCount[type]++;

            if (isObsType(type)) { obsMsgCount++; if (rtcm.obsflag != 0 && rtcm.obs.n > 0) obsEpochs++; }
            else if (isNavType(type)) { navMsgCount++; }
            else if (isStaType(type)) { staMsgCount++; }
        }

        // ---- 观测数据文件 ----
        BufferedWriter obsWriter = new BufferedWriter(new java.io.FileWriter(obsFile));
        obsWriter.write("# Rover 观测数据\n");
        obsWriter.write(String.format("# 总消息数: %d, 观测消息: %d, 完整历元: %d\n", msgCount, obsMsgCount, obsEpochs));
        obsWriter.write("#\n# --- 各类型消息统计 ---\n");
        for (int i = 0; i < 4096; i++) {
            if (typeCount[i] > 0) {
                obsWriter.write(String.format("#   Type %4d: %4d 条 %s\n", i, typeCount[i], getMsgDesc(i)));
            }
        }
        int nObs = Constants.NFREQ + Constants.NEXOBS;
        StringBuilder hdrBuf = new StringBuilder("# 历元序号  时间                          卫星  PRN");
        for (int fi = 0; fi < nObs; fi++) hdrBuf.append(String.format("  P[F%d]         ", fi + 1));
        for (int fi = 0; fi < nObs; fi++) hdrBuf.append(String.format("  L[F%d]         ", fi + 1));
        for (int fi = 0; fi < nObs; fi++) hdrBuf.append(String.format("  SNR[F%d]", fi + 1));
        obsWriter.write("#\n# --- 观测数据 ---\n");
        obsWriter.write(hdrBuf.toString() + "\n");

        Rtcm rtcm2 = new Rtcm();
        offset = 0;
        int epochNum = 0;
        while (offset < roverData.length) {
            int consumed = rtcm2.input(roverData, offset, roverData.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            if (isObsType(rtcm2.type) && rtcm2.obs.n > 0 && rtcm2.obsflag == 1) {
                epochNum++;
                double[] ymdhms = TimeSystem.time2ymdhms(rtcm2.obs.data[0].time);
                obsWriter.write(String.format("# 历元 #%d: %04d-%02d-%02d %02d:%02d:%09.6f, 卫星数: %d\n",
                        epochNum,
                        (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                        (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                        rtcm2.obs.n));
                for (int i = 0; i < rtcm2.obs.n; i++) {
                    Obsd o = rtcm2.obs.data[i];
                    int[] prn = new int[1];
                    int sys = SatUtils.satsys(o.sat, prn);
                    String sysStr = sysToStr(sys);
                    StringBuilder line = new StringBuilder(String.format("  %4d  %s%02d", epochNum, sysStr, prn[0]));
                    for (int fi = 0; fi < nObs; fi++) line.append(String.format("  %14.3f", o.P[fi]));
                    for (int fi = 0; fi < nObs; fi++) line.append(String.format("  %14.3f", o.L[fi]));
                    for (int fi = 0; fi < nObs; fi++) line.append(String.format("  %6.1f", o.SNR[fi]));
                    obsWriter.write(line.toString() + "\n");
                }
            }
        }
        obsWriter.write(String.format("#\n# 总历元数: %d\n", epochNum));
        obsWriter.close();
        log.info("Rover 观测数据已写入: {}", obsFile);

        // ---- 星历数据文件 ----
        BufferedWriter navWriter = new BufferedWriter(new java.io.FileWriter(navFile));
        navWriter.write("# Rover 星历数据\n");
        navWriter.write(String.format("# 导航消息: %d\n", navMsgCount));
        navWriter.write("#\n# --- 各类型消息统计 ---\n");
        for (int i = 0; i < 4096; i++) {
            if (typeCount[i] > 0 && (isNavType(i))) {
                navWriter.write(String.format("#   Type %4d: %4d 条 %s\n", i, typeCount[i], getMsgDesc(i)));
            }
        }
        navWriter.write("#\n# --- 星历详细 ---\n");
        navWriter.write("# 卫星  PRN   toe                         A(sqrtA)     e           i0(rad)      OMG0(rad)    omg(rad)     M0(rad)      deln         OMGd         idot         f0           f1           f2\n");

        int ephCount = 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            if (rtcm.nav.eph[i] != null && rtcm.nav.eph[i].sat != 0) {
                Eph eph = rtcm.nav.eph[i];
                int[] prn = new int[1];
                int sys = SatUtils.satsys(eph.sat, prn);
                String sysStr = sysToStr(sys);
                double[] toe = TimeSystem.time2ymdhms(eph.toe);
                navWriter.write(String.format("  %s%02d    %04d-%02d-%02d %02d:%02d:%09.6f  %12.3f  %.10f  %.10f  %.10f  %.10f  %.10f  %.10e  %.10e  %.10e  %.10e  %.10e  %.10e\n",
                        sysStr, prn[0],
                        (int)toe[0], (int)toe[1], (int)toe[2],
                        (int)toe[3], (int)toe[4], toe[5],
                        eph.A, eph.e, eph.i0, eph.OMG0, eph.omg, eph.M0,
                        eph.deln, eph.OMGd, eph.idot,
                        eph.f0, eph.f1, eph.f2));
                ephCount++;
            }
        }
        int gephCount = 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            if (rtcm.nav.geph[i] != null && rtcm.nav.geph[i].sat != 0) {
                Geph geph = rtcm.nav.geph[i];
                int[] prn = new int[1];
                int sys = SatUtils.satsys(geph.sat, prn);
                double[] toe = TimeSystem.time2ymdhms(geph.toe);
                navWriter.write(String.format("  R%02d    %04d-%02d-%02d %02d:%02d:%09.6f  frq=%d  pos=(%.3f,%.3f,%.3f)  vel=(%.6f,%.6f,%.6f)  acc=(%.6f,%.6f,%.6f)\n",
                        prn[0],
                        (int)toe[0], (int)toe[1], (int)toe[2],
                        (int)toe[3], (int)toe[4], toe[5],
                        geph.frq,
                        geph.pos[0], geph.pos[1], geph.pos[2],
                        geph.vel[0], geph.vel[1], geph.vel[2],
                        geph.acc[0], geph.acc[1], geph.acc[2]));
                gephCount++;
            }
        }
        navWriter.write(String.format("#\n# 星历总数: eph=%d, geph=%d\n", ephCount, gephCount));
        navWriter.close();
        log.info("Rover 星历数据已写入: {}", navFile);

        // ---- 基站信息文件 ----
        BufferedWriter staWriter = new BufferedWriter(new java.io.FileWriter(staFile));
        staWriter.write("# Rover 基站信息\n");
        staWriter.write(String.format("# 站点消息: %d\n", staMsgCount));
        staWriter.write("#\n# --- 各类型消息统计 ---\n");
        for (int i = 0; i < 4096; i++) {
            if (typeCount[i] > 0 && isStaType(i)) {
                staWriter.write(String.format("#   Type %4d: %4d 条 %s\n", i, typeCount[i], getMsgDesc(i)));
            }
        }
        staWriter.write("#\n# --- 基站信息 ---\n");
        staWriter.write(String.format("# Station ID: %d\n", rtcm.staid));
        if (rtcm.sta.pos != null && rtcm.sta.pos[0] != 0.0) {
            staWriter.write(String.format("# ECEF X: %.4f m\n", rtcm.sta.pos[0]));
            staWriter.write(String.format("# ECEF Y: %.4f m\n", rtcm.sta.pos[1]));
            staWriter.write(String.format("# ECEF Z: %.4f m\n", rtcm.sta.pos[2]));
            double[] llh = new double[3];
            org.rtklib.java.coord.CoordTransform.ecef2pos(rtcm.sta.pos, llh);
            staWriter.write(String.format("# Lat: %.9f deg\n", llh[0] * Constants.R2D));
            staWriter.write(String.format("# Lon: %.9f deg\n", llh[1] * Constants.R2D));
            staWriter.write(String.format("# Hgt: %.4f m\n", llh[2]));
        }
        staWriter.close();
        log.info("Rover 基站信息已写入: {}", staFile);

        assertTrue(msgCount > 0, "应解码至少一条 RTCM 消息");
        assertTrue(obsMsgCount > 0, "应解码至少一条观测消息");
        assertTrue(obsEpochs > 0, "应有至少一个完整观测历元");
        log.info("Rover RTCM3 解析测试 通过");
    }

    @Test
    @DisplayName("Base RTCM3 解析（观测/星历/基站 分文件输出）")
    void testBaseRtcmParsing() throws IOException {
        String ts = timestamp();
        String obsFile = RESULT_DIR + "\\" + ts + "_base_obs.txt";
        String staFile = RESULT_DIR + "\\" + ts + "_base_sta.txt";

        Rtcm rtcm = new Rtcm();
        int offset = 0;
        int msgCount = 0;
        int obsMsgCount = 0;
        int obsEpochs = 0;
        int[] typeCount = new int[4096];

        while (offset < baseData.length) {
            int consumed = rtcm.input(baseData, offset, baseData.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            msgCount++;
            int type = rtcm.type;
            if (type >= 0 && type < 4096) typeCount[type]++;
            if (isObsType(type)) { obsMsgCount++; if (rtcm.obs.n > 0) obsEpochs++; }
        }

        // ---- 观测数据文件 ----
        BufferedWriter obsWriter = new BufferedWriter(new java.io.FileWriter(obsFile));
        obsWriter.write("# Base 观测数据\n");
        obsWriter.write(String.format("# 总消息数: %d, 观测消息: %d, 完整历元: %d\n", msgCount, obsMsgCount, obsEpochs));
        obsWriter.write("#\n# --- 各类型消息统计 ---\n");
        for (int i = 0; i < 4096; i++) {
            if (typeCount[i] > 0) {
                obsWriter.write(String.format("#   Type %4d: %4d 条 %s\n", i, typeCount[i], getMsgDesc(i)));
            }
        }
        obsWriter.write("#\n# --- 观测数据 ---\n");
        obsWriter.write("# 历元序号  时间                          卫星  PRN   P[F1]         P[F2]         P[F3]         L[F1]         L[F2]         L[F3]         SNR[F1]  SNR[F2]  SNR[F3]\n");

        Rtcm rtcm2 = new Rtcm();
        offset = 0;
        int epochNum = 0;
        while (offset < baseData.length) {
            int consumed = rtcm2.input(baseData, offset, baseData.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            if (isObsType(rtcm2.type) && rtcm2.obs.n > 0 && rtcm2.obsflag == 1) {
                epochNum++;
                double[] ymdhms = TimeSystem.time2ymdhms(rtcm2.obs.data[0].time);
                obsWriter.write(String.format("# 历元 #%d: %04d-%02d-%02d %02d:%02d:%09.6f, 卫星数: %d\n",
                        epochNum,
                        (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                        (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                        rtcm2.obs.n));
                for (int i = 0; i < rtcm2.obs.n; i++) {
                    Obsd o = rtcm2.obs.data[i];
                    int[] prn = new int[1];
                    int sys = SatUtils.satsys(o.sat, prn);
                    String sysStr = sysToStr(sys);
                    obsWriter.write(String.format("  %4d  %s%02d  %14.3f  %14.3f  %14.3f  %14.3f  %14.3f  %14.3f  %6.1f  %6.1f  %6.1f\n",
                            epochNum, sysStr, prn[0],
                            o.P[0], o.P[1], o.P[2],
                            o.L[0], o.L[1], o.L[2],
                            o.SNR[0], o.SNR[1], o.SNR[2]));
                }
            }
        }
        obsWriter.write(String.format("#\n# 总历元数: %d\n", epochNum));
        obsWriter.close();
        log.info("Base 观测数据已写入: {}", obsFile);

        // ---- 基站信息文件 ----
        BufferedWriter staWriter = new BufferedWriter(new java.io.FileWriter(staFile));
        staWriter.write("# Base 基站信息\n");
        staWriter.write("#\n# --- 基站信息 ---\n");
        staWriter.write(String.format("# Station ID: %d\n", rtcm.staid));
        if (rtcm.sta.pos != null && rtcm.sta.pos[0] != 0.0) {
            staWriter.write(String.format("# ECEF X: %.4f m\n", rtcm.sta.pos[0]));
            staWriter.write(String.format("# ECEF Y: %.4f m\n", rtcm.sta.pos[1]));
            staWriter.write(String.format("# ECEF Z: %.4f m\n", rtcm.sta.pos[2]));
            double[] llh = new double[3];
            org.rtklib.java.coord.CoordTransform.ecef2pos(rtcm.sta.pos, llh);
            staWriter.write(String.format("# Lat: %.9f deg\n", llh[0] * Constants.R2D));
            staWriter.write(String.format("# Lon: %.9f deg\n", llh[1] * Constants.R2D));
            staWriter.write(String.format("# Hgt: %.4f m\n", llh[2]));
        }
        staWriter.close();
        log.info("Base 基站信息已写入: {}", staFile);

        assertTrue(msgCount > 0, "应解码至少一条 RTCM 消息");
        assertTrue(obsMsgCount > 0, "应解码至少一条观测消息");
        log.info("Base RTCM3 解析测试 通过");
    }

    private String getMsgDesc(int type) {
        if (type >= 1001 && type <= 1004) return "(GPS L1/L2 obs)";
        if (type == 1005) return "(Station ARP)";
        if (type == 1006) return "(Station ARP+H)";
        if (type == 1007 || type == 1008) return "(Antenna)";
        if (type == 1019) return "(GPS Eph)";
        if (type == 1020) return "(GLO Eph)";
        if (type == 1029) return "(Text)";
        if (type == 1033) return "(Receiver/antenna desc)";
        if (type >= 1041 && type <= 1042) return "(BDS Eph)";
        if (type >= 1044 && type <= 1045) return "(QZS/GAL Eph)";
        if (type >= 1071 && type <= 1077) return "(GPS MSM)";
        if (type >= 1081 && type <= 1087) return "(GLO MSM)";
        if (type >= 1091 && type <= 1097) return "(GAL MSM)";
        if (type >= 1111 && type <= 1117) return "(QZS MSM)";
        if (type >= 1121 && type <= 1127) return "(BDS MSM)";
        return "";
    }

    // ========================================================================
    // 与 RTKLIB C 源码逐位对比测试
    // 手动按 C 源码的位操作解析原始字节，与 Rtcm 类输出对比
    // ========================================================================

    private static final double RANGE_MS = 299792.458;
    private static final double P2_10 = 1.0 / 1024.0;
    private static final double P2_24 = 1.0 / 16777216.0;
    private static final double P2_29 = 1.0 / 536870912.0;
    private static final double CLIGHT = 299792458.0;
    private static final double FREQL1 = 1.57542E9;
    private static final double P2_43 = 1.0 / 8796093022208.0;
    private static final double P2_31 = 1.0 / 2147483648.0;
    private static final double P2_33 = 1.0 / 8589934592.0;
    private static final double P2_19 = 1.0 / 524288.0;
    private static final double P2_5 = 1.0 / 32.0;
    private static final double P2_6 = 1.0 / 64.0;
    private static final double P2_32 = 1.0 / 4294967296.0;
    private static final double P2_50 = 1.0 / 1125899906842624.0;
    private static final double P2_55 = 1.0 / 36028797018963968.0;
    private static final double P2_59 = 1.0 / 576460752303423488.0;
    private static final double P2_66 = 1.0 / 73786976294838210000.0;
    private static final double SC2RAD = 3.1415926535898;

    @Test
    @DisplayName("逐位对比：RTKLIB C vs Java 对 MSM 1124 和星历 1042 的解析")
    void testBitLevelComparison() throws IOException {
        log.info("========== 逐位对比测试 ==========");

        // 扫描 rover 数据，找到所有 1124 和 1042 消息
        int offset = 0;
        int foundMsm1124 = 0;
        int foundEph1042 = 0;
        while (offset < roverData.length && (foundMsm1124 < 1 || foundEph1042 < 1)) {
            // 寻找 RTCM3 同步头 0xD3
            int syncPos = -1;
            for (int i = offset; i < roverData.length; i++) {
                if ((roverData[i] & 0xFF) == 0xD3) {
                    syncPos = i;
                    break;
                }
            }
            if (syncPos < 0) break;
            if (syncPos + 6 > roverData.length) break;

            // 读取消息长度 (bits 14-23 of header)
            int msgLen = ((roverData[syncPos + 1] & 0x03) << 8) | (roverData[syncPos + 2] & 0xFF);
            int totalLen = msgLen + 6; // 3 bytes header + msgLen bytes body + 3 bytes CRC
            if (syncPos + totalLen > roverData.length) {
                offset = syncPos + 1;
                continue;
            }

            // 计算 CRC（与 RTKLIB C 一致：对 header+body 计算 CRC-24Q）
            int crc = ((roverData[syncPos + totalLen - 3] & 0xFF) << 16)
                    | ((roverData[syncPos + totalLen - 2] & 0xFF) << 8)
                    | (roverData[syncPos + totalLen - 1] & 0xFF);
            int calcCrc = calcCrc24q(roverData, syncPos, totalLen - 3);
            if (crc != calcCrc) {
                offset = syncPos + 1;
                continue;
            }

            // 消息类型 (bits 24-35 of buff)
            byte[] buff = new byte[totalLen];
            System.arraycopy(roverData, syncPos, buff, 0, totalLen);
            int type = (int) BitUtils.getbitu(buff, 24, 12);

            if (type == 1124 && foundMsm1124 < 1) {
                compareMsm1124(buff);
                foundMsm1124++;
            }
            if (type == 1042 && foundEph1042 < 1) {
                compareEph1042(buff);
                foundEph1042++;
            }

            offset = syncPos + totalLen;
        }
    }

    private void compareMsm1124(byte[] buff) {
        System.out.println("--- MSM 1124 逐位对比 ---");
        StringBuilder sb = new StringBuilder();

        int i = 24;
        int type = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        sb.append(String.format("  type=%d%n", type));

        // MSM header (匹配 RTKLIB C: decode_msm_head)
        int staid = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        long towRaw = BitUtils.getbitu(buff, i, 30);
        double tow = towRaw * 0.001; i += 30;
        tow += 14.0; // BDT -> GPST
        sb.append(String.format("  staid=%d, TOW_raw=%d, TOW_adjusted=%.1f%n", staid, towRaw, tow));

        int sync = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int iod = (int) BitUtils.getbitu(buff, i, 3); i += 3;
        int time_s = (int) BitUtils.getbitu(buff, i, 7); i += 7;
        int clk_str = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        int clk_ext = (int) BitUtils.getbitu(buff, i, 2); i += 2;
        int smooth = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        int tint_s = (int) BitUtils.getbitu(buff, i, 3); i += 3;
        sb.append(String.format("  sync=%d, iod=%d, time_s=%d, clk_str=%d, clk_ext=%d, smooth=%d, tint_s=%d%n",
                sync, iod, time_s, clk_str, clk_ext, smooth, tint_s));

        // 卫星掩码
        int[] sats = new int[64];
        int nsat = 0;
        for (int j = 1; j <= 64; j++) {
            int mask = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            if (mask != 0) {
                sats[nsat++] = j;
            }
        }

        // 信号掩码
        int[] sigs = new int[32];
        int nsig = 0;
        for (int j = 1; j <= 32; j++) {
            int mask = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            if (mask != 0) {
                sigs[nsig++] = j;
            }
        }
        sb.append(String.format("  nsat=%d, nsig=%d, sats=[", nsat, nsig));
        for (int j = 0; j < nsat; j++) sb.append(sats[j]).append(j < nsat - 1 ? "," : "");
        sb.append("]");

        // 信号ID映射
        String[] msmSigCmp = {
            "", "2I", "7I", "6I", "3I", "1I", "7Q", "6Q", "5Q", "7D", "6D", "5D",
            "1D", "5P", "6P", "7P", "8P", "5X", "1X", "3X", "2X", "6X", "7X", "8X",
            "1S", "6S", "7S", "8S", "1L", "6L", "7L", "8L"
        };

        String[] sigNames = new String[nsig];
        sb.append("  sigs=[");
        for (int j = 0; j < nsig; j++) {
            sigNames[j] = msmSigCmp[sigs[j]];
            sb.append(sigNames[j]).append(j < nsig - 1 ? "," : "");
        }
        sb.append("]\n");

        // cell mask
        int[] cellmask = new int[nsat * nsig];
        int ncell = 0;
        for (int j = 0; j < nsat * nsig; j++) {
            cellmask[j] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
            if (cellmask[j] != 0) ncell++;
        }
        sb.append(String.format("  ncell=%d, header_bits=%d%n", ncell, i));

        // 卫星数据：粗距
        double[] r = new double[nsat];
        for (int j = 0; j < nsat; j++) {
            int rng = (int) BitUtils.getbitu(buff, i, 8); i += 8;
            if (rng != 255) r[j] = rng * RANGE_MS;
        }
        for (int j = 0; j < nsat; j++) {
            int rng_m = (int) BitUtils.getbitu(buff, i, 10); i += 10;
            if (r[j] != 0.0) r[j] += rng_m * P2_10 * RANGE_MS;
            r[j] = Math.round(r[j] * 1000.0) / 1000.0;
        }

        // 信号数据
        double[] pr = new double[ncell];
        double[] cp = new double[ncell];
        double[] cnr = new double[ncell];
        int[] lock = new int[ncell];
        int[] half = new int[ncell];

        for (int j = 0; j < ncell; j++) {
            int prv = (int) BitUtils.getbits(buff, i, 15); i += 15;
            pr[j] = (prv != -16384) ? prv * P2_24 * RANGE_MS : -1E16;
        }
        for (int j = 0; j < ncell; j++) {
            int cpv = (int) BitUtils.getbits(buff, i, 22); i += 22;
            cp[j] = (cpv != -2097152) ? cpv * P2_29 * RANGE_MS : -1E16;
        }
        for (int j = 0; j < ncell; j++) {
            lock[j] = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        }
        for (int j = 0; j < ncell; j++) {
            half[j] = (int) BitUtils.getbitu(buff, i, 1); i += 1;
        }
        for (int j = 0; j < ncell; j++) {
            cnr[j] = BitUtils.getbitu(buff, i, 6) * 1.0; i += 6;
        }

        // ---- 手动解析结果 (模拟 RTKLIB C) ----
        sb.append("--- 手动解析 (RTKLIB C 风格) ---\n");
        int cellIdx = 0;
        for (int satIdx = 0; satIdx < nsat; satIdx++) {
            for (int sigIdx = 0; sigIdx < nsig; sigIdx++) {
                if (cellmask[sigIdx + satIdx * nsig] == 0) continue;
                if (r[satIdx] != 0.0 && pr[cellIdx] > -1E12) {
                    double P = r[satIdx] + pr[cellIdx];
                    double L = (cp[cellIdx] > -1E12) ? r[satIdx] + cp[cellIdx] : 0;
                    sb.append(String.format("  C%02d %-3s: P=%12.3f L=%12.3f SNR=%5.1f lock=%d half=%d%n",
                            sats[satIdx], sigNames[sigIdx], P, L, cnr[cellIdx], lock[cellIdx], half[cellIdx]));
                }
                cellIdx++;
            }
        }

        // ---- 用 Rtcm 类解码同一消息，对比 ----
        sb.append("--- Rtcm 类解码结果 ---\n");
        Rtcm rtcm = new Rtcm();
        int offset = 0;
        while (offset < buff.length) {
            int consumed = rtcm.input(buff, offset, buff.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            if (rtcm.type == 1124 && rtcm.obs.n > 0) {
                for (int oi = 0; oi < rtcm.obs.n; oi++) {
                    Obsd obsd = rtcm.obs.data[oi];
                    int[] prn = new int[1];
                    int sys = SatUtils.satsys(obsd.sat, prn);
                    String sysStr = sysToStr(sys);
                    sb.append(String.format("  %s %02d: P=[%12.3f,%12.3f,%12.3f] L=[%12.3f,%12.3f,%12.3f] SNR=[%5.1f,%5.1f,%5.1f] LLI=[%d,%d,%d]%n",
                            sysStr, prn[0],
                            obsd.P[0], obsd.P[1], obsd.P[2],
                            obsd.L[0], obsd.L[1], obsd.L[2],
                            obsd.SNR[0], obsd.SNR[1], obsd.SNR[2],
                            obsd.LLI[0], obsd.LLI[1], obsd.LLI[2]));
                }
            }
        }
        System.out.print(sb.toString());
    }

    private void compareEph1042(byte[] buff) {
        System.out.println("--- 星历 1042 逐位对比 ---");
        StringBuilder sb = new StringBuilder();

        int i = 24;
        int type = (int) BitUtils.getbitu(buff, i, 12); i += 12;
        sb.append(String.format("  type=%d%n", type));

        int prn = (int) BitUtils.getbitu(buff, i, 6); i += 6;
        int week = (int) BitUtils.getbitu(buff, i, 13); i += 13;
        int sva = (int) BitUtils.getbitu(buff, i, 4); i += 4;
        double idot = BitUtils.getbits(buff, i, 14) * P2_43 * SC2RAD; i += 14;
        int iode = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        double toc = BitUtils.getbitu(buff, i, 17) * 8.0; i += 17;
        double f2 = BitUtils.getbits(buff, i, 11) * P2_66; i += 11;
        double f1 = BitUtils.getbits(buff, i, 22) * P2_50; i += 22;
        double f0 = BitUtils.getbits(buff, i, 24) * P2_33; i += 24;
        int iodc = (int) BitUtils.getbitu(buff, i, 5); i += 5;
        double crs = BitUtils.getbits(buff, i, 18) * P2_6; i += 18;
        double deln = BitUtils.getbits(buff, i, 16) * P2_43 * SC2RAD; i += 16;
        double M0 = BitUtils.getbits(buff, i, 32) * P2_31 * SC2RAD; i += 32;
        double cuc = BitUtils.getbits(buff, i, 18) * P2_31; i += 18;
        double e = BitUtils.getbitu(buff, i, 32) * P2_33; i += 32;
        double cus = BitUtils.getbits(buff, i, 18) * P2_31; i += 18;
        double sqrtA = BitUtils.getbitu(buff, i, 32) * P2_19; i += 32;
        double toes = BitUtils.getbitu(buff, i, 17) * 8.0; i += 17;
        double cic = BitUtils.getbits(buff, i, 18) * P2_31; i += 18;
        double OMG0 = BitUtils.getbits(buff, i, 32) * P2_31 * SC2RAD; i += 32;
        double cis = BitUtils.getbits(buff, i, 18) * P2_31; i += 18;
        double i0 = BitUtils.getbits(buff, i, 32) * P2_31 * SC2RAD; i += 32;
        double crc = BitUtils.getbits(buff, i, 18) * P2_6; i += 18;
        double omg = BitUtils.getbits(buff, i, 32) * P2_31 * SC2RAD; i += 32;
        double OMGd = BitUtils.getbits(buff, i, 24) * P2_43 * SC2RAD; i += 24;
        double tgd0 = BitUtils.getbits(buff, i, 10) * 1E-10; i += 10;
        double tgd1 = BitUtils.getbits(buff, i, 10) * 1E-10; i += 10;
        int svh = (int) BitUtils.getbitu(buff, i, 1); i += 1;

        double A = sqrtA * sqrtA;

        sb.append(String.format("--- 手动解析 (RTKLIB C 风格) ---%n"));
        sb.append(String.format("  C%02d: week=%d iode=%d iodc=%d svh=%d%n", prn, week, iode, iodc, svh));
        sb.append(String.format("  toc=%.1f toes=%.1f%n", toc, toes));
        sb.append(String.format("  sqrtA=%.8f A=%.6f e=%.10f%n", sqrtA, A, e));
        sb.append(String.format("  M0=%.10f OMG0=%.10f i0=%.10f omg=%.10f%n", M0, OMG0, i0, omg));
        sb.append(String.format("  deln=%.12e OMGd=%.12e idot=%.12e%n", deln, OMGd, idot));
        sb.append(String.format("  crs=%.6f crc=%.6f cis=%.12e cic=%.12e cus=%.12e cuc=%.12e%n",
                crs, crc, cis, cic, cus, cuc));
        sb.append(String.format("  f0=%.12e f1=%.12e f2=%.12e%n", f0, f1, f2));
        sb.append(String.format("  tgd0=%.12e tgd1=%.12e%n", tgd0, tgd1));

        // ---- 用 Rtcm 类解码同一消息，对比 ----
        sb.append(String.format("--- Rtcm 类解码结果 ---%n"));
        Rtcm rtcm = new Rtcm();
        int offset = 0;
        while (offset < buff.length) {
            int consumed = rtcm.input(buff, offset, buff.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            if (rtcm.type == 1042) {
                int sat = SatUtils.satno(Constants.SYS_CMP, prn);
                Eph eph = (sat > 0) ? rtcm.nav.eph[sat - 1] : null;
                if (eph != null && eph.A != 0.0) {
                    sb.append(String.format("  C%02d: week=%d iode=%d iodc=%d svh=%d%n",
                            prn, eph.week, eph.iode, eph.iodc, eph.svh));
                    sb.append(String.format("  toc.time=%d toc.sec=%.1f toes=%.1f%n", eph.toc.time, eph.toc.sec, eph.toes));
                    sb.append(String.format("  sqrtA=%.8f A=%.6f e=%.10f%n",
                            Math.sqrt(eph.A), eph.A, eph.e));
                    sb.append(String.format("  M0=%.10f OMG0=%.10f i0=%.10f omg=%.10f%n",
                            eph.M0, eph.OMG0, eph.i0, eph.omg));
                    sb.append(String.format("  deln=%.12e OMGd=%.12e idot=%.12e%n",
                            eph.deln, eph.OMGd, eph.idot));
                    sb.append(String.format("  crs=%.6f crc=%.6f cis=%.12e cic=%.12e cus=%.12e cuc=%.12e%n",
                            eph.crs, eph.crc, eph.cis, eph.cic, eph.cus, eph.cuc));
                    sb.append(String.format("  f0=%.12e f1=%.12e f2=%.12e%n", eph.f0, eph.f1, eph.f2));
                    sb.append(String.format("  tgd0=%.12e tgd1=%.12e%n", eph.tgd[0], eph.tgd[1]));
                }
            }
        }
        System.out.print(sb.toString());
    }

    @Test
    @DisplayName("RTKLIB convbin vs Java")
    void testCompareWithRtklib() throws IOException {
        String refDir = "C:\\Users\\admin\\Desktop\\rtklib_java_results\\rtklib_ref";
        String javaDir = RESULT_DIR;

        java.io.File refObsFile = new java.io.File(refDir, "rover.obs");
        java.io.File refNavFile = new java.io.File(refDir, "rover.nav");
        if (!refObsFile.exists()) {
            log.warn("RTKLIB reference not found, skipping comparison");
            return;
        }

        java.io.File[] javaObsFiles = new java.io.File(javaDir).listFiles((d, n) -> n.endsWith("_rover_obs.txt"));
        if (javaObsFiles == null || javaObsFiles.length == 0) {
            log.warn("Java output not found, run testRoverRtcmParsing first");
            return;
        }
        java.io.File latestJavaObs = javaObsFiles[0];
        for (java.io.File f : javaObsFiles) {
            if (f.lastModified() > latestJavaObs.lastModified()) latestJavaObs = f;
        }

        log.info("Comparing RTKLIB {} vs Java {}", refObsFile.getName(), latestJavaObs.getName());

        java.io.File[] javaNavFiles = new java.io.File(javaDir).listFiles((d, n) -> n.endsWith("_rover_nav.txt"));
        java.io.File latestJavaNav = null;
        if (javaNavFiles != null) {
            for (java.io.File f : javaNavFiles) {
                if (latestJavaNav == null || f.lastModified() > latestJavaNav.lastModified()) latestJavaNav = f;
            }
        }

        java.io.File[] javaStaFiles = new java.io.File(javaDir).listFiles((d, n) -> n.endsWith("_rover_sta.txt"));
        java.io.File latestJavaSta = null;
        if (javaStaFiles != null) {
            for (java.io.File f : javaStaFiles) {
                if (latestJavaSta == null || f.lastModified() > latestJavaSta.lastModified()) latestJavaSta = f;
            }
        }

        int obsMatch = 0, obsMismatch = 0, obsTotal = 0;

        java.util.List<String> refLines = java.nio.file.Files.readAllLines(refObsFile.toPath());
        java.util.List<String> javaLines = java.nio.file.Files.readAllLines(latestJavaObs.toPath());

        java.util.List<java.util.Map<String, double[]>> refEpochs = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, double[]>> javaEpochs = new java.util.ArrayList<>();

        java.util.Map<String, double[]> currentEpoch = null;
        boolean headerDone = false;
        int nRefObs = 10;
        int refFieldWidth = 16;
        for (String line : refLines) {
            if (!headerDone) { if (line.contains("END OF HEADER")) headerDone = true; continue; }
            if (line.startsWith(">")) {
                currentEpoch = new java.util.LinkedHashMap<>();
                refEpochs.add(currentEpoch);
                continue;
            }
            if (currentEpoch == null) continue;
            if (line.length() < 3) continue;
            String sat = line.substring(0, 3).trim();
            if (!sat.matches("[A-Z]\\d+")) continue;
            double[] vals = new double[nRefObs];
            for (int k = 0; k < nRefObs; k++) {
                int s = 3 + k * refFieldWidth;
                int e = Math.min(s + 14, line.length());
                if (s >= line.length()) { vals[k] = 0.0; continue; }
                String field = line.substring(s, e).trim();
                if (field.isEmpty()) { vals[k] = 0.0; continue; }
                try { vals[k] = Double.parseDouble(field); }
                catch (NumberFormatException ex) { vals[k] = 0.0; }
            }
            currentEpoch.put(sat, vals);
        }

        java.util.Map<String, double[]> currentJavaEpoch = null;
        int nObs = Constants.NFREQ + Constants.NEXOBS;
        for (String line : javaLines) {
            line = line.trim();
            if (line.startsWith("#")) {
                if (line.matches("#.*#\\d+:.*")) {
                    currentJavaEpoch = new java.util.LinkedHashMap<>();
                    javaEpochs.add(currentJavaEpoch);
                }
                continue;
            }
            if (currentJavaEpoch == null) continue;
            String[] parts = line.split("\\s+");
            if (parts.length >= 2 + 2 * nObs && parts[1].matches("[A-Z]\\d+")) {
                String sat = parts[1];
                int nVals = 2 * nObs;
                double[] vals = new double[nVals];
                try {
                    for (int k = 0; k < nVals; k++) {
                        vals[k] = Double.parseDouble(parts[2 + k]);
                    }
                } catch (Exception e) { continue; }
                currentJavaEpoch.put(sat, vals);
            }
        }

        int epochsToCompare = Math.min(5, Math.min(refEpochs.size(), javaEpochs.size()));
        log.info("=== OBS Comparison ({} epochs) ===", epochsToCompare);
        log.info("RTKLIB epochs: {}, Java epochs: {}", refEpochs.size(), javaEpochs.size());

        String[] sigNames = {"C2I", "L2I", "C7I", "L7I", "C5P", "L5P", "C6I", "L6I", "C1P", "L1P"};
        int[] refToJavaIdx = {0, nObs, 1, nObs + 1, 2, nObs + 2, 3, nObs + 3, 4, nObs + 4};

        for (int e = 0; e < epochsToCompare; e++) {
            java.util.Map<String, double[]> refE = refEpochs.get(e);
            java.util.Map<String, double[]> javaE = javaEpochs.get(e);
            log.info("--- Epoch {} ---", e + 1);

            for (String sat : refE.keySet()) {
                double[] r = refE.get(sat);
                double[] j = javaE.get(sat);
                if (j == null) continue;
                obsTotal++;

                boolean ok = true;
                StringBuilder mismStr = new StringBuilder();

                for (int si = 0; si < 10; si++) {
                    double rVal = r[si];
                    int jIdx = refToJavaIdx[si];
                    double jVal = (jIdx < j.length) ? j[jIdx] : 0.0;
                    if (rVal != 0 || jVal != 0) {
                        if (Math.abs(rVal - jVal) > 0.5) {
                            ok = false;
                            mismStr.append(String.format(" %s:R=%.3f J=%.3f", sigNames[si], rVal, jVal));
                        }
                    }
                }

                if (ok) { obsMatch++; }
                else { obsMismatch++; log.info("  {} MISMATCH:{}", sat, mismStr); }
            }
        }
        log.info("OBS Result: {}/{} match, {} mismatch", obsMatch, obsTotal, obsMismatch);

        if (latestJavaSta != null && latestJavaSta.exists()) {
            log.info("=== Station Position ===");
            double rx = 0, ry = 0, rz = 0;
            for (String line : refLines) {
                if (line.contains("APPROX POSITION XYZ")) {
                    String[] p = line.trim().split("\\s+");
                    rx = Double.parseDouble(p[0]); ry = Double.parseDouble(p[1]); rz = Double.parseDouble(p[2]);
                    break;
                }
            }
            double jx = 0, jy = 0, jz = 0;
            for (String line : java.nio.file.Files.readAllLines(latestJavaSta.toPath())) {
                if (line.contains("ECEF X:")) jx = Double.parseDouble(line.split(":\\s+")[1].replace(" m", ""));
                if (line.contains("ECEF Y:")) jy = Double.parseDouble(line.split(":\\s+")[1].replace(" m", ""));
                if (line.contains("ECEF Z:")) jz = Double.parseDouble(line.split(":\\s+")[1].replace(" m", ""));
            }
            double maxDiff = Math.max(Math.abs(rx - jx), Math.max(Math.abs(ry - jy), Math.abs(rz - jz)));
            log.info(String.format("RTKLIB: X=%.4f Y=%.4f Z=%.4f", rx, ry, rz));
            log.info(String.format("Java:   X=%.4f Y=%.4f Z=%.4f", jx, jy, jz));
            log.info(String.format("Station: %s (max diff: %.4f m)", maxDiff < 0.001 ? "MATCH" : "MISMATCH", maxDiff));
        }

        if (refNavFile.exists() && latestJavaNav != null) {
            log.info("=== NAV Comparison ===");
            java.util.List<String> navRefLines = java.nio.file.Files.readAllLines(refNavFile.toPath());
            java.util.List<String> navJavaLines = java.nio.file.Files.readAllLines(latestJavaNav.toPath());

            java.util.Map<String, double[]> refNavData = new java.util.LinkedHashMap<>();
            headerDone = false;
            for (int i = 0; i < navRefLines.size(); i++) {
                String line = navRefLines.get(i);
                if (!headerDone) { if (line.contains("END OF HEADER")) headerDone = true; continue; }
                line = line.trim();
                if (!line.matches("C\\d+\\s+.*")) continue;
                if (i + 7 >= navRefLines.size()) continue;
                try {
                    String sat = line.substring(0, 3).trim();
                    java.util.List<Double> params = new java.util.ArrayList<>();
                    for (int r = 0; r < 8; r++) {
                        String rl = navRefLines.get(i + r).trim();
                        String[] fields = rl.split("\\s+");
                        int start = (r == 0) ? 7 : 0;
                        for (int f = start; f < fields.length; f++) {
                            try { params.add(Double.parseDouble(fields[f].replace("D", "E").replace("d", "E"))); }
                            catch (NumberFormatException e) { /* skip */ }
                        }
                    }
                    if (params.size() >= 23) {
                        double f0 = params.get(0), f1 = params.get(1);
                        double iode = params.get(3), crs = params.get(4), deln = params.get(5), M0 = params.get(6);
                        double cuc = params.get(7), e = params.get(8), cus = params.get(9), sqrtA = params.get(10);
                        double toes = params.get(11), cic = params.get(12), OMG0 = params.get(13), cis = params.get(14);
                        double i0 = params.get(15), crc = params.get(16), omg = params.get(17), OMGd = params.get(18);
                        refNavData.put(sat, new double[]{sqrtA, e, i0, OMG0, omg, f0, f1});
                    }
                } catch (Exception ex) { /* skip */ }
            }

            java.util.Map<String, double[]> javaNavData = new java.util.LinkedHashMap<>();
            for (String line : navJavaLines) {
                line = line.trim();
                if (line.startsWith("#")) continue;
                if (!line.matches("C\\d+\\s+.*")) continue;
                String[] parts = line.split("\\s+");
                String sat = parts[0];
                try {
                    double A = Double.parseDouble(parts[3]);
                    double sqrtA = Math.sqrt(A);
                    double e = Double.parseDouble(parts[4]);
                    double i0 = Double.parseDouble(parts[5]);
                    double OMG0 = Double.parseDouble(parts[6]);
                    double omg = Double.parseDouble(parts[7]);
                    double f0 = Double.parseDouble(parts[12]);
                    double f1 = Double.parseDouble(parts[13]);
                    javaNavData.put(sat, new double[]{sqrtA, e, i0, OMG0, omg, f0, f1});
                } catch (Exception ex) { /* skip */ }
            }

            log.info("RTKLIB eph sats: {}, Java eph sats: {}", refNavData.size(), javaNavData.size());
            int ephMatch = 0, ephMismatch = 0;
            String[] navLabels = {"sqrtA", "e", "i0", "OMG0", "omg", "f0", "f1"};
            for (String sat : refNavData.keySet()) {
                double[] r = refNavData.get(sat);
                double[] j = javaNavData.get(sat);
                if (j == null) continue;
                boolean ok = true;
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 7; k++) {
                    double rv = r[k], jv = j[k];
                    if (k == 0) {
                        rv = rv * rv;
                        jv = jv * jv;
                    }
                    double tol = (k == 0) ? 0.1 : (k < 5) ? 1e-10 : (k == 5) ? 1e-12 : 1e-20;
                    if (Math.abs(rv - jv) > tol) {
                        ok = false;
                        String label = (k == 0) ? "A" : navLabels[k];
                        sb.append(String.format(" %s:R=%.6e J=%.6e", label, rv, jv));
                    }
                }
                if (ok) { ephMatch++; }
                else { ephMismatch++; log.info("  {} MISMATCH:{}", sat, sb); }
            }
            log.info("NAV Result: {}/{} match, {} mismatch", ephMatch, refNavData.size(), ephMismatch);
        }

        assertTrue(obsMismatch == 0, "OBS mismatch count: " + obsMismatch);
    }

    /**
     * CRC-24Q 计算 (RTCM3 标准)
     */
    private int calcCrc24q(byte[] data, int offset, int len) {
        int crc = 0;
        int[] tbl = {
            0x000000,0x864CFB,0x8AD50D,0x0C99F6,0x93E6E1,0x15AA1A,0x1933EC,0x9F7F17,
            0xA18139,0x27CDC2,0x2B5434,0xAD18CF,0x3267D8,0xB42B23,0xB8B2D5,0x3EFE2E,
            0xC54E89,0x430272,0x4F9B84,0xC9D77F,0x56A868,0xD0E493,0xDC7D65,0x5A319E,
            0x64CFB0,0xE2834B,0xEE1ABD,0x685646,0xF72951,0x7165AA,0x7DFC5C,0xFBB0A7,
            0x0CD1E9,0x8A9D12,0x8604E4,0x00481F,0x9F3708,0x197BF3,0x15E205,0x93AEFE,
            0xAD50D0,0x2B1C2B,0x2785DD,0xA1C926,0x3EB631,0xB8FACA,0xB4633C,0x322FC7,
            0xC99F60,0x4FD39B,0x434A6D,0xC50696,0x5A7981,0xDC357A,0xD0AC8C,0x56E077,
            0x681E59,0xEE52A2,0xE2CB54,0x6487AF,0xFBF8B8,0x7DB443,0x712DB5,0xF7614E,
            0x19A3D2,0x9FEF29,0x9376DF,0x153A24,0x8A4533,0x0C09C8,0x00903E,0x86DCC5,
            0xB822EB,0x3E6E10,0x32F7E6,0xB4BB1D,0x2BC40A,0xAD88F1,0xA11107,0x275DFC,
            0xDCED5B,0x5AA1A0,0x563856,0xD074AD,0x4F0BBA,0xC94741,0xC5DEB7,0x43924C,
            0x7D6C62,0xFB2099,0xF7B96F,0x71F594,0xEE8A83,0x68C678,0x645F8E,0xE21375,
            0x15723B,0x933EC0,0x9FA736,0x19EBCD,0x8694DA,0x00D821,0x0C41D7,0x8A0D2C,
            0xB4F302,0x32BFF9,0x3E260F,0xB86AF4,0x2715E3,0xA15918,0xADC0EE,0x2B8C15,
            0xD03CB2,0x567049,0x5AE9BF,0xDCA544,0x43DA53,0xC596A8,0xC90F5E,0x4F43A5,
            0x71BD8B,0xF7F170,0xFB6886,0x7D247D,0xE25B6A,0x641791,0x688E67,0xEEC29C,
            0x3347A4,0xB50B5F,0xB992A9,0x3FDE52,0xA0A145,0x26EDBE,0x2A7448,0xAC38B3,
            0x92C69D,0x148A66,0x181390,0x9E5F6B,0x01207C,0x876C87,0x8BF571,0x0DB98A,
            0xF6092D,0x7045D6,0x7CDC20,0xFA90DB,0x65EFCC,0xE3A337,0xEF3AC1,0x69763A,
            0x578814,0xD1C4EF,0xDD5D19,0x5B11E2,0xC46EF5,0x42220E,0x4EBBF8,0xC8F703,
            0x3F964D,0xB9DAB6,0xB54340,0x330FBB,0xAC70AC,0x2A3C57,0x26A5A1,0xA0E95A,
            0x9E1774,0x185B8F,0x14C279,0x928E82,0x0DF195,0x8BBD6E,0x872498,0x016863,
            0xFAD8C4,0x7C943F,0x700DC9,0xF64132,0x693E25,0xEF72DE,0xE3EB28,0x65A7D3,
            0x5B59FD,0xDD1506,0xD18CF0,0x57C00B,0xC8BF1C,0x4EF3E7,0x426A11,0xC426EA,
            0x2AE476,0xACA88D,0xA0317B,0x267D80,0xB90297,0x3F4E6C,0x33D79A,0xB59B61,
            0x8B654F,0x0D29B4,0x01B042,0x87FCB9,0x1883AE,0x9ECF55,0x9256A3,0x141A58,
            0xEFAAFF,0x69E604,0x657FF2,0xE33309,0x7C4C1E,0xFA00E5,0xF69913,0x70D5E8,
            0x4E2BC6,0xC8673D,0xC4FECB,0x42B230,0xDDCD27,0x5B81DC,0x57182A,0xD154D1,
            0x26359F,0xA07964,0xACE092,0x2AAC69,0xB5D37E,0x339F85,0x3F0673,0xB94A88,
            0x87B4A6,0x01F85D,0x0D61AB,0x8B2D50,0x145247,0x921EBC,0x9E874A,0x18CBB1,
            0xE37B16,0x6537ED,0x69AE1B,0xEFE2E0,0x709DF7,0xF6D10C,0xFA48FA,0x7C0401,
            0x42FA2F,0xC4B6D4,0xC82F22,0x4E63D9,0xD11CCE,0x575035,0x5BC9C3,0xDD8538
        };
        for (int j = offset; j < offset + len; j++) {
            crc = ((crc << 8) & 0xFFFFFF) ^ tbl[(crc >> 16) ^ (data[j] & 0xFF)];
        }
        return crc;
    }

    @Test
    @DisplayName("RtcmCallbackDecoder 回调式解码测试")
    void testCallbackDecoder() throws IOException {
        java.util.List<ObservationEpoch> epochs = new java.util.ArrayList<>();
        java.util.List<Eph> ephList = new java.util.ArrayList<>();
        java.util.List<Geph> gephList = new java.util.ArrayList<>();
        java.util.List<Sta> staList = new java.util.ArrayList<>();
        java.util.List<Ssr> ssrList = new java.util.ArrayList<>();
        java.util.List<AuxData> auxList = new java.util.ArrayList<>();

        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta sta) { staList.add(sta); }
            @Override public void onSsr(Ssr ssr) { ssrList.add(ssr); }
            @Override public void onEph(Eph eph) { ephList.add(eph); }
            @Override public void onGeph(Geph geph) { gephList.add(geph); }
            @Override public void onObservationEpoch(ObservationEpoch epoch) { epochs.add(epoch); }
            @Override public void onAuxData(AuxData aux) { auxList.add(aux); }
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(roverData, 0, roverData.length);
        decoder.finish();

        log.info("Callback decoder results:");
        log.info("  Observation epochs: {}", epochs.size());
        log.info("  Ephemeris records: {}", ephList.size());
        log.info("  GLONASS ephemeris: {}", gephList.size());
        log.info("  Station records: {}", staList.size());
        log.info("  SSR records: {}", ssrList.size());
        log.info("  Aux data records: {}", auxList.size());

        assertTrue(epochs.size() > 0, "Should have at least one observation epoch");
        for (ObservationEpoch epoch : epochs) {
            assertTrue(epoch.time.time > 0 || epoch.time.sec != 0, "Epoch time should be valid");
            assertTrue(epoch.obsList.size() > 0, "Each epoch should have observations");
            for (Obsd o : epoch.obsList) {
                assertTrue(o.sat > 0, "Satellite number should be positive");
            }
        }

        if (!ephList.isEmpty()) {
            for (Eph e : ephList) {
                assertTrue(e.sat > 0, "Ephemeris satellite should be valid");
                assertTrue(e.A > 0, "Semi-major axis should be positive");
            }
        }

        if (!staList.isEmpty()) {
            for (Sta s : staList) {
                assertTrue(s.pos[0] != 0 || s.pos[1] != 0 || s.pos[2] != 0,
                        "Station position should be non-zero");
            }
        }

        log.info("Callback decoder test PASSED");
    }
}