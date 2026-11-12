package org.rtklib.java.otl;

/**
 * [JAVA-ONLY 原创] 单个分潮在指定历元的交点潮调制量：振幅因子 f、相位改正 u、相位偏置。
 *
 * <p><b>来源说明</b>：f、u 的级数系数是 Schureman (1958) 潮汐调和分析中的经典结果，
 * 并在 IERS Conventions (2010) 第 7 章（海洋潮汐负荷）中重述；相位偏置对应
 * 同一章中的正弦/余弦分潮约定。均为公开发表的物理公式，本类按公式独立实现，
 * 未沿用任何特定软件源码的结构或命名。</p>
 *
 * <p>本类是不可变值对象。</p>
 */
public final class NodalCorrection {

    private static final double RAD = Math.PI / 180.0;

    /** 振幅调制因子 f。 */
    public final double amplitudeFactor;

    /** 相位改正 u，单位度。 */
    public final double phaseDegrees;

    /** 相位偏置，单位度（由分潮的正/余弦定义决定）。 */
    public final double biasDegrees;

    private NodalCorrection(double amplitudeFactor, double phaseDegrees, double biasDegrees) {
        this.amplitudeFactor = amplitudeFactor;
        this.phaseDegrees = phaseDegrees;
        this.biasDegrees = biasDegrees;
    }

    /**
     * 计算给定分潮的交点潮调制量。
     *
     * <p>短周期分潮（半日、全日）受月球升交点 N 的 18.61 年周期调制；
     * 长周期分潮（Mf、Mm、Ssa）在本模块的 BLQ 输出中不施加调制。</p>
     */
    public static NodalCorrection of(TidalConstituent constituent, AstronomicalArgument argument) {
        double nodeDeg = argument.ascendingNodeDegrees();
        double nodeRad = nodeDeg * RAD;

        double sinN = Math.sin(nodeRad);
        double cosN = Math.cos(nodeRad);
        double sin2N = Math.sin(2.0 * nodeDeg * RAD);
        double cos2N = Math.cos(2.0 * nodeDeg * RAD);

        switch (constituent) {
            case M2:
            case N2:
                return new NodalCorrection(1.000 - 0.037 * cosN, -2.1 * sinN, 0.0);

            case S2:
            case K2:
                return new NodalCorrection(1.024 + 0.286 * cosN + 0.008 * cos2N,
                        -17.7 * sinN + 0.7 * sin2N, 0.0);

            case K1:
                return new NodalCorrection(1.006 + 0.115 * cosN - 0.009 * cos2N,
                        -8.9 * sinN + 0.7 * sin2N, 90.0);

            case P1:
                return new NodalCorrection(1.006 + 0.115 * cosN - 0.009 * cos2N,
                        -8.9 * sinN + 0.7 * sin2N, -90.0);

            case O1:
            case Q1:
                return new NodalCorrection(1.009 + 0.187 * cosN - 0.015 * cos2N,
                        10.8 * sinN - 1.3 * sin2N, -90.0);

            default:
                return new NodalCorrection(1.0, 0.0, 0.0);
        }
    }
}