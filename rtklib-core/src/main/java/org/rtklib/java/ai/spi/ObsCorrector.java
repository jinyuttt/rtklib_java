package org.rtklib.java.ai.spi;

/**
 * 接口三 SPI：伪距偏差改正。
 * <p>
 * 每历元只允许一次批量调用，覆盖 {@code ns * nf} 个「卫星-频点」组合；
 * 禁止逐卫星回调（§3.3 性能硬约束）。
 * </p>
 */
public interface ObsCorrector {

    /**
     * 批量推理。
     *
     * @param ctx 当前历元快照，{@code ctx.sats()} 顺序即输出行序
     * @return 改正量；返回 {@code null}，或任一数组长度 {@code != ctx.ns() * ctx.nf()}
     *         时视为降级（{@code bias = 0}、{@code scale = 1}）
     * @implNote 禁止抛异常：异常由 core 侧捕获并按降级处理（§3.4）；
     *           禁止修改 {@code ctx} 内数组。
     */
    CodeCorrection correct(ObsCorrectionContext ctx);
}