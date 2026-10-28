package org.rtklib.java.networkvrs.sync;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 RINEX 三站集成测试。
 *
 * <p>数据不在仓库内：通过 {@code -Dnetwork.data.dir=<目录>} 指定，
 * 目录内需存在 {@code CIMO00ITA_R_..._MO.rnx}、{@code PRAT00ITA_...}、{@code VIRG00ITA_...}。
 * 未指定或文件缺失时自动跳过。</p>
 */
class RinexStationLoaderTest {

    private static final String DATA_DIR = System.getProperty("network.data.dir", "");

    @Test
    @DisplayName("真实三站 RINEX：装载坐标、历元对齐")
    void testLoadAndSynchronize() {
        Assumptions.assumeFalse(DATA_DIR.isEmpty(), "未指定 -Dnetwork.data.dir，跳过");

        Path dir = Paths.get(DATA_DIR);
        Path cimo = find(dir, "CIMO00ITA");
        Path prat = find(dir, "PRAT00ITA");
        Path virg = find(dir, "VIRG00ITA");
        Assumptions.assumeTrue(cimo != null && prat != null && virg != null, "三站 RINEX 文件缺失，跳过");

        StationObservation a = RinexStationLoader.load("CIMO", cimo);
        StationObservation b = RinexStationLoader.load("PRAT", prat);
        StationObservation c = RinexStationLoader.load("VIRG", virg);

        for (StationObservation station : List.of(a, b, c)) {
            assertFalse(station.observations().isEmpty());
            double[] ecef = station.ecef();
            assertTrue(Math.hypot(ecef[0], ecef[1]) > 1e6, "站点坐标应有效");
        }

        List<SynchronizedEpoch> epochs = new MultiStationSynchronizer(0.5, 3)
                .synchronize(List.of(a, b, c));

        assertTrue(epochs.size() > 1000, "24h/30s 应产生大量同步历元");
        assertFalse(epochs.get(0).commonSatellites().isEmpty());
    }

    private static Path find(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".rnx"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
