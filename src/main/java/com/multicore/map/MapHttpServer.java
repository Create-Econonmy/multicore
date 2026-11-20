package com.multicore.map;

import com.multicore.MultiCore;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * A tiny GET-only web server for the thread map (no JDK httpserver module needed). Connections from addresses
 * outside {@code allowedNetworks} are closed before a byte is read. Runs on its own daemon threads, never on a tick
 * thread.
 */
final class MapHttpServer {
    private final TerrainCache terrain;
    private final NetFilter filter;
    @Nullable
    private ServerSocket socket;
    @Nullable
    private ExecutorService pool;
    @Nullable
    private byte[] page;

    MapHttpServer(TerrainCache terrain, NetFilter filter) {
        this.terrain = terrain;
        this.filter = filter;
    }

    boolean start(String bind, int port) {
        try (InputStream in = MapHttpServer.class.getResourceAsStream("/multicore_map/index.html")) {
            this.page = in == null ? "<h1>map page missing from the jar</h1>".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
        } catch (IOException e) {
            this.page = ("<h1>" + e + "</h1>").getBytes(StandardCharsets.UTF_8);
        }
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName(bind), port), 32);
            this.socket = ss;
        } catch (IOException e) {
            MultiCore.LOGGER.warn("[MultiCore] map: could not listen on {}:{} ({}); the thread map is off", bind, port, e.toString());
            return false;
        }
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(1, 3, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "MultiCore map web " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        Thread acceptor = new Thread(this::acceptLoop, "MultiCore map web");
        acceptor.setDaemon(true);
        acceptor.start();
        MultiCore.LOGGER.info("[MultiCore] thread map on http://{}:{}/ (only answers allowedNetworks: LAN and this machine)", bind, port);
        return true;
    }

    void stop() {
        try {
            if (this.socket != null) this.socket.close();
        } catch (IOException ignored) {
        }
        if (this.pool != null) this.pool.shutdownNow();
    }

    private void acceptLoop() {
        ServerSocket ss = this.socket;
        while (ss != null && !ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (!ss.isClosed()) MultiCore.LOGGER.debug("[MultiCore] map: accept failed", e);
                continue;
            }
            if (!this.filter.allows(s.getInetAddress())) {
                closeQuietly(s);
                continue;
            }
            try {
                this.pool.execute(() -> this.handle(s));
            } catch (RejectedExecutionException e) {
                closeQuietly(s);
            }
        }
    }

    private void handle(Socket s) {
        try (s) {
            s.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            // skip headers
            for (int i = 0; i < 100; i++) {
                String h = readLine(in);
                if (h == null || h.isEmpty()) break;
            }
            String[] parts = line.split(" ");
            OutputStream out = s.getOutputStream();
            if (parts.length < 2 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))) {
                send(out, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8), "no-store", false);
                return;
            }
            boolean head = parts[0].equals("HEAD");
            String target = parts[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            Map<String, String> query = q < 0 ? Map.of() : parseQuery(target.substring(q + 1));
            switch (path) {
                case "/", "/index.html" -> send(out, 200, "text/html; charset=utf-8", this.page, "no-cache", head);
                case "/api/state" -> send(out, 200, "application/json", ThreadMap.stateJson().getBytes(StandardCharsets.UTF_8), "no-store", head);
                case "/api/tile" -> {
                    byte[] png = null;
                    try {
                        png = this.terrain.regionPng(query.getOrDefault("dim", ""),
                                Integer.parseInt(query.getOrDefault("x", "")), Integer.parseInt(query.getOrDefault("z", "")));
                    } catch (NumberFormatException ignored) {
                    }
                    if (png == null) send(out, 404, "text/plain", "no tile".getBytes(StandardCharsets.UTF_8), "no-store", head);
                    else send(out, 200, "image/png", png, query.containsKey("v") ? "max-age=86400" : "no-store", head);
                }
                default -> send(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), "no-store", head);
            }
        } catch (IOException ignored) {
            // client went away
        } catch (RuntimeException e) {
            MultiCore.LOGGER.debug("[MultiCore] map: request failed", e);
        }
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String cache, boolean head) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Error";
        };
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(body);
        out.flush();
    }

    @Nullable
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return c == -1 && sb.isEmpty() ? null : sb.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return m;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
