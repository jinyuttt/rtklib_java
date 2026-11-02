package org.rtklib.java.adjust.model;

import java.util.List;

public record MultiBaselineConfig(
        List<String> baseStationIds,
        boolean qualityWeight,
        CrossValidationConfig crossValidation,
        BatchConfig batch,
        DiagnosisConfig diagnosis,
        /**
         * 基站坐标的 1σ 不确定度 (m)，按各轴独立计。默认 0 表示不建模。
         *
         * <p>RTCM 播发的基站坐标通常有 1~2 cm 的偏差，且该偏差是<b>系统性</b>的：
         * 会整体平移到 Rover 解。由于 core 逐基站独立解算，各基线的基站坐标误差
         * 互不相关，因此可按 {@code σ_base²·I₃} 加到每条基线观测的协方差块上
         * （见 {@code MultiBaselineAdjust}）。</p>
         *
         * <p>注意：同一基站在不同历元的坐标偏差是相关的，本模型按历元独立处理，
         * 会略微低估整时段平均值的相关性。</p>
         */
        double baseCoordinateSigma,
        /**
         * 网平差观测累积时间窗（秒），默认 3600（1 小时）。
         *
         * <p>整时段网平差只使用最近该时长内的基线观测：基站位移监测关心的是
         * 近期状态，超长时段的单次大平差既无必要也浪费内存。观测按 GPS 时间
         * 从队列头部淘汰。设为 {@code <= 0} 关闭时间窗（仅受观测条数上限约束）。</p>
         */
        double networkWindowSeconds,
        /**
         * 解状态准入策略：只吃 FIX，还是允许 FLOAT 补位。
         *
         * <p>开阔短基线下 FIX 率接近 100%，网平差本身收益有限；真正需要网平差的是
         * 长基线、遮挡、城市峡谷这类 FIX 率低的场景，而恰恰在这些场景里，
         * "仅 FIX" 会把整段历元全部丢掉——平差在它最该发挥作用的场合反而没数据可用。</p>
         *
         * <p>因此准入策略做成配置项：{@code allowFloat=false} 为历史行为（仅 FIX），
         * {@code allowFloat=true} 时 FIX 优先、FLOAT 补位。默认关闭，保持既有语义不变。</p>
         */
        SolutionAdmissionConfig solutionAdmission
) {

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 解状态准入策略。
     *
     * @param allowFloat             是否允许 FLOAT 基线进入平差。false = 仅 FIX（历史行为）。
     * @param floatCovInflate        FLOAT 基线协方差的膨胀倍数（按方差计）。
     *                               FLOAT 的误差通常在分米级、FIX 在厘米级，
     *                               方差比约 100~10000，默认 1000（即 1σ 放大 31.6 倍），
     *                               使 FLOAT 在 WLS 中只占极小权重，融合位置基本由 FIX 决定。
     * @param floatAgreementThreshold FLOAT 与 FIX 位置较差的一致性判定阈值 (m)，
     *                               是<b>准入前置条件</b>而非事后判级：仅 1 条 FIX 时，
     *                               超过该值的 FLOAT 不进入 WLS 并被记入
     *                               {@code AdjustResult.rejectedFloats}；准入的历元判
     *                               {@code SINGLE_VERIFIED}，被拒的历元只剩 1 条 FIX、
     *                               判 {@code UNVERIFIED} 且 {@code crossValidationDist} 为 NaN。
     */
    public record SolutionAdmissionConfig(
            boolean allowFloat,
            double floatCovInflate,
            double floatAgreementThreshold
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean allowFloat = false;
            private double floatCovInflate = 1000.0;
            private double floatAgreementThreshold = 0.20;

            public Builder allowFloat(boolean v) { this.allowFloat = v; return this; }
            public Builder floatCovInflate(double v) { this.floatCovInflate = v; return this; }
            public Builder floatAgreementThreshold(double v) { this.floatAgreementThreshold = v; return this; }

            public SolutionAdmissionConfig build() {
                return new SolutionAdmissionConfig(allowFloat, floatCovInflate, floatAgreementThreshold);
            }
        }

        public static SolutionAdmissionConfig defaults() {
            return builder().build();
        }
    }

    public record CrossValidationConfig(
            double verifiedThreshold,
            double crossCheckedThreshold,
            double suspectThreshold,
            double plKVerified,
            double plKCrossChecked,
            double plKUnverified,
            double plKSuspect,
            double crossCheckedCovInflate
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private double verifiedThreshold = 0.02;
            private double crossCheckedThreshold = 0.05;
            private double suspectThreshold = 0.05;
            private double plKVerified = 2.0;
            private double plKCrossChecked = 3.0;
            private double plKUnverified = 6.0;
            private double plKSuspect = 10.0;
            private double crossCheckedCovInflate = 4.0;

            public Builder verifiedThreshold(double v) { this.verifiedThreshold = v; return this; }
            public Builder crossCheckedThreshold(double v) { this.crossCheckedThreshold = v; return this; }
            public Builder suspectThreshold(double v) { this.suspectThreshold = v; return this; }
            public Builder plKVerified(double v) { this.plKVerified = v; return this; }
            public Builder plKCrossChecked(double v) { this.plKCrossChecked = v; return this; }
            public Builder plKUnverified(double v) { this.plKUnverified = v; return this; }
            public Builder plKSuspect(double v) { this.plKSuspect = v; return this; }
            public Builder crossCheckedCovInflate(double v) { this.crossCheckedCovInflate = v; return this; }

            public CrossValidationConfig build() {
                return new CrossValidationConfig(verifiedThreshold, crossCheckedThreshold,
                        suspectThreshold, plKVerified, plKCrossChecked, plKUnverified,
                        plKSuspect, crossCheckedCovInflate);
            }
        }

        public static CrossValidationConfig defaults() {
            return builder().build();
        }
    }

    public record BatchConfig(
            int windowSize,
            boolean enabled
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int windowSize = 240;
            private boolean enabled = true;

            public Builder windowSize(int v) { this.windowSize = v; return this; }
            public Builder enabled(boolean v) { this.enabled = v; return this; }

            public BatchConfig build() {
                return new BatchConfig(windowSize, enabled);
            }
        }

        public static BatchConfig defaults() {
            return builder().build();
        }
    }

    public record DiagnosisConfig(
            boolean enable,
            double sigma0Threshold,
            int diagnoseWindow,
            double diagnoseRatio,
            int staticMinEpochs
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean enable = true;
            private double sigma0Threshold = 3.0;
            private int diagnoseWindow = 20;
            private double diagnoseRatio = 0.5;
            private int staticMinEpochs = 100;

            public Builder enable(boolean v) { this.enable = v; return this; }
            public Builder sigma0Threshold(double v) { this.sigma0Threshold = v; return this; }
            public Builder diagnoseWindow(int v) { this.diagnoseWindow = v; return this; }
            public Builder diagnoseRatio(double v) { this.diagnoseRatio = v; return this; }
            public Builder staticMinEpochs(int v) { this.staticMinEpochs = v; return this; }

            public DiagnosisConfig build() {
                return new DiagnosisConfig(enable, sigma0Threshold, diagnoseWindow, diagnoseRatio, staticMinEpochs);
            }
        }

        public static DiagnosisConfig defaults() {
            return builder().build();
        }
    }

    public static class Builder {
        private List<String> baseStationIds = List.of("A", "B");
        private boolean qualityWeight = true;
        private CrossValidationConfig crossValidation = CrossValidationConfig.defaults();
        private BatchConfig batch = BatchConfig.defaults();
        private DiagnosisConfig diagnosis = DiagnosisConfig.defaults();
        private double baseCoordinateSigma = 0.0;
        private double networkWindowSeconds = 3600.0;
        private SolutionAdmissionConfig solutionAdmission = SolutionAdmissionConfig.defaults();

        public Builder baseStationIds(String... ids) { this.baseStationIds = List.of(ids); return this; }
        public Builder baseStationIds(List<String> ids) { this.baseStationIds = ids; return this; }
        public Builder qualityWeight(boolean v) { this.qualityWeight = v; return this; }
        public Builder crossValidation(CrossValidationConfig v) { this.crossValidation = v; return this; }
        public Builder batch(BatchConfig v) { this.batch = v; return this; }
        public Builder diagnosis(DiagnosisConfig v) { this.diagnosis = v; return this; }
        public Builder baseCoordinateSigma(double v) { this.baseCoordinateSigma = v; return this; }
        public Builder networkWindowSeconds(double v) { this.networkWindowSeconds = v; return this; }
        public Builder solutionAdmission(SolutionAdmissionConfig v) { this.solutionAdmission = v; return this; }

        public Builder allowFloat(boolean v) {
            this.solutionAdmission = SolutionAdmissionConfig.builder()
                    .allowFloat(v)
                    .floatCovInflate(solutionAdmission.floatCovInflate())
                    .floatAgreementThreshold(solutionAdmission.floatAgreementThreshold())
                    .build();
            return this;
        }

        public MultiBaselineConfig build() {
            return new MultiBaselineConfig(baseStationIds, qualityWeight, crossValidation, batch,
                    diagnosis, baseCoordinateSigma, networkWindowSeconds,
                    solutionAdmission != null ? solutionAdmission : SolutionAdmissionConfig.defaults());
        }
    }

    public static MultiBaselineConfig defaults() {
        return builder().build();
    }
}