package org.rtklib.java.ppp;

import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.PppRtkFixValidator;
import org.rtklib.java.ai.spi.PppSolutionValidator;
import org.rtklib.java.ai.spi.PppVarianceScaler;
import org.rtklib.java.config.AiOpts;
import org.rtklib.java.data.AiPppState;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.trace.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PPP 体系 AI 三接口的失效安全运行时（§1.4 S1–S6）。
 *
 * 只做四件事：解析（含熔断短路）、预算度量、降级/成功记账、trace。
 * 所有降级路径统一回落到「不干预」，绝不 fail-closed（S1）。
 */
final class PppAiGuard {

    private static final Logger LOG = LoggerFactory.getLogger(PppAiGuard.class);

    /** trace 主题（须由 {@code TraceConfig.topics} 显式打开才输出）。 */
    static final String TOPIC = "AI_PPP";

    private PppAiGuard() {
    }

    /** 惰性分配簿记状态：仅在确有 provider 时调用，未注入时保持 {@code null}（I-1）。 */
    static AiPppState state(Rtk rtk) {
        if (rtk.aiPppState == null) {
            rtk.aiPppState = new AiPppState();
        }
        return rtk.aiPppState;
    }

    static PppSolutionValidator solutionValidator(Rtk rtk) {
        return (PppSolutionValidator) guard(rtk, AiPppState.IF_SOLUTION,
                AiHooks.resolvePppSolutionValidator(rtk));
    }

    static PppVarianceScaler varianceScaler(Rtk rtk) {
        return (PppVarianceScaler) guard(rtk, AiPppState.IF_VARIANCE,
                AiHooks.resolvePppVarianceScaler(rtk));
    }

    static PppRtkFixValidator rtkFixValidator(Rtk rtk) {
        return (PppRtkFixValidator) guard(rtk, AiPppState.IF_RTK_FIX,
                AiHooks.resolvePppRtkFixValidator(rtk));
    }

    /**
     * 熔断短路 + 重注入识别。
     *
     * 熔断期间 provider 不再被调用（等价未注入）；仅当解析到的实例与熔断时刻的实例
     * 不同（显式重注入）才解除熔断（S5）。
     */
    private static Object guard(Rtk rtk, int iface, Object provider) {
        if (provider == null) {
            return null;
        }
        AiPppState st = state(rtk);
        if (st.fused[iface]) {
            if (st.fusedProvider[iface] != null && st.fusedProvider[iface] != provider) {
                st.fused[iface] = false;
                st.consecutive[iface] = 0;
                st.fusedProvider[iface] = null;
                LOG.info("PPP AI 接口 {} 检测到重注入实例，解除熔断（S5）", name(iface));
                Trace.emit(TOPIC, "AI_UNFUSED", rtk.traceConfig, rtk.traceCallback,
                        rtk.epoch, rtk.sol.time, "interface", name(iface));
            } else {
                return null;
            }
        }
        st.provider[iface] = provider;
        return provider;
    }

    /** 单次推理耗时预算（纳秒）；{@code 0} 表示不做预算度量（{@code pppAiMaxInferenceMs <= 0}）。 */
    static long budgetNanos(Rtk rtk) {
        AiOpts ai = aiOpts(rtk);
        double ms = ai == null
                ? AiOpts.DEFAULT_PPP_AI_MAX_INFERENCE_MS : ai.pppAiMaxInferenceMs;
        if (!Double.isFinite(ms) || ms <= 0.0) {
            return 0L;
        }
        return (long) (ms * 1_000_000.0);
    }

    /** 预算是否已超（{@code budget <= 0} 时恒 {@code false}）。 */
    static boolean overBudget(long startNanos, long budget) {
        return budget > 0L && (System.nanoTime() - startNanos) > budget;
    }

    /** 成功记账：连续降级计数归零（S5 的显式恢复条件之一）。 */
    static void onSuccess(Rtk rtk, int iface) {
        AiPppState st = state(rtk);
        if (st.consecutive[iface] != 0) {
            st.consecutive[iface] = 0;
        }
    }

