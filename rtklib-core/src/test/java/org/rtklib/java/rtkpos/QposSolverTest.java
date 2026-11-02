package org.rtklib.java.rtkpos;

import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.MatrixUtil;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [Java扩展] QposSolver 单元测试。
 *
 * <p>钉死路线A的核心语义：</p>
 * <ul>
 *   <li>FIX 分支列集为 {@code A ∩ [0,NR)}，当 NR&gt;3 时电离层/对流层不确定性
 *       必须通过求逆边缘化传播到位置块（而非只取前 3 列）；</li>
 *   <li>FLOAT 分支列集保留模糊度列，位置块等于全活跃列法方程逆的前 3×3；</li>
 *   <li>必须用完整 {@code R⁻¹} 而非 {@code diag(R)⁻¹}；</li>
 *   <li>零列不进列集，位置列恒在列集最前。</li>
 * </ul>
 */
class QposSolverTest {

    /** 构造 SPD 的观测协方差：R = A·Aᵀ/nv + εI（模拟 ddcov 的块相关结构） */
    private static double[] buildSpdR(int nv, long seed) {
        Random rnd = new Random(seed);
        double[] a = new double[nv * nv];
        for (int i = 0; i < a.length; i++) a[i] = rnd.nextGaussian();
        double[] R = new double[nv * nv];
        for (int i = 0; i < nv; i++) {
            for (int j = 0; j < nv; j++) {
                double s = 0.0;
                for (int k = 0; k < nv; k++) s += a[i * nv + k] * a[j * nv + k];
                R[i * nv + j] = s / nv + (i == j ? 0.01 : 0.0);
            }
        }
        return R;
    }

    /** 逐行给指定列填随机值，其余列为 0 */
    private static double[] buildH(int nv, int nx, int[] activeCols, long seed) {
        Random rnd = new Random(seed);
        double[] H = new double[nv * nx];
        for (int i = 0; i < nv; i++) {
            for (int c : activeCols) H[i * nx + c] = rnd.nextGaussian();
        }
        return H;
    }

    /** 参考实现：对 H 的指定列构造 H_Aᵀ R⁻¹ H_A */
    private static double[] normal(double[] H, double[] R, int nv, int nx, int[] cols) {
        SimpleMatrix Rm = MatrixUtil.createMatrix(R, nv, nv);
        SimpleMatrix HAm = new SimpleMatrix(nv, cols.length);
        for (int i = 0; i < nv; i++) {
            for (int k = 0; k < cols.length; k++) {
                HAm.set(i, k, H[i * nx + cols[k]]);
            }
        }
        return MatrixUtil.toArray(HAm.transpose().mult(Rm.solve(HAm)));
    }

