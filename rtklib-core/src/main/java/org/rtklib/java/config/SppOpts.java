package org.rtklib.java.config;

import java.io.Serializable;

/**
 * SPP（伪距单点定位）参数。
 * <p>
 * 从 {@link RtkConfig} 拆分出的独立子配置，字段名去掉原 {@code spp} 前缀（已在命名空间内）。
 * 原 {@code enableSppEkf} → {@code enableEkf}，{@code sppEkfVarPos} → {@code ekfVarPos}，以此类推。
 * </p>
 */
public class SppOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean enableEkf = false;
    public boolean enableRobust = false;
    public boolean enableZeroVel = false;
    public boolean enableDopplerSnr = false;
    public boolean enableInnovChisq = true;
    public int ekfMinSat = 0;
    public int ekfLowSatThresh = 7;

    public double ekfVarPos = 50.0 * 50.0;
    public double ekfVarVel = 10.0 * 10.0;
    public double ekfVarAcc = 10.0 * 10.0;
    public double ekfClkProcessNoise = 10000.0;
    public double ekfAccPrn1 = 10.0;
    public double ekfAccPrn2 = 10.0;
    public double ekfVelResidScale = 1.0;
    public double ekfVarResetThresh = 50.0 * 50.0;
    public double ekfDtGap = 10.0;
    public double ekfVarCap = 50.0 * 50.0 / 4.0;
    public int ekfDivergedRecoverEpochs = 5;
    public double ekfDivergedRscaleBase = 100.0;
    public double ekfDivergedRscaleMax = 1000.0;
    public double ekfDivergedMaxEpochs = 20;
    public double ekfDivergedQscale = 1.0;
    public double innovOutlierThresh = 5.0;
    public double innovDownweightThresh = 3.0;
    public double innovOutlierThreshLowSat = 7.0;
    public double innovDownweightThreshLowSat = 5.0;
    public double innovDownweightRscale = 10.0;

    public double robustKPos = 1.345;
    public double robustKVel = 5.0;
    public int robustMaxIter = 15;
    public double robustPosOutlier = 100.0;
    public double robustVelOutlier = 20.0;
    public double robustMinW = 0.0001;

    public double zeroVelSpeedThresh = 0.5;
    public int zeroVelMinEpochs = 1;

    public SppOpts() {
    }

    public SppOpts(SppOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(SppOpts other) {
        this.enableEkf = other.enableEkf;
        this.enableRobust = other.enableRobust;
        this.enableZeroVel = other.enableZeroVel;
        this.enableDopplerSnr = other.enableDopplerSnr;
        this.enableInnovChisq = other.enableInnovChisq;
        this.ekfMinSat = other.ekfMinSat;
        this.ekfLowSatThresh = other.ekfLowSatThresh;
        this.ekfVarPos = other.ekfVarPos;
        this.ekfVarVel = other.ekfVarVel;
        this.ekfVarAcc = other.ekfVarAcc;
        this.ekfClkProcessNoise = other.ekfClkProcessNoise;
        this.ekfAccPrn1 = other.ekfAccPrn1;
        this.ekfAccPrn2 = other.ekfAccPrn2;
        this.ekfVelResidScale = other.ekfVelResidScale;
        this.ekfVarResetThresh = other.ekfVarResetThresh;
        this.ekfDtGap = other.ekfDtGap;
        this.ekfVarCap = other.ekfVarCap;
        this.ekfDivergedRecoverEpochs = other.ekfDivergedRecoverEpochs;
        this.ekfDivergedRscaleBase = other.ekfDivergedRscaleBase;
        this.ekfDivergedRscaleMax = other.ekfDivergedRscaleMax;
        this.ekfDivergedMaxEpochs = other.ekfDivergedMaxEpochs;
        this.ekfDivergedQscale = other.ekfDivergedQscale;
        this.innovOutlierThresh = other.innovOutlierThresh;
        this.innovDownweightThresh = other.innovDownweightThresh;
        this.innovOutlierThreshLowSat = other.innovOutlierThreshLowSat;
        this.innovDownweightThreshLowSat = other.innovDownweightThreshLowSat;
        this.innovDownweightRscale = other.innovDownweightRscale;
        this.robustKPos = other.robustKPos;
        this.robustKVel = other.robustKVel;
        this.robustMaxIter = other.robustMaxIter;
        this.robustPosOutlier = other.robustPosOutlier;
        this.robustVelOutlier = other.robustVelOutlier;
        this.robustMinW = other.robustMinW;
        this.zeroVelSpeedThresh = other.zeroVelSpeedThresh;
        this.zeroVelMinEpochs = other.zeroVelMinEpochs;
    }
}