    /**
     * 降级记账：连续降级累加、告警去重、达上限即熔断。
     *
     * @param reason 降级原因（{@code exception} / {@code non_finite} / {@code invalid_output} /
     *               {@code over_budget}），仅用于 trace 与日志
     */
    static void onDegrade(Rtk rtk, int iface, String reason) {
        AiPppState st = state(rtk);
        st.consecutive[iface]++;
        if (traceDegrades(rtk) && !st.degradeLogged) {
            st.degradeLogged = true;
            Trace.emit(TOPIC, "AI_DEGRADE", rtk.traceConfig, rtk.traceCallback,
                    rtk.epoch, rtk.sol.time, "interface", name(iface), "reason", reason);
        }
        int maxFail = maxConsecutiveFailures(rtk);
        if (!st.fused[iface] && st.consecutive[iface] >= maxFail) {
            st.fused[iface] = true;
            st.fusedProvider[iface] = st.provider[iface];
            LOG.warn("PPP AI 接口 {} 连续 {} 个历元降级，本会话熔断（等价未注入，S5）: epoch={}",
                    name(iface), st.consecutive[iface], rtk.epoch);
            if (!st.fusedLogged) {
                st.fusedLogged = true;
                Trace.emit(TOPIC, "AI_FUSED", rtk.traceConfig, rtk.traceCallback,
                        rtk.epoch, rtk.sol.time, "interface", name(iface),
                        "consecutive", st.consecutive[iface]);
            }
        }
    }

    /** 接口 B 的方差膨胀因子单侧钳位：非有限或 {@code < 1.0} 按 1.0，超上限钳到上限并告警一次。 */
    static double clampScale(Rtk rtk, double s) {
        AiOpts ai = aiOpts(rtk);
        double max = ai == null
                ? AiOpts.DEFAULT_PPP_VARIANCE_SCALE_MAX : ai.pppVarianceScaleMax;
        if (!Double.isFinite(s) || s < 1.0) {
            return 1.0;
        }
        if (s > max) {
            AiPppState st = state(rtk);
            if (!st.scaleClampLogged) {
                st.scaleClampLogged = true;
                LOG.warn("PPP 方差膨胀因子越界({} > {})，钳到上限（§4.4）: epoch={}",
                        s, max, rtk.epoch);
            }
            return max;
        }
        return s;
    }

    static AiOpts aiOpts(Rtk rtk) {
        return rtk.rtkConfig == null ? null : rtk.rtkConfig.ai;
    }

    static boolean traceDegrades(Rtk rtk) {
        AiOpts ai = aiOpts(rtk);
        return ai == null || ai.traceDegrades;
    }

    /** 接口 A 置信度阈值；未配置时取默认值。 */
    static double solutionMinConfidence(Rtk rtk) {
        AiOpts ai = aiOpts(rtk);
        return ai == null
                ? AiOpts.DEFAULT_PPP_SOLUTION_MIN_CONFIDENCE : ai.pppSolutionMinConfidence;
    }

    /** 接口 D 置信度阈值；未配置时取默认值。 */
    static double rtkFixMinConfidence(Rtk rtk) {
        AiOpts ai = aiOpts(rtk);
        return ai == null
                ? AiOpts.DEFAULT_PPP_RTK_FIX_MIN_CONFIDENCE : ai.pppRtkFixMinConfidence;
    }

    private static int maxConsecutiveFailures(Rtk rtk) {
        AiOpts ai = aiOpts(rtk);
        return ai == null
                ? AiOpts.DEFAULT_PPP_AI_MAX_CONSECUTIVE_FAILURES
                : ai.pppAiMaxConsecutiveFailures;
    }

    static String name(int iface) {
        switch (iface) {
            case AiPppState.IF_SOLUTION: return "ppp_solution_validator";
            case AiPppState.IF_VARIANCE: return "ppp_variance_scaler";
            case AiPppState.IF_RTK_FIX: return "ppp_rtk_fix_validator";
            default: return "unknown";
        }
    }
}