package org.rtklib.java.monitor;

import java.io.Serializable;

public class BaseMonitorConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 一级窗口时长（分钟），窗口满后计算中位数并检测突变 */
    public double windowMinutes = 60.0;

    /** 一级突变阈值（米）：窗口中位数与参考基准的偏移超过此值则告警 */
    public double movementThresh = 10.0;

    /** 二级漂移阈值（米）：历史中位数首尾距离超过此值则告警 */
    public double driftThresh = 5.0;

    /** 二级环形缓冲区大小：保存多少个窗口中位数，默认24（24小时×1窗口/小时） */
    public int historySize = 24;

    /** SPP最少卫星数：低于此值丢弃该历元 */
    public int minSatCount = 4;

    /** SPP最大PDOP：超过此值丢弃该历元 */
    public double maxPdop = 6.0;

    /** 离群剔除阈值（米）：SPP坐标到窗口中位数距离超过此值剔除 */
    public double outlierThresh = 3.0;

    /** 窗口最少有效历元数：剔除离群后少于此次数则跳过本次检测 */
    public int minValidInWindow = 10;

    /** 最低高度角（度）：低于此值的卫星不参与SPP */
    public double minElMaskDeg = 10.0;

    /** 最低SNR（dB-Hz）：低于此值的观测不参与SPP */
    public double minSnrDbHz = 20.0;

    public BaseMonitorConfig() {
    }

    public BaseMonitorConfig(BaseMonitorConfig other) {
        this.windowMinutes = other.windowMinutes;
        this.movementThresh = other.movementThresh;
        this.driftThresh = other.driftThresh;
        this.historySize = other.historySize;
        this.minSatCount = other.minSatCount;
        this.maxPdop = other.maxPdop;
        this.outlierThresh = other.outlierThresh;
        this.minValidInWindow = other.minValidInWindow;
        this.minElMaskDeg = other.minElMaskDeg;
        this.minSnrDbHz = other.minSnrDbHz;
    }
}