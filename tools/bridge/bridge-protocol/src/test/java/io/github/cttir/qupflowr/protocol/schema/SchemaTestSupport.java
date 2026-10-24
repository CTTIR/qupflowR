/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Locations of the shared schema tree and small helpers for schema tests. */
final class SchemaTestSupport {

    static final String DRAFT = "http://json-schema.org/draft-07/schema#";

    private SchemaTestSupport() {}

    /** inst/schema of the repository, passed by the Gradle build. */
    static Path schemaRoot() {
        String dir = System.getProperty("qupflowr.test.schemaDir");
        assertNotNull(dir, "system property qupflowr.test.schemaDir");
        Path root = Path.of(dir);
        assertTrue(Files.isDirectory(root.resolve("protocol").resolve("v1")), root.toString());
        return root;
    }

    /** Schema sets with a shared fixture corpus: the protocol and the annotatR vocabulary. */
    static final List<String> FIXTURE_SETS = List.of("protocol", "annotatr-control");

    static Path protocolDir() {
        return setDir("protocol");
    }

    static Path setDir(String set) {
        return schemaRoot().resolve(set).resolve("v1");
    }

    static Path fixtureDir(String kind) {
        return fixtureDir("protocol", kind);
    }

    static Path fixtureDir(String set, String kind) {
        return setDir(set).resolve("fixtures").resolve(kind);
    }

    /** Protocol fixture documents of a kind, without sidecars, sorted by name. */
    static List<Path> fixtureFiles(String kind) {
        return fixtureFiles("protocol", kind);
    }

    /** Fixture documents of a set and kind, without sidecars, sorted by name. */
    static List<Path> fixtureFiles(String set, String kind) {
        try (Stream<Path> files = Files.list(fixtureDir(set, kind))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".expect.json"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Schema name of a fixture: the part of the file name before {@code --}. */
    static String schemaOf(Path fixture) {
        String name = fixture.getFileName().toString();
        return name.substring(0, name.indexOf("--"));
    }

    static Path sidecar(Path fixture) {
        String name = fixture.getFileName().toString();
        return fixture.resolveSibling(name.replaceAll("\\.json$", ".expect.json"));
    }

    /**
     * Reads a JSON file with Gson's strict parser, independently of the code under test.
     *
     * @param file JSON file
     * @return parsed value
     */
    static JsonElement gsonRead(Path file) {
        try {
            JsonReader reader =
                    new JsonReader(
                            new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            reader.setStrictness(Strictness.STRICT);
            return JsonParser.parseReader(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A schema document with the Draft-07 {@code $schema} member and the given members. */
    static String schemaText(String... members) {
        return "{\"$schema\":\"" + DRAFT + "\"," + String.join("", members) + "}";
    }

    static Path write(Path dir, String name, String text) {
        try {
            Path file = dir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Errors of a validation as {@code "<path> <keyword>"} in report order. */
    static List<String> check(SchemaValidator validator, String instance, Path schema) {
        return validator.validate(instance, schema).errors().stream()
                .map(e -> e.path() + " " + e.keyword())
                .collect(Collectors.toList());
    }
}
