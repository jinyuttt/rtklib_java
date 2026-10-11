package org.rtklib.java.benchmarks;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.rtklib.java.kalman.KalmanFilter;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH micro-benchmark for KalmanFilter.update() hot path.
 *
 * <p>Measures single-invocation throughput across typical RTK/PPP state dimensions.
 * Results feed into Tier 0 baseline and Tier 2 (3A) Kalman optimization decision.</p>
 *
 * <p>Run with:
 * <pre>mvn test -pl rtklib-core -Dtest=KalmanFilterBenchmark -DfailIfNoTests=false</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"})
public class KalmanFilterBenchmark {

    @Param({"10", "50", "150", "300"})
    int nx;

    @Param({"5", "20", "40", "60"})
    int nv;

    double[] x;
    double[] P;
    double[] H;
    double[] v;
    double[] R;

    @Setup(Level.Iteration)
    public void setup() {
        if (nv > nx) {
            return;
        }

        Random rng = new Random(12345);

        x = new double[nx];
        for (int i = 0; i < nx; i++) {
            x[i] = (rng.nextDouble() - 0.5) * 2.0;
        }

        P = new double[nx * nx];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nx; j++) {
                if (i == j) {
                    P[i * nx + j] = 1.0 + rng.nextDouble() * 0.1;
                } else {
                    P[i * nx + j] = (rng.nextDouble() - 0.5) * 0.01;
                }
            }
        }

        H = new double[nv * nx];
        for (int i = 0; i < nv * nx; i++) {
            H[i] = (rng.nextDouble() - 0.5) * 0.5;
        }

        v = new double[nv];
        for (int i = 0; i < nv; i++) {
            v[i] = (rng.nextDouble() - 0.5) * 2.0;
        }

        R = new double[nv * nv];
        for (int i = 0; i < nv; i++) {
            for (int j = 0; j < nv; j++) {
                if (i == j) {
                    R[i * nv + j] = 0.01 + rng.nextDouble() * 0.001;
                }
            }
        }
    }

    @Benchmark
    public int kalmanUpdate(Blackhole bh) {
        if (nv > nx) {
            return 0;
        }
        int result = KalmanFilter.update(x, P, H, v, R, nx, nv);
        bh.consume(result);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(KalmanFilterBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.TEXT)
                .build();
        new Runner(opt).run();
    }
}