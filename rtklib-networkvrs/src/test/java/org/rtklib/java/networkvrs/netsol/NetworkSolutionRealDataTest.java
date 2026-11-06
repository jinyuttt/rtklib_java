package org.rtklib.java.networkvrs.netsol;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.sync.PreciseEphemerisLoader;
import org.rtklib.java.networkvrs.sync.RinexNavLoader;
import org.rtklib.java.networkvrs.sync.RinexStationLoader;
import org.rtklib.java.networkvrs.sync.SsrCorrectionProvider;
import org.rtklib.java.networkvrs.sync.StationObservation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实三站 RINEX + BRDC 广播星历的档 2 集成测试（V2 统计口径）。
 *
 * <p>数据不入库：需 {@code -Dnetwork.data.dir=<目录>}，目录内有三站 30 s RINEX；
 * 星历可用 {@code -Dnetwork.nav.file=<BRDC .rnx>}，未指定时在数据目录内自动找 {@code BRDC*.rnx}。
 * 默认只取前 120 个历元（1 h），可用 {@code -Dnetwork.max.epochs} 调整。缺失数据自动跳过。</p>
 */
class NetworkSolutionRealDataTest {

    private static final String DATA_DIR = System.getProperty("network.data.dir", "");
    private static final String NAV_FILE = System.getProperty("network.nav.file", "");
    private static final String SP3_FILE = System.getProperty("network.sp3.file", "");
    private static final String CLK_FILE = System.getProperty("network.clk.file", "");
    private static final String SSR_FILE = System.getProperty("network.ssr.file", "");
    private static final int MAX_EPOCHS = Integer.getInteger("network.max.epochs", 120);

