package org.rtklib.java.vrs.rtcm;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rtcm3EphEncoder {
    private static final Logger log = LoggerFactory.getLogger(Rtcm3EphEncoder.class);

    private static final double SC2RAD = Constants.SC2RAD;
    private static final double P2_3  = 1.0 / 8.0;
    private static final double P2_4  = 1.0 / 16.0;
    private static final double P2_5  = 1.0 / 32.0;
    private static final double P2_6  = 1.0 / 64.0;
    private static final double P2_7  = 1.0 / 128.0;
    private static final double P2_10 = 1.0 / 1024.0;
    private static final double P2_11 = 1.0 / 2048.0;
    private static final double P2_19 = 1.0 / 524288.0;
    private static final double P2_20 = 1.0 / 1048576.0;
    private static final double P2_29 = 1.0 / 536870912.0;
    private static final double P2_30 = 1.0 / 1073741824.0;
    private static final double P2_31 = P2_30 * 0.5;
    private static final double P2_32 = P2_31 * 0.5;
    private static final double P2_33 = P2_31 * 0.25;
    private static final double P2_34 = P2_32 * 0.25;
    private static final double P2_40 = P2_31 * P2_10 * 0.5;
    private static final double P2_43 = P2_33 * P2_10;
    private static final double P2_46 = P2_43 * P2_3;
    private static final double P2_50 = P2_46 * P2_4;
    private static final double P2_55 = P2_50 * P2_5;
    private static final double P2_59 = P2_55 * P2_4;
    private static final double P2_66 = P2_59 * P2_7;

    private Rtcm3EphEncoder() {
    }

    public static boolean encodeType1019(Rtcm3Encoder enc, int sync) {
        int[] prnArr = new int[1];
        int sys = SatUtils.satsys(enc.ephsat, prnArr);
        if (sys != Constants.SYS_GPS) return false;

        Eph eph = searchEph(enc.nav, enc.ephsat);
        if (eph == null) return false;

        int prn = prnArr[0];
        int i = 24;

        int week = eph.week % 1024;
        int toe  = Rtcm3Encoder.round(eph.toes / 16.0);
        int toc  = Rtcm3Encoder.round(TimeSystem.time2gpst(eph.toc, null) / 16.0);
        long sqrtA = Rtcm3Encoder.roundU(Math.sqrt(eph.A) / P2_19);
        long e    = Rtcm3Encoder.roundU(eph.e / P2_33);
        int i0   = Rtcm3Encoder.round(eph.i0   / P2_31 / SC2RAD);
        int OMG0 = Rtcm3Encoder.round(eph.OMG0 / P2_31 / SC2RAD);
        int omg  = Rtcm3Encoder.round(eph.omg  / P2_31 / SC2RAD);
        int M0   = Rtcm3Encoder.round(eph.M0   / P2_31 / SC2RAD);
        int deln = Rtcm3Encoder.round(eph.deln / P2_43 / SC2RAD);
        int idot = Rtcm3Encoder.round(eph.idot / P2_43 / SC2RAD);
        int OMGd = Rtcm3Encoder.round(eph.OMGd / P2_43 / SC2RAD);
        int crs  = Rtcm3Encoder.round(eph.crs / P2_5);
        int crc  = Rtcm3Encoder.round(eph.crc / P2_5);
        int cus  = Rtcm3Encoder.round(eph.cus / P2_29);
        int cuc  = Rtcm3Encoder.round(eph.cuc / P2_29);
        int cis  = Rtcm3Encoder.round(eph.cis / P2_29);
        int cic  = Rtcm3Encoder.round(eph.cic / P2_29);
        int af0  = Rtcm3Encoder.round(eph.f0 / P2_31);
        int af1  = Rtcm3Encoder.round(eph.f1 / P2_43);
        int af2  = Rtcm3Encoder.round(eph.f2 / P2_55);
        int tgd  = Rtcm3Encoder.round(eph.tgd[0] / P2_31);

        BitUtils.setbitu(enc.buff, i, 12, 1019);       i += 12;
        BitUtils.setbitu(enc.buff, i,  6, prn);        i +=  6;
        BitUtils.setbitu(enc.buff, i, 10, week);       i += 10;
        BitUtils.setbitu(enc.buff, i,  4, eph.sva);    i +=  4;
        BitUtils.setbitu(enc.buff, i,  2, eph.code);   i +=  2;
        BitUtils.setbits(enc.buff, i, 14, idot);       i += 14;
        BitUtils.setbitu(enc.buff, i,  8, eph.iode);   i +=  8;
        BitUtils.setbitu(enc.buff, i, 16, toc);        i += 16;
        BitUtils.setbits(enc.buff, i,  8, af2);        i +=  8;
        BitUtils.setbits(enc.buff, i, 16, af1);        i += 16;
        BitUtils.setbits(enc.buff, i, 22, af0);        i += 22;
        BitUtils.setbitu(enc.buff, i, 10, eph.iodc);   i += 10;
        BitUtils.setbits(enc.buff, i, 16, crs);        i += 16;
        BitUtils.setbits(enc.buff, i, 16, deln);       i += 16;
        BitUtils.setbits(enc.buff, i, 32, M0);         i += 32;
        BitUtils.setbits(enc.buff, i, 16, cuc);        i += 16;
        BitUtils.setbitu(enc.buff, i, 32, e);          i += 32;
        BitUtils.setbits(enc.buff, i, 16, cus);        i += 16;
        BitUtils.setbitu(enc.buff, i, 32, sqrtA);      i += 32;
        BitUtils.setbitu(enc.buff, i, 16, toe);        i += 16;
        BitUtils.setbits(enc.buff, i, 16, cic);        i += 16;
        BitUtils.setbits(enc.buff, i, 32, OMG0);       i += 32;
        BitUtils.setbits(enc.buff, i, 16, cis);        i += 16;
        BitUtils.setbits(enc.buff, i, 32, i0);         i += 32;
        BitUtils.setbits(enc.buff, i, 16, crc);        i += 16;
        BitUtils.setbits(enc.buff, i, 32, omg);        i += 32;
        BitUtils.setbits(enc.buff, i, 24, OMGd);       i += 24;
        BitUtils.setbits(enc.buff, i,  8, tgd);        i +=  8;
        BitUtils.setbitu(enc.buff, i,  6, eph.svh);    i +=  6;
        BitUtils.setbitu(enc.buff, i,  1, eph.flag);   i +=  1;
        BitUtils.setbitu(enc.buff, i,  1, eph.fit > 4 ? 1 : 0); i += 1;

        enc.nbit = i;
        return true;
    }

    public static boolean encodeType1020(Rtcm3Encoder enc, int sync) {
        int[] prnArr = new int[1];
        int sys = SatUtils.satsys(enc.ephsat, prnArr);
        if (sys != Constants.SYS_GLO) return false;

        int prn = prnArr[0];
        Geph geph = searchGeph(enc.nav, prn);
        if (geph == null || geph.sat != enc.ephsat) return false;

        int i = 24;
        int fcn = geph.frq + 7;

        BitUtils.setbitu(enc.buff, i, 12, 1020);       i += 12;
        BitUtils.setbitu(enc.buff, i,  6, prn);        i +=  6;
        BitUtils.setbitu(enc.buff, i,  5, fcn);        i +=  5;

        int Bn = (geph.svh >> 0) & 1;
        int Cn = (geph.svh >> 1) & 1;
        int Cna = (geph.svh >> 2) & 1;
        BitUtils.setbitu(enc.buff, i, 1, Cn);   i += 1;
        BitUtils.setbitu(enc.buff, i, 1, Cna);  i += 1;

        double[] ep = new double[6];
        TimeSystem.time2epoch(
                TimeSystem.timeadd(TimeSystem.gpst2utc(geph.tof), 10800.0), ep);
        int tkH = (int) ep[3];
        int tkM = (int) ep[4];
        int tkS = Rtcm3Encoder.round(ep[5] / 30.0);

        BitUtils.setbitu(enc.buff, i, 2, 0);    i += 2;
        BitUtils.setbitu(enc.buff, i, 5, tkH);  i += 5;
        BitUtils.setbitu(enc.buff, i, 6, tkM);  i += 6;
        BitUtils.setbitu(enc.buff, i, 1, tkS);  i += 1;
        BitUtils.setbitu(enc.buff, i, 1, Bn);   i += 1;

        double[] toeEp = new double[6];
        TimeSystem.time2epoch(
                TimeSystem.timeadd(TimeSystem.gpst2utc(geph.toe), 10800.0), toeEp);
        int tb = Rtcm3Encoder.round((toeEp[3] * 3600.0 + toeEp[4] * 60.0 + toeEp[5]) / 900.0);
        BitUtils.setbitu(enc.buff, i, 1, 0);   i += 1;
        BitUtils.setbitu(enc.buff, i, 7, tb);  i += 7;

        for (int j = 0; j < 3; j++) {
            int posV = Rtcm3Encoder.round(geph.pos[j] / P2_11 / 1E3);
            int velV = Rtcm3Encoder.round(geph.vel[j] / P2_20 / 1E3);
            int accV = Rtcm3Encoder.round(geph.acc[j] / P2_30 / 1E3);
            setbitg(enc.buff, i, 24, velV); i += 24;
            setbitg(enc.buff, i, 27, posV); i += 27;
            setbitg(enc.buff, i,  5, accV); i +=  5;
        }

        int gamn  = Rtcm3Encoder.round(geph.gamn / P2_40);
        int taun  = Rtcm3Encoder.round(geph.taun / P2_30);
        int dtaun = Rtcm3Encoder.round(geph.dtaun / P2_30);

        BitUtils.setbitu(enc.buff, i, 1, 0);          i += 1;
        setbitg(enc.buff, i, 11, gamn);                i += 11;
        BitUtils.setbitu(enc.buff, i, 2, 0);          i += 2;
        BitUtils.setbitu(enc.buff, i, 1, 0);          i += 1;
        setbitg(enc.buff, i, 22, taun);                i += 22;
        setbitg(enc.buff, i,  5, dtaun);               i +=  5;
        BitUtils.setbitu(enc.buff, i, 5, geph.age);   i += 5;
        BitUtils.setbitu(enc.buff, i, 1, 0);          i += 1;
        BitUtils.setbitu(enc.buff, i, 4, geph.sva);   i += 4;

        BitUtils.setbitu(enc.buff, i, 11, 0); i += 11;
        BitUtils.setbitu(enc.buff, i, 2, 0);  i += 2;
        BitUtils.setbitu(enc.buff, i, 1, 0);  i += 1;
        BitUtils.setbitu(enc.buff, i, 11, 0); i += 11;
        BitUtils.setbitu(enc.buff, i, 32, 0); i += 32;
        BitUtils.setbitu(enc.buff, i, 5, 0);  i += 5;
        BitUtils.setbitu(enc.buff, i, 22, 0); i += 22;
        BitUtils.setbitu(enc.buff, i, 1, 0);  i += 1;

        enc.nbit = i;
        return true;
    }

    public static boolean encodeType1042(Rtcm3Encoder enc, int sync) {
        int[] prnArr = new int[1];
        int sys = SatUtils.satsys(enc.ephsat, prnArr);
        if (sys != Constants.SYS_CMP) return false;

        Eph eph = searchEph(enc.nav, enc.ephsat);
        if (eph == null) return false;

        int prn = prnArr[0];
        int i = 24;

        int week = eph.week % 8192;
        int toe  = Rtcm3Encoder.round(eph.toes / 8.0);
        GTime bdtTime = TimeSystem.gpst2bdt(eph.toc);
        int toc  = Rtcm3Encoder.round(TimeSystem.time2gpst(bdtTime, null) / 8.0);
        long sqrtA = Rtcm3Encoder.roundU(Math.sqrt(eph.A) / P2_19);
        long e    = Rtcm3Encoder.roundU(eph.e / P2_33);
        int i0   = Rtcm3Encoder.round(eph.i0   / P2_31 / SC2RAD);
        int OMG0 = Rtcm3Encoder.round(eph.OMG0 / P2_31 / SC2RAD);
        int omg  = Rtcm3Encoder.round(eph.omg  / P2_31 / SC2RAD);
        int M0   = Rtcm3Encoder.round(eph.M0   / P2_31 / SC2RAD);
        int deln = Rtcm3Encoder.round(eph.deln / P2_43 / SC2RAD);
        int idot = Rtcm3Encoder.round(eph.idot / P2_43 / SC2RAD);
        int OMGd = Rtcm3Encoder.round(eph.OMGd / P2_43 / SC2RAD);
        int crs  = Rtcm3Encoder.round(eph.crs / P2_6);
        int crc  = Rtcm3Encoder.round(eph.crc / P2_6);
        int cus  = Rtcm3Encoder.round(eph.cus / P2_31);
        int cuc  = Rtcm3Encoder.round(eph.cuc / P2_31);
        int cis  = Rtcm3Encoder.round(eph.cis / P2_31);
        int cic  = Rtcm3Encoder.round(eph.cic / P2_31);
        int af0  = Rtcm3Encoder.round(eph.f0 / P2_33);
        int af1  = Rtcm3Encoder.round(eph.f1 / P2_50);
        int af2  = Rtcm3Encoder.round(eph.f2 / P2_66);
        int tgd1 = Rtcm3Encoder.round(eph.tgd[0] / 1E-10);
        int tgd2 = Rtcm3Encoder.round(eph.tgd[1] / 1E-10);

        BitUtils.setbitu(enc.buff, i, 12, 1042);       i += 12;
        BitUtils.setbitu(enc.buff, i,  6, prn);        i +=  6;
        BitUtils.setbitu(enc.buff, i, 13, week);       i += 13;
        BitUtils.setbitu(enc.buff, i,  4, eph.sva);    i +=  4;
        BitUtils.setbits(enc.buff, i, 14, idot);       i += 14;
        BitUtils.setbitu(enc.buff, i,  5, eph.iode);   i +=  5;
        BitUtils.setbitu(enc.buff, i, 17, toc);        i += 17;
        BitUtils.setbits(enc.buff, i, 11, af2);        i += 11;
        BitUtils.setbits(enc.buff, i, 22, af1);        i += 22;
        BitUtils.setbits(enc.buff, i, 24, af0);        i += 24;
        BitUtils.setbitu(enc.buff, i,  5, eph.iodc);   i +=  5;
        BitUtils.setbits(enc.buff, i, 18, crs);        i += 18;
        BitUtils.setbits(enc.buff, i, 16, deln);       i += 16;
        BitUtils.setbits(enc.buff, i, 32, M0);         i += 32;
        BitUtils.setbits(enc.buff, i, 18, cuc);        i += 18;
        BitUtils.setbitu(enc.buff, i, 32, e);          i += 32;
        BitUtils.setbits(enc.buff, i, 18, cus);        i += 18;
        BitUtils.setbitu(enc.buff, i, 32, sqrtA);      i += 32;
        BitUtils.setbitu(enc.buff, i, 17, toe);        i += 17;
        BitUtils.setbits(enc.buff, i, 18, cic);        i += 18;
        BitUtils.setbits(enc.buff, i, 32, OMG0);       i += 32;
        BitUtils.setbits(enc.buff, i, 18, cis);        i += 18;
        BitUtils.setbits(enc.buff, i, 32, i0);         i += 32;
        BitUtils.setbits(enc.buff, i, 18, crc);        i += 18;
        BitUtils.setbits(enc.buff, i, 32, omg);        i += 32;
        BitUtils.setbits(enc.buff, i, 24, OMGd);       i += 24;
        BitUtils.setbits(enc.buff, i, 10, tgd1);       i += 10;
        BitUtils.setbits(enc.buff, i, 10, tgd2);       i += 10;
        BitUtils.setbitu(enc.buff, i,  1, eph.svh);    i +=  1;

        enc.nbit = i;
        return true;
    }

    public static boolean encodeType1045(Rtcm3Encoder enc, int sync) {
        int[] prnArr = new int[1];
        int sys = SatUtils.satsys(enc.ephsat, prnArr);
        if (sys != Constants.SYS_GAL) return false;

        Eph eph = searchEph(enc.nav, enc.ephsat);
        if (eph == null) return false;

        int prn = prnArr[0];
        int i = 24;

        int week = (eph.week - 1024) % 4096;
        int toe  = Rtcm3Encoder.round(eph.toes / 60.0);
        int toc  = Rtcm3Encoder.round(TimeSystem.time2gpst(eph.toc, null) / 60.0);
        long sqrtA = Rtcm3Encoder.roundU(Math.sqrt(eph.A) / P2_19);
        long e    = Rtcm3Encoder.roundU(eph.e / P2_33);
        int i0   = Rtcm3Encoder.round(eph.i0   / P2_31 / SC2RAD);
        int OMG0 = Rtcm3Encoder.round(eph.OMG0 / P2_31 / SC2RAD);
        int omg  = Rtcm3Encoder.round(eph.omg  / P2_31 / SC2RAD);
        int M0   = Rtcm3Encoder.round(eph.M0   / P2_31 / SC2RAD);
        int deln = Rtcm3Encoder.round(eph.deln / P2_43 / SC2RAD);
        int idot = Rtcm3Encoder.round(eph.idot / P2_43 / SC2RAD);
        int OMGd = Rtcm3Encoder.round(eph.OMGd / P2_43 / SC2RAD);
        int crs  = Rtcm3Encoder.round(eph.crs / P2_5);
        int crc  = Rtcm3Encoder.round(eph.crc / P2_5);
        int cus  = Rtcm3Encoder.round(eph.cus / P2_29);
        int cuc  = Rtcm3Encoder.round(eph.cuc / P2_29);
        int cis  = Rtcm3Encoder.round(eph.cis / P2_29);
        int cic  = Rtcm3Encoder.round(eph.cic / P2_29);
        int af0  = Rtcm3Encoder.round(eph.f0 / P2_34);
        int af1  = Rtcm3Encoder.round(eph.f1 / P2_46);
        int af2  = Rtcm3Encoder.round(eph.f2 / P2_59);
        int bgd1 = Rtcm3Encoder.round(eph.tgd[0] / P2_32);
        int oshs  = (eph.svh >> 4) & 3;
        int osdvs = (eph.svh >> 3) & 1;

        BitUtils.setbitu(enc.buff, i, 12, 1045);       i += 12;
        BitUtils.setbitu(enc.buff, i,  6, prn);        i +=  6;
        BitUtils.setbitu(enc.buff, i, 12, week);       i += 12;
        BitUtils.setbitu(enc.buff, i, 10, eph.iode);   i += 10;
        BitUtils.setbitu(enc.buff, i,  8, eph.sva);    i +=  8;
        BitUtils.setbits(enc.buff, i, 14, idot);       i += 14;
        BitUtils.setbitu(enc.buff, i, 14, toc);        i += 14;
        BitUtils.setbits(enc.buff, i,  6, af2);        i +=  6;
        BitUtils.setbits(enc.buff, i, 21, af1);        i += 21;
        BitUtils.setbits(enc.buff, i, 31, af0);        i += 31;
        BitUtils.setbits(enc.buff, i, 16, crs);        i += 16;
        BitUtils.setbits(enc.buff, i, 16, deln);       i += 16;
        BitUtils.setbits(enc.buff, i, 32, M0);         i += 32;
        BitUtils.setbits(enc.buff, i, 16, cuc);        i += 16;
        BitUtils.setbitu(enc.buff, i, 32, e);          i += 32;
        BitUtils.setbits(enc.buff, i, 16, cus);        i += 16;
        BitUtils.setbitu(enc.buff, i, 32, sqrtA);      i += 32;
        BitUtils.setbitu(enc.buff, i, 14, toe);        i += 14;
        BitUtils.setbits(enc.buff, i, 16, cic);        i += 16;
        BitUtils.setbits(enc.buff, i, 32, OMG0);       i += 32;
        BitUtils.setbits(enc.buff, i, 16, cis);        i += 16;
        BitUtils.setbits(enc.buff, i, 32, i0);         i += 32;
        BitUtils.setbits(enc.buff, i, 16, crc);        i += 16;
        BitUtils.setbits(enc.buff, i, 32, omg);        i += 32;
        BitUtils.setbits(enc.buff, i, 24, OMGd);       i += 24;
        BitUtils.setbits(enc.buff, i, 10, bgd1);       i += 10;
        BitUtils.setbitu(enc.buff, i,  2, oshs);       i +=  2;
        BitUtils.setbitu(enc.buff, i,  1, osdvs);      i +=  1;
        BitUtils.setbitu(enc.buff, i,  7, 0);          i +=  7;

        enc.nbit = i;
        return true;
    }

    private static void setbitg(byte[] buff, int pos, int len, int value) {
        BitUtils.setbitu(buff, pos, 1, value < 0 ? 1 : 0);
        BitUtils.setbitu(buff, pos + 1, len - 1, value < 0 ? -value : value);
    }

    private static Eph searchEph(Nav nav, int sat) {
        if (nav.eph == null) return null;
        Eph best = null;
        for (Eph eph : nav.eph) {
            if (eph != null && eph.sat == sat) {
                best = eph;
                break;
            }
        }
        return best;
    }

    private static Geph searchGeph(Nav nav, int prn) {
        if (nav.geph == null || prn < 1) return null;
        int sat = SatUtils.satno(Constants.SYS_GLO, prn);
        for (Geph geph : nav.geph) {
            if (geph != null && geph.sat == sat) {
                return geph;
            }
        }
        return null;
    }
}