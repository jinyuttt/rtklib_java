package org.rtklib.java.research.pipeline;

import java.util.ArrayList;
import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.GTime;
import org.rtklib.java.research.data.Ephemeris;
import org.rtklib.java.research.data.Navigation;
import org.rtklib.java.research.data.Observation;
import org.rtklib.java.research.data.ObservationEpoch;
import org.rtklib.java.research.data.Solution;
import org.rtklib.java.research.data.SolutionStatus;
import org.rtklib.java.research.ephemeris.SatellitePosition;
import org.rtklib.java.research.ephemeris.SatellitePosition.SatelliteState;
import org.rtklib.java.research.integration.EkfState;
import org.rtklib.java.research.integration.IntegrationState;
import org.rtklib.java.research.stochastic.StochasticModel;

/**
 * EKF 后端实现（实现 SolverBackend 接口，内部桥接 EkfState）。
 *
 * <p>移植自 FE-GUT ekfstate.cc/.h + gnss_uwb.cc 主循环的 EKF 分支。</p>
 */
public class EkfBackend implements SolverBackend {

    private static final int DEFAULT_GNSS_NUM = 6;
    private static final int DEFAULT_UWB_NUM = 4;

    private String name;
    private SolverConfig config;
    private EkfState ekf;
    private SolverStatistics stats;
    private GTime lastTime;
    private boolean initialized;

    private int gnssNum;
    private int uwbNum;
    private double dt;

    public EkfBackend() {
        this("EKF");
    }

    public EkfBackend(String name) {
        this.name = name;
        this.stats = new SolverStatistics();
        this.initialized = false;
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
        this.gnssNum = DEFAULT_GNSS_NUM;
        this.uwbNum = DEFAULT_UWB_NUM;
        this.initialized = false;
        this.lastTime = null;
    }

    @Override
    public Solution solve(ObservationEpoch epoch, Navigation nav) {
        long t0 = System.currentTimeMillis();

        if (!initialized) {
            initializeFromFirstEpoch(epoch, nav);
        }

        if (lastTime != null) {
            dt = epoch.time.time - lastTime.time;
            if (dt <= 0) dt = 0.01;
        } else {
            dt = 0.01;
        }
        lastTime = new GTime(epoch.time);

        int nSat = Math.min(epoch.observations.size(), gnssNum);
        double[] pr = new double[gnssNum];
        double[] prrate = new double[gnssNum];
        double[][] satPos = new double[gnssNum][3];
        double[][] satVel = new double[gnssNum][3];
        int validCount = 0;

        for (int i = 0; i < nSat; i++) {
            Observation obs = epoch.observations.get(i);
            if (!obs.isValid()) continue;

            pr[validCount] = obs.pseudorange;
            double wl = obs.wavelength();
            prrate[validCount] = -obs.doppler * wl;

            Ephemeris eph = nav.getEphemeris(obs.sat);
            if (eph != null) {
                SatelliteState satState = SatellitePosition.compute(eph, epoch.time);
                satPos[validCount][0] = satState.position[0];
                satPos[validCount][1] = satState.position[1];
                satPos[validCount][2] = satState.position[2];
                satVel[validCount][0] = satState.velocity[0];
                satVel[validCount][1] = satState.velocity[1];
                satVel[validCount][2] = satState.velocity[2];
            }

            validCount++;
            if (validCount >= gnssNum) break;
        }

        double[] uwbRanges = new double[uwbNum];
        double[][] uwbAnchors = new double[uwbNum][3];
        for (int i = 0; i < uwbNum; i++) {
            uwbRanges[i] = 0;
        }

        if (validCount == 0) {
            Solution empty = new Solution();
            empty.time = new GTime(epoch.time);
            empty.status = SolutionStatus.NONE;
            empty.backendName = config.backendName;
            return empty;
        }

        ekf.setDt(dt);
        IntegrationState state;

        if (validCount > 0) {
            state = ekf.ekfUpdate(pr, prrate, satPos, satVel, uwbRanges, uwbAnchors);
        } else {
            state = ekf.ekfUwbUpdate(uwbRanges, uwbAnchors);
        }

        Solution sol = new Solution();
        sol.time = new GTime(epoch.time);
        sol.position = new double[]{state.xdata[0], state.xdata[1], state.xdata[2]};
        sol.velocity = new double[]{state.xdata[3], state.xdata[4], state.xdata[5]};
        sol.positionCov = state.P.extractMatrix(0, 3, 0, 3);
        sol.status = SolutionStatus.SINGLE;
        sol.numSatellites = validCount;
        sol.receiverClockBias = new double[]{state.xdata[9]};
        sol.backendName = config.backendName;

        double rms = sol.positionRms();
        if (Double.isNaN(rms)) rms = 0;
        sol.pdop = rms;

        sol.computeTimeMs = System.currentTimeMillis() - t0;

        stats.totalEpochs++;
        stats.singleEpochs++;
        stats.totalComputeTimeMs += sol.computeTimeMs;
        stats.avgComputeTimeMs = (double) stats.totalComputeTimeMs / stats.totalEpochs;

        return sol;
    }

