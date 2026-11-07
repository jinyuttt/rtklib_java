package org.rtklib.java.ai.spi;

/**
 * 接口三输入（当前历元快照）。
 * <p>
 * {@code sats} 长度等于 {@code ns}，顺序等于 {@code relpos} 的 {@code sat[]} 顺序，
 * 即 {@link CodeCorrection} 的行序约定。短窗历史由 impl 自行维护，core 不提供。
 * </p>
 *
 * @param epoch 历元序号
 * @param ns    参与双差的卫星数
 * @param nf    频点数（IFLC 时恒 1，此时接口被 core 侧门禁屏蔽，§4.3.7）
 * @param dt    采样间隔（秒），唯一来源，impl 不得自行推算
 * @param sats  单星特征，长度 == {@code ns}
 */
public record ObsCorrectionContext(
        int epoch,
        int ns,
        int nf,
        double dt,
        SatFeature[] sats) {
}