package org.rtklib.java.rtkpos;

import org.ejml.data.SingularMatrixException;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;

/**
 * [Java扩展] 路线A：单历元位置协方差 {@code Q_pos} 求解器。
 *
 * <p>用途：把 RTKLIB Kalman 后验位置协方差 {@code Pa} 替换为"只反映当前历元
 * 双差观测"的位置协方差，从而切断跨历元相关性污染。核心是解一次归约法方程。</p>
 *
 * <h3>数学定义</h3>
 * <p>给定同一次 {@code ddres} 产生的设计矩阵 {@code H[nv×nx]} 与观测协方差
 * {@code R[nv×nv]}（原始 ddcov 版本，非 IGGIII 改写版）：</p>
 *
 * <pre>
 *   A   = 扫 H 得到的活跃列集 ∪ {0,1,2}          （升序绝对列索引）
 *   N_A = H_Aᵀ R⁻¹ H_A                          （|A| × |A|）
 * </pre>
 *
 * <p>FLOAT 分支：模糊度仍是未知数，{@code Q_A = N_A⁻¹}，位置块取 {@code Q_A[0:3,0:3]}。</p>
 *
 * <p>FIX 分支：模糊度已固定为整数，视为已知常数，从列集中剔除，即
 * {@code A_fix = A ∩ [0, NR)}。{@code N_f = H_{A_fix}ᵀ R⁻¹ H_{A_fix}}，
 * {@code Q_pos = N_f⁻¹[0:3,0:3]}。</p>
 *
 * <p><b>FIX 为什么不能用 {@code (H_posᵀR⁻¹H_pos)⁻¹}：</b>那是
 * {@code A_fix = {0,1,2}} 的特例。当 {@code ionoopt=EST}、{@code tropopt>=EST} 或
 * GLONASS autocal 启用时，电离层/对流层参数仍参与滤波，其不确定性必须通过求逆
 * 边缘化传播到位置块。只取前 3 列会得到系统性偏小的 {@code Q_pos}（静默偏乐观）。</p>
 *
 * <p><b>为什么扫 H 而不是查 {@link RtkCore#IB}：</b>{@code IB()} 是纯索引函数，
 * 感知不到 {@code ddres} 在填 H 之前的四种跳过条件（参考星、{@code sysMap} 分组、
 * {@code validobs} 失败、频率无效），会纳入全零列导致法方程奇异。扫 H 只依赖
 * H 的实际稀疏结构，天然与真实观测集对齐。</p>
 *
 * <p><b>为什么用完整 R⁻¹ 而不是 {@code diag(R)⁻¹}：</b>{@code ddcov} 产生的 R
 * 不是对角阵（同一组双差共享参考星，存在相关性）。用对角近似会低估观测信息量，
 * 使 {@code Q_pos} 系统性偏大。</p>
 *
 * <p><b>R 必须是原始 ddcov 版本：</b>调用点应位于 {@code ddres} 之后、
 * {@code applyIggiii} 之前，此时 H 与 R 是同一次 ddcov 的产物，严格对齐。</p>
 */
public final class QposSolver {

    private QposSolver() {
    }

    /**
     * 归约法方程：活跃列集与对应的 {@code N_A}。
     *
     * <p>{@code cols} 按列索引升序排列，且恒以 {@code {0,1,2}} 开头（位置分量），
     * 因此位置块始终是逆矩阵的前 3×3 子块。</p>
     */
    public static final class ReducedNormal {
        /** 活跃列（升序绝对列索引），恒包含 0、1、2 */
        public final int[] cols;
        /** 归约法方程 N_A，行优先，维度 n×n，n = cols.length */
        public final double[] N;
        /** 活跃列数 */
        public final int n;

        ReducedNormal(int[] cols, double[] N) {
            this.cols = cols;
            this.N = N;
            this.n = cols.length;
        }
    }

