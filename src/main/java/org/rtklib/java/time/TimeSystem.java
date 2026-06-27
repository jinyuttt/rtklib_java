package org.rtklib.java.time;

import org.rtklib.java.data.GTime;
import org.rtklib.java.constants.Constants;

/**
 * Time system conversion utilities.
 * Aligned with RTKLIB time functions in rtkcmn.c.
 *
 * The internal GTime struct stores time as time_t (epoch seconds)
 * plus a sub-second double. All conversion routines match the
 * semantics of the corresponding C functions.
 */
public final class TimeSystem {
    private TimeSystem() {
        // Utility class
    }

    /** GPS time reference (1980-01-06 00:00:00) */
    private static final double[] GPST0 = {1980, 1, 6, 0, 0, 0};
    
    /** Galileo system time reference (1999-08-22 00:00:00) */
    private static final double[] GST0 = {1999, 8, 22, 0, 0, 0};
    
    /** BeiDou time reference (2006-01-01 00:00:00) */
    private static final double[] BDT0 = {2006, 1, 1, 0, 0, 0};
    
    /** Day-of-year for non-leap year (cumulative, start=1) */
    private static final int[] DOY = {1, 32, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335};
    
    /** Days in month for 4-year cycle */
    private static final int[] MDAY = {
        31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,
        31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,
        31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,
        31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31
    };

    /** Leap seconds table {y,m,d,h,m,s,utc-gpst} */
    private static final double[][] LEAPS = {
        {2017, 1, 1, 0, 0, 0, -18},
        {2015, 7, 1, 0, 0, 0, -17},
        {2012, 7, 1, 0, 0, 0, -16},
        {2009, 1, 1, 0, 0, 0, -15},
        {2006, 1, 1, 0, 0, 0, -14},
        {1999, 1, 1, 0, 0, 0, -13},
        {1997, 7, 1, 0, 0, 0, -12},
        {1996, 1, 1, 0, 0, 0, -11},
        {1994, 7, 1, 0, 0, 0, -10},
        {1993, 7, 1, 0, 0, 0,  -9},
        {1992, 7, 1, 0, 0, 0,  -8},
        {1991, 1, 1, 0, 0, 0,  -7},
        {1990, 1, 1, 0, 0, 0,  -6},
        {1988, 1, 1, 0, 0, 0,  -5},
        {1985, 7, 1, 0, 0, 0,  -4},
        {1983, 7, 1, 0, 0, 0,  -3}
    };

    /**
     * Convert calendar epoch to GTime.
     * @param ep {year, month, day, hour, min, sec}
     * @return GTime struct
     */
    public static GTime epoch2time(double[] ep) {
        GTime time = new GTime(0, 0.0);
        int year = (int) ep[0];
        int mon = (int) ep[1];
        int day = (int) ep[2];
        if (year < 1970 || year > 2099 || mon < 1 || mon > 12) {
            return time;
        }
        int days = (year - 1970) * 365 + (year - 1969) / 4
                + DOY[mon - 1] + day - 2
                + (year % 4 == 0 && mon >= 3 ? 1 : 0);
        int sec = (int) Math.floor(ep[5]);
        time.time = (long) days * 86400L + (int) ep[3] * 3600 + (int) ep[4] * 60 + sec;
        time.sec = ep[5] - sec;
        return time;
    }

    /**
     * Convert GTime to calendar epoch.
     * @param t  GTime struct
     * @param ep Output {year, month, day, hour, min, sec}
     */
    public static void time2epoch(GTime t, double[] ep) {
        int days = (int) (t.time / 86400L);
        int sec = (int) (t.time - (long) days * 86400L);
        int mon = 0;
        int day;
        for (day = days % 1461, mon = 0; mon < 48; mon++) {
            if (day >= MDAY[mon]) {
                day -= MDAY[mon];
            } else {
                break;
            }
        }
        ep[0] = 1970 + days / 1461 * 4 + mon / 12;
        ep[1] = mon % 12 + 1;
        ep[2] = day + 1;
        ep[3] = sec / 3600;
        ep[4] = sec % 3600 / 60;
        ep[5] = sec % 60 + t.sec;
    }

    /**
     * Convert GTime to year, month, day, hour, min, sec array.
     * @param t GTime struct
     * @return Array {year, month, day, hour, min, sec}
     */
    public static double[] time2ymdhms(GTime t) {
        double[] ep = new double[6];
        time2epoch(t, ep);
        return ep;
    }

    /**
     * Convert GPS week + tow to GTime.
     * @param week GPS week number
     * @param sec  Time of week in GPS seconds
     * @return GTime struct
     */
    public static GTime gpst2time(int week, double sec) {
        GTime t = epoch2time(GPST0);
        if (sec < -1E9 || sec > 1E9) sec = 0.0;
        t.time += 86400L * 7L * week + (int) sec;
        t.sec = sec - (int) sec;
        return t;
    }

