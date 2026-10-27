package org.rtklib.java.adjust.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基线一致性检核：重复基线、基站间相对漂移、异步环闭合差。
 *
 * <p><b>星形拓扑特性</b>：本模块输入的是各基线<b>独立解出的 Rover ECEF 位置</b>
 * {@code v_b}（同一历元、不同基站）。第 b 条基线的解算值会吸收其基站坐标偏差
 * {@code e_b}，即 {@code v_b = R_true − e_b}，于是任意两条基线之差：
 * <pre>
 *   m_ab = v_b − v_a = e_a − e_b = ΔA − ΔB
 * </pre>
 * Rover 位置被完全抵消，只反映两台基站之间的相对变化。因此基站间漂移检核
 * <b>不依赖 Rover 静止</b>——即使 Rover 在移动，基站间的相对位移依然可测。
 *
 * <p>若上游改用「Rover − 基站」增量而非绝对位置，两两求差的结果完全相同
 * （{@code (R−B) − (R−A) = A − B}），本模块无需区分；但同一批次内各基站
 * 必须使用同一约定，不可混用。
 *
 * <p><b>重复基线</b>：同一基站跨历元的基线向量较差。Rover 静止时等价于基站自身重复性；
 * Rover 移动时该指标同时包含 Rover 位移，不能单独归因于基站。
 *
 * <p><b>异步环闭合差</b>：星形网中任一闭合环都由 m_ab 线性组合而成。若三个环边取自
 * <b>同一时段</b>，闭合差恒为零（无检核价值）；因此本实现对三条边分别取时段的前/中/后
 * 三段均值，使闭合差反映"基站状态随时间变化"的不一致量。若要获得真正独立的环检核，
 * 必须引入基站之间的直接观测基线（星形拓扑本身不具备该冗余）。
 */
public class BaselineConsistencyChecker {

    /** 检核阈值配置，长度单位均为米。 */
    public record Config(
            double repeatThreshold,
            double driftThreshold,
            double loopThreshold,
            int maxSamples
    ) {
        public static Config defaults() {
            return new Config(0.010, 0.010, 0.015, 600);
        }
    }

    /** 单基站重复基线统计。 */
    public record BaseRepeat(String baseId, int samples, double rms, double maxDev) {}

    /** 基站间合成基线的时间漂移（后段均值 − 前段均值）。 */
    public record PairDrift(String baseA, String baseB, int samples, double[] drift, double driftNorm) {}

    /**
     * 单基站位移估计。
     *
     * <p>由 {@code drift_ij = Δ_i − Δ_j} 与重心基准 {@code Σ Δ = 0} 可得
     * {@code Δ_i = (1/k) · Σ_{j≠i} drift_ij}，因此可把"哪两台基站之间不一致"
     * 还原为"具体哪台基站移动了多少"。
     */
    public record BaseDrift(String baseId, int pairs, double[] delta, double norm) {}

    /** 异步环闭合差。 */
    public record LoopClosure(String baseA, String baseB, String baseC, double closure, boolean pass) {}

    /** 汇总报告。 */
    public record Report(
            List<BaseRepeat> repeats,
            List<PairDrift> drifts,
            List<BaseDrift> baseDrifts,
            List<LoopClosure> loops,
            boolean pass,
            List<String> suspectBaseIds
    ) {
        public static Report empty() {
            return new Report(List.of(), List.of(), List.of(), List.of(), true, List.of());
        }
    }

    private final Config config;
    private final Map<String, Deque<double[]>> perBase = new LinkedHashMap<>();
    private final Map<String, Deque<double[]>> perPair = new LinkedHashMap<>();
    private final Map<String, String[]> pairIds = new LinkedHashMap<>();

    public BaselineConsistencyChecker() {
        this(Config.defaults());
    }

    public BaselineConsistencyChecker(Config config) {
        this.config = (config != null) ? config : Config.defaults();
    }

    public Config config() {
        return config;
    }

