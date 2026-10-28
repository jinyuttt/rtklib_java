package org.rtklib.java.networkvrs.mac;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.EphSelector;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.netsol.NetworkSolutionEngine;
import org.rtklib.java.networkvrs.sync.StationObservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MAC 改正合成：把网解产出的「各站相对主站改正量」整理成可播发的
 * {@link MacCorrectionSet}。
 *
 * <p>三条硬约束（对应设计文档 §5.3.5 / §5.3.6）：</p>
 * <ol>
 *   <li>辅站按到主站的距离升序排列（RTCM 1014 要求）；</li>
 *   <li>改正差必须带广播星历 IODE / GLONASS IOD，选不到星历的卫星剔除并计数；</li>
 *   <li>只播发 {@link NetworkConfig#macBroadcastSystems} 内的系统（默认仅 GPS）。</li>
 * </ol>
 */
public final class MacCorrectionBuilder {

    private static final Logger log = LoggerFactory.getLogger(MacCorrectionBuilder.class);

    private final NetworkConfig config;

    public MacCorrectionBuilder(NetworkConfig config) {
        this.config = config;
        config.validate();
    }

    /**
     * @param solved    网解结果（{@link NetworkSolutionEngine#solve}）
     * @param stations  参与解算的站（用于取辅站坐标）
     * @param nav       广播星历；为 null 时用主站自带星历构造
     * @param masterId  主站名
     * @return 逐历元的 MAC 改正集合（无可播发内容的历元不输出）
     */
    public List<MacCorrectionSet> build(NetworkSolutionEngine.Result solved,
                                        List<StationObservation> stations,
                                        Nav nav, String masterId) {
        if (masterId == null || masterId.isEmpty()) {
            throw new IllegalArgumentException("masterId is empty");
        }
        Map<String, StationObservation> byId = new LinkedHashMap<>();
        for (StationObservation station : stations) {
            byId.put(station.id(), station);
        }
        StationObservation master = byId.get(masterId);
        if (master == null) {
            throw new IllegalArgumentException("master station not found: " + masterId);
        }
        double[] masterEcef = master.ecef();
        Nav effectiveNav = nav != null ? nav : navFrom(master.ephemerides());

        List<MacCorrectionSet> result = new ArrayList<>();
        for (NetworkSolutionEngine.EpochCorrections epoch : solved.epochs()) {
            Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> byStation =
                    epoch.byStation();
            Map<Integer, NetworkVrsProcessor.CorrectionValue> masterCorr =
                    byStation.get(masterId);
            if (masterCorr == null || masterCorr.isEmpty()) {
                continue;
            }

            Set<Integer> dropped = new LinkedHashSet<>();
            List<MacCorrectionSet.AuxStation> auxList = new ArrayList<>();
            for (Map.Entry<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> entry
                    : byStation.entrySet()) {
                if (entry.getKey().equals(masterId)) {
                    continue;
                }
                StationObservation aux = byId.get(entry.getKey());
                if (aux == null) {
                    continue;
                }
                Map<Integer, MacCorrectionSet.SatelliteCorrection> bySat = new LinkedHashMap<>();
                for (Integer sat : masterCorr.keySet()) {
                    NetworkVrsProcessor.CorrectionValue value = entry.getValue().get(sat);
                    if (value == null) {
                        continue;
                    }
                    int sys = SatUtils.satsys(sat, null);
                    if ((config.macBroadcastSystems & sys) == 0) {
                        continue;
                    }
                    Integer iode = iodeOf(effectiveNav, sat, epoch.time());
                    if (iode == null) {
                        dropped.add(sat);
                        continue;
                    }
                    bySat.put(sat, new MacCorrectionSet.SatelliteCorrection(
                            sat, iode, value.iono(), value.tropo() + value.orbit()));
                }
                if (bySat.isEmpty()) {
                    continue;
                }
                auxList.add(new MacCorrectionSet.AuxStation(
                        entry.getKey(), aux.ecef(), distance(masterEcef, aux.ecef()), bySat));
            }

            if (!dropped.isEmpty()) {
                log.warn("MAC: {} 卫星缺广播星历 IODE，已剔除: {}", dropped.size(), dropped);
            }
            if (auxList.isEmpty()) {
                continue;
            }
            auxList.sort(Comparator.comparingDouble(MacCorrectionSet.AuxStation::distance));
            result.add(new MacCorrectionSet(epoch.time(), config.macNetworkId, masterId,
                    masterEcef, auxList, dropped.size()));
        }
        return result;
    }

    private static Integer iodeOf(Nav nav, int sat, GTime time) {
        if (nav == null) {
            return null;
        }
        if (SatUtils.satsys(sat, null) == Constants.SYS_GLO) {
            Geph geph = EphSelector.selectGeph(null, nav, sat, time);
            return geph == null ? null : geph.iode;
        }
        Eph eph = EphSelector.selectEph(null, nav, sat, time);
        return eph == null ? null : eph.iode;
    }

    private static Nav navFrom(List<Eph> ephemerides) {
        Nav nav = new Nav();
        for (Eph eph : ephemerides) {
            if (eph.sat > 0 && eph.sat - 1 < nav.nmax) {
                nav.eph[eph.sat - 1] = new Eph(eph);
                if (eph.sat > nav.n) {
                    nav.n = eph.sat;
                }
            }
        }
        return nav;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}