package org.rtklib.java.ai.spi;

/**
 * 接口 B SPI：PPP 观测方差缩放（只膨胀、不收缩，§4）。
 *
 * 每历元最多一次批量推理，输出逐观测行的方差膨胀因子，core 侧施加到
 * {@code pppRes} 装配的 R 矩阵对角元（{@code R[j*nv+j] = varr[j] * scale}，§4.4）。
 * 施加方向单侧：{@code scale < 1.0} 或非有限按 1.0，超上限钳到
 * {@code AiOpts.pppVarianceScaleMax}（§4.6）。
 */
public interface PppVarianceScaler {

    /**
     * 批量推理。
     *
     * @param ctx 当前历元快照，{@code ctx.sats()} 顺序即输出行序
     * @return 逐观测方差膨胀因子，行主序长度 {@code ctx.ns() * ctx.nf()}，取值 {@code [1.0, +∞)}；
     *         返回 {@code null}、长度不符、含非有限值均视为降级（全部按 1.0，不干预）
     * @implNote 禁止抛异常：异常由 core 侧捕获并按降级处理（§4.6 / S1）；
     *           禁止修改 {@code ctx} 内数组；无 {@code biasMeters}——PPP 偏差会被
     *           {@code IB(sat, frq)} 状态吸收，写偏差进状态会污染 AR 输入（§4.4）。
     */
    double[] varianceScale(PppObservationContext ctx);
}