package org.rtklib.java.ppprtk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.PppRtkFixContext;
import org.rtklib.java.ai.spi.PppRtkFixValidator;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.AiPppState;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.ppp.PppAiInject;
import org.rtklib.java.ppp.PppCore;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口 D（PPP-RTK 固定校验）契约测试。
 *
 * <p>核心用例是 T9 施加原子性：AI 拒绝固定时，{@code rtk.xa}/{@code rtk.Pa} 的数组指针、
 * {@code rtk.x}/{@code rtk.P} 的数值与 {@code ssat.fix} 必须与拒绝前逐位一致——即注入点
 * 严格遵循「算-验-提交」三段式，拒绝时整体丢弃候选而不留脏状态（S2）。</p>
 *
 * <p>其余用例覆盖 T1（未注入零回归，I-1）、T2/T3（门限与非法输出不干预）、
 * T10（熔断与显式恢复）。</p>
 */
@DisplayName("接口 D：PPP-RTK 固定校验契约与失效安全（T1/T2/T3/T9/T10）")
class PppRtkFixValidatorTest {

    private static final int[] SATS = {1, 2, 3, 4};

    @AfterEach
    void tearDown() {
        AiHooks.clear();
        assertNull(AiHooks.provider(), "teardown 后注入点必须为空（§9）");
    }

    @Test
    @DisplayName("T1 I-1：未注入时 validateRtkFix 放行且不分配 AiPppState")
    void noInjectionIsZeroRegression() {
        Rtk rtk = new Rtk();

        assertTrue(PppAiInject.validateRtkFix(rtk, sampleContext()),
                "未注入必须放行（不干预）");
        assertNull(rtk.aiPppState, "未注入不得分配 AiPppState（I-1）");
    }

    @Test
    @DisplayName("T1：未注入时 PPP-RTK 固定照常提交（与 v3.4.0 零回归）")
    void noInjectionKeepsCoreFixBehavior() {
        Rtk rtk = newRtk();

        PppRtkAmbFix.ppprtkAmbFix(rtk, null);

        assertEquals(Constants.SOLQ_FIX, rtk.sol.stat, "未注入必须照常提交固定解");
        assertEquals(1, rtk.nfix);
        for (int sat : SATS) {
            assertEquals(1, rtk.ssat[sat - 1].fix[0], "未注入必须照常写 ssat.fix");
        }
        assertNull(rtk.aiPppState, "未注入不得分配 AiPppState（I-1）");
    }

    @Test
    @DisplayName("T9 施加原子性：AI 拒绝固定时 xa/Pa 指针与全部状态逐位不变")
    void aiRejectLeavesStateBitIdentical() {
        Rtk rtk = newRtk();

        AtomicInteger calls = new AtomicInteger();
        AtomicReference<PppRtkFixContext> seen = new AtomicReference<>();
        rtk.aiPppRtkFixValidator = ctx -> {
            calls.incrementAndGet();
            seen.set(ctx);
            return 0.0;
        };

        double[] xaRef = rtk.xa;
        double[] paRef = rtk.Pa;
        double[] xaCopy = rtk.xa.clone();
        double[] paCopy = rtk.Pa.clone();
        double[] xCopy = rtk.x.clone();
        double[] pCopy = rtk.P.clone();
        double[] rrCopy = rtk.sol.rr.clone();
        int[] fixCopy = new int[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            fixCopy[i] = rtk.ssat[i].fix[0];
        }

        PppRtkAmbFix.ppprtkAmbFix(rtk, null);

        assertEquals(1, calls.get(), "AI 必须在提交前被调用一次");
        assertSame(xaRef, rtk.xa, "拒绝时 xa 指针不得改变");
        assertSame(paRef, rtk.Pa, "拒绝时 Pa 指针不得改变");
        assertBitArrayEqual(xaCopy, rtk.xa, "xa 内容");
        assertBitArrayEqual(paCopy, rtk.Pa, "Pa 内容");
        assertBitArrayEqual(xCopy, rtk.x, "x 内容");
        assertBitArrayEqual(pCopy, rtk.P, "P 内容");
        assertBitArrayEqual(rrCopy, rtk.sol.rr, "sol.rr");
        for (int i = 0; i < Constants.MAXSAT; i++) {
            assertEquals(fixCopy[i], rtk.ssat[i].fix[0],
                    "拒绝时 ssat.fix 必须与拒绝前一致（sat=" + (i + 1) + "）");
        }
        assertEquals(Constants.SOLQ_FLOAT, rtk.sol.stat, "拒绝时不得写 SOLQ_FIX");
        assertEquals(0, rtk.nfix, "拒绝时不得累计固定历元数");

        PppRtkFixContext ctx = seen.get();
        assertNotNull(ctx, "拒绝前必须装配上下文");
        assertEquals(SATS.length, ctx.nFixed(), "上下文必须描述即将提交的候选");
        assertTrue(ctx.ratioPassed(), "注入点执行时 ratio 门限已通过");
        assertEquals(1, ctx.arAttempts(), "本次固定尝试计 1 次");
        assertEquals(0, ctx.arFixCount(), "拒绝前固定历元数仍为 0");
        assertEquals(0, rtk.aiPppState.consecutive[AiPppState.IF_RTK_FIX],
                "低置信度属于合法推理，不计降级");
        assertFalse(rtk.aiPppState.fused[AiPppState.IF_RTK_FIX]);
    }

