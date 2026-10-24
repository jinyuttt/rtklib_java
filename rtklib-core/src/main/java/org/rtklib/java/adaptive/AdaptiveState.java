package org.rtklib.java.adaptive;

public class AdaptiveState {
    private final AdaptiveMode mode;
    private volatile GnssScene currentScene;
    private volatile long currentEpoch;
    private volatile int sceneSwitchCount;
    private volatile int canyonEpochCount;
    private volatile int degradationCount;
    private volatile int rollbackCount;
    private volatile long totalEpochs;

    public AdaptiveState(AdaptiveMode mode) {
        this.mode = mode;
        this.currentScene = GnssScene.URBAN_CANYON;
    }

    public AdaptiveMode getMode() { return mode; }
    public GnssScene getCurrentScene() { return currentScene; }
    public long getCurrentEpoch() { return currentEpoch; }
    public int getSceneSwitchCount() { return sceneSwitchCount; }
    public int getCanyonEpochCount() { return canyonEpochCount; }
    public int getDegradationCount() { return degradationCount; }
    public int getRollbackCount() { return rollbackCount; }
    public long getTotalEpochs() { return totalEpochs; }

    void setScene(GnssScene scene) { this.currentScene = scene; }
    void setEpoch(long epoch) { this.currentEpoch = epoch; }
    void incrementSwitchCount() { this.sceneSwitchCount++; }
    void incrementCanyonEpoch() { this.canyonEpochCount++; }
    void incrementDegradation() { this.degradationCount++; }
    void incrementRollback() { this.rollbackCount++; }
    void incrementTotalEpochs() { this.totalEpochs++; }

    public String summary() {
        double canyonPct = totalEpochs > 0 ? 100.0 * canyonEpochCount / totalEpochs : 0;
        return String.format("[ADAPTIVE] 运行统计:\n" +
                        "  总历元: %d\n" +
                        "  场景切换: %d次\n" +
                        "  退化: %d次\n" +
                        "  回滚: %d次\n" +
                        "  CANYON停留: %d历元 (%.1f%%)",
                totalEpochs, sceneSwitchCount, degradationCount, rollbackCount,
                canyonEpochCount, canyonPct);
    }
}