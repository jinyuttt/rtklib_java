package org.rtklib.java.orbit;

public final class OrbitalMechanics {

    private OrbitalMechanics() {}

    private static final double MU = OrbitConstants.MU;
    private static final double TWOPI = OrbitConstants.TWOPI;

    public static OrbitalElements rv2coe(StateVector sv) {
        double[] coe = rv2coe(sv.x, sv.y, sv.z, sv.vx, sv.vy, sv.vz);
        return new OrbitalElements(coe[0], coe[1], coe[2], coe[3], coe[4], coe[5],
                sv.frame, sv.epochJd);
    }

    public static double[] rv2coe(double[] rv) {
        return rv2coe(rv[0], rv[1], rv[2], rv[3], rv[4], rv[5]);
    }

    public static double[] rv2coe(double x, double y, double z,
                                  double vx, double vy, double vz) {
        double hx = y * vz - z * vy;
        double hy = z * vx - x * vz;
        double hz = x * vy - y * vx;
        double hmag2 = hx * hx + hy * hy + hz * hz;
        double hmag = Math.sqrt(hmag2);

        double inc = Math.acos(Math.max(-1.0, Math.min(1.0, hz / hmag)));

        double raan;
        double nx = -hy;
        double ny = hx;
        double nmag = Math.sqrt(nx * nx + ny * ny);
        if (nmag > 1e-15) {
            raan = Math.atan2(ny, nx);
            if (raan < 0.0) raan += TWOPI;
        } else {
            raan = 0.0;
        }

        double r2 = x * x + y * y + z * z;
        double r = Math.sqrt(r2);
        double v2 = vx * vx + vy * vy + vz * vz;
        double rV2OnMu = r * v2 / MU;

        double a = r / (2.0 - rV2OnMu);
        double muA = MU * a;

        double ecc;
        double eccentricAnomaly;

        if (a > 0.0) {
            double rdotv = x * vx + y * vy + z * vz;
            double eSE = rdotv / Math.sqrt(muA);
            double eCE = rV2OnMu - 1.0;
            ecc = Math.sqrt(eSE * eSE + eCE * eCE);
            eccentricAnomaly = Math.atan2(eSE, eCE);
        } else {
            double rdotv = x * vx + y * vy + z * vz;
            double eSH = rdotv / Math.sqrt(-muA);
            double eCH = rV2OnMu - 1.0;
            ecc = Math.sqrt(1.0 - hmag2 / muA);
            eccentricAnomaly = Math.log((eCH + eSH) / (eCH - eSH)) / 2.0;
        }

        double trueAnomaly;
        if (ecc < 1.0) {
            double beta = ecc / (1.0 + Math.sqrt((1.0 - ecc) * (1.0 + ecc)));
            double sinE = Math.sin(eccentricAnomaly);
            double cosE = Math.cos(eccentricAnomaly);
            trueAnomaly = eccentricAnomaly + 2.0 * Math.atan(beta * sinE / (1.0 - beta * cosE));
        } else {
            trueAnomaly = 2.0 * Math.atan(Math.sqrt((ecc + 1.0) / (ecc - 1.0)) * Math.tanh(0.5 * eccentricAnomaly));
        }

        double nodeX = Math.cos(raan);
        double nodeY = Math.sin(raan);

        double px = x * nodeX + y * nodeY;

        double crossX = hy * 0.0 - hz * nodeY;
        double crossY = hz * nodeX - hx * 0.0;
        double crossZ = hx * nodeY - hy * nodeX;
        double crossMag = Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
        double py;
        if (crossMag > 1e-15) {
            py = (x * crossX + y * crossY + z * crossZ) / hmag;
        } else {
            py = 0.0;
        }

        double argp = Math.atan2(py, px) - trueAnomaly;

        double meanAnomaly;
        if (ecc < 1.0) {
            meanAnomaly = eccentricAnomaly - ecc * Math.sin(eccentricAnomaly);
        } else {
            double sinhH = Math.sinh(eccentricAnomaly);
            meanAnomaly = ecc * sinhH - eccentricAnomaly;
        }

        meanAnomaly = meanAnomaly % TWOPI;
        if (meanAnomaly < 0.0) meanAnomaly += TWOPI;
        if (argp < 0.0) argp += TWOPI;

        return new double[]{a, ecc, inc, raan, argp, meanAnomaly};
    }

