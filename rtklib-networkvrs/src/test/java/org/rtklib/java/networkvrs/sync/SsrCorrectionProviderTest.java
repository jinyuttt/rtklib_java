package org.rtklib.java.networkvrs.sync;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.time.TimeSystem;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SsrCorrectionProvider} 的 RTCM3 SSR 回放验证。
 *
 * <p>判据用外部真实录制流（CAS 播发的 SSR，1057~1068/1265~1270）：</p>
 * <ul>
 *   <li>能解码并建立逐卫星时间线，单历元可用改正星数达到可解算量级；</li>
 *   <li>改正量级落在合理范围（轨道 &lt; 30 m、钟差 &lt; 30 μs），排除单位/符号错误；</li>
 *   <li>注入 {@code nav.ssr[]} 后的记录时间戳等于查询历元，下游零阶项即当前改正；</li>
 *   <li>超出时效窗口后不再提供改正，避免过期数据静默参与解算。</li>
 * </ul>
 *
 * <p>文件路径：{@code -Dnetwork.ssr.file=<路径>}，缺省取 {@code test-data/gnss-validation/rtcm}
 * 下的录制流；不存在时跳过。</p>
 */
class SsrCorrectionProviderTest {

    private static final String SSR_FILE = System.getProperty("network.ssr.file", "");

    private static final String SSR_REFERENCE =
            System.getProperty("network.ssr.reference", "2026-10-26");

