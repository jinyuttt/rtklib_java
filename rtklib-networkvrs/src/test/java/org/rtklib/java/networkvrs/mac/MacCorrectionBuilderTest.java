package org.rtklib.java.networkvrs.mac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.netsol.NetworkSolutionEngine;
import org.rtklib.java.networkvrs.sync.StationObservation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacCorrectionBuilderTest {

    private static final GTime TIME = new GTime(2270000000L, 0.0);
    private static final double[] MASTER_ECEF = {1.0e6, 2.0e6, 3.0e6};
    private static final int CMP_SAT = SatUtils.satno(Constants.SYS_CMP, 1);

    @Test
    @DisplayName("辅站按距离升序、IODE 打标、几何差为对流层+轨道")
    void testBuildSortsAuxAndTagsIode() {
        Nav nav = new Nav();
        nav.eph[0] = eph(1, 42);

        Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> byStation =
                new LinkedHashMap<>();
        byStation.put("MASTER", corrections(new int[]{1, 2},
                new double[]{0, 0, 0}, new double[]{0, 0, 0}));
        byStation.put("FAR", corrections(new int[]{1, 2},
                new double[]{0.2, 0.1, 0.0}, new double[]{0.05, 0.02, 0.0}));
        byStation.put("NEAR", corrections(new int[]{1, 2},
                new double[]{0.1, 0.2, 0.01}, new double[]{0.01, 0.0, 0.0}));

        List<StationObservation> stations = List.of(
                station("MASTER", MASTER_ECEF),
                station("FAR", new double[]{1.2e6, 2.0e6, 3.0e6}),
                station("NEAR", new double[]{1.01e6, 2.0e6, 3.0e6}));

        List<MacCorrectionSet> sets = new MacCorrectionBuilder(new NetworkConfig())
                .build(result(byStation), stations, nav, "MASTER");

        assertEquals(1, sets.size());
        MacCorrectionSet set = sets.get(0);
        assertEquals(2, set.auxStations().size());
        assertEquals("NEAR", set.auxStations().get(0).stationId(), "近站应排在前");
        assertEquals("FAR", set.auxStations().get(1).stationId());
        assertTrue(set.auxStations().get(0).distance() < set.auxStations().get(1).distance());

        MacCorrectionSet.SatelliteCorrection near = set.auxStations().get(0).bySat().get(1);
        assertEquals(42, near.iode());
        assertEquals(0.1, near.ionoDiff(), 1e-12);
        assertEquals(0.21, near.geometricDiff(), 1e-12, "几何差 = 对流层 + 轨道残差");
        assertEquals(List.of(1), set.satellites());
        assertEquals(1, set.droppedSatellites(), "sat 2 无星历应剔除");
    }

    @Test
    @DisplayName("缺广播星历的卫星被剔除并计数")
    void testDropsSatelliteWithoutEphemeris() {
        Nav nav = new Nav();
        nav.eph[0] = eph(1, 7);

        Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> byStation =
                new LinkedHashMap<>();
        byStation.put("MASTER", corrections(new int[]{1, 2},
                new double[]{0, 0, 0}, new double[]{0, 0, 0}));
        byStation.put("AUX", corrections(new int[]{1, 2},
                new double[]{0.1, 0.1, 0.0}, new double[]{0.0, 0.0, 0.0}));

        List<StationObservation> stations = List.of(
                station("MASTER", MASTER_ECEF),
                station("AUX", new double[]{1.1e6, 2.0e6, 3.0e6}));

        List<MacCorrectionSet> sets = new MacCorrectionBuilder(new NetworkConfig())
                .build(result(byStation), stations, nav, "MASTER");

        assertEquals(1, sets.size());
        MacCorrectionSet set = sets.get(0);
        assertEquals(1, set.droppedSatellites(), "sat 2 无星历应剔除");
        assertEquals(List.of(1), set.satellites());
        assertFalse(set.auxStations().get(0).bySat().containsKey(2));
    }

    @Test
    @DisplayName("非 GPS/GLONASS 系统不参与 MAC 播发")
    void testFiltersNonBroadcastSystems() {
        Nav nav = new Nav();
        nav.eph[0] = eph(1, 11);
        nav.eph[CMP_SAT - 1] = eph(CMP_SAT, 12);

        Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> byStation =
                new LinkedHashMap<>();
        byStation.put("MASTER", corrections(new int[]{1, CMP_SAT},
                new double[]{0, 0, 0}, new double[]{0, 0, 0}));
        byStation.put("AUX", corrections(new int[]{1, CMP_SAT},
                new double[]{0.1, 0.1, 0.0}, new double[]{0.0, 0.0, 0.0}));

        List<StationObservation> stations = List.of(
                station("MASTER", MASTER_ECEF),
                station("AUX", new double[]{1.1e6, 2.0e6, 3.0e6}));

        List<MacCorrectionSet> sets = new MacCorrectionBuilder(new NetworkConfig())
                .build(result(byStation), stations, nav, "MASTER");

        assertEquals(List.of(1), sets.get(0).satellites(), "BDS 卫星不应播发");
        assertEquals(0, sets.get(0).droppedSatellites(), "被星座过滤的卫星不计入剔除");
    }

    @Test
    @DisplayName("星座配置非法时启动即失败")
    void testInvalidBroadcastSystemsRejected() {
        NetworkConfig config = new NetworkConfig();
        config.macBroadcastSystems = Constants.SYS_GAL;
        assertThrows(IllegalArgumentException.class, () -> new MacCorrectionBuilder(config));

        NetworkConfig empty = new NetworkConfig();
        empty.macBroadcastSystems = 0;
        assertThrows(IllegalArgumentException.class, () -> new MacCorrectionBuilder(empty));
    }

    private static NetworkSolutionEngine.Result result(
            Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> byStation) {
        return new NetworkSolutionEngine.Result(
                List.of(new NetworkSolutionEngine.EpochCorrections(TIME, byStation)),
                List.of(), null);
    }

    private static Map<Integer, NetworkVrsProcessor.CorrectionValue> corrections(
            int[] sats, double[] first, double[] second) {
        Map<Integer, NetworkVrsProcessor.CorrectionValue> bySat = new LinkedHashMap<>();
        bySat.put(sats[0], new NetworkVrsProcessor.CorrectionValue(first[0], first[1], first[2]));
        bySat.put(sats[1], new NetworkVrsProcessor.CorrectionValue(second[0], second[1], second[2]));
        return bySat;
    }

    private static StationObservation station(String id, double[] ecef) {
        return new StationObservation(id, ecef, List.of(), List.of());
    }

    private static Eph eph(int sat, int iode) {
        Eph eph = new Eph();
        eph.sat = sat;
        eph.iode = iode;
        eph.toe = TIME;
        return eph;
    }
}