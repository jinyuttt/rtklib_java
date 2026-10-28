package org.rtklib.java.networkvrs.mac;

import org.rtklib.java.data.GTime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单历元的 MAC 改正集合：主站标识 + 按距离升序排列的辅站改正差。
 *
 * <p>语义与 RTCM 10403.x 的 MAC 一致：主站观测原样播发，辅站只播发
 * 「相对主站」的电离层差与几何差，流动站在辅站之间内插。</p>
 */
public final class MacCorrectionSet {

    /**
     * 单星改正差。
     *
     * @param sat           卫星号（1-based，含系统偏移）
     * @param iode          广播星历 IODE / GLONASS IOD，流动站按此匹配星历
     * @param ionoDiff      电离层改正差（米）
     * @param geometricDiff 几何改正差（非色散项：对流层 + 轨道残差，米）
     */
    public record SatelliteCorrection(int sat, int iode, double ionoDiff, double geometricDiff) {
    }

    /**
     * 辅站改正。
     *
     * @param stationId 辅站名
     * @param ecef      辅站 ECEF（米）
     * @param distance  辅站到主站的距离（米）
     * @param bySat     卫星号 -> 改正差
     */
    public record AuxStation(String stationId, double[] ecef, double distance,
                             Map<Integer, SatelliteCorrection> bySat) {
        public AuxStation {
            if (stationId == null || stationId.isEmpty()) {
                throw new IllegalArgumentException("stationId is empty");
            }
            if (ecef == null || ecef.length != 3) {
                throw new IllegalArgumentException("ecef must be length 3");
            }
            ecef = ecef.clone();
            bySat = Map.copyOf(bySat);
        }
    }

    private final GTime time;
    private final int networkId;
    private final String masterId;
    private final double[] masterEcef;
    private final List<AuxStation> auxStations;
    private final int droppedSatellites;

    public MacCorrectionSet(GTime time, int networkId, String masterId, double[] masterEcef,
                            List<AuxStation> auxStations, int droppedSatellites) {
        if (masterId == null || masterId.isEmpty()) {
            throw new IllegalArgumentException("masterId is empty");
        }
        if (masterEcef == null || masterEcef.length != 3) {
            throw new IllegalArgumentException("masterEcef must be length 3");
        }
        this.time = time;
        this.networkId = networkId;
        this.masterId = masterId;
        this.masterEcef = masterEcef.clone();
        this.auxStations = List.copyOf(auxStations);
        this.droppedSatellites = droppedSatellites;
    }

    public GTime time() {
        return time;
    }

    public int networkId() {
        return networkId;
    }

    public String masterId() {
        return masterId;
    }

    public double[] masterEcef() {
        return masterEcef.clone();
    }

    /** @return 按到主站距离升序排列的辅站 */
    public List<AuxStation> auxStations() {
        return auxStations;
    }

    /** @return 因缺广播星历 IODE 而被剔除的卫星数 */
    public int droppedSatellites() {
        return droppedSatellites;
    }

    /** @return 参与播发的卫星号（各辅站改正差的并集，按升序） */
    public List<Integer> satellites() {
        Map<Integer, Boolean> seen = new LinkedHashMap<>();
        for (AuxStation aux : auxStations) {
            for (Integer sat : aux.bySat().keySet()) {
                seen.putIfAbsent(sat, Boolean.TRUE);
            }
        }
        return seen.keySet().stream().sorted().toList();
    }

    /** @return 是否可用于播发（至少一个辅站有改正差） */
    public boolean isBroadcastable() {
        for (AuxStation aux : auxStations) {
            if (!aux.bySat().isEmpty()) return true;
        }
        return false;
    }
}