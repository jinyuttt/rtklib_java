package org.rtklib.java.adjust.engine;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.coord.CoordTransform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网平差：自由网平差、约束平差、坐标系统转换。
 *
 * <p>与前端的逐历元 {@link GnssBaselineAdjust} 不同，本类面向<b>整时段</b>批处理：
 * 把一次观测时段内的全部基线向量一次性联合解算，用于输出最终坐标成果。
 *
 * <p><b>观察方程</b>：每条基线观测给出 Rover 与基站的坐标差
 * <pre>
 *   R − B_i = dXyz_i
 * </pre>
 *
 * <p><b>约束平差</b>：基站坐标视为已知（RTCM 播发值），只解 Rover 三维坐标。
 *
 * <p><b>自由网平差</b>：全部点坐标都作为未知数，仅附加重心基准
 * （Σ δ = 0）作为最小约束。解算结果给出各基站相对播发坐标的偏移量，
 * 可直接用于判断基站是否发生位移；绝对位置失去意义，但网内部几何最自洽。
 */
public class NetworkAdjustment {

    /**
     * 一条基线观测记录。
     *
     * @param baseId   基站标识
     * @param epochTag 历元标识
     * @param baseXyz  基站 ECEF 坐标（用于建立"基站位移"未知数）
     * @param dXyz     <b>真基线向量</b> {@code Rover − 基站} (m)。
     *                 注意与 {@code BaselineEpoch.BaselineEntry#dXyz} 不同，后者是 Rover 绝对位置，
     *                 二者不可直接互换。
     * @param gpsTime  观测历元 GPS 周内秒；0 表示未知（不参与时间窗淘汰）
     * @param cov      基线向量 3x3 协方差 (m²)
     */
    public record BaselineObs(
            String baseId,
            String epochTag,
            double gpsTime,
            double[] baseXyz,
            double[] dXyz,
            double[][] cov
    ) {
        public BaselineObs {
            if (baseXyz == null || baseXyz.length < 3) {
                throw new IllegalArgumentException("baseXyz 至少 3 维");
            }
            if (dXyz == null || dXyz.length < 3) {
                throw new IllegalArgumentException("dXyz 至少 3 维");
            }
        }
    }

    /** 平差结果。 */
    public record Result(
            boolean success,
            String message,
            double[] roverXyz,
            double[][] roverCov,
            Map<String, double[]> baseDelta,
            double sigma0,
            int dof,
            int obsCount,
            int unknownCount,
            double[] roverLlh
    ) {
        public static Result failure(String message) {
            return new Result(false, message, null, null, Map.of(), Double.NaN,
                    0, 0, 0, null);
        }
    }

    private static final double DATUM_WEIGHT = 1.0e4;

    // ---------------- 约束平差 ----------------

