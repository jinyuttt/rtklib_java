package org.rtklib.java.ai.spi;

/**
 * 接口四 SPI：参考星风险判别（只否决、不优选，§1.1）。
 *
 * 每历元最多一次批量调用，对 {@code ctx.sats()} 每颗卫星输出「作为参考星的风险分」。
 * core 侧只在风险分超阈值时才考虑降一级切换，其余历元完全不干预（§3.3）。
 */
public interface RefStarValidator {

    /**
     * 批量推理。
     *
     * @param ctx 当前历元快照，{@code ctx.sats()} 顺序即输出行序
     * @return 逐星风险分，取值 {@code [0,1]}，风险越高越不适合当参考星；
     *         返回 {@code null}、长度 {@code != ctx.sats().length}、含非有限值时视为降级
     *         （全部按 {@code 0.0}，等价不干预）
     * @implNote 禁止抛异常：异常由 core 侧捕获并按降级处理（§3.5）；
     *           禁止修改 {@code ctx} 内数组；特征集必须剔除 {@code resp}/{@code resc}（§5.1 H1）。
     */
    double[] refStarRisk(RefStarContext ctx);
}