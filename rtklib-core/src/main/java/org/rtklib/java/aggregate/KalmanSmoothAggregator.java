package org.rtklib.java.aggregate;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.time.TimeSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡尔曼平滑聚合（前向 KF + 反向 RTS）。
 *
 * <p>适合跟踪窗口内真实位移。常速度模型，状态 x = [pos(3), vel(3)]。
 * 将 RTK 输出的 SolData 当作伪观测，存在"滤波之滤波"过度平滑风险，
 * 静态缓变场景可用，动态场景慎用。</p>
 */
final class KalmanSmoothAggregator {

    private static final int N = 6; // 状态维度 [pos(3), vel(3)]

    private KalmanSmoothAggregator() {
    }

    static AggregatedSol aggregate(List<SolData> window, KalmanParams params) {
        int n = window.size();
        if (n == 0) {
            return AggregatedSol.failure(AggregationStatus.EMPTY_WINDOW, null, AggregationMode.KALMAN_SMOOTH);
        }

        GTime lastEpochTime = new GTime(window.get(n - 1).time);

        // 提取观测（FIX 优先，降级时含 FLOAT）
        List<double[]> zs = new ArrayList<>();      // 观测位置 [3]
        List<SimpleMatrix> Rs = new ArrayList<>();   // 观测协方差 3x3
        List<GTime> times = new ArrayList<>();
        List<Boolean> hasObs = new ArrayList<>();    // 该历元是否有有效观测
        int rejectedNonFix = 0;

        for (SolData sol : window) {
            Position ecef = sol.getPosition(CoordType.ECEF);
            boolean isFix = sol.status == SolutionStatus.FIX;
            boolean isFloat = sol.status == SolutionStatus.FLOAT;

            if (ecef == null) {
                rejectedNonFix++;
                hasObs.add(false);
                zs.add(null);
                Rs.add(null);
                times.add(sol.time);
                continue;
            }

            if (!isFix) {
                if (!params.allowFloatFallback || !isFloat) {
                    rejectedNonFix++;
                    hasObs.add(false);
                    zs.add(null);
                    Rs.add(null);
                    times.add(sol.time);
                    continue;
                }
            }

            hasObs.add(true);
            zs.add(new double[]{ecef.v1, ecef.v2, ecef.v3});
            Rs.add(covToMatrix(sol.covEcef));
            times.add(sol.time);
        }

        // 统计有效观测数
        int nObs = 0;
        for (boolean h : hasObs) if (h) nObs++;
        if (nObs < params.minFixEpochs) {
            return AggregatedSol.failure(AggregationStatus.INSUFFICIENT_FIX, lastEpochTime, AggregationMode.KALMAN_SMOOTH);
        }

        // 找到第一个有观测的历元初始化
        int firstObs = -1;
        for (int i = 0; i < n; i++) {
            if (hasObs.get(i)) { firstObs = i; break; }
        }

        // 前向滤波
        SimpleMatrix[] xf = new SimpleMatrix[n]; // 前向状态估计 [6x1]
        SimpleMatrix[] Pf = new SimpleMatrix[n]; // 前向协方差 [6x6]
        int consecutiveMiss = 0;

        // 初始化
        xf[firstObs] = new SimpleMatrix(N, 1);
        for (int k = 0; k < 3; k++) xf[firstObs].set(k, 0, zs.get(firstObs)[k]);
        Pf[firstObs] = new SimpleMatrix(N, N);
        SimpleMatrix R0 = Rs.get(firstObs);
        if (R0 != null) {
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    Pf[firstObs].set(i, j, R0.get(i, j));
        } else {
            for (int i = 0; i < 3; i++) Pf[firstObs].set(i, i, 1.0);
        }
        for (int i = 3; i < N; i++) Pf[firstObs].set(i, i, params.initVelVar);

        for (int k = firstObs + 1; k < n; k++) {
            double dt = TimeSystem.timediff(times.get(k), times.get(k - 1));
            if (dt < 0) dt = 0.0;

            // 状态转移 F = [I, dt*I; 0, I]
            SimpleMatrix F = stateTransition(dt);
            // 过程噪声 Q
            SimpleMatrix Q = processNoise(dt, params.q);

            // 预测
            SimpleMatrix x_pred = F.mult(xf[k - 1]);
            SimpleMatrix P_pred = F.mult(Pf[k - 1]).mult(F.transpose()).plus(Q);

            if (hasObs.get(k)) {
                consecutiveMiss = 0;
                SimpleMatrix z = vec3(zs.get(k));
                SimpleMatrix R = Rs.get(k);
                if (R == null) {
                    // 无协方差，给一个默认值
                    R = SimpleMatrix.identity(3).scale(1.0);
                }
                // 观测矩阵 H = [I, 0] (3x6)
                SimpleMatrix H = new SimpleMatrix(3, N);
                for (int i = 0; i < 3; i++) H.set(i, i, 1.0);

                SimpleMatrix y = z.minus(H.mult(x_pred));
                SimpleMatrix S = H.mult(P_pred).mult(H.transpose()).plus(R);
                SimpleMatrix K;
                try {
                    K = P_pred.mult(H.transpose()).mult(S.invert());
                } catch (Exception e) {
                    K = new SimpleMatrix(N, 3); // 增益为零，跳过更新
                }
                xf[k] = x_pred.plus(K.mult(y));
                SimpleMatrix I = SimpleMatrix.identity(N);
                Pf[k] = (I.minus(K.mult(H))).mult(P_pred);
            } else {
                consecutiveMiss++;
                xf[k] = x_pred;
                Pf[k] = P_pred;
                if (consecutiveMiss > params.maxConsecutiveMiss) {
                    return AggregatedSol.failure(AggregationStatus.KALMAN_OBS_LOST, lastEpochTime, AggregationMode.KALMAN_SMOOTH);
                }
            }
        }

        // RTS 反向平滑
        SimpleMatrix[] xs = new SimpleMatrix[n];
        SimpleMatrix[] Ps = new SimpleMatrix[n];
        xs[n - 1] = xf[n - 1];
        Ps[n - 1] = Pf[n - 1];

        for (int k = n - 2; k >= firstObs; k--) {
            double dt = TimeSystem.timediff(times.get(k + 1), times.get(k));
            if (dt < 0) dt = 0.0;
            SimpleMatrix F = stateTransition(dt);
            SimpleMatrix Q = processNoise(dt, params.q);

            SimpleMatrix x_pred = F.mult(xf[k]);
            SimpleMatrix P_pred = F.mult(Pf[k]).mult(F.transpose()).plus(Q);

            SimpleMatrix Ks;
            try {
                Ks = Pf[k].mult(F.transpose()).mult(P_pred.invert());
            } catch (Exception e) {
                Ks = new SimpleMatrix(N, N);
            }
            xs[k] = xf[k].plus(Ks.mult(xs[k + 1].minus(x_pred)));
            Ps[k] = Pf[k].plus(Ks.mult(Ps[k + 1].minus(P_pred)).mult(Ks.transpose()));
        }

        // 输出末时刻平滑状态
        SimpleMatrix xOut = xs[n - 1];
        SimpleMatrix POut = Ps[n - 1];

        double[] posEcef = new double[3];
        for (int k = 0; k < 3; k++) posEcef[k] = xOut.get(k, 0);

        double[] covEcef = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                covEcef[i * 3 + j] = POut.get(i, j);

        // 窗口标准差（用前向位置估计的散布近似）
        double[] windowStd = computeWindowStd(xf, firstObs, n);

        return new AggregatedSol(AggregationStatus.OK, lastEpochTime, lastEpochTime,
                posEcef, covEcef, null, nObs, rejectedNonFix, windowStd, AggregationMode.KALMAN_SMOOTH);
    }

