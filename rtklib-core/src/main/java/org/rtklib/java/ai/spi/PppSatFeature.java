package org.rtklib.java.ai.spi;

/**
 * 接口 B 单星特征。
 *
 * 所有长度 {@code nf} 的数组均由 core 按 {@code nf} 分配并填充，impl 不得写入。
 *
 * {@code resp} / {@code resc} 是**非差**语义，必须与接口三的双差残差严格区分（§4.3）：
 * 双差已消掉接收机钟差与卫星钟差，非差没有。两个接口的训练集不可互换、不可拼接。
 *
 * @param sat   卫星号，仅用于 trace/调试，不参与索引
 * @param sys   卫星系统码（{@code SatUtils.satsys(sat, null)} 的返回值）
 * @param az    方位角（弧度）
 * @param el    高度角（弧度）
 * @param snr   逐频 SNR（dBHz），长度 {@code nf}
 * @param resp  非差伪距残差（米），长度 {@code nf}；该频点未参与解算时为 0.0
 * @param resc  非差载波残差（米），长度 {@code nf}；该频点未参与解算时为 0.0
 * @param lock  锁相时长（秒），长度 {@code nf}
 * @param slip  周跳标志（位标志，非布尔），长度 {@code nf}
 * @param half  半周标志（位标志，非布尔），长度 {@code nf}
 * @param vsat  该频点是否参与解算（0/1），长度 {@code nf}
 */
public record PppSatFeature(
        int sat,
        int sys,
        double az,
        double el,
        double[] snr,
        double[] resp,
        double[] resc,
        int[] lock,
        int[] slip,
        int[] half,
        int[] vsat) {
}