    private static GTime referenceTime() {
        String[] parts = SSR_REFERENCE.split("-");
        return TimeSystem.utc2gpst(TimeSystem.epoch2time(new double[]{
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), 0, 0, 0.0}));
    }

    private static Path resolveSsrFile() {
        if (!SSR_FILE.isEmpty()) {
            return Paths.get(SSR_FILE);
        }
        File base = new File(".." + File.separator + "test-data" + File.separator
                + "gnss-validation" + File.separator + "rtcm");
        if (!base.isDirectory()) {
            base = new File("test-data" + File.separator + "gnss-validation" + File.separator + "rtcm");
        }
        return base.toPath().resolve("ssr_ssrc01cas0_20261026.rtcm3");
    }

    @Test
    @DisplayName("RTCM3 SSR 回放：解码、时效与 nav.ssr 注入")
    void testReplayRecordedSsrStream() {
        Path file = resolveSsrFile();
        Assumptions.assumeTrue(Files.exists(file), "缺少 SSR 录制流，跳过: " + file);

        GTime reference = referenceTime();
        SsrCorrectionProvider provider = SsrCorrectionProvider.fromRtcm(
                file, reference, SsrCorrectionProvider.DEFAULT_MAX_AGE_SEC);

        Nav nav = new Nav();
        Set<Integer> allSats = new LinkedHashSet<>();
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            allSats.add(sat);
        }

        // 录制流只覆盖当日一小段，逐历元扫描全天以定位改正最完整的时刻
        int bestTime = -1;
        int bestCount = 0;
        double bestDeph = 0.0;
        double bestDclk = 0.0;
        for (int sec = 0; sec <= 86400; sec += 30) {
            GTime time = TimeSystem.timeadd(reference, sec);
            provider.applyAt(nav, time, allSats);
            int count = 0;
            double maxDeph = 0.0;
            double maxDclk = 0.0;
            for (Ssr ssr : nav.ssr) {
                if (ssr == null || ssr.update == 0) {
                    continue;
                }
                count++;
                double norm = Math.sqrt(ssr.deph[0] * ssr.deph[0]
                        + ssr.deph[1] * ssr.deph[1] + ssr.deph[2] * ssr.deph[2]);
                maxDeph = Math.max(maxDeph, norm);
                maxDclk = Math.max(maxDclk, Math.abs(ssr.dclk[0]));
            }
            if (count > bestCount) {
                bestCount = count;
                bestTime = sec;
                bestDeph = maxDeph;
                bestDclk = maxDclk;
            }
        }
        System.out.printf("[ssr-replay] file=%s 全天最佳历元=+%d s 可用星数=%d "
                        + "max|deph|=%.2f m max|dclk|=%.2f m (%.1f ns)%n",
                file.getFileName(), bestTime, bestCount, bestDeph, bestDclk,
                bestDclk / Constants.CLIGHT * 1e9);

        assertTrue(bestCount >= 8, "SSR 可用卫星数不足: " + bestCount);
        assertTrue(bestDeph > 0.0 && bestDeph < 30.0, "SSR 轨道改正量级异常: " + bestDeph + " m");
        assertTrue(bestDclk > 0.0 && bestDclk < 100.0, "SSR 钟差改正量级异常: " + bestDclk + " m");

        // 注入后的记录时间戳应等于查询历元，使下游零阶项即当前时刻改正
        GTime time = TimeSystem.timeadd(reference, bestTime);
        provider.applyAt(nav, time, allSats);
        int stamped = 0;
        for (Ssr ssr : nav.ssr) {
            if (ssr == null || ssr.update == 0) {
                continue;
            }
            assertEquals(0.0, TimeSystem.timediff(ssr.t0[0], time), 1e-3,
                    "轨道改正时间戳应归算到查询历元");
            assertEquals(0.0, TimeSystem.timediff(ssr.t0[1], time), 1e-3,
                    "钟差改正时间戳应归算到查询历元");
            stamped++;
        }
        assertEquals(bestCount, stamped, "同一历元应刷新全部可用卫星");

        // 超出时效后应全部失效，避免过期改正参与解算
        GTime stale = TimeSystem.timeadd(time, 2.0 * 86400.0);
        provider.applyAt(nav, stale, allSats);
        int alive = 0;
        for (Ssr ssr : nav.ssr) {
            if (ssr != null && ssr.update != 0) {
                alive++;
            }
        }
        assertEquals(0, alive, "超出时效的 SSR 改正应失效");
    }

    @Test
    @DisplayName("实时摄入：分块喂入与整文件喂入结果等价")
    void testIncrementalIngestMatchesWholeFile() throws Exception {
        Path file = resolveSsrFile();
        Assumptions.assumeTrue(Files.exists(file), "缺少 SSR 录制流，跳过: " + file);

        GTime reference = referenceTime();
        byte[] data = Files.readAllBytes(file);

        SsrCorrectionProvider whole = SsrCorrectionProvider.fromRtcm(
                file, reference, SsrCorrectionProvider.DEFAULT_MAX_AGE_SEC);

        // 刻意用非电文边界的块长，强制跨越帧切分以验证 pending 缓冲续接
        SsrCorrectionProvider chunked = SsrCorrectionProvider.live(
                reference, SsrCorrectionProvider.DEFAULT_MAX_AGE_SEC);
        int chunk = 137;
        for (int offset = 0; offset < data.length; offset += chunk) {
            int length = Math.min(chunk, data.length - offset);
            chunked.ingest(data, offset, length);
        }

        assertEquals(whole.satelliteCount(), chunked.satelliteCount(),
                "分块摄入应建立相同数量的卫星时间线");

        Nav navA = new Nav();
        Nav navB = new Nav();
        Set<Integer> allSats = new LinkedHashSet<>();
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            allSats.add(sat);
        }

        int checked = 0;
        for (int sec = 0; sec <= 86400; sec += 300) {
            GTime time = TimeSystem.timeadd(reference, sec);
            whole.applyAt(navA, time, allSats);
            chunked.applyAt(navB, time, allSats);
            for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
                Ssr a = navA.ssr[sat - 1];
                Ssr b = navB.ssr[sat - 1];
                boolean liveA = a != null && a.update != 0;
                boolean liveB = b != null && b.update != 0;
                assertEquals(liveA, liveB, "卫星 " + sat + " 在 +" + sec + " s 的可用性应一致");
                if (!liveA) {
                    continue;
                }
                assertEquals(a.deph[0], b.deph[0], 1e-9, "deph[0] 应一致");
                assertEquals(a.deph[1], b.deph[1], 1e-9, "deph[1] 应一致");
                assertEquals(a.deph[2], b.deph[2], 1e-9, "deph[2] 应一致");
                assertEquals(a.dclk[0], b.dclk[0], 1e-9, "dclk[0] 应一致");
                checked++;
            }
        }
        assertTrue(checked > 0, "应至少比对到一个有效改正");
        System.out.printf("[ssr-ingest] chunk=%d bytes, satellites=%d, 比对改正数=%d%n",
                chunk, whole.satelliteCount(), checked);
    }
}