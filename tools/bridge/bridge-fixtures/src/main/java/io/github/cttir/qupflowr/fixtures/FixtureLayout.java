/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import io.github.cttir.qupflowr.fixtures.FixtureModel.ImageDef;
import java.util.List;

/**
 * Output layout of fixture set version 1. All paths are relative to the caller-chosen output root
 * and use {@code /} separators; directory and file names are NFC.
 *
 * <pre>
 * expected/expected-values.json      written first, from the specification only
 * expected/raw-tiff-check.json       JDK-only inspection of every TIFF
 * images/rgb_512x384.tif             JDK ImageIO TIFF writer
 * images/rgb_512x384_nores.tif       raw writer, no resolution tags
 * images/ome_3c2z2t_u16.ome.tif      Bio-Formats OMETiffWriter
 * images/Ordner mit Ümlaut/rgb_512x384.tif   byte copy of the RGB fixture
 * project/...                        QuPath project with five entries (one image deleted)
 * moved/copy/project/...             QuPath project written under staging/ and then moved
 * moved/copy/images/rgb_512x384.tif  image that moved together with that project
 * manifest.json                      written last; not listed in its own file inventory
 * </pre>
 */
public final class FixtureLayout {

    /** Expected values document. */
    public static final String EXPECTED_VALUES = "expected/expected-values.json";

    /** Raw TIFF inspection report. */
    public static final String RAW_TIFF_CHECK = "expected/raw-tiff-check.json";

    /** Manifest. */
    public static final String MANIFEST = "manifest.json";

    /** RGB fixture written with the JDK ImageIO TIFF writer. */
    public static final String RGB = "images/rgb_512x384.tif";

    /** RGB fixture without resolution tags. */
    public static final String RGB_NORES = "images/rgb_512x384_nores.tif";

    /** Directory name with a space and U+00DC. */
    public static final String UMLAUT_DIR_NAME = "Ordner mit Ümlaut";

    /** Byte copy of the RGB fixture in a directory with a space and an umlaut. */
    public static final String RGB_UMLAUT_COPY = "images/" + UMLAUT_DIR_NAME + "/rgb_512x384.tif";

    /** 16-bit OME-TIFF fixture. */
    public static final String OME = "images/ome_3c2z2t_u16.ome.tif";

    /** Image that is added to the project and deleted afterwards. */
    public static final String DELETED_IMAGE = "images/deleted/rgb_512x384_deleted.tif";

    /** Directory of the deleted image (removed as well). */
    public static final String DELETED_DIR = "images/deleted";

    /** Main project directory. */
    public static final String PROJECT_DIR = "project";

    /** Main project file. */
    public static final String PROJECT_FILE = "project/project.qpproj";

    /** Directory in which the moved project is written before the move. */
    public static final String STAGING_DIR = "staging";

    /** Project file of the moved project before the move. */
    public static final String STAGING_PROJECT_FILE = "staging/project/project.qpproj";

    /** Image inside the staging tree before the move. */
    public static final String STAGING_RGB = "staging/images/rgb_512x384.tif";

    /** Parent of the moved tree. */
    public static final String MOVED_PARENT = "moved";

    /** The staging directory after the move. */
    public static final String MOVED_DIR = "moved/copy";

    /** Project file of the moved project after the move. */
    public static final String MOVED_PROJECT_FILE = "moved/copy/project/project.qpproj";

    /** Image that moved together with the project. */
    public static final String MOVED_RGB = "moved/copy/images/rgb_512x384.tif";

    /** Paths that must not exist in a complete output. */
    public static final List<String> ABSENT_PATHS =
            List.of(DELETED_IMAGE, DELETED_DIR, STAGING_DIR);

    private FixtureLayout() {}

    /**
     * Image definitions, including the deleted image.
     *
     * @return images in a fixed order
     */
    public static List<ImageDef> images() {
        String imageIo = "JDK ImageIO TIFF writer, compression disabled";
        String raw = "JDK raw TIFF writer without resolution tags";
        return List.of(
                new ImageDef(
                        "rgb", RGB, imageIo, FixtureSpec.FORMULA_RGB,
                        FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT, 3, 1, 1, "uint8",
                        "tiff_resolution_unitless_1_1", null, null, null, null, "unknown", true),
                new ImageDef(
                        "rgb_umlaut_dir", RGB_UMLAUT_COPY, "byte copy of " + RGB,
                        FixtureSpec.FORMULA_RGB, FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT,
                        3, 1, 1, "uint8", "tiff_resolution_unitless_1_1", null, null, null, null,
                        "unknown", true),
                new ImageDef(
                        "rgb_nores", RGB_NORES, raw, FixtureSpec.FORMULA_RGB,
                        FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT, 3, 1, 1, "uint8",
                        "absent", null, null, null, null, "unknown", true),
                new ImageDef(
                        "ome", OME, "Bio-Formats OMETiffWriter, uncompressed, little-endian",
                        FixtureSpec.FORMULA_OME, FixtureSpec.OME_WIDTH, FixtureSpec.OME_HEIGHT,
                        FixtureSpec.OME_SIZE_C, FixtureSpec.OME_SIZE_Z, FixtureSpec.OME_SIZE_T,
                        "uint16", "ome_xml_physical_size", FixtureSpec.OME_PIXEL_WIDTH_UM,
                        FixtureSpec.OME_PIXEL_HEIGHT_UM, FixtureSpec.OME_Z_SPACING_UM, "µm",
                        "known", true),
                new ImageDef(
                        "deleted", DELETED_IMAGE, "byte copy of " + RGB + ", deleted after use",
                        FixtureSpec.FORMULA_RGB, FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT,
                        3, 1, 1, "uint8", "file_missing", null, null, null, null, "unknown",
                        false),
                new ImageDef(
                        "moved_rgb", MOVED_RGB, "byte copy of " + RGB + ", moved with project",
                        FixtureSpec.FORMULA_RGB, FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT,
                        3, 1, 1, "uint8", "tiff_resolution_unitless_1_1", null, null, null, null,
                        "unknown", true));
    }

    /**
     * Image definition by key.
     *
     * @param key image key
     * @return definition
     */
    public static ImageDef image(String key) {
        return images().stream()
                .filter(i -> i.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown image key " + key));
    }
}
