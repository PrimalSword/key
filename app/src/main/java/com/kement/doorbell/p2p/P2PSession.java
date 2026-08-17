package com.kement.doorbell.p2p;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JNI facade reconstructed from the owner's Kement 2.3.2 APK.
 *
 * The project intentionally does not redistribute Kement's native binaries.
 * At runtime it copies libVCTP2P.so and libc++_shared.so from the official
 * com.eken.kement APK already installed on the owner's phone into this app's
 * private code-cache directory, then loads them from there.
 */
public final class P2PSession {
    public interface P2PClientCall {
        void p2pConnected(String peer, boolean connected);
        void p2pReceiveDataCall(String peer, byte[] data, int type);
    }

    private static final String KEMENT_PACKAGE = "com.eken.kement";
    private static P2PSession instance;
    private static boolean nativeLoaded;
    private boolean hasLogin;

    private P2PSession() {}

    public static synchronized P2PSession getInstance(Context context) {
        if (!nativeLoaded) {
            loadNativeLibraries(context.getApplicationContext());
            nativeLoaded = true;
        }
        if (instance == null) instance = new P2PSession();
        return instance;
    }

    private static void loadNativeLibraries(Context context) {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("VCTP2P");
            return;
        } catch (Throwable ignored) {
        }

        try {
            File dir = new File(context.getCodeCacheDir(), "kement-native");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Não foi possível criar o diretório JNI privado");
            }

            File cxx = new File(dir, "libc++_shared.so");
            File p2p = new File(dir, "libVCTP2P.so");
            if (!cxx.isFile() || cxx.length() < 1024 || !p2p.isFile() || p2p.length() < 1024) {
                extractFromInstalledKement(context, cxx, p2p);
            }

            System.load(cxx.getAbsolutePath());
            System.load(p2p.getAbsolutePath());
        } catch (Throwable e) {
            throw new IllegalStateException(
                    "Não consegui carregar o VCTP2P do app Kement oficial. "
                            + "Mantenha com.eken.kement instalado no celular. Detalhe: " + safe(e), e);
        }
    }

    private static void extractFromInstalledKement(Context context, File cxxOut, File p2pOut) throws Exception {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo appInfo = pm.getApplicationInfo(KEMENT_PACKAGE, 0);

        List<String> apks = new ArrayList<>();
        if (appInfo.sourceDir != null) apks.add(appInfo.sourceDir);
        if (appInfo.splitSourceDirs != null) {
            for (String split : appInfo.splitSourceDirs) if (split != null) apks.add(split);
        }

        boolean gotCxx = extractEntry(apks, "lib/arm64-v8a/libc++_shared.so", cxxOut);
        boolean gotP2p = extractEntry(apks, "lib/arm64-v8a/libVCTP2P.so", p2pOut);
        if (!gotCxx || !gotP2p) {
            throw new IllegalStateException("Bibliotecas arm64-v8a não encontradas nos APKs instalados da Kement");
        }
    }

    private static boolean extractEntry(List<String> apkPaths, String entryName, File output) throws Exception {
        for (String path : apkPaths) {
            try (ZipFile zip = new ZipFile(path)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) continue;

                File temp = new File(output.getParentFile(), output.getName() + ".tmp");
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n > 0) out.write(buffer, 0, n);
                    }
                    out.getFD().sync();
                }
                if (output.exists() && !output.delete()) {
                    throw new IllegalStateException("Não foi possível substituir " + output.getName());
                }
                if (!temp.renameTo(output)) {
                    throw new IllegalStateException("Não foi possível finalizar " + output.getName());
                }
                output.setReadable(true, true);
                output.setWritable(false, true);
                output.setExecutable(true, true);
                return true;
            }
        }
        return false;
    }

    private static String safe(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.isEmpty() ? (t == null ? "erro" : t.getClass().getSimpleName()) : message;
    }

    private native void cancel();
    private native void isEncrypt(int enabled);
    private native void run(String apkId, String serverIp, int serverPort, String stunIp, int stunPort);

    public native void addListener(P2PClientCall listener);
    public native void removeListener(P2PClientCall listener);
    public native void connectToPeer(String peer);
    public native void disconnectToPeer(String peer);
    public native int getNatType();
    public native int getSpeed();
    public native int sendPANTILTData(byte[] data, int len);
    public native int sendSpeakerData(byte[] data, int len);

    public synchronized void loginP2P(String apkId, String serverIp, int serverPort,
                                      String stunIp, int stunPort) {
        if (hasLogin) return;
        run(apkId, serverIp, serverPort, stunIp, stunPort);
        hasLogin = true;
    }

    public synchronized void logoutP2P() {
        if (!hasLogin) return;
        hasLogin = false;
        cancel();
    }

    public void setEncrypt(boolean encrypt) {
        isEncrypt(encrypt ? 1 : 0);
    }
}
