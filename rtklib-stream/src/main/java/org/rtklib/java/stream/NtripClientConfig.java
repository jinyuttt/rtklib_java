package org.rtklib.java.stream;

import javax.net.ssl.SSLContext;

/**
 * NTRIP客户端配置。
 * <p>
 * 封装NTRIP连接所需的全部参数，包括服务器地址、认证信息、超时设置和自动重连策略。
 * 所有参数均有默认值，可直接使用或按需调整。
 * </p>
 *
 * <p>默认值一览：
 * <ul>
 *   <li>port: 2101（NTRIP标准端口）</li>
 *   <li>ssl: false（明文连接；启用后使用TLS/SSL，默认端口自动切换为443）</li>
 *   <li>userAgent: "NTRIP rtklib-java/2.1"</li>
 *   <li>ntripVersion: "Ntrip/2.0"</li>
 *   <li>connectTimeoutMs: 15000</li>
 *   <li>readTimeoutMs: 10000</li>
 *   <li>autoReconnect: false</li>
 *   <li>reconnectIntervalMs: 5000</li>
 *   <li>maxReconnectAttempts: 10</li>
 * </ul>
 *
 * @see NtripClient
 */
public class NtripClientConfig {

    private String host = "";
    private int port = 2101;
    private String mountpoint = "";
    private String username = "";
    private String password = "";
    private String userAgent = "NTRIP rtklib-java/2.1";
    private String ntripVersion = "Ntrip/2.0";
    private int connectTimeoutMs = 15000;
    private int readTimeoutMs = 10000;
    private boolean autoReconnect = false;
    private int reconnectIntervalMs = 5000;
    private int maxReconnectAttempts = 10;
    private boolean ssl = false;
    private SSLContext sslContext = null;

    /** 获取NTRIP Caster主机地址。 */
    public String getHost() { return host; }
    /** 设置NTRIP Caster主机地址，如 "igs.bdsmart.cn"。 */
    public void setHost(String host) { this.host = host; }

    /** 获取NTRIP Caster端口号，默认2101。 */
    public int getPort() { return port; }
    /** 设置NTRIP Caster端口号。 */
    public void setPort(int port) { this.port = port; }

    /** 获取挂载点名称，如 "IGS03"。 */
    public String getMountpoint() { return mountpoint; }
    /** 设置挂载点名称。空字符串或null表示仅获取Sourcetable。 */
    public void setMountpoint(String mountpoint) { this.mountpoint = mountpoint; }

    /** 获取NTRIP认证用户名。 */
    public String getUsername() { return username; }
    /** 设置NTRIP认证用户名，匿名Caster可留空。 */
    public void setUsername(String username) { this.username = username; }

    /** 获取NTRIP认证密码。 */
    public String getPassword() { return password; }
    /** 设置NTRIP认证密码。 */
    public void setPassword(String password) { this.password = password; }

    /** 获取HTTP User-Agent头。 */
    public String getUserAgent() { return userAgent; }
    /** 设置HTTP User-Agent头。 */
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    /** 获取NTRIP协议版本字符串。 */
    public String getNtripVersion() { return ntripVersion; }
    /** 设置NTRIP协议版本，如 "Ntrip/2.0" 或 "Ntrip/1.0"。 */
    public void setNtripVersion(String ntripVersion) { this.ntripVersion = ntripVersion; }

    /** 获取TCP连接超时时间（毫秒）。 */
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    /** 设置TCP连接超时时间（毫秒）。 */
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    /** 获取读取超时时间（毫秒），用于connect阶段和Sourcetable读取。 */
    public int getReadTimeoutMs() { return readTimeoutMs; }
    /** 设置读取超时时间（毫秒）。 */
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    /** 是否启用断线自动重连。 */
    public boolean isAutoReconnect() { return autoReconnect; }
    /** 设置是否启用断线自动重连。启用后start()会启动重连守护线程。 */
    public void setAutoReconnect(boolean autoReconnect) { this.autoReconnect = autoReconnect; }

    /** 获取重连间隔时间（毫秒）。 */
    public int getReconnectIntervalMs() { return reconnectIntervalMs; }
    /** 设置重连间隔时间（毫秒），两次重连尝试之间的等待时间。 */
    public void setReconnectIntervalMs(int reconnectIntervalMs) { this.reconnectIntervalMs = reconnectIntervalMs; }

    /** 获取最大重连尝试次数。 */
    public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
    /** 设置最大重连尝试次数，超过后不再重连。 */
    public void setMaxReconnectAttempts(int maxReconnectAttempts) { this.maxReconnectAttempts = maxReconnectAttempts; }

    /** 是否启用SSL/TLS加密连接。启用后连接使用TLS，默认端口自动从2101切换为443。 */
    public boolean isSsl() { return ssl; }
    /** 设置是否启用SSL/TLS。越来越多的NTRIP Caster（如EUREF、IGS新端点）要求HTTPS。 */
    public void setSsl(boolean ssl) { this.ssl = ssl; }

    /**
     * 获取自定义SSLContext。为null时使用JDK默认SSLContext（信任标准CA证书）。
     * <p>
     * 设置自定义SSLContext可用于：信任自签名证书、双向TLS认证（客户端证书）、
     * 或自定义TLS协议版本限制。
     * </p>
     */
    public SSLContext getSslContext() { return sslContext; }
    /** 设置自定义SSLContext。传null恢复使用JDK默认SSLContext。 */
    public void setSslContext(SSLContext sslContext) { this.sslContext = sslContext; }

    @Override
    public String toString() {
        return String.format("NtripClientConfig{host='%s', port=%d, mountpoint='%s', ssl=%s}", host, port, mountpoint, ssl);
    }
}