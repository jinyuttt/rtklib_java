package org.rtklib.java.stream.caster;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caster 侧的客户端会话。
 *
 * <p>一个会话对应一条已订阅挂载点的下行连接。下行数据通过 {@link #send(byte[])}
 * 投递到会话队列，由独立写线程异步发出，避免慢客户端阻塞 {@link NtripCaster#publish}
 * 的调用方。</p>
 *
 * <p>队列满时丢弃最旧帧并计入 {@link #getDroppedFrames()}：实时改正数的时效性
 * 优先于完整性，积压数据对客户端已无价值。</p>
 *
 * @see NtripCaster
 * @see NtripCasterListener
 */
public class CasterSession implements AutoCloseable {

    private final String id;
    private final String mountpoint;
    private final Socket socket;
    private final OutputStream out;
    private final BlockingQueue<byte[]> queue;
    private final Thread writer;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicLong droppedFrames = new AtomicLong();
    private volatile String userAgent = "";

    CasterSession(String id, String mountpoint, Socket socket, OutputStream out, int queueSize) {
        this.id = id;
        this.mountpoint = mountpoint;
        this.socket = socket;
        this.out = out;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.writer = new Thread(this::runWriter, "ntrip-caster-writer-" + id);
        this.writer.setDaemon(true);
    }

    void startWriter() {
        writer.start();
    }

    /** 获取会话标识。 */
    public String getId() { return id; }

    /** 获取订阅的挂载点名称。 */
    public String getMountpoint() { return mountpoint; }

    /** 获取客户端 User-Agent，未提供时为空字符串。 */
    public String getUserAgent() { return userAgent; }

    void setUserAgent(String userAgent) {
        this.userAgent = userAgent == null ? "" : userAgent;
    }

    /** 获取客户端地址，形如 {@code 192.168.1.10:53211}。 */
    public String getRemoteAddress() {
        return socket.getRemoteSocketAddress() == null
                ? "" : socket.getRemoteSocketAddress().toString();
    }

    /** 会话是否仍可写。 */
    public boolean isActive() {
        return active.get() && !socket.isClosed();
    }

    /** 获取因队列满而丢弃的帧数。 */
    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    /**
     * 向本会话投递一帧数据。
     *
     * <p>方法不阻塞、不抛异常；数据在会话关闭后被静默忽略。</p>
     *
     * @param data 待发送的完整数据帧，调用方可复用缓冲区，本方法内部持有引用不复制
     */
    public void send(byte[] data) {
        if (data == null || data.length == 0 || !isActive()) {
            return;
        }
        if (!queue.offer(data)) {
            queue.poll();
            queue.offer(data);
            droppedFrames.incrementAndGet();
        }
    }

    private void runWriter() {
        try {
            while (active.get()) {
                byte[] data = queue.poll(500, TimeUnit.MILLISECONDS);
                if (data == null) {
                    continue;
                }
                out.write(data);
                out.flush();
            }
        } catch (IOException e) {
            close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            writer.interrupt();
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public String toString() {
        return "CasterSession{" + id + ", " + mountpoint + ", " + getRemoteAddress() + "}";
    }
}