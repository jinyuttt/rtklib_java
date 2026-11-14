package org.rtklib.java.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.RefStarContext;
import org.rtklib.java.ai.spi.RefStarValidator;
import org.rtklib.java.ai.spi.SatFeature;
import org.rtklib.java.config.AiOpts;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Rtk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口四（参考星风险判别）架构契约。
 *
 * 本测试不需要任何观测数据、不启用任何 AI 特性，只验证「接线是否通」：
 * 未注入时接口四不存在且不分配任何状态（I-1 零回归），显式注入后可沿
 * 注入点 → 解析 → 批量推理 完整可达。物理效果（是否真的换了参考星）
 * 由后续 R2-a/R2-b 评估实验负责，不在本测试范围内。
 */
@DisplayName("接口四契约：不注入即零回归，注入即可达")
class RefStarContractTest {

    @AfterEach
    void tearDown() {
        AiHooks.clear();
        assertNull(AiHooks.provider(), "teardown 后注入点必须为空");
    }

    private static AiProvider providerOf(RefStarValidator v) {
        return new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return null;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return null;
            }

            @Override
            public RefStarValidator refStarValidator() {
                return v;
            }
        };
    }

    @Test
    @DisplayName("默认关闭：未注入时解析为 null，且 Rtk 不分配接口四状态")
    void noInjectionMeansNoInterfaceFour() {
        assertNull(AiHooks.provider());
        assertNull(AiHooks.resolveRefStarValidator(null), "无注入点且无实例字段时必须为 null");

        Rtk rtk = new Rtk();
        assertNull(rtk.aiRefStarValidator, "实例字段默认必须为 null");
        assertNull(rtk.aiRefStarState, "未注入时不得分配窗口状态（I-1 零回归）");
        assertNull(AiHooks.resolveRefStarValidator(rtk), "未注入时必须解析为 null，core 侧不进入任何分支");

        assertNull(AiHooks.resolveFixValidator(rtk), "接口一不受接口四影响");
        assertNull(AiHooks.resolveObsCorrector(rtk), "接口三不受接口四影响");
    }

    @Test
    @DisplayName("AiProvider 默认方法：既有 provider 不实现接口四也不破坏编译与语义")
    void providerDefaultReturnsNull() {
        AiProvider legacy = new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return null;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return null;
            }
        };
        assertNull(legacy.refStarValidator(), "默认实现必须返回 null = 不提供接口四");

        AiHooks.install(legacy);
        assertNotNull(AiHooks.provider());
        assertNull(AiHooks.resolveRefStarValidator(new Rtk()), "老 provider 注入后接口四仍为关闭");
    }

    @Test
    @DisplayName("显式注入即可达：注入点 → 解析 → 批量推理")
    void injectionReachesValidator() {
        RefStarValidator v = ctx -> {
            double[] r = new double[ctx.sats().length];
            for (int i = 0; i < r.length; i++) {
                r[i] = (i == 0) ? 0.9 : 0.1;
            }
            return r;
        };
        AiHooks.install(providerOf(v));

        assertSame(v, AiHooks.resolveRefStarValidator(null));
        assertSame(v, AiHooks.resolveRefStarValidator(new Rtk()));

        RefStarContext ctx = sampleContext(3);
        double[] risk = AiHooks.resolveRefStarValidator(new Rtk()).refStarRisk(ctx);
        assertEquals(3, risk.length);
        assertEquals(0.9, risk[0], 0.0);
        assertEquals(0.1, risk[1], 0.0);
    }

    @Test
    @DisplayName("实例级覆盖优先于注入点")
    void instanceOverridesInjection() {
        RefStarValidator injected = ctx -> new double[ctx.sats().length];
        RefStarValidator instance = ctx -> new double[ctx.sats().length];

        AiHooks.install(providerOf(injected));
        Rtk rtk = new Rtk();
        assertSame(injected, AiHooks.resolveRefStarValidator(rtk));

        rtk.aiRefStarValidator = instance;
        assertSame(instance, AiHooks.resolveRefStarValidator(rtk), "实例字段必须优先于注入点");

        rtk.aiRefStarValidator = null;
        assertSame(injected, AiHooks.resolveRefStarValidator(rtk), "实例字段清空后回落到注入点");
    }

    @Test
    @DisplayName("上下文契约：行序即 sats 顺序，三组窗口数组与 sats 等长")
    void contextShape() {
        RefStarContext ctx = sampleContext(4);
        assertEquals(4, ctx.sats().length);
        assertEquals(4, ctx.snrWinMean().length);
        assertEquals(4, ctx.snrWinStd().length);
        assertEquals(4, ctx.refCount().length);
        assertEquals(Constants.NFREQ, ctx.nf());
        assertEquals(30.0, ctx.dt(), 0.0);

        for (SatFeature s : ctx.sats()) {
            assertNotNull(s.snrRover());
            assertEquals(Constants.NFREQ, s.snrRover().length);
        }
        assertArrayEquals(new int[]{0, 1, 0, 2}, ctx.refCount());
    }

    @Test
    @DisplayName("AiOpts 阈值：非法值一律钳到保守侧，不抛异常")
    void optsClamp() {
        AiOpts ai = new AiOpts();
        assertEquals(AiOpts.DEFAULT_REF_STAR_RISK_MAX, ai.refStarRiskMax, 0.0);
        assertEquals(AiOpts.DEFAULT_REF_STAR_HOLD_EPOCHS, ai.refStarHoldEpochs);
        assertEquals(AiOpts.DEFAULT_REF_STAR_FALLBACK_TOPK, ai.refStarFallbackTopK);
        assertEquals(AiOpts.DEFAULT_REF_STAR_MAX_EL_DROP, ai.refStarMaxElDrop, 0.0);
        assertEquals(AiOpts.DEFAULT_REF_STAR_SWITCH_RATE_MAX, ai.refStarSwitchRateMax, 0.0);
        assertEquals(AiOpts.DEFAULT_REF_STAR_WINDOW_EPOCHS, ai.refStarWindowEpochs);

        ai.refStarRiskMax = 1.5;
        ai.refStarHoldEpochs = -1;
        ai.refStarFallbackTopK = 0;
        ai.refStarMaxElDrop = 120.0;
        ai.refStarSwitchRateMax = 2.0;
        ai.refStarWindowEpochs = 0;
        ai.validate();

        assertEquals(1.0, ai.refStarRiskMax, 0.0, "风险阈值超 1 钳到 1.0");
        assertEquals(0, ai.refStarHoldEpochs, "迟滞历元数不允许为负");
        assertEquals(1, ai.refStarFallbackTopK, "候选池至少 1，但 1 表示不允许降级（无第 2 名）");
        assertEquals(90.0, ai.refStarMaxElDrop, 0.0, "高度角降幅上限 90 度");
        assertEquals(1.0, ai.refStarSwitchRateMax, 0.0, "切换率上限 100%");
        assertEquals(1, ai.refStarWindowEpochs, "窗口至少 1 历元");

        ai.refStarRiskMax = Double.NaN;
        ai.refStarMaxElDrop = Double.NaN;
        ai.validate();
        assertEquals(AiOpts.DEFAULT_REF_STAR_RISK_MAX, ai.refStarRiskMax, 0.0, "NaN 回退默认值");
        assertEquals(AiOpts.DEFAULT_REF_STAR_MAX_EL_DROP, ai.refStarMaxElDrop, 0.0, "NaN 回退默认值");
    }

    @Test
    @DisplayName("默认阈值保守性：不用 0.5，切换率 2%，迟滞 15 历元")
    void defaultsAreConservative() {
        AiOpts ai = new AiOpts();
        assertTrue(ai.refStarRiskMax > 0.5, "误换代价高于漏判，阈值必须偏保守");
        assertEquals(0.02, ai.refStarSwitchRateMax, 0.0);
        assertEquals(15, ai.refStarHoldEpochs);
        assertEquals(3, ai.refStarFallbackTopK, "候选池 3，但只允许降一级到第 2 名（R2-b）");
        assertFalse(ai.enableAggressiveAi);
    }

    private static RefStarContext sampleContext(int n) {
        SatFeature[] sats = new SatFeature[n];
        double[] mean = new double[n];
        double[] std = new double[n];
        int[] refCount = new int[]{0, 1, 0, 2};
        for (int i = 0; i < n; i++) {
            sats[i] = new SatFeature(
                    i + 1,
                    Constants.SYS_GPS,
                    0.5,
                    Math.toRadians(45.0),
                    new double[Constants.NFREQ],
                    new double[Constants.NFREQ],
                    new double[Constants.NFREQ],
                    new double[Constants.NFREQ],
                    new int[Constants.NFREQ],
                    new int[Constants.NFREQ],
                    new int[Constants.NFREQ],
                    new double[Constants.NFREQ],
                    new int[Constants.NFREQ],
                    new int[Constants.NFREQ]);
            mean[i] = 40.0 + i;
            std[i] = 1.0 + i;
        }
        int[] rc = new int[n];
        System.arraycopy(refCount, 0, rc, 0, Math.min(n, refCount.length));
        return new RefStarContext(1, n, Constants.NFREQ, 30.0, sats, mean, std, rc);
    }
}