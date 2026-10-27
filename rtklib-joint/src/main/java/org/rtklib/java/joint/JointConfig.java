package org.rtklib.java.joint;

import java.io.Serializable;

public class JointConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public int maxBaseline = 3;
    public double maxTimeDiff = 0.05;

    public boolean sharedIono = true;
    public boolean sharedTrop = false;
    public double sharedIonoMaxDist = 10000.0;

    public boolean independentAmbFix = true;
    public boolean jointAmbFix = false;

    public boolean blockDiagonalR = true;
    public boolean modelCrossCorrelation = false;

    public boolean jointCycleSlip = true;

    public boolean useNetworkAdjustment = true;

    /**
     * 基站坐标外部覆盖（基站ID -> ECEF坐标，米）。
     * 优先于 RTCM 1005 播发坐标：用于基站坐标标定/修正注入（如 1005 坐标异常时
     * 由诊断器给出修正坐标）。为 null 或不含该基站时使用 RTCM 1005。
     */
    public java.util.Map<String, double[]> baseCoordOverride;
}