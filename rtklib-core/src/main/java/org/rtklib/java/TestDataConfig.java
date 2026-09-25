package org.rtklib.java;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class TestDataConfig {

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
                    System.out.println("[TestDataConfig] Loaded: " + p.toAbsolutePath());
                    return;
                } catch (IOException e) {
                    System.err.println("[TestDataConfig] Failed to read " + p + ": " + e.getMessage());
                }
            }
        }

        String resourcePath = "/" + CONFIG_FILE;
        try (InputStream is = TestDataConfig.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                props.load(is);
                System.out.println("[TestDataConfig] Loaded from classpath");
                return;
            }
        } catch (IOException e) {
            System.err.println("[TestDataConfig] Failed to read from classpath: " + e.getMessage());
        }

        System.out.println("[TestDataConfig] No " + CONFIG_FILE + " found, using defaults");
    }

    public static String get(String key, String defaultValue) {
        load();
        return props.getProperty(key, defaultValue);
    }

    public static String getRtcmBaseDir() {
        return get("rtcm.base.dir", "D:\\rtcm3\\rtcm");
    }

    public static String getProductDir() {
        return get("product.dir", "D:\\rtcm3\\product");
    }

    public static String getResultDir() {
        return get("result.dir", System.getProperty("user.home") + File.separator + "rtklib_java_results");
    }

    public static String[] getBaseRoverPairs(String group) {
        load();
        String val = props.getProperty("baserover." + group);
        if (val != null) return val.split("[,\\s]+");
        switch (group) {
            case "group1": return new String[]{"540423231901"};
            case "rover1": return new String[]{"540423187770", "540423379882", "540423211132", "540423230321", "540423124124", "540423147354", "540423503435", "540423128131"};
            case "group2": return new String[]{"540423214120"};
            case "rover2": return new String[]{"540423156203", "540423128507", "540423276898", "540423355855", "540423860355", "540423268595"};
            default: return new String[0];
        }
    }

    public static String getStation() {
        return get("station.id", "540423124124");
    }

    public static String getStationDate() {
        return get("station.date", "2026-06-29");
    }

    public static String getRoverFile() {
        return get("rover.file", "");
    }

    public static String getBaseFile() {
        return get("base.file", "");
    }

    public static Map<String, String[]> getBaseRoverMap() {
        Map<String, String[]> map = new LinkedHashMap<>();
        String[] bases1 = getBaseRoverPairs("group1");
        String[] rovers1 = getBaseRoverPairs("rover1");
        if (bases1.length > 0 && rovers1.length > 0) map.put(bases1[0], rovers1);

        String[] bases2 = getBaseRoverPairs("group2");
        String[] rovers2 = getBaseRoverPairs("rover2");
        if (bases2.length > 0 && rovers2.length > 0) map.put(bases2[0], rovers2);

        String customBases = get("custom.bases", "");
        if (!customBases.isEmpty()) {
            for (String base : customBases.split(";")) {
                String[] parts = base.split("=");
                if (parts.length == 2) {
                    map.put(parts[0].trim(), parts[1].split("[,\\s]+"));
                }
            }
        }
        return map;
    }
}