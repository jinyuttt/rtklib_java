package org.rtklib.java.ambiguity;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;

/**
 * LAMBDA/MLAMBDA整周模糊度固定算法。
 *
 * <p>对应RTKLIB lambda.c。实现整数最小二乘估计，用于RTK模糊度固定。</p>
 *
 * <h3>算法流程</h3>
 * <ol>
 *   <li>LD分解：Q = L' * diag(D) * L</li>
 *   <li>LAMBDA降相关：通过整数高斯变换和置换，使搜索空间更紧凑</li>
 *   <li>MLAMBDA搜索：在降相关后的空间中搜索最优整数解</li>
 *   <li>逆变换：将搜索结果变换回原始空间 F = Z'\E</li>
 * </ol>
 *
 * <h3>参考文献</h3>
 * <ul>
 *   <li>[1] P.J.G.Teunissen, The least-square ambiguity decorrelation adjustment:
 *       a method for fast GPS ambiguity estimation, J.Geodesy, Vol.70, 65-82, 1995</li>
 *   <li>[2] X.-W.Chang, X.Yang, T.Zhou, MLAMBDA: A modified LAMBDA method for
 *       integer least-squares estimation. J Geodesy 2005;79:552-565.</li>
 * </ul>
 *
 * <h3>矩阵存储约定（行优先）</h3>
 * <ul>
 *   <li>Q[n*n]：协方差矩阵，行优先 Q[i*n+j]</li>
 *   <li>L[n*n]：下三角矩阵，行优先 L[i*n+j]</li>
 *   <li>Z[n*n]：变换矩阵，行优先 Z[i*n+j]</li>
 *   <li>F[n*m]：固定解，行优先 F[i*m+j]</li>
 * </ul>
 *
 * <h3>与RTKLIB C版本的关键差异</h3>
 * <ul>
 *   <li>C版本使用列优先（Fortran风格），Java版本统一使用行优先</li>
 *   <li>矩阵运算（Z'*a, Z'\E）使用EJML通过MatrixUtil封装</li>
 *   <li>元素级运算（gauss, perm, search）保持1D数组操作，索引从列优先转为行优先</li>
 * </ul>
 */
public final class Lambda {
    private Lambda() {
    }

    private static final int LOOPMAX = 10000;

    /**
     * LAMBDA/MLAMBDA整数最小二乘估计。
     *
     * <p>对应RTKLIB lambda()。对浮点模糊度进行整数估计，返回m个最优固定解。</p>
     *
     * <p>计算步骤：</p>
     * <ol>
     *   <li>LD分解：Q = L' * diag(D) * L</li>
     *   <li>LAMBDA降相关：reduction(n, L, D, Z)</li>
     *   <li>变换浮点模糊度：z = Z' * a</li>
     *   <li>MLAMBDA搜索：search(n, m, L, D, z, E, s)</li>
     *   <li>逆变换固定解：F = Z'\E</li>
     * </ol>
     *
     * @param n 浮点参数个数（双差模糊度数）
     * @param m 固定解个数（通常为2，用于ratio检验）
     * @param a 浮点模糊度 [n]，输入
     * @param Q 协方差矩阵 [n*n]，行优先，输入
     * @param F 固定解 [n*m]，行优先，输出（F[i*m+j] = 第i个模糊度的第j个固定值）
     * @param s 残差平方和 [m]，输出（s[0]=最优, s[1]=次优, 用于ratio检验）
     * @return 0:成功 -1:LD分解失败 -2:搜索溢出
     */
    public static int lambda(int n, int m, double[] a, double[] Q, double[] F, double[] s) {
        if (n <= 0 || m <= 0) return -1;

        double[] L = new double[n * n];
        double[] D = new double[n];
        double[] Z = new double[n * n];
        double[] z = new double[n];
        double[] E = new double[n * m];

        for (int i = 0; i < n; i++) Z[i * n + i] = 1.0;

        int info;
        if ((info = factorLD(n, Q, L, D)) != 0) return info;

        reduction(n, L, D, Z);

        SimpleMatrix ZMat = MatrixUtil.createMatrix(Z, n, n);
        SimpleMatrix aMat = MatrixUtil.createMatrix(a, n, 1);
        SimpleMatrix Zt = MatrixUtil.transpose(ZMat);
        SimpleMatrix zMat = MatrixUtil.multiply(Zt, aMat);
        MatrixUtil.copyMatrix(zMat, z);

        if ((info = search(n, m, L, D, z, E, s)) != 0) return info;

        SimpleMatrix EMat = MatrixUtil.createMatrix(E, n, m);
        SimpleMatrix FMat = MatrixUtil.solve(Zt, EMat);
        MatrixUtil.copyMatrix(FMat, F);

        return 0;
    }

