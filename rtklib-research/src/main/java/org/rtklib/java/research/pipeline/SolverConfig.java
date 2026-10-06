package org.rtklib.java.research.pipeline;

import org.rtklib.java.research.stochastic.ElevationSnrModel;
import org.rtklib.java.research.stochastic.StochasticModel;

/**
 * 求解器配置（research模块自有）。
 */
public class SolverConfig {
    public String backendName;
    public int windowSize;
    public boolean useSlidingWindow;
    public double elMaskDeg;
    public double snrMaskDbHz;
    public int maxIterations;
    public double convergenceThreshold;
    public double ratioThreshold;
    public boolean useRobustLoss;
    public String robustLossType;
    public double robustLossThreshold;
    public boolean usePartialAr;
    public boolean useCascadeAr;
    public boolean useIace;
    public int systemMask;
    public int frequencyMask;
    public StochasticModel stochasticModel;

    public SolverConfig() {
        this.backendName = "unknown";
        this.windowSize = 30;
        this.useSlidingWindow = true;
        this.elMaskDeg = 15.0;
        this.snrMaskDbHz = 20.0f;
        this.maxIterations = 20;
        this.convergenceThreshold = 1e-6;
        this.ratioThreshold = 3.0;
        this.useRobustLoss = false;
        this.robustLossType = "none";
        this.robustLossThreshold = 1.5;
        this.usePartialAr = true;
        this.useCascadeAr = false;
        this.useIace = false;
        this.systemMask = 0xFF;
        this.frequencyMask = 0x3F;
        this.stochasticModel = new ElevationSnrModel();
    }

    public static SolverConfig forEkf() {
        SolverConfig c = new SolverConfig();
        c.backendName = "EKF";
        c.useSlidingWindow = false;
        return c;
    }

    public static SolverConfig forFgo() {
        SolverConfig c = new SolverConfig();
        c.backendName = "FGO";
        c.useSlidingWindow = true;
        c.windowSize = 30;
        return c;
    }

    public static SolverConfig forFgoWithHuber() {
        SolverConfig c = forFgo();
        c.backendName = "FGO+Huber";
        c.useRobustLoss = true;
        c.robustLossType = "huber";
        return c;
    }
}