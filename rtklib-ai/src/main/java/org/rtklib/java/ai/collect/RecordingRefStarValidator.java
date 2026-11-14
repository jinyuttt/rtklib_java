package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.feature.RefStarFeatures;
import org.rtklib.java.ai.spi.RefStarContext;
import org.rtklib.java.ai.spi.RefStarValidator;
import org.rtklib.java.ai.spi.SatFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

/**
 * 采集型接口四实现：把每历元每 {@code (sat, frq)} 的 15 维特征落盘成 CSV 一行，
 * 并<b>恒输出「不干预」</b>（风险分全 0）。
 *
 * 恒不干预的后果与接口三的 {@link RecordingObsCorrector} 完全同构：
 * <ul>
 *   <li>风险分全 0 时 core 的降级分支永不触发（{@code aiRefRisk[i] > refStarRiskMax} 恒假），
 *       参考星选择与不注入逐位一致，故同一次运行既产出训练特征、又产出 A 组基线；</li>
 *   <li>不产生 {@code AI4/AI_DEGRADE}、{@code AI4/REFSTAR_SWITCH}，采集不改变解算结果。</li>
 * </ul>
 *
 * <b>本类不维护任何窗口状态</b>：{@code snrWinMean} / {@code snrWinStd} / {@code refCount}
 * 由 core 的 {@code AiRefStarState} 在推理前算好并通过 {@link RefStarContext} 下传，
 * 本类只做格式化落盘。这一点与接口三<b>不同</b>（接口三的短窗聚合在 impl 内），
 * 原因是接口四的窗口语义是「近 W 历元、不含当历元」且依赖参考星掩码，
 * core 已持有该状态；impl 再算一份必然与推理侧错位。
 *
 * <b>时序契约：</b>特征行写入点与接口三相同（{@code aiDecideRefStar} 位于首轮 {@code zdres}
 * 之后、首次 {@code ddres} 之前），故落盘的是「本历元模型输入」。
 * 标签来自 {@code AI4/IS_REF} trace（当历元真实参考星），
 * 两者靠 {@code (run_id, epoch, sat, frq)} 对齐。
 *
 * 写盘失败不抛异常（{@code RefStarValidator} 契约要求不抛）：失败即停止写入并继续
 * 返回不干预，解算不受影响，仅丢失采集数据。
 */
public final class RecordingRefStarValidator implements RefStarValidator, AutoCloseable {

    /** CSV 表头：列序即写盘列序，与 {@code refstar_dataset} 的必需列一一对应。 */
    public static final String CSV_HEADER = "run_id,station,day,epoch,sat,frq,"
            + "f0,f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13,f14";

    private static final Logger LOG = LoggerFactory.getLogger(RecordingRefStarValidator.class);

    private final CollectConfig cfg;
    private final Writer out;
    private final StringBuilder line = new StringBuilder(256);

    private long rows;
    private long sinceFlush;
    private boolean writable = true;
    private boolean closed;

    RecordingRefStarValidator(CollectConfig cfg, Writer out) throws IOException {
        this.cfg = cfg;
        this.out = out;
        out.write(CSV_HEADER);
        out.write('\n');
        out.flush();
    }

    @Override
    public double[] refStarRisk(RefStarContext ctx) {
        if (ctx == null) {
            return null;
        }
        SatFeature[] sats = ctx.sats();
        int nsel = sats == null ? 0 : sats.length;
        double[] noIntervention = new double[nsel];
        if (nsel == 0) {
            return noIntervention;
        }
        synchronized (this) {
            if (!writable || closed) {
                return noIntervention;
            }
            try {
                writeRows(ctx);
            } catch (IOException e) {
                writable = false;
                LOG.error("接口四采集写盘失败，已停止采集（解算不受影响）: {}", cfg.refStarCsvPath(), e);
            } catch (RuntimeException e) {
                LOG.warn("接口四采集本历元失败（解算不受影响）: epoch={}, {}",
                        ctx.epoch(), e.toString());
            }
        }
        return noIntervention;
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

    private void writeRows(RefStarContext ctx) throws IOException {
        SatFeature[] sats = ctx.sats();
        int nf = Math.max(0, ctx.nf());
        for (int i = 0; i < sats.length; i++) {
            SatFeature sf = sats[i];
            for (int frq = 0; frq < nf; frq++) {
                double[] f = RefStarFeatures.extractRow(
                        sf, frq, ctx.snrWinMean()[i], ctx.snrWinStd()[i], ctx.refCount()[i]);
                line.setLength(0);
                line.append(cfg.runId()).append(',')
                        .append(cfg.station()).append(',')
                        .append(cfg.day()).append(',')
                        .append(ctx.epoch()).append(',')
                        .append(sf.sat()).append(',')
                        .append(frq);
                for (int k = 0; k < RefStarFeatures.DIM; k++) {
                    line.append(',').append(number(f[k]));
                }
                line.append('\n');
                out.write(line.toString());
                rows++;
            }
        }
        if (++sinceFlush >= cfg.flushEvery()) {
            out.flush();
            sinceFlush = 0;
        }
    }

    /** 不干预输出：全 0 风险分，长度必须等于行数（core 侧按 {@code risk.length == nsel} 校验）。 */
    static double[] zeros(int n) {
        double[] z = new double[Math.max(0, n)];
        Arrays.fill(z, 0.0);
        return z;
    }

    private static String number(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "0.0";
    }
}