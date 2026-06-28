package org.rtklib.java.ionosphere;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

public final class SbasCorrection {
    private SbasCorrection() {
    }

    private static final double RE_SBAS = 6378.1363;
    private static final double HION_SBAS = 350.0;

    private static final short[] X1 = {
        -180, -175, -170, -165, -160, -155, -150, -145, -140, -135, -130, -125,
        -120, -115, -110, -105, -100, -95, -90, -85, -80, -75, -70, -65, -60,
        -55, -50, -45
    };
    private static final short[] X2 = {
        -180, -170, -160, -150, -140, -130, -120, -110, -100, -90, -80, -70,
        -60, -50, -40, -30, -20, -10, 0, 10, 20, 30, 40, 50, 55
    };
    private static final short[] X3 = {
        -75, -65, -55, -50, -45, -40, -35, -30, -25, -20, -15, -10, -5, 0,
        5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 65, 75
    };
    private static final short[] X4 = {
        -85, -75, -65, -55, -50, -45, -40, -35, -30, -25, -20, -15, -10, -5,
        0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 65, 75
    };
    private static final short[] X5 = {
        -180, -175, -170, -165, -160, -155, -150, -145, -140, -135, -130,
        -125, -120, -115, -110, -105, -100, -95, -90, -85, -80, -75, -70,
        -65, -60, -55, -50, -45, -40, -35, -30, -25, -20, -15, -10, -5, 0,
        5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85,
        90, 95, 100, 105, 110, 115, 120, 125, 130, 135, 140, 145, 150, 155,
        160, 165, 170, 175
    };
    private static final short[] X6 = {
        -180, -170, -160, -150, -140, -130, -120, -110, -100, -90, -80, -70,
        -60, -50, -40, -30, -20, -10, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90,
        100, 110, 120, 130, 140, 150, 160, 170
    };
    private static final short[] X7 = {
        -180, -150, -120, -90, -60, -30, 0, 30, 60, 90, 120, 150
    };
    private static final short[] X8 = {
        -170, -140, -110, -80, -50, -20, 10, 40, 70, 100, 130, 160
    };

    private static final SbsIgpBand[][] IGP_BAND1 = new SbsIgpBand[9][8];
    private static final SbsIgpBand[][] IGP_BAND2 = new SbsIgpBand[2][5];
    private static boolean igpInitialized = false;

    private static final double[] VAR_FCORR = {
        0.052, 0.0924, 0.1444, 0.283, 0.4678, 0.8315, 1.2992, 1.8709,
        2.5465, 3.326, 5.1968, 20.7870, 230.9661, 2078.695
    };

    private static final double[] VAR_ICORR = {
        0.0084, 0.0333, 0.0749, 0.1331, 0.2079, 0.2994, 0.4075, 0.5322,
        0.6735, 0.8315, 1.1974, 1.8709, 3.326, 20.787, 187.0826
    };

    private static final double[] DEG_FCORR = {
        0.00000, 0.00005, 0.00009, 0.00012, 0.00015, 0.00020, 0.00030,
        0.00045, 0.00060, 0.00090, 0.00150, 0.00210, 0.00270, 0.00330,
        0.00460, 0.00580
    };

    private static final double[][] MET_PRM = {
        {1013.25, 299.65, 26.31, 6.30E-3, 2.77, 0.00, 0.00, 0.00, 0.00E-3, 0.00},
        {1017.25, 294.15, 21.79, 6.05E-3, 3.15, -3.75, 7.00, 8.85, 0.25E-3, 0.33},
        {1015.75, 283.15, 11.66, 5.58E-3, 2.57, -2.25, 11.00, 7.24, 0.32E-3, 0.46},
        {1011.75, 272.15, 6.78, 5.39E-3, 1.81, -1.75, 15.00, 5.36, 0.81E-3, 0.74},
        {1013.00, 263.65, 4.11, 4.53E-3, 1.55, -0.50, 14.50, 3.39, 0.62E-3, 0.30}
    };

