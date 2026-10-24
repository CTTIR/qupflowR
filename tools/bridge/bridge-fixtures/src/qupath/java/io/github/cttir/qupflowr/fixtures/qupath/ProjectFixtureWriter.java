/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures.qupath;

import io.github.cttir.qupflowr.fixtures.ExpectedValues;
import io.github.cttir.qupflowr.fixtures.FixtureModel.EntryDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Measurement;
import io.github.cttir.qupflowr.fixtures.FixtureModel.ObjectDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Plane;
import io.github.cttir.qupflowr.fixtures.FixtureModel.Roi;
import io.github.cttir.qupflowr.fixtures.ProjectSpec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Writes QuPath 0.7.0 projects from {@link ProjectSpec} the way a headless worker does: image
 * servers are initialised first, images are opened with the Bio-Formats server builder chosen
 * explicitly (no automatic reader selection, so no OpenSlide/JNA probing), objects receive their
 * reserved identifiers with {@code PathObject.setID} before insertion, and entries are saved with
 * {@code ProjectImageEntry.saveImageData} and {@code Project.syncChanges}.
 */
final class ProjectFixtureWriter {

    /** Server builder used for every fixture image. */
    static final String SERVER_BUILDER = "BioFormatsServerBuilder";

    private final Path root;
    private final List<String> checks = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    ProjectFixtureWriter(Path root) {
        this.root = root;
    }

    /** Registers QuPath's server builder JSON adapters (required before any project I/O). */
    static void initialiseImageServers() {
        if (ImageServers.getServerBuilderFactory() == null) {
            throw new IllegalStateException("ImageServers.getServerBuilderFactory() returned null");
        }
    }

    List<String> checks() {
        return List.copyOf(checks);
    }

    List<String> failures() {
        return List.copyOf(failures);
    }

    /**
     * Writes one project.
     *
     * @param projectDir project directory relative to the root (created, must be new)
     * @param entries entries in insertion order
     * @param classes project class list (may be empty)
     * @throws IOException on I/O errors
     */
    void writeProject(String projectDir, List<EntryDef> entries, List<List<String>> classes)
            throws IOException {
        Path dir = root.resolve(projectDir);
        if (Files.exists(dir)) {
            throw new IOException("Project directory exists: " + dir);
        }
        Files.createDirectories(dir);
        Project<BufferedImage> project = Projects.createProject(dir.toFile(), BufferedImage.class);
        for (EntryDef entry : entries) {
            writeEntry(project, entry);
        }
        if (!classes.isEmpty()) {
            List<PathClass> pathClasses = new ArrayList<>();
            for (List<String> components : classes) {
                pathClasses.add(PathClass.fromArray(components.toArray(String[]::new)));
            }
            project.setPathClasses(pathClasses);
        }
        project.syncChanges();
        check(projectDir + ".entry_count", entries.size(), project.getImageList().size());
    }

    private void writeEntry(Project<BufferedImage> project, EntryDef entry) throws IOException {
        String prefix = "entry[" + entry.key() + "]";
        Path image = root.resolve(entry.imagePath());
        ImageServer<BufferedImage> server =
                ImageServers.buildServer(image.toUri(), "--classname", SERVER_BUILDER);
        try {
            ProjectImageEntry<BufferedImage> pe = project.addImage(server.getBuilder());
            check(prefix + ".entry_id", entry.entryId(), pe.getID());
            pe.setImageName(entry.imageName());
            pe.getMetadata().put(ProjectSpec.ENTRY_METADATA_KEY, entry.key());
            ImageData<BufferedImage> data =
                    new ImageData<>(
                            server,
                            new PathObjectHierarchy(),
                            ImageType.valueOf(entry.imageType()));
            PathObjectHierarchy hierarchy = data.getHierarchy();
            hierarchy.getRootObject().setID(entry.rootObjectId());
            Map<String, PathObject> byKey = new HashMap<>();
            for (ObjectDef def : entry.objects()) {
                PathObject object = createObject(def);
                PathObject parent = def.parentKey() == null ? null : byKey.get(def.parentKey());
                if (def.parentKey() != null && parent == null) {
                    throw new IllegalStateException("Parent " + def.parentKey() + " of "
                            + def.key() + " is not defined before it");
                }
                if (parent == null) {
                    hierarchy.addObject(object, false);
                } else {
                    hierarchy.addObjectBelowParent(parent, object, false);
                }
                byKey.put(def.key(), object);
            }
            hierarchy.fireHierarchyChangedEvent(hierarchy.getRootObject());
            checkInMemory(prefix, entry, hierarchy);
            pe.saveImageData(data);
        } finally {
            closeQuietly(server);
        }
    }

    private void checkInMemory(String prefix, EntryDef entry, PathObjectHierarchy h) {
        Map<String, Object> expected = ExpectedValues.counts(entry.objects());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("annotations", (long) h.getAnnotationObjects().size());
        observed.put("detections_incl_cells_tiles", (long) h.getDetectionObjects().size());
        observed.put("cells", (long) h.getCellObjects().size());
        observed.put("tiles", (long) h.getTileObjects().size());
        observed.put("objects", (long) h.nObjects());
        check(prefix + ".in_memory_counts", expected, observed);
        TreeSet<String> expectedIds = new TreeSet<>();
        for (ObjectDef def : entry.objects()) {
            expectedIds.add(def.objectId().toString());
        }
        TreeSet<String> observedIds = new TreeSet<>();
        for (PathObject o : h.getAllObjects(false)) {
            observedIds.add(o.getID().toString());
        }
        check(prefix + ".in_memory_object_ids", expectedIds, observedIds);
    }

