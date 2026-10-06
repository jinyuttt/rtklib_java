package org.rtklib.java.research.pipeline;

import org.rtklib.java.research.data.ObservationEpoch;
import org.rtklib.java.research.data.Navigation;
import org.rtklib.java.research.data.Solution;

import java.util.List;

/**
 * 统一求解器后端接口（research模块核心接口）。
 *
 * <p>所有估计后端（EKF、FGO、学习型加权、紧融合）都实现此接口，
 * 使同一管线可以切换不同后端进行对比实验。</p>
 */
public interface SolverBackend {

    String name();

    void initialize(SolverConfig config);

    Solution solve(ObservationEpoch epoch, Navigation nav);

    List<Solution> solveBatch(List<ObservationEpoch> epochs, Navigation nav);

    SolverStatistics statistics();

    void reset();
}