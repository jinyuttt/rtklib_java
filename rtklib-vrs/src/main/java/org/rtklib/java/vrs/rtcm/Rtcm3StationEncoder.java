package org.rtklib.java.vrs.rtcm;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.data.Sta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rtcm3StationEncoder {
    private static final Logger log = LoggerFactory.getLogger(Rtcm3StationEncoder.class);

    private Rtcm3StationEncoder() {
    }

    public static boolean encodeType1005(Rtcm3Encoder enc, int sync) {
        double[] p = enc.sta.pos;
        int i = 24;

        BitUtils.setbitu(enc.buff, i, 12, 1005);           i += 12;
        BitUtils.setbitu(enc.buff, i, 12, enc.stationId);  i += 12;
        BitUtils.setbitu(enc.buff, i,  6, 0);              i +=  6;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        Rtcm3Encoder.set38bits(enc.buff, i, p[0] / 0.0001); i += 38;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        Rtcm3Encoder.set38bits(enc.buff, i, p[1] / 0.0001); i += 38;
        BitUtils.setbitu(enc.buff, i,  2, 0);              i +=  2;
        Rtcm3Encoder.set38bits(enc.buff, i, p[2] / 0.0001); i += 38;

        enc.nbit = i;
        return true;
    }

    public static boolean encodeType1006(Rtcm3Encoder enc, int sync) {
        double[] p = enc.sta.pos;
        int i = 24;
        int hgt = 0;

        if (enc.sta.hgt >= 0.0 && enc.sta.hgt <= 6.5535) {
            hgt = Rtcm3Encoder.round(enc.sta.hgt / 0.0001);
        }

        BitUtils.setbitu(enc.buff, i, 12, 1006);           i += 12;
        BitUtils.setbitu(enc.buff, i, 12, enc.stationId);  i += 12;
        BitUtils.setbitu(enc.buff, i,  6, 0);              i +=  6;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        Rtcm3Encoder.set38bits(enc.buff, i, p[0] / 0.0001); i += 38;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        Rtcm3Encoder.set38bits(enc.buff, i, p[1] / 0.0001); i += 38;
        BitUtils.setbitu(enc.buff, i,  2, 0);              i +=  2;
        Rtcm3Encoder.set38bits(enc.buff, i, p[2] / 0.0001); i += 38;
        BitUtils.setbitu(enc.buff, i, 16, hgt);            i += 16;

        enc.nbit = i;
        return true;
    }

    /**
     * 1032 Physical Reference Station Position。
     *
     * <p>位域布局与 1005 完全一致，仅 DF141 取 1 表示「本坐标为物理参考站坐标」。
     * 坐标优先取 {@code sta.xyz}（物理站 ECEF），未设置时回退 {@code sta.pos}。</p>
     */
    public static boolean encodeType1032(Rtcm3Encoder enc, int sync) {
        double[] p = physicalPosition(enc.sta);
        int i = 24;

        BitUtils.setbitu(enc.buff, i, 12, 1032);           i += 12;
        BitUtils.setbitu(enc.buff, i, 12, enc.stationId);  i += 12;
        BitUtils.setbitu(enc.buff, i,  6, 0);              i +=  6;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, 1);              i +=  1;
        Rtcm3Encoder.set38bits(enc.buff, i, p[0] / 0.0001); i += 38;
        BitUtils.setbitu(enc.buff, i,  1, 0);              i +=  1;
        Rtcm3Encoder.set38bits(enc.buff, i, p[1] / 0.0001); i += 38;
        BitUtils.setbitu(enc.buff, i,  2, 0);              i +=  2;
        Rtcm3Encoder.set38bits(enc.buff, i, p[2] / 0.0001); i += 38;

        enc.nbit = i;
        return true;
    }

    /**
     * 1033 Receiver and Antenna Descriptors。
     *
     * <p>字段顺序：天线描述 → 天线设置 ID → 天线序列号 → 接收机类型
     * → 接收机固件版本 → 接收机序列号，全部为变长字符串（长度 8 bit + 字符）。</p>
     */
    public static boolean encodeType1033(Rtcm3Encoder enc, int sync) {
        Sta sta = enc.sta;
        int i = 24;

        BitUtils.setbitu(enc.buff, i, 12, 1033);          i += 12;
        BitUtils.setbitu(enc.buff, i, 12, enc.stationId); i += 12;
        i = writeDescriptor(enc.buff, i, sta.antdes);
        BitUtils.setbitu(enc.buff, i,  8, sta.antSetupId & 0xFF); i += 8;
        i = writeDescriptor(enc.buff, i, sta.antsno);
        i = writeDescriptor(enc.buff, i, sta.rectype);
        i = writeDescriptor(enc.buff, i, sta.recver);
        i = writeDescriptor(enc.buff, i, sta.recsno);

        enc.nbit = i;
        return true;
    }

    /** 写入一个 RTCM 变长描述符（长度 8 bit + ASCII 字符），返回新的位偏移。 */
    private static int writeDescriptor(byte[] buff, int pos, String text) {
        String s = text == null ? "" : text;
        int n = Math.min(s.length(), 255);
        BitUtils.setbitu(buff, pos, 8, n);
        pos += 8;
        for (int k = 0; k < n; k++) {
            BitUtils.setbitu(buff, pos, 8, s.charAt(k) & 0xFF);
            pos += 8;
        }
        return pos;
    }

    private static double[] physicalPosition(Sta sta) {
        if (sta.xyz != null
                && (sta.xyz[0] != 0.0 || sta.xyz[1] != 0.0 || sta.xyz[2] != 0.0)) {
            return sta.xyz;
        }
        return sta.pos;
    }
}