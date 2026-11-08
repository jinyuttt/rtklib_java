package org.rtklib.java.ai.collect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.FixQualityContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 采集链路测试：验证「恒返回 1.0 + 列序对齐 + 多 run 累积 + 注入清理」。
 * <p>
 * 重点不是采集器内部实现，而是它<b>不会破坏解算</b>：任何异常路径都必须返回 1.0。
 * </p>
 */
class RecordingFixValidatorTest {

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        AiHooks.clear();
    }

    private static FixQualityContext ctx(int epoch) {
        return new FixQualityContext(epoch, 12, 2, 8, 3.5f, 1.8, 0.02, 0.01, 0.3, 10, 8);
    }

    private static CollectConfig cfg(Path dir, String runId) {
        return new CollectConfig(dir, "GEJ3034", "2021-304", runId, false, 1000);
    }

    @Test
    void writesOneRowPerCallAndAlwaysReturnsOne() throws IOException {
        CollectConfig c = cfg(dir, "r1");
        try (CollectSession s = CollectSession.open(c)) {
            assertEquals(1.0, s.validator().validate(ctx(1)));
            assertEquals(1.0, s.validator().validate(ctx(2)));
            assertEquals(2, s.validator().rows());
        }

        List<String> lines = Files.readAllLines(c.csvPath());
        assertEquals(3, lines.size());
        assertEquals(RecordingFixValidator.CSV_HEADER, lines.get(0));

        String[] cols = lines.get(1).split(",");
        assertEquals(RecordingFixValidator.CSV_HEADER.split(",").length, cols.length);
        assertEquals("r1", cols[0]);
        assertEquals("GEJ3034", cols[1]);
        assertEquals("2021-304", cols[2]);
        assertEquals("1", cols[3]);
        assertEquals("3.5", cols[7]);
        assertEquals(8.0 / 12.0, Double.parseDouble(cols[8]), 1e-12);
    }

    @Test
    void degenerateContextKeepsPlaceholderZero() throws IOException {
        CollectConfig c = cfg(dir, "r1");
        FixQualityContext noPhase = new FixQualityContext(5, 6, 1, 4, 2.0f, 2.5,
                0.05, 9.9, 9.9, 6, 0);
        try (CollectSession s = CollectSession.open(c)) {
            s.validator().validate(noPhase);
        }
        String[] cols = Files.readAllLines(c.csvPath()).get(1).split(",");
        assertEquals("0.0", cols[5]);
        assertEquals("9.9", cols[6]);
        assertEquals("0", cols[14]);
    }

    @Test
    void refusesToOverwriteExistingFile() throws IOException {
        CollectConfig c = cfg(dir, "r1");
        try (CollectSession s = CollectSession.open(c)) {
            s.validator().validate(ctx(1));
        }
        assertThrows(IOException.class, () -> CollectSession.open(c));
    }

    @Test
    void validateAfterCloseDoesNotWrite() throws IOException {
        CollectConfig c = cfg(dir, "r1");
        CollectSession s = CollectSession.open(c);
        s.validator().validate(ctx(1));
        s.close();
        assertEquals(1.0, s.validator().validate(ctx(2)));
        assertEquals(2, Files.readAllLines(c.csvPath()).size());
    }

    @Test
    void nullContextIsNotWritten() throws IOException {
        CollectConfig c = cfg(dir, "r1");
        try (CollectSession s = CollectSession.open(c)) {
            assertEquals(1.0, s.validator().validate(null));
            assertEquals(0, s.validator().rows());
        }
        assertEquals(1, Files.readAllLines(c.csvPath()).size());
    }

    @Test
    void runWithInstallsAndClearsHooks() throws IOException {
        CollectConfig c = cfg(dir, "r1");
        try (CollectSession s = CollectSession.open(c)) {
            assertNull(AiHooks.provider());
            s.runWith(() -> {
                assertNotNull(AiHooks.provider());
                assertNotNull(AiHooks.resolveFixValidator(null));
            });
            assertNull(AiHooks.provider());
            assertTrue(s.callWith(() -> AiHooks.resolveFixValidator(null) != null));
        }
        assertNull(AiHooks.provider());
    }

    @Test
    void hooksClearedEvenWhenBodyThrows() throws IOException {
        CollectConfig c = cfg(dir, "r1");
        try (CollectSession s = CollectSession.open(c)) {
            assertThrows(IllegalStateException.class,
                    () -> s.runWith(() -> {
                        throw new IllegalStateException("boom");
                    }));
        }
        assertNull(AiHooks.provider());
    }

    @Test
    void runWithRestoresPreviousProvider() throws IOException {
        org.rtklib.java.ai.spi.AiProvider host = new org.rtklib.java.ai.spi.AiProvider() {
            @Override
            public org.rtklib.java.ai.spi.FixValidator fixValidator() {
                return null;
            }

            @Override
            public org.rtklib.java.ai.spi.ObsCorrector obsCorrector() {
                return null;
            }
        };
        AiHooks.install(host);
        try (CollectSession s = CollectSession.open(cfg(dir, "r1"))) {
            s.runWith(() -> assertTrue(AiHooks.provider() != host));
        }
        assertEquals(host, AiHooks.provider());
    }

    @Test
    void multipleRunsAccumulateWithoutCollision() throws IOException {
        for (String run : List.of("a", "b")) {
            CollectConfig c = cfg(dir, run);
            try (CollectSession s = CollectSession.open(c)) {
                s.validator().validate(ctx(1));
                s.validator().validate(ctx(2));
            }
        }

        long csvCount;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            csvCount = files.filter(p -> p.toString().endsWith(".csv"))
                    .filter(p -> !p.getFileName().toString().equals("_runs.csv"))
                    .count();
        }
        assertEquals(2, csvCount);

        List<String> manifest = Files.readAllLines(dir.resolve("_runs.csv"));
        assertEquals(3, manifest.size());
        assertEquals(CollectSession.MANIFEST_HEADER, manifest.get(0));
        assertTrue(manifest.get(1).startsWith("a,GEJ3034,2021-304,GEJ3034_2021-304_a.csv,"));
        assertTrue(manifest.get(2).startsWith("b,GEJ3034,2021-304,GEJ3034_2021-304_b.csv,"));

        assertEquals("a", Files.readAllLines(dir.resolve("GEJ3034_2021-304_a.csv"))
                .get(1).split(",")[0]);
    }

    @Test
    void rejectsUnsafeTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> new CollectConfig(dir, "GEJ,3034", "2021-304", "r1", false, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new CollectConfig(dir, "GEJ3034", "2021/304", "r1", false, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new CollectConfig(dir, " ", "2021-304", "r1", false, 1000));
    }

    @Test
    void autoRunIdIsGeneratedWhenAbsent() {
        CollectConfig c = CollectConfig.of(dir, "GEJ3034", "2021-304");
        assertNotNull(c.runId());
        assertTrue(c.runId().length() > 0);
        assertTrue(c.csvPath().getFileName().toString().endsWith(".csv"));
    }
}