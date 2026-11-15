package org.rtklib.java.ppprtk;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

public final class SsrCorrector {
    /** RTKLIB ephemeris.c MAXAGESSR: max age of ssr orbit and clock (s) */
    public static final double MAXAGESSR = 90.0;
    /** RTKLIB ephemeris.c MAXAGESSR_HRCLK: max age of ssr high-rate clock (s) */
    public static final double MAXAGESSR_HRCLK = 10.0;
    /** RTKLIB ephemeris.c MAXECORSSR: max orbit correction of ssr (m) */
    public static final double MAXECORSSR = 10.0;
    /** RTKLIB ephemeris.c MAXCCORSSR: max clock correction of ssr (m) */
    public static final double MAXCCORSSR = 1E-6 * Constants.CLIGHT;

    private SsrCorrector() {
    }

    public static boolean hasSsrData(Ssr[] ssr) {
        if (ssr == null) return false;
        for (int i = 0; i < ssr.length; i++) {
            if (ssr[i] != null && ssr[i].update != 0) return true;
        }
        return false;
    }

    /**
     * SSR 轨道/钟差改正数计算，等价 RTKLIB satpos_ssr (ephemeris.c L630-676)。
     *
     * <p>返回 false 表示该星 SSR 改正不可用（RTKLIB 语义为 svh=-1），调用方必须剔除该星，
     * 严禁回退广播星历。输出 deph[0..2]（m，径向/切向/法向）与 dclk[0]（m）。</p>
     *
     * @param maxAge SSR 最大龄期（s），小于等于 0 表示不做龄期判定
     */
    private static boolean ssrCorrection(Ssr s, GTime time, double maxAge,
                                         double[] deph, double[] dclk) {
        if (s == null || s.update == 0) return false;
        if (s.t0[0] == null || s.t0[1] == null) return false;
        if (s.t0[0].time == 0 || s.t0[1].time == 0) return false;
        /* inconsistency between orbit and clock correction */
        if (s.iod[0] != s.iod[1]) return false;

        double t1 = TimeSystem.timediff(time, s.t0[0]);
        double t2 = TimeSystem.timediff(time, s.t0[1]);
        if (maxAge > 0.0 && (Math.abs(t1) > maxAge || Math.abs(t2) > maxAge)) return false;

        /* linear model is referenced to the middle of the update interval */
        if (s.udi[0] >= 1.0) t1 -= s.udi[0] / 2.0;
        if (s.udi[1] >= 1.0) t2 -= s.udi[1] / 2.0;

        for (int i = 0; i < 3; i++) deph[i] = s.deph[i] + s.ddeph[i] * t1;
        dclk[0] = s.dclk[0] + s.dclk[1] * t2 + s.dclk[2] * t2 * t2;

        /* invalid ssr correction */
        double normDeph = Math.sqrt(deph[0] * deph[0] + deph[1] * deph[1] + deph[2] * deph[2]);
        if (normDeph > MAXECORSSR || Math.abs(dclk[0]) > MAXCCORSSR) return false;
        return true;
    }

    public static void applyOrbitCorrection(Ssr[] ssr, double[] rs, Obsd[] obs, int n, double maxAge) {
        double[] deph = new double[3];
        double[] dclk = new double[1];
        for (int i = 0; i < n; i++) {
            int sat = obs[i].sat;
            if (sat <= 0 || sat > Constants.MAXSAT) continue;
            if (!ssrCorrection(ssr[sat - 1], obs[i].time, maxAge, deph, dclk)) continue;

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

            /* rs += -(er*deph[0] + ea*deph[1] + ec*deph[2]); velocity is not corrected */
            for (int j = 0; j < 3; j++) {
                rs[i * 6 + j] -= deph[0] * eR[j] + deph[1] * eA[j] + deph[2] * eC[j];
            }
        }
    }

    public static void applyClockCorrection(Ssr[] ssr, double[] dts, Obsd[] obs, int n, double maxAge) {
        double[] deph = new double[3];
        double[] dclk = new double[1];
        for (int i = 0; i < n; i++) {
            int sat = obs[i].sat;
            if (sat <= 0 || sat > Constants.MAXSAT) continue;
            if (!ssrCorrection(ssr[sat - 1], obs[i].time, maxAge, deph, dclk)) continue;
            dts[i * 2] += dclk[0] / Constants.CLIGHT;
        }
    }

    public static void applyHrClockCorrection(Ssr[] ssr, double[] dts, Obsd[] obs, int n, double maxAge) {
        double[] deph = new double[3];
        double[] dclk = new double[1];
        for (int i = 0; i < n; i++) {
            int sat = obs[i].sat;
            if (sat <= 0 || sat > Constants.MAXSAT) continue;
            Ssr s = ssr[sat - 1];
            if (!ssrCorrection(s, obs[i].time, maxAge, deph, dclk)) continue;
            if (s.iod[0] != s.iod[2] || s.t0[2] == null || s.t0[2].time == 0) continue;
            double t3 = TimeSystem.timediff(obs[i].time, s.t0[2]);
            if (Math.abs(t3) >= MAXAGESSR_HRCLK) continue;
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

    public static boolean hasSsrData(Ssr[] ssr, int sat) {
        if (sat <= 0 || sat > Constants.MAXSAT) return false;
        Ssr s = ssr[sat - 1];
        return s != null && s.update != 0;
    }

    public static boolean isSsrValid(Ssr[] ssr, int sat, GTime time, double maxAge) {
        if (sat <= 0 || sat > Constants.MAXSAT) return false;
        double[] deph = new double[3];
        double[] dclk = new double[1];
        return ssrCorrection(ssr[sat - 1], time, maxAge, deph, dclk);
    }

    public static int getUra(Ssr[] ssr, int sat) {
        if (sat <= 0 || sat > Constants.MAXSAT) return -1;
        Ssr s = ssr[sat - 1];
        if (s == null || s.update == 0) return -1;
        return s.ura;
    }
}