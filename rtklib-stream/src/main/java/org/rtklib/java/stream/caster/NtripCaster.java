package org.rtklib.java.stream.caster;

import org.rtklib.java.stream.nmea.NmeaGga;
import org.rtklib.java.stream.nmea.NmeaGgaParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NTRIP Caster 服务端。
 *
 * <p>监听 TCP 端口，处理客户端的 NTRIP 请求：{@code GET /} 返回挂载点列表，
 * {@code GET /mountpoint} 建立下行订阅。订阅成功后客户端可上行 NMEA GGA，
 * 由 {@link NtripCasterListener#onGga} 交给应用层。</p>
 *
 * <p>本类只做连接与分发，不生成任何改正数据：下行内容全部来自
 * {@link #publish(String, byte[])}，调用方把字节交给 Caster，由 Caster
 * 复制并广播给该挂载点的全部订阅者。数据从哪来、按什么规则合成，
 * 属于应用层职责。</p>
 *
 * <p>典型用法：
 * <pre>{@code
 * NtripCasterConfig config = new NtripCasterConfig()
 *         .addMountPoint(new MountPoint("VRS01"))
 *         .addUser("user", "pass");
 * NtripCaster caster = new NtripCaster(config, listener);
 * caster.start();
 * caster.publish("VRS01", rtcmBytes);
 * }</pre>
 *
 * @see NtripCasterConfig
 * @see NtripCasterListener
 * @see CasterSession
 */
public class NtripCaster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NtripCaster.class);

    private final NtripCasterConfig config;
    private final NtripCasterListener listener;
    private final Map<String, CasterSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionSequence = new AtomicLong();

    private ServerSocket serverSocket;
    private ExecutorService connectionExecutor;
    private Thread acceptThread;
    private volatile boolean running;

    /**
     * 创建 Caster。
     *
     * @param config   配置
     * @param listener 事件监听器，可为 null
     */
    public NtripCaster(NtripCasterConfig config, NtripCasterListener listener) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        this.config = config;
        this.listener = listener;
    }

    /**
     * 启动监听。
     *
     * @throws IOException 端口占用或绑定失败
     */
    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Caster 已启动");
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getHost(), config.getPort()), config.getBacklog());
        running = true;

        connectionExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ntrip-caster-conn");
            t.setDaemon(true);
            return t;
        });
        acceptThread = new Thread(this::acceptLoop, "ntrip-caster-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        log.info("NTRIP Caster 已启动: {}:{}", config.getHost(), port());
    }

    /**
     * 停止监听并断开全部会话。
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.debug("关闭监听端口异常", e);
        }
        for (CasterSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (connectionExecutor != null) {
            connectionExecutor.shutdownNow();
        }
        log.info("NTRIP Caster 已停止");
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * 向指定挂载点的全部订阅者广播一帧数据。
     *
     * <p>非阻塞：数据被复制一次后投递到各会话队列，实际发送由会话写线程完成。
     * 无订阅者时调用是安全的空操作。</p>
     *
     * @param mountpoint 挂载点名称
     * @param data       完整数据帧，允许为空或 null
     */
    public void publish(String mountpoint, byte[] data) {
        if (mountpoint == null || data == null || data.length == 0) {
            return;
        }
        byte[] frame = data.clone();
        for (CasterSession session : sessions.values()) {
            if (mountpoint.equals(session.getMountpoint())) {
                session.send(frame);
            }
        }
    }

    /** 获取实际监听端口（配置端口为 0 时返回系统分配的端口）。 */
    public int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    /** 是否正在运行。 */
    public boolean isRunning() {
        return running;
    }

    /** 获取当前会话总数。 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 获取指定挂载点的会话数。 */
    public int sessionCount(String mountpoint) {
        int count = 0;
        for (CasterSession session : sessions.values()) {
            if (session.getMountpoint().equals(mountpoint)) {
                count++;
            }
        }
        return count;
    }

    /** 获取当前全部会话（只读快照）。 */
    public Set<CasterSession> getSessions() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sessions.values()));
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connectionExecutor.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    log.warn("接受连接失败", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        CasterSession session = null;
        Throwable cause = null;
        try {
            socket.setTcpNoDelay(true);
            if (config.getReadTimeoutMs() > 0) {
                socket.setSoTimeout(config.getReadTimeoutMs());
            }
            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));

            String requestLine = readNonEmptyLine(reader);
            if (requestLine == null) {
                return;
            }
            Map<String, String> headers = readHeaders(reader);
            boolean version2 = isVersion2(requestLine, headers);

            String path = parsePath(requestLine);
            if (path == null) {
                writeError(out, version2, "400 Bad Request");
                return;
            }

            if (path.isEmpty() || path.equals("/")) {
                writeSourcetable(out, version2);
                return;
            }

            String mountpoint = path.substring(1);
            int query = mountpoint.indexOf('?');
            if (query >= 0) {
                mountpoint = mountpoint.substring(0, query);
            }

            if (!config.hasMountPoint(mountpoint)) {
                log.info("订阅被拒绝，挂载点不存在: {} ({})", mountpoint, socket.getRemoteSocketAddress());
                writeError(out, version2, "404 Not Found");
                return;
            }
            if (config.requiresAuthentication() && !checkAuth(headers)) {
                log.info("订阅被拒绝，鉴权失败: {} ({})", mountpoint, socket.getRemoteSocketAddress());
                writeUnauthorized(out, version2);
                return;
            }

            session = new CasterSession(Long.toString(sessionSequence.incrementAndGet()),
                    mountpoint, socket, out, config.getWriteQueueSize());
            session.setUserAgent(headers.get("user-agent"));

            writeStreamResponse(out, version2);
            sessions.put(session.getId(), session);
            session.startWriter();

            log.info("客户端已订阅: {} 来自 {} [{}]", mountpoint, session.getRemoteAddress(),
                    session.getUserAgent());
            notifyConnected(session);

            readLoop(reader, session);
        } catch (SocketTimeoutException e) {
            cause = e;
        } catch (IOException e) {
            cause = e;
        } catch (RuntimeException e) {
            cause = e;
            log.warn("处理连接异常", e);
        } finally {
            if (session != null) {
                sessions.remove(session.getId());
                session.close();
                notifyDisconnected(session, cause);
                log.info("客户端已断开: {} 来自 {}，丢弃帧 {}",
                        session.getMountpoint(), session.getRemoteAddress(), session.getDroppedFrames());
            } else {
                closeQuietly(socket);
            }
        }
    }

    private void readLoop(BufferedReader reader, CasterSession session) throws IOException {
        String line;
        while (running && session.isActive()) {
            line = reader.readLine();
            if (line == null) {
                return;
            }
            if (NmeaGgaParser.isGga(line)) {
                NmeaGgaParser.parse(line).ifPresent(gga -> notifyGga(session, gga));
            }
        }
    }

    private void notifyConnected(CasterSession session) {
        if (listener == null) {
            return;
        }
        try {
            listener.onClientConnected(session);
        } catch (RuntimeException e) {
            log.warn("onClientConnected 回调异常", e);
        }
    }

    private void notifyDisconnected(CasterSession session, Throwable cause) {
        if (listener == null) {
            return;
        }
        try {
            listener.onClientDisconnected(session, cause);
        } catch (RuntimeException e) {
            log.warn("onClientDisconnected 回调异常", e);
        }
    }

    private void notifyGga(CasterSession session, NmeaGga gga) {
        if (listener == null) {
            return;
        }
        try {
            listener.onGga(session, gga);
        } catch (RuntimeException e) {
            log.warn("onGga 回调异常", e);
        }
    }

    private void writeStreamResponse(OutputStream out, boolean version2) throws IOException {
        String response;
        if (version2) {
            response = "HTTP/1.1 200 OK\r\n"
                    + "Ntrip-Version: Ntrip/2.0\r\n"
                    + "Server: " + config.getCasterId() + "\r\n"
                    + "Date: " + httpDate() + "\r\n"
                    + "Content-Type: gnss/data\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
        } else {
            response = "ICY 200 OK\r\n"
                    + "Server: " + config.getCasterId() + "\r\n"
                    + "\r\n";
        }
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void writeSourcetable(OutputStream out, boolean version2) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("SOURCETABLE 200 OK\r\n");
        for (MountPoint mountpoint : config.getMountpoints().values()) {
            body.append(mountpoint.toSourcetableEntry()).append("\r\n");
        }
        body.append("ENDSOURCETABLE\r\n");
        byte[] content = body.toString().getBytes(StandardCharsets.ISO_8859_1);

        String head;
        if (version2) {
            head = "HTTP/1.1 200 OK\r\n"
                    + "Ntrip-Version: Ntrip/2.0\r\n"
                    + "Server: " + config.getCasterId() + "\r\n"
                    + "Date: " + httpDate() + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + content.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
        } else {
            head = "SOURCETABLE 200 OK\r\n"
                    + "Server: " + config.getCasterId() + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + content.length + "\r\n"
                    + "\r\n";
        }
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(content);
        out.flush();
    }

    private void writeError(OutputStream out, boolean version2, String status) throws IOException {
        String head = (version2 ? "HTTP/1.1 " : "ERROR ") + status + "\r\n"
                + "Server: " + config.getCasterId() + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void writeUnauthorized(OutputStream out, boolean version2) throws IOException {
        String head = (version2 ? "HTTP/1.1 " : "ERROR ") + "401 Unauthorized\r\n"
                + "Server: " + config.getCasterId() + "\r\n"
                + "WWW-Authenticate: Basic realm=\"NTRIP Caster\"\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private boolean checkAuth(Map<String, String> headers) {
        String header = headers.get("authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String user = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        String expected = config.getUsers().get(user);
        return expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isVersion2(String requestLine, Map<String, String> headers) {
        String version = headers.get("ntrip-version");
        if (version != null && version.contains("2.0")) {
            return true;
        }
        return requestLine.toUpperCase(Locale.ROOT).contains("HTTP/1.1");
    }

    private static String parsePath(String requestLine) {
        String[] parts = requestLine.split("\\s+");
        return parts.length >= 2 ? parts[1] : null;
    }

    private static String readNonEmptyLine(BufferedReader reader) throws IOException {
        for (int i = 0; i < 10; i++) {
            String line = reader.readLine();
            if (line == null || !line.isEmpty()) {
                return line;
            }
        }
        return null;
    }

    private static Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new java.util.HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static String httpDate() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}