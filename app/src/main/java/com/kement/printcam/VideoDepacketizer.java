package com.kement.printcam;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class VideoDepacketizer {
    private ByteArrayOutputStream avcFu;
    private long avcFuTs;
    private int avcFuType;
    private ByteArrayOutputStream hevcFu;
    private long hevcFuTs;
    private int hevcFuType;

    List<NalUnit> accept(MediaPacket p) {
        if (p == null || p.payload.length == 0) return new ArrayList<>();
        if (p.type == MediaPacket.TYPE_AVC) return acceptAvc(p);
        if (p.type == MediaPacket.TYPE_HEVC) return acceptHevc(p);
        return new ArrayList<>();
    }

    private List<NalUnit> acceptAvc(MediaPacket p) {
        List<NalUnit> out = new ArrayList<>();
        byte[] x = p.payload;
        int nalType = x[0] & 0x1f;
        if (nalType >= 1 && nalType <= 23) {
            out.add(new NalUnit("video/avc", nalType, p.timestamp, annex(x)));
            return out;
        }
        if (nalType == 24) {
            int pos = 1;
            while (pos + 2 <= x.length) {
                int len = ((x[pos] & 0xff) << 8) | (x[pos + 1] & 0xff);
                pos += 2;
                if (len <= 0 || pos + len > x.length) break;
                byte[] nal = Arrays.copyOfRange(x, pos, pos + len);
                int t = nal.length == 0 ? -1 : nal[0] & 0x1f;
                out.add(new NalUnit("video/avc", t, p.timestamp, annex(nal)));
                pos += len;
            }
            return out;
        }
        if (nalType == 28 && x.length >= 2) {
            int fuHeader = x[1] & 0xff;
            boolean start = (fuHeader & 0x80) != 0;
            boolean end = (fuHeader & 0x40) != 0;
            int originalType = fuHeader & 0x1f;
            if (start) {
                avcFu = new ByteArrayOutputStream(Math.max(4096, x.length * 4));
                writeStartCode(avcFu);
                avcFu.write((x[0] & 0xe0) | originalType);
                avcFu.write(x, 2, x.length - 2);
                avcFuTs = p.timestamp;
                avcFuType = originalType;
            } else if (avcFu != null) {
                avcFu.write(x, 2, x.length - 2);
            }
            if (end && avcFu != null) {
                out.add(new NalUnit("video/avc", avcFuType, avcFuTs, avcFu.toByteArray()));
                avcFu = null;
            }
        }
        return out;
    }

    private List<NalUnit> acceptHevc(MediaPacket p) {
        List<NalUnit> out = new ArrayList<>();
        byte[] x = p.payload;
        if (x.length < 2) return out;
        int nalType = (x[0] >> 1) & 0x3f;
        if (nalType <= 47) {
            out.add(new NalUnit("video/hevc", nalType, p.timestamp, annex(x)));
            return out;
        }
        if (nalType == 48) {
            int pos = 2;
            while (pos + 2 <= x.length) {
                int len = ((x[pos] & 0xff) << 8) | (x[pos + 1] & 0xff);
                pos += 2;
                if (len <= 0 || pos + len > x.length) break;
                byte[] nal = Arrays.copyOfRange(x, pos, pos + len);
                int t = nal.length < 2 ? -1 : (nal[0] >> 1) & 0x3f;
                out.add(new NalUnit("video/hevc", t, p.timestamp, annex(nal)));
                pos += len;
            }
            return out;
        }
        if (nalType == 49 && x.length >= 3) {
            int fuHeader = x[2] & 0xff;
            boolean start = (fuHeader & 0x80) != 0;
            boolean end = (fuHeader & 0x40) != 0;
            int originalType = fuHeader & 0x3f;
            if (start) {
                hevcFu = new ByteArrayOutputStream(Math.max(4096, x.length * 4));
                writeStartCode(hevcFu);
                hevcFu.write((x[0] & 0x81) | (originalType << 1));
                hevcFu.write(x[1] & 0xff);
                hevcFu.write(x, 3, x.length - 3);
                hevcFuTs = p.timestamp;
                hevcFuType = originalType;
            } else if (hevcFu != null) {
                hevcFu.write(x, 3, x.length - 3);
            }
            if (end && hevcFu != null) {
                out.add(new NalUnit("video/hevc", hevcFuType, hevcFuTs, hevcFu.toByteArray()));
                hevcFu = null;
            }
        }
        return out;
    }

    private static byte[] annex(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static void writeStartCode(ByteArrayOutputStream out) {
        out.write(0); out.write(0); out.write(0); out.write(1);
    }
}
