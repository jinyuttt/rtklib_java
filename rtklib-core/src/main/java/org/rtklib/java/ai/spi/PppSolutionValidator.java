package org.rtklib.java.ai.spi;

/**
 * 接口 A SPI：PPP 解有效性判别（只降级、不提升，§3）。
 *
 * 每历元最多一次单值推理，返回 {@code [0,1]} 的解有效性置信度。
 * core 侧只在 {@code sol.stat == SOLQ_PPP} 且置信度低于
 * {@code AiOpts.pppSolutionMinConfidence} 时把该历元降级为 {@code SOLQ_NONE}；
 * 其余历元完全不干预（§3.6）。
 */
public interface PppSolutionValidator {

    /**
     * 单值推理。
     *
     * @param ctx 当前历元快照，只读
     * @return 解有效性置信度，取值 {@code [0,1]}；
     *         返回 {@code null} / {@code NaN} / 非有限值视为降级（按 1.0，不干预）
     * @implNote 禁止抛异常：异常由 core 侧捕获并按降级处理（§3.6 / S1）；
     *           禁止修改 {@code ctx} 内数组；禁止写 {@code sol.rr} / {@code sol.qr} /
     *           {@code rtk.x} / {@code rtk.P} / {@code Ssat.fix}（S2）。
     */
    Double confidence(PppSolutionContext ctx);
}