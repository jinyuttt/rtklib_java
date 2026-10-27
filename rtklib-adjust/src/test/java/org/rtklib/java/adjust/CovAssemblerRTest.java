package org.rtklib.java.adjust;

import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.model.BaselineEpoch;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证全局观测协方差 R 的互协方差建模：默认按独立处理（块对角），
 * 显式给定 rho 时才构造相关块。
 */
class CovAssemblerRTest {

    private static final double VAR = 1.0e-4;

    @Test
    @DisplayName("默认 rho=0：跨基线块为 0（块对角）")
    void testIndependentBlocksAreZero() {
        BaselineEpoch epoch = buildEpoch(2);

        SimpleMatrix R = CovAssembler.assembleGlobalR(epoch, false);

        assertEquals(VAR, R.get(0, 0), 1e-12, "C_11 对角");
        assertEquals(VAR, R.get(3, 3), 1e-12, "C_22 对角");
        assertEquals(0.0, R.get(0, 3), 0.0, "独立时互协方差必须为 0");
        assertEquals(0.0, R.get(3, 0), 0.0, "对称位置为 0");
    }

    @Test
    @DisplayName("rho=0.5：互协方差 = rho·sqrt(C_ii·C_jj)")
    void testRhoFillsCrossBlock() {
        BaselineEpoch epoch = buildEpoch(2);

        SimpleMatrix R = CovAssembler.assembleGlobalR(epoch, false, 0.5);

        assertEquals(0.5 * VAR, R.get(0, 3), 1e-12, "跨块应为 0.5 倍方差");
        assertEquals(R.get(0, 3), R.get(3, 0), 1e-15, "对称性");
        assertEquals(VAR, R.get(0, 0), 1e-12, "对角块不受 rho 影响");
    }

    @Test
    @DisplayName("rho 越界被钳制在 [0, 0.99]")
    void testRhoClamped() {
        BaselineEpoch epoch = buildEpoch(2);

        SimpleMatrix high = CovAssembler.assembleGlobalR(epoch, false, 5.0);
        assertEquals(0.99 * VAR, high.get(0, 3), 1e-12);

        SimpleMatrix low = CovAssembler.assembleGlobalR(epoch, false, -1.0);
        assertEquals(0.0, low.get(0, 3), 0.0);
    }

    @Test
    @DisplayName("k=3 独立：所有跨基线块为 0，且 R 正定")
    void testThreeBaselinesIndependent() {
        BaselineEpoch epoch = buildEpoch(3);

        SimpleMatrix R = CovAssembler.assembleGlobalR(epoch, false);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (i == j) continue;
                assertEquals(0.0, R.get(3 * i, 3 * j), 0.0, "块 (" + i + "," + j + ")");
            }
        }
        assertTrue(R.determinant() > 0, "块对角 R 应正定");
    }

    private static BaselineEpoch buildEpoch(int k) {
        BaselineEpoch.BaselineEntry[] entries = new BaselineEpoch.BaselineEntry[k];
        for (int i = 0; i < k; i++) {
            double[][] cov = {{VAR, 0, 0}, {0, VAR, 0}, {0, 0, VAR}};
            entries[i] = new BaselineEpoch.BaselineEntry(
                    String.valueOf((char) ('A' + i)),
                    mockSolData(),
                    new double[]{0, 0, 0},
                    cov, cov, 1.0);
        }
        return new BaselineEpoch("t0", entries);
    }

    private static SolData mockSolData() {
        Sol sol = new Sol();
        sol.time = new GTime();
        sol.rr = new double[]{-2.2e6, 4.4e6, 4.6e6, 0, 0, 0};
        sol.qr = new float[]{(float) VAR, (float) VAR, (float) VAR, 0f, 0f, 0f};
        sol.stat = (byte) SolutionStatus.FIX.code;
        sol.ns = (byte) 10;
        sol.dtr = new double[]{0};
        sol.qv = new float[]{0, 0, 0, 0, 0, 0};
        return new SolData(sol, PrcOpt.POS_ECEF);
    }
}
