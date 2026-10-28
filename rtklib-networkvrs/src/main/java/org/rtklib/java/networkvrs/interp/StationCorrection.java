package org.rtklib.java.networkvrs.interp;

/**
 * 单个基准站对单颗卫星的改正量样本。
 *
 * @param stationId 站名
 * @param ecef      站坐标 ECEF（米）
 * @param value     改正量（米；色散/非色散分量由调用方区分）
 */
public record StationCorrection(String stationId, double[] ecef, double value) {

    public StationCorrection {
        if (stationId == null || stationId.isEmpty()) {
            throw new IllegalArgumentException("stationId is empty");
        }
        if (ecef == null || ecef.length != 3) {
            throw new IllegalArgumentException("ecef must be length 3");
        }
        ecef = ecef.clone();
    }
}
