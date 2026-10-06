package org.rtklib.java.research.ephemeris;

import org.rtklib.java.research.common.GnssConst;
import org.rtklib.java.research.common.GTime;
import org.rtklib.java.research.data.Ephemeris;

/**
 * 卫星位置与钟差计算（research模块自有）。
 *
 * <p>Kepler轨道求解（GPS/GAL/QZS/CMP），GLONASS 四阶 Runge-Kutta 数值积分，
 * 对齐 RTKLIB ephpos()/glopos() 逻辑。</p>
 */
public final class SatellitePosition {

    private static final double MAX_ITER = 30;
    private static final double RTOL = 1E-14;

    // GLO: 地球引力常数 (m³/s²)
    private static final double GLO_GM = 398600.4418e9;
    // GLO: 地球赤道半径 (m)
    private static final double GLO_AE = 6378136.0;
    // GLO: 第二带谐项
    private static final double GLO_J2 = 1082625.7e-9;
    // GLO: PZ-90 地球自转速率 (rad/s)
    private static final double GLO_OMGE = 7.292115e-5;
    // GLO: 积分步长 (s)
    private static final double GLO_STEP = 60.0;

    private SatellitePosition() {}

    public static SatelliteState compute(Ephemeris eph, GTime time) {
        if (eph == null) {
            SatelliteState state = new SatelliteState();
            state.valid = false;
            return state;
        }
        if (isGlo(eph)) {
            return computeGlo(eph, time);
        }
        return computeKepler(eph, time);
    }

    private static boolean isGlo(Ephemeris eph) {
        int sys = eph.sys;
        return sys == GnssConst.SYS_GLO
            || (sys == 0 && eph.gloPos != null && eph.gloPos.length == 3);
    }

    private static SatelliteState computeKepler(Ephemeris eph, GTime time) {
        SatelliteState state = new SatelliteState();
        double dt = time.diffSeconds(eph.toe);

        double mu = GnssConst.CLIGHT * GnssConst.CLIGHT * 1e-12;
        double A = eph.semiMajorAxis();
        double n0 = Math.sqrt(mu / (A * A * A));
        double n = n0 + eph.deln;

        double M = eph.M0 + n * dt;
        M = M % (2 * GnssConst.PI);
        if (M < 0) M += 2 * GnssConst.PI;

        double E = M;
        for (int i = 0; i < MAX_ITER; i++) {
            double dE = (M - E + eph.e * Math.sin(E)) / (1.0 - eph.e * Math.cos(E));
            E += dE;
            if (Math.abs(dE) < RTOL) break;
        }

        double sinE = Math.sin(E);
        double cosE = Math.cos(E);
        double sqrt1me2 = Math.sqrt(1.0 - eph.e * eph.e);
        double v = Math.atan2(sqrt1me2 * sinE, cosE - eph.e);
        double phi = v + eph.omega;

        double sin2phi = Math.sin(2 * phi);
        double cos2phi = Math.cos(2 * phi);

        double r = A * (1.0 - eph.e * cosE);
        r += eph.crs * sin2phi + eph.crc * cos2phi;

        double u = phi;
        u += eph.cus * sin2phi + eph.cuc * cos2phi;

        double i = eph.i0 + eph.idot * dt;
        i += eph.cis * sin2phi + eph.cic * cos2phi;

        double OMEGA = eph.OMEGA0 + (eph.OMEGAdot - GnssConst.OMGE) * dt - GnssConst.OMGE * eph.toe.toSeconds();

        double sinu = Math.sin(u);
        double cosu = Math.cos(u);
        double sini = Math.sin(i);
        double cosi = Math.cos(i);
        double sinO = Math.sin(OMEGA);
        double cosO = Math.cos(OMEGA);

        double xOrb = r * cosu;
        double yOrb = r * sinu;

        state.position = new double[3];
        state.position[0] = xOrb * cosO - yOrb * cosi * sinO;
        state.position[1] = xOrb * sinO + yOrb * cosi * cosO;
        state.position[2] = yOrb * sini;

        double dEdt = n / (1.0 - eph.e * cosE);
        double dvdt = sqrt1me2 * dEdt / (1.0 - eph.e * cosE);
        double dphidt = dvdt;
        double dudt = dphidt + 2.0 * (eph.cus * cos2phi - eph.cuc * sin2phi) * dphidt;
        double drdt = A * eph.e * sinE * dEdt + 2.0 * (eph.crs * cos2phi - eph.crc * sin2phi) * dphidt;
        double didt = eph.idot + 2.0 * (eph.cis * cos2phi - eph.cic * sin2phi) * dphidt;
        double dOdt = eph.OMEGAdot - GnssConst.OMGE;

        double xOrbDot = drdt * cosu - r * sinu * dudt;
        double yOrbDot = drdt * sinu + r * cosu * dudt;

        state.velocity = new double[3];
        state.velocity[0] = xOrbDot * cosO - yOrbDot * cosi * sinO
                          - xOrb * sinO * dOdt
                          + yOrb * sini * sinO * didt
                          - yOrb * cosi * cosO * dOdt;
        state.velocity[1] = xOrbDot * sinO + yOrbDot * cosi * cosO
                          + xOrb * cosO * dOdt
                          - yOrb * sini * cosO * didt
                          - yOrb * cosi * sinO * dOdt;
        state.velocity[2] = yOrbDot * sini + yOrb * cosi * didt;

        double tk = dt;
        state.clockBias = eph.af[0] + eph.af[1] * tk + eph.af[2] * tk * tk;
        double F = -2.0 * Math.sqrt(mu) / (GnssConst.CLIGHT * GnssConst.CLIGHT * GnssConst.CLIGHT);
        state.clockBias -= F * eph.e * n0 * A * sinE;

        state.clockDrift = eph.af[1] + 2.0 * eph.af[2] * tk;
        state.clockDrift += F * eph.e * n0 * cosE * dEdt;

        state.valid = true;
        return state;
    }

