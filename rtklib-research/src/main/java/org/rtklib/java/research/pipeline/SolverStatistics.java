package org.rtklib.java.research.pipeline;

/**
 * 求解器统计信息（research模块自有）。
 */
public class SolverStatistics {
    public String backendName;
    public int totalEpochs;
    public int fixEpochs;
    public int floatEpochs;
    public int singleEpochs;
    public int noneEpochs;
    public double fixRate;
    public double[] rmsENU;
    public double[] stdENU;
    public double[] maxErrorENU;
    public long totalComputeTimeMs;
    public double avgComputeTimeMs;

    public SolverStatistics() {
        this.backendName = "";
        this.totalEpochs = 0;
        this.fixEpochs = 0;
        this.floatEpochs = 0;
        this.singleEpochs = 0;
        this.noneEpochs = 0;
        this.fixRate = 0.0;
        this.rmsENU = new double[3];
        this.stdENU = new double[3];
        this.maxErrorENU = new double[3];
        this.totalComputeTimeMs = 0;
        this.avgComputeTimeMs = 0.0;
    }

    @Override
    public String toString() {
        return String.format(
                "Stats[%s: total=%d, fix=%d(%.1f%%), float=%d, single=%d, "
                + "RMS=[%.4f,%.4f,%.4f]m, avgTime=%.1fms]",
                backendName, totalEpochs, fixEpochs, fixRate * 100, floatEpochs, singleEpochs,
                rmsENU[0], rmsENU[1], rmsENU[2], avgComputeTimeMs);
    }
}