    public static StateVector coe2rv(OrbitalElements oe) {
        double[] rv = coe2rv(oe.a, oe.e, oe.i, oe.raan, oe.argp, oe.meanAnomaly);
        return new StateVector(rv, oe.frame, oe.epochJd);
    }

    public static double[] coe2rv(double a, double e, double i,
                                  double raan, double argp, double meanAnomaly) {
        double[] pAxis = new double[3];
        double[] qAxis = new double[3];
        referenceAxes(i, argp, raan, pAxis, qAxis);

        if (a > 0.0) {
            double eccentricAnomaly = solveKepler(meanAnomaly, e);

            double uME2 = (1.0 - e) * (1.0 + e);
            double s1Me2 = Math.sqrt(uME2);
            double cosE = Math.cos(eccentricAnomaly);
            double sinE = Math.sin(eccentricAnomaly);

            double xp = a * (cosE - e);
            double yp = a * sinE * s1Me2;
            double factor = Math.sqrt(MU / a) / (1.0 - e * cosE);
            double vxp = -sinE * factor;
            double vyp = cosE * s1Me2 * factor;

            double rx = xp * pAxis[0] + yp * qAxis[0];
            double ry = xp * pAxis[1] + yp * qAxis[1];
            double rz = xp * pAxis[2] + yp * qAxis[2];

            double vx = vxp * pAxis[0] + vyp * qAxis[0];
            double vy = vxp * pAxis[1] + vyp * qAxis[1];
            double vz = vxp * pAxis[2] + vyp * qAxis[2];

            return new double[]{rx, ry, rz, vx, vy, vz};
        } else {
            double trueAnomaly = hyperbolicMeanToTrue(e, meanAnomaly);
            double cosV = Math.cos(trueAnomaly);
            double sinV = Math.sin(trueAnomaly);
            double f = a * (1.0 - e * e);
            double posFactor = f / (1.0 + e * cosV);
            double velFactor = Math.sqrt(MU / f);

            double xp = posFactor * cosV;
            double yp = posFactor * sinV;
            double vxp = -velFactor * sinV;
            double vyp = velFactor * (e + cosV);

            double rx = xp * pAxis[0] + yp * qAxis[0];
            double ry = xp * pAxis[1] + yp * qAxis[1];
            double rz = xp * pAxis[2] + yp * qAxis[2];

            double vx = vxp * pAxis[0] + vyp * qAxis[0];
            double vy = vxp * pAxis[1] + vyp * qAxis[1];
            double vz = vxp * pAxis[2] + vyp * qAxis[2];

            return new double[]{rx, ry, rz, vx, vy, vz};
        }
    }

    private static void referenceAxes(double i, double pa, double raan,
                                       double[] pAxis, double[] qAxis) {
        double cosRaan = Math.cos(raan);
        double sinRaan = Math.sin(raan);
        double cosPa = Math.cos(pa);
        double sinPa = Math.sin(pa);
        double cosI = Math.cos(i);
        double sinI = Math.sin(i);

        double crcp = cosRaan * cosPa;
        double crsp = cosRaan * sinPa;
        double srcp = sinRaan * cosPa;
        double srsp = sinRaan * sinPa;

        pAxis[0] = crcp - cosI * srsp;
        pAxis[1] = srcp + cosI * crsp;
        pAxis[2] = sinI * sinPa;

        qAxis[0] = -crsp - cosI * srcp;
        qAxis[1] = -srsp + cosI * crcp;
        qAxis[2] = sinI * cosPa;
    }

