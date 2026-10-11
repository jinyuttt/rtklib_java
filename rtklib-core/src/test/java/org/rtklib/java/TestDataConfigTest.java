package org.rtklib.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TestDataConfig.resolveRtcmFiles")
public class TestDataConfigTest {

    @Test
    @DisplayName("null/empty returns empty list")
    void testNullEmpty() {
        assertEquals(Collections.emptyList(), TestDataConfig.resolveRtcmFiles(null));
        assertEquals(Collections.emptyList(), TestDataConfig.resolveRtcmFiles(""));
    }

    @Test
    @DisplayName("non-existent path returns empty list")
    void testNonExistent() {
        assertEquals(Collections.emptyList(), TestDataConfig.resolveRtcmFiles("Z:\\nonexistent\\path"));
    }

    @Test
    @DisplayName("single file returns singleton list")
    void testSingleFile(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("8.rtcm3");
        Files.writeString(file, "test");

        List<String> result = TestDataConfig.resolveRtcmFiles(file.toString());
        assertEquals(1, result.size());
        assertEquals(file.toString(), result.get(0));
    }

    @Test
    @DisplayName("directory: recursively collects .rtcm3 files sorted by path (lexicographic)")
    void testDirectory(@TempDir Path temp) throws IOException {
        Path dateDir = temp.resolve("2026-05-20");
        Files.createDirectories(dateDir);
        Files.writeString(dateDir.resolve("08.rtcm3"), "a");
        Files.writeString(dateDir.resolve("09.rtcm3"), "b");
        Files.writeString(dateDir.resolve("10.rtcm3"), "c");
        Files.writeString(dateDir.resolve("readme.txt"), "skip");

        List<String> result = TestDataConfig.resolveRtcmFiles(temp.toString());
        assertEquals(3, result.size());
        assertTrue(result.get(0).endsWith("08.rtcm3"), "lexicographic: 08 < 09 < 10");
        assertTrue(result.get(1).endsWith("09.rtcm3"));
        assertTrue(result.get(2).endsWith("10.rtcm3"));
    }

    @Test
    @DisplayName("nested directories: recursively finds all .rtcm3")
    void testNested(@TempDir Path temp) throws IOException {
        Path d1 = temp.resolve("2026-05-20");
        Path d2 = temp.resolve("2026-05-21");
        Files.createDirectories(d1);
        Files.createDirectories(d2);
        Files.writeString(d1.resolve("8.rtcm3"), "a");
        Files.writeString(d2.resolve("0.rtcm3"), "b");

        List<String> result = TestDataConfig.resolveRtcmFiles(temp.toString());
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("directory with no .rtcm3 returns empty list")
    void testEmptyDir(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("notes.txt"), "skip");

        List<String> result = TestDataConfig.resolveRtcmFiles(temp.toString());
        assertEquals(Collections.emptyList(), result);
    }
}