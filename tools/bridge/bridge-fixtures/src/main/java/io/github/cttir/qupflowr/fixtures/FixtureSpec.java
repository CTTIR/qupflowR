/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Closed-form definition of the synthetic image fixtures (fixture set version 1).
 *
 * <p>Every expected pixel value is computed from the constants and formulas in this class. Nothing
 * here reads an image, so the values are an independent oracle for any reader (QuPath,
 * Bio-Formats, ImageJ, the raw TIFF inspector of this module or the R test reader).
 *
 * <p>Coordinates are full-resolution pixel indices with the origin at the top-left corner, x to
 * the right and y downwards; x, y, c, z and t are zero-based.
 */
public final class FixtureSpec {

    /** Name of the fixture set. */
    public static final String FIXTURE_SET = "qupflowr-synthetic";

    /** Version of the fixture set; incremented when any formula, constant or layout changes. */
    public static final String FIXTURE_SET_VERSION = "1";

    /** Version of the generator program. */
    public static final String GENERATOR_VERSION = "1.0.0";

    /** Namespace prefix of every name-based identifier of this fixture set. */
    public static final String ID_NAMESPACE = "qupflowr-fixtures-v1";

    /** Formula id of the RGB fixture. */
    public static final String FORMULA_RGB = "rgb.fields_gradient.v1";

    /** Formula id of the 16-bit OME-TIFF fixture. */
    public static final String FORMULA_OME = "ome.linear_czt.v1";

    /** Formula id of the object identifiers. */
    public static final String FORMULA_OBJECT_ID = "object_id.name_uuid_v3.v1";

    /** Formula id of the OME-XML UUID. */
    public static final String FORMULA_OME_UUID = "ome_uuid.name_uuid_v3.v1";

    /** Formula id of float32 storage (vertices and detection measurements). */
    public static final String FORMULA_FLOAT32 = "storage.float32.v1";

    /** Formula id of ROI areas in pixel units. */
    public static final String FORMULA_AREA = "area.px2.v1";

    /** All formula ids in a fixed order. */
    public static final List<String> FORMULA_IDS =
            List.of(
                    FORMULA_RGB,
                    FORMULA_OME,
                    FORMULA_OBJECT_ID,
                    FORMULA_OME_UUID,
                    FORMULA_FLOAT32,
                    FORMULA_AREA);

    /** Text of the RGB formula. */
    public static final String RGB_FORMULA_TEXT =
            "y < 192: rgb = fields[floor(x / 128)] with fields = [[255,0,0],[0,255,0],[0,0,255],"
                    + "[17,99,201]]; y >= 192: r = floor(x / 2), g = 255 - floor(x / 2), "
                    + "b = floor((y - 192) / 2)";

    /** Text of the OME formula. */
    public static final String OME_FORMULA_TEXT =
            "v(c, z, t, x, y) = 1000 * c + 300 * z + 60 * t + (x mod 50) + (y mod 7)";

    /** Text of the object identifier formula. */
    public static final String OBJECT_ID_FORMULA_TEXT =
            "UUID version 3 = java.util.UUID.nameUUIDFromBytes(UTF-8(\""
                    + ID_NAMESPACE
                    + ":\" + entry_key + \":\" + object_key)): MD5 of the bytes, then "
                    + "byte 6 = (byte 6 & 0x0f) | 0x30 and byte 8 = (byte 8 & 0x3f) | 0x80";

    /** Text of the OME UUID formula. */
    public static final String OME_UUID_FORMULA_TEXT =
            "UUID version 3 of UTF-8(\"" + ID_NAMESPACE + ":ome-tiff:\" + file_name)";

    /** Text of the float32 storage formula. */
    public static final String FLOAT32_FORMULA_TEXT =
            "stored = (double) (float) input (IEEE 754 round to nearest, overflow to infinity)";

    /** Text of the area formula. */
    public static final String AREA_FORMULA_TEXT =
            "rectangle w * h; ellipse pi * w * h / 4; polygon shoelace of the input vertices; "
                    + "geometry sum over polygons of exterior minus holes; calibrated area = "
                    + "area_px2 * pixel_width * pixel_height";

    // ---------------------------------------------------------------- RGB fixture

    /** Width of the RGB fixtures. */
    public static final int RGB_WIDTH = 512;

    /** Height of the RGB fixtures. */
    public static final int RGB_HEIGHT = 384;

    /** Rows {@code y < RGB_SPLIT_Y} hold constant colour fields, rows below hold the gradient. */
    public static final int RGB_SPLIT_Y = 192;

    /** Width of each constant colour field. */
    public static final int RGB_FIELD_WIDTH = 128;

    private static final int[][] RGB_FIELDS = {
        {255, 0, 0},
        {0, 255, 0},
        {0, 0, 255},
        {17, 99, 201}
    };

    /** Sample points {x, y} of the RGB fixtures listed in the expected values. */
    private static final int[][] RGB_SAMPLE_POINTS = {
        {0, 0}, {127, 0}, {128, 0}, {300, 100}, {511, 191}, {0, 192},
        {1, 192}, {2, 200}, {255, 300}, {511, 383}, {384, 10}, {400, 383}
    };

    // ---------------------------------------------------------------- OME fixture

    /** Width of the OME fixture. */
    public static final int OME_WIDTH = 256;

    /** Height of the OME fixture. */
    public static final int OME_HEIGHT = 192;

