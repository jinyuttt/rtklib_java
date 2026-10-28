package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.data.Eph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.time.TimeSystem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 从 RTCM3 文件构建 {@link StationObservation}。
 *
 * <p>坐标取自 RTCM 1005/1006（天线参考点 ECEF），缺失时可用 {@code ecefOverride} 显式给定，
 * 二者都没有则报错。观测按绝对时间合并：同一历元被拆成多条 MSM 消息（BDS 常见 10+3 帧甚至空帧）
 * 时按卫星合并到一条 {@link Obsd}。</p>
 *
 * <p>文件路径符合 {@code <yyyy-MM-dd>/<hour>.rtcm3} 时自动按该小时推断时间基准，
 * 避免不含星历的流被解码到 GPS 周 0，导致与其他站时间基准不一致。</p>
 */
public final class RtcmStationLoader {

    private RtcmStationLoader() {
    }

    /**
     * 单文件装载（适用于单小时 RTCM 文件）。
     *
     * @param stationId 站名（唯一标识）
     * @param rtcmFile  RTCM3 文件
     * @return 站点观测
     */
    public static StationObservation load(String stationId, Path rtcmFile) {
        return load(stationId, List.of(rtcmFile), null);
    }

    /**
     * 多文件装载（逐小时文件按时间自动合并）。
     *
     * @param stationId 站名（唯一标识）
     * @param rtcmFiles RTCM3 文件列表（顺序无关）
     * @return 站点观测
     */
    public static StationObservation load(String stationId, List<Path> rtcmFiles) {
        return load(stationId, rtcmFiles, null);
    }

    /**
     * @param stationId    站名（唯一标识）
     * @param rtcmFiles    RTCM3 文件列表
     * @param ecefOverride 坐标覆盖（ECEF，米）；为 null 时使用 1005/1006
     * @return 站点观测
     */
    public static StationObservation load(String stationId, List<Path> rtcmFiles, double[] ecefOverride) {
        if (rtcmFiles == null || rtcmFiles.isEmpty()) {
            throw new IllegalArgumentException("RTCM 文件列表为空");
        }
        Collector collector = new Collector();
        for (Path file : rtcmFiles) {
            RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(collector);
            GTime reference = inferReference(file);
            if (reference != null) {
                decoder.setReferenceTime(reference);
            }
            byte[] data;
            try {
                data = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException("读取 RTCM 文件失败: " + file, e);
            }
            decoder.feed(data, 0, data.length);
            decoder.finish();
        }

        double[] ecef = collector.ecef != null ? collector.ecef : ecefOverride;
        if (ecef == null) {
            throw new IllegalStateException("RTCM 中缺少 1005/1006 站坐标，且未提供坐标覆盖: " + stationId);
        }
        List<Obsd> observations = collector.flatten();
        if (observations.isEmpty()) {
            throw new IllegalStateException("RTCM 无观测数据: " + stationId);
        }
        return new StationObservation(stationId, ecef, observations, collector.ephemerides());
    }

    /**
     * 按 {@code <yyyy-MM-dd>/<hour>.rtcm3} 路径推断时间基准。
     *
     * <p>空纪元取该小时的整点（按 UTC 解析，时区误差仅几小时，不影响周内 tow 对齐）。</p>
     *
     * @return 参考时间（GPST）；路径不符合约定返回 null
     */
    private static GTime inferReference(Path file) {
        Path parent = file.getParent();
        Path name = file.getFileName();
        if (parent == null || parent.getFileName() == null || name == null) {
            return null;
        }
        String fileName = name.toString();
        if (!fileName.endsWith(".rtcm3")) {
            return null;
        }
        int hour;
        try {
            hour = Integer.parseInt(fileName.substring(0, fileName.length() - ".rtcm3".length()));
        } catch (NumberFormatException e) {
            return null;
        }
        String[] date = parent.getFileName().toString().split("-");
        if (date.length != 3) {
            return null;
        }
        try {
            double[] ep = {Integer.parseInt(date[0]), Integer.parseInt(date[1]),
                    Integer.parseInt(date[2]), hour, 0, 0.0};
            return TimeSystem.utc2gpst(TimeSystem.epoch2time(ep));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 收集 1005/1006 坐标并按毫秒级时间键合并同历元多帧 MSM。
     */
    private static final class Collector implements RtcmDataHandler {

        private double[] ecef;
        private final TreeMap<Long, Map<Integer, Obsd>> epochs = new TreeMap<>();
        private final Map<Integer, Eph> ephBySat = new LinkedHashMap<>();

        @Override
        public void onStation(Sta sta) {
            if (sta == null || sta.pos == null || sta.pos.length != 3) {
                return;
            }
            if (sta.pos[0] == 0.0 && sta.pos[1] == 0.0 && sta.pos[2] == 0.0) {
                return;
            }
            ecef = sta.pos.clone();
        }

        @Override
        public void onObservationEpoch(ObservationEpoch epoch) {
            if (epoch == null || epoch.obsList == null || epoch.obsList.isEmpty()) {
                return;
            }
            GTime time = epoch.obsList.get(0).time;
            long key = Math.round((time.time + time.sec) * 1000.0);
            Map<Integer, Obsd> bySat = epochs.computeIfAbsent(key, k -> new LinkedHashMap<>());
            for (Obsd src : epoch.obsList) {
                if (src == null || src.sat == 0) {
                    continue;
                }
                Obsd existing = bySat.get(src.sat);
                if (existing == null) {
                    bySat.put(src.sat, new Obsd(src));
                } else {
                    merge(existing, src);
                }
            }
        }

        List<Obsd> flatten() {
            List<Obsd> observations = new ArrayList<>();
            for (Map<Integer, Obsd> perEpoch : epochs.values()) {
                observations.addAll(perEpoch.values());
            }
            return observations;
        }

        /**
         * 同星同历元多帧合并：非零观测值覆盖，空值保留已有。
         */
        private static void merge(Obsd dst, Obsd src) {
            for (int i = 0; i < dst.P.length; i++) {
                if (src.P[i] != 0.0) {
                    dst.P[i] = src.P[i];
                    dst.code[i] = src.code[i];
                    dst.Pstd[i] = src.Pstd[i];
                }
                if (src.L[i] != 0.0) {
                    dst.L[i] = src.L[i];
                    dst.Lstd[i] = src.Lstd[i];
                    if (dst.code[i] == 0) {
                        dst.code[i] = src.code[i];
                    }
                }
                if (src.LLI[i] != 0) {
                    dst.LLI[i] = src.LLI[i];
                }
                if (src.D[i] != 0.0f) {
                    dst.D[i] = src.D[i];
                }
                if (src.SNR[i] != 0.0f) {
                    dst.SNR[i] = src.SNR[i];
                }
            }
        }

        @Override
        public void onSsr(Ssr ssr) {
            // 网络 VRS 阶段暂不使用 SSR
        }

        @Override
        public void onEph(Eph eph) {
            if (eph == null || eph.sat == 0) {
                return;
            }
            Eph existing = ephBySat.get(eph.sat);
            if (existing == null || TimeSystem.timediff(eph.toe, existing.toe) > 0.0) {
                ephBySat.put(eph.sat, new Eph(eph));
            }
        }

        List<Eph> ephemerides() {
            return new ArrayList<>(ephBySat.values());
        }

        @Override
        public void onGeph(Geph geph) {
            // 同上
        }

        @Override
        public void onAuxData(AuxData aux) {
            // 天线描述暂不使用
        }

        @Override
        public void onFinish() {
            // 无收尾动作
        }
    }
}
