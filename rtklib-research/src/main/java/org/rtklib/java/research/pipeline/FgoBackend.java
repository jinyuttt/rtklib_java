package org.rtklib.java.research.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.ambiguity.IaceEstimator;
import org.rtklib.java.research.ambiguity.IaceResult;
import org.rtklib.java.research.ambiguity.LambdaSolver;
import org.rtklib.java.research.ambiguity.LambdaSolver.LambdaResult;
import org.rtklib.java.research.common.GTime;
import org.rtklib.java.research.data.Ephemeris;
import org.rtklib.java.research.data.Navigation;
import org.rtklib.java.research.data.Observation;
import org.rtklib.java.research.data.ObservationEpoch;
import org.rtklib.java.research.data.Solution;
import org.rtklib.java.research.data.SolutionStatus;
import org.rtklib.java.research.ephemeris.SatellitePosition;
import org.rtklib.java.research.ephemeris.SatellitePosition.SatelliteState;
import org.rtklib.java.research.factorgraph.Factor;
import org.rtklib.java.research.factorgraph.FactorGraph;
import org.rtklib.java.research.factorgraph.FactorGraph.OptimizationResult;
import org.rtklib.java.research.factorgraph.MarginalizationFactor;
import org.rtklib.java.research.factorgraph.MarginalizationInfo;
import org.rtklib.java.research.factorgraph.PhaseFactor;
import org.rtklib.java.research.factorgraph.PredictFactor;
import org.rtklib.java.research.factorgraph.ResidualBlockInfo;
import org.rtklib.java.research.factorgraph.RobustLoss;
import org.rtklib.java.research.factorgraph.SwitchPriorFactor;
import org.rtklib.java.research.factorgraph.SwitchWrapperFactor;
import org.rtklib.java.research.factorgraph.TcFactor;
import org.rtklib.java.research.factorgraph.Variable;
import org.rtklib.java.research.pipeline.SceneEvaluator.Scene;

/**
 * FGO 后端实现（实现 SolverBackend 接口，内部使用 FactorGraph 滑动窗口优化）。
 *
 * <p>移植自 FE-GUT gnss_uwb.cc 主循环的 FGO 分支 + tc_factor.h + predict_factor.h
 * + marginalization_info.h + marginalization_factor.h。</p>
 *
 * <p>数据流：
 * <ol>
 *   <li>新历元到来 → 创建状态变量节点（11维）+ tdk + amb 变量节点</li>
 *   <li>构建 TcFactor → 伪距+伪距率+UWB紧耦合因子</li>
 *   <li>构建 PhaseFactor → 载波相位+模糊度因子</li>
 *   <li>构建 PredictFactor → 连接前后历元状态/模糊度</li>
 *   <li>窗口满时 → 边缘化最老状态+模糊度 → 生成 MarginalizationFactor</li>
 *   <li>LM 优化 → 提取最新状态 → 输出 Solution (FLOAT)</li>
 * </ol>
 */
public class FgoBackend implements SolverBackend {

    private static final int STATE_DIM = 11;
    private static final int DEFAULT_GNSS_NUM = 6;
    private static final int DEFAULT_UWB_NUM = 4;
    private static final int DEFAULT_WINDOW_SIZE = 30;

    private String name;
    private SolverConfig config;
    private SolverStatistics stats;
    private FactorGraph graph;
    private GTime lastTime;
    private boolean initialized;

    private int windowSize;
    private int gnssNum;
    private int uwbNum;
    private int epochCount;

    private final Deque<String> stateVars;
    private final Deque<String> tdkVars;
    private final Deque<String> ambVars;
    private String prevStateVar;
    private String prevTdkVar;
    private String prevAmbVar;

    private MarginalizationFactor priorFactor;
    private List<String> priorParamVars;

    private final boolean usePhaseFactor;

    private IaceEstimator iaceEstimator;

    private final List<Double> lastSwitchValues;
    private String lastSwitchInfo;

    public FgoBackend() {
        this("FGO");
    }

    public FgoBackend(String name) {
        this(name, true);
    }

