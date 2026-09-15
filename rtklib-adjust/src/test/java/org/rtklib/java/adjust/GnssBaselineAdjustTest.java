package org.rtklib.java.adjust;

import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.engine.GnssBaselineAdjust;
import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.BaselineEpoch;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GNSS多基线间接平差全链路测试，覆盖k=1/k=2/k=3三种场景。
 *
 * <p>构造模拟SolData：已知P01真值和基站坐标，反算基线增量，
 * 加小噪声，构造协方差，验证平差结果。</p>
 */
public class GnssBaselineAdjustTest {

    private static final double P01_X = -2267749.0;
    private static final double P01_Y = 5009154.0;
    private static final double P01_Z = 3221290.0;

    private static final double[][] BASE_COORDS = {
            {-2267759.0, 5009144.0, 3221300.0},
            {-2267739.0, 5009164.0, 3221280.0},
            {-2267769.0, 5009134.0, 3221310.0}
    };

    private static final String[] BASE_IDS = {"A", "B", "C"};

    private static final double COV_DIAG = 1e-6;
    private static final double COV_OFF = 2e-7;

    @Test
    @DisplayName("k=1: 单基线平差，df=0，无Baarda检验")
    void testSingleBaseline() {
        BaselineEpoch epoch = buildEpoch(1, 0.0);
        assertEquals(1, epoch.count);

        AdjustResult result = GnssBaselineAdjust.adjust(epoch);
        assertTrue(result.success);
        assertEquals(0, result.dof);
        assertEquals(1, result.usedBaselineCount);
        assertTrue(Double.isNaN(result.sigma0));
        assertNull(result.baardaT);
        assertNull(result.Qv);

        for (int i = 0; i < 3; i++) {
            assertEquals(P01_X, result.p01Xyz[0], 0.01, "P01 X");
            assertEquals(P01_Y, result.p01Xyz[1], 0.01, "P01 Y");
            assertEquals(P01_Z, result.p01Xyz[2], 0.01, "P01 Z");
        }
    }

    @Test
    @DisplayName("k=2: 双基线平差，df=3，有Baarda检验")
    void testTwoBaselines() {
        BaselineEpoch epoch = buildEpoch(2, 0.001);
        assertEquals(2, epoch.count);

        AdjustResult result = GnssBaselineAdjust.adjust(epoch);
        assertTrue(result.success);
        assertEquals(3, result.dof);
        assertEquals(2, result.usedBaselineCount);
        assertFalse(Double.isNaN(result.sigma0));
        assertNotNull(result.baardaT);
        assertEquals(6, result.baardaT.length);
        assertNotNull(result.Qv);

        assertEquals(P01_X, result.p01Xyz[0], 0.01, "P01 X");
        assertEquals(P01_Y, result.p01Xyz[1], 0.01, "P01 Y");
        assertEquals(P01_Z, result.p01Xyz[2], 0.01, "P01 Z");

        assertTrue(result.sigma0 < 5.0, "sigma0应合理，实际=" + result.sigma0);

        for (double t : result.baardaT) {
            assertTrue(t < 10.0, "Baarda T值应合理: " + t);
        }
    }

    @Test
    @DisplayName("k=3: 三基线平差，df=6，完整精度评定")
    void testThreeBaselines() {
        BaselineEpoch epoch = buildEpoch(3, 0.001);
        assertEquals(3, epoch.count);

        AdjustResult result = GnssBaselineAdjust.adjust(epoch);
        assertTrue(result.success);
        assertEquals(6, result.dof);
        assertEquals(3, result.usedBaselineCount);
        assertFalse(Double.isNaN(result.sigma0));
        assertNotNull(result.baardaT);
        assertEquals(9, result.baardaT.length);
        assertNotNull(result.Qv);

        assertEquals(P01_X, result.p01Xyz[0], 0.01, "P01 X");
        assertEquals(P01_Y, result.p01Xyz[1], 0.01, "P01 Y");
        assertEquals(P01_Z, result.p01Xyz[2], 0.01, "P01 Z");

        assertTrue(result.sigma0 < 5.0, "sigma0应合理，实际=" + result.sigma0);

        for (double t : result.baardaT) {
            assertTrue(t < 10.0, "Baarda T值应合理: " + t);
        }
    }

    @Test
    @DisplayName("k=0: 无有效基线，返回失败")
    void testNoBaseline() {
        BaselineEpoch.BaselineEntry[] entries = new BaselineEpoch.BaselineEntry[0];
        BaselineEpoch epoch = new BaselineEpoch("2026-01-01 00:00:00", entries);
        assertEquals(0, epoch.count);

        AdjustResult result = GnssBaselineAdjust.adjust(epoch);
        assertFalse(result.success);
        assertEquals(0, result.usedBaselineCount);
    }

    @Test
    @DisplayName("k=3含1条非FIX基线: 自动剔除后k=2")
    void testWithNonFixBaseline() {
        BaselineEpoch.BaselineEntry[] entries = new BaselineEpoch.BaselineEntry[3];
        entries[0] = buildEntry(0, 0.001);
        entries[1] = buildEntry(1, 0.001);
        entries[2] = buildFloatEntry(2);

        BaselineEpoch epoch = new BaselineEpoch("2026-01-01 00:00:00", entries);
        assertEquals(2, epoch.count);

        AdjustResult result = GnssBaselineAdjust.adjust(epoch);
        assertTrue(result.success);
        assertEquals(3, result.dof);
        assertEquals(2, result.usedBaselineCount);
    }

