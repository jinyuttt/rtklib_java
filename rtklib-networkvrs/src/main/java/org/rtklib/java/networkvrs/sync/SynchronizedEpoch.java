package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个多站同步历元：同一时刻、多个基准站的观测。
 */
public final class SynchronizedEpoch {

    private final GTime time;
    private final Map<String, List<Obsd>> observations;

    /**
     * @param time         历元时间（以参考站时间为准）
     * @param observations 站名 -> 该历元观测
     */
    public SynchronizedEpoch(GTime time, Map<String, List<Obsd>> observations) {
        this.time = new GTime(time);
        Map<String, List<Obsd>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Obsd>> e : observations.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.observations = Collections.unmodifiableMap(copy);
    }

    public GTime time() {
        return new GTime(time);
    }

    /** @return 参与本历元的站名（保持输入顺序） */
    public Set<String> stationIds() {
        return observations.keySet();
    }

    public int stationCount() {
        return observations.size();
    }

    /** @return 指定站本历元的观测；站不存在返回空列表 */
    public List<Obsd> observations(String stationId) {
        List<Obsd> list = observations.get(stationId);
        return list == null ? List.of() : list;
    }

    /** @return 本历元所有站出现过的卫星号并集 */
    public Set<Integer> satellites() {
        Set<Integer> sats = new LinkedHashSet<>();
        for (List<Obsd> list : observations.values()) {
            for (Obsd obs : list) {
                sats.add(obs.sat);
            }
        }
        return sats;
    }

    /** @return 至少在一个站上出现的所有站都共有的卫星号交集（站数不足时为空） */
    public Set<Integer> commonSatellites() {
        if (observations.isEmpty()) {
            return Set.of();
        }
        Set<Integer> common = null;
        for (List<Obsd> list : observations.values()) {
            Set<Integer> sats = new LinkedHashSet<>();
            for (Obsd obs : list) {
                sats.add(obs.sat);
            }
            if (common == null) {
                common = sats;
            } else {
                common.retainAll(sats);
            }
        }
        return common == null ? Set.of() : common;
    }
}
