package org.rtklib.java.vrs.rtcm;

/**
 * RTCM3 字节出口，与输入侧的 {@link org.rtklib.java.rtcm.RtcmDataHandler} 对称。
 *
 * <p>库只负责把每条编码好的消息以字节数组交出，字节的去向（写文件、推 socket、
 * 投递到队列、攒成内存块）全部由调用方实现。</p>
 */
public interface RtcmSink extends AutoCloseable {

    /**
     * 接收一条完整的 RTCM3 帧。
     *
     * @param message 单帧字节，含帧头与 CRC，不含任何传输层封装
     */
    void write(byte[] message);

    @Override
    default void close() {
    }
}