package com.kement.printcam;

import android.view.Surface;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class VideoPipeline {
    interface Listener {
        void onPipelineStatus(String text);
        void onFirstFrame();
        void onPacketStats(long packets, long videoPackets, int lastType);
    }

    private final Listener listener;
    private final VideoDepacketizer depacketizer = new VideoDepacketizer();
    private final VideoDecoder decoder;
    private final AtomicLong packets = new AtomicLong();
    private final AtomicLong videoPackets = new AtomicLong();
    private volatile String mediaKey = "";
    private volatile int lastType = -1;

    VideoPipeline(Listener listener) {
        this.listener = listener;
        decoder = new VideoDecoder(new VideoDecoder.Listener() {
            @Override public void onDecoderStatus(String text) { listener.onPipelineStatus(text); }
            @Override public void onFirstFrame() { listener.onFirstFrame(); }
        });
    }

    void setMediaKey(String key) {
        mediaKey = key == null ? "" : key;
        if (!mediaKey.isEmpty()) {
            listener.onPipelineStatus("A câmera anunciou mídia AES (pk não vazio); este build ainda não decripta esse caminho.");
        }
    }
    void setSurface(Surface surface) { decoder.setSurface(surface); }
    void clearSurface() { decoder.clearSurface(); }

    void accept(byte[] raw) {
        long pcount = packets.incrementAndGet();
        MediaPacket p = MediaPacket.parse(raw, mediaKey);
        if (p == null) {
            if ((pcount % 250) == 1) listener.onPacketStats(pcount, videoPackets.get(), lastType);
            return;
        }
        lastType = p.type;
        if (p.type == MediaPacket.TYPE_AVC || p.type == MediaPacket.TYPE_HEVC) {
            long v = videoPackets.incrementAndGet();
            List<NalUnit> nals = depacketizer.accept(p);
            for (NalUnit nal : nals) decoder.accept(nal);
            if ((v % 100) == 1) listener.onPacketStats(pcount, v, lastType);
        } else if ((pcount % 200) == 1) {
            listener.onPacketStats(pcount, videoPackets.get(), lastType);
        }
    }

    void reset() {
        packets.set(0); videoPackets.set(0); lastType = -1; mediaKey = "";
        decoder.reset();
    }
    void shutdown() { decoder.shutdown(); }
}
