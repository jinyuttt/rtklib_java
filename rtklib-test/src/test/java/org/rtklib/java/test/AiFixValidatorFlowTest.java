package org.rtklib.java.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.OnnxAiProvider;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Accuracy;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 阶段 2 流程测试（跨模块）：{@code rtklib-core} 接线点 + {@code rtklib-ai} 实现 的端到端验证。
 * <p>
 * 模块内自洽测试（ONNX 加载、特征顺序、推理对账）留在 {@code rtklib-ai}；
 * 本类只验证「接线是否按设计生效」，即 §3.3 的不变量：
 * </p>
 * <ul>
 *   <li><b>P1 不注入 = 原生</b>：未注入时输出与基线逐位一致（I-1）。</li>
 *   <li><b>P2 注入但全接受 = 原生</b>：阈值 0.0 时不得产生任何数值扰动（接线本身零开销）。</li>
 *   <li><b>P3 拒绝真的生效</b>：阈值 1.0 时固定解全部降为浮点，且 {@code nfix} 不被推进。</li>
 *   <li><b>P4 异常不 fail-closed</b>：impl 抛异常或返回非有限置信度时按「不干预」处理。</li>
 * </ul>
 * <p>
 * 用 dummy 模型（随机权重）而非真实训练模型：本测试验证的是接线与降级语义，
 * 不是模型质量；dummy 模型的置信度无物理意义，正因如此才不能断言「AI 提升了精度」。
 * </p>
 */
@DisplayName("阶段2流程测试：core 接线 + ai 实现 端到端")
class AiFixValidatorFlowTest {

    private static final Logger log = LoggerFactory.getLogger(AiFixValidatorFlowTest.class);

    private static final String TD = "../test-data";
    private static final String GEJ_ROVER = TD + "/rinex/rinex304_gej_3034.21o";
    private static final String GEJ_BASE = TD + "/rinex/rinex304_gej_sept.21o";
    private static final String GEJ_NAV = TD + "/nav/rinex304_mixed_sept.21p";

    /** dummy 模型由 {@code rtklib-ai} 模块的测试资源提供，避免同一份二进制重复入库。 */
    private static final Path MODEL = Paths.get(
            "..", "rtklib-ai", "src", "test", "resources", "models", "dummy_fix_validator.onnx");

    @AfterEach
    void tearDown() {
        AiHooks.clear();
        assertNull(AiHooks.provider(), "teardown 后注入点必须为空（§9）");
    }

