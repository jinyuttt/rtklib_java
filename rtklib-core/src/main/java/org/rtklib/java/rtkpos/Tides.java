package org.rtklib.java.rtkpos;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Erp;
import org.rtklib.java.data.GTime;
import org.rtklib.java.time.TimeSystem;

/**
 * 固体潮位移修正。
 *
 * <p>移植自 RTKLIB tides.c 中的 dehanttideinel() 及 rtkcmn.c 中的
 * sunmoonpos() / eci2ecef() / sunpos_eci() / moonpos_eci() 函数。</p>
 *
 * <p>参考：</p>
 * <ul>
 *   <li>IERS Conventions 2003, 2010</li>
 *   <li>D.A.Vallado, Fundamentals of Astrodynamics and Applications 2nd ed</li>
 *   <li>J.Kouba, A Guide to using International GNSS Service (IGS) products</li>
 * </ul>
 */
public class Tides {

    private static final double[] EP2000 = {2000, 1, 1, 12, 0, 0};

    /* ======== 矩阵运算（行优先，row-major，与项目 MatrixUtil 一致） ======== */

    /** 向量内积 */
    private static double dot(double[] a, double[] b, int n) {
        double c = 0.0;
        for (int i = 0; i < n; i++) c += a[i] * b[i];
        return c;
    }

    /** 向量范数 */
    private static double norm(double[] a, int n) {
        double s = 0.0;
        for (int i = 0; i < n; i++) s += a[i] * a[i];
        return Math.sqrt(s);
    }

    /** 绕 X 轴旋转矩阵（行优先：M[i*3+j] = 第i行第j列） */
    private static void Rx(double t, double[] X) {
        double ct = Math.cos(t), st = Math.sin(t);
        X[0] = 1.0; X[1] = 0.0; X[2] = 0.0;
        X[3] = 0.0; X[4] = ct;  X[5] = -st;
        X[6] = 0.0; X[7] = st;  X[8] = ct;
    }

    /** 绕 Y 轴旋转矩阵（行优先：M[i*3+j] = 第i行第j列） */
    private static void Ry(double t, double[] X) {
        double ct = Math.cos(t), st = Math.sin(t);
        X[0] = ct;  X[1] = 0.0; X[2] = st;
        X[3] = 0.0; X[4] = 1.0; X[5] = 0.0;
        X[6] = -st; X[7] = 0.0; X[8] = ct;
    }

    /** 绕 Z 轴旋转矩阵（行优先：M[i*3+j] = 第i行第j列） */
    private static void Rz(double t, double[] X) {
        double ct = Math.cos(t), st = Math.sin(t);
        X[0] = ct;  X[1] = -st; X[2] = 0.0;
        X[3] = st;  X[4] = ct;  X[5] = 0.0;
        X[6] = 0.0; X[7] = 0.0; X[8] = 1.0;
    }

