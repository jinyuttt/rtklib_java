package org.rtklib.java.vrs.rtcm;

import org.rtklib.java.common.BitUtils;
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
}