    @Test
    @DisplayName("CovAssembler: 加权融合Rover协方差验证")
    void testFusedRoverCovariance() {
        double[][] p1 = buildCov3x3(COV_DIAG, COV_OFF);
        double[][] p2 = buildCov3x3(COV_DIAG * 2, COV_OFF);
        double[][] p3 = buildCov3x3(COV_DIAG * 0.5, COV_OFF);

        double[][] fused = CovAssembler.fuseRoverCovariance(new double[][][]{p1, p2, p3});

        assertNotNull(fused);
        assertEquals(3, fused.length);
        assertEquals(3, fused[0].length);

        assertTrue(fused[0][0] > 0, "融合协方差对角线应为正");
        assertTrue(fused[1][1] > 0);
        assertTrue(fused[2][2] > 0);
    }

    @Test
    @DisplayName("CovAssembler: 设计矩阵H动态构造验证")
    void testDesignMatrix() {
        SimpleMatrix h1 = CovAssembler.assembleDesignMatrix(1);
        assertEquals(3, h1.numRows());
        assertEquals(3, h1.numCols());
        assertEquals(1.0, h1.get(0, 0), 1e-10);
        assertEquals(1.0, h1.get(1, 1), 1e-10);
        assertEquals(1.0, h1.get(2, 2), 1e-10);

        SimpleMatrix h3 = CovAssembler.assembleDesignMatrix(3);
        assertEquals(9, h3.numRows());
        assertEquals(3, h3.numCols());
        assertEquals(1.0, h3.get(6, 0), 1e-10);
        assertEquals(1.0, h3.get(7, 1), 1e-10);
        assertEquals(1.0, h3.get(8, 2), 1e-10);
    }

    @Test
    @DisplayName("k=3无噪声: dx即P01坐标估计，应接近真值，sigma0接近0")
    void testThreeBaselinesNoNoise() {
        BaselineEpoch epoch = buildEpoch(3, 0.0);
        AdjustResult result = GnssBaselineAdjust.adjust(epoch);
        assertTrue(result.success);

        assertEquals(P01_X, result.dx[0], 1e-6, "无噪声时dx[0]应接近P01_X");
        assertEquals(P01_Y, result.dx[1], 1e-6, "无噪声时dx[1]应接近P01_Y");
        assertEquals(P01_Z, result.dx[2], 1e-6, "无噪声时dx[2]应接近P01_Z");

        assertEquals(P01_X, result.p01Xyz[0], 1e-6, "P01 X");
        assertEquals(P01_Y, result.p01Xyz[1], 1e-6, "P01 Y");
        assertEquals(P01_Z, result.p01Xyz[2], 1e-6, "P01 Z");
    }

    private BaselineEpoch buildEpoch(int k, double noise) {
        BaselineEpoch.BaselineEntry[] entries = new BaselineEpoch.BaselineEntry[k];
        for (int i = 0; i < k; i++) {
            entries[i] = buildEntry(i, noise);
        }
        return new BaselineEpoch("2026-01-01 00:00:00", entries);
    }

    private BaselineEpoch.BaselineEntry buildEntry(int baseIdx, double noise) {
        double[] base = BASE_COORDS[baseIdx];
        double dX = (P01_X - base[0]) + noise * (baseIdx - 1);
        double dY = (P01_Y - base[1]) + noise * (baseIdx - 1);
        double dZ = (P01_Z - base[2]) + noise * (baseIdx - 1);

        double roverX = base[0] + dX;
        double roverY = base[1] + dY;
        double roverZ = base[2] + dZ;

        SolData solData = buildMockSolData(roverX, roverY, roverZ, SolutionStatus.FIX);
        double[] dXyz = {dX, dY, dZ};
        double[][] cov = buildCov3x3(COV_DIAG, COV_OFF);

        return new BaselineEpoch.BaselineEntry(BASE_IDS[baseIdx], solData, dXyz, cov, cov);
    }

    private BaselineEpoch.BaselineEntry buildFloatEntry(int baseIdx) {
        double[] base = BASE_COORDS[baseIdx];
        double dX = P01_X - base[0];
        double dY = P01_Y - base[1];
        double dZ = P01_Z - base[2];
        double roverX = base[0] + dX;
        double roverY = base[1] + dY;
        double roverZ = base[2] + dZ;

        SolData solData = buildMockSolData(roverX, roverY, roverZ, SolutionStatus.FLOAT);
        double[] dXyz = {dX, dY, dZ};
        double[][] cov = buildCov3x3(COV_DIAG, COV_OFF);

        return new BaselineEpoch.BaselineEntry(BASE_IDS[baseIdx], solData, dXyz, cov, cov);
    }

    private SolData buildMockSolData(double x, double y, double z, SolutionStatus status) {
        Sol sol = new Sol();
        sol.time = new GTime();
        sol.rr = new double[]{x, y, z, 0, 0, 0};
        sol.qr = new float[]{
                (float) COV_DIAG, (float) COV_DIAG, (float) COV_DIAG,
                (float) COV_OFF, (float) COV_OFF, (float) COV_OFF
        };
        sol.type = (byte) 0;
        sol.stat = (byte) status.code;
        sol.ns = (byte) 10;
        sol.age = 0.0f;
        sol.ratio = 5.0f;
        sol.dtr = new double[]{0};
        sol.qv = new float[]{0, 0, 0, 0, 0, 0};

        int posMask = PrcOpt.POS_ECEF | PrcOpt.POS_LLH;
        return new SolData(sol, posMask);
    }

    private double[][] buildCov3x3(double diag, double off) {
        return new double[][]{
                {diag, off, off},
                {off, diag, off},
                {off, off, diag}
        };
    }
}