package org.rtklib.java.adjust.model;

import java.util.List;

public record MultiBaselineConfig(
        List<String> baseStationIds,
        boolean qualityWeight,
        CrossValidationConfig crossValidation,
        BatchConfig batch,
        DiagnosisConfig diagnosis
) {

    public static Builder builder() {
        return new Builder();
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

        public Builder baseStationIds(String... ids) { this.baseStationIds = List.of(ids); return this; }
        public Builder baseStationIds(List<String> ids) { this.baseStationIds = ids; return this; }
        public Builder qualityWeight(boolean v) { this.qualityWeight = v; return this; }
        public Builder crossValidation(CrossValidationConfig v) { this.crossValidation = v; return this; }
        public Builder batch(BatchConfig v) { this.batch = v; return this; }
        public Builder diagnosis(DiagnosisConfig v) { this.diagnosis = v; return this; }

        public MultiBaselineConfig build() {
            return new MultiBaselineConfig(baseStationIds, qualityWeight, crossValidation, batch, diagnosis);
        }
    }

    public static MultiBaselineConfig defaults() {
        return builder().build();
    }
}