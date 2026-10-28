package org.rtklib.java.rtcm;

/**
 * 自定义 RTCM3 消息解码器注入接口。
 *
 * <p>用于 RTCM 标准之外、但借用 RTCM3 帧结构的专有消息（例如 IGS 使用的
 * 专有消息号 4076）。实现类由外部扩展模块提供，通过
 * {@link Rtcm#registerCustomDecoder(CustomMessageDecoder)} 注册，
 * 核心的通用 RTCM 解析逻辑不包含任何专有格式代码。</p>
 *
 * <p>当 {@link Rtcm} 收到未内置支持的消息号时，会依次委托给已注册的解码器；
 * 没有任何解码器处理时，仍然按“已消费但不解析”处理，与原有行为一致。</p>
 */
public interface CustomMessageDecoder {

    /**
     * 本解码器处理的消息号（12 bit）。
     * @return 消息号，例如 4076
     */
    int messageType();

    /**
     * 解码一条消息。
     * @param rtcm       当前解码器实例（可读取上下文，如 nav/sta/time）
     * @param type       消息号
     * @param payload    RTCM3 消息体，位偏移 0 从消息号开始；
     *                   不包含 3 字节帧头与末尾 3 字节 CRC
     * @param payloadLen 消息体字节数
     * @return true 表示已识别并消费；false 表示本解码器无法处理
     */
    boolean decode(Rtcm rtcm, int type, byte[] payload, int payloadLen);
}