    /** Channels of the OME fixture. */
    public static final int OME_SIZE_C = 3;

    /** z-slices of the OME fixture. */
    public static final int OME_SIZE_Z = 2;

    /** Timepoints of the OME fixture. */
    public static final int OME_SIZE_T = 2;

    /** Dimension order of the OME fixture (plane index = z + 2 * (c + 3 * t)). */
    public static final String OME_DIMENSION_ORDER = "XYZCT";

    /** Physical pixel width in micrometres (anisotropic on purpose). */
    public static final double OME_PIXEL_WIDTH_UM = 0.25;

    /** Physical pixel height in micrometres. */
    public static final double OME_PIXEL_HEIGHT_UM = 0.5;

    /** z spacing in micrometres. */
    public static final double OME_Z_SPACING_UM = 1.5;

    /** Channel names with non-ASCII characters (U+00B5, U+00DC), NFC. */
    public static final List<String> OME_CHANNEL_NAMES = List.of("DAPI", "CD8 µ", "Kanal Ü");

    /** Image name written into the OME-XML. */
    public static final String OME_IMAGE_NAME = "qupflowr synthetic 3c2z2t";

    /** Largest value of the OME formula (c=2, z=1, t=1, x mod 50 = 49, y mod 7 = 6). */
    public static final int OME_MAX_VALUE = 2415;

    /** Sample points {x, y} of the OME fixture, listed for every plane. */
    private static final int[][] OME_SAMPLE_POINTS = {
        {0, 0}, {255, 191}, {49, 6}, {50, 7}, {37, 11}, {200, 100}, {1, 190}
    };

    private FixtureSpec() {}

    /**
     * Expected RGB value of the RGB fixtures.
     *
     * @param x column
     * @param y row
     * @return {r, g, b}
     */
    public static int[] rgb(int x, int y) {
        checkBounds(x, y, RGB_WIDTH, RGB_HEIGHT);
        if (y < RGB_SPLIT_Y) {
            return RGB_FIELDS[x / RGB_FIELD_WIDTH].clone();
        }
        int half = x / 2;
        return new int[] {half, 255 - half, (y - RGB_SPLIT_Y) / 2};
    }

    /**
     * Colour of one constant field.
     *
     * @param index field index 0 to 3
     * @return {r, g, b}
     */
    public static int[] rgbField(int index) {
        return RGB_FIELDS[index].clone();
    }

    /**
     * Expected uint16 value of the OME fixture.
     *
     * @param c channel
     * @param z z-slice
     * @param t timepoint
     * @param x column
     * @param y row
     * @return sample value
     */
    public static int omeValue(int c, int z, int t, int x, int y) {
        checkBounds(x, y, OME_WIDTH, OME_HEIGHT);
        checkPlane(c, z, t);
        return 1000 * c + 300 * z + 60 * t + (x % 50) + (y % 7);
    }

    /**
     * Plane index (and IFD index) of a plane in XYZCT order.
     *
     * @param c channel
     * @param z z-slice
     * @param t timepoint
     * @return zero-based plane index
     */
    public static int omePlaneIndex(int c, int z, int t) {
        checkPlane(c, z, t);
        return z + OME_SIZE_Z * (c + OME_SIZE_C * t);
    }

    /**
     * Plane of a plane index in XYZCT order.
     *
     * @param index zero-based plane index
     * @return {c, z, t}
     */
    public static int[] omePlane(int index) {
        int n = OME_SIZE_C * OME_SIZE_Z * OME_SIZE_T;
        if (index < 0 || index >= n) {
            throw new IllegalArgumentException("Plane index out of range: " + index);
        }
        int z = index % OME_SIZE_Z;
        int c = (index / OME_SIZE_Z) % OME_SIZE_C;
        int t = index / (OME_SIZE_Z * OME_SIZE_C);
        return new int[] {c, z, t};
    }

    /**
     * RGB sample points.
     *
     * @return copies of the {x, y} pairs
     */
    public static int[][] rgbSamplePoints() {
        return deepCopy(RGB_SAMPLE_POINTS);
    }

    /**
     * OME sample points.
     *
     * @return copies of the {x, y} pairs
     */
    public static int[][] omeSamplePoints() {
        return deepCopy(OME_SAMPLE_POINTS);
    }

    /**
     * Name-based (version 3) UUID of this fixture set.
     *
     * @param name name below {@link #ID_NAMESPACE}
     * @return UUID of {@code ID_NAMESPACE + ":" + name}
     */
    public static UUID nameUuid(String name) {
        return UUID.nameUUIDFromBytes((ID_NAMESPACE + ":" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * UUID written into the OME-XML of an OME-TIFF fixture.
     *
     * @param fileName file name of the OME-TIFF
     * @return name-based UUID
     */
    public static UUID omeUuid(String fileName) {
        return nameUuid("ome-tiff:" + fileName);
    }

    private static int[][] deepCopy(int[][] source) {
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].clone();
        }
        return copy;
    }

    private static void checkPlane(int c, int z, int t) {
        if (c < 0 || c >= OME_SIZE_C || z < 0 || z >= OME_SIZE_Z || t < 0 || t >= OME_SIZE_T) {
            throw new IllegalArgumentException(
                    "Plane out of range: c=" + c + ", z=" + z + ", t=" + t);
        }
    }

    private static void checkBounds(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IllegalArgumentException("Pixel out of range: x=" + x + ", y=" + y);
        }
    }
}
