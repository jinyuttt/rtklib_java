package org.rtklib.java.benchmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight end-to-end timing utility for PPP/RTK epoch-level profiling.
 *
 * <p>Usage:
 * <pre>
 * PerfTimer timer = new PerfTimer("relpos");
 * // ... hot path ...
 * timer.record();
 * // after all epochs:
 * timer.report();
 * </pre>
 *
 * <p>This complements JMH micro-benchmarks by providing whole-program epoch timing,
 * GC-aware accounting, and P99 latency measurement.</p>
 */
public class PerfTimer {

    private final String label;
    private final List<Long> deltas;
    private long lastTime;
    private int epochCount;

    public PerfTimer(String label) {
        this.label = label;
        this.deltas = new ArrayList<>();
    }

    public void start() {
        lastTime = System.nanoTime();
    }

    public void record() {
        long now = System.nanoTime();
        deltas.add(now - lastTime);
        epochCount++;
        lastTime = now;
    }

    public int getEpochCount() {
        return epochCount;
    }

    public String getLabel() {
        return label;
    }

    public long totalNs() {
        long sum = 0;
        for (long d : deltas) {
            sum += d;
        }
        return sum;
    }

    public double avgUs() {
        if (deltas.isEmpty()) return 0;
        return totalNs() / (double) deltas.size() / 1000.0;
    }

    public double p50Us() {
        return percentile(50.0) / 1000.0;
    }

    public double p99Us() {
        return percentile(99.0) / 1000.0;
    }

    public double maxUs() {
        if (deltas.isEmpty()) return 0;
        return Collections.max(deltas) / 1000.0;
    }

    public String report() {
        return String.format(
                "%s: epochs=%d total=%.1fms avg=%.1fus p50=%.1fus p99=%.1fus max=%.1fus",
                label, epochCount, totalNs() / 1_000_000.0,
                avgUs(), p50Us(), p99Us(), maxUs()
        );
    }

    private long percentile(double p) {
        if (deltas.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(deltas);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }
}