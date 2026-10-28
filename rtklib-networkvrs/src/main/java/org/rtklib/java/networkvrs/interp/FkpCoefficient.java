package org.rtklib.java.networkvrs.interp;

/**
 * FKP 平面拟合结果。
 *
 * @param intercept   目标点处的插值改正量（米）
 * @param eastSlope   东西向梯度（米/米）
 * @param northSlope  南北向梯度（米/米）
 * @param rms         拟合残差 RMS（米）
 * @param stationCount 参与拟合的站数
 * @param areaRatio   归一化三角形面积 2A/maxEdge²（小于阈值视为共线）
 * @param degenerate  几何是否退化（近共线）
 */
public record FkpCoefficient(double intercept,
                             double eastSlope,
                             double northSlope,
                             double rms,
                             int stationCount,
                             double areaRatio,
                             boolean degenerate) {
}