    /**
     * LD分解：Q = L' * diag(D) * L。
     *
     * <p>对应RTKLIB LD()。将协方差矩阵Q分解为下三角矩阵L和对角矩阵D。</p>
     *
     * <p><b>行优先索引</b>：C版本 L[i+j*n]（列优先）→ Java版本 L[i*n+j]（行优先）</p>
     *
     * @param n   矩阵维数
     * @param Q   协方差矩阵 [n*n]，行优先，输入
     * @param L   下三角矩阵 [n*n]，行优先，输出
     * @param D   对角元素 [n]，输出
     * @return 0:成功 -1:矩阵非正定
     */
    static int factorLD(int n, double[] Q, double[] L, double[] D) {
        int i, j, k, info = 0;
        double a;
        double[] A = new double[n * n];

        System.arraycopy(Q, 0, A, 0, n * n);

        for (i = n - 1; i >= 0; i--) {
            if ((D[i] = A[i * n + i]) <= 0.0) {
                info = -1;
                break;
            }
            a = Math.sqrt(D[i]);
            for (j = 0; j <= i; j++) L[i * n + j] = A[i * n + j] / a;
            for (j = 0; j <= i - 1; j++) {
                for (k = 0; k <= j; k++) {
                    A[j * n + k] -= L[i * n + k] * L[i * n + j];
                }
            }
            for (j = 0; j <= i; j++) L[i * n + j] /= L[i * n + i];
        }

        return info;
    }

    /**
     * 整数高斯变换。
     *
     * <p>对应RTKLIB gauss()。对L和Z矩阵的第i列和第j列进行整数高斯变换，
     * 消除L[i][j]的小数部分。</p>
     *
     * <p><b>行优先索引</b>：C版本 L[k+j*n]（列优先）→ Java版本 L[k*n+j]（行优先）</p>
     *
     * @param n 矩阵维数
     * @param L 下三角矩阵 [n*n]，行优先，输入/输出
     * @param Z 变换矩阵 [n*n]，行优先，输入/输出
     * @param i 行索引
     * @param j 列索引（j < i）
     */
    static void gauss(int n, double[] L, double[] Z, int i, int j) {
        int k, mu;
        if ((mu = (int) Math.round(L[i * n + j])) != 0) {
            for (k = i; k < n; k++) L[k * n + j] -= (double) mu * L[k * n + i];
            for (k = 0; k < n; k++) Z[k * n + j] -= (double) mu * Z[k * n + i];
        }
    }

    /**
     * 置换操作。
     *
     * <p>对应RTKLIB perm()。交换L矩阵的第j列和第j+1列，同时更新D和Z。</p>
     *
     * <p><b>行优先索引</b>：C版本 L[j+k*n]（列优先）→ Java版本 L[j*n+k]（行优先）</p>
     *
     * @param n   矩阵维数
     * @param L   下三角矩阵 [n*n]，行优先，输入/输出
     * @param D   对角元素 [n]，输入/输出
     * @param j   置换位置
     * @param del D[j]+L[j+1][j]^2*D[j+1]
     * @param Z   变换矩阵 [n*n]，行优先，输入/输出
     */
    static void perm(int n, double[] L, double[] D, int j, double del, double[] Z) {
        int k;
        double eta, lam, a0, a1;

        eta = D[j] / del;
        lam = D[j + 1] * L[(j + 1) * n + j] / del;
        D[j] = eta * D[j + 1];
        D[j + 1] = del;
        for (k = 0; k <= j - 1; k++) {
            a0 = L[j * n + k];
            a1 = L[(j + 1) * n + k];
            L[j * n + k] = -L[(j + 1) * n + j] * a0 + a1;
            L[(j + 1) * n + k] = eta * a0 + lam * a1;
        }
        L[(j + 1) * n + j] = lam;
        for (k = j + 2; k < n; k++) {
            double tmp = L[k * n + j];
            L[k * n + j] = L[k * n + j + 1];
            L[k * n + j + 1] = tmp;
        }
        for (k = 0; k < n; k++) {
            double tmp = Z[k * n + j];
            Z[k * n + j] = Z[k * n + j + 1];
            Z[k * n + j + 1] = tmp;
        }
    }

