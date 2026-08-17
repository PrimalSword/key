package com.kement.printcam;

import cn.coderfly.ezmediautils.EZMediaUtils;

import java.util.Arrays;

final class MediaPacket {
    static final int TYPE_AVC = 96;
    static final int TYPE_AUDIO = 97;
    static final int TYPE_HEVC = 100;
    static final int TYPE_AUDIO_AMR = 101;
    static final int TYPE_JPEG = 102;
    static final int TYPE_AUDIO_PCM = 103;
    static final int TYPE_AUDIO_ILBC = 105;

    private static volatile String lastParseError = "";

    final int type;
    final int flags;
    final int sequence;
    final long timestamp;
    final int aux;
    final byte[] payload;

    private MediaPacket(int type, int flags, int sequence, long timestamp, int aux, byte[] payload) {
        this.type = type;
        this.flags = flags;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.aux = aux;
        this.payload = payload;
    }

    static MediaPacket parse(byte[] raw, String mediaKey) {
        if (raw == null || raw.length < 13) return null;
        int marker = raw[0] & 0xff;
        if (marker < 128 || marker > 131) return null;
        int type = raw[1] & 0x7f;
        if (!(type == 95 || type == 96 || type == 97 || type == 100 ||
                type == 101 || type == 102 || type == 103 || type == 105)) return null;

        int sequence = (raw[2] & 0xff) | ((raw[3] & 0xff) << 8);
        long timestamp = ((long) raw[4] & 0xff)
                | (((long) raw[5] & 0xff) << 8)
                | (((long) raw[6] & 0xff) << 16)
                | (((long) raw[7] & 0xff) << 24);
        int aux = (raw[8] & 0xff) | ((raw[9] & 0xff) << 8)
                | ((raw[10] & 0xff) << 16) | ((raw[11] & 0xff) << 24);
        int flags = raw[0] & 0x03;
        byte[] payload = Arrays.copyOfRange(raw, 12, raw.length);

        if (mediaKey != null && !mediaKey.isEmpty()) {
            try {
                payload = EZMediaUtils.decryptAESData(payload, mediaKey, aux);
                if (payload == null || payload.length == 0) {
                    lastParseError = "decryptAESData retornou payload vazio";
                    return null;
                }
            } catch (Throwable e) {
                String m = e.getMessage();
                lastParseError = "AES falhou: " + (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
                return null;
            }
        }

        lastParseError = "";
        return new MediaPacket(type, flags, sequence, timestamp, aux, payload);
    }

    static String consumeLastParseError() {
        String value = lastParseError;
        lastParseError = "";
        return value;
    }
}