    /**
     * 扫 H 找活跃列并构造归约法方程 {@code N_A = H_Aᵀ R⁻¹ H_A}。
     *
     * @param H  设计矩阵（行优先，nv×nx）
     * @param R  观测协方差（行优先，nv×nv，必须是原始 ddcov 版本）
     * @param nv 双差观测数
     * @param nx 状态向量维度
     * @return 归约法方程；输入非法、R 奇异或结果非有限时返回 null
     */
    public static ReducedNormal build(double[] H, double[] R, int nv, int nx) {
        if (H == null || R == null || nv < 4 || nx < 3) return null;

        boolean[] active = new boolean[nx];
        active[0] = true;
        active[1] = true;
        active[2] = true;
        for (int i = 0; i < nv; i++) {
            int row = i * nx;
            for (int c = 3; c < nx; c++) {
                if (H[row + c] != 0.0) active[c] = true;
            }
        }

        int n = 0;
        for (int c = 0; c < nx; c++) {
            if (active[c]) n++;
        }
        if (n < 3) return null;

        int[] cols = new int[n];
        int p = 0;
        for (int c = 0; c < nx; c++) {
            if (active[c]) cols[p++] = c;
        }

        double[] HA = new double[nv * n];
        for (int i = 0; i < nv; i++) {
            int src = i * nx;
            int dst = i * n;
            for (int k = 0; k < n; k++) {
                HA[dst + k] = H[src + cols[k]];
            }
        }

        SimpleMatrix Nm;
        try {
            SimpleMatrix Rm = MatrixUtil.createMatrix(R, nv, nv);
            SimpleMatrix HAm = MatrixUtil.createMatrix(HA, nv, n);
            SimpleMatrix RinvHA = Rm.solve(HAm);
            Nm = HAm.transpose().mult(RinvHA);
        } catch (SingularMatrixException e) {
            return null;
        }

        double[] N = MatrixUtil.toArray(Nm);
        for (double v : N) {
            if (!Double.isFinite(v)) return null;
        }
        return new ReducedNormal(cols, N);
    }

    /**
     * FIX 分支：剔除模糊度列（{@code cols >= nr}），对 {@code A ∩ [0,nr)} 求逆取位置块。
     *
     * @param rn 归约法方程
     * @param nr 非模糊度状态维数，必须传 {@link RtkCore#NR} 的结果。
     *           注意不要与 {@code relpos} 形参中的 {@code nr}（基准站观测数）混淆。
     * @return 行优先 9 元素位置协方差；奇异或维数不足时返回 null
     */
    public static double[] fixCov(ReducedNormal rn, int nr) {
        if (rn == null || nr < 3) return null;

        int nf = 0;
        while (nf < rn.n && rn.cols[nf] < nr) nf++;
        if (nf < 3) return null;

        double[] Nf = new double[nf * nf];
        for (int i = 0; i < nf; i++) {
            int src = i * rn.n;
            int dst = i * nf;
            for (int j = 0; j < nf; j++) {
                Nf[dst + j] = rn.N[src + j];
            }
        }
        return leading3x3Inverse(Nf, nf);
    }

    /**
     * FLOAT 分支：模糊度仍为未知数，对全部活跃列求逆取位置块。
     *
     * @param rn 归约法方程
     * @return 行优先 9 元素位置协方差；奇异时返回 null
     */
    public static double[] floatCov(ReducedNormal rn) {
        if (rn == null || rn.n < 3) return null;
        return leading3x3Inverse(rn.N, rn.n);
    }

    /**
     * 便捷入口：一次完成"扫 H + 构造法方程 + 求逆取块"。
     *
     * @param includeAmbiguity true=FLOAT（保留模糊度列），false=FIX（列集与 [0,nr) 求交）
     */
    public static double[] compute(double[] H, double[] R, int nv, int nx,
                                   int nr, boolean includeAmbiguity) {
        ReducedNormal rn = build(H, R, nv, nx);
        if (rn == null) return null;
        return includeAmbiguity ? floatCov(rn) : fixCov(rn, nr);
    }

    /**
     * 对 n×n 法方程求逆，返回前 3×3 位置块（行优先 9 元素）。
     *
     * <p>{@code cols} 升序且以 {0,1,2} 开头，所以逆矩阵的前 3×3 子块就是位置的
     * 边缘协方差（其余状态的不确定性已通过求逆自然传播进来）。</p>
     */
    private static double[] leading3x3Inverse(double[] N, int n) {
        SimpleMatrix inv;
        try {
            inv = MatrixUtil.createMatrix(N, n, n).invert();
        } catch (SingularMatrixException e) {
            return null;
        }

        double[] q = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                q[i * 3 + j] = inv.get(i, j);
            }
        }
        for (double v : q) {
            if (!Double.isFinite(v)) return null;
        }
        return q;
    }
}