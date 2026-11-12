package org.rtklib.java.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.config.AiOpts;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Accuracy;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.data.SolData;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1 验收：SPI 契约 + AiOpts 阈值层 + I-1 零回归。
 */
@DisplayName("AI 阶段1：core SPI / AiOpts / I-1 零回归")
class AiSpiStage1Test {

    private static final String GEJ_ROVER = "rinex/rinex304_gej_3034.21o";
    private static final String GEJ_BASE = "rinex/rinex304_gej_sept.21o";
    private static final String GEJ_NAV = "nav/rinex304_mixed_sept.21p";

    @AfterEach
    void tearDown() {
        AiHooks.clear();
        assertNull(AiHooks.provider(), "teardown 后注入点必须为空（§9）");
    }

    @Test
    @DisplayName("AiOpts 默认值：置信度 0.5 / 膨胀上限 4.0 / 偏差上限 10m / 激进模式关")
    void aiOptsDefaults() {
        AiOpts ai = new AiOpts();
        assertEquals(0.5, ai.fixValidatorMinConfidence, 0.0);
        assertEquals(4.0, ai.varianceScaleMax, 0.0);
        assertEquals(10.0, ai.obsCorrectorMaxBias, 0.0);
        assertTrue(ai.traceRejects);
        assertTrue(ai.traceDegrades);
        assertFalse(ai.enableAggressiveAi, "激进模式默认必须关闭（I-1 / §4.6）");
        assertEquals(AiOpts.DEFAULT_AGGRESSIVE_MIN_SCALE, ai.aggressiveMinScale, 0.0);
    }

    @Test
    @DisplayName("AiOpts.validate：aggressiveMinScale 非法回退 0.1、超 1.0 钳到 1.0")
    void aiOptsValidateAggressiveMinScale() {
        AiOpts ai = new AiOpts();
        ai.aggressiveMinScale = 0.0;
        ai.validate();
        assertEquals(AiOpts.DEFAULT_AGGRESSIVE_MIN_SCALE, ai.aggressiveMinScale, 0.0);

        ai.aggressiveMinScale = -0.5;
        ai.validate();
        assertEquals(AiOpts.DEFAULT_AGGRESSIVE_MIN_SCALE, ai.aggressiveMinScale, 0.0);

        ai.aggressiveMinScale = 1.5;
        ai.validate();
        assertEquals(1.0, ai.aggressiveMinScale, 0.0, "超 1.0 钳到 1.0，等价保守模式");

        ai.aggressiveMinScale = 0.5;
        ai.validate();
        assertEquals(0.5, ai.aggressiveMinScale, 0.0, "合法区间内不得改写");
    }

    @Test
    @DisplayName("RtkConfig 深拷贝携带激进模式字段")
    void rtkConfigDeepCopiesAggressiveFields() {
        RtkConfig src = new RtkConfig();
        src.ai.enableAggressiveAi = true;
        src.ai.aggressiveMinScale = 0.5;

        RtkConfig copy = new RtkConfig(src);
        assertTrue(copy.ai.enableAggressiveAi);
        assertEquals(0.5, copy.ai.aggressiveMinScale, 0.0);

        copy.ai.aggressiveMinScale = 0.3;
        assertEquals(0.5, src.ai.aggressiveMinScale, 0.0, "AiOpts 必须深拷贝");
    }

    @Test
    @DisplayName("AiOpts.validate：非法值钳到更保守方向且幂等")
    void aiOptsValidateClamps() {
        AiOpts ai = new AiOpts();
        ai.fixValidatorMinConfidence = 1.7;
        ai.varianceScaleMax = 0.5;
        ai.obsCorrectorMaxBias = -3.0;
        ai.validate();

        assertEquals(1.0, ai.fixValidatorMinConfidence, 0.0);
        assertEquals(1.0, ai.varianceScaleMax, 0.0);
        assertEquals(AiOpts.DEFAULT_OBS_CORRECTOR_MAX_BIAS, ai.obsCorrectorMaxBias, 0.0);

        ai.fixValidatorMinConfidence = -0.2;
        ai.validate();
        assertEquals(0.0, ai.fixValidatorMinConfidence, 0.0, "再次校验钳到 0.0");
        ai.validate();
        assertEquals(0.0, ai.fixValidatorMinConfidence, 0.0, "validate 幂等，不得二次改写");
    }

    @Test
    @DisplayName("AiOpts.validate：NaN/Inf 回退默认值，不抛异常")
    void aiOptsValidateNonFinite() {
        AiOpts ai = new AiOpts();
        ai.fixValidatorMinConfidence = Double.NaN;
        ai.varianceScaleMax = Double.POSITIVE_INFINITY;
        ai.obsCorrectorMaxBias = Double.NEGATIVE_INFINITY;
        ai.validate();

        assertEquals(AiOpts.DEFAULT_FIX_VALIDATOR_MIN_CONFIDENCE, ai.fixValidatorMinConfidence, 0.0);
        assertEquals(AiOpts.DEFAULT_VARIANCE_SCALE_MAX, ai.varianceScaleMax, 0.0);
        assertEquals(AiOpts.DEFAULT_OBS_CORRECTOR_MAX_BIAS, ai.obsCorrectorMaxBias, 0.0);
    }