    /**
     * 矩阵乘法 C = A * B（行优先，row-major）。
     * @param tr "NN"=正常, "NT"=A * B^T, "TN"=A^T * B, "TT"=A^T * B^T
     */
    private static void matmul(String tr, int n, int k, int m,
                               double[] A, double[] B, double[] C) {
        int f = (tr.charAt(0) != 'N' ? 2 : 0) + (tr.charAt(1) != 'N' ? 1 : 0);
        switch (f) {
            case 0: /* NN: C[i*k+j] = sum_x A[i*m+x] * B[x*k+j] */
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < k; j++) {
                        double d = 0.0;
                        for (int x = 0; x < m; x++) d += A[i * m + x] * B[x * k + j];
                        C[i * k + j] = d;
                    }
                }
                break;
            case 1: /* NT: C[i*k+j] = sum_x A[i*m+x] * B[j*m+x] */
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < k; j++) {
                        double d = 0.0;
                        for (int x = 0; x < m; x++) d += A[i * m + x] * B[j * m + x];
                        C[i * k + j] = d;
                    }
                }
                break;
            case 2: /* TN: C[i*k+j] = sum_x A[x*m+i] * B[x*k+j] */
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < k; j++) {
                        double d = 0.0;
                        for (int x = 0; x < m; x++) d += A[x * m + i] * B[x * k + j];
                        C[i * k + j] = d;
                    }
                }
                break;
            case 3: /* TT: C[i*k+j] = sum_x A[x*m+i] * B[j*m+x] */
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < k; j++) {
                        double d = 0.0;
                        for (int x = 0; x < m; x++) d += A[x * m + i] * B[j * m + x];
                        C[i * k + j] = d;
                    }
                }
                break;
        }
    }

    /* ======== ERP 插值 ======== */

    /**
     * 获取指定时间的 ERP 值。
     * @return 成功返回 1，无 ERP 数据返回 0
     */
    public static int geterp(Erp erp, GTime time, double[] erpv) {
        if (erp == null || erp.n <= 0) return 0;

        double mjd = 51544.5 + TimeSystem.timediff(TimeSystem.gpst2utc(time),
                        TimeSystem.epoch2time(EP2000)) / 86400.0;

        if (mjd <= erp.data[0].mjd) {
            double day = mjd - erp.data[0].mjd;
            erpv[0] = erp.data[0].xp + erp.data[0].xpr * day;
            erpv[1] = erp.data[0].yp + erp.data[0].ypr * day;
            erpv[2] = erp.data[0].ut1_utc - erp.data[0].lod * day;
            erpv[3] = erp.data[0].lod;
            return 1;
        }
        int last = erp.n - 1;
        if (mjd >= erp.data[last].mjd) {
            double day = mjd - erp.data[last].mjd;
            erpv[0] = erp.data[last].xp + erp.data[last].xpr * day;
            erpv[1] = erp.data[last].yp + erp.data[last].ypr * day;
            erpv[2] = erp.data[last].ut1_utc - erp.data[last].lod * day;
            erpv[3] = erp.data[last].lod;
            return 1;
        }

        int j = 0, k = last;
        for (; j < k - 1;) {
            int i = (j + k) / 2;
            if (mjd < erp.data[i].mjd) k = i; else j = i;
        }
        double a = (erp.data[j].mjd == erp.data[j + 1].mjd)
                ? 0.5 : (mjd - erp.data[j].mjd) / (erp.data[j + 1].mjd - erp.data[j].mjd);
        erpv[0] = (1.0 - a) * erp.data[j].xp + a * erp.data[j + 1].xp;
        erpv[1] = (1.0 - a) * erp.data[j].yp + a * erp.data[j + 1].yp;
        erpv[2] = (1.0 - a) * erp.data[j].ut1_utc + a * erp.data[j + 1].ut1_utc;
        erpv[3] = (1.0 - a) * erp.data[j].lod + a * erp.data[j + 1].lod;
        return 1;
    }

    /* ======== ECI 坐标计算 ======== */

    /** 太阳在 ECI 中的位置 */
    private static void sunpos_eci(GTime tutc, double[] erpv, double[] rsun) {
        GTime tut = TimeSystem.timeadd(tutc, erpv[2]);
        double t = TimeSystem.timediff(tut, TimeSystem.epoch2time(EP2000)) / 86400.0 / 36525.0;

        double[] f = new double[5];
        TimeSystem.astArgs(t, f);

        double eps = 23.439291 - 0.0130042 * t;
        double sine = Math.sin(eps * Constants.D2R);
        double cose = Math.cos(eps * Constants.D2R);

        double Ms = 357.5277233 + 35999.05034 * t;
        double ls = 280.460 + 36000.770 * t + 1.914666471 * Math.sin(Ms * Constants.D2R)
                + 0.019994643 * Math.sin(2.0 * Ms * Constants.D2R);
        double rs = Constants.AU * (1.000140612 - 0.016708617 * Math.cos(Ms * Constants.D2R)
                - 0.000139589 * Math.cos(2.0 * Ms * Constants.D2R));
        double sinl = Math.sin(ls * Constants.D2R);
        double cosl = Math.cos(ls * Constants.D2R);
        rsun[0] = rs * cosl;
        rsun[1] = rs * cose * sinl;
        rsun[2] = rs * sine * sinl;
    }

    /** 月球在 ECI 中的位置 */
    private static void moonpos_eci(GTime tutc, double[] erpv, double[] rmoon) {
        GTime tut = TimeSystem.timeadd(tutc, erpv[2]);
        double t = TimeSystem.timediff(tut, TimeSystem.epoch2time(EP2000)) / 86400.0 / 36525.0;

        double[] f = new double[5];
        TimeSystem.astArgs(t, f);

        double eps = 23.439291 - 0.0130042 * t;
        double sine = Math.sin(eps * Constants.D2R);
        double cose = Math.cos(eps * Constants.D2R);

        double lm = 218.32 + 481267.883 * t + 6.29 * Math.sin(f[0])
                - 1.27 * Math.sin(f[0] - 2.0 * f[3]) + 0.66 * Math.sin(2.0 * f[3])
                + 0.21 * Math.sin(2.0 * f[0]) - 0.19 * Math.sin(f[1])
                - 0.11 * Math.sin(2.0 * f[2]);
        double pm = 5.13 * Math.sin(f[2]) + 0.28 * Math.sin(f[0] + f[2])
                - 0.28 * Math.sin(f[2] - f[0]) - 0.17 * Math.sin(f[2] - 2.0 * f[3]);
        double rm = Constants.RE_WGS84 / Math.sin((
                0.9508 + 0.0518 * Math.cos(f[0]) + 0.0095 * Math.cos(f[0] - 2.0 * f[3])
                + 0.0078 * Math.cos(2.0 * f[3]) + 0.0028 * Math.cos(2.0 * f[0])) * Constants.D2R);
        double sinl = Math.sin(lm * Constants.D2R);
        double cosl = Math.cos(lm * Constants.D2R);
        double sinp = Math.sin(pm * Constants.D2R);
        double cosp = Math.cos(pm * Constants.D2R);
        rmoon[0] = rm * cosp * cosl;
        rmoon[1] = rm * (cose * cosp * sinl - sine * sinp);
        rmoon[2] = rm * (sine * cosp * sinl + cose * sinp);
    }

    /**
     * ECI 到 ECEF 坐标转换。
     * @param U    Output: 3x3 转换矩阵（列优先）
     * @param gmst Output: GMST (rad)
     */
    public static void eci2ecef(GTime tutc, double[] erpv, double[] U, double[] gmst) {
        GTime tgps = TimeSystem.utc2gpst(tutc);
        double t = (TimeSystem.timediff(tgps, TimeSystem.epoch2time(EP2000)) + 19.0 + 32.184)
                / 86400.0 / 36525.0;
        double t2 = t * t, t3 = t2 * t;

        double[] f = new double[5];
        TimeSystem.astArgs(t, f);

        /* IAU 1976 precession */
        double ze = (2306.2181 * t + 0.30188 * t2 + 0.017998 * t3) * Constants.AS2R;
        double th = (2004.3109 * t - 0.42665 * t2 - 0.041833 * t3) * Constants.AS2R;
        double z  = (2306.2181 * t + 1.09468 * t2 + 0.018203 * t3) * Constants.AS2R;
        double eps = (84381.448 - 46.8150 * t - 0.00059 * t2 + 0.001813 * t3) * Constants.AS2R;

        double[] R1 = new double[9], R2 = new double[9], R3 = new double[9];
        double[] R = new double[9], P = new double[9];
        Rz(-z, R1); Ry(th, R2); Rz(-ze, R3);
        matmul("NN", 3, 3, 3, R1, R2, R);
        matmul("NN", 3, 3, 3, R, R3, P);  /* P = Rz(-z) * Ry(th) * Rz(-ze) */

        /* IAU 1980 nutation */
        double[] dpsi = new double[1], deps = new double[1];
        TimeSystem.nutIau1980(t, f, dpsi, deps);
        Rx(-eps - deps[0], R1); Rz(-dpsi[0], R2); Rx(eps, R3);
        double[] N = new double[9];
        matmul("NN", 3, 3, 3, R1, R2, R);
        matmul("NN", 3, 3, 3, R, R3, N);  /* N = Rx(-eps) * Rz(-dpsi) * Rx(eps) */

        /* Greenwich apparent sidereal time */
        double gmst_ = TimeSystem.utc2gmst(tutc, erpv[2]);
        double gast = gmst_ + dpsi[0] * Math.cos(eps);
        gast += (0.00264 * Math.sin(f[4]) + 0.000063 * Math.sin(2.0 * f[4])) * Constants.AS2R;

        /* ECI to ECEF transformation matrix */
        double[] W = new double[9];
        Ry(-erpv[0], R1); Rx(-erpv[1], R2); Rz(gast, R3);
        matmul("NN", 3, 3, 3, R1, R2, W);
        matmul("NN", 3, 3, 3, W, R3, R);  /* W = Ry(-xp) * Rx(-yp) */

        double[] NP = new double[9];
        matmul("NN", 3, 3, 3, N, P, NP);
        matmul("NN", 3, 3, 3, R, NP, U);  /* U = W * Rz(gast) * N * P */

        if (gmst != null) gmst[0] = gmst_;
    }

    /* ======== 太阳/月球 ECEF 位置 ======== */

    /**
     * 获取太阳和月球在 ECEF 坐标系中的位置。
     * @param tutc  UTC 时间
     * @param erpv  ERP 值数组 {xp, yp, ut1_utc, lod}
     */
    public static void sunmoonpos(GTime tutc, double[] erpv, double[] rsun, double[] rmoon,
                                  double[] gmst) {
        double[] U = new double[9];
        double gmst_ = 0;
        eci2ecef(tutc, erpv, U, new double[]{gmst_});
        if (gmst != null) gmst[0] = gmst_;

        if (rsun != null) {
            double[] rs = new double[3];
            sunpos_eci(tutc, erpv, rs);
            matmul("NN", 3, 1, 3, U, rs, rsun);
        }
        if (rmoon != null) {
            double[] rm = new double[3];
            moonpos_eci(tutc, erpv, rm);
            matmul("NN", 3, 1, 3, U, rm, rmoon);
        }
    }

    /* ======== Dehanttideinel 模型辅助函数 ======== */

    /**
     * 长周期频段的地幔非弹性修正（同相和异相）。
     */
    private static void step2lon_(double[] xsta, double t, double[] xcorsta) {
        final double[][] datdi = {
            {0, 0, 0, 1, 0, 0.47, 0.23, 0.16, 0.07},
            {0, 2, 0, 0, 0, -0.2, -0.12, -0.11, -0.05},
            {1, 0, -1, 0, 0, -0.11, -0.08, -0.09, -0.04},
            {2, 0, 0, 0, 0, -0.13, -0.11, -0.15, -0.07},
            {2, 0, 0, 1, 0, -0.05, -0.05, -0.06, -0.03}
        };

        double s = ((t * 1.85139e-6 - 0.0014663889) * t + 481267.88194) * t + 218.31664563;
        double pr = (((t * 7e-9 + 2.1e-8) * t + 3.08889e-4) * t + 1.396971278) * t;
        s += pr;
        double h = (((t * -6.54e-9 + 2e-8) * t + 3.0322222e-4) * t + 36000.7697489) * t + 280.46645;
        double p = (((t * 5.263e-8 - 1.24991e-5) * t - 0.01032172222) * t + 4069.01363525) * t
                + 83.35324312;
        double zns = (((t * 1.65e-8 - 2.13944e-6) * t - 0.00207561111) * t + 1934.13626197) * t
                + 234.95544499;
        double ps = (((t * -3.34e-9 - 1.778e-8) * t + 4.5688889e-4) * t + 1.71945766667) * t
                + 282.93734098;

        double rsta = norm(xsta, 3);
        double sinphi = xsta[2] / rsta;
        double cosphi = Math.sqrt(xsta[0] * xsta[0] + xsta[1] * xsta[1]) / rsta;
        double cosla = xsta[0] / cosphi / rsta;
        double sinla = xsta[1] / cosphi / rsta;

        s = s % 360.0;
        h = h % 360.0;
        p = p % 360.0;
        zns = zns % 360.0;
        ps = ps % 360.0;

        for (int i = 0; i < 3; i++) xcorsta[i] = 0.0;
        for (int j = 0; j < 5; j++) {
            double thetaf = (datdi[j][0] * s + datdi[j][1] * h + datdi[j][2] * p
                    + datdi[j][3] * zns + datdi[j][4] * ps) * Constants.D2R;
            double dr = datdi[j][5] * (3.0 * sinphi * sinphi - 1.0) / 2.0 * Math.cos(thetaf)
                    + datdi[j][7] * (3.0 * sinphi * sinphi - 1.0) / 2.0 * Math.sin(thetaf);
            double dn = datdi[j][6] * (2.0 * cosphi * sinphi) * Math.cos(thetaf)
                    + datdi[j][8] * (2.0 * cosphi * sinphi) * Math.sin(thetaf);
            double de = 0.0;
            xcorsta[0] += dr * cosla * cosphi - de * sinla - dn * sinphi * cosla;
            xcorsta[1] += dr * sinla * cosphi + de * cosla - dn * sinphi * sinla;
            xcorsta[2] += dr * sinphi + dn * cosphi;
        }
        for (int i = 0; i < 3; i++) xcorsta[i] /= 1e3;
    }

    /**
     * 周日频段的地幔非弹性修正（同相和异相）。
     */
    private static void step2diu_(double[] xsta, double fhr, double t, double[] xcorsta) {
        final double[][] datdi = {
            {-3, 0, 2, 0, 0, -0.01, 0, 0, 0},
            {-3, 2, 0, 0, 0, -0.01, 0, 0, 0},
            {-2, 0, 1, -1, 0, -0.02, 0, 0, 0},
            {-2, 0, 1, 0, 0, -0.08, 0, -0.01, 0.01},
            {-2, 2, -1, 0, 0, -0.02, 0, 0, 0},
            {-1, 0, 0, -1, 0, -0.10, 0, 0, 0},
            {-1, 0, 0, 0, 0, -0.51, 0, -0.02, 0.03},
            {-1, 2, 0, 0, 0, 0.01, 0, 0, 0},
            {0, -2, 1, 0, 0, 0.01, 0, 0, 0},
            {0, 0, -1, 0, 0, 0.02, 0, 0, 0},
            {0, 0, 1, 0, 0, 0.06, 0, 0, 0},
            {0, 0, 1, 1, 0, 0.01, 0, 0, 0},
            {0, 2, -1, 0, 0, 0.01, 0, 0, 0},
            {1, -3, 0, 0, 1, -0.06, 0, 0, 0},
            {1, -2, 0, -1, 0, 0.01, 0, 0, 0},
            {1, -2, 0, 0, 0, -1.23, -0.07, 0.06, 0.01},
            {1, -1, 0, 0, -1, 0.02, 0, 0, 0},
            {1, -1, 0, 0, 1, 0.04, 0, 0, 0},
            {1, 0, 0, -1, 0, -0.22, 0.01, 0.01, 0},
            {1, 0, 0, 0, 0, 12.00, -0.80, -0.67, -0.03},
            {1, 0, 0, 1, 0, 1.73, -0.12, -0.10, 0},
            {1, 0, 0, 2, 0, -0.04, 0, 0, 0},
            {1, 1, 0, 0, -1, -0.50, -0.01, 0.03, 0},
            {1, 1, 0, 0, 1, 0.01, 0, 0, 0},
            {0, 1, 0, 1, -1, -0.01, 0, 0, 0},
            {1, 2, -2, 0, 0, -0.01, 0, 0, 0},
            {1, 2, 0, 0, 0, -0.11, 0.01, 0.01, 0},
            {2, -2, 1, 0, 0, -0.01, 0, 0, 0},
            {2, 0, -1, 0, 0, -0.02, 0, 0, 0},
            {3, 0, 0, 0, 0, 0, 0, 0, 0},
            {3, 0, 0, 1, 0, 0, 0, 0, 0}
        };

        double s = ((t * 1.85139e-6 - 0.0014663889) * t + 481267.88194) * t + 218.31664563;
        double tau = fhr * 15.0 + 280.4606184
                + (((t * -2.58e-8 + 3.8793e-4) * t + 36000.7700536) * t) + (-s);
        double pr = (((t * 7e-9 + 2.1e-8) * t + 3.08889e-4) * t + 1.396971278) * t;
        s += pr;
        double h__ = (((t * -6.54e-9 + 2e-8) * t + 3.0322222e-4) * t + 36000.7697489) * t
                + 280.46645;
        double p = (((t * 5.263e-8 - 1.24991e-5) * t - 0.01032172222) * t + 4069.01363525) * t
                + 83.35324312;
        double zns = (((t * 1.65e-8 - 2.13944e-6) * t - 0.00207561111) * t + 1934.13626197) * t
                + 234.95544499;
        double ps = (((t * -3.34e-9 - 1.778e-8) * t + 4.5688889e-4) * t + 1.71945766667) * t
                + 282.93734098;

        s = s % 360.0;
        tau = tau % 360.0;
        h__ = h__ % 360.0;
        p = p % 360.0;
        zns = zns % 360.0;
        ps = ps % 360.0;

        double rsta = norm(xsta, 3);
        double sinphi = xsta[2] / rsta;
        double cosphi = Math.sqrt(xsta[0] * xsta[0] + xsta[1] * xsta[1]) / rsta;
        double cosla = xsta[0] / cosphi / rsta;
        double sinla = xsta[1] / cosphi / rsta;
        double zla = Math.atan2(xsta[1], xsta[0]);

        for (int i = 0; i < 3; i++) xcorsta[i] = 0.0;
        for (int j = 0; j < 31; j++) {
            double thetaf = (tau + datdi[j][0] * s + datdi[j][1] * h__ + datdi[j][2] * p
                    + datdi[j][3] * zns + datdi[j][4] * ps) * Constants.D2R;
            double dr = datdi[j][5] * 2.0 * sinphi * cosphi * Math.sin(thetaf + zla)
                    + datdi[j][6] * 2.0 * sinphi * cosphi * Math.cos(thetaf + zla);
            double dn = datdi[j][7] * (cosphi * cosphi - sinphi * sinphi) * Math.sin(thetaf + zla)
                    + datdi[j][8] * (cosphi * cosphi - sinphi * sinphi) * Math.cos(thetaf + zla);
            double de = datdi[j][7] * sinphi * Math.cos(thetaf + zla)
                    - datdi[j][8] * sinphi * Math.sin(thetaf + zla);
            xcorsta[0] += dr * cosla * cosphi - de * sinla - dn * sinphi * cosla;
            xcorsta[1] += dr * sinla * cosphi + de * cosla - dn * sinphi * sinla;
            xcorsta[2] += dr * sinphi + dn * cosphi;
        }
        for (int i = 0; i < 3; i++) xcorsta[i] /= 1e3;
    }

    /**
     * 周日频段异相地幔非弹性修正。
     */
    private static void st1idiu_(double[] xsta, double[] xsun, double[] xmon,
                                 double fac2sun, double fac2mon, double[] xcorsta) {
        final double dhi = -0.0025, dli = -7e-4;

        double rsta = norm(xsta, 3);
        double sinphi = xsta[2] / rsta;
        double cosphi = Math.sqrt(xsta[0] * xsta[0] + xsta[1] * xsta[1]) / rsta;
        double cos2phi = cosphi * cosphi - sinphi * sinphi;
        double sinla = xsta[1] / cosphi / rsta;
        double cosla = xsta[0] / cosphi / rsta;

        double rmon = norm(xmon, 3);
        double rsun = norm(xsun, 3);

        double drsun = dhi * -3.0 * sinphi * cosphi * fac2sun * xsun[2]
                * (xsun[0] * sinla - xsun[1] * cosla) / (rsun * rsun);
        double drmon = dhi * -3.0 * sinphi * cosphi * fac2mon * xmon[2]
                * (xmon[0] * sinla - xmon[1] * cosla) / (rmon * rmon);
        double dnsun = dli * -3.0 * cos2phi * fac2sun * xsun[2]
                * (xsun[0] * sinla - xsun[1] * cosla) / (rsun * rsun);
        double dnmon = dli * -3.0 * cos2phi * fac2mon * xmon[2]
                * (xmon[0] * sinla - xmon[1] * cosla) / (rmon * rmon);
        double desun = dli * -3.0 * sinphi * fac2sun * xsun[2]
                * (xsun[0] * cosla + xsun[1] * sinla) / (rsun * rsun);
        double demon = dli * -3.0 * sinphi * fac2mon * xmon[2]
                * (xmon[0] * cosla + xmon[1] * sinla) / (rmon * rmon);

        double dr = drsun + drmon;
        double dn = dnsun + dnmon;
        double de = desun + demon;

        xcorsta[0] = dr * cosla * cosphi - de * sinla - dn * sinphi * cosla;
        xcorsta[1] = dr * sinla * cosphi + de * cosla - dn * sinphi * sinla;
        xcorsta[2] = dr * sinphi + dn * cosphi;
    }

    /**
     * 半日频段异相地幔非弹性修正。
     */
    private static void st1isem_(double[] xsta, double[] xsun, double[] xmon,
                                 double fac2sun, double fac2mon, double[] xcorsta) {
        final double dhi = -0.0022, dli = -7e-4;

        double rsta = norm(xsta, 3);
        double sinphi = xsta[2] / rsta;
        double cosphi = Math.sqrt(xsta[0] * xsta[0] + xsta[1] * xsta[1]) / rsta;
        double sinla = xsta[1] / cosphi / rsta;
        double cosla = xsta[0] / cosphi / rsta;
        double costwola = cosla * cosla - sinla * sinla;
        double sintwola = 2.0 * cosla * sinla;

        double rmon = norm(xmon, 3);
        double rsun = norm(xsun, 3);

        double drsun = -3.0 / 4.0 * dhi * cosphi * cosphi * fac2sun
                * ((xsun[0] * xsun[0] - xsun[1] * xsun[1]) * sintwola
                - 2.0 * xsun[0] * xsun[1] * costwola) / (rsun * rsun);
        double drmon = -3.0 / 4.0 * dhi * cosphi * cosphi * fac2mon
                * ((xmon[0] * xmon[0] - xmon[1] * xmon[1]) * sintwola
                - 2.0 * xmon[0] * xmon[1] * costwola) / (rmon * rmon);
        double dnsun = 3.0 / 2.0 * dli * sinphi * cosphi * fac2sun
                * ((xsun[0] * xsun[0] - xsun[1] * xsun[1]) * sintwola
                - 2.0 * xsun[0] * xsun[1] * costwola) / (rsun * rsun);
        double dnmon = 3.0 / 2.0 * dli * sinphi * cosphi * fac2mon
                * ((xmon[0] * xmon[0] - xmon[1] * xmon[1]) * sintwola
                - 2.0 * xmon[0] * xmon[1] * costwola) / (rmon * rmon);
        double desun = -3.0 / 2.0 * dli * cosphi * fac2sun
                * ((xsun[0] * xsun[0] - xsun[1] * xsun[1]) * costwola
                + 2.0 * xsun[0] * xsun[1] * sintwola) / (rsun * rsun);
        double demon = -3.0 / 2.0 * dli * cosphi * fac2mon
                * ((xmon[0] * xmon[0] - xmon[1] * xmon[1]) * costwola
                + 2.0 * xmon[0] * xmon[1] * sintwola) / (rmon * rmon);

        double dr = drsun + drmon;
        double dn = dnsun + dnmon;
        double de = desun + demon;

        xcorsta[0] = dr * cosla * cosphi - de * sinla - dn * sinphi * cosla;
        xcorsta[1] = dr * sinla * cosphi + de * cosla - dn * sinphi * sinla;
        xcorsta[2] = dr * sinphi + dn * cosphi;
    }

    /**
     * 纬度依赖的 Love 数 L^(1) 修正。
     */
    private static void st1l1_(double[] xsta, double[] xsun, double[] xmon,
                               double fac2sun, double fac2mon, double[] xcorsta) {
        final double l1d = 0.0012, l1sd = 0.0024;

        double rsta = norm(xsta, 3);
        double sinphi = xsta[2] / rsta;
        double cosphi = Math.sqrt(xsta[0] * xsta[0] + xsta[1] * xsta[1]) / rsta;
        double sinla = xsta[1] / cosphi / rsta;
        double cosla = xsta[0] / cosphi / rsta;

        double rmon = norm(xmon, 3);
        double rsun = norm(xsun, 3);

        /* diurnal band */
        double l1 = l1d;
        double dnsun = -l1 * sinphi * sinphi * fac2sun * xsun[2]
                * (xsun[0] * cosla + xsun[1] * sinla) / (rsun * rsun);
        double dnmon = -l1 * sinphi * sinphi * fac2mon * xmon[2]
                * (xmon[0] * cosla + xmon[1] * sinla) / (rmon * rmon);
        double desun = l1 * sinphi * (cosphi * cosphi - sinphi * sinphi) * fac2sun * xsun[2]
                * (xsun[0] * sinla - xsun[1] * cosla) / (rsun * rsun);
        double demon = l1 * sinphi * (cosphi * cosphi - sinphi * sinphi) * fac2mon * xmon[2]
                * (xmon[0] * sinla - xmon[1] * cosla) / (rmon * rmon);
        double de = 3.0 * (desun + demon);
        double dn = 3.0 * (dnsun + dnmon);
        xcorsta[0] = -de * sinla - dn * sinphi * cosla;
        xcorsta[1] = de * cosla - dn * sinphi * sinla;
        xcorsta[2] = dn * cosphi;

        /* semi-diurnal band */
        l1 = l1sd;
        double costwola = cosla * cosla - sinla * sinla;
        double sintwola = 2.0 * cosla * sinla;
        dnsun = -l1 / 2.0 * sinphi * cosphi * fac2sun
                * ((xsun[0] * xsun[0] - xsun[1] * xsun[1]) * costwola
                + 2.0 * xsun[0] * xsun[1] * sintwola) / (rsun * rsun);
        dnmon = -l1 / 2.0 * sinphi * cosphi * fac2mon
                * ((xmon[0] * xmon[0] - xmon[1] * xmon[1]) * costwola
                + 2.0 * xmon[0] * xmon[1] * sintwola) / (rmon * rmon);
        desun = -l1 / 2.0 * sinphi * sinphi * cosphi * fac2sun
                * ((xsun[0] * xsun[0] - xsun[1] * xsun[1]) * sintwola
                - 2.0 * xsun[0] * xsun[1] * costwola) / (rsun * rsun);
        demon = -l1 / 2.0 * sinphi * sinphi * cosphi * fac2mon
                * ((xmon[0] * xmon[0] - xmon[1] * xmon[1]) * sintwola
                - 2.0 * xmon[0] * xmon[1] * costwola) / (rmon * rmon);
        de = 3.0 * (desun + demon);
        dn = 3.0 * (dnsun + dnmon);
        xcorsta[0] += -de * sinla - dn * sinphi * cosla;
        xcorsta[1] += de * cosla - dn * sinphi * sinla;
        xcorsta[2] += dn * cosphi;
    }

    /* ======== Dehanttideinel 主函数 ======== */

    /**
     * 计算固体潮引起的地球形变位移（Dehanttideinel 模型）。
     *
     * @param xsta  测站 ECEF 坐标 (m)
     * @param xsun  太阳 ECEF 坐标 (m)
     * @param xmon  月球 ECEF 坐标 (m)
     * @param dxtide 输出：潮汐位移 (m)，加到测站坐标上
     */
    public static void dehanttideinel(GTime tutc, double[] xsta, double[] xsun, double[] xmon,
                                      double[] dxtide) {
        final double h20 = 0.6078, l20 = 0.0847, h3 = 0.292, l3 = 0.015;
        final double mass_ratio_sun = 332946.0482;
        final double mass_ratio_moon = 0.0123000371;
        final double re = 6378136.6;

        double rsta = norm(xsta, 3);
        double rsun = norm(xsun, 3);
        double rmon = norm(xmon, 3);
        double scs = dot(xsta, xsun, 3);
        double scm = dot(xsta, xmon, 3);
        double scsun = scs / rsta / rsun;
        double scmon = scm / rsta / rmon;

        double cosphi = Math.sqrt(xsta[0] * xsta[0] + xsta[1] * xsta[1]) / rsta;
        double h2 = h20 - (1.0 - 1.5 * (cosphi * cosphi)) * 6e-4;
        double l2 = l20 + (1.0 - 1.5 * (cosphi * cosphi)) * 2e-4;

        /* P2 term */
        double p2sun = 3.0 * (h2 / 2.0 - l2) * (scsun * scsun) - h2 / 2.0;
        double p2mon = 3.0 * (h2 / 2.0 - l2) * (scmon * scmon) - h2 / 2.0;

        /* P3 term */
        double p3sun = 2.5 * (h3 - 3.0 * l3) * (scsun * scsun * scsun)
                + 1.5 * (l3 - h3) * scsun;
        double p3mon = 2.5 * (h3 - 3.0 * l3) * (scmon * scmon * scmon)
                + 1.5 * (l3 - h3) * scmon;

        /* Term in direction of sun/moon vector */
        double x2sun = 3.0 * l2 * scsun;
        double x2mon = 3.0 * l2 * scmon;
        double x3sun = 1.5 * l3 * (5.0 * scsun * scsun - 1.0);
        double x3mon = 1.5 * l3 * (5.0 * scmon * scmon - 1.0);

        /* Factors */
        double resun = re / rsun;
        double fac2sun = mass_ratio_sun * re * (resun * resun) * resun;
        double remon = re / rmon;
        double fac2mon = mass_ratio_moon * re * (remon * remon) * remon;
        double fac3sun = fac2sun * resun;
        double fac3mon = fac2mon * remon;

        /* Total displacement */
        for (int i = 0; i < 3; i++) {
            dxtide[i] = fac2sun * (x2sun * xsun[i] / rsun + p2sun * xsta[i] / rsta)
                    + fac2mon * (x2mon * xmon[i] / rmon + p2mon * xsta[i] / rsta)
                    + fac3sun * (x3sun * xsun[i] / rsun + p3sun * xsta[i] / rsta)
                    + fac3mon * (x3mon * xmon[i] / rmon + p3mon * xsta[i] / rsta);
        }

        /* Out-of-phase corrections */
        double[] xcorsta = new double[3];

        /* Diurnal band */
        st1idiu_(xsta, xsun, xmon, fac2sun, fac2mon, xcorsta);
        for (int i = 0; i < 3; i++) dxtide[i] += xcorsta[i];

        /* Semi-diurnal band */
        st1isem_(xsta, xsun, xmon, fac2sun, fac2mon, xcorsta);
        for (int i = 0; i < 3; i++) dxtide[i] += xcorsta[i];

        /* Latitude dependence of Love numbers */
        st1l1_(xsta, xsun, xmon, fac2sun, fac2mon, xcorsta);
        for (int i = 0; i < 3; i++) dxtide[i] += xcorsta[i];

        /* Step 2 corrections */
        double[] ep = new double[6];
        TimeSystem.time2epoch(tutc, ep);
        double fhr = ep[3] + ep[4] / 60.0 + ep[5] / 3600.0;

        GTime tgps = TimeSystem.utc2gpst(tutc);
        final double[] ep2000tt = {2000, 1, 1, 11, 59, 8.816};
        double t = TimeSystem.timediff(tgps, TimeSystem.epoch2time(ep2000tt)) / 86400.0 / 36525.0;

        /* Diurnal band (in-phase and out-of-phase frequency dependence) */
        step2diu_(xsta, fhr, t, xcorsta);
        for (int i = 0; i < 3; i++) dxtide[i] += xcorsta[i];

        /* Long-period band */
        step2lon_(xsta, t, xcorsta);
        for (int i = 0; i < 3; i++) dxtide[i] += xcorsta[i];
    }

    private static final int NTIN = 11;
    private static final int NT = 342;
    private static final int NCON = 20;
    private static final int NL_OTL = 8;

    public static void tidedisp(GTime tutc, double[] rr, int opt, Erp erp,
                                 double[][][] odisp, double[] dr) {
        dr[0] = dr[1] = dr[2] = 0.0;
        if (norm(rr, 3) <= 0.0) return;

        double[] erpv = new double[5];
        if (erp != null) {
            if (geterp(erp, TimeSystem.utc2gpst(tutc), erpv) == 0) {
                erpv[0] = erpv[1] = erpv[2] = erpv[3] = erpv[4] = 0.0;
            }
        }

        if ((opt & 1) != 0) {
            double[] rsun = new double[3], rmoon = new double[3];
            sunmoonpos(tutc, erpv, rsun, rmoon, null);
            double[] drt = new double[3];
            dehanttideinel(tutc, rr, rsun, rmoon, drt);
            for (int i = 0; i < 3; i++) dr[i] += drt[i];
        }

        double[] pos = new double[3], E = new double[9];
        if (((opt & 2) != 0 && odisp != null) || ((opt & 4) != 0 && erp != null)) {
            org.rtklib.java.coord.CoordTransform.ecef2pos(rr, pos);
            org.rtklib.java.coord.CoordTransform.xyz2enu(pos, E);
        }

        if ((opt & 2) != 0 && odisp != null) {
            GTime tut = TimeSystem.timeadd(tutc, erpv[2]);
            double[] otlDisp = hardisp(tut, odisp);
            double[] denu = new double[]{-otlDisp[1], -otlDisp[2], otlDisp[0]};
            double[] drt = new double[3];
            matmulTN3x1(E, denu, drt);
            for (int i = 0; i < 3; i++) dr[i] += drt[i];
        }

        if ((opt & 4) != 0 && erp != null) {
            double[] denu = new double[3];
            tidePole(tutc, pos, erpv, denu);
            double[] drt = new double[3];
            matmulTN3x1(E, denu, drt);
            for (int i = 0; i < 3; i++) dr[i] += drt[i];
        }
    }

    private static void matmulTN3x1(double[] E, double[] v, double[] out) {
        for (int i = 0; i < 3; i++) {
            out[i] = E[0 * 3 + i] * v[0] + E[1 * 3 + i] * v[1] + E[2 * 3 + i] * v[2];
        }
    }

    static void tidePole(GTime tutc, double[] pos, double[] erpv, double[] denu) {
        final double[] ep2000 = {2000, 1, 1, 11, 59, 08.816};
        GTime tgps = TimeSystem.utc2gpst(tutc);
        double y = TimeSystem.timediff(tgps, TimeSystem.epoch2time(ep2000)) / 86400.0 / 365.25;
        double xpBar = 55.0 + 1.677 * y;
        double ypBar = 320.5 + 3.460 * y;
        double m1 = erpv[0] / Constants.AS2R - xpBar * 1E-3;
        double m2 = -erpv[1] / Constants.AS2R + ypBar * 1E-3;
        double cosl = Math.cos(pos[1]);
        double sinl = Math.sin(pos[1]);
        denu[0] = 9E-3 * Math.sin(pos[0]) * (m1 * sinl - m2 * cosl);
        denu[1] = -9E-3 * Math.cos(2.0 * pos[0]) * (m1 * cosl + m2 * sinl);
        denu[2] = -33E-3 * Math.sin(2.0 * pos[0]) * (m1 * cosl + m2 * sinl);
    }

    static double[] hardisp(GTime time, double[][][] odisp) {
        int[][] idt = {
            {2, 0, 0, 0, 0, 0}, {2, 2, -2, 0, 0, 0}, {2, -1, 0, 1, 0, 0},
            {2, 2, 0, 0, 0, 0}, {1, 1, 0, 0, 0, 0}, {1, -1, 0, 0, 0, 0},
            {1, 1, -2, 0, 0, 0}, {1, -2, 0, 1, 0, 0}, {0, 2, 0, 0, 0, 0},
            {0, 1, 0, -1, 0, 0}, {0, 0, 2, 0, 0, 0}
        };
        double[] tamp0 = new double[NTIN], tph0 = new double[NTIN];
        double[] tamp1 = new double[NTIN], tph1 = new double[NTIN];
        double[] tamp2 = new double[NTIN], tph2 = new double[NTIN];
        for (int i = 0; i < NTIN; i++) {
            tamp0[i] = odisp[0][i][0]; tph0[i] = odisp[1][i][0];
            tamp1[i] = odisp[0][i][1]; tph1[i] = odisp[1][i][1];
            tamp2[i] = odisp[0][i][2]; tph2[i] = odisp[1][i][2];
        }
        AdmintResult r0 = admint(time, tamp0, idt, tph0);
        AdmintResult r1 = admint(time, tamp1, idt, tph1);
        AdmintResult r2 = admint(time, tamp2, idt, tph2);
        int ntout = r0.nout;
        double[] az = r0.amp, pz = r0.p, aw = r1.amp, pw = r1.p, as_ = r2.amp, ps = r2.p;
        double[] f = r0.f;
        double[] wf = new double[ntout];
        for (int i = 0; i < ntout; i++) {
            pz[i] = Constants.D2R * pz[i];
            ps[i] = Constants.D2R * ps[i];
            pw[i] = Constants.D2R * pw[i];
            f[i] = 1.0 * Math.PI * f[i] / 43200.0;
            wf[i] = f[i];
        }
        double[] hcz = new double[NT * 2], hcs = new double[NT * 2], hcw = new double[NT * 2];
        for (int i = 0; i < ntout; i++) {
            hcz[i * 2] = az[i] * Math.cos(pz[i]);
            hcz[i * 2 + 1] = -az[i] * Math.sin(pz[i]);
            hcs[i * 2] = as_[i] * Math.cos(ps[i]);
            hcs[i * 2 + 1] = -as_[i] * Math.sin(ps[i]);
            hcw[i * 2] = aw[i] * Math.cos(pw[i]);
            hcw[i * 2 + 1] = -aw[i] * Math.sin(pw[i]);
        }
        double dz = recursSingle(hcz, ntout, wf);
        double ds = recursSingle(hcs, ntout, wf);
        double dw = recursSingle(hcw, ntout, wf);
        return new double[]{dz, ds, dw};
    }

    private static double recursSingle(double[] hc, int nf, double[] om) {
        double[] scr = new double[nf * 3];
        for (int i = 0; i < nf; i++) {
            scr[i * 3] = hc[i * 2];
            scr[i * 3 + 1] = hc[i * 2] * Math.cos(om[i]) - hc[i * 2 + 1] * Math.sin(om[i]);
            scr[i * 3 + 2] = Math.cos(om[i]) * 2;
        }
        double x = 0;
        for (int j = 0; j < nf; j++) {
            double sc = scr[j * 3];
            x += sc;
            scr[j * 3] = scr[j * 3 + 2] * sc - scr[j * 3 + 1];
            scr[j * 3 + 1] = sc;
        }
        return x;
    }

    private static class AdmintResult {
        int nout;
        double[] amp, f, p;
        AdmintResult(int nout, double[] amp, double[] f, double[] p) {
            this.nout = nout; this.amp = amp; this.f = f; this.p = p;
        }
    }

    private static AdmintResult admint(GTime time, double[] ampin, int[][] idtin, double[] phin) {
        double[] tampArr = getTampArray();
        int[][] iddArr = getIddArray();
        double[] rl = new double[NCON], aim = new double[NCON], rf = new double[NCON];
        int k = 0;
        for (int ll = 0; ll < NTIN && k < NCON; ll++) {
            int kk, ii = 0;
            for (kk = 0; kk < NT; kk++) {
                ii = 0;
                for (int i = 0; i < 6; i++) ii += Math.abs(iddArr[kk][i] - idtin[ll][i]);
                if (ii == 0) break;
            }
            if (ii == 0) {
                rl[k] = ampin[ll] * Math.cos(phin[ll] * Constants.D2R) / Math.abs(tampArr[kk]);
                aim[k] = ampin[ll] * Math.sin(phin[ll] * Constants.D2R) / Math.abs(tampArr[kk]);
                double[] fp = tdfrph(time, iddArr[kk]);
                rf[k] = fp[0];
                k++;
            }
        }
        int[] key = new int[k];
        shellSortStable(rf, key, k);
        int nlp = 0, ndi = 0, nsd = 0;
        for (int i = 0; i < k; i++) {
            if (rf[i] < 0.5) nlp++;
            if (rf[i] < 1.5 && rf[i] > 0.5) ndi++;
            if (rf[i] < 2.5 && rf[i] > 1.5) nsd++;
        }
        double[] scr = new double[NCON];
        for (int i = 0; i < k; i++) scr[i] = rl[key[i]];
        for (int i = 0; i < k; i++) rl[i] = scr[i];
        for (int i = 0; i < k; i++) scr[i] = aim[key[i]];
        for (int i = 0; i < k; i++) aim[i] = scr[i];
        double[] zdr = new double[NCON], zdi = new double[NCON];
        if (nlp != 0) {
            cubicSpline(nlp, rf, rl, zdr, scr);
            cubicSpline(nlp, rf, aim, zdi, scr);
        }
        double[] dr = new double[NCON], di = new double[NCON], sdr = new double[NCON], sdi = new double[NCON];
        cubicSpline(ndi, rf, nlp, rl, dr, scr);
        cubicSpline(ndi, rf, nlp, aim, di, scr);
        cubicSpline(nsd, rf, nlp + ndi, rl, sdr, scr);
        cubicSpline(nsd, rf, nlp + ndi, aim, sdi, scr);
        double[] amp = new double[NT], f = new double[NT], p = new double[NT];
        int nout = 0;
        for (int i = 0; i < NT; i++) {
            if (iddArr[i][0] == 0 && nlp == 0) continue;
            double[] fp = tdfrph(time, iddArr[i]);
            f[nout] = fp[0]; p[nout] = fp[1];
            int c = iddArr[i][0];
            double sf = f[nout], re, am;
            if (c == 0) {
                p[nout] += 180;
                re = cubicSplineEval(sf, nlp, rf, rl, zdr);
                am = cubicSplineEval(sf, nlp, rf, aim, zdi);
            } else if (c == 1) {
                p[nout] += 90;
                re = cubicSplineEval(sf, ndi, rf, nlp, rl, dr);
                am = cubicSplineEval(sf, ndi, rf, nlp, aim, di);
            } else if (c == 2) {
                re = cubicSplineEval(sf, nsd, rf, nlp + ndi, rl, sdr);
                am = cubicSplineEval(sf, nsd, rf, nlp + ndi, aim, sdi);
            } else {
                continue;
            }
            amp[nout] = tampArr[i] * Math.sqrt(re * re + am * am);
            p[nout] += Math.atan2(am, re) * Constants.R2D;
            if (p[nout] > 180) p[nout] -= 360;
            nout++;
        }
        return new AdmintResult(nout, amp, f, p);
    }

    private static GTime ctimeCache = null;
    private static double[] dCache = new double[6], ddCache = new double[6];

    private static double[] tdfrph(GTime tut, int[] idood) {
        if (ctimeCache == null || Math.abs(TimeSystem.timediff(tut, ctimeCache)) > 0.001) {
            double[] ep = new double[6];
            TimeSystem.time2epoch(tut, ep);
            double dayfr = ep[3] / 24.0 + ep[4] / 1440.0 + ep[5] / 86400.0;
            GTime tgps = TimeSystem.utc2gpst(tut);
            final double[] ep2000 = {2000, 1, 1, 11, 59, 08.816};
            double t = TimeSystem.timediff(tgps, TimeSystem.epoch2time(ep2000)) / 86400.0 / 36525.0;
            ctimeCache = new GTime(tut);
            double f1 = t * (t * (t * (t * -6.8e-8 + 1.43431e-5) + 0.0088553333) + 477198.8675605) + 134.96340251;
            double f2 = t * (t * (t * (t * -3.2e-9 + 3.78e-8) - 1.536667e-4) + 35999.0502911389) + 357.5291091806;
            double f3 = t * (t * (t * (t * 1.2e-9 - 2.881e-7) - 0.003542) + 483202.0174577222) + 93.27209062;
            double f4 = t * (t * (t * (t * -8.8e-9 + 1.8314e-6) - 0.0017696111) + 445267.1114469445) + 297.8501954694;
            double f5 = t * (t * (t * (t * -1.65e-8 + 2.1394e-6) + 0.0020756111) - 1934.1362619722) + 125.04455501;
            dCache[0] = dayfr * 360 - f4;
            dCache[1] = f3 + f5;
            dCache[2] = dCache[1] - f4;
            dCache[3] = dCache[1] - f1;
            dCache[4] = -f5;
            dCache[5] = dCache[2] - f2;
            double fd1 = t * 1.3e-9 + 0.0362916471;
            double fd2 = 0.0027377786;
            double fd3 = 0.0367481951 - t * 5e-10;
            double fd4 = 0.033863192 - t * 3e-10;
            double fd5 = t * 3e-10 - 1.470938e-4;
            ddCache[0] = 1 - fd4;
            ddCache[1] = fd3 + fd5;
            ddCache[2] = ddCache[1] - fd4;
            ddCache[3] = ddCache[1] - fd1;
            ddCache[4] = -fd5;
            ddCache[5] = ddCache[2] - fd2;
        }
        double freq = 0, phase = 0;
        for (int i = 0; i < 6; i++) {
            freq += idood[i] * ddCache[i];
            phase += idood[i] * dCache[i];
        }
        phase = phase % 360;
        if (phase < 0) phase += 360;
        return new double[]{freq, phase};
    }

    private static void shellSortStable(double[] x, int[] k, int n) {
        for (int i = 0; i < n; ++i) k[i] = i;
        int igap = n;
        while (igap > 1) {
            igap /= 2;
            int imax = n - igap, iex;
            do {
                iex = 0;
                for (int i = 0; i < imax; i++) {
                    int ipl = i + igap;
                    if (x[i] <= x[ipl]) continue;
                    double sv = x[i]; int ik = k[i];
                    x[i] = x[ipl]; k[i] = k[ipl];
                    x[ipl] = sv; k[ipl] = ik;
                    iex++;
                }
            } while (iex > 0);
        }
        for (int j = 0; j + 1 < n; j++) {
            if (x[j] != x[j + 1]) continue;
            int l = j + 1;
            while (l + 1 < n) { if (x[l] != x[l + 1]) break; l++; }
            int nj = l - j + 1;
            igap = nj;
            while (igap > 1) {
                igap /= 2;
                int imax2 = nj - igap, iex2;
                do {
                    iex2 = 0;
                    for (int i = 0; i < imax2; i++) {
                        int ipl = j + i + igap;
                        if (k[j + i] <= k[ipl]) continue;
                        int ik2 = k[j + i]; k[j + i] = k[ipl]; k[ipl] = ik2;
                        iex2++;
                    }
                } while (iex2 > 0);
            }
            j = l;
        }
    }

    private static void cubicSpline(int nn, double[] x, double[] u, double[] s, double[] a) {
        cubicSpline(nn, x, 0, u, s, a);
    }

    private static void cubicSpline(int nn, double[] x, int xOff, double[] u, double[] s, double[] a) {
        int n = Math.abs(nn);
        if (n <= 3) { for (int i = 0; i < n; i++) s[i] = 0; return; }
        double q1 = cubicSplineQ(u[xOff + 1] - u[xOff], x[xOff + 1] - x[xOff], u[xOff + 2] - u[xOff], x[xOff + 2] - x[xOff]);
        int n1 = n - 1;
        double qn = cubicSplineQ(u[xOff + n1 - 1] - u[xOff + n - 1], x[xOff + n1 - 1] - x[xOff + n - 1],
                                  u[xOff + n - 3] - u[xOff + n - 1], x[xOff + n - 3] - x[xOff + n - 1]);
        if (nn <= 0) { q1 = s[0]; qn = s[1]; }
        s[0] = ((u[xOff + 1] - u[xOff]) / (x[xOff + 1] - x[xOff]) - q1) * 6;
        for (int i = 1; i < n1; i++) {
            s[i] = (u[xOff + i - 1] / (x[xOff + i] - x[xOff + i - 1]) - u[xOff + i] * (1.0 / (x[xOff + i] - x[xOff + i - 1]) + 1.0 / (x[xOff + i + 1] - x[xOff + i])) + u[xOff + i + 1] / (x[xOff + i + 1] - x[xOff + i])) * 6;
        }
        s[n - 1] = (qn + (u[xOff + n1 - 1] - u[xOff + n - 1]) / (x[xOff + n - 1] - x[xOff + n1 - 1])) * 6;
        a[0] = (x[xOff + 1] - x[xOff]) * 2;
        a[1] = (x[xOff + 1] - x[xOff]) * 1.5 + (x[xOff + 2] - x[xOff + 1]) * 2;
        s[1] -= s[0] * 0.5;
        for (int i = 2; i < n1; i++) {
            double c = (x[xOff + i] - x[xOff + i - 1]) / a[i - 1];
            a[i] = (x[xOff + i + 1] - x[xOff + i - 1]) * 2 - c * (x[xOff + i] - x[xOff + i - 1]);
            s[i] -= c * s[i - 1];
        }
        double c = (x[xOff + n - 1] - x[xOff + n1 - 1]) / a[n1 - 1];
        a[n - 1] = (2 - c) * (x[xOff + n - 1] - x[xOff + n1 - 1]);
        s[n - 1] -= c * s[n1 - 1];
        s[n - 1] /= a[n - 1];
        for (int i = n1 - 1; i >= 0; i--) s[i] = (s[i] - (x[xOff + i + 1] - x[xOff + i]) * s[i + 1]) / a[i];
    }

    private static double cubicSplineQ(double u1, double x1, double u2, double x2) {
        return (u1 / (x1 * x1) - u2 / (x2 * x2)) / (1.0 / x1 - 1.0 / x2);
    }

    private static double cubicSplineEval(double y, int nn, double[] x, double[] u, double[] s) {
        return cubicSplineEval(y, nn, x, 0, u, s);
    }

    private static double cubicSplineEval(double y, int nn, double[] x, int xOff, double[] u, double[] s) {
        int n = Math.abs(nn);
        if (n <= 0) return 0;
        if (n == 1) return u[xOff];
        if (y <= x[xOff]) return u[xOff];
        if (y >= x[xOff + n - 1]) return u[xOff + n - 1];
        int k1 = 0, k2 = 0;
        for (int k = 1; k < n; k++) {
            if (x[xOff + k - 1] < y && x[xOff + k] >= y) { k1 = k - 1; k2 = k; }
        }
        double dy = x[xOff + k2] - y, dy1 = y - x[xOff + k1], dk = x[xOff + k2] - x[xOff + k1];
        double f1 = (s[k1] * dy * dy * dy + s[k2] * dy1 * dy1 * dy1) / (dk * 6);
        double f2 = dy1 * (u[xOff + k2] / dk - s[k2] * dk / 6);
        double f3 = dy * (u[xOff + k1] / dk - s[k1] * dk / 6);
        return f1 + f2 + f3;
    }

    private static double[] tampCache;
    private static int[][] iddCache;

    private static double[] getTampArray() {
        if (tampCache != null) return tampCache;
        double[] t = new double[NT];
        int idx = 0;
        double[] v = {
            0.632208,0.294107,0.121046,0.079915,0.023818,-0.023589,0.022994,0.019333,
            -0.017871,0.017192,0.016018,0.004671,-0.004662,-0.004519,0.00447,0.004467,
            0.002589,-0.002455,-0.002172,0.001972,0.001947,0.001914,-0.001898,0.001802,
            0.001304,0.00117,0.00113,0.001061,-0.001022,-0.001017,0.001014,9.01e-4,
            -8.57e-4,8.55e-4,8.55e-4,7.72e-4,7.41e-4,7.41e-4,-7.21e-4,6.98e-4,
            6.58e-4,6.54e-4,-6.53e-4,6.33e-4,6.26e-4,-5.98e-4,5.9e-4,5.44e-4,
            4.79e-4,-4.64e-4,4.13e-4,-3.9e-4,3.73e-4,3.66e-4,3.66e-4,-3.6e-4,
            -3.55e-4,3.54e-4,3.29e-4,3.28e-4,3.19e-4,3.02e-4,2.79e-4,-2.74e-4,
            -2.72e-4,2.48e-4,-2.25e-4,2.24e-4,-2.23e-4,-2.16e-4,2.11e-4,2.09e-4,
            1.94e-4,1.85e-4,-1.74e-4,-1.71e-4,1.59e-4,1.31e-4,1.27e-4,1.2e-4,
            1.18e-4,1.17e-4,1.08e-4,1.07e-4,1.05e-4,-1.02e-4,1.02e-4,9.9e-5,
            -9.6e-5,9.5e-5,-8.9e-5,-8.5e-5,-8.4e-5,-8.1e-5,-7.7e-5,-7.2e-5,
            -6.7e-5,6.6e-5,6.4e-5,6.3e-5,6.3e-5,6.3e-5,6.2e-5,6.2e-5,
            -6e-5,5.6e-5,5.3e-5,5.1e-5,5e-5
        };
        System.arraycopy(v, 0, t, idx, v.length); idx += v.length;
        double[] v2 = {
            0.368645,-0.262232,-0.121995,-0.050208,0.050031,-0.04947,0.02062,0.020613,
            0.011279,-0.00953,-0.009469,-0.008012,0.007414,-0.0073,0.007227,-0.007131,
            -0.006644,0.005249,0.004137,0.004087,0.003944,0.003943,0.00342,0.003418,
            0.002416,0.002416,0.002885,0.002884,0.00216,-0.001936,0.001934,-0.001798,
            0.00169,0.001689,0.001516,0.001514,-0.001511,0.001383,0.001372,0.001371,
            -0.001253,-0.001075,0.00102,9.01e-4,8.65e-4,-7.94e-4,7.88e-4,7.82e-4,
            -7.47e-4,-7.45e-4,6.7e-4,-6.03e-4,-5.97e-4,5.42e-4,5.42e-4,-5.41e-4,
            -4.69e-4,-4.4e-4,4.38e-4,4.22e-4,4.1e-4,-3.74e-4,-3.65e-4,3.45e-4,
            3.35e-4,-3.21e-4,-3.19e-4,3.07e-4,2.91e-4,2.9e-4,-2.89e-4,2.86e-4,
            2.75e-4,2.71e-4,2.63e-4,-2.45e-4,2.25e-4,2.25e-4,2.21e-4,-2.02e-4,
            -2e-4,-1.99e-4,1.92e-4,1.83e-4,1.83e-4,1.83e-4,-1.7e-4,1.69e-4,
            1.68e-4,1.62e-4,1.49e-4,-1.47e-4,-1.41e-4,1.38e-4,1.36e-4,1.36e-4,
            1.27e-4,1.27e-4,-1.26e-4,-1.21e-4,-1.21e-4,1.17e-4,-1.16e-4,-1.14e-4,
            -1.14e-4,-1.14e-4,1.14e-4,1.13e-4,1.09e-4,1.08e-4,1.06e-4,
            -1.06e-4,-1.06e-4,1.05e-4,1.04e-4,-1.03e-4,-1e-4,-1e-4,-1e-4,
            9.9e-5,-9.8e-5,9.3e-5,9.3e-5,9e-5,-8.8e-5,8.3e-5,-8.3e-5,
            -8.2e-5,-8.1e-5,-7.9e-5,-7.7e-5,-7.5e-5,-7.5e-5,-7.5e-5,7.1e-5,
            7.1e-5,-7.1e-5,6.8e-5,6.8e-5,6.5e-5,6.5e-5,6.4e-5,6.4e-5,
            6.4e-5,-6.4e-5,-6e-5,5.6e-5,5.6e-5,5.3e-5,5.3e-5,5.3e-5,
            -5.3e-5,5.3e-5,5.3e-5,5.2e-5,5e-5
        };
        System.arraycopy(v2, 0, t, idx, v2.length); idx += v2.length;
        double[] v3 = {
            -0.066607,-0.035184,-0.030988,0.027929,-0.027616,-0.012753,-0.006728,-0.005837,
            -0.005286,-0.004921,-0.002884,-0.002583,-0.002422,0.00231,0.002283,-0.002037,
            0.001883,-0.001811,-0.001687,-0.001004,-9.25e-4,-8.44e-4,7.66e-4,7.66e-4,
            -7e-4,-4.95e-4,-4.92e-4,4.91e-4,4.83e-4,4.37e-4,-4.16e-4,-3.84e-4,
            3.74e-4,-3.12e-4,-2.88e-4,-2.73e-4,2.59e-4,2.45e-4,-2.32e-4,2.29e-4,
            -2.16e-4,2.06e-4,-2.04e-4,-2.02e-4,2e-4,1.95e-4,-1.9e-4,1.87e-4,
            1.8e-4,-1.79e-4,1.7e-4,1.53e-4,-1.37e-4,-1.19e-4,-1.19e-4,-1.12e-4,
            -1.1e-4,-1.1e-4,1.07e-4,-9.5e-5,-9.5e-5,-9.1e-5,-9e-5,-8.1e-5,
            -7.9e-5,-7.9e-5,7.7e-5,-7.3e-5,6.9e-5,-6.7e-5,-6.6e-5,6.5e-5,
            6.4e-5,-6.2e-5,6e-5,5.9e-5,-5.6e-5,5.5e-5,-5.1e-5
        };
        System.arraycopy(v3, 0, t, idx, v3.length);
        tampCache = t;
        return t;
    }

    private static int[][] getIddArray() {
        if (iddCache != null) return iddCache;
        iddCache = IddData.IDD;
        return iddCache;
    }
}