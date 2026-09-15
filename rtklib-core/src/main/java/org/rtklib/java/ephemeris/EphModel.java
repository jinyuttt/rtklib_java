package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Seph;
import org.rtklib.java.ionosphere.SbasCorrection;
import org.rtklib.java.time.TimeSystem;

/**
 * Satellite position/clock computation from broadcast ephemeris.
 * Aligned with RTKLIB ephemeris.c eph2pos/geph2pos/seph2pos.
 */
public final class EphModel {
    private EphModel() {
        // Utility class
    }

    /** GPS/Galileo/QZS/IRN mu (m^3/s^2) */
    private static final double MU_GPS = 3.9860050E14;
    /** Galileo mu */
    private static final double MU_GAL = 3.986004418E14;
    /** BeiDou mu */
    private static final double MU_CMP = 3.986004418E14;
    /** Earth angular velocity for GPS */
    private static final double OMGE_GPS = 7.2921151467E-5;
    /** Earth angular velocity for Galileo */
    private static final double OMGE_GAL = 7.2921151467E-5;
    /** Earth angular velocity for BeiDou GEO */
    private static final double OMGE_CMP = 7.2921150E-5;
    /** BeiDou GEO constants */
    private static final double SIN_5 = -0.0871557427476582; /* sin(-5.0 deg) */
    private static final double COS_5 =  0.9961946980917456; /* cos(-5.0 deg) */
    /** Kepler iteration max */
    private static final int MAX_ITER_KEPLER = 30;
    /** Kepler tolerance */
    private static final double RTOL_KEPLER = 1E-13;
    /** GLONASS mu */
    private static final double MU_GLO = 3.9860044E14;
    /** Earth angular velocity for GLONASS */
    private static final double OMGE_GLO = 7.292115E-5;
    /** J2 for GLONASS */
    private static final double J2_GLO = 1.0826257E-3;
    /** GLONASS earth radius (m) */
    private static final double RE_GLO = 6378136.0;
    /** Integration step for GLONASS ephemeris (s) */
    private static final double TSTEP = 60.0;
    /** Error of GLONASS ephemeris (m) */
    private static final double ERREPH_GLO = 5.0;
    /** Error of Galileo ephemeris for NAPA (m) */
    private static final double STD_GAL_NAPA = 500.0;
    /** URA table for SVA to variance (m^2) */
    private static final double[] URA_TABLE = {
        2.4, 3.4, 4.85, 6.85, 9.65, 13.65, 24.0, 48.0,
        96.0, 192.0, 384.0, 768.0, 1536.0, 3072.0, 6144.0, 0.0
    };

    public static double lastEphClk = 0.0;

    public static void eph2clk(GTime time, Eph eph) {
        double ts = TimeSystem.timediff(time, eph.toc);
        double t = ts;
        for (int i = 0; i < 2; i++) {
            t = ts - (eph.f0 + eph.f1 * t + eph.f2 * t * t);
        }
        lastEphClk = eph.f0 + eph.f1 * t + eph.f2 * t * t;
    }

