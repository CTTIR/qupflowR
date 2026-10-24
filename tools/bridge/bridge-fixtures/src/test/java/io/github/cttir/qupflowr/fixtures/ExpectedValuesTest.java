/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.cttir.qupflowr.fixtures.FixtureModel.EntryDef;
import io.github.cttir.qupflowr.fixtures.FixtureModel.ObjectDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Cross-checks the Java specification against the independently written R tables. */
class ExpectedValuesTest {

    private static final JsonObject DOC =
            JsonParser.parseString(JsonWriter.write(ExpectedValues.document())).getAsJsonObject();

    private static String text(JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    @Test
    void documentIsDeterministicAndVersioned() {
        assertEquals(JsonWriter.write(ExpectedValues.document()),
                JsonWriter.write(ExpectedValues.document()));
        assertEquals("1", DOC.get("fixture_set_version").getAsString());
        JsonArray ids = new JsonArray();
        Tables.json("layout.json").getAsJsonObject().getAsJsonArray("formula_ids")
                .forEach(ids::add);
        JsonArray javaIds = new JsonArray();
        DOC.getAsJsonArray("formulas")
                .forEach(f -> javaIds.add(f.getAsJsonObject().get("id")));
        assertEquals(ids, javaIds);
    }

    @Test
    void pixelSamplesEqualTheRTable() {
        List<Map<String, String>> table = Tables.csv("pixel-samples.csv");
        JsonArray samples = DOC.getAsJsonArray("pixel_samples");
        assertEquals(table.size(), samples.size());
        for (int i = 0; i < table.size(); i++) {
            JsonObject s = samples.get(i).getAsJsonObject();
            Map<String, String> row = table.get(i);
            assertEquals(row.get("image_key"), s.get("image_key").getAsString());
            for (String col : List.of("x", "y", "c", "z", "t", "value")) {
                assertEquals(Integer.parseInt(row.get(col)), s.get(col).getAsInt(),
                        "row " + i + " " + col);
            }
        }
    }

    @Test
    void planesEqualTheRTable() {
        List<Map<String, String>> table = Tables.csv("planes.csv");
        JsonArray planes = DOC.getAsJsonArray("planes");
        assertEquals(table.size(), planes.size());
        for (int i = 0; i < table.size(); i++) {
            JsonObject p = planes.get(i).getAsJsonObject();
            for (String col : List.of("image_key", "ifd", "c", "z", "t")) {
                assertEquals(table.get(i).get(col), text(p.get(col)), "row " + i + " " + col);
            }
        }
    }

    @Test
    void objectsEqualTheRTable() {
        List<Map<String, String>> table = Tables.csv("objects.csv");
        List<ObjectDef> objects = new ArrayList<>();
        List<String> projects = new ArrayList<>();
        for (EntryDef e : ProjectSpec.mainEntries()) {
            objects.addAll(e.objects());
            e.objects().forEach(o -> projects.add("main"));
        }
        for (EntryDef e : ProjectSpec.movedEntries()) {
            objects.addAll(e.objects());
            e.objects().forEach(o -> projects.add("moved"));
        }
        assertEquals(table.size(), objects.size());
        for (int i = 0; i < table.size(); i++) {
            Map<String, String> row = table.get(i);
            ObjectDef o = objects.get(i);
            String label = o.key();
            assertEquals(row.get("project"), projects.get(i), label);
            assertEquals(row.get("object_key"), o.key(), label);
            assertEquals(row.get("object_id"), o.objectId().toString(), label);
            assertEquals(row.get("parent_key"), o.parentKey(), label);
            assertEquals(row.get("type"), o.type().id(), label);
            assertEquals(row.get("class_path"),
                    o.classPath() == null ? null : String.join("|", o.classPath()), label);
            assertEquals(row.get("name"), o.name(), label);
            assertEquals(Boolean.parseBoolean(row.get("locked")), o.locked(), label);
            assertEquals(row.get("class_probability_state"),
                    ValueCodec.state(o.classProbability()), label);
            assertEquals(row.get("roi_kind"), o.roi().kind().id(), label);
            assertEquals(row.get("c"),
                    o.roi().plane().c() == null ? null : o.roi().plane().c().toString(), label);
            assertEquals(Integer.parseInt(row.get("z")), o.roi().plane().z(), label);
            assertEquals(Integer.parseInt(row.get("t")), o.roi().plane().t(), label);
            assertEquals(Integer.parseInt(row.get("qupath_c")), o.roi().plane().qupathC(), label);
            assertEquals(row.get("geometry_type"), o.roi().geometryType(), label);
            assertEquals(row.get("storage_precision"), o.roi().kind().storagePrecision(), label);
            assertEquals(row.get("measurement_precision"), o.measurementPrecision(), label);
            assertEquals(Integer.parseInt(row.get("measurement_count")),
                    o.measurements().size(), label);
            Double area = o.roi().areaPx2();
            if (row.get("area_px2") == null) {
                assertNull(area, label);
            } else {
                assertEquals(Double.parseDouble(row.get("area_px2")), area,
                        1e-9 * Math.max(1.0, area), label);
            }
            assertEquals(row.get("nucleus_kind"),
                    o.nucleus() == null ? null : o.nucleus().kind().id(), label);
        }
    }

    @Test
    void storedVerticesEqualTheRTable() {
        JsonArray rois = Tables.json("roi-coordinates.json").getAsJsonArray();
        List<ObjectDef> objects = new ArrayList<>();
        ProjectSpec.mainEntries().forEach(e -> objects.addAll(e.objects()));
        ProjectSpec.movedEntries().forEach(e -> objects.addAll(e.objects()));
        for (int i = 0; i < objects.size(); i++) {
            JsonObject roi = rois.get(i).getAsJsonObject().getAsJsonObject("roi");
            double[][] stored = objects.get(i).roi().storedPoints();
            if (!roi.has("stored_points")) {
                assertNull(stored);
                continue;
            }
            JsonArray expected = roi.getAsJsonArray("stored_points");
            assertEquals(expected.size(), stored.length);
            for (int k = 0; k < stored.length; k++) {
                JsonArray xy = expected.get(k).getAsJsonArray();
                // exact: both sides round the same decimal input to the nearest float32
                assertEquals(xy.get(0).getAsDouble(), stored[k][0], 0.0);
                assertEquals(xy.get(1).getAsDouble(), stored[k][1], 0.0);
            }
        }
    }

    @Test
    void measurementsEqualTheRTable() {
        List<Map<String, String>> table = Tables.csv("measurements.csv");
        JsonArray rows = DOC.getAsJsonArray("measurements");
        assertEquals(table.size(), rows.size());
        for (int i = 0; i < table.size(); i++) {
            Map<String, String> expected = table.get(i);
            JsonObject row = rows.get(i).getAsJsonObject();
            String label = "row " + i;
            assertEquals(expected.get("object_id"), row.get("object_id").getAsString(), label);
            assertEquals(expected.get("name"), row.get("name").getAsString(), label);
            assertEquals(expected.get("storage"), text(row.get("storage")), label);
            JsonObject input = row.getAsJsonObject("input");
            JsonObject stored = row.getAsJsonObject("stored");
            assertEquals(expected.get("input_state"), input.get("value_state").getAsString());
            assertEquals(expected.get("input_bits"), text(input.get("bits")), label);
            assertEquals(expected.get("stored_state"), stored.get("value_state").getAsString());
            assertEquals(expected.get("stored_bits"), text(stored.get("bits")), label);
            assertEquals(Boolean.parseBoolean(expected.get("dynamic_name_collision")),
                    row.get("dynamic_name_collision").getAsBoolean(), label);
        }
    }

    @Test
    void projectEntriesEqualTheLayoutTable() {
        JsonArray projects = Tables.json("layout.json").getAsJsonObject()
                .getAsJsonArray("projects");
        JsonArray javaProjects = DOC.getAsJsonArray("projects");
        assertEquals(projects.size(), javaProjects.size());
        for (int p = 0; p < projects.size(); p++) {
            JsonObject expected = projects.get(p).getAsJsonObject();
            JsonObject actual = javaProjects.get(p).getAsJsonObject();
            for (String key : List.of("key", "project_file", "written_as", "classes")) {
                assertEquals(expected.get(key), actual.get(key), key);
            }
            JsonArray entries = expected.getAsJsonArray("entries");
            JsonArray javaEntries = actual.getAsJsonArray("entries");
            assertEquals(entries.size(), javaEntries.size());
            for (int e = 0; e < entries.size(); e++) {
                JsonObject ee = entries.get(e).getAsJsonObject();
                JsonObject je = javaEntries.get(e).getAsJsonObject();
                for (String key : ee.keySet()) {
                    assertEquals(ee.get(key), je.get(key), ee.get("key") + "." + key);
                }
            }
        }
        JsonObject negative = DOC.getAsJsonArray("negative_cases").get(0).getAsJsonObject();
        assertTrue(negative.get("component").getAsString().contains(":"));
        assertFalse(negative.get("object_created").getAsBoolean());
        assertEquals(Tables.json("layout.json").getAsJsonObject().get("absent_paths"),
                DOC.get("absent_paths"));
    }
}
