package com.kement.printcam;

import android.content.Context;
import android.os.PowerManager;
import android.view.Surface;

import com.kement.doorbell.p2p.P2PSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class KementEngine implements CommandChannel.Listener, P2PSession.P2PClientCall, VideoPipeline.Listener {
    interface Listener {
        void onStatus(String text);
        void onLog(String text);
        void onDevice(String name, String sn);
        void onFirstFrame();
        void onStats(long packets, long videoPackets, int lastType);
        void onBattery(Integer battery, Integer rssi);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "KementEngine"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "KementReconnect"));
    private final VideoPipeline pipeline;
    private final AtomicBoolean wanted = new AtomicBoolean(false);

    private volatile KementApi.Session session;
    private volatile JSONObject device;
    private volatile String sn;
    private volatile CommandServer commandServer;
    private volatile P2PConfig p2pConfig;
    private volatile CommandChannel command;
    private volatile P2PSession p2p;
    private volatile boolean previewActive;
    private volatile boolean p2pConnected;
    private volatile long lastPreviewAt;
    private volatile int p2pConnectAttempts;
    private volatile boolean streamStartOk;
    private volatile boolean deviceSeenInState;
    private volatile ScheduledFuture<?> statePollTask;
    private PowerManager.WakeLock wakeLock;

    KementEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        pipeline = new VideoPipeline(this);
    }

    void setSurface(Surface surface) { pipeline.setSurface(surface); }
    void clearSurface() { pipeline.clearSurface(); }

    void start(String username, String password) {
        if (wanted.getAndSet(true)) return;
        acquireWakeLock();
        worker.execute(() -> {
            try {
                listener.onStatus("Entrando na Kement...");
                listener.onLog("Login no backend oficial...");
                session = KementApi.loginAndList(username, password);
                device = KementApi.firstDevice(session);
                sn = KementApi.deviceSn(device);
                String name = KementApi.deviceName(device);
                if (sn.isEmpty()) throw new IllegalStateException("Dispositivo sem SN");
                listener.onDevice(name, sn);

                commandServer = KementApi.commandServer(session);
                p2pConfig = KementApi.p2pConfig(session);
                listener.onLog("Command: " + commandServer.host + ":" + commandServer.port + (commandServer.tls ? " TLS" : " TCP"));
                listener.onLog("P2P: " + p2pConfig.p2p + " | STUN: " + p2pConfig.stun
                        + " | encrypt=" + p2pConfig.encrypted + " | kcp=" + p2pConfig.useKcp);

                p2p = P2PSession.getInstance(context);
                p2p.addListener(this);
                p2p.setEncrypt(p2pConfig.encrypted);
                p2p.loginP2P(session.appSn, p2pConfig.p2p.host, p2pConfig.p2p.port,
                        p2pConfig.stun.host, p2pConfig.stun.port);
                listener.onLog("Sessão VCTP2P iniciada.");

                connectCommandChannel();
                startStatePolling();
                listener.onStatus("Pronto. Aperte UMA vez o botão da Kement.");
            } catch (Throwable e) {
                listener.onStatus("Falha: " + safeMessage(e));
                listener.onLog(e.getClass().getSimpleName() + ": " + safeMessage(e));
                wanted.set(false);
                cleanupSession(false, false);
            }
        });
    }

    private void connectCommandChannel() throws Exception {
        CommandChannel old = command;
        if (old != null) old.close();
        command = new CommandChannel(commandServer, session, this);
        command.connect();
        listener.onLog("Canal de comando conectado.");
        command.sendDevicesState();
    }

    private synchronized void startStatePolling() {
        if (statePollTask != null) statePollTask.cancel(false);
        statePollTask = scheduler.scheduleAtFixedRate(() -> {
            if (!wanted.get()) return;
            CommandChannel c = command;
            if (c != null) c.sendDevicesState();
        }, 1, 1, TimeUnit.SECONDS);
        listener.onLog("Fallback ativo: consultando devices-state a cada 1 s.");
    }

    private synchronized void stopStatePolling() {
        if (statePollTask != null) {
            statePollTask.cancel(false);
            statePollTask = null;
        }
    }

    void stop() {
        if (!wanted.getAndSet(false) && session == null && command == null && p2p == null) {
            releaseWakeLock();
            return;
        }
        boolean wasPreview = previewActive;
        previewActive = false;
        p2pConnected = false;
        stopStatePolling();
        worker.execute(() -> cleanupSession(true, wasPreview));
    }

    private void cleanupSession(boolean notifyUi, boolean sendStandby) {
        stopStatePolling();
        try { if (sendStandby && command != null && sn != null) command.sendStandby(sn); } catch (Throwable ignored) {}
        try { if (p2p != null && sn != null) p2p.disconnectToPeer(sn); } catch (Throwable ignored) {}
        try { if (p2p != null) p2p.removeListener(this); } catch (Throwable ignored) {}
        try { if (p2p != null) p2p.logoutP2P(); } catch (Throwable ignored) {}
        try { if (command != null) command.close(); } catch (Throwable ignored) {}
        command = null; p2p = null; session = null; device = null; sn = null;
        previewActive = false; p2pConnected = false; streamStartOk = false; p2pConnectAttempts = 0;
        deviceSeenInState = false;
        pipeline.reset();
        releaseWakeLock();
        if (notifyUi) listener.onStatus("Sessão encerrada.");
    }

    void shutdown() {
        wanted.set(false);
        boolean wasPreview = previewActive;
        cleanupSession(false, wasPreview);
        pipeline.shutdown();
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    @Override public void onCommandMessage(JSONObject msg) {
        String cmd = msg.optString("cmd", "");
        String udid = msg.optString("udid", "");

        if ("app-login".equals(cmd)) {
            listener.onLog("app-login ACK | err=" + msg.optInt("err_no", 0)
                    + " | ip=" + msg.optString("ip", "?")
                    + " | port=" + msg.optInt("port", 0));
            CommandChannel c = command;
            if (c != null) c.sendDevicesState();
            return;
        }

        if ("devices-state".equals(cmd)) {
            handleDevicesState(msg);
            return;
        }

        if ("preview-start".equals(cmd) && sn != null && sn.equals(udid)) {
            activatePreview("preview-start");
            String wakeup = msg.optString("wakeup_type", "?");
            String pk = msg.optString("pk", "");
            pipeline.setMediaKey(pk);
            listener.onLog("preview-start | wakeup=" + wakeup + " | state=" + msg.optInt("state", -1)
                    + " | mode=" + msg.optInt("mode", -1) + " | pk=" + (pk.isEmpty() ? "vazio" : "AES"));
            listener.onStatus("Campainha acordou. Preparando mídia...");
            if (command != null) command.sendStreamStart(sn);
            return;
        }

        if ("fast-streaming".equals(cmd) && sn != null && sn.equals(udid)) {
            if (!previewActive) {
                activatePreview("fast-streaming sem preview-start");
                if (command != null) command.sendStreamStart(sn);
            }
            listener.onLog("fast-streaming: dispositivo pronto para mídia.");
            scheduleP2PConnect("fast-streaming", 250);
            return;
        }

        if ("stream-start".equals(cmd)) {
            int err = msg.optInt("err_no", -999);
            String peer = msg.optString("peer", "");
            String pk = msg.optString("pk", "");
            listener.onLog("stream-start resposta | err=" + err + " | peer=" + peer
                    + " | ip=" + msg.optString("ip", "") + " | video_port=" + msg.optInt("video_port", 0)
                    + " | pk=" + (pk.isEmpty() ? "vazio" : "presente"));
            if (!pk.isEmpty()) pipeline.setMediaKey(pk);
            if (err == 0) {
                if (!previewActive) activatePreview("stream-start OK");
                streamStartOk = true;
                scheduleP2PConnect("stream-start OK", 150);
            }
            return;
        }

        if ("device-properties".equals(cmd) && sn != null && sn.equals(udid)) {
            JSONObject p = msg.optJSONObject("properties");
            if (p != null) {
                Integer rssi = p.has("rssi") ? p.optInt("rssi") : null;
                listener.onBattery(null, rssi);
                listener.onLog("Wi-Fi: RSSI " + (rssi == null ? "?" : rssi) + " dBm | FW " + p.optString("firmware_ver", "?"));
            }
            return;
        }

        if ("device-info".equals(cmd) && sn != null && sn.equals(udid)) {
            JSONObject info = msg.optJSONObject("info");
            if (info != null) {
                Integer battery = info.has("battery_level") ? info.optInt("battery_level") : null;
                Integer rssi = info.has("rssi") ? info.optInt("rssi") : null;
                listener.onBattery(battery, rssi);
            }
            return;
        }

        if (!"heartbeat".equals(cmd)) listener.onLog("CMD <- " + msg.toString());
    }

    private void handleDevicesState(JSONObject msg) {
        JSONArray devices = msg.optJSONArray("devices");
        if (devices == null) return;
        JSONObject mine = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            String peer = d.optString("sn", d.optString("peer", d.optString("udid", "")));
            if (sn != null && sn.equals(peer)) {
                mine = d;
                break;
            }
        }

        boolean seen = mine != null;
        if (seen != deviceSeenInState) {
            deviceSeenInState = seen;
            if (seen) {
                listener.onLog("devices-state: campainha apareceu ONLINE | status="
                        + mine.opt("status") + " | mode=" + mine.opt("mode"));
            } else {
                listener.onLog("devices-state: campainha offline/ausente.");
            }
        }

        if (seen && !previewActive) {
            activatePreview("devices-state");
            listener.onStatus("Campainha online detectada. Abrindo vídeo...");
            CommandChannel c = command;
            if (c != null) c.sendStreamStart(sn);
            scheduleP2PConnect("devices-state fallback", 600);
        }
    }

    private void activatePreview(String source) {
        previewActive = true;
        lastPreviewAt = System.currentTimeMillis();
        streamStartOk = false;
        p2pConnectAttempts = 0;
        listener.onLog("Sessão de mídia ativada por " + source + ".");
    }

    private void scheduleP2PConnect(String reason, long delayMs) {
        if (!wanted.get() || !previewActive) return;
        scheduler.schedule(() -> {
            if (!wanted.get() || !previewActive || p2pConnected || p2p == null || sn == null) return;
            int attempt = ++p2pConnectAttempts;
            try {
                int nat = -999;
                try { nat = p2p.getNatType(); } catch (Throwable ignored) {}
                listener.onLog("P2P connect tentativa #" + attempt + " (" + reason + ") | NAT=" + nat);
                p2p.connectToPeer(sn);
            } catch (Throwable e) {
                listener.onLog("connectToPeer tentativa #" + attempt + " falhou: " + safeMessage(e));
            }
            if (attempt < 10) {
                scheduler.schedule(() -> {
                    if (!p2pConnected && wanted.get() && previewActive) {
                        if (command != null) command.sendStreamStart(sn);
                        scheduleP2PConnect("retry", 0);
                    }
                }, 1800, TimeUnit.MILLISECONDS);
            }
        }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    @Override public void onCommandStatus(String text) { listener.onLog(text); }

    @Override public void onCommandDisconnected(Throwable error) {
        if (!wanted.get()) return;
        listener.onLog("Canal de comando caiu" + (error == null ? "." : ": " + safeMessage(error)));
        scheduler.schedule(() -> {
            if (!wanted.get()) return;
            worker.execute(() -> {
                try {
                    listener.onLog("Reconectando canal de comando...");
                    connectCommandChannel();
                    startStatePolling();
                } catch (Throwable e) {
                    listener.onLog("Reconexão falhou: " + safeMessage(e));
                    onCommandDisconnected(e);
                }
            });
        }, 3, TimeUnit.SECONDS);
    }

    @Override public void p2pConnected(String peer, boolean connected) {
        listener.onLog("CALLBACK p2pConnected | peer=" + peer + " | connected=" + connected);
        if (sn == null || peer == null) return;
        p2pConnected = connected;
        if (connected) {
            listener.onStatus("P2P conectado. Recebendo vídeo...");
            listener.onLog("P2P conectado: " + peer);
            if (command != null) command.sendStreamStart(sn);
        } else {
            listener.onLog("P2P desconectou: " + peer);
            if (wanted.get() && previewActive && System.currentTimeMillis() - lastPreviewAt < 24L * 60 * 60_000L) {
                scheduler.schedule(() -> {
                    if (!wanted.get() || p2pConnected || p2p == null || sn == null) return;
                    try {
                        listener.onLog("Tentando reconectar P2P...");
                        if (command != null) command.sendStreamStart(sn);
                        p2p.connectToPeer(sn);
                    } catch (Throwable e) {
                        listener.onLog("Reconexão P2P falhou: " + safeMessage(e));
                    }
                }, 2, TimeUnit.SECONDS);
            }
        }
    }

    @Override public void p2pReceiveDataCall(String peer, byte[] data, int nativeType) {
        if (!wanted.get() || !previewActive || data == null) return;
        if (!p2pConnected) {
            p2pConnected = true;
            listener.onLog("CALLBACK de dados P2P antes do connected | peer=" + peer + " | nativeType=" + nativeType);
            listener.onStatus("P2P recebendo dados. Decodificando vídeo...");
        }
        pipeline.accept(data);
    }

    @Override public void onPipelineStatus(String text) { listener.onLog(text); }
    @Override public void onFirstFrame() {
        listener.onStatus("VÍDEO AO VIVO — sessão mantida ativa");
        listener.onFirstFrame();
    }
    @Override public void onPacketStats(long packets, long videoPackets, int lastType) {
        listener.onStats(packets, videoPackets, lastType);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KementPrintCam:Monitor");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.isEmpty() ? (t == null ? "erro" : t.getClass().getSimpleName()) : m;
    }
}
