package org.rtklib.java.common;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.SnrMask;
import org.rtklib.java.time.TimeSystem;
import java.util.Arrays;

/**
 * RTKLIB通用工具函数。
 *
 * <p>对应RTKLIB rtkcmn.c 中的通用函数。包含几何距离计算、
 * 方位角/高度角计算、卫星排除判断、DOP计算、SNR掩码测试、
 * 最小二乘估计等功能。</p>
 *
 * <h3>矩阵存储约定（行优先）</h3>
 * <p>所有矩阵均按<b>行优先（row-major）</b>存储，即 M[row * cols + col]。
 * 矩阵运算使用 EJML 库，通过 {@link MatrixUtil} 转换。</p>
 *
 * <h3>致命陷阱规避</h3>
 * <ul>
 *   <li>geodist() 包含 Sagnac 效应修正（地球自转），不可省略</li>
 *   <li>{@link SatUtils#sat2freq} 对 GLONASS 需要 FCN 频率号（-8偏移），不可忽略</li>
 *   <li>lsq() 使用 EJML 求解法方程，避免手撸数组导致的行列优先错误</li>
 * </ul>
 */
public final class RtklibCommon {
    private RtklibCommon() {
    }

    /**
     * 计算卫星与接收机之间的几何距离。
     *
     * <p>对应RTKLIB geodist()。计算公式：</p>
     * <pre>r = ||rs - rr|| + OMGE * (rs[0]*rr[1] - rs[1]*rr[0]) / CLIGHT</pre>
     *
     * <p>第二项为<b>Sagnac效应修正</b>（信号传播期间的地球自转），
     * 省略会导致东向偏差约50米。</p>
     *
     * @param rs 卫星ECEF坐标 [3]，单位：米
     * @param rr 接收机ECEF坐标 [3]，单位：米
     * @param e  输出：视线单位向量 [3]（从接收机指向卫星）
     * @return 几何距离，单位：米
     */
    public static double geodist(double[] rs, double[] rr, double[] e) {
        double r = 0.0;
        for (int i = 0; i < 3; i++) {
            e[i] = rs[i] - rr[i];
            r += e[i] * e[i];
        }
        r = Math.sqrt(r);
        for (int i = 0; i < 3; i++) {
            e[i] /= r;
        }
        r += Constants.OMGE * (rs[0] * rr[1] - rs[1] * rr[0]) / Constants.CLIGHT;
        return r;
    }

    /**
     * 计算卫星的方位角和高度角。
     *
     * <p>对应RTKLIB satazel()。将ECEF视线向量转为站心坐标系(ENU)，
     * 然后计算方位角和高度角。</p>
     *
     * @param pos  接收机大地坐标 (纬度, 经度, 高度)，单位：弧度, 弧度, 米
     * @param e    视线向量 [3]
     * @param azel 输出：方位角/高度角 [2]，单位：弧度
     * @return 高度角，单位：弧度
     */
    public static double satazel(double[] pos, double[] e, double[] azel) {
        double az = 0.0, el = Constants.PI / 2.0;
        if (pos[2] > -Constants.RE_WGS84) {
            double[] enu = new double[3];
            CoordTransform.ecef2enu(pos, e, enu);
            double dot2 = enu[0] * enu[0] + enu[1] * enu[1];
            az = dot2 < 1E-12 ? 0.0 : Math.atan2(enu[0], enu[1]);
            if (az < 0.0) az += 2.0 * Constants.PI;
            el = Math.asin(enu[2]);
        }
        if (azel != null) {
            azel[0] = az;
            azel[1] = el;
        }
        return el;
    }

