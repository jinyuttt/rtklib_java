package org.rtklib.java.ai;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.feature.ObsCorrectionFeatures;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.SatFeature;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口三全链路验证：公开 RINEX 数据 + {@link OnnxAiProvider} + 真实 ONNX Runtime 推理。
 * <p>
 * 与 core 侧 {@code AiObsCorrectorE2ETest} 的分工：那边用桩验证「core 怎么用」，
 * 这边验证「rtklib-ai 怎么给」——特征提取、短窗维护、归一化、ONNX 批量推理、
 * 输出成对拆分、provider 组装与独立降级，全部走真实代码路径。
 * </p>
 * <p>
 * 模型为仓库内置的确定性 dummy（{@code bias = 0.1 * tanh(x @ W_b)}，
 * {@code scale = 1 + 0.5 * sigmoid(x @ W_s)}），输出天然满足接口三契约
 * （{@code bias} 有界、{@code scale ∈ [1, 1.5]}），用于验证流程而非精度。
 * </p>
 */
@DisplayName("接口三全链路：公开 RINEX + 真实 ONNX 推理")
@Tag("manual")
class ObsCorrectorPipelineE2ETest {

    private static final Logger log = LoggerFactory.getLogger(ObsCorrectorPipelineE2ETest.class);

    private static final String TD = TestDataConfig.getTestDataDir();
    private static final String ROVER = TD + "/rinex/urban_rover_20000719.17o";
    private static final String BASE = TD + "/rinex/urban_base_20000719.17o";
    private static final String NAV = TD + "/nav/urban_20000719.17p";

    /** 训练管道默认安全头饱和值（`train.py --bias-max` / `--scale-max`），须与 core 钳位一致。 */
    private static final double BIAS_MAX = 2.0;
    private static final double SCALE_MAX = 4.0;

    /** A2-2 的错误固定判据：水平误差超过该值即记一次「错误固定」。 */
    private static final double FALSE_FIX_H = 0.5;

    private static Path obsModel;
    private static Path zeroModel;
    private static boolean avail;
    private static RtkProcessor.RtkResult baseline;

    @BeforeAll
    static void setup() throws URISyntaxException {
        avail = Files.exists(Paths.get(ROVER)) && Files.exists(Paths.get(BASE)) && Files.exists(Paths.get(NAV));
        obsModel = resource("/models/dummy_obs_corrector.onnx");
        zeroModel = resource("/models/dummy_zero_corrector.onnx");
        if (!avail || obsModel == null) {
            log.warn("公开数据或 dummy 模型缺失，跳过接口三全链路验证");
            return;
        }
        AiHooks.clear();
        baseline = RinexRtkProcessor.processRinex(ROVER, BASE, NAV, opt());
        log.info("baseline（未注入 AI）: total={} success={} fail={}",
                baseline.totalEpochs, baseline.successCount, baseline.failCount);
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = ObsCorrectorPipelineE2ETest.class.getResource(name);
        if (url == null) return null;
        Path p = Paths.get(url.toURI());
        return Files.isRegularFile(p) ? p : null;
    }

    @AfterEach
    void clearHook() {
        AiHooks.clear();
    }

    @AfterAll
    static void clearAfterAll() {
        AiHooks.clear();
    }

    private static PrcOpt opt() {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        return opt;
    }

    @Test
    @DisplayName("全链路：provider 组装 → 特征 → 短窗 → ONNX 批量推理 → core 施加")
    void testFullPipeline() {
        Assumptions.assumeTrue(avail && obsModel != null, "公开数据或 dummy 模型缺失");

        try (OnnxAiProvider provider = new OnnxAiProvider(null, null, obsModel, null)) {
            assertTrue(provider.isObsCorrectorAvailable(), "接口三应加载成功");
            ObsCorrector inner = provider.obsCorrector();
            assertNotNull(inner, "obsCorrector() 不应为 null");
            assertTrue(inner instanceof ObsCorrectorImpl, "应为 ObsCorrectorImpl，实际 " + inner.getClass());

            Recorder rec = new Recorder(inner, 0.1, 1.5);
            AiHooks.install(new AiProvider() {
                @Override
                public FixValidator fixValidator() {
                    return null;
                }

                @Override
                public ObsCorrector obsCorrector() {
                    return rec;
                }
            });

            RtkProcessor.RtkResult r = RinexRtkProcessor.processRinex(ROVER, BASE, NAV, opt());

            assertTrue(rec.calls > 0, "接口三应至少被调用一次");
            assertEquals(0, rec.badLength, "每历元输出长度必须等于 ns*nf");
            assertEquals(0, rec.nonFinite, "模型输出必须全为有限值");
            assertEquals(0, rec.badScale, "方差膨胀因子必须 ∈ [1.0, 1.5]，实际越界 " + rec.badScale + " 行");
            assertEquals(0, rec.badBias, "偏差必须 ∈ [-0.1, 0.1]，实际越界 " + rec.badBias + " 行");
            assertEquals(1, rec.seenNf, "单频配置下 ctx.nf 必须为 1");
            assertTrue(rec.maxNs >= 4, "参与双差的卫星数应 ≥ 4，实际 " + rec.maxNs);

            int windows = ((ObsCorrectorImpl) inner).windowSize();
            assertTrue(windows > 0, "短窗状态应在解算结束后仍持有条目，实际 " + windows);

            assertEquals(baseline.totalEpochs, r.totalEpochs, "历元总数必须一致");
            assertTrue(r.successCount > 0, "注入接口三后仍应有解");
            assertFinite(r);

            DiffStats ds = diffStats(baseline, r);
            log.info("全链路完成：接口三调用 {} 次，短窗条目 {}，逐历元 ECEF 变化 median={} m p95={} m max={} m，"
                            + ">1m 的历元 {} 个，success={}",
                    rec.calls, windows, String.format("%.4f", ds.median),
                    String.format("%.4f", ds.p95), String.format("%.4f", ds.max),
                    ds.over1m, r.successCount);
            assertTrue(ds.median < 1.0, "中位历元的解算变化应小于 1 m，实际 " + ds.median + " m");
            assertTrue(ds.p95 < 20.0, "95% 历元的解算变化应小于 20 m，实际 " + ds.p95 + " m");
        }
    }

