package org.rtklib.java;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.rtkpos.EpochCache;
import org.rtklib.java.rtkpos.InMemoryEpochCache;
import org.rtklib.java.time.TimeSystem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class EpochCacheTest {

    @Test
    void testInMemoryCacheBasicPutAndQuery() {
        InMemoryEpochCache cache = new InMemoryEpochCache(100);

        String sourceId = "rover1";
        GTime t1 = TimeSystem.gpst2time(2300, 36000.0);
        GTime t2 = TimeSystem.gpst2time(2300, 36030.0);
        GTime t3 = TimeSystem.gpst2time(2300, 36060.0);

        Obsd[] obs1 = new Obsd[]{new Obsd()};
        obs1[0].time = t1;
        Obsd[] obs2 = new Obsd[]{new Obsd()};
        obs2[0].time = t2;
        Obsd[] obs3 = new Obsd[]{new Obsd()};
        obs3[0].time = t3;

        cache.put(sourceId, obs1, 1, t1);
        cache.put(sourceId, obs2, 1, t2);
        cache.put(sourceId, obs3, 1, t3);

        assertEquals(3, cache.size(sourceId));

        List<EpochCache.CachedEpoch> all = cache.query(sourceId, null, null);
        assertEquals(3, all.size());

        List<EpochCache.CachedEpoch> range = cache.query(sourceId, t1, t2);
        assertEquals(2, range.size());

        List<EpochCache.CachedEpoch> latest2 = cache.getLatest(sourceId, 2);
        assertEquals(2, latest2.size());
        assertEquals(0, TimeSystem.timediff(t3, latest2.get(0).time), 1e-9);
        assertEquals(0, TimeSystem.timediff(t2, latest2.get(1).time), 1e-9);
    }

    @Test
    void testInMemoryCacheRingBufferOverflow() {
        InMemoryEpochCache cache = new InMemoryEpochCache(3);

        String sourceId = "base1";
        for (int i = 0; i < 5; i++) {
            GTime t = TimeSystem.gpst2time(2300, 36000.0 + i * 30.0);
            Obsd[] obs = new Obsd[]{new Obsd()};
            obs[0].time = t;
            cache.put(sourceId, obs, 1, t);
        }

        assertEquals(3, cache.size(sourceId));

        List<EpochCache.CachedEpoch> all = cache.query(sourceId, null, null);
        assertEquals(3, all.size());

        double firstTime = TimeSystem.time2gpst(all.get(0).time, new int[1]);
        assertEquals(36060.0, firstTime, 1e-9);
    }

    @Test
    void testInMemoryCacheMultipleSources() {
        InMemoryEpochCache cache = new InMemoryEpochCache(100);

        String rover = "rover1";
        String base = "base1";

        GTime t = TimeSystem.gpst2time(2300, 36000.0);
        cache.put(rover, new Obsd[]{new Obsd()}, 1, t);
        cache.put(base, new Obsd[]{new Obsd()}, 1, t);

        assertEquals(1, cache.size(rover));
        assertEquals(1, cache.size(base));

        cache.clear(rover);
        assertEquals(0, cache.size(rover));
        assertEquals(1, cache.size(base));

        cache.clearAll();
        assertEquals(0, cache.size(base));
    }

    @Test
    void testInMemoryCacheEmptyQuery() {
        InMemoryEpochCache cache = new InMemoryEpochCache(100);

        assertEquals(0, cache.size("nonexistent"));
        assertTrue(cache.query("nonexistent", null, null).isEmpty());
        assertTrue(cache.getLatest("nonexistent", 10).isEmpty());
    }
}