    @Test
    @DisplayName("P1+P2：不注入 / 注入但阈值 0.0（全接受）→ 与基线逐位一致")
    void noInjectionAndAcceptAllAreBitIdentical() throws IOException {
        assumeReady();

        AiHooks.clear();
        RtkProcessor.RtkResult baseline = run(new RtkConfig());
        assertTrue(baseline.totalEpochs > 0, "基线应至少解算 1 个历元");
        assertTrue(!baseline.solutions.isEmpty(), "基线应至少产出 1 个解");
        long fixCount = countFix(baseline);
        log.info("基线: total={}, success={}, fix={}", baseline.totalEpochs,
                baseline.successCount, fixCount);

        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL, null)) {
            assertTrue(provider.isFixValidatorAvailable(), "dummy 模型应能加载");

            RtkConfig acceptAll = new RtkConfig();
            acceptAll.ai.fixValidatorMinConfidence = 0.0;

            AiHooks.install(provider);
            RtkProcessor.RtkResult withAi = run(acceptAll);
            assertBitIdentical(baseline, withAi, "阈值 0.0 全接受");
            log.info("P2 通过: 全接受时与基线逐位一致（fix={}）", countFix(withAi));
        }
    }

    @Test
    @DisplayName("P3：阈值 1.0（全拒绝）→ 无固定解，nfix 不推进")
    void rejectAllSuppressesFix() throws IOException {
        assumeReady();

        AiHooks.clear();
        RtkProcessor.RtkResult baseline = run(new RtkConfig());
        assumeTrue(countFix(baseline) > 0, "基线无固定解，无法验证拒绝路径");

        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL, null)) {
            RtkConfig rejectAll = new RtkConfig();
            rejectAll.ai.fixValidatorMinConfidence = 1.0;

            AiHooks.install(provider);
            RtkProcessor.RtkResult rejected = run(rejectAll);

            assertEquals(0, countFix(rejected), "阈值 1.0 时不得产出任何固定解");
            assertEquals(baseline.totalEpochs, rejected.totalEpochs, "历元总数不应改变");
            log.info("P3 通过: 基线 fix={} → 全拒绝 fix={}", countFix(baseline), countFix(rejected));
        }
    }

    @Test
    @DisplayName("P4：impl 抛异常 → 按不干预处理（输出回到基线，非 fail-closed）")
    void throwingValidatorDegradesToBaseline() throws IOException {
        assumeReady();

        AiHooks.clear();
        RtkProcessor.RtkResult baseline = run(new RtkConfig());

        AiHooks.install(new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return ctx -> {
                    throw new IllegalStateException("模拟推理失败");
                };
            }

            @Override
            public ObsCorrector obsCorrector() {
                return ctx -> CodeCorrection.NONE;
            }
        });
        RtkProcessor.RtkResult degraded = run(new RtkConfig());

        assertBitIdentical(baseline, degraded, "异常降级");
        log.info("P4 通过: 抛异常时输出与基线逐位一致（不干预，非 fail-closed）");
    }

    @Test
    @DisplayName("P4b：置信度 NaN → 按不干预处理")
    void nonFiniteConfidenceDegradesToBaseline() throws IOException {
        assumeReady();

        AiHooks.clear();
        RtkProcessor.RtkResult baseline = run(new RtkConfig());

        AiHooks.install(new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return ctx -> Double.NaN;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return ctx -> CodeCorrection.NONE;
            }
        });
        RtkProcessor.RtkResult degraded = run(new RtkConfig());

        assertBitIdentical(baseline, degraded, "非有限置信度降级");
    }

    @Test
    @DisplayName("P5：默认阈值 0.5 下 dummy 模型可跑通（不破坏解算）")
    void defaultThresholdKeepsSolving() throws IOException {
        assumeReady();

        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL, null)) {
            AiHooks.install(provider);
            RtkProcessor.RtkResult result = run(new RtkConfig());

            assertTrue(result.totalEpochs > 0, "应处理历元");
            assertTrue(result.successCount > 0, "开启接口一后不应破坏解算");
            log.info("P5 通过: total={}, success={}, fix={}", result.totalEpochs,
                    result.successCount, countFix(result));
        }
    }

    @Test
    @DisplayName("P6：AiOpts 阈值经 RtkConfig 传播到 core 判定（0.0 与 1.0 行为可区分）")
    void thresholdIsActuallyRead() throws IOException {
        assumeReady();

        AiHooks.clear();
        long baseFix = countFix(run(new RtkConfig()));
        assumeTrue(baseFix > 0, "基线无固定解，无法验证阈值生效");

        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL, null)) {
            AiHooks.install(provider);

            RtkConfig low = new RtkConfig();
            low.ai.fixValidatorMinConfidence = 0.0;
            long lowFix = countFix(run(low));

            RtkConfig high = new RtkConfig();
            high.ai.fixValidatorMinConfidence = 1.0;
            long highFix = countFix(run(high));

            assertEquals(baseFix, lowFix, "阈值 0.0 应等价于不干预");
            assertEquals(0, highFix, "阈值 1.0 应拒绝全部固定解");
        }
    }

    private static void assumeReady() {
        assumeTrue(Files.exists(MODEL), "dummy 模型不存在: " + MODEL.toAbsolutePath());
        assumeTrue(Files.exists(Paths.get(GEJ_ROVER)) && Files.exists(Paths.get(GEJ_BASE))
                && Files.exists(Paths.get(GEJ_NAV)), "GEJ RINEX 数据不可用");
    }

    private static RtkProcessor.RtkResult run(RtkConfig cfg) {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        return RinexRtkProcessor.processRinex(GEJ_ROVER, GEJ_BASE, GEJ_NAV, opt, cfg);
    }

    private static long countFix(RtkProcessor.RtkResult r) {
        return r.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
    }

    private static void assertBitIdentical(RtkProcessor.RtkResult a, RtkProcessor.RtkResult b,
                                           String tag) {
        assertEquals(a.totalEpochs, b.totalEpochs, tag + " totalEpochs");
        assertEquals(a.successCount, b.successCount, tag + " successCount");
        assertEquals(a.failCount, b.failCount, tag + " failCount");

        List<SolData> sa = a.solutions;
        List<SolData> sb = b.solutions;
        assertEquals(sa.size(), sb.size(), tag + " solutions 数量");

        for (int i = 0; i < sa.size(); i++) {
            SolData x = sa.get(i);
            SolData y = sb.get(i);
            String at = tag + " epoch#" + i + " " + x.timeStr;

            assertEquals(x.timeStr, y.timeStr, at + " 时间");
            assertEquals(x.status, y.status, at + " 状态");
            assertEquals(x.numSat, y.numSat, at + " 卫星数");
            assertBitEqual(x.ratio, y.ratio, at + " ratio");
            assertBitEqual(x.age, y.age, at + " age");

            assertEquals(x.positions.size(), y.positions.size(), at + " 位置数量");
            for (int j = 0; j < x.positions.size(); j++) {
                Position px = x.positions.get(j);
                Position py = y.positions.get(j);
                assertEquals(px.type, py.type, at + " 位置坐标系");
                assertBitEqual(px.v1, py.v1, at + " 位置 v1");
                assertBitEqual(px.v2, py.v2, at + " 位置 v2");
                assertBitEqual(px.v3, py.v3, at + " 位置 v3");
            }

            assertEquals(x.accuracies.size(), y.accuracies.size(), at + " 精度数量");
            for (int j = 0; j < x.accuracies.size(); j++) {
                Accuracy ax = x.accuracies.get(j);
                Accuracy ay = y.accuracies.get(j);
                assertEquals(ax.type, ay.type, at + " 精度坐标系");
                assertBitEqual(ax.s1, ay.s1, at + " 精度 s1");
                assertBitEqual(ax.s2, ay.s2, at + " 精度 s2");
                assertBitEqual(ax.s3, ay.s3, at + " 精度 s3");
                assertBitEqual(ax.c12, ay.c12, at + " 精度 c12");
                assertBitEqual(ax.c23, ay.c23, at + " 精度 c23");
                assertBitEqual(ax.c31, ay.c31, at + " 精度 c31");
            }
        }
    }

    private static void assertBitEqual(double expected, double actual, String message) {
        assertTrue(Double.compare(expected, actual) == 0,
                message + " 逐位不一致: expected=" + expected + ", actual=" + actual);
    }
}