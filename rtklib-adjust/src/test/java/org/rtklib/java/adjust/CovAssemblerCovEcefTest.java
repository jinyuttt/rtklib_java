package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link CovAssembler#extractCovariance3x3(SolData)} 使用 {@code SolData.covEcef}
 * 还原完整协方差时保留非对角符号，而不是走 {@code Accuracy} 的绝对值回退路径。
 */
class CovAssemblerCovEcefTest {

    /** 负的第 1 行第 2 列协方差（m²），用于检验符号是否被保留。 */
    private static final float C_XY = -2.5e-4f;
    private static final float C_ZZ = 4.0e-4f;

    @Test
    @DisplayName("covEcef 优先：非对角负相关符号被保留")
    void testCovEcefPreservesSign() {
        SolData solData = buildSolData(new float[]{
                1.0e-4f, 1.5e-4f, C_ZZ, C_XY, 0.0f, 0.0f
        });

        double[][] P = CovAssembler.extractCovariance3x3(solData);

        assertNotNull(P, "有协方差时不应返回 null");
        assertEquals(1.0e-4, P[0][0], 1e-9, "c_xx");
        assertEquals(1.5e-4, P[1][1], 1e-9, "c_yy");
        assertEquals(C_ZZ, P[2][2], 1e-9, "c_zz");
        assertEquals(C_XY, P[0][1], 1e-9, "c_xy 必须为负");
        assertEquals(C_XY, P[1][0], 1e-9, "对称性");
        assertTrue(P[0][1] < 0, "若走 Accuracy 回退会恒正，此处必须为负");
    }

    @Test
    @DisplayName("qr[0]==0 哨兵：covEcef 为 null，回退到 Accuracy 对角")
    void testSentinelFallsBackToDiagonal() {
        Sol sol = baseSol();
        sol.qr = new float[]{0f, 0f, 0f, 0f, 0f, 0f};
        SolData solData = new SolData(sol, PrcOpt.POS_ECEF);

        assertNull(solData.covEcef, "无协方差哨兵时应返回 null");

        double[][] P = CovAssembler.extractCovariance3x3(solData);
        assertNotNull(P, "Accuracy 仍在，回退路径应给出对角阵");
        assertEquals(0.0, P[0][0], 0.0);
        assertEquals(0.0, P[0][1], 0.0);
    }

    @Test
    @DisplayName("extractBaselineDxyz 返回 rover 绝对 ECEF 位置")
    void testBaselineDxyzIsAbsoluteRoverPosition() {
        SolData solData = buildSolData(new float[]{
                1.0e-4f, 1.0e-4f, 1.0e-4f, 0f, 0f, 0f
        });

        double[] dXyz = CovAssembler.extractBaselineDxyz(solData);
        Position ecef = solData.getPosition(CoordType.ECEF);

        assertNotNull(dXyz);
        assertEquals(ecef.v1, dXyz[0], 1e-9);
        assertEquals(ecef.v2, dXyz[1], 1e-9);
        assertEquals(ecef.v3, dXyz[2], 1e-9);
    }

    private static SolData buildSolData(float[] qr) {
        Sol sol = baseSol();
        sol.qr = qr;
        return new SolData(sol, PrcOpt.POS_ECEF);
    }

    private static Sol baseSol() {
        Sol sol = new Sol();
        sol.time = new GTime();
        sol.rr = new double[]{-2.2e6, 4.4e6, 4.6e6, 0, 0, 0};
        sol.type = (byte) 0;
        sol.stat = (byte) SolutionStatus.FIX.code;
        sol.ns = (byte) 12;
        sol.age = 0.0f;
        sol.ratio = 5.0f;
        sol.dtr = new double[]{0};
        sol.qv = new float[]{0, 0, 0, 0, 0, 0};
        return sol;
    }
}
