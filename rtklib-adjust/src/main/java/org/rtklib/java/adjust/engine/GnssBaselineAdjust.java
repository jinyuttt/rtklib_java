package org.rtklib.java.adjust.engine;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.decomposition.chol.CholeskyDecompositionInner_DDRM;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.BaselineEpoch;

/**
 * GNSS多基线间接平差引擎。
 *
 * <p>基于高斯-马尔可夫最小二乘模型，对同一历元多条独立RTK基线进行间接平差，
 * 融合跨基线互协方差，输出流动站P01的最优坐标及精度评定。</p>
 *
 * <h3>数学模型</h3>
 * <pre>
 *   观测方程: l = H·x + v
 *   法方程:   N = HᵀR⁻¹H,  u = HᵀR⁻¹l
 *   参数解:   dx = N⁻¹u
 *   残差:     v = H·dx - l
 *   自由度:   df = m - n,  m=3k, n=3
 *   单位权方差: σ₀² = vᵀR⁻¹v / df  (df>0时)
 *   残差协因数: Qv = R - H·N⁻¹·Hᵀ
 *   Baarda:   T_i = |v_i| / (σ₀·√Qv_ii)  (df>0时)
 *   参数协方差: Dx = σ₀²·N⁻¹  (df>0时), 否则 Dx = N⁻¹
 * </pre>
 *
 * <h3>特殊情况</h3>
 * <ul>
 *   <li>k=1 (df=0): 无法精度评定，直接取基线结果，σ₀=NaN，不计算Baarda</li>
 *   <li>k=0: 返回失败结果</li>
 * </ul>
 */
public class GnssBaselineAdjust {

    /**
     * 对单历元多基线执行间接平差。
     *
     * @param epoch 包含有效FIX基线的历元数据
     * @return 平差结果
     */
    public static AdjustResult adjust(BaselineEpoch epoch) {
        int k = epoch.count;
        if (k == 0) {
            return AdjustResult.failure(epoch.epochTag, 0, "无有效FIX基线");
        }

        int m = 3 * k;
        int n = 3;
        int df = m - n;

        try {
            SimpleMatrix l = CovAssembler.assembleObservationVector(epoch);
            SimpleMatrix H = CovAssembler.assembleDesignMatrix(k);
            SimpleMatrix R = CovAssembler.assembleGlobalR(epoch);

            if (!isPositiveDefinite(R)) {
                return AdjustResult.failure(epoch.epochTag, k,
                        "全局协方差矩阵R非正定，det=" + R.determinant());
            }

            SimpleMatrix RInv = R.invert();

            SimpleMatrix Ht = H.transpose();
            SimpleMatrix N = Ht.mult(RInv).mult(H);

            if (!isPositiveDefinite(N)) {
                return AdjustResult.failure(epoch.epochTag, k,
                        "法方程系数矩阵N非正定，det=" + N.determinant());
            }

            SimpleMatrix NInv = N.invert();
            SimpleMatrix u = Ht.mult(RInv).mult(l);
            SimpleMatrix dxMat = NInv.mult(u);

            SimpleMatrix vMat = H.mult(dxMat).minus(l);

            double[] dx = new double[n];
            for (int i = 0; i < n; i++) dx[i] = dxMat.get(i, 0);

            double[] v = new double[m];
            for (int i = 0; i < m; i++) v[i] = vMat.get(i, 0);

            double[] p01Xyz = new double[]{dx[0], dx[1], dx[2]};

            if (df == 0) {
                double[][] Dx = simpleMatrixTo2d(NInv);
                return AdjustResult.success(epoch.epochTag, dx, Dx, v,
                        Double.NaN, 0, null, null, p01Xyz, k);
            }

            double vRv = vMat.transpose().mult(RInv).mult(vMat).get(0, 0);
            double sigma0Sq = vRv / df;
            double sigma0 = Math.sqrt(Math.max(sigma0Sq, 0.0));

            SimpleMatrix Qv = R.minus(H.mult(NInv).mult(Ht));

            double[] baardaT = new double[m];
            for (int i = 0; i < m; i++) {
                double qvii = Qv.get(i, i);
                if (qvii > 0 && sigma0 > 0) {
                    baardaT[i] = Math.abs(v[i]) / (sigma0 * Math.sqrt(qvii));
                } else {
                    baardaT[i] = 0.0;
                }
            }

            SimpleMatrix DxMat = NInv.scale(sigma0Sq);
            double[][] Dx = simpleMatrixTo2d(DxMat);

            return AdjustResult.success(epoch.epochTag, dx, Dx, v,
                    sigma0, df, Qv, baardaT, p01Xyz, k);

        } catch (RuntimeException e) {
            return AdjustResult.failure(epoch.epochTag, k, "平差计算异常: " + e.getMessage());
        }
    }

    /**
     * Cholesky分解判断正定性。
     *
     * <p>比行列式阈值更稳健：不依赖矩阵维度，对协方差类矩阵（对角~1e-6）可靠。</p>
     */
    private static boolean isPositiveDefinite(SimpleMatrix mat) {
        DMatrixRMaj drm = mat.getDDRM().copy();
        CholeskyDecompositionInner_DDRM chol = new CholeskyDecompositionInner_DDRM(true);
        return chol.decompose(drm);
    }

    private static double[][] simpleMatrixTo2d(SimpleMatrix sm) {
        double[][] arr = new double[sm.numRows()][sm.numCols()];
        for (int i = 0; i < sm.numRows(); i++) {
            for (int j = 0; j < sm.numCols(); j++) {
                arr[i][j] = sm.get(i, j);
            }
        }
        return arr;
    }
}