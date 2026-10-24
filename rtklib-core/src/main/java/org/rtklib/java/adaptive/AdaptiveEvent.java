package org.rtklib.java.adaptive;

public class AdaptiveEvent {
    public enum Type {
        SCENE_SWITCH,
        DEGRADATION,
        BASELINE_SWITCH,
        ROLLBACK,
        OPTIMIZATION_AUTO_OFF
    }

    private final long epoch;
    private final Type type;
    private final String from;
    private final String to;
    private final String metrics;
    private final String changes;
    private final String reason;

    public AdaptiveEvent(long epoch, Type type, String from, String to,
                         String metrics, String changes, String reason) {
        this.epoch = epoch;
        this.type = type;
        this.from = from;
        this.to = to;
        this.metrics = metrics;
        this.changes = changes;
        this.reason = reason;
    }

    public long getEpoch() { return epoch; }
    public Type getType() { return type; }
    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getMetrics() { return metrics; }
    public String getChanges() { return changes; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return String.format("AdaptiveEvent{epoch=%d, type=%s, %s→%s, reason=%s}",
                epoch, type, from, to, reason);
    }
}