    @Test
    @DisplayName("T2：AI 高置信度时按 v3.4.0 语义提交固定解")
    void aiAcceptCommitsFix() {
        Rtk rtk = newRtk();
        rtk.aiPppRtkFixValidator = ctx -> 1.0;
        double[] xCopy = rtk.x.clone();
        double[] pCopy = rtk.P.clone();

        PppRtkAmbFix.ppprtkAmbFix(rtk, null);

        assertEquals(Constants.SOLQ_FIX, rtk.sol.stat);
        assertEquals(1, rtk.nfix);
        double lam = Constants.CLIGHT / Constants.FREQL1;
        int nx = rtk.nx;
        for (int sat : SATS) {
            int idx = PppCore.IB(sat, 0, rtk.opt);
            assertEquals(1, rtk.ssat[sat - 1].fix[0], "放行必须提交 ssat.fix");
            assertBitEqual(0.0, rtk.Pa[idx * nx + idx], "固定行方差必须在 Pa 零化");
            assertBitEqual(0.0, rtk.Pa[idx * nx + idx + 1], "固定行协方差必须在 Pa 零化");
            assertBitEqual(0.0, rtk.Pa[(idx + 1) * nx + idx], "固定列协方差必须在 Pa 零化");
            double cycles = rtk.xa[idx] / lam;
            assertTrue(Math.abs(cycles - Math.rint(cycles)) < 1e-6,
                    "提交的模糊度必须是整数周（sat=" + sat + ", cycles=" + cycles + "）");
        }
        assertBitArrayEqual(xCopy, rtk.x, "非组合模式不得改动浮点解 x（v3.4.0 语义）");
        assertBitArrayEqual(pCopy, rtk.P, "非组合模式不得改动浮点协方差 P（v3.4.0 语义）");
        for (int i = 0; i < 3; i++) {
            assertBitEqual(rtk.xa[i], rtk.sol.rr[i], "sol.rr 必须取固定解位置");
        }
        assertNotNull(rtk.aiPppState);
        assertEquals(0, rtk.aiPppState.consecutive[AiPppState.IF_RTK_FIX]);
    }

    @Test
    @DisplayName("T3：AI 非法输出（null/NaN/越界/异常）一律按不干预处理")
    void invalidOutputNeverBlocksFix() {
        List<PppRtkFixValidator> bad = Arrays.asList(
                ctx -> null,
                ctx -> Double.NaN,
                ctx -> 1.5,
                ctx -> {
                    throw new IllegalStateException("boom");
                });

        for (PppRtkFixValidator v : bad) {
            Rtk rtk = newRtk();
            rtk.aiPppRtkFixValidator = v;

            PppRtkAmbFix.ppprtkAmbFix(rtk, null);

            assertEquals(Constants.SOLQ_FIX, rtk.sol.stat, "非法输出必须按不干预处理（S1）");
            assertEquals(1, rtk.nfix);
            assertEquals(1, rtk.aiPppState.consecutive[AiPppState.IF_RTK_FIX],
                    "非法输出必须计入连续降级");
            assertFalse(rtk.aiPppState.fused[AiPppState.IF_RTK_FIX]);
        }
    }

    @Test
    @DisplayName("T2：拒绝门限读取 AiOpts.pppRtkFixMinConfidence（默认 0.5）")
    void rejectThresholdComesFromAiOpts() {
        Rtk passed = newRtk();
        passed.aiPppRtkFixValidator = ctx -> 0.6;
        PppRtkAmbFix.ppprtkAmbFix(passed, null);
        assertEquals(Constants.SOLQ_FIX, passed.sol.stat, "0.6 >= 默认门限 0.5 必须放行");

        Rtk strict = newRtk();
        strict.rtkConfig.ai.pppRtkFixMinConfidence = 0.9;
        double[] xaRef = strict.xa;
        strict.aiPppRtkFixValidator = ctx -> 0.6;
        PppRtkAmbFix.ppprtkAmbFix(strict, null);
        assertEquals(Constants.SOLQ_FLOAT, strict.sol.stat, "0.6 < 0.9 必须拒绝");
        assertSame(xaRef, strict.xa, "拒绝时 xa 指针不得改变");
        assertEquals(0, strict.nfix);
    }

    @Test
    @DisplayName("T10：接口 D 连续降级达阈值即熔断，熔断期间不调用；重注入解除熔断")
    void breakerOpensAndRecoversOnReinjection() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.ai.pppAiMaxConsecutiveFailures = 2;
        PppRtkFixContext ctx = sampleContext();

