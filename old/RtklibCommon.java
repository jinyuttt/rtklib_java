package org.rtklib.java.common;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SnrMask;

/**
 * RTKLIB common utility functions aligned with rtkcmn.c.
 */
public final class RtklibCommon_Old {
    private RtklibCommon_Old() {
        // utility class
    }

    /**
     * Compute geometric distance between satellite and receiver.
     * @param rs Satellite position (ECEF)
     * @param rr Receiver position (ECEF)
     * @param e  Line-of-sight unit vector (output)
     * @return Distance (m)
     */
    public static double geodist(double[] rs, double[] rr, double[] e) {
        double r = 0.0;
        for (int i = 0; i < 3; i++) {
            e[i] = rs[i] - rr[i];
            r += e[i] * e[i];
        }
        r = Math.sqrt(r);
        for (int i = 0; i < 3; i++) {
            e[i] /= r;
        }
        /* [SPP-PASSED] Sagnac effect correction (Earth rotation during signal propagation).
           Without this term, eastward bias ~50m appears. Must match C version geodist(). */
        r += Constants.OMGE * (rs[0] * rr[1] - rs[1] * rr[0]) / Constants.CLIGHT;
        return r;
    }

    /**
     * Compute azimuth and elevation angles.
     * @param pos  Receiver position {lat, lon, h} (rad, rad, m)
     * @param e    Line-of-sight vector
     * @param azel Azimuth/elevation {az, el} (rad) (output)
     * @return Elevation angle (rad)
     */
    public static double satazel(double[] pos, double[] e, double[] azel) {
        double az = 0.0, el = Constants.PI / 2.0;
        if (pos[2] > -Constants.RE_WGS84) {
            double[] enu = new double[3];
            CoordTransform.ecef2enu(pos, e, enu);
            double dot2 = enu[0] * enu[0] + enu[1] * enu[1];
            az = dot2 < 1E-12 ? 0.0 : Math.atan2(enu[0], enu[1]);
            if (az < 0.0) az += 2.0 * Constants.PI;
            el = Math.asin(enu[2]);
        }
        if (azel != null) {
            azel[0] = az;
            azel[1] = el;
        }
        return el;
    }

    /**
     * Test satellite exclusion.
     * Aligned with RTKLIB rtkcmn.c satexclude().
     * @param sat Satellite number
     * @param var Variance
     * @param svh Satellite health
     * @param opt Processing options
     * @return 1 if excluded, 0 otherwise
     */
    public static int satexclude(int sat, double var, int svh, PrcOpt opt) {
        int sys = SatUtils.satsys(sat, null);

        if (svh < 0) return 1; /* ephemeris unavailable */

        if (opt != null) {
            if (sat > 0 && sat <= opt.exsats.length && opt.exsats[sat - 1] == 1) return 1; /* excluded satellite */
            if (sat > 0 && sat <= opt.exsats.length && opt.exsats[sat - 1] == 2) return 0; /* included satellite */
            if ((sys & opt.navsys) == 0) return 1; /* unselected sat sys */
        }
        if (sys == Constants.SYS_QZS) svh &= 0xFE; /* mask QZSS LEX health */
        if (sys == Constants.SYS_GLO) {
            if ((svh & 9) != 0 || (svh & 6) == 4) {
                return 1;
            }
        } else if (svh != 0) {
            return 1;
        }
        if (var > Constants.MAX_VAR_EPH) {
            return 1;
        }
        return 0;
    }

    /**
     * Compute dilution of precision (DOP).
     * @param ns    Number of satellites
     * @param azel  Azimuth/elevation array (ns*2)
     * @param elmin Minimum elevation angle (rad)
     * @param dop   DOP values {GDOP, PDOP, HDOP, VDOP} (output)
     */
    public static void dops(int ns, double[] azel, double elmin, double[] dop) {
        for (int i = 0; i < 4; i++) dop[i] = 0.0;
        double[][] H = new double[4][ns];
        double[][] Q = new double[4][4];
        int i, j, k;
        double cosel, sinel;
        for (i = 0; i < ns; i++) {
            if (azel[1 + i * 2] < elmin) continue;
            cosel = Math.cos(azel[1 + i * 2]);
            sinel = Math.sin(azel[1 + i * 2]);
            H[0][i] = -cosel * Math.cos(azel[i * 2]);
            H[1][i] = -cosel * Math.sin(azel[i * 2]);
            H[2][i] = -sinel;
            H[3][i] = 1.0;
        }
        // Simple matrix inversion for DOP
        // Placeholder for full implementation
        if (ns >= 4) {
            for (i = 0; i < 4; i++) {
                Q[i][i] = 1.0;
            }
            dop[0] = Math.sqrt(Q[0][0] + Q[1][1] + Q[2][2] + Q[3][3]);
            dop[1] = Math.sqrt(Q[0][0] + Q[1][1] + Q[2][2]);
            dop[2] = Math.sqrt(Q[0][0] + Q[1][1]);
            dop[3] = Math.sqrt(Q[2][2]);
        }
    }

