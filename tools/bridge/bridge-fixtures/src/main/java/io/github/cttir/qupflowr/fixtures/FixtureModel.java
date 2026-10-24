/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Plain data types of the fixture specification. They describe what the generator creates; they
 * never hold values read back from a file or from QuPath.
 */
public final class FixtureModel {

    private FixtureModel() {}

    /**
     * Image plane of an object.
     *
     * @param c channel, or {@code null} for no single channel (QuPath sentinel -1)
     * @param z zero-based z-slice
     * @param t zero-based timepoint
     */
    public record Plane(Integer c, int z, int t) {

        /** Plane without channel binding at z=0, t=0 (QuPath default plane). */
        public static final Plane DEFAULT = new Plane(null, 0, 0);

        /**
         * Plane without channel binding.
         *
         * @param z z-slice
         * @param t timepoint
         * @return plane with {@code c == null}
         */
        public static Plane of(int z, int t) {
            return new Plane(null, z, t);
        }

        /**
         * QuPath channel value of this plane.
         *
         * @return {@code c}, or -1 for no channel binding
         */
        public int qupathC() {
            return c == null ? -1 : c;
        }
    }

    /** Object types of the data model. */
    public enum ObjectType {
        /** Annotation object. */
        ANNOTATION,
        /** Detection object that is neither a cell nor a tile. */
        DETECTION,
        /** Cell object (a detection with an optional nucleus). */
        CELL,
        /** Tile object (a detection). */
        TILE;

        /**
         * Data-model spelling.
         *
         * @return lower-case type name
         */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Whether QuPath stores measurements of this type as float32.
         *
         * @return true for detections, cells and tiles
         */
        public boolean float32Measurements() {
            return this != ANNOTATION;
        }
    }

    /** Native ROI kinds created by the generator. */
    public enum RoiKind {
        /** RectangleROI, parameters x, y, width, height (double). */
        RECTANGLE,
        /** EllipseROI, parameters x, y, width, height of the bounding box (double). */
        ELLIPSE,
        /** LineROI, parameters x1, y1, x2, y2 (double). */
        LINE,
        /** PointsROI, vertices stored as float32. */
        POINTS,
        /** PolygonROI, vertices stored as float32. */
        POLYGON,
        /** PolylineROI, vertices stored as float32. */
        POLYLINE,
        /** GeometryROI (polygons with holes or several parts), WKB double coordinates. */
        GEOMETRY;

        /**
         * Data-model spelling.
         *
         * @return lower-case kind name
         */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Storage precision of the coordinates.
         *
         * @return {@code "float32"} or {@code "float64"}
         */
        public String storagePrecision() {
            return switch (this) {
                case POINTS, POLYGON, POLYLINE -> "float32";
                default -> "float64";
            };
        }

        /**
         * Whether the ROI is an area.
         *
         * @return true for rectangles, ellipses, polygons and geometries
         */
        public boolean isArea() {
            return this == RECTANGLE || this == ELLIPSE || this == POLYGON || this == GEOMETRY;
        }
    }

