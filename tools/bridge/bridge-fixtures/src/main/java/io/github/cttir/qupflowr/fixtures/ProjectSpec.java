/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import io.github.cttir.qupflowr.fixtures.FixtureModel.EntryDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Measurement;
import io.github.cttir.qupflowr.fixtures.FixtureModel.ObjectDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.ObjectType;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Plane;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Roi;
import io.github.cttir.qupflowr.fixtures.FixtureModel.RoiKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * QuPath project fixtures: entries, objects, classes, measurement inputs and the documented
 * negative case. Hand constants only; derived values (identifiers, areas, float32 storage) come
 * from the formulas in {@link FixtureModel} and {@link FixtureSpec}.
 *
 * <pre>
 * entry 4 "ome" (anisotropic 0.25 x 0.5 µm, 3c/2z/2t)
 * root
 * +-- ann_polygon_hole       annotation geometry (polygon, 1 hole)  c=-  z=1 t=1  Tümor, locked
 * |   +-- cell_with_nucleus  cell polygon + ellipse nucleus         c=-  z=1 t=1  Tümor: Positive
 * +-- ann_multipolygon       annotation geometry (2 polygons)       c=-  z=0 t=0  Tümor: 2+
 * |   +-- detection_three_plus  detection polygon                   c=-  z=0 t=0  Tümor: 3+
 * +-- ann_rectangle          annotation rectangle                   c=-  z=1 t=0  unclassified
 * |   +-- ann_ellipse        annotation ellipse                     c=2  z=1 t=0  Negative
 * |   |   +-- detection      detection polygon                      c=-  z=1 t=0  Tümor: Positive
 * |   +-- tile               tile rectangle                         c=-  z=1 t=0  unclassified
 * +-- ann_points             annotation points (3)                  c=-  z=0 t=1  Tümor
 * +-- ann_line               annotation line                        c=-  z=1 t=1  unclassified
 * +-- ann_polyline           annotation polyline                    c=-  z=0 t=0  unclassified
 * +-- cell_without_nucleus   cell polygon, no nucleus               c=-  z=0 t=1  Negative
 * </pre>
 */
public final class ProjectSpec {

    /** Base class with a non-ASCII character (U+00FC, NFC). */
    public static final String TUMOR = "Tümor";

    /** QuPath's negative class name. */
    public static final String NEGATIVE = "Negative";

    /** Entry metadata key naming the fixture entry. */
    public static final String ENTRY_METADATA_KEY = "qupflowr.fixture";

    /** Measurement name with a unit that does not collide with a dynamic name. */
    public static final String NAME_WITH_UNIT = "Area µm²";

    /**
     * Stored measurement whose name equals QuPath's dynamic ROI area name in a µm-calibrated image
     * ({@code "Area " + GeneralTools.micrometerSymbol() + "^2"}, ROIAreaValue.java:21).
     */
    public static final String DYNAMIC_COLLISION_UM = "Area µm^2";

    /** Stored measurement equal to the dynamic ROI area name of an image calibrated in pixels. */
    public static final String DYNAMIC_COLLISION_PX = "Area px^2";

    /** The nine special-value measurement inputs. */
    public static final List<Measurement> SPECIAL_VALUES =
            List.of(
                    new Measurement("zero", 0.0),
                    new Measurement("negative", -12.5),
                    new Measurement("nan", Double.NaN),
                    new Measurement("pos_inf", Double.POSITIVE_INFINITY),
                    new Measurement("neg_inf", Double.NEGATIVE_INFINITY),
                    new Measurement(NAME_WITH_UNIT, 1234.5678901234567),
                    new Measurement("tiny double", Double.MIN_VALUE),
                    new Measurement("big double", 1e300),
                    new Measurement("", 7.0));

    /** Project class list written to classifiers/classes.json of the main project. */
    public static final List<List<String>> PROJECT_CLASSES =
            List.of(
                    List.of(TUMOR),
                    List.of(TUMOR, "Positive"),
                    List.of(TUMOR, "2+"),
                    List.of(TUMOR, "3+"),
                    List.of(NEGATIVE));

    /** Negative case key: a class component containing the QuPath delimiter. */
    public static final String NEGATIVE_COLON_KEY = "class_component_colon";

    /** Class component of the negative case. */
    public static final String NEGATIVE_COLON_COMPONENT = "Positive: strong";

