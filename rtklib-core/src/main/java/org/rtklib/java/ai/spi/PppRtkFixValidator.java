package org.rtklib.java.ai.spi;

/**
 * 接口 D SPI：PPP-RTK 固定校验（只否决、不优选，§5）。
 *
 * <p>core 侧在 {@code PppRtkAmbFix} 的「算-验-提交」三段式中间调用：固定候选已算好
 * 但尚未提交，provider 只决定「这个固定解是否可信」，不参与候选生成，也不能改写
 * 候选或任何求解状态（S2）。</p>
 *
 * <p>失效安全（S1）：未注入、熔断、{@code null}、NaN/越界、异常、超预算一律按
 * 「不干预」处理——即保留确定性固定解，AI 只能否决、不能把浮点解升级为固定解。</p>
 */
public interface PppRtkFixValidator {

    /**
     * 单值推理。
     *
     * @param ctx 当前历元快照，只读
     * @return 固定解置信度，取值 {@code [0,1]}；低于 {@code pppRtkFixMinConfidence} 时
     *         core 侧丢弃固定候选、保持浮点解。返回 {@code null} / NaN / 越界视为非法输出，
     *         由 core 侧按不干预（保留固定）处理（S1）
     * @implNote 禁止抛异常：异常由 core 侧捕获并按降级处理（S1）；
     *           禁止修改 {@code ctx} 内数组，禁止写 {@code rtk} 任何字段
     */
    Double confidence(PppRtkFixContext ctx);
}