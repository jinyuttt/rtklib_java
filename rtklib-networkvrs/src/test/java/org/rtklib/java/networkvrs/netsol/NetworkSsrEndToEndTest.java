package org.rtklib.java.networkvrs.netsol;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.sync.RtcmStationLoader;
import org.rtklib.java.networkvrs.sync.SsrCorrectionProvider;
import org.rtklib.java.networkvrs.sync.StationObservation;
import org.rtklib.java.time.TimeSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多站 RTCM3 观测 + 同日录制 SSR 流的端到端验证。
 *
 * <p>数据不在仓库内（私有数据集），全部通过属性指定，缺任一项自动跳过：</p>
 * <ul>
 *   <li>{@code -Dnetwork.rtcm.dir}：观测根目录，结构 {@code <deviceId>/<yyyy-MM-dd>/<hour>.rtcm3}</li>
 *   <li>{@code -Dnetwork.station.ids}：参与网形的设备 ID（逗号分隔，至少 3 个）</li>
 *   <li>{@code -Dnetwork.master.id}：主站设备 ID，须在 {@code network.station.ids} 内</li>
 *   <li>{@code -Dnetwork.date}：观测日期目录（北京时间命名）</li>
 *   <li>{@code -Dnetwork.hours}：观测小时文件（北京时间，逗号分隔）</li>
 *   <li>{@code -Dnetwork.ssr.dir}：录制 SSR 流目录（UTC 日期命名，按小时分文件）</li>
 *   <li>{@code -Dnetwork.max.epochs}：参与网解的最大历元数（默认 240）</li>
 *   <li>{@code -Dnetwork.window.epochs}：批处理窗口历元数（默认 20）</li>
 * </ul>
 *
 * <p>观测流内 TOW 为 UTC、目录/文件名是北京时间（UTC+8），SSR 录制目录按 UTC 命名，
 * 因此 SSR 小时文件由观测首历元换算出的 UTC 小时自动定位，不依赖手工指定。</p>
 */
class NetworkSsrEndToEndTest {

    private static final String RTCM_DIR = System.getProperty("network.rtcm.dir", "");
    private static final String STATION_IDS = System.getProperty("network.station.ids", "");
    private static final String DATE = System.getProperty("network.date", "");
    private static final String HOURS = System.getProperty("network.hours", "");
    private static final String SSR_DIR = System.getProperty("network.ssr.dir", "");
    private static final String MASTER = System.getProperty("network.master.id", "");
    private static final int MAX_EPOCHS = Integer.getInteger("network.max.epochs", 240);
    private static final int WINDOW_EPOCHS = Integer.getInteger("network.window.epochs", 20);
    private static final boolean VERBOSE = Boolean.getBoolean("network.verbose");

    private static Fixture fixture;

    @Test
    @DisplayName("四站 + 录制 SSR：窄巷固定率、ratio 与基线重复性")
    void testRecordedSsrEndToEnd() {
        Fixture f = fixture();
        SsrCorrectionProvider ssr = f.ssr();
        Assumptions.assumeTrue(ssr != null, "缺少录制 SSR 流，跳过");

        Nav nav = f.freshNav();
        NetworkConfig config = f.config(Constants.EPHOPT_SSRAPC);
        GTime start = f.stations().get(0).observations().get(0).time;
        ssr.applyAt(nav, start, satellitesOf(f.stations()));
        int applied = ssrUpdateCount(nav);
        System.out.printf("[e2e] SSR 首历元写入改正的卫星数=%d（观测内 BDS 卫星数=%d）%n",
                applied, bdsSatelliteCount(f.stations()));
        if (VERBOSE) {
            System.out.printf("[e2e] SSR 时间线 [%s, %s]；观测起点 %s%n",
                    utcText(ssr.earliestTime()), utcText(ssr.latestTime()), utcText(start));
            printSsrApplied(nav, "[e2e-deph]");
        }
        assertTrue(applied >= 4, "SSR 改正星数不足，链路未贯通: " + applied);

        NetworkSolutionEngine.Result result = new NetworkSolutionEngine()
                .solve(f.stations(), nav, MASTER, config, ssr);
        Stats stats = Stats.of(result, f.aPrioriVectors(), f.stationEcef());
        stats.print("[e2e-ssr]");

        assertFalse(result.windows().isEmpty(), "应有基线窗口解");
        assertEquals(f.stations().size() * (f.stations().size() - 1) / 2,
                result.windows().stream()
                        .map(w -> w.stationA() + "-" + w.stationB()).distinct().count(),
                "N 站应形成 N(N-1)/2 条基线");
        assertTrue(stats.fixed() > 0, "SSR 改正下应有窄巷固定解");
        assertTrue(stats.meanPhaseRms() < 0.05,
                "相位残差 RMS 应在 5 cm 以内，实际 " + stats.meanPhaseRms());
    }