    /**
     * 喂入一个历元的基线数据。
     *
     * @param epochTag    历元标识
     * @param baseToRover 基站标识 → 基线向量 [dX, dY, dZ]（Rover − 基站），单位米
     */
    public void feed(String epochTag, Map<String, double[]> baseToRover) {
        if (baseToRover == null || baseToRover.size() < 2) return;

        List<String> ids = new ArrayList<>(baseToRover.keySet());
        for (Map.Entry<String, double[]> e : baseToRover.entrySet()) {
            double[] v = e.getValue();
            if (v == null || v.length < 3) continue;
            Deque<double[]> q = perBase.computeIfAbsent(e.getKey(), x -> new ArrayDeque<>());
            append(q, new double[]{v[0], v[1], v[2]});
        }

        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                String a = ids.get(i);
                String b = ids.get(j);
                double[] va = baseToRover.get(a);
                double[] vb = baseToRover.get(b);
                if (va == null || vb == null || va.length < 3 || vb.length < 3) continue;

                // 统一按字典序存放，保证跨历元漂移差分的符号一致：
                // m = 后者的基线向量 − 前者的基线向量 = 前者基站坐标 − 后者基站坐标。
                boolean aFirst = a.compareTo(b) <= 0;
                String first = aFirst ? a : b;
                String second = aFirst ? b : a;
                double[] vFirst = aFirst ? va : vb;
                double[] vSecond = aFirst ? vb : va;

                double[] m = {vSecond[0] - vFirst[0], vSecond[1] - vFirst[1], vSecond[2] - vFirst[2]};
                String key = pairKey(a, b);
                Deque<double[]> q = perPair.computeIfAbsent(key, x -> new ArrayDeque<>());
                append(q, m);
                pairIds.put(key, new String[]{first, second});
            }
        }
    }

    public void reset() {
        perBase.clear();
        perPair.clear();
        pairIds.clear();
    }

    /** 生成检核报告。 */
    public Report report() {
        Set<String> suspects = new LinkedHashSet<>();

        // 重复基线：Rover 静止时等价于基站自身重复性；Rover 移动时会混入 Rover 位移，
        // 故仅作参考指标，不作为定位异常基站的主判据。
        List<BaseRepeat> repeats = buildRepeats();

        List<PairDrift> drifts = new ArrayList<>();
        for (Map.Entry<String, Deque<double[]>> e : perPair.entrySet()) {
            String[] ids = pairIds.get(e.getKey());
            if (ids == null) continue;
            double[] drift = halfDrift(e.getValue());
            if (drift == null) continue;
            drifts.add(new PairDrift(ids[0], ids[1], e.getValue().size(), drift, norm(drift)));
        }

        // 基站间漂移与 Rover 无关。k ≥ 3 时可由 drift_ij 反算单站位移，定位到具体基站；
        // k = 2 时只有一个方程、无法区分两台基站谁动了，只能标记整对。
        List<BaseDrift> baseDrifts = buildBaseDrifts(drifts);
        if (perBase.size() >= 3 && !baseDrifts.isEmpty()) {
            for (BaseDrift bd : baseDrifts) {
                if (bd.norm() > config.driftThreshold()) {
                    suspects.add(bd.baseId());
                }
            }
        } else {
            for (PairDrift pd : drifts) {
                if (pd.driftNorm() > config.driftThreshold()) {
                    suspects.add(pd.baseA());
                    suspects.add(pd.baseB());
                }
            }
        }

        List<LoopClosure> loops = buildLoops();
        for (LoopClosure lc : loops) {
            if (!lc.pass()) {
                suspects.add(lc.baseA());
                suspects.add(lc.baseB());
                suspects.add(lc.baseC());
            }
        }

        boolean pass = suspects.isEmpty();
        return new Report(repeats, drifts, baseDrifts, loops, pass, new ArrayList<>(suspects));
    }

    // ---------- 内部实现 ----------

    private List<BaseRepeat> buildRepeats() {
        List<BaseRepeat> repeats = new ArrayList<>();
        for (Map.Entry<String, Deque<double[]>> e : perBase.entrySet()) {
            Deque<double[]> q = e.getValue();
            if (q.isEmpty()) continue;
            double[] mean = mean(q);
            double sum2 = 0.0;
            double maxDev = 0.0;
            for (double[] v : q) {
                double d2 = dist2(v, mean);
                sum2 += d2;
                maxDev = Math.max(maxDev, Math.sqrt(d2));
            }
            double rms = Math.sqrt(sum2 / q.size());
            repeats.add(new BaseRepeat(e.getKey(), q.size(), rms, maxDev));
        }
        return repeats;
    }

    /**
     * 由 {@code drift_ij = Δ_i − Δ_j} 与重心基准 {@code Σ Δ = 0} 反算单站位移。
     * 完整星形网（每两台基站都有合成基线）下解为 {@code Δ_i = (1/k) · Σ_{j≠i} drift_ij}。
     */
    private List<BaseDrift> buildBaseDrifts(List<PairDrift> drifts) {
        int k = perBase.size();
        // k = 2 时只有一个方程，重心基准把漂移平分成 ±d/2，无法判断谁动了，故不给单站位移。
        if (k < 3) return List.of();

        Map<String, double[]> deltaSum = new LinkedHashMap<>();
        Map<String, Integer> pairCount = new LinkedHashMap<>();

        for (PairDrift pd : drifts) {
            accumulate(deltaSum, pairCount, pd.baseA(), pd.drift(), 1.0);
            accumulate(deltaSum, pairCount, pd.baseB(), pd.drift(), -1.0);
        }

        List<BaseDrift> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : deltaSum.entrySet()) {
            double[] s = e.getValue();
            double[] delta = {s[0] / k, s[1] / k, s[2] / k};
            out.add(new BaseDrift(e.getKey(), pairCount.get(e.getKey()), delta, norm(delta)));
        }
        return out;
    }

    private static void accumulate(Map<String, double[]> sum, Map<String, Integer> cnt,
                                   String id, double[] drift, double sign) {
        double[] s = sum.computeIfAbsent(id, x -> new double[3]);
        s[0] += sign * drift[0];
        s[1] += sign * drift[1];
        s[2] += sign * drift[2];
        cnt.merge(id, 1, Integer::sum);
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private List<LoopClosure> buildLoops() {
        List<String> bases = new ArrayList<>(perBase.keySet());
        List<LoopClosure> loops = new ArrayList<>();
        for (int i = 0; i < bases.size(); i++) {
            for (int j = i + 1; j < bases.size(); j++) {
                for (int m = j + 1; m < bases.size(); m++) {
                    String a = bases.get(i);
                    String b = bases.get(j);
                    String c = bases.get(m);

                    // 环 a→b→c→a 的闭合差：(A−B) + (B−C) + (C−A)
                    double[] segAB = signedSegment(a, b, 0);
                    double[] segBC = signedSegment(b, c, 1);
                    double[] segCA = signedSegment(c, a, 2);
                    if (segAB == null || segBC == null || segCA == null) continue;

                    double sx = segAB[0] + segBC[0] + segCA[0];
                    double sy = segAB[1] + segBC[1] + segCA[1];
                    double sz = segAB[2] + segBC[2] + segCA[2];
                    double closure = Math.sqrt(sx * sx + sy * sy + sz * sz);

                    loops.add(new LoopClosure(a, b, c, closure, closure <= config.loopThreshold()));
                }
            }
        }
        return loops;
    }

    /**
     * 取「a − b」形式的第 seg 段均值。
     *
     * <p>{@link #perPair} 中统一按字典序存放（m = 前者 − 后者），此处按调用方要求的
     * 端点顺序补上符号，保证环闭合差是真闭合（同段时恒为零）。
     */
    private double[] signedSegment(String a, String b, int seg) {
        String key = pairKey(a, b);
        String[] ids = pairIds.get(key);
        double[] s = segmentMean(perPair.get(key), seg);
        if (ids == null || s == null) return null;
        if (ids[0].equals(a)) return s;
        return new double[]{-s[0], -s[1], -s[2]};
    }

    /** 取时段内第 seg 段（0=前,1=中,2=后）的均值；样本不足返回 null。 */
    private static double[] segmentMean(Deque<double[]> q, int seg) {
        if (q == null || q.size() < 3) return null;
        List<double[]> list = new ArrayList<>(q);
        int n = list.size();
        int third = Math.max(1, n / 3);
        int from;
        int to;
        switch (seg) {
            case 0 -> { from = 0; to = third; }
            case 1 -> { from = third; to = n - third; }
            default -> { from = n - third; to = n; }
        }
        if (to <= from) return null;
        double[] sum = {0, 0, 0};
        for (int i = from; i < to; i++) {
            double[] v = list.get(i);
            sum[0] += v[0];
            sum[1] += v[1];
            sum[2] += v[2];
        }
        int cnt = to - from;
        return new double[]{sum[0] / cnt, sum[1] / cnt, sum[2] / cnt};
    }

    /** 后半段均值 − 前半段均值；样本不足返回 null。 */
    private static double[] halfDrift(Deque<double[]> q) {
        if (q == null || q.size() < 4) return null;
        List<double[]> list = new ArrayList<>(q);
        int n = list.size();
        int half = n / 2;

        double[] first = {0, 0, 0};
        for (int i = 0; i < half; i++) {
            first[0] += list.get(i)[0];
            first[1] += list.get(i)[1];
            first[2] += list.get(i)[2];
        }
        double[] second = {0, 0, 0};
        for (int i = half; i < n; i++) {
            second[0] += list.get(i)[0];
            second[1] += list.get(i)[1];
            second[2] += list.get(i)[2];
        }
        int n2 = n - half;
        return new double[]{
                second[0] / n2 - first[0] / half,
                second[1] / n2 - first[1] / half,
                second[2] / n2 - first[2] / half
        };
    }

    private static double[] mean(Deque<double[]> q) {
        double[] sum = {0, 0, 0};
        int n = 0;
        for (double[] v : q) {
            sum[0] += v[0];
            sum[1] += v[1];
            sum[2] += v[2];
            n++;
        }
        if (n == 0) return new double[]{0, 0, 0};
        return new double[]{sum[0] / n, sum[1] / n, sum[2] / n};
    }

    private void append(Deque<double[]> q, double[] v) {
        q.addLast(v);
        while (q.size() > Math.max(4, config.maxSamples())) {
            q.removeFirst();
        }
    }

    private static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static String pairKey(String a, String b) {
        return (a.compareTo(b) <= 0) ? a + "|" + b : b + "|" + a;
    }
}
