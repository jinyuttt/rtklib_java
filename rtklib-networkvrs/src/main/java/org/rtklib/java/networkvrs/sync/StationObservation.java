package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Obsd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个基准站的观测输入。
 *
 * <p>站点坐标取自 RINEX 头 APPROX POSITION XYZ 或 RTCM 1005/1006（ECEF，米），
 * 观测为按时间升序排列的 {@link Obsd} 列表，另附该站的广播星历
 * （用于计算卫星高度角与对流层模型；RINEX 装载时为空）。</p>
 */
public final class StationObservation {

    private final String id;
    private final double[] ecef;
    private final double[] pos;
    private final List<Obsd> observations;
    private final List<Eph> ephemerides;

    /**
     * @param id           站名（唯一标识）
     * @param ecef         站坐标 ECEF（米，长度 3）
     * @param observations 该站全部历元观测
     */
    public StationObservation(String id, double[] ecef, List<Obsd> observations) {
        this(id, ecef, observations, List.of());
    }

    /**
     * @param id           站名（唯一标识）
     * @param ecef         站坐标 ECEF（米，长度 3）
     * @param observations 该站全部历元观测
     * @param ephemerides  广播星历（可为空）
     */
    public StationObservation(String id, double[] ecef, List<Obsd> observations, List<Eph> ephemerides) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("station id is empty");
        }
        if (ecef == null || ecef.length != 3) {
            throw new IllegalArgumentException("ecef must be length 3");
        }
        this.id = id;
        this.ecef = ecef.clone();
        this.pos = new double[3];
        CoordTransform.ecef2pos(this.ecef, this.pos);
        this.observations = List.copyOf(observations);
        this.ephemerides = ephemerides == null ? List.of() : List.copyOf(ephemerides);
    }

    public String id() {
        return id;
    }

    /** @return ECEF 坐标副本（米） */
    public double[] ecef() {
        return ecef.clone();
    }

    /** @return 大地坐标副本 [lat(rad), lon(rad), height(m)] */
    public double[] pos() {
        return pos.clone();
    }

    public List<Obsd> observations() {
        return observations;
    }

    /** @return 该站广播星历（可能为空） */
    public List<Eph> ephemerides() {
        return ephemerides;
    }

    /**
     * 按绝对时间（time + sec）分组，保持历元升序。
     *
     * @return 绝对秒 -> 该历元观测
     */
    public Map<Double, List<Obsd>> groupByEpoch() {
        Map<Double, List<Obsd>> grouped = new LinkedHashMap<>();
        List<Double> keys = new ArrayList<>();
        for (Obsd obs : observations) {
            double t = obs.time.time + obs.time.sec;
            List<Obsd> list = grouped.get(t);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(t, list);
                keys.add(t);
            }
            list.add(obs);
        }
        if (keys.size() > 1) {
            keys.sort(Double::compareTo);
            Map<Double, List<Obsd>> sorted = new LinkedHashMap<>();
            for (Double k : keys) {
                sorted.put(k, grouped.get(k));
            }
            return sorted;
        }
        return grouped;
    }
}