    /**
     * Compute satellite position and clock from GPS/GAL/QZS/CMP/IRN ephemeris.
     * @param time Time of computation
     * @param eph  Ephemeris data
     * @param rs   Output position (3) + velocity (3) (m, m/s)
     * @param dts  Output clock bias (s) + clock drift (s/s)
     * @param vare Output position/clock variance (m^2)
     */
    public static void eph2pos(GTime time, Eph eph, double[] rs, double[] dts, double[] vare) {
        if (eph.A <= 0.0) {
            rs[0] = rs[1] = rs[2] = 0.0;
            dts[0] = 0.0;
            vare[0] = 0.0;
            return;
        }
        double tk = TimeSystem.timediff(time, eph.toe);
        int[] prn = new int[1];
        int sys = SatUtils.satsys(eph.sat, prn);
        double mu, omge;
        switch (sys) {
            case Constants.SYS_GAL:
                mu = MU_GAL;
                omge = OMGE_GAL;
                break;
            case Constants.SYS_CMP:
                mu = MU_CMP;
                omge = OMGE_CMP;
                break;
            default:
                mu = MU_GPS;
                omge = OMGE_GPS;
        }
        double M = eph.M0 + (Math.sqrt(mu / (eph.A * eph.A * eph.A)) + eph.deln) * tk;

        // Kepler iteration
        double E = M, Ek = 0.0;
        int n = 0;
        for (n = 0, E = M, Ek = 0.0; Math.abs(E - Ek) > RTOL_KEPLER && n < MAX_ITER_KEPLER; n++) {
            Ek = E;
            E -= (E - eph.e * Math.sin(E) - M) / (1.0 - eph.e * Math.cos(E));
        }
        double sinE = Math.sin(E);
        double cosE = Math.cos(E);
        double u = Math.atan2(Math.sqrt(1.0 - eph.e * eph.e) * sinE, cosE - eph.e) + eph.omg;
        double r = eph.A * (1.0 - eph.e * cosE);
        double i = eph.i0 + eph.idot * tk;
        double sin2u = Math.sin(2.0 * u);
        double cos2u = Math.cos(2.0 * u);
        u += eph.cus * sin2u + eph.cuc * cos2u;
        r += eph.crs * sin2u + eph.crc * cos2u;
        i += eph.cis * sin2u + eph.cic * cos2u;
        double x = r * Math.cos(u);
        double y = r * Math.sin(u);
        double cosi = Math.cos(i);

        if (sys == Constants.SYS_CMP && (prn[0] <= 5 || prn[0] >= 59)) {
            // GEO satellite
            double O = eph.OMG0 + eph.OMGd * tk - omge * eph.toes;
            double sinO = Math.sin(O);
            double cosO = Math.cos(O);
            double xg = x * cosO - y * cosi * sinO;
            double yg = x * sinO + y * cosi * cosO;
            double zg = y * Math.sin(i);
            double sino = Math.sin(omge * tk);
            double coso = Math.cos(omge * tk);
            rs[0] = xg * coso + yg * sino * COS_5 + zg * sino * SIN_5;
            rs[1] = -xg * sino + yg * coso * COS_5 + zg * coso * SIN_5;
            rs[2] = -yg * SIN_5 + zg * COS_5;
        } else {
            double O = eph.OMG0 + (eph.OMGd - omge) * tk - omge * eph.toes;
            double sinO = Math.sin(O);
            double cosO = Math.cos(O);
            rs[0] = x * cosO - y * cosi * sinO;
            rs[1] = x * sinO + y * cosi * cosO;
            rs[2] = y * Math.sin(i);
        }
        tk = TimeSystem.timediff(time, eph.toc);
        dts[0] = eph.f0 + eph.f1 * tk + eph.f2 * tk * tk;
        dts[0] -= 2.0 * Math.sqrt(mu * eph.A) * eph.e * sinE / (Constants.CLIGHT * Constants.CLIGHT);
        vare[0] = varUraeph(sys, eph.sva);
    }

    /**
     * Compute URA variance (m^2) from SVA.
     * @param sys System code
     * @param sva SVA (URA index)
     * @return Variance (m^2)
     */
    public static double varUraeph(int sys, int sva) {
        if (sys == Constants.SYS_GAL) {
            if (sva <= 49) return sva * 0.01 * sva * 0.01;
            if (sva <= 74) { double v = 0.5 + (sva - 50) * 0.02; return v * v; }
            if (sva <= 99) { double v = 1.0 + (sva - 75) * 0.04; return v * v; }
            if (sva <= 125) { double v = 2.0 + (sva - 100) * 0.16; return v * v; }
            return STD_GAL_NAPA * STD_GAL_NAPA;
        }
        if (sva < 0 || sva >= URA_TABLE.length - 1) return 6144.0 * 6144.0;
        double ura = URA_TABLE[sva];
        if (ura <= 0.0) ura = 6144.0;
        return ura * ura;
    }

