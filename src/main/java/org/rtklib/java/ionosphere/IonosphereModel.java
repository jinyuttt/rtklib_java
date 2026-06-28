package org.rtklib.java.ionosphere;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.ionosphere.SbasCorrection;

/**
 * Ionosphere delay correction models.
 * Aligned with RTKLIB rtkcmn.c ionocorr().
 * 
 * Supported models:
 * - Klobuchar: GPS broadcast ionosphere model
 * - NeQuick: European ionosphere model (simplified)
 * - None: No correction
 */
public final class IonosphereModel {
    private IonosphereModel() {
    }

    private static final double ERR_ION = 5.0;
    private static final double ERR_BRDCI = 0.5;

    public static boolean ionocorr(GTime time, Nav nav, int sat, double[] pos,
                                    double[] azel, int ionoopt, double[] out) {
        out[0] = 0.0;
        out[1] = 0.0;
        if (ionoopt == Constants.IONOOPT_BRDC) {
            if (nav.ion_gps == null || nav.ion_gps.length < 8) {
                out[1] = RtklibCommon.sqr(ERR_ION);
                return true;
            }
            out[0] = ionmodel(time, pos, azel, nav.ion_gps);
            out[1] = RtklibCommon.sqr(out[0] * ERR_BRDCI);
            return true;
        }
        if (ionoopt == Constants.IONOOPT_SBAS) {
            double[] delay = {0.0}, var = {0.0};
            if (SbasCorrection.sbsioncorr(time, nav, pos, azel, delay, var) != 0) {
                out[0] = delay[0];
                out[1] = var[0];
            } else {
                out[1] = RtklibCommon.sqr(ERR_ION);
            }
            return true;
        }
        if (ionoopt == Constants.IONOOPT_OFF) {
            out[1] = RtklibCommon.sqr(ERR_ION);
            return true;
        }
        return true;
    }

    /**
     * Compute ionosphere delay using Klobuchar model.
     * 
     * @param time   Time of observation (GPST)
     * @param pos    Receiver position {lat, lon, h} (rad, rad, m)
     * @param azel   Azimuth/elevation {az, el} (rad)
     * @param ion    Ionosphere parameters {a0,a1,a2,a3,b0,b1,b2,b3}
     * @param freq   Frequency index (0=L1, 1=L2, etc.)
     * @return Ionosphere delay (m)
     */
    public static double ionmodel(GTime t, double[] pos, double[] azel, double[] ion) {
        double[] ion_default = {
            0.1118E-07, -0.7451E-08, -0.5961E-07, 0.1192E-06,
            0.1167E+06, -0.2294E+06, -0.1311E+06, 0.1049E+07
        };
        double tt, f, psi, phi, lam, amp, per, x;
        int[] week = new int[1];

        if (pos[2] < -1E3 || azel[1] <= 0) return 0.0;

        if (norm(ion, 8) <= 0.0) ion = ion_default;

        psi = 0.0137 / (azel[1] / Constants.PI + 0.11) - 0.022;

        phi = pos[0] / Constants.PI + psi * Math.cos(azel[0]);
        if (phi > 0.416) phi = 0.416;
        else if (phi < -0.416) phi = -0.416;
        lam = pos[1] / Constants.PI + psi * Math.sin(azel[0]) / Math.cos(phi * Constants.PI);

        phi += 0.064 * Math.cos((lam - 1.617) * Constants.PI);

        tt = 43200.0 * lam + TimeSystem.time2gpst(t, week);
        tt -= Math.floor(tt / 86400.0) * 86400.0;

        f = 1.0 + 16.0 * Math.pow(0.53 - azel[1] / Constants.PI, 3.0);

        amp = ion[0] + phi * (ion[1] + phi * (ion[2] + phi * ion[3]));
        per = ion[4] + phi * (ion[5] + phi * (ion[6] + phi * ion[7]));
        amp = amp < 0.0 ? 0.0 : amp;
        per = per < 72000.0 ? 72000.0 : per;
        x = 2.0 * Constants.PI * (tt - 50400.0) / per;

        return Constants.CLIGHT * f * (Math.abs(x) < 1.57 ? 5E-9 + amp * (1.0 + x * x * (-0.5 + x * x / 24.0)) : 5E-9);
    }

    private static double norm(double[] v, int n) {
        double sum = 0.0;
        for (int i = 0; i < n; i++) sum += v[i] * v[i];
        return Math.sqrt(sum);
    }

    /**
     * Compute ionosphere delay using NeQuick model (simplified).
     * This is a simplified version of NeQuick-G.
     * 
     * @param pos  Receiver position {lat, lon, h} (rad, rad, m)
     * @param azel Azimuth/elevation {az, el} (rad)
     * @param neq  NeQuick parameters
     * @param freq Frequency index
     * @return Ionosphere delay (m)
     */
    public static double nequick(double[] pos, double[] azel, double[] neq, int freq) {
        if (azel[1] <= 0.0) return 0.0;
        
        double zenith = Constants.PI / 2.0 - azel[1];
        
        double STEC = neq[0] * Math.exp(-zenith * zenith / (2.0 * neq[1] * neq[1]));
        
        double freq_factor = 1.0;
        if (freq == 1) freq_factor = Math.pow(Constants.FREQL1 / Constants.FREQL2, 2);
        else if (freq == 2) freq_factor = Math.pow(Constants.FREQL1 / Constants.FREQL5, 2);
        
        return STEC * 40.3 / (Constants.FREQL1 * Constants.FREQL1 / 1E6) * freq_factor;
    }

    /**
     * Compute ionosphere delay for dual-frequency ionosphere-free combination.
     * 
     * @param P1  Pseudorange on frequency 1 (m)
     * @param P2  Pseudorange on frequency 2 (m)
     * @return Ionosphere delay (m)
     */
    public static double ionoFree(double P1, double P2) {
        double gamma = Math.pow(Constants.FREQL1 / Constants.FREQL2, 2);
        return (gamma * P1 - P2) / (gamma - 1.0);
    }

    /**
     * Compute ionosphere delay for triple-frequency ionosphere-free combination.
     * 
     * @param P1  Pseudorange on frequency 1 (m)
     * @param P2  Pseudorange on frequency 2 (m)
     * @param P5  Pseudorange on frequency 5 (m)
     * @return Ionosphere delay (m)
     */
    public static double ionoFreeTriple(double P1, double P2, double P5) {
        double gamma12 = Math.pow(Constants.FREQL1 / Constants.FREQL2, 2);
        double gamma15 = Math.pow(Constants.FREQL1 / Constants.FREQL5, 2);
        
        double a = (gamma15 - gamma12) / ((gamma12 - 1.0) * (gamma15 - 1.0));
        double b = (gamma12 * (gamma15 - 1.0) - gamma15 * (gamma12 - 1.0)) / 
                   ((gamma12 - 1.0) * (gamma15 - 1.0));
        
        return a * P1 + b * P2 + (1.0 - a - b) * P5;
    }
}