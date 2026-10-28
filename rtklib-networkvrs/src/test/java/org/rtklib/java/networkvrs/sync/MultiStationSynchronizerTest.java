package org.rtklib.java.networkvrs.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiStationSynchronizerTest {

    private static final double D2R = Math.PI / 180.0;

    @Test
    @DisplayName("三站同网格：全部历元对齐，共视卫星取交集")
    void testAlignedGrids() {
        StationObservation a = station("A", 30.00, 95.000, new double[]{0, 30, 60}, new int[]{1, 2, 3});
        StationObservation b = station("B", 30.09, 95.000, new double[]{0, 30, 60}, new int[]{1, 2, 3});
        StationObservation c = station("C", 30.00, 95.100, new double[]{0, 30, 60}, new int[]{1, 2});

        List<SynchronizedEpoch> epochs = new MultiStationSynchronizer(0.5, 3).synchronize(List.of(a, b, c));

        assertEquals(3, epochs.size());
        for (SynchronizedEpoch epoch : epochs) {
            assertEquals(3, epoch.stationCount());
            assertEquals(2, epoch.commonSatellites().size());
        }
    }

    @Test
    @DisplayName("容差内的小相位差可对齐，超容差则丢站")
    void testTolerance() {
        StationObservation a = station("A", 30.00, 95.000, new double[]{0, 30}, new int[]{1});
        StationObservation b = station("B", 30.09, 95.000, new double[]{0.2, 30.2}, new int[]{1});
        StationObservation c = station("C", 30.00, 95.100, new double[]{0, 30}, new int[]{1});

        List<SynchronizedEpoch> loose = new MultiStationSynchronizer(0.5, 3).synchronize(List.of(a, b, c));
        assertEquals(2, loose.size());

        List<SynchronizedEpoch> tight = new MultiStationSynchronizer(0.1, 3).synchronize(List.of(a, b, c));
        assertEquals(0, tight.size());
    }

    @Test
    @DisplayName("低于最少站数的历元不输出")
    void testMinStations() {
        StationObservation a = station("A", 30.00, 95.000, new double[]{0, 30}, new int[]{1});
        StationObservation b = station("B", 30.09, 95.000, new double[]{0}, new int[]{1});
        StationObservation c = station("C", 30.00, 95.100, new double[]{0}, new int[]{1});

        List<SynchronizedEpoch> epochs = new MultiStationSynchronizer(0.5, 3).synchronize(List.of(a, b, c));
        assertEquals(1, epochs.size());
        assertEquals(0.0, epochs.get(0).time().sec, 1e-9);
    }

    private static StationObservation station(String id, double latDeg, double lonDeg,
                                              double[] times, int[] sats) {
        double[] pos = new double[]{latDeg * D2R, lonDeg * D2R, 100.0};
        double[] ecef = new double[3];
        CoordTransform.pos2ecef(pos, ecef);

        List<Obsd> observations = new ArrayList<>();
        for (double t : times) {
            for (int sat : sats) {
                Obsd obs = new Obsd();
                long whole = (long) Math.floor(t);
                obs.time = new GTime(whole, t - whole);
                obs.sat = sat;
                obs.P[0] = 2.0e7 + sat;
                observations.add(obs);
            }
        }
        assertTrue(observations.size() > 0);
        return new StationObservation(id, ecef, observations);
    }
}
