package com.mudassir.vidstash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Writes iTunes-style tags (title, artist, album, cover art) into an MP4/M4A file, so
 * downloaded audio shows up properly in music players.
 *
 * Works by rebuilding the "moov" box with a fresh moov/udta/meta/ilst. If moov sits in
 * front of the media data, the chunk offset tables (stco/co64) are shifted to match.
 * Written in plain Java so it can be unit-tested off-device.
 */
public final class Mp4Tagger {

    private Mp4Tagger() {}

    /** Tags {@code input} and writes the result to {@code output}. Null values are skipped. */
    public static void tag(File input, File output, String title, String artist, String album,
                           byte[] jpegCover) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(input, "r")) {
            long fileLen = in.length();
            long moovPos = -1, moovSize = 0;
            boolean mediaAfterMoov = false;

            long pos = 0;
            while (pos + 8 <= fileLen) {
                in.seek(pos);
                long size = in.readInt() & 0xFFFFFFFFL;
                String type = readType(in);
                int header = 8;
                if (size == 1) {
                    size = in.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = fileLen - pos;
                }
                if (size < header) throw new IOException("Corrupt MP4 box at " + pos);
                if (type.equals("moov")) {
                    moovPos = pos;
                    moovSize = size;
                } else if (type.equals("mdat") && moovPos >= 0) {
                    mediaAfterMoov = true;
                }
                pos += size;
            }
            if (moovPos < 0) throw new IOException("No moov box");
            if (moovSize > 64L * 1024 * 1024) throw new IOException("moov box too large");

            byte[] moov = new byte[(int) moovSize];
            in.seek(moovPos);
            in.readFully(moov);

            byte[] newMoov = rebuildMoov(moov, buildUdta(title, artist, album, jpegCover));
            long delta = newMoov.length - moov.length;
            if (mediaAfterMoov && delta != 0) {
                shiftChunkOffsets(newMoov, 8, newMoov.length, delta);
            }

            try (OutputStream out = new FileOutputStream(output)) {
                copy(in, 0, moovPos, out);
                out.write(newMoov);
                copy(in, moovPos + moovSize, fileLen - moovPos - moovSize, out);
            }
        }
    }

    // ---- moov rebuilding ----

    /** Returns moov with any existing udta removed and the new udta appended. */
    static byte[] rebuildMoov(byte[] moov, byte[] udta) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int pos = 8; // moov uses a 32-bit header in practice (it is far smaller than 4 GB)
        while (pos + 8 <= moov.length) {
            int size = ByteBuffer.wrap(moov, pos, 4).getInt();
            String type = new String(moov, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (size < 8 || pos + size > moov.length) break;
            if (!type.equals("udta")) body.write(moov, pos, size);
            pos += size;
        }
        body.write(udta, 0, udta.length);
        return box("moov", body.toByteArray());
    }

    static byte[] buildUdta(String title, String artist, String album, byte[] jpegCover) {
        ByteArrayOutputStream ilst = new ByteArrayOutputStream();
        writeText(ilst, "©nam", title);
        writeText(ilst, "©ART", artist);
        writeText(ilst, "©alb", album);
        writeText(ilst, "©too", "VidStash");
        if (jpegCover != null && jpegCover.length > 0) {
            byte[] item = dataItem("covr", 13, jpegCover);
            ilst.write(item, 0, item.length);
        }

        // hdlr: version/flags, pre_defined, handler "mdir", 3 reserved words ("appl", 0, 0), empty name
        ByteBuffer hdlr = ByteBuffer.allocate(4 + 4 + 4 + 12 + 1);
        hdlr.putInt(0).putInt(0).put("mdir".getBytes(StandardCharsets.ISO_8859_1))
            .put("appl".getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(0).put((byte) 0);

        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        meta.write(0); meta.write(0); meta.write(0); meta.write(0); // meta is a full box
        byte[] h = box("hdlr", hdlr.array());
        meta.write(h, 0, h.length);
        byte[] il = box("ilst", ilst.toByteArray());
        meta.write(il, 0, il.length);

        return box("udta", box("meta", meta.toByteArray()));
    }

    private static void writeText(ByteArrayOutputStream ilst, String key, String value) {
        if (value == null || value.isEmpty()) return;
        byte[] item = dataItem(key, 1, value.getBytes(StandardCharsets.UTF_8));
        ilst.write(item, 0, item.length);
    }

    /** An ilst item: key box containing a "data" box (type indicator + locale + payload). */
    private static byte[] dataItem(String key, int dataType, byte[] payload) {
        ByteBuffer data = ByteBuffer.allocate(8 + payload.length);
        data.putInt(dataType).putInt(0).put(payload);
        return box(key, box("data", data.array()));
    }

    private static byte[] box(String type, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length);
        b.putInt(8 + body.length).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(body);
        return b.array();
    }

    // ---- chunk offset patching ----

    /** Adds {@code delta} to every stco/co64 entry found under the container range. */
    static void shiftChunkOffsets(byte[] buf, int start, int end, long delta) {
        int pos = start;
        while (pos + 8 <= end) {
            ByteBuffer bb = ByteBuffer.wrap(buf);
            int size = bb.getInt(pos);
            String type = new String(buf, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (size < 8 || pos + size > end) return;
            switch (type) {
                case "trak": case "mdia": case "minf": case "stbl": case "edts":
                    shiftChunkOffsets(buf, pos + 8, pos + size, delta);
                    break;
                case "stco": {
                    int count = bb.getInt(pos + 12);
                    for (int i = 0; i < count; i++) {
                        int at = pos + 16 + i * 4;
                        long v = (bb.getInt(at) & 0xFFFFFFFFL) + delta;
                        bb.putInt(at, (int) v);
                    }
                    break;
                }
                case "co64": {
                    int count = bb.getInt(pos + 12);
                    for (int i = 0; i < count; i++) {
                        int at = pos + 16 + i * 8;
                        bb.putLong(at, bb.getLong(at) + delta);
                    }
                    break;
                }
                default:
                    break;
            }
            pos += size;
        }
    }

    // ---- io helpers ----

    private static String readType(RandomAccessFile in) throws IOException {
        byte[] t = new byte[4];
        in.readFully(t);
        return new String(t, StandardCharsets.ISO_8859_1);
    }

    private static void copy(RandomAccessFile in, long from, long len, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        in.seek(from);
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            out.write(buf, 0, n);
            left -= n;
        }
    }
}
