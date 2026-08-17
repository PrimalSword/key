package com.kement.printcam;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

final class KementApi {
    static final String API_ROOT = "https://api.v2.gdxp.com";
    static final String APP_NAME = "kement";
    static final String APP_VERSION = "2.3.2";
    private static final String LOGIN_SIGN_SUFFIX = "EKDB_ni&Hb&Zt&zz^7qn9";
    static final String COMMAND_LOGIN_SECRET = "eead%Hb27Zf$v#vG";

    static final class Session {
        final String username;
        final String appSn;
        final String sessionId;
        final JSONObject loginJson;
        final JSONObject devicesJson;
        final List<JSONObject> devices;

        Session(String username, String appSn, String sessionId,
                JSONObject loginJson, JSONObject devicesJson, List<JSONObject> devices) {
            this.username = username;
            this.appSn = appSn;
            this.sessionId = sessionId;
            this.loginJson = loginJson;
            this.devicesJson = devicesJson;
            this.devices = devices;
        }
    }

    static String newAppSn() { return "APK_" + UUID.randomUUID(); }

    static Session loginAndList(String username, String password) throws Exception {
        String appSn = newAppSn();
        JSONObject login = login(username, password, appSn);
        JSONObject content = login.optJSONObject("content");
        if (content == null) throw new IllegalStateException("Login sem content");
        String sessionId = content.optString("session_id", "");
        if (sessionId.isEmpty()) throw new IllegalStateException("Login sem session_id");

        JSONObject list = getJson(API_ROOT + "/app_group/list_v2/" + sessionId + "/" + appSn, appSn);
        List<JSONObject> devices = new ArrayList<>();
        collectDevices(list, devices);
        if (devices.isEmpty()) throw new IllegalStateException("Nenhum dispositivo Kement encontrado na conta");
        return new Session(username, appSn, sessionId, login, list, devices);
    }

    private static JSONObject login(String username, String password, String appSn) throws Exception {
        String preUser = enc(username);
        String prePass = enc(password);
        String body = "username=" + enc(preUser)
                + "&password=" + enc(prePass)
                + "&pushToken=" + enc("pushToken")
                + "&sign=" + enc(md5(username + "/" + password + LOGIN_SIGN_SUFFIX))
                + "&appSn=" + enc(appSn);

        HttpURLConnection c = (HttpURLConnection) new URL(API_ROOT + "/app_group/login").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        applyHeaders(c, appSn);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }

