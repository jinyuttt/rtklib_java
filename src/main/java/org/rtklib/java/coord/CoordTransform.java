package org.rtklib.java.coord;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.constants.Constants;

/**
 * 坐标转换工具类。
 *
 * <p>对应RTKLIB rtkcmn.c 中的坐标转换函数。内部使用 WGS84 椭球参数。</p>
 *
 * <h3>矩阵存储约定（行优先）</h3>
 * <p>所有矩阵均按<b>行优先（row-major）</b>存储，即 M[row * cols + col]。
 * 与 RTKLIB C 版本的列优先存储不同，Java 版本统一行优先，
 * 通过 {@link MatrixUtil} 转为 EJML SimpleMatrix 进行运算。</p>
 *
 * <h3>ENU变换矩阵</h3>
 * <p>xyz2enu 输出的 3x3 变换矩阵 E 按行优先存储：</p>
 * <pre>
 * E = [-sinl,      cosl,       0   ]   &lt;- 东(E)方向单位向量
 *     [-sinp*cosl, -sinp*sinl, cosp]   &lt;- 北(N)方向单位向量
 *     [ cosp*cosl,  cosp*sinl, sinp]   &lt;- 天(U)方向单位向量
 * </pre>
 * <p>其中 sinp=sin(lat), cosp=cos(lat), sinl=sin(lon), cosl=cos(lon)。</p>
 *
 * <h3>与RTKLIB C版本的差异</h3>
 * <ul>
 *   <li>C版本 xyz2enu 输出列优先：E[0..2]=第0列, E[3..5]=第1列, E[6..8]=第2列</li>
 *   <li>Java版本输出行优先：E[0..2]=第0行, E[3..5]=第1行, E[6..8]=第2行</li>
 *   <li>矩阵乘法使用 EJML SimpleMatrix，不直接手撸数组运算</li>
 * </ul>
 */
public final class CoordTransform {
    private CoordTransform() {
    }

    /**
     * ECEF坐标转大地坐标（纬度、经度、高度）。
     *
     * <p>对应RTKLIB ecef2pos()。使用 WGS84 椭球参数迭代求解纬度。</p>
     *
     * @param r   ECEF坐标 (x, y, z)，单位：米
     * @param pos 输出：大地坐标 (纬度, 经度, 高度)，单位：弧度, 弧度, 米
     */
    public static void ecef2pos(double[] r, double[] pos) {
        double e2 = Constants.FE_WGS84 * (2.0 - Constants.FE_WGS84);
        double r2 = dot2(r, r);
        double z = r[2];
        double zk = 0.0;
        double v = Constants.RE_WGS84;
        double sinp;
        while (Math.abs(z - zk) >= 1E-4) {
            zk = z;
            sinp = z / Math.sqrt(r2 + z * z);
            v = Constants.RE_WGS84 / Math.sqrt(1.0 - e2 * sinp * sinp);
            z = r[2] + v * e2 * sinp;
        }
        pos[0] = r2 > 1E-12 ? Math.atan(z / Math.sqrt(r2)) : (r[2] > 0.0 ? Constants.PI / 2.0 : -Constants.PI / 2.0);
        pos[1] = r2 > 1E-12 ? Math.atan2(r[1], r[0]) : 0.0;
        pos[2] = Math.sqrt(r2 + z * z) - v;
    }

    /**
     * 大地坐标转ECEF坐标。
     *
     * <p>对应RTKLIB pos2ecef()。</p>
     *
     * @param pos 大地坐标 (纬度, 经度, 高度)，单位：弧度, 弧度, 米
     * @param r   输出：ECEF坐标 (x, y, z)，单位：米
     */
    public static void pos2ecef(double[] pos, double[] r) {
        double sinp = Math.sin(pos[0]);
        double cosp = Math.cos(pos[0]);
        double sinl = Math.sin(pos[1]);
        double cosl = Math.cos(pos[1]);
        double e2 = Constants.FE_WGS84 * (2.0 - Constants.FE_WGS84);
        double v = Constants.RE_WGS84 / Math.sqrt(1.0 - e2 * sinp * sinp);

        r[0] = (v + pos[2]) * cosp * cosl;
        r[1] = (v + pos[2]) * cosp * sinl;
        r[2] = (v * (1.0 - e2) + pos[2]) * sinp;
    }

    /**
     * 计算ECEF到站心(ENU)的变换矩阵。
     *
     * <p>对应RTKLIB xyz2enu()。输出3x3变换矩阵E，满足：</p>
     * <pre>[e, n, u]^T = E * [dx, dy, dz]^T</pre>
     *
     * <p><b>行优先存储</b>（与C版本列优先不同）：</p>
     * <ul>
     *   <li>E[0..2] = 第0行（东方向单位向量）</li>
     *   <li>E[3..5] = 第1行（北方向单位向量）</li>
     *   <li>E[6..8] = 第2行（天方向单位向量）</li>
     * </ul>
     *
     * @param pos 大地坐标 (纬度, 经度)，单位：弧度
     * @param E   输出：3x3变换矩阵，行优先存储 [9]
     */
    public static void xyz2enu(double[] pos, double[] E) {
        double sinp = Math.sin(pos[0]);
        double cosp = Math.cos(pos[0]);
        double sinl = Math.sin(pos[1]);
        double cosl = Math.cos(pos[1]);

        E[0] = -sinl;        E[1] = cosl;         E[2] = 0.0;
        E[3] = -sinp * cosl; E[4] = -sinp * sinl; E[5] = cosp;
        E[6] = cosp * cosl;  E[7] = cosp * sinl;  E[8] = sinp;
    }

