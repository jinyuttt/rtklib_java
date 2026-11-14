package org.rtklib.java.ai.spi;

/**
 * AI 接口注入回调（宿主唯一需要实现/注入的类型）。
 * <p>
 * core 侧只定义「什么时候问」，不定义「谁回答」：本接口不引用任何
 * {@code rtklib-ai} 类型，实现方通过 {@link AiHooks#install(AiProvider)} 注入。
 * 未注入（{@link AiHooks} 取值为 {@code null}）时，core 行为与 v3.4.0 逐位一致（I-1）。
 * </p>
 */
public interface AiProvider {

    /** 接口一：固定质量校验。返回 {@code null} = 本 provider 不提供接口一。 */
    FixValidator fixValidator();

    /** 接口三：伪距偏差改正。返回 {@code null} = 本 provider 不提供接口三。 */
    ObsCorrector obsCorrector();

    /**
     * 接口四：参考星风险判别。返回 {@code null} = 本 provider 不提供接口四。
     *
     * 默认实现返回 {@code null}：接口四是实验 provider（D20），默认关闭、仅显式注入。
     * 既有实现不必改动即自动「不提供」，core 侧行为与未注入逐位一致（I-1）。
     */
    default RefStarValidator refStarValidator() {
        return null;
    }
}