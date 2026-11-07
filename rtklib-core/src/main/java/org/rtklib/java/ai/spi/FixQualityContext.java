package org.rtklib.java.ai.spi;

/**
 * 接口一输入（固定解产出后的上下文）。
 * <p>
 * {@code epoch}/{@code nf}/{@code nCodeRows}/{@code nPhaseRows} 不直接入特征：
 * {@code epoch} 供 impl 去重与 trace；{@code nf} 供归一化与对账；
 * {@code nCodeRows}/{@code nPhaseRows} 只用于判定 {@code pcrRms}/{@code prrRms}
 * 是否可信（{@code 0} = 占位值而非真实测量）。
 * </p>
 * <p>
 * {@code nfix} 不在本 record 内，且不得添加（§4.4.4）。
 * </p>
 */
public record FixQualityContext(
        int epoch,
        int ns,
        int nf,
        int nb,
        float ratio,
        double pdop,
        double adrRms,
        double prrRms,
        double pcrRms,
        int nCodeRows,
        int nPhaseRows) {
}