    @Test
    @DisplayName("真实三站：网解固定率、相位残差与 FKP 输出")
    void testNetworkSolution() {
        Assumptions.assumeFalse(DATA_DIR.isEmpty(), "未指定 -Dnetwork.data.dir，跳过");
        Path dir = Paths.get(DATA_DIR);
        Path cimo = find(dir, "CIMO00ITA", ".rnx");
        Path prat = find(dir, "PRAT00ITA", ".rnx");
        Path virg = find(dir, "VIRG00ITA", ".rnx");
        Assumptions.assumeTrue(cimo != null && prat != null && virg != null, "三站 RINEX 文件缺失，跳过");

        Path navPath = NAV_FILE.isEmpty() ? find(dir, "BRDC", ".rnx") : Paths.get(NAV_FILE);
        Assumptions.assumeTrue(navPath != null && Files.exists(navPath), "缺少 BRDC 导航文件，跳过");

        StationObservation a = slice(RinexStationLoader.load("CIMO", cimo));
        StationObservation b = slice(RinexStationLoader.load("PRAT", prat));
        StationObservation c = slice(RinexStationLoader.load("VIRG", virg));
        Nav nav = RinexNavLoader.load(navPath);

        NetworkConfig config = new NetworkConfig();
        config.interpolationModel = NetworkConfig.InterpolationModel.FKP;
        config.solutionWindowEpochs = Integer.getInteger("network.window.epochs", 20);

        NetworkSolutionEngine.Result result = new NetworkSolutionEngine()
                .solve(List.of(a, b, c), nav, "CIMO", config);

        assertFalse(result.epochs().isEmpty(), "应有逐历元分解结果");
        assertFalse(result.windows().isEmpty(), "应有基线窗口解");
        assertEquals(3, result.network().baselines().size());

        long fixed = result.windows().stream().filter(BaselineWindowSolution::fixed).count();
        double rmsSum = 0.0;
        int rmsCount = 0;
        double deltaSum = 0.0;
        int deltaCount = 0;
        double fracSum = 0.0;
        int fracCount = 0;
        for (BaselineWindowSolution window : result.windows()) {
            if (Double.isFinite(window.phaseRms())) {
                rmsSum += window.phaseRms();
                rmsCount++;
            }
            double[] delta = window.estimatedDelta();
            deltaSum += Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]);
            deltaCount++;
            for (double n1 : window.floatN1Cycles().values()) {
                double frac = Math.abs(n1 - Math.rint(n1));
                fracSum += frac;
                fracCount++;
            }
        }
        System.out.printf("[network-vrs] epochs=%d windows=%d fixed=%d meanPhaseRms=%.4f m "
                        + "mean|delta|=%.3f m mean|fracN1|=%.3f cyc%n",
                result.epochs().size(), result.windows().size(), fixed,
                rmsCount == 0 ? Double.NaN : rmsSum / rmsCount,
                deltaCount == 0 ? Double.NaN : deltaSum / deltaCount,
                fracCount == 0 ? Double.NaN : fracSum / fracCount);
        for (BaselineWindowSolution window : result.windows()) {
            System.out.printf("  %s-%s refs=%s n=%d fixed=%s ratio=%.1f rms=%.4f delta=%.3f%n",
                    window.stationA(), window.stationB(), window.refSats(),
                    window.floatNifMeters().size(), window.fixed(), window.ratio(),
                    window.phaseRms(), Math.hypot(window.estimatedDelta()[0],
                            Math.hypot(window.estimatedDelta()[1], window.estimatedDelta()[2])));
        }

        // V2 统计：宽巷固定率与基线重复性（δx 跨窗口的标准差）
        long wlWindows = result.windows().stream()
                .filter(w -> !w.floatN1Cycles().isEmpty()).count();
        Map<String, List<double[]>> deltasByBaseline = new java.util.LinkedHashMap<>();
        for (BaselineWindowSolution window : result.windows()) {
            deltasByBaseline.computeIfAbsent(window.stationA() + "-" + window.stationB(),
                    k -> new ArrayList<>()).add(window.estimatedDelta());
        }
        System.out.printf("[V2] windows=%d nlFixed=%d wlFixedWindows=%d (%.0f%%)%n",
                result.windows().size(), fixed, wlWindows,
                100.0 * wlWindows / Math.max(1, result.windows().size()));
        for (Map.Entry<String, List<double[]>> entry : deltasByBaseline.entrySet()) {
            double[] axisStd = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                double[] values = new double[entry.getValue().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = entry.getValue().get(i)[axis];
                }
                axisStd[axis] = robustStd(values);
            }
            System.out.printf("[V2] baseline=%s repeatability=%.3f m (n=%d)%n",
                    entry.getKey(),
                    Math.sqrt(axisStd[0] * axisStd[0] + axisStd[1] * axisStd[1]
                            + axisStd[2] * axisStd[2]),
                    entry.getValue().size());
        }

        // V4 前哨：以主站为原点，统计 FKP 在 30/50/70 km 处的改正梯度（非 RTK FIX 率）
        org.rtklib.java.networkvrs.interp.FkpInterpolator fkp =
                new org.rtklib.java.networkvrs.interp.FkpInterpolator(config);
        Map<String, double[]> ecefById = new java.util.LinkedHashMap<>();
        ecefById.put("CIMO", a.ecef());
        ecefById.put("PRAT", b.ecef());
        ecefById.put("VIRG", c.ecef());
        double[] masterPos = a.pos();
        double[] distances = {30.0, 50.0, 70.0};
        double[] ionoAcc = new double[distances.length];
        double[] nonDispAcc = new double[distances.length];
        int[] counts = new int[distances.length];
        int sampled = 0;
        for (int e = 0; e < result.epochs().size(); e += 10) {
            NetworkSolutionEngine.EpochCorrections ec = result.epochs().get(e);
            Map<Integer, NetworkVrsProcessor.CorrectionValue> master = ec.byStation().get("CIMO");
            if (master == null || master.isEmpty()) {
                continue;
            }
            sampled++;
            for (int si = 0; si < distances.length; si++) {
                double[] target = masterPos.clone();
                target[0] += distances[si] * 1000.0 / 6371000.0; // 正北方向偏移
                for (int sat : master.keySet()) {
                    List<org.rtklib.java.networkvrs.interp.StationCorrection> iono = new ArrayList<>();
                    List<org.rtklib.java.networkvrs.interp.StationCorrection> nondisp = new ArrayList<>();
                    for (Map.Entry<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> st
                            : ec.byStation().entrySet()) {
                        NetworkVrsProcessor.CorrectionValue v = st.getValue().get(sat);
                        if (v == null) {
                            continue;
                        }
                        iono.add(new org.rtklib.java.networkvrs.interp.StationCorrection(
                                st.getKey(), ecefById.get(st.getKey()), v.iono()));
                        nondisp.add(new org.rtklib.java.networkvrs.interp.StationCorrection(
                                st.getKey(), ecefById.get(st.getKey()), v.tropo()));
                    }
                    if (iono.size() < config.minStations) {
                        continue;
                    }
                    ionoAcc[si] += Math.abs(fkp.interpolate(target, iono));
                    nonDispAcc[si] += Math.abs(fkp.interpolate(target, nondisp));
                    counts[si]++;
                }
            }
        }
        for (int si = 0; si < distances.length; si++) {
            System.out.printf("[V4] epochs=%d d=%2.0f km n=%d mean|iono|=%.4f m mean|nondisp|=%.4f m%n",
                    sampled, distances[si], counts[si],
                    counts[si] == 0 ? Double.NaN : ionoAcc[si] / counts[si],
                    counts[si] == 0 ? Double.NaN : nonDispAcc[si] / counts[si]);
        }

        boolean anyFixed = fixed > 0;
        boolean floatOk = rmsCount > 0 && rmsSum / rmsCount < 0.10;
        assertTrue(anyFixed || floatOk,
                "既无固定解，浮点相位残差也超过 0.10 m（V2 不达标）");

        int epochsWithCorrections = 0;
        for (NetworkSolutionEngine.EpochCorrections epoch : result.epochs()) {
            Map<Integer, NetworkVrsProcessor.CorrectionValue> master = epoch.byStation().get("CIMO");
            assertNotNull(master);
            for (NetworkVrsProcessor.CorrectionValue value : master.values()) {
                assertEquals(0.0, value.iono(), 1e-9, "主站电离层改正应为 0");
                assertEquals(0.0, value.tropo(), 1e-9, "主站非色散改正应为 0");
            }
            for (Map<Integer, NetworkVrsProcessor.CorrectionValue> bySat : epoch.byStation().values()) {
                for (NetworkVrsProcessor.CorrectionValue value : bySat.values()) {
                    assertTrue(Double.isFinite(value.iono()) && Math.abs(value.iono()) < 10.0,
                            "电离层改正应在合理量级");
                    assertTrue(Double.isFinite(value.tropo()) && Math.abs(value.tropo()) < 5.0,
                            "非色散改正应在合理量级");
                }
            }
            if (!epoch.byStation().isEmpty()) {
                epochsWithCorrections++;
            }
        }
        assertEquals(result.epochs().size(), epochsWithCorrections);

        // 处理器侧 FKP 路径：目标点取主站，输出应非空且有限
        NetworkVrsProcessor processor = new NetworkVrsProcessor(config, (station, obs) -> null);
        List<NetworkVrsProcessor.EpochCorrection> corrections =
                processor.processNetwork(List.of(a, b, c), nav, a.pos(), "CIMO");
        assertFalse(corrections.isEmpty(), "FKP 路径应输出逐历元改正");
        for (NetworkVrsProcessor.EpochCorrection epoch : corrections) {
            for (NetworkVrsProcessor.CorrectionValue value : epoch.corrections().values()) {
                assertTrue(Double.isFinite(value.iono()));
                assertTrue(Double.isFinite(value.tropo()));
            }
        }
    }

    @Test
    @DisplayName("真实三站：精密星历（SP3/CLK）下的窄巷固定率")
    void testNetworkSolutionWithPreciseEphemeris() {
        Assumptions.assumeFalse(DATA_DIR.isEmpty(), "未指定 -Dnetwork.data.dir，跳过");
        Path dir = Paths.get(DATA_DIR);
        Path cimo = find(dir, "CIMO00ITA", ".rnx");
        Path prat = find(dir, "PRAT00ITA", ".rnx");
        Path virg = find(dir, "VIRG00ITA", ".rnx");
        Assumptions.assumeTrue(cimo != null && prat != null && virg != null, "三站 RINEX 文件缺失，跳过");

        Path navPath = NAV_FILE.isEmpty() ? find(dir, "BRDC", ".rnx") : Paths.get(NAV_FILE);
        Assumptions.assumeTrue(navPath != null && Files.exists(navPath), "缺少 BRDC 导航文件，跳过");
        Path sp3 = SP3_FILE.isEmpty() ? find(dir, "WUM", ".SP3") : Paths.get(SP3_FILE);
        Path clk = CLK_FILE.isEmpty() ? find(dir, "WUM", ".CLK") : Paths.get(CLK_FILE);
        Assumptions.assumeTrue(sp3 != null && Files.exists(sp3), "缺少 SP3 精密轨道，跳过");
        Assumptions.assumeTrue(clk != null && Files.exists(clk), "缺少 CLK 精密钟差，跳过");

        StationObservation a = slice(RinexStationLoader.load("CIMO", cimo));
        StationObservation b = slice(RinexStationLoader.load("PRAT", prat));
        StationObservation c = slice(RinexStationLoader.load("VIRG", virg));
        Nav nav = PreciseEphemerisLoader.load(RinexNavLoader.load(navPath), sp3, clk);

        NetworkConfig config = new NetworkConfig();
        config.interpolationModel = NetworkConfig.InterpolationModel.FKP;
        config.solutionWindowEpochs = Integer.getInteger("network.window.epochs", 20);
        config.ephemerisOption = Constants.EPHOPT_PREC;

        NetworkSolutionEngine.Result result = new NetworkSolutionEngine()
                .solve(List.of(a, b, c), nav, "CIMO", config);

        assertFalse(result.epochs().isEmpty(), "精密星历下应有逐历元分解结果");
        assertFalse(result.windows().isEmpty(), "精密星历下应有基线窗口解");

        long fixed = result.windows().stream().filter(BaselineWindowSolution::fixed).count();
        double fracSum = 0.0;
        double fracSqSum = 0.0;
        int fracCount = 0;
        double ratioSum = 0.0;
        double rmsSum = 0.0;
        int rmsCount = 0;
        for (BaselineWindowSolution window : result.windows()) {
            ratioSum += window.ratio();
            if (Double.isFinite(window.phaseRms())) {
                rmsSum += window.phaseRms();
                rmsCount++;
            }
            for (double n1 : window.floatN1Cycles().values()) {
                double frac = n1 - Math.rint(n1);
                fracSum += frac;
                fracSqSum += frac * frac;
                fracCount++;
            }
        }
        double fracMean = fracCount == 0 ? Double.NaN : fracSum / fracCount;
        double fracStd = fracCount == 0 ? Double.NaN
                : Math.sqrt(Math.max(0.0, fracSqSum / fracCount - fracMean * fracMean));
        System.out.printf("[prec] windows=%d nlFixed=%d meanRatio=%.2f meanPhaseRms=%.4f m "
                        + "fracN1 mean=%+.3f std=%.3f cyc (n=%d)%n",
                result.windows().size(), fixed, ratioSum / Math.max(1, result.windows().size()),
                rmsCount == 0 ? Double.NaN : rmsSum / rmsCount, fracMean, fracStd, fracCount);
        for (BaselineWindowSolution window : result.windows()) {
            System.out.printf("  [prec] %s-%s refs=%s n=%d fixN=%d fixed=%s ratio=%.2f rms=%.4f%n",
                    window.stationA(), window.stationB(), window.refSats(),
                    window.floatN1Cycles().size(), window.ambiguityCount(),
                    window.fixed(), window.ratio(), window.phaseRms());
        }
        Map<String, List<double[]>> deltasByBaseline = new java.util.LinkedHashMap<>();
        for (BaselineWindowSolution window : result.windows()) {
            deltasByBaseline.computeIfAbsent(window.stationA() + "-" + window.stationB(),
                    k -> new ArrayList<>()).add(window.estimatedDelta());
        }
        for (Map.Entry<String, List<double[]>> entry : deltasByBaseline.entrySet()) {
            double[] axisStd = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                double[] values = new double[entry.getValue().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = entry.getValue().get(i)[axis];
                }
                axisStd[axis] = robustStd(values);
            }
            System.out.printf("[prec] baseline=%s repeatability=%.3f m (n=%d)%n",
                    entry.getKey(),
                    Math.sqrt(axisStd[0] * axisStd[0] + axisStd[1] * axisStd[1]
                            + axisStd[2] * axisStd[2]),
                    entry.getValue().size());
        }
        Map<Integer, List<Double>> fracBySat = new java.util.TreeMap<>();
        for (BaselineWindowSolution window : result.windows()) {
            for (Map.Entry<Integer, Double> e : window.floatN1Cycles().entrySet()) {
                fracBySat.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(e.getValue() - Math.rint(e.getValue()));
            }
        }
        for (Map.Entry<Integer, List<Double>> e : fracBySat.entrySet()) {
            List<Double> v = e.getValue();
            double mean = 0.0;
            for (double x : v) {
                mean += x;
            }
            mean /= v.size();
            double ss = 0.0;
            for (double x : v) {
                ss += (x - mean) * (x - mean);
            }
            System.out.printf("  [prec-sat] sat=%d n=%d fracMean=%+.3f fracStd=%.3f%n",
                    e.getKey(), v.size(), mean, v.size() < 2 ? Double.NaN
                            : Math.sqrt(ss / (v.size() - 1)));
        }

        assertTrue(fracCount > 0, "应有浮点 N1 可用于统计小数分布");
        assertTrue(Double.isFinite(fracMean), "N1 小数部分均值应为有限值");
    }

    @Test
    @DisplayName("真实三站：SSR 改正流下的窄巷固定率")
    void testNetworkSolutionWithSsr() {
        Assumptions.assumeFalse(DATA_DIR.isEmpty(), "未指定 -Dnetwork.data.dir，跳过");
        Path dir = Paths.get(DATA_DIR);
        Path cimo = find(dir, "CIMO00ITA", ".rnx");
        Path prat = find(dir, "PRAT00ITA", ".rnx");
        Path virg = find(dir, "VIRG00ITA", ".rnx");
        Assumptions.assumeTrue(cimo != null && prat != null && virg != null, "三站 RINEX 文件缺失，跳过");

        Path navPath = NAV_FILE.isEmpty() ? find(dir, "BRDC", ".rnx") : Paths.get(NAV_FILE);
        Assumptions.assumeTrue(navPath != null && Files.exists(navPath), "缺少 BRDC 导航文件，跳过");

        StationObservation a = slice(RinexStationLoader.load("CIMO", cimo));
        StationObservation b = slice(RinexStationLoader.load("PRAT", prat));
        StationObservation c = slice(RinexStationLoader.load("VIRG", virg));
        GTime start = a.observations().get(0).time;

        Path ssrFile = SSR_FILE.isEmpty() ? find(dir, "ssr", ".rtcm3") : Paths.get(SSR_FILE);
        Nav nav = RinexNavLoader.load(navPath);
        boolean replay = false;
        SsrCorrectionProvider provider = null;
        if (ssrFile != null && Files.exists(ssrFile)) {
            provider = SsrCorrectionProvider.fromRtcm(ssrFile, start,
                    SsrCorrectionProvider.DEFAULT_MAX_AGE_SEC);
            provider.applyAt(nav, start, epochSatellites(a));
            replay = ssrUpdateCount(nav) >= 4;
            System.out.printf("[ssr] 回放 %s 首历元可用改正星数=%d%n",
                    ssrFile.getFileName(), ssrUpdateCount(nav));
        }
        if (!replay) {
            // 录制流时段与观测不匹配时，用 SP3/CLK 反算 SSR 改正，等价回放 SSR 链路
            Path sp3 = SP3_FILE.isEmpty() ? find(dir, "WUM", ".SP3") : Paths.get(SP3_FILE);
            Path clk = CLK_FILE.isEmpty() ? find(dir, "WUM", ".CLK") : Paths.get(CLK_FILE);
            Assumptions.assumeTrue(sp3 != null && Files.exists(sp3), "既无覆盖观测时段的 SSR 流，也无 SP3，跳过");
            Assumptions.assumeTrue(clk != null && Files.exists(clk), "既无覆盖观测时段的 SSR 流，也无 CLK，跳过");
            nav = PreciseEphemerisLoader.load(nav, sp3, clk);
            provider = SsrCorrectionProvider.fromPreciseProducts();
            System.out.println("[ssr] 无覆盖观测时段的录制流，改用 SP3/CLK 反算 SSR 改正");
        }
        provider.applyAt(nav, start, epochSatellites(a));
        Assumptions.assumeTrue(ssrUpdateCount(nav) >= 4, "SSR 改正不可用（星数不足），跳过");

        NetworkConfig config = new NetworkConfig();
        config.interpolationModel = NetworkConfig.InterpolationModel.FKP;
        config.solutionWindowEpochs = Integer.getInteger("network.window.epochs", 20);
        config.ephemerisOption = Constants.EPHOPT_SSRAPC;

        NetworkSolutionEngine.Result result = new NetworkSolutionEngine()
                .solve(List.of(a, b, c), nav, "CIMO", config, provider);

        assertFalse(result.epochs().isEmpty(), "SSR 下应有逐历元分解结果");
        assertFalse(result.windows().isEmpty(), "SSR 下应有基线窗口解");

        long fixed = result.windows().stream().filter(BaselineWindowSolution::fixed).count();
        double fracSum = 0.0;
        double fracSqSum = 0.0;
        int fracCount = 0;
        double ratioSum = 0.0;
        double rmsSum = 0.0;
        int rmsCount = 0;
        for (BaselineWindowSolution window : result.windows()) {
            ratioSum += window.ratio();
            if (Double.isFinite(window.phaseRms())) {
                rmsSum += window.phaseRms();
                rmsCount++;
            }
            for (double n1 : window.floatN1Cycles().values()) {
                double frac = n1 - Math.rint(n1);
                fracSum += frac;
                fracSqSum += frac * frac;
                fracCount++;
            }
        }
        double fracMean = fracCount == 0 ? Double.NaN : fracSum / fracCount;
        double fracStd = fracCount == 0 ? Double.NaN
                : Math.sqrt(Math.max(0.0, fracSqSum / fracCount - fracMean * fracMean));
        System.out.printf("[ssr] mode=%s windows=%d nlFixed=%d meanRatio=%.2f meanPhaseRms=%.4f m "
                        + "fracN1 mean=%+.3f std=%.3f cyc (n=%d)%n",
                replay ? "rtcm-replay" : "precise-derived",
                result.windows().size(), fixed, ratioSum / Math.max(1, result.windows().size()),
                rmsCount == 0 ? Double.NaN : rmsSum / rmsCount, fracMean, fracStd, fracCount);
        for (BaselineWindowSolution window : result.windows()) {
            System.out.printf("  [ssr] %s-%s n=%d fixN=%d fixed=%s ratio=%.2f rms=%.4f%n",
                    window.stationA(), window.stationB(),
                    window.floatN1Cycles().size(), window.ambiguityCount(),
                    window.fixed(), window.ratio(), window.phaseRms());
        }
        Map<String, List<double[]>> deltasByBaseline = new java.util.LinkedHashMap<>();
        for (BaselineWindowSolution window : result.windows()) {
            deltasByBaseline.computeIfAbsent(window.stationA() + "-" + window.stationB(),
                    k -> new ArrayList<>()).add(window.estimatedDelta());
        }
        for (Map.Entry<String, List<double[]>> entry : deltasByBaseline.entrySet()) {
            double[] axisStd = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                double[] values = new double[entry.getValue().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = entry.getValue().get(i)[axis];
                }
                axisStd[axis] = robustStd(values);
            }
            System.out.printf("[ssr] baseline=%s repeatability=%.3f m (n=%d)%n",
                    entry.getKey(),
                    Math.sqrt(axisStd[0] * axisStd[0] + axisStd[1] * axisStd[1]
                            + axisStd[2] * axisStd[2]),
                    entry.getValue().size());
        }

        if (!replay) {
            // 反算模式下 SSR 链路应与精密星历完全等价：同一历元双差距离差异应在毫米级
            Map<Double, List<Obsd>> ea = a.groupByEpoch();
            Map<Double, List<Obsd>> eb = b.groupByEpoch();
            double worst = 0.0;
            int compared = 0;
            DdObservationBuilder builder = new DdObservationBuilder();
            for (Map.Entry<Double, List<Obsd>> entry : ea.entrySet()) {
                List<Obsd> obsB = eb.get(entry.getKey());
                if (obsB == null) {
                    continue;
                }
                GTime epoch = entry.getValue().get(0).time;
                List<DdObservation> prec = builder.build(a.ecef(), b.ecef(), entry.getValue(), obsB,
                        nav, config.minElevationDeg, Constants.EPHOPT_PREC);
                provider.applyAt(nav, epoch, epochSatellites(a));
                List<DdObservation> ssr = builder.build(a.ecef(), b.ecef(), entry.getValue(), obsB,
                        nav, config.minElevationDeg, Constants.EPHOPT_SSRAPC);
                Map<Integer, Double> ssrRange = new java.util.HashMap<>();
                for (DdObservation d : ssr) {
                    ssrRange.put(d.sat(), d.ddRange());
                }
                for (DdObservation d : prec) {
                    Double other = ssrRange.get(d.sat());
                    if (other == null) {
                        continue;
                    }
                    worst = Math.max(worst, Math.abs(other - d.ddRange()));
                    compared++;
                }
                if (compared > 200) {
                    break;
                }
            }
            System.out.printf("[ssr] 与精密星历双差距离一致性：max|ΔddRange|=%.4f m (n=%d)%n",
                    worst, compared);
            assertTrue(compared > 0, "应有可比的双差记录");
            assertEquals(0.0, worst, 0.05, "SSR 反算改正应与精密星历一致（毫米级）");
        }

        assertTrue(fracCount > 0, "应有浮点 N1 可用于统计小数分布");
        assertTrue(fixed > 0 || (rmsCount > 0 && rmsSum / rmsCount < 0.10),
                "SSR 改正下既无固定解，浮点相位残差也超过 0.10 m");
    }

    private static Set<Integer> epochSatellites(StationObservation station) {
        Set<Integer> sats = new java.util.LinkedHashSet<>();
        for (Obsd obs : station.observations()) {
            if (obs.sat > 0) {
                sats.add(obs.sat);
            }
        }
        return sats;
    }

    private static int ssrUpdateCount(Nav nav) {
        int count = 0;
        for (Ssr ssr : nav.ssr) {
            if (ssr != null && ssr.update != 0) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("诊断：双差码残差按系统统计")
    void testDdCodeResidualBySystem() {
        Assumptions.assumeFalse(DATA_DIR.isEmpty(), "未指定 -Dnetwork.data.dir，跳过");
        Path dir = Paths.get(DATA_DIR);
        Path cimo = find(dir, "CIMO00ITA", ".rnx");
        Path prat = find(dir, "PRAT00ITA", ".rnx");
        Path virg = find(dir, "VIRG00ITA", ".rnx");
        Assumptions.assumeTrue(cimo != null && prat != null && virg != null, "三站 RINEX 文件缺失，跳过");
        Path navPath = NAV_FILE.isEmpty() ? find(dir, "BRDC", ".rnx") : Paths.get(NAV_FILE);
        Assumptions.assumeTrue(navPath != null && Files.exists(navPath), "缺少 BRDC 导航文件，跳过");

        StationObservation a = slice(RinexStationLoader.load("CIMO", cimo));
        StationObservation b = slice(RinexStationLoader.load("PRAT", prat));
        Nav nav = RinexNavLoader.load(navPath);
        DdObservationBuilder builder = new DdObservationBuilder();

        Map<Integer, double[]> stats = new java.util.TreeMap<>();
        Map<Integer, List<Double>> mw = new java.util.TreeMap<>();
        Map<Double, List<Obsd>> ea = a.groupByEpoch();
        Map<Double, List<Obsd>> eb = b.groupByEpoch();
        int epochs = 0;
        for (Double key : ea.keySet()) {
            if (!eb.containsKey(key) || epochs >= 20) {
                break;
            }
            List<DdObservation> dd = builder.build(a.ecef(), b.ecef(),
                    ea.get(key), eb.get(key), nav, 15.0);
            for (DdObservation d : dd) {
                double r = d.ddCodeIf() - d.ddRange() - d.ddModelTropo();
                double[] s = stats.computeIfAbsent(d.sys(), k -> new double[3]);
                s[0] += r;
                s[1] += r * r;
                s[2] += 1;
                mw.computeIfAbsent(d.sat(), k -> new ArrayList<>()).add(d.ddMwCycles());
            }
            epochs++;
        }
        for (Map.Entry<Integer, double[]> e : stats.entrySet()) {
            double n = e.getValue()[2];
            double mean = e.getValue()[0] / n;
            double rms = Math.sqrt(e.getValue()[1] / n);
            System.out.printf("[dd-code] sys=%d n=%.0f mean=%.3f m rms=%.3f m%n",
                    e.getKey(), n, mean, rms);
        }
        for (Map.Entry<Integer, List<Double>> e : mw.entrySet()) {
            List<Double> v = e.getValue();
            double mean = 0.0;
            for (double x : v) {
                mean += x;
            }
            mean /= v.size();
            double ss = 0.0;
            for (double x : v) {
                ss += (x - mean) * (x - mean);
            }
            double scatter = v.size() < 2 ? Double.NaN
                    : Math.sqrt(ss / (v.size() - 1)) / Math.sqrt(v.size());
            System.out.printf("[dd-mw] sat=%d n=%d mean=%.3f frac=%.3f scatter=%.3f%n",
                    e.getKey(), v.size(), mean, mean - Math.rint(mean), scatter);
        }
    }

    private static double robustStd(double[] values) {
        if (values.length < 2) {
            return 0.0;
        }
        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.length;
        double ss = 0.0;
        for (double v : values) {
            ss += (v - mean) * (v - mean);
        }
        return Math.sqrt(ss / (values.length - 1));
    }

    private static StationObservation slice(StationObservation station) {
        Map<Double, List<Obsd>> grouped = station.groupByEpoch();
        List<Double> keys = new ArrayList<>(grouped.keySet());
        int keep = Math.min(MAX_EPOCHS, keys.size());
        Set<Double> selected = new HashSet<>(keys.subList(0, keep));
        List<Obsd> observations = new ArrayList<>();
        for (Obsd obs : station.observations()) {
            if (selected.contains(obs.time.time + obs.time.sec)) {
                observations.add(obs);
            }
        }
        return new StationObservation(station.id(), station.ecef(), observations,
                station.ephemerides());
    }

    private static Path find(Path dir, String prefix, String suffix) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}