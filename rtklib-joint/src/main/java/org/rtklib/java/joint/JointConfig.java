package org.rtklib.java.joint;

import java.io.Serializable;

public class JointConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public int maxBaseline = 3;
    public double maxTimeDiff = 0.05;

    public boolean jointCycleSlip = true;

    /**
     * [FIX-ALG-042 §13.4 Phase A] 基站稳定性监测开关。
     * 开启后每历元做闸1（残差 χ²）+ 闸2（载波残差反演位移），只读输出诊断，
     * 不改变滤波权重与基站集合，故不影响解算结果。
     */
    public boolean baseStabilityMonitor = true;

    /**
     * [FIX-ALG-042 §13.9 Phase B] 闸2 结论联动降权。
     *
     * <p>开启后，监测器判定 DRIFTING 的基线在下一历元的观测协方差 {@code R_b} 按
     * {@code |Δδb_b|/门限} 的平方膨胀（见 {@link BaseStabilityMonitor#getWeight(int)}），
     * 使其对联合滤波的影响随位移显著性平滑下降；判定恢复后自动回到 1.0。
     * 关闭时与 Phase A 完全一致（只读诊断）。</p>
     */
    public boolean baseStabilityDownWeight = true;

    /**
     * [FIX-ALG-042 §13.9 Phase D] DISPLACED 基线剔除。
     *
     * <p>开启后，闸2 持续给出大位移的基线被永久冻结（其模糊度状态块置零、后续历元不再
     * 参与联合滤波），避免已位移基站的观测持续污染 rover 坐标。冻结是不可逆的
     * （需人工介入恢复），对应 {@link BaseStabilityMonitor#REJECTED} 语义的前一阶段。</p>
     */
    public boolean baseDisplacedReject = true;

    /**
     * [FIX-ALG-042 §13.9 Phase C] 闸3：基站间基线骨架网。
     *
     * <p>开启后按「基站对」独立解算基站间基线（静态、只用 FIX 解），监测基站间长度变化。
     * 这是唯一不依赖 rover 的基站位移观测量：rover 侧异常不会进入该判据，
     * 可在 rover 完全无解时仍判断基站是否位移。需要 {@code nBaseline >= 3} 才能定位到
     * 具体基站；{@code nBaseline == 2} 时只能给出「两条基线之和异常」的联合结论。</p>
     */
    public boolean baseSkeletonNetwork = false;

    /**
     * 闸3 执行间隔（历元）。
     *
     * <p>基站间基线为静态解，理论上可以低频执行；但静态滤波的模糊度锁定/固定需要
     * 连续历元累积（{@code minlock}），间隔过大会把「收敛所需历元」放大成同样的倍数。
     * 故默认逐历元执行（1），仅在 CPU 紧张时增大。</p>
     */
    public int baseSkeletonInterval = 1;

    /**
     * 基站坐标外部覆盖（基站ID -> ECEF坐标，米）。
     * 优先于 RTCM 1005 播发坐标：用于基站坐标标定/修正注入（如 1005 坐标异常时
     * 由诊断器给出修正坐标）。为 null 或不含该基站时使用 RTCM 1005。
     */
    public java.util.Map<String, double[]> baseCoordOverride;

    /**
     * [§2.1.1] 电离层状态跨基线共享（rover 侧）。
     *
     * <p>物理上 rover 只有一个，电离层延迟属于「rover + 卫星」，与基站无关，
     * 故 k 条基线应共用同一组电离层状态。为 true 时状态向量中电离层只占
     * {@code MAXSAT*(ionoGradient?3:1)} 维；为 false 时退回「每基线一份」的旧结构。</p>
     */
    public boolean sharedIono = true;

    /**
     * [§2.1.1] 对流层状态跨基线共享（rover 侧）。
     *
     * <p>rover 天顶湿延迟只有一个，k 条基线应共用；基站侧天顶湿延迟仍按基站各一份
     * （不同基站气象条件不同，不可共享）。为 false 时退回「每基线 rover+base 各一份」。</p>
     */
    public boolean sharedTrop = true;

    /**
     * [§2.1.1] 电离层共享的基线长度上限（km，0 = 不限制）。
     *
     * <p>DD 电离层残差实际是「rover 电离层 - 基站电离层」。基线越长，各基站侧电离层
     * 差异越大，单一 rover 共享状态无法同时吸收 k 条基线的不同残差，此时自动退回
     * 每基线独立电离层（并输出一次告警）。短基线（数 km）内共享成立。</p>
     */
    public double sharedIonoMaxDist = 0.0;
}