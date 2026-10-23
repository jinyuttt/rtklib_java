package org.rtklib.java.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NTRIP Sourcetable 响应解码单元测试。
 * <p>
 * 使用本地 ServerSocket 模拟 Caster，覆盖三种响应形态：
 * NTRIP 1.0 明文、NTRIP 2.0 chunked、NTRIP 2.0 chunked+gzip。
 * 不依赖外网与认证信息，可在 CI 稳定执行。
 * </p>
 */
class NtripSourcetableDecodeTest {

    private static final String TABLE =
            "STR;WUH20;Wuhan;Wuhan station;RTCM 3.3;1005(1),1077(1),1087(1),1097(1),1127(1);2;GPS+GLO+GAL+BDS;CHN;30;0;0;test;none;B;N;0;\r\n"
                    + "STR;HKWS;HongKong;HKWS station;RTCM 3.2;1005(1),1074(1),1084(1),1094(1);2;GPS+GLO+GAL;HKG;1;0;0;test;none;B;N;0;\r\n"
                    + "ENDSOURCETABLE\r\n";

    @Test
    void plainSourcetableNtrip10() throws Exception {
        byte[] body = TABLE.getBytes(StandardCharsets.UTF_8);
        String header = "SOURCETABLE 200 OK\r\n"
                + "Server: test-caster\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "\r\n";

        String table = fetchSourcetable(concat(header.getBytes(StandardCharsets.UTF_8), body));

        assertTrue(table.startsWith("STR;WUH20;"), "明文响应应以 STR 行起始");
        Map<String, String> mounts = NtripClient.parseSourcetable(table);
        assertEquals(2, mounts.size());
        assertTrue(mounts.containsKey("WUH20"));
        assertTrue(mounts.containsKey("HKWS"));
    }

    @Test
    void chunkedSourcetableNtrip20() throws Exception {
        byte[] body = TABLE.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n"
                + "Ntrip-Version: Ntrip/2.0\r\n"
                + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n";

        String table = fetchSourcetable(concat(header.getBytes(StandardCharsets.UTF_8), chunked(body, 40)));

        // 关键断言：分块长度行不得混入文本
        assertTrue(table.startsWith("STR;WUH20;"), "分块头部不应泄漏到 Sourcetable");
        Map<String, String> mounts = NtripClient.parseSourcetable(table);
        assertEquals(2, mounts.size());
    }

    @Test
    void gzipChunkedSourcetableNtrip20() throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(gz)) {
            gzip.write(TABLE.getBytes(StandardCharsets.UTF_8));
        }
        String header = "HTTP/1.1 200 OK\r\n"
                + "Ntrip-Version: Ntrip/2.0\r\n"
                + "Content-Encoding: gzip\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n";

        String table = fetchSourcetable(concat(header.getBytes(StandardCharsets.UTF_8), chunked(gz.toByteArray(), 17)));

        assertTrue(table.startsWith("STR;WUH20;"), "gzip+chunked 响应解码后应以 STR 行起始");
        assertTrue(table.contains("ENDSOURCETABLE"));
        assertEquals(2, NtripClient.parseSourcetable(table).size());
    }

    /** 把正文切成若干块（块长以十六进制小写给出），含结束块。 */
    private static byte[] chunked(byte[] body, int chunkSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int off = 0; off < body.length; off += chunkSize) {
            int len = Math.min(chunkSize, body.length - off);
            writeAscii(out, Integer.toHexString(len) + "\r\n");
            out.write(body, off, len);
            writeAscii(out, "\r\n");
        }
        writeAscii(out, "0\r\n\r\n");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** 启动一次性本地 Caster，返回 Sourcetable 文本。 */
    private static String fetchSourcetable(byte[] responseBytes) throws Exception {
        AtomicReference<String> receivedRequest = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket s = server.accept()) {
                    InputStream sin = s.getInputStream();
                    receivedRequest.set(readRequest(sin));
                    OutputStream sout = s.getOutputStream();
                    sout.write(responseBytes);
                    sout.flush();
                } catch (IOException ignored) {
                    // 测试用服务器，异常由客户端断言体现
                }
            }, "test-caster");
            serverThread.setDaemon(true);
            serverThread.start();

            NtripClientConfig cfg = new NtripClientConfig();
            cfg.setHost("127.0.0.1");
            cfg.setPort(server.getLocalPort());
            cfg.setUsername("u");
            cfg.setPassword("p");
            cfg.setReadTimeoutMs(5000);
            cfg.setConnectTimeoutMs(5000);

            NtripClient client = new NtripClient(cfg, new StreamListener() {
                @Override
                public void onData(byte[] data, int offset, int length) {
                }

                @Override
                public void onError(Exception e) {
                }

                @Override
                public void onClosed() {
                }
            });
            String table = client.getSourcetable();
            serverThread.join(5000);
            assertTrue(receivedRequest.get() != null && receivedRequest.get().startsWith("GET / HTTP/1.1"),
                    "客户端应发送 HTTP/1.1 GET / 请求");
            return table;
        }
    }

    /** 读取到请求头结束（空行）为止。 */
    private static String readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        int consecutiveNewlines = 0;
        while ((b = in.read()) != -1) {
            baos.write(b);
            if (b == '\n') {
                if (++consecutiveNewlines == 2) break;
            } else if (b != '\r') {
                consecutiveNewlines = 0;
            }
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
