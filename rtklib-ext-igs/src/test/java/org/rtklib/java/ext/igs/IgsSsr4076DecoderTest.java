package org.rtklib.java.ext.igs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.rtcm.Rtcm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用实测 CAS IGS-SSR 样本验证 4076 解码器：
 * 校验子类型识别、公共头部与 IM201 的球谐系数/合成。
 */
public class IgsSsr4076DecoderTest {

    private static final String SAMPLE =
            "test-data/gnss-validation/rtcm/ssr_ssrc01cas1_20261028.rtcm3";

    @Test
    @DisplayName("CAS 样本：71 帧 4076 的子类型与头部字段")
    void decodesCasSample() throws IOException {
        Path sample = resolveSample();
        Assumptions.assumeTrue(sample != null, "样本不存在，跳过: " + SAMPLE);
        byte[] data = Files.readAllBytes(sample);

        List<IgsSsr4076Message> messages = new ArrayList<>();
        Rtcm rtcm = new Rtcm();
        rtcm.registerCustomDecoder(new IgsSsr4076Decoder(messages::add));
        feed(rtcm, data);

        assertEquals(71, messages.size());

        Map<Integer, Long> counts = messages.stream()
                .collect(Collectors.groupingBy(m -> m.subtype, TreeMap::new, Collectors.counting()));
        assertEquals(expectedCounts(), counts);

        IgsSsr4076Message im21 = firstOf(messages, 21);
        assertEquals(0, im21.version, "CAS 流版本字段实测为 0");
        assertEquals(269220, im21.epochTimeSec);
        assertEquals(5, im21.updateIntervalSec);
        assertEquals(0, im21.crsIndicator);
        assertEquals(32, im21.numberOfSatellites);
        assertEquals("GPS 轨道改正", im21.subtypeName());

        IgsSsr4076Message im22 = firstOf(messages, 22);
        assertEquals(31, im22.numberOfSatellites);
        assertEquals(IgsSsr4076Message.NO_FIELD, im22.crsIndicator, "钟差消息无 CRS 指示");

        IgsSsr4076Message im26 = firstOf(messages, 26);
        assertEquals(31, im26.numberOfSatellites);
        assertEquals(0, im26.consistencyMaintained);
        assertEquals(0, im26.mwConsistencyMaintained);

        IgsSsr4076Message im101 = firstOf(messages, 101);
        assertEquals(269206, im101.epochTimeSec, "BDS 历元按 BDT 计，比 GPS 早 14 s");
        assertEquals(32, im101.numberOfSatellites);
        assertEquals("BDS 轨道改正", im101.subtypeName());

        IgsSsr4076Message im201 = firstOf(messages, 201);
        assertEquals(60, im201.updateIntervalSec);
        assertEquals(1, im201.numberOfIonosphericLayers);
        assertEquals(0.0, im201.vtecQualityIndicator, 1e-9);
        assertEquals(IgsSsr4076Message.NO_FIELD, im201.numberOfSatellites);
        assertEquals("IM201 电离层 VTEC 球谐", im201.subtypeName());

        assertEquals(1, im201.ionosphericLayers.size());
        IgsSsrVtecLayer layer = im201.ionosphericLayers.get(0);
        assertEquals(450.0, layer.heightKm, 1e-9);
        assertEquals(15, layer.degree);
        assertEquals(15, layer.order);
        assertEquals(23.02, layer.c(0, 0), 1e-9);
        assertEquals(9.105, layer.c(1, 1), 1e-9);
        assertEquals(1.3, layer.s(1, 1), 1e-9);
        assertEquals(0.01, layer.s(15, 15), 1e-9);
        assertEquals(0.0, layer.s(0, 0), 1e-9, "m=0 无正弦项");

        IgsSsr4076Message im202 = firstOf(messages, 202);
        assertEquals(1, im202.numberOfIonosphericLayers);
        assertEquals("IM202 电离层 VTEC-RMS", im202.subtypeName());
        assertTrue(im202.ionosphericLayers.isEmpty(), "标准未定义 IM202 系数布局，不应解析");
    }

    @Test
    @DisplayName("IM201 样本：VTEC 球谐合成")
    void synthesizesVtecFromSample() throws IOException {
        Path sample = resolveSample();
        Assumptions.assumeTrue(sample != null, "样本不存在，跳过: " + SAMPLE);
        byte[] data = Files.readAllBytes(sample);

        List<IgsSsr4076Message> messages = new ArrayList<>();
        Rtcm rtcm = new Rtcm();
        rtcm.registerCustomDecoder(new IgsSsr4076Decoder(messages::add));
        feed(rtcm, data);

        IgsSsrVtecLayer layer = firstOf(messages, 201).ionosphericLayers.get(0);

        // 与参考实现（同一完全归一化勒让德约定）逐项对齐
        assertEquals(57.2606428278, IgsSsrVtec.vtecTecu(layer, 0.0, 0.0), 1e-6);
        assertEquals(26.1558728161,
                IgsSsrVtec.vtecTecu(layer, Math.toRadians(45.0), 1.0), 1e-6);

        // 标准要求：负 VTEC 置 0，系数范围 ±163.835 TECU
        double vtec = IgsSsrVtec.vtecTecu(layer, Math.toRadians(-20.0), 2.0);
        assertEquals(11.4304728408, vtec, 1e-6);
        assertTrue(vtec >= 0.0 && vtec <= 163.835);
    }

    @Test
    @DisplayName("未注册解码器时 4076 仍按原行为消费")
    void coreConsumes4076WithoutDecoder() throws IOException {
        Path sample = resolveSample();
        Assumptions.assumeTrue(sample != null, "样本不存在，跳过: " + SAMPLE);
        byte[] data = Files.readAllBytes(sample);

        Rtcm rtcm = new Rtcm();
        feed(rtcm, data);
        assertEquals(4076, rtcm.type);
    }

    private static void feed(Rtcm rtcm, byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) break;
            offset += consumed;
        }
        assertEquals(data.length, offset, "样本应被完整消费");
    }

    private static Map<Integer, Long> expectedCounts() {
        Map<Integer, Long> expected = new TreeMap<>();
        expected.put(21, 6L);
        expected.put(22, 6L);
        expected.put(25, 6L);
        expected.put(26, 5L);
        expected.put(61, 6L);
        expected.put(62, 6L);
        expected.put(65, 6L);
        expected.put(66, 5L);
        expected.put(101, 6L);
        expected.put(102, 6L);
        expected.put(105, 6L);
        expected.put(106, 5L);
        expected.put(201, 1L);
        expected.put(202, 1L);
        return expected;
    }

    private static IgsSsr4076Message firstOf(List<IgsSsr4076Message> messages, int subtype) {
        return messages.stream()
                .filter(m -> m.subtype == subtype)
                .findFirst()
                .orElseThrow();
    }

    /** 从工作目录向上查找样本，兼容仓库根目录或模块目录作为 user.dir */
    private static Path resolveSample() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(SAMPLE);
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }
}
