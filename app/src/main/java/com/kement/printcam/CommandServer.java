package com.kement.printcam;

final class CommandServer extends Endpoint {
    final boolean tls;
    CommandServer(String host, int port, boolean tls) {
        super(host, port); this.tls = tls;
    }
}
