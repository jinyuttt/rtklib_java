package org.rtklib.java.ai;

import java.nio.file.Files;
import java.nio.file.Path;

import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.inference.OnnxInference;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AiProvider} 的 ONNX 实现（设计文档 §6.1）：只做组装，不设门面。
 * <p>
 * 组装方式：{@code OnnxInference}（模型加载 + 推理） + {@link FeatureNorm}（归一化参数）
 * → {@link FixValidatorImpl}。阶段 2 只实现接口一，{@link #obsCorrector()} 恒返回
 * {@code null}（未实现 = 不干预，core 走原生分支，§3.4）。
 * </p>
 * <p>
 * <b>加载失败即降级为「不干预」</b>：模型缺失、维度不符、ONNX Runtime native 库不可用等
 * 任何加载期错误，都只记一次告警并让 {@link #fixValidator()} 返回 {@code null}，
 * 绝不抛给调用方，更不 fail-closed（§3.4）。理由：AI 是增强线，加载失败不应让定位崩掉，
 * 也不应让「AI 开启」比「AI 关闭」更差。
 * </p>
 * <p>
 * 生命周期：本类持有 {@link OnnxInference}（内含 {@code OrtSession}），使用方应在会话结束时
 * 调用 {@link #close()}；{@link org.rtklib.java.ai.spi.AiHooks#clear()} 不会自动关闭它。
 * </p>
 */
public final class OnnxAiProvider implements AiProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxAiProvider.class);

    /** 接口一模型文件名（放在模型目录下）。 */
    public static final String FIX_VALIDATOR_MODEL = "fix_validator.onnx";
    /** 接口一归一化参数文件名（优先读取；缺失则退化恒等归一化）。 */
    public static final String FIX_VALIDATOR_NORM = "fix_validator.norm.json";
    /** 兼容名：模型目录下只有一份 {@code norm.json} 时使用（§5.1）。 */
    public static final String SHARED_NORM = "norm.json";

    private final OnnxInference inference;
    private final FixValidator fixValidator;

    /**
     * 按模型目录加载。
     *
     * @param modelDir 模型目录，内含 {@value #FIX_VALIDATOR_MODEL}
     */
    public OnnxAiProvider(Path modelDir) {
        this(modelDir == null ? null : modelDir.resolve(FIX_VALIDATOR_MODEL),
                resolveNorm(modelDir));
    }

    /**
     * 显式指定模型与归一化文件（测试与自定义部署使用）。
     *
     * @param modelPath 模型路径
     * @param normPath  归一化参数路径，{@code null} 或不存在 → 恒等归一化
     */
    public OnnxAiProvider(Path modelPath, Path normPath) {
        OnnxInference loaded = null;
        FixValidator validator = null;
        if (modelPath != null && Files.isRegularFile(modelPath)) {
            try {
                loaded = OnnxInference.load(modelPath);
                FeatureNorm norm = FeatureNorm.load(normPath);
                validator = new FixValidatorImpl(loaded, norm);
                log.info("接口一已启用: model={}, norm={}, 输入维={}",
                        modelPath, norm.isIdentity() ? "<identity>" : normPath, loaded.inputDim());
            } catch (Throwable t) {
                if (loaded != null) loaded.close();
                loaded = null;
                validator = null;
                log.warn("接口一模型加载失败({})，降级为不干预（core 走原生分支）: {}",
                        modelPath, t.toString());
            }
        } else {
            log.warn("接口一模型不存在({})，降级为不干预（core 走原生分支）", modelPath);
        }
        this.inference = loaded;
        this.fixValidator = validator;
    }

    private static Path resolveNorm(Path modelDir) {
        if (modelDir == null) return null;
        Path dedicated = modelDir.resolve(FIX_VALIDATOR_NORM);
        if (Files.isRegularFile(dedicated)) return dedicated;
        Path shared = modelDir.resolve(SHARED_NORM);
        return Files.isRegularFile(shared) ? shared : null;
    }

    @Override
    public FixValidator fixValidator() {
        return fixValidator;
    }

    /** 阶段 2 未实现接口三：返回 {@code null} 即「本 provider 不提供接口三」。 */
    @Override
    public ObsCorrector obsCorrector() {
        return null;
    }

    /** 是否成功加载接口一（{@code false} 表示本 provider 等价于未注入）。 */
    public boolean isFixValidatorAvailable() {
        return fixValidator != null;
    }

    @Override
    public void close() {
        if (inference != null) {
            inference.close();
        }
    }
}