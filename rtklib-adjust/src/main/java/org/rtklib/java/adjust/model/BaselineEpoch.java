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

        /**
         * [Java扩展] 基线质量因子(0~1)，1=最优。
         * 由ratio/numSat/age/DOP综合计算，用于P0基线质量加权。
         * 值越小，该基线在平差中的权重越低。
         */
        public final double qualityFactor;

        public BaselineEntry(String baseId, SolData solData,
                             double[] dXyz, double[][] cBaseline, double[][] pRover) {
            this(baseId, solData, dXyz, cBaseline, pRover, 1.0);
        }

        public BaselineEntry(String baseId, SolData solData,
                             double[] dXyz, double[][] cBaseline, double[][] pRover,
                             double qualityFactor) {
            this.baseId = baseId;
            this.solData = solData;
            this.dXyz = dXyz;
            this.cBaseline = cBaseline;
            this.pRover = pRover;
            this.qualityFactor = qualityFactor;
        }

        /**
         * [Java扩展] 根据SolData中的质量指标计算基线质量因子。
         *
         * <p>综合ratio(模糊度固定可靠性)、numSat(卫星数)、age(差分龄期)、hdop(水平精度因子)，
         * 对低质量FIX基线降权，使σ₀更稳健，减少假固定对平差结果的污染。</p>
         *
         * @param solData 解算结果
         * @return 质量因子(0.01~1.0)，1.0=最优
         */
        public static double computeQualityFactor(SolData solData) {
            double w = 1.0;

            if (solData.ratio < 3.0 && solData.ratio > 0.0) {
                w *= solData.ratio / 3.0;
            }

            if (solData.numSat < 6 && solData.numSat > 0) {
                w *= solData.numSat / 6.0;
            }

            if (solData.age > 5.0 && solData.age < 3600.0) {
                w *= 5.0 / solData.age;
            }

            if (solData.hdop > 3.0 && solData.hdop < 99.0) {
                w *= 3.0 / solData.hdop;
            }

            return Math.max(w, 0.01);
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