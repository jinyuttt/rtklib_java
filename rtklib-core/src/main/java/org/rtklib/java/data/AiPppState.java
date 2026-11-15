package org.rtklib.java.data;

/**
 * PPP 体系 AI 三接口（A / B / D）的跨历元簿记状态。
 *
 * 与 {@link Rtk} 分离持有，避免把 AI 簿记混进解算主状态；
 * 未注入任一 PPP AI 接口时该对象为 {@code null}，不分配任何数组（I-1 零回归）。
 *
 * 熔断状态（S5）全部是内存态：不落盘、不跨实例，新建 {@link Rtk} 或重注入即恢复。
 */
public final class AiPppState {

    /** 接口 A：PPP 解有效性判别。 */
    public static final int IF_SOLUTION = 0;
    /** 接口 B：PPP 观测方差缩放。 */
    public static final int IF_VARIANCE = 1;
    /** 接口 D：PPP-RTK 固定校验。 */
    public static final int IF_RTK_FIX = 2;
    /** 接口槽位数量。 */
    public static final int IF_COUNT = 3;

    /** 逐接口连续降级历元数（成功即归零，S5）。 */
    public final int[] consecutive = new int[IF_COUNT];

    /** 逐接口本会话熔断标志；置位后 provider 不再被调用（等价未注入）。 */
    public final boolean[] fused = new boolean[IF_COUNT];

    /** 逐接口最近一次解析到的 provider 实例（用于识别「重注入」恢复）。 */
    public final Object[] provider = new Object[IF_COUNT];

    /** 逐接口熔断时刻的 provider 实例（熔断后仅当实例变化才解除）。 */
    public final Object[] fusedProvider = new Object[IF_COUNT];

    /** 降级告警去重（每会话一次，避免逐历元刷屏，§4.1）。 */
    public boolean degradeLogged;

    /** 熔断告警去重（每会话一次）。 */
    public boolean fusedLogged;

    /** 接口 B 钳位告警去重（每会话一次，§4.4）。 */
    public boolean scaleClampLogged;

    /** 接口 B 缓存的历元号；{@code -1} 表示尚未缓存。 */
    public int scaleEpoch = -1;

    /** 接口 B 缓存上下文中的卫星号列表（即 {@code PppObservationContext.sats} 的 {@code sat} 列）。 */
    public int[] scaleSats = new int[0];

    /** 接口 B 缓存的方差膨胀因子（行主序 {@code ns * nf}）。 */
    public double[] scaleVals = new double[0];

    /** 接口 B 缓存对应的频点数。 */
    public int scaleNf = 1;

    /** 接口 B 缓存是否可用（降级时为 {@code false}，本历元不再重试）。 */
    public boolean scaleValid;
}