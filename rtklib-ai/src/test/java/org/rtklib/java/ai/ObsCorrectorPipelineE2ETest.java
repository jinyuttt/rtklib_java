package org.rtklib.java.ai;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.ObsCorrector;
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