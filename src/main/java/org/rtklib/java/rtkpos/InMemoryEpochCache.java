package org.rtklib.java.rtkpos;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.time.TimeSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;

/**
 * 内存环形缓冲区缓存，按sourceId分桶。
 * <p>
 * 每个sourceId对应一个独立的环形缓冲区，容量由maxEpochsPerSource控制。
 * 超出容量时自动丢弃最旧的历元。
 * </p>
 */
public class InMemoryEpochCache implements EpochCache {

    private final int maxEpochsPerSource;
    private final Map<String, BoundedList> buckets = new HashMap<>();

    public InMemoryEpochCache(int maxEpochsPerSource) {
        this.maxEpochsPerSource = maxEpochsPerSource > 0 ? maxEpochsPerSource : Integer.MAX_VALUE;
    }

    @Override
    public synchronized void put(String sourceId, Obsd[] obs, int n, GTime time) {
        BoundedList bucket = buckets.computeIfAbsent(sourceId, k -> new BoundedList(maxEpochsPerSource));
        bucket.add(new CachedEpoch(obs, n, time));
    }

    @Override
    public synchronized List<CachedEpoch> query(String sourceId, GTime from, GTime to) {
        BoundedList bucket = buckets.get(sourceId);
        if (bucket == null) return Collections.emptyList();

        List<CachedEpoch> result = new ArrayList<>();
        for (int i = 0; i < bucket.size(); i++) {
            CachedEpoch ce = bucket.get(i);
            if (from != null && TimeSystem.timediff(ce.time, from) < -1e-9) continue;
            if (to != null && TimeSystem.timediff(ce.time, to) > 1e-9) continue;
            result.add(ce);
        }
        return result;
    }

    @Override
    public synchronized List<CachedEpoch> getLatest(String sourceId, int count) {
        BoundedList bucket = buckets.get(sourceId);
        if (bucket == null) return Collections.emptyList();

        List<CachedEpoch> result = new ArrayList<>();
        int size = bucket.size();
        int start = Math.max(0, size - count);
        for (int i = size - 1; i >= start; i--) {
            result.add(bucket.get(i));
        }
        return result;
    }

    @Override
    public synchronized int size(String sourceId) {
        BoundedList bucket = buckets.get(sourceId);
        return bucket != null ? bucket.size() : 0;
    }

    @Override
    public synchronized void clear(String sourceId) {
        BoundedList bucket = buckets.remove(sourceId);
        if (bucket != null) bucket.clear();
    }

    @Override
    public synchronized void clearAll() {
        buckets.clear();
    }

    private static class BoundedList {
        private final CachedEpoch[] ring;
        private final int capacity;
        private int head = 0;
        private int count = 0;

        BoundedList(int capacity) {
            this.capacity = capacity;
            this.ring = new CachedEpoch[capacity];
        }

        public void add(CachedEpoch ce) {
            if (count < capacity) {
                ring[count] = ce;
                count++;
            } else {
                ring[head] = ce;
                head = (head + 1) % capacity;
            }
        }

        public CachedEpoch get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
            return ring[(head + index) % capacity];
        }

        public int size() {
            return count;
        }

        public void clear() {
            head = 0;
            count = 0;
            Arrays.fill(ring, null);
        }
    }
}