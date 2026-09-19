package org.rtklib.java.adjust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.data.GTime;
import org.rtklib.java.monitor.BaseMonitorCallback;
import org.rtklib.java.monitor.BaseMonitorConfig;
import org.rtklib.java.monitor.BaseStationMonitor;
import org.rtklib.java.time.TimeSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BaseStationMonitorTest {

    private static final TestDataConfig CFG = new TestDataConfig();
    private static final boolean DATA_AVAILABLE = CFG.isAvailable()
            && !CFG.getDataRoot().isEmpty()
            && !CFG.getBadBase().isEmpty();

    @BeforeAll
    static void checkData() {
        if (!DATA_AVAILABLE) {
            System.out.println("测试数据不可用，跳过测试。" + CFG.getMissingMessage());
        }
    }

    private String buildPath(String station, String date, int hour) {
        return String.format("%s\\%s\\%s\\%d.rtcm3", CFG.getDataRoot(), station, date, hour);
    }

    @Test
    @DisplayName("基站监测：RTCM文件输入 - 60min窗口SPP中位数和突变检测")
    void testBaseMonitorRtcmFile() throws IOException {
        if (!DATA_AVAILABLE) return;

        String date = "2026-07-04";
        String baseStation = CFG.getBadBase();
        System.out.println("\n========================================================");
        System.out.println("  基站稳定性监测测试 (RTCM文件输入)");
        System.out.printf("  基站: %s  日期=%s  17-22时%n", baseStation, date);
        System.out.println("========================================================");

        BaseMonitorConfig config = new BaseMonitorConfig();
        config.windowMinutes = 60.0;
        config.movementThresh = 10.0;
        config.driftThresh = 5.0;
        config.historySize = 24;
        config.minSatCount = 4;
        config.maxPdop = 6.0;
        config.outlierThresh = 3.0;
        config.minValidInWindow = 10;

        List<String> windowResults = new ArrayList<>();
        List<String> movementAlerts = new ArrayList<>();
        List<String> driftAlerts = new ArrayList<>();
        int[] sppCount = {0};

        BaseMonitorCallback callback = new BaseMonitorCallback() {
            @Override
            public void onSppResult(int staid, GTime time, double[] pos, int numSat, double pdop) {
                sppCount[0]++;
            }

            @Override
            public void onWindowResult(int staid, GTime time, double[] medianPos, double offset) {
                String s = String.format("  窗口结果 staid=%d time=%s offset=%.2fm",
                        staid, time2str(time), offset);
                windowResults.add(s);
            }

            @Override
            public void onBaseMovement(int staid, GTime time, double[] median, double[] ref,
                                       double offset, String msg) {
                movementAlerts.add("  ⚠ " + msg);
            }

            @Override
            public void onBaseDrift(int staid, GTime time, double drift, double hours, String msg) {
                driftAlerts.add("  ⚠ " + msg);
            }
        };

        int staid = Integer.parseInt(baseStation);
        BaseStationMonitor monitor = new BaseStationMonitor(staid, config, callback);

        for (int hour = 17; hour <= 22; hour++) {
            String baseFile = buildPath(baseStation, date, hour);
            if (!Files.exists(Paths.get(baseFile))) continue;
            monitor.onRtcmFile(baseFile);
        }

        System.out.printf("%n  SPP成功历元: %d%n", sppCount[0]);
        System.out.printf("  参考基准已初始化: %s%n", monitor.isReferenceInitialized());
        System.out.printf("  二级历史数量: %d%n", monitor.getHistoryCount());

        if (monitor.isReferenceInitialized()) {
            double[] ref = monitor.getReferencePos();
            System.out.printf("  参考基准: [%.1f, %.1f, %.1f]%n", ref[0], ref[1], ref[2]);
        }

        System.out.println("\n  === 窗口结果 ===");
        for (String s : windowResults) System.out.println(s);

        if (!movementAlerts.isEmpty()) {
            System.out.println("\n  === 突变告警 ===");
            for (String s : movementAlerts) System.out.println(s);
        } else {
            System.out.println("\n  无突变告警（基站稳定）");
        }

        if (!driftAlerts.isEmpty()) {
            System.out.println("\n  === 漂移告警 ===");
            for (String s : driftAlerts) System.out.println(s);
        } else {
            System.out.println("\n  无漂移告警");
        }

        System.out.println("\n  === 二级历史 ===");
        for (int i = 0; i < monitor.getHistoryCount(); i++) {
            double[] p = monitor.getHistoryPos(i);
            GTime t = monitor.getHistoryTime(i);
            System.out.printf("  [%d] %s  [%.1f, %.1f, %.1f]%n", i, time2str(t), p[0], p[1], p[2]);
        }

        assertTrue(monitor.isReferenceInitialized(), "参考基准应已初始化");
        assertTrue(monitor.getHistoryCount() > 0, "应有二级历史数据");
    }

    @Test
    @DisplayName("基站监测：模拟基站移动检测")
    void testBaseMovementDetection() throws IOException {
        if (!DATA_AVAILABLE) return;

        String date = "2026-07-04";
        String baseStation = CFG.getBadBase();
        System.out.println("\n========================================================");
        System.out.println("  模拟基站移动检测");
        System.out.println("========================================================");

        BaseMonitorConfig config = new BaseMonitorConfig();
        config.windowMinutes = 60.0;
        config.movementThresh = 10.0;
        config.historySize = 24;

        List<String> alerts = new ArrayList<>();

        BaseMonitorCallback callback = new BaseMonitorCallback() {
            @Override
            public void onBaseMovement(int staid, GTime time, double[] median, double[] ref,
                                       double offset, String msg) {
                alerts.add(msg);
            }
        };

        int staid = Integer.parseInt(baseStation);
        BaseStationMonitor monitor = new BaseStationMonitor(staid, config, callback);

        for (int hour = 17; hour <= 22; hour++) {
            String baseFile = buildPath(baseStation, date, hour);
            if (!Files.exists(Paths.get(baseFile))) continue;
            monitor.onRtcmFile(baseFile);
        }

        System.out.printf("  正常数据: 告警数=%d (应为0)%n", alerts.size());
        assertEquals(0, alerts.size(), "正常数据不应触发移动告警");

        double[] refPos = monitor.getReferencePos();
        assertNotNull(refPos, "参考基准应已初始化");
        System.out.printf("  参考基准: [%.1f, %.1f, %.1f]%n", refPos[0], refPos[1], refPos[2]);

        monitor.resetReference();
        System.out.println("  重置参考基准后: initialized=" + monitor.isReferenceInitialized());
        assertFalse(monitor.isReferenceInitialized(), "重置后应未初始化");
    }

    @Test
    @DisplayName("基站监测：RTCM字节数据输入")
    void testBaseMonitorRtcmData() throws IOException {
        if (!DATA_AVAILABLE) return;

        String date = "2026-07-04";
        String baseStation = CFG.getBadBase();
        System.out.println("\n========================================================");
        System.out.println("  基站监测 (RTCM字节数据输入)");
        System.out.println("========================================================");

        BaseMonitorConfig config = new BaseMonitorConfig();
        config.windowMinutes = 60.0;

        int staid = Integer.parseInt(baseStation);
        BaseStationMonitor monitor = new BaseStationMonitor(staid, config);

        int totalBytes = 0;
        for (int hour = 17; hour <= 18; hour++) {
            String baseFile = buildPath(baseStation, date, hour);
            if (!Files.exists(Paths.get(baseFile))) continue;
            byte[] data = Files.readAllBytes(Paths.get(baseFile));
            monitor.onRtcmData(data);
            totalBytes += data.length;
        }

        System.out.printf("  输入字节数: %d%n", totalBytes);
        System.out.printf("  参考基准已初始化: %s%n", monitor.isReferenceInitialized());
        System.out.printf("  二级历史数量: %d%n", monitor.getHistoryCount());

        if (monitor.isReferenceInitialized()) {
            double[] ref = monitor.getReferencePos();
            System.out.printf("  参考基准: [%.1f, %.1f, %.1f]%n", ref[0], ref[1], ref[2]);
        }
    }

    private String time2str(GTime time) {
        if (time == null || time.time == 0) return "N/A";
        double[] ep = TimeSystem.time2ymdhms(time);
        return String.format("%04.0f-%02.0f-%02.0f %02.0f:%02.0f:%04.1f",
                ep[0], ep[1], ep[2], ep[3], ep[4], ep[5]);
    }
}