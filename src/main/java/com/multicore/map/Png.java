package com.multicore.map;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Minimal RGBA PNG writer (java.desktop / ImageIO is not guaranteed on a server). */
final class Png {
    private Png() {
    }

    /** @param rgba width*height*4 bytes, rows top to bottom */
    static byte[] encode(int width, int height, byte[] rgba) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(rgba.length / 4);
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream z = new DeflaterOutputStream(raw, deflater, 1 << 16)) {
                int stride = width * 4;
                for (int y = 0; y < height; y++) {
                    z.write(0); // filter: none
                    z.write(rgba, y * stride, stride);
                }
            } finally {
                deflater.end();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.size() + 64);
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            byte[] ihdr = new byte[13];
            putInt(ihdr, 0, width);
            putInt(ihdr, 4, height);
            ihdr[8] = 8; // bit depth
            ihdr[9] = 6; // RGBA
            chunk(out, "IHDR", ihdr);
            chunk(out, "IDAT", raw.toByteArray());
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        putInt(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
