package org.rtklib.java.networkvrs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.sync.StationObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkVrsProcessorTest {

    private static final double D2R = Math.PI / 180.0;

    @Test
    @DisplayName("三站两历元：插值到站点位置时取该站改正量")
    void testProcessEpochs() {
        StationObservation a = station("A", 30.00, 95.000);
        StationObservation b = station("B", 30.09, 95.000);
        StationObservation c = station("C", 30.00, 95.100);

        Map<String, Double> ionoByStation = Map.of("A", 1.0, "B", 2.0, "C", 3.0);
        NetworkVrsProcessor.CorrectionProvider provider = (station, obs) ->
                new NetworkVrsProcessor.CorrectionValue(ionoByStation.get(station.id()), 0.0, 0.0);

        NetworkVrsProcessor processor = new NetworkVrsProcessor(new NetworkConfig(), provider);
        List<NetworkVrsProcessor.EpochCorrection> epochs =
                processor.process(List.of(a, b, c), a.pos());

        assertEquals(2, epochs.size());
        for (NetworkVrsProcessor.EpochCorrection epoch : epochs) {
            assertEquals(2, epoch.corrections().size());
            NetworkVrsProcessor.CorrectionValue value = epoch.corrections().get(1);
            assertEquals(1.0, value.iono(), 1e-6);
            assertEquals(0.0, value.tropo(), 1e-12);
        }
    }

    @Test
    @DisplayName("相对主站改正：主站处为零、其他站为相对增量")
    void testProcessRelative() {
        StationObservation a = station("A", 30.00, 95.000);
        StationObservation b = station("B", 30.09, 95.000);
        StationObservation c = station("C", 30.00, 95.100);

        Map<String, Double> ionoByStation = Map.of("A", 1.0, "B", 3.0, "C", 5.0);
        NetworkVrsProcessor.CorrectionProvider provider = (station, obs) ->
                new NetworkVrsProcessor.CorrectionValue(ionoByStation.get(station.id()), 0.0, 0.0);

        NetworkVrsProcessor processor = new NetworkVrsProcessor(new NetworkConfig(), provider);

        List<NetworkVrsProcessor.EpochCorrection> atMaster =
                processor.processRelative(List.of(a, b, c), a.pos(), "A");
        assertEquals(2, atMaster.size());
        for (NetworkVrsProcessor.EpochCorrection epoch : atMaster) {
            assertEquals(0.0, epoch.corrections().get(1).iono(), 1e-9);
        }

        List<NetworkVrsProcessor.EpochCorrection> atB =
                processor.processRelative(List.of(a, b, c), b.pos(), "A");
        for (NetworkVrsProcessor.EpochCorrection epoch : atB) {
            assertEquals(2.0, epoch.corrections().get(1).iono(), 1e-9);
        }
    }

    @Test
    @DisplayName("相对主站改正：主站不存在时抛异常")
    void testProcessRelativeMissingMaster() {
        StationObservation a = station("A", 30.00, 95.000);
        NetworkVrsProcessor processor = new NetworkVrsProcessor(new NetworkConfig(),
                (station, obs) -> new NetworkVrsProcessor.CorrectionValue(1.0, 0.0, 0.0));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> processor.processRelative(List.of(a), a.pos(), "X"));
    }

    @Test
    @DisplayName("无共视卫星时器不产出改正")
    void testNoCommonSatellites() {
        StationObservation a = station("A", 30.00, 95.000, new int[]{1});
        StationObservation b = station("B", 30.09, 95.000, new int[]{2});
        StationObservation c = station("C", 30.00, 95.100, new int[]{3});

        NetworkVrsProcessor processor = new NetworkVrsProcessor(new NetworkConfig(),
                (station, obs) -> new NetworkVrsProcessor.CorrectionValue(1.0, 0.0, 0.0));

        assertTrue(processor.process(List.of(a, b, c), a.pos()).isEmpty());
        assertFalse(a.observations().isEmpty());
    }

    private static StationObservation station(String id, double latDeg, double lonDeg) {
        return station(id, latDeg, lonDeg, new int[]{1, 2});
    }

    private static StationObservation station(String id, double latDeg, double lonDeg, int[] sats) {
        double[] ecef = new double[3];
        CoordTransform.pos2ecef(new double[]{latDeg * D2R, lonDeg * D2R, 100.0}, ecef);

        List<Obsd> observations = new ArrayList<>();
        for (long t : new long[]{0, 30}) {
            for (int sat : sats) {
                Obsd obs = new Obsd();
                obs.time = new GTime(t, 0.0);
                obs.sat = sat;
                observations.add(obs);
            }
        }
        return new StationObservation(id, ecef, observations);
    }
}
