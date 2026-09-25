package org.rtklib.java.ppp;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.Gpt3GridReader;
import org.rtklib.java.ephemeris.Vmf3OpReader;
import org.rtklib.java.rtkpos.Tides;
import org.rtklib.java.tide.AtmosphericTideS1S2;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PppOptimizations {
    private PppOptimizations() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppOptimizations.class);

    private static final double RE_WGS84 = 6378137.0;
    private static final double FE_WGS84 = 1.0 / 298.257223563;

    public static double tropoDelayGpt3Vmf3(GTime time, double[] pos, double[] azel,
                                             double[] x, double[] dtdx, double[] var,
                                             RtkConfig cfg, Nav nav) {
        if (!cfg.enableGpt3Vmf3) {
            return Double.NaN;
        }

        double lat = pos[0] * 180.0 / Math.PI;
        double lon = pos[1] * 180.0 / Math.PI;
        double hell = pos[2];

        double doy = dayOfYear(time);

        double p, T, dT, e, ah, aw, undu;

        if (nav != null && nav.gpt3GridLoaded && nav.gpt3Grid != null) {
            double[] gpt3 = Gpt3GridReader.interpolateGpt3(nav.gpt3Grid, lat, lon, hell, doy);
            if (gpt3 != null) {
                p = gpt3[0];
                T = gpt3[1];
                e = gpt3[2];
                ah = gpt3[3];
                aw = gpt3[4];
                dT = 0.0;
                undu = 0.0;
                LOG.debug("GPT3 grid interpolation: p={} T={} e={} ah={} aw={}",
                        String.format("%.2f", p), String.format("%.2f", T),
                        String.format("%.4f", e), ah, aw);
            } else {
                double[] fallback = gpt3Fallback(lat, lon, hell, doy);
                p = fallback[0]; T = fallback[1]; dT = fallback[2];
                e = fallback[3]; ah = fallback[4]; aw = fallback[5]; undu = fallback[6];
                LOG.warn("GPT3 grid interpolation failed, using fallback formula");
            }
        } else {
            double[] fallback = gpt3Fallback(lat, lon, hell, doy);
            p = fallback[0]; T = fallback[1]; dT = fallback[2];
            e = fallback[3]; ah = fallback[4]; aw = fallback[5]; undu = fallback[6];
            if (nav == null || !nav.gpt3GridLoaded) {
                LOG.warn("GPT3 grid not loaded, using fallback formula (less accurate)");
            }
        }

        double el = azel[1];

        double bH = 0.0029052;
        double bW = vmf3Bw(lat, doy);

        if (nav != null && nav.vmf3OpLoaded && nav.vmf3Coeff != null) {
            double mjd = Vmf3OpReader.mjdFromGTime(time);
            double[] vmf3Op = Vmf3OpReader.interpolateVmf3(nav.vmf3Coeff, mjd);
            if (vmf3Op != null) {
                ah = vmf3Op[0];
                aw = vmf3Op[1];
                if (vmf3Op[2] > 0) bH = vmf3Op[2];
                if (vmf3Op[3] > 0) bW = vmf3Op[3];
                LOG.debug("VMF3 OP interpolated: ah={} aw={} bH={} bW={}",
                        String.format("%.6e", ah), String.format("%.6e", aw),
                        String.format("%.6e", bH), String.format("%.6e", bW));
            }
        }

        double[] vmf3 = vmf3(el, lat, hell, doy, ah, aw, bH, bW);
        double mfh = vmf3[0];
        double mfw = vmf3[1];

        double[] zhdZwd = saastamoinenZhdZwd(p, T, lat, hell);
        double zhd = zhdZwd[0];
        double zwd = zhdZwd[1];

        double gradN = 0.0, gradE = 0.0;
        if (el > 0.0) {
            double cotz = 1.0 / Math.tan(el);
            gradN = mfw * cotz * Math.cos(azel[0]);
            gradE = mfw * cotz * Math.sin(azel[0]);
        }

        dtdx[0] = mfw;
        dtdx[1] = gradN * (x[0] - zhd);
        dtdx[2] = gradE * (x[0] - zhd);

        var[0] = 0.01 * 0.01;

        double delay = mfh * zhd + mfw * (x[0] - zhd);

        LOG.debug("GPT3+VMF3: zhd={}m zwd={}m mfh={} mfw={} delay={}m",
                String.format("%.4f", zhd), String.format("%.4f", zwd),
                String.format("%.6f", mfh), String.format("%.6f", mfw),
                String.format("%.4f", delay));

        return delay;
    }

    private static double dayOfYear(GTime time) {
        double[] ymdhms = new double[6];
        TimeSystem.time2epoch(time, ymdhms);
        int year = (int) ymdhms[0];
        int mon = (int) ymdhms[1];
        int day = (int) ymdhms[2];
        int[] daysInMon = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) daysInMon[2] = 29;
        int doy = day;
        for (int m = 1; m < mon; m++) doy += daysInMon[m];
        double hourFraction = ymdhms[3] + ymdhms[4] / 60.0 + ymdhms[5] / 3600.0;
        return doy + hourFraction / 24.0;
    }

    private static double[] gpt3Fallback(double lat, double lon, double hell, double doy) {
        double[] result = new double[7];

        double cosLat = Math.cos(lat * Math.PI / 180.0);
        double sinLat = Math.sin(lat * Math.PI / 180.0);
        double cosLon = Math.cos(lon * Math.PI / 180.0);
        double sinLon = Math.sin(lon * Math.PI / 180.0);

        double P0 = 1013.25 * Math.pow(1.0 - 0.0000226 * hell, 5.225);
        double T0 = 15.0 - 0.0065 * hell;
        double e0 = 0.01 * Math.exp(-0.000639 * hell);

        double dT = 0.0;
        double dP = 0.0;
        double de = 0.0;
        double dah = 0.0;
        double daw = 0.0;
        double undu = 0.0;

        double cosDoy = Math.cos(2.0 * Math.PI * (doy - 28.0) / 365.25);
        double sinDoy = Math.sin(2.0 * Math.PI * (doy - 28.0) / 365.25);

        double[] aP = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        double[] bP = {0, 0, 0, 0, 0, 0, 0, 0, 0};
        double[] aT = {-4.8, 2.0, -1.6, 0.4, 0.3, 0.2, -0.1, 0.1, 0.0};
        double[] bT = {0.8, -0.6, 0.3, -0.2, 0.1, -0.1, 0.05, -0.03, 0.01};
        double[] ae = {0.5, -0.3, 0.2, -0.1, 0.05, -0.03, 0.02, -0.01, 0.005};
        double[] be = {0.2, -0.1, 0.05, -0.03, 0.02, -0.01, 0.005, -0.003, 0.001};
        double[] aah = {1.23e-5, -5.4e-6, 2.8e-6, -1.5e-6, 8.0e-7, -4.0e-7, 2.0e-7, -1.0e-7, 5.0e-8};
        double[] bah = {2.0e-6, -1.0e-6, 5.0e-7, -2.0e-7, 1.0e-7, -5.0e-8, 2.0e-8, -1.0e-8, 5.0e-9};
        double[] aaw = {5.6e-6, -2.3e-6, 1.2e-6, -6.0e-7, 3.0e-7, -1.5e-7, 7.0e-8, -3.0e-8, 1.5e-8};
        double[] baw = {1.0e-6, -5.0e-7, 2.0e-7, -1.0e-7, 5.0e-8, -2.0e-8, 1.0e-8, -5.0e-9, 2.0e-9};

        double[] aUndu = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};

        int idx = 0;
        double[][] basis = {
                {1.0, 0.0, 0.0},
                {sinLat, 0.0, 0.0},
                {cosLat * cosLon, 0.0, 0.0},
                {cosLat * sinLon, 0.0, 0.0},
                {sinLat * sinLat, 0.0, 0.0},
                {sin2Lat() * cosLon, 0.0, 0.0},
                {sin2Lat() * sinLon, 0.0, 0.0},
                {cosLat * cosLat * Math.cos(2.0 * lon * Math.PI / 180.0), 0.0, 0.0},
                {cosLat * cosLat * Math.sin(2.0 * lon * Math.PI / 180.0), 0.0, 0.0}
        };

        for (int i = 0; i < 9; i++) {
            double B = basis[i][0];
            dP += (aP[i] * cosDoy + bP[i] * sinDoy) * B;
            dT += (aT[i] * cosDoy + bT[i] * sinDoy) * B;
            de += (ae[i] * cosDoy + be[i] * sinDoy) * B;
            dah += (aah[i] * cosDoy + bah[i] * sinDoy) * B;
            daw += (aaw[i] * cosDoy + baw[i] * sinDoy) * B;
            undu += aUndu[i] * B;
        }

        double p = P0 + dP;
        double T = T0 + dT;
        double e = e0 + de;
        double ah = 1.23e-5 + dah;
        double aw = 5.6e-6 + daw;

        result[0] = p;
        result[1] = T;
        result[2] = dT;
        result[3] = e;
        result[4] = ah;
        result[5] = aw;
        result[6] = undu;

        return result;
    }

    private static double sin2Lat() {
        return 0.0;
    }

    private static double[] vmf3(double el, double lat, double hell, double doy,
                                 double ah, double aw, double bH, double bW) {
        double[] result = new double[2];

        double sinel = Math.sin(el);
        if (sinel < 0.01) sinel = 0.01;

        double ch = 0.0;
        double cw = 0.0;

        double mfh = 1.0 + ah / (1.0 + bH / (1.0 + ch / sinel));
        double mfw = 1.0 + aw / (1.0 + bW / (1.0 + cw / sinel));

        double htCorr = vmf3HtCorr(el, hell);
        mfh *= htCorr;
        mfw *= htCorr;

        result[0] = mfh;
        result[1] = mfw;
        return result;
    }

    private static double vmf3Bw(double lat, double doy) {
        double absLat = Math.abs(lat);
        double t = (doy - 28.0) / 365.25 * 2.0 * Math.PI;

        if (absLat <= 15.0) {
            return 0.00108;
        } else if (absLat <= 30.0) {
            double f = (absLat - 15.0) / 15.0;
            return 0.00108 * (1.0 - f) + 0.00148 * f;
        } else if (absLat <= 45.0) {
            double f = (absLat - 30.0) / 15.0;
            return 0.00148 * (1.0 - f) + 0.00220 * f;
        } else if (absLat <= 60.0) {
            double f = (absLat - 45.0) / 15.0;
            return 0.00220 * (1.0 - f) + 0.00334 * f;
        } else if (absLat <= 75.0) {
            double f = (absLat - 60.0) / 15.0;
            return 0.00334 * (1.0 - f) + 0.00536 * f;
        } else {
            return 0.00536;
        }
    }

    private static double vmf3HtCorr(double el, double hell) {
        double sinel = Math.sin(el);
        if (sinel < 0.01) sinel = 0.01;
        double a = 1.0 / sinel;
        double htCorr = 1.0 - hell / 1e6 * a * a;
        if (htCorr < 0.5) htCorr = 0.5;
        return htCorr;
    }

    private static double[] saastamoinenZhdZwd(double p, double T, double lat, double hell) {
        double[] result = new double[2];

        double cosLat = Math.cos(lat * Math.PI / 180.0);
        if (cosLat < 0.01) cosLat = 0.01;

        double zhd = 0.00002412 * p / (1.0 - 0.00266 * cosLat * cosLat - 0.00028 * hell / 1000.0);

        double Tkelvin = T + 273.15;
        double zwd = 0.00000385 * 0.01 * Tkelvin * Tkelvin / (1.0 + 0.0000226 * hell);

        result[0] = zhd;
        result[1] = zwd;
        return result;
    }

    public static double[] tideDisplacementIers2010(GTime tutc, double[] rr, int opt,
                                                     Erp erp, double[][][] odisp,
                                                     RtkConfig cfg) {
        if (!cfg.enableIers2010) {
            return null;
        }

        double[] dr2010 = new double[3];
        Tides.tidedisp(tutc, rr, opt, erp, odisp, dr2010);

        double[] drAtal = atmosphericTide(tutc, rr);
        for (int i = 0; i < 3; i++) dr2010[i] += drAtal[i];

        LOG.debug("IERS2010 tide: dr=[{},{},{}]m",
                String.format("%.5f", dr2010[0]), String.format("%.5f", dr2010[1]), String.format("%.5f", dr2010[2]));

        return dr2010;
    }

    private static double[] atmosphericTide(GTime time, double[] rr) {
        double[] dr = new double[3];

        double[] pos = new double[3];
        CoordTransform.ecef2pos(rr, pos);
        double latDeg = pos[0] * Constants.R2D;
        double lonDeg = pos[1] * Constants.R2D;
        double height = pos[2];

        double mjd = 40587.0 + (time.time + time.sec) / 86400.0;

        double[] enu = AtmosphericTideS1S2.displacementS1S2(latDeg, lonDeg, height, mjd, null);

        CoordTransform.enu2ecef(pos, enu, dr);

        return dr;
    }

}