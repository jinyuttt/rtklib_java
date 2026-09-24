package org.rtklib.java.otl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BlqExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(BlqExtractor.class);

    private final List<Fes2004Record> records;
    private final LoveNumberLoader.LoveNumbers love;

    public BlqExtractor() throws IOException {
        this.records = Fes2004Loader.load();
        this.love = LoveNumberLoader.load(BlqConsts.MAX_DEG);
    }

    /**
     * Extract BLQ coefficients at the given station location.
     *
     * @return double[3][11][2]: [component][constituent][0=amplitude_mm, 1=phase_deg]
     *         component 0=radial, 1=west, 2=south
     */
    public double[][][] extract(double latDeg, double lonDeg) {
        double[] rln = geodeticToGeocentric(latDeg, lonDeg, 0.0);
        double rr = rln[0];
        double latGeo = rln[1];
        double lon = rln[2];

        double sinTheta = Math.sin(Math.toRadians(latGeo));
        double cosTheta = Math.cos(Math.toRadians(latGeo));
        double t = sinTheta;
        double aeOverRr = BlqConsts.AE / rr;
        double gr = normalGravityAtSurface(latDeg);

        double fk = 4.0 * Math.PI * BlqConsts.G * BlqConsts.RHO_W / BlqConsts.GE;
        double scale = fk * 1.0e-2 * BlqConsts.GM / (rr * gr);

        double[][] pnmDt = SphericalHarmonic.belPnmDt(BlqConsts.MAX_DEG, t);
        double[] pnm = pnmDt[0];
        double[] dpt1 = pnmDt[1];

        double[][][] result = new double[3][BlqConsts.NUM_CONSTITUENTS][2];

        for (int c = 0; c < BlqConsts.NUM_CONSTITUENTS; c++) {
            double doodson = BlqConsts.DOODSON_NUMBERS[c];
            List<Fes2004Record> matching = new ArrayList<>();
            for (Fes2004Record r : records) {
                if (Math.abs(r.doodson - doodson) < 0.0005) {
                    matching.add(r);
                }
            }

            double[] zEnu = computeComplexSum(matching, lon, aeOverRr, pnm, dpt1);

            double eastRe = scale / cosTheta * zEnu[0];
            double eastIm = scale / cosTheta * zEnu[1];
            double northRe = scale * zEnu[2];
            double northIm = scale * zEnu[3];
            double upRe = scale * zEnu[4];
            double upIm = scale * zEnu[5];

            result[0][c][0] = Math.sqrt(upRe * upRe + upIm * upIm) * 1000.0;
            result[0][c][1] = normalizePhaseDeg(-Math.toDegrees(Math.atan2(upIm, upRe)));

            double westRe = -eastRe;
            double westIm = -eastIm;
            result[1][c][0] = Math.sqrt(westRe * westRe + westIm * westIm) * 1000.0;
            result[1][c][1] = normalizePhaseDeg(-Math.toDegrees(Math.atan2(westIm, westRe)));

            double southRe = -northRe;
            double southIm = -northIm;
            result[2][c][0] = Math.sqrt(southRe * southRe + southIm * southIm) * 1000.0;
            result[2][c][1] = normalizePhaseDeg(-Math.toDegrees(Math.atan2(southIm, southRe)));
        }

        LOG.info("Extracted BLQ coefficients for lat={}, lon={}", latDeg, lonDeg);
        return result;
    }

    /**
     * Compute OTL displacement time series at the given station location.
     * Ported from PRIDE-PPPAR: OLoadDFlu + LTideFlupnm chain.
     *
     * @param latDeg      latitude in degrees
     * @param lonDeg      longitude in degrees
     * @param startMjd    start time in Modified Julian Day
     * @param endMjd      end time in Modified Julian Day
     * @param intervalSec sampling interval in seconds
     * @return double[numEpochs][4]: [mjd, east_mm, north_mm, up_mm]
     */
    public double[][] extractDisplacement(double latDeg, double lonDeg,
                                           double startMjd, double endMjd, double intervalSec) {
        double[] rln = geodeticToGeocentric(latDeg, lonDeg, 0.0);
        double rr = rln[0];
        double latGeo = rln[1];
        double lon = rln[2];

        double sinTheta = Math.sin(Math.toRadians(latGeo));
        double cosTheta = Math.cos(Math.toRadians(latGeo));
        double t = sinTheta;
        double aeOverRr = BlqConsts.AE / rr;
        double gr = normalGravityAtSurface(latDeg);

        double fk = 4.0 * Math.PI * BlqConsts.G * BlqConsts.RHO_W / BlqConsts.GE;

        double[][] pnmDt = SphericalHarmonic.belPnmDt(BlqConsts.MAX_DEG, t);
        double[] pnm = pnmDt[0];
        double[] dpt1 = pnmDt[1];

        int numEpochs = (int) Math.ceil((endMjd - startMjd) * 86400.0 / intervalSec) + 1;
        double[][] result = new double[numEpochs][4];

        for (int ep = 0; ep < numEpochs; ep++) {
            double mjd = startMjd + ep * intervalSec / 86400.0;
            double[] enu = computeDisplacementAtEpoch(mjd, lon, aeOverRr, pnm, dpt1, fk, rr, gr, cosTheta);
            result[ep][0] = mjd;
            result[ep][1] = enu[0] * 1000.0;
            result[ep][2] = enu[1] * 1000.0;
            result[ep][3] = enu[2] * 1000.0;
        }

        LOG.info("Computed OTL displacement time series: {} epochs, lat={}, lon={}", numEpochs, latDeg, lonDeg);
        return result;
    }

    private double[] computeDisplacementAtEpoch(double mjd, double lonDeg, double aeOverRr,
                                                  double[] pnm, double[] dpt1,
                                                  double fk, double rr, double gr, double cosTheta) {
        int size = (BlqConsts.MAX_DEG + 2) * (BlqConsts.MAX_DEG + 2);
        double[] cnm = new double[size];
        double[] snm = new double[size];

        double[] astr = AstroArg.computeAstr(mjd);

        for (Fes2004Record rec : records) {
            int n = rec.n;
            int m = rec.m;
            if (n < 1) continue;

            int k = n * (n + 1) / 2 + m;

            int doodsonInt = (int) Math.round(rec.doodson * 1000.0);
            int[] dod = AstroArg.decodeDoodson(doodsonInt);

            double th1 = 0;
            for (int j = 0; j < 6; j++) {
                th1 += dod[j] * astr[j];
            }

            double[] fu = AstroArg.calcTidefu(mjd, doodsonInt);
            double df = fu[0];
            double du = fu[1];
            double bias = AstroArg.biasTide(doodsonInt);
            double rad = Math.PI / 180.0;
            th1 = th1 + (du + bias) * rad;
            th1 = th1 % (2.0 * Math.PI);

            double cp = rec.cPlus * Math.sin(rec.epsPlus * rad) / (2 * n + 1);
            double sp = rec.cPlus * Math.cos(rec.epsPlus * rad) / (2 * n + 1);
            double cn = rec.cMinus * Math.sin(rec.epsMinus * rad) / (2 * n + 1);
            double sn = rec.cMinus * Math.cos(rec.epsMinus * rad) / (2 * n + 1);

            cnm[k] += ((cp + cn) * Math.cos(th1) + (sp + sn) * Math.sin(th1)) * df;
            snm[k] -= ((cp - cn) * Math.sin(th1) - (sp - sn) * Math.cos(th1)) * df;
        }

        for (int i = 0; i < size; i++) {
            cnm[i] *= fk * 1.0e-2;
            snm[i] *= fk * 1.0e-2;
        }

        double east = 0, north = 0, up = 0;
        for (int n = 1; n <= BlqConsts.MAX_DEG; n++) {
            double dh = love.h[n];
            double dl = love.l[n];
            double tnEast = 0, tnNorth = 0, tnUp = 0;
            for (int m = 0; m <= n; m++) {
                int j = n * (n + 1) / 2 + m;
                double cosml = Math.cos(Math.toRadians(m * lonDeg));
                double sinml = Math.sin(Math.toRadians(m * lonDeg));
                tnEast += m * (cnm[j] * sinml - snm[j] * cosml) * pnm[j + 1] * dl;
                tnNorth += (cnm[j] * cosml + snm[j] * sinml) * dpt1[j + 1] * dl;
                tnUp += (cnm[j] * cosml + snm[j] * sinml) * pnm[j + 1] * dh;
            }
            double ratioN = Math.exp(n * Math.log(aeOverRr));
            tnEast *= ratioN;
            tnNorth *= ratioN;
            tnUp *= ratioN;
            east += tnEast;
            north += tnNorth;
            up += tnUp;
        }

        east = -east / cosTheta * BlqConsts.GM / rr / gr;
        north = -north * BlqConsts.GM / rr / gr;
        up = up * BlqConsts.GM / rr / gr;

        return new double[]{east, north, up};
    }

    /**
     * Compute complex sums for ENU displacement components.
     * Returns [eastRe, eastIm, northRe, northIm, upRe, upIm].
     *
     * The complex sum for each component is:
     *   Z = Σ (C+sinε+ + C-sinε- + i(C+cosε+ + C-cosε-)) · e^(imλ) · (ae/rr)^n · {Pnm·dh or dPnm·dl}
     *
     * East:  Z_east = Σ m·(cnm·sinml - snm·cosml)·Pnm·dl  → complex: i·m·term·Pnm·dl
     * North: Z_north = Σ (cnm·cosml + snm·sinml)·dPnm·dl  → complex: term·dPnm·dl
     * Up:    Z_up = Σ (cnm·cosml + snm·sinml)·Pnm·dh      → complex: term·Pnm·dh
     */
    private double[] computeComplexSum(List<Fes2004Record> records, double lonDeg,
                                        double aeOverRr, double[] pnm, double[] dpt1) {
        double zEastRe = 0, zEastIm = 0;
        double zNorthRe = 0, zNorthIm = 0;
        double zUpRe = 0, zUpIm = 0;

        for (Fes2004Record rec : records) {
            int n = rec.n;
            int m = rec.m;
            if (n < 1 || n > BlqConsts.MAX_DEG) continue;

            double ratioN = Math.pow(aeOverRr, n);
            int j = n * (n + 1) / 2 + m;

            double cosML = Math.cos(Math.toRadians(m * lonDeg));
            double sinML = Math.sin(Math.toRadians(m * lonDeg));

            double epsPlusRad = Math.toRadians(rec.epsPlus);
            double epsMinusRad = Math.toRadians(rec.epsMinus);
            double factor = 1.0 / (2 * n + 1);
            double cp = rec.cPlus * Math.sin(epsPlusRad) * factor;
            double sp = rec.cPlus * Math.cos(epsPlusRad) * factor;
            double cn = rec.cMinus * Math.sin(epsMinusRad) * factor;
            double sn = rec.cMinus * Math.cos(epsMinusRad) * factor;

            double zCnmRe = cp + cn;
            double zCnmIm = -(sp + sn);
            double zSnmRe = sp - sn;
            double zSnmIm = cp - cn;

            double zRe = zCnmRe * cosML + zSnmRe * sinML;
            double zIm = zCnmIm * cosML + zSnmIm * sinML;

            double dh = love.h[n];
            double dl = love.l[n];
            double pVal = pnm[j + 1];
            double dpVal = dpt1[j + 1];

            double upRe = zRe * pVal * dh;
            double upIm = zIm * pVal * dh;
            double northRe = zRe * dpVal * dl;
            double northIm = zIm * dpVal * dl;
            double eastRe = -m * zIm * pVal * dl;
            double eastIm = m * zRe * pVal * dl;

            zUpRe += ratioN * upRe;
            zUpIm += ratioN * upIm;
            zNorthRe += ratioN * northRe;
            zNorthIm += ratioN * northIm;
            zEastRe += ratioN * eastRe;
            zEastIm += ratioN * eastIm;
        }

        return new double[]{zEastRe, zEastIm, zNorthRe, zNorthIm, zUpRe, zUpIm};
    }

    private static double[] geodeticToGeocentric(double latDeg, double lonDeg, double heightM) {
        double a = 6378137.0;
        double f = 1.0 / 298.25641153;
        double e2 = (2.0 - f) * f;
        double latRad = Math.toRadians(latDeg);
        double lonRad = Math.toRadians(lonDeg);
        double sinLat = Math.sin(latRad);
        double cosLat = Math.cos(latRad);
        double nn = a / Math.sqrt(1.0 - e2 * sinLat * sinLat);
        double x = (nn + heightM) * cosLat * Math.cos(lonRad);
        double y = (nn + heightM) * cosLat * Math.sin(lonRad);
        double z = (nn * (1.0 - e2) + heightM) * sinLat;
        double re = Math.sqrt(x * x + y * y + z * z);
        double latGeo = Math.toDegrees(Math.atan(z / Math.sqrt(x * x + y * y)));
        return new double[]{re, latGeo, lonDeg};
    }

    private static double normalGravityAtSurface(double latDeg) {
        double sinLat2 = Math.sin(Math.toRadians(latDeg));
        sinLat2 = sinLat2 * sinLat2;
        double k = 1.931851353e-3;
        double e2 = 6.69437999014e-3;
        double gEq = 9.7803278;
        return gEq * (1.0 + k * sinLat2) / Math.sqrt(1.0 - e2 * sinLat2);
    }

    private static double normalizePhaseDeg(double phase) {
        phase = phase % 360.0;
        if (phase < 0) phase += 360.0;
        return phase;
    }
}
