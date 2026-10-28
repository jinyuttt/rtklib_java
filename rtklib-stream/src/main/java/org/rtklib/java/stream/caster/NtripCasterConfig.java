package org.rtklib.java.stream.caster;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NTRIP Caster 配置。
 *
 * <p>封装监听地址、挂载点列表与鉴权凭据。所有参数均有默认值，
 * 只需注册挂载点即可启动一个可用的 Caster。</p>
 *
 * <p>默认值一览：
 * <ul>
 *   <li>host: "0.0.0.0"（监听全部网卡）</li>
 *   <li>port: 2101（NTRIP 标准端口，0 表示由系统分配空闲端口）</li>
 *   <li>backlog: 50</li>
 *   <li>casterId: "rtklib-java NTRIP Caster"</li>
 *   <li>readTimeoutMs: 0（不因上行空闲而断开）</li>
 *   <li>writeQueueSize: 256（每会话待发帧上限）</li>
 * </ul>
 *
 * @see NtripCaster
 */
public class NtripCasterConfig {

    private String host = "0.0.0.0";
    private int port = 2101;
    private int backlog = 50;
    private String casterId = "rtklib-java NTRIP Caster";
    private int readTimeoutMs = 0;
    private int writeQueueSize = 256;
    private final Map<String, MountPoint> mountpoints = new LinkedHashMap<>();
    private final Map<String, String> users = new LinkedHashMap<>();

    /** 获取监听地址。 */
    public String getHost() { return host; }
    /** 设置监听地址，默认 "0.0.0.0" 监听全部网卡。 */
    public void setHost(String host) { this.host = host; }

    /** 获取监听端口，默认 2101。 */
    public int getPort() { return port; }
    /** 设置监听端口，0 表示由系统分配空闲端口。 */
    public void setPort(int port) { this.port = port; }

    /** 获取连接等待队列长度。 */
    public int getBacklog() { return backlog; }
    /** 设置连接等待队列长度。 */
    public void setBacklog(int backlog) { this.backlog = backlog; }

    /** 获取 Caster 标识，用于 HTTP Server 头。 */
    public String getCasterId() { return casterId; }
    /** 设置 Caster 标识。 */
    public void setCasterId(String casterId) { this.casterId = casterId; }

    /** 获取上行读取超时（毫秒），0 表示不超时。 */
    public int getReadTimeoutMs() { return readTimeoutMs; }
    /** 设置上行读取超时（毫秒），0 表示不超时。 */
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    /** 获取每会话待发帧队列上限。 */
    public int getWriteQueueSize() { return writeQueueSize; }
    /** 设置每会话待发帧队列上限，队列满时丢弃最旧帧。 */
    public void setWriteQueueSize(int writeQueueSize) { this.writeQueueSize = writeQueueSize; }

    /**
     * 注册挂载点。
     *
     * @param mountpoint 挂载点描述
     * @return 本配置对象，便于链式调用
     */
    public NtripCasterConfig addMountPoint(MountPoint mountpoint) {
        mountpoints.put(mountpoint.getName(), mountpoint);
        return this;
    }

    /** 获取全部挂载点（按注册顺序）。 */
    public Map<String, MountPoint> getMountpoints() { return mountpoints; }

    /** 判断挂载点是否已注册。 */
    public boolean hasMountPoint(String name) {
        return name != null && mountpoints.containsKey(name);
    }

    /**
     * 添加 Basic 鉴权用户。
     *
     * <p>未添加任何用户时允许匿名访问；一旦添加用户，订阅挂载点必须携带
     * 匹配的凭据，而获取 Sourcetable 仍保持匿名可用。</p>
     *
     * @param username 用户名
     * @param password 密码
     * @return 本配置对象，便于链式调用
     */
    public NtripCasterConfig addUser(String username, String password) {
        users.put(username, password);
        return this;
    }

    /** 获取全部鉴权用户。 */
    public Map<String, String> getUsers() { return users; }

    /** 是否启用了鉴权。 */
    public boolean requiresAuthentication() {
        return !users.isEmpty();
    }
}