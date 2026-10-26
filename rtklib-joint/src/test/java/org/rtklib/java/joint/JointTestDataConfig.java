package org.rtklib.java.joint;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class JointTestDataConfig {

    private static final String CONFIG_FILE = "joint-test-data.properties";
    private static final Properties props = new Properties();
    private static boolean loaded = false;

    private JointTestDataConfig() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        String[] searchPaths = {
            System.getProperty("user.dir"),
            System.getProperty("user.dir") + File.separator + "rtklib-joint",
            System.getProperty("user.dir") + File.separator + "rtklib-joint" + File.separator + "src" + File.separator + "test" + File.separator + "resources",
            System.getProperty("user.home"),
        };

        for (String dir : searchPaths) {
            Path p = Paths.get(dir, CONFIG_FILE);
            if (Files.exists(p)) {
                try (InputStream is = Files.newInputStream(p)) {
                    props.load(is);
                    System.out.println("[JointTestDataConfig] Loaded: " + p.toAbsolutePath());
                    return;
                } catch (IOException e) {
                    System.err.println("[JointTestDataConfig] Failed to read " + p + ": " + e.getMessage());
                }
            }
        }

        String resourcePath = "/" + CONFIG_FILE;
        try (InputStream is = JointTestDataConfig.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                props.load(is);
                System.out.println("[JointTestDataConfig] Loaded from classpath");
                return;
            }
        } catch (IOException e) {
            System.err.println("[JointTestDataConfig] Failed to read from classpath: " + e.getMessage());
        }

        System.out.println("[JointTestDataConfig] No " + CONFIG_FILE + " found, using defaults");
    }

    public static boolean isAvailable() {
        load();
        String root = getDataRoot();
        return !root.isEmpty() && Files.isDirectory(Paths.get(root));
    }

    public static String getDataRoot() {
        load();
        return props.getProperty("data.root", "");
    }

    public static String getDate() {
        load();
        return props.getProperty("date", "2026-10-26");
    }

    public static String[] getBaseGroup() {
        load();
        String val = props.getProperty("base.group", "");
        if (val.isEmpty()) return new String[0];
        return val.split("[,\\s]+");
    }

    public static String getRoverId() {
        load();
        return props.getProperty("rover.id", "");
    }

    public static String[] getRoverGroup() {
        load();
        String val = props.getProperty("rover.group", "");
        if (val.isEmpty()) return new String[0];
        return val.split("[,\\s]+");
    }

    public static int getStartHour() {
        load();
        return Integer.parseInt(props.getProperty("start.hour", "0"));
    }

    public static int getEndHour() {
        load();
        return Integer.parseInt(props.getProperty("end.hour", "17"));
    }

    public static String getRtcmPath(String station, int hour) {
        return String.format("%s%s%s%s%s%s%d.rtcm3",
                getDataRoot(), File.separator, station, File.separator, getDate(), File.separator, hour);
    }

    public static List<String> getRtcmFiles(String station) {
        List<String> files = new ArrayList<>();
        String root = getDataRoot();
        String date = getDate();
        if (root.isEmpty() || date.isEmpty()) return files;

        Path dir = Paths.get(root, station, date);
        if (!Files.isDirectory(dir)) return files;

        for (int h = getStartHour(); h <= getEndHour(); h++) {
            Path f = dir.resolve(h + ".rtcm3");
            if (Files.exists(f)) {
                files.add(f.toString());
            }
        }
        return files;
    }

    public static String getMissingMessage() {
        return "Joint RTK 测试数据配置文件未找到。请复制 joint-test-data.properties.template 为 joint-test-data.properties" +
                " 并填入真实设备ID和数据路径。该文件已加入.gitignore，不会提交到仓库。";
    }
}