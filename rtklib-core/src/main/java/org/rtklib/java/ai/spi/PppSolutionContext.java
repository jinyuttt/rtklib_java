package org.rtklib.java.ai.spi;

/**
 * 接口 A（PPP 解有效性判别）输入（当前历元快照）。
 *
 * 所有数组由 core 侧分配并填充，impl 只读；禁止在 impl 内写回任何数组或
 * {@code Rtk} / {@code Ssat} 状态（§3.3）。
 *
 * {@code deterministicDegraded} 是**归因字段**，不改变注入点行为：core 侧无论确定性基线
 * 分支是否启用，都按 {@code posVar > pppQrThreshold} 计算该标志（§3.5），
 * 供评估区分「确定性拦的」与「AI 独立拦的」。
 *
 * @param epoch                历元号
 * @param ns                   参与解算的卫星数（{@code elevation} / {@code snr} 的长度）
 * @param nf                   频点数（仅作上下文说明，不索引本结构数组）
 * @param positionVar          ECEF X/Y/Z 位置状态对角方差（{@code sol.qr[0..2]}），长度 3
 * @param residuals            PPP 非差验后伪距残差（米），长度 = 有效观测行数
 * @param elevation            逐星高度角（弧度），长度 == {@code ns}
 * @param snr                  逐星 SNR（dBHz），长度 == {@code ns}
 * @param convergenceMetric    滤波收敛度（新息 RMS，米；不可用时为 0.0）
 * @param biasState            偏差状态标志（已初始化非差 bias 状态的卫星数）
 * @param deterministicDegraded 确定性 {@code qr} 基线是否已把本历元判为不可信
 */
public record PppSolutionContext(
        int epoch,
        int ns,
        int nf,
        double[] positionVar,
        double[] residuals,
        double[] elevation,
        double[] snr,
        double convergenceMetric,
        int biasState,
        boolean deterministicDegraded) {
}