    public static double solveKepler(double m, double e) {
        double reducedM = m % TWOPI;
        if (reducedM < -Math.PI) reducedM += TWOPI;
        if (reducedM > Math.PI) reducedM -= TWOPI;

        double k1 = 3.0 * Math.PI + 2.0;
        double k2 = Math.PI - 1.0;
        double k3 = 6.0 * Math.PI - 1.0;
        double aCoeff = 3.0 * k2 * k2 / k1;
        double bCoeff = k3 * k3 / (6.0 * k1);

        double ea;
        if (Math.abs(reducedM) < 1.0 / 6.0) {
            ea = reducedM + e * (Math.cbrt(6.0 * reducedM) - reducedM);
        } else {
            if (reducedM < 0) {
                double w = Math.PI + reducedM;
                ea = reducedM + e * (aCoeff * w / (bCoeff - w) - Math.PI - reducedM);
            } else {
                double w = Math.PI - reducedM;
                ea = reducedM + e * (Math.PI - aCoeff * w / (bCoeff - w) - reducedM);
            }
        }

        double e1 = 1.0 - e;
        boolean noCancellationRisk = (e1 + ea * ea / 6.0) >= 0.1;

        for (int j = 0; j < 2; j++) {
            double f, fd;
            double sinEa = Math.sin(ea);
            double cosEa = Math.cos(ea);
            double fdd = e * sinEa;
            double fddd = e * cosEa;

            if (noCancellationRisk) {
                f = (ea - fdd) - reducedM;
                fd = 1.0 - fddd;
            } else {
                f = eMeSinE(e, ea) - reducedM;
                double s = Math.sin(0.5 * ea);
                fd = e1 + 2.0 * e * s * s;
            }

            double dee = f * fd / (0.5 * f * fdd - fd * fd);
            double w = fd + 0.5 * dee * (fdd + dee * fddd / 3.0);
            fd += dee * (fdd + 0.5 * dee * fddd);
            ea -= (f - dee * (fd - w)) / fd;
        }

        ea += m - reducedM;
        return ea;
    }

    private static double eMeSinE(double e, double E) {
        double x = (1.0 - e) * Math.sin(E);
        double mE2 = -E * E;
        double term = E;
        double d = 0;
        double x0 = Double.NaN;
        while (!Double.valueOf(x).equals(x0)) {
            d += 2;
            term *= mE2 / (d * (d + 1));
            x0 = x;
            x = x - term;
        }
        return x;
    }

    private static double hyperbolicMeanToTrue(double e, double m) {
        double H = hyperbolicMeanToEccentric(e, m);
        return 2.0 * Math.atan(Math.sqrt((e + 1.0) / (e - 1.0)) * Math.tanh(0.5 * H));
    }

    private static double hyperbolicMeanToEccentric(double e, double m) {
        double L = m / e;
        double g = 1.0 / e;

        if (L == 0.0) return 0.0;

        double cl = Math.sqrt(1.0 + L * L);
        double al = Math.log(L + cl);
        double w = g * g * al / (cl * cl * cl);
        double s = 1.0 - g / cl;
        double S = L + g * al / Math.cbrt(s * s * s + w * L * (1.5 - (4.0 / 3.0) * g));

        for (int i = 0; i < 2; i++) {
            double s0 = S * S;
            double s1 = s0 + 1.0;
            double clS = Math.sqrt(s1);
            double alS = Math.log(S + clS);
            double f = S - g * alS - L;
            double fd = 1.0 - g / clS;
            double fdd = -g * S / (clS * s1);
            double dee = f * fd / (0.5 * f * fdd - fd * fd);
            double w2 = fd + 0.5 * dee * (fdd + dee * (-g / (s1 * clS)) / 3.0);
            fd += dee * (fdd + 0.5 * dee * (-g / (s1 * clS)));
            S -= (f - dee * (fd - w2)) / fd;
        }

        return Math.log(S + Math.sqrt(S * S + 1.0));
    }

    public static double trueAnomalyFromMean(double m, double e) {
        if (e < 1.0) {
            double ea = solveKepler(m, e);
            double beta = e / (1.0 + Math.sqrt((1.0 - e) * (1.0 + e)));
            double sinE = Math.sin(ea);
            double cosE = Math.cos(ea);
            return ea + 2.0 * Math.atan(beta * sinE / (1.0 - beta * cosE));
        } else {
            return hyperbolicMeanToTrue(e, m);
        }
    }

    public static double meanAnomalyFromTrue(double ta, double e) {
        if (e < 1.0) {
            double beta = e / (1.0 + Math.sqrt(1.0 - e * e));
            double sinTa = Math.sin(ta);
            double cosTa = Math.cos(ta);
            double ea = ta - 2.0 * Math.atan(beta * sinTa / (1.0 + beta * cosTa));
            return ea - e * Math.sin(ea);
        } else {
            double sinhH = Math.sqrt(e * e - 1.0) * Math.sin(ta) / (1.0 + e * Math.cos(ta));
            double H = Math.log(sinhH + Math.sqrt(1.0 + sinhH * sinhH));
            return e * sinhH - H;
        }
    }
}