    /** Exception QuPath 0.7.0 throws for the negative case. */
    public static final String NEGATIVE_COLON_QUPATH_OUTCOME = "java.lang.IllegalArgumentException";

    /** Error code the bridge must return for the negative case. */
    public static final String NEGATIVE_COLON_BRIDGE_OUTCOME = "VALIDATION_FAILED";

    private ProjectSpec() {}

    /**
     * Entries of the main project in insertion order.
     *
     * @return entries 1 to 5
     */
    public static List<EntryDef> mainEntries() {
        return List.of(
                new EntryDef(
                        "1", "rgb", "RGB Fixture ä", FixtureLayout.RGB, "BRIGHTFIELD_H_E",
                        "rgb", List.of(obj("rgb", "rgb_marker", null, ObjectType.ANNOTATION,
                                Roi.rectangle(Plane.DEFAULT, 10, 10, 50, 50), null,
                                List.of(TUMOR), null, false, Double.NaN, List.of()))),
                new EntryDef(
                        "2", "rgb_umlaut_dir", "RGB aus Ordner mit Ümlaut",
                        FixtureLayout.RGB_UMLAUT_COPY, "BRIGHTFIELD_H_E", "rgb_umlaut_dir",
                        List.of()),
                new EntryDef(
                        "3", "rgb_nores", "RGB ohne Kalibrierung", FixtureLayout.RGB_NORES,
                        "BRIGHTFIELD_H_E", "rgb_nores",
                        List.of(obj("rgb_nores", "nores_rectangle", null, ObjectType.ANNOTATION,
                                Roi.rectangle(Plane.DEFAULT, 100, 50, 64, 32), null, null, null,
                                false, Double.NaN,
                                List.of(new Measurement(DYNAMIC_COLLISION_PX, -1.0))))),
                new EntryDef(
                        "4", "ome", "OME 3c2z2t µ", FixtureLayout.OME, "FLUORESCENCE", "ome",
                        omeObjects()),
                new EntryDef(
                        "5", "missing_image", "Fehlendes Bild", FixtureLayout.DELETED_IMAGE,
                        "BRIGHTFIELD_H_E", "deleted",
                        List.of(obj("missing_image", "missing_marker", null,
                                ObjectType.ANNOTATION,
                                Roi.ellipse(Plane.DEFAULT, 20, 20, 30, 30), null,
                                List.of(NEGATIVE), null, false, Double.NaN, List.of()))));
    }

    /**
     * Entries of the moved project, with image paths as written before the move.
     *
     * @return entries 1 and 2
     */
    public static List<EntryDef> movedEntries() {
        return List.of(
                new EntryDef(
                        "1", "moved_rgb_inside", "RGB mit relativem Pfad",
                        FixtureLayout.STAGING_RGB, "BRIGHTFIELD_H_E", "moved_rgb",
                        List.of(obj("moved_rgb_inside", "moved_marker", null,
                                ObjectType.ANNOTATION,
                                Roi.rectangle(Plane.DEFAULT, 5, 5, 20, 20), null, null, null,
                                false, Double.NaN, List.of()))),
                new EntryDef(
                        "2", "moved_ome_outside", "OME mit absolutem Pfad", FixtureLayout.OME,
                        "FLUORESCENCE", "ome", List.of()));
    }

