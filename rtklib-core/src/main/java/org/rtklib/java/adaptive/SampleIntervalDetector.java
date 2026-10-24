package org.rtklib.java.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class SampleIntervalDetector {
    private static final Logger LOG = LoggerFactory.getLogger(SampleIntervalDetector.class);

    private static final int DETECT_EPOCHS = 30;
    private static final double INTERVAL_TOLERANCE = 0.001;

    private final Map<Double, Integer> intervalCounts = new LinkedHashMap<>();
    private int epochCount = 0;
    private double prevTime = Double.NaN;
    private double detectedInterval = Double.NaN;
    private boolean detectionComplete = false;

    public void feed(double gpst) {
        if (detectionComplete) return;
        if (Double.isNaN(prevTime)) {
            prevTime = gpst;
            return;
        }
        double dt = gpst - prevTime;
        prevTime = gpst;
        if (dt <= 0) return;

        double rounded = Math.round(dt / INTERVAL_TOLERANCE) * INTERVAL_TOLERANCE;
        intervalCounts.merge(rounded, 1, Integer::sum);
        epochCount++;
        if (epochCount >= DETECT_EPOCHS) {
            detectionComplete = true;
            detectedInterval = intervalCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(Double.NaN);
        }
    }

    public double getDetectedInterval() { return detectedInterval; }
    public boolean isDetectionComplete() { return detectionComplete; }

    public String getDistributionSummary() {
        if (intervalCounts.isEmpty()) return "(no data)";
        StringBuilder sb = new StringBuilder();
        double total = intervalCounts.values().stream().mapToInt(i -> i).sum();
        for (Map.Entry<Double, Integer> e : intervalCounts.entrySet()) {
            double pct = 100.0 * e.getValue() / total;
            sb.append(String.format("\n  %.3fs: %d次 (%.1f%%)", e.getKey(), e.getValue(), pct));
        }
        if (!Double.isNaN(detectedInterval)) {
            double hz = 1.0 / detectedInterval;
            sb.append(String.format("\n  主间隔: %.3fs (%.1fHz)", detectedInterval, hz));
        }
        return sb.toString();
    }

    public void warnIfAbnormal(int windowEpochs, int cooldownEpochs) {
        if (!detectionComplete || Double.isNaN(detectedInterval)) return;
        if (detectedInterval > 5.0) {
            double windowTime = windowEpochs * detectedInterval;
            double cooldownTime = cooldownEpochs * detectedInterval;
            LOG.warn("[ADAPTIVE] 历元间隔分布 (前{}历元):{}", DETECT_EPOCHS, getDistributionSummary());
            LOG.warn("[ADAPTIVE] ⚠ 实际采样间隔{}s，当前配置: {}历元 × {}s = {:.1f}s ({:.0f}分{:.0f}秒)",
                    String.format("%.3f", detectedInterval), windowEpochs,
                    String.format("%.3f", detectedInterval), windowTime,
                    Math.floor(windowTime / 60), windowTime % 60);
        }
    }
}