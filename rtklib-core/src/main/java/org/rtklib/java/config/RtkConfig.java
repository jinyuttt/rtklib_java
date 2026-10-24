package org.rtklib.java.config;

import java.io.Serializable;
import java.util.Set;

import org.rtklib.java.adaptive.AdaptiveListener;
import org.rtklib.java.cssr.CompactSsrDecoder;

public class RtkConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean enableParRefReselect = false;
    public boolean enableAdaptiveQ = false;
    public boolean enableIggiii = false;
    public boolean enableSnrMedian = false;
    public boolean enableIonoTropGradient = false;
    public boolean enableAmbAnchor = false;

    public double parElMask = 15.0;
    public int parMaxConsecutiveReselect = 3;

    public double adaptiveQNsRef = 8.0;
    public double adaptiveQPdopRef = 3.0;
    public double adaptiveQScaleMinZeroVel = 0.1;
    public double adaptiveQScaleMax = 2.0;
    public double adaptiveQScaleMinMoving = 0.5;
    public double zeroVelSpeedThresh = 0.5;
    public double zeroVelPosDiffThresh = 0.05;
    public int zeroVelConsecutiveEpochs = 3;
    public double zeroVelStdThresh = 0.2;
    public double adaptiveQTraceThresh = 1e6;
    public int adaptiveQWinSize = 50;
    public double adaptiveQStaticThresh = 0.001;
    public double adaptiveQDynamicThresh = 0.05;
    public double adaptiveQScaleMinStatic = 0.01;
    public double adaptiveQScaleMaxDynamic = 5.0;

    public double iggiiiK0 = 3.0;
    public double iggiiiK1 = 6.0;
    public double iggiiiMinW = 0.5;
    public double iggiiiLowElMask = 10.0 * Math.PI / 180.0;
    public double iggiiiLowElNormThresh = 2.5;
    public double iggiiiLowElW = 0.5;
    public double iggiiiMultiFreqW = 0.5;
    public double iggiiiLowElExtraIterMask = 15.0 * Math.PI / 180.0;

    public double snrMedianMinEl = 10.0 * Math.PI / 180.0;
    public double snrMedianMinLockTime = 10.0;
    public int snrMedianWindowSize = 20;
    public double snrMedianKCode = 2.0;
    public double snrMedianKPhase = 0.5;
    public double snrMedianMinSnr = 25.0;
    public double snrMedianInvalidVar = 1e6;
    public int snrMedianMinSatsForFallback = 3;
    public double snrMedianFallbackCodeRef = 35.0;
    public double snrMedianFallbackPhaseRef = 40.0;
    public double snrMedianAbsMin = 20.0;

    public double gradientIonoInitVar = 1e-4;
    public double gradientIonoPrn = 1e-3;

    public int ambAnchorMinFixCount = 100;
    public double ambAnchorVar = 1e-9;

    public int atmFrozenNsThresh = 7;

    public boolean enableCascadeAR = false;
    public double cascadeArRatioEwl = 1.5;
    public double cascadeArRatioWl = 2.0;
    public double cascadeArRatioNl = 3.0;
    public boolean cascadeArBootstrapping = false;

    public boolean enableResidualEdit = false;
    public double residEditJumpThresh = 4.0;
    public int residEditMinArcLen = 10;
    public double residEditPcConsistThresh = 3.0;

    public boolean enablePartialAR = false;
    public double partialArMinRatio = 2.0;
    public int partialArMinSats = 4;
    public double partialArMinBootstrapping = 0.99;
    public int partialArMaxSubsetTries = 10;

    public boolean enableBootstrapping = false;
    public double bootstrappingMinSuccess = 0.99;
    public boolean bootstrappingWithRatio = true;

    public boolean enableSegmentSigmaFilter = false;
    public double segmentSigmaThreshold = 5.0;
    public int segmentSigmaMinLen = 5;

    public boolean enableBdsCodeBias = false;
    public double bdsCodeBiasElThresh = 30.0;
    public boolean bdsCodeBiasForceOn = false;

    public boolean enableParamTypeNoise = false;
    public double noiseZtdRw = 1e-4;
    public double noiseClkWhite = 1e2;
    public double noiseIonoRw = 1e-4;

    /**
     * [JAVA-ONLY v2.3.0] IFLC频率对逐卫星协商。
     * IONOOPT_IFLC模式下，RTKLIB C固定用槽位0+seliflc()槽位做无电离层组合；
     * 当流动站与基准站接收机频段不一致（如流动站BDS-3只有B1I+B2a、基准站有
     * B1I+B2b+B2a）时，固定槽位对会导致整星被剔除。开启后每历元按两站公共
     * 频段协商槽位对（sticky策略，配对切换按周跳处理）。
     * 默认false，与RTKLIB C行为逐位一致。
     */
    public boolean enableIflcPairNegotiation = false;

    public boolean enableGpt3Vmf3 = false;
    public String gpt3GridFile = "";
    public String vmf3OpFile = "";
    public boolean useGpt3Grid = false;

    public boolean enableIers2010 = false;
    public boolean enableAt1S2 = false;

    public String ionexFile = "";

    public boolean enableIsbIfcbIfb = false;
    /** JAVA-ONLY：PPP 精密星历模式下用 SPP 的系统间偏差(ISB)初始化各系统接收机钟差。
     * 默认 false=逐值对齐 RTKLIB 2.5.0 ppp.c udclk_ppp（精密模式 neglect receiver
     * inter-system bias，全部系统用 dtr[0] 初始化，GLO/GAL 初值偏差数百米被
     * maxinno 剔除，实际上仅 GPS 参与解算）。开启后精密模式也用 dtr[0]+dtr[i]
     * （即广播模式语义），多系统 PPP 可用性显著改善。 */
    public boolean enablePppIsbClockInit = false;
    public boolean estimateIsb = true;
    public boolean estimateIfcb = true;
    public boolean estimateIfb = true;
    public double isbPrn = 0.01;
    public double ifcbPrn = 0.01;
    public double ifbPrn = 0.001;

    public boolean enablePppAR = false;
    public double pppArRatioWl = 2.0;
    public double pppArRatioNl = 3.0;
    /**
     * PRIDE-PPPAR 'Widelane decision' 第1参数 wl_maxdev（周，默认0.20）：
     * WL取整偏差 |frac - 中位数| >= 该值时该卫星拒绝参与WL固定（bdeci prob=0条件）。
     */
    public double pppArWlMaxdev = 0.20;

    /**
     * PRIDE-PPPAR 'Narrowlane decision' 第1参数 nl_maxdev（周，默认0.15）：
     * NL取整偏差 |frac-中位数| >= 该值时该卫星拒绝参与NL固定（bdeci prob=0条件）。
     */
    public double pppArNlMaxdev = 0.15;

    /**
     * PRIDE-PPPAR 'Narrowlane decision' 第2参数 nl_maxsig（周，默认0.15）：
     * NL弧段加权均值标准误超过该值时该卫星拒绝参与NL固定（bdeci prob=0条件）。
     */
    public double pppArNlMaxsig = 0.15;

    /**
     * PRIDE-PPPAR 'Critical search' minsav（默认4）：NL弧段累积历元数达到该值
     * 才允许该卫星参与NL固定（弧段过短的状态序列统计不可靠）。
     */
    public int pppArNlMinsav = 4;
    public String fcbFile = "";
    public String updFile = "";
    public boolean enablePppArFixHold = false;
    public double pppArFixHoldVar = 1e-6;
    public int pppArFixHoldMinEp = 50;
    public boolean enablePppPartialAR = false;
    public double pppPartialArMinRatio = 2.0;
    public int pppPartialArMinSats = 4;
    public int pppPartialArMaxTries = 10;

    /**
     * [JAVA-ONLY] IFLC 逐星取整的整数域全星一致性检验（δ 门，默认关闭）。
     * 对每颗拟固定卫星计算 delta_i = (fixedAmb_i - x[IB]_float)/lambda_NL
     * （NL 周单位），以全星中位数为基准剔除 |delta_i - med| 超限者。
     * WL 错 ±1 时偏差约 ±3.5 NL 周、NL 错 ±1 时约 ±1 周，clean 星 <0.3 周——
     * frac 门对整周级 OSB 系统偏差原理性盲区（FIX-ALG-026 实证 PRN 23/25/26），
     * 该检验同时覆盖 WL/NL 两级整周错误。仅作用于 IFLC 取整分支。
     */
    public boolean enablePppArDeltaGate = false;

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
    public double pppArDeltaMax = 0.5;

    public boolean enableBds3PppAR = false;
    public boolean enableMultiFreqAR = false;
    public double pppArRatioEwl = 1.5;
    public boolean enableOsb = false;
    public String osbFile = "";
    public String dcbFile = "";

    /**
     * [JAVA-ONLY] IONOOPT_EST 健壮初始化开关。
     * 非组合 PPP（ionoopt=EST）首历元（或周跳后重置）时，相位 prefit 残差含未建模的
     * 码偏差/电离层初值，超出 maxinno 门限被全部剔除，仅码行进入 EKF，相位偏差状态
     * 永不更新，形成永久死锁（C 2.5.0 固有缺陷，C 依赖 postpos 自动加载 DCB 产品规避）。
     * 开启后：对本历元刚 initx 的 bias/ion 状态（协方差 P 恰为 VAR_BIAS/VAR_IONO，
     * 即尚未被任何 KF 更新吸收）跳过相位行的 prefit 剔除，让 EKF 一次性吸收常数偏差。
     * 仅放宽相位行（code==0），码行剔除逻辑保持与 C 一致；状态被估计过一历元后
     * 自动恢复正常剔除。
     * 默认 false=与 RTKLIB C 行为一致。正确做法是加载 DCB 产品（DcbReader）。
     */
    public boolean enableIonoEstRobust = false;

    public boolean enableSppEkf = false;
    public boolean enableSppRobust = false;
    public boolean enableSppZeroVel = false;
    public boolean enableSppDopplerSnr = false;
    public boolean enableSppInnovChisq = true;
    public int sppEkfMinSat = 0;
    public int sppEkfLowSatThresh = 7;

    public double sppEkfVarPos = 50.0 * 50.0;
    public double sppEkfVarVel = 10.0 * 10.0;
    public double sppEkfVarAcc = 10.0 * 10.0;
    public double sppEkfClkProcessNoise = 10000.0;
    public double sppEkfAccPrn1 = 10.0;
    public double sppEkfAccPrn2 = 10.0;
    public double sppEkfVelResidScale = 1.0;
    public double sppEkfVarResetThresh = 50.0 * 50.0;
    public double sppEkfDtGap = 10.0;
    public double sppEkfVarCap = 50.0 * 50.0 / 4.0;
    public int sppEkfDivergedRecoverEpochs = 5;
    public double sppEkfDivergedRscaleBase = 100.0;
    public double sppEkfDivergedRscaleMax = 1000.0;
    public double sppEkfDivergedMaxEpochs = 20;
    public double sppEkfDivergedQscale = 1.0;
    public double sppInnovOutlierThresh = 5.0;
    public double sppInnovDownweightThresh = 3.0;
    public double sppInnovOutlierThreshLowSat = 7.0;
    public double sppInnovDownweightThreshLowSat = 5.0;
    public double sppInnovDownweightRscale = 10.0;

    public double sppRobustKPos = 1.345;
    public double sppRobustKVel = 5.0;
    public int sppRobustMaxIter = 15;
    public double sppRobustPosOutlier = 100.0;
    public double sppRobustVelOutlier = 20.0;
    public double sppRobustMinW = 0.0001;

    public double sppZeroVelSpeedThresh = 0.5;
    public int sppZeroVelMinEpochs = 1;

    public boolean enablePppRtk = false;
    public boolean enablePppRtkAR = false;
    public double pppRtkArRatio = 3.0;
    public boolean enablePppRtkFixHold = false;
    public int pppRtkFixHoldMinEpoch = 10;
    public double pppRtkFixHoldVar = 1e-4;
    public double ssrMaxAge = 60.0;
    public int ssrIonoMode = 1;
    public boolean enableCompactSsr = false;
    public String compactSsrGridFile = "";
    public boolean enableSsrTropDirect = false;
    public transient CompactSsrDecoder cssrDecoder = null;

    public boolean enableAdaptiveScene = false;
    public int adaptiveWindowEpochs = 0;
    public int adaptiveCooldownEpochs = 0;
    public Set<String> adaptiveManagedParams = null;
    public Set<String> adaptiveLockedParams = null;
    public boolean adaptiveAuditEnabled = true;
    public String adaptiveAuditDir = "";
    public int adaptiveAuditMaxSizeKB = 5120;
    public int adaptiveAuditMaxFiles = 3;
    public boolean adaptiveVerbose = true;
    public boolean adaptiveTraceAll = false;
    public transient AdaptiveListener adaptiveListener = null;

    public RtkConfig() {
    }

    public RtkConfig(RtkConfig other) {
        this.enableParRefReselect = other.enableParRefReselect;
        this.enableAdaptiveQ = other.enableAdaptiveQ;
        this.enableIggiii = other.enableIggiii;
        this.enableSnrMedian = other.enableSnrMedian;
        this.enableIonoTropGradient = other.enableIonoTropGradient;
        this.enableAmbAnchor = other.enableAmbAnchor;
        this.parElMask = other.parElMask;
        this.parMaxConsecutiveReselect = other.parMaxConsecutiveReselect;
        this.adaptiveQNsRef = other.adaptiveQNsRef;
        this.adaptiveQPdopRef = other.adaptiveQPdopRef;
        this.adaptiveQScaleMinZeroVel = other.adaptiveQScaleMinZeroVel;
        this.adaptiveQScaleMax = other.adaptiveQScaleMax;
        this.adaptiveQScaleMinMoving = other.adaptiveQScaleMinMoving;
        this.zeroVelSpeedThresh = other.zeroVelSpeedThresh;
        this.zeroVelPosDiffThresh = other.zeroVelPosDiffThresh;
        this.zeroVelConsecutiveEpochs = other.zeroVelConsecutiveEpochs;
        this.zeroVelStdThresh = other.zeroVelStdThresh;
        this.adaptiveQTraceThresh = other.adaptiveQTraceThresh;
        this.adaptiveQWinSize = other.adaptiveQWinSize;
        this.adaptiveQStaticThresh = other.adaptiveQStaticThresh;
        this.adaptiveQDynamicThresh = other.adaptiveQDynamicThresh;
        this.adaptiveQScaleMinStatic = other.adaptiveQScaleMinStatic;
        this.adaptiveQScaleMaxDynamic = other.adaptiveQScaleMaxDynamic;
        this.iggiiiK0 = other.iggiiiK0;
        this.iggiiiK1 = other.iggiiiK1;
        this.iggiiiMinW = other.iggiiiMinW;
        this.iggiiiLowElMask = other.iggiiiLowElMask;
        this.iggiiiLowElNormThresh = other.iggiiiLowElNormThresh;
        this.iggiiiLowElW = other.iggiiiLowElW;
        this.iggiiiMultiFreqW = other.iggiiiMultiFreqW;
        this.iggiiiLowElExtraIterMask = other.iggiiiLowElExtraIterMask;
        this.snrMedianMinEl = other.snrMedianMinEl;
        this.snrMedianMinLockTime = other.snrMedianMinLockTime;
        this.snrMedianWindowSize = other.snrMedianWindowSize;
        this.snrMedianKCode = other.snrMedianKCode;
        this.snrMedianKPhase = other.snrMedianKPhase;
        this.snrMedianMinSnr = other.snrMedianMinSnr;
        this.snrMedianInvalidVar = other.snrMedianInvalidVar;
        this.snrMedianMinSatsForFallback = other.snrMedianMinSatsForFallback;
        this.snrMedianFallbackCodeRef = other.snrMedianFallbackCodeRef;
        this.snrMedianFallbackPhaseRef = other.snrMedianFallbackPhaseRef;
        this.snrMedianAbsMin = other.snrMedianAbsMin;
        this.gradientIonoInitVar = other.gradientIonoInitVar;
        this.gradientIonoPrn = other.gradientIonoPrn;
        this.ambAnchorMinFixCount = other.ambAnchorMinFixCount;
        this.ambAnchorVar = other.ambAnchorVar;
        this.atmFrozenNsThresh = other.atmFrozenNsThresh;

        this.enableCascadeAR = other.enableCascadeAR;
        this.cascadeArRatioEwl = other.cascadeArRatioEwl;
        this.cascadeArRatioWl = other.cascadeArRatioWl;
        this.cascadeArRatioNl = other.cascadeArRatioNl;
        this.cascadeArBootstrapping = other.cascadeArBootstrapping;

        this.enableResidualEdit = other.enableResidualEdit;
        this.residEditJumpThresh = other.residEditJumpThresh;
        this.residEditMinArcLen = other.residEditMinArcLen;
        this.residEditPcConsistThresh = other.residEditPcConsistThresh;

        this.enablePartialAR = other.enablePartialAR;
        this.partialArMinRatio = other.partialArMinRatio;
        this.partialArMinSats = other.partialArMinSats;
        this.partialArMinBootstrapping = other.partialArMinBootstrapping;
        this.partialArMaxSubsetTries = other.partialArMaxSubsetTries;

        this.enableBootstrapping = other.enableBootstrapping;
        this.bootstrappingMinSuccess = other.bootstrappingMinSuccess;
        this.bootstrappingWithRatio = other.bootstrappingWithRatio;

        this.enableSegmentSigmaFilter = other.enableSegmentSigmaFilter;
        this.segmentSigmaThreshold = other.segmentSigmaThreshold;
        this.segmentSigmaMinLen = other.segmentSigmaMinLen;

        this.enableBdsCodeBias = other.enableBdsCodeBias;
        this.bdsCodeBiasElThresh = other.bdsCodeBiasElThresh;
        this.bdsCodeBiasForceOn = other.bdsCodeBiasForceOn;

        this.enablePppIsbClockInit = other.enablePppIsbClockInit;

        this.enableParamTypeNoise = other.enableParamTypeNoise;
        this.noiseZtdRw = other.noiseZtdRw;
        this.noiseClkWhite = other.noiseClkWhite;
        this.noiseIonoRw = other.noiseIonoRw;

        this.enableIflcPairNegotiation = other.enableIflcPairNegotiation;

        this.enableGpt3Vmf3 = other.enableGpt3Vmf3;
        this.gpt3GridFile = other.gpt3GridFile;
        this.vmf3OpFile = other.vmf3OpFile;
        this.useGpt3Grid = other.useGpt3Grid;

        this.enableIers2010 = other.enableIers2010;
        this.enableAt1S2 = other.enableAt1S2;

        this.ionexFile = other.ionexFile;

        this.enableIsbIfcbIfb = other.enableIsbIfcbIfb;
        this.estimateIsb = other.estimateIsb;
        this.estimateIfcb = other.estimateIfcb;
        this.estimateIfb = other.estimateIfb;
        this.isbPrn = other.isbPrn;
        this.ifcbPrn = other.ifcbPrn;
        this.ifbPrn = other.ifbPrn;

        this.enablePppAR = other.enablePppAR;
        this.pppArRatioWl = other.pppArRatioWl;
        this.pppArRatioNl = other.pppArRatioNl;
        this.fcbFile = other.fcbFile;
        this.updFile = other.updFile;
        this.enablePppArFixHold = other.enablePppArFixHold;
        this.pppArFixHoldVar = other.pppArFixHoldVar;
        this.pppArFixHoldMinEp = other.pppArFixHoldMinEp;
        this.enablePppPartialAR = other.enablePppPartialAR;
        this.pppPartialArMinRatio = other.pppPartialArMinRatio;
        this.pppPartialArMinSats = other.pppPartialArMinSats;
        this.pppPartialArMaxTries = other.pppPartialArMaxTries;
        this.enablePppArDeltaGate = other.enablePppArDeltaGate;
        this.pppArDeltaMax = other.pppArDeltaMax;
        this.enableBds3PppAR = other.enableBds3PppAR;
        this.enableMultiFreqAR = other.enableMultiFreqAR;
        this.pppArRatioEwl = other.pppArRatioEwl;
        this.pppArWlMaxdev = other.pppArWlMaxdev;
        this.pppArNlMaxdev = other.pppArNlMaxdev;
        this.pppArNlMaxsig = other.pppArNlMaxsig;
        this.pppArNlMinsav = other.pppArNlMinsav;
        this.enableOsb = other.enableOsb;
        this.osbFile = other.osbFile;
        this.dcbFile = other.dcbFile;

        this.enableIonoEstRobust = other.enableIonoEstRobust;

        this.enableSppEkf = other.enableSppEkf;
        this.enableSppRobust = other.enableSppRobust;
        this.enableSppZeroVel = other.enableSppZeroVel;
        this.enableSppDopplerSnr = other.enableSppDopplerSnr;
        this.enableSppInnovChisq = other.enableSppInnovChisq;
        this.sppEkfMinSat = other.sppEkfMinSat;
        this.sppEkfLowSatThresh = other.sppEkfLowSatThresh;
        this.sppEkfVarPos = other.sppEkfVarPos;
        this.sppEkfVarVel = other.sppEkfVarVel;
        this.sppEkfVarAcc = other.sppEkfVarAcc;
        this.sppEkfClkProcessNoise = other.sppEkfClkProcessNoise;
        this.sppEkfAccPrn1 = other.sppEkfAccPrn1;
        this.sppEkfAccPrn2 = other.sppEkfAccPrn2;
        this.sppEkfVelResidScale = other.sppEkfVelResidScale;
        this.sppEkfVarResetThresh = other.sppEkfVarResetThresh;
        this.sppEkfDtGap = other.sppEkfDtGap;
        this.sppEkfVarCap = other.sppEkfVarCap;
        this.sppEkfDivergedRecoverEpochs = other.sppEkfDivergedRecoverEpochs;
        this.sppEkfDivergedRscaleBase = other.sppEkfDivergedRscaleBase;
        this.sppEkfDivergedRscaleMax = other.sppEkfDivergedRscaleMax;
        this.sppEkfDivergedMaxEpochs = other.sppEkfDivergedMaxEpochs;
        this.sppEkfDivergedQscale = other.sppEkfDivergedQscale;
        this.sppInnovOutlierThresh = other.sppInnovOutlierThresh;
        this.sppInnovDownweightThresh = other.sppInnovDownweightThresh;
        this.sppInnovOutlierThreshLowSat = other.sppInnovOutlierThreshLowSat;
        this.sppInnovDownweightThreshLowSat = other.sppInnovDownweightThreshLowSat;
        this.sppInnovDownweightRscale = other.sppInnovDownweightRscale;
        this.sppRobustKPos = other.sppRobustKPos;
        this.sppRobustKVel = other.sppRobustKVel;
        this.sppRobustMaxIter = other.sppRobustMaxIter;
        this.sppRobustPosOutlier = other.sppRobustPosOutlier;
        this.sppRobustVelOutlier = other.sppRobustVelOutlier;
        this.sppRobustMinW = other.sppRobustMinW;
        this.sppZeroVelSpeedThresh = other.sppZeroVelSpeedThresh;
        this.sppZeroVelMinEpochs = other.sppZeroVelMinEpochs;

        this.enablePppRtk = other.enablePppRtk;
        this.enablePppRtkAR = other.enablePppRtkAR;
        this.pppRtkArRatio = other.pppRtkArRatio;
        this.enablePppRtkFixHold = other.enablePppRtkFixHold;
        this.pppRtkFixHoldMinEpoch = other.pppRtkFixHoldMinEpoch;
        this.pppRtkFixHoldVar = other.pppRtkFixHoldVar;
        this.ssrMaxAge = other.ssrMaxAge;
        this.ssrIonoMode = other.ssrIonoMode;
        this.enableCompactSsr = other.enableCompactSsr;
        this.compactSsrGridFile = other.compactSsrGridFile;
        this.enableSsrTropDirect = other.enableSsrTropDirect;
        this.cssrDecoder = other.cssrDecoder;

        this.enableAdaptiveScene = other.enableAdaptiveScene;
        this.adaptiveWindowEpochs = other.adaptiveWindowEpochs;
        this.adaptiveCooldownEpochs = other.adaptiveCooldownEpochs;
        this.adaptiveManagedParams = other.adaptiveManagedParams;
        this.adaptiveLockedParams = other.adaptiveLockedParams;
        this.adaptiveAuditEnabled = other.adaptiveAuditEnabled;
        this.adaptiveAuditDir = other.adaptiveAuditDir;
        this.adaptiveAuditMaxSizeKB = other.adaptiveAuditMaxSizeKB;
        this.adaptiveAuditMaxFiles = other.adaptiveAuditMaxFiles;
        this.adaptiveVerbose = other.adaptiveVerbose;
        this.adaptiveTraceAll = other.adaptiveTraceAll;
        this.adaptiveListener = other.adaptiveListener;
    }
}