    /**
     * Objects of the OME entry, parents before children.
     *
     * @return twelve objects
     */
    public static List<ObjectDef> omeObjects() {
        String e = "ome";
        Plane z1t1 = Plane.of(1, 1);
        Plane z1t0 = Plane.of(1, 0);
        List<Measurement> special = SPECIAL_VALUES;
        List<Measurement> specialAndCollision = new ArrayList<>(SPECIAL_VALUES);
        specialAndCollision.add(new Measurement(DYNAMIC_COLLISION_UM, -1.0));
        double[][] exterior = {{20, 20}, {120, 20}, {120, 100}, {20, 100}};
        double[][] hole = {{50, 40}, {70, 40}, {70, 60}, {50, 60}};
        double[][] partA = {{140, 10}, {150, 10}, {150, 20}, {140, 20}};
        double[][] partB = {{160, 30}, {180, 30}, {180, 35}, {160, 35}};
        return List.of(
                obj(e, "ann_polygon_hole", null, ObjectType.ANNOTATION,
                        Roi.geometry(z1t1, new double[][][] {exterior, hole}), null,
                        List.of(TUMOR), "Region mit Loch ä", true, Double.NaN,
                        List.copyOf(specialAndCollision)),
                obj(e, "cell_with_nucleus", "ann_polygon_hole", ObjectType.CELL,
                        Roi.vertices(RoiKind.POLYGON, z1t1,
                                new double[][] {{30, 30}, {45.3, 28}, {48, 44}, {32, 46}}),
                        Roi.ellipse(z1t1, 35.1, 33, 8, 8),
                        List.of(TUMOR, "Positive"), null, false, 0.73, special),
                obj(e, "ann_multipolygon", null, ObjectType.ANNOTATION,
                        Roi.geometry(Plane.DEFAULT, new double[][][] {partA},
                                new double[][][] {partB}),
                        null, List.of(TUMOR, "2+"), null, false, Double.NaN, special),
                obj(e, "detection_three_plus", "ann_multipolygon", ObjectType.DETECTION,
                        Roi.vertices(RoiKind.POLYGON, Plane.DEFAULT,
                                new double[][] {{142, 12}, {148, 12}, {145, 18}}),
                        null, List.of(TUMOR, "3+"), null, false, Double.NaN,
                        List.of(new Measurement("nan", Double.NaN))),
                obj(e, "ann_rectangle", null, ObjectType.ANNOTATION,
                        Roi.rectangle(z1t0, 150.1, 100, 80, 60), null, null, null, false,
                        Double.NaN, List.of()),
                obj(e, "ann_ellipse", "ann_rectangle", ObjectType.ANNOTATION,
                        Roi.ellipse(new Plane(2, 1, 0), 160.1, 110, 40, 30), null,
                        List.of(NEGATIVE), null, false, Double.NaN, List.of()),
                obj(e, "detection", "ann_ellipse", ObjectType.DETECTION,
                        Roi.vertices(RoiKind.POLYGON, z1t0,
                                new double[][] {{165, 115}, {175, 115}, {170, 125}}),
                        null, List.of(TUMOR, "Positive"), null, false, Double.NaN, special),
                obj(e, "tile", "ann_rectangle", ObjectType.TILE,
                        Roi.rectangle(z1t0, 200, 140, 16, 16), null, null, null, false,
                        Double.NaN,
                        List.of(new Measurement("zero", 0.0), new Measurement("negative", -12.5))),
                obj(e, "ann_points", null, ObjectType.ANNOTATION,
                        Roi.vertices(RoiKind.POINTS, Plane.of(0, 1),
                                new double[][] {{10, 180}, {12.7, 182}, {250, 5}}),
                        null, List.of(TUMOR), null, false, Double.NaN, List.of()),
                obj(e, "ann_line", null, ObjectType.ANNOTATION,
                        Roi.line(z1t1, 5, 5, 100.3, 150), null, null, null, false, Double.NaN,
                        List.of()),
                obj(e, "ann_polyline", null, ObjectType.ANNOTATION,
                        Roi.vertices(RoiKind.POLYLINE, Plane.DEFAULT,
                                new double[][] {{5, 150}, {50, 160}, {90, 140}}),
                        null, null, null, false, Double.NaN, List.of()),
                obj(e, "cell_without_nucleus", null, ObjectType.CELL,
                        Roi.vertices(RoiKind.POLYGON, Plane.of(0, 1),
                                new double[][] {{220, 20}, {240, 22}, {238, 40}, {222, 38}}),
                        null, List.of(NEGATIVE), null, false, Double.NaN, List.of()));
    }

    /**
     * Union of measurement names over the objects of an entry, in first-seen order.
     *
     * @param entry entry
     * @return names
     */
    public static List<String> measurementNames(EntryDef entry) {
        Set<String> names = new LinkedHashSet<>();
        for (ObjectDef o : entry.objects()) {
            for (Measurement m : o.measurements()) {
                names.add(m.name());
            }
        }
        return List.copyOf(names);
    }

    private static ObjectDef obj(
            String entryKey,
            String key,
            String parentKey,
            ObjectType type,
            Roi roi,
            Roi nucleus,
            List<String> classPath,
            String name,
            boolean locked,
            double probability,
            List<Measurement> measurements) {
        return new ObjectDef(entryKey, key, parentKey, type, roi, nucleus, classPath, name, locked,
                probability, measurements);
    }
}
