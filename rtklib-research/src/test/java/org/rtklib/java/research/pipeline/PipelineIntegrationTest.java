package org.rtklib.java.research.pipeline;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.rtklib.java.research.data.Navigation;
import org.rtklib.java.research.data.ObservationEpoch;
import org.rtklib.java.research.data.Solution;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PipelineIntegrationTest {

    private static final int MAX_EPOCHS = 100;

    private DatasetLoader loader;
    private Navigation nav;
    private List<ObservationEpoch> epochs;
    private boolean dataLoaded;

    @BeforeAll
    void setUp() {
        String datasetPath = System.getProperty("dataset.path", "");
        if (datasetPath.isEmpty()) {
            datasetPath = System.getProperty("user.dir") +
                    File.separator + ".." + File.separator +
                    "reference-projects" + File.separator +
                    "FE-GUT" + File.separator + "dataset";
        }

        System.out.println("== rtklib-research Pipeline Integration Test ==");
        System.out.println("Dataset: " + datasetPath);

        try {
            loader = new DatasetLoader(datasetPath);
            loader.loadAll();
            System.out.println("Loaded " + loader.size() + " epochs");

            int testCount = Math.min(loader.size(), MAX_EPOCHS);
            epochs = new ArrayList<>();
            for (int i = 0; i < testCount; i++) {
                epochs.add(loader.getEpoch(i));
            }
            System.out.println("Testing with " + testCount + " epochs");

            nav = new Navigation();
            dataLoaded = true;
        } catch (IOException e) {
            System.err.println("Skipping test: dataset not found at " + datasetPath);
            dataLoaded = false;
        }
    }

    @Test
    void testEkfPipeline() {
        if (!dataLoaded) return;

        SolverConfig config = new SolverConfig();
        config.backendName = "EKF";
        config.windowSize = 30;
        config.maxIterations = 10;
        config.convergenceThreshold = 1e-6;
        config.useRobustLoss = false;

        SolverBackend backend = new EkfBackend("EKF");
        backend.initialize(config);

        System.out.println("\n--- EKF Backend ---");
        List<Solution> solutions = backend.solveBatch(epochs, nav);
        assertNotNull(solutions, "EKF solutions should not be null");

        SolverStatistics stats = backend.statistics();
        System.out.println("  Total epochs: " + stats.totalEpochs);
        System.out.println("  Avg compute time: " + String.format("%.2f", stats.avgComputeTimeMs) + " ms");

        printPositionSummary("EKF", solutions);
    }

    @Test
    void testFgoPipeline() {
        if (!dataLoaded) return;

        SolverConfig config = new SolverConfig();
        config.backendName = "FGO";
        config.windowSize = 30;
        config.maxIterations = 10;
        config.convergenceThreshold = 1e-6;
        config.useRobustLoss = true;
        config.robustLossType = "HUBER";
        config.robustLossThreshold = 1.345;

        SolverBackend backend = new FgoBackend("FGO");
        backend.initialize(config);

        System.out.println("\n--- FGO Backend ---");
        List<Solution> solutions = backend.solveBatch(epochs, nav);
        assertNotNull(solutions, "FGO solutions should not be null");

        SolverStatistics stats = backend.statistics();
        System.out.println("  Total epochs: " + stats.totalEpochs);
        System.out.println("  Avg compute time: " + String.format("%.2f", stats.avgComputeTimeMs) + " ms");

        printPositionSummary("FGO", solutions);
    }

    @Test
    void testBackendComparison() {
        if (!dataLoaded) return;

        SolverBackend ekfBackend = new EkfBackend("EKF");
        SolverBackend fgoBackend = new FgoBackend("FGO");

        SolverConfig ekfConfig = new SolverConfig();
        ekfConfig.backendName = "EKF";
        ekfConfig.windowSize = 30;

        SolverConfig fgoConfig = new SolverConfig();
        fgoConfig.backendName = "FGO";
        fgoConfig.windowSize = 30;

        ekfBackend.initialize(ekfConfig);
        fgoBackend.initialize(fgoConfig);

        List<Solution> ekfSol = ekfBackend.solveBatch(epochs, nav);
        List<Solution> fgoSol = fgoBackend.solveBatch(epochs, nav);

        BackendComparator comparator = new BackendComparator();
        comparator.addBackend(ekfBackend);
        comparator.addBackend(fgoBackend);
        comparator.compareAndReport(ekfSol);

        System.out.println("\n--- Comparison ---");
        System.out.println(comparator.formatComparisonTable());
    }

    @Test
    void testFgoSwitchVariable() {
        if (!dataLoaded) return;

        SolverConfig switchConfig = SolverConfig.forFgoWithSwitch();
        FgoBackend switchBackend = new FgoBackend("FGO+Switch");
        switchBackend.initialize(switchConfig);

        System.out.println("\n--- FGO+SwitchVariable Backend ---");
        List<Solution> solutions = switchBackend.solveBatch(epochs, nav);
        assertNotNull(solutions, "FGO+Switch solutions should not be null");

        SolverStatistics stats = switchBackend.statistics();
        System.out.println("  Total epochs: " + stats.totalEpochs);
        System.out.println("  Avg compute time: " + String.format("%.2f", stats.avgComputeTimeMs) + " ms");

        printPositionSummary("FGO+Switch", solutions);

        String switchInfo = switchBackend.lastSwitchInfo();
        System.out.println("  Switch info: " + switchInfo);
        System.out.println("  Switch values tracked: " + switchBackend.lastSwitchValues().size());
    }

    @Test
    void testSceneAdaptivePipiline() {
        if (!dataLoaded) return;

        SolverConfig adaptiveConfig = new SolverConfig();
        adaptiveConfig.backendName = "FGO+SceneAdaptive";
        adaptiveConfig.windowSize = 30;
        adaptiveConfig.maxIterations = 10;
        adaptiveConfig.convergenceThreshold = 1e-6;
        adaptiveConfig.useSceneAdaptive = true;

        FgoBackend adaptiveBackend = new FgoBackend("FGO+SceneAdaptive");
        adaptiveBackend.initialize(adaptiveConfig);

        System.out.println("\n--- FGO+SceneAdaptive Backend ---");
        List<Solution> solutions = adaptiveBackend.solveBatch(epochs, nav);
        assertNotNull(solutions, "SceneAdaptive solutions should not be null");

        SolverStatistics stats = adaptiveBackend.statistics();
        System.out.println("  Total epochs: " + stats.totalEpochs);
        System.out.println("  Avg compute time: " + String.format("%.2f", stats.avgComputeTimeMs) + " ms");

        printPositionSummary("FGO+SceneAdaptive", solutions);

        System.out.println("  Switch info (last epoch): " + adaptiveBackend.lastSwitchInfo());
    }

    @Test
    void testSwitchVariableComparison() {
        if (!dataLoaded) return;

        FgoBackend fgo = new FgoBackend("FGO");
        FgoBackend switchFgo = new FgoBackend("FGO+Switch");

        SolverConfig fgoConfig = new SolverConfig();
        fgoConfig.backendName = "FGO";
        fgoConfig.windowSize = 30;
        fgoConfig.maxIterations = 10;
        fgoConfig.useRobustLoss = true;
        fgoConfig.robustLossType = "HUBER";

        SolverConfig switchConfig = SolverConfig.forFgoWithSwitch();

        fgo.initialize(fgoConfig);
        switchFgo.initialize(switchConfig);

        List<Solution> fgoSol = fgo.solveBatch(epochs, nav);
        List<Solution> switchSol = switchFgo.solveBatch(epochs, nav);

        BackendComparator comparator = new BackendComparator();
        comparator.addBackend(fgo);
        comparator.addBackend(switchFgo);
        comparator.compareAndReport(fgoSol);

        System.out.println("\n--- SwitchVariable vs Huber Comparison ---");
        System.out.println(comparator.formatComparisonTable());
        System.out.println("Note: SwitchVariable handles NLOS by turning off bad factors completely.");
        System.out.println("      Huber loss downweights but never fully removes outliers.");
    }

    private void printPositionSummary(String label, List<Solution> solutions) {
        double avgX = 0, avgY = 0, avgZ = 0;
        int count = 0;
        Solution last = null;
        for (Solution sol : solutions) {
            if (sol != null && sol.position != null && sol.position[0] != 0) {
                avgX += sol.position[0];
                avgY += sol.position[1];
                avgZ += sol.position[2];
                count++;
                last = sol;
            }
        }
        if (count > 0) {
            avgX /= count; avgY /= count; avgZ /= count;
            System.out.printf("  Avg pos: [%.3f, %.3f, %.3f]%n", avgX, avgY, avgZ);
            System.out.println("  Valid: " + count + " / " + solutions.size());
        }
        if (last != null) {
            double rms = last.positionRms();
            System.out.printf("  RMS: %.3f m%n", Double.isNaN(rms) ? 0 : rms);
        }
    }
}