    @Test
    @DisplayName("RtkConfig 深拷贝携带 AiOpts，改副本不影响源")
    void rtkConfigDeepCopiesAiOpts() {
        RtkConfig src = new RtkConfig();
        src.ai.fixValidatorMinConfidence = 0.9;

        RtkConfig copy = new RtkConfig(src);
        assertEquals(0.9, copy.ai.fixValidatorMinConfidence, 0.0);

        copy.ai.fixValidatorMinConfidence = 0.1;
        assertEquals(0.9, src.ai.fixValidatorMinConfidence, 0.0, "AiOpts 必须深拷贝");
    }

    @Test
    @DisplayName("AiHooks：install/clear/install(null) 语义")
    void hooksInstallAndClear() {
        AiProvider provider = noOpProvider();
        AiHooks.install(provider);
        assertSame(provider, AiHooks.provider());

        AiHooks.install(null);
        assertNull(AiHooks.provider(), "install(null) 即整体关闭");

        AiHooks.install(provider);
        AiHooks.clear();
        assertNull(AiHooks.provider());
    }

    @Test
    @DisplayName("I-1：未注入时两个接口解析均为 null（core 走原生分支）")
    void resolveIsNullWithoutInjection() {
        Rtk rtk = new Rtk();
        assertNull(AiHooks.resolveFixValidator(rtk));
        assertNull(AiHooks.resolveObsCorrector(rtk));
        assertNull(AiHooks.resolveFixValidator(null));
        assertNull(AiHooks.resolveObsCorrector(null));
    }

    @Test
    @DisplayName("解析优先级：Rtk 实例字段 > 注入点")
    void instanceFieldOverridesInjection() {
        AiHooks.install(noOpProvider());

        FixValidator instanceValidator = ctx -> 1.0;
        ObsCorrector instanceCorrector = ctx -> CodeCorrection.NONE;

        Rtk rtk = new Rtk();
        rtk.aiFixValidator = instanceValidator;
        rtk.aiObsCorrector = instanceCorrector;

        assertSame(instanceValidator, AiHooks.resolveFixValidator(rtk));
        assertSame(instanceCorrector, AiHooks.resolveObsCorrector(rtk));
    }

    @Test
    @DisplayName("解析时惰性校验 AiOpts（配置错误不抛异常）")
    void resolveValidatesAiOpts() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.ai.varianceScaleMax = 0.5;
        rtk.rtkConfig.ai.fixValidatorMinConfidence = -1.0;

        AiHooks.resolveFixValidator(rtk);

        assertEquals(1.0, rtk.rtkConfig.ai.varianceScaleMax, 0.0);
        assertEquals(0.0, rtk.rtkConfig.ai.fixValidatorMinConfidence, 0.0);
    }

    @Test
    @DisplayName("ThreadLocal 隔离：注入只对当前线程可见")
    void hooksAreThreadIsolated() throws Exception {
        AiHooks.install(noOpProvider());

        AtomicReference<AiProvider> other = new AtomicReference<>();
        Thread t = new Thread(() -> other.set(AiHooks.provider()));
        t.start();
        t.join();

        assertNull(other.get(), "其他线程不得看到本线程注入");
        assertNotNull(AiHooks.provider());
    }

    @Test
    @DisplayName("I-1 零回归：注入空实现 provider 后解算输出与原生逐位一致")
    void zeroRegressionWithNoOpProvider() {
        Assumptions.assumeTrue(
                TestDataConfig.hasTestDataFile(GEJ_ROVER)
                        && TestDataConfig.hasTestDataFile(GEJ_BASE)
                        && TestDataConfig.hasTestDataFile(GEJ_NAV),
                "G+E+J RINEX 数据不可用");

        String rover = TestDataConfig.getTestDataFile(GEJ_ROVER);
        String base = TestDataConfig.getTestDataFile(GEJ_BASE);
        String nav = TestDataConfig.getTestDataFile(GEJ_NAV);

        AiHooks.clear();
        RtkProcessor.RtkResult nativeRun = RinexRtkProcessor.processRinex(
                rover, base, nav, createOpt(), new RtkConfig());

        AiHooks.install(noOpProvider());
        RtkProcessor.RtkResult aiRun = RinexRtkProcessor.processRinex(
                rover, base, nav, createOpt(), new RtkConfig());

        assertTrue(nativeRun.totalEpochs > 0, "应至少解算 1 个历元");
        assertTrue(nativeRun.solutions.size() > 0, "应至少产出 1 个解");
        assertBitIdentical(nativeRun, aiRun);
    }

    private static PrcOpt createOpt() {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        return opt;
    }

    private static AiProvider noOpProvider() {
        return new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return ctx -> 1.0;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return ctx -> CodeCorrection.NONE;
            }
        };
    }

    private static void assertBitIdentical(RtkProcessor.RtkResult a, RtkProcessor.RtkResult b) {
        assertEquals(a.totalEpochs, b.totalEpochs, "totalEpochs");
        assertEquals(a.successCount, b.successCount, "successCount");
        assertEquals(a.failCount, b.failCount, "failCount");

        List<SolData> sa = a.solutions;
        List<SolData> sb = b.solutions;
        assertEquals(sa.size(), sb.size(), "solutions 数量");

        for (int i = 0; i < sa.size(); i++) {
            SolData x = sa.get(i);
            SolData y = sb.get(i);
            String at = "epoch#" + i + " " + x.timeStr;

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