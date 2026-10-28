package org.rtklib.java.networkvrs.netsol;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.correction.ModelCorrectionProvider;
import org.rtklib.java.networkvrs.error.NetworkErrorDecomposer;
import org.rtklib.java.networkvrs.sync.MultiStationSynchronizer;
import org.rtklib.java.networkvrs.sync.RinexNavLoader;
import org.rtklib.java.networkvrs.sync.StationObservation;
import org.rtklib.java.networkvrs.sync.SynchronizedEpoch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络解算主流程（档 2）：多站同步 → 双差 → 窗口网解（含 AR）→ 误差分解。
 *
 * <p>输出为逐历元「各站相对主站的改正量」，交给 {@link NetworkVrsProcessor}
 * 用 IDW/FKP 插值到流动站位置。任一基线未能固定时才用单站码组合先验兜底，
 * 因此每历元都有可用输出。</p>
 */
public final class NetworkSolutionEngine {

    /**
     * 单历元分解结果。
     *
     * @param time      历元
     * @param byStation 站 -> 卫星 -> 相对主站改正量
     */
    public record EpochCorrections(
            GTime time,
            Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> byStation) {
    }

    /**
     * @param epochs  逐历元分解结果
     * @param windows  基线窗口解（诊断用）
     * @param network  基线网
     */
    public record Result(List<EpochCorrections> epochs,
                         List<BaselineWindowSolution> windows,
                         BaselineNetwork network) {
    }

    /**
     * @param stations 各站观测（星历可从 {@code nav} 注入）
     * @param nav      广播星历；为 null 时使用各站自带的星历
     * @param masterId 主站
     * @param config   配置
     */
    public Result solve(List<StationObservation> stations, Nav nav, String masterId,
                        NetworkConfig config) {
        if (stations == null || stations.size() < config.minStations) {
            throw new IllegalArgumentException("站数不足: 需要 " + config.minStations);
        }
        boolean masterFound = false;
        for (StationObservation station : stations) {
            if (station.id().equals(masterId)) {
                masterFound = true;
                break;
            }
        }
        if (!masterFound) {
            throw new IllegalArgumentException("master station not found: " + masterId);
        }

        List<StationObservation> prepared = prepare(stations, nav);
        MultiStationSynchronizer synchronizer =
                new MultiStationSynchronizer(config.epochToleranceSec, config.minStations);
        List<SynchronizedEpoch> epochs = synchronizer.synchronize(prepared);
        if (epochs.isEmpty()) {
            return new Result(List.of(), List.of(), BaselineNetwork.build(prepared, config.maxBaselineMeters));
        }

        Map<String, StationObservation> byId = new LinkedHashMap<>();
        for (StationObservation station : prepared) {
            byId.put(station.id(), station);
        }

        BaselineNetwork network = BaselineNetwork.build(prepared, config.maxBaselineMeters);
        if (!network.connected()) {
            throw new IllegalArgumentException("基线网不连通：站距超过 " + config.maxBaselineMeters + " m");
        }

        DdObservationBuilder builder = new DdObservationBuilder();
        Map<String, List<DdEpoch>> ddByBaseline = new LinkedHashMap<>();
        for (BaselineNetwork.Baseline baseline : network.baselines()) {
            ddByBaseline.put(key(baseline), new ArrayList<>());
        }
        List<Map<String, List<DdObservation>>> epochDd = new ArrayList<>(epochs.size());
        for (SynchronizedEpoch epoch : epochs) {
            Map<String, List<DdObservation>> byBaseline = new LinkedHashMap<>();
            for (BaselineNetwork.Baseline baseline : network.baselines()) {
                StationObservation a = byId.get(baseline.stationA());
                StationObservation b = byId.get(baseline.stationB());
                List<Obsd> obsA = epoch.observations(baseline.stationA());
                List<Obsd> obsB = epoch.observations(baseline.stationB());
                if (obsA == null || obsB == null || obsA.isEmpty() || obsB.isEmpty()) {
                    continue;
                }
                List<DdObservation> dd = builder.build(a.ecef(), b.ecef(), obsA, obsB,
                        navOf(a, nav), config.minElevationDeg);
                if (!dd.isEmpty()) {
                    byBaseline.put(key(baseline), dd);
                    ddByBaseline.get(key(baseline)).add(new DdEpoch(epoch.time(), dd));
                }
            }
            epochDd.add(byBaseline);
        }

        BaselineWindowSolver solver = new BaselineWindowSolver(config);
        List<BaselineWindowSolution> solutions = new ArrayList<>();
        int stride = Math.max(1, config.solutionWindowEpochs);
        for (BaselineNetwork.Baseline baseline : network.baselines()) {
            List<DdEpoch> list = ddByBaseline.get(key(baseline));
            StationObservation a = byId.get(baseline.stationA());
            StationObservation b = byId.get(baseline.stationB());
            for (int i = 0; i < list.size(); i += stride) {
                int end = Math.min(i + stride, list.size());
                BaselineWindowSolution solution = solver.solve(baseline.stationA(),
                        baseline.stationB(), a.ecef(), b.ecef(), list.subList(i, end));
                if (solution != null) {
                    solutions.add(solution);
                }
            }
        }

        List<Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>>> priors =
                computePriors(epochs, byId);
        NetworkErrorDecomposer decomposer = new NetworkErrorDecomposer();
        List<EpochCorrections> result = new ArrayList<>(epochs.size());
        for (int e = 0; e < epochs.size(); e++) {
            SynchronizedEpoch epoch = epochs.get(e);
            double absolute = epoch.time().time + epoch.time().sec;
            List<BaselineWindowSolution> covering = new ArrayList<>();
            for (BaselineWindowSolution solution : solutions) {
                if (solution.covers(absolute)) {
                    covering.add(solution);
                }
            }
            Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> corrections =
                    decomposer.decomposeEpoch(masterId, new ArrayList<>(epoch.stationIds()),
                            epochDd.get(e), priors.get(e), covering, config);
            result.add(new EpochCorrections(epoch.time(), corrections));
        }
        return new Result(result, solutions, network);
    }