    private static synchronized void initIgpBands() {
        if (igpInitialized) return;

        IGP_BAND1[0] = new SbsIgpBand[]{
            new SbsIgpBand((short) -180, X1, (short) 1, (short) 28),
            new SbsIgpBand((short) -175, X2, (short) 29, (short) 51),
            new SbsIgpBand((short) -170, X3, (short) 52, (short) 78),
            new SbsIgpBand((short) -165, X2, (short) 79, (short) 101),
            new SbsIgpBand((short) -160, X3, (short) 102, (short) 128),
            new SbsIgpBand((short) -155, X2, (short) 129, (short) 151),
            new SbsIgpBand((short) -150, X3, (short) 152, (short) 178),
            new SbsIgpBand((short) -145, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[1] = new SbsIgpBand[]{
            new SbsIgpBand((short) -140, X4, (short) 1, (short) 28),
            new SbsIgpBand((short) -135, X2, (short) 29, (short) 51),
            new SbsIgpBand((short) -130, X3, (short) 52, (short) 78),
            new SbsIgpBand((short) -125, X2, (short) 79, (short) 101),
            new SbsIgpBand((short) -120, X3, (short) 102, (short) 128),
            new SbsIgpBand((short) -115, X2, (short) 129, (short) 151),
            new SbsIgpBand((short) -110, X3, (short) 152, (short) 178),
            new SbsIgpBand((short) -105, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[2] = new SbsIgpBand[]{
            new SbsIgpBand((short) -100, X3, (short) 1, (short) 27),
            new SbsIgpBand((short) -95, X2, (short) 28, (short) 50),
            new SbsIgpBand((short) -90, X1, (short) 51, (short) 78),
            new SbsIgpBand((short) -85, X2, (short) 79, (short) 101),
            new SbsIgpBand((short) -80, X3, (short) 102, (short) 128),
            new SbsIgpBand((short) -75, X2, (short) 129, (short) 151),
            new SbsIgpBand((short) -70, X3, (short) 152, (short) 178),
            new SbsIgpBand((short) -65, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[3] = new SbsIgpBand[]{
            new SbsIgpBand((short) -60, X3, (short) 1, (short) 27),
            new SbsIgpBand((short) -55, X2, (short) 28, (short) 50),
            new SbsIgpBand((short) -50, X4, (short) 51, (short) 78),
            new SbsIgpBand((short) -45, X2, (short) 79, (short) 101),
            new SbsIgpBand((short) -40, X3, (short) 102, (short) 128),
            new SbsIgpBand((short) -35, X2, (short) 129, (short) 151),
            new SbsIgpBand((short) -30, X3, (short) 152, (short) 178),
            new SbsIgpBand((short) -25, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[4] = new SbsIgpBand[]{
            new SbsIgpBand((short) -20, X3, (short) 1, (short) 27),
            new SbsIgpBand((short) -15, X2, (short) 28, (short) 50),
            new SbsIgpBand((short) -10, X3, (short) 51, (short) 77),
            new SbsIgpBand((short) -5, X2, (short) 78, (short) 100),
            new SbsIgpBand((short) 0, X1, (short) 101, (short) 128),
            new SbsIgpBand((short) 5, X2, (short) 129, (short) 151),
            new SbsIgpBand((short) 10, X3, (short) 152, (short) 178),
            new SbsIgpBand((short) 15, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[5] = new SbsIgpBand[]{
            new SbsIgpBand((short) 20, X3, (short) 1, (short) 27),
            new SbsIgpBand((short) 25, X2, (short) 28, (short) 50),
            new SbsIgpBand((short) 30, X3, (short) 51, (short) 77),
            new SbsIgpBand((short) 35, X2, (short) 78, (short) 100),
            new SbsIgpBand((short) 40, X4, (short) 101, (short) 128),
            new SbsIgpBand((short) 45, X2, (short) 129, (short) 151),
            new SbsIgpBand((short) 50, X3, (short) 152, (short) 178),
            new SbsIgpBand((short) 55, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[6] = new SbsIgpBand[]{
            new SbsIgpBand((short) 60, X3, (short) 1, (short) 27),
            new SbsIgpBand((short) 65, X2, (short) 28, (short) 50),
            new SbsIgpBand((short) 70, X3, (short) 51, (short) 77),
            new SbsIgpBand((short) 75, X2, (short) 78, (short) 100),
            new SbsIgpBand((short) 80, X3, (short) 101, (short) 127),
            new SbsIgpBand((short) 85, X2, (short) 128, (short) 150),
            new SbsIgpBand((short) 90, X1, (short) 151, (short) 178),
            new SbsIgpBand((short) 95, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[7] = new SbsIgpBand[]{
            new SbsIgpBand((short) 100, X3, (short) 1, (short) 27),
            new SbsIgpBand((short) 105, X2, (short) 28, (short) 50),
            new SbsIgpBand((short) 110, X3, (short) 51, (short) 77),
            new SbsIgpBand((short) 115, X2, (short) 78, (short) 100),
            new SbsIgpBand((short) 120, X3, (short) 101, (short) 127),
            new SbsIgpBand((short) 125, X2, (short) 128, (short) 150),
            new SbsIgpBand((short) 130, X4, (short) 151, (short) 178),
            new SbsIgpBand((short) 135, X2, (short) 179, (short) 201)
        };
        IGP_BAND1[8] = new SbsIgpBand[]{
            new SbsIgpBand((short) 140, X3, (short) 1, (short) 27),
            new SbsIgpBand((short) 145, X2, (short) 28, (short) 50),
            new SbsIgpBand((short) 150, X3, (short) 51, (short) 77),
            new SbsIgpBand((short) 155, X2, (short) 78, (short) 100),
            new SbsIgpBand((short) 160, X3, (short) 101, (short) 127),
            new SbsIgpBand((short) 165, X2, (short) 128, (short) 150),
            new SbsIgpBand((short) 170, X3, (short) 151, (short) 177),
            new SbsIgpBand((short) 175, X2, (short) 178, (short) 200)
        };

        IGP_BAND2[0] = new SbsIgpBand[]{
            new SbsIgpBand((short) 60, X5, (short) 1, (short) 72),
            new SbsIgpBand((short) 65, X6, (short) 73, (short) 108),
            new SbsIgpBand((short) 70, X6, (short) 109, (short) 144),
            new SbsIgpBand((short) 75, X6, (short) 145, (short) 180),
            new SbsIgpBand((short) 85, X7, (short) 181, (short) 192)
        };
        IGP_BAND2[1] = new SbsIgpBand[]{
            new SbsIgpBand((short) -60, X5, (short) 1, (short) 72),
            new SbsIgpBand((short) -65, X6, (short) 73, (short) 108),
            new SbsIgpBand((short) -70, X6, (short) 109, (short) 144),
            new SbsIgpBand((short) -75, X6, (short) 145, (short) 180),
            new SbsIgpBand((short) -85, X8, (short) 181, (short) 192)
        };

        igpInitialized = true;
    }

    static double varfcorr(int udre) {
        if (udre < 1 || udre > 14) return 0.0;
        return VAR_FCORR[udre - 1];
    }

    static double varicorr(int give) {
        if (give < 1 || give > 15) return 0.0;
        return VAR_ICORR[give - 1];
    }

    static double degfcorr(int ai) {
        if (ai < 1 || ai > 15) return 0.0058;
        return DEG_FCORR[ai];
    }

    public static int sbsupdatecorr(SbsMsg msg, Nav nav) {
        int type = (int) BitUtils.getbitu(msg.msg, 8, 6);
        int stat = -1;

        if (msg.week == 0) return -1;

        switch (type) {
            case 0:
                stat = decodeSbstype2(msg, nav.sbssat);
                break;
            case 1:
                stat = decodeSbstype1(msg, nav.sbssat);
                break;
            case 2:
            case 3:
            case 4:
            case 5:
                stat = decodeSbstype2(msg, nav.sbssat);
                break;
            case 6:
                stat = decodeSbstype6(msg, nav.sbssat);
                break;
            case 7:
                stat = decodeSbstype7(msg, nav.sbssat);
                break;
            case 9:
                stat = decodeSbstype9(msg, nav);
                break;
            case 18:
                stat = decodeSbstype18(msg, nav.sbsion);
                break;
            case 24:
                stat = decodeSbstype24(msg, nav.sbssat);
                break;
            case 25:
                stat = decodeSbstype25(msg, nav.sbssat);
                break;
            case 26:
                stat = decodeSbstype26(msg, nav.sbsion);
                break;
            case 63:
                break;
            default:
                break;
        }
        return stat < 0 ? -1 : type;
    }

    static int decodeSbstype1(SbsMsg msg, SbsSat sbssat) {
        int n = 0;
        for (int i = 1; i <= 210 && n < Constants.MAXSAT; i++) {
            if (BitUtils.getbitu(msg.msg, 13 + i, 1) == 0) continue;
            int sat;
            if (i <= 37) {
                sat = SatUtils.satno(Constants.SYS_GPS, i);
            } else if (i <= 61) {
                sat = SatUtils.satno(Constants.SYS_GLO, i - 37);
            } else if (i <= 119) {
                sat = 0;
            } else if (i <= 138) {
                sat = SatUtils.satno(Constants.SYS_SBS, i);
            } else if (i <= 182) {
                sat = 0;
            } else if (i <= 192) {
                sat = SatUtils.satno(Constants.SYS_SBS, i + 10);
            } else if (i <= 202) {
                sat = SatUtils.satno(Constants.SYS_QZS, i);
            } else {
                sat = 0;
            }
            sbssat.sat[n++].sat = sat;
        }
        sbssat.iodp = (int) BitUtils.getbitu(msg.msg, 224, 2);
        sbssat.nsat = n;
        return 1;
    }

    static int decodeSbstype2(SbsMsg msg, SbsSat sbssat) {
        if (sbssat.iodp != (int) BitUtils.getbitu(msg.msg, 16, 2)) return 0;

        int type = (int) BitUtils.getbitu(msg.msg, 8, 6);
        int iodf = (int) BitUtils.getbitu(msg.msg, 14, 2);

        for (int i = 0; i < 13; i++) {
            int j = 13 * ((type == 0 ? 2 : type) - 2) + i;
            if (j >= sbssat.nsat) break;
            int udre = (int) BitUtils.getbitu(msg.msg, 174 + 4 * i, 4);
            GTime t0 = sbssat.sat[j].fcorr.t0;
            double prc = sbssat.sat[j].fcorr.prc;
            sbssat.sat[j].fcorr.t0 = TimeSystem.gpst2time(msg.week, msg.tow);
            sbssat.sat[j].fcorr.prc = BitUtils.getbits(msg.msg, 18 + i * 12, 12) * 0.125;
            sbssat.sat[j].fcorr.udre = (short) (udre + 1);
            double dt = TimeSystem.timediff(sbssat.sat[j].fcorr.t0, t0);
            if (t0.time == 0 || dt <= 0.0 || 18.0 < dt || sbssat.sat[j].fcorr.ai == 0) {
                sbssat.sat[j].fcorr.rrc = 0.0;
                sbssat.sat[j].fcorr.dt = 0.0;
            } else {
                sbssat.sat[j].fcorr.rrc = (sbssat.sat[j].fcorr.prc - prc) / dt;
                sbssat.sat[j].fcorr.dt = dt;
            }
            sbssat.sat[j].fcorr.iodf = iodf;
        }
        return 1;
    }

    static int decodeSbstype6(SbsMsg msg, SbsSat sbssat) {
        int[] iodf = new int[4];
        for (int i = 0; i < 4; i++) {
            iodf[i] = (int) BitUtils.getbitu(msg.msg, 14 + i * 2, 2);
        }
        for (int i = 0; i < sbssat.nsat && i < Constants.MAXSAT; i++) {
            if (sbssat.sat[i].fcorr.iodf != iodf[i / 13]) continue;
            int udre = (int) BitUtils.getbitu(msg.msg, 22 + i * 4, 4);
            sbssat.sat[i].fcorr.udre = (short) (udre + 1);
        }
        return 1;
    }

    static int decodeSbstype7(SbsMsg msg, SbsSat sbssat) {
        if (sbssat.iodp != (int) BitUtils.getbitu(msg.msg, 18, 2)) return 0;

        sbssat.tlat = (int) BitUtils.getbitu(msg.msg, 14, 4);

        for (int i = 0; i < sbssat.nsat && i < Constants.MAXSAT; i++) {
            sbssat.sat[i].fcorr.ai = (short) BitUtils.getbitu(msg.msg, 22 + i * 4, 4);
        }
        return 1;
    }

    static int decodeSbstype9(SbsMsg msg, Nav nav) {
        int sat = SatUtils.satno(Constants.SYS_SBS, msg.prn);
        if (sat == 0) return 0;

        int t = (int) BitUtils.getbitu(msg.msg, 22, 13) * 16 - (int) msg.tow % 86400;
        if (t <= -43200) t += 86400;
        else if (t > 43200) t -= 86400;

        Seph seph = new Seph();
        seph.sat = sat;
        seph.t0 = TimeSystem.gpst2time(msg.week, msg.tow + t);
        seph.tof = TimeSystem.gpst2time(msg.week, msg.tow);
        seph.sva = (int) BitUtils.getbitu(msg.msg, 35, 4);
        seph.svh = seph.sva == 15 ? 1 : 0;

        seph.pos[0] = BitUtils.getbits(msg.msg, 39, 30) * 0.08;
        seph.pos[1] = BitUtils.getbits(msg.msg, 69, 30) * 0.08;
        seph.pos[2] = BitUtils.getbits(msg.msg, 99, 25) * 0.4;
        seph.vel[0] = BitUtils.getbits(msg.msg, 124, 17) * 0.000625;
        seph.vel[1] = BitUtils.getbits(msg.msg, 141, 17) * 0.000625;
        seph.vel[2] = BitUtils.getbits(msg.msg, 158, 18) * 0.004;
        seph.acc[0] = BitUtils.getbits(msg.msg, 176, 10) * 0.0000125;
        seph.acc[1] = BitUtils.getbits(msg.msg, 186, 10) * 0.0000125;
        seph.acc[2] = BitUtils.getbits(msg.msg, 196, 10) * 0.0000625;

        seph.af0 = BitUtils.getbits(msg.msg, 206, 12) * Constants.P2_31;
        seph.af1 = BitUtils.getbits(msg.msg, 218, 8) * Constants.P2_39 / 2.0;

        int idx = msg.prn - Constants.MINPRNSBS;
        if (idx >= 0 && idx < nav.seph.length) {
            if (Math.abs(TimeSystem.timediff(nav.seph[idx].t0, seph.t0)) < 1E-3) {
                return 0;
            }
            int prevIdx = Constants.NSATSBS + idx;
            if (prevIdx < nav.seph.length) {
                nav.seph[prevIdx] = nav.seph[idx];
            }
            nav.seph[idx] = seph;
        }
        return 1;
    }

    static int decodeSbstype18(SbsMsg msg, SbsIon[] sbsion) {
        initIgpBands();
        int band = (int) BitUtils.getbitu(msg.msg, 18, 4);

        SbsIgpBand[] p;
        int m;
        if (band >= 0 && band <= 8) {
            p = IGP_BAND1[band];
            m = 8;
        } else if (band >= 9 && band <= 10) {
            p = IGP_BAND2[band - 9];
            m = 5;
        } else {
            return 0;
        }

        sbsion[band].iodi = (int) BitUtils.getbitu(msg.msg, 22, 2);

        int n = 0;
        for (int i = 1; i <= 201; i++) {
            if (BitUtils.getbitu(msg.msg, 23 + i, 1) == 0) continue;
            for (int j = 0; j < m; j++) {
                if (i < p[j].bits || p[j].bite < i) continue;
                sbsion[band].igp[n].lat = band <= 8 ? p[j].y[i - p[j].bits] : p[j].x;
                sbsion[band].igp[n].lon = band <= 8 ? p[j].x : p[j].y[i - p[j].bits];
                n++;
                break;
            }
        }
        sbsion[band].nigp = n;
        return 1;
    }

    static int decodeLongcorr0(SbsMsg msg, int pos, SbsSat sbssat) {
        int n = (int) BitUtils.getbitu(msg.msg, pos, 6);
        if (n == 0 || n > Constants.MAXSAT) return 0;

        sbssat.sat[n - 1].lcorr.iode = (int) BitUtils.getbitu(msg.msg, pos + 6, 8);

        for (int i = 0; i < 3; i++) {
            sbssat.sat[n - 1].lcorr.dpos[i] = BitUtils.getbits(msg.msg, pos + 14 + i * 15, 15) * 0.125;
        }
        sbssat.sat[n - 1].lcorr.daf0 = BitUtils.getbits(msg.msg, pos + 59, 11) * Constants.P2_31;
        sbssat.sat[n - 1].lcorr.daf1 = BitUtils.getbits(msg.msg, pos + 70, 8) * Constants.P2_39;
        int t = (int) BitUtils.getbitu(msg.msg, pos + 78, 13) * 16 - (int) msg.tow % 86400;
        if (t <= -43200) t += 86400;
        else if (t > 43200) t -= 86400;
        sbssat.sat[n - 1].lcorr.t0 = TimeSystem.gpst2time(msg.week, msg.tow + t);
        return 1;
    }

    static int decodeLongcorr1(SbsMsg msg, int pos, SbsSat sbssat) {
        int n = (int) BitUtils.getbitu(msg.msg, pos, 6);
        if (n == 0 || n > Constants.MAXSAT) return 0;

        sbssat.sat[n - 1].lcorr.iode = (int) BitUtils.getbitu(msg.msg, pos + 6, 8);

        for (int i = 0; i < 3; i++) {
            sbssat.sat[n - 1].lcorr.dpos[i] = BitUtils.getbits(msg.msg, pos + 14 + i * 11, 11) * 0.125;
            sbssat.sat[n - 1].lcorr.dvel[i] = BitUtils.getbits(msg.msg, pos + 58 + i * 8, 8) * Constants.P2_11;
        }
        sbssat.sat[n - 1].lcorr.daf0 = BitUtils.getbits(msg.msg, pos + 47, 11) * Constants.P2_31;
        sbssat.sat[n - 1].lcorr.daf1 = BitUtils.getbits(msg.msg, pos + 82, 8) * Constants.P2_39;
        int t = (int) BitUtils.getbitu(msg.msg, pos + 90, 13) * 16 - (int) msg.tow % 86400;
        if (t <= -43200) t += 86400;
        else if (t > 43200) t -= 86400;
        sbssat.sat[n - 1].lcorr.t0 = TimeSystem.gpst2time(msg.week, msg.tow + t);
        return 1;
    }

    static int decodeLongcorrh(SbsMsg msg, int pos, SbsSat sbssat) {
        if (BitUtils.getbitu(msg.msg, pos, 1) == 0) {
            if (sbssat.iodp == (int) BitUtils.getbitu(msg.msg, pos + 103, 2)) {
                return decodeLongcorr0(msg, pos + 1, sbssat) != 0
                        && decodeLongcorr0(msg, pos + 52, sbssat) != 0 ? 1 : 0;
            }
        } else {
            if (sbssat.iodp == (int) BitUtils.getbitu(msg.msg, pos + 104, 2)) {
                return decodeLongcorr1(msg, pos + 1, sbssat);
            }
        }
        return 0;
    }

    static int decodeSbstype24(SbsMsg msg, SbsSat sbssat) {
        if (sbssat.iodp != (int) BitUtils.getbitu(msg.msg, 110, 2)) return 0;

        int blk = (int) BitUtils.getbitu(msg.msg, 112, 2);
        int iodf = (int) BitUtils.getbitu(msg.msg, 114, 2);

        for (int i = 0; i < 6; i++) {
            int j = 13 * blk + i;
            if (j >= sbssat.nsat) break;
            int udre = (int) BitUtils.getbitu(msg.msg, 86 + 4 * i, 4);

            sbssat.sat[j].fcorr.t0 = TimeSystem.gpst2time(msg.week, msg.tow);
            sbssat.sat[j].fcorr.prc = BitUtils.getbits(msg.msg, 14 + i * 12, 12) * 0.125;
            sbssat.sat[j].fcorr.udre = (short) (udre + 1);
            sbssat.sat[j].fcorr.iodf = iodf;
        }
        return decodeLongcorrh(msg, 120, sbssat);
    }

    static int decodeSbstype25(SbsMsg msg, SbsSat sbssat) {
        int r1 = decodeLongcorrh(msg, 14, sbssat);
        int r2 = decodeLongcorrh(msg, 120, sbssat);
        return (r1 != 0 && r2 != 0) ? 1 : 0;
    }

    static int decodeSbstype26(SbsMsg msg, SbsIon[] sbsion) {
        int band = (int) BitUtils.getbitu(msg.msg, 14, 4);
        if (band > Constants.MAXBAND) return 0;
        if (sbsion[band].iodi != (int) BitUtils.getbitu(msg.msg, 217, 2)) return 0;

        int block = (int) BitUtils.getbitu(msg.msg, 18, 4);

        for (int i = 0; i < 15; i++) {
            int j = block * 15 + i;
            if (j >= sbsion[band].nigp) continue;
            int give = (int) BitUtils.getbitu(msg.msg, 22 + i * 13 + 9, 4);
            int delay = (int) BitUtils.getbitu(msg.msg, 22 + i * 13, 9);

            sbsion[band].igp[j].t0 = TimeSystem.gpst2time(msg.week, msg.tow);
            sbsion[band].igp[j].delay = (delay == 0x1FF) ? 0.0f : delay * 0.125f;
            sbsion[band].igp[j].give = (short) (give + 1);
            if (sbsion[band].igp[j].give >= 16) {
                sbsion[band].igp[j].give = 0;
            }
        }
        return 1;
    }

    static double ionppp(double[] pos, double[] azel, double re, double hion, double[] posp) {
        double rp = re + pos[2] / 1000.0;
        double tz = Constants.PI / 2.0 - azel[1];
        double sinel = Math.sin(azel[1]);
        double[] rr = new double[3];
        CoordTransform.pos2ecef(pos, rr);
        double[] enu = new double[3];
        enu[0] = Math.sin(azel[0]) * sinel;
        enu[1] = Math.cos(azel[0]) * sinel;
        enu[2] = Math.cos(azel[1]);
        double[] ep = new double[3];
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) {
            r[i] = rr[i] + rp * enu[i];
        }
        CoordTransform.ecef2pos(r, ep);
        posp[0] = ep[0];
        posp[1] = ep[1];
        double fp = 1.0 / Math.sqrt(1.0 - rp * rp * Math.cos(tz) * Math.cos(tz) / ((re + hion) * (re + hion)));
        return fp;
    }

    static void searchigp(GTime time, double[] posp, SbsIon[] ion, SbsIgp[] igp, double[] xy) {
        double lat = posp[0] * Constants.R2D;
        double lon = posp[1] * Constants.R2D;
        int[] latp = new int[2];
        int[] lonp = new int[4];

        if (lon >= 180.0) lon -= 360.0;

        if (-55.0 <= lat && lat < 55.0) {
            latp[0] = (int) Math.floor(lat / 5.0) * 5;
            latp[1] = latp[0] + 5;
            lonp[0] = lonp[1] = (int) Math.floor(lon / 5.0) * 5;
            lonp[2] = lonp[3] = lonp[0] + 5;
            xy[0] = (lon - lonp[0]) / 5.0;
            xy[1] = (lat - latp[0]) / 5.0;
        } else {
            latp[0] = (int) Math.floor((lat - 5.0) / 10.0) * 10 + 5;
            latp[1] = latp[0] + 10;
            lonp[0] = lonp[1] = (int) Math.floor(lon / 10.0) * 10;
            lonp[2] = lonp[3] = lonp[0] + 10;
            xy[0] = (lon - lonp[0]) / 10.0;
            xy[1] = (lat - latp[0]) / 10.0;
            if (75.0 <= lat && lat < 85.0) {
                lonp[1] = (int) Math.floor(lon / 90.0) * 90;
                lonp[3] = lonp[1] + 90;
            } else if (-85.0 <= lat && lat < -75.0) {
                lonp[0] = (int) Math.floor((lon - 50.0) / 90.0) * 90 + 40;
                lonp[2] = lonp[0] + 90;
            } else if (lat >= 85.0) {
                for (int i = 0; i < 4; i++) lonp[i] = (int) Math.floor(lon / 90.0) * 90;
            } else if (lat < -85.0) {
                for (int i = 0; i < 4; i++) lonp[i] = (int) Math.floor((lon - 50.0) / 90.0) * 90 + 40;
            }
        }
        for (int i = 0; i < 4; i++) if (lonp[i] == 180) lonp[i] = -180;

        for (int b = 0; b <= Constants.MAXBAND; b++) {
            for (int k = 0; k < ion[b].nigp; k++) {
                SbsIgp p = ion[b].igp[k];
                if (p.t0.time == 0) continue;
                if (p.lat == latp[0] && p.lon == lonp[0] && p.give > 0) igp[0] = p;
                else if (p.lat == latp[1] && p.lon == lonp[1] && p.give > 0) igp[1] = p;
                else if (p.lat == latp[0] && p.lon == lonp[2] && p.give > 0) igp[2] = p;
                else if (p.lat == latp[1] && p.lon == lonp[3] && p.give > 0) igp[3] = p;
                if (igp[0] != null && igp[1] != null && igp[2] != null && igp[3] != null) return;
            }
        }
    }

    public static int sbsioncorr(GTime time, Nav nav, double[] pos, double[] azel,
                                  double[] delay, double[] var) {
        delay[0] = 0.0;
        var[0] = 0.0;
        if (pos[2] < -100.0 || azel[1] <= 0) return 1;

        double[] posp = new double[2];
        double fp = ionppp(pos, azel, RE_SBAS, HION_SBAS, posp);

        SbsIgp[] igp = new SbsIgp[4];
        double[] xy = new double[2];
        searchigp(time, posp, nav.sbsion, igp, xy);
        double x = xy[0];
        double y = xy[1];

        double[] w = new double[4];
        int err = 0;

        if (igp[0] != null && igp[1] != null && igp[2] != null && igp[3] != null) {
            w[0] = (1.0 - x) * (1.0 - y);
            w[1] = (1.0 - x) * y;
            w[2] = x * (1.0 - y);
            w[3] = x * y;
        } else if (igp[0] != null && igp[1] != null && igp[2] != null) {
            w[1] = y;
            w[2] = x;
            w[0] = 1.0 - w[1] - w[2];
            if (w[0] < 0.0) err = 1;
        } else if (igp[0] != null && igp[2] != null && igp[3] != null) {
            w[0] = 1.0 - x;
            w[3] = y;
            w[2] = 1.0 - w[0] - w[3];
            if (w[2] < 0.0) err = 1;
        } else if (igp[0] != null && igp[1] != null && igp[3] != null) {
            w[0] = 1.0 - y;
            w[3] = x;
            w[1] = 1.0 - w[0] - w[3];
            if (w[1] < 0.0) err = 1;
        } else if (igp[1] != null && igp[2] != null && igp[3] != null) {
            w[1] = 1.0 - x;
            w[2] = 1.0 - y;
            w[3] = 1.0 - w[1] - w[2];
            if (w[3] < 0.0) err = 1;
        } else {
            err = 1;
        }

        if (err != 0) return 0;

        for (int i = 0; i < 4; i++) {
            if (igp[i] == null) continue;
            double t = TimeSystem.timediff(time, igp[i].t0);
            delay[0] += w[i] * igp[i].delay;
            var[0] += w[i] * varicorr(igp[i].give) * 9E-8 * Math.abs(t);
        }
        delay[0] *= fp;
        var[0] *= fp * fp;
        return 1;
    }

    static void getmet(double lat, double[] met) {
        lat = Math.abs(lat);
        if (lat <= 15.0) {
            System.arraycopy(MET_PRM[0], 0, met, 0, 10);
        } else if (lat >= 75.0) {
            System.arraycopy(MET_PRM[4], 0, met, 0, 10);
        } else {
            int j = (int) (lat / 15.0);
            double a = (lat - j * 15.0) / 15.0;
            for (int i = 0; i < 10; i++) {
                met[i] = (1.0 - a) * MET_PRM[j - 1][i] + a * MET_PRM[j][i];
            }
        }
    }

    public static double sbstropcorr(GTime time, double[] pos, double[] azel, double[] var) {
        final double k1 = 77.604;
        final double k2 = 382000.0;
        final double rd = 287.054;
        final double gm = 9.784;
        final double g = 9.80665;

        if (pos[2] < -100.0 || 10000.0 < pos[2] || azel[1] <= 0) {
            var[0] = 0.0;
            return 0.0;
        }

        double[] met = new double[10];
        getmet(pos[0] * Constants.R2D, met);
        double c = Math.cos(2.0 * Constants.PI * (TimeSystem.time2doy(time) - (pos[0] >= 0.0 ? 28.0 : 211.0)) / 365.25);
        for (int i = 0; i < 5; i++) met[i] -= met[i + 5] * c;

        double h = pos[2];
        double zh = 1E-6 * k1 * rd * met[0] / gm;
        double zw = 1E-6 * k2 * rd / (gm * (met[4] + 1.0) - met[3] * rd) * met[2] / met[1];
        zh *= Math.pow(1.0 - met[3] * h / met[1], g / (rd * met[3]));
        zw *= Math.pow(1.0 - met[3] * h / met[1], (met[4] + 1.0) * g / (rd * met[3]) - 1.0);

        double sinel = Math.sin(azel[1]);
        double m = 1.001 / Math.sqrt(0.002001 + sinel * sinel);
        var[0] = 0.12 * 0.12 * m * m;
        return (zh + zw) * m;
    }

    static int sbslongcorr(GTime time, int sat, SbsSat sbssat, double[] drs, double[] ddts) {
        for (int k = 0; k < sbssat.nsat; k++) {
            SbsSatP p = sbssat.sat[k];
            if (p.sat != sat || p.lcorr.t0.time == 0) continue;
            double t = TimeSystem.timediff(time, p.lcorr.t0);
            if (Math.abs(t) > Constants.MAXSBSAGEL) return 0;
            for (int i = 0; i < 3; i++) drs[i] = p.lcorr.dpos[i] + p.lcorr.dvel[i] * t;
            ddts[0] = p.lcorr.daf0 + p.lcorr.daf1 * t;
            return 1;
        }
        if (SatUtils.satsys(sat, null) == Constants.SYS_SBS) return 1;
        return 0;
    }

    static int sbsfastcorr(GTime time, int sat, SbsSat sbssat, double[] prc, double[] var) {
        for (int k = 0; k < sbssat.nsat; k++) {
            SbsSatP p = sbssat.sat[k];
            if (p.sat != sat) continue;
            if (p.fcorr.t0.time == 0) break;
            double t = TimeSystem.timediff(time, p.fcorr.t0) + sbssat.tlat;
            if (Math.abs(t) > Constants.MAXSBSAGEF || p.fcorr.udre >= 15) continue;
            prc[0] = p.fcorr.prc;
            if (p.fcorr.ai > 0 && Math.abs(t) <= 8.0 * p.fcorr.dt) {
                prc[0] += p.fcorr.rrc * t;
            }
            var[0] = varfcorr(p.fcorr.udre) + degfcorr(p.fcorr.ai) * t * t / 2.0;
            return 1;
        }
        return 0;
    }

    public static int sbssatcorr(GTime time, int sat, Nav nav, double[] rs,
                                  double[] dts, double[] var) {
        double[] drs = {0, 0, 0};
        double[] dclk = {0};
        double[] prc = {0};
        var[0] = 0.0;

        if (!sbslongcorr(time, sat, nav.sbssat, drs, dclk)) return 0;
        if (!sbsfastcorr(time, sat, nav.sbssat, prc, var)) return 0;

        for (int i = 0; i < 3; i++) rs[i] += drs[i];
        dts[0] += dclk[0] + prc[0] / Constants.CLIGHT;
        return 1;
    }
}