package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.ObsCorrectorImpl;
import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.feature.ObsCorrectionFeatures;
import org.rtklib.java.ai.inference.BatchInference;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.SatFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;

/**
 * 采集型接口三实现：把每历元每 {@code (sat, frq)} 的 17 维特征落盘成 CSV 一行，
 * 并<b>恒输出「不干预」</b>（{@code bias = 0}、{@code varianceScale = 1}）。
 * <p>
 * 恒不干预的两个后果：
 * </p>
 * <ul>
 *   <li>core 侧 {@code ddres} 的残差、方差、{@code maxinno} 剔除行为与不注入逐位一致，
 *       故同一次运行既产出训练特征、又产出「无干预」的码残差基线（A 组）；</li>
 *   <li>不会产生 {@code AI3/AI_DEGRADE}，不会因为采集而改变解算结果。</li>
 * </ul>
 * <p>
 * <b>短窗状态必须由 {@link ObsCorrectorImpl} 维护，不得在本类重算。</b>
 * 短窗聚合（{@code f13~f16}）依赖三条重置规则（数据缺口 / 周跳 / 卫星消失），
 * 重写一份必然与推理侧错位，且错位是静默的：训练分布与推理分布不同而两边都不报错。
 * 因此本类内部持有一个 {@code ObsCorrectorImpl}，配一个恒输出 {@code (0, 1)} 的
 * 桩推理后端，让它跑完整的「重置 → 入窗 → 聚合」流程，再取它算出的聚合值。
 * </p>
 * <p>
 * <b>时序契约：</b>本类落盘的是「本历元模型输入」，标签（码残差）由同一历元的
 * {@code AI3/DDRES} trace 提供，两者靠 {@code (runId, epoch, sat, frq)} 对齐。
 * 因此特征行必须在 {@code ddres} 之前写入——core 的 {@code aiComputeCorrection}
 * 恰好位于迭代首轮 {@code zdres(rover)} 之后、首次 {@code ddres} 之前，天然满足。
 * </p>
 * <p>
 * 写盘失败不抛异常（{@code ObsCorrector} 契约要求不抛）：一旦失败即停止写入并
 * 继续返回不干预，解算不受影响，仅丢失采集数据。
 * </p>
 */
public final class RecordingObsCorrector implements ObsCorrector, AutoCloseable {

    /** CSV 表头：列序即写盘列序，与 {@code obs_dataset} 的必需列一一对应。 */
    public static final String CSV_HEADER = "run_id,station,day,epoch,sat,frq,"
            + "f0,f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11,f12,f13,f14,f15,f16";

    private static final Logger LOG = LoggerFactory.getLogger(RecordingObsCorrector.class);

    private final CollectConfig cfg;
    private final Writer out;
    private final ObsCorrectorImpl windowKeeper;
    private final StringBuilder line = new StringBuilder(256);

    private long rows;
    private long sinceFlush;
    private boolean writable = true;
    private boolean closed;

    RecordingObsCorrector(CollectConfig cfg, Writer out) throws IOException {
        this.cfg = cfg;
        this.out = out;
        this.windowKeeper = new ObsCorrectorImpl(
                new NoOpInference(), FeatureNorm.identity(ObsCorrectionFeatures.DIM));
        out.write(CSV_HEADER);
        out.write('\n');
        out.flush();
    }

    @Override
    public CodeCorrection correct(ObsCorrectionContext ctx) {
        if (ctx == null) {
            return CodeCorrection.NONE;
        }
        int rows = Math.max(0, ctx.ns()) * Math.max(0, ctx.nf());
        CodeCorrection noIntervention = noIntervention(rows);
        synchronized (this) {
            if (!writable || closed) {
                return noIntervention;
            }
            try {
                /* 先让窗口机跑完完整流程（重置 → 入窗），再取聚合值落盘：
                 * 顺序反了就会拿到「未含本历元样本」的窗口，f16 histLen 恒偏小。 */
                windowKeeper.correct(ctx);
                writeRows(ctx);
            } catch (IOException e) {
                writable = false;
                LOG.error("接口三采集写盘失败，已停止采集（解算不受影响）: {}", cfg.obsCsvPath(), e);
            } catch (RuntimeException e) {
                /* 窗口机内部异常（如上下文非法）不应污染后续历元：清空窗口后继续，
                 * 该历元特征缺失但解算仍按不干预推进。 */
                windowKeeper.reset();
                LOG.warn("接口三采集本历元失败，窗口已清空（解算不受影响）: epoch={}, {}",
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

    private void writeRows(ObsCorrectionContext ctx) throws IOException {
        SatFeature[] sats = ctx.sats();
        int ns = ctx.ns();
        int nf = ctx.nf();
        for (int i = 0; i < ns; i++) {
            SatFeature sf = sats[i];
            for (int frq = 0; frq < nf; frq++) {
                ObsCorrectionFeatures.WindowAgg agg = windowKeeper.windowAggregate(sf.sat(), frq);
                double[] f = ObsCorrectionFeatures.extractRow(sf, frq, agg);
                line.setLength(0);
                line.append(cfg.runId()).append(',')
                        .append(cfg.station()).append(',')
                        .append(cfg.day()).append(',')
                        .append(ctx.epoch()).append(',')
                        .append(sf.sat()).append(',')
                        .append(frq);
                for (int k = 0; k < ObsCorrectionFeatures.DIM; k++) {
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

    private static CodeCorrection noIntervention(int rows) {
        double[] scale = new double[rows];
        java.util.Arrays.fill(scale, 1.0);
        return new CodeCorrection(new double[rows], scale);
    }

    private static String number(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "0.0";
    }

    /**
     * 桩推理后端：输出恒为 {@code [0.0, 1.0]}（偏差 0、膨胀因子 1），
     * 只为了让 {@link ObsCorrectorImpl} 把短窗流程跑完整。
     */
    private static final class NoOpInference implements BatchInference {

        @Override
        public int inputDim() {
            return ObsCorrectionFeatures.DIM;
        }

        @Override
        public int outputDim() {
            return ObsCorrectorImpl.OUT_DIM;
        }

        @Override
        public float[] infer(float[] batch, int rows) {
            float[] out = new float[rows * ObsCorrectorImpl.OUT_DIM];
            for (int i = 0; i < rows; i++) {
                out[i * ObsCorrectorImpl.OUT_DIM + 1] = 1.0f;
            }
            return out;
        }
    }
}