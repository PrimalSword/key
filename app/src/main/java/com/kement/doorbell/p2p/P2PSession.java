package com.kement.doorbell.p2p;

import android.content.Context;

import com.kement.printcam.KementNativeLoader;

/** JNI facade matching libVCTP2P.so from the owner's installed Kement app. */
public final class P2PSession {
    public interface P2PClientCall {
        void p2pConnected(String peer, boolean connected);
        void p2pReceiveDataCall(String peer, byte[] data, int type);
    }

    private static P2PSession instance;
    private boolean hasLogin;

    private P2PSession() {}

    public static synchronized P2PSession getInstance(Context context) {
        KementNativeLoader.ensureP2P(context.getApplicationContext());
        if (instance == null) instance = new P2PSession();
        return instance;
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
