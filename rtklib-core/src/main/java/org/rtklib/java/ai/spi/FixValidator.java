package org.rtklib.java.ai.spi;

/**
 * 接口一 SPI：固定质量校验。
 * <p>
 * 只输出连续置信度，不输出布尔判定；阈值判定永远在 core 侧由
 * {@code AiOpts.fixValidatorMinConfidence} 完成（D9），调阈值不需要换模型。
 * </p>
 */
public interface FixValidator {

    /**
     * 固定质量校验。
     *
     * @param ctx 固定解产出后的 6 维残差类上下文
     * @return 置信度，范围 {@code [0,1]}；{@code 1.0} = 完全信任（等价于不干预）
     * @implNote 禁止抛异常：异常由 core 侧捕获并按 {@code 1.0} 处理（§3.4 降级）；
     *           禁止在本方法内修改任何 {@code Rtk}/{@code Ssat} 状态。
     */
    double validate(FixQualityContext ctx);
}