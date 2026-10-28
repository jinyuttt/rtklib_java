package org.rtklib.java.networkvrs.config;

/**
 * 网络 RTK 配置（档 1：多站同步 + IDW；档 2：网解 + 误差分解 + FKP）。
 *
 * <p>字段均为 public，保持与 core 的 RtkConfig 风格一致。</p>
 */
public class NetworkConfig {

    /** 插值模型。 */
    public enum InterpolationModel {
        /** 反距离加权（档 1，默认）。 */
        IDW,
        /** FKP 平面拟合（档 2）。 */
        FKP
    }

    /** 输出一个同步历元所需的最少站数（网形插值要求 ≥3）。 */
    public int minStations = 3;

    /** 历元对齐容差（秒）。小于半个采样间隔即可，默认 0.5 s。 */
    public double epochToleranceSec = 0.5;

    /** IDW 距离权指数 p（w = 1/d^p），默认 2。 */
    public double idwPower = 2.0;

    // ---------------- 档 2：网解 ----------------

    /** 插值模型选择，默认保持档 1 的 IDW。 */
    public InterpolationModel interpolationModel = InterpolationModel.IDW;

    /** FKP 平面拟合最少站数。 */
    public int fkpMinStations = 3;

    /**
     * FKP 几何条件阈值：三角形归一化面积 2A/maxEdge²。
     * 低于该值视为近共线，默认 0.05（约 3° 最小角）。
     */
    public double fkpMinAreaRatio = 0.05;

    /** FKP 几何退化时是否回退 IDW，默认开启。 */
    public boolean fkpFallbackToIdw = true;

    /** 参与网解与插值的最大基线长度（米），默认 150 km。 */
    public double maxBaselineMeters = 150000.0;

    /** 最低卫星高度角（度）。 */
    public double minElevationDeg = 15.0;

    /** 一个基线窗口至少需要的双差卫星对数（含参考星外的卫星）。 */
    public int minCommonSatellites = 4;

    /** 网解批处理窗口长度（历元数），30 s 采样下 20 历元约 10 min。 */
    public int solutionWindowEpochs = 20;

    /** 单频伪距观测标准差（米），用于网解权。 */
    public double codeSigmaM = 1.5;

    /** 单频载波相位观测标准差（米）。 */
    public double phaseSigmaM = 0.01;

    /** 宽巷（MW）固定阈值（周），|均值−最近整数| 超过即不固定。 */
    public double wlFixThresholdCycles = 0.3;

    /** 宽巷窗口内散度阈值（周），超过视为存在周跳/多路径，不固定。 */
    public double wlScatterThresholdCycles = 0.15;

    /** LAMBDA ratio 固定阈值（s[1]/s[0]）。 */
    public double arRatioThreshold = 3.0;

    /** 窄巷固定所需最少模糊度个数。 */
    public int minFixedAmbiguities = 4;

    /** 码几何无关组合电离层先验标准差（米）。 */
    public double ionoPriorSigmaM = 0.5;

    /** 双差相位几何无关组合电离层观测标准差（米）。 */
    public double ddIonoPhaseSigmaM = 0.02;

    /** 非色散残差（扣除模型对流层后）先验标准差（米）。 */
    public double nonDispPriorSigmaM = 0.3;

    /** 双差非色散残差观测标准差（米）。 */
    public double ddNonDispSigmaM = 0.05;
}