    /**
     * ROI definition.
     *
     * @param kind native ROI kind
     * @param plane image plane
     * @param box x, y, width, height (rectangle, ellipse) or x1, y1, x2, y2 (line); else null
     * @param points vertices {x, y} of points, polygons and polylines; else null
     * @param polygons geometry: polygons, each a list of rings (exterior first, not closed); else
     *     null
     */
    public record Roi(
            RoiKind kind, Plane plane, double[] box, double[][] points, double[][][][] polygons) {

        /**
         * Rectangle ROI.
         *
         * @param plane plane
         * @param x left
         * @param y top
         * @param w width
         * @param h height
         * @return ROI definition
         */
        public static Roi rectangle(Plane plane, double x, double y, double w, double h) {
            return new Roi(RoiKind.RECTANGLE, plane, new double[] {x, y, w, h}, null, null);
        }

        /**
         * Ellipse ROI.
         *
         * @param plane plane
         * @param x bounding box left
         * @param y bounding box top
         * @param w bounding box width
         * @param h bounding box height
         * @return ROI definition
         */
        public static Roi ellipse(Plane plane, double x, double y, double w, double h) {
            return new Roi(RoiKind.ELLIPSE, plane, new double[] {x, y, w, h}, null, null);
        }

        /**
         * Line ROI.
         *
         * @param plane plane
         * @param x1 start x
         * @param y1 start y
         * @param x2 end x
         * @param y2 end y
         * @return ROI definition
         */
        public static Roi line(Plane plane, double x1, double y1, double x2, double y2) {
            return new Roi(RoiKind.LINE, plane, new double[] {x1, y1, x2, y2}, null, null);
        }

        /**
         * Vertex ROI (points, polygon or polyline).
         *
         * @param kind POINTS, POLYGON or POLYLINE
         * @param plane plane
         * @param points vertices {x, y}
         * @return ROI definition
         */
        public static Roi vertices(RoiKind kind, Plane plane, double[]... points) {
            if (kind != RoiKind.POINTS && kind != RoiKind.POLYGON && kind != RoiKind.POLYLINE) {
                throw new IllegalArgumentException("Not a vertex ROI kind: " + kind);
            }
            return new Roi(kind, plane, null, points, null);
        }

        /**
         * Geometry ROI.
         *
         * @param plane plane
         * @param polygons polygons, each a list of rings with the exterior first
         * @return ROI definition
         */
        public static Roi geometry(Plane plane, double[][][]... polygons) {
            return new Roi(RoiKind.GEOMETRY, plane, null, null, polygons);
        }

        /**
         * Geometry type of the WKB representation.
         *
         * @return {@code Polygon}, {@code MultiPolygon}, {@code MultiPoint} or {@code LineString}
         */
        public String geometryType() {
            return switch (kind) {
                case RECTANGLE, ELLIPSE, POLYGON -> "Polygon";
                case GEOMETRY -> polygons.length == 1 ? "Polygon" : "MultiPolygon";
                case POINTS -> "MultiPoint";
                case LINE, POLYLINE -> "LineString";
            };
        }

        /**
         * Area in square pixels, computed from the definition (formula {@code area.px2.v1}).
         *
         * @return area, or {@code null} for non-area ROIs
         */
        public Double areaPx2() {
            return switch (kind) {
                case RECTANGLE -> box[2] * box[3];
                case ELLIPSE -> Math.PI * box[2] * box[3] / 4.0;
                case POLYGON -> Math.abs(signedArea(points));
                case GEOMETRY -> {
                    double sum = 0;
                    for (double[][][] polygon : polygons) {
                        sum += Math.abs(signedArea(polygon[0]));
                        for (int r = 1; r < polygon.length; r++) {
                            sum -= Math.abs(signedArea(polygon[r]));
                        }
                    }
                    yield sum;
                }
                default -> null;
            };
        }

        /**
         * Vertices after float32 storage (formula {@code storage.float32.v1}).
         *
         * @return narrowed copies of {@link #points()}, or null
         */
        public double[][] storedPoints() {
            if (points == null) {
                return null;
            }
            double[][] out = new double[points.length][];
            for (int i = 0; i < points.length; i++) {
                out[i] = new double[] {(float) points[i][0], (float) points[i][1]};
            }
            return out;
        }

        /**
         * Number of polygon parts of a geometry.
         *
         * @return number of polygons, or 0 for other kinds
         */
        public int polygonCount() {
            return polygons == null ? 0 : polygons.length;
        }

        /**
         * Number of interior rings per polygon of a geometry.
         *
         * @return holes per polygon, empty for other kinds
         */
        public int[] holesPerPolygon() {
            if (polygons == null) {
                return new int[0];
            }
            int[] out = new int[polygons.length];
            for (int i = 0; i < polygons.length; i++) {
                out[i] = polygons[i].length - 1;
            }
            return out;
        }

        private static double signedArea(double[][] ring) {
            double twice = 0;
            for (int i = 0; i < ring.length; i++) {
                double[] a = ring[i];
                double[] b = ring[(i + 1) % ring.length];
                twice += a[0] * b[1] - b[0] * a[1];
            }
            return twice / 2.0;
        }
    }

