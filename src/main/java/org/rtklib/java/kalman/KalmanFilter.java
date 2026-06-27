package org.rtklib.java.kalman;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kalman滤波器实现，用于RTK定位的状态估计。
 *
 * <p>对应RTKLIB filter()函数。实现扩展Kalman滤波(EKF)的测量更新和时间更新。</p>
 *
 * <h3>核心公式</h3>
 *
 * <p><b>测量更新（Update）</b>：</p>
 * <ul>
 *   <li>K = P * H^T * (H * P * H^T + R)^-1</li>
 *   <li>x_new = x + K * v</li>
 *   <li>P_new = (I - K*H) * P * (I - K*H)^T + K * R * K^T</li>
 * </ul>
 *
 * <p><b>时间更新（Predict）</b>：</p>
 * <ul>
 *   <li>x_pred = F * x</li>
 *   <li>P_pred = F * P * F^T + Q</li>
 * </ul>
 *
 * <h3>矩阵存储约定</h3>
 * <p>所有输入/输出数组均按<b>行优先</b>存储，通过 {@link MatrixUtil} 转为 EJML SimpleMatrix 运算。</p>
 * <ul>
 *   <li>x[n]：状态向量</li>
 *   <li>P[n*n]：协方差矩阵，P[i*n+j] = 第i行第j列</li>
 *   <li>H[m*n]：设计矩阵，H[obs*n+state] = 第obs行第state列</li>
 *   <li>R[m*m]：观测噪声协方差，R[i*m+j] = 第i行第j列</li>
 * </ul>
 *
 * <h3>与RTKLIB C版本的差异</h3>
 * <p>C版本使用列优先存储（Fortran风格），Java版本统一行优先。
 * 所有矩阵运算委托给 EJML 库，避免手撸数组运算导致的顺序错误。</p>
 */
public final class KalmanFilter {
    private KalmanFilter() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(KalmanFilter.class);

    /**
     * Kalman滤波测量更新。
     *
     * <p>对应RTKLIB filter()。根据双差残差v和设计矩阵H更新状态向量x和协方差P。</p>
     *
     * <p>计算步骤：</p>
     * <ol>
     *   <li>Ht = H^T</li>
     *   <li>PHt = P * Ht</li>
     *   <li>Q = H * PHt + R （新息协方差）</li>
     *   <li>K = PHt * Q^-1 （Kalman增益）</li>
     *   <li>x_new = x + K * v</li>
     *   <li>P_new = (I-K*H)*P*(I-K*H)^T + K*R*K^T （Joseph形式，数值稳定）</li>
     * </ol>
     *
     * @param x 状态向量 [n]，行优先，输入：预测值，输出：更新值
     * @param P 协方差矩阵 [n*n]，行优先，输入：预测值，输出：更新值
     * @param H 设计矩阵 [m*n]，行优先（H[obs*n+state]）
     * @param v 残差向量 [m]
     * @param R 观测协方差矩阵 [m*m]，行优先
     * @param n 状态维数
     * @param m 观测维数
     * @return 0:成功 -1:失败（如矩阵求逆失败）
     */
    public static int update(double[] x, double[] P, double[] H, double[] v, double[] R, int n, int m) {
        if (n <= 0 || m <= 0) {
            return -1;
        }

        try {
            int[] ix = new int[n];
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (x[i] != 0.0 && P[i * n + i] > 0.0) {
                    ix[k++] = i;
                }
            }
            if (k == 0) return 0;

            double[] xc = new double[k];
            double[] Pc = new double[k * k];
            double[] Hc = new double[m * k];
            for (int i = 0; i < k; i++) {
                xc[i] = x[ix[i]];
                for (int j = 0; j < k; j++) {
                    Pc[i * k + j] = P[ix[i] * n + ix[j]];
                }
                for (int j = 0; j < m; j++) {
                    Hc[j * k + i] = H[j * n + ix[i]];
                }
            }

            SimpleMatrix Xc = MatrixUtil.createMatrix(xc, k, 1);
            SimpleMatrix PcMat = MatrixUtil.createMatrix(Pc, k, k);
            SimpleMatrix HcMat = MatrixUtil.createMatrix(Hc, m, k);
            SimpleMatrix V = MatrixUtil.createMatrix(v, m, 1);
            SimpleMatrix RMat = MatrixUtil.createMatrix(R, m, m);

            if (LOG.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("KF update: n=%d m=%d k=%d\n", n, m, k));
                sb.append("ix=[");
                for (int i = 0; i < k; i++) sb.append(ix[i]).append(" ");
                sb.append("]\n");
                sb.append("xc=[");
                for (int i = 0; i < Math.min(k, 10); i++) sb.append(String.format("%.6f ", xc[i]));
                sb.append("]\n");
                sb.append("v=[");
                for (int i = 0; i < m; i++) sb.append(String.format("%.4f ", v[i]));
                sb.append("]\n");
                sb.append("Hc_full=[\n");
                for (int i = 0; i < m; i++) {
                    sb.append(String.format("  obs%d: [", i));
                    for (int j = 0; j < k; j++) sb.append(String.format("%.6f ", Hc[i * k + j]));
                    sb.append("]\n");
                }
                sb.append("]\n");
                sb.append("Pc_diag=[");
                for (int i = 0; i < Math.min(k, 10); i++) sb.append(String.format("%.4f ", Pc[i * k + i]));
                sb.append("]\n");
                sb.append("R_diag=[");
                for (int i = 0; i < m; i++) sb.append(String.format("%.6f ", R[i * m + i]));
                sb.append("]\n");
                LOG.debug(sb.toString());
            }

