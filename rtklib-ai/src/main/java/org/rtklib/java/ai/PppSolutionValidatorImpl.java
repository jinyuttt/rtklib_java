package org.rtklib.java.ai;

import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.feature.PppSolutionFeatures;
import org.rtklib.java.ai.inference.OnnxInference;
import org.rtklib.java.ai.spi.PppSolutionContext;
import org.rtklib.java.ai.spi.PppSolutionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 接口 A 实现（设计文档 §3.3）：10 维特征 → ONNX 单行推理 → 连续置信度。
 *
 * 只做「特征 + 推理 + 值域钳位」，不做阈值判定：阈值永远在 core 侧由
 * {@code AiOpts.pppSolutionMinConfidence} 完成，换阈值不需要换模型。
 *
 * 不修改任何 {@code Rtk}/{@code Ssat} 状态；只读 {@code ctx}。推理异常不在本类吞掉：
 * 由 core 侧统一捕获并按不干预处理（§3.6 / S1）。
 */
public final class PppSolutionValidatorImpl implements PppSolutionValidator {

    private static final Logger log = LoggerFactory.getLogger(PppSolutionValidatorImpl.class);

    private final OnnxInference inference;
    private final FeatureNorm norm;

    /**
     * @param inference ONNX 推理层，非 {@code null}
     * @param norm      归一化参数，{@code null} 等价于恒等归一化
     */
    public PppSolutionValidatorImpl(OnnxInference inference, FeatureNorm norm) {
        if (inference == null) {
            throw new IllegalArgumentException("OnnxInference 不得为 null");
        }
        if (inference.inputDim() != PppSolutionFeatures.DIM) {
            throw new IllegalArgumentException("模型输入维 " + inference.inputDim()
                    + " 与接口 A 特征维 " + PppSolutionFeatures.DIM + " 不一致");
        }
        if (inference.outputDim() != 1) {
            throw new IllegalArgumentException("模型输出维 " + inference.outputDim()
                    + " 必须为 1（接口 A 只输出单个置信度）");
        }
        this.inference = inference;
        this.norm = norm == null ? FeatureNorm.identity(PppSolutionFeatures.DIM) : norm;
    }

    @Override
    public Double confidence(PppSolutionContext ctx) {
        double[] raw = PppSolutionFeatures.extractRaw(ctx);
        float[] x = norm.normalize(raw);

        float y;
        try {
            y = inference.inferSingle(x);
        } catch (ai.onnxruntime.OrtException e) {
            throw new IllegalStateException("接口 A ONNX 推理失败", e);
        }

        if (Float.isNaN(y)) {
            throw new IllegalStateException("模型输出为 NaN（输入特征: "
                    + java.util.Arrays.toString(x) + "），按降级处理");
        }
        double c = y;
        if (c < 0.0) c = 0.0;
        if (c > 1.0) c = 1.0;
        if (log.isDebugEnabled()) {
            log.debug("接口 A 推理: epoch={} 特征={} 置信度={}",
                    ctx.epoch(), java.util.Arrays.toString(x), c);
        }
        return c;
    }
}