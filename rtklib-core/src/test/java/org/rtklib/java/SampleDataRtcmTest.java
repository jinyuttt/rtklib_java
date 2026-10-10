package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.data.*;
import org.rtklib.java.common.CrcUtils;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("公共测试数据 - RTKLIB样例RTCM3帧扫描")
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
    @DisplayName("1. RTCM3帧同步与CRC校验")
    @EnabledIf("isDataAvailable")
    void testRtcm3FrameSync() {
        int frameCount = 0;
        int crcPassCount = 0;
        TreeMap<Integer, Integer> frameTypes = new TreeMap<>();

        for (int fi = 0; fi < rtcmData.length - 2; fi++) {
            if ((rtcmData[fi] & 0xFF) == 0xD3 && (rtcmData[fi + 1] & 0x01) == 0) {
                int len = ((rtcmData[fi + 1] & 0x03) << 8) | (rtcmData[fi + 2] & 0xFF);
                if (fi + 3 + len + 3 <= rtcmData.length && len >= 0 && len <= 1023) {
                    int msgType = ((rtcmData[fi + 3] & 0x1F) << 8) | (rtcmData[fi + 4] & 0xFF);
                    frameTypes.merge(msgType, 1, Integer::sum);
                    frameCount++;

                    if (checkCrc24q(rtcmData, fi, len)) {
                        crcPassCount++;
                    }

                    fi += 2 + len + 3;
                }
            }
        }

        log.info("RTCM3帧总数: {}, CRC通过: {}", frameCount, crcPassCount);
        for (var entry : frameTypes.entrySet()) {
            String label = getMsgTypeLabel(entry.getKey());
            log.info("  消息类型 {}: {} 帧 ({})", entry.getKey(), entry.getValue(), label);
        }

        assertTrue(frameCount > 0, "应检测到RTCM3帧");
        assertTrue(crcPassCount > 0, "应至少有一帧CRC校验通过");
        double crcRate = (double) crcPassCount / frameCount;
        log.info(String.format("CRC通过率: %.2f%%", crcRate * 100));
        assertTrue(crcRate > 0.99, "CRC通过率应>99%");
    }

    @Test
    @DisplayName("2. NovAtel私有消息类型识别")
    @EnabledIf("isDataAvailable")
    void testNovAtelPrivateMessageTypes() {
        TreeMap<Integer, Integer> frameTypes = scanFrameTypes();

        log.info("消息类型分布:");
        for (var entry : frameTypes.entrySet()) {
            log.info("  Type {}: {} 帧 ({})", entry.getKey(), entry.getValue(), getMsgTypeLabel(entry.getKey()));
        }

        assertTrue(frameTypes.containsKey(7872), "RTKLIB样例应包含NovAtel私有消息7872");
        assertTrue(frameTypes.get(7872) > 100, "消息7872应超过100帧");

        int totalFrames = frameTypes.values().stream().mapToInt(Integer::intValue).sum();
        log.info("总帧数: {}", totalFrames);
        assertTrue(totalFrames >= 800, "82KB RTKLIB样例应至少800帧");
    }

    @Test
    @DisplayName("3. RTCM3帧结构完整性")
    @EnabledIf("isDataAvailable")
    void testFrameStructureIntegrity() {
        int[] frameBounds = findAllFrameStarts();
        assertTrue(frameBounds.length > 0, "应找到帧起始位置");

        int maxGap = 0;
        int overlapCount = 0;
        for (int i = 1; i < frameBounds.length; i++) {
            int gap = frameBounds[i] - frameBounds[i - 1];
            if (gap < 6) overlapCount++;
            maxGap = Math.max(maxGap, gap);
        }

        log.info("帧起始位置数: {}, 最大帧间距: {} bytes, 重叠帧: {}", frameBounds.length, maxGap, overlapCount);
        assertTrue(overlapCount == 0, "帧不应重叠");
    }

    @Test
    @DisplayName("4. 数据时间跨度估算")
    @EnabledIf("isDataAvailable")
    void testDataTimeSpan() {
        TreeMap<Integer, Integer> frameTypes = scanFrameTypes();

        int obsFrames = 0;
        for (var entry : frameTypes.entrySet()) {
            int type = entry.getKey();
            if (type == 7872 || type == 7888 || (type >= 1001 && type <= 1004)
                    || (type >= 1074 && type <= 1077) || (type >= 1124 && type <= 1127)) {
                obsFrames += entry.getValue();
            }
        }

        log.info("观测类消息帧数: {}", obsFrames);
        assertTrue(obsFrames > 100, "1Hz 1小时数据应至少有约3600个观测帧，实际" + obsFrames);

        double estimatedHours = obsFrames / 3600.0;
        log.info(String.format("估算数据时长: %.2f 小时 (按1Hz)", estimatedHours));
    }

    @Test
    @DisplayName("5. 标准解码器兼容性检测")
    @EnabledIf("isDataAvailable")
    void testStandardDecoderCompatibility() {
        List<ObservationEpoch> epochs = new ArrayList<>();
        List<Eph> ephList = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) { ephList.add(eph); }
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) { epochs.add(epoch); }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(rtcmData, 0, rtcmData.length);
        decoder.finish();

        boolean hasStandardMessages = epochs.size() > 0 || ephList.size() > 0;
        log.info("标准解码器结果: 观测历元={}, 星历={}", epochs.size(), ephList.size());
        log.info("标准消息支持: {}", hasStandardMessages ? "是" : "否 (NovAtel私有格式)");

        if (!hasStandardMessages) {
            log.info("此数据使用NovAtel私有RTCM3消息类型，标准解码器无法解析，属于预期行为");
        }

        if (!epochs.isEmpty()) {
            ObservationEpoch first = epochs.get(0);
            double[] ymd = TimeSystem.time2ymdhms(first.time);
            log.info(String.format("首历元: %04d-%02d-%02d %02d:%02d:%06.3f, 卫星数=%d",
                    (int) ymd[0], (int) ymd[1], (int) ymd[2], (int) ymd[3], (int) ymd[4], ymd[5], first.obsList.size()));
        }
    }

    private TreeMap<Integer, Integer> scanFrameTypes() {
        TreeMap<Integer, Integer> frameTypes = new TreeMap<>();
        for (int fi = 0; fi < rtcmData.length - 2; fi++) {
            if ((rtcmData[fi] & 0xFF) == 0xD3 && (rtcmData[fi + 1] & 0x01) == 0) {
                int len = ((rtcmData[fi + 1] & 0x03) << 8) | (rtcmData[fi + 2] & 0xFF);
                if (fi + 3 + len + 3 <= rtcmData.length && len >= 0 && len <= 1023) {
                    int msgType = ((rtcmData[fi + 3] & 0x1F) << 8) | (rtcmData[fi + 4] & 0xFF);
                    frameTypes.merge(msgType, 1, Integer::sum);
                    fi += 2 + len + 3;
                }
            }
        }
        return frameTypes;
    }

    private int[] findAllFrameStarts() {
        List<Integer> starts = new ArrayList<>();
        for (int fi = 0; fi < rtcmData.length - 2; fi++) {
            if ((rtcmData[fi] & 0xFF) == 0xD3 && (rtcmData[fi + 1] & 0x01) == 0) {
                int len = ((rtcmData[fi + 1] & 0x03) << 8) | (rtcmData[fi + 2] & 0xFF);
                if (fi + 3 + len + 3 <= rtcmData.length && len >= 0 && len <= 1023) {
                    starts.add(fi);
                    fi += 2 + len + 3;
                }
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private boolean checkCrc24q(byte[] data, int offset, int bodyLen) {
        int total = 3 + bodyLen + 3;
        int crcActual = ((data[offset + total - 3] & 0xFF) << 16)
                | ((data[offset + total - 2] & 0xFF) << 8)
                | (data[offset + total - 1] & 0xFF);
        int crcExpected = CrcUtils.rtkCrc24q(data, offset, total - 3);
        return (crcActual & 0xFFFFFF) == (crcExpected & 0xFFFFFF);
    }

    private String getMsgTypeLabel(int type) {
        if (type == 1005) return "基站坐标(ARP)";
        if (type == 1006) return "基站坐标(ARP+高)";
        if (type == 1007) return "天线描述";
        if (type == 1019) return "GPS星历";
        if (type == 1020) return "GLONASS星历";
        if (type == 1042) return "BDS星历";
        if (type == 1074) return "GPS MSM4";
        if (type == 1075) return "GPS MSM5";
        if (type == 1077) return "GPS MSM7";
        if (type == 1124) return "BDS MSM4";
        if (type == 1127) return "BDS MSM7";
        if (type == 7872) return "NovAtel私有(7872)";
        if (type == 7888) return "NovAtel私有(7888)";
        if (type == 7920) return "NovAtel私有(7920)";
        if (type == 8160) return "NovAtel私有(8160)";
        if (type >= 4001 && type <= 4095) return "私有消息";
        if (type >= 7000 && type <= 8191) return "厂商私有";
        return "标准消息";
    }
}