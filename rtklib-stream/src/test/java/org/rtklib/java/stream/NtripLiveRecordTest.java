package org.rtklib.java.stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NTRIP 实时流接收冒烟测试。
 * <p>
 * 凭据通过环境变量注入，不写入仓库：
 * NTRIP_USER / NTRIP_PASS 必填；NTRIP_HOST / NTRIP_PORT / NTRIP_MOUNT /
 * NTRIP_OUT / NTRIP_SECONDS 可选。
 * </p>
 */
class NtripLiveRecordTest {

    @Test
    void recordConfiguredSeconds() throws Exception {
        String host = env("NTRIP_HOST", "igs.bdsmart.cn");
        int port = Integer.parseInt(env("NTRIP_PORT", "2001"));
        String mountpoint = env("NTRIP_MOUNT", "WUH20");
        String username = System.getenv("NTRIP_USER");
        String password = System.getenv("NTRIP_PASS");
        Assumptions.assumeTrue(username != null && !username.isBlank(),
                "未设置 NTRIP_USER，跳过实时流测试");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "未设置 NTRIP_PASS，跳过实时流测试");

        int seconds = Integer.parseInt(env("NTRIP_SECONDS", "30"));
        File out = new File(env("NTRIP_OUT", "target/record.rtcm3"));
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        AtomicLong received = new AtomicLong();
        AtomicLong firstByte = new AtomicLong(-1);

        NtripClientConfig config = new NtripClientConfig();
        config.setHost(host);
        config.setPort(port);
        config.setMountpoint(mountpoint);
        config.setUsername(username);
        config.setPassword(password);
        config.setConnectTimeoutMs(15000);
        config.setReadTimeoutMs(15000);

        try (OutputStream os = new FileOutputStream(out)) {
            NtripClient client = new NtripClient(config, new StreamListener() {
                @Override
                public void onData(byte[] data, int offset, int length) {
                    if (length <= 0) {
                        return;
                    }
                    if (firstByte.get() < 0) {
                        firstByte.set(data[offset] & 0xFF);
                    }
                    received.addAndGet(length);
                    try {
                        os.write(data, offset, length);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onError(Exception e) {
                    System.out.println("NTRIP_ERROR: " + e);
                }

                @Override
                public void onClosed() {
                    System.out.println("NTRIP_CLOSED");
                }
            });

            client.connect();
            client.start();
            Thread.sleep(seconds * 1000L);
            client.stop();
        }

        System.out.printf("MOUNTPOINT=%s%n", mountpoint);
        System.out.printf("RECEIVED_BYTES=%d%n", received.get());
        System.out.printf("FIRST_BYTE=0x%02X%n", firstByte.get());
        System.out.printf("SAVED=%s%n", out.getAbsolutePath());

        assertTrue(received.get() > 0, "应接收到数据");
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}