    @Test
    @DisplayName("零改正模型（bias=0、scale=1）：整条链路对解算零影响，与基线逐位一致")
    void testZeroCorrectionModelIsBitIdentical() {
        Assumptions.assumeTrue(avail && zeroModel != null, "公开数据或零改正模型缺失");

        try (OnnxAiProvider provider = new OnnxAiProvider(null, null, zeroModel, null)) {
            assertTrue(provider.isObsCorrectorAvailable(), "零改正模型应加载成功");
            AiHooks.install(provider);
            RtkProcessor.RtkResult r = RinexRtkProcessor.processRinex(ROVER, BASE, NAV, opt());

            assertEquals(baseline.totalEpochs, r.totalEpochs, "历元总数必须一致");
            assertEquals(baseline.successCount, r.successCount, "成功历元数必须一致");
            assertEquals(0.0, maxEcefDiff(baseline, r),
                    "bias=0/scale=1 时必须与未注入 AI 的解算逐位一致（特征→推理→施加链路零影响）");
            log.info("零改正模型通过：全链路对解算零影响，与基线逐位一致");
        }
    }

    @Test
    @DisplayName("独立降级：接口三模型缺失时 provider 只关闭接口三，解算与基线逐位一致")
    void testMissingModelDegradesToNoIntervention() {
        Assumptions.assumeTrue(avail, "公开数据缺失");

        try (OnnxAiProvider provider = new OnnxAiProvider(null, null,
                Paths.get(TD, "no_such_dir/obs_corrector.onnx"), null)) {
            assertFalse(provider.isObsCorrectorAvailable(), "模型缺失时接口三必须不可用");
            assertNull(provider.obsCorrector(), "模型缺失时 obsCorrector() 必须为 null");
            AiHooks.install(provider);
            RtkProcessor.RtkResult r = RinexRtkProcessor.processRinex(ROVER, BASE, NAV, opt());

            assertEquals(baseline.totalEpochs, r.totalEpochs, "历元总数必须一致");
            assertEquals(baseline.successCount, r.successCount, "成功历元数必须一致");
            assertEquals(0.0, maxEcefDiff(baseline, r), "接口三不可用时必须与基线逐位一致（I-1）");
            log.info("独立降级通过：接口三模型缺失，解算结果与基线逐位一致");
        }
    }

