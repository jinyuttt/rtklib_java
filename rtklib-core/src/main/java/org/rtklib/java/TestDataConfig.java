package org.rtklib.java;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TestDataConfig {

    private static final Logger LOG = LoggerFactory.getLogger(TestDataConfig.class);
    private static final String CONFIG_FILE = "test-data.properties";
    private static final Properties props = new Properties();
    private static boolean loaded = false;

    private TestDataConfig() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        String[] searchPaths = {
            System.getProperty("user.dir"),
            System.getProperty("user.dir") + File.separator + "rtklib-core",
            System.getProperty("user.dir") + File.separator + "rtklib-core" + File.separator + "src" + File.separator + "test" + File.separator + "resources",
            System.getProperty("user.home"),
        };

        for (String dir : searchPaths) {
            Path p = Paths.get(dir, CONFIG_FILE);
            if (Files.exists(p)) {
                try (InputStream is = Files.newInputStream(p)) {
                    props.load(is);
                    LOG.info("[TestDataConfig] Loaded: {}", p.toAbsolutePath());
                    return;
                } catch (IOException e) {
                    LOG.warn("[TestDataConfig] Failed to read {}: {}", p, e.getMessage());
                }
            }
        }

        String resourcePath = "/" + CONFIG_FILE;
        try (InputStream is = TestDataConfig.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                props.load(is);
                LOG.info("[TestDataConfig] Loaded from classpath");
                return;
            }
        } catch (IOException e) {
            LOG.error("[TestDataConfig] Failed to read from classpath: {}", e.getMessage());
        }

        LOG.info("[TestDataConfig] No {} found, using defaults", CONFIG_FILE);
    }

    public static String get(String key, String defaultValue) {
        load();
        return props.getProperty(key, defaultValue);
    }

    public static String getRtcmBaseDir() {
        return get("rtcm.base.dir", getTestDataDir() + File.separator + "rtcm");
    }

    public static String getProductDir() {
        return get("product.dir", getTestDataDir() + File.separator + "product");
    }

    public static String getGreatPvtPppDir() {
        return get("greatpvt.ppp.dir", "D:\\code\\GREAT-PVT\\sample_data\\PPPFLT_2023305\\PPPFLT_2023305");
    }

    public static String getGreatPvtRtkDir() {
        return get("greatpvt.rtk.dir", "D:\\code\\GREAT-PVT\\sample_data\\RTKFLT_2020351\\RTKFLT_2020351");
    }

    public static String getResultDir() {
        return get("result.dir", System.getProperty("user.home") + File.separator + "rtklib_java_results");
    }

    public static String getStation() {
        return get("station.id", "");
    }

    public static String getStationDate() {
        return get("station.date", "");
    }

    public static String getTestDataDir() {
        return get("test-data.dir", findTestDataDir());
    }

    private static String findTestDataDir() {
        String[] candidates = {
            System.getProperty("user.dir") + File.separator + "test-data",
            System.getProperty("user.dir") + File.separator + ".." + File.separator + "test-data",
            System.getProperty("user.dir") + File.separator + "rtklib-core" + File.separator + ".." + File.separator + "test-data",
        };
        for (String dir : candidates) {
            if (Files.isDirectory(Paths.get(dir))) return dir;
        }
        return System.getProperty("user.dir") + File.separator + "test-data";
    }

    public static String getTestDataFile(String relativePath) {
        String dir = getTestDataDir();
        Path p = Paths.get(dir, relativePath);
        if (Files.exists(p)) return p.toString();
        return relativePath;
    }

    public static boolean hasTestDataFile(String relativePath) {
        return Files.exists(Paths.get(getTestDataDir(), relativePath));
    }

    public static String getRoverFile() {
        return get("rover.file", "");
    }

    public static String getBaseFile() {
        return get("base.file", "");
    }

    public static List<String> resolveRtcmFiles(String path) {
        if (path == null || path.isEmpty()) return Collections.emptyList();
        Path p = Paths.get(path);
        if (!Files.exists(p)) return Collections.emptyList();
        if (Files.isRegularFile(p)) return Collections.singletonList(path);
        try {
            return Files.walk(p)
                    .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".rtcm3"))
                    .sorted()
                    .map(Path::toString)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public static List<String[]> resolveBaseRoverPairs() {
        String roverPath = getRoverFile();
        String basePath = getBaseFile();
        if (roverPath.isEmpty() || basePath.isEmpty()) return Collections.emptyList();

        Path roverDir = Paths.get(roverPath);
        Path baseDir = Paths.get(basePath);

        if (Files.isRegularFile(baseDir) && Files.isRegularFile(roverDir)) {
            return Collections.singletonList(new String[]{basePath, roverPath});
        }

        if (Files.isDirectory(baseDir) && Files.isDirectory(roverDir)) {
            List<String[]> pairs = new ArrayList<>();
            try {
                List<Path> baseFiles = Files.walk(baseDir)
                        .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".rtcm3"))
                        .sorted()
                        .collect(Collectors.toList());
                for (Path bf : baseFiles) {
                    Path relative = baseDir.relativize(bf);
                    Path rf = roverDir.resolve(relative);
                    if (Files.exists(rf)) {
                        pairs.add(new String[]{bf.toString(), rf.toString()});
                    }
                }
            } catch (IOException e) {
                // ignore
            }
            return pairs;
        }

        return Collections.emptyList();
    }

}