package org.rtklib.java.ai.spi;

/**
 * 接口四输入（当前历元快照，按 {@code ctx.sats()} 行序）。
 *
 * {@code sats} 的行序等于 {@code relpos} 的 {@code sat[]} 顺序的子集（剔除流动站无几何的
 * 退化行，与接口三同一口径），即 {@link RefStarValidator#refStarRisk(RefStarContext)} 的行序约定。
 * core 负责把行序反查回 {@code sat[]} 下标，impl 不得自行推算。
 *
 * H1 语义约束（§5.1）：{@code sats} 中 {@code resp}/{@code resc} 对参考星恒 0，原样下传
 * 等于标签泄漏。core 侧已在构造时把这两维强制置 0，impl 看不到任何残差标签；
 * 「它当过参考星」的合法信息改由 {@link #refCount()}（wasRef 掩码）显式提供——
 * 近 W 历元内被选为参考星的历元数，impl 无需也不能再用 {@code resp == 0} 反推。
 *
 * @param epoch      历元序号
 * @param ns         参与双差的卫星数（{@code sats} 的原始长度上限，实际行数见 {@code sats.length}）
 * @param nf         频点数（IFLC 时恒 1，此时接口被 core 侧门禁屏蔽，§3.5）
 * @param dt         历元采样间隔（秒），唯一来源，impl 不得自行推算
 * @param sats       单星特征，长度 == 有效行数（已剔除退化行）
 * @param snrWinMean 主频点流动站 SNR 近 W 历元窗口均值（dBHz），长度 == {@code sats.length}
 * @param snrWinStd  主频点流动站 SNR 近 W 历元窗口标准差（dBHz），长度 == {@code sats.length}
 * @param refCount   该星在近 W 历元内被选为参考星的次数（{@code 0..W}），长度 == {@code sats.length}
 */
public record RefStarContext(
        int epoch,
        int ns,
        int nf,
        double dt,
        SatFeature[] sats,
        double[] snrWinMean,
        double[] snrWinStd,
        int[] refCount) {
}