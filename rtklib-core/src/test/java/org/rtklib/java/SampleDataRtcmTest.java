package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.rtklib.java.rinex.RtcmToRinexConverter;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("公共测试数据 - RTCM3解析与RINEX转换")
public class SampleDataRtcmTest {

    private static final Logger log = LoggerFactory.getLogger(SampleDataRtcmTest.class);

    private static final String RTCM_PATH = TestDataConfig.getTestDataFile("rtcm/open-sky_base_20090515.rtcm3");
    private static byte[] rtcmData;
    private static boolean dataAvailable;

    static boolean isDataAvailable() {
        return dataAvailable;
    }

    @BeforeAll
    static void loadData() {
        dataAvailable = Files.exists(Path.of(RTCM_PATH));
        if (!dataAvailable) {
            log.warn("公共测试数据不存在: {}，跳过测试", RTCM_PATH);
            return;
        }
        try (FileInputStream fis = new FileInputStream(RTCM_PATH)) {
            rtcmData = fis.readAllBytes();
            log.info("加载RTCM3数据: {} bytes ({})", rtcmData.length, RTCM_PATH);
        } catch (IOException e) {
            dataAvailable = false;
            log.warn("读取RTCM3数据失败: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("1. RTCM3帧扫描 - 消息类型统计")
    @EnabledIf("isDataAvailable")
    void testRtcm3FrameScan() {
        int frameCount = 0;
        TreeMap<Integer, Integer> frameTypes = new TreeMap<>();
        for (int fi = 0; fi < rtcmData.length - 2; fi++) {
            if ((rtcmData[fi] & 0xFF) == 0xD3 && (rtcmData[fi + 1] & 0x01) == 0) {
                int len = ((rtcmData[fi + 1] & 0x03) << 8) | (rtcmData[fi + 2] & 0xFF);
                if (fi + 3 + len <= rtcmData.length && len >= 0 && len <= 1023) {
                    int msgType = ((rtcmData[fi + 3] & 0x1F) << 8) | (rtcmData[fi + 4] & 0xFF);
                    frameTypes.merge(msgType, 1, Integer::sum);
                    frameCount++;
                    fi += 2 + len + 3;
                }
            }
        }

        log.info("RTCM3帧总数: {}", frameCount);
        for (var entry : frameTypes.entrySet()) {
            log.info("  消息类型 {}: {} 帧", entry.getKey(), entry.getValue());
        }

        assertTrue(frameCount > 0, "应检测到RTCM3帧");
        assertTrue(frameTypes.containsKey(1006) || frameTypes.containsKey(1005),
                "VRS基站数据应包含1005/1006基站坐标消息");
    }

    @Test
    @DisplayName("2. Callback解码 - 观测历元与星历提取")
    @EnabledIf("isDataAvailable")
    void testCallbackDecode() {
        List<ObservationEpoch> epochs = new ArrayList<>();
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();
        List<Sta> staList = new ArrayList<>();

        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta sta) { staList.add(sta); }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) { ephList.add(eph); }
            @Override public void onGeph(Geph geph) { gephList.add(geph); }
            @Override public void onObservationEpoch(ObservationEpoch epoch) { epochs.add(epoch); }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(rtcmData, 0, rtcmData.length);
        decoder.finish();

        log.info("观测历元: {}, 星历: {}, GLONASS星历: {}, 基站: {}",
                epochs.size(), ephList.size(), gephList.size(), staList.size());

        assertTrue(epochs.size() > 0, "应提取到观测历元");
        assertTrue(ephList.size() > 0, "应提取到星历");

        if (!staList.isEmpty()) {
            Sta s = staList.get(0);
            log.info("基站坐标: name={} pos=({:.4f},{:.4f},{:.4f})".replace("{:.4f}", "%.4f"),
                    s.name, s.pos[0], s.pos[1], s.pos[2]);
        }

        ObservationEpoch first = epochs.get(0);
        double[] ymd = TimeSystem.time2ymdhms(first.time);
        log.info("首历元: {}-{:02d}-{:02d} {:02d}:{:02d}:{:06.3f}, 卫星数={}".replace("{:02d}", "%02d").replace("{:06.3f}", "%06.3f"),
                (int) ymd[0], (int) ymd[1], (int) ymd[2], (int) ymd[3], (int) ymd[4], ymd[5], first.obsList.size());

        assertTrue(first.obsList.size() > 0, "首历元应有观测卫星");
    }

    @Test
    @DisplayName("3. RTCM3 → RINEX 3.05 转换")
    @EnabledIf("isDataAvailable")
    void testRtcmToRinex(@TempDir Path tempDir) throws IOException {
        String output2outputDir = tempDir.toString();
        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, outputDir, "0263");

        boolean result = converter.convert(rtcmData, rtcmData.length);
        assertTrue(result, "RTCM→RINEX转换应成功");

        Path obsFile = Path.of(outputDir, "0263.obs");
        Path navFile = Path.of(outputDir, "0263.nav");

        assertTrue(Files.exists(obsFile), "RINEX观测文件应存在");
        assertTrue(Files.size(obsFile) > 0, "RINEX观测文件不应为空");
        assertTrue(Files.exists(navFile), "RINEX导航文件应存在");
        assertTrue(Files.size(navFile) > 0, "RINEX导航文件不应为空");

        String obsContent = Files.readString(obsFile);
        assertTrue(obsContent.contains("RINEX VERSION / TYPE"), "应含RINEX版本头");
        assertTrue(obsContent.contains("END OF HEADER"), "应含头结束标记");

        log.info("RINEX obs: {} bytes, nav: {} bytes", Files.size(obsFile), Files.size(navFile));
    }

