package org.rtklib.java.vrs.rtcm;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * {@link RtcmSink} 的现成实现工厂。
 */
public final class RtcmSinks {

    private RtcmSinks() {
    }

    /**
     * 写入文件。调用方负责关闭，用于批量离线输出。
     */
    public static RtcmSink file(String path) throws IOException {
        return file(Path.of(path));
    }

    /**
     * 写入文件。调用方负责关闭，用于批量离线输出。
     */
    public static RtcmSink file(Path path) throws IOException {
        return new StreamSink(new BufferedOutputStream(new FileOutputStream(path.toFile())));
    }

    /**
     * 在内存中累积，供调用方一次性取出全部字节。仅适合有界批量场景。
     */
    public static Collector collect() {
        return new Collector();
    }

    /**
     * 内存累积器，{@link #toByteArray()} 返回当前已收到的全部字节。
     */
    public static final class Collector implements RtcmSink {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void write(byte[] message) {
            buffer.writeBytes(message);
        }

        public byte[] toByteArray() {
            return buffer.toByteArray();
        }

        public int size() {
            return buffer.size();
        }
    }

    private static class StreamSink implements RtcmSink {
        private final OutputStream out;

        StreamSink(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(byte[] message) {
            try {
                out.write(message);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}