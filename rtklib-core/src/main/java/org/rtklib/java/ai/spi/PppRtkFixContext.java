package org.rtklib.java.ai.spi;

/**
 * 接口 D（PPP-RTK 固定校验）输入（当前历元快照，只读）。
 *
 * <p>所有字段在 AR 计算完成、提交状态之前取值，因此描述的是「即将被提交的固定解」，
 * 而不是提交后的状态（§5.4）。特征取值语义：</p>
 *
 * @param epoch        历元号（{@code rtk.epoch}）
 * @param ns           参与解算的卫星数（{@code sol.ns}）
 * @param ratioWl      宽巷 ratio（IFLC 逐星取整路径无 ratio 语义，为 {@code 0.0}；
 *                     非组合模式未走 WL 级，为 {@code NaN}）
 * @param ratioNl      窄巷 ratio（IFLC 逐星取整路径无 ratio 语义，为 {@code 0.0}；
 *                     非组合模式为 LAMBDA 次优/最优残差平方和比值）
 * @param maxDevWl     候选宽巷取整偏差最大值（周；非组合模式为 {@code NaN}）
 * @param maxDevNl     候选窄巷取整偏差最大值（周）
 * @param postResidualRms 固定后验残差 RMS（窄巷周；固定模糊度与浮点模糊度之差）
 * @param positionVar  ECEF X/Y/Z 位置状态对角方差之和（{@code sol.qr[0..2]}）
 * @param pdop         当前历元 PDOP（{@code sol.pdop}）
 * @param prevRatio1   上一历元 ratio（{@code sol.prev_ratio1}）
 * @param prevRatio2   上上历元 ratio（{@code sol.prev_ratio2}）
 * @param nFixed       本次固定候选的模糊度个数
 * @param arAttempts   本历元 AR 尝试次数（本次固定尝试计 1）
 * @param arFixCount   已连续固定的历元数（{@code rtk.nfix}）
 * @param ratioPassed  ratio 门限是否已通过（注入点执行时恒为 {@code true}）
 */
public record PppRtkFixContext(
        int epoch,
        int ns,
        double ratioWl,
        double ratioNl,
        double maxDevWl,
        double maxDevNl,
        double postResidualRms,
        double positionVar,
        double pdop,
        double prevRatio1,
        double prevRatio2,
        int nFixed,
        int arAttempts,
        int arFixCount,
        boolean ratioPassed) {
}