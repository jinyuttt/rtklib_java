package org.rtklib.java.ai;

import java.util.Arrays;

import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.feature.RefStarFeatures;
import org.rtklib.java.ai.inference.BatchInference;
import org.rtklib.java.ai.spi.RefStarContext;
import org.rtklib.java.ai.spi.RefStarValidator;
import org.rtklib.java.ai.spi.SatFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 接口四实现（设计文档 §3.1 / §5.3）：{@code ns × nf} 行特征 → 一次批量推理 → 逐星风险分。
 *
 * 三条硬边界：
 * <ul>
 *   <li><b>只否决、不优选</b>（§1.1）：本类只输出风险分，参考星由 core 的原生评分选择，
 *       本类<b>不</b>返回「建议的参考星」；</li>
 *   <li><b>不抛异常</b>：任何契约违规（上下文非法、输出长度不符、含非有限值、后端异常）
 *       一律返回 {@code null}，由 core 侧统一降级为「全 0.0 = 不干预」并写 {@code AI4 / AI_DEGRADE}；</li>
 *   <li><b>无跨历元状态</b>：窗口统计（{@code snrWinMean} / {@code snrWinStd} / {@code refCount}）
 *       全部由 core 的 {@code AiRefStarState} 维护后下传，本类若自带一份窗口必然与推理侧错位。</li>
 * </ul>
 *
 * 行序契约（H2）：输入行序恒为 {@code ctx.sats()} 顺序 × {@code frq}，输出行序相同；
 * 逐星聚合取该星所有频点风险分的<b>最大值</b>（任一频点风险高即该星不适合作参考星，
 * 均值会被「大部分频点干净」稀释）。行序反查回 {@code sat[]} 下标由 core 负责，本类不得推算。
 */
public final class RefStarValidatorImpl implements RefStarValidator {

    private static final Logger LOG = LoggerFactory.getLogger(RefStarValidatorImpl.class);

    /** 模型输出维：单标量风险分（sigmoid 后落在 [0,1]，§5.3）。 */
    public static final int OUT_DIM = 1;

    private final BatchInference inference;
    private final FeatureNorm norm;

    private boolean degradeLogged;

    /**
     * @param inference 批量推理后端，非 {@code null}
     * @param norm      归一化参数，{@code null} 等价于该维度的恒等归一化
     * @throws IllegalArgumentException 输入/输出维与接口四契约不符（装配期错误须 fail-fast）
     */
    public RefStarValidatorImpl(BatchInference inference, FeatureNorm norm) {
        if (inference == null) {
            throw new IllegalArgumentException("BatchInference 不得为 null");
        }
        if (inference.inputDim() != RefStarFeatures.DIM) {
            throw new IllegalArgumentException("模型输入维 " + inference.inputDim()
                    + " 与接口四特征维 " + RefStarFeatures.DIM + " 不一致");
        }
        if (inference.outputDim() != OUT_DIM) {
            throw new IllegalArgumentException("模型输出维 " + inference.outputDim()
                    + " 必须为 " + OUT_DIM + "（接口四只输出单标量风险分）");
        }
        if (norm != null && !norm.isIdentity() && norm.dim() != RefStarFeatures.DIM) {
            throw new IllegalArgumentException("归一化参数维 " + norm.dim()
                    + " 与接口四特征维 " + RefStarFeatures.DIM + " 不一致");
        }
        this.inference = inference;
        this.norm = norm == null ? FeatureNorm.identity(RefStarFeatures.DIM) : norm;
    }

    @Override
    public double[] refStarRisk(RefStarContext ctx) {
        if (ctx == null) {
            return degrade("context", "null");
        }
        SatFeature[] sats = ctx.sats();
        int ns = sats == null ? 0 : sats.length;
        int nf = ctx.nf();
        if (ns <= 0 || nf <= 0) {
            return degrade("context", "ns=" + ns + " nf=" + nf);
        }
        if (ctx.snrWinMean() == null || ctx.snrWinStd() == null || ctx.refCount() == null
                || ctx.snrWinMean().length != ns || ctx.snrWinStd().length != ns
                || ctx.refCount().length != ns) {
            return degrade("context", "窗口数组长度与 sats 不一致");
        }

        int rows = ns * nf;
        float[] batch = new float[rows * RefStarFeatures.DIM];
        for (int i = 0; i < ns; i++) {
            SatFeature sf = sats[i];
            for (int frq = 0; frq < nf; frq++) {
                double[] raw = RefStarFeatures.extractRow(
                        sf, frq, ctx.snrWinMean()[i], ctx.snrWinStd()[i], ctx.refCount()[i]);
                float[] normalized = norm.normalize(raw, RefStarFeatures.DIM);
                System.arraycopy(normalized, 0, batch,
                        (i * nf + frq) * RefStarFeatures.DIM, RefStarFeatures.DIM);
            }
        }

        float[] out;
        try {
            out = inference.infer(batch, rows);
        } catch (Exception e) {
            return degrade("exception", e.toString());
        }
        if (out == null || out.length != rows * OUT_DIM) {
            return degrade("length_mismatch", (out == null ? "null" : "len=" + out.length)
                    + "/" + (rows * OUT_DIM));
        }

        double[] risk = new double[ns];
        Arrays.fill(risk, 0.0);
        for (int i = 0; i < ns; i++) {
            double max = 0.0;
            for (int frq = 0; frq < nf; frq++) {
                double v = out[(i * nf + frq) * OUT_DIM];
                if (!Double.isFinite(v)) {
                    return degrade("non_finite", "row=" + (i * nf + frq));
                }
                if (v > max) max = v;
            }
            risk[i] = Math.min(1.0, Math.max(0.0, max));
        }
        return risk;
    }

    /** 降级：返回 {@code null} 即「不干预」，与 {@code RefStarValidator} 契约一致。 */
    private double[] degrade(String reason, String detail) {
        if (!degradeLogged) {
            degradeLogged = true;
            LOG.warn("接口四推理降级（{}: {}），本历元按不干预处理（后续同类错误不再重复告警）",
                    reason, detail);
        }
        return null;
    }
}