    private static List<StationObservation> prepare(List<StationObservation> stations, Nav nav) {
        List<StationObservation> prepared = new ArrayList<>(stations.size());
        boolean anyNav = nav != null;
        for (StationObservation station : stations) {
            if (!station.ephemerides().isEmpty()) {
                anyNav = true;
            }
        }
        if (!anyNav) {
            throw new IllegalArgumentException("缺少导航星历：请传入 Nav 或带星历的站点");
        }
        for (StationObservation station : stations) {
            if (station.ephemerides().isEmpty() && nav != null) {
                prepared.add(RinexNavLoader.attach(station, nav));
            } else {
                prepared.add(station);
            }
        }
        return prepared;
    }

    /**
     * 单站模型先验：按历元升序调用，保证 Hatch 平滑状态连续。
     */
    private static List<Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>>> computePriors(
            List<SynchronizedEpoch> epochs, Map<String, StationObservation> byId) {
        ModelCorrectionProvider provider = new ModelCorrectionProvider();
        List<Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>>> priors =
                new ArrayList<>(epochs.size());
        for (SynchronizedEpoch epoch : epochs) {
            Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> byStation =
                    new LinkedHashMap<>();
            for (String id : epoch.stationIds()) {
                StationObservation station = byId.get(id);
                Map<Integer, NetworkVrsProcessor.CorrectionValue> bySat = new LinkedHashMap<>();
                List<Obsd> observations = epoch.observations(id);
                if (station != null && observations != null) {
                    for (Obsd obs : observations) {
                        NetworkVrsProcessor.CorrectionValue value = provider.compute(station, obs);
                        if (value != null) {
                            bySat.put(obs.sat, value);
                        }
                    }
                }
                byStation.put(id, bySat);
            }
            priors.add(byStation);
        }
        return priors;
    }

    private static Nav navOf(StationObservation station, Nav fallback) {
        if (fallback != null) {
            return fallback;
        }
        Nav nav = new Nav();
        for (org.rtklib.java.data.Eph eph : station.ephemerides()) {
            if (eph.sat > 0 && eph.sat - 1 < nav.eph.length) {
                nav.eph[eph.sat - 1] = new org.rtklib.java.data.Eph(eph);
            }
        }
        return nav;
    }

    private static String key(BaselineNetwork.Baseline baseline) {
        return baseline.stationA() + "|" + baseline.stationB();
    }
}