    @Test
    @DisplayName("训练管道产物：加载 training/out 导出的模型跑公开数据（产物缺失则跳过）")
    void testTrainedModelFromPipeline() {
        Path model = Paths.get("training", "out", "obs_corrector.onnx");
        Path norm = Paths.get("training", "out", "obs_corrector.norm.json");
        Assumptions.assumeTrue(avail && Files.isRegularFile(model),
                "训练产物不存在，先执行 python -m training.obs_corrector.train --demo");

        try (OnnxAiProvider provider = new OnnxAiProvider(null, null, model,
                Files.isRegularFile(norm) ? norm : null)) {
            assertTrue(provider.isObsCorrectorAvailable(), "训练产物应能被 provider 加载");
            ObsCorrector inner = provider.obsCorrector();
            assertNotNull(inner, "训练产物加载后必须提供 ObsCorrector");

            Recorder rec = new Recorder(inner, BIAS_MAX, SCALE_MAX);
            AiHooks.install(new AiProvider() {
                @Override
                public FixValidator fixValidator() {
                    return null;
                }

                @Override
                public ObsCorrector obsCorrector() {
                    return rec;
                }
            });

            RtkProcessor.RtkResult r1 = RinexRtkProcessor.processRinex(ROVER, BASE, NAV, opt());
            assertEquals(baseline.totalEpochs, r1.totalEpochs, "历元总数必须一致");
            assertTrue(r1.successCount > 0, "注入训练产物后仍应有解");
            assertFinite(r1);

            assertTrue(rec.calls > 0, "训练产物应真正被调用，实际 0 次");
            assertEquals(0, rec.badLength, "每历元输出长度必须等于 ns*nf");
            assertEquals(0, rec.nonFinite, "模型输出必须全为有限值");
            assertEquals(0, rec.badBias, "bias 必须落在训练饱和区间 ±" + BIAS_MAX + " m 内，越界 " + rec.badBias + " 行");
            assertEquals(0, rec.badScale, "scale 必须落在 [1.0, " + SCALE_MAX + "]，越界 " + rec.badScale + " 行");

            DiffStats ds = diffStats(baseline, r1);
            log.info("训练产物推理完成：调用 {} 次，配对历元 {}，max|bias|={} m，"
                            + "逐历元 ECEF 变化 median={} m p95={} m max={} m，success={}",
                    rec.calls, ds.matched, String.format("%.4f", rec.maxAbsBias),
                    String.format("%.4f", ds.median), String.format("%.4f", ds.p95),
                    String.format("%.4f", ds.max), r1.successCount);
            log.warn("注意：该模型由「仅演示」数据集训练（§6.3），特征与真实 RINEX 存在分布差异，"
                    + "解算变化量大属预期，本用例只验证链路与契约，不判定精度");

            assertTrue(ds.median > 0.0,
                    "训练产物必须真正生效（改正被施加），实际 median=" + ds.median + " m");

            RtkProcessor.RtkResult r2 = RinexRtkProcessor.processRinex(ROVER, BASE, NAV, opt());
            assertEquals(0.0, maxEcefDiff(r1, r2), "同一模型两次解算必须逐位一致（可复现）");
            log.info("可复现性通过：同一训练产物两次解算逐位一致");
        }
    }

