package com.kement.printcam;

final class P2PConfig {
    final Endpoint p2p;
    final Endpoint stun;
    final boolean encrypted;
    final boolean useKcp;
    P2PConfig(Endpoint p2p, Endpoint stun, boolean encrypted, boolean useKcp) {
        this.p2p = p2p; this.stun = stun; this.encrypted = encrypted; this.useKcp = useKcp;
    }
}
