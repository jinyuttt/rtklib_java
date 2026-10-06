package org.rtklib.java.research.ambiguity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ejml.simple.SimpleMatrix;

/**
 * 整数模糊度聚类估计器（IACE）。
 *
 * <p>用 DBSCAN 聚类替代方差映射，解决遮挡/多径场景下固定解连续性差的问题。
 * 每颗卫星独立维护浮点模糊度样本缓冲区，满足最小样本数后触发 1D DBSCAN。</p>
 *
 * <h3>算法流程</h3>
 * <ol>
 *   <li>每历元 addSample(satId, floatAmb) → 卫星独立环形缓冲区</li>
 *   <li>estimate(satIds) → 对每颗卫星运行 1D-DBSCAN</li>
 *   <li>自适应 eps = clamp(2σ_buf, 0.10, 0.50) cycles</li>
 *   <li>簇评分 = ratioSize / (1 + σ_cluster)</li>
 *   <li>跨卫星一致性投票：>50% 卫星评分达标 → fix</li>
 * </ol>
 *
 * <p>距离：欧氏距离（wavelength 归一化后为 cycle 单位）。</p>
 */
public class IaceEstimator {

    private static final double EPS_MIN = 0.10;
    private static final double EPS_MAX = 0.50;
    private static final double EPS_FACTOR = 2.0;
    private static final double SCORE_THRESHOLD = 0.5;
    private static final double CONSISTENCY_RATIO = 0.5;

    private final int minSamples;
    private final int bufferCapacity;
    private final Map<Integer, ArrayDeque<Double>> buffers;

    public IaceEstimator() {
        this(10, 30);
    }

    public IaceEstimator(int minSamples, int bufferCapacity) {
        this.minSamples = minSamples;
        this.bufferCapacity = bufferCapacity;
        this.buffers = new HashMap<>();
    }

    public void addSample(int satId, double floatAmb) {
        ArrayDeque<Double> buf = buffers.computeIfAbsent(satId, k -> new ArrayDeque<>());
        buf.addLast(floatAmb);
        while (buf.size() > bufferCapacity) {
            buf.pollFirst();
        }
    }

    public void removeSatellite(int satId) {
        buffers.remove(satId);
    }

    public void reset(int satId) {
        buffers.remove(satId);
    }

    public void resetAll() {
        buffers.clear();
    }

    /**
     * 执行聚类估计。
     *
     * @param satIds 当前历元的卫星 ID 列表（确定输出顺序）
     * @return 聚类估计结果
     */
    public IaceResult estimate(List<Integer> satIds) {
        if (satIds.isEmpty()) return IaceResult.fail();

        int n = satIds.size();
        double[] fixedAmb = new double[n];
        boolean[] isFixed = new boolean[n];
        double[] scores = new double[n];
        int fixedCount = 0;
        int bestClusterSize = 0;

        for (int i = 0; i < n; i++) {
            ArrayDeque<Double> buf = buffers.get(satIds.get(i));
            if (buf == null || buf.size() < minSamples) {
                continue;
            }

            SatelliteCluster cluster = cluster1D(buf);
            if (cluster == null) continue;

            double score = computeScore(cluster);
            scores[i] = score;
            bestClusterSize = Math.max(bestClusterSize, cluster.size);

            if (score >= SCORE_THRESHOLD) {
                fixedAmb[i] = cluster.center;
                isFixed[i] = true;
                fixedCount++;
            }
        }

        int quorum = (int) Math.ceil(n * CONSISTENCY_RATIO);
        if (fixedCount < quorum) {
            return IaceResult.fail();
        }

        SimpleMatrix fixed = new SimpleMatrix(n, 1);
        for (int i = 0; i < n; i++) {
            fixed.set(i, 0, isFixed[i] ? fixedAmb[i] : 0);
        }

        double avgScore = 0;
        int scoreCount = 0;
        for (int i = 0; i < n; i++) {
            if (isFixed[i]) { avgScore += scores[i]; scoreCount++; }
        }
        avgScore = scoreCount > 0 ? avgScore / scoreCount : 0;

        return IaceResult.success(fixed, avgScore, n, fixedCount, bestClusterSize);
    }

    private SatelliteCluster cluster1D(ArrayDeque<Double> buffer) {
        Double[] samples = buffer.toArray(new Double[0]);
        int n = samples.length;

        double mean = 0;
        for (double s : samples) mean += s;
        mean /= n;

        double std = 0;
        for (double s : samples) std += (s - mean) * (s - mean);
        std = Math.sqrt(std / n);

        double eps = Math.max(EPS_MIN, Math.min(EPS_MAX, EPS_FACTOR * std));

        boolean[] visited = new boolean[n];
        int[] labels = new int[n];
        Arrays.fill(labels, -1);

        int clusterId = 0;
        int[] clusterSizes = new int[n];

        for (int i = 0; i < n; i++) {
            if (visited[i]) continue;
            visited[i] = true;

            List<Integer> neighbors = regionQuery(samples, i, eps);
            if (neighbors.size() < minSamples) {
                labels[i] = -1;
                continue;
            }

            labels[i] = clusterId;
            clusterSizes[clusterId] = 1;

            ArrayDeque<Integer> seeds = new ArrayDeque<>(neighbors);
            while (!seeds.isEmpty()) {
                int q = seeds.pollFirst();
                if (visited[q]) {
                    if (labels[q] == -1) {
                        labels[q] = clusterId;
                        clusterSizes[clusterId]++;
                    }
                    continue;
                }
                visited[q] = true;
                List<Integer> qNeighbors = regionQuery(samples, q, eps);
                if (qNeighbors.size() >= minSamples) {
                    for (int r : qNeighbors) {
                        if (!visited[r]) seeds.addLast(r);
                    }
                }
                if (labels[q] == -1) {
                    labels[q] = clusterId;
                    clusterSizes[clusterId]++;
                }
            }
            clusterId++;
        }

        int bestId = -1;
        int bestSize = -1;
        for (int c = 0; c < clusterId; c++) {
            if (clusterSizes[c] > bestSize) {
                bestSize = clusterSizes[c];
                bestId = c;
            }
        }

        if (bestId < 0 || bestSize < minSamples) return null;

        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] == bestId) { sum += samples[i]; cnt++; }
        }

        double center = Math.round(sum / cnt);

        double clusterStd = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] == bestId) {
                clusterStd += (samples[i] - center) * (samples[i] - center);
            }
        }
        clusterStd = Math.sqrt(Math.max(clusterStd / cnt, 0));

        return new SatelliteCluster(center, clusterStd, cnt);
    }

    private List<Integer> regionQuery(Double[] samples, int idx, double eps) {
        List<Integer> neighbors = new ArrayList<>();
        for (int i = 0; i < samples.length; i++) {
            if (i != idx && Math.abs(samples[i] - samples[idx]) < eps) {
                neighbors.add(i);
            }
        }
        return neighbors;
    }

    private double computeScore(SatelliteCluster cluster) {
        return cluster.size / (double) bufferCapacity / (1.0 + cluster.std);
    }

    private static class SatelliteCluster {
        final double center;
        final double std;
        final int size;

        SatelliteCluster(double center, double std, int size) {
            this.center = center;
            this.std = std;
            this.size = size;
        }
    }
}