    @Test
    @DisplayName("录制 SSR vs 广播星历：固定率与相位残差对比")
    void testSsrVersusBroadcast() {
        Fixture f = fixture();
        SsrCorrectionProvider ssr = f.ssr();
        Assumptions.assumeTrue(ssr != null, "缺少录制 SSR 流，跳过");

        NetworkSolutionEngine.Result brdc = new NetworkSolutionEngine()
                .solve(f.stations(), f.freshNav(), MASTER, f.config(Constants.EPHOPT_BRDC));
        Stats brdcStats = Stats.of(brdc, f.aPrioriVectors(), f.stationEcef());
        brdcStats.print("[e2e-brdc]");

        NetworkSolutionEngine.Result ssrResult = new NetworkSolutionEngine()
                .solve(f.stations(), f.freshNav(), MASTER,
                        f.config(Constants.EPHOPT_SSRAPC), ssr);
        Stats ssrStats = Stats.of(ssrResult, f.aPrioriVectors(), f.stationEcef());
        ssrStats.print("[e2e-ssr]");

        System.out.printf("[e2e] 固定率 %.1f%% -> %.1f%%；meanRatio %.2f -> %.2f；"
                        + "相位RMS %.4f -> %.4f m%n",
                100.0 * brdcStats.fixedRate(), 100.0 * ssrStats.fixedRate(),
                brdcStats.meanRatio(), ssrStats.meanRatio(),
                brdcStats.meanPhaseRms(), ssrStats.meanPhaseRms());

        assertFalse(ssrResult.windows().isEmpty(), "SSR 模式应有窗口解");
        assertTrue(ssrStats.fixed() >= brdcStats.fixed(),
                "SSR 改正不应降低固定窗口数: brdc=" + brdcStats.fixed()
                        + " ssr=" + ssrStats.fixed());
        // 本网形基线仅 147~589 m，SSR 轨道改正的米级量在双差中几乎完全抵消
        // （残余约「轨道误差 × 基线长 / 星地距」≈ 0.1 mm），因此两者结果理应
        // 高度一致；这里只要求 SSR 不出现超过 2% 的劣化，用作链路一致性检查。
        double tolerance = 0.02 * Math.abs(brdcStats.meanRatio());
        assertTrue(ssrStats.meanRatio() >= brdcStats.meanRatio() - tolerance,
                String.format("SSR 改正的 ratio 劣化超过 2%%: brdc=%.3f ssr=%.3f",
                        brdcStats.meanRatio(), ssrStats.meanRatio()));
    }

    private static Fixture fixture() {
        if (fixture == null) {
            fixture = load();
        }
        return fixture;
    }