    /**
     * ECEF向量转站心(ENU)向量。
     *
     * <p>对应RTKLIB ecef2enu()。计算公式：[e,n,u]^T = E * r</p>
     *
     * @param pos 大地参考坐标 (纬度, 经度)，单位：弧度
     * @param r   输入：ECEF向量 [3]
     * @param e   输出：ENU向量 [3]
     */
    public static void ecef2enu(double[] pos, double[] r, double[] e) {
        double[] E = new double[9];
        xyz2enu(pos, E);
        matmulVec(E, r, e, false);
    }

    /**
     * 站心(ENU)向量转ECEF向量。
     *
     * <p>对应RTKLIB enu2ecef()。计算公式：r = E^T * [e,n,u]^T</p>
     *
     * @param pos 大地参考坐标 (纬度, 经度)，单位：弧度
     * @param e   输入：ENU向量 [3]
     * @param r   输出：ECEF向量 [3]
     */
    public static void enu2ecef(double[] pos, double[] e, double[] r) {
        double[] E = new double[9];
        xyz2enu(pos, E);
        matmulVec(E, e, r, true);
    }

    /**
     * ECEF协方差转站心(ENU)协方差。
     *
     * <p>对应RTKLIB covenu()。计算公式：Q = E * P * E^T</p>
     *
     * <p><b>行优先存储</b>：输入P和输出Q均按行优先存储。</p>
     *
     * @param pos 大地坐标 (纬度, 经度)，单位：弧度
     * @param P   输入：3x3 ECEF协方差矩阵，行优先 [9]
     * @param Q   输出：3x3 ENU协方差矩阵，行优先 [9]
     */
    public static void covenu(double[] pos, double[] P, double[] Q) {
        double[] E = new double[9];
        xyz2enu(pos, E);

        SimpleMatrix EMat = MatrixUtil.createMatrix(E, 3, 3);
        SimpleMatrix PMat = MatrixUtil.createMatrix(P, 3, 3);

        SimpleMatrix EP = MatrixUtil.multiply(EMat, PMat);
        SimpleMatrix EPt = MatrixUtil.multiply(EP, MatrixUtil.transpose(EMat));

        MatrixUtil.copyMatrix(EPt, Q);
    }

    public static void covecef(double[] pos, double[] Q, double[] P) {
        double[] E = new double[9];
        xyz2enu(pos, E);

        SimpleMatrix EMat = MatrixUtil.createMatrix(E, 3, 3);
        SimpleMatrix QMat = MatrixUtil.createMatrix(Q, 3, 3);

        SimpleMatrix EtQ = MatrixUtil.multiply(MatrixUtil.transpose(EMat), QMat);
        SimpleMatrix EtQE = MatrixUtil.multiply(EtQ, EMat);

        MatrixUtil.copyMatrix(EtQE, P);
    }

    /**
     * 矩阵与向量乘法（行优先存储）。
     *
     * <p>计算 c = op(A) * b，其中 A 是 3x3 矩阵，b 是 3x1 向量。</p>
     *
     * @param A    3x3矩阵，行优先 [9]
     * @param b    3x1向量 [3]
     * @param c    输出：3x1向量 [3]
     * @param trans 是否转置A（true: c = A^T * b, false: c = A * b）
     */
    private static void matmulVec(double[] A, double[] b, double[] c, boolean trans) {
        if (trans) {
            c[0] = A[0] * b[0] + A[3] * b[1] + A[6] * b[2];
            c[1] = A[1] * b[0] + A[4] * b[1] + A[7] * b[2];
            c[2] = A[2] * b[0] + A[5] * b[1] + A[8] * b[2];
        } else {
            c[0] = A[0] * b[0] + A[1] * b[1] + A[2] * b[2];
            c[1] = A[3] * b[0] + A[4] * b[1] + A[5] * b[2];
            c[2] = A[6] * b[0] + A[7] * b[1] + A[8] * b[2];
        }
    }

    /**
     * 二维向量点积。
     *
     * @param a 向量a [2]
     * @param b 向量b [2]
     * @return a·b
     */
    public static double dot2(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    /**
     * 三维向量点积。
     *
     * @param a 向量a [3]
     * @param b 向量b [3]
     * @return a·b
     */
    public static double dot3(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * 三维向量叉积。
     *
     * @param a 向量a [3]
     * @param b 向量b [3]
     * @param c 输出：叉积向量 a×b [3]
     */
    public static void cross3(double[] a, double[] b, double[] c) {
        c[0] = a[1] * b[2] - a[2] * b[1];
        c[1] = a[2] * b[0] - a[0] * b[2];
        c[2] = a[0] * b[1] - a[1] * b[0];
    }

    /**
     * 三维向量范数。
     *
     * @param a 向量 [3]
     * @return ||a||
     */
    public static double norm3(double[] a) {
        return Math.sqrt(dot3(a, a));
    }

    /**
     * n维向量范数。
     *
     * @param a 向量 [n]
     * @param n 维数
     * @return ||a||
     */
    public static double norm(double[] a, int n) {
        double s = 0.0;
        for (int i = 0; i < n; i++) s += a[i] * a[i];
        return Math.sqrt(s);
    }

    /**
     * n维向量点积。
     *
     * @param a 向量a [n]
     * @param b 向量b [n]
     * @param n 维数
     * @return a·b
     */
    public static double dot(double[] a, double[] b, int n) {
        double s = 0.0;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }
}