    /** 参考实现：法方程求逆后取前 3×3 位置块 */
    private static double[] leading3x3(double[] N, int n) {
        SimpleMatrix inv = MatrixUtil.createMatrix(N, n, n).invert();
        double[] q = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) q[i * 3 + j] = inv.get(i, j);
        }
        return q;
    }

    private static double relDiff(double[] a, double[] b) {
        double max = 0.0;
        double denom = 0.0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
            denom = Math.max(denom, Math.abs(a[i]));
        }
        return denom == 0.0 ? max : max / denom;
    }

    @Test
    void colsAlwaysStartWithPositionColumns() {
        int nv = 8;
        int nx = 7;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 4, 6}, 11L);
        double[] R = buildSpdR(nv, 11L);

        QposSolver.ReducedNormal rn = QposSolver.build(H, R, nv, nx);
        assertNotNull(rn);
        assertEquals(0, rn.cols[0]);
        assertEquals(1, rn.cols[1]);
        assertEquals(2, rn.cols[2]);
        assertEquals(5, rn.n, "活跃列应为 {0,1,2,4,6}");
        assertEquals(4, rn.cols[3]);
        assertEquals(6, rn.cols[4]);
    }

    @Test
    void zeroColumnsAreExcluded() {
        int nv = 8;
        int nx = 7;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3}, 21L);
        double[] R = buildSpdR(nv, 21L);

        QposSolver.ReducedNormal rn = QposSolver.build(H, R, nv, nx);
        assertNotNull(rn);
        assertEquals(4, rn.n, "第 4/5/6 列为全零，不应进列集");

        int[] all = {0, 1, 2, 3};
        double[] ref = leading3x3(normal(H, R, nv, nx, all), 4);
        assertArrayEquals(ref, QposSolver.floatCov(rn), 1e-10);
    }

    /**
     * NR=3 时 FIX 退化为 {0,1,2}，与"只取前 3 列"数值一致。
     */
    @Test
    void fixDegeneratesToPositionOnlyWhenNrIsThree() {
        int nv = 12;
        int nx = 7;
        int nr = 3;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3, 4, 5, 6}, 31L);
        double[] R = buildSpdR(nv, 31L);

        double[] expected = leading3x3(normal(H, R, nv, nx, new int[]{0, 1, 2}), 3);
        double[] actual = QposSolver.compute(H, R, nv, nx, nr, false);
        assertNotNull(actual);
        assertArrayEquals(expected, actual, 1e-10);
    }

    /**
     * 关键用例：NR=5（3 位置 + 2 电离层）时，FIX 必须对 5×5 法方程求逆后取位置块，
     * 而不是只取前 3 列。后者会忽略电离层不确定性传播，系统性偏乐观。
     */
    @Test
    void fixMarginalizesIonoUncertaintyWhenNrGreaterThanThree() {
        int nv = 30;
        int nx = 7;
        int nr = 5;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3, 4, 5, 6}, 41L);
        double[] R = buildSpdR(nv, 41L);

        int[] nonAmb = {0, 1, 2, 3, 4};
        double[] expected = leading3x3(normal(H, R, nv, nx, nonAmb), 5);
        double[] actual = QposSolver.compute(H, R, nv, nx, nr, false);
        assertNotNull(actual);
        assertArrayEquals(expected, actual, 1e-10);

        double[] naive = leading3x3(normal(H, R, nv, nx, new int[]{0, 1, 2}), 3);
        assertTrue(relDiff(naive, actual) > 1e-3,
                "只取前3列与边缘化电离层必须给出可区分的协方差，relDiff=" + relDiff(naive, actual));
    }

    /**
     * FLOAT：位置块 = 全活跃列法方程逆的前 3×3。
     */
    @Test
    void floatEqualsFullNormalLeadingBlock() {
        int nv = 20;
        int nx = 8;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3, 4, 5, 6, 7}, 51L);
        double[] R = buildSpdR(nv, 51L);

        int[] all = {0, 1, 2, 3, 4, 5, 6, 7};
        double[] expected = leading3x3(normal(H, R, nv, nx, all), 8);
        double[] actual = QposSolver.compute(H, R, nv, nx, 8, true);
        assertNotNull(actual);
        assertArrayEquals(expected, actual, 1e-10);
    }

    /**
     * Schur 补交叉校验：全逆的位置块 == (N_ff - N_fa N_aa⁻¹ N_af)⁻¹。
     * 这条恒等式正是"模糊度不确定性通过求逆传播到位置块"的数学表述。
     */
    @Test
    void floatLeadingBlockEqualsInverseOfSchurComplement() {
        int nv = 20;
        int nx = 8;
        int np = 3;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3, 4, 5, 6, 7}, 61L);
        double[] R = buildSpdR(nv, 61L);

        int[] all = {0, 1, 2, 3, 4, 5, 6, 7};
        double[] N = normal(H, R, nv, nx, all);

        double[] Nff = new double[9];
        double[] Nfa = new double[3 * (nx - np)];
        double[] Naa = new double[(nx - np) * (nx - np)];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) Nff[i * 3 + j] = N[i * nx + j];
            for (int j = np; j < nx; j++) Nfa[i * (nx - np) + (j - np)] = N[i * nx + j];
        }
        for (int i = np; i < nx; i++) {
            for (int j = np; j < nx; j++) Naa[(i - np) * (nx - np) + (j - np)] = N[i * nx + j];
        }

        SimpleMatrix Nffm = MatrixUtil.createMatrix(Nff, 3, 3);
        SimpleMatrix Nfam = MatrixUtil.createMatrix(Nfa, 3, nx - np);
        SimpleMatrix Naam = MatrixUtil.createMatrix(Naa, nx - np, nx - np);
        SimpleMatrix schur = Nffm.minus(Nfam.mult(Naam.invert()).mult(Nfam.transpose()));
        double[] expected = MatrixUtil.toArray(schur.invert());

        double[] actual = QposSolver.compute(H, R, nv, nx, nx, true);
        assertNotNull(actual);
        assertArrayEquals(expected, actual, 1e-8);
    }

    /**
     * 完整 R⁻¹ vs diag(R)⁻¹ 必须可区分：ddcov 的 R 带参考星相关性。
     */
    @Test
    void usesFullInverseNotDiagonalApproximation() {
        int nv = 10;
        int nx = 7;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3, 4, 5, 6}, 71L);

        double[] R = new double[nv * nv];
        for (int i = 0; i < nv; i++) {
            for (int j = 0; j < nv; j++) R[i * nv + j] = (i == j) ? 1.0 : 0.9;
        }

        double[] actual = QposSolver.compute(H, R, nv, nx, nx, true);
        assertNotNull(actual);

        double[] Rdiag = new double[nv * nv];
        for (int i = 0; i < nv; i++) Rdiag[i * nv + i] = 1.0;
        double[] diagApprox = QposSolver.compute(H, Rdiag, nv, nx, nx, true);
        assertNotNull(diagApprox);

        assertTrue(relDiff(diagApprox, actual) > 1e-2,
                "对角近似与完整R⁻¹必须可区分，relDiff=" + relDiff(diagApprox, actual));
    }

    @Test
    void returnsNullForSingularR() {
        int nv = 8;
        int nx = 7;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3}, 81L);
        double[] R = new double[nv * nv];

        assertNull(QposSolver.build(H, R, nv, nx));
        assertNull(QposSolver.compute(H, R, nv, nx, nx, true));
    }

    @Test
    void returnsNullWhenNvTooSmall() {
        int nv = 3;
        int nx = 7;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3}, 91L);
        double[] R = buildSpdR(nv, 91L);

        assertNull(QposSolver.build(H, R, nv, nx),
                "nv<4 无法定出 3 维位置，与 computeDiagData 的门槛保持一致");
    }

    @Test
    void returnsNullForNullInputs() {
        assertNull(QposSolver.build(null, new double[64], 8, 7));
        assertNull(QposSolver.build(new double[56], null, 8, 7));
        assertNull(QposSolver.fixCov(null, 3));
        assertNull(QposSolver.floatCov(null));
    }

    /**
     * FIX 且 nr 超过活跃列数时，nf 截断到活跃列数，不应越界。
     */
    @Test
    void fixClampsToActiveColumnCount() {
        int nv = 12;
        int nx = 10;
        int nr = 9;
        double[] H = buildH(nv, nx, new int[]{0, 1, 2, 3}, 101L);
        double[] R = buildSpdR(nv, 101L);

        double[] expected = leading3x3(normal(H, R, nv, nx, new int[]{0, 1, 2, 3}), 4);
        double[] actual = QposSolver.compute(H, R, nv, nx, nr, false);
        assertNotNull(actual);
        assertArrayEquals(expected, actual, 1e-10);
    }
}