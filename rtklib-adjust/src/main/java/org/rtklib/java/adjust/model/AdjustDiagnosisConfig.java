package org.rtklib.java.adjust.model;

public class AdjustDiagnosisConfig {

    public final boolean enable;

    public final double sigma0Threshold;

    public final int diagnoseWindow;

    public final double diagnoseRatio;

    public final boolean useSppForDirection;

    public final boolean useStaticForCorrection;

    public final double minStaticDataHours;

    public final boolean cacheDiagnosis;

    private AdjustDiagnosisConfig(Builder builder) {
        this.enable = builder.enable;
        this.sigma0Threshold = builder.sigma0Threshold;
        this.diagnoseWindow = builder.diagnoseWindow;
        this.diagnoseRatio = builder.diagnoseRatio;
        this.useSppForDirection = builder.useSppForDirection;
        this.useStaticForCorrection = builder.useStaticForCorrection;
        this.minStaticDataHours = builder.minStaticDataHours;
        this.cacheDiagnosis = builder.cacheDiagnosis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enable = false;
        private double sigma0Threshold = 3.0;
        private int diagnoseWindow = 100;
        private double diagnoseRatio = 0.8;
        private boolean useSppForDirection = true;
        private boolean useStaticForCorrection = true;
        private double minStaticDataHours = 2.0;
        private boolean cacheDiagnosis = true;

        public Builder enable(boolean enable) { this.enable = enable; return this; }
        public Builder sigma0Threshold(double threshold) { this.sigma0Threshold = threshold; return this; }
        public Builder diagnoseWindow(int window) { this.diagnoseWindow = window; return this; }
        public Builder diagnoseRatio(double ratio) { this.diagnoseRatio = ratio; return this; }
        public Builder useSppForDirection(boolean use) { this.useSppForDirection = use; return this; }
        public Builder useStaticForCorrection(boolean use) { this.useStaticForCorrection = use; return this; }
        public Builder minStaticDataHours(double hours) { this.minStaticDataHours = hours; return this; }
        public Builder cacheDiagnosis(boolean cache) { this.cacheDiagnosis = cache; return this; }

        public AdjustDiagnosisConfig build() {
            return new AdjustDiagnosisConfig(this);
        }
    }

    @Override
    public String toString() {
        return String.format("AdjustDiagnosisConfig{enable=%s, σ₀>%.1f, window=%d, ratio=%.0f%%, spp=%s, static=%s, minHours=%.1f, cache=%s}",
                enable, sigma0Threshold, diagnoseWindow, diagnoseRatio * 100,
                useSppForDirection, useStaticForCorrection, minStaticDataHours, cacheDiagnosis);
    }
}