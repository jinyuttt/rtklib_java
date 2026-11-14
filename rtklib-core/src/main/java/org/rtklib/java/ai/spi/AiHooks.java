package org.rtklib.java.ai.spi;

import org.rtklib.java.data.Rtk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 接口注入点（{@link ThreadLocal} 持有 {@link AiProvider}）。
 * <p>
 * 使用 {@link ThreadLocal} 而非裸 static：保住测试隔离与并行 A/B 能力；
 * 裸 static 会让 I-1 零回归验证退化为「测试顺序相关」。
 * </p>
 * <p>
 * 解析优先级：实例字段（{@link Rtk}）&gt; 注入点；两者都为 {@code null} 即关闭。
 * </p>
 */
public final class AiHooks {

    private static final Logger log = LoggerFactory.getLogger(AiHooks.class);

    private static final ThreadLocal<AiProvider> TL = new ThreadLocal<>();

    private AiHooks() {
    }

    /**
     * 宿主注入。传 {@code null} 即整体关闭。
     *
     * @param provider AI 提供者，可为 {@code null}
     */
    public static void install(AiProvider provider) {
        if (provider == null) {
            TL.remove();
        } else {
            log.info("AI 接口已注入: {}（行为对象不随 RtkConfig 传播，须由使用方显式注入）",
                    provider.getClass().getName());
            TL.set(provider);
        }
    }

    /** 清除注入，恢复 v3.4.0 原生行为（测试 teardown 必须调用）。 */
    public static void clear() {
        TL.remove();
    }

    /** 当前线程已注入的 provider，未注入返回 {@code null}。 */
    public static AiProvider provider() {
        return TL.get();
    }

    /**
     * 解析接口一实现。
     *
     * @param rtk 当前解算实例，可为 {@code null}
     * @return 实例字段优先；否则取注入点；都为空返回 {@code null}（不干预）
     */
    public static FixValidator resolveFixValidator(Rtk rtk) {
        if (rtk != null) {
            validateAiOpts(rtk);
            if (rtk.aiFixValidator != null) return rtk.aiFixValidator;
        }
        AiProvider p = TL.get();
        return p == null ? null : p.fixValidator();
    }

    /**
     * 解析接口三实现。
     *
     * @param rtk 当前解算实例，可为 {@code null}
     * @return 实例字段优先；否则取注入点；都为空返回 {@code null}（不干预）
     */
    public static ObsCorrector resolveObsCorrector(Rtk rtk) {
        if (rtk != null) {
            validateAiOpts(rtk);
            if (rtk.aiObsCorrector != null) return rtk.aiObsCorrector;
        }
        AiProvider p = TL.get();
        return p == null ? null : p.obsCorrector();
    }

    /**
     * 解析接口四实现。
     *
     * @param rtk 当前解算实例，可为 {@code null}
     * @return 实例字段优先；否则取注入点；都为空返回 {@code null}（不干预）
     */
    public static RefStarValidator resolveRefStarValidator(Rtk rtk) {
        if (rtk != null) {
            validateAiOpts(rtk);
            if (rtk.aiRefStarValidator != null) return rtk.aiRefStarValidator;
        }
        AiProvider p = TL.get();
        return p == null ? null : p.refStarValidator();
    }

    private static void validateAiOpts(Rtk rtk) {
        if (rtk.rtkConfig != null && rtk.rtkConfig.ai != null) {
            rtk.rtkConfig.ai.validate();
        }
    }
}