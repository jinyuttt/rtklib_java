package org.rtklib.java.ppprtk;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.time.TimeSystem;

public final class SsrIono {
    private SsrIono() {
    }

    private static final double C2_40_3 = 40.3e16;

    public static double ssrIonoDelay(GTime time, Nav nav, int sat,
                                       double[] pos, double[] azel,
                                       int freq, double wavelength) {
        double freqHz = Constants.CLIGHT / wavelength;
        if (freqHz == 0.0) return 0.0;
        return C2_40_3 / (freqHz * freqHz) * stecModel(time, nav, sat, pos, azel);
    }

    public static double stecModel(GTime time, Nav nav, int sat,
                                    double[] pos, double[] azel) {
        if (nav.ssr != null && sat >= 1 && sat <= Constants.MAXSAT) {
            Ssr ssr = nav.ssr[sat - 1];
            if (ssr != null && ssr.update != 0) {
                double stecSsr = extractSsrStec(ssr, time);
                if (stecSsr != 0.0) {
                    return stecSsr;
                }
            }
        }

        double[] ionOut = new double[2];
        IonosphereModel.ionocorr(time, nav, sat, pos, azel, Constants.IONOOPT_BRDC, ionOut);
        return ionOut[0];
    }

    private static double extractSsrStec(Ssr ssr, GTime time) {
        boolean hasDispersive = false;
        for (int i = 0; i < ssr.dispInd.length; i++) {
            if (ssr.dispInd[i] != 0) {
                hasDispersive = true;
                break;
            }
        }
        if (!hasDispersive) return 0.0;

        if (ssr.t0[5] != null) {
            double age = Math.abs(TimeSystem.timediff(time, ssr.t0[5]));
            if (age > 600.0) return 0.0;
        }

        return 0.0;
    }

    public static boolean hasSsrIono(Ssr[] ssr, int sat) {
        if (ssr == null || sat < 1 || sat > Constants.MAXSAT) return false;
        Ssr s = ssr[sat - 1];
        if (s == null || s.update == 0) return false;
        for (int i = 0; i < s.dispInd.length; i++) {
            if (s.dispInd[i] != 0) return true;
        }
        return false;
    }

    public static double ssrIonoMapFunc(double[] pos, double[] azel) {
        return IonosphereModel.ionmapf(pos, azel);
    }
}