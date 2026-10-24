package org.rtklib.java.adaptive;

public class OptimizationStats {
    public int iggiiiDownweighted;
    public int iggiiiRejected;
    public int snrDownweighted;
    public int segArcsRejected;
    public int arAttempted;
    public int arFixed;
    public int arDeltaGateRejected;

    private int epochCount = 0;
    private double slidingIggiiiRate = 0;
    private double slidingSegRejectRate = 0;
    private static final double ALPHA = 0.05;

    public void update(int nv, int totalArcs) {
        epochCount++;
        double iggRate = nv > 0 ? (double) iggiiiDownweighted / nv : 0;
        double segRate = totalArcs > 0 ? (double) segArcsRejected / totalArcs : 0;
        if (epochCount == 1) {
            slidingIggiiiRate = iggRate;
            slidingSegRejectRate = segRate;
        } else {
            slidingIggiiiRate = (1 - ALPHA) * slidingIggiiiRate + ALPHA * iggRate;
            slidingSegRejectRate = (1 - ALPHA) * slidingSegRejectRate + ALPHA * segRate;
        }
    }

    public double getSlidingIggiiiRate() { return slidingIggiiiRate; }
    public double getSlidingSegRejectRate() { return slidingSegRejectRate; }

    public void reset() {
        iggiiiDownweighted = 0;
        iggiiiRejected = 0;
        snrDownweighted = 0;
        segArcsRejected = 0;
        arAttempted = 0;
        arFixed = 0;
        arDeltaGateRejected = 0;
    }
}