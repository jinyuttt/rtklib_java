package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [Java扩展] computeHPos 回归测试。
 *
 * <p>覆盖历史缺陷：内层循环上界误写为 nv（应为 3），导致
 * HposT_W[k*nv+j] 与 NInv[i*3+k] 越界，异常被吞后 hPos 恒为 null。</p>
 *
 * <p>同时钉死输出维度为 3×nv（而非 3×3），因为 hPos 的数学定义是
 * N⁻¹H_posᵀW，列数必须等于观测数 nv。</p>
 */
class RtkCoreComputeHPosTest {

    private static double[] invokeComputeHPos(double[] H, double[] R, int nv, int nx)
            throws Exception {
        Method m = RtkCore.class.getDeclaredMethod(
                "computeHPos", double[].class, double[].class, int.class, int.class);
        m.setAccessible(true);
        try {
            return (double[]) m.invoke(null, H, R, nv, nx);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private static double[] buildH(int nv, int nx, long seed) {
        Random rnd = new Random(seed);
        double[] H = new double[nv * nx];
        for (int i = 0; i < nv; i++) {
            for (int k = 0; k < nx; k++) {
                H[i * nx + k] = rnd.nextGaussian();
            }
        }
        return H;
    }

    private static double[] buildDiagR(int nv, long seed) {
        Random rnd = new Random(seed);
        double[] R = new double[nv * nv];
        for (int i = 0; i < nv; i++) {
            R[i * nv + i] = 0.01 + 0.05 * rnd.nextDouble();
        }
        return R;
    }

    /**
     * 校验正规方程 N·hPos = H_posᵀ·W，其中 N = H_posᵀ·W·H_pos。
     * 这是 hPos = N⁻¹H_posᵀW 的等价定义，比直接比较 NInv 更稳健。
     */
    private static void assertNormalEquation(double[] H, double[] R, double[] hPos,
                                             int nv, int nx) {
        double[] HPW = new double[3 * nv];
        for (int j = 0; j < nv; j++) {
            double w = 1.0 / R[j * nv + j];
            for (int c = 0; c < 3; c++) {
                HPW[c * nv + j] = H[j * nx + c] * w;
            }
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < nv; j++) {
                double lhs = 0.0;
                for (int k = 0; k < 3; k++) {
                    double nIK = 0.0;
                    for (int t = 0; t < nv; t++) {
                        nIK += HPW[i * nv + t] * H[t * nx + k];
                    }
                    lhs += nIK * hPos[k * nv + j];
                }
                assertEquals(HPW[i * nv + j], lhs, 1e-10,
                        "N·hPos 必须等于 H_posᵀ·W，偏差出现在 (" + i + "," + j + ")");
            }
        }
    }

    /**
     * 校验 hPos·H_pos = I₃。
     *
     * <p>这是 hPos = (H_posᵀWH_pos)⁻¹H_posᵀW 的直接推论，不依赖 HposT_W 的
     * 任何存储约定，因此能独立验证 hPos 的 3×nv 布局是否正确：
     * 若行列为 nv×3 写反，结果会显著偏离单位阵。</p>
     */
    private static void assertEquivalentDesignMatrix(double[] H, double[] hPos,
                                                     int nv, int nx) {
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                double sum = 0.0;
                for (int j = 0; j < nv; j++) {
                    sum += hPos[i * nv + j] * H[j * nx + k];
                }
                double expected = (i == k) ? 1.0 : 0.0;
                assertEquals(expected, sum, 1e-10,
                        "hPos·H_pos 必须是单位阵，偏差出现在 (" + i + "," + k + ")");
            }
        }
    }

    @Test
    void testDimensionIsThreeByNv() throws Exception {
        int nv = 8;
        int nx = 20;
        double[] H = buildH(nv, nx, 1L);
        double[] R = buildDiagR(nv, 2L);

        double[] hPos = invokeComputeHPos(H, R, nv, nx);

        assertNotNull(hPos, "合法输入下 hPos 不应为 null");
        assertEquals(3 * nv, hPos.length, "hPos 维度必须是 3×nv");
        assertNotEquals(9, hPos.length,
                "nv>=4 时 3×nv>=12，不得退化为 3×3 的 9 元素（否则 CovAssembler 会误用为 H 行块）");
        assertNormalEquation(H, R, hPos, nv, nx);
        assertEquivalentDesignMatrix(H, hPos, nv, nx);
    }

    @Test
    void testMinNvGuardFour() throws Exception {
        int nv = 4;
        int nx = 20;
        double[] H = buildH(nv, nx, 3L);
        double[] R = buildDiagR(nv, 4L);

        double[] hPos = invokeComputeHPos(H, R, nv, nx);

        assertNotNull(hPos);
        assertEquals(12, hPos.length, "nv=4 时 hPos 长度应为 12");
        assertNormalEquation(H, R, hPos, nv, nx);
        assertEquivalentDesignMatrix(H, hPos, nv, nx);
    }

    @Test
    void testLargeNv() throws Exception {
        int nv = 30;
        int nx = 40;
        double[] H = buildH(nv, nx, 5L);
        double[] R = buildDiagR(nv, 6L);

        double[] hPos = invokeComputeHPos(H, R, nv, nx);

        assertNotNull(hPos);
        assertEquals(3 * nv, hPos.length);
        assertNormalEquation(H, R, hPos, nv, nx);
        assertEquivalentDesignMatrix(H, hPos, nv, nx);
    }

    @Test
    void testNullOnNonPositiveDiagonalR() throws Exception {
        int nv = 6;
        int nx = 20;
        double[] H = buildH(nv, nx, 7L);
        double[] R = buildDiagR(nv, 8L);
        R[2 * nv + 2] = 0.0;

        assertNull(invokeComputeHPos(H, R, nv, nx),
                "R 对角非正时应返回 null 而非抛出异常");
    }
}