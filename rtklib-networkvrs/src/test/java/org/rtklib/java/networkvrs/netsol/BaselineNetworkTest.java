package org.rtklib.java.networkvrs.netsol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.networkvrs.sync.StationObservation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineNetworkTest {

    private static final double D2R = Math.PI / 180.0;

    @Test
    @DisplayName("三站构成三角形：三条基线且连通")
    void testTriangle() {
        List<StationObservation> stations = List.of(
                station("A", 30.00, 95.00),
                station("B", 30.00, 95.10),
                station("C", 30.10, 95.05));

        BaselineNetwork network = BaselineNetwork.build(stations, 150000.0);
        assertEquals(3, network.baselines().size());
        assertTrue(network.connected());
        assertTrue(network.geometryRatio() > 0.3);
        assertFalse(network.geometryDegenerate(0.05));
    }

    @Test
    @DisplayName("三站近共线：几何退化")
    void testCollinear() {
        List<StationObservation> stations = List.of(
                station("A", 30.00, 95.00),
                station("B", 30.05, 95.00),
                station("C", 30.10, 95.00));

        BaselineNetwork network = BaselineNetwork.build(stations, 150000.0);
        assertTrue(network.geometryRatio() < 0.01);
        assertTrue(network.geometryDegenerate(0.05));
    }

    @Test
    @DisplayName("超过四站只保留最短边（生成树）")
    void testSpanningTree() {
        List<StationObservation> stations = List.of(
                station("A", 30.00, 95.00),
                station("B", 30.02, 95.00),
                station("C", 30.04, 95.00),
                station("D", 30.02, 95.04),
                station("E", 30.06, 95.04));

        BaselineNetwork network = BaselineNetwork.build(stations, 150000.0);
        assertEquals(stations.size() - 1, network.baselines().size());
        assertTrue(network.connected());
    }

    private static StationObservation station(String id, double latDeg, double lonDeg) {
        double[] pos = {latDeg * D2R, lonDeg * D2R, 100.0};
        double[] ecef = new double[3];
        CoordTransform.pos2ecef(pos, ecef);
        return new StationObservation(id, ecef, List.<Obsd>of());
    }
}
