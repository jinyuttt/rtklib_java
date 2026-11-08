package org.rtklib.java.ai.spi;

/**
 * 接口三单星特征。
 * <p>
 * 所有长度 {@code nf} 的数组均由 core 按 {@code nf} 分配并填充，impl 不得写入。
 * </p>
 *
 * @param sat       卫星号，仅用于 trace/调试，不参与索引
 * @param sys       卫星系统码（{@code SatUtils.satsys(sat, null)} 的返回值，是 {@code SYS_??} 位掩码）
 * @param az        方位角（弧度），取自 {@code relpos} 工作缓冲区 {@code azel}
 * @param el        高度角（弧度），同上
 * @param snrRover  流动站 SNR（dBHz）
 * @param snrBase   基准站 SNR（dBHz）
 * @param resp      上一历元伪距残差（米），参考星恒 0
 * @param resc      上一历元载波残差（米），参考星恒 0
 * @param lock      锁相时长（秒）
 * @param slip      周跳标志（位标志，非布尔）
 * @param half      半周标志（位标志，非布尔）
 * @param gf        几何无关组合值（当历元值，非变化率）
 * @param vsat      卫星在频率 f 上是否有效（0/1）
 * @param fix       模糊度状态：0 未固定 / 1 浮点 / 2 固定 / 3 已锚定
 */
public record SatFeature(
        int sat,
        int sys,
        double az,
        double el,
        double[] snrRover,
        double[] snrBase,
        double[] resp,
        double[] resc,
        int[] lock,
        int[] slip,
        int[] half,
        double[] gf,
        int[] vsat,
        int[] fix) {
}