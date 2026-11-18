package org.rtklib.java.ai.collect;

/**
 * 采集期的 PPP 解位置来源（宿主提供，逐历元回调）。
 *
 * 为什么采集器不自己取位置：接口 A 的输入快照
 * {@code PppSolutionContext} 刻意只暴露位置**方差**（{@code sol.qr}）与残差，
 * 不暴露 ECEF 坐标——SPI 的约定是「impl 只读快照、不读 {@code Rtk}」（§3.3 / S2），
 * 而训练标签必须由解算位置与参考坐标比对得到。二者冲突只能由**宿主**弥合：
 * 宿主持有 {@code Rtk}，逐历元把 {@code sol.rr} 交给采集器。
 *
 * 回调在 core 的注入点内同步执行，必须常数时间：不得做 IO、不得改 {@code Rtk}。
 * 返回 {@code null} 表示该历元位置不可用（CSV 该列留空，组装阶段计为
 * {@code missing_position} 并告警，不会伪造坐标）。
 */
@FunctionalInterface
public interface PppPositionSource {

    /**
     * @param epoch 进程内历元号（与 {@code PppSolutionContext.epoch()} 同一个值）
     * @return ECEF {@code {x,y,z}}（米），不可用时返回 {@code null}
     */
    double[] position(int epoch);
}