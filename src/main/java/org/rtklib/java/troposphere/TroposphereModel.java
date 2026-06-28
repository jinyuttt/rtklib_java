package org.rtklib.java.troposphere;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.ionosphere.SbasCorrection;
import org.rtklib.java.time.TimeSystem;

/**
 * Troposphere delay correction models.
 * Aligned with RTKLIB rtkcmn.c tropcorr().
 * 
 * Supported models:
 * - Saastamoinen: Standard troposphere model
 * - UNB3: Improved troposphere model
 * - GMF: Global mapping function
 */
public final class TroposphereModel {
    private TroposphereModel() {
    }

    private static final double ERR_TROP = 3.0;
    private static final double ERR_SAAS = 0.3;
    private static final double REL_HUMI = 0.7;

    public static boolean tropcorr(GTime time, Nav nav, double[] pos,
                                    double[] azel, int tropopt, double[] out) {
        if (tropopt == Constants.TROPOPT_SAAS || tropopt == Constants.TROPOPT_EST || tropopt == Constants.TROPOPT_ESTG) {
            out[0] = saastamoinen(pos, azel, REL_HUMI, 293.15);
            out[1] = RtklibCommon.sqr(ERR_SAAS / (Math.sin(azel[1]) + 0.1));
            return true;
        }
        if (tropopt == Constants.TROPOPT_SBAS) {
            double[] var = {0.0};
            out[0] = SbasCorrection.sbstropcorr(time, pos, azel, var);
            out[1] = var[0];
            return true;
        }
        if (tropopt == Constants.TROPOPT_OFF) {
            out[0] = 0.0;
            out[1] = RtklibCommon.sqr(ERR_TROP);
            return true;
        }
        out[0] = 0.0;
        out[1] = 0.0;
        return true;
    }

    /**
     * Compute troposphere delay using Saastamoinen model.
     * 
     * @param pos    Receiver position {lat, lon, h} (rad, rad, m)
     * @param azel   Azimuth/elevation {az, el} (rad)
     * @param humi   Relative humidity (0-1)
     * @param temp   Temperature (K)
     * @return Troposphere delay (m)
     */
    public static double saastamoinen(double[] pos, double[] azel, double humi, double temp) {
        final double TEMP0 = 15.0;
        double hgt, pres, e, z, trph, trpw;

        if (pos[2] < -100.0 || pos[2] > 1E4 || azel[1] <= 0.0) return 0.0;

        hgt = pos[2] < 0.0 ? 0.0 : pos[2];

        pres = 1013.25 * Math.pow(1.0 - 2.2557E-5 * hgt, 5.2568);
        temp = TEMP0 - 6.5E-3 * hgt + 273.16;
        e = 6.108 * humi * Math.exp((17.15 * temp - 4684.0) / (temp - 38.45));

        z = Constants.PI / 2.0 - azel[1];
        trph = 0.0022768 * pres / (1.0 - 0.00266 * Math.cos(2.0 * pos[0]) - 0.00028 * hgt / 1E3) / Math.cos(z);
        trpw = 0.002277 * (1255.0 / temp + 0.05) * e / Math.cos(z);

        return trph + trpw;
    }

    /**
     * Compute troposphere delay using UNB3 model.
     * 
     * @param pos    Receiver position {lat, lon, h} (rad, rad, m)
     * @param azel   Azimuth/elevation {az, el} (rad)
     * @param humi   Relative humidity (0-1)
     * @param temp   Temperature (K)
     * @return Troposphere delay (m)
     */
    public static double unb3(double[] pos, double[] azel, double humi, double temp) {
        double el = azel[1];
        
        if (el <= 0.0) return 0.0;
        
        double z = Constants.PI / 2.0 - el;
        
        double A_h = 1.2769934E-3;
        double B_h = 2.9153695E-3;
        double C_h = 6.3938833E-3;
        
        double sinel = Math.sin(el);
        double m_hydro = A_h / sinel + B_h / (sinel * sinel) + C_h / (sinel * sinel * sinel);
        
        double pres = stdPressure(pos[2]);
        double delay = pres * m_hydro / 1013.25;
        
        double e0 = stdTemp(temp, humi);
        double T0 = temp;
        
        double A_w = 5.8021897E-4;
        double B_w = 1.4275268E-3;
        double C_w = 4.3472961E-3;
        
        double m_wet = A_w / sinel + B_w / (sinel * sinel) + C_w / (sinel * sinel * sinel);
        
        delay += (1255.0 / T0 + 0.05) * e0 * m_wet / 1013.25;
        
        return delay;
    }

    /**
     * Compute troposphere delay using GMF (Global Mapping Function).
     * Simplified version using VMF1 coefficients.
     * 
     * @param pos    Receiver position {lat, lon, h} (rad, rad, m)
     * @param azel   Azimuth/elevation {az, el} (rad)
     * @param humi   Relative humidity (0-1)
     * @param temp   Temperature (K)
     * @param gmf    GMF coefficients
     * @return Troposphere delay (m)
     */
    public static double gmf(double[] pos, double[] azel, double humi, double temp, double[] gmf) {
        double el = azel[1];
        
        if (el <= 0.0) return 0.0;
        
        double pres = stdPressure(pos[2]);
        double e0 = stdTemp(temp, humi);
        
        double sinel = Math.sin(el);
        double z = Constants.PI / 2.0 - el;
        
        double m_hydro = 0.001 / sinel * (1.0 + gmf[0] * z + gmf[1] * z * z);
        double m_wet = 0.001 / sinel * (1.0 + gmf[2] * z + gmf[3] * z * z);
        
        double delay = 0.002277 * m_hydro * pres;
        delay += 0.002277 * (1255.0 / temp + 0.05) * e0 * m_wet;
        
        return delay;
    }

