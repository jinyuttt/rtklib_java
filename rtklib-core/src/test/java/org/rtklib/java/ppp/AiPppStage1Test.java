package org.rtklib.java.ppp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.PppSolutionValidator;
import org.rtklib.java.ai.spi.PppVarianceScaler;
import org.rtklib.java.config.AiOpts;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.AiPppState;
import org.rtklib.java.data.Rtk;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 阶段1 PPP 三接口契约测试。
 *
 * <p>覆盖清单中的 T1（I-1 零回归）、T2/T3（输出契约与非法输出不干预）、
 * T9（施加原子性）、T10（熔断与显式恢复）、T11（单调性与不推翻确定性结论）。</p>
 */
@DisplayName("AI 阶段1：PPP 三接口契约与失效安全（T1/T2/T3/T9/T10/T11）")
class AiPppStage1Test {

    @AfterEach
    void tearDown() {
        AiHooks.clear();
        assertNull(AiHooks.provider(), "teardown 后注入点必须为空（§9）");
    }

    @Test
    @DisplayName("T1 I-1：未注入时 PPP 三接口解析均为 null，且不分配 AiPppState")
    void noInjectionIsZeroRegression() {
        Rtk rtk = newRtk(1);
        rtk.sol.stat = Constants.SOLQ_PPP;
        rtk.sol.ns = 8;
        double qr0 = rtk.sol.qr[0];

        assertNull(AiHooks.resolvePppSolutionValidator(rtk));
        assertNull(AiHooks.resolvePppVarianceScaler(rtk));
        assertNull(AiHooks.resolvePppRtkFixValidator(rtk));
        assertNull(PppAiInject.varianceScaler(rtk), "未注入时接口 B 解析必须为 null");

        PppAiInject.applySolutionValidator(rtk);

        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat, "未注入不得改动解状态");
        assertEquals((byte) 8, rtk.sol.ns);
        assertEquals(Double.compare(qr0, rtk.sol.qr[0]), 0, "未注入不得改动位置方差");
        assertNull(rtk.aiPppState, "未注入不得分配 AiPppState（I-1）");
    }

    @Test
    @DisplayName("T1 解析优先级：Rtk 实例字段 > 注入点")
    void instanceFieldOverridesInjection() {
        PppSolutionValidator instanceA = ctx -> 1.0;
        PppVarianceScaler instanceB = ctx -> new double[0];

        AiHooks.install(new AiProvider() {
            @Override
            public PppSolutionValidator pppSolutionValidator() {
                return ctx -> 0.0;
            }

            @Override
            public PppVarianceScaler pppVarianceScaler() {
                return ctx -> new double[]{9.0};
            }

            @Override
            public ObsCorrector obsCorrector() {
                return ctx -> CodeCorrection.NONE;
            }

            @Override
            public FixValidator fixValidator() {
                return ctx -> 1.0;
            }
        });

        Rtk rtk = new Rtk();
        rtk.aiPppSolutionValidator = instanceA;
        rtk.aiPppVarianceScaler = instanceB;

        assertSame(instanceA, AiHooks.resolvePppSolutionValidator(rtk));
        assertSame(instanceB, AiHooks.resolvePppVarianceScaler(rtk));
        assertNull(AiHooks.resolvePppRtkFixValidator(rtk), "注入点未提供 D 接口");
    }

    @Test
    @DisplayName("T2：接口 A 低于阈值时降级为 SOLQ_NONE，高于阈值不改动")
    void solutionValidatorDowngradesOnly() {
        Rtk rtk = newRtk(1);
        rtk.sol.stat = Constants.SOLQ_PPP;
        rtk.aiPppSolutionValidator = ctx -> 0.0;

        PppAiInject.applySolutionValidator(rtk);
        assertEquals(Constants.SOLQ_NONE, rtk.sol.stat, "置信度低于阈值必须降级");
        assertNotNull(rtk.aiPppState);

        Rtk rtk2 = newRtk(1);
        rtk2.sol.stat = Constants.SOLQ_FLOAT;
        rtk2.aiPppSolutionValidator = ctx -> 1.0;
        PppAiInject.applySolutionValidator(rtk2);
        assertEquals(Constants.SOLQ_FLOAT, rtk2.sol.stat, "高置信度不得改动解状态");
    }

    @Test
    @DisplayName("T3：接口 A 非法输出（null/NaN/越界/异常）一律不干预且不抛异常")
    void solutionValidatorRejectsInvalidOutput() {
        Rtk a = newRtk(1);
        a.sol.stat = Constants.SOLQ_PPP;
        a.aiPppSolutionValidator = ctx -> null;

        Rtk b = newRtk(1);
        b.sol.stat = Constants.SOLQ_PPP;
        b.aiPppSolutionValidator = ctx -> Double.NaN;

        Rtk c = newRtk(1);
        c.sol.stat = Constants.SOLQ_PPP;
        c.aiPppSolutionValidator = ctx -> 1.5;

        Rtk d = newRtk(1);
        d.sol.stat = Constants.SOLQ_PPP;
        d.aiPppSolutionValidator = ctx -> {
            throw new IllegalStateException("boom");
        };

        for (Rtk rtk : new Rtk[]{a, b, c, d}) {
            PppAiInject.applySolutionValidator(rtk);
            assertEquals(Constants.SOLQ_PPP, rtk.sol.stat, "非法输出必须按不干预处理（S1）");
            assertEquals(1, rtk.aiPppState.consecutive[AiPppState.IF_SOLUTION],
                    "非法输出必须计入连续降级");
            assertFalse(rtk.aiPppState.fused[AiPppState.IF_SOLUTION]);
        }
    }

    @Test
    @DisplayName("T11：SOLQ_FIX 不调用 provider、不被降级；SOLQ_NONE 不被升级")
    void solutionValidatorNeverOverturnsDeterministicResult() {
        Rtk rtk = newRtk(1);
        AtomicInteger calls = new AtomicInteger();
        rtk.aiPppSolutionValidator = ctx -> {
            calls.incrementAndGet();
            return 0.0;
        };

        rtk.sol.stat = Constants.SOLQ_FIX;
        PppAiInject.applySolutionValidator(rtk);
        assertEquals(0, calls.get(), "SOLQ_FIX 不得调用 provider（S4）");
        assertEquals(Constants.SOLQ_FIX, rtk.sol.stat);

        rtk.sol.stat = Constants.SOLQ_NONE;
        PppAiInject.applySolutionValidator(rtk);
        assertEquals(0, calls.get(), "非 SOLQ_PPP 解不进入 AI 判别（S4）");
        assertEquals(Constants.SOLQ_NONE, rtk.sol.stat, "AI 不得把解升级（单调性）");
    }

    @Test
    @DisplayName("T2：接口 B 只做单侧膨胀，按行序映射到 R 对角元")
    void varianceScalerInflatesDiagonalOnly() {
        Rtk rtk = newRtk(1);
        rtk.ssat[0].vs = 1;
        rtk.ssat[1].vs = 1;

        double[] R = new double[]{2.0, 0.0, 0.0, 3.0};
        PppAiInject.applyVarianceScale(rtk, 2, new int[]{1, 2}, new int[]{0, 0}, R,
                ctx -> new double[]{1.0, 4.0});

        assertBitEqual(2.0, R[0], "因子 1.0 不得改动");
        assertBitEqual(3.0 * 4.0, R[3], "因子 4.0 必须乘到对应行对角元");
        assertBitEqual(0.0, R[1], "非对角元不得改动（S2）");
        assertBitEqual(0.0, R[2], "非对角元不得改动（S2）");
        assertTrue(rtk.aiPppState.scaleValid);
    }

    @Test
    @DisplayName("T11：接口 B 因子 < 1.0 按 1.0 处理（只膨胀不收缩）")
    void varianceScalerNeverShrinks() {
        Rtk rtk = newRtk(1);
        rtk.ssat[0].vs = 1;

        double[] R = new double[]{2.0};
        PppAiInject.applyVarianceScale(rtk, 1, new int[]{1}, new int[]{0}, R,
                ctx -> new double[]{0.25});

        assertBitEqual(2.0, R[0], "因子 < 1.0 必须按不干预处理");
        assertFalse(rtk.aiPppState.scaleValid);
        assertEquals(0, rtk.aiPppState.consecutive[AiPppState.IF_VARIANCE],
                "合法但无膨胀的输出属于成功，不计降级");
    }

    @Test
    @DisplayName("T2：接口 B 因子超上限钳到 pppVarianceScaleMax")
    void varianceScalerClampsToMax() {
        Rtk rtk = newRtk(1);
        rtk.ssat[0].vs = 1;

        double[] R = new double[]{2.0};
        PppAiInject.applyVarianceScale(rtk, 1, new int[]{1}, new int[]{0}, R,
                ctx -> new double[]{100.0});

        assertBitEqual(2.0 * AiOpts.DEFAULT_PPP_VARIANCE_SCALE_MAX, R[0],
                "超上限必须钳到上限");
        assertTrue(rtk.aiPppState.scaleClampLogged);
    }

    @Test
    @DisplayName("T9：接口 B 异常/长度不符时 R 逐位不变（施加原子性）")
    void varianceScalerFailureIsAtomic() {
        Rtk rtk = newRtk(1);
        rtk.ssat[0].vs = 1;
        rtk.ssat[1].vs = 1;

        double[] R = new double[]{2.0, 0.0, 0.0, 3.0};
        PppAiInject.applyVarianceScale(rtk, 2, new int[]{1, 2}, new int[]{0, 0}, R,
                ctx -> {
                    throw new IllegalStateException("boom");
                });

        assertBitEqual(2.0, R[0], "异常必须不干预");
        assertBitEqual(3.0, R[3], "异常必须不干预");
        assertFalse(rtk.aiPppState.scaleValid);
        assertEquals(1, rtk.aiPppState.consecutive[AiPppState.IF_VARIANCE]);

        rtk.epoch = 1;
        double[] R2 = new double[]{2.0, 0.0, 0.0, 3.0};
        PppAiInject.applyVarianceScale(rtk, 2, new int[]{1, 2}, new int[]{0, 0}, R2,
                ctx -> new double[]{4.0});

        assertBitEqual(2.0, R2[0], "长度不符必须不干预");
        assertBitEqual(3.0, R2[3], "长度不符必须不干预");
        assertEquals(2, rtk.aiPppState.consecutive[AiPppState.IF_VARIANCE]);
    }

    @Test
    @DisplayName("T9：部分行非法只影响该行，不扩散到其他行")
    void varianceScalerPartialInvalidStaysLocal() {
        Rtk rtk = newRtk(1);
        rtk.ssat[0].vs = 1;
        rtk.ssat[1].vs = 1;

        double[] R = new double[]{2.0, 0.0, 0.0, 3.0};
        PppAiInject.applyVarianceScale(rtk, 2, new int[]{1, 2}, new int[]{0, 0}, R,
                ctx -> new double[]{4.0, Double.NaN});

        assertBitEqual(2.0 * 4.0, R[0], "合法行必须按因子膨胀");
        assertBitEqual(3.0, R[3], "非法行按 1.0 处理，不得被其他行的因子污染（§4.4）");
        assertTrue(rtk.aiPppState.scaleValid);
    }

    @Test
    @DisplayName("T2：接口 B 同历元重复调用命中缓存，不重复推理")
    void varianceScalerCachesPerEpoch() {
        Rtk rtk = newRtk(1);
        rtk.ssat[0].vs = 1;
        AtomicInteger calls = new AtomicInteger();

        PppVarianceScaler scaler = ctx -> {
            calls.incrementAndGet();
            return new double[]{4.0};
        };

        PppAiInject.applyVarianceScale(rtk, 1, new int[]{1}, new int[]{0}, new double[]{1.0}, scaler);
        PppAiInject.applyVarianceScale(rtk, 1, new int[]{1}, new int[]{0}, new double[]{1.0}, scaler);
        assertEquals(1, calls.get(), "同历元必须命中缓存（§4.3）");

        rtk.epoch = 1;
        PppAiInject.applyVarianceScale(rtk, 1, new int[]{1}, new int[]{0}, new double[]{1.0}, scaler);
        assertEquals(2, calls.get(), "新历元必须重新推理");
    }

    @Test
    @DisplayName("T10：连续降级达阈值即熔断，熔断期间不调用；重注入解除熔断")
    void breakerOpensAndRecoversOnReinjection() {
        Rtk rtk = newRtk(1);
        rtk.rtkConfig.ai.pppAiMaxConsecutiveFailures = 2;
        rtk.sol.stat = Constants.SOLQ_PPP;

        AtomicInteger badCalls = new AtomicInteger();
        PppSolutionValidator bad = ctx -> {
            badCalls.incrementAndGet();
            throw new IllegalStateException("boom");
        };
        rtk.aiPppSolutionValidator = bad;

        PppAiInject.applySolutionValidator(rtk);
        PppAiInject.applySolutionValidator(rtk);
        assertEquals(2, badCalls.get());
        assertTrue(rtk.aiPppState.fused[AiPppState.IF_SOLUTION], "达阈值必须熔断（S5）");
        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat, "熔断前降级不得改动解状态");

        PppAiInject.applySolutionValidator(rtk);
        assertEquals(2, badCalls.get(), "熔断期间 provider 不得被调用");
        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat, "熔断后等价未注入");

        rtk.aiPppSolutionValidator = ctx -> 0.9;
        PppAiInject.applySolutionValidator(rtk);
        assertFalse(rtk.aiPppState.fused[AiPppState.IF_SOLUTION], "显式重注入必须解除熔断");
        assertEquals(0, rtk.aiPppState.consecutive[AiPppState.IF_SOLUTION]);
        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat);
    }

    @Test
    @DisplayName("T10：成功调用把连续降级计数归零")
    void successResetsConsecutiveCounter() {
        Rtk rtk = newRtk(1);
        rtk.sol.stat = Constants.SOLQ_PPP;

        rtk.aiPppSolutionValidator = ctx -> Double.NaN;
        PppAiInject.applySolutionValidator(rtk);
        assertEquals(1, rtk.aiPppState.consecutive[AiPppState.IF_SOLUTION]);

        rtk.aiPppSolutionValidator = ctx -> 0.9;
        PppAiInject.applySolutionValidator(rtk);
        assertEquals(0, rtk.aiPppState.consecutive[AiPppState.IF_SOLUTION]);
    }

    @Test
    @DisplayName("T1 确定性基线：默认门限关闭时零回归，只产生归因标志")
    void qrGateDefaultOffIsZeroRegression() {
        Rtk rtk = newRtk(1);
        rtk.sol.stat = Constants.SOLQ_PPP;
        rtk.sol.qr[0] = 1.0e6f;
        rtk.sol.qr[1] = 1.0e6f;
        rtk.sol.qr[2] = 1.0e6f;

        assertFalse(PppQrGate.apply(rtk), "默认门限 Double.MAX_VALUE 恒不触发");
        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat, "默认关闭不得改动解状态（I-1）");
        assertFalse(rtk.pppQrDegraded);
    }

    @Test
    @DisplayName("T2 确定性基线：posVar 超门限只降级 SOLQ_PPP，归因标志与启用解耦")
    void qrGateDegradesPppOnly() {
        Rtk ppp = newRtk(1);
        ppp.rtkConfig.ai.pppQrThreshold = 0.01;
        ppp.sol.stat = Constants.SOLQ_PPP;
        ppp.sol.qr[0] = 1.0f;

        assertTrue(PppQrGate.apply(ppp));
        assertEquals(Constants.SOLQ_NONE, ppp.sol.stat, "超门限必须降级");
        assertTrue(ppp.pppQrDegraded);

        Rtk fix = newRtk(1);
        fix.rtkConfig.ai.pppQrThreshold = 0.01;
        fix.sol.stat = Constants.SOLQ_FIX;
        fix.sol.qr[0] = 1.0f;
        assertTrue(PppQrGate.apply(fix), "归因标志与门限是否启用解耦（§3.5）");
        assertEquals(Constants.SOLQ_FIX, fix.sol.stat, "AR 固定解不得被推翻（S4）");

        Rtk flt = newRtk(1);
        flt.rtkConfig.ai.pppQrThreshold = 0.01;
        flt.sol.stat = Constants.SOLQ_FLOAT;
        flt.sol.qr[0] = 1.0f;
        assertTrue(PppQrGate.apply(flt));
        assertEquals(Constants.SOLQ_FLOAT, flt.sol.stat, "FLOAT 不在确定性基线降级范围");
    }

    @Test
    @DisplayName("T2 确定性基线：posVar 非有限时按不干预处理（S1）")
    void qrGateNonFinitePosVarIsOpen() {
        Rtk rtk = newRtk(1);
        rtk.rtkConfig.ai.pppQrThreshold = 0.01;
        rtk.sol.stat = Constants.SOLQ_PPP;
        rtk.sol.qr[0] = Float.NaN;
        rtk.sol.qr[1] = Float.NaN;
        rtk.sol.qr[2] = Float.NaN;

        assertFalse(PppQrGate.apply(rtk), "非有限方差不可判定，按不干预");
        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat);
        assertFalse(rtk.pppQrDegraded);
    }

    @Test
    @DisplayName("T8 归因：确定性已降级时接口 A 照常调用，上下文标志同源、写入幂等")
    void deterministicDegradedIsVisibleToSolutionValidator() {
        Rtk rtk = newRtk(1);
        rtk.rtkConfig.ai.pppQrThreshold = 0.01;
        rtk.sol.stat = Constants.SOLQ_PPP;
        rtk.sol.qr[0] = 1.0f;
        AtomicInteger calls = new AtomicInteger();
        rtk.aiPppSolutionValidator = ctx -> {
            calls.incrementAndGet();
            assertTrue(ctx.deterministicDegraded(), "标志必须与门限判据同源（T8）");
            return 0.9;
        };

        PppQrGate.apply(rtk);
        assertEquals(Constants.SOLQ_NONE, rtk.sol.stat);
        PppAiInject.applySolutionValidator(rtk);

        assertEquals(1, calls.get(), "确定性降级历元仍需 AI 调用，用于区分归因（§3.3）");
        assertEquals(Constants.SOLQ_NONE, rtk.sol.stat, "AI 写入必须幂等，不得升级（S3）");
    }

    @Test
    @DisplayName("T1 配置钳位：pppQrThreshold 非法值回退默认（分支关闭）")
    void qrThresholdValidationFallsBackToClosed() {
        AiOpts negative = new AiOpts();
        negative.pppQrThreshold = -1.0;
        negative.validate();
        assertEquals(Double.MAX_VALUE, negative.pppQrThreshold, 0.0, "负数回退默认（关闭）");

        AiOpts nan = new AiOpts();
        nan.pppQrThreshold = Double.NaN;
        nan.validate();
        assertEquals(Double.MAX_VALUE, nan.pppQrThreshold, 0.0, "NaN 回退默认（关闭）");

        AiOpts valid = new AiOpts();
        valid.pppQrThreshold = 0.25;
        valid.validate();
        assertEquals(0.25, valid.pppQrThreshold, 0.0, "合法值原样保留");
    }

    @Test
    @DisplayName("T1 配置默认值：A 阈值 0.5 / B 膨胀上限 4.0 / 预算 50ms / 熔断 10 历元")
    void aiOptsPppDefaults() {
        AiOpts ai = new AiOpts();
        assertEquals(0.5, ai.pppSolutionMinConfidence, 0.0);
        assertEquals(4.0, ai.pppVarianceScaleMax, 0.0);
        assertEquals(50.0, ai.pppAiMaxInferenceMs, 0.0);
        assertEquals(10, ai.pppAiMaxConsecutiveFailures);
    }

    private static Rtk newRtk(int nf) {
        Rtk rtk = new Rtk();
        rtk.opt.nf = nf;
        rtk.tt = 30.0;
        rtk.nx = PppCore.pppnx(rtk.opt);
        rtk.x = new double[rtk.nx];
        rtk.sol.ns = 0;
        return rtk;
    }

    private static void assertBitEqual(double expected, double actual, String message) {
        assertTrue(Double.compare(expected, actual) == 0,
                message + " 逐位不一致: expected=" + expected + ", actual=" + actual);
    }
}