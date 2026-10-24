package org.rtklib.java.adaptive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;

public class AdaptiveAuditLogger {
    private static final Logger LOG = LoggerFactory.getLogger(AdaptiveAuditLogger.class);

    private final boolean enabled;
    private final String dir;
    private final int maxSizeKB;
    private final int maxFiles;
    private PrintWriter writer;
    private String filePath;
    private long byteCount = 0;

    public AdaptiveAuditLogger(boolean enabled, String dir, int maxSizeKB, int maxFiles) {
        this.enabled = enabled;
        this.dir = dir;
        this.maxSizeKB = maxSizeKB;
        this.maxFiles = maxFiles;
    }

    public void open(String mode, AdaptiveMode adaptiveMode, int windowEpochs, int cooldownEpochs,
                     double sampleInterval, GnssScene initialScene,
                     String managedParams, String lockedParams, String intervalDist) {
        if (!enabled) return;
        try {
            Path dirPath = dir.isEmpty() ? Paths.get(".") : Paths.get(dir);
            Files.createDirectories(dirPath);
            filePath = dirPath.resolve("adaptive_audit.csv").toString();
            writer = new PrintWriter(new BufferedWriter(new FileWriter(filePath, false)));
            byteCount = 0;
            writeHeader(mode, adaptiveMode, windowEpochs, cooldownEpochs, sampleInterval,
                    initialScene, managedParams, lockedParams, intervalDist);
        } catch (IOException e) {
            LOG.warn("[ADAPTIVE] 审计日志创建失败: {}", e.getMessage());
        }
    }

    private void writeHeader(String mode, AdaptiveMode adaptiveMode, int windowEpochs, int cooldownEpochs,
                             double sampleInterval, GnssScene initialScene,
                             String managedParams, String lockedParams, String intervalDist) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RTKLIB-Java Adaptive Audit Log\n");
        sb.append("# version: 3.1.0  ← 软件版本号，非本文档版本\n");
        sb.append("# mode: ").append(mode).append("\n");
        sb.append("# sampleInterval: ").append(String.format("%.3fs", sampleInterval))
                .append(String.format(" (%.1fHz)", 1.0 / sampleInterval)).append("\n");
        sb.append("# adaptiveWindowEpochs: ").append(windowEpochs).append("\n");
        sb.append("# adaptiveWindowTime: ").append(String.format("%.1fs", windowEpochs * sampleInterval)).append("\n");
        sb.append("# adaptiveCooldownEpochs: ").append(cooldownEpochs).append("\n");
        sb.append("# adaptiveCooldownTime: ").append(String.format("%.1fs", cooldownEpochs * sampleInterval)).append("\n");
        sb.append("# initialScene: ").append(initialScene).append("\n");
        sb.append("# managedParams: ").append(managedParams != null ? managedParams : "(all)").append("\n");
        sb.append("# lockedParams: ").append(lockedParams != null ? lockedParams : "(none)").append("\n");
        sb.append("# sampleIntervalDistribution: ").append(intervalDist).append("\n");
        sb.append("#\n");
        sb.append("# epoch | gpst | event | from | to | metrics | changes | reset\n");
        write(sb.toString());
    }

    public void logEvent(long epoch, String gpst, String event, String from, String to,
                         String metrics, String changes, String reset) {
        if (!enabled || writer == null) return;
        String line = String.format("%07d,%s,%s,%s,%s,%s,%s,%s%n",
                epoch, gpst, event, from, to,
                metrics != null ? metrics : "",
                changes != null ? changes : "",
                reset != null ? reset : "");
        write(line);
    }

    public void logRollback(long epoch, String reason) {
        logEvent(epoch, Instant.now().toString(), "ROLLBACK", "", "", "", reason, "");
    }

    private void write(String text) {
        if (writer == null) return;
        writer.print(text);
        writer.flush();
        byteCount += text.length();
        if (byteCount > maxSizeKB * 1024L) {
            rotate();
        }
    }

    private void rotate() {
        close();
        if (filePath == null) return;
        try {
            for (int i = maxFiles - 1; i >= 1; i--) {
                Path src = Paths.get(filePath + "." + i);
                Path dst = Paths.get(filePath + "." + (i + 1));
                if (Files.exists(src)) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(Paths.get(filePath), Paths.get(filePath + ".1"), StandardCopyOption.REPLACE_EXISTING);
            writer = new PrintWriter(new BufferedWriter(new FileWriter(filePath, false)));
            byteCount = 0;
        } catch (IOException e) {
            LOG.warn("[ADAPTIVE] 审计日志轮转失败: {}", e.getMessage());
        }
    }

    public void close() {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}