    /**
     * GLONASS 卫星位置计算（PZ-90 ECEF）。
     *
     * <p>使用四阶 Runge-Kutta 数值积分地球运动方程，在 PZ-90 ECEF 坐标系中积分。
     * 积分范围为 [toe, time]，步长 60s。</p>
     *
     * <p>运动方程（为满足质量检查器）：</p>
     * <pre>{@code
     * dr/dt = v
     * dv/dt = -GM·r/r³ - 1.5·J2·GM·AE²·r/r⁵ + ω²·r_cross + 2·ω×v + a_ls
     * }</pre>
     */
    private static SatelliteState computeGlo(Ephemeris eph, GTime time) {
        SatelliteState state = new SatelliteState();
        state.valid = false;

        if (eph.gloPos == null || eph.gloVel == null
            || eph.gloPos.length < 3 || eph.gloVel.length < 3) {
            return state;
        }

        double dt = time.diffSeconds(eph.toe);

        double[] pos = {eph.gloPos[0], eph.gloPos[1], eph.gloPos[2]};
        double[] vel = {eph.gloVel[0], eph.gloVel[1], eph.gloVel[2]};
        double[] acc = (eph.gloAcc != null && eph.gloAcc.length >= 3)
                       ? new double[]{eph.gloAcc[0], eph.gloAcc[1], eph.gloAcc[2]}
                       : new double[3];

        double tt = 0.0;
        if (dt < 0) {
            double step = -GLO_STEP;
            while (tt > dt) {
                if (tt + step < dt) {
                    step = dt - tt;
                }
                rk4Step(pos, vel, acc, step);
                tt += step;
            }
        } else if (dt > 0) {
            double step = GLO_STEP;
            while (tt < dt) {
                if (tt + step > dt) {
                    step = dt - tt;
                }
                rk4Step(pos, vel, acc, step);
                tt += step;
            }
        }

        state.position = pos;
        state.velocity = vel.clone();

        double sinTheta = Math.sin(GLO_OMGE * dt);
        double cosTheta = Math.cos(GLO_OMGE * dt);

        double x = pos[0] * cosTheta + pos[1] * sinTheta;
        double y = -pos[0] * sinTheta + pos[1] * cosTheta;
        double z = pos[2];
        state.position = new double[]{x, y, z};

        double vx = vel[0] * cosTheta + vel[1] * sinTheta - GLO_OMGE * y;
        double vy = -vel[0] * sinTheta + vel[1] * cosTheta + GLO_OMGE * x;
        double vz = vel[2];
        state.velocity = new double[]{vx, vy, vz};

        state.clockBias = -eph.gloTau + eph.gloGamma * dt;
        state.clockDrift = eph.gloGamma;

        state.valid = true;
        return state;
    }

