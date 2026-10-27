package org.rtklib.java.aggregate;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.time.TimeSystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 鲁棒加权聚合（IGG3 降权 + 加权平均）。
 *
 * <p>适合静态/缓变场景：压噪声、抗假固定。输出经验协方差（不偏乐观）。</p>
 */
final class RobustAggregator {

    private RobustAggregator() {
    }

    static AggregatedSol aggregate(List<SolData> window, RobustParams params) {
        int n = window.size();
        if (n == 0) {
            return AggregatedSol.failure(AggregationStatus.EMPTY_WINDOW, null, AggregationMode.ROBUST);
        }

        GTime lastEpochTime = new GTime(window.get(n - 1).time);

        // 提取 FIX 历元的坐标和协方差
        List<double[]> xs = new ArrayList<>();
        List<SimpleMatrix> Cs = new ArrayList<>();
        List<GTime> times = new ArrayList<>();
        int rejectedNonFix = 0;

        for (SolData sol : window) {
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) {
                rejectedNonFix++;
                continue;
            }
            boolean isFix = sol.status == SolutionStatus.FIX;
            if (!isFix) {
                rejectedNonFix++;
                continue;
            }
            xs.add(new double[]{ecef.v1, ecef.v2, ecef.v3});
            Cs.add(covToMatrix(sol.covEcef));
            times.add(sol.time);
        }

        int nFix = xs.size();
        if (nFix < params.minFixEpochs) {
            if (params.allowFloatFallback) {
                // 降级：重新纳入 FLOAT 历元
                return aggregateWithFloat(window, params, lastEpochTime, rejectedNonFix);
            }
            AggregatedSol fail = AggregatedSol.failure(AggregationStatus.INSUFFICIENT_FIX, lastEpochTime, AggregationMode.ROBUST);
            return withCounts(fail, 0, rejectedNonFix, null);
        }

