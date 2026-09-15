package org.rtklib.java.adjust.model;

import org.rtklib.java.data.SolData;

/**
 * 单历元多基线容器。
 *
 * <p>存放同一历元下多条GNSS基线的SolData，仅保留FIX状态的基线。
 * 每条基线包含：基站标识、ECEF基线增量、基线自身协方差、Rover位置协方差。</p>
 */
public class BaselineEpoch {

    /** 历元标识（时间戳） */
    public final String epochTag;

    /** 有效FIX基线列表（最多3条） */
    public final BaselineEntry[] baselines;

    /** 有效基线数量 */
    public final int count;

    /**
     * 单条基线数据。
     */
    public static class BaselineEntry {
        /** 基站标识（A/B/C） */
        public final String baseId;
        /** 原始SolData */
        public final SolData solData;
        /** ECEF基线增量 [dX, dY, dZ] (m) */
        public final double[] dXyz;
        /** 基线自身3x3协方差 (m^2)，行优先存储 */
        public final double[][] cBaseline;
        /** Rover位置3x3协方差 (m^2)，行优先存储 */
        public final double[][] pRover;

        public BaselineEntry(String baseId, SolData solData,
                             double[] dXyz, double[][] cBaseline, double[][] pRover) {
            this.baseId = baseId;
            this.solData = solData;
            this.dXyz = dXyz;
            this.cBaseline = cBaseline;
            this.pRover = pRover;
        }
    }

    /**
     * 构造BaselineEpoch，自动过滤非FIX基线。
     *
     * @param epochTag  历元标识
     * @param baselines 原始基线数组（最多3条），非FIX的会被剔除
     */
    public BaselineEpoch(String epochTag, BaselineEntry[] baselines) {
        this.epochTag = epochTag;
        BaselineEntry[] valid = new BaselineEntry[baselines.length];
        int n = 0;
        for (BaselineEntry b : baselines) {
            if (b != null && b.solData != null
                    && b.solData.status == org.rtklib.java.data.SolutionStatus.FIX) {
                valid[n++] = b;
            }
        }
        this.baselines = new BaselineEntry[n];
        System.arraycopy(valid, 0, this.baselines, 0, n);
        this.count = n;
    }
}