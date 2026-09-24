package org.rtklib.java.ppprtk;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

public final class SsrCorrector {
    private SsrCorrector() {
    }

    public static boolean hasSsrData(Ssr[] ssr) {
        if (ssr == null) return false;
        for (int i = 0; i < ssr.length; i++) {
            if (ssr[i] != null && ssr[i].update != 0) return true;
        }
        return false;
    }

    public static void applyOrbitCorrection(Ssr[] ssr, double[] rs, Obsd[] obs, int n) {
        for (int i = 0; i < n; i++) {
            int sat = obs[i].sat;
            if (sat <= 0 || sat > Constants.MAXSAT) continue;
            Ssr s = ssr[sat - 1];
            if (s == null || s.update == 0) continue;

            double rx = rs[i * 6], ry = rs[i * 6 + 1], rz = rs[i * 6 + 2];
            double normR = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (normR < 1.0) continue;

            double[] eR = {rx / normR, ry / normR, rz / normR};

            double vx = rs[i * 6 + 3], vy = rs[i * 6 + 4], vz = rs[i * 6 + 5];
            double normV = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (normV < 1.0) continue;

            double[] eA = {vx / normV, vy / normV, vz / normV};

            double[] eC = {
                eR[1] * eA[2] - eR[2] * eA[1],
                eR[2] * eA[0] - eR[0] * eA[2],
                eR[0] * eA[1] - eR[1] * eA[0]
            };

            for (int j = 0; j < 3; j++) {
                rs[i * 6 + j] += s.deph[0] * eR[j] + s.deph[1] * eA[j] + s.deph[2] * eC[j];
                rs[i * 6 + 3 + j] += s.ddeph[0] * eR[j] + s.ddeph[1] * eA[j] + s.ddeph[2] * eC[j];
            }
        }
    }

    public static void applyClockCorrection(Ssr[] ssr, double[] dts, Obsd[] obs, int n) {
        for (int i = 0; i < n; i++) {
            int sat = obs[i].sat;
            if (sat <= 0 || sat > Constants.MAXSAT) continue;
            Ssr s = ssr[sat - 1];
            if (s == null || s.update == 0) continue;
            if (s.t0[1] == null) continue;

            double dt = TimeSystem.timediff(obs[i].time, s.t0[1]);
            double dclk = s.dclk[0] + s.dclk[1] * dt + s.dclk[2] * dt * dt;
            dts[i * 2] += dclk / Constants.CLIGHT;
        }
    }

    public static void applyHrClockCorrection(Ssr[] ssr, double[] dts, Obsd[] obs, int n) {
        for (int i = 0; i < n; i++) {
            int sat = obs[i].sat;
            if (sat <= 0 || sat > Constants.MAXSAT) continue;
            Ssr s = ssr[sat - 1];
            if (s == null || s.update == 0) continue;
            dts[i * 2] += s.hrclk / Constants.CLIGHT;
        }
    }

    public static double getCodeBias(Ssr[] ssr, int sat, int code) {
        if (sat <= 0 || sat > Constants.MAXSAT) return 0.0;
        Ssr s = ssr[sat - 1];
        if (s == null || s.update == 0) return 0.0;
        if (code <= 0 || code > Constants.MAXCODE) return 0.0;
        return s.cbias[code - 1];
    }

    public static double getPhaseBias(Ssr[] ssr, int sat, int code) {
        if (sat <= 0 || sat > Constants.MAXSAT) return 0.0;
        Ssr s = ssr[sat - 1];
        if (s == null || s.update == 0) return 0.0;
        if (code <= 0 || code > Constants.MAXCODE) return 0.0;
        return s.pbias[code - 1];
    }

    public static boolean isSsrValid(Ssr[] ssr, int sat, GTime time, double maxAge) {
        if (sat <= 0 || sat > Constants.MAXSAT) return false;
        Ssr s = ssr[sat - 1];
        if (s == null || s.update == 0) return false;
        GTime t0Orb = s.t0[0];
        GTime t0Clk = s.t0[1];
        if (t0Orb == null || t0Clk == null) return false;
        double ageOrb = Math.abs(TimeSystem.timediff(time, t0Orb));
        double ageClk = Math.abs(TimeSystem.timediff(time, t0Clk));
        return ageOrb <= maxAge && ageClk <= maxAge;
    }

    public static int getUra(Ssr[] ssr, int sat) {
        if (sat <= 0 || sat > Constants.MAXSAT) return -1;
        Ssr s = ssr[sat - 1];
        if (s == null || s.update == 0) return -1;
        return s.ura;
    }
}