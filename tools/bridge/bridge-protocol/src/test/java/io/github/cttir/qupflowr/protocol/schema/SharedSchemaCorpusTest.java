/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.FIXTURE_SETS;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.fixtureDir;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.fixtureFiles;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.gsonRead;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.schemaOf;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.schemaRoot;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.setDir;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the fixture corpora shared with the R package directly from {@code
 * inst/schema/<set>/v1/fixtures} for the protocol set and the annotatR capability vocabulary: valid
 * documents validate, invalid documents fail with exactly the errors declared in their {@code
 * .expect.json} sidecars (written from the schema rules before either validator ran).
 */
class SharedSchemaCorpusTest {

    private static final SchemaValidator VALIDATOR = new SchemaValidator(schemaRoot());

    private static Path schemaFile(String set, String name) {
        return VALIDATOR.schemaPath(name, set, "v1");
    }

    /** A fixture file with its schema set. */
    private record Fixture(String set, Path file) {
        String label() {
            return set + "/" + file.getFileName();
        }
    }

    private static Stream<Fixture> fixtures(String kind) {
        return FIXTURE_SETS.stream()
                .flatMap(set -> fixtureFiles(set, kind).stream().map(f -> new Fixture(set, f)));
    }

    @Test
    void corpusHasTheDocumentedSize() {
        assertTrue(fixtureFiles("protocol", "valid").size() >= 30, "valid fixtures");
        assertTrue(fixtureFiles("protocol", "invalid").size() >= 60, "invalid fixtures");
        assertTrue(fixtureFiles("annotatr-control", "valid").size() >= 2, "annotatR valid");
        assertTrue(fixtureFiles("annotatr-control", "invalid").size() >= 3, "annotatR invalid");
    }

    @TestFactory
    Stream<DynamicTest> everyValidFixturePassesItsSchema() {
        return fixtures("valid")
                .map(
                        fixture ->
                                DynamicTest.dynamicTest(
                                        fixture.label(),
                                        () -> {
                                            ValidationReport report =
                                                    VALIDATOR.validate(
                                                            Files.readAllBytes(fixture.file()),
                                                            schemaFile(
                                                                    fixture.set(),
                                                                    schemaOf(fixture.file())));
                                            assertTrue(report.valid(), report.toString());
                                        }));
    }

    @TestFactory
    Stream<DynamicTest> everyInvalidFixtureFailsWithExactlyItsDeclaredErrors() {
        return fixtures("invalid")
                .map(
                        fixture ->
                                DynamicTest.dynamicTest(
                                        fixture.label(), () -> checkInvalidFixture(fixture)));
    }

    private static void checkInvalidFixture(Fixture fixture) throws IOException {
        Path file = fixture.file();
        Path sidecar = sidecar(file);
        assertTrue(Files.isRegularFile(sidecar), "sidecar of " + fixture.label());
        JsonObject expected = gsonRead(sidecar).getAsJsonObject();
        assertEquals(schemaOf(file), expected.get("schema").getAsString());
        assertFalse(expected.get("valid").getAsBoolean());
        assertFalse(expected.get("rule").getAsString().isBlank());
        Set<String> want = new TreeSet<>();
        JsonArray errors = expected.getAsJsonArray("errors");
        assertFalse(errors.isEmpty(), "declared errors");
        for (JsonElement error : errors) {
            JsonObject e = error.getAsJsonObject();
            String key =
                    e.get("path").getAsString()
                            + " | "
                            + e.get("keyword").getAsString()
                            + " | "
                            + e.get("schema_path").getAsString();
            assertTrue(want.add(key), "duplicate declared error " + key);
        }
        ValidationReport report =
                VALIDATOR.validate(
                        Files.readAllBytes(file), schemaFile(fixture.set(), schemaOf(file)));
        Set<String> got =
                report.errors().stream()
                        .map(e -> e.path() + " | " + e.keyword() + " | " + e.schemaPath())
                        .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(want, got, report.toString());
    }

    @TestFactory
    Stream<DynamicTest> eachIndexedSchemaHasAValidAndThreeDistinctInvalidFixtures() {
        return FIXTURE_SETS.stream()
                .map(set -> DynamicTest.dynamicTest(set, () -> checkFixtureCoverage(set)));
    }

