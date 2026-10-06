package org.rtklib.java.research.common;

import org.ejml.simple.SimpleMatrix;

/**
 * WGS84地球模型（移植自FE-GUT earth.h Earth类）。
 *
 * <p>提供重力、子午圈/卯酉圈半径、CNE旋转矩阵、BLH↔ECEF、
 * 局部坐标↔全局坐标等WGS84椭球计算。</p>
 */
public final class EarthModel {
    private EarthModel() {}

    public static final double WIE = 7.2921151467E-5;
    public static final double RA = 6378137.0;
    public static final double RB = 6356752.3142451793;
    public static final double GM0 = 3.986004418E14;
    public static final double E1 = 0.0066943799901413156;
    public static final double E2 = 0.0067394967422764341;
    public static final double FE = 1.0 / 298.257223563;

    public static double gravity(double lat, double h) {
        double sin2 = Math.sin(lat);
        sin2 *= sin2;
        return 9.7803267715 * (1 + 0.0052790414 * sin2 + 0.0000232718 * sin2 * sin2)
                + h * (0.0000000043977311 * sin2 - 0.0000030876910891)
                + 0.0000000000007211 * h * h;
    }

    public static double rn(double lat) {
        double sinlat = Math.sin(lat);
        return RA / Math.sqrt(1.0 - E1 * sinlat * sinlat);
    }

    public static double[] meridianPrimeVerticalRadius(double lat) {
        double tmp = Math.sin(lat);
        tmp *= tmp;
        tmp = 1 - E1 * tmp;
        double sqrttmp = Math.sqrt(tmp);
        double rm = RA * (1 - E1) / (sqrttmp * tmp);
        double rn = RA / sqrttmp;
        return new double[]{rm, rn};
    }

    public static SimpleMatrix cne(double[] blh) {
        double sinlat = Math.sin(blh[0]);
        double coslat = Math.cos(blh[0]);
        double sinlon = Math.sin(blh[1]);
        double coslon = Math.cos(blh[1]);

        SimpleMatrix dcm = new SimpleMatrix(3, 3);
        dcm.set(0, 0, -sinlat * coslon);
        dcm.set(0, 1, -sinlon);
        dcm.set(0, 2, -coslat * coslon);
        dcm.set(1, 0, -sinlat * sinlon);
        dcm.set(1, 1, coslon);
        dcm.set(1, 2, -coslat * sinlon);
        dcm.set(2, 0, coslat);
        dcm.set(2, 1, 0);
        dcm.set(2, 2, -sinlat);
        return dcm;
    }

    public static double[] blh2ecef(double[] blh) {
        double coslat = Math.cos(blh[0]);
        double sinlat = Math.sin(blh[0]);
        double coslon = Math.cos(blh[1]);
        double sinlon = Math.sin(blh[1]);
        double rnVal = rn(blh[0]);
        double rnh = rnVal + blh[2];
        double[] ecef = new double[3];
        ecef[0] = rnh * coslat * coslon;
        ecef[1] = rnh * coslat * sinlon;
        ecef[2] = (rnh - rnVal * E1) * sinlat;
        return ecef;
    }

    public static double[] ecef2blh(double[] ecef) {
        double p = Math.sqrt(ecef[0] * ecef[0] + ecef[1] * ecef[1]);
        double lat, lon, h = 0, h2;
        lat = Math.atan(ecef[2] / (p * (1.0 - E1)));
        lon = 2.0 * Math.atan2(ecef[1], ecef[0] + p);
        do {
            h2 = h;
            double rnVal = rn(lat);
            h = p / Math.cos(lat) - rnVal;
            lat = Math.atan(ecef[2] / (p * (1.0 - E1 * rnVal / (rnVal + h))));
        } while (Math.abs(h - h2) > 1.0e-4);
        return new double[]{lat, lon, h};
    }

    public static SimpleMatrix dri(double[] blh) {
        double[] rmn = meridianPrimeVerticalRadius(blh[0]);
        SimpleMatrix m = new SimpleMatrix(3, 3);
        m.set(0, 0, 1.0 / (rmn[0] + blh[2]));
        m.set(1, 1, 1.0 / ((rmn[1] + blh[2]) * Math.cos(blh[0])));
        m.set(2, 2, -1);
        return m;
    }

    public static SimpleMatrix dr(double[] blh) {
        double[] rmn = meridianPrimeVerticalRadius(blh[0]);
        SimpleMatrix m = new SimpleMatrix(3, 3);
        m.set(0, 0, rmn[0] + blh[2]);
        m.set(1, 1, (rmn[1] + blh[2]) * Math.cos(blh[0]));
        m.set(2, 2, -1);
        return m;
    }

    public static double[] local2global(double[] origin, double[] local) {
        double[] ecef0 = blh2ecef(origin);
        SimpleMatrix cn0e = cne(origin);
        SimpleMatrix localVec = new SimpleMatrix(3, 1);
        for (int i = 0; i < 3; i++) localVec.set(i, 0, local[i]);
        SimpleMatrix ecef1Vec = localVec;
        for (int i = 0; i < 3; i++) ecef1Vec.set(i, 0, ecef0[i] + cn0e.get(i, 0) * local[0] + cn0e.get(i, 1) * local[1] + cn0e.get(i, 2) * local[2]);
        double[] ecef1 = new double[3];
        for (int i = 0; i < 3; i++) ecef1[i] = ecef1Vec.get(i, 0);
        return ecef2blh(ecef1);
    }

    public static double[] global2local(double[] origin, double[] global) {
        double[] ecef0 = blh2ecef(origin);
        double[] ecef1 = blh2ecef(global);
        SimpleMatrix cn0e = cne(origin);
        double[] local = new double[3];
        for (int i = 0; i < 3; i++) {
            local[i] = cn0e.get(0, i) * (ecef1[0] - ecef0[0])
                     + cn0e.get(1, i) * (ecef1[1] - ecef0[1])
                     + cn0e.get(2, i) * (ecef1[2] - ecef0[2]);
        }
        return local;
    }

    public static double[] iewn(double lat) {
        return new double[]{WIE * Math.cos(lat), 0, -WIE * Math.sin(lat)};
    }
}