    private static Fixture load() {
        Assumptions.assumeFalse(RTCM_DIR.isEmpty(),
                "未指定 -Dnetwork.rtcm.dir，跳过");
        Assumptions.assumeFalse(SSR_DIR.isEmpty(),
                "未指定 -Dnetwork.ssr.dir，跳过");
        Assumptions.assumeFalse(STATION_IDS.isEmpty(),
                "未指定 -Dnetwork.station.ids，跳过");
        Assumptions.assumeFalse(DATE.isEmpty(),
                "未指定 -Dnetwork.date，跳过");
        Assumptions.assumeFalse(HOURS.isEmpty(),
                "未指定 -Dnetwork.hours，跳过");
        Assumptions.assumeTrue(Files.isDirectory(Paths.get(RTCM_DIR)), "观测根目录不存在，跳过");
        Assumptions.assumeTrue(Files.isDirectory(Paths.get(SSR_DIR)), "SSR 录制目录不存在，跳过");

        List<String> ids = new ArrayList<>();
        for (String part : STATION_IDS.split(",")) {
            if (!part.trim().isEmpty()) {
                ids.add(part.trim());
            }
        }
        Assumptions.assumeTrue(ids.size() >= 3, "至少需要三个站 ID");
        Assumptions.assumeTrue(!MASTER.isEmpty() && ids.contains(MASTER),
                "主站 ID 未指定或不在站列表中，跳过");

        List<Integer> hours = new ArrayList<>();
        for (String part : HOURS.split(",")) {
            if (!part.trim().isEmpty()) {
                hours.add(Integer.parseInt(part.trim()));
            }
        }

        List<StationObservation> stations = new ArrayList<>();
        for (String id : ids) {
            List<Path> files = new ArrayList<>();
            for (int hour : hours) {
                Path file = Paths.get(RTCM_DIR, id, DATE, hour + ".rtcm3");
                Assumptions.assumeTrue(Files.exists(file), "缺少观测文件，跳过: " + file);
                files.add(file);
            }
            stations.add(RtcmStationLoader.load(id, files));
        }

        int ssrHour = utcHourOf(stations.get(0).observations().get(0).time);
        SsrCorrectionProvider ssr = loadSsr(Paths.get(SSR_DIR), ssrHour,
                stations.get(0).observations().get(0).time);

        // 录制流的首个改正时刻可能晚于观测起点，解算窗口从这里对齐，
        // 否则早期历元没有改正可用（applyAt 按 maxAge 拒绝写入）
        GTime aligned = alignedStart(stations, ssr);
        List<StationObservation> sliced = new ArrayList<>();
        for (StationObservation station : stations) {
            sliced.add(slice(station, aligned, MAX_EPOCHS));
        }
        for (StationObservation station : sliced) {
            System.out.printf("[e2e] %s epochs=%d eph=%d ecef=(%.3f,%.3f,%.3f)%n",
                    station.id(), station.groupByEpoch().size(), station.ephemerides().size(),
                    station.ecef()[0], station.ecef()[1], station.ecef()[2]);
        }
        return new Fixture(sliced, mergeNav(sliced), ssr);
    }

    private static GTime alignedStart(List<StationObservation> stations, SsrCorrectionProvider ssr) {
        GTime first = stations.get(0).observations().get(0).time;
        if (ssr == null) {
            return first;
        }
        GTime ssrFirst = ssr.earliestTime();
        if (ssrFirst == null) {
            return first;
        }
        return TimeSystem.timediff(ssrFirst, first) > 0.0 ? ssrFirst : first;
    }

    /**
     * 按观测首历元所在 UTC 小时装载录制 SSR 流，并顺延覆盖后续小时文件。
     */
    private static SsrCorrectionProvider loadSsr(Path dir, int firstUtcHour, GTime start) {
        SsrCorrectionProvider provider = SsrCorrectionProvider.live(start,
                SsrCorrectionProvider.DEFAULT_MAX_AGE_SEC);
        int loaded = 0;
        for (int offset = 0; offset < 8; offset++) {
            int hour = (firstUtcHour + offset) % 24;
            Path file = findHourFile(dir, hour);
            if (file == null) {
                continue;
            }
            try {
                provider.ingest(Files.readAllBytes(file));
                loaded++;
                System.out.printf("[e2e] SSR 摄入 %s（%d 字节）%n", file.getFileName(), Files.size(file));
            } catch (IOException e) {
                throw new IllegalStateException("读取 SSR 流失败: " + file, e);
            }
        }
        if (loaded == 0) {
            return null;
        }
        System.out.printf("[e2e] SSR 时间线卫星数=%d%n", provider.satelliteCount());
        return provider;
    }

    private static Path findHourFile(Path dir, int hour) {
        for (String name : List.of(String.format("%02d.rtcm3", hour), hour + ".rtcm3")) {
            Path file = dir.resolve(name);
            if (Files.exists(file)) {
                return file;
            }
        }
        return null;
    }