    private static void checkFixtureCoverage(String set) {
        JsonObject index = gsonRead(setDir(set).resolve("index.json")).getAsJsonObject();
        Set<String> withFixtures = new TreeSet<>();
        for (JsonElement entry : index.getAsJsonArray("schemas")) {
            JsonObject schema = entry.getAsJsonObject();
            if (schema.get("fixtures").getAsBoolean()) {
                withFixtures.add(schema.get("name").getAsString());
            }
        }
        assertTrue(
                withFixtures.size() >= (set.equals("protocol") ? 15 : 1), withFixtures.toString());
        Set<String> validSchemas = new HashSet<>();
        fixtureFiles(set, "valid").forEach(f -> validSchemas.add(schemaOf(f)));
        Map<String, Set<String>> signatures = new TreeMap<>();
        for (Path file : fixtureFiles(set, "invalid")) {
            List<String> parts = new ArrayList<>();
            for (JsonElement e :
                    gsonRead(sidecar(file)).getAsJsonObject().getAsJsonArray("errors")) {
                JsonObject error = e.getAsJsonObject();
                parts.add(
                        error.get("path").getAsString()
                                + " "
                                + error.get("keyword").getAsString()
                                + " "
                                + error.get("schema_path").getAsString());
            }
            parts.sort(null);
            signatures
                    .computeIfAbsent(schemaOf(file), k -> new HashSet<>())
                    .add(String.join(";", parts));
        }
        for (String schema : withFixtures) {
            assertTrue(validSchemas.contains(schema), set + ": valid fixture for " + schema);
            assertTrue(
                    signatures.getOrDefault(schema, Set.of()).size() >= 3,
                    set + ": three distinct invalid fixtures for " + schema);
        }
        Set<String> used = new TreeSet<>(validSchemas);
        used.addAll(signatures.keySet());
        assertEquals(withFixtures, used);
    }

    /**
     * Valid object fixtures carry base64 WKB for coordinates documented in sidecars; this reader is
     * written for the test only (little-endian ISO WKB, Point and Polygon).
     */
    @Test
    void objectFixturesCarryRealWkbForDocumentedCoordinates() throws IOException {
        int checked = 0;
        List<Path> expectations;
        try (Stream<Path> files = Files.list(fixtureDir("valid"))) {
            expectations =
                    files.filter(p -> p.getFileName().toString().endsWith(".expect.json"))
                            .sorted()
                            .collect(Collectors.toList());
        }
        assertTrue(expectations.size() >= 3);
        for (Path file : expectations) {
            JsonObject spec = gsonRead(file).getAsJsonObject().getAsJsonObject("wkb");
            String instanceName = file.getFileName().toString().replace(".expect.json", ".json");
            JsonElement instance = gsonRead(file.resolveSibling(instanceName));
            for (Map.Entry<String, JsonElement> entry : spec.entrySet()) {
                JsonElement node = instance;
                for (String segment : entry.getKey().substring(1).split("/")) {
                    node = node.getAsJsonObject().get(segment);
                }
                byte[] wkb = Base64.getDecoder().decode(node.getAsString());
                JsonObject want = entry.getValue().getAsJsonObject();
                List<Double> coordinates = new ArrayList<>();
                want.getAsJsonArray("coordinates").forEach(c -> flatten(c, coordinates));
                assertEquals(want.get("type").getAsString() + coordinates, readWkb(wkb));
                checked++;
            }
        }
        assertTrue(checked >= 4, "checked geometries");
    }

    private static void flatten(JsonElement value, List<Double> out) {
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(v -> flatten(v, out));
        } else {
            out.add(value.getAsDouble());
        }
    }

    private static String readWkb(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, in.get(), "little-endian marker");
        int type = in.getInt();
        List<Double> coordinates = new ArrayList<>();
        String name;
        if (type == 1) {
            name = "Point";
            coordinates.add(in.getDouble());
            coordinates.add(in.getDouble());
        } else if (type == 3) {
            name = "Polygon";
            int rings = in.getInt();
            for (int r = 0; r < rings; r++) {
                int points = in.getInt();
                for (int i = 0; i < 2 * points; i++) {
                    coordinates.add(in.getDouble());
                }
            }
        } else {
            name = "unsupported type " + type;
        }
        assertFalse(in.hasRemaining(), "trailing WKB bytes");
        return name + coordinates;
    }
}
