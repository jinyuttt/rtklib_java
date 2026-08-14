package org.rtklib.java.pntpos;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Sol;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.Ssat;

/**
 * 定位结果回调接口，适用于SPP、RTK等所有定位模式。
 * <p>
 * 通过该接口可以实时接收定位结果、失败通知和完成统计，
 * 输出流与回调可同时工作，互不干扰。
 * </p>
 *
 * <h3>两套回调</h3>
 * <ul>
 *   <li>{@link #onSolution(Sol, Ssat[])}：内部回调，参数为 C 对齐的 {@link Sol}，
 *       解算核心内部使用，不应由应用层直接实现</li>
 *   <li>{@link #onResult(SolData)}：输出回调，参数为 Java 友好的 {@link SolData}，
 *       已根据 {@link org.rtklib.java.data.PrcOpt#posMask} 完成坐标转换，
 *       应用层应实现此方法接收定位结果</li>
 * </ul>
 */
public interface PosHandler {

    /**
     * 定位成功时回调（内部，C 对齐结构）。
     *
     * @param sol  定位解算结果，包含坐标、精度、质量状态等
     * @param ssat 各卫星状态数组，包含方位角、残差、可用性等信息
     */
    void onSolution(Sol sol, Ssat[] ssat);

    /**
     * 定位成功时回调（输出，Java 友好封装）。
     *
     * <p>默认实现为空，应用层按需覆盖。{@link SolData} 已根据
     * {@link org.rtklib.java.data.PrcOpt#posMask} 完成坐标转换，
     * 包含 {@link org.rtklib.java.data.Position}、
     * {@link org.rtklib.java.data.Accuracy}、
     * {@link org.rtklib.java.data.Velocity} 列表。</p>
     *
     * @param solData 定位结果输出数据
     */
    default void onResult(SolData solData) {}

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

    default void onBackwardResult(String sourceId, Object result) {}
}