    /**
     * Dot product of two 3-vectors.
     * @param a First vector
     * @param b Second vector
     * @return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
     */
    private static double dot3(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * GLONASS orbit differential equations.
     * Aligned with RTKLIB ephemeris.c deq().
     * @param x    State vector [pos(3), vel(3)]
     * @param xdot Output derivative [vel(3), acc(3)]
     * @param acc  Acceleration in ECEF (m/s^2)
     */
    private static void deq(double[] x, double[] xdot, double[] acc) {
        double r2 = dot3(x, x);
        double r3 = r2 * Math.sqrt(r2);
        double omg2 = OMGE_GLO * OMGE_GLO;

        if (r2 <= 0.0) {
            xdot[0] = xdot[1] = xdot[2] = xdot[3] = xdot[4] = xdot[5] = 0.0;
            return;
        }
        double a = 1.5 * J2_GLO * MU_GLO * RE_GLO * RE_GLO / r2 / r3;
        double b = 5.0 * x[2] * x[2] / r2;
        double c = -MU_GLO / r3 - a * (1.0 - b);
        xdot[0] = x[3]; xdot[1] = x[4]; xdot[2] = x[5];
        xdot[3] = (c + omg2) * x[0] + 2.0 * OMGE_GLO * x[4] + acc[0];
        xdot[4] = (c + omg2) * x[1] - 2.0 * OMGE_GLO * x[3] + acc[1];
        xdot[5] = (c - 2.0 * a) * x[2] + acc[2];
    }

    /**
     * GLONASS position and velocity by RK4 numerical integration.
     * Aligned with RTKLIB ephemeris.c glorbit().
     * @param t   Integration step (s)
     * @param x   State vector [pos(3), vel(3)] (in/out)
     * @param acc Acceleration in ECEF (m/s^2)
     */
    private static void glorbit(double t, double[] x, double[] acc) {
        double[] k1 = new double[6];
        double[] k2 = new double[6];
        double[] k3 = new double[6];
        double[] k4 = new double[6];
        double[] w = new double[6];

        deq(x, k1, acc);
        for (int i = 0; i < 6; i++) w[i] = x[i] + k1[i] * t / 2.0;
        deq(w, k2, acc);
        for (int i = 0; i < 6; i++) w[i] = x[i] + k2[i] * t / 2.0;
        deq(w, k3, acc);
        for (int i = 0; i < 6; i++) w[i] = x[i] + k3[i] * t;
        deq(w, k4, acc);
        for (int i = 0; i < 6; i++) x[i] += (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i]) * t / 6.0;
    }

    /**
     * Compute GLONASS satellite position and clock bias.
     * Aligned with RTKLIB ephemeris.c geph2pos().
     * @param time Time of computation (GPST)
     * @param geph GLONASS ephemeris
     * @param rs   Output position {x,y,z} (ECEF) (m)
     * @param dts  Output clock bias (s)
     * @param vars Output position and clock variance (m^2)
     */
    public static void geph2pos(GTime time, Geph geph, double[] rs, double[] dts, double[] vars) {
        double t = TimeSystem.timediff(time, geph.toe);
        dts[0] = -geph.taun + geph.gamn * t;

        double[] x = new double[6];
        for (int i = 0; i < 3; i++) {
            x[i] = geph.pos[i];
            x[i + 3] = geph.vel[i];
        }
        for (double tt = t < 0.0 ? -TSTEP : TSTEP; Math.abs(t) > 1E-9; t -= tt) {
            if (Math.abs(t) < TSTEP) tt = t;
            glorbit(tt, x, geph.acc);
        }
        for (int i = 0; i < 3; i++) rs[i] = x[i];

        vars[0] = ERREPH_GLO * ERREPH_GLO;
    }

    /**
     * Compute SBAS satellite position and clock bias.
     * Aligned with RTKLIB ephemeris.c seph2pos().
     * @param time Time of computation (GPST)
     * @param seph SBAS ephemeris
     * @param rs   Output position {x,y,z} (ECEF) (m)
     * @param dts  Output clock bias (s)
     * @param vars Output position and clock variance (m^2)
     */
    public static void seph2pos(GTime time, Seph seph, double[] rs, double[] dts, double[] vars) {
        double t = TimeSystem.timediff(time, seph.t0);

        for (int i = 0; i < 3; i++) {
            rs[i] = seph.pos[i] + seph.vel[i] * t + seph.acc[i] * t * t / 2.0;
        }
        dts[0] = seph.af0 + seph.af1 * t;

        vars[0] = varUraeph(Constants.SYS_SBS, seph.sva);
    }

