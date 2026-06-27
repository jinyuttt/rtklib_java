package org.rtklib.java;

import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.data.GTime;

public class TimeDebug2 {
    public static void main(String[] args) {
        GTime bdtTime = TimeSystem.bdt2time(1066, 54000.0);
        GTime gpsTime = TimeSystem.bdt2gpst(bdtTime);
        int[] weekArr = new int[1];
        double towP = TimeSystem.time2gpst(gpsTime, weekArr);
        int week = weekArr[0];
        System.out.printf("this.time: GPS week=%d, towP=%.1f%n", week, towP);
        
        double tow = 61218.0;
        double dt = tow - towP;
        int nweek = (int) Math.round(dt / 604800.0);
        week -= nweek;
        System.out.printf("tow=%.1f, dt=%.1f, nweek=%d, week=%d%n", tow, dt, nweek, week);
        
        while (tow >= 604800.0) { tow -= 604800.0; week++; }
        while (tow < 0.0) { tow += 604800.0; week--; }
        System.out.printf("After adjustment: week=%d, tow=%.1f%n", week, tow);
        
        GTime result = TimeSystem.gpst2time(week, tow);
        double[] ymd = TimeSystem.time2ymdhms(result);
        System.out.printf("Result: %04d-%02d-%02d %02d:%02d:%06.3f%n",
            (int)ymd[0], (int)ymd[1], (int)ymd[2], (int)ymd[3], (int)ymd[4], ymd[5]);
    }
}