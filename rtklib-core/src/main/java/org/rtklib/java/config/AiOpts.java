package org.rtklib.java.config;

import java.io.Serializable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 增强参数（阈值层）。
 * 无 {@code enable} 字段：注入 {@code AiProvider} 即启用，不注入即原生行为（I-1）。
 * 字段名去掉了 {@code ai} 前缀（已在命名空间内）。阈值改动不需要换模型。
 * 校验在首次读取时惰性执行（{@link #validate()}），非法值钳到安全范围并告警，
 * 不抛异常——配置错误不应让定位崩掉（§4.5）。
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
    /** 激进模式方差因子硬下限（防止法方程奇异，§4.6.2）。 */
    public static final double DEFAULT_AGGRESSIVE_MIN_SCALE = 0.1;

    /** 接口四风险分触发切换的默认阈值（§3.4）。 */
    public static final double DEFAULT_REF_STAR_RISK_MAX = 0.8;
    /** 接口四同组切换后的默认迟滞历元数（§3.4 H3）。 */
    public static final int DEFAULT_REF_STAR_HOLD_EPOCHS = 15;
    /** 接口四 fallback 候选池的默认上限（原生评分前 K 名，§3.4）。 */
    public static final int DEFAULT_REF_STAR_FALLBACK_TOPK = 3;
    /** 接口四 fallback 相对原参考星的默认最大高度角降幅（度，§3.4 H4）。 */
    public static final double DEFAULT_REF_STAR_MAX_EL_DROP = 15.0;
    /** 接口四单 run 内某组切换历元占比的默认上限（§3.4）。 */
    public static final double DEFAULT_REF_STAR_SWITCH_RATE_MAX = 0.02;
    /** 接口四风险标签与 SNR 窗口的默认窗口长度 W（§3.4）。 */
    public static final int DEFAULT_REF_STAR_WINDOW_EPOCHS = 5;

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

    /**
     * 激进模式开关（默认关，§4.6）。
     * 关闭时接口三的 varianceScale 单侧膨胀，硬钳下限 1.0（模型不得宣称比解析误差模型更可信）；
     * 开启时允许小于 1.0，但仍受 {@link #aggressiveMinScale} 硬下限保护。
     * 开启即违反 §4.3.4 强制规则 1，仅供实验，不得作为生产默认。
     */
    public boolean enableAggressiveAi = false;

    /** 激进模式下 varianceScale 的硬下限（默认 0.1，取值落在 (0.0, 1.0]）。 */
    public double aggressiveMinScale = DEFAULT_AGGRESSIVE_MIN_SCALE;

    /**
     * 接口四：风险分超过该值才触发参考星切换（默认 0.8，取值 {@code [0,1]}）。
     * 不用 0.5——误换的代价（破坏双差自洽）远高于漏判（§3.4）。
     */
    public double refStarRiskMax = DEFAULT_REF_STAR_RISK_MAX;

    /** 接口四：同一分组切换后 N 历元内禁止再切（默认 15，必须 >= 0）。 */
    public int refStarHoldEpochs = DEFAULT_REF_STAR_HOLD_EPOCHS;

    /**
     * 接口四：fallback 候选池上限（原生评分前 K 名，默认 3，必须 >= 1）。
     * 注意池大小不等于选中规则——实际只允许降一级到第 2 名（§3.3 / R2-b）。
     */
    public int refStarFallbackTopK = DEFAULT_REF_STAR_FALLBACK_TOPK;

    /** 接口四：fallback 相对原参考星的最大高度角降幅（度，默认 15，取值 {@code [0,90]}）。 */
    public double refStarMaxElDrop = DEFAULT_REF_STAR_MAX_EL_DROP;

    /** 接口四：单 run 内某组切换历元占比上限（默认 0.02，取值 {@code [0,1]}）。 */
    public double refStarSwitchRateMax = DEFAULT_REF_STAR_SWITCH_RATE_MAX;

    /** 接口四：风险标签与 SNR 窗口的窗口长度 W（默认 5，必须 >= 1）。 */
    public int refStarWindowEpochs = DEFAULT_REF_STAR_WINDOW_EPOCHS;

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
        this.enableAggressiveAi = other.enableAggressiveAi;
        this.aggressiveMinScale = other.aggressiveMinScale;
        this.refStarRiskMax = other.refStarRiskMax;
        this.refStarHoldEpochs = other.refStarHoldEpochs;
        this.refStarFallbackTopK = other.refStarFallbackTopK;
        this.refStarMaxElDrop = other.refStarMaxElDrop;
        this.refStarSwitchRateMax = other.refStarSwitchRateMax;
        this.refStarWindowEpochs = other.refStarWindowEpochs;
    }

    /**
     * 校验：非法值钳到安全范围并告警，幂等，不抛异常。
     * 钳位方向一律取「更保守」：阈值越高越保守，膨胀上限越低越保守。
     * 钳位会就地写回字段，因此重复调用不再告警，也不会二次改写合法值。
     */
    public void validate() {
        fixValidatorMinConfidence = clampUnit(
                fixValidatorMinConfidence, DEFAULT_FIX_VALIDATOR_MIN_CONFIDENCE,
                "fixValidatorMinConfidence");

        varianceScaleMax = clampAtLeastOne(
                varianceScaleMax, DEFAULT_VARIANCE_SCALE_MAX, "varianceScaleMax");

        obsCorrectorMaxBias = clampPositive(
                obsCorrectorMaxBias, DEFAULT_OBS_CORRECTOR_MAX_BIAS, "obsCorrectorMaxBias");

        aggressiveMinScale = clampMinScale(aggressiveMinScale);

        refStarRiskMax = clampUnit(
                refStarRiskMax, DEFAULT_REF_STAR_RISK_MAX, "refStarRiskMax");

        refStarSwitchRateMax = clampUnit(
                refStarSwitchRateMax, DEFAULT_REF_STAR_SWITCH_RATE_MAX, "refStarSwitchRateMax");

        refStarMaxElDrop = clampRange(refStarMaxElDrop, 0.0, 90.0,
                DEFAULT_REF_STAR_MAX_EL_DROP, "refStarMaxElDrop");

        refStarHoldEpochs = clampNonNegative(refStarHoldEpochs, "refStarHoldEpochs");
        refStarFallbackTopK = clampAtLeastOne(refStarFallbackTopK, "refStarFallbackTopK");
        refStarWindowEpochs = clampAtLeastOne(refStarWindowEpochs, "refStarWindowEpochs");
    }

    private static int clampNonNegative(int v, String name) {
        if (v < 0) {
            log.warn("AiOpts.{} = {} 小于 0，钳到 0", name, v);
            return 0;
        }
        return v;
    }

    private static int clampAtLeastOne(int v, String name) {
        if (v < 1) {
            log.warn("AiOpts.{} = {} 小于 1，钳到 1", name, v);
            return 1;
        }
        return v;
    }

    private static double clampRange(double v, double lo, double hi, double dft, String name) {
        if (!Double.isFinite(v)) {
            log.warn("AiOpts.{} 非有限值({})，回退默认值 {}", name, v, dft);
            return dft;
        }
        if (v < lo) {
            log.warn("AiOpts.{} = {} 小于 {}，钳到 {}", name, v, lo, lo);
            return lo;
        }
        if (v > hi) {
            log.warn("AiOpts.{} = {} 大于 {}，钳到 {}", name, v, hi, hi);
            return hi;
        }
        return v;
    }

    /**
     * 激进模式硬下限钳位：非法值一律回退默认 0.1，超过 1.0 钳到 1.0（等价退回保守模式）。
     */
    private static double clampMinScale(double v) {
        if (!Double.isFinite(v) || v <= 0.0) {
            log.warn("AiOpts.aggressiveMinScale = {} 非法，回退默认值 {}",
                    v, DEFAULT_AGGRESSIVE_MIN_SCALE);
            return DEFAULT_AGGRESSIVE_MIN_SCALE;
        }
        if (v > 1.0) {
            log.warn("AiOpts.aggressiveMinScale = {} 大于 1.0，钳到 1.0（等价保守模式）", v);
            return 1.0;
        }
        return v;
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