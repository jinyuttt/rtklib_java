package org.rtklib.java.networkvrs.sync;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.correction.ModelCorrectionProvider;
import org.rtklib.java.networkvrs.output.VrsOutputBridge;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.vrs.config.VrsOptionalConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 RTCM3 三站集成测试。
 *
 * <p>数据不在仓库内，通过属性指定并自动跳过：</p>
 * <ul>
 *   <li>{@code -Dnetwork.rtcm.dir=<根目录>}：目录结构为 {@code <deviceId>/<yyyy-MM-dd>/<hour>.rtcm3}</li>
 *   <li>{@code -Dnetwork.station.ids=<id1,id2,id3>}：参与网形的设备 ID</li>
 * </ul>
 *
 * <p>测试取三站共有日期里最早的 2 个小时文件，验证 RTCM 解码、历元对齐与流程贯通。</p>
 */
class RtcmStationLoaderTest {

    private static final String RTCM_DIR = System.getProperty("network.rtcm.dir", "");
    private static final String STATION_IDS = System.getProperty("network.station.ids", "");

    @Test
    @DisplayName("真实 RTCM 三站：坐标、历元对齐与共视卫星")
    void testLoadAndSynchronize() {
        List<StationObservation> stations = loadStations();

        for (StationObservation station : stations) {
            double[] ecef = station.ecef();
            assertTrue(Math.hypot(ecef[0], ecef[1]) > 1e6, station.id() + " 坐标应有效");
            assertFalse(station.observations().isEmpty(), station.id() + " 应有观测");
            List<Double> keys = new ArrayList<>(station.groupByEpoch().keySet());
            System.out.printf("%s obs=%d epochs=%d first=%.3f last=%.3f phase15=%.3f%n",
                    station.id(), station.observations().size(), keys.size(),
                    keys.get(0), keys.get(keys.size() - 1), keys.get(0) % 15.0);
        }

        List<SynchronizedEpoch> epochs = new MultiStationSynchronizer(0.5, 3)
                .synchronize(stations);

        assertFalse(epochs.isEmpty(), "应存在三站同步历元");
        int bdsTotal = 0;
        for (SynchronizedEpoch epoch : epochs) {
            int[] prn = new int[1];
            for (int sat : epoch.commonSatellites()) {
                if (SatUtils.satsys(sat, prn) == Constants.SYS_CMP) {
                    bdsTotal++;
                }
            }
        }
        assertTrue(bdsTotal > 0, "同步历元应有共视 BDS 卫星");
        System.out.printf("stations=%d epochs=%d commonBds=%d%n",
                stations.size(), epochs.size(), bdsTotal);
    }

    @Test
    @DisplayName("真实 RTCM 三站：网络 VRS 流程贯通（零改正占位）")
    void testProcessWithPlaceholderProvider() {
        List<StationObservation> stations = loadStations();
        NetworkVrsProcessor processor = new NetworkVrsProcessor(new NetworkConfig(),
                (station, obs) -> new NetworkVrsProcessor.CorrectionValue(0.0, 0.0, 0.0));

        List<NetworkVrsProcessor.EpochCorrection> epochs = processor.process(stations,
                stations.get(0).pos());

        assertFalse(epochs.isEmpty(), "应产出插值历元");
        for (NetworkVrsProcessor.EpochCorrection epoch : epochs) {
            assertFalse(epoch.corrections().isEmpty(), "历元应有卫星改正");
        }
        NetworkVrsProcessor.CorrectionValue sample = epochs.get(0).corrections().values().iterator().next();
        assertEquals(0.0, sample.iono(), 1e-12);
        assertEquals(0.0, sample.tropo(), 1e-12);
        System.out.printf("vrs epochs=%d sats(first)=%d%n",
                epochs.size(), epochs.get(0).corrections().size());
    }

