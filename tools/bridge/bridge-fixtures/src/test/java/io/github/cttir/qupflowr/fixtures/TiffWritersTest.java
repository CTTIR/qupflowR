/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TiffWritersTest {

    @TempDir Path tmp;

    private static Map<String, String> digestRow(String path) {
        return Tables.csv("digests.csv").stream()
                .filter(r -> r.get("path").equals(path))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void generatorClassesNeedNoQuPathOrBioFormats() {
        // The test runtime class path of this source set holds no QuPath or Bio-Formats jar.
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("qupath.lib.images.servers.ImageServer"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("loci.formats.FormatTools"));
    }

    @Test
    void rawWriterReproducesTheRReconstructedFile() throws IOException {
        Path file = tmp.resolve("raw.tif");
        TiffWriters.writeRawRgbWithoutResolution(
                file, FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT, FixtureSpec::rgb);
        Map<String, String> row = digestRow(FixtureLayout.RGB_NORES);
        assertEquals("r_reconstruction", row.get("provenance"));
        assertEquals(Long.parseLong(row.get("size")), Files.size(file));
        assertEquals(row.get("sha256"), Digests.sha256(file));
        byte[] bytes = Files.readAllBytes(file);
        assertArrayEquals(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0}, Arrays.copyOf(bytes, 8));
        assertEquals(140, TiffWriters.RAW_PIXEL_OFFSET);
        // pixel (511, 383): offset 140 + (383 * 512 + 511) * 3 -> {255, 0, 95}
        int base = 140 + (383 * 512 + 511) * 3;
        assertEquals(255, Byte.toUnsignedInt(bytes[base]));
        assertEquals(0, Byte.toUnsignedInt(bytes[base + 1]));
        assertEquals(95, Byte.toUnsignedInt(bytes[base + 2]));

        Map<String, Object> report = RawTiffInspector.checkRgb(file, "raw.tif");
        assertEquals(true, report.get("pass"));
        assertEquals(589_824L, report.get("samples_checked"));
        assertNull(report.get("resolution_unit"));
        assertNull(report.get("x_resolution_rational"));
    }

    @Test
    void imageIoWriterMatchesPinAndCarriesUnitlessResolution() throws IOException {
        Path file = tmp.resolve("imageio.tif");
        String writer = TiffWriters.writeImageIoRgb(
                file, FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT, FixtureSpec::rgb);
        assertTrue(writer.contains("TIFF"), writer);
        Map<String, Object> report = RawTiffInspector.checkRgb(file, "imageio.tif");
        assertEquals(true, report.get("pass"));
        assertArrayEquals(new long[] {1}, (long[]) report.get("resolution_unit"));
        assertArrayEquals(new long[] {1, 1}, (long[]) report.get("x_resolution_rational"));
        // Regression pin of the JDK 25 ImageIO writer, not an oracle (see digests.csv).
        Map<String, String> row = digestRow(FixtureLayout.RGB);
        assertEquals("observed_pin_jdk25_imageio", row.get("provenance"));
        assertEquals(row.get("sha256"), Digests.sha256(file));
    }

    @Test
    void inspectorCountsASingleTamperedSample() throws IOException {
        Path file = tmp.resolve("tampered.tif");
        TiffWriters.writeRawRgbWithoutResolution(
                file, FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT, FixtureSpec::rgb);
        byte[] bytes = Files.readAllBytes(file);
        bytes[140 + (100 * 512 + 300) * 3] = 1; // red of (300, 100) is 0 in the formula
        Files.write(file, bytes);
        Map<String, Object> report = RawTiffInspector.checkRgb(file, "tampered.tif");
        assertEquals(1L, report.get("sample_mismatches"));
        assertEquals(false, report.get("pass"));
    }

    @Test
    void inspectorRejectsMalformedFiles() throws IOException {
        Path file = tmp.resolve("bad.tif");
        Files.write(file, new byte[] {'X', 'X', 42, 0, 8, 0, 0, 0});
        assertThrows(IOException.class, () -> RawTiffInspector.parse(file));
        Files.write(file, new byte[] {'I', 'I', 43, 0, 8, 0, 0, 0});
        assertThrows(IOException.class, () -> RawTiffInspector.parse(file));
        Path good = tmp.resolve("good.tif");
        TiffWriters.writeRawRgbWithoutResolution(good, 4, 4, (x, y) -> new int[] {x, y, 0});
        byte[] truncated = Arrays.copyOf(Files.readAllBytes(good), 20);
        assertThrows(IOException.class, () -> RawTiffInspector.parse(truncated));
        Map<String, Object> wrongSize = RawTiffInspector.checkRgb(good, "good.tif");
        assertEquals(false, wrongSize.get("layout_matches_spec"));
        assertEquals(false, wrongSize.get("pass"));
    }

    @Test
    void writersRejectInvalidColours() {
        Path file = tmp.resolve("invalid.tif");
        assertThrows(IllegalArgumentException.class,
                () -> TiffWriters.writeRawRgbWithoutResolution(
                        file, 2, 2, (x, y) -> new int[] {256, 0, 0}));
        assertFalse(Files.exists(file), "nothing is written for an invalid source");
        assertThrows(IllegalArgumentException.class,
                () -> TiffWriters.writeImageIoRgb(file, 2, 2, (x, y) -> new int[] {0, 0}));
        assertFalse(Files.exists(file), "nothing is written for an invalid source");
    }
}
