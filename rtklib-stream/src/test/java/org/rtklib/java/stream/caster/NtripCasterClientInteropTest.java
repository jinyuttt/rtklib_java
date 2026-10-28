package org.rtklib.java.stream.caster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rtklib.java.stream.NtripClient;
import org.rtklib.java.stream.NtripClientConfig;
import org.rtklib.java.stream.StreamListener;
import org.rtklib.java.stream.nmea.NmeaGga;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caster 与既有 {@link NtripClient} 的协议互操作验证。
 *
 * <p>本模块的客户端与服务端互为对照：客户端能订阅 Caster，Caster 能解析客户端
 * 上行的 GGA，即证明两侧实现的 NTRIP 握手、鉴权与数据分帧相互一致。</p>
 */
class NtripCasterClientInteropTest {

    private static final String GGA_LINE =
            "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";

    private NtripCaster caster;
    private NtripClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
            client = null;
        }
        if (caster != null) {
            caster.stop();
            caster = null;
        }
    }

    private NtripCaster startCaster(NtripCasterListener listener) throws IOException {
        NtripCasterConfig config = new NtripCasterConfig();
        config.setHost("127.0.0.1");
        config.setPort(0);
        config.addMountPoint(new MountPoint("VRS01"));
        caster = new NtripCaster(config, listener);
        caster.start();
        return caster;
    }

    private NtripClient createClient(String mountpoint, String user, String password,
                                     StreamListener listener) {
        NtripClientConfig config = new NtripClientConfig();
        config.setHost("127.0.0.1");
        config.setPort(caster.port());
        config.setMountpoint(mountpoint);
        config.setUsername(user);
        config.setPassword(password);
        config.setReadTimeoutMs(5000);
        client = new NtripClient(config, listener);
        return client;
    }

    @Test
    void clientSubscribesAndReceivesPublishedFrames() throws Exception {
        startCaster(null);
        ByteArrayOutputStream received = new ByteArrayOutputStream();

        NtripClient c = createClient("VRS01", "", "", new StreamListener() {
            @Override
            public void onData(byte[] data, int offset, int length) {
                received.write(data, offset, length);
            }

            @Override
            public void onError(Exception e) {
            }

            @Override
            public void onClosed() {
            }
        });

        c.connect();
        c.start();
        await(() -> caster.sessionCount("VRS01") == 1, 3000);

        byte[] frame = {(byte) 0xD3, 0x00, 0x02, 0x41, 0x10};
        caster.publish("VRS01", frame);

        await(() -> received.size() >= frame.length, 3000);
        assertArrayEquals(frame, received.toByteArray());
    }

    @Test
    void clientUplinkGgaIsParsedByCaster() throws Exception {
        List<NmeaGga> ggaEvents = new CopyOnWriteArrayList<>();
        startCaster(new NtripCasterListener() {
            @Override
            public void onGga(CasterSession session, NmeaGga gga) {
                ggaEvents.add(gga);
            }
        });

        NtripClient c = createClient("VRS01", "", "", new StreamListener() {
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

        c.connect();
        c.start();
        await(() -> caster.sessionCount("VRS01") == 1, 3000);

        c.write((GGA_LINE + "\r\n").getBytes(StandardCharsets.ISO_8859_1));

        await(() -> !ggaEvents.isEmpty(), 3000);
        assertEquals(1, ggaEvents.size());
        assertEquals(48 + 7.038 / 60.0, ggaEvents.get(0).latitudeDeg(), 1e-9);
        assertEquals(592.3, ggaEvents.get(0).ellipsoidHeight(), 1e-6);
    }

    @Test
    void clientReadsSourcetableFromCaster() throws Exception {
        startCaster(null);

        NtripClient c = createClient("VRS01", "", "", new StreamListener() {
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

        String table = c.getSourcetable();
        assertTrue(table.contains("STR;VRS01;"), table);
        assertTrue(table.contains("ENDSOURCETABLE"), table);
    }

    @Test
    void clientWithCredentialsSubscribesToAuthenticatedCaster() throws Exception {
        NtripCasterConfig config = new NtripCasterConfig();
        config.setHost("127.0.0.1");
        config.setPort(0);
        config.addMountPoint(new MountPoint("VRS01"));
        config.addUser("user", "secret");
        caster = new NtripCaster(config, null);
        caster.start();

        NtripClient c = createClient("VRS01", "user", "secret", new StreamListener() {
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

        c.connect();
        assertTrue(c.isConnected());
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