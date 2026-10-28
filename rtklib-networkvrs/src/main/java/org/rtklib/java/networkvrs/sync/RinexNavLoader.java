package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.rinex.RinexParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 RINEX 3 混合导航文件（如 BRDC00WRD_R_..._MN.rnx）加载广播星历。
 *
 * <p>只取 GPS/GAL/BDS/QZS/IRN 的 {@link Eph}（GLONASS 星历在 {@code nav.geph}，
 * 档 2 网解暂不使用）；装载后可附到 {@link StationObservation} 供卫星高度角与
 * 对流层模型使用。</p>
 */
public final class RinexNavLoader {

    private RinexNavLoader() {
    }

    /**
     * @param rinexNav RINEX 3 导航文件
     * @return 导航数据
     */
    public static Nav load(Path rinexNav) {
        RinexParser parser = new RinexParser();
        if (!parser.parseNav(rinexNav.toString())) {
            throw new IllegalArgumentException("无法解析 RINEX 导航文件: " + rinexNav);
        }
        return parser.nav;
    }

    /**
     * 把导航文件中的卫星星历附到站观测（保留站名、坐标与观测）。
     */
    public static StationObservation attach(StationObservation station, Nav nav) {
        return new StationObservation(station.id(), station.ecef(),
                station.observations(), ephemerides(nav));
    }

    /**
     * @return 导航中的有效星历列表（不含空槽位）
     */
    public static List<Eph> ephemerides(Nav nav) {
        List<Eph> list = new ArrayList<>();
        if (nav == null || nav.eph == null) {
            return list;
        }
        for (Eph eph : nav.eph) {
            if (eph != null && eph.sat > 0) {
                list.add(new Eph(eph));
            }
        }
        return list;
    }
}