    /**
     * Test SNR mask.
     * Aligned with RTKLIB rtkcmn.c testsnr().
     * @param base Rover or base-station (0:rover, 1:base)
     * @param idx  Frequency index (0:L1, 1:L2, 2:L3, ...)
     * @param el   Elevation angle (rad)
     * @param snr  C/N0 (dBHz)
     * @param mask SNR mask
     * @return 1 if masked (excluded), 0 if unmasked (ok)
     */
    public static int testsnr(int base, int idx, double el, double snr, SnrMask mask) {
        double minsnr, a;
        int i;

        if (base == 0 ? mask.ena0 == 0 : mask.ena1 == 0) return 0;
        if (idx < 0 || idx >= SnrMask.NFREQ) return 0;

        a = (el * Constants.R2D + 5.0) / 10.0;
        i = (int) Math.floor(a);
        a -= i;
        if (i < 1) minsnr = mask.mask[idx * SnrMask.NROW + 0];
        else if (i > 8) minsnr = mask.mask[idx * SnrMask.NROW + 8];
        else minsnr = (1.0 - a) * mask.mask[idx * SnrMask.NROW + (i - 1)] + a * mask.mask[idx * SnrMask.NROW + i];

        return snr < minsnr ? 1 : 0;
    }

    /**
     * Square of a number.
     * @param x Input
     * @return x^2
     */
    public static double sqr(double x) {
        return x * x;
    }

    /**
     * Vector dot product.
     * @param a Vector a
     * @param b Vector b
     * @param n Length
     * @return a 路 b
     */
    public static double dot(double[] a, double[] b, int n) {
        double d = 0.0;
        for (int i = 0; i < n; i++) d += a[i] * b[i];
        return d;
    }

    /**
     * Vector norm.
     * @param a Vector
     * @param n Length
     * @return ||a||
     */
    public static double norm(double[] a, int n) {
        return Math.sqrt(dot(a, a, n));
    }

    /**
     * Select ionosphere-free combination frequency index.
     * @param nf  Number of frequencies
     * @param sys System code
     * @return Frequency index
     */
    public static int seliflc(int nf, int sys) {
        if (nf >= 2) return 1;
        if (sys == Constants.SYS_IRN) return 0;
        return 0;
    }

    /**
     * Get satellite frequency.
     * @param sat  Satellite number
     * @param code Code index
     * @param nav  Navigation data
     * @return Frequency (Hz)
     */
    public static double sat2freq(int sat, int code, Nav nav) {
        int sys = SatUtils.satsys(sat, null);
        int fcn = 0;
        
        if (sys == Constants.SYS_GLO && nav != null) {
            int prn = sat - Constants.SYS_GLO;
            if (prn >= 1 && prn <= 24 && nav.glo_fcn != null && nav.glo_fcn.length >= prn) {
                fcn = nav.glo_fcn[prn - 1];
            }
        }
        
        return ObsCode.code2freq(sys, code, fcn);
    }

    /**
     * Least squares estimation.
     * @param H Design matrix (n x m)
     * @param v Observation vector (m)
     * @param n Number of unknowns
     * @param m Number of observations
     * @param x Solution vector (n)
     * @param Q Covariance matrix (n x n)
     * @return 0: success, -1: error
     */
    public static int lsq(double[] H, double[] v, int n, int m, double[] x, double[] Q) {
        if (m < n) return -1;
        double[][] A = new double[n][n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < m; k++) {
                    A[i][j] += H[k * n + i] * H[k * n + j];
                }
            }
            for (int k = 0; k < m; k++) {
                b[i] += H[k * n + i] * v[k];
            }
        }
        double[][] AI = new double[n][n];
        for (int i = 0; i < n; i++) AI[i][i] = 1.0;
        for (int i = 0; i < n; i++) {
            double maxVal = Math.abs(A[i][i]);
            int maxRow = i;
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(A[k][i]) > maxVal) {
                    maxVal = Math.abs(A[k][i]);
                    maxRow = k;
                }
            }
            if (maxVal < 1E-12) return -1;
            if (maxRow != i) {
                double[] tmpA = A[i]; A[i] = A[maxRow]; A[maxRow] = tmpA;
                double[] tmpI = AI[i]; AI[i] = AI[maxRow]; AI[maxRow] = tmpI;
                double tmpB = b[i]; b[i] = b[maxRow]; b[maxRow] = tmpB;
            }
            double diag = A[i][i];
            for (int j = 0; j < n; j++) { A[i][j] /= diag; AI[i][j] /= diag; }
            b[i] /= diag;
            for (int k = 0; k < n; k++) {
                if (k == i) continue;
                double factor = A[k][i];
                for (int j = 0; j < n; j++) { A[k][j] -= factor * A[i][j]; AI[k][j] -= factor * AI[i][j]; }
                b[k] -= factor * b[i];
            }
        }
        System.out.println("lsq: n=" + n + " m=" + m);
        System.out.print("lsq b: ");
        for (int i = 0; i < n; i++) System.out.print(String.format("%.3e ", b[i]));
        System.out.println();
        System.out.println("lsq A:");
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) System.out.print(String.format("%.3e ", A[i][j]));
            System.out.println();
        }
        for (int i = 0; i < n; i++) x[i] = b[i];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                Q[i * n + j] = AI[i][j];
            }
        }
        return 0;
    }
}