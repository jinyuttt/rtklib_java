package org.rtklib.java.stream.caster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rtklib.java.stream.nmea.NmeaGga;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NtripCasterTest {

    private static final String GGA_LINE =
            "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";

    private NtripCaster caster;
    private final List<NmeaGga> ggaEvents = new CopyOnWriteArrayList<>();
    private final AtomicInteger connected = new AtomicInteger();
    private final AtomicInteger disconnected = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (caster != null) {
            caster.stop();
            caster = null;
        }
    }

    private NtripCasterConfig newConfig() {
        NtripCasterConfig config = new NtripCasterConfig();
        config.setHost("127.0.0.1");
        config.setPort(0);
        config.addMountPoint(new MountPoint("VRS01"));
        return config;
    }

    private NtripCaster start(NtripCasterConfig config) throws IOException {
        caster = new NtripCaster(config, new NtripCasterListener() {
            @Override
            public void onClientConnected(CasterSession session) {
                connected.incrementAndGet();
            }

            @Override
            public void onClientDisconnected(CasterSession session, Throwable cause) {
                disconnected.incrementAndGet();
            }

            @Override
            public void onGga(CasterSession session, NmeaGga gga) {
                ggaEvents.add(gga);
            }
        });
        caster.start();
        return caster;
    }

    @Test
    void servesSourcetable() throws Exception {
        NtripCaster c = start(newConfig());

        try (Socket socket = connect(c)) {
            write(socket, "GET / HTTP/1.1\r\nHost: localhost\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            String head = readHead(socket.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 200 OK"), head);

            String body = readBody(socket.getInputStream(), head);
            assertTrue(body.contains("STR;VRS01;"), body);
            assertTrue(body.contains("ENDSOURCETABLE"), body);
        }
    }

    @Test
    void sourcetableEntryCarriesMountPointFields() throws Exception {
        NtripCasterConfig config = newConfig();
        MountPoint mp = config.getMountpoints().get("VRS01");
        mp.setCountry("CHN");
        mp.setLatitude(36.61);
        mp.setLongitude(117.05);
        mp.setFormatDetails("1005(1),1077(1)");
        NtripCaster c = start(config);

        try (Socket socket = connect(c)) {
            write(socket, "GET / HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            String head = readHead(socket.getInputStream());
            String body = readBody(socket.getInputStream(), head);

            assertTrue(body.contains("STR;VRS01;VRS01;RTCM 3.2;1005(1),1077(1);2;GPS+GLO+GAL+BDS;;CHN;"
                    + "36.61;117.05;1;1;rtklib-java;none;B;N;0;"), body);
        }
    }

    @Test
    void subscriberReceivesPublishedFrames() throws Exception {
        NtripCaster c = start(newConfig());

        try (Socket socket = connect(c)) {
            write(socket, "GET /VRS01 HTTP/1.1\r\nHost: localhost\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            String head = readHead(socket.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 200 OK"), head);
            assertTrue(head.contains("Content-Type: gnss/data"), head);

            await(() -> c.sessionCount("VRS01") == 1, 2000);

            byte[] frame = {(byte) 0xD3, 0x00, 0x04, 0x4C, 0xE0, 0x00, 0x00};
            c.publish("VRS01", frame);

            byte[] received = new byte[frame.length];
            readFully(socket.getInputStream(), received);
            assertArrayEquals(frame, received);
        }
    }

    @Test
    void publishesToAllSubscribersOfMountPoint() throws Exception {
        NtripCaster c = start(newConfig());
        byte[] frame = {0x01, 0x02, 0x03};

        try (Socket a = connect(c); Socket b = connect(c)) {
            write(a, "GET /VRS01 HTTP/1.0\r\n\r\n");
            write(b, "GET /VRS01 HTTP/1.0\r\n\r\n");
            assertTrue(readHead(a.getInputStream()).startsWith("ICY 200 OK"));
            assertTrue(readHead(b.getInputStream()).startsWith("ICY 200 OK"));

            await(() -> c.sessionCount("VRS01") == 2, 2000);
            c.publish("VRS01", frame);

            byte[] gotA = new byte[frame.length];
            byte[] gotB = new byte[frame.length];
            readFully(a.getInputStream(), gotA);
            readFully(b.getInputStream(), gotB);
            assertArrayEquals(frame, gotA);
            assertArrayEquals(frame, gotB);
        }
    }

    @Test
    void doesNotPublishToOtherMountPoint() throws Exception {
        NtripCasterConfig config = newConfig();
        config.addMountPoint(new MountPoint("VRS02"));
        NtripCaster c = start(config);

        try (Socket socket = connect(c)) {
            write(socket, "GET /VRS01 HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            readHead(socket.getInputStream());
            await(() -> c.sessionCount("VRS01") == 1, 2000);

            c.publish("VRS02", new byte[]{0x09});
            c.publish("VRS01", new byte[]{0x07});

            byte[] received = new byte[1];
            readFully(socket.getInputStream(), received);
            assertArrayEquals(new byte[]{0x07}, received);
        }
    }

    @Test
    void parsesUplinkGga() throws Exception {
        NtripCaster c = start(newConfig());

        try (Socket socket = connect(c)) {
            write(socket, "GET /VRS01 HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            readHead(socket.getInputStream());
            await(() -> c.sessionCount("VRS01") == 1, 2000);

            write(socket, GGA_LINE + "\r\n");

            await(() -> !ggaEvents.isEmpty(), 3000);
            assertEquals(1, ggaEvents.size());
            NmeaGga gga = ggaEvents.get(0);
            assertEquals(48 + 7.038 / 60.0, gga.latitudeDeg(), 1e-9);
            assertEquals(592.3, gga.ellipsoidHeight(), 1e-6);
        }
    }

    @Test
    void sessionCallbackFiresOnConnectAndDisconnect() throws Exception {
        NtripCaster c = start(newConfig());

        Socket socket = connect(c);
        write(socket, "GET /VRS01 HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
        readHead(socket.getInputStream());

        await(() -> connected.get() == 1, 2000);
        socket.close();
        await(() -> disconnected.get() == 1, 3000);
        await(() -> c.sessionCount() == 0, 2000);
    }

    @Test
    void rejectsUnknownMountPoint() throws Exception {
        NtripCaster c = start(newConfig());

        try (Socket socket = connect(c)) {
            write(socket, "GET /NOPE HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            String head = readHead(socket.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 404"), head);
        }
    }

    @Test
    void rejectsMissingCredentials() throws Exception {
        NtripCasterConfig config = newConfig();
        config.addUser("user", "secret");
        NtripCaster c = start(config);

        try (Socket socket = connect(c)) {
            write(socket, "GET /VRS01 HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            String head = readHead(socket.getInputStream());
            assertTrue(head.startsWith("HTTP/1.1 401"), head);
            assertTrue(head.contains("WWW-Authenticate: Basic"), head);
        }
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        NtripCasterConfig config = newConfig();
        config.addUser("user", "secret");
        NtripCaster c = start(config);

        try (Socket socket = connect(c)) {
            String auth = Base64.getEncoder().encodeToString("user:wrong".getBytes(StandardCharsets.UTF_8));
            write(socket, "GET /VRS01 HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n"
                    + "Authorization: Basic " + auth + "\r\n\r\n");
            assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 401"));
        }
    }

    @Test
    void acceptsCorrectCredentials() throws Exception {
        NtripCasterConfig config = newConfig();
        config.addUser("user", "secret");
        NtripCaster c = start(config);

        try (Socket socket = connect(c)) {
            String auth = Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
            write(socket, "GET /VRS01 HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n"
                    + "Authorization: Basic " + auth + "\r\n\r\n");
            assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
            await(() -> c.sessionCount("VRS01") == 1, 2000);
        }
    }

    @Test
    void sourcetableRemainsPublicWhenAuthEnabled() throws Exception {
        NtripCasterConfig config = newConfig();
        config.addUser("user", "secret");
        NtripCaster c = start(config);

        try (Socket socket = connect(c)) {
            write(socket, "GET / HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
            assertTrue(readHead(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
        }
    }

    @Test
    void legacyClientGetsIcyResponse() throws Exception {
        NtripCaster c = start(newConfig());

        try (Socket socket = connect(c)) {
            write(socket, "GET /VRS01 HTTP/1.0\r\n\r\n");
            assertTrue(readHead(socket.getInputStream()).startsWith("ICY 200 OK"));
        }
    }

    @Test
    void publishWithoutSubscribersIsNoOp() throws Exception {
        NtripCaster c = start(newConfig());
        c.publish("VRS01", new byte[]{1, 2, 3});
        c.publish("UNKNOWN", new byte[]{1});
        c.publish(null, new byte[]{1});
        c.publish("VRS01", new byte[0]);
        assertEquals(0, c.sessionCount());
    }

    @Test
    void stopDisconnectsActiveSessions() throws Exception {
        NtripCaster c = start(newConfig());

        Socket socket = connect(c);
        write(socket, "GET /VRS01 HTTP/1.1\r\nNtrip-Version: Ntrip/2.0\r\n\r\n");
        readHead(socket.getInputStream());
        await(() -> c.sessionCount() == 1, 2000);

        c.stop();
        assertFalse(c.isRunning());
        await(() -> c.sessionCount() == 0, 2000);
        socket.close();
    }

    private Socket connect(NtripCaster c) throws IOException {
        Socket socket = new Socket("127.0.0.1", c.port());
        socket.setSoTimeout(5000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void write(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buffer.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
            } else {
                state = 0;
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static String readBody(InputStream in, String head) throws IOException {
        int length = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] body = new byte[length];
        readFully(in, body);
        return new String(body, StandardCharsets.ISO_8859_1);
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) {
                throw new IOException("流已关闭，期望 " + buffer.length + " 字节，实收 " + offset);
            }
            offset += n;
        }
    }

    private static void await(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等待条件超时: " + timeoutMs + "ms");
    }
}