    private static int utcHourOf(GTime time) {
        GTime utc = TimeSystem.gpst2utc(time);
        double[] ep = TimeSystem.time2ymdhms(utc);
        return (int) ep[3];
    }

    private static Nav mergeNav(List<StationObservation> stations) {
        Nav nav = new Nav();
        for (StationObservation station : stations) {
            for (Eph eph : station.ephemerides()) {
                if (eph.sat <= 0 || eph.sat > Constants.MAXSAT) {
                    continue;
                }
                Eph current = nav.eph[eph.sat - 1];
                if (current.sat == 0 || TimeSystem.timediff(eph.toe, current.toe) > 0.0) {
                    nav.eph[eph.sat - 1] = new Eph(eph);
                }
            }
        }
        return nav;
    }

    private static Set<Integer> satellitesOf(List<StationObservation> stations) {
        Set<Integer> sats = new LinkedHashSet<>();
        for (StationObservation station : stations) {
            for (Obsd obs : station.observations()) {
                if (obs.sat > 0) {
                    sats.add(obs.sat);
                }
            }
        }
        return sats;
    }

    private static int bdsSatelliteCount(List<StationObservation> stations) {
        Set<Integer> sats = new LinkedHashSet<>();
        for (Obsd obs : stations.get(0).observations()) {
            if (obs.sat > 0 && org.rtklib.java.common.SatUtils.satsys(obs.sat, new int[1])
                    == Constants.SYS_CMP) {
                sats.add(obs.sat);
            }
        }
        return sats.size();
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

    private static String utcText(GTime time) {
        if (time == null) {
            return "none";
        }
        GTime utc = TimeSystem.gpst2utc(time);
        double[] ep = TimeSystem.time2ymdhms(utc);
        return String.format("%04d-%02d-%02d %02d:%02d:%05.2f",
                (int) ep[0], (int) ep[1], (int) ep[2], (int) ep[3], (int) ep[4], ep[5]);
    }

    private static void printSsrApplied(Nav nav, String tag) {
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            Ssr s = nav.ssr[sat - 1];
            if (s == null || s.update == 0) {
                continue;
            }
            double deph = Math.sqrt(s.deph[0] * s.deph[0] + s.deph[1] * s.deph[1]
                    + s.deph[2] * s.deph[2]);
            System.out.printf("%s sat=%3d deph=(%.3f,%.3f,%.3f) |deph|=%.3f m dclk=%.3f m%n",
                    tag, sat, s.deph[0], s.deph[1], s.deph[2], deph,
                    s.dclk[0] / Constants.CLIGHT);
        }
    }

