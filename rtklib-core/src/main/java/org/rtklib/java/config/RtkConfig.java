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
    }
}