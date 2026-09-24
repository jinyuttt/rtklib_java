package org.rtklib.java.otl;

/**
 * Astronomical arguments and nodal corrections for tidal constituents.
 * Ported from PRIDE-PPPAR: CalcTidefu.f90 (BiasTide, CalcTidefu, ASTRO5)
 * and OLoadDFlu.f90 (astronomical argument computation).
 */
public final class AstroArg {

    private AstroArg() {}

    /**
     * Compute the 5 basic astronomical mean longitudes: s, h, p, N, p'.
     * Ported from ASTRO5 (R.D. Ray, NASA/GSFC, 2003).
     *
     * @param mjd UTC in Modified Julian Day
     * @return double[5]: s, h, p, N, p' in degrees
     */
    public static double[] astro5(double mjd) {
        double tjd = mjd + 2400000.5;
        double t = (tjd - 2451545.0) / 36525.0;

        double s = (((-1.53388e-8 * t + 1.855835e-6) * t - 1.5786e-3) * t
                + 481267.88123421) * t + 218.3164477;
        double d = (((-8.8445e-9 * t + 1.83195e-6) * t - 1.8819e-3) * t
                + 445267.1114034) * t + 297.8501921;
        double h = s - d;
        double p = ((-1.249172e-5 * t - 1.032e-2) * t + 4069.0137287) * t + 83.3532465;
        double en = ((2.22222e-6 * t + 2.0708e-3) * t - 1934.136261) * t + 125.04452;
        double ps = 282.94 + 1.7192 * t;

        s = s % 360.0; if (s < 0) s += 360.0;
        h = h % 360.0; if (h < 0) h += 360.0;
        p = p % 360.0; if (p < 0) p += 360.0;
        en = en % 360.0; if (en < 0) en += 360.0;

        return new double[]{s, h, p, en, ps};
    }

    /**
     * Compute the 6 arguments for Doodson development: tao, s, h, p, N', ps.
     * Ported from OLoadDFlu.f90 lines 30-43.
     *
     * @param mjd UTC in Modified Julian Day
     * @return double[6]: tao, s, h, p, N', ps in radians
     */
    public static double[] computeAstr(double mjd) {
        double pi = Math.PI;
        double rad = pi / 180.0;

        double t = (mjd - 51544.5) / 36525.0;
        double tt = (mjd - 51544.0) - Math.floor(mjd - 51544.0);

        double[] astr = new double[7];
        astr[2] = 218.31664563 + 481267.88119575 * t
                - 0.001466388889 * t * t
                - 0.000000074112 * t * t * t
                - 0.000000153389 * t * t * t * t;
        astr[3] = 280.4664501606 + 36000.769748805556 * t
                + 0.000303222222 * t * t
                - 0.000001905501 * t * t * t
                - 0.000000065361 * t * t * t * t;
        astr[4] = 83.35324312 + 4069.01363525 * t
                - 0.010321722222 * t * t
                - 0.000014417168 * t * t * t
                + 0.0000000526333 * t * t * t * t;
        astr[5] = 234.95544499 + 1934.136261972222 * t
                - 0.002075611111 * t * t
                - 0.000000213944 * t * t * t
                + 0.000000164972 * t * t * t * t;
        astr[6] = 282.9373409806 + 1.719457666668 * t * t
                + 0.000456888889 * t * t
                - 0.000001943279 * t * t * t
                - 0.0000000033444 * t * t * t * t;
        astr[1] = 360.0 * tt - astr[2] + astr[3];

        for (int i = 1; i <= 6; i++) {
            astr[i] = (astr[i] * rad) % (2.0 * pi);
        }

        double[] result = new double[6];
        for (int i = 0; i < 6; i++) {
            result[i] = astr[i + 1];
        }
        return result;
    }