    private static StationObservation slice(StationObservation station, GTime start, int maxEpochs) {
        Map<Double, List<Obsd>> grouped = station.groupByEpoch();
        double from = start.time + start.sec;
        List<Double> keys = new ArrayList<>();
        for (double key : grouped.keySet()) {
            if (key >= from) {
                keys.add(key);
            }
        }
        int keep = Math.min(maxEpochs, keys.size());
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

    private static final class Fixture {
        private final List<StationObservation> stations;
        private final Nav nav;
        private final SsrCorrectionProvider ssr;

        Fixture(List<StationObservation> stations, Nav nav, SsrCorrectionProvider ssr) {
            this.stations = stations;
            this.nav = nav;
            this.ssr = ssr;
        }

        List<StationObservation> stations() {
            return stations;
        }

        SsrCorrectionProvider ssr() {
            return ssr;
        }

        Nav freshNav() {
            return mergeNav(stations);
        }

        /**
         * @return 各站先验 ECEF 坐标，键为站 ID
         */
        Map<String, double[]> stationEcef() {
            Map<String, double[]> map = new LinkedHashMap<>();
            for (StationObservation station : stations) {
                map.put(station.id(), station.ecef().clone());
            }
            return map;
        }

        /**
         * @return 各有序站对的先验基线向量（B − A），键与窗口解的 {@code stationA-stationB} 一致
         */
        Map<String, double[]> aPrioriVectors() {
            Map<String, double[]> map = new LinkedHashMap<>();
            for (StationObservation a : stations) {
                for (StationObservation b : stations) {
                    if (a.id().equals(b.id())) {
                        continue;
                    }
                    map.put(a.id() + "-" + b.id(), new double[]{
                            b.ecef()[0] - a.ecef()[0],
                            b.ecef()[1] - a.ecef()[1],
                            b.ecef()[2] - a.ecef()[2]});
                }
            }
            return map;
        }

        NetworkConfig config(int ephemerisOption) {
            NetworkConfig config = new NetworkConfig();
            config.interpolationModel = NetworkConfig.InterpolationModel.FKP;
            config.solutionWindowEpochs = WINDOW_EPOCHS;
            config.ephemerisOption = ephemerisOption;
            return config;
        }
    }

    /**
     * 三角形闭合差：把每条基线的固定窗口矢量取均值后，检查
     * {@code V(i→j) + V(j→k) - V(i→k)} 是否闭合。闭合差小说明各基线独立解算结果
     * 互相一致（先验坐标的系统偏差已被 {@code estimatedDelta} 吸收），闭合差大则
     * 说明存在按基线变化的建模误差。
     */
    private static void printTriangleClosure(Map<String, List<BaselineWindowSolution>> grouped,
                                             Map<String, double[]> aPriori) {
        Map<String, double[]> mean = new LinkedHashMap<>();
        Set<String> stations = new LinkedHashSet<>();
        for (Map.Entry<String, List<BaselineWindowSolution>> entry : grouped.entrySet()) {
            String[] ids = entry.getKey().split("-");
            stations.add(ids[0]);
            stations.add(ids[1]);
            double[] apri = aPriori.get(entry.getKey());
            if (apri == null) {
                continue;
            }
            double[] sum = new double[3];
            int count = 0;
            for (BaselineWindowSolution window : entry.getValue()) {
                if (!window.fixed()) {
                    continue;
                }
                sum[0] += apri[0] + window.estimatedDelta()[0];
                sum[1] += apri[1] + window.estimatedDelta()[1];
                sum[2] += apri[2] + window.estimatedDelta()[2];
                count++;
            }
            if (count > 0) {
                mean.put(entry.getKey(),
                        new double[]{sum[0] / count, sum[1] / count, sum[2] / count});
            }
        }
        List<String> ids = new ArrayList<>(stations);
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                for (int k = j + 1; k < ids.size(); k++) {
                    double[] vij = mean.get(ids.get(i) + "-" + ids.get(j));
                    double[] vjk = mean.get(ids.get(j) + "-" + ids.get(k));
                    double[] vik = mean.get(ids.get(i) + "-" + ids.get(k));
                    if (vij == null || vjk == null || vik == null) {
                        continue;
                    }
                    double[] closure = {
                            vij[0] + vjk[0] - vik[0],
                            vij[1] + vjk[1] - vik[1],
                            vij[2] + vjk[2] - vik[2]};
                    System.out.printf("[e2e-tri] %s-%s-%s closure=%.4f m%n",
                            ids.get(i), ids.get(j), ids.get(k),
                            BaselineStats.norm(closure));
                }
            }
        }
    }

    /**
     * 逐站估计坐标：窗口解的 {@code estimatedDelta} 是站 B 相对其先验 ECEF 的改正量，
     * 因此 {@code B_est = B_apri + delta}。同一站在不同基线里的估计值应一致；与先验的
     * 偏差即该站先验坐标（1005 / FIX 中位数）的误差，估计值之间的离散度反映解算精度。
     */
    private static void printStationPositions(Map<String, List<BaselineWindowSolution>> grouped,
                                              Map<String, double[]> aPrioriStation) {
        Map<String, List<double[]>> byStation = new LinkedHashMap<>();
        for (Map.Entry<String, List<BaselineWindowSolution>> entry : grouped.entrySet()) {
            String[] ids = entry.getKey().split("-");
            double[] apri = aPrioriStation.get(ids[1]);
            if (apri == null) {
                continue;
            }
            double[] sum = new double[3];
            int count = 0;
            for (BaselineWindowSolution window : entry.getValue()) {
                if (!window.fixed()) {
                    continue;
                }
                sum[0] += apri[0] + window.estimatedDelta()[0];
                sum[1] += apri[1] + window.estimatedDelta()[1];
                sum[2] += apri[2] + window.estimatedDelta()[2];
                count++;
            }
            if (count > 0) {
                byStation.computeIfAbsent(ids[1], k -> new ArrayList<>())
                        .add(new double[]{sum[0] / count, sum[1] / count, sum[2] / count});
            }
        }
        for (Map.Entry<String, List<double[]>> entry : byStation.entrySet()) {
            String id = entry.getKey();
            double[] apri = aPrioriStation.get(id);
            if (apri == null) {
                continue;
            }
            double[] mean = new double[3];
            for (double[] p : entry.getValue()) {
                mean[0] += p[0];
                mean[1] += p[1];
                mean[2] += p[2];
            }
            int n = entry.getValue().size();
            mean[0] /= n;
            mean[1] /= n;
            mean[2] /= n;
            double offset = BaselineStats.norm(new double[]{
                    mean[0] - apri[0], mean[1] - apri[1], mean[2] - apri[2]});
            double spread = 0.0;
            if (n > 1) {
                for (double[] p : entry.getValue()) {
                    spread += BaselineStats.norm(new double[]{
                            p[0] - mean[0], p[1] - mean[1], p[2] - mean[2]});
                }
                spread /= n;
            }
            System.out.printf("[e2e-sta] %s baselines=%d |est-apri|=%.4f m "
                            + "spread=%.4f m est=(%.3f,%.3f,%.3f)%n",
                    id, n, offset, spread, mean[0], mean[1], mean[2]);
        }
    }

    private record Stats(int windows, int fixed, double meanRatio, double minRatio,
                         double meanPhaseRms, double meanRepeatability, double meanAbsFracN1,
                         Map<String, BaselineStats> baselines) {

        static Stats of(NetworkSolutionEngine.Result result, Map<String, double[]> aPriori,
                        Map<String, double[]> aPrioriStation) {
            List<BaselineWindowSolution> list = result.windows();
            int fixed = (int) list.stream().filter(BaselineWindowSolution::fixed).count();
            double ratioSum = 0.0;
            double minRatio = Double.POSITIVE_INFINITY;
            double rmsSum = 0.0;
            int rmsCount = 0;
            double fracSum = 0.0;
            int fracCount = 0;
            Map<String, List<BaselineWindowSolution>> grouped = new LinkedHashMap<>();
            for (BaselineWindowSolution window : list) {
                ratioSum += window.ratio();
                minRatio = Math.min(minRatio, window.ratio());
                if (Double.isFinite(window.phaseRms())) {
                    rmsSum += window.phaseRms();
                    rmsCount++;
                }
                for (double n1 : window.floatN1Cycles().values()) {
                    fracSum += Math.abs(n1 - Math.rint(n1));
                    fracCount++;
                }
                grouped.computeIfAbsent(window.stationA() + "-" + window.stationB(),
                        k -> new ArrayList<>()).add(window);
            }
            if (VERBOSE) {
                for (BaselineWindowSolution window : list) {
                    double[] apri = aPriori.get(window.stationA() + "-" + window.stationB());
                    double length = Double.NaN;
                    if (apri != null) {
                        length = BaselineStats.norm(new double[]{
                                apri[0] + window.estimatedDelta()[0],
                                apri[1] + window.estimatedDelta()[1],
                                apri[2] + window.estimatedDelta()[2]});
                    }
                    double frac = Double.NaN;
                    if (!window.floatN1Cycles().isEmpty()) {
                        double sum = 0.0;
                        for (double n1 : window.floatN1Cycles().values()) {
                            sum += Math.abs(n1 - Math.rint(n1));
                        }
                        frac = sum / window.floatN1Cycles().size();
                    }
                    System.out.printf("[e2e-win] %-24s tow=%9.0f fix=%d amb=%d dd=%d ratio=%5.2f "
                                    + "rms=%.4f len=%.3f frac=%.3f%n",
                            window.stationA() + "-" + window.stationB(),
                            window.start().time + window.start().sec, window.fixed() ? 1 : 0,
                            window.ambiguityCount(), window.floatN1Cycles().size(),
                            window.ratio(), window.phaseRms(),
                            length, frac);
                }
                printTriangleClosure(grouped, aPriori);
                printStationPositions(grouped, aPrioriStation);
            }

            Map<String, BaselineStats> perBaseline = new LinkedHashMap<>();
            double repeatSum = 0.0;
            int repeatCount = 0;
            for (Map.Entry<String, List<BaselineWindowSolution>> entry : grouped.entrySet()) {
                BaselineStats stats = BaselineStats.of(entry.getKey(), entry.getValue(),
                        aPriori.get(entry.getKey()));
                perBaseline.put(entry.getKey(), stats);
                if (Double.isFinite(stats.repeatability())) {
                    repeatSum += stats.repeatability();
                    repeatCount++;
                }
            }
            return new Stats(list.size(), fixed,
                    list.isEmpty() ? Double.NaN : ratioSum / list.size(),
                    minRatio,
                    rmsCount == 0 ? Double.NaN : rmsSum / rmsCount,
                    repeatCount == 0 ? Double.NaN : repeatSum / repeatCount,
                    fracCount == 0 ? Double.NaN : fracSum / fracCount,
                    perBaseline);
        }

        double fixedRate() {
            return windows == 0 ? 0.0 : 1.0 * fixed / windows;
        }

        void print(String tag) {
            System.out.printf("%s windows=%d nlFixed=%d (%.1f%%) meanRatio=%.2f minRatio=%.2f "
                            + "meanPhaseRms=%.4f m meanRepeat=%.3f m mean|fracN1|=%.3f cyc%n",
                    tag, windows, fixed, 100.0 * fixedRate(), meanRatio, minRatio,
                    meanPhaseRms, meanRepeatability, meanAbsFracN1);
            for (BaselineStats stats : baselines.values()) {
                stats.print(tag);
            }
        }
    }

    /**
     * 单条基线的窗口统计：固定率、ratio、估计基线长度与固定窗口下的重复性。
     */
    private record BaselineStats(String key, int windows, int fixed, double meanRatio,
                                 double aPrioriLength, double estimatedLength,
                                 double repeatability) {

        static BaselineStats of(String key, List<BaselineWindowSolution> list,
                                double[] aPrioriVector) {
            int fixed = (int) list.stream().filter(BaselineWindowSolution::fixed).count();
            double ratioSum = 0.0;
            for (BaselineWindowSolution window : list) {
                ratioSum += window.ratio();
            }
            double aPrioriLength = aPrioriVector == null ? Double.NaN : norm(aPrioriVector);
            double estimatedSum = 0.0;
            int estimatedCount = 0;
            List<double[]> fixedVectors = new ArrayList<>();
            for (BaselineWindowSolution window : list) {
                double[] vector = window.estimatedDelta();
                double[] absolute = vector;
                if (aPrioriVector != null) {
                    absolute = new double[]{
                            aPrioriVector[0] + vector[0],
                            aPrioriVector[1] + vector[1],
                            aPrioriVector[2] + vector[2]};
                }
                if (window.fixed()) {
                    estimatedSum += norm(absolute);
                    estimatedCount++;
                    fixedVectors.add(absolute);
                }
            }
            double repeatability = Double.NaN;
            if (fixedVectors.size() > 1) {
                double[] axisStd = new double[3];
                for (int axis = 0; axis < 3; axis++) {
                    double[] values = new double[fixedVectors.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = fixedVectors.get(i)[axis];
                    }
                    axisStd[axis] = robustStd(values);
                }
                repeatability = Math.sqrt(axisStd[0] * axisStd[0] + axisStd[1] * axisStd[1]
                        + axisStd[2] * axisStd[2]);
            }
            return new BaselineStats(key, list.size(), fixed,
                    list.isEmpty() ? Double.NaN : ratioSum / list.size(),
                    aPrioriLength,
                    estimatedCount == 0 ? Double.NaN : estimatedSum / estimatedCount,
                    repeatability);
        }

        void print(String tag) {
            System.out.printf("%s   %-24s win=%3d fix=%3d meanRatio=%5.2f "
                            + "lenApri=%.3f m lenEst=%.3f m repeat=%.4f m%n",
                    tag, key, windows, fixed, meanRatio, aPrioriLength, estimatedLength,
                    repeatability);
        }

        private static double norm(double[] v) {
            return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        }
    }
}