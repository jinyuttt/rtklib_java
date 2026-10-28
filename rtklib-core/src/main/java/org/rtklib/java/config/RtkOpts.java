package org.rtklib.java.config;

import java.io.Serializable;

/**
 * RTK（双差相对定位）专用参数。
 * <p>
 * 从 {@link RtkConfig} 拆分出的独立子配置，归属 RtkCore / RtkOptimizations 等 RTK 相关逻辑。
 * </p>
 */
public class RtkOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    /* 自适应过程噪声 */
    public boolean enableAdaptiveQ = false;
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

    /* IGGIII 抗差 */
    public boolean enableIggiii = false;
    public double iggiiiK0 = 3.0;
    public double iggiiiK1 = 6.0;
    public double iggiiiMinW = 0.5;
    public double iggiiiLowElMask = 10.0 * Math.PI / 180.0;
    public double iggiiiLowElNormThresh = 2.5;
    public double iggiiiLowElW = 0.5;
    public double iggiiiMultiFreqW = 0.5;
    public double iggiiiLowElExtraIterMask = 15.0 * Math.PI / 180.0;

    /* SNR 中位数抗差 */
    public boolean enableSnrMedian = false;
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

    /* 电离层/对流层梯度 */
    public boolean enableIonoTropGradient = false;
    public double gradientIonoInitVar = 1e-4;
    public double gradientIonoPrn = 1e-3;

    /* 模糊度锚定 */
    public boolean enableAmbAnchor = false;
    public int ambAnchorMinFixCount = 100;
    public double ambAnchorVar = 1e-9;

    /* Cascade AR */
    public boolean enableCascadeAR = false;
    public double cascadeArRatioEwl = 1.5;
    public double cascadeArRatioWl = 2.0;
    public double cascadeArRatioNl = 3.0;
    public boolean cascadeArBootstrapping = false;

    /* 残差编辑 */
    public boolean enableResidualEdit = false;
    public double residEditJumpThresh = 4.0;
    public int residEditMinArcLen = 10;
    public double residEditPcConsistThresh = 3.0;

    /* 部分模糊度固定 */
    public boolean enablePartialAR = false;
    public double partialArMinRatio = 2.0;
    public int partialArMinSats = 4;
    public double partialArMinBootstrapping = 0.99;
    public int partialArMaxSubsetTries = 10;

    /* Bootstrapping */
    public boolean enableBootstrapping = false;
    public double bootstrappingMinSuccess = 0.99;
    public boolean bootstrappingWithRatio = true;

    /* 分段 sigma 滤波 */
    public boolean enableSegmentSigmaFilter = false;
    public double segmentSigmaThreshold = 5.0;
    public int segmentSigmaMinLen = 5;

    /* BDS 码偏差 */
    public boolean enableBdsCodeBias = false;
    public double bdsCodeBiasElThresh = 30.0;
    public boolean bdsCodeBiasForceOn = false;

    public RtkOpts() {
    }

    public RtkOpts(RtkOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(RtkOpts other) {
        this.enableAdaptiveQ = other.enableAdaptiveQ;
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

        this.enableIggiii = other.enableIggiii;
        this.iggiiiK0 = other.iggiiiK0;
        this.iggiiiK1 = other.iggiiiK1;
        this.iggiiiMinW = other.iggiiiMinW;
        this.iggiiiLowElMask = other.iggiiiLowElMask;
        this.iggiiiLowElNormThresh = other.iggiiiLowElNormThresh;
        this.iggiiiLowElW = other.iggiiiLowElW;
        this.iggiiiMultiFreqW = other.iggiiiMultiFreqW;
        this.iggiiiLowElExtraIterMask = other.iggiiiLowElExtraIterMask;

        this.enableSnrMedian = other.enableSnrMedian;
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

        this.enableIonoTropGradient = other.enableIonoTropGradient;
        this.gradientIonoInitVar = other.gradientIonoInitVar;
        this.gradientIonoPrn = other.gradientIonoPrn;

        this.enableAmbAnchor = other.enableAmbAnchor;
        this.ambAnchorMinFixCount = other.ambAnchorMinFixCount;
        this.ambAnchorVar = other.ambAnchorVar;

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
    }
}
