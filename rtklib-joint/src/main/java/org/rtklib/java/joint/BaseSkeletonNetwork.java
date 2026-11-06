package org.rtklib.java.joint;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.rtkpos.RtkCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [FIX-ALG-042 §13.9 Phase C] 闸3：基站间基线骨架网。
 *
 * <p><b>为什么需要闸3</b>：闸1/闸2 都基于 rover 的双差残差，rover 侧异常（位置跳变、
 * 观测粗差、整历元模糊度重置）会同时污染所有基线，只能靠「公共模式分离」间接排除。
 * 基站间基线（base-base）不含 rover 观测，其长度变化是<b>纯粹</b>的基站位移观测量，
 * 因此可作为独立佐证：rover 无解（{@code totalNs=0}）时闸3 依然工作。</p>
 *
 * <p><b>实现方式</b>：对每对基站 {@code (i,j)} 维护一个独立的静态 {@link Rtk} 滤波器
 * （{@code PMODE_STATIC}、{@code dynamics=0}），把基站 j 的观测当作 rover、
 * 基站 i 的坐标当作已知基站，调用 {@link RtkCore#rtkpos} 解出 j 相对 i 的位置。
 * 只用 FIX 解的基线长度，参考长度由前 {@link #REF_SAMPLES} 个 FIX 解的中位数确定，
 * 判据为 {@code |Δlen| > max(5 cm, 3σ)}（σ 取静态滤波器位置协方差）。</p>
 *
 * <p><b>归属逻辑</b>：{@code k>=3} 时单台基站位移会污染它参与的全部 {@code k-1} 对，
 * 而正常基站只污染 1 对，故「异常对数 >= 2」即可定位到具体基站；
 * {@code k=2} 时只有 1 对，只能给出「两基站间基线异常」的联合结论（不可定位）。</p>
 *
 * <p><b>本阶段定位</b>：闸3 输出为<b>诊断与佐证</b>，不直接驱动剔除。
 * 剔除（Phase D）由闸2 的逐历元证据驱动，闸3 结论用于确认「位移确实发生在基站侧」
 * 而非 rover 侧。原因是静态骨架解的采样率低（默认 60 历元一次），
 * 单次结论的时延不足以支撑及时的剔除动作。</p>
 */
public class BaseSkeletonNetwork implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(BaseSkeletonNetwork.class);

    /** 判定基站间基线长度异常的绝对下限（米）。 */
    private static final double SKEL_STEP_M = 0.05;
    /** 判定显著性的 σ 倍数（静态解收敛后 σ 通常为毫米级，实际由绝对下限兜底）。 */
    private static final double SKEL_SIGMA_K = 3.0;
    /** 连续异常次数达到该值才计为一对「异常基线」。 */
    private static final int SKEL_BAD_COUNT = 3;
    /** 参考长度标定所用的 FIX 解样本数。 */
    private static final int REF_SAMPLES = 10;
    /** 基站坐标变化超过该值（米）视为坐标被修正，需重建滤波器。 */
    private static final double COORD_CHANGE_M = 1E-3;

    /** 单对基站（i < j）的骨架解算状态。 */
    private static final class Pair implements Serializable {
        private static final long serialVersionUID = 1L;

        final int i;
        final int j;
        Rtk rtk;
        final double[] refRbI = new double[3];
        final double[] refRbJ = new double[3];

        final double[] refSamples = new double[REF_SAMPLES];
        int refCount;
        double refLen;

        double lastLen;
        double lastChange;
        double lastSigma;
        double maxChange;
        int badCount;
        int samples;
        int fixSamples;
        int attempts;

        Pair(int i, int j) {
            this.i = i;
            this.j = j;
        }
    }

    private final Map<Long, Pair> pairs = new LinkedHashMap<>();
    /** 各基站是否被闸3 判为「骨架网异常」（诊断结论，不驱动剔除）。 */
    private boolean[] suspect;
    /** 最近一次结论（避免逐次刷屏）。 */
    private boolean[] lastSuspect;

    /**
     * 每历元调用（内部按 {@link JointConfig#baseSkeletonInterval} 控制执行频率）。
     *
     * @param jrtk     联合解算状态（只读取 opt/jointConfig/epoch 与剔除标志，不回写）
     * @param bases    基站数组（使用其坐标与观测缓冲）
     * @param nBaseline 基站数
     * @param nav      星历
     */
    public void update(JointRtk jrtk, BaseStation[] bases, int nBaseline, Nav nav) {
        if (jrtk == null || bases == null || nBaseline < 2) return;
        int interval = (jrtk.jointConfig != null) ? Math.max(1, jrtk.jointConfig.baseSkeletonInterval) : 1;
        if (interval > 1 && jrtk.epoch % interval != 0) return;

        for (int i = 0; i < nBaseline; i++) {
            for (int j = i + 1; j < nBaseline; j++) {
                BaseStation bi = bases[i];
                BaseStation bj = bases[j];
                if (bi == null || bj == null) continue;
                if (jrtk.isBaselineExcluded(i) || jrtk.isBaselineExcluded(j)) continue;
                if (bi.obsBuf == null || bi.obsCount <= 0) continue;
                if (bj.obsBuf == null || bj.obsCount <= 0) continue;

                Pair p = ensurePair(jrtk, i, j, bi, bj);
                solve(jrtk, p, bi, bj, nav);
            }
        }
        updateSuspect(nBaseline);
    }

    private Pair ensurePair(JointRtk jrtk, int i, int j, BaseStation bi, BaseStation bj) {
        long key = ((long) i << 32) | j;
        Pair p = pairs.get(key);
        boolean coordChanged = false;
        if (p != null) {
            for (int k = 0; k < 3; k++) {
                if (Math.abs(p.refRbI[k] - bi.rb[k]) > COORD_CHANGE_M
                        || Math.abs(p.refRbJ[k] - bj.rb[k]) > COORD_CHANGE_M) {
                    coordChanged = true;
                    break;
                }
            }
        }
        if (p == null || coordChanged) {
            p = new Pair(i, j);
            System.arraycopy(bi.rb, 0, p.refRbI, 0, 3);
            System.arraycopy(bj.rb, 0, p.refRbJ, 0, 3);
            p.rtk = createRtk(jrtk.opt, bi.rb);
            pairs.put(key, p);
            if (coordChanged) {
                log.info("basestability: 基站坐标变化，重建骨架基线 pair({},{}) 的滤波器", i, j);
            }
        }
        return p;
    }

    /**
     * 为「基站 j 相对基站 i」建立静态滤波器：基站 i 作为已知坐标（rb）。
     *
     * <p>除模式与动态模型外，其余配置（{@code navsys}/{@code nf}/{@code ionoopt}/
     * {@code tropopt}/{@code modear} 等）沿用联合解算的 {@link PrcOpt}，
     * 保证骨架解与主解使用同一套观测模型（否则两条链路的天线/频率/系统配置可能不一致）。</p>
     */
    private Rtk createRtk(PrcOpt template, double[] rbBase) {
        Rtk rtk = new Rtk();
        PrcOpt opt = (template != null) ? new PrcOpt(template) : new PrcOpt(rtk.opt);
        opt.mode = Constants.PMODE_STATIC;
        opt.dynamics = 0;
        opt.modear = Constants.ARMODE_CONT;
        opt.rb = rbBase.clone();
        rtk.opt = opt;
        for (int k = 0; k < 3; k++) rtk.rb[k] = rbBase[k];
        return rtk;
    }

    private void solve(JointRtk jrtk, Pair p, BaseStation bi, BaseStation bj, Nav nav) {
        p.attempts++;

        int nu = Math.min(bj.obsCount, Constants.MAXOBS);
        int nr = Math.min(bi.obsCount, Constants.MAXOBS - nu);
        if (nr <= 0) return;

        // 基站观测的 rcv 均为 2（EpochAligner 依赖该标记），骨架解要求 rover 段 rcv=1，
        // 故对 rover 侧做深拷贝后改写 rcv，绝不改动共享的 BaseStation 观测缓冲。
        Obsd[] merged = new Obsd[nu + nr];
        for (int k = 0; k < nu; k++) {
            Obsd c = new Obsd(bj.obsBuf[k]);
            c.rcv = 1;
            merged[k] = c;
        }
        System.arraycopy(bi.obsBuf, 0, merged, nu, nr);

        int ret;
        try {
            ret = RtkCore.rtkpos(p.rtk, merged, nu + nr, nav);
        } catch (RuntimeException e) {
            log.warn("basestability: 骨架基线 pair({},{}) 解算异常: {}", p.i, p.j, e.getMessage());
            return;
        }
        int stat = p.rtk.sol.stat;
        if (ret == 0 || (stat != Constants.SOLQ_FIX && stat != Constants.SOLQ_FLOAT)) return;
        if (stat == Constants.SOLQ_FIX) p.fixSamples++;

        double[] dr = new double[3];
        for (int k = 0; k < 3; k++) dr[k] = p.rtk.sol.rr[k] - bi.rb[k];
        double len = RtklibCommon.norm(dr, 3);
        p.lastLen = len;

        int nx = p.rtk.nx;
        double sigma = 0.0;
        if (nx > 0 && p.rtk.P != null && p.rtk.P.length >= nx * nx) {
            sigma = Math.sqrt((p.rtk.P[0] + p.rtk.P[nx + 1] + p.rtk.P[2 * nx + 2]) / 3.0);
        }
        p.lastSigma = sigma;

        if (p.refCount < REF_SAMPLES) {
            // 参考长度只用 FIX 解标定：FLOAT 解尚未收敛，其基线长度含厘米级偏差。
            if (stat != Constants.SOLQ_FIX) return;
            p.refSamples[p.refCount++] = len;
            p.refLen = median(p.refSamples, p.refCount);
            return;
        }

        double change = len - p.refLen;
        p.lastChange = change;
        p.samples++;
        if (p.samples % 10 == 0) {
            log.info("DBG skeleton pair({},{}) #{} len={} ref={} change={} sigma={} ns={} nx={}",
                    p.i, p.j, p.samples, String.format("%.3f", len), String.format("%.3f", p.refLen),
                    String.format("%.3f", change), String.format("%.4f", sigma),
                    p.rtk.sol.ns, p.rtk.nx);
        }
        if (Math.abs(change) > p.maxChange) p.maxChange = Math.abs(change);

        double thr = Math.max(SKEL_STEP_M, SKEL_SIGMA_K * sigma);
        boolean bad = Math.abs(change) > thr;
        if (bad) {
            p.badCount++;
        } else if (p.badCount > 0) {
            p.badCount--;
        }
        if (bad && p.badCount == SKEL_BAD_COUNT) {
            log.warn("basestability: 骨架基线 pair({},{}) 长度变化 |dL|={}m ({}sigma), refLen={}m",
                    p.i, p.j, String.format("%.3f", Math.abs(change)),
                    String.format("%.1f", sigma > 0 ? Math.abs(change) / sigma : 0.0),
                    String.format("%.3f", p.refLen));
        }
    }

    /**
     * 由各对的异常结论反推具体基站：{@code k>=3} 时异常对数 >= 2 才可定位。
     */
    private void updateSuspect(int nb) {
        int[] badPairs = new int[nb];
        for (Pair p : pairs.values()) {
            if (p.i < nb && p.j < nb && p.badCount >= SKEL_BAD_COUNT) {
                badPairs[p.i]++;
                badPairs[p.j]++;
            }
        }
        if (suspect == null || suspect.length != nb) {
            suspect = new boolean[nb];
            lastSuspect = new boolean[nb];
        }
        for (int b = 0; b < nb; b++) {
            boolean now;
            if (nb == 2) {
                // 只有一对，无法定位到具体基站：给出联合结论。
                now = badPairs[b] >= 1;
            } else {
                // 正常基站最多污染 1 对，位移基站污染 k-1 >= 2 对。
                now = badPairs[b] >= 2;
            }
            suspect[b] = now;
            if (now != lastSuspect[b]) {
                if (now) {
                    if (nb == 2) {
                        log.warn("basestability: 骨架网异常，基站间基线长度变化超门限"
                                + "（k=2 无法定位到具体基站，需增加基站或人工核查）");
                    } else {
                        log.warn("basestability: 骨架网定位到基站[{}] 异常（{}/{} 对基站间基线同时异常）",
                                b, badPairs[b], nb - 1);
                    }
                } else {
                    log.info("basestability: 基站[{}] 骨架网异常解除", b);
                }
            }
            lastSuspect[b] = now;
        }
    }

    private static double median(double[] a, int n) {
        double[] copy = new double[n];
        System.arraycopy(a, 0, copy, 0, n);
        java.util.Arrays.sort(copy);
        return (n % 2 == 1) ? copy[n / 2] : 0.5 * (copy[n / 2 - 1] + copy[n / 2]);
    }

    /** 基站间基线 pair(i,j) 的参考长度（米）；未标定返回 0。 */
    public double getRefLength(int i, int j) {
        Pair p = pairOf(i, j);
        return p == null ? 0.0 : p.refLen;
    }

    /** 基站间基线 pair(i,j) 最近一次 FIX 解的长度（米）；无解返回 0。 */
    public double getLength(int i, int j) {
        Pair p = pairOf(i, j);
        return p == null ? 0.0 : p.lastLen;
    }

    /** 基站间基线 pair(i,j) 最近一次长度变化（米，带符号）。 */
    public double getLengthChange(int i, int j) {
        Pair p = pairOf(i, j);
        return p == null ? 0.0 : p.lastChange;
    }

    /** 基站间基线 pair(i,j) 历史最大长度变化绝对值（米）。 */
    public double getMaxLengthChange(int i, int j) {
        Pair p = pairOf(i, j);
        return p == null ? 0.0 : p.maxChange;
    }

    /** 基站间基线 pair(i,j) 的 FIX 解个数。 */
    public int getFixSamples(int i, int j) {
        Pair p = pairOf(i, j);
        return p == null ? 0 : p.fixSamples;
    }

    /** 基站间基线 pair(i,j) 的解算尝试次数（用于评估可用率）。 */
    public int getAttempts(int i, int j) {
        Pair p = pairOf(i, j);
        return p == null ? 0 : p.attempts;
    }

    /** 闸3 是否把基站 b 判为骨架网异常（诊断结论）。 */
    public boolean isSuspect(int b) {
        return suspect != null && b >= 0 && b < suspect.length && suspect[b];
    }

    /** 闸3 判为异常的基站列表。 */
    public int[] suspectBases() {
        if (suspect == null) return new int[0];
        int n = 0;
        for (int b = 0; b < suspect.length; b++) if (suspect[b]) n++;
        int[] out = new int[n];
        int k = 0;
        for (int b = 0; b < suspect.length; b++) if (suspect[b]) out[k++] = b;
        return out;
    }

    private Pair pairOf(int i, int j) {
        if (i == j) return null;
        int lo = Math.min(i, j);
        int hi = Math.max(i, j);
        return pairs.get(((long) lo << 32) | hi);
    }
}