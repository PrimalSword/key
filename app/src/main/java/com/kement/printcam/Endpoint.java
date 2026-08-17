package com.kement.printcam;

class Endpoint {
    final String host;
    final int port;
    Endpoint(String host, int port) { this.host = host; this.port = port; }
    @Override public String toString() { return host + ":" + port; }
}
