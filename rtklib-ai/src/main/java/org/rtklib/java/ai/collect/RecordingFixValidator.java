package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.feature.FixQualityFeatures;
import org.rtklib.java.ai.spi.FixQualityContext;
import org.rtklib.java.ai.spi.FixValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;

/**
 * 采集型接口一实现：把每次 {@code aiAcceptFix()} 调用的上下文落盘成 CSV 一行，
 * 并<b>恒返回 {@code 1.0}</b>（不干预）。
 * <p>
 * 恒返回 1.0 的两个后果：
 * </p>
 * <ul>
 *   <li>core 走原生固定分支，行为与不注入逐位一致，故这份数据同时是 A/B 的对照组基线；</li>
 *   <li>{@code 1.0 >= fixValidatorMinConfidence} 恒成立，不会产生 {@code AR/AI_REJECT}，
 *       不会因为采集而改变解算结果。</li>
 * </ul>
 * <p>
 * 特征必须复用 {@link FixQualityFeatures#extractRaw}，<b>不得在本类重算</b>：
 * 该函数内含退化处理（{@code nPhaseRows==0 → prr=0}、{@code ns<=0 → 不除零}），
 * 重写一份会让训练分布与推理分布悄悄错位。
 * </p>
 * <p>
 * 写盘失败不抛异常（{@code FixValidator} 契约要求不抛）：一旦失败即停止写入并返回 1.0，
 * 解算不受影响，仅丢失采集数据。
 * </p>
 * <p>
 * 本类不承诺跨线程安全：{@code AiHooks} 是 {@code ThreadLocal} 单槽，天然一线程一实例。
 * 并行采集时每个线程各自 {@code open()} 自己的会话。
 * </p>
 */
public final class RecordingFixValidator implements FixValidator, AutoCloseable {

    /** CSV 表头：列序即写盘列序，与 {@code fix_dataset} 的必需列一一对应。 */
    public static final String CSV_HEADER = "run_id,station,day,epoch,"
            + "f0,f1,f2,f3,f4,f5,"
            + "ns,nf,nb,n_code_rows,n_phase_rows";

    private static final Logger LOG = LoggerFactory.getLogger(RecordingFixValidator.class);

    private final CollectConfig cfg;
    private final Writer out;
    private final StringBuilder line = new StringBuilder(192);

    private long rows;
    private long sinceFlush;
    private boolean writable = true;
    private boolean closed;

    RecordingFixValidator(CollectConfig cfg, Writer out) throws IOException {
        this.cfg = cfg;
        this.out = out;
        out.write(CSV_HEADER);
        out.write('\n');
        out.flush();
    }

    @Override
    public double validate(FixQualityContext ctx) {
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
                LOG.error("接口一采集写盘失败，已停止采集（解算不受影响）: {}", cfg.csvPath(), e);
            }
        }
        return 1.0;
    }

    /** 已落盘行数（不含表头）。 */
    public synchronized long rows() {
        return rows;
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

    private void writeRow(FixQualityContext ctx) throws IOException {
        double[] f = FixQualityFeatures.extractRaw(ctx);
        line.setLength(0);
        line.append(cfg.runId()).append(',')
                .append(cfg.station()).append(',')
                .append(cfg.day()).append(',')
                .append(ctx.epoch());
        for (int i = 0; i < FixQualityFeatures.DIM; i++) {
            line.append(',').append(number(f[i]));
        }
        line.append(',').append(ctx.ns())
                .append(',').append(ctx.nf())
                .append(',').append(ctx.nb())
                .append(',').append(ctx.nCodeRows())
                .append(',').append(ctx.nPhaseRows())
                .append('\n');
        out.write(line.toString());
        rows++;
        if (++sinceFlush >= cfg.flushEvery()) {
            out.flush();
            sinceFlush = 0;
        }
    }

    private static String number(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "0.0";
    }
}