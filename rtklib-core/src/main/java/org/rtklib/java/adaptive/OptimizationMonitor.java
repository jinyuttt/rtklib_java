package org.rtklib.java.adaptive;

import org.rtklib.java.config.RtkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimizationMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(OptimizationMonitor.class);

    private static final double IGGIII_RATE_MAX = 0.50;
    private static final double SEG_REJECT_RATE_MAX = 0.30;
    private static final double SNR_MIN_WEIGHT_MIN = 0.01;
    private static final int DIVERGING_THRESHOLD = 3;
    private static final double DIVERGING_BASELINE_FACTOR = 3.0;
    private static final int BASELINE_WINDOW = 100;

    private int divergingCount = 0;
    private double prevPosVarTrace = Double.NaN;
    private double baselinePosVarTrace = Double.MAX_VALUE;
    private final double[] posVarTraceWindow = new double[BASELINE_WINDOW];
    private int windowIdx = 0;
    private boolean windowFilled = false;

    public boolean checkAndAct(OptimizationStats stats, RtkConfig cfg, AdaptiveOverride override,
                              AdaptiveAuditLogger auditLogger, AdaptiveListener listener) {
        boolean anyAction = false;

        if (stats.getSlidingIggiiiRate() > IGGIII_RATE_MAX) {
            LOG.warn("[ADAPTIVE] IGGIII 降权率 {:.1f}% 超过上限 {:.0f}%",
                    stats.getSlidingIggiiiRate() * 100, IGGIII_RATE_MAX * 100);
            if (listener != null) {
                AdaptiveRecommendation rec = new AdaptiveRecommendation(0, "IGGIII过度降权");
                rec.addSuggestion("enableIggiii", "false", "降权率超限");
                listener.onParamRecommendation(rec);
            }
        }

        if (stats.getSlidingSegRejectRate() > SEG_REJECT_RATE_MAX) {
            LOG.warn("[ADAPTIVE] 段σ剔除率 {:.1f}% 超过上限 {:.0f}%",
                    stats.getSlidingSegRejectRate() * 100, SEG_REJECT_RATE_MAX * 100);
        }

        return anyAction;
    }

    public boolean checkDivergence(double posVarTrace) {
        if (!Double.isNaN(prevPosVarTrace)) {
            if (posVarTrace > prevPosVarTrace) {
                divergingCount++;
            } else {
                divergingCount = 0;
            }
        }
        prevPosVarTrace = posVarTrace;

        posVarTraceWindow[windowIdx] = posVarTrace;
        windowIdx = (windowIdx + 1) % BASELINE_WINDOW;
        if (windowIdx == 0) windowFilled = true;

        updateBaseline();

        return divergingCount >= DIVERGING_THRESHOLD && posVarTrace > baselinePosVarTrace * DIVERGING_BASELINE_FACTOR;
    }

    private void updateBaseline() {
        int count = windowFilled ? BASELINE_WINDOW : windowIdx;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (posVarTraceWindow[i] < min) min = posVarTraceWindow[i];
        }
        if (min < Double.MAX_VALUE) baselinePosVarTrace = min;
    }

    public void reset() {
        divergingCount = 0;
        prevPosVarTrace = Double.NaN;
        windowIdx = 0;
        windowFilled = false;
    }
}