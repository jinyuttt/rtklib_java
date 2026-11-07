package org.rtklib.java.ai.spi;

/**
 * 接口三输出。
 * <p>
 * 行序即索引：{@code idx = i * nf + frq}，{@code i} 为卫星在 {@code relpos} 的
 * {@code sat[]} 中的下标（0-based）。两个数组长度必须都等于 {@code ns * nf}；
 * 任一为 {@code null} 即视为降级。
 * </p>
 * <p>
 * 不含卫星号/频点号字段：带卫星号会迫使 core 做「卫星号 → 下标」映射，
 * 而参考星下标由 {@code ddres} 现场择优，映射一旦写错就是静默错行（§4.4.2）。
 * </p>
 *
 * @param biasMeters    伪距偏差改正量（米），仅对 {@code code} 行生效
 * @param varianceScale 方差膨胀因子，必须 {@code >= 1.0}（单侧膨胀）
 */
public record CodeCorrection(double[] biasMeters, double[] varianceScale) {

    /** 降级/不干预取值。 */
    public static final CodeCorrection NONE = new CodeCorrection(null, null);
}