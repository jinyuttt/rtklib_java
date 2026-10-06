package org.rtklib.java.research.data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import org.rtklib.java.research.common.GTime;

/**
 * 导航数据容器（research模块自有）。
 *
 * <p>存储所有卫星的广播星历，支持按卫星号快速查找。</p>
 */
public class Navigation implements Serializable {
    private static final long serialVersionUID = 1L;

    public Map<Integer, Ephemeris> ephemerisMap;
    public GTime referenceTime;

    public Navigation() {
        this.ephemerisMap = new HashMap<>();
        this.referenceTime = new GTime();
    }

    public void putEphemeris(int sat, Ephemeris eph) {
        ephemerisMap.put(sat, eph);
    }

    public Ephemeris getEphemeris(int sat) {
        return ephemerisMap.get(sat);
    }

    public boolean hasEphemeris(int sat) {
        return ephemerisMap.containsKey(sat);
    }

    public int satelliteCount() {
        return ephemerisMap.size();
    }

    @Override
    public String toString() {
        return String.format("Nav[nSat=%d, t0=%s]", ephemerisMap.size(), referenceTime);
    }
}