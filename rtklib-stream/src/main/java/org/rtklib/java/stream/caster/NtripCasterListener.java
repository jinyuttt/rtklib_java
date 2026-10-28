package org.rtklib.java.stream.caster;

import org.rtklib.java.stream.nmea.NmeaGga;

/**
 * NTRIP Caster 事件监听器。
 *
 * <p>所有回调在 Caster 内部线程中触发，实现方需自行保证线程安全，
 * 且不应在回调中执行阻塞操作。</p>
 *
 * <p>本接口只负责「告知」，不决定数据流向：需要向客户端回送改正数的场景，
 * 由应用层在 {@link #onGga} 中调用 {@link CasterSession#send(byte[])}
 * 或 {@link NtripCaster#publish(String, byte[])} 完成。</p>
 *
 * @see NtripCaster
 * @see CasterSession
 */
public interface NtripCasterListener {

    /**
     * 客户端订阅成功并完成握手时回调。
     *
     * @param session 新建会话
     */
    default void onClientConnected(CasterSession session) {
    }

    /**
     * 会话结束时回调。
     *
     * @param session 已结束的会话
     * @param cause   导致结束的异常；正常断开或主动关闭时为 null
     */
    default void onClientDisconnected(CasterSession session, Throwable cause) {
    }

    /**
     * 收到客户端上行 GGA 时回调。
     *
     * @param session 发送该语句的会话
     * @param gga     解析结果
     */
    default void onGga(CasterSession session, NmeaGga gga) {
    }
}