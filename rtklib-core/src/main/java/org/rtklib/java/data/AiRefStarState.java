package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * 接口四（参考星风险判别）的跨历元状态。
 *
 * 与 {@link Rtk} 分离持有，避免把实验 provider 的簿记混进解算主状态；
 * 未注入接口四时该对象为 {@code null}，不分配任何数组（I-1 零回归）。
 *
 * 分组键与 {@code ddres} 的参考星择优循环一致：{@code key = m * (nf * 2) + f}，
 * 其中 {@code m ∈ [0,6)} 为系统组、{@code f ∈ [0, nf*2)} 为码/相位频点。
 */
public final class AiRefStarState {

    /** 最大分组数（{@code nf} 取 {@link Constants#NFREQ} 时的上界）。 */
    public static final int MAX_GROUPS = 6 * Constants.NFREQ * 2;

    /** 每个分组禁止再次切换的历元号（{@code epoch < holdUntilEpoch} 时禁止）。 */
    public final int[] holdUntilEpoch = new int[MAX_GROUPS];

    /** 每个分组累计的切换次数（R4-a 的存在性口径）。 */
    public final int[] switchCount = new int[MAX_GROUPS];

    /** 每个分组累计的「有参考星」历元数（切换率的分母）。 */
    public final int[] eligibleEpochs = new int[MAX_GROUPS];

    /** 每个分组是否已因切换率超限而停止干预（{@code AI_REFSTAR_RATE_STOP}）。 */
    public final boolean[] rateStopped = new boolean[MAX_GROUPS];

    /** 窗口长度 W（与 {@code AiOpts.refStarWindowEpochs} 一致，仅用于数组边界）。 */
    public final int window;

    /**
     * 参考星历史环形缓冲：{@code refHist[satIndex][slot]} 是该历元该星「作为参考星的频点位掩码」。
     * 位 {@code f} 置位表示该星在该频点 {@code f} 上是参考星。
     */
    public final int[][] refHist;

    /** 环形缓冲写入位置（每历元前进一格）。 */
    public int refHistPos;

    /** 已写入的历元数（{@code <= window}），用于窗口内求均值/标准差与计数。 */
    public int refHistCount;

    /** 主频点流动站 SNR 环形缓冲，按卫星下标存储。 */
    public final double[][] snrHist;

    /** 每颗星已写入的 SNR 样本数（{@code <= window}）。 */
    public final int[] snrHistCount;

    /** SNR 环形缓冲写入位置（每历元前进一格，全星共用）。 */
    public int snrHistPos;

    /** 上一次推理的观测时刻（秒），用于求历元采样间隔。 */
    public double prevObsSec;

    /** 降级告警去重（每会话一次，避免逐历元刷屏）。 */
    public boolean degradeLogged;

    /** 切换率超限告警去重。 */
    public boolean rateStopLogged;

    public AiRefStarState(int window) {
        this.window = Math.max(1, window);
        this.refHist = new int[Constants.MAXSAT][this.window];
        this.snrHist = new double[Constants.MAXSAT][this.window];
        this.snrHistCount = new int[Constants.MAXSAT];
    }
}