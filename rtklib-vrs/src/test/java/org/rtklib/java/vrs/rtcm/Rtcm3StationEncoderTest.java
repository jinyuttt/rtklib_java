package org.rtklib.java.vrs.rtcm;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rtcm.Rtcm;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1032 / 1033 站点消息编码验证。
 *
 * <p>1033 的判据是外部真实录制流：从 {@code record_wuh20.msm.rtcm3} 取真实 1033 帧，
 * 用 {@link Rtcm} 解码出字段后重编码，与原帧逐字节比对。自研解码器在这里只作为
 * 字段提取的脚手架，最终判据是字节相等。</p>
 */
public class Rtcm3StationEncoderTest {

    private static final File RECORD = new File(TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "rtcm"
            + File.separator + "record_wuh20.msm.rtcm3");

    @Test
    @DisplayName("1033：真实帧解码→重编码→逐字节一致")
    void testType1033RoundTripWithRealFrame() throws Exception {
        Assumptions.assumeTrue(RECORD.exists(), "缺少录制流 record_wuh20.msm.rtcm3，跳过");

        List<byte[]> frames = extractFrames(Files.readAllBytes(RECORD.toPath()), 1033);
        Assumptions.assumeFalse(frames.isEmpty(), "录制流中无 1033 帧，跳过");

        Rtcm rtcm = new Rtcm();
        Rtcm3Encoder encoder = new Rtcm3Encoder();

        for (byte[] frame : frames) {
            assertEquals(frame.length, rtcm.input(frame, frame.length));

            Sta sta = rtcm.sta;
            assertEquals(621, rtcm.staid);
            assertEquals("JAVRINGANT_G5T  NONE", sta.antdes);
            assertEquals("3354C", sta.antsno);
            assertEquals("JAVAD TR_2S", sta.rectype);
            assertEquals("4.6.01-251111", sta.recver);
            assertEquals("5218113", sta.recsno);

            encoder.stationId = 621;
            encoder.sta = new Sta(sta);
            byte[] rebuilt = encoder.encode(1033, 0, 0);
            assertNotNull(rebuilt);
            assertArrayEquals(frame, rebuilt);
        }
    }

    @Test
    @DisplayName("1033：仅接收机类型的短帧可解码（天线描述为空）")
    void testType1033ShortFrame() throws Exception {
        File gmsd = new File(TestDataConfig.getTestDataDir()
                + File.separator + "rtcm" + File.separator + "rtcm3_gmsd_20121014.rtcm3");
        Assumptions.assumeTrue(gmsd.exists(), "缺少 rtcm3_gmsd 样本，跳过");

        List<byte[]> frames = extractFrames(Files.readAllBytes(gmsd.toPath()), 1033);
        Assumptions.assumeFalse(frames.isEmpty(), "样本中无 1033 帧，跳过");

        Rtcm rtcm = new Rtcm();
        Rtcm3Encoder encoder = new Rtcm3Encoder();
        for (byte[] frame : frames) {
            assertEquals(frame.length, rtcm.input(frame, frame.length));
            assertEquals("TRIMBLE NETR9", rtcm.sta.rectype);
            assertTrue(rtcm.sta.antdes.isEmpty());

            encoder.stationId = rtcm.staid;
            encoder.sta = new Sta(rtcm.sta);
            assertArrayEquals(frame, encoder.encode(1033, 0, 0));
        }
    }

    @Test
    @DisplayName("1032：物理参考站坐标编码位域与 1005 同构，长度 25 字节")
    void testType1032Encode() {
        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = 42;
        encoder.sta = new Sta();
        encoder.sta.xyz = new double[]{4447188.6811, 601402.2782, 4473056.4848};

        byte[] msg = encoder.encode(1032, 0, 0);
        assertNotNull(msg);
        assertEquals(25, msg.length);
        assertEquals(1032, (int) org.rtklib.java.common.BitUtils.getbitu(msg, 24, 12));
        assertEquals(42, (int) org.rtklib.java.common.BitUtils.getbitu(msg, 36, 12));

        double x = read38(msg, 58);
        double y = read38(msg, 97);
        double z = read38(msg, 137);
        assertEquals(4447188.6811, x, 1e-4);
        assertEquals(601402.2782, y, 1e-4);
        assertEquals(4473056.4848, z, 1e-4);
    }

    private static double read38(byte[] buff, int pos) {
        int high = (int) org.rtklib.java.common.BitUtils.getbits(buff, pos, 32);
        long low = org.rtklib.java.common.BitUtils.getbitu(buff, pos + 32, 6);
        return (high * 64.0 + low) * 0.0001;
    }

    /** 按 RTCM3 帧头切分并筛出指定消息类型。 */
    private static List<byte[]> extractFrames(byte[] data, int type) {
        List<byte[]> out = new ArrayList<>();
        int pos = 0;
        while (pos + 6 <= data.length) {
            if ((data[pos] & 0xFF) != 0xD3) {
                pos++;
                continue;
            }
            int len = ((data[pos + 1] & 0x03) << 8) | (data[pos + 2] & 0xFF);
            int total = len + 6;
            if (pos + total > data.length) break;
            int mt = ((data[pos + 3] & 0xFF) << 4) | ((data[pos + 4] & 0xF0) >> 4);
            if (mt == type) {
                byte[] frame = new byte[total];
                System.arraycopy(data, pos, frame, 0, total);
                out.add(frame);
            }
            pos += total;
        }
        return out;
    }
}