    private static PathObject createObject(ObjectDef def) {
        PathClass pathClass =
                def.classPath() == null
                        ? null
                        : PathClass.fromArray(def.classPath().toArray(String[]::new));
        ROI roi = toRoi(def.roi());
        PathObject object =
                switch (def.type()) {
                    case ANNOTATION -> PathObjects.createAnnotationObject(roi, pathClass);
                    case DETECTION -> PathObjects.createDetectionObject(roi, pathClass);
                    case TILE -> PathObjects.createTileObject(roi, pathClass, null);
                    case CELL ->
                            PathObjects.createCellObject(
                                    roi,
                                    def.nucleus() == null ? null : toRoi(def.nucleus()),
                                    pathClass,
                                    null);
                };
        object.setID(def.objectId());
        if (def.name() != null) {
            object.setName(def.name());
        }
        if (def.locked()) {
            object.setLocked(true);
        }
        if (!Double.isNaN(def.classProbability())) {
            object.setPathClass(pathClass, def.classProbability());
        }
        MeasurementList ml = object.getMeasurementList();
        for (Measurement m : def.measurements()) {
            ml.put(m.name(), m.value());
        }
        return object;
    }

    static ImagePlane toPlane(Plane plane) {
        return plane.c() == null
                ? ImagePlane.getPlane(plane.z(), plane.t())
                : ImagePlane.getPlaneWithChannel(plane.c(), plane.z(), plane.t());
    }

    static ROI toRoi(Roi roi) {
        ImagePlane plane = toPlane(roi.plane());
        double[] b = roi.box();
        return switch (roi.kind()) {
            case RECTANGLE -> ROIs.createRectangleROI(b[0], b[1], b[2], b[3], plane);
            case ELLIPSE -> ROIs.createEllipseROI(b[0], b[1], b[2], b[3], plane);
            case LINE -> ROIs.createLineROI(b[0], b[1], b[2], b[3], plane);
            case POINTS -> ROIs.createPointsROI(points(roi.points()), plane);
            case POLYGON -> ROIs.createPolygonROI(points(roi.points()), plane);
            case POLYLINE -> ROIs.createPolylineROI(xs(roi.points()), ys(roi.points()), plane);
            case GEOMETRY -> GeometryTools.geometryToROI(geometry(roi.polygons()), plane);
        };
    }

    private static Geometry geometry(double[][][][] polygons) {
        GeometryFactory factory = GeometryTools.getDefaultFactory();
        Polygon[] parts = new Polygon[polygons.length];
        for (int i = 0; i < polygons.length; i++) {
            LinearRing shell = ring(factory, polygons[i][0]);
            LinearRing[] holes = new LinearRing[polygons[i].length - 1];
            for (int r = 1; r < polygons[i].length; r++) {
                holes[r - 1] = ring(factory, polygons[i][r]);
            }
            parts[i] = factory.createPolygon(shell, holes);
        }
        return parts.length == 1 ? parts[0] : factory.createMultiPolygon(parts);
    }

    private static LinearRing ring(GeometryFactory factory, double[][] open) {
        Coordinate[] coords = new Coordinate[open.length + 1];
        for (int i = 0; i < open.length; i++) {
            coords[i] = new Coordinate(open[i][0], open[i][1]);
        }
        coords[open.length] = new Coordinate(open[0][0], open[0][1]);
        return factory.createLinearRing(coords);
    }

    private static List<Point2> points(double[][] xy) {
        List<Point2> list = new ArrayList<>();
        for (double[] p : xy) {
            list.add(new Point2(p[0], p[1]));
        }
        return list;
    }

    private static double[] xs(double[][] xy) {
        double[] out = new double[xy.length];
        for (int i = 0; i < xy.length; i++) {
            out[i] = xy[i][0];
        }
        return out;
    }

    private static double[] ys(double[][] xy) {
        double[] out = new double[xy.length];
        for (int i = 0; i < xy.length; i++) {
            out[i] = xy[i][1];
        }
        return out;
    }

    /**
     * Confirms that QuPath rejects a class component containing ':' (the documented negative
     * case); the specification promises that such a class cannot be created.
     */
    void checkColonComponentRejected() {
        String outcome;
        try {
            PathClass.getInstance(
                    PathClass.getInstance(ProjectSpec.TUMOR), ProjectSpec.NEGATIVE_COLON_COMPONENT,
                    null);
            outcome = "no exception";
        } catch (IllegalArgumentException e) {
            outcome = e.getClass().getName();
        }
        check("negative.class_component_colon", ProjectSpec.NEGATIVE_COLON_QUPATH_OUTCOME, outcome);
    }

    private void check(String name, Object expected, Object observed) {
        checks.add(name);
        if (!expected.equals(observed)) {
            failures.add(name + ": expected " + expected + ", observed " + observed);
        }
    }

    private static void closeQuietly(ImageServer<BufferedImage> server) {
        try {
            server.close();
        } catch (Exception e) {
            // Closing a Bio-Formats reader of a fixture cannot affect the written project.
        }
    }
}
