package org.rtklib.java.benchmarks;

import org.ejml.simple.SimpleMatrix;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmark for SimpleMatrix primitive operations.
 *
 * <p>Covers the four hot-path operations in KalmanFilter.update():
 * constructor, multiply, invert, and transpose.
 * Results feed into Tier 0 baseline and Tier 2 (3A) SimpleMatrix-to-primitive
 * array migration decision.</p>
 *
 * <p>Matrix dimensions mirror actual KalmanFilter.update() usage:
 * <ul>
 *   <li>k (compressed state): 10, 50, 150, 300</li>
 *   <li>nv (observation): 5, 20, 40, 60</li>
 *   <li>k × k (covariance): 10×10, 50×50, 150×150, 300×300</li>
 *   <li>nv × nv (innovation cov): 5×5, 20×20, 40×40, 60×60</li>
 *   <li>k × nv (gain / design): 50×20, 150×40, 300×60</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>mvn test -pl rtklib-core "-Dtest=JmhRunnerTest#runSimpleMatrixBenchmark" -DfailIfNoTests=false</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 3, jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"})
public class SimpleMatrixBenchmark {

    @Param({"50_20", "150_40", "300_60"})
    String dim;

    int k;
    int nv;

    SimpleMatrix A_kk;
    SimpleMatrix B_kk;
    SimpleMatrix A_nv_nv;
    SimpleMatrix A_k_nv;
    SimpleMatrix B_nv_k;

    @Setup(Level.Iteration)
    public void setup() {
        String[] parts = dim.split("_");
        k = Integer.parseInt(parts[0]);
        nv = Integer.parseInt(parts[1]);

        Random rng = new Random(12345 + k * 1000 + nv);

        A_kk = randomMatrix(rng, k, k);
        B_kk = randomMatrix(rng, k, k);
        A_nv_nv = randomMatrix(rng, nv, nv);

        A_k_nv = randomMatrix(rng, k, nv);
        B_nv_k = randomMatrix(rng, nv, k);
    }

    @Benchmark
    public SimpleMatrix newSquareMatrix(Blackhole bh) {
        SimpleMatrix M = new SimpleMatrix(k, k);
        M.set(0, 0, 1.0);
        bh.consume(M);
        return M;
    }

    @Benchmark
    public SimpleMatrix multiply_kk_kk(Blackhole bh) {
        SimpleMatrix C = A_kk.mult(B_kk);
        bh.consume(C);
        return C;
    }

    @Benchmark
    public SimpleMatrix multiply_k_nv_by_nv_k(Blackhole bh) {
        SimpleMatrix C = A_k_nv.mult(B_nv_k);
        bh.consume(C);
        return C;
    }

    @Benchmark
    public SimpleMatrix multiply_nv_k_by_k_nv(Blackhole bh) {
        SimpleMatrix C = B_nv_k.mult(A_k_nv);
        bh.consume(C);
        return C;
    }

    @Benchmark
    public SimpleMatrix invert_nv_nv(Blackhole bh) {
        SimpleMatrix C = A_nv_nv.invert();
        bh.consume(C);
        return C;
    }

    @Benchmark
    public SimpleMatrix invert_kk(Blackhole bh) {
        SimpleMatrix C = A_kk.invert();
        bh.consume(C);
        return C;
    }

    @Benchmark
    public SimpleMatrix transpose_k_nv(Blackhole bh) {
        SimpleMatrix C = A_k_nv.transpose();
        bh.consume(C);
        return C;
    }

    @Benchmark
    public SimpleMatrix transpose_kk(Blackhole bh) {
        SimpleMatrix C = A_kk.transpose();
        bh.consume(C);
        return C;
    }

    private static SimpleMatrix randomMatrix(Random rng, int rows, int cols) {
        SimpleMatrix M = new SimpleMatrix(rows, cols);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                M.set(i, j, (rng.nextDouble() - 0.5) * 0.5);
            }
        }
        return M;
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(SimpleMatrixBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.TEXT)
                .build();
        new Runner(opt).run();
    }
}