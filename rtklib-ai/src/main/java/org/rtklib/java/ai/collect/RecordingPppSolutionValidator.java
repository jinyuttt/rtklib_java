package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.feature.PppSolutionFeatures;
import org.rtklib.java.ai.spi.PppSolutionContext;
import org.rtklib.java.ai.spi.PppSolutionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;

/**
 * 采集型接口 A 实现：把每次 {@code confidence()} 调用的上下文落盘成 CSV 一行，
 * 并恒返回 {@code 1.0}（不干预）。
 *
 * 恒返回 {@code 1.0} 的两个后果与 {@link RecordingFixValidator} 同源：
 *
 *   - {@code 1.0 >= pppSolutionMinConfidence} 恒成立，采集不会把任何历元降级，
 *       解算结果与不注入逐位一致——这份数据同时就是 A 的确定性基线对照组；
 *   - 采集到的 {@code deterministic_degraded} 仍是 core 按 {@code posVar > 阈值}
 *       独立算出的归因字段（§3.5），不受本类影响。
 *
 * 特征必须复用 {@link PppSolutionFeatures#extractRaw}，不得在本类重算：该函数内含
 * 退化处理（负方差归零、{@code ns<=0} 不除零），重写一份会让训练分布与推理分布悄悄错位。
 *
 * 位置列 {@code x,y,z} 由宿主经 {@link PppPositionSource} 提供（SPI 快照不含坐标，见该类
 * 说明）；未提供时留空，由组装阶段计为 {@code missing_position}，绝不填 0——
 * 伪造坐标会把标签算成百万米级误差，比缺数据更糟。给了 {@code reference} 时额外写
 * {@code pos_err_3d}（有真值口径优先于簇心口径）。
 *
 * 写盘失败不抛异常（{@code PppSolutionValidator} 契约要求不抛）：失败即停止写入并返回
 * {@code 1.0}，解算不受影响，仅丢失采集数据。本类不承诺跨线程安全（{@code AiHooks}
 * 是 {@code ThreadLocal} 单槽，天然一线程一实例）。
 */
public final class RecordingPppSolutionValidator implements PppSolutionValidator, AutoCloseable {

    /** CSV 表头：列序即写盘列序，与 {@code ppp_solution_validator/assemble.py} 的必需列对应。 */
    public static final String CSV_HEADER = "run_id,station,day,epoch,"
            + "f0,f1,f2,f3,f4,f5,f6,f7,f8,f9,"
            + "ns,deterministic_degraded,x,y,z,pos_err_3d";

    private static final Logger LOG =
            LoggerFactory.getLogger(RecordingPppSolutionValidator.class);

    private final CollectConfig cfg;
    private final Writer out;
    private final PppPositionSource positions;
    private final double[] reference;
    private final StringBuilder line = new StringBuilder(256);

    private long rows;
    private long sinceFlush;
    private long missingPosition;
    private int lastEpoch = Integer.MIN_VALUE;
    private boolean writable = true;
    private boolean closed;

    RecordingPppSolutionValidator(CollectConfig cfg, Writer out,
                                  PppPositionSource positions, double[] reference)
            throws IOException {
        this.cfg = cfg;
        this.out = out;
        this.positions = positions;
        this.reference = reference == null ? null : reference.clone();
        out.write(CSV_HEADER);
        out.write('\n');
        out.flush();
    }

    @Override
    public Double confidence(PppSolutionContext ctx) {
        if (ctx == null) {
            return 1.0;
        }
        synchronized (this) {
            if (!writable || closed) {
                return 1.0;
            }
            try {
                writeRow(ctx);
            } catch (IOException e) {
                writable = false;
                LOG.error("接口 A 采集写盘失败，已停止采集（解算不受影响）: {}", cfg.pppCsvPath(), e);
            }
        }
        return 1.0;
    }

    /** 已落盘行数（不含表头）。 */
    public synchronized long rows() {
        return rows;
    }

    /** 位置缺失行数（组装阶段会再统计一次，这里供会话日志自证）。 */
    public synchronized long missingPosition() {
        return missingPosition;
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                out.flush();
            } finally {
                out.close();
            }
        }
    }

    private void writeRow(PppSolutionContext ctx) throws IOException {
        /* 同一历元重复调用只记第一行：core 侧每个历元调用一次，但 trace 重放/重试
         * 可能重复；重复行会让 assemble 判定主键冲突并整 run 弃用。 */
        if (ctx.epoch() == lastEpoch) {
            return;
        }
        lastEpoch = ctx.epoch();

        double[] f = PppSolutionFeatures.extractRaw(ctx);
        double[] pos = positions == null ? null : positions.position(ctx.epoch());
        double err = Double.NaN;
        if (pos != null && pos.length >= 3) {
            if (!finite(pos[0]) || !finite(pos[1]) || !finite(pos[2])) {
                pos = null;
            }
        } else {
            pos = null;
        }
        if (pos == null) {
            missingPosition++;
        } else if (reference != null && reference.length >= 3) {
            err = Math.sqrt(sqr(pos[0] - reference[0]) + sqr(pos[1] - reference[1])
                    + sqr(pos[2] - reference[2]));
        }

        line.setLength(0);
        line.append(cfg.runId()).append(',')
                .append(cfg.station()).append(',')
                .append(cfg.day()).append(',')
                .append(ctx.epoch());
        for (int i = 0; i < PppSolutionFeatures.DIM; i++) {
            line.append(',').append(number(f[i]));
        }
        line.append(',').append(ctx.ns())
                .append(',').append(ctx.deterministicDegraded() ? 1 : 0)
                .append(',');
        if (pos == null) {
            line.append(",,,");
        } else {
            line.append(number(pos[0])).append(',')
                    .append(number(pos[1])).append(',')
                    .append(number(pos[2]));
        }
        line.append(',');
        if (Double.isFinite(err)) {
            line.append(number(err));
        }
        line.append('\n');
        out.write(line.toString());
        rows++;
        if (++sinceFlush >= cfg.flushEvery()) {
            out.flush();
            sinceFlush = 0;
        }
    }

    private static double sqr(double v) {
        return v * v;
    }

    private static boolean finite(double v) {
        return Double.isFinite(v);
    }

    private static String number(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "0.0";
    }
}