    /**
     * Convert GTime to GPS week and tow.
     * @param t    GTime struct
     * @param week Output GPS week (null to skip)
     * @return Time of week in GPS seconds
     */
    public static double time2gpst(GTime t, int[] week) {
        GTime t0 = epoch2time(GPST0);
        long sec = t.time - t0.time;
        int w = (int) (sec / (86400L * 7L));
        if (week != null) week[0] = w;
        return (double) (sec - (long) w * 86400L * 7L) + t.sec;
    }

    /**
     * Convert GST week + tow to GTime.
     * @param week GST week number
     * @param sec  Time of week in GST seconds
     * @return GTime struct
     */
    public static GTime gst2time(int week, double sec) {
        GTime t = epoch2time(GST0);
        if (sec < -1E9 || sec > 1E9) sec = 0.0;
        t.time += 86400L * 7L * week + (int) sec;
        t.sec = sec - (int) sec;
        return t;
    }

    /**
     * Convert GTime to GST week and tow.
     * @param t    GTime struct
     * @param week Output GST week
     * @return Time of week in GST seconds
     */
    public static double time2gst(GTime t, int[] week) {
        GTime t0 = epoch2time(GST0);
        long sec = t.time - t0.time;
        int w = (int) (sec / (86400L * 7L));
        if (week != null) week[0] = w;
        return (double) (sec - (long) w * 86400L * 7L) + t.sec;
    }

    /**
     * Convert BDT week + tow to GTime.
     * @param week BDT week number
     * @param sec  Time of week in BDT seconds
     * @return GTime struct
     */
    public static GTime bdt2time(int week, double sec) {
        GTime t = epoch2time(BDT0);
        if (sec < -1E9 || sec > 1E9) sec = 0.0;
        t.time += 86400L * 7L * week + (int) sec;
        t.sec = sec - (int) sec;
        return t;
    }

    /**
     * Convert GTime to BDT week and tow.
     * @param t    GTime struct
     * @param week Output BDT week
     * @return Time of week in BDT seconds
     */
    public static double time2bdt(GTime t, int[] week) {
        GTime t0 = epoch2time(BDT0);
        long sec = t.time - t0.time;
        int w = (int) (sec / (86400L * 7L));
        if (week != null) week[0] = w;
        return (double) (sec - (long) w * 86400L * 7L) + t.sec;
    }

    /**
     * Add seconds to GTime.
     * @param t   GTime
     * @param sec Seconds to add
     * @return New GTime
     */
    public static GTime timeadd(GTime t, double sec) {
        GTime result = new GTime(t);
        double tt = t.sec + sec;
        int loopCount = 0;
        while (tt < 0.0) {
            tt += 1.0;
            result.time -= 1L;
            if (++loopCount > 10) break;
        }
        while (tt >= 1.0) {
            tt -= 1.0;
            result.time += 1L;
            if (++loopCount > 10) break;
        }
        result.sec = tt;
        return result;
    }

    /**
     * Subtract two GTimes, return t1 - t2 in seconds.
     * @param t1 GTime
     * @param t2 GTime
     * @return Difference in seconds
     */
    public static double timediff(GTime t1, GTime t2) {
        return (double) (t1.time - t2.time) + t1.sec - t2.sec;
    }

    /**
     * Get UTC time from GPST by applying leap seconds.
     * @param t GPST time
     * @return UTC time
     */
    public static GTime gpst2utc(GTime t) {
        GTime tu = new GTime(t);
        for (int i = 0; i < LEAPS.length; i++) {
            if (timediff(t, epoch2time(LEAPS[i])) >= 0.0) {
                tu.time += (long) LEAPS[i][6];
                break;
            }
        }
        return tu;
    }

    /**
     * Get GPST from UTC time.
     * @param t UTC time
     * @return GPST time
     */
    public static GTime utc2gpst(GTime t) {
        GTime tg = new GTime(t);
        for (int i = 0; i < LEAPS.length; i++) {
            if (timediff(t, epoch2time(LEAPS[i])) >= 0.0) {
                tg.time -= (long) LEAPS[i][6];
                break;
            }
        }
        return tg;
    }

    /**
     * GPST to BDT (BDS).
     * @param t GPST time
     * @return BDT time (note: BDT = GPST - 14s)
     */
    public static GTime gpst2bdt(GTime t) {
        return timeadd(t, -14.0);
    }

    /**
     * BDT to GPST.
     * @param t BDT time
     * @return GPST time
     */
    public static GTime bdt2gpst(GTime t) {
        return timeadd(t, 14.0);
    }

    /**
     * Get the current GPST.
     * @return Current GPST
     */
    public static GTime timeget() {
        return new GTime(System.currentTimeMillis() / 1000L, 0.0);
    }
}