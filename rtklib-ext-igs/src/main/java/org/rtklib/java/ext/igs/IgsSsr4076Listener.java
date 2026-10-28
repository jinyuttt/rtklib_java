package org.rtklib.java.ext.igs;

/**
 * IGS SSR 4076 消息监听器。
 */
@FunctionalInterface
public interface IgsSsr4076Listener {

    /**
     * 收到一条已识别的 4076 消息。
     * @param message 头部解析结果
     */
    void onIgsSsr4076Message(IgsSsr4076Message message);
}
