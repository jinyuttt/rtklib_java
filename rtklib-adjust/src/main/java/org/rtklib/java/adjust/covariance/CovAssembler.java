package org.rtklib.java.adjust.covariance;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.adjust.model.BaselineEpoch;
import org.rtklib.java.data.*;

/**
 * 协方差拼接核心。
 *
 * <p>从多条BaselineEntry提取协方差，加权融合公共Rover协方差Pr_fused，
 * 动态组装mxm全局观测协方差矩阵R。</p>
 *
 * <h3>加权融合方法</h3>
 * <p>多条基线各自给出P_rover_k，公共Pr_fused取信息矩阵融合：</p>
 * <pre>
 *   W_k = P_rover_k^{-1}
 *   Pr_fused = (W_1 + W_2 + W_3)^{-1}
 * </pre>
 * <p>单条基线时Pr_fused = P_rover_1，无需融合。</p>
 *
 * <h3>全局R矩阵结构</h3>
 * <pre>
 *   k=1: R = [C_11]                           (3x3)
 *   k=2: R = [C_11, Pr_fused; Pr_fused, C_22] (6x6)
 *   k=3: R = [C_11, Pr, Pr; Pr, C_22, Pr; Pr, Pr, C_33] (9x9)
 * </pre>
 */
public class CovAssembler {

    /**
     * 从SolData提取ECEF基线增量。
     *
     * @param solData 来自rtklib-core的解算结果
     * @return [dX, dY, dZ] (m)，如果无ECEF位置返回null
     */
    public static double[] extractBaselineDxyz(SolData solData) {
        Position ecef = solData.getPosition(CoordType.ECEF);
        if (ecef == null) return null;
        return new double[]{ecef.v1, ecef.v2, ecef.v3};
    }

    /**
     * 从SolData提取3x3 ECEF协方差矩阵。
     *
     * <p>SolData的Accuracy存储的是标准差(m)，需还原为协方差(m^2)：</p>
     * <pre>
     *   P[0][0] = s1^2  (c_xx)
     *   P[1][1] = s2^2  (c_yy)
     *   P[2][2] = s3^2  (c_zz)
     *   P[0][1] = P[1][0] = c12^2 * sign  (c_xy)
     *   P[1][2] = P[2][1] = c23^2 * sign  (c_yz)
     *   P[0][2] = P[2][0] = c31^2 * sign  (c_zx)
     * </pre>
     *
     * @param solData 来自rtklib-core的解算结果
     * @return 3x3协方差矩阵 (m^2)，行优先；如果无ECEF精度返回null
     */
    public static double[][] extractCovariance3x3(SolData solData) {
        Accuracy acc = solData.getAccuracy(CoordType.ECEF);
        if (acc == null) return null;

        double[][] P = new double[3][3];
        P[0][0] = acc.s1 * acc.s1;
        P[1][1] = acc.s2 * acc.s2;
        P[2][2] = acc.s3 * acc.s3;
        P[0][1] = P[1][0] = acc.c12 * acc.c12;
        P[1][2] = P[2][1] = acc.c23 * acc.c23;
        P[0][2] = P[2][0] = acc.c31 * acc.c31;
        return P;
    }

    /**
     * 加权融合多条基线的Rover协方差，得到公共Pr_fused。
     *
     * <p>信息矩阵融合：Pr_fused = (P1^{-1} + P2^{-1} + P3^{-1})^{-1}</p>
     * <p>单条基线时直接返回P_rover_1。</p>
     *
     * @param pRovers 各基线的Rover 3x3协方差矩阵
     * @return 融合后的3x3协方差矩阵，行优先
     * @throws RuntimeException 如果矩阵奇异无法求逆
     */
    public static double[][] fuseRoverCovariance(double[][][] pRovers) {
        if (pRovers.length == 1) {
            return copyMatrix(pRovers[0]);
        }

        SimpleMatrix sumW = new SimpleMatrix(3, 3);
        for (double[][] P : pRovers) {
            SimpleMatrix mat = toSimpleMatrix(P);
            if (mat.determinant() == 0) {
                throw new RuntimeException("Rover协方差矩阵奇异，无法求逆进行融合");
            }
            SimpleMatrix inv = mat.invert();
            sumW = sumW.plus(inv);
        }

        if (sumW.determinant() == 0) {
            throw new RuntimeException("信息矩阵和奇异，无法求逆得到Pr_fused");
        }

        SimpleMatrix prFused = sumW.invert();
        return fromSimpleMatrix(prFused);
    }

