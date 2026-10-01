package io.github.stevenijm777.nrmc.capture.io;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes 2-D float32 arrays as a compressed NumPy archive (.npz), readable with numpy.load. */
public final class NpyWriter {
    private NpyWriter() {
    }

    public static void writeFloat32Npz(Path file, String arrayName, int height, int width, float[] data)
            throws IOException {
        if (data.length != height * width) {
            throw new IllegalArgumentException("expected " + height * width + " values, got " + data.length);
        }
        ByteBuffer payload = ByteBuffer.allocate(4 * data.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.asFloatBuffer().put(data);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            zip.putNextEntry(new ZipEntry(arrayName + ".npy"));
            zip.write(header("<f4", height, width));
            zip.write(payload.array());
            zip.closeEntry();
        }
    }

    /** NPY format 1.0 header; the total preamble is padded to a multiple of 64 bytes. */
    static byte[] header(String dtype, int height, int width) {
        String dict = "{'descr': '" + dtype + "', 'fortran_order': False, 'shape': (" + height + ", " + width + "), }";
        int fixed = 10; // magic (6) + version (2) + header length (2)
        int unpadded = fixed + dict.length() + 1;
        String padded = dict + " ".repeat((64 - unpadded % 64) % 64) + "\n";
        ByteBuffer out = ByteBuffer.allocate(fixed + padded.length()).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 0x93).put("NUMPY".getBytes(StandardCharsets.US_ASCII));
        out.put((byte) 1).put((byte) 0);
        out.putShort((short) padded.length());
        out.put(padded.getBytes(StandardCharsets.US_ASCII));
        return out.array();
    }
}
