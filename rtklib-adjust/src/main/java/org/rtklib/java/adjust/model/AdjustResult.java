package org.rtklib.java.adjust.model;

import org.ejml.simple.SimpleMatrix;

/**
 * 间接平差结果POJO。
 *
 * <p>包含平差后的坐标改正数、参数协方差、残差、单位权中误差、Baarda数据探测T统计量。</p>
 */
public class AdjustResult {

    /** 历元标识 */
    public final String epochTag;

    /** 参数改正数 [dX, dY, dZ] (m) */
    public final double[] dx;

    /** 参数协方差矩阵 3x3 (m^2) */
    public final double[][] Dx;

    /** 残差向量 (m)，长度=3*有效基线数 */
    public final double[] v;

    /** 单位权中误差 sigma0，df=0时为NaN */
    public final double sigma0;

    /** 自由度 df = m - n，m=3k, n=3 */
    public final int dof;

    /** 残差协因数矩阵 Qv (mxm)，df=0时为null */
    public final SimpleMatrix Qv;

    /** Baarda数据探测T统计量，每个观测分量一个，df=0时为null */
    public final double[] baardaT;

    /** 平差后P01坐标 [X, Y, Z] (m) */
    public final double[] p01Xyz;

    /** 实际参与平差的基线数 */
    public final int usedBaselineCount;

    /** 是否成功 */
    public final boolean success;

    /** 失败原因（success=false时有值） */
    public final String errorMessage;

    private AdjustResult(String epochTag, double[] dx, double[][] Dx, double[] v,
                         double sigma0, int dof, SimpleMatrix Qv, double[] baardaT,
                         double[] p01Xyz, int usedBaselineCount) {
        this.epochTag = epochTag;
        this.dx = dx;
        this.Dx = Dx;
        this.v = v;
        this.sigma0 = sigma0;
        this.dof = dof;
        this.Qv = Qv;
        this.baardaT = baardaT;
        this.p01Xyz = p01Xyz;
        this.usedBaselineCount = usedBaselineCount;
        this.success = true;
        this.errorMessage = null;
    }

    private AdjustResult(String epochTag, int usedBaselineCount, String errorMessage) {
        this.epochTag = epochTag;
        this.dx = null;
        this.Dx = null;
        this.v = null;
        this.sigma0 = Double.NaN;
        this.dof = 0;
        this.Qv = null;
        this.baardaT = null;
        this.p01Xyz = null;
        this.usedBaselineCount = usedBaselineCount;
        this.success = false;
        this.errorMessage = errorMessage;
    }

    public static AdjustResult success(String epochTag, double[] dx, double[][] Dx,
                                       double[] v, double sigma0, int dof,
                                       SimpleMatrix Qv, double[] baardaT,
                                       double[] p01Xyz, int usedBaselineCount) {
        return new AdjustResult(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount);
    }

    public static AdjustResult failure(String epochTag, int usedBaselineCount, String errorMessage) {
        return new AdjustResult(epochTag, usedBaselineCount, errorMessage);
    }
}