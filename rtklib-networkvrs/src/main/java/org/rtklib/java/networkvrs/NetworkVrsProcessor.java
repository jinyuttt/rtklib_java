package org.rtklib.java.networkvrs;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.interp.CorrectionInterpolator;
import org.rtklib.java.networkvrs.interp.FkpInterpolator;
import org.rtklib.java.networkvrs.interp.IdwInterpolator;
import org.rtklib.java.networkvrs.interp.StationCorrection;
import org.rtklib.java.networkvrs.netsol.NetworkSolutionEngine;
import org.rtklib.java.networkvrs.sync.MultiStationSynchronizer;
import org.rtklib.java.networkvrs.sync.StationObservation;
import org.rtklib.java.networkvrs.sync.SynchronizedEpoch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网络 VRS 主入口（档 1 骨架）。
 *
 * <p>流程：多站观测 → 历元对齐 → 逐卫星收集各站改正量 → IDW 插值到流动站位置。
 * 本阶段不做站间网解，色散/非色散改正量由 {@link CorrectionProvider} 提供
 * （档 2 接入网解与误差分解后替换实现）；插值结果后续交给 rtklib-vrs 生成 RTCM。</p>
 */
public class NetworkVrsProcessor {

    /**
     * 单站单星改正量：[色散项(电离层), 非色散项(对流层+轨道残差), 轨道残差]，单位米。
     */
    public record CorrectionValue(double iono, double tropo, double orbit) {
    }

    /**
     * 单历元插值结果：卫星号 -> 流动站位置改正量。
     */
    public record EpochCorrection(GTime time, Map<Integer, CorrectionValue> corrections) {
    }

    /**
     * 单站单星改正量计算扩展点。
     */
    @FunctionalInterface
    public interface CorrectionProvider {
        /**
         * @param station 基准站
         * @param obs     该站在当前历元该卫星的观测
         * @return 改正量；返回 null 表示该样本不可用
         */
        CorrectionValue compute(StationObservation station, Obsd obs);
    }

    private final NetworkConfig config;
    private final CorrectionProvider provider;
    private final CorrectionInterpolator interpolator;
    private final MultiStationSynchronizer synchronizer;

    public NetworkVrsProcessor(NetworkConfig config, CorrectionProvider provider) {
        this(config, provider, createInterpolator(config));
    }

    /**
     * @return 按配置选择插值模型（默认 IDW；档 2 可切 FKP）
     */
    private static CorrectionInterpolator createInterpolator(NetworkConfig config) {
        if (config.interpolationModel == NetworkConfig.InterpolationModel.FKP) {
            return new FkpInterpolator(config);
        }
        return new IdwInterpolator(config.idwPower);
    }

    public NetworkVrsProcessor(NetworkConfig config, CorrectionProvider provider,
                               CorrectionInterpolator interpolator) {
        this.config = config;
        this.provider = provider;
        this.interpolator = interpolator;
        this.synchronizer = new MultiStationSynchronizer(config.epochToleranceSec, config.minStations);
    }

    /**
     * @param stations  各基准站观测
     * @param targetPos 流动站大地坐标 [lat(rad), lon(rad), height(m)]
     * @return 逐历元插值结果（无有效卫星的历元不输出）
     */
    public List<EpochCorrection> process(List<StationObservation> stations, double[] targetPos) {
        return process(stations, targetPos, null);
    }