    private static double[] computeWindowStd(SimpleMatrix[] xf, int firstObs, int n) {
        double[] mean = new double[3];
        int cnt = 0;
        for (int k = firstObs; k < n; k++) {
            for (int i = 0; i < 3; i++) mean[i] += xf[k].get(i, 0);
            cnt++;
        }
        if (cnt == 0) return new double[3];
        for (int i = 0; i < 3; i++) mean[i] /= cnt;

        double[] var = new double[3];
        for (int k = firstObs; k < n; k++) {
            for (int i = 0; i < 3; i++) {
                double d = xf[k].get(i, 0) - mean[i];
                var[i] += d * d;
            }
        }
        double[] std = new double[3];
        for (int i = 0; i < 3; i++) std[i] = Math.sqrt(var[i] / Math.max(1, cnt - 1));
        return std;
    }

    private static SimpleMatrix stateTransition(double dt) {
        SimpleMatrix F = SimpleMatrix.identity(N);
        for (int i = 0; i < 3; i++) {
            F.set(i, i + 3, dt);
        }
        return F;
    }

    private static SimpleMatrix processNoise(double dt, double q) {
        // 常速度模型：Q = q² * [dt³/3, dt²/2; dt²/2, dt] （每个轴独立）
        SimpleMatrix Q = new SimpleMatrix(N, N);
        double q2 = q * q;
        double t1 = q2 * dt * dt * dt / 3.0;
        double t2 = q2 * dt * dt / 2.0;
        double t3 = q2 * dt;
        for (int i = 0; i < 3; i++) {
            Q.set(i, i, t1);
            Q.set(i, i + 3, t2);
            Q.set(i + 3, i, t2);
            Q.set(i + 3, i + 3, t3);
        }
        return Q;
    }

    private static SimpleMatrix covToMatrix(double[] covEcef) {
        if (covEcef == null || covEcef.length < 9) return null;
        SimpleMatrix m = new SimpleMatrix(3, 3);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                m.set(i, j, covEcef[i * 3 + j]);
        return m;
    }

    private static SimpleMatrix vec3(double[] v) {
        SimpleMatrix m = new SimpleMatrix(3, 1);
        for (int i = 0; i < 3; i++) m.set(i, 0, v[i]);
        return m;
    }
}
