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
    public static double time2doy(GTime t) {
        double[] ep = new double[6];
        time2epoch(t, ep);
        ep[1] = ep[2] = 1.0;
        ep[3] = ep[4] = ep[5] = 0.0;
        return timediff(t, epoch2time(ep)) / 86400.0 + 1.0;
    }

    /**
     * Extract seconds of day from GTime.
     * @param time GTime struct
     * @param day  Output: time truncated to start of day
     * @return Seconds of day (0-86400)
     */
    public static double time2sec(GTime time, GTime day) {
        double[] ep = new double[6];
        time2epoch(time, ep);
        double sec = ep[3] * 3600.0 + ep[4] * 60.0 + ep[5];
        ep[3] = ep[4] = ep[5] = 0.0;
        GTime t = epoch2time(ep);
        day.time = t.time;
        day.sec = t.sec;
        return sec;
    }

    /**
     * Convert UTC to GMST (Greenwich Mean Sidereal Time).
     * @param t       UTC time
     * @param ut1Utc  UT1-UTC (s)
     * @return GMST (rad)
     */
    public static double utc2gmst(GTime t, double ut1Utc) {
        final double[] ep2000 = {2000, 1, 1, 12, 0, 0};
        GTime tut = timeadd(t, ut1Utc);
        GTime tut0 = new GTime();
        double ut = time2sec(tut, tut0);
        double t1 = timediff(tut0, epoch2time(ep2000)) / 86400.0 / 36525.0;
        double t2 = t1 * t1;
        double t3 = t2 * t1;
        double gmst0 = 24110.54841 + 8640184.812866 * t1 + 0.093104 * t2 - 6.2E-6 * t3;
        double gmst = gmst0 + 1.002737909350795 * ut;
        return (gmst % 86400.0) * Constants.PI / 43200.0;
    }

    /**
     * Astronomical arguments: f = {l, l', F, D, OMG} (rad).
     * Coefficients for IAU 1980 nutation.
     * @param t  Julian centuries since J2000.0 (TT)
     * @param f  Output: {l, l', F, D, OMG} (rad)
     */
    public static void astArgs(double t, double[] f) {
        final double[][] fc = {
            {134.96340251, 1717915923.2178,   31.8792,  0.051635, -0.00024470},
            {357.52910918,  129596581.0481,   -0.5532,  0.000136, -0.00001149},
            { 93.27209062, 1739527262.8478,  -12.7512, -0.001037,  0.00000417},
            {297.85019547, 1602961601.2090,   -6.3706,  0.006593, -0.00003169},
            {125.04455501,   -6962890.2665,    7.4722,  0.007702, -0.00005939}
        };
        double[] tt = new double[4];
        tt[0] = t;
        for (int i = 1; i < 4; i++) tt[i] = tt[i - 1] * t;
        for (int i = 0; i < 5; i++) {
            f[i] = fc[i][0] * 3600.0;
            for (int j = 0; j < 4; j++) f[i] += fc[i][j + 1] * tt[j];
            f[i] = (f[i] * Constants.AS2R) % (2.0 * Constants.PI);
        }
    }

    /**
     * IAU 1980 nutation model.
     * @param t     Julian centuries since J2000.0 (TT)
     * @param f     Astronomical arguments {l, l', F, D, OMG} (rad)
     * @param dpsi  Output: nutation in longitude (rad)
     * @param deps  Output: nutation in obliquity (rad)
     */
    public static void nutIau1980(double t, double[] f, double[] dpsi, double[] deps) {
        final double[][] nut = {
            {   0,   0,   0,   0,   1, -6798.4, -171996, -174.2, 92025,   8.9},
            {   0,   0,   2,  -2,   2,   182.6,  -13187,   -1.6,  5736,  -3.1},
            {   0,   0,   2,   0,   2,    13.7,   -2274,   -0.2,   977,  -0.5},
            {   0,   0,   0,   0,   2, -3399.2,    2062,    0.2,  -895,   0.5},
            {   0,  -1,   0,   0,   0,  -365.3,   -1426,    3.4,    54,  -0.1},
            {   1,   0,   0,   0,   0,    27.6,     712,    0.1,    -7,   0.0},
            {   0,   1,   2,  -2,   2,   121.7,    -517,    1.2,   224,  -0.6},
            {   0,   0,   2,   0,   1,    13.6,    -386,   -0.4,   200,   0.0},
            {   1,   0,   2,   0,   2,     9.1,    -301,    0.0,   129,  -0.1},
            {   0,  -1,   2,  -2,   2,   365.2,     217,   -0.5,   -95,   0.3},
            {  -1,   0,   0,   2,   0,    31.8,     158,    0.0,    -1,   0.0},
            {   0,   0,   2,  -2,   1,   177.8,     129,    0.1,   -70,   0.0},
            {  -1,   0,   2,   0,   2,    27.1,     123,    0.0,   -53,   0.0},
            {   1,   0,   0,   0,   1,    27.7,      63,    0.1,   -33,   0.0},
            {   0,   0,   0,   2,   0,    14.8,      63,    0.0,    -2,   0.0},
            {  -1,   0,   2,   2,   2,     9.6,     -59,    0.0,    26,   0.0},
            {  -1,   0,   0,   0,   1,   -27.4,     -58,   -0.1,    32,   0.0},
            {  -2,   0,   0,   2,   0,  -205.9,     -48,    0.0,     1,   0.0},
            {  -2,   0,   2,   0,   1,  1305.5,      46,    0.0,   -24,   0.0},
            {   0,   0,   2,   2,   2,     7.1,     -38,    0.0,    16,   0.0},
            {   2,   0,   2,   0,   2,     6.9,     -31,    0.0,    13,   0.0},
            {   2,   0,   0,   0,   0,    13.8,      29,    0.0,    -1,   0.0},
            {   1,   0,   2,  -2,   2,    23.9,      29,    0.0,   -12,   0.0},
            {   0,   0,   2,   0,   0,    13.6,      26,    0.0,    -1,   0.0},
            {   0,   0,   2,  -2,   0,   173.3,     -22,    0.0,     0,   0.0},
            {  -1,   0,   2,   0,   1,    27.0,      21,    0.0,   -10,   0.0},
            {   0,   2,   0,   0,   0,   182.6,      17,   -0.1,     0,   0.0},
            {   0,   2,   2,  -2,   2,    91.3,     -16,    0.1,     7,   0.0},
            {  -1,   0,   0,   2,   1,    32.0,      16,    0.0,    -8,   0.0},
            {   0,   1,   0,   0,   1,   386.0,     -15,    0.0,     9,   0.0},
            {   1,   0,   0,  -2,   1,   -31.7,     -13,    0.0,     7,   0.0},
            {   0,  -1,   0,   0,   1,  -346.6,     -12,    0.0,     6,   0.0},
            {   2,   0,  -2,   0,   0, -1095.2,      11,    0.0,     0,   0.0},
            {  -1,   0,   2,   2,   1,     9.5,     -10,    0.0,     5,   0.0},
            {   1,   0,   2,   2,   2,     5.6,      -8,    0.0,     3,   0.0},
            {   0,   0,   2,   2,   1,     7.1,      -7,    0.0,     3,   0.0},
            {   1,   1,   0,  -2,   0,   -34.8,      -7,    0.0,     0,   0.0},
            {   0,   1,   2,   0,   2,    13.2,       7,    0.0,    -3,   0.0},
            {  -2,   0,   0,   2,   1,  -199.8,      -6,    0.0,     3,   0.0},
            {   0,   0,   0,   2,   1,    14.8,      -6,    0.0,     3,   0.0},
            {   2,   0,   2,  -2,   2,    12.8,       6,    0.0,    -3,   0.0},
            {   1,   0,   0,   2,   0,     9.6,       6,    0.0,     0,   0.0},
            {   1,   0,   2,  -2,   1,    23.9,       6,    0.0,    -3,   0.0},
            {   0,   0,   0,  -2,   1,   -14.7,      -5,    0.0,     3,   0.0},
            {   0,  -1,   2,  -2,   1,   346.6,      -5,    0.0,     3,   0.0},
            {   2,   0,   2,   0,   1,     6.9,      -5,    0.0,     3,   0.0},
            {   1,  -1,   0,   0,   0,    29.8,       5,    0.0,     0,   0.0},
            {   1,   0,   0,  -1,   0,   411.8,      -4,    0.0,     0,   0.0},
            {   0,   0,   0,   1,   0,    29.5,      -4,    0.0,     0,   0.0},
            {   0,   1,   0,  -2,   0,   -15.4,      -4,    0.0,     0,   0.0},
            {   1,   0,  -2,   0,   0,   -26.9,       4,    0.0,     0,   0.0},
            {   2,   0,   0,  -2,   1,   212.3,       4,    0.0,    -2,   0.0},
            {   0,   1,   2,  -2,   1,   119.6,       4,    0.0,    -2,   0.0},
            {   1,  -1,   0,  -1,   0, -3232.9,      -3,    0.0,     0,   0.0},
            {  -1,  -1,   2,   2,   2,     9.8,      -3,    0.0,     1,   0.0},
            {   0,  -1,   2,   2,   2,     7.2,      -3,    0.0,     1,   0.0},
            {   1,  -1,   2,   0,   2,     9.4,      -3,    0.0,     1,   0.0},
            {   3,   0,   2,   0,   2,     5.5,      -3,    0.0,     1,   0.0},
            {  -2,   0,   2,   0,   2,  1615.7,      -3,    0.0,     1,   0.0},
            {   1,   0,   2,   0,   0,     9.1,       3,    0.0,     0,   0.0},
            {  -1,   0,   2,   4,   2,     5.8,      -2,    0.0,     1,   0.0},
            {   1,   0,   0,   0,   2,    27.8,      -2,    0.0,     1,   0.0},
            {  -1,   0,   2,  -2,   1,   -32.6,      -2,    0.0,     1,   0.0},
            {   0,  -2,   2,  -2,   1,  6786.3,      -2,    0.0,     1,   0.0},
            {  -2,   0,   0,   0,   1,   -13.7,      -2,    0.0,     1,   0.0},
            {   2,   0,   0,   0,   1,    13.8,       2,    0.0,    -1,   0.0},
            {   3,   0,   0,   0,   0,     9.2,       2,    0.0,     0,   0.0},
            {   1,   1,   2,   0,   2,     8.9,       2,    0.0,    -1,   0.0},
            {   0,   0,   2,   1,   2,     9.3,       2,    0.0,    -1,   0.0},
            {   1,   0,   0,   2,   1,     9.6,      -1,    0.0,     0,   0.0},
            {   1,   0,   2,   2,   1,     5.6,      -1,    0.0,     1,   0.0},
            {   0,   1,   0,   2,   0,    14.2,      -1,    0.0,     0,   0.0},
            {   0,   1,   2,  -2,   0,   117.5,      -1,    0.0,     0,   0.0},
            {   0,   1,  -2,   2,   0,  -329.8,      -1,    0.0,     0,   0.0},
            {   1,   0,  -2,   2,   0,    23.8,      -1,    0.0,     0,   0.0},
            {   1,   0,  -2,  -2,   0,    -9.5,      -1,    0.0,     0,   0.0},
            {   1,   0,   2,  -2,   0,    32.8,      -1,    0.0,     0,   0.0},
            {   1,   0,   0,  -4,   0,   -10.1,      -1,    0.0,     0,   0.0},
            {   2,   0,   0,  -4,   0,   -15.9,      -1,    0.0,     0,   0.0},
            {   0,   0,   2,   4,   2,     4.8,      -1,    0.0,     0,   0.0},
            {   0,   0,   2,  -1,   2,    25.4,      -1,    0.0,     0,   0.0},
            {  -2,   0,   2,   4,   2,     7.3,      -1,    0.0,     1,   0.0},
            {   2,   0,   2,   2,   2,     4.7,      -1,    0.0,     0,   0.0},
            {   0,  -1,   2,   0,   1,    14.2,      -1,    0.0,     0,   0.0},
            {   0,   0,  -2,   0,   1,   -13.6,      -1,    0.0,     0,   0.0},
            {   0,   0,   4,  -2,   2,    12.7,       1,    0.0,     0,   0.0},
            {   0,   1,   0,   0,   2,   409.2,       1,    0.0,     0,   0.0},
            {   1,   1,   2,  -2,   2,    22.5,       1,    0.0,    -1,   0.0},
            {   3,   0,   2,  -2,   2,     8.7,       1,    0.0,     0,   0.0},
            {  -1,   0,   0,   0,   2,   -27.3,       1,    0.0,    -1,   0.0},
            {   0,   0,  -2,   2,   1,  -169.0,       1,    0.0,     0,   0.0},
            {   0,   1,   2,   0,   1,    13.1,       1,    0.0,     0,   0.0},
            {  -1,   0,   4,   0,   2,     9.1,       1,    0.0,     0,   0.0},
            {   2,   1,   0,  -2,   0,   131.7,       1,    0.0,     0,   0.0},
            {   2,   0,   0,   2,   0,     7.1,       1,    0.0,     0,   0.0},
            {   2,   0,   2,  -2,   1,    12.8,       1,    0.0,    -1,   0.0},
            {   2,   0,  -2,   0,   1,  -943.2,       1,    0.0,     0,   0.0},
            {   1,  -1,   0,  -2,   0,   -29.3,       1,    0.0,     0,   0.0},
            {  -1,   0,   0,   1,   1,  -388.3,       1,    0.0,     0,   0.0},
            {  -1,  -1,   0,   2,   1,    35.0,       1,    0.0,     0,   0.0},
            {   0,   1,   0,   1,   0,    27.3,       1,    0.0,     0,   0.0}
        };

        double psi = 0.0, eps = 0.0;
        for (int i = 0; i < 106; i++) {
            double ang = nut[i][0] * f[0] + nut[i][1] * f[1] + nut[i][2] * f[2]
                       + nut[i][3] * f[3] + nut[i][4] * f[4];
            psi += (nut[i][6] + nut[i][7] * t) * Math.sin(ang);
            eps += (nut[i][8] + nut[i][9] * t) * Math.cos(ang);
        }
        dpsi[0] = psi * 1E-4 * Constants.AS2R;
        deps[0] = eps * 1E-4 * Constants.AS2R;
    }

}