/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures.qupath;

import io.github.cttir.qupflowr.fixtures.FixtureSpec;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.out.OMETiffWriter;
import loci.formats.out.TiffWriter;
import ome.units.UNITS;
import ome.units.quantity.Length;

/**
 * Writes the 16-bit OME-TIFF fixture with Bio-Formats' {@link OMETiffWriter}: 256 x 192 pixels,
 * 3 channels, 2 z-slices, 2 timepoints, XYZCT, little-endian, uncompressed, anisotropic physical
 * pixel size and UTF-8 channel names.
 *
 * <p>{@code OMETiffWriter} draws a random UUID for the OME-XML ({@code UUID.randomUUID()} in its
 * private {@code getUUID(String)}, cached per file name in the private map {@code uuids}). To make
 * the file reproducible, the map is filled with the name-based UUID of formula {@code
 * ome_uuid.name_uuid_v3.v1} before {@code setId}; the writer then writes that UUID itself. This
 * relies on the Bio-Formats 8.4.0 field name and fails loudly if it changes.
 */
final class OmeTiffFixtureWriter {

    private OmeTiffFixtureWriter() {}

    /**
     * Writes the fixture.
     *
     * @param path target file (must not exist)
     * @throws IOException on write errors or when the UUID cannot be preset
     */
    static void write(Path path) throws IOException {
        int w = FixtureSpec.OME_WIDTH;
        int h = FixtureSpec.OME_HEIGHT;
        int sizeC = FixtureSpec.OME_SIZE_C;
        int sizeZ = FixtureSpec.OME_SIZE_Z;
        int sizeT = FixtureSpec.OME_SIZE_T;
        if (Files.exists(path)) {
            throw new IOException("OME-TIFF target exists: " + path);
        }
        IMetadata meta = MetadataTools.createOMEXMLMetadata();
        MetadataTools.populateMetadata(
                meta, 0, FixtureSpec.OME_IMAGE_NAME, true, FixtureSpec.OME_DIMENSION_ORDER,
                FormatTools.getPixelTypeString(FormatTools.UINT16), w, h, sizeZ, sizeC, sizeT, 1);
        meta.setPixelsPhysicalSizeX(
                new Length(FixtureSpec.OME_PIXEL_WIDTH_UM, UNITS.MICROMETER), 0);
        meta.setPixelsPhysicalSizeY(
                new Length(FixtureSpec.OME_PIXEL_HEIGHT_UM, UNITS.MICROMETER), 0);
        meta.setPixelsPhysicalSizeZ(new Length(FixtureSpec.OME_Z_SPACING_UM, UNITS.MICROMETER), 0);
        for (int c = 0; c < sizeC; c++) {
            meta.setChannelName(FixtureSpec.OME_CHANNEL_NAMES.get(c), 0, c);
        }
        String fileName = path.getFileName().toString();
        OMETiffWriter writer = new OMETiffWriter();
        try {
            presetUuid(writer, fileName, FixtureSpec.omeUuid(fileName).toString());
            writer.setMetadataRetrieve(meta);
            writer.setCompression(TiffWriter.COMPRESSION_UNCOMPRESSED);
            writer.setWriteSequentially(true);
            writer.setId(path.toString());
            int imageCount = sizeZ * sizeC * sizeT;
            for (int t = 0; t < sizeT; t++) {
                for (int c = 0; c < sizeC; c++) {
                    for (int z = 0; z < sizeZ; z++) {
                        int index = FormatTools.getIndex(
                                FixtureSpec.OME_DIMENSION_ORDER, sizeZ, sizeC, sizeT, imageCount,
                                z, c, t);
                        if (index != FixtureSpec.omePlaneIndex(c, z, t)) {
                            throw new IOException("Bio-Formats plane index " + index
                                    + " differs from the specification for c=" + c + " z=" + z
                                    + " t=" + t);
                        }
                        ByteBuffer plane = ByteBuffer.allocate(w * h * 2)
                                .order(ByteOrder.LITTLE_ENDIAN);
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                plane.putShort((short) FixtureSpec.omeValue(c, z, t, x, y));
                            }
                        }
                        writer.saveBytes(index, plane.array());
                    }
                }
            }
        } catch (FormatException e) {
            throw new IOException("Bio-Formats could not write " + path + ": " + e.getMessage(), e);
        } finally {
            writer.close();
        }
    }

    private static void presetUuid(OMETiffWriter writer, String fileName, String uuid)
            throws IOException {
        try {
            Field field = OMETiffWriter.class.getDeclaredField("uuids");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> uuids = (Map<String, String>) field.get(writer);
            uuids.put(fileName, uuid);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Cannot preset the OME-XML UUID of OMETiffWriter: " + e, e);
        }
    }
}
