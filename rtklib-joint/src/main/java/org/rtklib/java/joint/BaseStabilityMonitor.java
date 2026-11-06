package org.rtklib.java.joint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [FIX-ALG-042 §13.4/§13.9] 基站稳定性监测：闸1（每基线残差 χ² 检验）+ 闸2（载波残差反演基站位移）。
 *
 * <p><b>Phase A（只读诊断）</b>：维护状态机与日志告警，不改变滤波权重、不剔除基站。
 * <b>Phase B</b>：把闸2 结论映射为基线权重 {@link #getWeight(int)}，由 {@link JointCore}
 * 在下一历元膨胀该基线的观测协方差，实现「位移越显著、权重越低」的平滑降权。
 * <b>Phase D</b>：闸2 持续给出大位移的基线进入 {@link #DISPLACED}，由 {@link JointCore}
 * 冻结其状态块并从联合滤波中剔除。</p>
 *
 * <p>闸2 的关键性质：载波残差中的模糊度误差是逐历元常量，在历元差分中完全抵消，
 * 故判据使用 {@code Δδb} 而非 {@code δb} 绝对值——即使 AR 完全不能固定（FIX=0%），
 * 闸2 依然能检测「基站有没有动」。</p>
 */
public class BaseStabilityMonitor {
    private static final Logger log = LoggerFactory.getLogger(BaseStabilityMonitor.class);

    /** 状态：未知。 */
    public static final int UNKNOWN = 0;
    /** 状态：稳定，全权参与联合滤波。 */
    public static final int STABLE = 1;
    /** 状态：疑似漂移，Phase B 会降权。 */
    public static final int DRIFTING = 2;
    /** 状态：已位移，Phase D 会剔除。 */
    public static final int DISPLACED = 3;
    /** 状态：永久排除，需人工介入。 */
    public static final int REJECTED = 4;

    /** 异常归属结论：无异常。 */
    private static final int MODE_NONE = 0;
    /** 异常归属结论：公共模式（全部基线同时异常，指向共用 rover 侧）。 */
    private static final int MODE_COMMON = 1;
    /** 异常归属结论：单基站模式（仅一条基线异常，指向该基站自身）。 */
    private static final int MODE_SINGLE = 2;

    /** 连续有效历元数达到该值才判定 STABLE（并开始参与状态判定）。 */
    private static final int MIN_EPOCHS_STABLE = 10;
    /**
     * 闸1 的自校准判据：当前 χ²/dof 超过「本基线近期中位数」的该倍数即视为异常。
     *
     * <p>不能用绝对阈值：不同配置下 χ²/dof 的量级差三个数量级
     * （单基线健康时约 0.2，双基线健康时约 900），绝对阈值必然误判其中一种。
     * 判据只看「相对自身基线水平的跃变」，因此与配置无关。</p>
     */
    private static final double CHI2_REF_FACTOR = 5.0;
    /** 闸1 判坏的绝对下限，避免在 χ²/dof 本身极小时被微小波动触发。 */
    private static final double CHI2_ABS_MIN = 10.0;
    /** 闸1 参考水平窗口长度（历元）。用中位数抵抗坏历元污染。 */
    private static final int CHI2_REF_WINDOW = 200;
    /** 闸1 参考水平至少需要该样本数才启用判据。 */
    private static final int CHI2_REF_MIN_SAMPLES = 20;
    /** 触发 DRIFTING 的闸1 超限连续历元数。 */
    private static final int DRIFT_M_EPOCHS = 5;
    /** 连续正常历元数达到该值才从 DRIFTING 恢复 STABLE。 */
    private static final int RECOVER_EPOCHS = 10;
    /** 闸2 单历元阶跃告警阈值（米），仅用于日志。 */
    private static final double STEP_LOG_M = 0.05;
    /** 闸2 显著性倍数：{@code |Δδb|} 需超过该倍数的合成 σ 才算物理阶跃，而非反演噪声。 */
    private static final double STEP_SIGMA_K = 3.0;
    /**
     * 闸2 粗差门限的绝对下限（米）。
     *
     * <p>同弧段残差差分正常只有毫米级（实测中位数 4 mm），而模糊度重初始化/周跳会让个别
     * 观测的 {@code dv} 跳到米级。门限取「绝对下限」与「dv 中位数的倍数」中的较大者：
     * 前者防止在极小噪声下误剔，后者保证真实大位移（所有 dv 同时变化）不会被误剔。</p>
     */
    private static final double DV_GATE_M = 0.02;
    /** 闸2 粗差门限相对 dv 中位数的倍数。 */
    private static final double DV_GATE_K = 5.0;
    /**
     * 闸2 自洽性门限（米）：后验残差 RMS 超过该值说明 dv 不是几何可解释的位移。
     *
     * <p>整历元模糊度重置会让所有弧段 dv 同时跳米级（实测 0.5~1.3 m），此时几何反演
     * 会给出 {@code |Δδb|=3~4 m} 而 σ 仅 25 mm 的伪阶跃。真实位移的后验残差只有毫米级，
     * 故用 3 cm 作为「可信」上限，把不可解释的历元整体放弃。</p>
     */
    private static final double COHERENCE_RMS_M = 0.03;
    /** 闸2 阶跃量样本环容量；仅用于统计分位数，与判据无关。 */
    private static final int STEP_RING_SIZE = 512;

    /**
     * [Phase B] 降权门限的绝对下限（米，物理位移）。
     *
     * <p>健康小时单历元物理位移的 p90 实测约 8 mm、max 约 19 mm（无条件分位），
     * 取 3 cm 作为「开始降权」的下限，保证健康数据不会触发任何降权。
     * 门限同时受 {@link #STEP_SIGMA_K} 倍合成 σ 约束：噪声大时自动放宽。</p>
     */
    private static final double WEIGHT_STEP_FLOOR_M = 0.03;
    /** [Phase B] 权重上限（对应门限的 20 倍位移，即 20σ），避免权重无界衰减到 0。 */
    private static final double MAX_WEIGHT = 400.0;
    /**
     * [Phase D] 判定 DISPLACED 的物理位移下限（米）。
     *
     * <p>设计文档给出的绝对值判据是 5 cm，但实测健康数据（k=2 公共模式分离后）
     * 单历元位移 max 可达 1.9 cm、换算物理量 3.8 cm，与 5 cm 仅差 1.3 倍，
     * 会频繁误判。故取 0.30 m 作为「已位移」下限：这一量级只可能来自基站被移动/重设，
     * 中间区间（3 cm ~ 30 cm）由 Phase B 的连续降权承担。</p>
     */
    private static final double DISPLACE_STEP_M = 0.30;
    /** [Phase D] 连续超过 {@link #DISPLACE_STEP_M} 的历元数达到该值才剔除（1 Hz 约 1 分钟）。 */
    private static final int DISPLACE_M_EPOCHS = 60;
    /** [Phase B] 闸2 连续超门限的历元数达到该值才置 DRIFTING（与闸1 一致，抑制单历元噪声）。 */
    private static final int STEP_DRIFT_EPOCHS = 3;
    /**
     * [Phase B/D] 判据所用的物理位移中位数窗口长度（有效历元数）。
     *
     * <p>取 5 是「3 个历元即触发 DRIFTING」与「60 个历元才剔除」两个时标之间的折中：
     * 窗口内至少要有 3 个样本才能让中位数摆脱单历元伪值，同时 5 个历元的时延对
     * 1 Hz 数据只有 5 s，对真实位移的响应仍然及时。</p>
     */
    private static final int STEP_MED_WINDOW = 5;
    /**
     * [Phase B/D] 可归因单台基站位移所需的最少基线数。
     *
     * <p>闸2 的公共模式分离用「各基线位移量的中位数」作 rover 公共分量：
     * {@code k=2} 时中位数退化为均值，单台基站位移 {@code d} 被均分成 {@code ±d/2}
     * 落在两条基线上，两条基线同时「看起来」位移，无法判断是哪台基站动了；
     * {@code k>=3} 时中位数锚定在多数（健康）基线上，只有位移基站的基线偏离，可直接归因。
     * 归因不了的降权会打击健康基站，比不降权更糟，故 {@code k<3} 只做诊断。</p>
     */
    private static final int MIN_BASES_FOR_ATTRIBUTION = 3;

    private int[] status;
    private int[] nEpoch;
    private int[] chi2BadCount;
    private int[] recoverCount;

    // ==================== [§13.9 Phase B/D] ====================
    /** 本历元闸2 是否产出了可比位移（决定 {@code stepBadCount} 是否更新）。 */
    private boolean[] stepSolved;
    /** 闸2 连续超门限历元数（正） / 连续正常历元数（负，取 max 0）。 */
    private int[] stepBadCount;
    /** 闸2 连续超过 {@link #DISPLACE_STEP_M} 的历元数。 */
    private int[] displaceCount;
    /** 当前基线权重（R 膨胀系数，1.0 = 不降权）。 */
    private double[] weight;
    /** 闸2 驱动的 DRIFTING 历元累计数（与闸1 驱动的 {@link #driftingEpochs} 分开统计）。 */
    private int[] driftStepEpochs;
    /** 处于 DRIFTING 的历元累计数（用于回归测试量化健康度）。 */
    private int[] driftingEpochs;
    /** 判定为「模糊度重置」的历元累计数（诊断用）。 */
    private int[] resetEpochs;
    /** 因基线数不足以分离 rover/基站位移而无法输出闸2 结果的历元累计数（诊断用）。 */
    private int[] unidentifiableEpochs;
    /** 全部基线同时 DRIFTING（即异常来自共用 rover 侧）的历元累计数。 */
    private int commonModeEpochs;
    /** 上一次的异常归属结论（0=无异常，MODE_COMMON，MODE_SINGLE），用于只在结论切换时输出日志。 */
    private int conclusionMode;
    /** 上一次 SINGLE-BASE 结论对应的基线号（-1 表示不适用）。 */
    private int conclusionBaseline = -1;

    /** χ²/dof 历史环形缓冲，用于自校准参考水平。 */
    private double[][] chi2Hist;
    private int[] chi2HistPos;
    private int[] chi2HistCount;

    /** 上一历元的残差标志集合，用于识别模糊度重置（卫星集变化 → 有新弧段）。 */
    private int[][] lastVflg;
    /** 上一历元的残差值 / 方差 / 个数，用于同弧段时间差分（闸2 的可用形式）。 */
    private double[][] lastResV;
    private double[][] lastResVar;
    private int[] lastResN;

    /** 上一历元反演的位移量（ECEF，米）。 */
    private double[][] lastDb;
    /** 上一历元位移量的标准差。 */
    private double[][] lastSigma;
    /** 最近一次 χ²/dof。 */
    private double[] lastChi2Ratio;
    /** 最近一次闸2 单历元阶跃量（米）及其合成 σ，仅诊断用。 */
    private double[] lastStep;
    private double[] lastStepSigma;

    /**
     * 闸2 阶跃量样本环（米），用于统计**无条件**分位数。
     *
     * <p>日志只在超过 {@link #STEP_LOG_M} 时输出，因此日志里的分位数是「条件于超门限」的，
     * 会系统性高估。Phase B 标定降权门限必须用无条件分布，故单独留环形缓冲。</p>
     */
    private double[][] stepRing;
    private int[] stepRingN;
    private int[] stepRingPos;
    /** 闸2 成功产出位移的历元数，与历史最大阶跃量。 */
    private int[] stepSamples;
    private double[] stepMax;
    /** 闸2 具备可比上一历元、进入解算的历元数；与 {@link #stepSamples} 之差即被放弃的历元数。 */
    private int[] gate2Attempts;
    /** [Phase B/D] 物理位移中位数窗口（环形）及其写入位置与已填样本数。 */
    private double[][] stepMed;
    private int[] stepMedPos;
    private int[] stepMedCount;
    /** [Phase B/D] 一次性日志去重标记：不可归因（k<3）、无健康参照。 */
    private boolean attributionWarned;
    private boolean noReferenceWarned;

    private void ensure(int nb) {
        if (status != null && status.length == nb) return;
        status = new int[nb];
        nEpoch = new int[nb];
        chi2BadCount = new int[nb];
        recoverCount = new int[nb];
        stepSolved = new boolean[nb];
        stepBadCount = new int[nb];
        displaceCount = new int[nb];
        weight = new double[nb];
        driftStepEpochs = new int[nb];
        driftingEpochs = new int[nb];
        resetEpochs = new int[nb];
        unidentifiableEpochs = new int[nb];
        chi2Hist = new double[nb][CHI2_REF_WINDOW];
        chi2HistPos = new int[nb];
        chi2HistCount = new int[nb];
        lastVflg = new int[nb][];
        lastResV = new double[nb][];
        lastResVar = new double[nb][];
        lastResN = new int[nb];
        lastDb = new double[nb][];
        lastSigma = new double[nb][];
        lastChi2Ratio = new double[nb];
        lastStep = new double[nb];
        lastStepSigma = new double[nb];
        stepRing = new double[nb][STEP_RING_SIZE];
        stepRingN = new int[nb];
        stepRingPos = new int[nb];
        stepSamples = new int[nb];
        stepMax = new double[nb];
        gate2Attempts = new int[nb];
        stepMed = new double[nb][STEP_MED_WINDOW];
        stepMedPos = new int[nb];
        stepMedCount = new int[nb];
        attributionWarned = false;
        noReferenceWarned = false;
        commonModeEpochs = 0;
        conclusionMode = 0;
        conclusionBaseline = -1;
        for (int b = 0; b < nb; b++) {
            status[b] = UNKNOWN;
            weight[b] = 1.0;
        }
    }

    /**
     * 每历元在 Kalman 更新之后调用（只读）。
     *
     * @param blObs     各基线观测（提供 {@code e} 视线向量与 {@code sat/iu} 索引）
     * @param nBaseline 基线数
     * @param v         各基线残差数组（{@code ddres} 输出）
     * @param nv        各基线残差个数
     * @param vflg      各基线残差标志 {@code (satRef<<16)|(satJ<<8)|(code<<4)|frq}
     * @param var       各基线残差方差（R 的对角元）
     */
    public void update(BaselineObs[] blObs, int nBaseline,
                       double[][] v, int[] nv, int[][] vflg, double[][] var) {
        ensure(nBaseline);
        double[][] u = new double[nBaseline][];
        double[][] uSig = new double[nBaseline][];
        for (int b = 0; b < nBaseline; b++) {
            BaselineObs bo = blObs[b];
            stepSolved[b] = false;
            if (bo == null || nv[b] <= 0) continue;
            nEpoch[b]++;

            double chi2 = 0.0;
            int dof = 0;
            for (int i = 0; i < nv[b]; i++) {
                double r = var[b][i];
                if (r <= 0.0) continue;
                chi2 += v[b][i] * v[b][i] / r;
                dof++;
            }
            lastChi2Ratio[b] = dof > 0 ? chi2 / dof : 0.0;
            boolean chi2Bad = isChi2Abnormal(b, lastChi2Ratio[b]);
            chi2BadCount[b] = chi2Bad ? chi2BadCount[b] + 1 : Math.max(0, chi2BadCount[b] - 1);

            double[] db = new double[3];
            double[] sigma = new double[3];
            boolean solvedAbs = solveDisplacement(bo, v[b], vflg[b], var[b], nv[b], db, sigma);
            if (solvedAbs) {
                lastDb[b] = db;
                lastSigma[b] = sigma;
            }

            // 闸2 采用「同弧段时间差分」：只用上一历元也存在同一弧段的载波观测，
            // 对残差做历元差分后再反演。模糊度误差在同一弧段内近似不变，差分后抵消；
            // 直接反演 δb 绝对值则会把各卫星的模糊度误差投影进 δb，实测噪声达米级。
            // 差分后 dv_j = a_jᵀ(Δδrr - Δδb_b)，即解出的 u_b 同时含 rover 先验位置误差变化
            // 与该基站位移。二者的几何系数完全相同，单基线无法分离，
            // 故在此只解出 u_b，公共模式分离留到本轮末统一处理（见 separateCommonMode）。
            double[] du = new double[3];
            double[] duSig = new double[3];
            if (solveDeltaDisplacement(b, bo, v[b], vflg[b], var[b], nv[b], du, duSig)) {
                u[b] = du;
                uSig[b] = duSig;
            }
            boolean reset = updateFlagsAndDetectReset(b, vflg[b], nv[b]);
            lastStep[b] = 0.0;
            lastStepSigma[b] = 0.0;
            if (reset) {
                // 卫星集变化（新弧段初始化/失锁）：该历元的 Δδb 会被新弧段的初始模糊度偏置污染。
                // 时间差分本身已只用共同弧段，故不必整体作废，仅计数以便评估闸2 可用样本占比。
                resetEpochs[b]++;
            }
            storeResiduals(b, v[b], var[b], nv[b]);

            if (nEpoch[b] < MIN_EPOCHS_STABLE) continue;

            if (status[b] == UNKNOWN) {
                status[b] = STABLE;
            }
            if (chi2BadCount[b] >= DRIFT_M_EPOCHS && status[b] == STABLE) {
                log.warn("basestability: baseline[{}] -> DRIFTING, chi2/dof={} (ref={}, exceeded {} epochs)",
                        b, String.format("%.2f", lastChi2Ratio[b]),
                        String.format("%.2f", getChi2Ref(b)), chi2BadCount[b]);
                status[b] = DRIFTING;
                recoverCount[b] = 0;
            } else if (status[b] == DRIFTING) {
                // [Phase B] 恢复条件需闸1 与闸2 同时正常：闸1 只看残差整体水平，
                // 位移造成的残差可能仍落在 χ² 的正常范围内（被模糊度吸收），单看闸1 会漏。
                if (chi2BadCount[b] == 0 && stepBadCount[b] == 0) {
                    recoverCount[b]++;
                    if (recoverCount[b] >= RECOVER_EPOCHS) {
                        log.info("basestability: baseline[{}] DRIFTING -> STABLE (weight {} -> 1.0)",
                                b, String.format("%.1f", weight[b]));
                        status[b] = STABLE;
                        recoverCount[b] = 0;
                        weight[b] = 1.0;
                    }
                } else {
                    recoverCount[b] = 0;
                }
            }

            if (status[b] == DRIFTING) driftingEpochs[b]++;
        }
        reportCommonMode(nBaseline);
        separateCommonMode(nBaseline, u, uSig);
        updateWeights(nBaseline);
    }

    /**
     * [§13.9 Phase B/D] 把闸2 结论接入状态机与降权。
     *
     * <p>本历元闸2 未产出位移（{@link #stepSolved} 为 false：首个历元、同弧段不足、
     * 自洽性检验不通过）时<b>不更新</b>判据计数——这类历元没有位移信息，
     * 既不能算「异常」也不能算「正常」，否则会把恢复计数错算成正常。</p>
     *
     * <p>判据量是<b>物理位移</b>：{@link #physicalStep} 已把 k=2 时的均分效应换算回来；
     * 且取最近 {@link #STEP_MED_WINDOW} 个有效历元的<b>中位数</b>。单历元位移量在
     * 模糊度重初始化/参考星切换处会出现米级伪值（几何反演被残差跳变污染），
     * 若直接用单历元值，一次伪值即可把权重打到上限（实测会把双基线 FIX 率从 91% 打到 0.4%）。
     * 中位数窗口把这类孤立伪值完全滤掉，同时保留真实持续位移（中位数随之一致抬升）。</p>
     *
     * <p>门限 {@code max(3 cm, 3σ)} 的自校准含义与闸1 一致——绝对下限保证健康数据不触发，
     * σ 倍数保证噪声大时不误判。</p>
     *
     * <p><b>已知边界：单历元跳变（基站被一次性搬移）检测不到。</b>闸2 量的是「历元间变化量」，
     * 一次性跳变只在发生的那一个历元有非零位移，其余历元位移量为 0；中位数窗口会把这种
     * 孤立单历元值压回 0。这不是取舍失误而是判据的固有边界——能检出的情形是持续爬升
     * （如标墩沉降），不能检出的是瞬时跳变。瞬时跳变的正确检测手段是闸3 骨架网
     * （{@link BaseSkeletonNetwork}）：它用基站间基线的<b>绝对长度</b>与标定值比对，
     * 不依赖时间差分，天然覆盖一次性跳变。故此处不为此放宽中位数窗口——
     * 放宽的代价是健康数据上的米级伪值直接触发降权（实测 FIX 率 91% → 0.4%）。</p>
     *
     * <p><b>权重只由闸2 驱动</b>：闸1 的 DRIFTING 反映残差整体健康度（可能是 rover 侧问题、
     * 模型失配、周跳未处理），不构成位移证据，因此不参与降权，避免「残差变差 → 降权 →
     * 解更差 → 残差更差」的正反馈。</p>
     *
     * <p>权重 {@code w = (中位位移/门限)^2}，上限 {@link #MAX_WEIGHT}。平方关系对应
     * 「残差按权重成反比衰减」：位移是 2 倍门限时权重降到 1/4。持续成立
     * {@link #STEP_DRIFT_EPOCHS} 个历元后才开始降权，与 DRIFTING 的触发条件一致。</p>
     *
     * <p><b>两条前置约束（缺一不可，否则降权是净损失）</b>：</p>
     * <ol>
     *   <li><b>可归因性</b>：必须能把位移归到具体基站。{@link #separateCommonMode} 在
     *       {@code k=2} 时用均值作公共分量，单台基站位移被均分成 ±d/2 落到两条基线上，
     *       两条基线因此都「看起来」位移了同样的量，无法判断是哪台基站动了。
     *       对健康基站降权比不降权更糟，故 {@code k<3} 一律不降权、不剔除（仅诊断）。
     *       {@code k>=3} 时中位数作公共分量，只有真正位移的那台基站的基线偏离，
     *       可直接归因。</li>
     *   <li><b>存在健康参照</b>：若全部基线同时被判位移，则已没有参照物。
     *       此时对所有基线同等降权只改变协方差的绝对尺度（相对权重不变），
     *       反而把 Qb 放大到 AR 无法固定，是纯损失。故此种历元整体放弃降权并清零计数。</li>
     * </ol>
     */
    private void updateWeights(int nb) {
        if (nb < MIN_BASES_FOR_ATTRIBUTION) {
            // k=2：闸2 只能给出「两基站间基线异常」的联合结论，不可归因。
            // 对健康基站降权会直接破坏它的模糊度固定（实测双基线 FIX 率 91% -> 55%），
            // 故 Phase B/D 在 k<3 时不执行，只保留 Phase A 的诊断日志。
            for (int b = 0; b < nb; b++) weight[b] = 1.0;
            if (!attributionWarned && nb >= 2 && stepSolvedAny(nb)) {
                log.info("basestability: k={} 基站无法归因单台位移（闸2 公共模式分离在 k=2 时"
                        + "把位移均分到两条基线），Phase B 降权 / Phase D 剔除不执行，仅诊断", nb);
                attributionWarned = true;
            }
            return;
        }

        int candidates = 0;
        int healthy = 0;
        double[] cand = new double[nb];
        for (int b = 0; b < nb; b++) {
            cand[b] = 1.0;
            if (status[b] == DISPLACED || status[b] == REJECTED) {
                weight[b] = MAX_WEIGHT;
                candidates++;
                continue;
            }
            if (!stepSolved[b]) {
                weight[b] = 1.0;
                continue;
            }

            double phys = physicalStep(b, nb);
            pushStep(b, phys);
            double medPhys = medianStep(b);
            double thr = Math.max(WEIGHT_STEP_FLOOR_M, STEP_SIGMA_K * lastStepSigma[b]);

            if (phys > thr) {
                stepBadCount[b]++;
            } else if (stepBadCount[b] > 0) {
                stepBadCount[b]--;
            }
            displaceCount[b] = (medPhys > DISPLACE_STEP_M) ? displaceCount[b] + 1 : 0;

            if (stepBadCount[b] >= STEP_DRIFT_EPOCHS) {
                candidates++;
                double k = medPhys / thr;
                cand[b] = Math.min(MAX_WEIGHT, Math.max(1.0, k * k));
            } else {
                healthy++;
            }
        }

        if (candidates > 0 && healthy == 0) {
            // 无健康参照：全部基线都被判位移（rover 侧异常 / 模型失配 / 真实多站位移）。
            // 同等降权不改变相对权重，只放大 Qb 使 AR 失效，故整体放弃。
            for (int b = 0; b < nb; b++) {
                if (status[b] != DISPLACED && status[b] != REJECTED) {
                    stepBadCount[b] = 0;
                    displaceCount[b] = 0;
                    weight[b] = 1.0;
                }
            }
            if (!noReferenceWarned) {
                log.warn("basestability: 全部 {} 条基线均被判位移，无健康参照，Phase B 降权本历元放弃"
                        + "（同等降权只会放大协方差而破坏 AR）", nb);
                noReferenceWarned = true;
            }
            return;
        }
        noReferenceWarned = false;

        for (int b = 0; b < nb; b++) {
            if (status[b] == DISPLACED || status[b] == REJECTED) continue;
            if (cand[b] <= 1.0) {
                weight[b] = 1.0;
                continue;
            }
            if (displaceCount[b] >= DISPLACE_M_EPOCHS) {
                status[b] = DISPLACED;
                weight[b] = MAX_WEIGHT;
                log.error("basestability: baseline[{}] -> DISPLACED, 中位物理位移 {}m 连续 {} 历元"
                                + " -> 该基线将从联合滤波中剔除（Phase D）",
                        b, String.format("%.3f", medianStep(b)), displaceCount[b]);
                continue;
            }
            if (status[b] == STABLE) {
                status[b] = DRIFTING;
                recoverCount[b] = 0;
                log.warn("basestability: baseline[{}] -> DRIFTING (gate2), 中位物理位移 {}m ({}sigma)"
                                + " -> Phase B 降权生效",
                        b, String.format("%.3f", medianStep(b)),
                        String.format("%.1f", lastStepSigma[b] > 0
                                ? medianStep(b) / lastStepSigma[b] : 0.0));
            }
            driftStepEpochs[b]++;
            weight[b] = cand[b];
        }
    }

    private boolean stepSolvedAny(int nb) {
        for (int b = 0; b < nb; b++) if (stepSolved[b]) return true;
        return false;
    }

    /** 把本历元物理位移压入中位数窗口（仅保留最近 {@link #STEP_MED_WINDOW} 个有效历元）。 */
    private void pushStep(int b, double phys) {
        stepMed[b][stepMedPos[b]] = phys;
        stepMedPos[b] = (stepMedPos[b] + 1) % STEP_MED_WINDOW;
        if (stepMedCount[b] < STEP_MED_WINDOW) stepMedCount[b]++;
    }

    private double medianStep(int b) {
        int n = stepMedCount[b];
        if (n <= 0) return 0.0;
        double[] copy = new double[n];
        System.arraycopy(stepMed[b], 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        return (n % 2 == 1) ? copy[n / 2] : 0.5 * (copy[n / 2 - 1] + copy[n / 2]);
    }

    /**
     * 闸2 的判据量：把公共模式分离后的 {@code |Δδb_b|} 换算为「该基站实际位移」。
     *
     * <p>k=2 时中位数退化为均值，单台基站位移 {@code d} 被均分为 {@code ±d/2} 分给两条基线，
     * 故需乘 2 才能得到物理位移。k>=3 时中位数不被单点污染，直接可用。
     * 这一换算必须做，否则 k=2 的门限实际收紧一倍（见 {@link #getStep(int)} 的说明）。</p>
     */
    private double physicalStep(int b, int nb) {
        return (nb == 2) ? 2.0 * lastStep[b] : lastStep[b];
    }

    /**
     * 闸1 结论输出：判定异常归属「共用 rover 侧」还是「某台基站」。
     *
     * <p>rover 坐标在联合滤波中所有基线共享，因此 rover 侧异常（位置跳变、观测粗差、
     * 周跳未处理）会让**所有基线同时** χ² 超限；而单台基站位移只污染它自己那条基线。
     * 据此把诊断结论直接写进日志，避免调试时把「共用 rover 问题」误判成「某台基站坏了」。</p>
     *
     * <p>仅在结论发生切换时输出（上升沿），避免逐历元刷屏。</p>
     */
    private void reportCommonMode(int nb) {
        int nDrift = 0;
        int nKnown = 0;
        int driftBase = -1;
        double worst = 0.0;
        for (int b = 0; b < nb; b++) {
            if (status[b] == UNKNOWN) continue;
            nKnown++;
            if (status[b] == DRIFTING) {
                nDrift++;
                driftBase = b;
                worst = Math.max(worst, lastChi2Ratio[b]);
            }
        }
        int mode = MODE_NONE;
        if (nKnown >= 2 && nDrift >= nKnown) {
            mode = MODE_COMMON;
        } else if (nDrift == 1 && nKnown >= 2) {
            mode = MODE_SINGLE;
        }
        if (mode == MODE_COMMON) commonModeEpochs++;
        if (mode != conclusionMode || (mode == MODE_SINGLE && driftBase != conclusionBaseline)) {
            if (mode == MODE_COMMON) {
                log.warn("basestability: COMMON-MODE 异常，全部 {} 条基线同时 DRIFTING (worst chi2/dof={})"
                        + " -> 指向共用的 rover 坐标/rover 观测，而非某台基站单独异常", nKnown,
                        String.format("%.2f", worst));
            } else if (mode == MODE_SINGLE) {
                log.warn("basestability: SINGLE-BASE 异常，仅 baseline[{}] DRIFTING (chi2/dof={})"
                        + " -> 指向该基站自身（坐标/观测/多路径），其余基线正常", driftBase,
                        String.format("%.2f", worst));
            } else {
                log.info("basestability: 异常解除，全部 {} 条基线恢复非 DRIFTING", nKnown);
            }
        }
        conclusionMode = mode;
        conclusionBaseline = driftBase;
    }

    /**
     * 闸2 第二步：多基线公共模式分离，把「rover 侧」与「基站侧」位移分开。
     *
     * <p>时间差分反演得到 {@code u_b = Δδrr - Δδb_b}，其中 {@code Δδrr} 是 rover 先验位置
     * 误差的历元变化量。rover 坐标在联合滤波中是**所有基线共享**的，故 rover 侧跳变会让
     * 每条基线的 {@code u_b} 呈现**同一个**向量（公共模式）；而某台基站位移只进入它自己的
     * 基线。因此取各基线 {@code u_b} 的分量中位数作为公共分量 {@code Δδrr} 的估计，
     * 令 {@code Δδb_b = Δδrr - u_b} 即得该基线的位移。</p>
     *
     * <p>这正是联合解算相对单基线解算的独有优势：单基线时 {@code Δδrr} 与 {@code Δδb}
     * 几何系数完全相同、不可分离，闸2 只能给出 {@code |u|} 作为位移上界（本方法在该情形下
     * 输出 0 并在 {@link #getUnidentifiableEpochs()} 计数）。两条基线时中位数退化为均值，
     * 位移量被均分到两条基线并呈相反符号，故判据量减半但仍可检出。</p>
     */
    private void separateCommonMode(int nb, double[][] u, double[][] uSig) {
        int cnt = 0;
        for (int b = 0; b < nb; b++) if (u[b] != null) cnt++;
        if (cnt < 2) {
            // 可分离性不足：不输出位移，避免把 rover 侧跳变误报为基站位移。
            for (int b = 0; b < nb; b++) {
                if (u[b] != null) unidentifiableEpochs[b]++;
            }
            return;
        }
        double[] common = new double[3];
        for (int k = 0; k < 3; k++) {
            double[] tmp = new double[cnt];
            int idx = 0;
            for (int b = 0; b < nb; b++) if (u[b] != null) tmp[idx++] = u[b][k];
            java.util.Arrays.sort(tmp);
            common[k] = (cnt % 2 == 1) ? tmp[cnt / 2] : 0.5 * (tmp[cnt / 2 - 1] + tmp[cnt / 2]);
        }
        for (int b = 0; b < nb; b++) {
            if (u[b] == null) continue;
            double d2 = 0.0;
            double v2 = 0.0;
            for (int k = 0; k < 3; k++) {
                double d = common[k] - u[b][k];
                d2 += d * d;
                v2 += uSig[b][k] * uSig[b][k];
            }
            lastStep[b] = Math.sqrt(d2);
            lastStepSigma[b] = Math.sqrt(v2);
            stepSolved[b] = true;
            stepSamples[b]++;
            if (lastStep[b] > stepMax[b]) stepMax[b] = lastStep[b];
            stepRing[b][stepRingPos[b]] = lastStep[b];
            stepRingPos[b] = (stepRingPos[b] + 1) % STEP_RING_SIZE;
            if (stepRingN[b] < STEP_RING_SIZE) stepRingN[b]++;
            // 闸2 结论由 updateWeights 接入状态机与降权（Phase B/D，见 §13.9）。
            // 双重门槛：既要超绝对下限，也要超 3σ 合成噪声，避免把反演噪声当位移。
            if (lastStep[b] > STEP_LOG_M && lastStep[b] > STEP_SIGMA_K * lastStepSigma[b]
                    && chi2BadCount[b] == 0) {
                log.debug("basestability: baseline[{}] step |dDb|={}m ({}sigma, diagnostic only)",
                        b, String.format("%.3f", lastStep[b]),
                        String.format("%.1f", lastStepSigma[b] > 0
                                ? lastStep[b] / lastStepSigma[b] : 0.0));
            }
        }
    }

    /**
     * 闸1 自校准判据：当前 χ²/dof 是否相对本基线近期水平异常。
     *
     * <p>参考水平取窗口内**中位数**：坏历元（即使连续）最多占窗口一半，中位数仍锚定在健康水平，
     * 因此不会像均值/EMA 那样被持续异常段"同化"而失去检测能力。</p>
     *
     * <p>本方法在判定后把当前值写入窗口，故参考水平滞后一个历元，属预期行为。</p>
     */
    private boolean isChi2Abnormal(int b, double ratio) {
        int n = chi2HistCount[b];
        boolean abnormal = false;
        if (n >= CHI2_REF_MIN_SAMPLES) {
            double[] hist = chi2Hist[b];
            double[] copy = new double[n];
            System.arraycopy(hist, 0, copy, 0, n);
            java.util.Arrays.sort(copy);
            double ref = (n % 2 == 1) ? copy[n / 2] : 0.5 * (copy[n / 2 - 1] + copy[n / 2]);
            double thr = Math.max(CHI2_ABS_MIN, CHI2_REF_FACTOR * ref);
            abnormal = ratio > thr;
        }
        double[] hist = chi2Hist[b];
        hist[chi2HistPos[b]] = ratio;
        chi2HistPos[b] = (chi2HistPos[b] + 1) % CHI2_REF_WINDOW;
        if (n < CHI2_REF_WINDOW) chi2HistCount[b] = n + 1;
        return abnormal;
    }

    /**
     * 闸2：同弧段时间差分反演 {@code Δδb}。
     *
     * <p>只保留「本历元与上一历元都存在同一弧段」的载波观测，令
     * {@code dv_j = v_j(t) - v_j(t-1) = (e_j - e_ref)^T · Δδb + Δ(模糊度误差)}。
     * 同一弧段内模糊度误差近似不变，故 {@code Δ(模糊度误差) ≈ 0}，
     * 剩下的 {@code Δδb} 才是真实位移；这也是判据用差分而非绝对值的原因。</p>
     *
     * <p>但 DD 残差对 rover 先验位置误差 {@code δrr} 与基站位移 {@code δb} 的几何系数完全相同：
     * {@code v_j = a_jᵀ(δrr - δb) + δN}，单基线时两者不可分离。因此本方法只输出
     * {@code u = Δδrr - Δδb}，由 {@link #separateCommonMode} 用多基线公共模式把它分开。</p>
     *
     * <p>权重 {@code 1/(var_t + var_{t-1})}。解算前先做稳健剔除：门限取
     * {@code max(2 cm, 5×dv 中位数)}。正常历元 dv 只有毫米级，而模糊度重初始化会让个别
     * 观测的 dv 跳到米级；若改用几何 σ 做后验剔除，坏观测会把解拉偏，
     * 反而把好观测全剔掉（实测教训）。解算后再做自洽性检验，见 {@link #COHERENCE_RMS_M}。</p>
     */
    private boolean solveDeltaDisplacement(int b, BaselineObs bo, double[] v, int[] vflg,
                                           double[] var, int nv, double[] outD, double[] outSigma) {
        int[] pf = lastVflg[b];
        double[] pv = lastResV[b];
        double[] pvar = lastResVar[b];
        int pn = lastResN[b];
        if (pf == null || pv == null || pvar == null || pn <= 0) return false;
        gate2Attempts[b]++;

        int m = 0;
        double[] dv = new double[nv];
        double[] dw = new double[nv];
        double[] da = new double[nv * 3];
        for (int i = 0; i < nv; i++) {
            if (((vflg[i] >> 4) & 1) != 0) continue;
            int j = findFlag(pf, pn, vflg[i]);
            if (j < 0) continue;
            int satRef = (vflg[i] >> 16) & 0xFF;
            int satJ = (vflg[i] >> 8) & 0xFF;
            int iRef = findSat(bo, satRef);
            int iJ = findSat(bo, satJ);
            if (iRef < 0 || iJ < 0) continue;
            int iuRef = bo.iu[iRef];
            int iuJ = bo.iu[iJ];
            if (iuRef < 0 || iuJ < 0) continue;
            double r = var[i] + pvar[j];
            if (r <= 0.0) continue;
            dv[m] = v[i] - pv[j];
            dw[m] = 1.0 / r;
            for (int k = 0; k < 3; k++) {
                da[m * 3 + k] = bo.e[k + iuJ * 3] - bo.e[k + iuRef * 3];
            }
            m++;
        }
        if (m < 4) return false;

        // 稳健剔除：同一弧段的模糊度偶尔会整体跳变（周跳/重初始化），此时个别观测的 dv 达米级，
        // 而正常 dv 只有毫米级（中位数约 4 mm，形式几何 σ 约 16 mm 高估 2.5 倍）。
        // 故门限取 dv 自身中位数的倍数，而不是形式 σ 的倍数；也不用 MAD——本数据上
        // 5×MAD 约 33 mm 比 5×median 的 22 mm 更松，且小样本（m 可到 4）下两次中位数方差更大。
        // 代价是真实大位移会抬高门限而可能漏剔个别粗差，漏剔由 COHERENCE_RMS_M 兜底（整历元放弃）。
        double[] absDv = new double[m];
        for (int i = 0; i < m; i++) absDv[i] = Math.abs(dv[i]);
        double[] sorted = absDv.clone();
        java.util.Arrays.sort(sorted);
        double medAbsDv = sorted[m / 2];
        double gate = Math.max(DV_GATE_M, DV_GATE_K * medAbsDv);
        int kept = 0;
        for (int i = 0; i < m; i++) {
            if (absDv[i] > gate) {
                dw[i] = 0.0;
            } else {
                kept++;
            }
        }
        if (kept < 4) return false;
        boolean ok = solve3x3(dv, dw, da, m, outD, outSigma);
        if (!ok) return false;

        // 自洽性判据：真实位移必然能被几何完全解释（dv_j = a_jᵀ·u），后验残差只有毫米级；
        // 而整历元模糊度重置会让所有弧段的 dv 同时跳米级，这是几何无法解释的公共模式，
        // 后验残差必然很大。此时该历元整体不可信，直接放弃（不报阶跃）。
        // 注意：检验必须用扣除 rover 位移之前的解 u，否则残差会被 Δrr 污染。
        double post = 0.0;
        int postN = 0;
        for (int i = 0; i < m; i++) {
            if (dw[i] <= 0.0) continue;
            double e = dv[i] - (da[i * 3] * outD[0] + da[i * 3 + 1] * outD[1] + da[i * 3 + 2] * outD[2]);
            post += e * e;
            postN++;
        }
        double postRms = Math.sqrt(post / Math.max(1, postN));
        if (postRms > COHERENCE_RMS_M) return false;
        return true;
    }

    private boolean solve3x3(double[] dv, double[] dw, double[] da, int m, double[] outD) {
        return solve3x3(dv, dw, da, m, outD, null);
    }

    private boolean solve3x3(double[] dv, double[] dw, double[] da, int m,
                             double[] outD, double[] outSigma) {
        double[] n = new double[9];
        double[] rhs = new double[3];
        int used = 0;
        for (int i = 0; i < m; i++) {
            double w = dw[i];
            if (w <= 0.0) continue;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    n[row * 3 + col] += w * da[i * 3 + row] * da[i * 3 + col];
                }
                rhs[row] += w * da[i * 3 + row] * dv[i];
            }
            used++;
        }
        if (used < 4) return false;

        double det = n[0] * (n[4] * n[8] - n[5] * n[7])
                - n[1] * (n[3] * n[8] - n[5] * n[6])
                + n[2] * (n[3] * n[7] - n[4] * n[6]);
        if (Math.abs(det) < 1e-12) return false;
        double inv = 1.0 / det;
        double[] q = new double[9];
        q[0] = (n[4] * n[8] - n[5] * n[7]) * inv;
        q[1] = (n[2] * n[7] - n[1] * n[8]) * inv;
        q[2] = (n[1] * n[5] - n[2] * n[4]) * inv;
        q[3] = (n[5] * n[6] - n[3] * n[8]) * inv;
        q[4] = (n[0] * n[8] - n[2] * n[6]) * inv;
        q[5] = (n[2] * n[3] - n[0] * n[5]) * inv;
        q[6] = (n[3] * n[7] - n[4] * n[6]) * inv;
        q[7] = (n[1] * n[6] - n[0] * n[7]) * inv;
        q[8] = (n[0] * n[4] - n[1] * n[3]) * inv;

        for (int row = 0; row < 3; row++) {
            outD[row] = q[row * 3] * rhs[0] + q[row * 3 + 1] * rhs[1] + q[row * 3 + 2] * rhs[2];
            if (outSigma != null) {
                double diag = q[row * 3 + row];
                outSigma[row] = diag > 0 ? Math.sqrt(diag) : 0.0;
            }
        }
        return true;
    }

    private static int findFlag(int[] flg, int n, int target) {
        for (int j = 0; j < n; j++) {
            if (flg[j] == target) return j;
        }
        return -1;
    }

    private void storeResiduals(int b, double[] v, double[] var, int nv) {
        double[] cv = new double[nv];
        double[] cvar = new double[nv];
        System.arraycopy(v, 0, cv, 0, nv);
        System.arraycopy(var, 0, cvar, 0, nv);
        lastResV[b] = cv;
        lastResVar[b] = cvar;
        lastResN[b] = nv;
    }

    /**
     * 记录本历元残差标志集合并判断是否为「模糊度重置历元」。
     *
     * <p>残差标志 {@code (satRef<<16)|(satJ<<8)|(code<<4)|frq} 唯一确定一条观测方程。
     * 若本历元的标志集合与上一历元不完全一致（有新增或丢失），说明卫星弧段发生了变化：
     * 新弧段的模糊度是新初始化的，{@code δb} 的反演结果会整体平移，
     * 该历元的 {@code Δδb} 是模糊度偏置换段而非基站位移。</p>
     *
     * <p>用集合比对而非长度比对：换星（丢一颗同时升一颗）时长度不变但模糊度全换，
     * 只比长度会漏判。</p>
     */
    private boolean updateFlagsAndDetectReset(int b, int[] flg, int n) {
        int[] prev = lastVflg[b];
        int[] cur = new int[n];
        System.arraycopy(flg, 0, cur, 0, n);
        lastVflg[b] = cur;
        if (prev == null) return true;
        if (prev.length != n) return true;
        boolean[] used = new boolean[prev.length];
        for (int i = 0; i < n; i++) {
            boolean found = false;
            for (int j = 0; j < prev.length; j++) {
                if (!used[j] && prev[j] == cur[i]) {
                    used[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return true;
        }
        return false;
    }

    /**
     * 闸2 的绝对值形式：由载波双差残差最小二乘反演 {@code δb}（仅诊断，见 §0.7 为何不用它判位移）。
     *
     * <p>观测方程 {@code v_j = (e_j - e_ref)^T · δb}，权 {@code w = 1/var}。
     * 只取载波残差（伪距残差精度不足）。</p>
     */
    private boolean solveDisplacement(BaselineObs bo, double[] v, int[] vflg, double[] var,
                                      int nv, double[] outDb, double[] outSigma) {
        int m = 0;
        double[] dv = new double[nv];
        double[] dw = new double[nv];
        double[] da = new double[nv * 3];
        for (int i = 0; i < nv; i++) {
            if (((vflg[i] >> 4) & 1) != 0) continue;
            int satRef = (vflg[i] >> 16) & 0xFF;
            int satJ = (vflg[i] >> 8) & 0xFF;
            int iRef = findSat(bo, satRef);
            int iJ = findSat(bo, satJ);
            if (iRef < 0 || iJ < 0) continue;
            int iuRef = bo.iu[iRef];
            int iuJ = bo.iu[iJ];
            if (iuRef < 0 || iuJ < 0) continue;
            double r = var[i];
            if (r <= 0.0) continue;
            dv[m] = v[i];
            dw[m] = 1.0 / r;
            for (int k = 0; k < 3; k++) {
                da[m * 3 + k] = bo.e[k + iuJ * 3] - bo.e[k + iuRef * 3];
            }
            m++;
        }
        return solve3x3(dv, dw, da, m, outDb, outSigma);
    }

    private static int findSat(BaselineObs bo, int sat) {
        for (int j = 0; j < bo.ns; j++) {
            if (bo.sat[j] == sat) return j;
        }
        return -1;
    }

    /** 基站稳定状态：UNKNOWN/STABLE/DRIFTING/DISPLACED/REJECTED。 */
    public int getStatus(int baseline) {
        if (status == null || baseline < 0 || baseline >= status.length) return UNKNOWN;
        return status[baseline];
    }

    /** 最近一次反演的位移量（ECEF，米）；未解出时返回全零。 */
    public double[] getDisplacement(int baseline) {
        if (lastDb == null || baseline < 0 || baseline >= lastDb.length || lastDb[baseline] == null) {
            return new double[3];
        }
        return lastDb[baseline].clone();
    }

    /** 位移量标准差；未解出时返回全零。 */
    public double[] getDisplacementSigma(int baseline) {
        if (lastSigma == null || baseline < 0 || baseline >= lastSigma.length || lastSigma[baseline] == null) {
            return new double[3];
        }
        return lastSigma[baseline].clone();
    }

    /** 最近一次 χ²/dof，用于诊断残差健康度。 */
    public double getChi2Ratio(int baseline) {
        if (lastChi2Ratio == null || baseline < 0 || baseline >= lastChi2Ratio.length) return 0.0;
        return lastChi2Ratio[baseline];
    }

    /** 闸1 的自校准参考水平（本基线近 {@link #CHI2_REF_WINDOW} 历元 χ²/dof 中位数）；样本不足时返回 0。 */
    public double getChi2Ref(int baseline) {
        if (chi2Hist == null || baseline < 0 || baseline >= chi2Hist.length) return 0.0;
        int n = chi2HistCount[baseline];
        if (n <= 0) return 0.0;
        double[] copy = new double[n];
        System.arraycopy(chi2Hist[baseline], 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        return (n % 2 == 1) ? copy[n / 2] : 0.5 * (copy[n / 2 - 1] + copy[n / 2]);
    }

    /** 已累计的有效历元数（含 UNKNOWN 阶段）。 */
    public int getEpochCount(int baseline) {
        if (nEpoch == null || baseline < 0 || baseline >= nEpoch.length) return 0;
        return nEpoch[baseline];
    }

    /** 处于 DRIFTING 状态的历元累计数；用于回归测试量化基线健康度。 */
    public int getDriftingEpochs(int baseline) {
        if (driftingEpochs == null || baseline < 0 || baseline >= driftingEpochs.length) return 0;
        return driftingEpochs[baseline];
    }

    /** 判定为「模糊度重置」的历元累计数；用于评估闸2 可用样本占比。 */
    public int getResetEpochs(int baseline) {
        if (resetEpochs == null || baseline < 0 || baseline >= resetEpochs.length) return 0;
        return resetEpochs[baseline];
    }

    /**
     * 因基线数不足以分离 rover/基站位移而无法输出闸2 结果的历元累计数。
     *
     * <p>单基线（或该历元仅一条基线有解）时，{@code Δδrr} 与 {@code Δδb} 几何不可分离，
     * 闸2 输出 0 并在此计数，避免把 rover 侧跳变误报为基站位移。</p>
     */
    public int getUnidentifiableEpochs(int baseline) {
        if (unidentifiableEpochs == null || baseline < 0 || baseline >= unidentifiableEpochs.length) return 0;
        return unidentifiableEpochs[baseline];
    }

    /**
     * 全部基线同时 DRIFTING 的历元累计数。
     *
     * <p>该计数大于 0 说明异常是**公共模式**的（共用 rover 坐标/观测），
     * 而不是某台基站单独异常；这是调试「FIX=0%」时区分责任方的关键量。</p>
     */
    public int getCommonModeEpochs() {
        return commonModeEpochs;
    }

    /**
     * 最近一次闸2 单历元阶跃量 {@code |Δδb_b|}（米），**已做公共模式分离**。
     *
     * <p><b>语义必须明确</b>：返回的是分离后的该基线位移模长 {@code |Δδb_b| = |common - u_b|}，
     * 既不是反演中间量 {@code u_b}，也不是绝对值反演的 {@code δb}。
     * 下列情形返回 0：基线数不足 2（不可分离，见 {@link #getUnidentifiableEpochs()}）、
     * 上一历元无可比同弧段残差（首个历元）、稳健剔除后可用观测不足 4 个、
     * 或自洽性检验不通过（见 {@link #COHERENCE_RMS_M}）。</p>
     *
     * <p><b>与物理位移的换算</b>：两条基线时中位数退化为均值，某台基站位移 {@code d}
     * 会被均分成 {@code ±d/2} 分给两条基线。故<b>实际位移约为 {@code 2×getStep()} </b>；
     * Phase B 设降权门限时必须按「实际位移 = 2×本值」换算，否则门限会实际收紧一半。</p>
     */
    public double getStep(int baseline) {
        if (lastStep == null || baseline < 0 || baseline >= lastStep.length) return 0.0;
        return lastStep[baseline];
    }

    /** 最近一次闸2 单历元阶跃的合成 σ（米）；重置历元或未解出时为 0。 */
    public double getStepSigma(int baseline) {
        if (lastStepSigma == null || baseline < 0 || baseline >= lastStepSigma.length) return 0.0;
        return lastStepSigma[baseline];
    }

    /** 闸2 成功产出位移的历元累计数（{@code getStep()} 有非零值的历元数）。 */
    public int getStepSamples(int baseline) {
        if (stepSamples == null || baseline < 0 || baseline >= stepSamples.length) return 0;
        return stepSamples[baseline];
    }

    /** 闸2 进入解算的历元累计数（具备可比上一历元同弧段残差）。 */
    public int getGate2Attempts(int baseline) {
        if (gate2Attempts == null || baseline < 0 || baseline >= gate2Attempts.length) return 0;
        return gate2Attempts[baseline];
    }

    /**
     * [Phase B] 该基线当前应使用的观测协方差膨胀系数（1.0 = 不降权）。
     *
     * <p>语义：调用方把该基线的观测协方差 {@code R_b} 整体乘以本返回值（等效权重
     * {@code 1/w}）。取值由闸2 的物理位移与自校准门限之比决定，见 {@link #updateWeights}。
     * 返回 {@link #MAX_WEIGHT} 表示该基线已 DISPLACED，实际应剔除而非仅降权。</p>
     */
    public double getWeight(int baseline) {
        if (weight == null || baseline < 0 || baseline >= weight.length) return 1.0;
        return weight[baseline];
    }

    /** [Phase B] 闸2 连续超门限历元数；大于 0 说明位移判据正在持续成立。 */
    public int getStepBadCount(int baseline) {
        if (stepBadCount == null || baseline < 0 || baseline >= stepBadCount.length) return 0;
        return stepBadCount[baseline];
    }

    /** [Phase B] 由闸2 驱动的 DRIFTING 历元累计数（与闸1 驱动的 {@link #getDriftingEpochs} 分开）。 */
    public int getDriftStepEpochs(int baseline) {
        if (driftStepEpochs == null || baseline < 0 || baseline >= driftStepEpochs.length) return 0;
        return driftStepEpochs[baseline];
    }

    /** [Phase D] 该基线是否已判定 DISPLACED（应被剔除）。 */
    public boolean isDisplaced(int baseline) {
        int s = getStatus(baseline);
        return s == DISPLACED || s == REJECTED;
    }

    /** [Phase D] 是否存在任一已 DISPLACED 的基线。 */
    public boolean hasDisplaced() {
        if (status == null) return false;
        for (int b = 0; b < status.length; b++) {
            if (status[b] == DISPLACED || status[b] == REJECTED) return true;
        }
        return false;
    }

    /**
     * 闸2 历元放弃率（0~1）：{@code 1 - stepSamples/attempts}。
     *
     * <p>该量衡量闸2 的可用样本占比：健康小时应接近 0；若偏高说明残差集合频繁变化
     * （周跳/参考星切换/AR 状态切换），Phase B 降权会因此失去足够的观测支撑。</p>
     */
    public double getGate2RejectRatio(int baseline) {
        int att = getGate2Attempts(baseline);
        if (att <= 0) return 0.0;
        return 1.0 - (double) getStepSamples(baseline) / att;
    }

    /** 闸2 历史最大阶跃量 {@code |Δδb_b|}（米）。 */
    public double getStepMax(int baseline) {
        if (stepMax == null || baseline < 0 || baseline >= stepMax.length) return 0.0;
        return stepMax[baseline];
    }

    /**
     * 闸2 阶跃量的**无条件**分位数（米）。
     *
     * <p>统计对象是全部成功产出位移的历元（{@link #getStepSamples()}），而非仅超过
     * {@link #STEP_LOG_M} 的历元。日志里的分位数是「条件于超门限」的，会系统性高估，
     * 不能直接用于 Phase B 门限标定。</p>
     *
     * @param p 分位点，0~1（如 0.5 为中位数，0.9 为 p90）
     */
    public double getStepPercentile(int baseline, double p) {
        if (stepRing == null || baseline < 0 || baseline >= stepRing.length) return 0.0;
        int n = stepRingN[baseline];
        if (n <= 0) return 0.0;
        double[] copy = new double[n];
        System.arraycopy(stepRing[baseline], 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        int idx = (int) Math.round(p * (n - 1));
        if (idx < 0) idx = 0;
        if (idx >= n) idx = n - 1;
        return copy[idx];
    }
}