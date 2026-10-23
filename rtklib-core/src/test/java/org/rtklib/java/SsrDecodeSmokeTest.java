package org.rtklib.java;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.rtcm.Rtcm;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSR 解码冒烟测试：验证 Rtcm.java 的 SSR 电文解码链路是活的。
 * <p>
 * 输入为 test-data/rtcm/ssr 下的真实录制单帧样本（来源于公开仓库
 * earthscope/gnsstools, codecs/rtcm/rtcm3/data），覆盖：
 * 1057/1058/1060（GPS 轨道/钟差/组合）、1063/1064（GLO 轨道/钟差）、
 * 1264/1265/1267/1270（GPS/GLO/QZS/IRN 相位偏差）。
 * </p>
 * <p>
 * 断言只做两件事：
 * 1) 每帧都能通过 CRC24Q 与对应解码器；
 * 2) 解码后 nav.ssr[] 中至少存在卫星的轨道改正（deph）与钟差改正（dclk）被真实赋值（非零）。
 * </p>
 */
@DisplayName("SSR解码冒烟测试")
class SsrDecodeSmokeTest {

    @Test
    void decodeSsrFrames() throws Exception {
        File dir = new File(TestDataConfig.getTestDataDir(), "rtcm" + File.separator + "ssr");
        File[] frames = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".bin"));
        Assumptions.assumeTrue(frames != null && frames.length > 0,
                "未找到SSR样本帧，目录: " + dir);

        Arrays.sort(frames, Comparator.comparing(File::getName));
        Rtcm rtcm = new Rtcm();
        Set<Integer> decodedTypes = new LinkedHashSet<>();
        int decoded = 0;

        for (File frame : frames) {
            byte[] raw = Files.readAllBytes(frame.toPath());
            int consumed = rtcm.input(raw, raw.length);
            if (consumed <= 0) {
                System.out.println("[SSR-SMOKE] " + frame.getName() + " 解码失败, consumed=" + consumed);
                continue;
            }
            decoded++;
            decodedTypes.add(rtcm.type);
            System.out.println("[SSR-SMOKE] " + frame.getName() + " -> RTCM " + rtcm.type
                    + " (" + raw.length + " bytes)");
        }

        assertEquals(frames.length, decoded, "所有SSR样本帧都应通过CRC与解码器");
        System.out.println("[SSR-SMOKE] 已解码电文类型: " + decodedTypes);

        int nOrbit = 0, nClock = 0, nPhase = 0;
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            Ssr ssr = rtcm.nav.ssr[sat - 1];
            if (ssr == null) continue;

            boolean hasOrbit = ssr.deph[0] != 0.0 || ssr.deph[1] != 0.0 || ssr.deph[2] != 0.0;
            boolean hasClock = ssr.dclk[0] != 0.0 || ssr.dclk[1] != 0.0
                    || ssr.dclk[2] != 0.0 || ssr.hrclk != 0.0;

            StringBuilder sig = new StringBuilder();
            int nBias = 0;
            for (int code = 0; code < ssr.pbias.length; code++) {
                if (ssr.pbias[code] != 0.0) {
                    if (nBias > 0) sig.append(',');
                    sig.append(code + 1).append(':').append(String.format("%.4f", ssr.pbias[code]));
                    nBias++;
                }
            }

            if (hasOrbit) nOrbit++;
            if (hasClock) nClock++;
            if (nBias > 0) nPhase++;
            if (!hasOrbit && !hasClock && nBias == 0) continue;

            System.out.println(String.format(
                    "[SSR-SMOKE] %s orbit=%s deph=(%s,%s,%s)m ddeph=(%s,%s,%s)m/s "
                            + "clock=%s dclk=(%s,%s,%s)m hrclk=%sm pbias=%d [%s]",
                    SatUtils.satno2id(sat), hasOrbit, fmt(ssr.deph[0]), fmt(ssr.deph[1]), fmt(ssr.deph[2]),
                    fmt(ssr.ddeph[0]), fmt(ssr.ddeph[1]), fmt(ssr.ddeph[2]),
                    hasClock, fmt(ssr.dclk[0]), fmt(ssr.dclk[1]), fmt(ssr.dclk[2]), fmt(ssr.hrclk),
                    nBias, sig));
        }

        System.out.println("[SSR-SMOKE] 汇总: 轨道改正卫星=" + nOrbit
                + ", 钟差改正卫星=" + nClock + ", 相位偏差卫星=" + nPhase);

        assertTrue(nOrbit > 0, "nav.ssr[].deph 未被赋值，SSR轨道改正解码链路异常");
        assertTrue(nClock > 0, "nav.ssr[].dclk/hrclk 未被赋值，SSR钟差改正解码链路异常");
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }
}
