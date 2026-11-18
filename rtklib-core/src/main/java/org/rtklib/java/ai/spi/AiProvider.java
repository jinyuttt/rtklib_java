package org.rtklib.java.ai.spi;

/**
 * AI 接口注入回调（宿主唯一需要实现/注入的类型）。
 *
 * core 侧只定义「什么时候问」，不定义「谁回答」：本接口不引用任何
 * {@code rtklib-ai} 类型，实现方通过 {@link AiHooks#install(AiProvider)} 注入。
 * 未注入（{@link AiHooks} 取值为 {@code null}）时，core 行为与 v3.4.0 逐位一致（I-1）。
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

    /**
     * 接口 A：PPP 解有效性判别（§3）。返回 {@code null} = 本 provider 不提供接口 A。
     *
     * 默认实现返回 {@code null}：未提供即「不干预」，core 侧行为与未注入逐位一致（I-1）。
     */
    default PppSolutionValidator pppSolutionValidator() {
        return null;
    }

    /**
     * 接口 B：PPP 观测方差缩放（§4）。返回 {@code null} = 本 provider 不提供接口 B。
     *
     * 默认实现返回 {@code null}：未提供即「不干预」，core 侧行为与未注入逐位一致（I-1）。
     */
    default PppVarianceScaler pppVarianceScaler() {
        return null;
    }

    /**
     * 接口 D：PPP-RTK 固定校验（§5）。返回 {@code null} = 本 provider 不提供接口 D。
     *
     * 默认实现返回 {@code null}：本版本 core 侧无 D 注入点（§5.2 施加原子性阻塞），
     * 仅在训练侧与契约测试落地。
     */
    default PppRtkFixValidator pppRtkFixValidator() {
        return null;
    }
}