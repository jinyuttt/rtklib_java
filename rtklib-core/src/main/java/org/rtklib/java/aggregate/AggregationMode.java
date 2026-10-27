package org.rtklib.java.aggregate;

/**
 * 历元聚合模式。
 *
 * <ul>
 *   <li>{@link #ROBUST}：鲁棒加权（IGG3 降权 + 加权平均），适合静态/缓变场景压噪声、抗假固定</li>
 *   <li>{@link #KALMAN_SMOOTH}：卡尔曼前向滤波 + RTS 反向平滑，适合跟踪窗口内真实位移</li>
 * </ul>
 */
public enum AggregationMode {
    ROBUST,
    KALMAN_SMOOTH
}
