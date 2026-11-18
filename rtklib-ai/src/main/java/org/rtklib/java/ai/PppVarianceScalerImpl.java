package org.rtklib.java.ai;

import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.feature.PppVarianceFeatures;
import org.rtklib.java.ai.inference.BatchInference;
import org.rtklib.java.ai.spi.PppObservationContext;
import org.rtklib.java.ai.spi.PppSatFeature;
import org.rtklib.java.ai.spi.PppVarianceScaler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 接口 B 实现（设计文档 §4）：{@code ns × nf} 行特征 → 一次批量推理 → 逐行方差膨胀因子。
 *
 * 只做「特征 + 推理」，不做钳位：{@code scale} 的 {@code [1.0, pppVarianceScaleMax]}
 * 区间由 core 侧 {@code PppAiGuard.clampScale} 统一施加，换上限不需要换模型（§4.6）。
 * 同理只输出因子、不输出偏差——PPP 的码偏差会被 {@code IB(sat, frq)} 状态吸收，
 * 写偏差进状态会污染 AR 输入（§4.4）。
 *
 * 无跨历元状态：接口 B 的 12 维特征全部来自当前历元快照（{@code resp/resc} 已是验后残差，
 * 不像接口三那样需要短窗聚合），故本类可跨历元复用、也可跨线程复用（后端
 * {@link BatchInference} 自身线程安全）。
 *
 * 推理异常不在本类吞掉：由 core 侧 {@code PppAiInject} 统一捕获并按不干预处理（S1）。
 * 上下文非法（{@code ns <= 0}、{@code sats} 长度不符）返回 {@code null}——core 把
 * {@code null} 与「长度不符」视为同一条降级路径。
 */
public final class PppVarianceScalerImpl implements PppVarianceScaler {

    private static final Logger log = LoggerFactory.getLogger(PppVarianceScalerImpl.class);

    private final BatchInference inference;
    private final FeatureNorm norm;

    /**
     * @param inference 批量推理后端，非 {@code null}
     * @param norm      归一化参数，{@code null} 等价于恒等归一化
     * @throws IllegalArgumentException 输入/输出维与接口 B 契约不符（契约违规须 fail-fast）
     */
    public PppVarianceScalerImpl(BatchInference inference, FeatureNorm norm) {
        if (inference == null) {
            throw new IllegalArgumentException("BatchInference 不得为 null");
        }
        if (inference.inputDim() != PppVarianceFeatures.DIM) {
            throw new IllegalArgumentException("模型输入维 " + inference.inputDim()
                    + " 与接口 B 特征维 " + PppVarianceFeatures.DIM + " 不一致");
        }
        if (inference.outputDim() != 1) {
            throw new IllegalArgumentException("模型输出维 " + inference.outputDim()
                    + " 必须为 1（接口 B 每行只输出一个方差膨胀因子，无 biasMeters）");
        }
        if (norm != null && !norm.isIdentity() && norm.dim() != PppVarianceFeatures.DIM) {
            throw new IllegalArgumentException("归一化参数维 " + norm.dim()
                    + " 与接口 B 特征维 " + PppVarianceFeatures.DIM + " 不一致");
        }
        this.inference = inference;
        this.norm = norm == null ? FeatureNorm.identity(PppVarianceFeatures.DIM) : norm;
    }

    @Override
    public double[] varianceScale(PppObservationContext ctx) {
        if (ctx == null) {
            return null;
        }
        int ns = ctx.ns();
        int nf = ctx.nf();
        PppSatFeature[] sats = ctx.sats();
        if (ns <= 0 || nf <= 0 || sats == null || sats.length != ns) {
            log.warn("接口 B 上下文非法（ns={}, nf={}, sats={}），按不干预处理",
                    ns, nf, sats == null ? "null" : sats.length);
            return null;
        }

        int rows = ns * nf;
        float[] batch = new float[rows * PppVarianceFeatures.DIM];
        for (int i = 0; i < ns; i++) {
            PppSatFeature sf = sats[i];
            for (int frq = 0; frq < nf; frq++) {
                double[] raw = PppVarianceFeatures.extractRaw(sf, frq);
                float[] normalized = norm.normalize(raw, PppVarianceFeatures.DIM);
                System.arraycopy(normalized, 0, batch,
                        (i * nf + frq) * PppVarianceFeatures.DIM, PppVarianceFeatures.DIM);
            }
        }

        float[] out;
        try {
            out = inference.infer(batch, rows);
        } catch (Exception e) {
            throw new IllegalStateException("接口 B ONNX 推理失败", e);
        }
        if (out == null || out.length != rows) {
            throw new IllegalStateException("模型输出长度 "
                    + (out == null ? "null" : out.length) + " 与 rows=" + rows
                    + " 不一致（契约违规）");
        }

        double[] scale = new double[rows];
        for (int i = 0; i < rows; i++) {
            scale[i] = out[i];
        }
        if (log.isDebugEnabled()) {
            log.debug("接口 B 推理: epoch={} ns={} nf={} 行数={}",
                    ctx.epoch(), ns, nf, rows);
        }
        return scale;
    }
}