    @Test
    @DisplayName("真实 RTCM 三站：留一法验证电离层插值")
    void testLeaveOneOutIono() {
        List<StationObservation> stations = loadStations();
        StationObservation target = stations.get(2);
        List<StationObservation> network = List.of(stations.get(0), stations.get(1));

        NetworkConfig config = new NetworkConfig();
        config.minStations = 2;
        ModelCorrectionProvider provider = new ModelCorrectionProvider();
        NetworkVrsProcessor processor = new NetworkVrsProcessor(config, provider);
        List<NetworkVrsProcessor.EpochCorrection> epochs = processor.process(network, target.pos());
        Assumptions.assumeFalse(epochs.isEmpty(), "两站插值无输出，跳过");

        // 目标站自身改正量（独立实例，保证平滑状态互不影响）
        ModelCorrectionProvider truthProvider = new ModelCorrectionProvider();
        Map<Long, Map<Integer, Double>> truth = new HashMap<>();
        for (Map.Entry<Double, List<Obsd>> entry : target.groupByEpoch().entrySet()) {
            long key = Math.round(entry.getKey() * 1000.0);
            Map<Integer, Double> perSat = new HashMap<>();
            for (Obsd obs : entry.getValue()) {
                NetworkVrsProcessor.CorrectionValue value = truthProvider.compute(target, obs);
                if (value != null) {
                    perSat.put(obs.sat, value.iono());
                }
            }
            truth.put(key, perSat);
        }

        // 逐历元去掉均值（消除接收机频间偏差），比较插值场与目标站实测场
        List<Double> residuals = new ArrayList<>();
        Map<Integer, List<Double>> bySat = new TreeMap<>();
        for (NetworkVrsProcessor.EpochCorrection epoch : epochs) {
            long key = Math.round((epoch.time().time + epoch.time().sec) * 1000.0);
            Map<Integer, Double> targetValues = truth.get(key);
            if (targetValues == null) {
                continue;
            }
            List<Integer> common = new ArrayList<>();
            for (Integer sat : epoch.corrections().keySet()) {
                if (targetValues.containsKey(sat)) {
                    common.add(sat);
                }
            }
            if (common.size() < 4) {
                continue;
            }
            double interpMean = 0.0;
            double truthMean = 0.0;
            for (int sat : common) {
                interpMean += epoch.corrections().get(sat).iono();
                truthMean += targetValues.get(sat);
            }
            interpMean /= common.size();
            truthMean /= common.size();
            for (int sat : common) {
                double r = (epoch.corrections().get(sat).iono() - interpMean)
                        - (targetValues.get(sat) - truthMean);
                residuals.add(r);
                bySat.computeIfAbsent(sat, k -> new ArrayList<>()).add(r);
            }
        }

        assertFalse(residuals.isEmpty(), "应有可比较的电离层残差");
        double sum = 0.0;
        for (double r : residuals) {
            sum += r * r;
        }
        double rms = Math.sqrt(sum / residuals.size());
        System.out.printf("leave-one-out iono samples=%d rms=%.3f m%n",
                residuals.size(), rms);
        double maxSatBias = 0.0;
        for (Map.Entry<Integer, List<Double>> e : bySat.entrySet()) {
            double s = 0.0;
            double mean = 0.0;
            for (double r : e.getValue()) {
                s += r * r;
                mean += r;
            }
            mean /= e.getValue().size();
            maxSatBias = Math.max(maxSatBias, Math.abs(mean));
            double var = s / e.getValue().size() - mean * mean;
            System.out.printf("  sat=%d n=%d rms=%.3f mean=%.3f std=%.3f%n",
                    e.getKey(), e.getValue().size(), Math.sqrt(s / e.getValue().size()),
                    mean, Math.sqrt(Math.max(var, 0.0)));
        }
        System.out.printf("maxSatBias=%.3f m%n", maxSatBias);
        // 档 1 用码组合估计电离层，精度在分米~米级；档 2 相位网解后再收紧
        assertTrue(rms < 2.0, "电离层插值残差 RMS 应在 2 m 以内");
    }