    /**
     * Compute nodal correction factors df (amplitude) and du (phase) for a constituent.
     * Ported from CalcTidefu.f90.
     *
     * @param mjd     Modified Julian Day
     * @param doodson Doodson number (e.g., 255555 for M2)
     * @return double[2]: [df, du] where df is amplitude factor, du is phase correction in degrees
     */
    public static double[] calcTidefu(double mjd, double doodson) {
        double pi = Math.PI;
        double rad = pi / 180.0;
        double df = 1.0;
        double du = 0.0;

        double[] shpn = astro5(mjd);
        double omega = shpn[3];

        double sinn = Math.sin(omega * rad);
        double cosn = Math.cos(omega * rad);
        double sin2n = Math.sin(2.0 * omega * rad);
        double cos2n = Math.cos(2.0 * omega * rad);

        double[] f = new double[23];
        double[] u = new double[23];

        f[1] = 1.009 + 0.187 * cosn - 0.015 * cos2n;
        f[2] = f[1];
        f[4] = 1.006 + 0.115 * cosn - 0.009 * cos2n;
        f[3] = f[4];
        f[5] = 1.000 - 0.037 * cosn;
        f[6] = f[5];
        f[8] = 1.024 + 0.286 * cosn + 0.008 * cos2n;
        f[7] = f[8];
        f[9] = Math.sqrt(Math.pow(1.0 + 0.189 * cosn - 0.0058 * cos2n, 2)
                + Math.pow(0.189 * sinn - 0.0058 * sin2n, 2));
        f[10] = f[9];
        f[11] = f[9];
        f[12] = Math.sqrt(Math.pow(1.0 + 0.185 * cosn, 2) + Math.pow(0.185 * sinn, 2));
        f[13] = Math.sqrt(Math.pow(1.0 + 0.198 * cosn, 2) + Math.pow(0.198 * sinn, 2));
        f[14] = Math.sqrt(Math.pow(1.0 + 0.640 * cosn + 0.134 * cos2n, 2)
                + Math.pow(0.640 * sinn + 0.134 * sin2n, 2));
        f[15] = Math.sqrt(Math.pow(1.0 - 0.0373 * cosn, 2) + Math.pow(0.0373 * sinn, 2));
        f[16] = f[15];
        f[17] = f[15];
        f[18] = f[15];
        f[19] = f[3];
        f[20] = f[6] * Math.sqrt(f[6]);
        f[21] = f[6] * f[6];
        f[22] = f[6] * f[6] * f[6];

        u[1] = 10.8 * sinn - 1.3 * sin2n;
        u[2] = u[1];
        u[4] = -8.9 * sinn + 0.7 * sin2n;
        u[3] = u[4];
        u[5] = -2.1 * sinn;
        u[6] = u[5];
        u[8] = -17.7 * sinn + 0.7 * sin2n;
        u[7] = u[8];
        u[9] = Math.atan2(0.189 * sinn - 0.0058 * sin2n,
                1.0 + 0.189 * cosn - 0.0058 * sin2n) / rad;
        u[10] = u[9];
        u[11] = u[9];
        u[12] = Math.atan2(0.185 * sinn, 1.0 + 0.185 * cosn) / rad;
        u[13] = Math.atan2(-0.198 * sinn, 1.0 + 0.198 * cosn) / rad;
        u[14] = Math.atan2(-0.640 * sinn - 0.134 * sin2n,
                1.0 + 0.640 * cosn + 0.134 * cos2n) / rad;
        u[15] = Math.atan2(-0.0373 * sinn, 1.0 - 0.0373 * cosn) / rad;
        u[16] = u[15];
        u[17] = u[15];
        u[18] = u[15];
        u[19] = u[3];
        u[20] = 1.5 * u[6];
        u[21] = 2.0 * u[6];
        u[22] = 3.0 * u[6];

        int[] ddsn = {0, 135655, 145555, 163555, 165555, 245655, 255555, 273555, 275555,
                125755, 127555, 137455, 155655, 175455, 185555, 235755, 237555, 247455,
                265455, 164556, 355555, 455555, 655555};

        for (int i = 1; i <= 22; i++) {
            if (Math.abs(doodson - ddsn[i]) < 0.1) {
                df = f[i];
                du = u[i];
                break;
            }
        }

        return new double[]{df, du};
    }

    /**
     * Compute phase bias for a tidal constituent.
     * Ported from BiasTide (IERS 2010 Tables 6.6, 6.7).
     *
     * @param doodson Doodson number (e.g., 255555 for M2)
     * @return bias in degrees
     */
    public static double biasTide(double doodson) {
        double bias = 0.0;
        if (Math.abs(doodson - 55565) < 1e-3) bias = 180;
        else if (Math.abs(doodson - 125755) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 127555) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 135655) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 137455) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 145545) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 145555) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 155555) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 155655) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 157455) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 162556) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 163555) < 1e-3) bias = -90;
        else if (Math.abs(doodson - 163655) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 164556) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 165555) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 166554) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 167555) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 175455) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 185555) < 1e-3) bias = 90;
        else if (Math.abs(doodson - 253755) < 1e-3) bias = 180;
        else if (Math.abs(doodson - 255545) < 1e-3) bias = 180;
        else if (Math.abs(doodson - 265455) < 1e-3) bias = 180;
        else if (Math.abs(doodson - 263655) < 1e-3) bias = 180;
        else if (Math.abs(doodson - 275554) < 1e-3) bias = 180;
        else if (Math.abs(doodson - 355555) < 1e-3) bias = 180;
        return bias;
    }

    /**
     * Decode Doodson number into 6 arguments multipliers.
     * Ported from OLoadDFlu.f90 lines 49-53.
     *
     * @param doodsonInt Doodson number as integer (e.g., 255555)
     * @return int[6]: Doodson arguments multipliers
     */
    public static int[] decodeDoodson(int doodsonInt) {
        int[] dod = new int[6];
        int td = doodsonInt;
        int bs = 100000;
        for (int j = 0; j < 6; j++) {
            dod[j] = td / bs;
            td = td - bs * dod[j];
            bs = bs / 10;
            if (j > 0) dod[j] = dod[j] - 5;
        }
        return dod;
    }
}