    public FgoBackend(String name, boolean usePhaseFactor) {
        this.name = name;
        this.stats = new SolverStatistics();
        this.initialized = false;
        this.stateVars = new ArrayDeque<>();
        this.tdkVars = new ArrayDeque<>();
        this.ambVars = new ArrayDeque<>();
        this.usePhaseFactor = usePhaseFactor;
        this.lastSwitchValues = new ArrayList<>();
        this.lastSwitchInfo = "";
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void initialize(SolverConfig config) {
        this.config = config;
        this.stats = new SolverStatistics();
        this.stats.backendName = config.backendName;
        this.windowSize = Math.max(config.windowSize, 3);
        this.gnssNum = DEFAULT_GNSS_NUM;
        this.uwbNum = DEFAULT_UWB_NUM;
        this.initialized = false;
        this.lastTime = null;
        this.epochCount = 0;
        this.graph = new FactorGraph();
        this.stateVars.clear();
        this.tdkVars.clear();
        this.ambVars.clear();
        this.prevStateVar = null;
        this.prevTdkVar = null;
        this.prevAmbVar = null;
        this.priorFactor = null;
        this.priorParamVars = null;

        if (config.useIace) {
            this.iaceEstimator = new IaceEstimator();
        } else {
            this.iaceEstimator = null;
        }
    }

    @Override
    public Solution solve(ObservationEpoch epoch, Navigation nav) {
        long t0 = System.currentTimeMillis();

        if (!initialized) {
            initializeFromFirstEpoch(epoch, nav);
        }

        double dt;
        if (lastTime != null) {
            dt = epoch.time.time - lastTime.time;
            if (dt <= 0) dt = 0.1;
        } else {
            dt = 0.1;
        }
        lastTime = new GTime(epoch.time);
        epochCount++;

        String stateVarName = "s" + epochCount;
        String tdkVarName = "tdk" + epochCount;
        String ambVarName = "amb" + epochCount;

        Variable stateVar = new Variable(stateVarName, STATE_DIM);
        Variable tdkVar = new Variable(tdkVarName, 1);

        if (prevStateVar != null) {
            Variable prevVar = graph.getVariable(prevStateVar);
            if (prevVar != null && prevVar.value != null) {
                for (int i = 0; i < STATE_DIM; i++) stateVar.value.set(i, 0, prevVar.value.get(i, 0));
            }
        }

        graph.addVariable(stateVar);
        graph.addVariable(tdkVar);

        int nSat = Math.min(epoch.observations.size(), gnssNum);
        double[] pr = new double[gnssNum];
        double[] prrate = new double[gnssNum];
        double[][] satPos = new double[gnssNum][3];
        double[] phaseObs = new double[gnssNum];
        double[] wavelengths = new double[gnssNum];
        double[] satClkBias = new double[gnssNum];
        boolean[] hasPhase = new boolean[gnssNum];
        int validCount = 0;
        int phaseCount = 0;

        for (int i = 0; i < nSat; i++) {
            Observation obs = epoch.observations.get(i);
            if (!obs.isValid()) continue;

            pr[validCount] = obs.pseudorange;
            double wl = obs.wavelength();
            prrate[validCount] = -obs.doppler * wl;
            wavelengths[validCount] = wl;

            if (usePhaseFactor && Math.abs(obs.carrierPhase) > 1e-6) {
                phaseObs[validCount] = obs.carrierPhase;
                hasPhase[validCount] = true;
                phaseCount++;
            }

            Ephemeris eph = nav.getEphemeris(obs.sat);
            if (eph != null) {
                SatelliteState satState = SatellitePosition.compute(eph, epoch.time);
                satPos[validCount][0] = satState.position[0];
                satPos[validCount][1] = satState.position[1];
                satPos[validCount][2] = satState.position[2];
                satClkBias[validCount] = satState.clockBias;
            }

            validCount++;
            if (validCount >= gnssNum) break;
        }

        double[] uwbRanges = new double[uwbNum];
        double[][] uwbAnchors = new double[uwbNum][3];

        boolean useSwitch = config.useSwitchVariable;
        if (config.useSceneAdaptive) {
            Scene scene = SceneEvaluator.classify(validCount);
            SolverConfig adapted = SceneEvaluator.adaptiveStrategy(config, scene);
            useSwitch = adapted.useSwitchVariable;
        }

        lastSwitchValues.clear();
        lastSwitchInfo = "";

        if (validCount > 0) {
            TcFactor tc = new TcFactor(pr, prrate, satPos, uwbRanges, uwbAnchors,
                                        stateVar, tdkVar, 2.0, 0.1, 0.5);

            if (useSwitch) {
                Variable swTc = new Variable("sw_tc_" + epochCount, 1);
                swTc.value.set(0, 0, 1.0);
                graph.addVariable(swTc);

                SwitchWrapperFactor swTcFactor = new SwitchWrapperFactor(tc, swTc);
                graph.addFactor(swTcFactor);
                graph.addFactor(new SwitchPriorFactor(swTc, config.switchPriorSigma));

                lastSwitchValues.add(0.0);
                lastSwitchInfo += "TC:sw=1.0 ";
            } else {
                graph.addFactor(tc);
            }

            if (usePhaseFactor && phaseCount > 0) {
                Variable ambVar = new Variable(ambVarName, validCount);
                graph.addVariable(ambVar);
                ambVars.addLast(ambVarName);

                PhaseFactor pf = new PhaseFactor(stateVar, ambVar,
                                                  phaseObs, wavelengths, satPos, satClkBias);

                if (useSwitch) {
                    Variable swPh = new Variable("sw_ph_" + epochCount, 1);
                    swPh.value.set(0, 0, 1.0);
                    graph.addVariable(swPh);

                    SwitchWrapperFactor swPhFactor = new SwitchWrapperFactor(pf, swPh);
                    graph.addFactor(swPhFactor);
                    graph.addFactor(new SwitchPriorFactor(swPh, config.switchPriorSigma));

                    lastSwitchValues.add(0.0);
                    lastSwitchInfo += "PH:sw=1.0 ";
                } else {
                    graph.addFactor(pf);
                }

                if (prevAmbVar != null) {
                    Variable prevAmb = graph.getVariable(prevAmbVar);
                    if (prevAmb != null) {
                        PredictFactor ambPred = new PredictFactor(prevAmb, ambVar, dt, validCount);
                        graph.addFactor(ambPred);
                    }
                }
                prevAmbVar = ambVarName;
            }

            if (prevStateVar != null) {
                Variable prevVar = graph.getVariable(prevStateVar);
                if (prevVar != null) {
                    PredictFactor pf = new PredictFactor(prevVar, stateVar, dt);
                    graph.addFactor(pf);
                }
            }

            if (priorFactor != null) {
                graph.addFactor(priorFactor);
            }
        }

        if (config.useSlidingWindow && stateVars.size() >= windowSize) {
            marginalizeOldest();
        }

        stateVars.addLast(stateVarName);
        tdkVars.addLast(tdkVarName);
        prevStateVar = stateVarName;
        prevTdkVar = tdkVarName;

        OptimizationResult result = graph.optimize(config.maxIterations, config.convergenceThreshold, true);

        Variable curState = graph.getVariable(stateVarName);
        Variable curTdk = graph.getVariable(tdkVarName);

        Solution sol = new Solution();
        sol.time = new GTime(epoch.time);
        sol.status = usePhaseFactor ? SolutionStatus.FLOAT : SolutionStatus.SINGLE;
        sol.numSatellites = validCount;
        sol.backendName = config.backendName;

        if (curState != null && curState.value != null) {
            sol.position = new double[3];
            for (int i = 0; i < 3; i++) sol.position[i] = curState.value.get(i, 0);
            sol.velocity = new double[3];
            for (int i = 0; i < 3; i++) sol.velocity[i] = curState.value.get(i + 3, 0);
        }

        if (curTdk != null && curTdk.value != null) {
            sol.receiverClockBias = new double[]{curTdk.value.get(0, 0)};
        }

        if (usePhaseFactor) {
            Variable curAmb = graph.getVariable(ambVarName);
            if (curAmb != null && curAmb.value != null && phaseCount > 0) {
                sol.floatAmbiguities = new double[validCount];
                for (int i = 0; i < validCount; i++) {
                    sol.floatAmbiguities[i] = curAmb.value.get(i, 0);
                }

                List<Integer> satIds = new ArrayList<>();
                for (int i = 0; i < validCount; i++) {
                    satIds.add(epoch.observations.get(i).sat);
                }

                if (iaceEstimator != null) {
                    for (int i = 0; i < validCount; i++) {
                        iaceEstimator.addSample(satIds.get(i), sol.floatAmbiguities[i]);
                    }
                }

                boolean fixed = false;

                int ambOffset = 0;
                for (Map.Entry<String, Variable> entry : graph.getVariables().entrySet()) {
                    if (entry.getValue().fixed) continue;
                    if (entry.getKey().equals(ambVarName)) break;
                    ambOffset += entry.getValue().dimension;
                }

                if (result.covariance != null && result.covariance.numRows() >= ambOffset + validCount) {
                    SimpleMatrix floatAmb = new SimpleMatrix(validCount, 1);
                    for (int i = 0; i < validCount; i++) {
                        floatAmb.set(i, 0, sol.floatAmbiguities[i]);
                    }
                    SimpleMatrix ambCov = result.covariance.extractMatrix(ambOffset, ambOffset + validCount,
                                                                           ambOffset, ambOffset + validCount);

                    LambdaResult lr = LambdaSolver.solve(floatAmb, ambCov, 2);
                    if (lr.fixed) {
                        sol.status = SolutionStatus.FIX;
                        sol.ratio = lr.ratio;
                        sol.fixedAmbiguities = new double[validCount];
                        for (int i = 0; i < validCount; i++) {
                            sol.fixedAmbiguities[i] = lr.fixedAmbiguity.get(i, 0);
                        }
                        stats.fixEpochs++;
                        fixed = true;
                    }
                }

                if (!fixed && iaceEstimator != null) {
                    IaceResult ir = iaceEstimator.estimate(satIds);
                    if (ir.success) {
                        sol.status = SolutionStatus.FIX;
                        sol.ratio = ir.clusterScore;
                        sol.fixedAmbiguities = new double[validCount];
                        for (int i = 0; i < validCount; i++) {
                            sol.fixedAmbiguities[i] = ir.fixedAmbiguities.get(i, 0);
                        }
                        stats.fixEpochs++;
                        fixed = true;
                    }
                }

                if (!fixed) {
                    stats.floatEpochs++;
                }
            } else {
                stats.floatEpochs++;
            }
        }

        if (result.covariance != null) {
            sol.positionCov = result.covariance.extractMatrix(0, Math.min(3, result.covariance.numRows()),
                                                               0, Math.min(3, result.covariance.numCols()));
        }

        double rms = sol.positionRms();
        sol.pdop = Double.isNaN(rms) ? 0 : rms;

        sol.computeTimeMs = System.currentTimeMillis() - t0;

        stats.totalEpochs++;
        if (!usePhaseFactor) {
            stats.singleEpochs++;
        }
        stats.totalComputeTimeMs += sol.computeTimeMs;
        stats.avgComputeTimeMs = (double) stats.totalComputeTimeMs / stats.totalEpochs;

        return sol;
    }

    private void marginalizeOldest() {
        String oldestStateName = stateVars.peekFirst();
        String oldestTdkName = tdkVars.peekFirst();
        String oldestAmbName = ambVars.isEmpty() ? null : ambVars.peekFirst();
        if (oldestStateName == null) return;

        Variable oldestState = graph.getVariable(oldestStateName);
        if (oldestState == null) return;

        MarginalizationInfo margInfo = new MarginalizationInfo();

        List<Factor> allFactors = graph.getFactors();
        List<Factor> margFactors = new ArrayList<>();

        for (Factor f : allFactors) {
            boolean involvesOldest = false;
            for (Variable v : f.connectedVariables) {
                if (v.name.equals(oldestStateName)
                    || v.name.equals(oldestTdkName)
                    || (oldestAmbName != null && v.name.equals(oldestAmbName))) {
                    involvesOldest = true;
                    break;
                }
            }
            if (involvesOldest) {
                margFactors.add(f);
            }
        }

        for (Factor f : margFactors) {
            List<double[]> paramBlocks = new ArrayList<>();
            List<Integer> margIndices = new ArrayList<>();

            for (int k = 0; k < f.connectedVariables.size(); k++) {
                Variable v = f.connectedVariables.get(k);
                double[] data = new double[v.dimension];
                if (v.value != null) {
                    for (int i = 0; i < Math.min(v.dimension, v.value.getNumElements()); i++) {
                        data[i] = v.value.get(i, 0);
                    }
                }
                paramBlocks.add(data);

                if (v.name.equals(oldestStateName)
                    || v.name.equals(oldestTdkName)
                    || (oldestAmbName != null && v.name.equals(oldestAmbName))) {
                    margIndices.add(k);
                }
                if (config.useSwitchVariable && v.name.startsWith("sw_")) {
                    margIndices.add(k);
                }
            }

            RobustLoss loss = null;
            if (config.useRobustLoss) {
                RobustLoss.Type type = RobustLoss.Type.valueOf(config.robustLossType.toUpperCase());
                loss = new RobustLoss(type, config.robustLossThreshold);
            }

            ResidualBlockInfo rbi = new ResidualBlockInfo(f, loss, paramBlocks, margIndices);
            margInfo.addResidualBlockInfo(rbi);
        }

        boolean success = margInfo.marginalization();
        if (!success) {
            if (!stateVars.isEmpty()) stateVars.pollFirst();
            if (!tdkVars.isEmpty()) tdkVars.pollFirst();
            if (!ambVars.isEmpty()) ambVars.pollFirst();
            return;
        }

        Map<Integer, double[]> addressMap = new HashMap<>();
        margInfo.getParameterBlocks(addressMap);

        List<Variable> keptVars = new ArrayList<>();
        for (Factor f : margFactors) {
            for (Variable v : f.connectedVariables) {
                boolean isOldest = v.name.equals(oldestStateName)
                        || v.name.equals(oldestTdkName)
                        || (oldestAmbName != null && v.name.equals(oldestAmbName));
                if (isOldest) continue;

                if (v.name.startsWith("sw_") && config.useSwitchVariable) {
                    continue;
                }
                if (!keptVars.contains(v)) {
                    keptVars.add(v);
                }
            }
        }

        priorFactor = new MarginalizationFactor(margInfo, keptVars);
        priorParamVars = new ArrayList<>();
        for (Variable v : keptVars) priorParamVars.add(v.name);

        for (Factor f : margFactors) {
            graph.removeFactor(f);
        }

        graph.removeVariable(oldestStateName);
        graph.removeVariable(oldestTdkName);
        if (oldestAmbName != null) {
            graph.removeVariable(oldestAmbName);
        }

        if (!stateVars.isEmpty()) stateVars.pollFirst();
        if (!tdkVars.isEmpty()) tdkVars.pollFirst();
        if (!ambVars.isEmpty()) ambVars.pollFirst();
    }

    private void initializeFromFirstEpoch(ObservationEpoch epoch, Navigation nav) {
        this.graph = new FactorGraph();
        this.lastTime = null;
        this.epochCount = 0;
        this.stateVars.clear();
        this.tdkVars.clear();
        this.ambVars.clear();
        this.prevStateVar = null;
        this.prevTdkVar = null;
        this.prevAmbVar = null;
        this.priorFactor = null;
        this.initialized = true;
    }

    @Override
    public List<Solution> solveBatch(List<ObservationEpoch> epochs, Navigation nav) {
        List<Solution> solutions = new ArrayList<>();
        initialize(config);
        for (ObservationEpoch epoch : epochs) {
            solutions.add(solve(epoch, nav));
        }
        return solutions;
    }

    @Override
    public SolverStatistics statistics() {
        return stats;
    }

    @Override
    public void reset() {
        this.initialized = false;
        this.lastTime = null;
        this.stats = new SolverStatistics();
        this.graph = new FactorGraph();
        this.stateVars.clear();
        this.tdkVars.clear();
        this.ambVars.clear();
        this.prevStateVar = null;
        this.prevTdkVar = null;
        this.prevAmbVar = null;
        this.priorFactor = null;
        this.epochCount = 0;
        this.lastSwitchValues.clear();
        this.lastSwitchInfo = "";
    }

    public List<Double> lastSwitchValues() {
        return new ArrayList<>(lastSwitchValues);
    }

    public String lastSwitchInfo() {
        return lastSwitchInfo;
    }
}