    /**
     * One stored measurement input.
     *
     * @param name byte-exact measurement name (may be empty)
     * @param value input value, including NaN and infinities
     */
    public record Measurement(String name, double value) {

        /**
         * Canonical constructor.
         *
         * @param name name
         * @param value value
         */
        public Measurement {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * One object of a fixture image.
     *
     * @param entryKey key of the project entry
     * @param key object key, unique within the entry
     * @param parentKey key of the parent, or {@code null} for the root
     * @param type object type
     * @param roi ROI
     * @param nucleus nucleus ROI of a cell, or {@code null}
     * @param classPath class components, or {@code null} for unclassified
     * @param name object name, or {@code null}
     * @param locked lock flag
     * @param classProbability class probability, NaN when not set
     * @param measurements stored measurement inputs in insertion order
     */
    public record ObjectDef(
            String entryKey,
            String key,
            String parentKey,
            ObjectType type,
            Roi roi,
            Roi nucleus,
            List<String> classPath,
            String name,
            boolean locked,
            double classProbability,
            List<Measurement> measurements) {

        /**
         * Object identifier (formula {@code object_id.name_uuid_v3.v1}).
         *
         * @return name-based UUID
         */
        public UUID objectId() {
            return FixtureSpec.nameUuid(entryKey + ":" + key);
        }

        /**
         * Storage precision of the measurement list.
         *
         * @return {@code "float32"} or {@code "float64"}
         */
        public String measurementPrecision() {
            return type.float32Measurements() ? "float32" : "float64";
        }
    }

    /**
     * One image entry of a QuPath project fixture.
     *
     * @param entryId QuPath entry ID (assigned in insertion order starting at 1)
     * @param key entry key
     * @param imageName image name shown in QuPath
     * @param imagePath image path relative to the output root, as written into the project
     * @param imageType QuPath image type name
     * @param imageKey key of the image definition in {@link FixtureLayout}
     * @param objects objects of the entry, parents before children
     */
    public record EntryDef(
            String entryId,
            String key,
            String imageName,
            String imagePath,
            String imageType,
            String imageKey,
            List<ObjectDef> objects) {

        /**
         * Identifier of the hierarchy root object. QuPath draws a random UUID for every root
         * object and serialises it into {@code data.qpdata}; the generator sets this name-based
         * UUID instead so that the file is reproducible. The root is not a data-model object.
         *
         * @return UUID of {@code ID_NAMESPACE + ":" + key + ":root"}
         */
        public UUID rootObjectId() {
            return FixtureSpec.nameUuid(key + ":root");
        }
    }

    /**
     * Image file definition.
     *
     * @param key image key
     * @param path path relative to the output root
     * @param writer writer description
     * @param formulaId pixel formula id
     * @param width width in pixels
     * @param height height in pixels
     * @param sizeC channels
     * @param sizeZ z-slices
     * @param sizeT timepoints
     * @param pixelType {@code uint8} or {@code uint16}
     * @param calibrationInFile where calibration is recorded in the file
     * @param pixelWidth pixel width in {@code unit}, or null when unknown
     * @param pixelHeight pixel height in {@code unit}, or null when unknown
     * @param zSpacing z spacing in {@code unit}, or null when unknown or single plane
     * @param unit calibration unit, or null when unknown
     * @param calibrationState {@code known} or {@code unknown}
     * @param existsInOutput whether the file remains in the output
     */
    public record ImageDef(
            String key,
            String path,
            String writer,
            String formulaId,
            int width,
            int height,
            int sizeC,
            int sizeZ,
            int sizeT,
            String pixelType,
            String calibrationInFile,
            Double pixelWidth,
            Double pixelHeight,
            Double zSpacing,
            String unit,
            String calibrationState,
            boolean existsInOutput) {}
}
