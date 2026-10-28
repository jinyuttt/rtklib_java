package org.rtklib.java.networkvrs.output;

import org.rtklib.java.data.Eph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Sta;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.sync.StationObservation;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.vrs.VrsCorrectionProvider;
import org.rtklib.java.vrs.VrsGenerator;
import org.rtklib.java.vrs.config.VrsOptionalConfig;
import org.rtklib.java.vrs.rtcm.Rtcm3Encoder;
import org.rtklib.java.vrs.rtcm.RtcmSink;
import org.rtklib.java.vrs.rtcm.RtcmSinks;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 网络改正输出桥：把网络插值得到的相对主站改正量交给 rtklib-vrs 生成 RTCM3。
 *
 * <p>以主站观测为基准，逐历元构造 {@link ObservationEpoch} 驱动 {@link VrsGenerator}；
 * 改正量按 {@code 历元时间 + 卫星号} 查表，命中则替代模型改正，未命中回退为模型改正。</p>
 */
public final class VrsOutputBridge {

    /** 改正量与主站历元的匹配容差（毫秒），覆盖站间历元相位差。 */
    private static final long MATCH_TOLERANCE_MS = 500L;

    private VrsOutputBridge() {
    }

    /**
     * 输出到文件（便捷重载）：内部打开出口，生命周期由本方法管理。
     *
     * @param outputRtcmPath 输出 RTCM3 文件
     * @return 传入的历元数（含未产生观测的历元）
     */
    public static int generate(StationObservation master,
                               double[] targetPos,
                               List<NetworkVrsProcessor.EpochCorrection> corrections,
                               String outputRtcmPath,
                               VrsOptionalConfig opt,
                               String vrsStationId) throws IOException {
        try (RtcmSink sink = RtcmSinks.file(outputRtcmPath)) {
            return generate(master, targetPos, corrections, sink, opt, vrsStationId);
        }
    }

    /**
     * 输出为字节数组（便捷重载）：把全部帧攒在内存里一次性返回。
     *
     * <p>只适合有界批量场景；连续推流请用 {@link #generate} 的 {@link RtcmSink} 重载，
     * 避免字节在内存中无界累积。</p>
     *
     * @return 全部 RTCM3 帧拼接后的字节
     */
    public static byte[] generateToBytes(StationObservation master,
                                         double[] targetPos,
                                         List<NetworkVrsProcessor.EpochCorrection> corrections,
                                         VrsOptionalConfig opt,
                                         String vrsStationId) {
        RtcmSinks.Collector collector = RtcmSinks.collect();
        generate(master, targetPos, corrections, collector, opt, vrsStationId);
        return collector.toByteArray();
    }

    /**
     * 库主入口：把改正量交给 VRS 生成器，每条 RTCM3 帧以字节数组回调给出口。
     *
     * <p>库不关心字节去向——落盘、推 socket、入队都由调用方实现。</p>
     *
     * @param master       主站观测（同时作为 VRS 计算的基准观测与星历来源）
     * @param targetPos    虚拟站大地坐标 [lat(rad), lon(rad), height(m)]，由调用方按流动站位置给出
     * @param corrections  {@link NetworkVrsProcessor#processRelative} 的输出（相对主站增量）
     * @param sink         RTCM3 字节出口
     * @param opt          VRS 可选配置
     * @param vrsStationId 输出流的站名
     * @return 传入的历元数（含未产生观测的历元）
     */
    public static int generate(StationObservation master,
                               double[] targetPos,
                               List<NetworkVrsProcessor.EpochCorrection> corrections,
                               RtcmSink sink,
                               VrsOptionalConfig opt,
                               String vrsStationId) {
        NavigableMap<Long, Map<Integer, double[]>> byEpoch = new TreeMap<>();
        for (NetworkVrsProcessor.EpochCorrection epoch : corrections) {
            Map<Integer, double[]> perSat = new HashMap<>();
            for (Map.Entry<Integer, NetworkVrsProcessor.CorrectionValue> entry
                    : epoch.corrections().entrySet()) {
                NetworkVrsProcessor.CorrectionValue value = entry.getValue();
                perSat.put(entry.getKey(),
                        new double[]{value.iono(), value.tropo(), value.orbit()});
            }
            byEpoch.put(timeKey(epoch.time()), perSat);
        }

        VrsCorrectionProvider provider = (time, sat) -> {
            Map<Integer, double[]> perSat = lookup(byEpoch, timeKey(time));
            return perSat == null ? null : perSat.get(sat);
        };

        Nav nav = new Nav();
        for (Eph eph : master.ephemerides()) {
            if (eph.sat > 0 && eph.sat - 1 < nav.nmax) {
                nav.eph[eph.sat - 1] = new Eph(eph);
                if (eph.sat > nav.n) {
                    nav.n = eph.sat;
                }
            }
        }

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = opt.rtcmStationId;
        encoder.seqNo = opt.seqNo;

        VrsGenerator generator = new VrsGenerator(targetPos, vrsStationId, opt, encoder,
                sink, nav, null, null, provider);
        generator.onStation(masterSta(master));
        for (Eph eph : master.ephemerides()) {
            generator.onEph(new Eph(eph));
        }

        int epochs = 0;
        for (Map.Entry<Double, List<Obsd>> entry : master.groupByEpoch().entrySet()) {
            List<Obsd> observations = entry.getValue();
            if (observations.isEmpty()) {
                continue;
            }
            ObservationEpoch epoch = new ObservationEpoch(observations.get(0).time, master.id());
            epoch.obsList.addAll(observations);
            generator.onObservationEpoch(epoch);
            epochs++;
        }
        generator.onFinish();
        return epochs;
    }

    private static Sta masterSta(StationObservation master) {
        Sta sta = new Sta();
        sta.name = master.id();
        sta.marker = master.id();
        sta.pos = master.ecef();
        return sta;
    }

    /**
     * 在容差内寻找最近历元的改正量（多站网格存在相位差时主站与参考站时间不必相等）。
     */
    private static Map<Integer, double[]> lookup(NavigableMap<Long, Map<Integer, double[]>> byEpoch,
                                                 long key) {
        Map.Entry<Long, Map<Integer, double[]>> floor = byEpoch.floorEntry(key);
        Map.Entry<Long, Map<Integer, double[]>> ceil = byEpoch.ceilingEntry(key);
        long bestDiff = Long.MAX_VALUE;
        Map<Integer, double[]> best = null;
        if (floor != null) {
            bestDiff = key - floor.getKey();
            best = floor.getValue();
        }
        if (ceil != null && ceil.getKey() - key < bestDiff) {
            bestDiff = ceil.getKey() - key;
            best = ceil.getValue();
        }
        return bestDiff <= MATCH_TOLERANCE_MS ? best : null;
    }

    private static long timeKey(GTime time) {
        return Math.round((time.time + time.sec) * 1000.0);
    }
}