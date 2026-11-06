package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 逐历元 SSR 改正注入：把某一时刻可用的 SSR 改正写入 {@code nav.ssr[]}，
 * 使网解在 {@link Constants#EPHOPT_SSRAPC}/{@link Constants#EPHOPT_SSRCOM}
 * 下按「广播星历 + SSR 改正」计算卫星位置与钟差。
 *
 * <p>两种来源：</p>
 * <ul>
 *   <li>{@link #live} —— 增量摄入 RTCM3 SSR 字节流（1057~1068/1240~1270），
 *       按卫星建立时间线，逐历元取最近一条并线性外推到该历元。
 *       实时流（{@code NtripClient} 的 {@code onData}）与录制文件回放
 *       （{@link #fromRtcm}）都走这一条路径，解码与注入逻辑完全一致。</li>
 *   <li>{@link #fromPreciseProducts} —— 由已装载的 SP3/CLK 反算 SSR 轨道/钟差改正
 *       （{@code deph = RAC(精密 - 广播)}，{@code dclk = 精密钟差 - 广播钟差}），
 *       用于在没有当日 SSR 流的条件下事后回放 SSR 链路。</li>
 * </ul>
 *
 * <p>注入后的记录已归算到当前历元（{@code t0} 等于该历元），因此下游
 * {@link org.rtklib.java.ppprtk.SsrCorrector} 的零阶项即为该时刻的改正值。</p>
 */
public final class SsrCorrectionProvider {

    /** 默认 SSR 最大时效（s），覆盖 60 s 轨道更新与 10 s 钟差更新的 IGS RTS 流。 */
    public static final double DEFAULT_MAX_AGE_SEC = 90.0;

    /** 广播星历速度的数值差分步长（s），与 {@link EphModel#satposPrec} 一致。 */
    private static final double VELOCITY_STEP_SEC = 1e-3;

    /** 卫星 -> 按参考历元升序的 SSR 记录；实时摄入时增量插入。 */
    private final Map<Integer, NavigableMap<Long, Ssr>> timeline = new HashMap<>();

    /** RTCM3 增量解码器；文件回放与实时流共用同一实例。 */
    private final Rtcm decoder;

    /** 尚未凑成完整电文的尾部字节，由下一次 {@link #ingest} 续接。 */
    private byte[] pending = new byte[0];

    private int pendingLen;

    private final double maxAgeSec;

    private final boolean derivedFromPrecise;

    private SsrCorrectionProvider(double maxAgeSec, boolean derivedFromPrecise,
                                  GTime referenceTime) {
        this.maxAgeSec = maxAgeSec;
        this.derivedFromPrecise = derivedFromPrecise;
        this.decoder = derivedFromPrecise ? null : new Rtcm();
        if (this.decoder != null && referenceTime != null) {
            this.decoder.setReferenceTime(referenceTime);
        }
    }

    /**
     * 创建实时 SSR 摄入通道（按 GPS 周 0 基准解码）。
     *
     * <p>仅用于相对时刻检查；需要绝对时刻时请用
     * {@link #live(GTime, double)} 指定周基准。</p>
     *
     * @return 可增量摄入字节流的 SSR 改正提供者
     */
    public static SsrCorrectionProvider live() {
        return live(null, DEFAULT_MAX_AGE_SEC);
    }

    /**
     * 创建实时 SSR 摄入通道。
     *
     * <p>典型用法：把 {@code NtripClient} 的 {@code onData(byte[], offset, length)}
     * 回调直接转给 {@link #ingest(byte[], int, int)}，解算前调用
     * {@link #applyAt} 即可。RTCM SSR 电文只携带周内秒，解码器需要一周基准才能
     * 还原绝对时刻；{@code referenceTime} 取流起始附近的任意时刻即可
     * （允许一周内的偏差）。</p>
     *
     * @param referenceTime 周基准参考时刻；为 {@code null} 时按 GPS 周 0 解码
     * @param maxAgeSec    改正最大时效（s），超过则视为无效改正
     * @return 可增量摄入字节流的 SSR 改正提供者
     */
    public static SsrCorrectionProvider live(GTime referenceTime, double maxAgeSec) {
        return new SsrCorrectionProvider(maxAgeSec, false, referenceTime);
    }

    /**
     * 回放录制的 RTCM3 SSR 流（按 GPS 周 0 基准解码）。
     *
     * <p>仅用于相对时刻检查；需要绝对时刻时请用
     * {@link #fromRtcm(Path, GTime, double)} 指定周基准。</p>
     *
     * @param file RTCM3 原始流文件
     * @return SSR 改正提供者
     */
    public static SsrCorrectionProvider fromRtcm(Path file) {
        return fromRtcm(file, null, DEFAULT_MAX_AGE_SEC);
    }

    /**
     * 回放录制的 RTCM3 SSR 流。
     *
     * <p>RTCM SSR 电文只携带周内秒，解码器需要一周基准才能还原绝对时刻；
     * {@code referenceTime} 取流起始附近的任意时刻即可（允许一周内的偏差）。</p>
     *
     * @param file          RTCM3 原始流文件
     * @param referenceTime 周基准参考时刻；为 {@code null} 时按 GPS 周 0 解码
     * @param maxAgeSec    改正最大时效（s），超过则视为无效改正
     * @return SSR 改正提供者
     */
    public static SsrCorrectionProvider fromRtcm(Path file, GTime referenceTime, double maxAgeSec) {
        if (file == null || !Files.exists(file)) {
            throw new IllegalArgumentException("缺少 SSR RTCM 流文件: " + file);
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 SSR 流失败: " + file, e);
        }
        // 与实时流共用同一条字节摄入路径，避免文件/流两种解码实现出现分歧
        SsrCorrectionProvider provider = live(referenceTime, maxAgeSec);
        provider.ingest(data);
        return provider;
    }

    /**
     * 增量摄入一段 RTCM3 字节流（实时链路入口）。
     *
     * <p>可被 {@code NtripClient} 的 {@code onData} 回调反复调用：不完整的尾部电文
     * 会缓存在 {@link #pending} 中，与下一次调用的数据续接。非 SSR 电文（如 MSM 观测、
     * 1005 站坐标）会被正常解码但不进入 SSR 时间线，因此同一路原始流可直接整段喂入。</p>
     *
     * @param data 原始字节
     */
    public void ingest(byte[] data) {
        if (data != null) {
            ingest(data, 0, data.length);
        }
    }

    /**
     * 增量摄入一段 RTCM3 字节流。
     *
     * @param data   原始字节缓冲
     * @param offset 起始偏移
     * @param length 有效长度
     * @throws IllegalArgumentException 切片越界，或反算模式下调用（该模式无字节流）
     */
    public synchronized void ingest(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return;
        }
        if (derivedFromPrecise) {
            throw new IllegalArgumentException("反算模式不接受字节流摄入");
        }
        if (offset < 0 || offset + length > data.length) {
            throw new IllegalArgumentException(
                    "非法切片: offset=" + offset + ", length=" + length + ", capacity=" + data.length);
        }
        append(data, offset, length);
        int pos = 0;
        while (pos < pendingLen) {
            int consumed = decoder.input(pending, pos, pendingLen - pos);
            if (consumed <= 0) {
                break;
            }
            pos += consumed;
            if (isSsrMessage(decoder.type)) {
                captureDecoded(decoder);
            }
        }
        if (pos > 0) {
            int remain = pendingLen - pos;
            if (remain > 0) {
                System.arraycopy(pending, pos, pending, 0, remain);
            }
            pendingLen = remain;
        }
    }

    /** 把新数据追加到 {@link #pending} 尾部，容量不足时按 2 的幂扩张。 */
    private void append(byte[] data, int offset, int length) {
        int need = pendingLen + length;
        if (need > pending.length) {
            int capacity = Math.max(pending.length, 256);
            while (capacity < need) {
                capacity <<= 1;
            }
            pending = Arrays.copyOf(pending, capacity);
        }
        System.arraycopy(data, offset, pending, pendingLen, length);
        pendingLen = need;
    }

    /**
     * @return 已建立的卫星数（时间线非空的卫星），用于链路健康检查
     */
    public synchronized int satelliteCount() {
        return timeline.size();
    }

    /**
     * @return 时间线中最早的改正记录时刻；无数据返回 {@code null}
     *
     * <p>回放录制流时用于对齐解算起点：{@link #applyAt} 在超过
     * {@code maxAgeSec} 的历元上不会写入改正，因此解算窗口应从这个时刻之后开始。</p>
     */
    public synchronized GTime earliestTime() {
        long key = Long.MAX_VALUE;
        for (NavigableMap<Long, Ssr> records : timeline.values()) {
            if (!records.isEmpty()) {
                key = Math.min(key, records.firstKey());
            }
        }
        return key == Long.MAX_VALUE ? null : fromEpochKey(key);
    }

    /**
     * @return 时间线中最晚的改正记录时刻；无数据返回 {@code null}
     */
    public synchronized GTime latestTime() {
        long key = Long.MIN_VALUE;
        for (NavigableMap<Long, Ssr> records : timeline.values()) {
            if (!records.isEmpty()) {
                key = Math.max(key, records.lastKey());
            }
        }
        return key == Long.MIN_VALUE ? null : fromEpochKey(key);
    }

    /**
     * 收集本帧电文更新过的卫星改正。
     *
     * <p>一条 SSR 电文可携带多颗卫星（{@code nsat} 颗），因此不能只看
     * {@link Rtcm#ephsat}；这里扫描 {@code nav.ssr[]}，把本帧刚解码的卫星
     * 快照进按历元归并的记录表，并清除其 {@code update} 标记以便下一帧识别增量。</p>
     */
    private void captureDecoded(Rtcm rtcm) {
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            Ssr ssr = rtcm.nav.ssr[sat - 1];
            if (ssr == null || ssr.update == 0) {
                continue;
            }
            ssr.update = 0;
            GTime t0 = ssr.t0[0];
            if (t0.time == 0 && t0.sec == 0.0) {
                continue;
            }
            // 同一卫星同一参考历元的轨道/钟差/偏差电文合并为一条记录
            timeline.computeIfAbsent(sat, k -> new TreeMap<>())
                    .put(recordKey(ssr), new Ssr(ssr));
        }
    }

    /**
     * 由已装载的 SP3/CLK 精密产品反算 SSR 改正（事后 SSR 回放）。
     *
     * <p>{@link #applyAt} 每次调用时按当前历元实时计算「精密 - 广播」差值，
     * 因此不依赖任何录制的 SSR 流；结果与 {@link Constants#EPHOPT_PREC} 等价。</p>
     *
     * @return SSR 改正提供者
     */
    public static SsrCorrectionProvider fromPreciseProducts() {
        return new SsrCorrectionProvider(DEFAULT_MAX_AGE_SEC, true, null);
    }

    /**
     * 把 {@code time} 时刻的 SSR 改正写入 {@code nav.ssr[]}。
     *
     * @param nav       目标导航数据（需含广播星历；反算模式还需 peph/pclk）
     * @param time      历元（GPST）
     * @param satellites 本历元实际参与解算的卫星号；未列出的卫星不会刷新
     */
    public synchronized void applyAt(Nav nav, GTime time, Iterable<Integer> satellites) {
        if (nav == null || nav.ssr == null || time == null || satellites == null) {
            return;
        }
        for (int sat : satellites) {
            if (sat <= 0 || sat > Constants.MAXSAT) {
                continue;
            }
            Ssr target = nav.ssr[sat - 1];
            if (target == null) {
                continue;
            }
            // 先失效，避免上一历元的改正被沿用
            target.update = 0;
            Ssr applied = derivedFromPrecise ? derive(nav, sat, time) : lookup(sat, time);
            if (applied != null) {
                nav.ssr[sat - 1] = applied;
            }
        }
    }

    /**
     * @return 该卫星在 {@code time} 时刻的改正记录；超龄或无数据返回 {@code null}
     */
    private Ssr lookup(int sat, GTime time) {
        NavigableMap<Long, Ssr> records = timeline.get(sat);
        if (records == null || records.isEmpty()) {
            return null;
        }
        double tsec = time.time + time.sec;
        // recordKey 为毫秒；加 1e-6 ms 容差吸收 double 乘法的末位误差
        long queryKey = (long) Math.floor(tsec * 1000.0 + 1e-6);
        Map.Entry<Long, Ssr> floor = records.floorEntry(queryKey);
        Ssr latest = floor != null ? floor.getValue() : records.firstEntry().getValue();
        if (Math.abs(tsec - recordKey(latest) / 1000.0) > maxAgeSec) {
            return null;
        }
        return propagate(latest, time);
    }

    /**
     * @return 记录的有效时刻（ms，取轨道与钟差参考历元中较新者）
     */
    private static long recordKey(Ssr rec) {
        long key = epochKey(rec.t0[0]);
        long clockKey = epochKey(rec.t0[1]);
        return Math.max(key, clockKey);
    }

    /**
     * 把 SSR 记录外推到 {@code time}：轨道按 {@code ddeph} 线性项、钟差按多项式，
     * 并令 {@code t0} 归算到该历元，使下游零阶改正即为当前时刻的值。
     */
    private static Ssr propagate(Ssr rec, GTime time) {
        Ssr s = new Ssr(rec);
        double dtOrb = TimeSystem.timediff(time, rec.t0[0]);
        double dtClk = TimeSystem.timediff(time, rec.t0[1]);
        for (int j = 0; j < 3; j++) {
            s.deph[j] = rec.deph[j] + rec.ddeph[j] * dtOrb;
            s.ddeph[j] = 0.0;
        }
        s.dclk[0] = rec.dclk[0] + rec.dclk[1] * dtClk + rec.dclk[2] * dtClk * dtClk;
        s.dclk[1] = rec.dclk[1] + 2.0 * rec.dclk[2] * dtClk;
        s.dclk[2] = 0.0;
        s.t0[0] = new GTime(time);
        s.t0[1] = new GTime(time);
        s.update = 1;
        return s;
    }

    /**
     * 由精密产品反算单星 SSR 改正。
     *
     * <p>轨道改正取 {@code RAC} 分量，并按 {@link org.rtklib.java.ppprtk.SsrCorrector}
     * 使用的非正交基（{@code eR}、{@code eA}、{@code eR×eA}）求解 Gram 系数，
     * 保证下游回算后与精密轨道严格一致。</p>
     */
    private static Ssr derive(Nav nav, int sat, GTime time) {
        double[] rs0 = new double[6];
        double[] dts0 = new double[2];
        double[] vare0 = new double[1];
        EphModel.satpos(time, nav, sat, rs0, dts0, vare0);
        if (rs0[0] == 0.0 && rs0[1] == 0.0 && rs0[2] == 0.0) {
            return null;
        }
        double[] rsp = new double[6];
        double[] dtsp = new double[2];
        double[] varp = new double[1];
        if (!EphModel.satposPrec(time, sat, nav, rsp, dtsp, varp)) {
            return null;
        }
        broadcastVelocity(nav, sat, time, rs0);
        double[] eR = unit(rs0, 0);
        double[] eA = unit(rs0, 3);
        if (eR == null || eA == null) {
            return null;
        }
        double[] eC = {
                eR[1] * eA[2] - eR[2] * eA[1],
                eR[2] * eA[0] - eR[0] * eA[2],
                eR[0] * eA[1] - eR[1] * eA[0]
        };
        double c = dot(eR, eA);
        double s2 = 1.0 - c * c;
        if (s2 < 1e-6) {
            return null;
        }
        double[] delta = {rsp[0] - rs0[0], rsp[1] - rs0[1], rsp[2] - rs0[2]};
        double dR = dot(delta, eR);
        double dA = dot(delta, eA);
        double dC = dot(delta, eC);

        Ssr s = new Ssr();
        s.deph[0] = (dR - c * dA) / s2;
        s.deph[1] = (dA - c * dR) / s2;
        s.deph[2] = dC / s2;
        s.dclk[0] = (dtsp[0] - dts0[0]) * Constants.CLIGHT;
        s.dclk[1] = (dtsp[1] - dts0[1]) * Constants.CLIGHT;
        s.t0[0] = new GTime(time);
        s.t0[1] = new GTime(time);
        s.udi[0] = 0.0;
        s.udi[1] = 0.0;
        s.update = 1;
        return s;
    }

    /**
     * 广播星历速度：中心差商（与 {@link EphModel#satposPrec} 的速度算法一致）。
     */
    private static void broadcastVelocity(Nav nav, int sat, GTime time, double[] rs) {
        double[] rs2 = new double[6];
        double[] dts2 = new double[2];
        double[] vare2 = new double[1];
        EphModel.satpos(TimeSystem.timeadd(time, VELOCITY_STEP_SEC), nav, sat, rs2, dts2, vare2);
        for (int j = 0; j < 3; j++) {
            rs[3 + j] = (rs2[j] - rs[j]) / VELOCITY_STEP_SEC;
        }
    }

    private static double[] unit(double[] rs, int offset) {
        double norm = Math.sqrt(rs[offset] * rs[offset]
                + rs[offset + 1] * rs[offset + 1]
                + rs[offset + 2] * rs[offset + 2]);
        if (norm < 1.0) {
            return null;
        }
        return new double[]{rs[offset] / norm, rs[offset + 1] / norm, rs[offset + 2] / norm};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static long epochKey(GTime t) {
        return t.time * 1000L + Math.round(t.sec * 1000.0);
    }

    private static GTime fromEpochKey(long key) {
        return new GTime(key / 1000L, (key % 1000L) / 1000.0);
    }

    private static boolean isSsrMessage(int type) {
        return (type >= 1057 && type <= 1068) || (type >= 1240 && type <= 1270);
    }
}