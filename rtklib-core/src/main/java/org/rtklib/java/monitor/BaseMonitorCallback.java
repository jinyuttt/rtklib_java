package org.rtklib.java.monitor;

import org.rtklib.java.data.GTime;

/**
 * 基站稳定性监测回调接口。
 * <p>
 * 全部为default方法，调用方按需实现。staid为基站标识（RTCM3站ID），
 * 用于多基站场景下区分告警来源。
 * </p>
 */
public interface BaseMonitorCallback {

    /**
     * 每个SPP历元结果推送。
     *
     * @param staid  基站ID
     * @param time   历元时间
     * @param pos    SPP坐标 [x,y,z] ECEF (m)
     * @param numSat 参与解算卫星数
     * @param pdop   PDOP
     */
    default void onSppResult(int staid, GTime time, double[] pos, int numSat, double pdop) {}

    /**
     * 一级窗口中位数结果推送（每个窗口满时触发一次）。
     *
     * @param staid     基站ID
     * @param time      窗口结束时间
     * @param medianPos 窗口中位数坐标 [x,y,z] ECEF (m)
     * @param offset    与参考基准的偏移量 (m)，-1表示参考基准未初始化
     */
    default void onWindowResult(int staid, GTime time, double[] medianPos, double offset) {}

    /**
     * 一级突变告警：窗口中位数与参考基准偏移超过movementThresh。
     *
     * @param staid  基站ID
     * @param time   窗口结束时间
     * @param median 窗口中位数 [x,y,z]
     * @param ref    参考基准 [x,y,z]
     * @param offset 偏移量 (m)
     * @param msg    告警描述
     */
    default void onBaseMovement(int staid, GTime time, double[] median, double[] ref,
                                double offset, String msg) {}

    /**
     * 二级漂移告警：历史中位数首尾距离超过driftThresh。
     *
     * @param staid  基站ID
     * @param time   当前窗口时间
     * @param drift  漂移量 (m)
     * @param hours  漂移时间跨度 (小时)
     * @param msg    告警描述
     */
    default void onBaseDrift(int staid, GTime time, double drift, double hours, String msg) {}
}