    /**
     * Compute satellite position and clock bias.
     * Unified interface for all satellite systems.
     * Aligned with RTKLIB ephemeris.c satpos().
     * @param time Time of computation
     * @param nav  Navigation data
     * @param sat  Satellite number
     * @param rs   Output position (3) + velocity (3) (m, m/s)
     * @param dts  Output clock bias (s) + clock drift (s/s)
     * @param vare Output position/clock variance (m^2)
     */
    public static void satpos(GTime time, Nav nav, int sat, double[] rs, double[] dts, double[] vare) {
        int[] prn = new int[1];
        int sys = SatUtils.satsys(sat, prn);

        for (int i = 0; i < 6; i++) rs[i] = 0.0;
        for (int i = 0; i < 2; i++) dts[i] = 0.0;
        vare[0] = 0.0;

        switch (sys) {
            case Constants.SYS_GPS:
            case Constants.SYS_GAL:
            case Constants.SYS_QZS:
            case Constants.SYS_CMP:
            case Constants.SYS_IRN:
                Eph eph = EphModel.searchEphemeris(nav, sat, time);
                if (eph != null) {
                    eph2pos(time, eph, rs, dts, vare);
                }
                break;
            case Constants.SYS_GLO:
                Geph geph = EphModel.searchGloEphemeris(nav, prn[0], time);
                if (geph != null) {
                    geph2pos(time, geph, rs, dts, vare);
                }
                break;
            case Constants.SYS_SBS:
                Seph seph = EphModel.searchSbsEphemeris(nav, sat, time);
                if (seph != null) {
                    seph2pos(time, seph, rs, dts, vare);
                    double[] varc = new double[1];
                    if (SbasCorrection.sbssatcorr(time, sat, nav, rs, dts, varc) != 0) {
                        vare[0] += varc[0];
                    }
                }
                break;
        }
    }

    /**
     * Search ephemeris for GPS/GAL/QZS/CMP/IRN satellite.
     */
    private static Eph searchEphemeris(Nav nav, int sat, GTime time) {
        int[] prn = new int[1];
        SatUtils.satsys(sat, prn);
        if (nav.eph == null) return null;
        double minT = 1E10;
        Eph best = null;
        for (Eph eph : nav.eph) {
            if (eph.sat == sat) {
                double dt = Math.abs(TimeSystem.timediff(time, eph.toe));
                if (dt < minT && dt < 7200.0) {
                    minT = dt;
                    best = eph;
                }
            }
        }
        return best;
    }

    /**
     * Search GLONASS ephemeris.
     */
    private static Geph searchGloEphemeris(Nav nav, int prn, GTime time) {
        if (nav.geph == null || prn < 1 || prn > Constants.MAXSAT) return null;
        Geph geph = nav.geph[prn - 1];
        if (geph != null && geph.sat > 0) {
            double dt = Math.abs(TimeSystem.timediff(time, geph.toe));
            if (dt < 7200.0) return geph;
        }
        return null;
    }

    /**
     * Search SBAS ephemeris.
     */
    private static Seph searchSbsEphemeris(Nav nav, int sat, GTime time) {
        if (nav.seph == null) return null;
        for (Seph seph : nav.seph) {
            if (seph.sat == sat) {
                double dt = Math.abs(TimeSystem.timediff(time, seph.t0));
                if (dt < 3600.0) return seph;
            }
        }
        return null;
    }

    /**
     * Compute satellite positions, velocities and clocks for all observations.
     * SPP通过 - Aligned with RTKLIB ephemeris.c satposs().
     * @param teph Time to select ephemeris (observation time)
     * @param obs  Observation data array
     * @param n    Number of observations
     * @param nav  Navigation data
     * @param rs   Output satellite positions/velocities (6*n)
     * @param dts  Output satellite clocks (2*n)
     * @param vare Output sat position/clock error variances (n)
     * @param svh  Output sat health flags (n)
     */
    public static void satposs(GTime teph, Obsd[] obs, int n, Nav nav,
                               double[] rs, double[] dts, double[] vare, int[] svh) {
        satposs(teph, obs, n, nav, rs, dts, vare, svh, Constants.EPHOPT_BRDC);
    }