        AtomicInteger badCalls = new AtomicInteger();
        rtk.aiPppRtkFixValidator = c -> {
            badCalls.incrementAndGet();
            throw new IllegalStateException("boom");
        };

        assertTrue(PppAiInject.validateRtkFix(rtk, ctx), "异常必须按不干预处理（S1）");
        assertTrue(PppAiInject.validateRtkFix(rtk, ctx), "异常必须按不干预处理（S1）");
        assertEquals(2, badCalls.get());
        assertTrue(rtk.aiPppState.fused[AiPppState.IF_RTK_FIX], "达阈值必须熔断（S5）");

        assertTrue(PppAiInject.validateRtkFix(rtk, ctx), "熔断期间等价未注入（放行）");
        assertEquals(2, badCalls.get(), "熔断期间 provider 不得被调用");

        rtk.aiPppRtkFixValidator = c -> 0.0;
        assertFalse(PppAiInject.validateRtkFix(rtk, ctx), "重注入后恢复判别能力");
        assertFalse(rtk.aiPppState.fused[AiPppState.IF_RTK_FIX], "显式重注入必须解除熔断");
        assertEquals(0, rtk.aiPppState.consecutive[AiPppState.IF_RTK_FIX]);
    }

    @Test
    @DisplayName("ratio 语义：默认门限 3.0 下必须按 次优/最优 计算（次优/最优≈21 通过）")
    void ratioIsSecondBestOverBest() {
        Rtk rtk = newRtk(0.1);
        rtk.rtkConfig.pppRtk.arRatio = 3.0;

        PppRtkAmbFix.ppprtkAmbFix(rtk, null);

        assertEquals(Constants.SOLQ_FIX, rtk.sol.stat,
                "次优/最优≈21 > 3.0，健康场景必须通过 ratio 门限；"
                        + "若按 最优/次优 计算则≈0.05 被误拒（UC 路径恒不固定）");
        assertEquals(1, rtk.nfix);
    }

    @Test
    @DisplayName("ratio 语义：取整歧义场景 次优≈最优，ratio≈1 必须被门限拒绝")
    void ratioRejectsAmbiguousCase() {
        Rtk rtk = newRtk(0.5);
        rtk.rtkConfig.pppRtk.arRatio = 3.0;

        PppRtkAmbFix.ppprtkAmbFix(rtk, null);

        assertEquals(Constants.SOLQ_FLOAT, rtk.sol.stat, "ratio≈1 < 3.0 必须拒绝固定");
        assertEquals(0, rtk.nfix);
    }

    /**
     * 构造可稳定通过 ratio 门限的非组合（UC）场景：4 颗 GPS 单频模糊度，
     * 对角方差 0.01 m²，浮点值偏离整数 0.3 周。ratio 门限置 0 以隔离 AI 判别。
     */
    private static Rtk newRtk() {
        return newRtk(0.3);
    }

    /** 同上，浮点值偏离整数 {@code frac} 周（用于构造不同 ratio 的搜索场景）。 */
    private static Rtk newRtk(double frac) {
        Rtk rtk = new Rtk();
        PrcOpt opt = rtk.opt;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.elmin = 0.0;
        opt.minlock = 0;
        rtk.nx = PppCore.pppnx(opt);
        rtk.rtkConfig.pppRtk.enableAR = true;
        rtk.rtkConfig.pppRtk.arRatio = 0.0;
        rtk.sol.stat = Constants.SOLQ_FLOAT;
        rtk.sol.ns = (byte) SATS.length;

        Arrays.fill(rtk.P, 0.0);
        double lam = Constants.CLIGHT / Constants.FREQL1;
        for (int sat : SATS) {
            int idx = PppCore.IB(sat, 0, opt);
            rtk.ssat[sat - 1].vs = 1;
            rtk.ssat[sat - 1].azel[1] = 30.0 * Constants.D2R;
            rtk.ssat[sat - 1].vsat[0] = 1;
            rtk.ssat[sat - 1].lock[0] = 10;
            rtk.ssat[sat - 1].code[0][0] = Constants.CODE_L1C;
            rtk.P[idx * rtk.nx + idx] = 0.01;
            rtk.x[idx] = frac * lam;
        }
        return rtk;
    }

    private static PppRtkFixContext sampleContext() {
        return new PppRtkFixContext(0, 8, 0.0, 5.0, 0.1, 0.1, 0.1,
                1.0, 1.5, 4.0, 3.0, 4, 1, 0, true);
    }

    private static void assertBitArrayEqual(double[] expected, double[] actual, String what) {
        assertEquals(expected.length, actual.length, what + " 长度不一致");
        for (int i = 0; i < expected.length; i++) {
            assertBitEqual(expected[i], actual[i], what + "[" + i + "]");
        }
    }

    private static void assertBitEqual(double expected, double actual, String message) {
        assertTrue(Double.compare(expected, actual) == 0,
                message + " 逐位不一致: expected=" + expected + ", actual=" + actual);
    }
}