    private void initializeFromFirstEpoch(ObservationEpoch epoch, Navigation nav) {
        double[] ecef = new double[]{0, 0, 6378137.0};

        double[] elevations = new double[gnssNum];
        int[] sysIds = new int[gnssNum];
        int validSat = 0;

        if (!epoch.observations.isEmpty()) {
            Observation obs = epoch.observations.get(0);
            Ephemeris eph = nav.getEphemeris(obs.sat);
            if (eph != null) {
                SatelliteState satState = SatellitePosition.compute(eph, epoch.time);
                ecef[0] = satState.position[0] * 0.1;
                ecef[1] = satState.position[1] * 0.1;
                ecef[2] = satState.position[2] * 0.1 + 6000000;
            }

            for (int i = 0; i < Math.min(epoch.observations.size(), gnssNum); i++) {
                obs = epoch.observations.get(i);
                if (!obs.isValid()) continue;
                Ephemeris eph2 = nav.getEphemeris(obs.sat);
                if (eph2 != null) {
                    SatelliteState satState = SatellitePosition.compute(eph2, epoch.time);
                    double dx = satState.position[0] - ecef[0];
                    double dy = satState.position[1] - ecef[1];
                    double dz = satState.position[2] - ecef[2];
                    double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    elevations[validSat] = Math.asin(Math.abs(satState.position[2] - ecef[2]) / range);
                    sysIds[validSat] = eph2.sys != 0 ? eph2.sys : 1;
                    validSat++;
                }
            }
        }

        IntegrationState initState = new IntegrationState();
        initState.time = epoch.time.time;
        initState.xdata[0] = ecef[0];
        initState.xdata[1] = ecef[1];
        initState.xdata[2] = ecef[2];

        double initVarPos = 10000.0;
        double initVarVel = 100.0;
        double initVarAcc = 10.0;
        double initVarClk = 9e12;
        for (int i = 0; i < 3; i++) {
            initState.P.set(i, i, initVarPos);
            initState.P.set(i + 3, i + 3, initVarVel);
            initState.P.set(i + 6, i + 6, initVarAcc);
        }
        initState.P.set(9, 9, initVarClk);
        initState.P.set(10, 10, 100.0);
        initState.P.set(11, 11, 0.01);

        this.ekf = new EkfState(initState, gnssNum, uwbNum, 0.01, 2.0, 0.1, 0.5);

        StochasticModel sm = config.stochasticModel;
        if (sm != null && validSat > 0) {
            SimpleMatrix Q = sm.processNoiseCovariance(IntegrationState.DIM_STATE, 0.01);
            SimpleMatrix Rpr = sm.observationCovariance(
                java.util.Arrays.copyOf(elevations, validSat),
                java.util.Arrays.copyOf(sysIds, validSat),
                1.0);

            int totalDim = 2 * gnssNum + uwbNum;
            SimpleMatrix R = new SimpleMatrix(totalDim, totalDim);
            for (int i = 0; i < Math.min(validSat, gnssNum); i++) {
                R.set(i, i, Rpr.get(i, i));
                R.set(gnssNum + i, gnssNum + i, 0.1 * 0.1);
            }
            double uwbVar = 0.5 * 0.5;
            for (int i = 0; i < uwbNum; i++) {
                R.set(2 * gnssNum + i, 2 * gnssNum + i, uwbVar);
            }

            SimpleMatrix RUwb = new SimpleMatrix(uwbNum, uwbNum);
            for (int i = 0; i < uwbNum; i++) RUwb.set(i, i, uwbVar);

            ekf.setNoiseMatrices(Q, R, RUwb);
        }

        this.initialized = true;
        this.lastTime = new GTime(epoch.time);
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
    }
}