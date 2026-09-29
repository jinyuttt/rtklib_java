package org.rtklib.java.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class NtripClient {

    private static final Logger log = LoggerFactory.getLogger(NtripClient.class);

    private final String host;
    private final int port;
    private final String mountpoint;
    private final String username;
    private final String password;
    private final DataListener listener;

    private Socket socket;
    private InputStream in;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readThread;

    public interface DataListener {
        void onData(byte[] data, int offset, int length);
        void onSourcetable(String sourcetable);
        void onError(Exception e);
    }

    public NtripClient(String host, int port, String mountpoint,
                       String username, String password, DataListener listener) {
        this.host = host;
        this.port = port;
        this.mountpoint = mountpoint;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    public void connect() throws IOException {
        socket = new Socket();
        socket.setSoTimeout(10000);
        socket.connect(new java.net.InetSocketAddress(host, port), 15000);
        in = socket.getInputStream();

        OutputStream out = socket.getOutputStream();
        String auth = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));

        if (mountpoint == null || mountpoint.isEmpty()) {
            String req = "GET / HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: NTRIP rtklib-java/2.1\r\n"
                    + "Authorization: Basic " + auth + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String table = readSourcetable();
            listener.onSourcetable(table);
            socket.close();
            return;
        }

        String req = "GET /" + mountpoint + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: NTRIP rtklib-java/2.1\r\n"
                + "Authorization: Basic " + auth + "\r\n"
                + "Ntrip-Version: Ntrip/2.0\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        String statusLine = readLine();
        if (statusLine == null || !statusLine.contains("200")) {
            throw new IOException("NTRIP connection failed: " + statusLine);
        }

        String line;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) break;
        }

        log.info("NTRIP connected: {}:{}/{}", host, port, mountpoint);
    }

    private String readSourcetable() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = readLine()) != null) {
            sb.append(line).append("\n");
            if (line.startsWith("ENDSOURCETABLE")) break;
        }
        return sb.toString();
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') continue;
            if (b == '\n') break;
            baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    public void start() {
        running.set(true);
        readThread = new Thread(this::readLoop, "NTRIP-Reader");
        readThread.setDaemon(true);
        readThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[4096];
        try {
            socket.setSoTimeout(0);
            while (running.get()) {
                int n = in.read(buf);
                if (n == -1) break;
                if (n > 0) {
                    listener.onData(buf, 0, n);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                listener.onError(e);
            }
        } finally {
            running.set(false);
            log.info("NTRIP read loop ended");
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        if (readThread != null) {
            try { readThread.join(3000); } catch (InterruptedException ignored) {}
        }
    }

    public boolean isRunning() { return running.get(); }

    public static Map<String, String> parseSourcetable(String table) {
        Map<String, String> mounts = new LinkedHashMap<>();
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