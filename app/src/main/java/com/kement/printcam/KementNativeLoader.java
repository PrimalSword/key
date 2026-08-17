package com.kement.printcam;

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
 * Loads the native pieces used by the owner's installed Kement application.
 * The binaries are never stored in this repository: they are copied at runtime
 * from com.eken.kement into this app's private code-cache directory.
 */
public final class KementNativeLoader {
    private static final String KEMENT_PACKAGE = "com.eken.kement";
    private static boolean p2pLoaded;
    private static boolean mediaLoaded;

    private KementNativeLoader() {}

    public static synchronized void ensureP2P(Context context) {
        if (p2pLoaded) return;
        try {
            File dir = nativeDir(context);
            ensureExtracted(context, dir, "libc++_shared.so");
            ensureExtracted(context, dir, "libVCTP2P.so");
            System.load(new File(dir, "libc++_shared.so").getAbsolutePath());
            System.load(new File(dir, "libVCTP2P.so").getAbsolutePath());
            p2pLoaded = true;
        } catch (Throwable e) {
            throw new IllegalStateException("Não consegui carregar o VCTP2P do Kement oficial: " + safe(e), e);
        }
    }

    /**
     * Kement 2.3.2 loads the FFmpeg/EZMedia stack in this exact order before
     * calling EZMediaUtils.decryptAESData(). Keep the same order here.
     */
    public static synchronized void ensureMedia(Context context) {
        if (mediaLoaded) return;
        try {
            File dir = nativeDir(context);
            String[] order = {
                    "libavutil.so",
                    "libswresample.so",
                    "libswscale.so",
                    "libavcodec.so",
                    "libavformat.so",
                    "libavfilter.so",
                    "libEZMediaUtils.so"
            };
            for (String name : order) ensureExtracted(context, dir, name);
            for (String name : order) System.load(new File(dir, name).getAbsolutePath());
            mediaLoaded = true;
        } catch (Throwable e) {
            throw new IllegalStateException("Não consegui carregar o decryptor de mídia do Kement oficial: " + safe(e), e);
        }
    }

    private static File nativeDir(Context context) {
        File dir = new File(context.getCodeCacheDir(), "kement-native");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar o diretório JNI privado");
        }
        return dir;
    }

    private static void ensureExtracted(Context context, File dir, String libraryName) throws Exception {
        File output = new File(dir, libraryName);
        if (output.isFile() && output.length() > 1024) return;

        List<String> apkPaths = installedKementApks(context);
        String entryName = "lib/arm64-v8a/" + libraryName;
        for (String path : apkPaths) {
            try (ZipFile zip = new ZipFile(path)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) continue;

                File temp = new File(dir, libraryName + ".tmp");
                if (temp.exists()) temp.delete();
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
                    throw new IllegalStateException("Não foi possível substituir " + libraryName);
                }
                if (!temp.renameTo(output)) {
                    throw new IllegalStateException("Não foi possível finalizar " + libraryName);
                }
                output.setReadable(true, true);
                output.setWritable(false, true);
                output.setExecutable(true, true);
                return;
            }
        }
        throw new IllegalStateException(libraryName + " não encontrada nos APKs instalados da Kement");
    }

    private static List<String> installedKementApks(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        ApplicationInfo appInfo = pm.getApplicationInfo(KEMENT_PACKAGE, 0);
        List<String> apks = new ArrayList<>();
        if (appInfo.sourceDir != null) apks.add(appInfo.sourceDir);
        if (appInfo.splitSourceDirs != null) {
            for (String split : appInfo.splitSourceDirs) {
                if (split != null) apks.add(split);
            }
        }
        if (apks.isEmpty()) throw new IllegalStateException("Kement oficial não encontrado");
        return apks;
    }

    private static String safe(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.isEmpty() ? (t == null ? "erro" : t.getClass().getSimpleName()) : m;
    }
}
