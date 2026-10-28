package org.rtklib.java.stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.rtklib.java.rtcm.Rtcm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;
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

    /**
     * SSR 流落盘 + 解码校验：拉取 SSR 挂载点，落盘 .rtcm3，同时用 core 的 Rtcm
     * 逐帧解码并统计 SSR 消息类型（1057-1068 轨道/钟差/偏差、1240-1263 Galileo/BDS SSR、
     * 1265-1270 相位偏差）。
     * <p>
     * 默认挂载点 SSRA01CAS0（CAS IGS 实时服务，含相位偏差）。
     * 凭据经环境变量注入，不落仓库。
     * </p>
     */
    @Test
    void recordSsrWithDecodeCheck() throws Exception {
        String host = env("NTRIP_HOST", "igs.bdsmart.cn");
        int port = Integer.parseInt(env("NTRIP_PORT", "2001"));
        String mountpoint = env("NTRIP_MOUNT", "SSRA01CAS0");
        String username = System.getenv("NTRIP_USER");
        String password = System.getenv("NTRIP_PASS");
        Assumptions.assumeTrue(username != null && !username.isBlank(),
                "未设置 NTRIP_USER，跳过 SSR 落盘测试");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "未设置 NTRIP_PASS，跳过 SSR 落盘测试");

        int seconds = Integer.parseInt(env("NTRIP_SECONDS", "120"));
        File out = new File(env("NTRIP_OUT", "target/ssr_" + mountpoint + ".rtcm3"));
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        AtomicLong received = new AtomicLong();
        Map<Integer, Integer> typeCount = new TreeMap<>();
        Rtcm rtcm = new Rtcm();
        ByteArrayOutputStream pending = new ByteArrayOutputStream();

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
                    received.addAndGet(length);
                    try {
                        os.write(data, offset, length);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    // 追加到解码缓冲并逐帧喂给 Rtcm
                    pending.write(data, offset, length);
                    byte[] buf = pending.toByteArray();
                    int consumed = 0;
                    while (consumed < buf.length) {
                        int n = rtcm.input(buf, consumed, buf.length - consumed);
                        if (n <= 0) {
                            break;
                        }
                        consumed += n;
                        typeCount.merge(rtcm.type, 1, Integer::sum);
                    }
                    // 保留未消费部分（半帧）
                    pending.reset();
                    if (consumed < buf.length) {
                        pending.write(buf, consumed, buf.length - consumed);
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

        long ssrFrames = typeCount.entrySet().stream()
                .filter(e -> isSsrType(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();

        System.out.printf("MOUNTPOINT=%s%n", mountpoint);
        System.out.printf("RECEIVED_BYTES=%d%n", received.get());
        System.out.printf("TOTAL_FRAMES=%d%n", typeCount.values().stream().mapToInt(Integer::intValue).sum());
        System.out.printf("SSR_FRAMES=%d%n", ssrFrames);
        System.out.println("MSG_TYPES=" + typeCount);
        System.out.printf("SAVED=%s%n", out.getAbsolutePath());

        assertTrue(received.get() > 0, "应接收到数据");
        assertTrue(ssrFrames > 0, "应解码出至少 1 帧 SSR 消息（1057-1068/1240-1263/1265-1270）");
    }

    /** 是否为 SSR 消息类型（轨道/钟差/码偏差/相位偏差/URA）。 */
    private static boolean isSsrType(int type) {
        return (type >= 1057 && type <= 1068)
                || (type >= 1240 && type <= 1263)
                || (type >= 1265 && type <= 1270);
    }
}
