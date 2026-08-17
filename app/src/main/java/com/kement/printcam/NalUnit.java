package com.kement.printcam;

final class NalUnit {
    final String mime;
    final int nalType;
    final long timestamp;
    final byte[] annexB;
    NalUnit(String mime, int nalType, long timestamp, byte[] annexB) {
        this.mime = mime; this.nalType = nalType; this.timestamp = timestamp; this.annexB = annexB;
    }
    boolean isKeyFrame() {
        if ("video/avc".equals(mime)) return nalType == 5;
        return nalType >= 16 && nalType <= 21;
    }
}
