package cn.coderfly.ezmediautils;

import android.content.Context;

import com.kement.printcam.KementNativeLoader;

/** Exact JNI facade used by the installed Kement 2.3.2 media stack. */
public final class EZMediaUtils {
    private EZMediaUtils() {}

    public static void ensureLoaded(Context context) {
        KementNativeLoader.ensureMedia(context.getApplicationContext());
    }

    public static native byte[] decryptAESData(byte[] data, String key, int aux);
}
