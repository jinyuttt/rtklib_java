package org.rtklib.java.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * NTRIP客户端，实现NTRIP v2.0协议的数据流连接。
 * <p>
 * NTRIP (Networked Transport of RTCM via Internet Protocol) 是GNSS差分数据传输的事实标准协议。
 * 本客户端支持：
 * <ul>
 *   <li>连接NTRIP Caster获取指定挂载点的实时数据流（RTCM3观测值、SSR改正数等）</li>
 *   <li>获取Sourcetable（可用挂载点列表）</li>
 *   <li>HTTP Basic认证</li>
 *   <li>断线自动重连（可配置重连间隔和最大次数）</li>
 *   <li>向NTRIP Caster发送数据（如NMEA GGA位置报告）</li>
 *   <li>SSL/TLS加密连接（支持自签名证书和双向TLS认证）</li>
 * </ul>
 *
 * <p>典型使用流程：
 * <pre>
 *   // 1. 创建配置
 *   NtripClientConfig cfg = new NtripClientConfig();
 *   cfg.setHost("igs.bdsmart.cn");
 *   cfg.setPort(2101);
 *   cfg.setMountpoint("IGS03");
 *   cfg.setAutoReconnect(true);
 *
 *   // 2. 创建客户端
 *   NtripClient client = new NtripClient(cfg, new StreamListener() {
 *       public void onData(byte[] data, int offset, int length) {
 *           // 处理收到的RTCM3数据
 *       }
 *       public void onError(Exception e) { e.printStackTrace(); }
 *       public void onClosed() { System.out.println("closed"); }
 *   });
 *
 *   // 3. 连接并启动数据读取
 *   client.connect();
 *   client.start();
 *
 *   // 4. 使用完毕后关闭
 *   client.stop();
 * </pre>
 *
 * <p>线程模型：
 * <ul>
 *   <li>connect()/getSourcetable() 在调用线程中执行，阻塞直到连接建立或失败</li>
 *   <li>start() 启动守护线程 "NTRIP-Reader" 持续读取数据并通过 StreamListener 回调</li>
 *   <li>若启用自动重连，额外启动守护线程 "NTRIP-Reconnect" 监测连接状态并重连</li>
 * </ul>
 *
 * @see NtripClientConfig
 * @see StreamListener
 */
public class NtripClient {

    private static final Logger log = LoggerFactory.getLogger(NtripClient.class);

    private final NtripClientConfig config;
    private final StreamListener listener;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    /** 当前连接是否使用HTTP分块传输（Transfer-Encoding: chunked）。 */
    private volatile boolean chunked = false;
    private Thread readThread;
    private Thread reconnectThread;

    /**
     * 使用配置对象创建NTRIP客户端。
     *
     * @param config   连接配置，不可为null
     * @param listener 数据监听器，不可为null
     */
    public NtripClient(NtripClientConfig config, StreamListener listener) {
        this.config = config;
        this.listener = listener;
    }

    /**
     * 使用独立参数创建NTRIP客户端（便捷构造器）。
     *
     * @param host       NTRIP Caster主机地址
     * @param port       NTRIP Caster端口号
     * @param mountpoint 挂载点名称
     * @param username   认证用户名
     * @param password   认证密码
     * @param listener   数据监听器
     */
    public NtripClient(String host, int port, String mountpoint,
                       String username, String password, StreamListener listener) {
        NtripClientConfig cfg = new NtripClientConfig();
        cfg.setHost(host);
        cfg.setPort(port);
        cfg.setMountpoint(mountpoint);
        cfg.setUsername(username);
        cfg.setPassword(password);
        this.config = cfg;
        this.listener = listener;
    }

    /**
     * Sourcetable监听器（保留接口，供扩展使用）。
     */
    public interface SourcetableListener {
        void onSourcetable(String sourcetable);
    }

    /**
     * 连接NTRIP Caster并订阅指定挂载点的数据流。
     * <p>
     * 本方法阻塞执行，完成TCP连接、发送HTTP GET请求、读取服务端响应头。
     * 连接成功后可调用 {@link #start()} 启动异步数据读取。
     * </p>
     *
     * @throws IOException          连接失败、服务端返回非200状态、或I/O错误
     * @throws IllegalArgumentException 挂载点为空或null
     */
    public void connect() throws IOException {
        doConnect();
        connected.set(true);
        reconnectCount.set(0);
        log.info("NTRIP connected: {}://{}:{}/{}", config.isSsl() ? "https" : "http",
                config.getHost(), config.getPort(), config.getMountpoint());
    }