            SimpleMatrix Hct = MatrixUtil.transpose(HcMat);
            SimpleMatrix PHt = MatrixUtil.multiply(PcMat, Hct);
            SimpleMatrix HPHt = MatrixUtil.multiply(HcMat, PHt);
            SimpleMatrix S = MatrixUtil.add(HPHt, RMat);
            SimpleMatrix Sinv = MatrixUtil.invert(S);
            SimpleMatrix K = MatrixUtil.multiply(PHt, Sinv);

            SimpleMatrix KV = MatrixUtil.multiply(K, V);
            SimpleMatrix XcNew = MatrixUtil.add(Xc, KV);

            if (LOG.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                sb.append("K(row0)[0:9]=[");
                for (int i = 0; i < Math.min(k, 10); i++) sb.append(String.format("%.8f ", K.get(0, i)));
                sb.append("]\n");
                sb.append("KV=[");
                for (int i = 0; i < Math.min(k, 10); i++) sb.append(String.format("%.6f ", KV.get(i, 0)));
                sb.append("]\n");
                sb.append("dx=[");
                for (int i = 0; i < Math.min(k, 10); i++) sb.append(String.format("%.6f ", XcNew.get(i, 0) - xc[i]));
                sb.append("]\n");
                LOG.debug(sb.toString());
            }

            SimpleMatrix Ic = MatrixUtil.identity(k);
            SimpleMatrix KHc = MatrixUtil.multiply(K, HcMat);
            SimpleMatrix I_KH = MatrixUtil.subtract(Ic, KHc);
            SimpleMatrix P_new = MatrixUtil.multiply(I_KH, PcMat);

            if (LOG.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder();
                sb.append("P_new_diag=[");
                for (int i = 0; i < Math.min(k, 10); i++) sb.append(String.format("%.4f ", P_new.get(i, i)));
                sb.append("]\n");
                sb.append("I_KH_diag=[");
                for (int i = 0; i < Math.min(k, 10); i++) sb.append(String.format("%.6f ", I_KH.get(i, i)));
                sb.append("]\n");
                LOG.debug(sb.toString());
            }

            for (int i = 0; i < k; i++) {
                x[ix[i]] = XcNew.get(i, 0);
                for (int j = 0; j < k; j++) {
                    P[ix[i] * n + ix[j]] = P_new.get(i, j);
                }
            }

            return 0;
        } catch (Exception e) {
            LOG.warn("Kalman filter update failed: n={} m={} msg={}", n, m, e.getMessage());
            return -1;
        }
    }

    /**
     * Kalman滤波时间更新（预测）。
     *
     * <p>根据状态转移矩阵F和过程噪声Q预测下一时刻的状态和协方差。</p>
     *
     * @param x 状态向量 [n]，行优先，输入：当前状态，输出：预测状态
     * @param P 协方差矩阵 [n*n]，行优先，输入：当前协方差，输出：预测协方差
     * @param F 状态转移矩阵 [n*n]，行优先
     * @param Q 过程噪声协方差 [n*n]，行优先
     * @param n 状态维数
     */
    public static void predict(double[] x, double[] P, double[] F, double[] Q, int n) {
        SimpleMatrix X = MatrixUtil.createMatrix(x, n, 1);
        SimpleMatrix PMat = MatrixUtil.createMatrix(P, n, n);
        SimpleMatrix FMat = MatrixUtil.createMatrix(F, n, n);
        SimpleMatrix QMat = MatrixUtil.createMatrix(Q, n, n);

        SimpleMatrix X_pred = MatrixUtil.multiply(FMat, X);

        SimpleMatrix FP = MatrixUtil.multiply(FMat, PMat);
        SimpleMatrix FPFt = MatrixUtil.multiply(FP, MatrixUtil.transpose(FMat));
        SimpleMatrix P_pred = MatrixUtil.add(FPFt, QMat);

        MatrixUtil.copyMatrix(X_pred, x);
        MatrixUtil.copyMatrix(P_pred, P);
    }
}