    @Test
    @DisplayName("真实 RTCM 三站：相对改正 → VRS RTCM 输出并回读校验")
    void testNetworkCorrectionsToRtcmOutput() throws IOException {
        List<StationObservation> stations = loadStations();
        String masterId = System.getProperty("network.master.id", "");
        StationObservation master = masterId.isEmpty() ? stations.get(0)
                : stations.stream().filter(s -> s.id().equals(masterId)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("主站不在参与站列表中: " + masterId));

        NetworkVrsProcessor processor = new NetworkVrsProcessor(new NetworkConfig(),
                new ModelCorrectionProvider());
        double[] target = master.pos();
        target[0] += 0.01 * Math.PI / 180.0; // 主站以北约 1.1 km 处的虚拟站
        List<NetworkVrsProcessor.EpochCorrection> corrections =
                processor.processRelative(stations, target, master.id());
        Assumptions.assumeFalse(corrections.isEmpty(), "无相对改正输出，跳过");

        VrsOptionalConfig opt = new VrsOptionalConfig();
        opt.navsys = Constants.SYS_CMP | Constants.SYS_GPS | Constants.SYS_GAL;
        opt.msmType = 4;
        opt.stationMsgType = 1005;

        Path output = Paths.get("target", "network-vrs-bridge.rtcm3");
        Files.createDirectories(output.getParent());
        Files.deleteIfExists(output);

        int epochs = VrsOutputBridge.generate(master, target, corrections,
                output.toString(), opt, "NETVRS");
        assertTrue(epochs > 0, "应处理历元");
        assertTrue(Files.size(output) > 0, "输出文件不应为空");

        BridgeInspector inspector = new BridgeInspector();
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(inspector);
        byte[] data = Files.readAllBytes(output);
        decoder.feed(data, 0, data.length);
        decoder.finish();

        assertTrue(inspector.stationCount > 0, "输出应含 1005 站坐标");
        assertTrue(inspector.epochCount > 0, "输出应含观测历元");
        assertTrue(inspector.msmCount > 0, "输出应含 MSM 消息");

        double[] expected = new double[3];
        CoordTransform.pos2ecef(target, expected);
        double diff = Math.sqrt(Math.pow(inspector.sta.pos[0] - expected[0], 2)
                + Math.pow(inspector.sta.pos[1] - expected[1], 2)
                + Math.pow(inspector.sta.pos[2] - expected[2], 2));
        System.out.printf("network→vrs: master=%s inputEpochs=%d outEpochs=%d msm=%d stationDiff=%.4f m%n",
                master.id(), epochs, inspector.epochCount, inspector.msmCount, diff);
        assertTrue(diff < 0.05, "1005 坐标应等于目标位置");
    }

    private static final class BridgeInspector implements RtcmDataHandler {
        private Sta sta;
        private int stationCount = 0;
        private int epochCount = 0;
        private int msmCount = 0;

        @Override
        public void onRtcmMessageType(int type, int length) {
            if (type >= 1071 && type <= 1137) {
                msmCount++;
            }
        }

        @Override
        public void onStation(Sta sta) {
            this.sta = sta;
            stationCount++;
        }

        @Override
        public void onEph(Eph eph) {
        }

        @Override
        public void onGeph(Geph geph) {
        }

        @Override
        public void onObservationEpoch(ObservationEpoch epoch) {
            epochCount++;
        }

        @Override
        public void onSsr(Ssr ssr) {
        }

        @Override
        public void onAuxData(AuxData aux) {
        }

        @Override
        public void onFinish() {
        }
    }