    /**
     * 组装全局观测协方差矩阵R (mxm)。
     *
     * @param epoch 包含有效基线的历元数据
     * @return 全局R矩阵 (3k x 3k)
     * @throws RuntimeException 如果矩阵奇异
     */
    public static SimpleMatrix assembleGlobalR(BaselineEpoch epoch) {
        int k = epoch.count;
        int m = 3 * k;

        if (k == 1) {
            return toSimpleMatrix(epoch.baselines[0].cBaseline);
        }

        double[][][] pRovers = new double[k][][];
        for (int i = 0; i < k; i++) {
            pRovers[i] = epoch.baselines[i].pRover;
        }
        double[][] prFused = fuseRoverCovariance(pRovers);
        SimpleMatrix prMat = toSimpleMatrix(prFused);

        SimpleMatrix R = new SimpleMatrix(m, m);
        for (int i = 0; i < k; i++) {
            SimpleMatrix cKk = toSimpleMatrix(epoch.baselines[i].cBaseline);
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    R.set(i * 3 + r, i * 3 + c, cKk.get(r, c));
                }
            }
        }

        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        R.set(i * 3 + r, j * 3 + c, prMat.get(r, c));
                        R.set(j * 3 + r, i * 3 + c, prMat.get(r, c));
                    }
                }
            }
        }

        return R;
    }

    /**
     * 组装观测向量l (3k x 1)。
     */
    public static SimpleMatrix assembleObservationVector(BaselineEpoch epoch) {
        int k = epoch.count;
        int m = 3 * k;
        SimpleMatrix l = new SimpleMatrix(m, 1);
        for (int i = 0; i < k; i++) {
            double[] dxyz = epoch.baselines[i].dXyz;
            l.set(i * 3, 0, dxyz[0]);
            l.set(i * 3 + 1, 0, dxyz[1]);
            l.set(i * 3 + 2, 0, dxyz[2]);
        }
        return l;
    }

    /**
     * 动态构造设计矩阵H (3k x 3)。
     *
     * <p>每条基线对应H中连续3行，构成3x3单位阵I₃。</p>
     */
    public static SimpleMatrix assembleDesignMatrix(int k) {
        int m = 3 * k;
        SimpleMatrix H = new SimpleMatrix(m, 3);
        for (int i = 0; i < k; i++) {
            H.set(i * 3, 0, 1.0);
            H.set(i * 3 + 1, 1, 1.0);
            H.set(i * 3 + 2, 2, 1.0);
        }
        return H;
    }

    private static SimpleMatrix toSimpleMatrix(double[][] mat) {
        SimpleMatrix sm = new SimpleMatrix(mat.length, mat[0].length);
        for (int i = 0; i < mat.length; i++) {
            for (int j = 0; j < mat[0].length; j++) {
                sm.set(i, j, mat[i][j]);
            }
        }
        return sm;
    }

    private static double[][] fromSimpleMatrix(SimpleMatrix sm) {
        double[][] mat = new double[sm.getNumRows()][sm.getNumCols()];
        for (int i = 0; i < sm.getNumRows(); i++) {
            for (int j = 0; j < sm.getNumCols(); j++) {
                mat[i][j] = sm.get(i, j);
            }
        }
        return mat;
    }

    private static double[][] copyMatrix(double[][] src) {
        double[][] dst = new double[src.length][src[0].length];
        for (int i = 0; i < src.length; i++) {
            System.arraycopy(src[i], 0, dst[i], 0, src[i].length);
        }
        return dst;
    }
}