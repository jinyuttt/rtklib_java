package org.rtklib.java.benchmarks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit entry point for running JMH benchmarks via surefire.
 *
 * <p>Warmup=5 iterations, Measurement=5 iterations, Fork=3 per dimension pair.
 * GC profiling is enabled via {@code -prof gc} for allocation baseline.</p>
 *
 * <p>Run with:
 * <pre>mvn test -pl rtklib-core "-Dtest=JmhRunnerTest" -DfailIfNoTests=false</pre>
 *
 * <p>To run without GC profiler (faster):
 * <pre>mvn test -pl rtklib-core "-Dtest=JmhRunnerTest#runKalmanFilterBenchmark" -DfailIfNoTests=false</pre>
 */
@DisplayName("JMH Benchmark Runner")
@Tag("manual")
public class JmhRunnerTest {

    private static final Logger log = LoggerFactory.getLogger(JmhRunnerTest.class);

    @Test
    @DisplayName("KalmanFilter.update() micro-benchmark (5+5 iterations, 3 forks, +GC profiler)")
    void runKalmanFilterBenchmark() throws Exception {
        log.info("=== KalmanFilter.update() JMH Benchmark ===");
        log.info("Warmup=5, Measurement=5, Fork=3, G1GC, GC Profiler");
        org.openjdk.jmh.Main.main(new String[]{
                KalmanFilterBenchmark.class.getName(),
                "-wi", "5",
                "-i", "5",
                "-f", "3",
                "-bm", "avgt",
                "-tu", "us",
                "-prof", "gc",
                "-jvmArgs", "-Xms2g -Xmx2g -XX:+UseG1GC"
        });
    }

    @Test
    @DisplayName("KalmanFilter.update() micro-benchmark (fast: 3 forks, no GC profiler)")
    void runKalmanFilterBenchmarkFast() throws Exception {
        log.info("=== KalmanFilter.update() JMH Benchmark (fast) ===");
        log.info("Warmup=5, Measurement=5, Fork=3, G1GC, No profiler");
        org.openjdk.jmh.Main.main(new String[]{
                KalmanFilterBenchmark.class.getName(),
                "-wi", "5",
                "-i", "5",
                "-f", "3",
                "-bm", "avgt",
                "-tu", "us",
                "-jvmArgs", "-Xms2g -Xmx2g -XX:+UseG1GC"
        });
    }

    @Test
    @DisplayName("SimpleMatrix operations micro-benchmark (5+5 iterations, 3 forks, +GC profiler)")
    void runSimpleMatrixBenchmark() throws Exception {
        log.info("=== SimpleMatrix Operations JMH Benchmark ===");
        log.info("Warmup=5, Measurement=5, Fork=3, G1GC, GC Profiler");
        org.openjdk.jmh.Main.main(new String[]{
                SimpleMatrixBenchmark.class.getName(),
                "-wi", "5",
                "-i", "5",
                "-f", "3",
                "-bm", "avgt",
                "-tu", "ns",
                "-prof", "gc",
                "-jvmArgs", "-Xms2g -Xmx2g -XX:+UseG1GC"
        });
    }
}