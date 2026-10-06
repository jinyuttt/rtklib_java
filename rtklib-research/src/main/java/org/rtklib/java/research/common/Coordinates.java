package org.rtklib.java.research.common;

/**
 * 坐标转换工具（research模块自有）。
 *
 * <p>ECEF↔LLH、ECEF↔ENU变换，WGS84椭球。</p>
 */
public final class Coordinates {
    private Coordinates() {}

    public static void ecef2llh(double[] r, double[] llh) {
        double e2 = GnssConst.FE_WGS84 * (2.0 - GnssConst.FE_WGS84);
        double r2 = r[0] * r[0] + r[1] * r[1];
        double z = r[2];
        double zk = 0.0;
        double v = GnssConst.RE_WGS84;
        double sinp;
        while (Math.abs(z - zk) >= 1E-4) {
            zk = z;
            sinp = z / Math.sqrt(r2 + z * z);
            v = GnssConst.RE_WGS84 / Math.sqrt(1.0 - e2 * sinp * sinp);
            z = r[2] + v * e2 * sinp;
        }
        llh[0] = r2 > 1E-12 ? Math.atan(z / Math.sqrt(r2)) : (r[2] > 0.0 ? GnssConst.PI / 2.0 : -GnssConst.PI / 2.0);
        llh[1] = r2 > 1E-12 ? Math.atan2(r[1], r[0]) : 0.0;
        llh[2] = Math.sqrt(r2 + z * z) - v;
    }

    public static void llh2ecef(double[] llh, double[] r) {
        double sinp = Math.sin(llh[0]);
        double cosp = Math.cos(llh[0]);
        double sinl = Math.sin(llh[1]);
        double cosl = Math.cos(llh[1]);
        double e2 = GnssConst.FE_WGS84 * (2.0 - GnssConst.FE_WGS84);
        double v = GnssConst.RE_WGS84 / Math.sqrt(1.0 - e2 * sinp * sinp);
        r[0] = (v + llh[2]) * cosp * cosl;
        r[1] = (v + llh[2]) * cosp * sinl;
        r[2] = (v * (1.0 - e2) + llh[2]) * sinp;
    }

    public static void ecef2enu(double[] llh, double[] ecef, double[] enu) {
        double sinp = Math.sin(llh[0]);
        double cosp = Math.cos(llh[0]);
        double sinl = Math.sin(llh[1]);
        double cosl = Math.cos(llh[1]);
        enu[0] = -sinl * ecef[0] + cosl * ecef[1];
        enu[1] = -sinp * cosl * ecef[0] - sinp * sinl * ecef[1] + cosp * ecef[2];
        enu[2] = cosp * cosl * ecef[0] + cosp * sinl * ecef[1] + sinp * ecef[2];
    }

    public static void enu2ecef(double[] llh, double[] enu, double[] ecef) {
        double sinp = Math.sin(llh[0]);
        double cosp = Math.cos(llh[0]);
        double sinl = Math.sin(llh[1]);
        double cosl = Math.cos(llh[1]);
        ecef[0] = -sinl * enu[0] - sinp * cosl * enu[1] + cosp * cosl * enu[2];
        ecef[1] = cosl * enu[0] - sinp * sinl * enu[1] + cosp * sinl * enu[2];
        ecef[2] = cosp * enu[1] + sinp * enu[2];
    }

    public static double elevation(double[] receiverPos, double[] satPos) {
        double[] dr = new double[3];
        for (int i = 0; i < 3; i++) dr[i] = satPos[i] - receiverPos[i];
        double[] llh = new double[3];
        ecef2llh(receiverPos, llh);
        double[] enu = new double[3];
        ecef2enu(llh, dr, enu);
        double r = Math.sqrt(enu[0] * enu[0] + enu[1] * enu[1] + enu[2] * enu[2]);
        return r > 0.0 ? Math.asin(enu[2] / r) : 0.0;
    }

    public static double azimuth(double[] receiverPos, double[] satPos) {
        double[] dr = new double[3];
        for (int i = 0; i < 3; i++) dr[i] = satPos[i] - receiverPos[i];
        double[] llh = new double[3];
        ecef2llh(receiverPos, llh);
        double[] enu = new double[3];
        ecef2enu(llh, dr, enu);
        return Math.atan2(enu[0], enu[1]);
    }

    public static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}