    /**
     * 判断卫星是否应被排除。
     *
     * <p>对应RTKLIB satexclude()。根据卫星健康标志、方差、
     * 处理选项中的排除列表和导航系统选择判断是否排除。</p>
     *
     * @param sat 卫星号
     * @param var 卫星位置方差（平方米）
     * @param svh 卫星健康标志
     * @param opt 处理选项
     * @return 1=排除 0=保留
     */
    public static int satexclude(int sat, double var, int svh, PrcOpt opt) {
        int sys = SatUtils.satsys(sat, null);

        if (svh < 0) return 1;

        if (opt != null) {
            if (sat > 0 && sat <= opt.exsats.length && opt.exsats[sat - 1] == 1) return 1;
            if (sat > 0 && sat <= opt.exsats.length && opt.exsats[sat - 1] == 2) return 0;
            if ((sys & opt.navsys) == 0) return 1;
        }
        if (sys == Constants.SYS_QZS) svh &= 0xFE;
        if (sys == Constants.SYS_GLO) {
            if ((svh & 9) != 0 || (svh & 6) == 4) {
                return 1;
            }
        } else if (svh != 0) {
            return 1;
        }
        if (var > Constants.MAX_VAR_EPH) {
            return 1;
        }
        return 0;
    }

    /**
     * 计算精度因子(DOP)。
     *
     * <p>对应RTKLIB dops()。计算GDOP、PDOP、HDOP、VDOP。</p>
     *
     * @param ns    卫星数
     * @param azel  方位角/高度角数组 [ns*2]
     * @param elmin 最小高度角（弧度）
     * @param dop   输出：DOP值 {GDOP, PDOP, HDOP, VDOP}
     */
    public static void dops(int ns, double[] azel, double elmin, double[] dop) {
        for (int i = 0; i < 4; i++) dop[i] = 0.0;
        int n = 0;
        double[] H = new double[4 * ns];
        double cosel, sinel;
        for (int i = 0; i < ns; i++) {
            if (azel[1 + i * 2] < elmin || azel[1 + i * 2] <= 0.0) continue;
            cosel = Math.cos(azel[1 + i * 2]);
            sinel = Math.sin(azel[1 + i * 2]);
            H[0 * ns + n] = cosel * Math.sin(azel[i * 2]);
            H[1 * ns + n] = cosel * Math.cos(azel[i * 2]);
            H[2 * ns + n] = sinel;
            H[3 * ns + n] = 1.0;
            n++;
        }
        if (n < 4) return;

        SimpleMatrix HMat = MatrixUtil.createMatrix(H, 4, n);
        SimpleMatrix QMat = MatrixUtil.multiply(HMat, MatrixUtil.transpose(HMat));
        SimpleMatrix QInv = MatrixUtil.invert(QMat);
        double[] Q = MatrixUtil.toArray(QInv);

        dop[0] = Math.sqrt(Q[0 * 4 + 0] + Q[1 * 4 + 1] + Q[2 * 4 + 2] + Q[3 * 4 + 3]);
        dop[1] = Math.sqrt(Q[0 * 4 + 0] + Q[1 * 4 + 1] + Q[2 * 4 + 2]);
        dop[2] = Math.sqrt(Q[0 * 4 + 0] + Q[1 * 4 + 1]);
        dop[3] = Math.sqrt(Q[2 * 4 + 2]);
    }

    /**
     * SNR掩码测试。
     *
     * <p>对应RTKLIB testsnr()。根据高度角和SNR掩码判断
     * 观测值信噪比是否满足要求。</p>
     *
     * @param base 0=流动站 1=基准站
     * @param idx  频率索引 (0:L1, 1:L2, ...)
     * @param el   高度角（弧度）
     * @param snr  载噪比 (dBHz)
     * @param mask SNR掩码
     * @return 1=被掩码排除 0=通过
     */
    public static int testsnr(int base, int idx, double el, double snr, SnrMask mask) {
        double minsnr, a;
        int i;

        if (base == 0 ? mask.ena0 == 0 : mask.ena1 == 0) return 0;
        if (idx < 0 || idx >= SnrMask.NFREQ) return 0;

        a = (el * Constants.R2D + 5.0) / 10.0;
        i = (int) Math.floor(a);
        a -= i;
        if (i < 1) minsnr = mask.mask[idx * SnrMask.NROW + 0];
        else if (i > 8) minsnr = mask.mask[idx * SnrMask.NROW + 8];
        else minsnr = (1.0 - a) * mask.mask[idx * SnrMask.NROW + (i - 1)] + a * mask.mask[idx * SnrMask.NROW + i];

        return snr < minsnr ? 1 : 0;
    }

