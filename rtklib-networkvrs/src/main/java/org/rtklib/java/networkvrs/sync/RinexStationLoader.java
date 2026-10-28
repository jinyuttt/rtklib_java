package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.data.Obsd;
import org.rtklib.java.rinex.RinexParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 RINEX 3 观测文件构建 {@link StationObservation}。
 *
 * <p>坐标使用头文件 APPROX POSITION XYZ（未做天线 PCO 改正），
 * 精度足够用于网络 RTK 的几何与插值；相位中心改正留待后续阶段。</p>
 */
public final class RinexStationLoader {

    private RinexStationLoader() {
    }

    /**
     * @param stationId 站名（唯一标识）
     * @param rinexObs  RINEX 3 观测文件
     * @return 站点观测
     */
    public static StationObservation load(String stationId, Path rinexObs) {
        RinexParser parser = new RinexParser();
        if (!parser.parseObs(rinexObs.toString())) {
            throw new IllegalArgumentException("无法解析 RINEX 观测文件: " + rinexObs);
        }

        double[] ecef = parser.sta.pos == null ? new double[3] : parser.sta.pos.clone();
        if (ecef[0] == 0.0 && ecef[1] == 0.0 && ecef[2] == 0.0) {
            throw new IllegalStateException("RINEX 头缺少 APPROX POSITION XYZ: " + rinexObs);
        }

        List<Obsd> observations = new ArrayList<>();
        if (parser.obs.data != null) {
            for (Obsd obs : parser.obs.data) {
                if (obs != null) {
                    observations.add(obs);
                }
            }
        }
        if (observations.isEmpty()) {
            throw new IllegalStateException("RINEX 无观测数据: " + rinexObs);
        }
        return new StationObservation(stationId, ecef, observations);
    }
}