    @Test
    @DisplayName("A2 配对验收：固定率 / 错误固定率 / 位置 RMS / ns 四项指标（C 组 vs A 组同历元配对）")
    void testA2PairedAcceptanceMetrics() {
        Assumptions.assumeTrue(avail, "公开数据缺失");
        Path model = Paths.get("training", "out", "obs_corrector.onnx");
        Path norm = Paths.get("training", "out", "obs_corrector.norm.json");
        Assumptions.assumeTrue(Files.isRegularFile(model),
                "训练产物不存在，先执行 python -m training.obs_corrector.train --demo");

        /* A 组：不注入任何接口三（core 原生分支）。
         * 必须用**带 trace 的同一次运行**作为配对基准：baseline 字段来自另一次运行，
         * 若按时间戳配对仍能对齐，但 ns/Q 只能从 trace 拿到，故这里重跑一次。 */
        Map<String, EpochRec> groupA = runWithTrace(null);
        assertEquals(baseline.totalEpochs, groupA.size(), "POSITION trace 行数必须等于历元总数");

        Map<String, EpochRec> groupC;
        DistCorrector dist = null;
        try (OnnxAiProvider provider = new OnnxAiProvider(null, null, model,
                Files.isRegularFile(norm) ? norm : null)) {
            assertTrue(provider.isObsCorrectorAvailable(), "训练产物应能被 provider 加载");
            dist = new DistCorrector(provider.obsCorrector());
            groupC = runWithTrace(wrap(dist));
        }

        Map<String, Truth> truth = loadTruth();
        assertFalse(truth.isEmpty(), "参考真值文件缺失，A2 的错误固定率/RMS 无法判定");

        A2Metrics a = metrics(groupA, truth, baseline.totalEpochs);
        A2Metrics c = metrics(groupC, truth, baseline.totalEpochs);
        PairDiff diff = pairDiff(groupA, groupC);

        log.info("A2 判定（A 组=未注入，C 组=训练产物注入，同一 opt/同一 RINEX）");
        log.info("  A2-1 固定率：A={} ({}) → C={} ({}), Δ={} pp（判据 ≤ +1.0 pp 下降）",
                a.fix, percent(a.fixRate()), c.fix, percent(c.fixRate()),
                String.format("%.3f", (a.fixRate() - c.fixRate()) * 100));
        log.info("  A2-2 错误固定率（水平误差 > {} m 记错）：A={}/{}={} → C={}/{}={}",
                FALSE_FIX_H, a.falseFix, a.fix, percent(a.falseFixRate()),
                c.falseFix, c.fix, percent(c.falseFixRate()));
        log.info("  A2-3 位置 RMS（FIX+FLOAT 对参考真值，{} 个配对历元）："
                        + "A: H={} m V={} m → C: H={} m V={} m",
                a.rmsEpochs, m(a.rmsH), m(a.rmsV), m(c.rmsH), m(c.rmsV));
        log.info("  A2-4 ns：A 均值={} → C 均值={}，配对 {} 历元中 ns 减少 {} 个、增加 {} 个",
                String.format("%.3f", a.meanNs), String.format("%.3f", c.meanNs),
                diff.paired, diff.nsDown, diff.nsUp);
        log.info("  A2-4 解状态迁移：success 减少 {} 个历元、增加 {} 个历元（A success={}, C success={}）",
                diff.lost, diff.gained, a.solved, c.solved);

        /* 饱和体检：若大量行顶在 ±BIAS_MAX、且 scale 全钉在 1.0，说明两个输出头都已在
         * 分布外塌缩，「A2 不通过」反映的是模型失效，而不是改正能力不足。 */
        if (dist != null) {
            dist.log("真实 RINEX 上的模型输出分布（C 组同一次运行，不额外解算）");
            double[][] nm = loadNorm(norm);
            dist.logFeatures("真实 RINEX 上的原始特征分布 vs 训练集归一化参数",
                    nm == null ? null : nm[0], nm == null ? null : nm[1]);
        }

        /* 逐历元归因：success 从 A 掉到 C 的历元必须能说清「为什么」。 */
        for (String g : diff.lostEpochs) {
            EpochRec ra = groupA.get(g);
            EpochRec rc = groupC.get(g);
            log.warn("  [归因] gpst={} A(Q={}, ns={}) → C(Q={}, ns={})",
                    g, ra.q, ra.ns, rc.q, rc.ns);
        }
        for (String g : diff.gainedEpochs) {
            EpochRec ra = groupA.get(g);
            EpochRec rc = groupC.get(g);
            log.info("  [补偿] gpst={} A(Q={}, ns={}) → C(Q={}, ns={})",
                    g, ra.q, ra.ns, rc.q, rc.ns);
        }

        /* 契约先立住：两组必须配对到同样的历元集合，否则后面的统计没有意义。 */
        assertEquals(groupA.size(), groupC.size(), "A/C 两组历元集合必须一致");
        assertEquals(a.solved - diff.lost, diff.paired,
                "配对历元数必须等于「A 有解且 C 也有解」的历元数");
        assertEquals(c.solved - diff.gained, diff.paired,
                "配对历元数必须等于「C 有解且 A 也有解」的历元数");

        /* 饱和归因（诊断，不参与 A2 判据）：把 bias 与 scale 拆开各跑一次，
         * 判断「固定率下降」来自偏差改正还是方差膨胀，为后续修模型提供依据。 */
        try (OnnxAiProvider provider = new OnnxAiProvider(null, null, model,
                Files.isRegularFile(norm) ? norm : null)) {
            ObsCorrector inner = provider.obsCorrector();
            if (inner != null) {
                for (Variant v : new Variant[]{Variant.BIAS_ONLY, Variant.SCALE_ONLY}) {
                    A2Metrics vm = metrics(runWithTrace(wrap(new VariantCorrector(inner, v))),
                            truth, baseline.totalEpochs);
                    log.info("  [归因] {}：固定 {} ({})，错误固定率 {}，RMS H={} m V={} m，ns 均值 {}",
                            v, vm.fix, percent(vm.fixRate()), percent(vm.falseFixRate()),
                            m(vm.rmsH), m(vm.rmsV), String.format("%.3f", vm.meanNs));
                }
            }
        }

        /* A2 判据（§7.5.2）：四项同时成立才算「没有明显变差」。 */
        assertTrue((a.fixRate() - c.fixRate()) * 100 <= 1.0,
                "A2-1 不通过：固定率下降超过 1.0 pp，实际 " + String.format("%.3f", (a.fixRate() - c.fixRate()) * 100));
        assertTrue(c.falseFixRate() <= a.falseFixRate() + 1e-9,
                "A2-2 不通过：错误固定率上升，A=" + percent(a.falseFixRate()) + " C=" + percent(c.falseFixRate()));
        assertTrue(c.rmsH <= a.rmsH * 1.02,
                "A2-3 不通过：水平 RMS 恶化，A=" + m(a.rmsH) + " m C=" + m(c.rmsH) + " m");
        assertTrue(c.rmsV <= a.rmsV * 1.02,
                "A2-3 不通过：垂直 RMS 恶化，A=" + m(a.rmsV) + " m C=" + m(c.rmsV) + " m");
        assertTrue(c.meanNs >= a.meanNs - 0.5,
                "A2-4 不通过：ns 显著减少，A=" + String.format("%.3f", a.meanNs)
                        + " C=" + String.format("%.3f", c.meanNs));
    }

    /** 归因变体：只留偏差 / 只留方差膨胀，用于定位饱和模型到底伤在哪一路。 */
    private enum Variant {
        FULL("FULL"),
        BIAS_ONLY("BIAS_ONLY"),
        SCALE_ONLY("SCALE_ONLY");

        private final String label;

        Variant(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class VariantCorrector implements ObsCorrector {

        private final ObsCorrector delegate;
        private final Variant variant;

        VariantCorrector(ObsCorrector delegate, Variant variant) {
            this.delegate = delegate;
            this.variant = variant;
        }

        @Override
        public CodeCorrection correct(ObsCorrectionContext ctx) {
            CodeCorrection cc = delegate.correct(ctx);
            if (cc == null || cc.biasMeters() == null || cc.varianceScale() == null) return cc;
            double[] bias = cc.biasMeters().clone();
            double[] scale = cc.varianceScale().clone();
            if (variant == Variant.BIAS_ONLY) java.util.Arrays.fill(scale, 1.0);
            if (variant == Variant.SCALE_ONLY) java.util.Arrays.fill(bias, 0.0);
            return new CodeCorrection(bias, scale);
        }
    }

    /** 把单个 {@link ObsCorrector} 包成 {@link AiProvider}，只装接口三、不装接口一。 */
    private static AiProvider wrap(ObsCorrector oc) {
        return new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return null;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return oc;
            }
        };
    }

