package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.data.Obsd;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 多站历元对齐。
 *
 * <p>以历元最多的站为参考，逐个历元在其他站的 15s/30s 网格上寻找容差内最近历元；
 * 匹配站数达到 {@code minStations} 才输出。不同站网格存在相位差（实测 0/3/12 s）时，
 * 超出容差的历元会被丢弃而不是硬凑。</p>
 */
public class MultiStationSynchronizer {

    private final double toleranceSec;
    private final int minStations;

    /**
     * @param toleranceSec 历元匹配容差（秒）
     * @param minStations  输出所需最少站数
     */
    public MultiStationSynchronizer(double toleranceSec, int minStations) {
        if (toleranceSec < 0) {
            throw new IllegalArgumentException("toleranceSec must be >= 0");
        }
        if (minStations < 1) {
            throw new IllegalArgumentException("minStations must be >= 1");
        }
        this.toleranceSec = toleranceSec;
        this.minStations = minStations;
    }

    /**
     * @param stations 各站观测（顺序无关）
     * @return 按时间升序的同步历元
     */
    public List<SynchronizedEpoch> synchronize(List<StationObservation> stations) {
        if (stations.isEmpty()) {
            return List.of();
        }

        Map<String, NavigableMap<Double, List<Obsd>>> grids = new LinkedHashMap<>();
        for (StationObservation station : stations) {
            grids.put(station.id(), new TreeMap<>(station.groupByEpoch()));
        }

        // 参考站：历元最多者，避免用稀疏站主导对齐
        String reference = stations.stream()
                .max(Comparator.comparingInt(s -> s.groupByEpoch().size()))
                .map(StationObservation::id)
                .orElseThrow();

        NavigableMap<Double, List<Obsd>> refGrid = grids.get(reference);
        List<SynchronizedEpoch> epochs = new ArrayList<>(refGrid.size());
        for (Map.Entry<Double, List<Obsd>> entry : refGrid.entrySet()) {
            double t = entry.getKey();
            Map<String, List<Obsd>> matched = new LinkedHashMap<>();
            matched.put(reference, entry.getValue());
            for (Map.Entry<String, NavigableMap<Double, List<Obsd>>> g : grids.entrySet()) {
                if (g.getKey().equals(reference)) {
                    continue;
                }
                Double key = nearest(g.getValue(), t, toleranceSec);
                if (key != null) {
                    matched.put(g.getKey(), g.getValue().get(key));
                }
            }
            if (matched.size() >= minStations) {
                epochs.add(new SynchronizedEpoch(entry.getValue().get(0).time, matched));
            }
        }
        return epochs;
    }

    /**
     * 在网格上寻找容差内最近历元。
     *
     * @return 匹配的历元键，无匹配返回 null
     */
    private static Double nearest(NavigableMap<Double, List<Obsd>> grid, double t, double tolerance) {
        Map.Entry<Double, List<Obsd>> floor = grid.floorEntry(t);
        Map.Entry<Double, List<Obsd>> ceil = grid.ceilingEntry(t);
        Double best = null;
        double bestDiff = Double.MAX_VALUE;
        if (floor != null) {
            best = floor.getKey();
            bestDiff = Math.abs(best - t);
        }
        if (ceil != null) {
            double diff = Math.abs(ceil.getKey() - t);
            if (diff < bestDiff) {
                best = ceil.getKey();
                bestDiff = diff;
            }
        }
        return bestDiff <= tolerance ? best : null;
    }
}