    /**
     * 相对改正模式：以主站为零点，插值其余各站相对主站的增量，再交给 VRS 生成层。
     *
     * <p>与 {@link #process} 的区别在于输出语义：这里每颗卫星的改正量是
     * "流动站处相对主站的增量"，配合主站观测即可还原虚拟站观测，供
     * rtklib-vrs 的 {@code VrsCorrectionProvider} 使用。主站缺失该卫星时整颗卫星跳过。</p>
     *
     * @param masterId 主站名，必须存在于 {@code stations} 中
     */
    public List<EpochCorrection> processRelative(List<StationObservation> stations,
                                                 double[] targetPos, String masterId) {
        if (masterId == null || masterId.isEmpty()) {
            throw new IllegalArgumentException("masterId is empty");
        }
        boolean found = false;
        for (StationObservation station : stations) {
            if (station.id().equals(masterId)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("master station not found: " + masterId);
        }
        return process(stations, targetPos, masterId);
    }

    /**
     * 档 2：走网解与误差分解，再用 IDW/FKP 插值到流动站。
     *
     * <p>与 {@link #processRelative} 的输出契约一致（相对主站增量），但改正量来自
     * 基线网双差解与误差分解，而非单站模型；档 1 行为保持不变。</p>
     *
     * @param stations 各站观测
     * @param nav      广播星历（可为 null，此时使用各站自带星历）
     * @param targetPos 流动站大地坐标 [lat(rad), lon(rad), height(m)]
     * @param masterId 主站
     */
    public List<EpochCorrection> processNetwork(List<StationObservation> stations, Nav nav,
                                                double[] targetPos, String masterId) {
        NetworkSolutionEngine.Result solved =
                new NetworkSolutionEngine().solve(stations, nav, masterId, config);
        Map<String, double[]> ecefById = new LinkedHashMap<>();
        for (StationObservation station : stations) {
            ecefById.put(station.id(), station.ecef());
        }

        List<EpochCorrection> result = new ArrayList<>(solved.epochs().size());
        for (NetworkSolutionEngine.EpochCorrections epoch : solved.epochs()) {
            Map<Integer, CorrectionValue> corrections = new LinkedHashMap<>();
            Set<Integer> sats = new LinkedHashSet<>();
            for (Map<Integer, CorrectionValue> bySat : epoch.byStation().values()) {
                sats.addAll(bySat.keySet());
            }
            for (int sat : sats) {
                List<StationCorrection> iono = new ArrayList<>();
                List<StationCorrection> tropo = new ArrayList<>();
                List<StationCorrection> orbit = new ArrayList<>();
                for (Map.Entry<String, Map<Integer, CorrectionValue>> entry
                        : epoch.byStation().entrySet()) {
                    CorrectionValue value = entry.getValue().get(sat);
                    double[] ecef = ecefById.get(entry.getKey());
                    if (value == null || ecef == null) {
                        continue;
                    }
                    iono.add(new StationCorrection(entry.getKey(), ecef, value.iono()));
                    tropo.add(new StationCorrection(entry.getKey(), ecef, value.tropo()));
                    orbit.add(new StationCorrection(entry.getKey(), ecef, value.orbit()));
                }
                if (iono.size() < config.minStations) {
                    continue;
                }
                corrections.put(sat, new CorrectionValue(
                        interpolator.interpolate(targetPos, iono),
                        interpolator.interpolate(targetPos, tropo),
                        interpolator.interpolate(targetPos, orbit)));
            }
            if (!corrections.isEmpty()) {
                result.add(new EpochCorrection(epoch.time(), corrections));
            }
        }
        return result;
    }

    /**
     * @param masterId 非 null 时按相对主站模式输出，null 时输出绝对改正量
     */
    private List<EpochCorrection> process(List<StationObservation> stations,
                                          double[] targetPos, String masterId) {
        Map<String, StationObservation> byId = new LinkedHashMap<>();
        for (StationObservation station : stations) {
            byId.put(station.id(), station);
        }

        List<SynchronizedEpoch> epochs = synchronizer.synchronize(stations);
        List<EpochCorrection> result = new ArrayList<>(epochs.size());
        for (SynchronizedEpoch epoch : epochs) {
            Map<Integer, CorrectionValue> corrections = new LinkedHashMap<>();
            for (int sat : epoch.commonSatellites()) {
                // 主站每颗卫星只计算一次：既作为相对零点，也避免重复推进平滑状态
                CorrectionValue masterValue = null;
                if (masterId != null) {
                    StationObservation master = byId.get(masterId);
                    Obsd masterObs = findSatellite(epoch.observations(masterId), sat);
                    if (master == null || masterObs == null) {
                        continue;
                    }
                    masterValue = provider.compute(master, masterObs);
                    if (masterValue == null) {
                        continue;
                    }
                }

                List<StationCorrection> iono = new ArrayList<>();
                List<StationCorrection> tropo = new ArrayList<>();
                List<StationCorrection> orbit = new ArrayList<>();
                for (String stationId : epoch.stationIds()) {
                    StationObservation station = byId.get(stationId);
                    Obsd obs = findSatellite(epoch.observations(stationId), sat);
                    if (station == null || obs == null) {
                        continue;
                    }
                    CorrectionValue value;
                    if (masterId != null && stationId.equals(masterId)) {
                        value = masterValue;
                    } else {
                        value = provider.compute(station, obs);
                    }
                    if (value == null) {
                        continue;
                    }
                    if (masterValue != null) {
                        value = new CorrectionValue(
                                value.iono() - masterValue.iono(),
                                value.tropo() - masterValue.tropo(),
                                value.orbit() - masterValue.orbit());
                    }
                    double[] ecef = station.ecef();
                    iono.add(new StationCorrection(stationId, ecef, value.iono()));
                    tropo.add(new StationCorrection(stationId, ecef, value.tropo()));
                    orbit.add(new StationCorrection(stationId, ecef, value.orbit()));
                }
                if (iono.size() < config.minStations) {
                    continue;
                }
                corrections.put(sat, new CorrectionValue(
                        interpolator.interpolate(targetPos, iono),
                        interpolator.interpolate(targetPos, tropo),
                        interpolator.interpolate(targetPos, orbit)));
            }
            if (!corrections.isEmpty()) {
                result.add(new EpochCorrection(epoch.time(), corrections));
            }
        }
        return result;
    }

    private static Obsd findSatellite(List<Obsd> observations, int sat) {
        for (Obsd obs : observations) {
            if (obs.sat == sat) {
                return obs;
            }
        }
        return null;
    }
}
