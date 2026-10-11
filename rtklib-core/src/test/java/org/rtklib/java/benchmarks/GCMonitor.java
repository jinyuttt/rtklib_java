package org.rtklib.java.benchmarks;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Lightweight GC monitoring utility for E2E timing tests.
 *
 * <p>Takes GC snapshots before/after a timed section and computes deltas:
 * Young GC count, Full GC count, total GC time, and approximate heap usage delta.
 * Used as the Tier 0 end-to-end GC baseline and Tier 1 3B acceptance criteria.</p>
 */
public final class GCMonitor {

    private final long youngGcCount;
    private final long youngGcTimeMs;
    private final long fullGcCount;
    private final long fullGcTimeMs;
    private final long heapUsedBytes;

    private GCMonitor(long youngGcCount, long youngGcTimeMs, long fullGcCount,
                      long fullGcTimeMs, long heapUsedBytes) {
        this.youngGcCount = youngGcCount;
        this.youngGcTimeMs = youngGcTimeMs;
        this.fullGcCount = fullGcCount;
        this.fullGcTimeMs = fullGcTimeMs;
        this.heapUsedBytes = heapUsedBytes;
    }

    public static GCMonitor snapshot() {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        long ygc = 0, ygt = 0, fgc = 0, fgt = 0;

        for (GarbageCollectorMXBean bean : beans) {
            String name = bean.getName().toLowerCase();
            long count = bean.getCollectionCount();
            long time = bean.getCollectionTime();
            if (count < 0) count = 0;
            if (time < 0) time = 0;

            if (name.contains("young") || name.contains("copy") || name.contains("scavenge")
                    || name.contains("ps scavenge") || name.contains("parnew")
                    || name.contains("g1 young")) {
                ygc = count;
                ygt = time;
            } else if (name.contains("mark") || name.contains("sweep") || name.contains("compact")
                    || name.contains("ps marksweep") || name.contains("concurrentmarksweep")
                    || name.contains("g1 old") || name.contains("g1 mixed") || name.contains("full")) {
                fgc = count;
                fgt = time;
            }
        }

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        long heapUsed = heap.getUsed();

        return new GCMonitor(ygc, ygt, fgc, fgt, heapUsed);
    }

    public static GCMonitorSnapshot diff(GCMonitor before, GCMonitor after) {
        return new GCMonitorSnapshot(
                after.youngGcCount - before.youngGcCount,
                after.youngGcTimeMs - before.youngGcTimeMs,
                after.fullGcCount - before.fullGcCount,
                after.fullGcTimeMs - before.fullGcTimeMs,
                after.heapUsedBytes - before.heapUsedBytes
        );
    }

    public static class GCMonitorSnapshot {
        public final long youngGcCount;
        public final long youngGcTimeMs;
        public final long fullGcCount;
        public final long fullGcTimeMs;
        public final long heapDeltaBytes;

        GCMonitorSnapshot(long ygc, long ygt, long fgc, long fgt, long heapDelta) {
            this.youngGcCount = ygc;
            this.youngGcTimeMs = ygt;
            this.fullGcCount = fgc;
            this.fullGcTimeMs = fgt;
            this.heapDeltaBytes = heapDelta;
        }

        public double heapDeltaMB() {
            return heapDeltaBytes / (1024.0 * 1024.0);
        }

        @Override
        public String toString() {
            return String.format(
                    "YoungGC: %d (%.1fms), FullGC: %d (%.1fms), HeapDelta: %.1f MB",
                    youngGcCount, youngGcTimeMs / 1.0,
                    fullGcCount, fullGcTimeMs / 1.0,
                    heapDeltaMB()
            );
        }
    }
}