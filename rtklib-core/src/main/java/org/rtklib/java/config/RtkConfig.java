package org.rtklib.java.config;

import java.io.Serializable;

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

    public boolean enableBdsCodeBias = false;
    public double bdsCodeBiasElThresh = 30.0;
    public boolean bdsCodeBiasForceOn = false;

    public boolean enableParamTypeNoise = false;
    public double noiseZtdRw = 1e-4;
    public double noiseClkWhite = 1e2;
    public double noiseIonoRw = 1e-3;

    public boolean enableGpt3Vmf3 = false;
    public String gpt3GridFile = "";
    public String vmf3OpFile = "";
    public boolean useGpt3Grid = false;

    public boolean enableIers2010 = false;
    public boolean enableAt1S2 = false;

    public boolean enableIsbIfcbIfb = false;
    public boolean estimateIsb = true;
    public boolean estimateIfcb = true;
    public boolean estimateIfb = true;
    public double isbPrn = 0.01;
    public double ifcbPrn = 0.01;
    public double ifbPrn = 0.001;

    public boolean enablePppAR = false;
    public double pppArRatioWl = 2.0;
    public double pppArRatioNl = 3.0;
    public String fcbFile = "";
    public String updFile = "";
    public boolean enablePppArFixHold = false;
    public double pppArFixHoldVar = 1e-6;
    public int pppArFixHoldMinEp = 50;
    public boolean enablePppPartialAR = false;
    public double pppPartialArMinRatio = 2.0;
    public int pppPartialArMinSats = 4;
    public int pppPartialArMaxTries = 10;
    public boolean enableBds3PppAR = false;
    public boolean enableOsb = false;
    public String osbFile = "";
    public String dcbFile = "";

    public boolean enablePppRtk = false;
    public boolean enablePppRtkAR = false;
    public double pppRtkArRatio = 3.0;
    public boolean enablePppRtkFixHold = false;
    public int pppRtkFixHoldMinEpoch = 10;
    public double pppRtkFixHoldVar = 1e-4;
    public double ssrMaxAge = 60.0;
    public int ssrIonoMode = 1;

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

        this.enableBdsCodeBias = other.enableBdsCodeBias;
        this.bdsCodeBiasElThresh = other.bdsCodeBiasElThresh;
        this.bdsCodeBiasForceOn = other.bdsCodeBiasForceOn;

        this.enableParamTypeNoise = other.enableParamTypeNoise;
        this.noiseZtdRw = other.noiseZtdRw;
        this.noiseClkWhite = other.noiseClkWhite;
        this.noiseIonoRw = other.noiseIonoRw;

        this.enableGpt3Vmf3 = other.enableGpt3Vmf3;
        this.gpt3GridFile = other.gpt3GridFile;
        this.vmf3OpFile = other.vmf3OpFile;
        this.useGpt3Grid = other.useGpt3Grid;

        this.enableIers2010 = other.enableIers2010;
        this.enableAt1S2 = other.enableAt1S2;

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
        this.enableBds3PppAR = other.enableBds3PppAR;
        this.enableOsb = other.enableOsb;
        this.osbFile = other.osbFile;
        this.dcbFile = other.dcbFile;

        this.enablePppRtk = other.enablePppRtk;
        this.enablePppRtkAR = other.enablePppRtkAR;
        this.pppRtkArRatio = other.pppRtkArRatio;
        this.enablePppRtkFixHold = other.enablePppRtkFixHold;
        this.pppRtkFixHoldMinEpoch = other.pppRtkFixHoldMinEpoch;
        this.pppRtkFixHoldVar = other.pppRtkFixHoldVar;
        this.ssrMaxAge = other.ssrMaxAge;
        this.ssrIonoMode = other.ssrIonoMode;
    }
}