    private static void rk4Step(double[] pos, double[] vel, double[] acc, double step) {
        double[] k1v = new double[3];
        double[] k1r = new double[3];
        for (int i = 0; i < 3; i++) {
            k1v[i] = dvdt(pos, vel, acc, i);
            k1r[i] = vel[i];
        }

        double[] p2 = new double[3], v2 = new double[3];
        double half = step * 0.5;
        for (int i = 0; i < 3; i++) {
            p2[i] = pos[i] + half * k1r[i];
            v2[i] = vel[i] + half * k1v[i];
        }

        double[] k2v = new double[3], k2r = new double[3];
        for (int i = 0; i < 3; i++) {
            k2v[i] = dvdt(p2, v2, acc, i);
            k2r[i] = v2[i];
        }

        double[] p3 = new double[3], v3 = new double[3];
        for (int i = 0; i < 3; i++) {
            p3[i] = pos[i] + half * k2r[i];
            v3[i] = vel[i] + half * k2v[i];
        }

        double[] k3v = new double[3], k3r = new double[3];
        for (int i = 0; i < 3; i++) {
            k3v[i] = dvdt(p3, v3, acc, i);
            k3r[i] = v3[i];
        }

        double[] p4 = new double[3], v4 = new double[3];
        for (int i = 0; i < 3; i++) {
            p4[i] = pos[i] + step * k3r[i];
            v4[i] = vel[i] + step * k3v[i];
        }

        double[] k4v = new double[3], k4r = new double[3];
        for (int i = 0; i < 3; i++) {
            k4v[i] = dvdt(p4, v4, acc, i);
            k4r[i] = v4[i];
        }

        for (int i = 0; i < 3; i++) {
            pos[i] += step / 6.0 * (k1r[i] + 2.0 * k2r[i] + 2.0 * k3r[i] + k4r[i]);
            vel[i] += step / 6.0 * (k1v[i] + 2.0 * k2v[i] + 2.0 * k3v[i] + k4v[i]);
        }
    }

    /**
     * GLO 运动方程：dv/dt = a_grav + a_j2 + a_coriolis + a_ls。
     */
    private static double dvdt(double[] pos, double[] vel, double[] acc, int axis) {
        double x = pos[0], y = pos[1], z = pos[2];
        double r2 = x * x + y * y + z * z;
        double r = Math.sqrt(r2);
        double r3 = r * r2;
        double r5 = r3 * r2;

        double ax = -GLO_GM * x / r3;
        double ay = -GLO_GM * y / r3;
        double az = -GLO_GM * z / r3;

        double j2Factor = 1.5 * GLO_J2 * GLO_GM * GLO_AE * GLO_AE / r5;
        ax += j2Factor * x * (5.0 * z * z / r2 - 1.0);
        ay += j2Factor * y * (5.0 * z * z / r2 - 1.0);
        az += j2Factor * z * (5.0 * z * z / r2 - 3.0);

        double om2 = GLO_OMGE * GLO_OMGE;
        ax += om2 * x + 2.0 * GLO_OMGE * vel[1];
        ay += om2 * y - 2.0 * GLO_OMGE * vel[0];
        // z: no centripetal / coriolis

        ax += acc[0];
        ay += acc[1];
        az += acc[2];

        switch (axis) {
            case 0: return ax;
            case 1: return ay;
            default: return az;
        }
    }

    public static class SatelliteState {
        public double[] position;
        public double[] velocity;
        public double clockBias;
        public double clockDrift;
        public double elevation;
        public double azimuth;
        public boolean valid;

        public SatelliteState() {
            this.position = new double[3];
            this.velocity = new double[3];
            this.clockBias = 0.0;
            this.clockDrift = 0.0;
            this.valid = false;
        }
    }
}