    /**
     * Compute standard pressure at given height.
     * @param h Height (m)
     * @return Pressure (hPa)
     */
    private static double stdPressure(double h) {
        return 1013.25 * Math.pow(1.0 - 2.25577E-5 * h, 5.25588);
    }

    /**
     * Compute standard temperature at given height.
     * @param T0   Surface temperature (K)
     * @param humi Relative humidity (0-1)
     * @return Temperature (K)
     */
    private static double stdTemp(double T0, double humi) {
        return 6.1078 * Math.exp(17.27 * (T0 - 273.15) / (T0 - 35.85)) * humi;
    }

    /**
     * Compute mapping function for troposphere delay.
     * 
     * @param el Elevation angle (rad)
     * @return Mapping function value
     */
    public static double mapFunc(double el) {
        if (el <= 0.0) return 0.0;
        double sinel = Math.sin(el);
        return 1.001 / Math.sqrt(0.002001 + sinel * sinel);
    }

    /**
     * Compute troposphere gradient.
     * 
     * @param pos  Receiver position {lat, lon, h} (rad, rad, m)
     * @param azel Azimuth/elevation {az, el} (rad)
     * @param humi Relative humidity (0-1)
     * @param temp Temperature (K)
     * @return Gradient (m/rad)
     */
    public static double tropGrad(double[] pos, double[] azel, double humi, double temp) {
        double el = azel[1];
        
        if (el <= 0.0 || el >= Constants.PI / 2.0) return 0.0;
        
        double pres = stdPressure(pos[2]);
        double e0 = stdTemp(temp, humi);
        
        double sinel = Math.sin(el);
        double cosel = Math.cos(el);
        
        double m_prime = -0.001 / (sinel * sinel) * 
                        Math.sqrt(0.002001 + sinel * sinel) / 
                        (0.002001 + sinel * sinel);
        
        double delay_grad = 0.002277 * m_prime * pres * cosel;
        delay_grad += 0.002277 * m_prime * (1255.0 / temp + 0.05) * e0 * cosel;
        
        return delay_grad;
    }
    private static double interpc(double[] coef, double lat) {
        int i = (int)(lat / 15.0);
        if (i < 1) return coef[0];
        if (i > 4) return coef[4];
        return coef[i - 1] * (1.0 - lat / 15.0 + i) + coef[i] * (lat / 15.0 - i);
    }

    private static double mapfNmf(double el, double a, double b, double c) {
        double sinel = Math.sin(el);
        return (1.0 + a / (1.0 + b / (1.0 + c))) / (sinel + a / (sinel + b / (sinel + c)));
    }

    public static double nmf(GTime time, double[] pos, double[] azel, double[] mapfw) {
        double[][] coef = {
            { 1.2769934E-3, 1.2683230E-3, 1.2465397E-3, 1.2196049E-3, 1.2045996E-3},
            { 2.9153695E-3, 2.9152299E-3, 2.9288445E-3, 2.9022565E-3, 2.9024912E-3},
            {62.610505E-3, 62.837393E-3, 63.721774E-3, 63.824265E-3, 64.258455E-3},
            { 0.0000000E-0, 1.2709626E-5, 2.6523662E-5, 3.4000452E-5, 4.1202191E-5},
            { 0.0000000E-0, 2.1414979E-5, 3.0160779E-5, 7.2562722E-5, 11.723375E-5},
            { 0.0000000E-0, 9.0128400E-5, 4.3497037E-5, 84.795348E-5, 170.37206E-5},
            { 5.8021897E-4, 5.6794847E-4, 5.8118019E-4, 5.9727542E-4, 6.1641693E-4},
            { 1.4275268E-3, 1.5138625E-3, 1.4572752E-3, 1.5007428E-3, 1.7599082E-3},
            { 4.3472961E-2, 4.6729510E-2, 4.3908931E-2, 4.4626982E-2, 5.4736038E-2}
        };
        double[] aht = {2.53E-5, 5.49E-3, 1.14E-3};

        double el = azel[1];
        double lat = pos[0] * Constants.R2D;
        double hgt = pos[2];

        if (el <= 0.0) {
            if (mapfw != null) mapfw[0] = 0.0;
            return 0.0;
        }

        double y = (TimeSystem.time2doy(time) - 28.0) / 365.25 + (lat < 0.0 ? 0.5 : 0.0);
        double cosy = Math.cos(2.0 * Constants.PI * y);
        lat = Math.abs(lat);

        double[] ah = new double[3];
        double[] aw = new double[3];
        for (int i = 0; i < 3; i++) {
            ah[i] = interpc(coef[i], lat) - interpc(coef[i + 3], lat) * cosy;
            aw[i] = interpc(coef[i + 6], lat);
        }

        double dm = (1.0 / Math.sin(el) - mapfNmf(el, aht[0], aht[1], aht[2])) * hgt / 1E3;

        if (mapfw != null) mapfw[0] = mapfNmf(el, aw[0], aw[1], aw[2]);

        return mapfNmf(el, ah[0], ah[1], ah[2]) + dm;
    }

    public static double tropmapf(GTime time, double[] pos, double[] azel, double[] mapfw) {
        if (pos[2] < -1000.0 || pos[2] > 20000.0) {
            if (mapfw != null) mapfw[0] = 0.0;
            return 0.0;
        }
        return nmf(time, pos, azel, mapfw);
    }
}