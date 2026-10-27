package org.rtklib.java.adjust.engine;

import org.rtklib.java.adjust.model.BaselineEpoch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 残差白度检验：判断平差残差是否仍含未被函数模型吸收的系统性误差。
 *
 * <p>平差的前提假设之一是观测残差为白噪声（零均值、互不相关）。若残差存在
 * 显著自相关，说明模型漏掉了某种系统性项（多路径、未建模的电离层/对流层残差、
 * 基站坐标偏差…），此时形式精度（{@code Dx}）会偏乐观。</p>
 *
 * <p>对每条残差序列（基站 × 坐标轴）做两项检验：</p>
 * <ul>
 *   <li><b>lag-1 自相关系数</b>：白噪声下近似 {@code N(0, 1/n)}，取 99% 置信带
 *       {@code |r| ≤ 2.58/√n}；</li>
 *   <li><b>游程检验（runs test）</b>：正负号交替次数偏离随机期望的程度，
 *       统计量 {@code z = (R − (n+1)/2) / √((n−1)/4)}，判据 {@code |z| ≤ 2.58}。</li>
 * </ul>
 *
 * <p>采用 99%（而非 95%）判据，是因为一次报告会同时检验"基站数 × 3 轴"条序列，
 * 单序列 95% 会把总体误报率放大到十几%。</p>
 *
 * <p>样本数少于 {@value #MIN_SAMPLES} 时不作判定（返回 pass），避免小样本误报。</p>
 */
public final class ResidualWhiteness {

    /** 判定所需的最小样本数，少于此值不作结论。 */
    private static final int MIN_SAMPLES = 30;

    /** 99% 双侧正态分位（多序列检验下控制总体误报率）。 */
    private static final double Z_99 = 2.58;

    /** 每条序列保留的最大样本数。 */
    private static final int MAX_SAMPLES = 600;

    /** 单条残差序列的检验结果。 */
    public record Series(
            String baseId,
            int axis,
            int samples,
            double lag1,
            double durbinWatson,
            double runsZ,
            boolean pass
    ) {
        /** 是否因样本不足而未作判定。 */
        public boolean undetermined() {
            return samples < MIN_SAMPLES;
        }
    }

    /** 汇总报告。 */
    public record Report(List<Series> series, boolean pass) {
        public static Report empty() {
            return new Report(List.of(), true);
        }
    }

    private final Map<String, Deque<Double>> series = new LinkedHashMap<>();

    /**
     * 喂入一个历元的平差残差。
     *
     * @param entries 该历元的基线条目（顺序须与 {@code v} 的分块顺序一致）
     * @param v       平差残差向量（长度 3k，每基线连续 3 个坐标轴分量）
     */
    public void feed(List<BaselineEpoch.BaselineEntry> entries, double[] v) {
        if (entries == null || v == null || v.length < 3 * entries.size()) return;

        for (int i = 0; i < entries.size(); i++) {
            String baseId = entries.get(i).baseId;
            for (int axis = 0; axis < 3; axis++) {
                String key = baseId + "|" + axis;
                Deque<Double> q = series.computeIfAbsent(key, x -> new ArrayDeque<>());
                q.addLast(v[3 * i + axis]);
                while (q.size() > MAX_SAMPLES) {
                    q.removeFirst();
                }
            }
        }
    }

    /**
     * 喂入单条基线相对融合解的交叉检核残差（预测残差）。
     *
     * <p>用于嫌疑基线被剔除（df=0）的历元：此时剩余基线的平差残差恒为 0，
     * 白度检验唯一有信息量的序列就是被剔除基线相对融合解的残差。
     * 若其显著非白（如基站坐标存在系统差），恰是白度检验应捕获的信号。</p>
     *
     * @param baseId 该残差所属的基站
     * @param v3     3 维残差 [vx, vy, vz] (m)
     */
    public void feedSingle(String baseId, double[] v3) {
        if (baseId == null || v3 == null || v3.length < 3) return;
        for (int axis = 0; axis < 3; axis++) {
            String key = baseId + "|" + axis;
            Deque<Double> q = series.computeIfAbsent(key, x -> new ArrayDeque<>());
            q.addLast(v3[axis]);
            while (q.size() > MAX_SAMPLES) {
                q.removeFirst();
            }
        }
    }

    public void reset() {
        series.clear();
    }

    /** 生成检验报告。 */
    public Report report() {
        List<Series> out = new ArrayList<>();
        boolean allPass = true;

        for (Map.Entry<String, Deque<Double>> e : series.entrySet()) {
            String key = e.getKey();
            int sep = key.lastIndexOf('|');
            String baseId = sep > 0 ? key.substring(0, sep) : key;
            int axis = sep > 0 ? Integer.parseInt(key.substring(sep + 1)) : 0;

            int n = e.getValue().size();
            double[] x = new double[n];
            int idx = 0;
            for (double d : e.getValue()) {
                x[idx++] = d;
            }

            double mean = 0.0;
            for (double d : x) mean += d;
            mean /= n;

            double denom = 0.0;
            for (int i = 0; i < n; i++) {
                x[i] -= mean;               // 就地去均值
                denom += x[i] * x[i];
            }

            double lag1 = Double.NaN;
            double durbinWatson = Double.NaN;
            if (denom > 0 && n >= 2) {
                double num = 0.0;
                double dwNum = 0.0;
                for (int i = 1; i < n; i++) {
                    num += x[i] * x[i - 1];
                    double diff = x[i] - x[i - 1];
                    dwNum += diff * diff;
                }
                lag1 = num / denom;
                durbinWatson = dwNum / denom;
            }

            int runs = 0;
            int prevSign = 0;
            int nonZero = 0;
            for (double d : x) {
                int sign = d > 0 ? 1 : (d < 0 ? -1 : 0);
                if (sign == 0) continue;
                nonZero++;
                if (sign != prevSign) {
                    runs++;
                    prevSign = sign;
                }
            }
            double runsZ = Double.NaN;
            if (nonZero >= 2) {
                double expected = (nonZero + 1) / 2.0;
                double sd = Math.sqrt((nonZero - 1) / 4.0);
                runsZ = sd > 0 ? (runs - expected) / sd : 0.0;
            }

            boolean pass = true;
            if (n >= MIN_SAMPLES && denom > 0) {
                double lag1Limit = Z_99 / Math.sqrt(n);
                pass = Math.abs(lag1) <= lag1Limit
                        && (Double.isNaN(runsZ) || Math.abs(runsZ) <= Z_99);
            }
            allPass = allPass && pass;

            out.add(new Series(baseId, axis, n, lag1, durbinWatson, runsZ, pass));
        }

        return new Report(out, allPass);
    }
}
