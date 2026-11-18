package org.rtklib.java.ai;

import java.nio.file.Files;
import java.nio.file.Path;

import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.feature.ObsCorrectionFeatures;
import org.rtklib.java.ai.feature.PppSolutionFeatures;
import org.rtklib.java.ai.feature.PppVarianceFeatures;
import org.rtklib.java.ai.feature.RefStarFeatures;
import org.rtklib.java.ai.inference.OnnxInference;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.PppSolutionValidator;
import org.rtklib.java.ai.spi.PppVarianceScaler;
import org.rtklib.java.ai.spi.RefStarValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AiProvider} 的 ONNX 实现（设计文档 §6.1）：只做组装，不设门面。
 *
 * 组装方式：{@code OnnxInference}（模型加载 + 推理） + {@link FeatureNorm}（归一化参数）
 * → {@link FixValidatorImpl} / {@link ObsCorrectorImpl} / {@link PppSolutionValidatorImpl} /
 * {@link PppVarianceScalerImpl}。各接口各自独立加载、各自独立降级：任一模型缺失
 * 都只让该接口返回 {@code null}（不干预），不影响其余接口。
 *
 * 加载失败即降级为「不干预」：模型缺失、维度不符、ONNX Runtime native 库不可用等
 * 任何加载期错误，都只记一次告警并让对应接口返回 {@code null}，绝不抛给调用方，
 * 更不 fail-closed（§3.4）。理由：AI 是增强线，加载失败不应让定位崩掉，
 * 也不应让「AI 开启」比「AI 关闭」更差。
 *
 * 生命周期：本类持有多个 {@link OnnxInference}（内含 {@code OrtSession}），使用方应在
 * 会话结束时调用 {@link #close()}；{@link org.rtklib.java.ai.spi.AiHooks#clear()} 不会自动关闭它。
 */
public final class OnnxAiProvider implements AiProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxAiProvider.class);

    /** 接口一模型文件名（放在模型目录下）。 */
    public static final String FIX_VALIDATOR_MODEL = "fix_validator.onnx";
    /** 接口一归一化参数文件名（优先读取；缺失则退化恒等归一化）。 */
    public static final String FIX_VALIDATOR_NORM = "fix_validator.norm.json";
    /** 接口三模型文件名（放在模型目录下）。 */
    public static final String OBS_CORRECTOR_MODEL = "obs_corrector.onnx";
    /** 接口三归一化参数文件名（优先读取；缺失则退化恒等归一化）。 */
    public static final String OBS_CORRECTOR_NORM = "obs_corrector.norm.json";
    /** 接口四模型文件名（放在模型目录下；缺失即接口四不启用）。 */
    public static final String REF_STAR_MODEL = "refstar_sliprisk.onnx";
    /** 接口四归一化参数文件名（优先读取；缺失则退化恒等归一化）。 */
    public static final String REF_STAR_NORM = "refstar_sliprisk.norm.json";
    /** 接口 A 模型文件名（放在模型目录下；缺失即接口 A 不启用）。 */
    public static final String PPP_SOLUTION_MODEL = "ppp_solution_validator.onnx";
    /** 接口 A 归一化参数文件名（优先读取；缺失则退化恒等归一化）。 */
    public static final String PPP_SOLUTION_NORM = "ppp_solution_validator.norm.json";
    /** 接口 B 模型文件名（放在模型目录下；缺失即接口 B 不启用）。 */
    public static final String PPP_VARIANCE_MODEL = "ppp_variance_scaler.onnx";
    /** 接口 B 归一化参数文件名（优先读取；缺失则退化恒等归一化）。 */
    public static final String PPP_VARIANCE_NORM = "ppp_variance_scaler.norm.json";
    /** 兼容名：模型目录下只有一份 {@code norm.json} 时使用（§5.1）。 */
    public static final String SHARED_NORM = "norm.json";

    private final OnnxInference inference;
    private final OnnxInference obsInference;
    private final OnnxInference refStarInference;
    private final FixValidator fixValidator;
    private final ObsCorrector obsCorrector;
    private final RefStarValidator refStarValidator;
    private final OnnxInference pppSolutionInference;
    private final PppSolutionValidator pppSolutionValidator;
    private final OnnxInference pppVarianceInference;
    private final PppVarianceScaler pppVarianceScaler;

    /**
     * 按模型目录加载（各接口各自独立降级）。
     *
     * @param modelDir 模型目录，内含 {@value #FIX_VALIDATOR_MODEL} /
     *                 {@value #OBS_CORRECTOR_MODEL} / {@value #REF_STAR_MODEL} /
     *                 {@value #PPP_SOLUTION_MODEL} / {@value #PPP_VARIANCE_MODEL}
     */
    public OnnxAiProvider(Path modelDir) {
        this(modelDir == null ? null : modelDir.resolve(FIX_VALIDATOR_MODEL),
                resolveNorm(modelDir),
                modelDir == null ? null : modelDir.resolve(OBS_CORRECTOR_MODEL),
                resolveObsNorm(modelDir),
                modelDir == null ? null : modelDir.resolve(REF_STAR_MODEL),
                resolveRefStarNorm(modelDir),
                modelDir == null ? null : modelDir.resolve(PPP_SOLUTION_MODEL),
                resolvePppSolutionNorm(modelDir),
                modelDir == null ? null : modelDir.resolve(PPP_VARIANCE_MODEL),
                resolvePppVarianceNorm(modelDir));
    }

    /**
     * 显式指定接口一模型与归一化文件（测试与自定义部署使用；接口三/四/A/B 不启用）。
     *
     * @param modelPath 模型路径
     * @param normPath  归一化参数路径，{@code null} 或不存在 → 恒等归一化
     */
    public OnnxAiProvider(Path modelPath, Path normPath) {
        this(modelPath, normPath, null, null, null, null);
    }

    /**
     * 显式指定两个接口的模型与归一化文件（任一为 {@code null} 即该接口不启用）。
     *
     * @param modelPath    接口一模型路径
     * @param normPath     接口一归一化参数路径
     * @param obsModelPath 接口三模型路径
     * @param obsNormPath  接口三归一化参数路径
     */
    public OnnxAiProvider(Path modelPath, Path normPath, Path obsModelPath, Path obsNormPath) {
        this(modelPath, normPath, obsModelPath, obsNormPath, null, null);
    }

    /**
     * 显式指定三个接口的模型与归一化文件（任一为 {@code null} 即该接口不启用）。
     *
     * @param modelPath      接口一模型路径
     * @param normPath       接口一归一化参数路径
     * @param obsModelPath   接口三模型路径
     * @param obsNormPath    接口三归一化参数路径
     * @param refStarPath    接口四模型路径
     * @param refStarNormPath 接口四归一化参数路径
     */
    public OnnxAiProvider(Path modelPath, Path normPath, Path obsModelPath, Path obsNormPath,
                          Path refStarPath, Path refStarNormPath) {
        this(modelPath, normPath, obsModelPath, obsNormPath, refStarPath, refStarNormPath,
                null, null);
    }

    /**
     * 显式指定接口一/三/四/A 的模型与归一化文件（接口 B 不启用）。
     *
     * @param modelPath           接口一模型路径
     * @param normPath            接口一归一化参数路径
     * @param obsModelPath        接口三模型路径
     * @param obsNormPath         接口三归一化参数路径
     * @param refStarPath         接口四模型路径
     * @param refStarNormPath     接口四归一化参数路径
     * @param pppSolutionPath     接口 A 模型路径
     * @param pppSolutionNormPath 接口 A 归一化参数路径
     */
    public OnnxAiProvider(Path modelPath, Path normPath, Path obsModelPath, Path obsNormPath,
                          Path refStarPath, Path refStarNormPath,
                          Path pppSolutionPath, Path pppSolutionNormPath) {
        this(modelPath, normPath, obsModelPath, obsNormPath, refStarPath, refStarNormPath,
                pppSolutionPath, pppSolutionNormPath, null, null);
    }

    /**
     * 显式指定全部接口的模型与归一化文件（任一为 {@code null} 即该接口不启用）。
     *
     * @param modelPath           接口一模型路径
     * @param normPath            接口一归一化参数路径
     * @param obsModelPath        接口三模型路径
     * @param obsNormPath         接口三归一化参数路径
     * @param refStarPath         接口四模型路径
     * @param refStarNormPath     接口四归一化参数路径
     * @param pppSolutionPath     接口 A 模型路径
     * @param pppSolutionNormPath 接口 A 归一化参数路径
     * @param pppVariancePath     接口 B 模型路径
     * @param pppVarianceNormPath 接口 B 归一化参数路径
     */
    public OnnxAiProvider(Path modelPath, Path normPath, Path obsModelPath, Path obsNormPath,
                          Path refStarPath, Path refStarNormPath,
                          Path pppSolutionPath, Path pppSolutionNormPath,
                          Path pppVariancePath, Path pppVarianceNormPath) {
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

        OnnxInference obsLoaded = null;
        ObsCorrector corrector = null;
        if (obsModelPath != null && Files.isRegularFile(obsModelPath)) {
            try {
                obsLoaded = OnnxInference.load(obsModelPath);
                FeatureNorm obsNorm = FeatureNorm.load(obsNormPath, ObsCorrectionFeatures.DIM);
                corrector = new ObsCorrectorImpl(obsLoaded, obsNorm);
                log.info("接口三已启用: model={}, norm={}, 输入维={}",
                        obsModelPath, obsNorm.isIdentity() ? "<identity>" : obsNormPath,
                        obsLoaded.inputDim());
            } catch (Throwable t) {
                if (obsLoaded != null) obsLoaded.close();
                obsLoaded = null;
                corrector = null;
                log.warn("接口三模型加载失败({})，降级为不干预（core 走原生分支）: {}",
                        obsModelPath, t.toString());
            }
        } else {
            log.warn("接口三模型不存在({})，降级为不干预（core 走原生分支）", obsModelPath);
        }

        OnnxInference refLoaded = null;
        RefStarValidator refValidator = null;
        if (refStarPath != null && Files.isRegularFile(refStarPath)) {
            try {
                refLoaded = OnnxInference.load(refStarPath);
                FeatureNorm refNorm = FeatureNorm.load(refStarNormPath, RefStarFeatures.DIM);
                refValidator = new RefStarValidatorImpl(refLoaded, refNorm);
                log.info("接口四已启用: model={}, norm={}, 输入维={}",
                        refStarPath, refNorm.isIdentity() ? "<identity>" : refStarNormPath,
                        refLoaded.inputDim());
            } catch (Throwable t) {
                if (refLoaded != null) refLoaded.close();
                refLoaded = null;
                refValidator = null;
                log.warn("接口四模型加载失败({})，降级为不干预（core 走原生分支）: {}",
                        refStarPath, t.toString());
            }
        } else {
            log.warn("接口四模型不存在({})，降级为不干预（core 走原生分支）", refStarPath);
        }

        this.inference = loaded;
        this.fixValidator = validator;
        this.obsInference = obsLoaded;
        this.obsCorrector = corrector;
        OnnxInference pppLoaded = null;
        PppSolutionValidator pppValidator = null;
        if (pppSolutionPath != null && Files.isRegularFile(pppSolutionPath)) {
            try {
                pppLoaded = OnnxInference.load(pppSolutionPath);
                FeatureNorm pppNorm = FeatureNorm.load(pppSolutionNormPath, PppSolutionFeatures.DIM);
                pppValidator = new PppSolutionValidatorImpl(pppLoaded, pppNorm);
                log.info("接口 A 已启用: model={}, norm={}, 输入维={}",
                        pppSolutionPath, pppNorm.isIdentity() ? "<identity>" : pppSolutionNormPath,
                        pppLoaded.inputDim());
            } catch (Throwable t) {
                if (pppLoaded != null) pppLoaded.close();
                pppLoaded = null;
                pppValidator = null;
                log.warn("接口 A 模型加载失败({})，降级为不干预（core 走原生分支）: {}",
                        pppSolutionPath, t.toString());
            }
        } else {
            log.warn("接口 A 模型不存在({})，降级为不干预（core 走原生分支）", pppSolutionPath);
        }

        OnnxInference pppVarLoaded = null;
        PppVarianceScaler pppVarScaler = null;
        if (pppVariancePath != null && Files.isRegularFile(pppVariancePath)) {
            try {
                pppVarLoaded = OnnxInference.load(pppVariancePath);
                FeatureNorm pppVarNorm =
                        FeatureNorm.load(pppVarianceNormPath, PppVarianceFeatures.DIM);
                pppVarScaler = new PppVarianceScalerImpl(pppVarLoaded, pppVarNorm);
                log.info("接口 B 已启用: model={}, norm={}, 输入维={}",
                        pppVariancePath,
                        pppVarNorm.isIdentity() ? "<identity>" : pppVarianceNormPath,
                        pppVarLoaded.inputDim());
            } catch (Throwable t) {
                if (pppVarLoaded != null) pppVarLoaded.close();
                pppVarLoaded = null;
                pppVarScaler = null;
                log.warn("接口 B 模型加载失败({})，降级为不干预（core 走原生分支）: {}",
                        pppVariancePath, t.toString());
            }
        } else {
            log.warn("接口 B 模型不存在({})，降级为不干预（core 走原生分支）", pppVariancePath);
        }

        this.refStarInference = refLoaded;
        this.refStarValidator = refValidator;
        this.pppSolutionInference = pppLoaded;
        this.pppSolutionValidator = pppValidator;
        this.pppVarianceInference = pppVarLoaded;
        this.pppVarianceScaler = pppVarScaler;
    }

    private static Path resolveNorm(Path modelDir) {
        if (modelDir == null) return null;
        Path dedicated = modelDir.resolve(FIX_VALIDATOR_NORM);
        if (Files.isRegularFile(dedicated)) return dedicated;
        Path shared = modelDir.resolve(SHARED_NORM);
        return Files.isRegularFile(shared) ? shared : null;
    }

    private static Path resolveObsNorm(Path modelDir) {
        if (modelDir == null) return null;
        Path dedicated = modelDir.resolve(OBS_CORRECTOR_NORM);
        if (Files.isRegularFile(dedicated)) return dedicated;
        Path shared = modelDir.resolve(SHARED_NORM);
        return Files.isRegularFile(shared) ? shared : null;
    }

    private static Path resolvePppSolutionNorm(Path modelDir) {
        if (modelDir == null) return null;
        Path dedicated = modelDir.resolve(PPP_SOLUTION_NORM);
        if (Files.isRegularFile(dedicated)) return dedicated;
        Path shared = modelDir.resolve(SHARED_NORM);
        return Files.isRegularFile(shared) ? shared : null;
    }

    private static Path resolvePppVarianceNorm(Path modelDir) {
        if (modelDir == null) return null;
        Path dedicated = modelDir.resolve(PPP_VARIANCE_NORM);
        if (Files.isRegularFile(dedicated)) return dedicated;
        Path shared = modelDir.resolve(SHARED_NORM);
        return Files.isRegularFile(shared) ? shared : null;
    }

    private static Path resolveRefStarNorm(Path modelDir) {
        if (modelDir == null) return null;
        Path dedicated = modelDir.resolve(REF_STAR_NORM);
        if (Files.isRegularFile(dedicated)) return dedicated;
        Path shared = modelDir.resolve(SHARED_NORM);
        return Files.isRegularFile(shared) ? shared : null;
    }

    @Override
    public FixValidator fixValidator() {
        return fixValidator;
    }

    /** 接口三实现；返回 {@code null} 即「本 provider 不提供接口三」，core 走原生分支。 */
    @Override
    public ObsCorrector obsCorrector() {
        return obsCorrector;
    }

    /**
     * 接口四实现；返回 {@code null} 即「本 provider 不提供接口四」，core 走原生分支。
     *
     * 接口四是实验接口：模型目录里没有 {@value #REF_STAR_MODEL} 时本方法返回 {@code null}，
     * 此时即使注入本 provider，core 的行为也与未注入逐位一致（I-1）。
     */
    @Override
    public RefStarValidator refStarValidator() {
        return refStarValidator;
    }

    /** 是否成功加载接口一（{@code false} 表示本 provider 等价于未注入）。 */
    public boolean isFixValidatorAvailable() {
        return fixValidator != null;
    }

    /** 是否成功加载接口三。 */
    public boolean isObsCorrectorAvailable() {
        return obsCorrector != null;
    }

    /**
     * 接口 A 实现；返回 {@code null} 即「本 provider 不提供接口 A」，core 走原生分支。
     *
     * 模型目录里没有 {@value #PPP_SOLUTION_MODEL} 时本方法返回 {@code null}，
     * 此时即使注入本 provider，core 的行为也与未注入逐位一致（I-1）。
     */
    @Override
    public PppSolutionValidator pppSolutionValidator() {
        return pppSolutionValidator;
    }

    /**
     * 接口 B 实现；返回 {@code null} 即「本 provider 不提供接口 B」，core 走原生分支。
     *
     * 模型目录里没有 {@value #PPP_VARIANCE_MODEL} 时本方法返回 {@code null}，
     * 此时即使注入本 provider，core 的行为也与未注入逐位一致（I-1）。
     */
    @Override
    public PppVarianceScaler pppVarianceScaler() {
        return pppVarianceScaler;
    }

    /** 是否成功加载接口 A（{@code false} 表示接口 A 等价于未注入）。 */
    public boolean isPppSolutionAvailable() {
        return pppSolutionValidator != null;
    }

    /** 是否成功加载接口 B（{@code false} 表示接口 B 等价于未注入）。 */
    public boolean isPppVarianceAvailable() {
        return pppVarianceScaler != null;
    }

    /**
     * 是否至少有一个接口成功加载。
     *
     * 用于宿主决定「是否注入本 provider」：只要任一接口可用就必须注入，
     * 否则单独部署的接口（例如只有 A 模型）会被其他接口缺失连坐关闭。
     */
    public boolean isAnyInterfaceAvailable() {
        return fixValidator != null || obsCorrector != null || refStarValidator != null
                || pppSolutionValidator != null || pppVarianceScaler != null;
    }

    /** 是否成功加载接口四。 */
    public boolean isRefStarAvailable() {
        return refStarValidator != null;
    }

    @Override
    public void close() {
        if (inference != null) {
            inference.close();
        }
        if (obsInference != null) {
            obsInference.close();
        }
        if (refStarInference != null) {
            refStarInference.close();
        }
        if (pppSolutionInference != null) {
            pppSolutionInference.close();
        }
        if (pppVarianceInference != null) {
            pppVarianceInference.close();
        }
    }
}
