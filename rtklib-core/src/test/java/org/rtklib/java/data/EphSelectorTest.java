package org.rtklib.java.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.time.TimeSystem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EphSelectorTest {

    private static Eph gpsEph(int sat, double toeSec, int iode) {
        Eph e = new Eph();
        e.sat = sat;
        e.iode = iode;
        e.toe = TimeSystem.gpst2time(2300, toeSec);
        return e;
    }

    private static Geph gloEph(int sat, double toeSec, int iode) {
        Geph g = new Geph();
        g.sat = sat;
        g.iode = iode;
        g.toe = TimeSystem.gpst2time(2300, toeSec);
        return g;
    }

    @Test
    @DisplayName("按 toe 最近选择：同一卫星多条星历取最近")
    void testSelectNearestToe() {
        Eph early = gpsEph(5, 0.0, 11);
        Eph late = gpsEph(5, 7200.0, 22);
        GTime t = TimeSystem.gpst2time(2300, 6000.0);

        Eph picked = EphSelector.selectEph(List.of(early, late), null, 5, t);
        assertNotNull(picked);
        assertEquals(22, picked.iode);

        GTime t2 = TimeSystem.gpst2time(2300, 1000.0);
        assertEquals(11, EphSelector.selectEph(List.of(early, late), null, 5, t2).iode);
    }

    @Test
    @DisplayName("选不到星历：池与 nav 均无该卫星时返回 null")
    void testSelectMissingSatellite() {
        Nav nav = new Nav();
        Eph other = gpsEph(7, 0.0, 3);
        GTime t = TimeSystem.gpst2time(2300, 0.0);

        assertNull(EphSelector.selectEph(List.of(other), nav, 5, t));
        assertNull(EphSelector.selectEph(null, nav, 5, t));
        assertNull(EphSelector.selectEph(List.of(), null, 5, t));
    }

    @Test
    @DisplayName("回退 nav 单槽位：池为空时取 nav.eph[sat-1]")
    void testFallbackToNav() {
        Nav nav = new Nav();
        Eph e = gpsEph(9, 0.0, 77);
        nav.eph[9 - 1] = e;

        Eph picked = EphSelector.selectEph(null, nav, 9, TimeSystem.gpst2time(2300, 0.0));
        assertSame(e, picked);
    }

    @Test
    @DisplayName("applyEphPool：逐历元把最近星历写入 nav")
    void testApplyPool() {
        Nav nav = new Nav();
        Eph early = gpsEph(12, 0.0, 1);
        Eph late = gpsEph(12, 3600.0, 2);
        List<Eph> pool = List.of(early, late);

        EphSelector.applyEphPool(pool, null, nav, TimeSystem.gpst2time(2300, 100.0));
        assertEquals(1, nav.eph[12 - 1].iode);

        EphSelector.applyEphPool(pool, null, nav, TimeSystem.gpst2time(2300, 3500.0));
        assertEquals(2, nav.eph[12 - 1].iode);
    }

    @Test
    @DisplayName("GLONASS：按 PRN 回退 nav.geph")
    void testSelectGeph() {
        int sat = SatUtils.satno(Constants.SYS_GLO, 3);
        Geph g = gloEph(sat, 0.0, 5);
        Nav nav = new Nav();
        nav.geph[3 - 1] = g;

        Geph picked = EphSelector.selectGeph(null, nav, sat, TimeSystem.gpst2time(2300, 0.0));
        assertSame(g, picked);

        assertNull(EphSelector.selectGeph(null, nav, SatUtils.satno(Constants.SYS_GPS, 3),
                TimeSystem.gpst2time(2300, 0.0)));
    }
}