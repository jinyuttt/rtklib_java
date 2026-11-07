package org.rtklib.java.config;

import java.io.Serializable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 增强参数（阈值层）。
 * <p>
 * 无 {@code enable} 字段：注入 {@code AiProvider} 即启用，不注入即原生行为（I-1）。
 * 字段名去掉了 {@code ai} 前缀（已在命名空间内）。阈值改动不需要换模型。
 * </p>
 * <p>
 * 校验在首次读取时惰性执行（{@link #validate()}），非法值钳到安全范围并告警，
 * 不抛异常——配置错误不应让定位崩掉（§4.5）。
 * </p>
 */
public class AiOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(AiOpts.class);

    /** 接口一置信度阈值默认值。 */
    public static final double DEFAULT_FIX_VALIDATOR_MIN_CONFIDENCE = 0.5;
    /** 接口三方差膨胀上限默认值。 */
    public static final double DEFAULT_VARIANCE_SCALE_MAX = 4.0;
    /** 接口三单历元伪距偏差上限默认值（米）。 */
    public static final double DEFAULT_OBS_CORRECTOR_MAX_BIAS = 10.0;

    /** 接口一：置信度低于此值则固定解降为浮点（默认 0.5）。 */
    public double fixValidatorMinConfidence = DEFAULT_FIX_VALIDATOR_MIN_CONFIDENCE;

    /** 接口三：方差膨胀因子上限，超出则钳到该值（默认 4.0，必须 >= 1.0）。 */
    public double varianceScaleMax = DEFAULT_VARIANCE_SCALE_MAX;

    /** 接口三：单历元单行伪距偏差绝对值上限，超出则丢弃该历元改正（默认 10.0 米）。 */
    public double obsCorrectorMaxBias = DEFAULT_OBS_CORRECTOR_MAX_BIAS;

    /** 接口一拒收时写 trace（默认开）。 */
    public boolean traceRejects = true;

    /** 接口三降级/丢弃时写 trace（默认开）。 */
    public boolean traceDegrades = true;

    public AiOpts() {
    }

    public AiOpts(AiOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(AiOpts other) {
        this.fixValidatorMinConfidence = other.fixValidatorMinConfidence;
        this.varianceScaleMax = other.varianceScaleMax;
        this.obsCorrectorMaxBias = other.obsCorrectorMaxBias;
        this.traceRejects = other.traceRejects;
        this.traceDegrades = other.traceDegrades;
    }

    /**
     * 校验：非法值钳到安全范围并告警，幂等，不抛异常。
     * <p>
     * 钳位方向一律取「更保守」：阈值越高越保守，膨胀上限越低越保守。
     * 钳位会就地写回字段，因此重复调用不再告警，也不会二次改写合法值。
     * </p>
     */
    public void validate() {
        fixValidatorMinConfidence = clampUnit(
                fixValidatorMinConfidence, DEFAULT_FIX_VALIDATOR_MIN_CONFIDENCE,
                "fixValidatorMinConfidence");

        varianceScaleMax = clampAtLeastOne(
                varianceScaleMax, DEFAULT_VARIANCE_SCALE_MAX, "varianceScaleMax");

        obsCorrectorMaxBias = clampPositive(
                obsCorrectorMaxBias, DEFAULT_OBS_CORRECTOR_MAX_BIAS, "obsCorrectorMaxBias");
    }

    private static double clampUnit(double v, double dft, String name) {
        if (!Double.isFinite(v)) {
            log.warn("AiOpts.{} 非有限值({})，回退默认值 {}", name, v, dft);
            return dft;
        }
        if (v < 0.0) {
            log.warn("AiOpts.{} = {} 小于 0，钳到 0.0", name, v);
            return 0.0;
        }
        if (v > 1.0) {
            log.warn("AiOpts.{} = {} 大于 1，钳到 1.0", name, v);
            return 1.0;
        }
        return v;
    }

    private static double clampAtLeastOne(double v, double dft, String name) {
        if (!Double.isFinite(v)) {
            log.warn("AiOpts.{} 非有限值({})，回退默认值 {}", name, v, dft);
            return dft;
        }
        if (v < 1.0) {
            log.warn("AiOpts.{} = {} 小于 1.0（违反单侧膨胀），钳到 1.0", name, v);
            return 1.0;
        }
        return v;
    }

    private static double clampPositive(double v, double dft, String name) {
        if (!Double.isFinite(v)) {
            log.warn("AiOpts.{} 非有限值({})，回退默认值 {}", name, v, dft);
            return dft;
        }
        if (v <= 0.0) {
            log.warn("AiOpts.{} = {} 非正数，回退默认值 {}", name, v, dft);
            return dft;
        }
        return v;
    }
}