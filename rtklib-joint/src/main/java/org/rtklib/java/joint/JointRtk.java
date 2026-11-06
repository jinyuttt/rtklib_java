package org.rtklib.java.joint;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.RtkState;
import org.rtklib.java.data.Ssat;
import org.rtklib.java.data.Sol;
import org.rtklib.java.rtkpos.RtkCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

public class JointRtk implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(JointRtk.class);

    public Sol sol;
    public double[] rb;
    public double[] x;
    public double[] P;
    public double[] xa;
    public double[] Pa;
    public int nx;
    public int na;

    public int nBaseline;
    public BaseStation[] bases;
    public int[] baselineOffset;
    public int[] baselineAmbCount;

    public Ssat[] ssat;

    /**
     * [JAVA-ONLY] 每基线独立的卫星状态（周跳检测用）。
     * 全局 {@link #ssat} 中的 base 侧槽位（slip 高两位、code[][1]、pt[1][]、ph[1][]、gf）
     * 在 k 条基线下会被逐基线覆盖，导致跨基线污染：基站 B 的周跳会误重置基站 A 的模糊度。
     * 故周跳检测在 {@code ssatBl[b]} 上进行，各自维护 rover 槽位与本站 base 槽位。
     * 单基线时行为与全局 {@link #ssat} 等价（rover 观测相同，base 观测即唯一基站）。
     */
    public Ssat[][] ssatBl;

    /** [§2.1.1] 本周期电离层是否跨基线共享（由 {@link #refreshAtmSharing()} 刷新）。 */
    public boolean sharedIonoOn = true;

    /** [§2.1.1] 本周期 rover 侧对流层是否跨基线共享（由 {@link #refreshAtmSharing()} 刷新）。 */
    public boolean sharedTropOn = true;

    /** [§2.1.1] 共享电离层因超距停用的告警只输出一次。 */
    private boolean ionoDistWarned;

    /** [§2.1.1] 共享电离层一旦因超距停用即锁定，避免基线长度在阈值附近抖动导致 nx 反复重建。 */
    private boolean ionoShareLockedOff;

    /** [§2.1.1] 电离层「连续失锁历元数」，用于长期失锁后重置电离层状态（对齐 RtkCore.udion 的 outc）。 */
    public int[] atmOutc;

    /** [§2.1.1] 基站侧电离层失锁历元数（对齐 RtkCore.udion 的 outc[1]）。 */
    public int[] atmOutcBase;

    /** [§2.1.1] 取（必要时分配）电离层失锁计数器。 */
    public int[] atmOutc() {
        if (atmOutc == null || atmOutc.length != Constants.MAXSAT) {
            atmOutc = new int[Constants.MAXSAT];
        }
        return atmOutc;
    }

    /** [§2.1.1] 取（必要时分配）基站侧电离层失锁计数器。 */
    public int[] atmOutcBase() {
        if (atmOutcBase == null || atmOutcBase.length != Constants.MAXSAT) {
            atmOutcBase = new int[Constants.MAXSAT];
        }
        return atmOutcBase;
    }

    public PrcOpt opt;
    public JointConfig jointConfig;

    /** [FIX-ALG-042 §13.4 Phase A] 基站稳定性监测（只读，不影响解算结果）。 */
    public BaseStabilityMonitor monitor;

    /**
     * [FIX-ALG-042 §13.9 Phase D] 已被剔除的基线标志。
     *
     * <p>被剔除的基线不再参与任何滤波计算：其观测不进入 ddres、其模糊度状态块被冻结
     * （x=0、P 行列为 0），因此 nx 布局不变，其余基线的索引与状态完全不受影响。
     * 冻结不可逆：基站坐标修正后需重建 {@link JointRtk}（或调用 {@link #clearExcluded()}）。</p>
     */
    public boolean[] baselineExcluded;

    /** [FIX-ALG-042 §13.9 Phase C] 基站间基线骨架网（闸3，诊断用）。 */
    public BaseSkeletonNetwork skeleton;

    /** 该基线是否已被剔除。 */
    public boolean isBaselineExcluded(int b) {
        return baselineExcluded != null && b >= 0 && b < baselineExcluded.length && baselineExcluded[b];
    }

    /** 已剔除基线数（用于日志与判断是否还有可用基线）。 */
    public int excludedCount() {
        if (baselineExcluded == null) return 0;
        int n = 0;
        for (int b = 0; b < baselineExcluded.length; b++) {
            if (baselineExcluded[b]) n++;
        }
        return n;
    }

    /** 清除全部剔除标志（基站坐标修正后调用，下一历元重新参与解算）。 */
    public void clearExcluded() {
        if (baselineExcluded == null) return;
        for (int b = 0; b < baselineExcluded.length; b++) baselineExcluded[b] = false;
        log.info("joint: 清除基站剔除标志，全部 {} 条基线恢复参与解算", baselineExcluded.length);
    }

    /**
     * [§13.9 Phase D] 剔除第 b 条基线：冻结其模糊度状态块，后续历元不再参与滤波。
     *
     * <p>只清该基线的模糊度段（{@code [offset, offset+NB)}），rover 坐标、共享大气与
     * 其他基线的模糊度保持原样，因此不会像重建 nx 那样打断其他基线的收敛。</p>
     */
    public void excludeBaseline(int b) {
        if (b < 0 || b >= nBaseline) return;
        if (baselineExcluded == null || baselineExcluded.length != nBaseline) {
            baselineExcluded = new boolean[nBaseline];
        }
        if (baselineExcluded[b]) return;
        baselineExcluded[b] = true;

        if (x == null || P == null || nx <= 0) return;
        int off = baselineOffsetOf(b);
        int nb = NB();
        for (int i = off; i < off + nb && i < nx; i++) {
            x[i] = 0.0;
            for (int j = 0; j < nx; j++) {
                P[i * nx + j] = 0.0;
                P[j * nx + i] = 0.0;
            }
        }
        log.warn("joint: baseline[{}] 已剔除（Phase D），冻结模糊度状态段 [{}, {})", b, off, off + nb);
    }

    public int nfix;
    public int epoch;
    public double tt;

    public JointRtk() {
        sol = new Sol();
        rb = new double[3];
        ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            ssat[i] = new Ssat();
        }
    }

    public RtkState toState() {
        RtkConfig rtkConfig = new RtkConfig();
        double[] snrMedian = new double[2];
        double[] safeX = (x != null) ? x : new double[0];
        return new RtkState(opt, ssat, rtkConfig, snrMedian, sol, safeX, 0.0);
    }

    /** 按基线分配独立的卫星状态（仅在基线数变化时重建）。 */
    public void ensureBaselineSsat(int nb) {
        if (nb <= 0) return;
        if (ssatBl != null && ssatBl.length == nb) return;
        ssatBl = new Ssat[nb][];
        for (int b = 0; b < nb; b++) {
            ssatBl[b] = new Ssat[Constants.MAXSAT];
            for (int i = 0; i < Constants.MAXSAT; i++) {
                ssatBl[b][i] = new Ssat();
            }
        }
    }

    /** 取第 b 条基线的卫星状态；未分配时回退全局状态。 */
    public Ssat[] ssatOf(int b) {
        if (ssatBl == null || b < 0 || b >= ssatBl.length) return ssat;
        return ssatBl[b];
    }

    public int NP() {
        return RtkCore.NP(toState());
    }

    public int NI() {
        return RtkCore.NI(toState());
    }

    public int NT() {
        return RtkCore.NT(toState());
    }

    public int NB() {
        return RtkCore.NB(toState());
    }

    public int NR() {
        return RtkCore.NR(toState());
    }

    public int NL() {
        return RtkCore.NL(toState());
    }

    // ==================== [§2.1.1] 共享大气状态布局 ====================
    //
    // 旧结构（每基线一份大气，等效退回 SBS）：
    //   [pos] + Σ_b (iono_b + trop_rover_b + trop_base_b) + Σ_b amb_b
    // 新结构（大气属于「rover+卫星」/「基站+卫星」，与基线无关）：
    //   [pos] + [iono × nIonoBlocks] + [trop_rover × nTropRoverBlocks]
    //         + [trop_base × k] + Σ_b amb_b
    // 其中 nIonoBlocks = sharedIono ? 1 : k，nTropRoverBlocks = sharedTrop ? 1 : k。
    // 单基线时（1 + 1 + 1）与 RtkCore 布局完全一致；sharedIono=false 且 sharedTrop=false
    // 时与旧 joint 布局逐维一致，可作为回归对照。

    /** 每卫星电离层维数（含梯度时为 3：斜延迟 + 北 + 东）。 */
    public int NIPerSat() {
        return (opt != null && opt.ionoGradient) ? 3 : 1;
    }

    /** 单个电离层块的维数；未配 ionoopt=EST 时为 0。 */
    public int NIBlock() {
        if (opt == null || opt.ionoopt != Constants.IONOOPT_EST) return 0;
        return Constants.MAXSAT * NIPerSat();
    }

    /** 单个对流层块（每测站）的维数；未配 tropopt=EST/ESTG 时为 0。 */
    public int NTPerR() {
        if (opt == null || opt.tropopt < Constants.TROPOPT_EST) return 0;
        return (opt.tropopt >= Constants.TROPOPT_ESTG) ? 3 : 1;
    }

    /** 电离层块数：0（不估）/ 1（共享）/ k（每基线独立）。 */
    public int NIonoBlocks() {
        int niBlock = NIBlock();
        if (niBlock == 0) return 0;
        return sharedIonoOn ? 1 : Math.max(1, nBaseline);
    }

    /** rover 侧对流层块数：0（不估）/ 1（共享）/ k（每基线独立）。 */
    public int NTropRoverBlocks() {
        if (NTPerR() == 0) return 0;
        return sharedTropOn ? 1 : Math.max(1, nBaseline);
    }

    /** 大气状态总维数（pos 之后、模糊度之前）。 */
    public int NAtm() {
        return NIonoBlocks() * NIBlock() + (NTropRoverBlocks() + Math.max(1, nBaseline)) * NTPerR();
    }

    /** 非模糊度状态数（= na）。 */
    public int NRTotal() {
        return NP() + NAtm();
    }

    /** 完整状态维数。 */
    public int NXTotal() {
        return NRTotal() + Math.max(1, nBaseline) * NB();
    }

    /** 第 b 条基线模糊度段的起点（唯一真源，与 {@link #NXTotal()} 布局一致）。 */
    public int baselineOffsetOf(int b) {
        return NRTotal() + b * NB();
    }

    /** 第 b 条基线所属的电离层块号。 */
    public int ionoBlockOf(int b) {
        return sharedIonoOn ? 0 : b;
    }

    /** 第 b 条基线所属的 rover 侧对流层块号。 */
    public int tropRoverBlockOf(int b) {
        return sharedTropOn ? 0 : b;
    }

    /** 电离层状态索引（指定块）。 */
    public int II(int ionoBlock, int sat) {
        int niBlock = NIBlock();
        if (niBlock == 0 || ionoBlock < 0 || ionoBlock >= NIonoBlocks()) return -1;
        return NP() + ionoBlock * niBlock + (sat - 1) * NIPerSat();
    }

    /** 电离层状态索引（共享块，等价于 {@code II(0, sat)}）。 */
    public int II(int sat) {
        return II(0, sat);
    }

    /** rover 侧对流层状态索引（指定块，r 为块内分量：0=ZWD，1/2=梯度）。 */
    public int ITrover(int block, int r) {
        int ntPerR = NTPerR();
        if (ntPerR == 0 || block < 0 || block >= NTropRoverBlocks() || r < 0 || r >= ntPerR) return -1;
        return NP() + NIonoBlocks() * NIBlock() + block * ntPerR + r;
    }

    /** 基站侧对流层状态索引（按基站各一份）。 */
    public int ITbase(int b, int r) {
        int ntPerR = NTPerR();
        if (ntPerR == 0 || b < 0 || b >= nBaseline || r < 0 || r >= ntPerR) return -1;
        return NP() + NIonoBlocks() * NIBlock() + NTropRoverBlocks() * ntPerR + b * ntPerR + r;
    }

    public int IB(int baseline, int sat, int f) {
        if (baselineOffset == null || baseline < 0 || baseline >= baselineOffset.length) return -1;
        return baselineOffset[baseline] + Constants.MAXSAT * f + (sat - 1);
    }

    public int baselineDim(int i) {
        if (baselineOffset == null || i >= nBaseline) return 0;
        if (i == nBaseline - 1) return nx - baselineOffset[i];
        return baselineOffset[i + 1] - baselineOffset[i];
    }

    /**
     * [§2.1.1] 刷新本周期的共享策略开关。
     *
     * <p>电离层共享在长基线下不成立（DD 电离层残差随基站距离变化），
     * {@link JointConfig#sharedIonoMaxDist} 给出上限，超限则本周期退回每基线独立电离层。
     * 需在分配 nx 之前调用，保证索引函数与维度计算一致。</p>
     */
    public void refreshAtmSharing() {
        boolean shareIono = jointConfig == null || jointConfig.sharedIono;
        if (shareIono && ionoShareLockedOff) shareIono = false;
        if (shareIono && jointConfig != null && jointConfig.sharedIonoMaxDist > 0.0) {
            double maxDist = maxBaselineLength();
            if (maxDist > jointConfig.sharedIonoMaxDist * 1000.0) {
                shareIono = false;
                ionoShareLockedOff = true;
                if (!ionoDistWarned) {
                    ionoDistWarned = true;
                    log.warn("共享电离层停用：最长基线 {} km 超过 sharedIonoMaxDist {} km，退回每基线独立电离层",
                            String.format("%.2f", maxDist / 1000.0), String.format("%.2f", jointConfig.sharedIonoMaxDist));
                }
            }
        }
        sharedIonoOn = shareIono;
        sharedTropOn = jointConfig == null || jointConfig.sharedTrop;
    }

    /** 当前周期内最长基线长度（m）；状态不可用时返回 0。 */
    public double maxBaselineLength() {
        if (rb == null || x == null || x.length < 3 || bases == null) return 0.0;
        double[] rr = new double[]{rb[0] + x[0], rb[1] + x[1], rb[2] + x[2]};
        double max = 0.0;
        for (int b = 0; b < nBaseline && b < bases.length; b++) {
            if (bases[b] == null || bases[b].rb == null) continue;
            double d2 = 0.0;
            for (int i = 0; i < 3; i++) {
                double d = rr[i] - bases[b].rb[i];
                d2 += d * d;
            }
            max = Math.max(max, Math.sqrt(d2));
        }
        return max;
    }
}