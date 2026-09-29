package org.rtklib.java.stream;

/**
 * 数据流监听器。
 * <p>
 * 所有流类（NTRIP客户端、串口等）通过此接口向调用方推送数据、错误和关闭事件。
 * 回调方法在流线程中调用，实现方需注意线程安全。
 * </p>
 *
 * @see NtripClient
 * @see SerialPort
 */
public interface StreamListener {

    /**
     * 收到数据时回调。
     *
     * @param data   数据缓冲区，内容在回调返回后可能被覆盖，如需保留请复制
     * @param offset 数据在缓冲区中的起始偏移
     * @param length 数据长度（字节）
     */
    void onData(byte[] data, int offset, int length);

    /**
     * 流发生错误时回调。
     * <p>
     * 错误后连接可能已断开，调用方可通过此回调触发重连或资源清理。
     * </p>
     *
     * @param e 异常信息
     */
    void onError(Exception e);

    /**
     * 流已关闭时回调。
     * <p>
     * 可能是主动调用 stop() 关闭，也可能是对端断开连接后正常结束。
     * </p>
     */
    void onClosed();
}