    /**
     * 计算平方。
     *
     * @param x 输入值
     * @return x^2
     */
    public static double sqr(double x) {
        return x * x;
    }

    /**
     * 向量点积。
     *
     * @param a 向量a [n]
     * @param b 向量b [n]
     * @param n 维数
     * @return a·b
     */
    public static double dot(double[] a, double[] b, int n) {
        double d = 0.0;
        for (int i = 0; i < n; i++) d += a[i] * b[i];
        return d;
    }

    /**
     * 向量范数。
     *
     * @param a 向量 [n]
     * @param n 维数
     * @return ||a||
     */
    public static double norm(double[] a, int n) {
        return Math.sqrt(dot(a, a, n));
    }

    /**
     * 选择电离层无关组合(IFLC)的第二频率索引。
     *
     * <p>对应RTKLIB seliflc()。</p>
     *
     * @param nf  频率数
     * @param sys 卫星系统
     * @return 第二频率索引
     */
    public static int seliflc(int nf, int sys) {
        if (nf >= 2) return 1;
        if (sys == Constants.SYS_IRN) return 0;
        return 0;
    }

    /**
     * 最小二乘估计。
     *
     * <p>对应RTKLIB lsq()。求解法方程：</p>
     * <pre>
     * x = (H^T * H)^-1 * H^T * v
     * Q = (H^T * H)^-1
     * </pre>
     *
     * <p><b>行优先存储</b>：H[m*n] 按行优先存储，H[obs*n + state]。
     * 使用 EJML 库求解法方程，避免手撸数组导致的行列优先错误。</p>
     *
     * @param H 设计矩阵 [m*n]，行优先，m个观测，n个未知数
     * @param v 观测向量 [m]
     * @param n 未知数个数
     * @param m 观测数
     * @param x 输出：解向量 [n]
     * @param Q 输出：协方差矩阵 [n*n]，行优先
     * @return 0:成功 -1:失败（如矩阵奇异）
     */
    public static int lsq(double[] H, double[] v, int n, int m, double[] x, double[] Q) {
        if (m < n) return -1;
        try {
            SimpleMatrix HMat = MatrixUtil.createMatrix(H, m, n);
            SimpleMatrix V = MatrixUtil.createMatrix(v, m, 1);

            SimpleMatrix Ht = MatrixUtil.transpose(HMat);
            SimpleMatrix HtH = MatrixUtil.multiply(Ht, HMat);
            SimpleMatrix Htv = MatrixUtil.multiply(Ht, V);

            SimpleMatrix X = MatrixUtil.solve(HtH, Htv);
            SimpleMatrix QMat = MatrixUtil.invert(HtH);

            MatrixUtil.copyMatrix(X, x);
            MatrixUtil.copyMatrix(QMat, Q);

            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    public static int sortobs(Obsd[] obs, int n) {
        if (n <= 0) return 0;

        Arrays.sort(obs, 0, n, (o1, o2) -> {
            double tt = TimeSystem.timediff(o1.time, o2.time);
            if (Math.abs(tt) > Constants.DTTOL) return tt < 0 ? -1 : 1;
            if (o1.rcv != o2.rcv) return Integer.compare(o1.rcv, o2.rcv);
            return Integer.compare(o1.sat, o2.sat);
        });

        int j = 0;
        for (int i = 1; i < n; i++) {
            if (obs[i].sat != obs[j].sat ||
                obs[i].rcv != obs[j].rcv ||
                TimeSystem.timediff(obs[i].time, obs[j].time) != 0.0) {
                obs[++j] = obs[i];
            }
        }
        int newN = j + 1;

        return newN;
    }
}