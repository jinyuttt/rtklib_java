package org.rtklib.java.research.pipeline;

import org.rtklib.java.research.data.Solution;
import org.rtklib.java.research.data.SolutionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 多后端对比运行器（research模块自有）。
 *
 * <p>同一组数据依次跑多个后端，收集统计信息用于对比。</p>
 */
public class BackendComparator {
    private final List<SolverBackend> backends;
    private final List<SolverStatistics> statisticsList;

    public BackendComparator() {
        this.backends = new ArrayList<>();
        this.statisticsList = new ArrayList<>();
    }

    public void addBackend(SolverBackend backend) {
        backends.add(backend);
    }

    public List<SolverStatistics> getStatistics() {
        return statisticsList;
    }

    public void compareAndReport(List<Solution> referenceSolutions) {
        statisticsList.clear();
        for (SolverBackend backend : backends) {
            SolverStatistics stats = backend.statistics();
            computeAccuracy(referenceSolutions, stats);
            statisticsList.add(stats);
        }
    }

    private void computeAccuracy(List<Solution> reference, SolverStatistics stats) {
        if (reference == null || reference.isEmpty()) return;
        double sumE = 0, sumN = 0, sumU = 0;
        double sumE2 = 0, sumN2 = 0, sumU2 = 0;
        double maxE = 0, maxN = 0, maxU = 0;
        int n = reference.size();
        for (Solution sol : reference) {
            if (sol.positionCov == null) continue;
            double[] enu = sol.getPositionENU(reference.get(0).position);
            sumE += enu[0]; sumN += enu[1]; sumU += enu[2];
            sumE2 += enu[0] * enu[0]; sumN2 += enu[1] * enu[1]; sumU2 += enu[2] * enu[2];
            maxE = Math.max(maxE, Math.abs(enu[0]));
            maxN = Math.max(maxN, Math.abs(enu[1]));
            maxU = Math.max(maxU, Math.abs(enu[2]));
        }
        if (n > 0) {
            stats.rmsENU[0] = Math.sqrt(sumE2 / n);
            stats.rmsENU[1] = Math.sqrt(sumN2 / n);
            stats.rmsENU[2] = Math.sqrt(sumU2 / n);
            stats.maxErrorENU[0] = maxE;
            stats.maxErrorENU[1] = maxN;
            stats.maxErrorENU[2] = maxU;
        }
    }

    public String formatComparisonTable() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-20s %6s %6s %8s %8s %8s %8s %10s%n",
                "Backend", "Total", "Fix", "FixRate", "RMS_E", "RMS_N", "RMS_U", "AvgTime(ms)"));
        sb.append("-".repeat(90)).append("\n");
        for (SolverStatistics s : statisticsList) {
            sb.append(String.format("%-20s %6d %6d %7.1f%% %8.4f %8.4f %8.4f %10.1f%n",
                    s.backendName, s.totalEpochs, s.fixEpochs, s.fixRate * 100,
                    s.rmsENU[0], s.rmsENU[1], s.rmsENU[2], s.avgComputeTimeMs));
        }
        return sb.toString();
    }
}