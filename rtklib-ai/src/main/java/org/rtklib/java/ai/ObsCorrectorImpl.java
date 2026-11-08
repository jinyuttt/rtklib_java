package org.rtklib.java.ai;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.feature.ObsCorrectionFeatures;
import org.rtklib.java.ai.inference.BatchInference;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.SatFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 接口三实现（设计文档 §6.1）：{@code ns × nf} 行特征 → 一次批量推理 → 成对输出。
 * <p>
 * 三条硬边界（§4.3.1）在本类体现为「什么都不做」：不改 {@code obs}、不剔除卫星、
 * 只输出偏差与方差膨胀因子，由 core 侧施加。
 * </p>
 * <p>
 * <b>短窗状态住在本类</b>（§5.3）：core 只给当前历元快照，窗口按 {@code (sat, frq)}
 * 维护长度 {@value ObsCorrectionFeatures#WINDOW} 的环形缓冲。三条重置规则：
 * </p>
 * <ol>
 *   <li>{@code epoch} 间隔 &gt; {@code 1.5 * dt}（数据缺口），或 {@code epoch} 倒退
 *       （同一实例被跨会话复用）→ 清空全部窗口</li>
 *   <li>{@code slip[frq] != 0}（该频点周跳）→ 只清空该 {@code (sat, frq)} 的窗口</li>
 *   <li>本历元不再出现的 {@code sat} → 移除，避免无界增长</li>
 * </ol>
 * <p>
 * {@code dt} 的唯一来源是 {@code ctx.dt()}；{@code dt <= 0} 或非有限时按「清空全部窗口」
 * 处理，该历元 {@code histLen} 退化为 {@code 0.2}（§5.3）。
 * </p>
 * <p>
 * 线程安全：窗口是实例状态，<b>不可</b>跨线程并发调用同一个实例。
 * </p>
 */
public final class ObsCorrectorImpl implements ObsCorrector {

    private static final Logger log = LoggerFactory.getLogger(ObsCorrectorImpl.class);

    /** 模型输出维：{@code [biasMeters, varianceScale]} 成对输出（§4.3.4 强制）。 */
    public static final int OUT_DIM = 2;

    /** 数据缺口判据系数：间隔 &gt; {@code 1.5 * dt} 视为缺口（§5.3）。 */
    private static final double GAP_FACTOR = 1.5;

    private final BatchInference inference;
    private final FeatureNorm norm;

    /** 键 = {@code sat * KEY_STRIDE + frq}，值为该行最近若干历元的 (resp, resc) 样本。 */
    private final Map<Integer, Window> windows = new HashMap<>();

    private int lastEpoch = Integer.MIN_VALUE;

    /**
     * @param inference 批量推理后端，非 {@code null}
     * @param norm      归一化参数，{@code null} 等价于该维度的恒等归一化
     * @throws IllegalArgumentException 输入/输出维与接口三契约不符（契约违规须 fail-fast）
     */
    public ObsCorrectorImpl(BatchInference inference, FeatureNorm norm) {
        if (inference == null) {
            throw new IllegalArgumentException("BatchInference 不得为 null");
        }
        if (inference.inputDim() != ObsCorrectionFeatures.DIM) {
            throw new IllegalArgumentException("模型输入维 " + inference.inputDim()
                    + " 与接口三特征维 " + ObsCorrectionFeatures.DIM + " 不一致");
        }
        if (inference.outputDim() != OUT_DIM) {
            throw new IllegalArgumentException("模型输出维 " + inference.outputDim()
                    + " 必须为 " + OUT_DIM
                    + "（接口三要求 bias 与 varianceScale 成对输出，只输出 bias 属契约违规，§4.3.4）");
        }
        if (norm != null && !norm.isIdentity() && norm.dim() != ObsCorrectionFeatures.DIM) {
            throw new IllegalArgumentException("归一化参数维 " + norm.dim()
                    + " 与接口三特征维 " + ObsCorrectionFeatures.DIM + " 不一致");
        }
        this.inference = inference;
        this.norm = norm == null ? FeatureNorm.identity(ObsCorrectionFeatures.DIM) : norm;
    }

    @Override
    public CodeCorrection correct(ObsCorrectionContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ObsCorrectionContext 不得为 null");
        }
        int ns = ctx.ns();
        int nf = ctx.nf();
        SatFeature[] sats = ctx.sats();
        if (ns <= 0 || nf <= 0 || sats == null || sats.length != ns) {
            log.warn("接口三上下文非法（ns={}, nf={}, sats={}），按不干预处理",
                    ns, nf, sats == null ? "null" : sats.length);
            return CodeCorrection.NONE;
        }

        resetWindows(ctx);

        /* 当前历元的 (resp, resc) 必须先入窗再聚合：§5.3 要求 histLen 最小为 0.2，
         * 即首历元也按 1 个样本计。resetWindows 已先按周跳/缺口清理，故不会混入旧样本。 */
        pushSamples(ctx, sats, ns, nf);

        int rows = ns * nf;
        float[] batch = new float[rows * ObsCorrectionFeatures.DIM];
        for (int i = 0; i < ns; i++) {
            SatFeature sf = sats[i];
            for (int frq = 0; frq < nf; frq++) {
                Window w = windows.get(key(sf.sat(), frq));
                ObsCorrectionFeatures.WindowAgg agg = w == null
                        ? ObsCorrectionFeatures.WindowAgg.EMPTY : w.aggregate();
                double[] raw = ObsCorrectionFeatures.extractRow(sf, frq, agg);
                float[] normalized = norm.normalize(raw, ObsCorrectionFeatures.DIM);
                System.arraycopy(normalized, 0, batch,
                        (i * nf + frq) * ObsCorrectionFeatures.DIM, ObsCorrectionFeatures.DIM);
            }
        }

        float[] out;
        try {
            out = inference.infer(batch, rows);
        } catch (Exception e) {
            throw new IllegalStateException("接口三 ONNX 推理失败", e);
        }
        if (out == null || out.length != rows * OUT_DIM) {
            throw new IllegalStateException("模型输出长度 "
                    + (out == null ? "null" : out.length) + " 与 rows*outDim="
                    + (rows * OUT_DIM) + " 不一致（契约违规）");
        }

        double[] bias = new double[rows];
        double[] scale = new double[rows];
        for (int idx = 0; idx < rows; idx++) {
            bias[idx] = out[idx * OUT_DIM];
            scale[idx] = out[idx * OUT_DIM + 1];
        }

        lastEpoch = ctx.epoch();

        if (log.isDebugEnabled()) {
            log.debug("接口三推理: epoch={} ns={} nf={} 窗口行数={}",
                    ctx.epoch(), ns, nf, windows.size());
        }
        return new CodeCorrection(bias, scale);
    }

    /** 已维护的窗口条目数（测试与诊断用）。 */
    public int windowSize() {
        return windows.size();
    }

    /** 清空全部窗口（测试与跨会话重置用）。 */
    public void reset() {
        windows.clear();
        lastEpoch = Integer.MIN_VALUE;
    }

    /**
     * 三条重置规则（§5.3）。缺口判定基于 {@code ctx.epoch()} 的整数间隔，
     * 因为 {@code epoch} 是 core 的历元计数，名义间隔即 {@code dt}。
     */
    private void resetWindows(ObsCorrectionContext ctx) {
        double dt = ctx.dt();
        boolean dtUsable = Double.isFinite(dt) && dt > 0.0;

        boolean gap;
        if (!dtUsable) {
            gap = true;
        } else if (lastEpoch == Integer.MIN_VALUE) {
            gap = false;
        } else {
            int step = ctx.epoch() - lastEpoch;
            /* step < 0 只可能出现在「同一实例被跨会话复用」时（新会话 epoch 从 1 重新计数）。
             * 正常会话内 epoch 单调递增，故倒退一律按新会话处理并清空窗口，
             * 否则上一会话的窗口样本会泄漏进本会话前几个历元，破坏可复现性。 */
            gap = step < 0 || step * dt > GAP_FACTOR * dt;
        }
        if (gap) {
            windows.clear();
        }

        Set<Integer> presentSats = new HashSet<>();
        for (SatFeature sf : ctx.sats()) {
            presentSats.add(sf.sat());
        }
        Iterator<Map.Entry<Integer, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Window> e = it.next();
            int sat = e.getKey() / KEY_STRIDE;
            if (!presentSats.contains(sat)) {
                it.remove();
            }
        }

        for (SatFeature sf : ctx.sats()) {
            for (int frq = 0; frq < ctx.nf(); frq++) {
                int slip = pick(sf.slip(), frq);
                if (slip != 0) {
                    windows.remove(key(sf.sat(), frq));
                }
            }
        }
    }

    private void pushSamples(ObsCorrectionContext ctx, SatFeature[] sats, int ns, int nf) {
        for (int i = 0; i < ns; i++) {
            SatFeature sf = sats[i];
            for (int frq = 0; frq < nf; frq++) {
                Window w = windows.computeIfAbsent(key(sf.sat(), frq),
                        k -> new Window(ObsCorrectionFeatures.WINDOW));
                w.push(pickD(sf.resp(), frq), pickD(sf.resc(), frq));
            }
        }
    }

    private static final int KEY_STRIDE = 16;

    private static int key(int sat, int frq) {
        return sat * KEY_STRIDE + frq;
    }

    private static int pick(int[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return 0;
        return arr[idx];
    }

    private static double pickD(double[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return 0.0;
        return arr[idx];
    }

    /** 长度固定的环形缓冲：只存 {@code (resp, resc)} 两个分量（§5.3 内存上界）。 */
    private static final class Window {

        private final double[] resp;
        private final double[] resc;
        private int count;
        private int head;

        Window(int capacity) {
            this.resp = new double[capacity];
            this.resc = new double[capacity];
        }

        void push(double r, double c) {
            resp[head] = r;
            resc[head] = c;
            head = (head + 1) % resp.length;
            if (count < resp.length) count++;
        }

        ObsCorrectionFeatures.WindowAgg aggregate() {
            if (count == 0) return ObsCorrectionFeatures.WindowAgg.EMPTY;
            double sumResp = 0.0;
            double sumResc = 0.0;
            for (int i = 0; i < count; i++) {
                sumResp += resp[i];
                sumResc += resc[i];
            }
            double meanResp = sumResp / count;
            double meanResc = sumResc / count;

            double varResp = 0.0;
            if (count >= 2) {
                for (int i = 0; i < count; i++) {
                    double d = resp[i] - meanResp;
                    varResp += d * d;
                }
                varResp /= count - 1;
            }
            double stdResp = count >= 2 ? Math.sqrt(varResp) : 0.0;
            if (!Double.isFinite(stdResp)) stdResp = 0.0;
            return new ObsCorrectionFeatures.WindowAgg(meanResp, stdResp, meanResc, count);
        }
    }
}