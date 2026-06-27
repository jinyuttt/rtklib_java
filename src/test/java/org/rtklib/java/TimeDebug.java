package org.rtklib.java;

import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.data.GTime;

public class TimeDebug {
    public static void main(String[] args) {
        GTime bdtTime = TimeSystem.bdt2time(1066, 54000.0);
        GTime gpsTime = TimeSystem.bdt2gpst(bdtTime);
        double[] ymd = TimeSystem.time2ymdhms(gpsTime);
        System.out.printf("BDT week 1066, toe 54000 -> GPS: %04d-%02d-%02d %02d:%02d:%06.3f%n",
            (int)ymd[0], (int)ymd[1], (int)ymd[2], (int)ymd[3], (int)ymd[4], ymd[5]);
        int[] wk = new int[1];
        double tow = TimeSystem.time2gpst(gpsTime, wk);
        System.out.printf("GPS week=%d, tow=%.1f%n", wk[0], tow);
        
        GTime gpsTime2 = TimeSystem.gpst2time(2422, 61218.0);
        double[] ymd2 = TimeSystem.time2ymdhms(gpsTime2);
        System.out.printf("GPS week 2422, tow 61218 -> %04d-%02d-%02d %02d:%02d:%06.3f%n",
            (int)ymd2[0], (int)ymd2[1], (int)ymd2[2], (int)ymd2[3], (int)ymd2[4], ymd2[5]);
        
        GTime gpsTime3 = TimeSystem.gpst2time(2421, 61218.0);
        double[] ymd3 = TimeSystem.time2ymdhms(gpsTime3);
        System.out.printf("GPS week 2421, tow 61218 -> %04d-%02d-%02d %02d:%02d:%06.3f%n",
            (int)ymd3[0], (int)ymd3[1], (int)ymd3[2], (int)ymd3[3], (int)ymd3[4], ymd3[5]);
    }
}