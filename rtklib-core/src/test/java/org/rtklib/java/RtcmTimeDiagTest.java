package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Temporary diagnostic: print observation times and ephemeris toes from RTCM.
 */
public class RtcmTimeDiagTest {

    @Test
    void testDiagTimes() throws Exception {
        String path = TestDataConfig.getTestDataFile("rtcm/rtcm3_gmsd_20121014.rtcm3");
        byte[] data;
        try (FileInputStream fis = new FileInputStream(path)) {
            data = fis.readAllBytes();
        }

        Rtcm rtcm = new Rtcm();
        int offset = 0;
        int obsCount = 0;
        List<Integer> types = new ArrayList<>();
        int firstEphWeek = -1;
        int firstObsWeek = -1;
        double firstObsSow = -1;

        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;

            int type = rtcm.type;
            if (type == 1019 || type == 1020) {
                if (firstEphWeek < 0 && type == 1019 && rtcm.ephsat != 0) {
                    int[] wk = new int[1];
                    double sow = TimeSystem.time2gpst(rtcm.nav.eph[rtcm.ephsat - 1].toe, wk);
                    firstEphWeek = wk[0];
                    System.out.printf("[DIAG] First 1019 eph: sat=%d week=%d toes=%.1f toe.time=%d toe.sec=%.3f%n",
                            rtcm.ephsat, wk[0], rtcm.nav.eph[rtcm.ephsat - 1].toes,
                            rtcm.nav.eph[rtcm.ephsat - 1].toe.time,
                            rtcm.nav.eph[rtcm.ephsat - 1].toe.sec);
                }
            }

            if (isObsType(type) && rtcm.obs.n > 0 && rtcm.obsflag == 1) {
                if (firstObsWeek < 0) {
                    int[] wk = new int[1];
                    double sow = TimeSystem.time2gpst(rtcm.obs.data[0].time, wk);
                    firstObsWeek = wk[0];
                    firstObsSow = sow;
                    System.out.printf("[DIAG] First obs epoch: type=%d week=%d sow=%.1f time.time=%d time.sec=%.3f nsat=%d%n",
                            type, wk[0], sow, rtcm.obs.data[0].time.time, rtcm.obs.data[0].time.sec, rtcm.obs.n);
                    for (int i = 0; i < Math.min(3, rtcm.obs.n); i++) {
                        System.out.printf("[DIAG]   obs[%d] sat=%d P[0]=%.3f%n", i, rtcm.obs.data[i].sat, rtcm.obs.data[i].P[0]);
                    }
                }
                obsCount++;
                if (obsCount <= 2) {
                    types.add(type);
                }
            }
        }

        System.out.printf("[DIAG] Total obs epochs=%d, firstEphWeek=%d, firstObsWeek=%d, firstObsSow=%.1f%n",
                obsCount, firstEphWeek, firstObsWeek, firstObsSow);

        // Compute time difference
        if (firstEphWeek > 0) {
            double dt = Math.abs((firstObsWeek - firstEphWeek) * 604800.0 + firstObsSow);
            System.out.printf("[DIAG] Week diff=%d, raw dt=%.1f seconds (%.1f hours)%n",
                    firstObsWeek - firstEphWeek, dt, dt / 3600.0);
        }

        // Check if ephemeris was found for first obs sat
        if (firstObsWeek >= 0) {
            Rtcm rtcm2 = new Rtcm();
            int off2 = 0;
            while (off2 < data.length) {
                int consumed = rtcm2.input(data, off2, data.length - off2);
                if (consumed <= 0) { off2++; continue; }
                off2 += consumed;
            }
            int ephFound = 0;
            for (int i = 0; i < rtcm2.nav.eph.length; i++) {
                if (rtcm2.nav.eph[i] != null && rtcm2.nav.eph[i].A > 0) {
                    ephFound++;
                    int[] wk = new int[1];
                    double sow = TimeSystem.time2gpst(rtcm2.nav.eph[i].toe, wk);
                    if (ephFound <= 3) {
                        System.out.printf("[DIAG] eph[%d] sat=%d week=%d toes=%.1f toe.time=%d A=%.3f%n",
                                i, rtcm2.nav.eph[i].sat, wk[0], rtcm2.nav.eph[i].toes,
                                rtcm2.nav.eph[i].toe.time, rtcm2.nav.eph[i].A);
                    }
                }
            }
            System.out.printf("[DIAG] Total valid eph=%d%n", ephFound);
        }
    }

    private static boolean isObsType(int type) {
        return (type >= 1001 && type <= 1004)
                || (type >= 1074 && type <= 1077)
                || (type >= 1084 && type <= 1087)
                || (type >= 1094 && type <= 1097)
                || (type >= 1104 && type <= 1107)
                || (type >= 1114 && type <= 1117)
                || (type >= 1124 && type <= 1127)
                || (type >= 1134 && type <= 1137);
    }
}
