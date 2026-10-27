package org.rtklib.java.vrs.rtcm;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.common.CrcUtils;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Sta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rtcm3Encoder {
    private static final Logger log = LoggerFactory.getLogger(Rtcm3Encoder.class);

    public static final int RTCM3_PREAMBLE = 0xD3;

    public byte[] buff;
    public int nbit;
    public int stationId;
    public int seqNo;
    public Sta sta;
    public Nav nav;
    public Obs obs;
    public int ephsat;
    public double[][] cp;
    public double[][] lltime;

    public Rtcm3Encoder() {
        this.buff = new byte[2048];
        this.stationId = 1;
        this.seqNo = 0;
        this.sta = new Sta();
        this.nav = new Nav();
        this.obs = new Obs();
        this.ephsat = 0;
        this.cp = new double[1][1];
        this.lltime = new double[1][1];
    }

    public byte[] encode(int type, int subtype, int sync) {
        for (int i = 0; i < buff.length; i++) buff[i] = 0;
        nbit = 24;

        boolean ok = false;
        switch (type) {
            case 1005: ok = Rtcm3StationEncoder.encodeType1005(this, sync); break;
            case 1006: ok = Rtcm3StationEncoder.encodeType1006(this, sync); break;
            case 1019: ok = Rtcm3EphEncoder.encodeType1019(this, sync); break;
            case 1020: ok = Rtcm3EphEncoder.encodeType1020(this, sync); break;
            case 1042: ok = Rtcm3EphEncoder.encodeType1042(this, sync); break;
            case 1045: ok = Rtcm3EphEncoder.encodeType1045(this, sync); break;
            default:
                if (type >= 1071 && type <= 1137) {
                    ok = Rtcm3MsmEncoder.encodeMsm(this, type, sync);
                }
                break;
        }
        if (!ok) return null;
        return frameBuffer();
    }

    private byte[] frameBuffer() {
        int nbytes = (nbit + 7) / 8;
        int len = nbytes - 3;

        buff[0] = (byte) RTCM3_PREAMBLE;
        buff[1] = (byte) ((len >> 8) & 0x03);
        buff[2] = (byte) (len & 0xFF);

        int crc = CrcUtils.rtkCrc24q(buff, 0, nbytes);
        buff[nbytes]     = (byte) ((crc >> 16) & 0xFF);
        buff[nbytes + 1] = (byte) ((crc >> 8) & 0xFF);
        buff[nbytes + 2] = (byte) (crc & 0xFF);

        int total = nbytes + 3;
        byte[] result = new byte[total];
        System.arraycopy(buff, 0, result, 0, total);
        return result;
    }

    public static void set38bits(byte[] buff, int pos, double value) {
        int wordH = (int) Math.floor(value / 64.0);
        long wordL = (long) (value - wordH * 64.0);
        BitUtils.setbits(buff, pos, 32, wordH);
        BitUtils.setbitu(buff, pos + 32, 6, wordL);
    }

    public static int round(double x) {
        return (int) Math.floor(x + 0.5);
    }

    public static long roundU(double x) {
        return (long) Math.floor(x + 0.5);
    }
}