    private static List<StationObservation> loadStations() {
        Assumptions.assumeFalse(RTCM_DIR.isEmpty(), "未指定 -Dnetwork.rtcm.dir，跳过");
        Assumptions.assumeFalse(STATION_IDS.isEmpty(), "未指定 -Dnetwork.station.ids，跳过");

        Path root = Paths.get(RTCM_DIR);
        Assumptions.assumeTrue(Files.isDirectory(root), "RTCM 根目录不存在，跳过");

        List<String> ids = new ArrayList<>();
        for (String part : STATION_IDS.split(",")) {
            if (!part.trim().isEmpty()) {
                ids.add(part.trim());
            }
        }
        Assumptions.assumeTrue(ids.size() >= 3, "至少需要三个站 ID");

        List<Map<String, Map<Integer, Path>>> perStation = new ArrayList<>();
        List<String> commonDates = null;
        for (String id : ids) {
            Path stationDir = resolveStationDir(root, id);
            Assumptions.assumeTrue(stationDir != null, "找不到站 " + id + " 的 RTCM 目录，跳过");
            Map<String, Map<Integer, Path>> dates = scanStation(stationDir);
            Assumptions.assumeFalse(dates.isEmpty(), "站 " + id + " 无 RTCM 文件，跳过");
            perStation.add(dates);
            if (commonDates == null) {
                commonDates = new ArrayList<>(dates.keySet());
            } else {
                commonDates.retainAll(dates.keySet());
            }
        }
        Assumptions.assumeFalse(commonDates == null || commonDates.isEmpty(), "三站无共同日期，跳过");

        // 选共有时段最多的日期，其次取较新的
        String date = null;
        int best = 0;
        for (String candidate : commonDates) {
            int hours = commonHoursOf(perStation, candidate).size();
            if (hours > best || (hours == best && date != null && candidate.compareTo(date) > 0)) {
                best = hours;
                date = candidate;
            }
        }
        Assumptions.assumeFalse(date == null || best == 0, "三站无共同时段，跳过");

        List<Integer> commonHours = commonHoursOf(perStation, date);
        commonHours.sort(Integer::compareTo);
        if (commonHours.size() > 2) {
            commonHours = commonHours.subList(0, 2);
        }

        List<StationObservation> stations = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            List<Path> files = new ArrayList<>();
            Map<Integer, Path> hours = perStation.get(i).get(date);
            for (int hour : commonHours) {
                files.add(hours.get(hour));
            }
            stations.add(RtcmStationLoader.load(ids.get(i), files));
        }
        return stations;
    }

    private static List<Integer> commonHoursOf(List<Map<String, Map<Integer, Path>>> perStation, String date) {
        List<Integer> hours = null;
        for (Map<String, Map<Integer, Path>> dates : perStation) {
            List<Integer> current = new ArrayList<>(dates.get(date).keySet());
            if (hours == null) {
                hours = current;
            } else {
                hours.retainAll(current);
            }
        }
        return hours == null ? new ArrayList<>() : hours;
    }

    /**
     * 定位站目录：可能是根目录直接子目录，也可能位于存储分组目录下的一层。
     */
    private static Path resolveStationDir(Path root, String id) {
        Path direct = root.resolve(id);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        try (var children = Files.list(root)) {
            return children.filter(Files::isDirectory)
                    .map(d -> d.resolve(id))
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 扫描站目录：日期 -> (小时编号 -> RTCM 文件)，仅保留含 RTCM 文件的日期。
     */
    private static Map<String, Map<Integer, Path>> scanStation(Path stationDir) {
        Map<String, Map<Integer, Path>> result = new TreeMap<>();
        try (var dates = Files.list(stationDir)) {
            for (Path dateDir : dates.filter(Files::isDirectory).toList()) {
                Map<Integer, Path> hours = new TreeMap<>();
                try (var files = Files.list(dateDir)) {
                    for (Path file : files.toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".rtcm3")) {
                            continue;
                        }
                        try {
                            hours.put(Integer.parseInt(name.substring(0, name.length() - 6)), file);
                        } catch (NumberFormatException ignored) {
                            // 非纯数字文件名忽略
                        }
                    }
                } catch (Exception ignored) {
                    // 单个日期目录读取失败不影响整体
                }
                if (!hours.isEmpty()) {
                    result.put(dateDir.getFileName().toString(), hours);
                }
            }
        } catch (Exception ignored) {
            // 站目录读取失败按无数据处理
        }
        return result;
    }
}
