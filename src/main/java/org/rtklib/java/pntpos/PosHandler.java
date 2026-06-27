package org.rtklib.java.pntpos;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Sol;
import org.rtklib.java.data.Ssat;

/**
 * 定位结果回调接口，适用于SPP、RTK等所有定位模式。
 * <p>
 * 通过该接口可以实时接收定位结果、失败通知和完成统计，
 * 输出流与回调可同时工作，互不干扰。
 * </p>
 */
public interface PosHandler {

    /**
     * 定位成功时回调。
     *
     * @param sol  定位解算结果，包含坐标、精度、质量状态等
     * @param ssat 各卫星状态数组，包含方位角、残差、可用性等信息
     */
    void onSolution(Sol sol, Ssat[] ssat);

    /**
     * 定位失败时回调。
     *
     * @param time 当前历元时间
     * @param msg  失败原因描述
     */
    void onPosFail(GTime time, String msg);

    /**
     * 所有历元处理完成时回调。
     *
     * @param totalEpochs  总历元数
     * @param successCount 成功历元数
     * @param failCount    失败历元数
     */
    void onFinish(int totalEpochs, int successCount, int failCount);
}