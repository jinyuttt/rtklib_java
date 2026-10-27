package org.rtklib.java.adjust.covariance;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.adjust.model.BaselineEpoch;
import org.rtklib.java.data.*;

/**
 * 协方差拼接核心。
 *
 * <p>从多条BaselineEntry提取协方差，动态组装mxm全局观测协方差矩阵R，
 * 并提供"融合后 rover 协方差"的信息矩阵融合工具。</p>
 *
 * <h3>融合后的 rover 协方差（仅供结果评估）</h3>
 * <p>多条基线各自给出P_rover_k，公共Pr_fused取信息矩阵融合：</p>
 * <pre>
 *   W_k = P_rover_k^{-1}
 *   Pr_fused = (W_1 + W_2 + W_3)^{-1}
 * </pre>
 * <p>单条基线时Pr_fused = P_rover_1，无需融合。</p>
 *
 * <h3>全局R矩阵结构</h3>
 * <p>core 是单基线引擎，各基站独立调用，因此默认认为各基线解算误差相互独立
 * （{@code rho = 0}），R 为块对角阵：</p>
 * <pre>
 *   k=1: R = [C_11]                (3x3)
 *   k=2: R = [C_11,   0; 0, C_22]  (6x6)
 *   k=3: R = blkdiag(C_11, C_22, C_33)  (9x9)
 * </pre>
 * <p>若实测发现基线间存在不可忽略的共模误差，可用
 * {@link #assembleGlobalR(BaselineEpoch, boolean, double)} 传入 {@code rho > 0}。</p>
 *
 * <p>注意：{@link #fuseRoverCovariance(double[][][])} 得到的是"融合后坐标的
 * 方差"，属于<b>结果</b>的协方差，不能当作观测量的互协方差块使用。</p>
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
     * <p>优先使用 {@link SolData#covEcef}（{@code Sol.qr} 解包的完整协方差，
     * 含非对角符号）。仅当该历元无协方差时才回退到 {@link Accuracy} 重构，
     * 此时非对角只能取绝对值——{@link Accuracy} 的 c12/c23/c31 已是
     * {@code sqrt(|c_ij|)}，符号信息在构造时已丢失。</p>
     *
     * @param solData 来自rtklib-core的解算结果
     * @return 3x3协方差矩阵 (m^2)，行优先；如果无ECEF精度返回null
     */
    public static double[][] extractCovariance3x3(SolData solData) {
        double[] cov = solData.covEcef;
        if (cov != null && cov.length >= 9) {
            return new double[][]{
                    {cov[0], cov[1], cov[2]},
                    {cov[3], cov[4], cov[5]},
                    {cov[6], cov[7], cov[8]}
            };
        }

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
        return assembleGlobalR(epoch, false);
    }

    /**
     * [Java扩展] 组装全局观测协方差矩阵R (mxm)，可选启用基线质量加权(P0)。
     *
     * <p>启用质量加权时，对角块C_ii按qualityFactor缩放：
     * R_effective_ii = C_ii / w_i，等效于将该基线的权重乘以w_i。
     * 非对角块(Pr_fused)不缩放，因为互协方差是物理量。</p>
     *
     * @param epoch         包含有效基线的历元数据
     * @param qualityWeight 是否启用基线质量加权
     * @return 全局R矩阵 (3k x 3k)
     * @throws RuntimeException 如果矩阵奇异
     */
    public static SimpleMatrix assembleGlobalR(BaselineEpoch epoch, boolean qualityWeight) {
        return assembleGlobalR(epoch, qualityWeight, 0.0);
    }

    /**
     * 组装全局观测协方差矩阵R (mxm)，可指定基线间相关系数。
     *
     * <p>启用质量加权时，对角块C_ii按qualityFactor缩放：
     * R_effective_ii = C_ii / w_i，等效于将该基线的权重乘以w_i。</p>
     *
     * <p>互协方差块按 {@code R_ij = rho · sqrt(C_ii ⊙ C_jj)} 逐元素构造。
     * {@code rho = 0} 表示各基线解算结果相互独立（core 为单基线引擎、逐基站
     * 独立调用，这是当前架构的实际情况），此时 R 为块对角阵。</p>
     *
     * @param epoch         包含有效基线的历元数据
     * @param qualityWeight 是否启用基线质量加权
     * @param rho           基线间误差相关系数，取值 [0, 1)
     * @return 全局R矩阵 (3k x 3k)
     * @throws RuntimeException 如果矩阵奇异
     */
    public static SimpleMatrix assembleGlobalR(BaselineEpoch epoch, boolean qualityWeight, double rho) {
        int k = epoch.count;
        int m = 3 * k;
        double rhoClamped = Math.max(0.0, Math.min(rho, 0.99));

        if (k == 1) {
            SimpleMatrix R = toSimpleMatrix(epoch.baselines[0].cBaseline);
            if (qualityWeight && epoch.baselines[0].qualityFactor < 1.0) {
                R = R.scale(1.0 / epoch.baselines[0].qualityFactor);
            }
            return R;
        }

        SimpleMatrix R = new SimpleMatrix(m, m);
        for (int i = 0; i < k; i++) {
            SimpleMatrix cKk = toSimpleMatrix(epoch.baselines[i].cBaseline);
            double w = qualityWeight ? epoch.baselines[i].qualityFactor : 1.0;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    R.set(i * 3 + r, i * 3 + c, cKk.get(r, c) / w);
                }
            }
        }

        if (rhoClamped > 0) {
            for (int i = 0; i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    SimpleMatrix ci = toSimpleMatrix(epoch.baselines[i].cBaseline);
                    SimpleMatrix cj = toSimpleMatrix(epoch.baselines[j].cBaseline);
                    for (int r = 0; r < 3; r++) {
                        for (int c = 0; c < 3; c++) {
                            double v = rhoClamped * Math.sqrt(Math.abs(ci.get(r, c) * cj.get(r, c)));
                            R.set(i * 3 + r, j * 3 + c, v);
                            R.set(j * 3 + r, i * 3 + c, v);
                        }
                    }
                }
            }
        }

        return R;
    }

    /**
     * 组装观测向量l (3k x 1)。
     *
     * <p>观测向量为各基线估计的Rover ECEF位置（基线增量+基站坐标），
     * 而非基线增量本身。因为观测方程 l_i = P01 - Base_i + v_i，
     * 等价于 (l_i + Base_i) = P01 + v_i，有效观测是Rover位置。</p>
     */
    public static SimpleMatrix assembleObservationVector(BaselineEpoch epoch) {
        int k = epoch.count;
        int m = 3 * k;
        SimpleMatrix l = new SimpleMatrix(m, 1);
        for (int i = 0; i < k; i++) {
            Position ecef = epoch.baselines[i].solData.getPosition(CoordType.ECEF);
            l.set(i * 3, 0, ecef.v1);
            l.set(i * 3 + 1, 0, ecef.v2);
            l.set(i * 3 + 2, 0, ecef.v3);
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

    /**
     * [Java扩展] 动态构造设计矩阵H (3k x 3)，支持hPos等效设计矩阵(P3)。
     *
     * <p>如果基线SolData中携带hPos(3x3行优先)，则使用hPos替代I₃，
     * 反映该基线对Rover坐标的实际灵敏度（由卫星几何和权重决定）。
     * 无hPos时退化为I₃，与当前行为一致。</p>
     *
     * @param epoch 包含有效基线的历元数据
     * @return 设计矩阵H (3k x 3)
     */
    public static SimpleMatrix assembleDesignMatrix(BaselineEpoch epoch) {
        int k = epoch.count;
        int m = 3 * k;
        SimpleMatrix H = new SimpleMatrix(m, 3);
        for (int i = 0; i < k; i++) {
            double[] hPos = epoch.baselines[i].solData.hPos;
            if (hPos != null && hPos.length == 9) {
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        H.set(i * 3 + r, c, hPos[r * 3 + c]);
                    }
                }
            } else {
                H.set(i * 3, 0, 1.0);
                H.set(i * 3 + 1, 1, 1.0);
                H.set(i * 3 + 2, 2, 1.0);
            }
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
        double[][] mat = new double[sm.numRows()][sm.numCols()];
        for (int i = 0; i < sm.numRows(); i++) {
            for (int j = 0; j < sm.numCols(); j++) {
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