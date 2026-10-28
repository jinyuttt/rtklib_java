package org.rtklib.java.config;

import java.io.Serializable;

/**
 * PPP-AR（整周模糊度固定）参数。
 * <p>
 * 从 {@link RtkConfig} 拆分出的独立子配置，字段名去掉原 {@code ppp} 前缀
 * （{@code enablePppAR} → {@code enableAR}，{@code pppArRatioWl} → {@code arRatioWl}）。
 * </p>
 */
public class PppArOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean enableAR = false;
    public double arRatioWl = 2.0;
    public double arRatioNl = 3.0;
    /**
     * PRIDE-PPPAR 'Widelane decision' 第1参数 wl_maxdev（周，默认0.20）：
     * WL取整偏差 |frac - 中位数| >= 该值时该卫星拒绝参与WL固定（bdeci prob=0条件）。
     */
    public double arWlMaxdev = 0.20;

    /**
     * PRIDE-PPPAR 'Narrowlane decision' 第1参数 nl_maxdev（周，默认0.15）：
     * NL取整偏差 |frac-中位数| >= 该值时该卫星拒绝参与NL固定（bdeci prob=0条件）。
     */
    public double arNlMaxdev = 0.15;

    /**
     * PRIDE-PPPAR 'Narrowlane decision' 第2参数 nl_maxsig（周，默认0.15）：
     * NL弧段加权均值标准误超过该值时该卫星拒绝参与NL固定（bdeci prob=0条件）。
     */
    public double arNlMaxsig = 0.15;

    /**
     * PRIDE-PPPAR 'Critical search' minsav（默认4）：NL弧段累积历元数达到该值
     * 才允许该卫星参与NL固定（弧段过短的状态序列统计不可靠）。
     */
    public int arNlMinsav = 4;
    public String fcbFile = "";
    public String updFile = "";
    public boolean enableFixHold = false;
    public double fixHoldVar = 1e-6;
    public int fixHoldMinEp = 50;
    public boolean enablePartialAR = false;
    public double partialArMinRatio = 2.0;
    public int partialArMinSats = 4;
    public int partialArMaxTries = 10;

    /**
     * [JAVA-ONLY] IFLC 逐星取整的整数域全星一致性检验（δ 门，默认关闭）。
     * 对每颗拟固定卫星计算 delta_i = (fixedAmb_i - x[IB]_float)/lambda_NL
     * （NL 周单位），以全星中位数为基准剔除 |delta_i - med| 超限者。
     * WL 错 ±1 时偏差约 ±3.5 NL 周、NL 错 ±1 时约 ±1 周，clean 星 <0.3 周——
     * frac 门对整周级 OSB 系统偏差原理性盲区（FIX-ALG-026 实证 PRN 23/25/26），
     * 该检验同时覆盖 WL/NL 两级整周错误。仅作用于 IFLC 取整分支。
     */
    public boolean enableDeltaGate = false;

    /**
     * [JAVA-ONLY] δ 门门限（NL 周，默认 0.5，半周界），双重语义：
     * (1) 基准自检：|medDelta| = |med((fixedAmb_i − x[IB]_float)/λ_NL)| 超过
     *     门限 → 整历元放弃固定（浮点未收敛到整数格或基准整体错位）。
     *     必须先行——否则病态历元以 var=0 冻结错误整数并永久污染时序
     *     （FIX-ALG-026 实测 46 通过历元中 18 个 |medDelta|≥1 周，最大 −2.90）。
     * (2) 逐星剔除：dev_i = δ_i − medDelta 超过门限的拟固定卫星被剔除。
     *     dev 对公共收敛偏移免疫（grid_offset 在减 median 时相消），仅含
     *     整数错误与 OSB 残差散布：NL 错 ±1 → dev≈±1、WL 错 ±1 → dev≈±3.5、
     *     clean 星 |dev|≈残差散布 <0.3 周——半周界同时拦截两级整周错误。
     * 实测标定：0.4 门限（无基准自检时）6521/6521 全拒（收敛瞬态污染
     * 统计）；2.0 门限放行 NL±1 错误星导致 var=0 冻结投毒（2D 3.19cm）。
     */
    public double deltaMax = 0.5;

    public boolean enableBds3AR = false;
    public boolean enableMultiFreqAR = false;
    public double arRatioEwl = 1.5;

    public PppArOpts() {
    }

    public PppArOpts(PppArOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(PppArOpts other) {
        this.enableAR = other.enableAR;
        this.arRatioWl = other.arRatioWl;
        this.arRatioNl = other.arRatioNl;
        this.arWlMaxdev = other.arWlMaxdev;
        this.arNlMaxdev = other.arNlMaxdev;
        this.arNlMaxsig = other.arNlMaxsig;
        this.arNlMinsav = other.arNlMinsav;
        this.fcbFile = other.fcbFile;
        this.updFile = other.updFile;
        this.enableFixHold = other.enableFixHold;
        this.fixHoldVar = other.fixHoldVar;
        this.fixHoldMinEp = other.fixHoldMinEp;
        this.enablePartialAR = other.enablePartialAR;
        this.partialArMinRatio = other.partialArMinRatio;
        this.partialArMinSats = other.partialArMinSats;
        this.partialArMaxTries = other.partialArMaxTries;
        this.enableDeltaGate = other.enableDeltaGate;
        this.deltaMax = other.deltaMax;
        this.enableBds3AR = other.enableBds3AR;
        this.enableMultiFreqAR = other.enableMultiFreqAR;
        this.arRatioEwl = other.arRatioEwl;
    }
}