    @Test
    @DisplayName("4. RTCM3文件 → RINEX 3.05 转换 (RtcmFileToRinexConverter)")
    @EnabledIf("isDataAvailable")
    void testRtcmFileToRinex(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();
        RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "0263");

        boolean result = converter.convert(RTCM_PATH);
        assertTrue(result, "RTCM文件→RINEX转换应成功");

        Path obsFile = Path.of(outputDir, "0263.obs");
        Path navFile = Path.of(outputDir, "0263.nav");

        assertTrue(Files.exists(obsFile), "RINEX观测文件应存在");
        assertTrue(Files.exists(navFile), "RINEX导航文件应存在");

        log.info("文件转换 RINEX obs: {} bytes, nav: {} bytes", Files.size(obsFile), Files.size(navFile));
    }

    @Test
    @DisplayName("5. 星历系统分布验证")
    @EnabledIf("isDataAvailable")
    void testEphemerisSystemDistribution() {
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) { ephList.add(eph); }
            @Override public void onGeph(Geph geph) { gephList.add(geph); }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(rtcmData, 0, rtcmData.length);
        decoder.finish();

        Map<String, Integer> sysCount = new LinkedHashMap<>();
        Set<Integer> uniqueSats = new HashSet<>();
        for (Eph e : ephList) {
            uniqueSats.add(e.sat);
            int[] prn = new int[1];
            int sys = SatUtils.satsys(e.sat, prn);
            String name = sysName(sys);
            sysCount.merge(name, 1, Integer::sum);
        }

        log.info("星历系统分布: {} ({} 颗唯一卫星)", sysCount, uniqueSats.size());
        assertTrue(uniqueSats.size() >= 4, "GPS数据应至少有4颗卫星星历");
    }

    @Test
    @DisplayName("6. 观测历元时间连续性检查")
    @EnabledIf("isDataAvailable")
    void testEpochTimeContinuity() {
        List<ObservationEpoch> epochs = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) { epochs.add(epoch); }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(rtcmData, 0, rtcmData.length);
        decoder.finish();

        assertTrue(epochs.size() > 1, "应有多个观测历元");

        int gapCount = 0;
        double maxGap = 0;
        for (int i = 1; i < epochs.size(); i++) {
            double dt = TimeSystem.timediff(epochs.get(i).time, epochs.get(i - 1).time);
            if (dt > 2.0) {
                gapCount++;
                maxGap = Math.max(maxGap, dt);
            }
        }

        log.info("历元数: {}, 间隔>2s的跳变: {}, 最大跳变: {:.1f}s".replace("{:.1f}", "%.1f"),
                epochs.size(), gapCount, maxGap);
        assertTrue(gapCount < epochs.size() / 2, "时间跳变不应超过历元数的一半");
    }

    private String sysName(int sys) {
        if (sys == Constants.SYS_GPS) return "G";
        if (sys == Constants.SYS_GLO) return "R";
        if (sys == Constants.SYS_GAL) return "E";
        if (sys == Constants.SYS_QZS) return "J";
        if (sys == Constants.SYS_CMP) return "C";
        if (sys == Constants.SYS_IRN) return "I";
        if (sys == Constants.SYS_SBS) return "S";
        return "?";
    }
}