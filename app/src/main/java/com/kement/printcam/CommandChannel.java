package com.kement.printcam;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class CommandChannel {
    interface Listener {
        void onCommandMessage(JSONObject msg);
        void onCommandStatus(String text);
        void onCommandDisconnected(Throwable error);
    }

    private final CommandServer server;
    private final KementApi.Session session;
    private final Listener listener;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private final StringBuilder inbound = new StringBuilder();
    private ScheduledExecutorService scheduler;

    CommandChannel(CommandServer server, KementApi.Session session, Listener listener) {
        this.server = server;
        this.session = session;
        this.listener = listener;
    }

    synchronized void connect() throws Exception {
        close();
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(server.host, server.port), 12000);
        raw.setTcpNoDelay(true);
        raw.setKeepAlive(true);

        if (server.tls) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, null, new SecureRandom());
                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, server.host, server.port, true);
                ssl.startHandshake();
                socket = ssl;
            } catch (Throwable first) {
                try { raw.close(); } catch (Throwable ignored) {}
                listener.onCommandStatus("TLS padrão recusado; usando compatibilidade do endpoint legado...");
                Socket raw2 = new Socket();
                raw2.connect(new InetSocketAddress(server.host, server.port), 12000);
                SSLContext ctx = SSLContext.getInstance("TLS");
                TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                ctx.init(null, trustAll, new SecureRandom());
                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw2, server.host, server.port, true);
                ssl.startHandshake();
                socket = ssl;
            }
        } else {
            socket = raw;
        }

        out = socket.getOutputStream();
        running = true;
        startReader();
        sendAppLogin();
        startKeepAlive();
    }

    private void sendAppLogin() throws Exception {
        JSONObject o = new JSONObject();
        o.put("cmd", "app-login");
        o.put("udid", session.appSn);
        o.put("username", session.username);
        o.put("pushToken", "pushToken");
        o.put("lang", "pt");
        o.put("platform_id", KementApi.platformId(session));
        o.put("AppName", KementApi.APP_NAME);
        o.put("k", KementApi.commandLoginKey(session.appSn));
        send(o);
    }

    void sendDevicesState() {
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", "devices-state");
            o.put("udid", session.appSn);
            send(o);
        } catch (Throwable e) {
            listener.onCommandStatus("devices-state falhou: " + e.getMessage());
        }
    }

    void sendStreamStart(String peer) {
        try { sendPeerCommand("stream-start", peer); }
        catch (Throwable e) { listener.onCommandStatus("Falha ao mandar stream-start: " + e.getMessage()); }
    }

    void sendStandby(String peer) {
        try { sendPeerCommand("standby", peer); }
        catch (Throwable ignored) {}
    }

    private void sendPeerCommand(String cmd, String peer) throws Exception {
        JSONObject o = new JSONObject();
        o.put("cmd", cmd);
        o.put("udid", session.appSn);
        o.put("peer", peer);
        send(o);
    }

    private void startKeepAlive() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KementCmdKeepAlive");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            try {
                JSONObject o = new JSONObject();
                o.put("cmd", "heartbeat");
                o.put("udid", session.appSn);
                send(o);
            } catch (Throwable e) {
                listener.onCommandStatus("Heartbeat falhou: " + e.getMessage());
            }
        }, 8, 10, TimeUnit.SECONDS);
    }

    private void startReader() throws Exception {
        final InputStream in = socket.getInputStream();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[16384];
            try {
                while (running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    synchronized (inbound) {
                        inbound.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        for (String json : extractJsonObjects(inbound)) {
                            try { listener.onCommandMessage(new JSONObject(json)); }
                            catch (Throwable parse) { listener.onCommandStatus("JSON do command server não reconhecido"); }
                        }
                    }
                }
                if (running) listener.onCommandDisconnected(null);
            } catch (Throwable e) {
                if (running) listener.onCommandDisconnected(e);
            }
        }, "KementCmdReader");
        reader.setDaemon(true);
        reader.start();
    }

    synchronized void send(JSONObject obj) throws Exception {
        if (!running || out == null) throw new IllegalStateException("command socket desconectado");
        byte[] b = obj.toString().getBytes(StandardCharsets.UTF_8);
        out.write(b);
        out.flush();
    }

    synchronized void close() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
        out = null;
        synchronized (inbound) { inbound.setLength(0); }
    }

    static List<String> extractJsonObjects(StringBuilder src) {
        List<String> out = new ArrayList<>();
        int start = -1, depth = 0, consumed = 0;
        boolean inString = false, escape = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (start < 0) {
                if (c == '{') { start = i; depth = 1; inString = false; escape = false; }
                else if (!Character.isWhitespace(c)) consumed = i + 1;
                continue;
            }
            if (inString) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    out.add(src.substring(start, i + 1));
                    consumed = i + 1;
                    start = -1;
                }
            }
        }
        if (consumed > 0 && consumed <= src.length()) src.delete(0, consumed);
        return out;
    }
}