    /**
     * 获取NTRIP Caster的Sourcetable（可用挂载点列表）。
     * <p>
     * 发送 GET / 请求，读取完整Sourcetable后关闭连接。
     * 本方法独立于 {@link #connect()}，不需要预先连接。
     * </p>
     * <p>
     * 兼容 NTRIP 1.0（SOURCETABLE 200 OK）与 2.0（HTTP/1.1 200 OK）响应，
     * 并按响应头处理 {@code Transfer-Encoding: chunked} 与
     * {@code Content-Encoding: gzip}：先解块再解压（HTTP 编码施加顺序的逆序）。
     * </p>
     *
     * @return Sourcetable原始文本
     * @throws IOException 连接或读取失败
     */
    public String getSourcetable() throws IOException {
        socket = createSocket();
        socket.setSoTimeout(config.getReadTimeoutMs());
        socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), config.getConnectTimeoutMs());
        in = socket.getInputStream();
        out = socket.getOutputStream();

        String auth = buildAuthHeader();
        String req = "GET / HTTP/1.1\r\n"
                + "Host: " + config.getHost() + "\r\n"
                + "User-Agent: " + config.getUserAgent() + "\r\n"
                + "Authorization: Basic " + auth + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 跳过状态行（NTRIP 1.0 为 "SOURCETABLE 200 OK"，2.0 为 "HTTP/1.1 200 OK"）
        readLine();

        boolean chunkedTable = false;
        boolean gzipTable = false;
        String line;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) break;
            String lower = line.toLowerCase();
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunkedTable = true;
            } else if (lower.startsWith("content-encoding:") && lower.contains("gzip")) {
                gzipTable = true;
            }
        }

        InputStream src = in;
        if (chunkedTable) src = new ChunkedInputStream(src);
        if (gzipTable) src = new GZIPInputStream(src);

        String table = readSourcetable(src);
        closeSocket();
        return table;
    }

    /**
     * 启动异步数据读取线程。
     * <p>
     * 调用前必须先成功调用 {@link #connect()}。
     * 启动后，收到的数据通过 {@link StreamListener#onData} 回调推送。
     * 若配置了自动重连，同时启动重连守护线程。
     * </p>
     *
     * @throws IllegalStateException 尚未连接时调用
     */
    public void start() {
        if (!connected.get()) {
            throw new IllegalStateException("NTRIP not connected. Call connect() first.");
        }
        running.set(true);
        readThread = new Thread(this::readLoop, "NTRIP-Reader");
        readThread.setDaemon(true);
        readThread.start();

        if (config.isAutoReconnect()) {
            reconnectThread = new Thread(this::reconnectLoop, "NTRIP-Reconnect");
            reconnectThread.setDaemon(true);
            reconnectThread.start();
        }
    }

    /**
     * 停止数据读取并关闭连接。
     * <p>
     * 关闭Socket、等待读取线程和重连线程结束，最后回调 {@link StreamListener#onClosed()}。
     * </p>
     */
    public void stop() {
        running.set(false);
        connected.set(false);
        closeSocket();
        joinThread(readThread);
        joinThread(reconnectThread);
        if (listener != null) {
            listener.onClosed();
        }
    }

    /**
     * 向NTRIP Caster发送数据。
     * <p>
     * 用于向Caster发送NMEA GGA位置报告等上行数据。
     * 仅在连接已建立且处于运行状态时有效。
     * </p>
     *
     * @param data 待发送的数据
     * @throws IOException 写入失败
     */
    public void write(byte[] data) throws IOException {
        if (out != null && connected.get()) {
            out.write(data);
            out.flush();
        }
    }

    /** 数据读取线程是否正在运行。 */
    public boolean isRunning() { return running.get(); }

    /** 是否已连接到NTRIP Caster。 */
    public boolean isConnected() { return connected.get(); }

    /** 获取累计重连尝试次数。 */
    public int getReconnectCount() { return reconnectCount.get(); }

    /**
     * 执行NTRIP连接握手：建立TCP连接、发送GET请求、校验响应状态码、跳过响应头。
     *
     * @throws IOException 连接或握手失败
     */
    private void doConnect() throws IOException {
        socket = createSocket();
        socket.setSoTimeout(config.getReadTimeoutMs());
        socket.connect(new InetSocketAddress(config.getHost(), config.getPort()), config.getConnectTimeoutMs());
        in = socket.getInputStream();
        out = socket.getOutputStream();

        String auth = buildAuthHeader();
        String mp = config.getMountpoint();
        if (mp == null || mp.isEmpty()) {
            throw new IllegalArgumentException("Mountpoint is required for data connection. Use getSourcetable() to list available mountpoints.");
        }

        String req = "GET /" + mp + " HTTP/1.1\r\n"
                + "Host: " + config.getHost() + "\r\n"
                + "User-Agent: " + config.getUserAgent() + "\r\n"
                + "Authorization: Basic " + auth + "\r\n"
                + "Ntrip-Version: " + config.getNtripVersion() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        String statusLine = readLine();
        if (statusLine == null || !statusLine.contains("200")) {
            closeSocket();
            throw new IOException("NTRIP connection failed: " + statusLine);
        }

        chunked = false;
        String line;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) break;
            if (line.regionMatches(true, 0, "Transfer-Encoding:", 0, 18)
                    && line.toLowerCase().contains("chunked")) {
                chunked = true;
            }
        }
    }

    /**
     * 数据读取循环，在守护线程中运行。
     * <p>
     * 持续从Socket读取数据并通过 {@link StreamListener#onData} 回调。
     * 读取到EOF或发生I/O错误时退出循环。
     * </p>
     */
    private void readLoop() {
        try {
            socket.setSoTimeout(0);
            if (chunked) {
                readChunkedLoop();
            } else {
                readRawLoop();
            }
        } catch (IOException e) {
            if (running.get()) {
                connected.set(false);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        } finally {
            running.set(false);
            log.info("NTRIP read loop ended");
        }
    }

    /** 直接透传原始字节流（无HTTP分块编码）。 */
    private void readRawLoop() throws IOException {
        byte[] buf = new byte[4096];
        while (running.get()) {
            int n = in.read(buf);
            if (n == -1) break;
            if (n > 0 && listener != null) {
                listener.onData(buf, 0, n);
            }
        }
    }

    /**
     * 解析HTTP/1.1分块传输编码（Transfer-Encoding: chunked），
     * 仅向监听器推送分块内的有效载荷，避免分块头污染RTCM数据流。
     */
    private void readChunkedLoop() throws IOException {
        byte[] buf = new byte[8192];
        while (running.get()) {
            String sizeLine = readLine();
            if (sizeLine == null) break;
            sizeLine = sizeLine.trim();
            if (sizeLine.isEmpty()) continue;
            int ext = sizeLine.indexOf(';');
            if (ext >= 0) sizeLine = sizeLine.substring(0, ext);
            int size;
            try {
                size = Integer.parseInt(sizeLine, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid chunk size: " + sizeLine);
            }
            if (size == 0) break;
            int remaining = size;
            while (remaining > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, remaining));
                if (n == -1) throw new IOException("Unexpected EOF in chunked stream");
                if (n > 0 && listener != null) {
                    listener.onData(buf, 0, n);
                }
                remaining -= n;
            }
            readLine();
        }
    }

    /**
     * 自动重连循环，在守护线程中运行。
     * <p>
     * 按配置的重连间隔定期检测连接状态，若已断开则尝试重新连接。
     * 重连成功后重置计数器并启动新的读取线程。
     * 超过最大重连次数后停止尝试。
     * </p>
     */
    private void reconnectLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(config.getReconnectIntervalMs());
            } catch (InterruptedException e) {
                break;
            }
            if (!running.get() && !connected.get()) {
                break;
            }
            if (!connected.get() && reconnectCount.get() < config.getMaxReconnectAttempts()) {
                try {
                    log.info("NTRIP reconnecting... (attempt {}/{})", reconnectCount.get() + 1, config.getMaxReconnectAttempts());
                    doConnect();
                    connected.set(true);
                    running.set(true);
                    reconnectCount.set(0);
                    readThread = new Thread(this::readLoop, "NTRIP-Reader");
                    readThread.setDaemon(true);
                    readThread.start();
                    log.info("NTRIP reconnected: {}://{}:{}/{}", config.isSsl() ? "https" : "http",
                            config.getHost(), config.getPort(), config.getMountpoint());
                } catch (IOException e) {
                    reconnectCount.incrementAndGet();
                    log.warn("NTRIP reconnect failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 根据SSL配置创建Socket。
     * <p>
     * 若 {@code config.isSsl()} 为true，创建SSLSocket并完成TLS握手；
     * 否则创建普通明文Socket。
     * SSLContext来源优先级：config.getSslContext() > JDK默认。
     * </p>
     *
     * @return 已创建但尚未连接的Socket
     * @throws IOException 创建SSLSocket失败（如SSLContext未初始化）
     */
    private Socket createSocket() throws IOException {
        if (!config.isSsl()) {
            return new Socket();
        }

        SSLContext ctx = config.getSslContext();
        if (ctx == null) {
            try {
                ctx = SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("Failed to get default SSLContext", e);
            }
        }

        SSLSocket sslSocket = (SSLSocket) ctx.getSocketFactory().createSocket();
        sslSocket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        return sslSocket;
    }

    /**
     * 构建HTTP Basic认证头内容（Base64编码的username:password）。
     */
    private String buildAuthHeader() {
        return Base64.getEncoder().encodeToString(
                (config.getUsername() + ":" + config.getPassword()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从指定输入流读取完整Sourcetable文本，直到遇到ENDSOURCETABLE行。
     *
     * @param src 已按响应头完成解块/解压的输入流
     */
    private String readSourcetable(InputStream src) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = readLine(src)) != null) {
            sb.append(line).append("\n");
            if (line.startsWith("ENDSOURCETABLE")) break;
        }
        return sb.toString();
    }

    /**
     * 从输入流读取一行文本（以LF或CRLF结尾）。
     * CR字符被跳过，LF作为行结束符。
     *
     * @return 一行文本，流结束时返回null
     */
    private String readLine() throws IOException {
        return readLine(in);
    }

    /**
     * 从指定输入流读取一行文本（以LF或CRLF结尾）。
     * CR字符被跳过，LF作为行结束符。
     *
     * @return 一行文本，流结束时返回null
     */
    private static String readLine(InputStream src) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = src.read()) != -1) {
            if (b == '\r') continue;
            if (b == '\n') break;
            baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * HTTP/1.1 分块传输编码解码流（RFC 7230 §4.1）。
     * <p>
     * 仅向调用方暴露块内有效载荷：隐藏块长行、块扩展（{@code ;} 之后）与
     * 块尾 CRLF，并把 0 长度块视为流结束。用于 Sourcetable 这类需要按行
     * 读取、而 Caster 又可能启用 chunked 的响应。
     * </p>
     */
    private static final class ChunkedInputStream extends InputStream {
        private final InputStream src;
        private int remaining = 0;
        private boolean eof = false;

        ChunkedInputStream(InputStream src) {
            this.src = src;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) return -1;
            if (len == 0) return 0;
            while (remaining == 0) {
                String sizeLine = readLine(src);
                if (sizeLine == null) {
                    eof = true;
                    return -1;
                }
                sizeLine = sizeLine.trim();
                if (sizeLine.isEmpty()) continue;
                int ext = sizeLine.indexOf(';');
                if (ext >= 0) sizeLine = sizeLine.substring(0, ext).trim();
                int size;
                try {
                    size = Integer.parseInt(sizeLine, 16);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid chunk size: " + sizeLine);
                }
                if (size == 0) {
                    eof = true;
                    return -1;
                }
                remaining = size;
            }
            int n = src.read(b, off, Math.min(len, remaining));
            if (n == -1) throw new IOException("Unexpected EOF in chunked stream");
            remaining -= n;
            if (remaining == 0) readLine(src); // 消费块数据后的 CRLF
            return n;
        }
    }

    /** 安全关闭Socket，忽略异常。 */
    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    /** 等待线程结束，最多等待3秒。 */
    private void joinThread(Thread t) {
        if (t != null) {
            try { t.join(3000); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * 解析NTRIP Sourcetable文本，提取挂载点信息。
     * <p>
     * Sourcetable中以 "STR;" 开头的行描述挂载点，格式为：
     * <pre>STR;mountpoint;...;description;format;...;network;...</pre>
     * 本方法提取挂载点名称、描述、格式和网络信息。
     * </p>
     *
     * @param table Sourcetable原始文本
     * @return 挂载点名称到描述信息的映射（保持原始顺序），描述格式为 "描述 | fmt=格式 | net=网络"
     */
    public static Map<String, String> parseSourcetable(String table) {
        Map<String, String> mounts = new LinkedHashMap<>();
        if (table == null || table.isEmpty()) return mounts;
        String[] lines = table.split("\n");
        for (String line : lines) {
            if (line.startsWith("STR;")) {
                String[] parts = line.split(";");
                if (parts.length >= 2) {
                    String mp = parts[1];
                    String desc = parts.length >= 4 ? parts[3] : "";
                    String fmt = parts.length >= 5 ? parts[4] : "";
                    String net = parts.length >= 9 ? parts[8] : "";
                    mounts.put(mp, String.format("%s | fmt=%s | net=%s", desc, fmt, net));
                }
            }
        }
        return mounts;
    }
}