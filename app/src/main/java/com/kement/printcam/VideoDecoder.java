package com.kement.printcam;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VideoDecoder {
    interface Listener {
        void onDecoderStatus(String text);
        void onFirstFrame();
    }

    private final Listener listener;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> new Thread(r, "KementVideoDecoder"));
    private Surface surface;
    private MediaCodec codec;
    private String mime;
    private byte[] avcSps, avcPps, hevcVps, hevcSps, hevcPps;
    private final List<NalUnit> pending = new ArrayList<>();
    private boolean firstFrameShown;

    VideoDecoder(Listener listener) { this.listener = listener; }

    synchronized void setSurface(Surface surface) {
        this.surface = surface;
        if (codec != null) resetCodecLocked();
    }
    synchronized void clearSurface() {
        surface = null;
        resetCodecLocked();
    }

    void accept(NalUnit nal) {
        if (nal != null) exec.execute(() -> acceptOnDecoderThread(nal));
    }

    private synchronized void acceptOnDecoderThread(NalUnit nal) {
        observeConfig(nal);
        if (codec == null) {
            pending.add(nal);
            if (pending.size() > 160) pending.remove(0);
            tryConfigureLocked(nal.mime);
            if (codec == null) return;
            List<NalUnit> copy = new ArrayList<>(pending);
            pending.clear();
            for (NalUnit x : copy) queueLocked(x);
            return;
        }
        if (!nal.mime.equals(mime)) {
            resetCodecLocked();
            pending.clear();
            pending.add(nal);
            tryConfigureLocked(nal.mime);
            return;
        }
        queueLocked(nal);
    }

    private void observeConfig(NalUnit nal) {
        if ("video/avc".equals(nal.mime)) {
            if (nal.nalType == 7) avcSps = nal.annexB;
            else if (nal.nalType == 8) avcPps = nal.annexB;
        } else if ("video/hevc".equals(nal.mime)) {
            if (nal.nalType == 32) hevcVps = nal.annexB;
            else if (nal.nalType == 33) hevcSps = nal.annexB;
            else if (nal.nalType == 34) hevcPps = nal.annexB;
        }
    }

    private void tryConfigureLocked(String wantedMime) {
        if (surface == null || !surface.isValid()) return;
        boolean ready = "video/avc".equals(wantedMime)
                ? avcSps != null && avcPps != null
                : hevcVps != null && hevcSps != null && hevcPps != null;
        if (!ready) return;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(wantedMime, 1920, 1080);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            if ("video/avc".equals(wantedMime)) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(avcSps));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(avcPps));
            } else {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(concat(hevcVps, hevcSps, hevcPps)));
            }
            codec = MediaCodec.createDecoderByType(wantedMime);
            codec.configure(format, surface, null, 0);
            codec.start();
            mime = wantedMime;
            firstFrameShown = false;
            listener.onDecoderStatus("Decoder ativo: " + wantedMime);
        } catch (Throwable e) {
            listener.onDecoderStatus("Falha ao iniciar decoder " + wantedMime + ": " + e.getMessage());
            resetCodecLocked();
        }
    }

    private void queueLocked(NalUnit nal) {
        if (codec == null || nal.annexB == null || nal.annexB.length == 0) return;
        try {
            int inIndex = codec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                ByteBuffer in = codec.getInputBuffer(inIndex);
                if (in != null) {
                    in.clear();
                    if (nal.annexB.length <= in.remaining()) {
                        in.put(nal.annexB);
                        long ptsUs = nal.timestamp > 0 ? nal.timestamp * 1000L : System.nanoTime() / 1000L;
                        int flags = nal.isKeyFrame() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        codec.queueInputBuffer(inIndex, 0, nal.annexB.length, ptsUs, flags);
                    } else {
                        listener.onDecoderStatus("NAL grande demais: " + nal.annexB.length + " B");
                    }
                }
            }
            drainLocked();
        } catch (Throwable e) {
            listener.onDecoderStatus("Erro no decoder: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            resetCodecLocked();
        }
    }

    private void drainLocked() {
        if (codec == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(info, 0);
            if (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true);
                if (!firstFrameShown) {
                    firstFrameShown = true;
                    listener.onFirstFrame();
                }
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                listener.onDecoderStatus("Vídeo: " + codec.getOutputFormat());
            } else break;
        }
    }

    synchronized void reset() {
        pending.clear();
        avcSps = avcPps = null;
        hevcVps = hevcSps = hevcPps = null;
        resetCodecLocked();
    }

    private void resetCodecLocked() {
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
        }
        codec = null;
        mime = null;
        firstFrameShown = false;
    }

    void shutdown() {
        reset();
        exec.shutdownNow();
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) if (p != null) n += p.length;
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
