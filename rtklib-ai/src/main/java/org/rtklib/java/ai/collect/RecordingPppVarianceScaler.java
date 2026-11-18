package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.feature.PppVarianceFeatures;
import org.rtklib.java.ai.spi.PppObservationContext;
import org.rtklib.java.ai.spi.PppSatFeature;
import org.rtklib.java.ai.spi.PppVarianceScaler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

/**
 * 采集型接口 B 实现：把每历元每 {@code (sat, frq)} 的 12 维特征、非差伪距残差与
 * 名义 {@code σ} 落盘成 CSV 一行，并恒输出「不干预」（全部 {@code 1.0}）。
 *
 * 恒输出 {@code 1.0} 的后果与 {@link RecordingObsCorrector} 同源：R 矩阵对角元不变，
 * 解算结果与不注入逐位一致，因此同一次运行既产出接口 B 的训练样本，又产出
 * 「无干预」的对照基线。
 *
 * {@code σ} 的唯一来源是 {@link PppObservationContext#nominalSigma()}（core 侧
 * R 对角元开方），本类不自己算、也不从特征反推：训练目标
 * {@code v_norm = resp / sigma} 必须与 core 实际施加的观测权重同一口径，否则
 * 学到的因子在推理时会系统性偏大或偏小。
 *
 * 非有限或非正的 {@code σ} 行直接跳过（不写占位值）：组装阶段对
 * {@code σ <= 0} 与非有限残差同样丢弃，两处口径一致，避免「训练有、推理没有」的错位。
 *
 * 写盘失败不抛异常（{@code PppVarianceScaler} 契约要求不抛）：失败即停止写入并继续
 * 返回不干预，解算不受影响，仅丢失采集数据。
 */
public final class RecordingPppVarianceScaler implements PppVarianceScaler, AutoCloseable {

    /** CSV 表头：列序即写盘列序，与 {@code ppp_variance_scaler/assemble.py} 的必需列对应。 */
    public static final String CSV_HEADER = "run_id,station,day,epoch,sat,frq,nf,"
            + "f0,f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,resp,sigma";

    private static final Logger LOG =
            LoggerFactory.getLogger(RecordingPppVarianceScaler.class);

    private final CollectConfig cfg;
    private final Writer out;
    private final StringBuilder line = new StringBuilder(256);

    private long rows;
    private long sinceFlush;
    private long skippedRows;
    private int lastEpoch = Integer.MIN_VALUE;
    private boolean writable = true;
    private boolean closed;

    RecordingPppVarianceScaler(CollectConfig cfg, Writer out) throws IOException {
        this.cfg = cfg;
        this.out = out;
        out.write(CSV_HEADER);
        out.write('\n');
        out.flush();
    }

    @Override
    public double[] varianceScale(PppObservationContext ctx) {
        if (ctx == null) {
            return null;
        }
        int ns = ctx.ns();
        int nf = ctx.nf();
        double[] noIntervention = new double[Math.max(0, ns) * Math.max(0, nf)];
        Arrays.fill(noIntervention, 1.0);
        PppSatFeature[] sats = ctx.sats();
        if (ns <= 0 || nf <= 0 || sats == null || sats.length != ns) {
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
                LOG.error("接口 B 采集写盘失败，已停止采集（解算不受影响）: {}",
                        cfg.pppScaleCsvPath(), e);
            }
        }
        return noIntervention;
    }

    /** 已落盘行数（不含表头）。 */
    public synchronized long rows() {
        return rows;
    }

    /** 因 {@code σ} 缺失/非正而跳过的行数。 */
    public synchronized long skippedRows() {
        return skippedRows;
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

    private void writeRows(PppObservationContext ctx) throws IOException {
        /* 同一历元重复调用只记一遍：core 侧每历元一次，trace 重放可能重复，
         * 重复行会让 assemble 判定 (epoch, sat, frq) 主键冲突并整 run 弃用。 */
        if (ctx.epoch() == lastEpoch) {
            return;
        }
        lastEpoch = ctx.epoch();

        PppSatFeature[] sats = ctx.sats();
        double[] sigma = ctx.nominalSigma();
        int ns = ctx.ns();
        int nf = ctx.nf();
        for (int i = 0; i < ns; i++) {
            PppSatFeature sf = sats[i];
            for (int frq = 0; frq < nf; frq++) {
                double[] f = PppVarianceFeatures.extractRaw(sf, frq);
                double s = (sigma != null && i * nf + frq < sigma.length)
                        ? sigma[i * nf + frq] : Double.NaN;
                double resp = f[PppVarianceFeatures.IDX_RESP];
                if (!Double.isFinite(s) || s <= 0.0 || !Double.isFinite(resp)) {
                    skippedRows++;
                    continue;
                }
                line.setLength(0);
                line.append(cfg.runId()).append(',')
                        .append(cfg.station()).append(',')
                        .append(cfg.day()).append(',')
                        .append(ctx.epoch()).append(',')
                        .append(sf.sat()).append(',')
                        .append(frq).append(',')
                        .append(nf);
                for (int k = 0; k < PppVarianceFeatures.DIM; k++) {
                    line.append(',').append(number(f[k]));
                }
                line.append(',').append(number(resp))
                        .append(',').append(number(s)).append('\n');
                out.write(line.toString());
                rows++;
            }
        }
        if (++sinceFlush >= cfg.flushEvery()) {
            out.flush();
            sinceFlush = 0;
        }
    }

    private static String number(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "0.0";
    }
}