    private static String percent(double v) {
        return String.format("%.2f%%", v * 100);
    }

    private static String m(double v) {
        return String.format("%.4f", v);
    }

    private static void assertFinite(RtkProcessor.RtkResult r) {
        int n = 0;
        for (SolData sd : r.solutions) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p == null) continue;
            assertTrue(Double.isFinite(p.v1) && Double.isFinite(p.v2) && Double.isFinite(p.v3),
                    "解算位置必须为有限值：" + sd.timeStr);
            n++;
        }
        assertTrue(n > 0, "应至少有解");
    }

    /**
     * 用**现有** POSITION trace 采集逐历元 (Q, ns, ECEF)。
     * <p>
     * 为什么复用 trace 而不改 {@code RtkResult}：{@code RtkResult.solutions} 只保存成功历元，
     * 失败历元的 ns/Q 不在其中，而 A2-4 的「success −1/−2 归因」恰恰需要失败历元的 ns。
     * POSITION 在 rtkpos 尾部无条件 emit（含 stat=NONE 的历元），是现成的逐历元输出。
     * </p>
     */
    private static Map<String, EpochRec> runWithTrace(AiProvider provider) {
        List<String> lines = new java.util.ArrayList<>();
        org.rtklib.java.trace.TraceConfig cfg = new org.rtklib.java.trace.TraceConfig();
        cfg.enabled = true;
        cfg.topics.add("POSITION");

        RinexRtkProcessor p = new RinexRtkProcessor(opt());
        p.setTraceConfig(cfg);
        p.setTraceCallback(lines::add);

        AiHooks.clear();
        if (provider != null) AiHooks.install(provider);
        try {
            p.process(ROVER, BASE, NAV);
        } finally {
            AiHooks.clear();
        }

        Map<String, EpochRec> out = new java.util.LinkedHashMap<>();
        for (String line : lines) {
            Map<String, String> kv = traceKv(line);
            if (!"POSITION".equals(kv.get("topic")) || !"UPDATE".equals(kv.get("action"))) continue;
            String gpst = kv.get("gpst");
            if (gpst == null) continue;
            out.put(gpst, new EpochRec(gpst,
                    (int) Math.round(num(kv.get("Q"))),
                    (int) Math.round(num(kv.get("ns"))),
                    num(kv.get("x")), num(kv.get("y")), num(kv.get("z"))));
        }
        return out;
    }

    private static Map<String, String> traceKv(String line) {
        Map<String, String> kv = new java.util.HashMap<>();
        for (String part : line.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq > 0) kv.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return kv;
    }

    private static double num(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** 参考真值：{@code reference/urban_rtk_20000719.pos}，键为 GPS 周内秒（与 trace 的 gpst 一致）。 */
    private record Truth(double latDeg, double lonDeg, double h, int q) {
    }

    private static Map<String, Truth> loadTruth() {
        Path ref = Paths.get(TD, "reference", "urban_rtk_20000719.pos");
        Map<String, Truth> m = new java.util.HashMap<>();
        if (!Files.isRegularFile(ref)) return m;
        try {
            for (String line : Files.readAllLines(ref)) {
                if (line.startsWith("%")) continue;
                String[] p = line.trim().split("\\s+");
                if (p.length < 6) continue;
                m.put(p[1], new Truth(Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Double.parseDouble(p[4]), (int) Double.parseDouble(p[5])));
            }
        } catch (Exception e) {
            log.warn("参考真值解析失败：{}", e.getMessage());
        }
        return m;
    }

    /** 单历元记录（Q 用 SOLQ_* 码：1=FIX、2=FLOAT、0=NONE）。 */
    private record EpochRec(String gpst, int q, int ns, double x, double y, double z) {
        boolean fixed() {
            return q == Constants.SOLQ_FIX;
        }

        boolean solved() {
            return q == Constants.SOLQ_FIX || q == Constants.SOLQ_FLOAT;
        }
    }

    /** 单组 A2 统计。 */
    private record A2Metrics(int total, int fix, int falseFix, int rmsEpochs,
                             double rmsH, double rmsV, double meanNs, int solved) {
        double fixRate() {
            return total > 0 ? (double) fix / total : 0.0;
        }

        double falseFixRate() {
            return fix > 0 ? (double) falseFix / fix : 0.0;
        }
    }

    private static A2Metrics metrics(Map<String, EpochRec> recs, Map<String, Truth> truth, int total) {
        int fix = 0, falseFix = 0, rmsN = 0, solved = 0;
        double sumH2 = 0.0, sumV2 = 0.0, sumNs = 0.0;
        for (EpochRec r : recs.values()) {
            sumNs += r.ns;
            if (r.solved()) solved++;
            if (r.fixed()) fix++;
            Truth t = truth.get(r.gpst);
            if (t == null) continue;
            double[] d = neuError(r, t);
            if (r.fixed() && Math.sqrt(d[0] * d[0] + d[1] * d[1]) > FALSE_FIX_H) falseFix++;
            if (r.solved()) {
                sumH2 += d[0] * d[0] + d[1] * d[1];
                sumV2 += d[2] * d[2];
                rmsN++;
            }
        }
        double rmsH = rmsN > 0 ? Math.sqrt(sumH2 / rmsN) : 0.0;
        double rmsV = rmsN > 0 ? Math.sqrt(sumV2 / rmsN) : 0.0;
        double meanNs = recs.isEmpty() ? 0.0 : sumNs / recs.size();
        return new A2Metrics(total, fix, falseFix, rmsN, rmsH, rmsV, meanNs, solved);
    }

    /** ECEF → 相对真值的 (dN, dE, dU)，米。 */
    private static double[] neuError(EpochRec r, Truth t) {
        double[] ecef = {r.x, r.y, r.z};
        double[] llh = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(ecef, llh);
        double lat = llh[0] * Constants.R2D;
        double lon = llh[1] * Constants.R2D;
        double dN = (lat - t.latDeg) * Constants.RE_WGS84 * Constants.D2R;
        double dE = (lon - t.lonDeg) * Constants.RE_WGS84
                * Math.cos(t.latDeg * Constants.D2R) * Constants.D2R;
        return new double[]{dN, dE, llh[2] - t.h};
    }

    /** A/C 配对差异：解状态迁移与 ns 变化，附逐历元归因清单。 */
    private record PairDiff(int paired, int nsDown, int nsUp, int lost, int gained,
                            List<String> lostEpochs, List<String> gainedEpochs) {
    }

    private static PairDiff pairDiff(Map<String, EpochRec> a, Map<String, EpochRec> c) {
        int paired = 0, nsDown = 0, nsUp = 0, lost = 0, gained = 0;
        List<String> lostEpochs = new java.util.ArrayList<>();
        List<String> gainedEpochs = new java.util.ArrayList<>();
        for (Map.Entry<String, EpochRec> e : a.entrySet()) {
            EpochRec rc = c.get(e.getKey());
            if (rc == null) continue;
            EpochRec ra = e.getValue();
            if (ra.solved() && rc.solved()) paired++;
            if (rc.ns < ra.ns) nsDown++;
            if (rc.ns > ra.ns) nsUp++;
            if (ra.solved() && !rc.solved()) {
                lost++;
                lostEpochs.add(e.getKey());
            }
            if (!ra.solved() && rc.solved()) {
                gained++;
                gainedEpochs.add(e.getKey());
            }
        }
        return new PairDiff(paired, nsDown, nsUp, lost, gained, lostEpochs, gainedEpochs);
    }

    private static double maxEcefDiff(RtkProcessor.RtkResult a, RtkProcessor.RtkResult b) {
        return diffStats(a, b).max;
    }

    /** 逐历元 ECEF 变化的分布统计（仅统计双方都有解的历元）。 */
    private record DiffStats(int matched, double median, double p95, double max, int over1m) {
    }

    /**
     * 按**历元时刻**配对比较两次解算。
     * <p>
     * 不能按 list 下标配对：{@code RtkResult.solutions} 只保存成功历元，
     * 两次运行的失败历元不同（哪怕只差 1 个）就会让后续所有下标错位，
     * 于是「变化量」变成两个不相同时刻的差，量级完全失真。
     * </p>
     */
    private static DiffStats diffStats(RtkProcessor.RtkResult a, RtkProcessor.RtkResult b) {
        java.util.Map<String, Position> ref = new java.util.HashMap<>();
        for (SolData sd : b.solutions) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) ref.put(sd.timeStr, p);
        }
        double[] diffs = new double[a.solutions.size()];
        int cnt = 0;
        for (SolData sd : a.solutions) {
            Position pa = sd.getPosition(CoordType.ECEF);
            Position pb = ref.get(sd.timeStr);
            if (pa == null || pb == null) continue;
            double d1 = pa.v1 - pb.v1;
            double d2 = pa.v2 - pb.v2;
            double d3 = pa.v3 - pb.v3;
            diffs[cnt++] = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
        }
        if (cnt == 0) return new DiffStats(0, 0.0, 0.0, 0.0, 0);
        double[] sorted = java.util.Arrays.copyOf(diffs, cnt);
        java.util.Arrays.sort(sorted);
        int over1 = 0;
        for (double d : sorted) if (d > 1.0) over1++;
        return new DiffStats(cnt, sorted[(int) Math.round(0.5 * (cnt - 1))],
                sorted[(int) Math.round(0.95 * (cnt - 1))],
                sorted[cnt - 1], over1);
    }

    /** 只统计、不改值：采集模型在真实数据上的 bias/scale 分布与原始特征分布，用于判定「饱和」。 */
    private static final class DistCorrector implements ObsCorrector {

        private static final String[] FEATURE_NAMES = {
                "el", "az", "snrRover", "snrBase", "resp", "resc", "lock", "slip", "half",
                "gf", "vsat", "fix", "sysId", "respWinMean", "respWinStd", "rescWinMean", "histLen"
        };

        private final ObsCorrector delegate;
        private double[] bias = new double[1024];
        private double[] scale = new double[1024];
        private int n;

        private final double[] fSum = new double[ObsCorrectionFeatures.DIM];
        private final double[] fSqSum = new double[ObsCorrectionFeatures.DIM];
        private final double[] fMin = new double[ObsCorrectionFeatures.DIM];
        private final double[] fMax = new double[ObsCorrectionFeatures.DIM];
        /** 非零行数：区分「该维在真实链路上结构性恒 0」与「偶发非零」。 */
        private final long[] fNonZero = new long[ObsCorrectionFeatures.DIM];
        private long fN;
        private final double[][] samples = new double[3][ObsCorrectionFeatures.DIM];
        private int nSamples;
        /** 首历元逐星快照：直接回答「核心侧取数对不对」，不受全局统计掩盖。 */
        private String firstEpochDump;

        DistCorrector(ObsCorrector delegate) {
            this.delegate = delegate;
            java.util.Arrays.fill(fMin, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(fMax, Double.NEGATIVE_INFINITY);
        }

        @Override
        public CodeCorrection correct(ObsCorrectionContext ctx) {
            CodeCorrection cc = delegate.correct(ctx);
            if (cc == null || cc.biasMeters() == null || cc.varianceScale() == null
                    || cc.biasMeters().length != ctx.ns() * ctx.nf()) return cc;
            int need = n + cc.biasMeters().length;
            if (need > bias.length) {
                int cap = bias.length;
                while (cap < need) cap <<= 1;
                bias = java.util.Arrays.copyOf(bias, cap);
                scale = java.util.Arrays.copyOf(scale, cap);
            }
            System.arraycopy(cc.biasMeters(), 0, bias, n, cc.biasMeters().length);
            System.arraycopy(cc.varianceScale(), 0, scale, n, cc.varianceScale().length);
            n = need;

            /* 原始特征分布：f13~f16 依赖 impl 内部短窗，故必须取 impl 本历元真正用到的
             * 聚合（delegate.correct 已先入窗），用 EMPTY 会把 f13~f16 人为压成 0。 */
            boolean first = fN == 0;
            ObsCorrectorImpl impl = delegate instanceof ObsCorrectorImpl
                    ? (ObsCorrectorImpl) delegate : null;
            StringBuilder dump = first ? new StringBuilder() : null;
            for (SatFeature sf : ctx.sats()) {
                for (int frq = 0; frq < ctx.nf(); frq++) {
                    ObsCorrectionFeatures.WindowAgg agg = impl == null
                            ? ObsCorrectionFeatures.WindowAgg.EMPTY : impl.windowAggregate(sf.sat(), frq);
                    double[] raw = ObsCorrectionFeatures.extractRow(sf, frq, agg);
                    for (int d = 0; d < ObsCorrectionFeatures.DIM; d++) {
                        fSum[d] += raw[d];
                        fSqSum[d] += raw[d] * raw[d];
                        fMin[d] = Math.min(fMin[d], raw[d]);
                        fMax[d] = Math.max(fMax[d], raw[d]);
                        if (Math.abs(raw[d]) > 1e-9) fNonZero[d]++;
                    }
                    if (dump != null) {
                        dump.append(String.format("  epoch=%d sat=%d(%d) frq=%d el=%.2f° az=%.2f°"
                                        + " snrR=%.1f snrB=%.1f resp=%.4f resc=%.4f lock=%d"
                                        + " slip=%d half=%d gf=%.4f vsat=%d fix=%d"
                                        + " win(respMean=%.4f respStd=%.4f rescMean=%.4f n=%d)%n",
                                ctx.epoch(), sf.sat(), sf.sys(), frq, raw[0], raw[1],
                                raw[2], raw[3], raw[4], raw[5], (int) raw[6],
                                (int) raw[7], (int) raw[8], raw[9], (int) raw[10], (int) raw[11],
                                raw[13], raw[14], raw[15], agg.n()));
                    }
                    if (nSamples < samples.length) {
                        System.arraycopy(raw, 0, samples[nSamples++], 0, ObsCorrectionFeatures.DIM);
                    }
                    fN++;
                }
            }
            if (dump != null) firstEpochDump = dump.toString();
            return cc;
        }

        void log(String title) {
            if (n == 0) return;
            double[] b = java.util.Arrays.copyOf(bias, n);
            double[] s = java.util.Arrays.copyOf(scale, n);
            java.util.Arrays.sort(b);
            java.util.Arrays.sort(s);
            int bSat = 0, bZero = 0, sOne = 0, sMax = 0;
            for (double v : b) {
                if (Math.abs(v) >= BIAS_MAX * 0.99) bSat++;
                if (Math.abs(v) < 1e-3) bZero++;
            }
            for (double v : s) {
                if (Math.abs(v - 1.0) < 1e-3) sOne++;
                if (v >= SCALE_MAX * 0.99) sMax++;
            }
            log.info("{}：{} 行", title, n);
            log.info("  bias   p01={} p50={} p99={} | 顶在 ±{} m 的行 {} ({}), 近似 0 的行 {} ({})",
                    m(b[(int) Math.round(0.01 * (n - 1))]), m(b[(int) Math.round(0.50 * (n - 1))]),
                    m(b[(int) Math.round(0.99 * (n - 1))]), BIAS_MAX,
                    bSat, percent((double) bSat / n), bZero, percent((double) bZero / n));
            log.info("  scale  p01={} p50={} p99={} | 钉在 1.0 的行 {} ({}), 顶在 {} 的行 {} ({})",
                    m(s[(int) Math.round(0.01 * (n - 1))]), m(s[(int) Math.round(0.50 * (n - 1))]),
                    m(s[(int) Math.round(0.99 * (n - 1))]),
                    sOne, percent((double) sOne / n), SCALE_MAX,
                    sMax, percent((double) sMax / n));
        }

        /** 打印真实特征分布，与训练集归一化参数对照，定位「分布外」的维度。 */
        void logFeatures(String title, double[] normMean, double[] normStd) {
            if (fN == 0) return;
            log.info("{}（{} 行）：", title, fN);
            for (int d = 0; d < ObsCorrectionFeatures.DIM; d++) {
                double mean = fSum[d] / fN;
                double var = Math.max(fSqSum[d] / fN - mean * mean, 0.0);
                double std = Math.sqrt(var);
                String z = "";
                if (normStd != null && normStd[d] > 1e-8) {
                    z = String.format(" | 训练 mean=%s std=%s → 均值偏移 z=%s",
                            m(normMean[d]), m(normStd[d]), m((mean - normMean[d]) / normStd[d]));
                }
                log.info(String.format("  f%-2d %-12s real mean=%.4f std=%.4f min=%.4f max=%.4f nz=%.1f%%%s",
                        d, FEATURE_NAMES[d], mean, std, fMin[d], fMax[d],
                        100.0 * fNonZero[d] / fN, z));
            }
            if (firstEpochDump != null) {
                log.info("  首历元逐星取数快照（f0~f16 = core 原始快照 + impl 实窗聚合）:\n{}",
                        firstEpochDump);
            }
            for (int s = 0; s < nSamples; s++) {
                StringBuilder sb = new StringBuilder("  样本行 " + s + ": ");
                for (int d = 0; d < ObsCorrectionFeatures.DIM; d++) {
                    sb.append(FEATURE_NAMES[d]).append('=').append(m(samples[s][d]).replace("-", "⁻")).append(' ');
                }
                log.info(sb.toString());
            }
        }
    }

    /** 读 ``norm.json`` 的 mean/std（只做最小解析，测试用）。 */
    private static double[][] loadNorm(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            String text = Files.readString(p);
            double[] mean = parseJsonArray(text, "mean");
            double[] std = parseJsonArray(text, "std");
            return (mean == null || std == null) ? null : new double[][]{mean, std};
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] parseJsonArray(String text, String key) {
        int k = text.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int lb = text.indexOf('[', k);
        int rb = text.indexOf(']', lb);
        if (lb < 0 || rb < 0) return null;
        String[] parts = text.substring(lb + 1, rb).split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    /** 转发并校验输出契约的记录器，让测试能看到 impl 真实产出的偏差/方差分布。 */
    private static final class Recorder implements ObsCorrector {

        private final ObsCorrector delegate;
        private final double biasMax;
        private final double scaleMax;

        int calls;
        int badLength;
        int nonFinite;
        int badScale;
        int badBias;
        int seenNf;
        int maxNs;
        double maxAbsBias;

        Recorder(ObsCorrector delegate, double biasMax, double scaleMax) {
            this.delegate = delegate;
            this.biasMax = biasMax;
            this.scaleMax = scaleMax;
        }

        @Override
        public CodeCorrection correct(ObsCorrectionContext ctx) {
            calls++;
            seenNf = ctx.nf();
            maxNs = Math.max(maxNs, ctx.ns());
            int rows = ctx.ns() * ctx.nf();
            CodeCorrection cc = delegate.correct(ctx);
            if (cc == null || cc.biasMeters() == null || cc.varianceScale() == null
                    || cc.biasMeters().length != rows || cc.varianceScale().length != rows) {
                badLength++;
                return cc;
            }
            for (int i = 0; i < rows; i++) {
                double b = cc.biasMeters()[i];
                double s = cc.varianceScale()[i];
                if (!Double.isFinite(b) || !Double.isFinite(s)) {
                    nonFinite++;
                    continue;
                }
                maxAbsBias = Math.max(maxAbsBias, Math.abs(b));
                if (b < -biasMax - 1e-6 || b > biasMax + 1e-6) badBias++;
                if (s < 1.0 - 1e-6 || s > scaleMax + 1e-6) badScale++;
            }
            return cc;
        }
    }
}