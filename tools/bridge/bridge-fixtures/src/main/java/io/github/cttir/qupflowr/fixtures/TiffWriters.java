/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * JDK-only writers of the 8-bit RGB fixtures. Neither writer uses QuPath, Bio-Formats or any
 * image reader.
 */
public final class TiffWriters {

    /** Pixel source returning {r, g, b} for a coordinate. */
    @FunctionalInterface
    public interface RgbSource {
        /**
         * Colour of a pixel.
         *
         * @param x column
         * @param y row
         * @return {r, g, b}, each 0 to 255
         */
        int[] rgb(int x, int y);
    }

    /** Number of IFD entries of the raw writer. */
    public static final int RAW_IFD_ENTRIES = 10;

    /** Offset of the BitsPerSample values in a raw TIFF. */
    public static final int RAW_BITS_OFFSET = 8 + 2 + RAW_IFD_ENTRIES * 12 + 4;

    /** Offset of the pixel strip in a raw TIFF. */
    public static final int RAW_PIXEL_OFFSET = RAW_BITS_OFFSET + 6;

    private TiffWriters() {}

    /**
     * Writes an uncompressed RGB TIFF with the JDK ImageIO TIFF writer. The writer adds
     * XResolution = YResolution = 1/1 and ResolutionUnit = 1 (no absolute unit).
     *
     * @param path target file (replaced)
     * @param width width
     * @param height height
     * @param source pixel source
     * @return class name of the ImageIO writer used
     * @throws IOException if no TIFF writer exists or writing fails
     */
    public static String writeImageIoRgb(Path path, int width, int height, RgbSource source)
            throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] c = checkedRgb(source, x, y);
                img.setRGB(x, y, (c[0] << 16) | (c[1] << 8) | c[2]);
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
        if (!writers.hasNext()) {
            throw new IOException("No ImageIO TIFF writer available in this Java runtime");
        }
        ImageWriter writer = writers.next();
        Files.deleteIfExists(path);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_DISABLED);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return writer.getClass().getName();
    }

    /**
     * Writes a little-endian classic TIFF with one uncompressed chunky RGB strip and no
     * XResolution, YResolution or ResolutionUnit tag.
     *
     * <p>Layout: header (8 bytes: {@code II}, 42, IFD offset 8); IFD with 10 entries (ImageWidth,
     * ImageLength, BitsPerSample to offset 134, Compression 1, Photometric 2, StripOffsets 140,
     * SamplesPerPixel 3, RowsPerStrip, StripByteCounts, PlanarConfiguration 1) and next IFD 0;
     * BitsPerSample 8, 8, 8 at offset 134; pixels r, g, b row by row from offset 140. SHORT values
     * are left-justified in the 4-byte value field.
     *
     * @param path target file (replaced)
     * @param width width
     * @param height height
     * @param source pixel source
     * @throws IOException if writing fails
     */
    public static void writeRawRgbWithoutResolution(
            Path path, int width, int height, RgbSource source) throws IOException {
        int pixelBytes = Math.multiplyExact(Math.multiplyExact(width, height), 3);
        ByteBuffer buf =
                ByteBuffer.allocate(RAW_PIXEL_OFFSET + pixelBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        buf.putShort((short) RAW_IFD_ENTRIES);
        entry(buf, 256, 3, 1, width);
        entry(buf, 257, 3, 1, height);
        entry(buf, 258, 3, 3, RAW_BITS_OFFSET);
        entry(buf, 259, 3, 1, 1);
        entry(buf, 262, 3, 1, 2);
        entry(buf, 273, 4, 1, RAW_PIXEL_OFFSET);
        entry(buf, 277, 3, 1, 3);
        entry(buf, 278, 3, 1, height);
        entry(buf, 279, 4, 1, pixelBytes);
        entry(buf, 284, 3, 1, 1);
        buf.putInt(0);
        buf.putShort((short) 8).putShort((short) 8).putShort((short) 8);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int[] c = checkedRgb(source, x, y);
                buf.put((byte) c[0]).put((byte) c[1]).put((byte) c[2]);
            }
        }
        Files.write(path, buf.array());
    }

    private static int[] checkedRgb(RgbSource source, int x, int y) {
        int[] c = source.rgb(x, y);
        if (c.length != 3) {
            throw new IllegalArgumentException("RGB source must return three values");
        }
        for (int v : c) {
            if (v < 0 || v > 255) {
                throw new IllegalArgumentException("RGB value out of range at " + x + "," + y);
            }
        }
        return c;
    }

    private static void entry(ByteBuffer buf, int tag, int type, int count, int value) {
        buf.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            buf.putShort((short) value).putShort((short) 0);
        } else {
            buf.putInt(value);
        }
    }
}