    /**
     * 约束平差：基站坐标已知，解 Rover 三维坐标。
     *
     * @param obs 基线观测（可跨多个历元）
     */
    public static Result constrained(List<BaselineObs> obs) {
        if (obs == null || obs.isEmpty()) {
            return Result.failure("无基线观测");
        }

        int m = obs.size();
        SimpleMatrix N = new SimpleMatrix(3, 3);
        SimpleMatrix u = new SimpleMatrix(3, 1);

        List<SimpleMatrix> rInvList = new ArrayList<>(m);
        for (BaselineObs o : obs) {
            SimpleMatrix R = covMatrix(o.cov());
            SimpleMatrix RInv = invert(R);
            if (RInv == null) {
                return Result.failure("观测协方差不可逆");
            }
            rInvList.add(RInv);

            SimpleMatrix l = new SimpleMatrix(3, 1);
            for (int c = 0; c < 3; c++) {
                l.set(c, 0, o.baseXyz()[c] + o.dXyz()[c]);
            }
            N = N.plus(RInv);
            u = u.plus(RInv.mult(l));
        }

        SimpleMatrix x = solve(N, u);
        if (x == null) {
            return Result.failure("法方程奇异");
        }

        double[] rover = new double[]{x.get(0, 0), x.get(1, 0), x.get(2, 0)};

        double vRv = 0.0;
        for (int i = 0; i < m; i++) {
            BaselineObs o = obs.get(i);
            SimpleMatrix v = new SimpleMatrix(3, 1);
            for (int c = 0; c < 3; c++) {
                v.set(c, 0, rover[c] - (o.baseXyz()[c] + o.dXyz()[c]));
            }
            vRv += v.transpose().mult(rInvList.get(i)).mult(v).get(0, 0);
        }

        int dof = 3 * m - 3;
        double sigma0 = dof > 0 ? Math.sqrt(Math.max(vRv / dof, 0.0)) : Double.NaN;

        SimpleMatrix NInv = invert(N);
        double[][] roverCov = new double[3][3];
        if (NInv != null) {
            double s2 = Double.isNaN(sigma0) ? 1.0 : sigma0 * sigma0;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    roverCov[i][j] = NInv.get(i, j) * s2;
                }
            }
        }

        return new Result(true, "OK", rover, roverCov, Map.of(),
                sigma0, dof, m, 3, ecefToLlh(rover));
    }

    // ---------------- 自由网平差 ----------------

    /**
     * 自由网平差：Rover 与各基站坐标全部作为未知数，附加重心基准最小约束。
     *
     * <p>结果中的 {@code baseDelta} 为各基站相对播发坐标的偏移（米），
     * 可直接用于基站位移判别。该值只有在观测跨越多历元时才有冗余度。
     *
     * @param obs 基线观测（应覆盖多个历元）
     */
    public static Result freeNetwork(List<BaselineObs> obs) {
        if (obs == null || obs.isEmpty()) {
            return Result.failure("无基线观测");
        }

        Map<String, Integer> baseIndex = new LinkedHashMap<>();
        for (BaselineObs o : obs) {
            baseIndex.putIfAbsent(o.baseId(), baseIndex.size());
        }
        int k = baseIndex.size();
        if (k < 1) {
            return Result.failure("无有效基站");
        }

        int n = 3 + 3 * k;

        double[] r0 = new double[3];
        for (BaselineObs o : obs) {
            for (int c = 0; c < 3; c++) {
                r0[c] += o.baseXyz()[c] + o.dXyz()[c];
            }
        }
        for (int c = 0; c < 3; c++) {
            r0[c] /= obs.size();
        }

        // 法方程逐观测累积。各基线独立解算 ⇒ 观测互不相关，权矩阵 R 严格块对角，
        // 第 i 个观测的权重就是其 3x3 协方差的逆。因此 N = HᵀR⁻¹H / u = HᵀR⁻¹l
        // 可以逐观测用 3x3 块累加，无需物化 m×m 的 R（万级观测会到 GB 级）。
        // 设计矩阵块结构：H_i = [I₃ | 0 | -I₃(第 bi 基站) | 0]。
        double[][] N = new double[n][n];
        double[] u = new double[n];
        List<double[][]> rInvList = new ArrayList<>(obs.size());

        for (BaselineObs o : obs) {
            int bi = baseIndex.get(o.baseId());
            SimpleMatrix riInv = invert(covMatrix(o.cov()));
            if (riInv == null) {
                return Result.failure("观测协方差不可逆");
            }
            double[][] ri = to3x3(riInv);
            rInvList.add(ri);

            // l_i = dXyz − (r0 − baseXyz)，u += HᵀR⁻¹l = R⁻¹l（rover 块）与 −R⁻¹l（base 块）
            for (int r = 0; r < 3; r++) {
                double rlr = 0.0;
                for (int c = 0; c < 3; c++) {
                    double lc = o.dXyz()[c] - (r0[c] - o.baseXyz()[c]);
                    rlr += ri[r][c] * lc;
                }
                u[r] += rlr;                          // (rover) += R⁻¹l
                u[3 + 3 * bi + r] -= rlr;             // (base)  −= R⁻¹l
            }
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    double w = ri[r][c];
                    N[r][c] += w;                     // (rover,rover) += R⁻¹
                    N[3 + 3 * bi + r][c] -= w;        // (base,rover)  −= R⁻¹
                    N[r][3 + 3 * bi + c] -= w;        // (rover,base)  −= R⁻¹
                    N[3 + 3 * bi + r][3 + 3 * bi + c] += w;  // (base,base) += R⁻¹
                }
            }
        }

        // 重心基准：Σ δ = 0，即 C = [I₃ | I₃ ... I₃]，N += w·CᵀC（仅同轴元素非零）
        for (int c = 0; c < 3; c++) {
            N[c][c] += DATUM_WEIGHT;
            for (int b1 = 0; b1 < k; b1++) {
                int r1 = 3 + 3 * b1 + c;
                N[c][r1] += DATUM_WEIGHT;
                N[r1][c] += DATUM_WEIGHT;
                for (int b2 = 0; b2 < k; b2++) {
                    N[r1][3 + 3 * b2 + c] += DATUM_WEIGHT;
                }
            }
        }

        double[] nFlat = new double[n * n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(N[r], 0, nFlat, r * n, n);
        }
        SimpleMatrix Nm = new SimpleMatrix(n, n, true, nFlat);
        SimpleMatrix x = solve(Nm, new SimpleMatrix(n, 1, true, u));
        if (x == null) {
            return Result.failure("法方程奇异");
        }

        double[] rover = new double[3];
        for (int c = 0; c < 3; c++) {
            rover[c] = r0[c] + x.get(c, 0);
        }

        Map<String, double[]> baseDelta = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : baseIndex.entrySet()) {
            int bi = e.getValue();
            baseDelta.put(e.getKey(), new double[]{
                    x.get(3 + 3 * bi, 0),
                    x.get(3 + 3 * bi + 1, 0),
                    x.get(3 + 3 * bi + 2, 0)
            });
        }

        // 残差平方和：v_i = H_i·x − l_i，vRv = Σ v_iᵀ R_i⁻¹ v_i（逐观测）。
        // 注意 H_i·x 用的是未知数（位移）x 本身：δr₀ − δb，不能用绝对坐标 rover/r0。
        double vRv = 0.0;
        int idx = 0;
        for (BaselineObs o : obs) {
            int bi = baseIndex.get(o.baseId());
            double[][] ri = rInvList.get(idx++);
            double q = 0.0;
            for (int a = 0; a < 3; a++) {
                double va = x.get(a, 0) - x.get(3 + 3 * bi + a, 0)
                        - (o.dXyz()[a] - (r0[a] - o.baseXyz()[a]));
                for (int b = 0; b < 3; b++) {
                    double vb = x.get(b, 0) - x.get(3 + 3 * bi + b, 0)
                            - (o.dXyz()[b] - (r0[b] - o.baseXyz()[b]));
                    q += va * ri[a][b] * vb;
                }
            }
            vRv += q;
        }

        int dof = 3 * obs.size() - 3 * k;
        if (dof < 0) dof = 0;
        double sigma0 = dof > 0 ? Math.sqrt(Math.max(vRv / dof, 0.0)) : Double.NaN;

        SimpleMatrix NInv = invert(Nm);
        double[][] roverCov = new double[3][3];
        if (NInv != null) {
            double s2 = Double.isNaN(sigma0) ? 1.0 : sigma0 * sigma0;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    roverCov[i][j] = NInv.get(i, j) * s2;
                }
            }
        }

        return new Result(true, "OK", rover, roverCov, baseDelta,
                sigma0, dof, 3 * obs.size(), n, ecefToLlh(rover));
    }

    // ---------------- 坐标系统转换 ----------------

    /** ECEF → 大地坐标 [lat(rad), lon(rad), height(m)]。 */
    public static double[] ecefToLlh(double[] xyz) {
        if (xyz == null) return null;
        double[] llh = new double[3];
        CoordTransform.ecef2pos(xyz, llh);
        return llh;
    }

    /** 大地坐标 → ECEF。 */
    public static double[] llhToEcef(double[] llh) {
        if (llh == null) return null;
        double[] xyz = new double[3];
        CoordTransform.pos2ecef(llh, xyz);
        return xyz;
    }

    /** ECEF → 以 refXyz 为原点的当地 ENU 坐标。 */
    public static double[] ecefToEnu(double[] xyz, double[] refXyz) {
        if (xyz == null || refXyz == null) return null;
        double[] llh = new double[3];
        CoordTransform.ecef2pos(refXyz, llh);
        double[] d = {xyz[0] - refXyz[0], xyz[1] - refXyz[1], xyz[2] - refXyz[2]};
        double[] enu = new double[3];
        CoordTransform.ecef2enu(llh, d, enu);
        return enu;
    }

    /** 当地 ENU → ECEF。 */
    public static double[] enuToEcef(double[] enu, double[] refXyz) {
        if (enu == null || refXyz == null) return null;
        double[] llh = new double[3];
        CoordTransform.ecef2pos(refXyz, llh);
        double[] d = new double[3];
        CoordTransform.enu2ecef(llh, enu, d);
        return new double[]{refXyz[0] + d[0], refXyz[1] + d[1], refXyz[2] + d[2]};
    }

    /**
     * 七参数（Bursa-Wolf 小角度模型）最小二乘拟合。
     *
     * <p>内部先对两组坐标去中心化再解算，避免 ECEF 量级坐标（10⁶ m）与平移量（1 m）
     * 混在同一法方程中导致条件数爆炸；解出旋转与尺度后按中心点回代平移量。
     *
     * @return {tX, tY, tZ, rX, rY, rZ, scale}，旋转单位弧度，尺度为无量纲比例（1e-6 级）
     */
    public static double[] fitHelmert7(List<double[]> src, List<double[]> dst) {
        if (src == null || dst == null || src.size() != dst.size() || src.size() < 3) {
            return null;
        }

        int n = 7;
        SimpleMatrix N = new SimpleMatrix(n, n);
        SimpleMatrix u = new SimpleMatrix(n, 1);

        double[] ms = centroid(src);
        double[] md = centroid(dst);
        if (ms == null || md == null) return null;

        for (int i = 0; i < src.size(); i++) {
            double[] a0 = src.get(i);
            double[] b0 = dst.get(i);
            if (a0 == null || b0 == null) return null;
            double[] a = {a0[0] - ms[0], a0[1] - ms[1], a0[2] - ms[2]};
            double[] b = {b0[0] - md[0], b0[1] - md[1], b0[2] - md[2]};

            double[][] H = new double[3][7];
            H[0] = new double[]{1, 0, 0, 0, a[2], -a[1], a[0]};
            H[1] = new double[]{0, 1, 0, -a[2], 0, a[0], a[1]};
            H[2] = new double[]{0, 0, 1, a[1], -a[0], 0, a[2]};

            for (int r = 0; r < 3; r++) {
                double res = (b[r] - a[r]);
                for (int c = 0; c < n; c++) {
                    u.set(c, 0, u.get(c, 0) + H[r][c] * res);
                    for (int d = 0; d < n; d++) {
                        N.set(c, d, N.get(c, d) + H[r][c] * H[r][d]);
                    }
                }
            }
        }

        SimpleMatrix x = solve(N, u);
        if (x == null) return null;

        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = x.get(i, 0);

        // 中心化模型：dst − md = t' + (1+k)·(I+Ω)·(src − ms)
        // 回代：t = md + t' − (1+k)·(I+Ω)·ms
        double rx = t[3], ry = t[4], rz = t[5];
        double k = 1.0 + t[6];
        double[] rot = {
                k * (ms[0] + ry * ms[2] - rz * ms[1]),
                k * (ms[1] - rx * ms[2] + rz * ms[0]),
                k * (ms[2] + rx * ms[1] - ry * ms[0])
        };
        t[0] = md[0] + t[0] - rot[0];
        t[1] = md[1] + t[1] - rot[1];
        t[2] = md[2] + t[2] - rot[2];
        return t;
    }

    private static double[] centroid(List<double[]> pts) {
        double[] sum = new double[3];
        int n = 0;
        for (double[] p : pts) {
            if (p == null || p.length < 3) continue;
            sum[0] += p[0];
            sum[1] += p[1];
            sum[2] += p[2];
            n++;
        }
        if (n == 0) return null;
        return new double[]{sum[0] / n, sum[1] / n, sum[2] / n};
    }

    /** 按七参数把源坐标转换到目标坐标系。 */
    public static double[] applyHelmert7(double[] src, double[] t) {
        if (src == null || t == null || t.length < 7) return null;
        double x = src[0], y = src[1], z = src[2];
        double wx = x + t[4] * z - t[5] * y;
        double wy = y - t[3] * z + t[5] * x;
        double wz = z + t[3] * y - t[4] * x;
        double k = 1.0 + t[6];
        return new double[]{
                t[0] + k * wx,
                t[1] + k * wy,
                t[2] + k * wz
        };
    }

    // ---------------- 数值工具 ----------------

    private static SimpleMatrix covMatrix(double[][] cov) {
        SimpleMatrix R = new SimpleMatrix(3, 3);
        if (cov == null) {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    R.set(i, j, i == j ? 0.01 : 0.0);
                }
            }
            return R;
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                R.set(i, j, cov[i][j]);
            }
        }
        return R;
    }

    /** 3x3 矩阵转二维数组。 */
    private static double[][] to3x3(SimpleMatrix m) {
        return new double[][]{
                {m.get(0, 0), m.get(0, 1), m.get(0, 2)},
                {m.get(1, 0), m.get(1, 1), m.get(1, 2)},
                {m.get(2, 0), m.get(2, 1), m.get(2, 2)}
        };
    }

    private static SimpleMatrix invert(SimpleMatrix m) {
        if (m == null || m.numRows() != m.numCols()) return null;
        try {
            SimpleMatrix inv = m.invert();
            int n = m.numRows();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (!Double.isFinite(inv.get(i, j))) return null;
                }
            }
            // 用 A·A⁻¹ ≈ I 的相对偏差判断是否可逆（行列式判据会随矩阵维度失真）
            SimpleMatrix prod = m.mult(inv);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double expect = (i == j) ? 1.0 : 0.0;
                    if (Math.abs(prod.get(i, j) - expect) > 0.05) return null;
                }
            }
            return inv;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static SimpleMatrix solve(SimpleMatrix N, SimpleMatrix u) {
        try {
            SimpleMatrix NInv = N.invert();
            return NInv.mult(u);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