    public static void satposs(GTime teph, Obsd[] obs, int n, Nav nav,
                               double[] rs, double[] dts, double[] vare, int[] svh, int ephopt) {
        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            for (int j = 0; j < 6; j++) rs[j + i * 6] = 0.0;
            for (int j = 0; j < 2; j++) dts[j + i * 2] = 0.0;
            vare[i] = 0.0;
            svh[i] = 0;

            double pr = 0.0;
            for (int j = 0; j < Constants.NFREQ; j++) {
                if ((pr = obs[i].P[j]) != 0.0) break;
            }
            if (pr == 0.0) continue;

            GTime time = TimeSystem.timeadd(obs[i].time, -pr / Constants.CLIGHT);

            double[] dtOut = new double[1];
            if (ephopt == Constants.EPHOPT_PREC) {
                double[] varc = new double[1];
                if (Sp3Reader.pephclk(time, obs[i].sat, nav, dtOut, varc) == 0) continue;
            } else {
                if (!ephclk(time, teph, obs[i].sat, nav, dtOut)) continue;
            }

            time = TimeSystem.timeadd(time, -dtOut[0]);

            double[] rsi = new double[6];
            double[] dtsi = new double[2];
            double[] varei = new double[1];
            int[] svhi = new int[1];

            if (ephopt == Constants.EPHOPT_PREC) {
                if (!satposPrec(time, obs[i].sat, nav, rsi, dtsi, varei)) continue;
                svhi[0] = 0;
            } else {
                if (!satposBrdc(time, teph, obs[i].sat, nav, rsi, dtsi, varei, svhi)) continue;
            }

            for (int j = 0; j < 6; j++) rs[j + i * 6] = rsi[j];
            for (int j = 0; j < 2; j++) dts[j + i * 2] = dtsi[j];
            vare[i] = varei[0];
            svh[i] = svhi[0];

            if (dts[i * 2] == 0.0 && ephopt != Constants.EPHOPT_PREC) {
                if (!ephclk(time, teph, obs[i].sat, nav, dtOut)) continue;
                dts[i * 2] = dtOut[0];
                dts[i * 2 + 1] = 0.0;
                vare[i] = Constants.SQR_STD_BRDCCLK;
            }
        }
    }

    private static boolean satposPrec(GTime time, int sat, Nav nav,
                                      double[] rs, double[] dts, double[] vare) {
        double[] rsTmp = new double[3];
        double[] dtsTmp = new double[1];
        double[] vareTmp = new double[1];
        double[] varcTmp = new double[1];

        if (Sp3Reader.pephpos(time, sat, nav, rsTmp, dtsTmp, vareTmp, varcTmp) == 0) return false;

        rs[0] = rsTmp[0];
        rs[1] = rsTmp[1];
        rs[2] = rsTmp[2];
        rs[3] = rs[4] = rs[5] = 0.0;

        double tt = 1E-3;
        GTime time2 = TimeSystem.timeadd(time, tt);
        double[] rs2 = new double[3];
        double[] dts2 = new double[1];
        if (Sp3Reader.pephpos(time2, sat, nav, rs2, dts2, null, null) != 0) {
            for (int i = 0; i < 3; i++) rs[i + 3] = (rs2[i] - rs[i]) / tt;
        }

        if (Sp3Reader.pephclk(time, sat, nav, dtsTmp, varcTmp) != 0) {
            dts[0] = dtsTmp[0];
            double[] dts2c = new double[1];
            GTime time2c = TimeSystem.timeadd(time, tt);
            if (Sp3Reader.pephclk(time2c, sat, nav, dts2c, null) != 0) {
                dts[1] = (dts2c[0] - dts[0]) / tt;
            }
        } else {
            dts[0] = 0.0;
        }

        vare[0] = vareTmp[0];
        return true;
    }

    /**
     * Satellite clock bias by broadcast ephemeris.
     * SPP通过 - Aligned with RTKLIB ephemeris.c ephclk().
     * @return 1=success, 0=failure
     */
    /* [SPP-PASSED] Returns boolean status + output array, matching C version signature.
       Previous version returned clock value directly and used dt==0.0 to detect failure,
       which incorrectly excluded satellites with small but valid clock biases.
       DO NOT revert to return-by-value pattern. */
    private static boolean ephclk(GTime time, GTime teph, int sat, Nav nav, double[] dtOut) {
        int sys = SatUtils.satsys(sat, null);
        if (sys == Constants.SYS_GPS || sys == Constants.SYS_GAL || sys == Constants.SYS_QZS
                || sys == Constants.SYS_CMP || sys == Constants.SYS_IRN) {
            Eph eph = searchEphemeris(nav, sat, teph);
            if (eph == null) return false;
            eph2clk(time, eph);
            dtOut[0] = lastEphClk;
            return true;
        } else if (sys == Constants.SYS_GLO) {
            int[] prn = new int[1];
            SatUtils.satsys(sat, prn);
            Geph geph = searchGloEphemeris(nav, prn[0], teph);
            if (geph == null) return false;
            dtOut[0] = geph2clk(time, geph);
            return true;
        } else if (sys == Constants.SYS_SBS) {
            Seph seph = searchSbsEphemeris(nav, sat, teph);
            if (seph == null) return false;
            dtOut[0] = seph2clk(time, seph);
            return true;
        }
        return false;
    }

    /**
     * GLONASS clock bias.
     * SPP通过 - Aligned with RTKLIB ephemeris.c geph2clk().
     */
    private static double geph2clk(GTime time, Geph geph) {
        double t = TimeSystem.timediff(time, geph.toe);
        return -geph.taun + geph.gamn * t;
    }

    /**
     * SBAS clock bias.
     * SPP通过 - Aligned with RTKLIB ephemeris.c seph2clk().
     */
    private static double seph2clk(GTime time, Seph seph) {
        double t = TimeSystem.timediff(time, seph.t0);
        return seph.af0 + seph.af1 * t;
    }

    /**
     * Satellite position and clock by broadcast ephemeris.
     * SPP通过 - Aligned with RTKLIB ephemeris.c ephpos().
     * Computes position, velocity (by differential), clock and clock drift.
     */
    private static boolean satposBrdc(GTime time, GTime teph, int sat, Nav nav,
                                      double[] rs, double[] dts, double[] vare, int[] svh) {
        int[] prn = new int[1];
        int sys = SatUtils.satsys(sat, prn);
        svh[0] = -1;

        double tt = 1E-3;
        double[] rst = new double[6];
        double[] dtst = new double[2];

        if (sys == Constants.SYS_GPS || sys == Constants.SYS_GAL || sys == Constants.SYS_QZS
                || sys == Constants.SYS_CMP || sys == Constants.SYS_IRN) {
            Eph eph = searchEphemeris(nav, sat, teph);
            if (eph == null) return false;
            eph2pos(time, eph, rs, dts, vare);
            GTime time2 = TimeSystem.timeadd(time, tt);
            eph2pos(time2, eph, rst, dtst, vare);
            svh[0] = eph.svh;
        } else if (sys == Constants.SYS_GLO) {
            Geph geph = searchGloEphemeris(nav, prn[0], teph);
            if (geph == null) return false;
            geph2pos(time, geph, rs, dts, vare);
            GTime time2 = TimeSystem.timeadd(time, tt);
            geph2pos(time2, geph, rst, dtst, vare);
            svh[0] = geph.svh;
        } else if (sys == Constants.SYS_SBS) {
            Seph seph = searchSbsEphemeris(nav, sat, teph);
            if (seph == null) return false;
            seph2pos(time, seph, rs, dts, vare);
            GTime time2 = TimeSystem.timeadd(time, tt);
            seph2pos(time2, seph, rst, dtst, vare);
            svh[0] = seph.svh;
            double[] varc = new double[1];
            if (SbasCorrection.sbssatcorr(time, sat, nav, rs, dts, varc) != 0) {
                vare[0] += varc[0];
            }
        } else {
            return false;
        }

        for (int i = 0; i < 3; i++) rs[i + 3] = (rst[i] - rs[i]) / tt;
        dts[1] = (dtst[0] - dts[0]) / tt;

        return true;
    }
}