/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import io.github.cttir.qupflowr.fixtures.FixtureModel.EntryDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.ImageDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Measurement;
import io.github.cttir.qupflowr.fixtures.FixtureModel.ObjectDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.ObjectType;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Plane;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Roi;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the expected-values document from the specification classes only. The document is
 * written before any image reader opens a fixture and is the oracle for every reader test.
 */
public final class ExpectedValues {

    /** Document name. */
    public static final String DOCUMENT = "qupflowr-fixture-expected-values";

    /** Document version. */
    public static final String DOCUMENT_VERSION = "1";

    private ExpectedValues() {}

    /**
     * Builds the complete document.
     *
     * @return ordered map ready for {@link JsonWriter}
     */
    public static Map<String, Object> document() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("document", DOCUMENT);
        doc.put("document_version", DOCUMENT_VERSION);
        doc.put("fixture_set", FixtureSpec.FIXTURE_SET);
        doc.put("fixture_set_version", FixtureSpec.FIXTURE_SET_VERSION);
        doc.put("generator_version", FixtureSpec.GENERATOR_VERSION);
        doc.put(
                "coordinate_convention",
                "full-resolution pixels, origin top-left, x right, y down; zero-based c, z, t; "
                        + "c = null means no single channel (QuPath -1)");
        doc.put("formulas", formulas());
        doc.put("images", images());
        doc.put("pixel_samples", pixelSamples());
        doc.put("planes", planes());
        doc.put("projects", List.of(mainProject(), movedProject()));
        doc.put("measurements", measurements());
        doc.put("negative_cases", List.of(negativeColon()));
        doc.put("absent_paths", FixtureLayout.ABSENT_PATHS);
        return doc;
    }

    /**
     * Formula ids and texts.
     *
     * @return list of {id, text}
     */
    public static List<Map<String, Object>> formulas() {
        List<String> texts =
                List.of(
                        FixtureSpec.RGB_FORMULA_TEXT,
                        FixtureSpec.OME_FORMULA_TEXT,
                        FixtureSpec.OBJECT_ID_FORMULA_TEXT,
                        FixtureSpec.OME_UUID_FORMULA_TEXT,
                        FixtureSpec.FLOAT32_FORMULA_TEXT,
                        FixtureSpec.AREA_FORMULA_TEXT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < FixtureSpec.FORMULA_IDS.size(); i++) {
            out.add(ordered("id", FixtureSpec.FORMULA_IDS.get(i), "text", texts.get(i)));
        }
        return out;
    }

    private static List<Map<String, Object>> images() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ImageDef image : FixtureLayout.images()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", image.key());
            m.put("path", image.path());
            m.put("writer", image.writer());
            m.put("formula_id", image.formulaId());
            m.put("width", image.width());
            m.put("height", image.height());
            m.put("size_c", image.sizeC());
            m.put("size_z", image.sizeZ());
            m.put("size_t", image.sizeT());
            m.put("pixel_type", image.pixelType());
            boolean ome = "ome".equals(image.key());
            // The RGB writers store no channel names; names shown by readers are reader defaults.
            m.put("channel_names", ome ? FixtureSpec.OME_CHANNEL_NAMES : null);
            m.put("dimension_order", ome ? FixtureSpec.OME_DIMENSION_ORDER : null);
            m.put(
                    "ome_uuid",
                    ome ? "urn:uuid:" + FixtureSpec.omeUuid(Path.of(image.path()).getFileName()
                            .toString()) : null);
            Map<String, Object> cal = new LinkedHashMap<>();
            cal.put("in_file", image.calibrationInFile());
            cal.put("pixel_width", image.pixelWidth());
            cal.put("pixel_height", image.pixelHeight());
            cal.put("z_spacing", image.zSpacing());
            cal.put("unit", image.unit());
            cal.put("state", image.calibrationState());
            m.put("calibration", cal);
            m.put("exists_in_output", image.existsInOutput());
            out.add(m);
        }
        return out;
    }

    /**
     * Pixel samples of the RGB fixture, the RGB fixture without resolution tags and every plane of
     * the OME fixture.
     *
     * @return list of {image_key, x, y, c, z, t, value}
     */
    public static List<Map<String, Object>> pixelSamples() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : List.of("rgb", "rgb_nores")) {
            for (int[] p : FixtureSpec.rgbSamplePoints()) {
                int[] rgb = FixtureSpec.rgb(p[0], p[1]);
                for (int c = 0; c < 3; c++) {
                    out.add(sample(key, p[0], p[1], c, 0, 0, rgb[c]));
                }
            }
        }
        for (int index = 0; index < 12; index++) {
            int[] plane = FixtureSpec.omePlane(index);
            for (int[] p : FixtureSpec.omeSamplePoints()) {
                out.add(sample("ome", p[0], p[1], plane[0], plane[1], plane[2],
                        FixtureSpec.omeValue(plane[0], plane[1], plane[2], p[0], p[1])));
            }
        }
        return out;
    }

    private static Map<String, Object> sample(String key, int x, int y, int c, int z, int t,
            int value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("image_key", key);
        m.put("x", x);
        m.put("y", y);
        m.put("c", c);
        m.put("z", z);
        m.put("t", t);
        m.put("value", value);
        return m;
    }

    /**
     * IFD-to-plane table of the TIFF fixtures (chunky RGB has one IFD without channel binding).
     *
     * @return list of {image_key, ifd, c, z, t}
     */
    public static List<Map<String, Object>> planes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : List.of("rgb", "rgb_nores")) {
            out.add(ordered("image_key", key, "ifd", 0, "c", null, "z", 0, "t", 0));
        }
        for (int index = 0; index < 12; index++) {
            int[] p = FixtureSpec.omePlane(index);
            out.add(ordered("image_key", "ome", "ifd", index, "c", p[0], "z", p[1], "t", p[2]));
        }
        return out;
    }

    private static Map<String, Object> mainProject() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "main");
        m.put("project_file", FixtureLayout.PROJECT_FILE);
        m.put("written_as", FixtureLayout.PROJECT_FILE);
        m.put("classes", ProjectSpec.PROJECT_CLASSES);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (EntryDef entry : ProjectSpec.mainEntries()) {
            Map<String, Object> e = entry(entry);
            e.put("image_exists", FixtureLayout.image(entry.imageKey()).existsInOutput());
            entries.add(e);
        }
        m.put("entries", entries);
        return m;
    }

    private static Map<String, Object> movedProject() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "moved");
        m.put("project_file", FixtureLayout.MOVED_PROJECT_FILE);
        m.put("written_as", FixtureLayout.STAGING_PROJECT_FILE);
        m.put("classes", List.of());
        Set<String> existing =
                FixtureLayout.images().stream()
                        .filter(ImageDef::existsInOutput)
                        .map(ImageDef::path)
                        .collect(Collectors.toSet());
        Path previousDir = Path.of(FixtureLayout.STAGING_PROJECT_FILE).getParent();
        Path currentDir = Path.of(FixtureLayout.MOVED_PROJECT_FILE).getParent();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (EntryDef entry : ProjectSpec.movedEntries()) {
            Map<String, Object> e = entry(entry);
            Path stored = Path.of(entry.imagePath());
            Path relative = previousDir.relativize(stored);
            Path resolved = currentDir.resolve(relative).normalize();
            e.put("stored_path_exists", existing.contains(slashes(stored)));
            e.put("relative_to_project_dir", slashes(relative));
            e.put("relative_resolved", slashes(resolved));
            e.put("relative_exists", existing.contains(slashes(resolved)));
            e.put("image_exists", existing.contains(slashes(stored))
                    || existing.contains(slashes(resolved)));
            entries.add(e);
        }
        m.put("entries", entries);
        m.put(
                "qupath_note",
                "source reading of QuPath 0.7.0 UriUpdater.updateReplacementsRelative "
                        + "(UriUpdater.java:386-409): when the previous project file no longer "
                        + "exists, QuPath relativizes against that file path instead of its "
                        + "directory; not executed by the generator");
        return m;
    }

    private static Map<String, Object> entry(EntryDef entry) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("entry_id", entry.entryId());
        e.put("key", entry.key());
        e.put("image_name", entry.imageName());
        e.put("image_name_utf8_hex", ValueCodec.utf8Hex(entry.imageName()));
        e.put("image_path", entry.imagePath());
        e.put("image_key", entry.imageKey());
        e.put("image_type", entry.imageType());
        e.put("metadata", ordered(ProjectSpec.ENTRY_METADATA_KEY, entry.key()));
        e.put("counts", counts(entry.objects()));
        e.put("root_object_id", entry.rootObjectId().toString());
        ImageDef image = FixtureLayout.image(entry.imageKey());
        Map<String, String> ids = new HashMap<>();
        for (ObjectDef o : entry.objects()) {
            ids.put(o.key(), o.objectId().toString());
        }
        List<Map<String, Object>> objects = new ArrayList<>();
        for (ObjectDef o : entry.objects()) {
            objects.add(object(o, ids, image));
        }
        e.put("objects", objects);
        return e;
    }

    /**
     * Object counts as QuPath reports them (cells and tiles count as detections).
     *
     * @param objects objects
     * @return ordered counts
     */
    public static Map<String, Object> counts(List<ObjectDef> objects) {
        long annotations = objects.stream().filter(o -> o.type() == ObjectType.ANNOTATION).count();
        long cells = objects.stream().filter(o -> o.type() == ObjectType.CELL).count();
        long tiles = objects.stream().filter(o -> o.type() == ObjectType.TILE).count();
        return ordered(
                "annotations", annotations,
                "detections_incl_cells_tiles", objects.size() - annotations,
                "cells", cells,
                "tiles", tiles,
                "objects", (long) objects.size());
    }

    private static Map<String, Object> object(
            ObjectDef o, Map<String, String> ids, ImageDef image) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", o.key());
        m.put("object_id", o.objectId().toString());
        m.put("parent_key", o.parentKey());
        m.put("parent_id", o.parentKey() == null ? null : ids.get(o.parentKey()));
        m.put("type", o.type().id());
        m.put("class_path", o.classPath());
        m.put("name", o.name());
        m.put("name_utf8_hex", ValueCodec.utf8Hex(o.name()));
        m.put("locked", o.locked());
        m.put("class_probability", ValueCodec.encode(o.classProbability()));
        m.put("roi", roi(o.roi(), image));
        m.put("nucleus", o.nucleus() == null ? null : roi(o.nucleus(), image));
        m.put("measurement_precision", o.measurementPrecision());
        m.put("measurement_count", o.measurements().size());
        return m;
    }

    private static Map<String, Object> roi(Roi roi, ImageDef image) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", roi.kind().id());
        Plane plane = roi.plane();
        m.put("plane", ordered("c", plane.c(), "z", plane.z(), "t", plane.t()));
        m.put("qupath_c", plane.qupathC());
        m.put("geometry_type", roi.geometryType());
        m.put("storage_precision", roi.kind().storagePrecision());
        switch (roi.kind()) {
            case RECTANGLE, ELLIPSE ->
                    m.put("box", ordered("x", roi.box()[0], "y", roi.box()[1], "width",
                            roi.box()[2], "height", roi.box()[3]));
            case LINE ->
                    m.put("line", ordered("x1", roi.box()[0], "y1", roi.box()[1], "x2",
                            roi.box()[2], "y2", roi.box()[3]));
            case POINTS, POLYGON, POLYLINE -> {
                m.put("points", roi.points());
                m.put("stored_points", roi.storedPoints());
            }
            case GEOMETRY -> {
                m.put("polygons", roi.polygons());
                m.put("polygon_count", roi.polygonCount());
                m.put("holes_per_polygon", roi.holesPerPolygon());
            }
        }
        Double area = roi.areaPx2();
        m.put("area_px2", area);
        boolean calibrated = "known".equals(image.calibrationState());
        m.put(
                "area_calibrated",
                area != null && calibrated ? area * image.pixelWidth() * image.pixelHeight()
                        : null);
        m.put("area_calibrated_unit", area != null && calibrated ? image.unit() + "^2" : null);
        return m;
    }

    /**
     * Long measurement table: for every object with a measurement list, one row per name of the
     * entry's name union; absent names have state {@code missing}.
     *
     * @return rows
     */
    public static List<Map<String, Object>> measurements() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntryDef entry : ProjectSpec.mainEntries()) {
            List<String> names = ProjectSpec.measurementNames(entry);
            ImageDef image = FixtureLayout.image(entry.imageKey());
            for (ObjectDef o : entry.objects()) {
                if (o.measurements().isEmpty()) {
                    continue;
                }
                Map<String, Double> values = new LinkedHashMap<>();
                for (Measurement mm : o.measurements()) {
                    values.put(mm.name(), mm.value());
                }
                for (String name : names) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("project", "main");
                    row.put("entry_key", entry.key());
                    row.put("object_key", o.key());
                    row.put("object_id", o.objectId().toString());
                    row.put("name", name);
                    row.put("name_utf8_hex", ValueCodec.utf8Hex(name));
                    Double input = values.get(name);
                    row.put("storage", input == null ? null : o.measurementPrecision());
                    row.put("input", input == null ? ValueCodec.missing()
                            : ValueCodec.encode(input));
                    double stored = input == null ? Double.NaN
                            : o.type().float32Measurements() ? ValueCodec.float32(input) : input;
                    row.put("stored", input == null ? ValueCodec.missing()
                            : ValueCodec.encode(stored));
                    row.put("dynamic_name_collision", dynamicCollision(name, image));
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static boolean dynamicCollision(String name, ImageDef image) {
        boolean micrometres =
                "known".equals(image.calibrationState()) && "µm".equals(image.unit());
        return micrometres
                ? ProjectSpec.DYNAMIC_COLLISION_UM.equals(name)
                : "absent".equals(image.calibrationInFile())
                        && ProjectSpec.DYNAMIC_COLLISION_PX.equals(name);
    }

    private static Map<String, Object> negativeColon() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", ProjectSpec.NEGATIVE_COLON_KEY);
        m.put("base_class", ProjectSpec.TUMOR);
        m.put("component", ProjectSpec.NEGATIVE_COLON_COMPONENT);
        m.put("qupath_outcome", ProjectSpec.NEGATIVE_COLON_QUPATH_OUTCOME);
        m.put("bridge_outcome", ProjectSpec.NEGATIVE_COLON_BRIDGE_OUTCOME);
        m.put("object_created", false);
        m.put(
                "reason",
                "QuPath 0.7.0 PathClass.getInstance(PathClass, String, Integer) rejects a name "
                        + "containing the delimiter ':' (T00 SP02 finding F-SP02-03)");
        return m;
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }

    /**
     * Insertion-ordered map from alternating keys and values.
     *
     * @param keyValues key, value, key, value, ...
     * @return ordered map
     */
    public static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