    /**
     * LAMBDA降相关。
     *
     * <p>对应RTKLIB reduction()。通过整数高斯变换和置换操作，
     * 使协方差矩阵的条件数减小，搜索空间更紧凑。</p>
     *
     * @param n 矩阵维数
     * @param L 下三角矩阵 [n*n]，行优先，输入/输出
     * @param D 对角元素 [n]，输入/输出
     * @param Z 变换矩阵 [n*n]，行优先，输入/输出
     */
    static void reduction(int n, double[] L, double[] D, double[] Z) {
        int i, j, k;
        double del;

        j = n - 2;
        k = n - 2;
        while (j >= 0) {
            if (j <= k) {
                for (i = j + 1; i < n; i++) gauss(n, L, Z, i, j);
            }
            del = D[j] + L[(j + 1) * n + j] * L[(j + 1) * n + j] * D[j + 1];
            if (del + 1E-6 < D[j + 1]) {
                perm(n, L, D, j, del, Z);
                k = j;
                j = n - 2;
            } else {
                j--;
            }
        }
    }

    /**
     * MLAMBDA搜索。
     *
     * <p>对应RTKLIB search()。在降相关后的空间中搜索m个最优整数向量，
     * 返回固定解和对应的残差平方和。</p>
     *
     * <p>搜索策略：从最高维度开始，逐步向下搜索，剪枝掉残差过大的候选解。</p>
     *
     * <p><b>行优先索引</b>：</p>
     * <ul>
     *   <li>S[k*n+i]：C版本 S[k+i*n]（列优先）→ 行优先</li>
     *   <li>L[(k+1)*n+i]：C版本 L[k+1+i*n]（列优先）→ 行优先</li>
     *   <li>zn[i*m+nn]：C版本 zn[i+nn*n]（列优先）→ 行优先</li>
     * </ul>
     *
     * @param n  浮点参数个数
     * @param m  固定解个数
     * @param L  变换后下三角矩阵 [n*n]，行优先
     * @param D  变换后对角元素 [n]
     * @param zs 变换后浮点模糊度 [n]
     * @param zn 固定解 [n*m]，行优先，输出
     * @param s  残差平方和 [m]，输出
     * @return 0:成功 -2:搜索溢出
     */
    static int search(int n, int m, double[] L, double[] D,
                      double[] zs, double[] zn, double[] s) {
        int i, j, k, c, nn = 0, imax = 0;
        double newdist, maxdist = 1E99, y;
        double[] S = new double[n * n];
        double[] dist = new double[n];
        double[] zb = new double[n];
        double[] z = new double[n];
        double[] step = new double[n];

        k = n - 1;
        dist[k] = 0.0;
        zb[k] = zs[k];
        z[k] = Math.round(zb[k]);
        y = zb[k] - z[k];
        step[k] = y <= 0.0 ? -1.0 : 1.0;

        for (c = 0; c < LOOPMAX; c++) {
            newdist = dist[k] + y * y / D[k];
            if (newdist < maxdist) {
                if (k != 0) {
                    dist[--k] = newdist;
                    for (i = 0; i <= k; i++) {
                        S[k * n + i] = S[(k + 1) * n + i] + (z[k + 1] - zb[k + 1]) * L[(k + 1) * n + i];
                    }
                    zb[k] = zs[k] + S[k * n + k];
                    z[k] = Math.round(zb[k]);
                    y = zb[k] - z[k];
                    step[k] = y <= 0.0 ? -1.0 : 1.0;
                } else {
                    if (nn < m) {
                        if (nn == 0 || newdist > s[imax]) imax = nn;
                        for (i = 0; i < n; i++) zn[i * m + nn] = z[i];
                        s[nn++] = newdist;
                    } else {
                        if (newdist < s[imax]) {
                            for (i = 0; i < n; i++) zn[i * m + imax] = z[i];
                            s[imax] = newdist;
                            for (i = 0, imax = 0; i < m; i++) {
                                if (s[imax] < s[i]) imax = i;
                            }
                        }
                        maxdist = s[imax];
                    }
                    z[0] += step[0];
                    y = zb[0] - z[0];
                    step[0] = -step[0] - (step[0] <= 0.0 ? -1.0 : 1.0);
                }
            } else {
                if (k == n - 1) break;
                else {
                    k++;
                    z[k] += step[k];
                    y = zb[k] - z[k];
                    step[k] = -step[k] - (step[k] <= 0.0 ? -1.0 : 1.0);
                }
            }
        }

        for (i = 0; i < m - 1; i++) {
            for (j = i + 1; j < m; j++) {
                if (s[i] < s[j]) continue;
                double tmp = s[i];
                s[i] = s[j];
                s[j] = tmp;
                for (k = 0; k < n; k++) {
                    tmp = zn[k * m + i];
                    zn[k * m + i] = zn[k * m + j];
                    zn[k * m + j] = tmp;
                }
            }
        }

        if (c >= LOOPMAX) {
            return -2;
        }
        return 0;
    }
}