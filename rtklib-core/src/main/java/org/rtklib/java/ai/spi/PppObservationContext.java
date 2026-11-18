package org.rtklib.java.ai.spi;

/**
 * 接口 B 输入（历元级批量快照）。
 *
 * {@code sats} 长度等于 {@code ns}，顺序即 {@link PppVarianceScaler} 输出的
 * {@code ns × nf} 行主序（{@code idx = satIndex * nf + frq}）。core 侧再把该行序
 * 映射到 {@code pppRes} 的「被接受观测递增顺序」（§4.4）。
 *
 * @param epoch 历元号
 * @param ns    上下文中的卫星数
 * @param nf    频点数
 * @param dt    采样间隔（秒），唯一来源，impl 不得自行推算
 * @param sats  单星特征，长度 == {@code ns}
 * @param nominalSigma 逐星逐频标称标准差 σ（米），长度 == {@code ns × nf}，
 *                     主序 {@code idx = satIndex * nf + frq}；该频点未参与解算时为 {@code NaN}。
 *                     唯一来源是 core 的观测方差对角线（{@code sqrt(varr[row])}），
 *                     推理侧与采集侧只读，不得自行推算。
 */
public record PppObservationContext(
        int epoch,
        int ns,
        int nf,
        double dt,
        PppSatFeature[] sats,
        double[] nominalSigma) {
}