        return doAggregate(xs, Cs, times, params, lastEpochTime, rejectedNonFix);
    }

    private static AggregatedSol aggregateWithFloat(List<SolData> window, RobustParams params,
                                                     GTime lastEpochTime, int alreadyRejected) {
        List<double[]> xs = new ArrayList<>();
        List<SimpleMatrix> Cs = new ArrayList<>();
        List<GTime> times = new ArrayList<>();

        for (SolData sol : window) {
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            if (sol.status != SolutionStatus.FIX && sol.status != SolutionStatus.FLOAT) continue;
            xs.add(new double[]{ecef.v1, ecef.v2, ecef.v3});
            Cs.add(covToMatrix(sol.covEcef));
            times.add(sol.time);
        }

        if (xs.size() < params.minFixEpochs) {
            return AggregatedSol.failure(AggregationStatus.INSUFFICIENT_FIX, lastEpochTime, AggregationMode.ROBUST);
        }
        return doAggregate(xs, Cs, times, params, lastEpochTime, alreadyRejected);
    }

    private static AggregatedSol doAggregate(List<double[]> xs, List<SimpleMatrix> Cs, List<GTime> times,
                                              RobustParams params, GTime lastEpochTime, int rejectedNonFix) {
        int n = xs.size();

        // 初值
        double[] x0 = new double[3];
        if (params.useMedianInit) {
            for (int k = 0; k < 3; k++) {
                double[] col = new double[n];
                for (int i = 0; i < n; i++) col[i] = xs.get(i)[k];
                x0[k] = median(col);
            }
        } else {
            // 协方差加权平均（仅含协方差非空的）
            SimpleMatrix sumInv = new SimpleMatrix(3, 3);
            SimpleMatrix sumInvX = new SimpleMatrix(3, 1);
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                SimpleMatrix Ci = Cs.get(i);
                if (Ci == null) continue;
                SimpleMatrix CiInv = safeInvert(Ci);
                if (CiInv == null) continue;
                sumInv = sumInv.plus(CiInv);
                SimpleMatrix xi = vec3(xs.get(i));
                sumInvX = sumInvX.plus(CiInv.mult(xi));
                cnt++;
            }
            if (cnt > 0) {
                SimpleMatrix x0m = safeInvert(sumInv).mult(sumInvX);
                for (int k = 0; k < 3; k++) x0[k] = x0m.get(k, 0);
            } else {
                for (int k = 0; k < 3; k++) {
                    double[] col = new double[n];
                    for (int i = 0; i < n; i++) col[i] = xs.get(i)[k];
                    x0[k] = median(col);
                }
            }
        }

        double[] w = new double[n];
        Arrays.fill(w, 1.0);

        double[] xhat = Arrays.copyOf(x0, 3);
        SimpleMatrix Chat = null;
        int zeroedCount = 0;

        for (int iter = 0; iter < params.maxIter; iter++) {
            double[] wOld = Arrays.copyOf(w, n);

            // 标准化残差 + IGG3 降权
            for (int i = 0; i < n; i++) {
                SimpleMatrix Ci = Cs.get(i);
                if (Ci == null) {
                    w[i] = 1.0; // 无协方差历元，权重保持 1（仅当初值用，不参与加权平均）
                    continue;
                }
                double[] r = new double[3];
                for (int k = 0; k < 3; k++) r[k] = xs.get(i)[k] - xhat[k];
                SimpleMatrix ri = vec3(r);
                SimpleMatrix CiInv = safeInvert(Ci);
                if (CiInv == null) {
                    w[i] = 1.0;
                    continue;
                }
                double z = Math.sqrt(Math.max(0.0, ri.transpose().mult(CiInv).mult(ri).get(0, 0)));
                w[i] = igg3Weight(z, params.k0, params.k1);
            }

            // 加权平均（仅含 w>0 且有协方差的历元）
            SimpleMatrix sumWInv = new SimpleMatrix(3, 3);
            SimpleMatrix sumWInvX = new SimpleMatrix(3, 1);
            int used = 0;
            for (int i = 0; i < n; i++) {
                if (w[i] <= 0.0) continue;
                SimpleMatrix Ci = Cs.get(i);
                if (Ci == null) continue;
                SimpleMatrix CiInv = safeInvert(Ci);
                if (CiInv == null) continue;
                SimpleMatrix wCiInv = CiInv.scale(w[i]);
                sumWInv = sumWInv.plus(wCiInv);
                sumWInvX = sumWInvX.plus(wCiInv.mult(vec3(xs.get(i))));
                used++;
            }

            if (used == 0) {
                // 全部被置零，退回中位数
                for (int k = 0; k < 3; k++) {
                    double[] col = new double[n];
                    for (int i = 0; i < n; i++) col[i] = xs.get(i)[k];
                    xhat[k] = median(col);
                }
                break;
            }

            Chat = safeInvert(sumWInv);
            if (Chat == null) break;
            SimpleMatrix xhatM = Chat.mult(sumWInvX);
            for (int k = 0; k < 3; k++) xhat[k] = xhatM.get(k, 0);

            // 收敛判断
            double dw = 0.0;
            for (int i = 0; i < n; i++) dw += Math.abs(w[i] - wOld[i]);
            if (dw < 1e-6) break;
        }

        // 统计被置零的 FIX 数
        for (int i = 0; i < n; i++) {
            if (w[i] <= 0.0) zeroedCount++;
        }

        // 经验协方差
        SimpleMatrix Cemp = empiricalCov(xs, w, xhat);
        double[] covEcef = matrixToCov(Cemp);

        // 小样本保护：对角线下限取 Ĉ 对应分量
        int usedCount = 0;
        for (int i = 0; i < n; i++) if (w[i] > 0.0) usedCount++;
        if (usedCount < params.minEmpiricalEpochs && Chat != null) {
            for (int k = 0; k < 3; k++) {
                double floor = Chat.get(k, k);
                if (covEcef[k * 3 + k] < floor) covEcef[k * 3 + k] = floor;
            }
        }

        double[] covTheo = Chat != null ? matrixToCov(Chat) : null;

        // 窗口标准差
        double[] windowStd = new double[3];
        for (int k = 0; k < 3; k++) {
            windowStd[k] = Math.sqrt(Math.max(0.0, Cemp.get(k, k)));
        }

        // 时间加权中心（权重 = w_i）
        GTime centerTime = weightedTimeCenter(times, w);

        int rejected = rejectedNonFix + zeroedCount;
        return new AggregatedSol(AggregationStatus.OK, centerTime, lastEpochTime,
                xhat, covEcef, covTheo, usedCount, rejected, windowStd, AggregationMode.ROBUST);
    }

    private static double igg3Weight(double z, double k0, double k1) {
        double az = Math.abs(z);
        if (az <= k0) return 1.0;
        if (az <= k1) return k0 / az;
        return 0.0;
    }

    private static SimpleMatrix empiricalCov(List<double[]> xs, double[] w, double[] xhat) {
        int n = xs.size();
        SimpleMatrix C = new SimpleMatrix(3, 3);
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (w[i] <= 0.0) continue;
            double[] r = new double[3];
            for (int k = 0; k < 3; k++) r[k] = xs.get(i)[k] - xhat[k];
            SimpleMatrix ri = vec3(r);
            C = C.plus(ri.mult(ri.transpose()));
            cnt++;
        }
        if (cnt > 1) {
            C = C.scale(1.0 / (cnt - 1));
        }
        return C;
    }

    private static GTime weightedTimeCenter(List<GTime> times, double[] w) {
        // 以第一个历元为基准，计算加权平均时间差
        GTime t0 = times.get(0);
        double sumW = 0.0, sumWt = 0.0;
        for (int i = 0; i < times.size(); i++) {
            if (w[i] <= 0.0) continue;
            double dt = TimeSystem.timediff(times.get(i), t0);
            sumW += w[i];
            sumWt += w[i] * dt;
        }
        if (sumW <= 0.0) return new GTime(t0);
        double dtCenter = sumWt / sumW;
        return TimeSystem.timeadd(t0, dtCenter);
    }

    private static SimpleMatrix covToMatrix(double[] covEcef) {
        if (covEcef == null || covEcef.length < 9) return null;
        SimpleMatrix m = new SimpleMatrix(3, 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                m.set(i, j, covEcef[i * 3 + j]);
        return m;
    }

    private static double[] matrixToCov(SimpleMatrix m) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                c[i * 3 + j] = m.get(i, j);
        return c;
    }

    private static SimpleMatrix vec3(double[] v) {
        SimpleMatrix m = new SimpleMatrix(3, 1);
        for (int i = 0; i < 3; i++) m.set(i, 0, v[i]);
        return m;
    }

    private static SimpleMatrix safeInvert(SimpleMatrix m) {
        try {
            return m.invert();
        } catch (Exception e) {
            return null;
        }
    }

    private static double median(double[] a) {
        double[] s = Arrays.copyOf(a, a.length);
        Arrays.sort(s);
        int mid = s.length / 2;
        if (s.length % 2 == 1) return s[mid];
        return (s[mid - 1] + s[mid]) / 2.0;
    }

    private static AggregatedSol withCounts(AggregatedSol sol, int used, int rejected, double[] std) {
        return new AggregatedSol(sol.status, sol.time, sol.lastEpochTime, sol.posEcef,
                sol.covEcef, sol.covEcefTheoretical, used, rejected, std, sol.mode);
    }
}
