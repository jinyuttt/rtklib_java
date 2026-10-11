package org.rtklib.java.adjust;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public final class TestDataConfig {

    private static final String CONFIG_FILE = "adjust-test-data.properties";
    private static final Properties props = new Properties();
    private static boolean loaded = false;

    private TestDataConfig() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        String[] searchPaths = {
            System.getProperty("user.dir"),
            System.getProperty("user.dir") + File.separator + "rtklib-adjust",
            System.getProperty("user.dir") + File.separator + "rtklib-adjust" + File.separator + "src" + File.separator + "test" + File.separator + "resources",
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

    public static boolean isAvailable() {
        load();
        return !props.isEmpty();
    }

    public static String getDataRoot() {
        load();
        return props.getProperty("data.root", props.getProperty("rtcm.base.dir", ""));
    }

    public static String getDate() {
        load();
        return props.getProperty("date", "");
    }

    public static String[] getBaseGroup() {
        load();
        String val = props.getProperty("base.group", "");
        if (val.isEmpty()) return new String[0];
        return val.split("[,\\s]+");
    }

    public static String[] getRoverGroup() {
        load();
        String val = props.getProperty("rover.group", "");
        if (val.isEmpty()) return new String[0];
        return val.split("[,\\s]+");
    }

    public static List<String[]> getAllPairs() {
        String[] bases = getBaseGroup();
        String[] rovers = getRoverGroup();
        List<String[]> pairs = new ArrayList<>();
        for (String rover : rovers) {
            for (String base : bases) {
                pairs.add(new String[]{base, rover});
            }
        }
        return pairs;
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
        String[] bases = getBaseGroup();
        String[] rovers = getRoverGroup();
        String date = getDate();
        String dataRoot = getDataRoot();
        if (bases.length == 0 || rovers.length == 0 || date.isEmpty() || dataRoot.isEmpty()) {
            return Collections.emptyList();
        }

        Path baseDir = Paths.get(dataRoot, bases[0], date);
        Path roverDir = Paths.get(dataRoot, rovers[0], date);

        if (Files.isRegularFile(baseDir) && Files.isRegularFile(roverDir)) {
            return Collections.singletonList(new String[]{baseDir.toString(), roverDir.toString()});
        }

        List<String[]> pairs = new ArrayList<>();
        for (String rover : rovers) {
            Path rDir = Paths.get(dataRoot, rover, date);
            for (String base : bases) {
                Path bDir = Paths.get(dataRoot, base, date);
                if (!Files.isDirectory(bDir) || !Files.isDirectory(rDir)) continue;
                try {
                    List<Path> baseFiles = Files.walk(bDir)
                            .filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".rtcm3"))
                            .sorted()
                            .collect(Collectors.toList());
                    for (Path bf : baseFiles) {
                        Path relative = bDir.relativize(bf);
                        Path rf = rDir.resolve(relative);
                        if (Files.exists(rf)) {
                            pairs.add(new String[]{bf.toString(), rf.toString()});
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return pairs;
    }

    public static String getRtcmPath(String station, int hour) {
        return String.format("%s%s%s%s%s%s%d.rtcm3",
                getDataRoot(), File.separator, station, File.separator, getDate(), File.separator, hour);
    }

    public static String getMissingMessage() {
        return "测试数据配置文件未找到。请复制 test-data.properties.template 为 test-data.properties" +
                " 并填入真实设备ID和数据路径。该文件已加入.gitignore，不会提交到仓库。";
    }
}