        JSONObject obj = new JSONObject(readResponse(c));
        int result = obj.optInt("resultCode", Integer.MIN_VALUE);
        if (result != 0) {
            throw new IllegalStateException("Login recusado: " + obj.optString("msg", "erro") + " (" + result + ")");
        }
        return obj;
    }

    private static JSONObject getJson(String url, String appSn) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("GET");
        applyHeaders(c, appSn);
        JSONObject obj = new JSONObject(readResponse(c));
        if (obj.has("resultCode") && obj.optInt("resultCode", 0) != 0) {
            throw new IllegalStateException("API recusou lista: " + obj.optString("msg"));
        }
        return obj;
    }

    private static void applyHeaders(HttpURLConnection c, String appSn) {
        c.setRequestProperty("AppName", APP_NAME);
        c.setRequestProperty("AppLang", "pt");
        c.setRequestProperty("AppVersion", APP_VERSION);
        c.setRequestProperty("SystemVersion", "KementPrintCam_1");
        c.setRequestProperty("AppID", appSn);
        c.setRequestProperty("OS", "2");
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) throw new IllegalStateException("HTTP " + code + " sem corpo");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code + ": " + sb);
        return sb.toString();
    }

    static JSONObject firstDevice(Session s) {
        for (JSONObject d : s.devices) {
            String sn = deviceSn(d).toUpperCase();
            if (sn.startsWith("EKDB") || sn.startsWith("EK")) return d;
        }
        return s.devices.get(0);
    }

    static String deviceSn(JSONObject d) {
        for (String k : new String[]{"sn", "deviceSn", "device_sn", "peer"}) {
            String v = d.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    static String deviceName(JSONObject d) {
        for (String k : new String[]{"name", "deviceName", "device_name"}) {
            String v = d.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "Kement";
    }

    static int platformId(Session s) {
        JSONObject content = s.loginJson.optJSONObject("content");
        return content == null ? 0 : content.optInt("platform_id", 0);
    }

    static CommandServer commandServer(Session s) throws Exception {
        JSONObject cfg = findObjectWithKey(s.devicesJson, "cmd_servers");
        if (cfg == null) cfg = findObjectWithKey(s.loginJson, "cmd_servers");
        if (cfg == null) throw new IllegalStateException("Backend não anunciou cmd_servers");
        JSONArray arr = asArray(cfg.opt("cmd_servers"));
        boolean tls = cfg.optInt("enable_tls_encryption", 0) == 1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String host = firstString(o, "ip", "host", "ipv6");
            int port = tls ? firstPositive(o, "tls_port", "ssl_port", "port")
                           : firstPositive(o, "port", "ssl_port");
            if (!host.isEmpty() && port > 0) return new CommandServer(host, port, tls);
        }
        throw new IllegalStateException("cmd_servers sem host/porta utilizável");
    }

    static P2PConfig p2pConfig(Session s) throws Exception {
        JSONObject cfg = findObjectWithKey(s.devicesJson, "p2p_servers");
        if (cfg == null) cfg = findObjectWithKey(s.devicesJson, "p2p_encrypt_servers");
        if (cfg == null) cfg = findObjectWithKey(s.loginJson, "p2p_servers");
        if (cfg == null) cfg = findObjectWithKey(s.loginJson, "p2p_encrypt_servers");
        if (cfg == null) throw new IllegalStateException("Backend não anunciou configuração P2P");

        boolean encrypted = cfg.optInt("enable_encryption", 0) == 1;
        JSONArray p2pArr = encrypted ? asArray(cfg.opt("p2p_encrypt_servers")) : new JSONArray();
        if (p2pArr.length() == 0) {
            p2pArr = asArray(cfg.opt("p2p_servers"));
            encrypted = false;
        }
        JSONArray stunArr = asArray(cfg.opt("stun_servers"));
        Endpoint p2p = firstEndpoint(p2pArr);
        Endpoint stun = firstEndpoint(stunArr);
        if (p2p == null) throw new IllegalStateException("p2p_servers vazio");
        if (stun == null) throw new IllegalStateException("stun_servers vazio");
        return new P2PConfig(p2p, stun, encrypted, cfg.optInt("use_kcp", 0) == 1);
    }

    static String commandLoginKey(String appSn) throws Exception {
        return "0" + md5(COMMAND_LOGIN_SECRET + appSn + "app-login");
    }

    static JSONObject findObjectWithKey(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has(key) && obj.opt(key) != null) return obj;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONObject found = findObjectWithKey(obj.opt(keys.next()), key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findObjectWithKey(a.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectDevices(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            String sn = deviceSn(obj);
            if (sn.length() >= 6) {
                boolean exists = false;
                for (JSONObject d : out) if (sn.equals(deviceSn(d))) { exists = true; break; }
                if (!exists) out.add(obj);
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) collectDevices(obj.opt(keys.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectDevices(a.opt(i), out);
        }
    }

    private static Endpoint firstEndpoint(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                String host = firstString(o, "ip", "host", "ipv6");
                int port = firstPositive(o, "port", "udp_port", "server_port");
                if (!host.isEmpty() && port > 0) return new Endpoint(host, port);
                continue;
            }
            String raw = arr.optString(i, "").trim();
            int colon = raw.lastIndexOf(':');
            if (colon > 0 && colon < raw.length() - 1) {
                try {
                    String host = raw.substring(0, colon).trim();
                    int port = Integer.parseInt(raw.substring(colon + 1).trim());
                    if (!host.isEmpty() && port > 0) return new Endpoint(host, port);
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    private static JSONArray asArray(Object x) {
        if (x instanceof JSONArray) return (JSONArray) x;
        JSONArray a = new JSONArray();
        if (x instanceof JSONObject || x instanceof String) a.put(x);
        return a;
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            String s = o.optString(k, "");
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static int firstPositive(JSONObject o, String... keys) {
        for (String k : keys) {
            int v = o.optInt(k, 0);
            if (v > 0) return v;
        }
        return 0;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(32);
        for (byte b : digest) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
