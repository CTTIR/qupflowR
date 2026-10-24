/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.DRAFT;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.gsonRead;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.protocolDir;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.schemaRoot;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.cttir.qupflowr.protocol.json.CanonicalJsonReader;
import io.github.cttir.qupflowr.protocol.schema.SchemaValidator.Resolved;
import io.github.cttir.qupflowr.protocol.schema.SchemaValidator.SchemaRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Meta checks of the shared schemas and of the rules that refuse unsupported schemas. */
class SchemaMetaTest {

    /**
     * Keyword list typed from BLUEPRINT section 3 (decision D022), not taken from the validator.
     */
    private static final Set<String> BLUEPRINT_KEYWORDS =
            Set.of(
                    "$schema",
                    "$id",
                    "$ref",
                    "definitions",
                    "title",
                    "description",
                    "type",
                    "properties",
                    "required",
                    "additionalProperties",
                    "items",
                    "minItems",
                    "maxItems",
                    "uniqueItems",
                    "enum",
                    "const",
                    "minimum",
                    "maximum",
                    "exclusiveMinimum",
                    "exclusiveMaximum",
                    "minLength",
                    "maxLength",
                    "pattern",
                    "format",
                    "oneOf",
                    "anyOf",
                    "allOf",
                    "not");

    private static final Set<String> BLUEPRINT_FORMATS =
            Set.of("uuid", "date-time", "sha256", "uri-reference");

    private static List<Path> allSchemaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(schemaRoot())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".schema.json"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /** Keywords, references and formats at schema positions, independent of the validator. */
    private record Walked(List<String> keywords, List<String> refs, List<String> formats) {}

    private static Walked walk(JsonElement node) {
        Walked out = new Walked(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        walkInto(node, out);
        return out;
    }

    private static void walkInto(JsonElement node, Walked out) {
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject schema = node.getAsJsonObject();
        out.keywords().addAll(schema.keySet());
        if (schema.has("$ref")) {
            out.refs().add(schema.get("$ref").getAsString());
        }
        if (schema.has("format")) {
            out.formats().add(schema.get("format").getAsString());
        }
        for (String container : List.of("definitions", "properties")) {
            if (schema.has(container)) {
                for (Map.Entry<String, JsonElement> e :
                        schema.getAsJsonObject(container).entrySet()) {
                    walkInto(e.getValue(), out);
                }
            }
        }
        for (String single : List.of("additionalProperties", "items", "not")) {
            if (schema.has(single)) {
                walkInto(schema.get(single), out);
            }
        }
        for (String combinator : List.of("oneOf", "anyOf", "allOf")) {
            if (schema.has(combinator)) {
                schema.getAsJsonArray(combinator).forEach(branch -> walkInto(branch, out));
            }
        }
    }

    @Test
    void validatorSupportsExactlyTheBlueprintKeywordsAndFormats() {
        assertEquals(BLUEPRINT_KEYWORDS, Set.copyOf(SchemaValidator.supportedKeywords()));
        assertEquals(BLUEPRINT_KEYWORDS.size(), SchemaValidator.supportedKeywords().size());
        assertEquals(BLUEPRINT_FORMATS, Set.copyOf(SchemaValidator.supportedFormats()));
    }

    @Test
    void everySchemaUsesOnlySupportedKeywordsAndFormats() throws IOException {
        List<Path> files = allSchemaFiles();
        assertTrue(files.size() >= 17, files.toString());
        SchemaValidator validator = new SchemaValidator(schemaRoot());
        for (Path file : files) {
            String name = file.getFileName().toString();
            Walked walked = walk(gsonRead(file));
            Set<String> unsupported = new TreeSet<>(walked.keywords());
            unsupported.removeAll(BLUEPRINT_KEYWORDS);
            assertEquals(Set.of(), unsupported, name);
            assertTrue(BLUEPRINT_FORMATS.containsAll(walked.formats()), name);
            assertEquals(List.of(), validator.lint(file), name);
        }
    }

    @Test
    void everyReferenceResolvesInsideTheSchemaRoot() throws IOException {
        SchemaValidator validator = new SchemaValidator(schemaRoot());
        String root = validator.root().toString();
        int resolved = 0;
        for (Path file : allSchemaFiles()) {
            Path real = file.toRealPath();
            for (String ref : new LinkedHashSet<>(walk(gsonRead(file)).refs())) {
                assertFalse(ref.matches("^[a-z]+:.*"), ref);
                Resolved target = validator.resolve(ref, new SchemaRef(real, ""), root);
                assertTrue(target.file().toString().startsWith(root + "/"), ref);
                resolved++;
            }
        }
        assertTrue(resolved > 50, "resolved references: " + resolved);
    }

    @Test
    void schemasAreDraft07DocumentsMatchingTheIndex() throws IOException {
        JsonObject index = gsonRead(protocolDir().resolve("index.json")).getAsJsonObject();
        assertEquals("1.0", index.get("protocol_version").getAsString());
        assertEquals(DRAFT, index.get("json_schema_draft").getAsString());
        Set<String> listed = new TreeSet<>();
        Set<String> names = new TreeSet<>();
        for (JsonElement e : index.getAsJsonArray("schemas")) {
            JsonObject entry = e.getAsJsonObject();
            String file = entry.get("file").getAsString();
            listed.add(file);
            names.add(entry.get("name").getAsString());
            JsonObject schema = gsonRead(protocolDir().resolve(file)).getAsJsonObject();
            assertEquals(DRAFT, schema.get("$schema").getAsString(), file);
            assertEquals(entry.get("id").getAsString(), schema.get("$id").getAsString(), file);
            assertTrue(entry.get("id").getAsString().endsWith("/protocol/v1/" + file), file);
            assertFalse(schema.get("title").getAsString().isBlank(), file);
            assertFalse(schema.get("description").getAsString().isBlank(), file);
        }
        Set<String> present;
        try (Stream<Path> files = Files.list(protocolDir())) {
            present =
                    files.map(p -> p.getFileName().toString())
                            .filter(n -> n.endsWith(".schema.json"))
                            .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(present, listed);
        assertEquals(
                new TreeSet<>(
                        List.of(
                                "common",
                                "envelope-success",
                                "envelope-error",
                                "error",
                                "health",
                                "capabilities",
                                "discovery-manifest",
                                "session",
                                "image-entry",
                                "image-info",
                                "object-record",
                                "measurement-record",
                                "patch-request",
                                "patch",
                                "event",
                                "job",
                                "error-codes")),
                names);
    }

    private static List<String> lint(String... parts) {
        return SchemaLint.lint(CanonicalJsonReader.read(String.join("", parts))).stream()
                .map(SchemaIssue::problem)
                .collect(Collectors.toList());
    }

    @Test
    void lintRejectsWhatTheValidatorDoesNotSupport() {
        String head = "{\"$schema\":\"" + DRAFT + "\"";
        assertEquals(List.of(), lint(head, "}"));
        assertEquals(List.of("unsupported_keyword"), lint(head, ",\"patternProperties\":{}}"));
        assertEquals(
                List.of("unsupported_keyword", "unsupported_keyword"),
                lint(head, ",\"if\":{},\"then\":{}}"));
        assertEquals(
                List.of("root_only_keyword"),
                lint(head, ",\"properties\":{\"a\":{\"$schema\":\"x\"}}}"));
        assertEquals(List.of("invalid_required"), lint(head, ",\"required\":[\"a\",\"a\"]}"));
        assertEquals(List.of("not_a_count"), lint(head, ",\"minLength\":-1}"));
        assertEquals(List.of("invalid_enum"), lint(head, ",\"enum\":[]}"));
        assertEquals(List.of("branches_not_array"), lint(head, ",\"oneOf\":[]}"));
        assertEquals(List.of("pattern_not_portable"), lint(head, ",\"pattern\":\"a++\"}"));
        assertEquals(List.of(), lint(head, ",\"pattern\":\"^(?:a|b)[.]$\"}"));
        assertEquals(List.of("not_a_boolean"), lint(head, ",\"uniqueItems\":\"yes\"}"));
        assertEquals(List.of("schema_not_object"), lint("[]"));
    }

    @Test
    void lintChecksKeywordValueTypes() {
        String head = "{\"$schema\":\"" + DRAFT + "\"";
        assertEquals(List.of("members_not_object"), lint(head, ",\"properties\":[]}"));
        assertEquals(List.of("ref_not_string"), lint(head, ",\"$ref\":1}"));
        assertEquals(List.of("not_a_number"), lint(head, ",\"minimum\":\"1\"}"));
        assertEquals(List.of("not_a_string"), lint(head, ",\"title\":1}"));
        assertEquals(List.of("not_a_string"), lint(head, ",\"pattern\":1}"));
        assertEquals(List.of("members_not_object"), lint(head, ",\"definitions\":null}"));
        assertEquals(List.of("schema_not_object"), lint(head, ",\"not\":1}"));
        assertEquals(List.of("schema_not_object"), lint(head, ",\"additionalProperties\":[]}"));
        assertEquals(List.of("schema_not_object"), lint(head, ",\"items\":[]}"));
        assertEquals(List.of("invalid_type"), lint(head, ",\"type\":[]}"));
        assertEquals(List.of("invalid_type"), lint(head, ",\"type\":[\"string\",\"string\"]}"));
        assertEquals(List.of(), lint(head, ",\"type\":[\"string\",\"null\"],\"required\":[]}"));
        assertEquals(List.of("invalid_required"), lint(head, ",\"required\":{}}"));
        assertEquals(List.of("invalid_enum"), lint(head, ",\"enum\":{\"a\":1}}"));
        assertEquals(List.of("unsupported_format"), lint(head, ",\"format\":1}"));
        assertEquals(List.of("not_a_count"), lint(head, ",\"maxItems\":1.5}"));
        assertEquals(List.of(), lint(head, ",\"maxItems\":2.0}"));
        assertEquals(List.of("not_a_boolean"), lint(head, ",\"uniqueItems\":null}"));
        assertEquals(List.of("draft07_required"), lint("{\"$schema\":[\"" + DRAFT + "\"]}"));
        assertEquals(List.of(), lint(head, ",\"$ref\":\"#\",\"title\":\"t\",\"definitions\":{}}"));
        assertEquals(
                List.of("unsupported_keyword", "ref_with_siblings"),
                lint(head, ",\"$ref\":\"#\",\"$comment\":\"c\"}"));
    }

    @Test
    void lintReportsIssuesInTheOrderOfTheRValidator() {
        String head = "{\"$schema\":\"" + DRAFT + "\"";
        List<SchemaIssue> issues =
                SchemaLint.lint(
                        CanonicalJsonReader.read(
                                head
                                        + ",\"anyOf\":[{\"x\":1}],\"items\":{\"minItems\":-1},"
                                        + "\"properties\":{\"a/b\":{\"type\":\"float\"}},"
                                        + "\"maxLength\":\"2\",\"q\":0}"));
        assertEquals(
                List.of(
                        new SchemaIssue("", "q", "unsupported_keyword"),
                        new SchemaIssue("", "maxLength", "not_a_count"),
                        new SchemaIssue("/properties/a~1b", "type", "invalid_type"),
                        new SchemaIssue("/items", "minItems", "not_a_count"),
                        new SchemaIssue("/anyOf/0", "x", "unsupported_keyword")),
                issues);
    }

    @Test
    void patternsMustBePortable() {
        String head = "{\"$schema\":\"" + DRAFT + "\",\"pattern\":";
        for (String portable :
                List.of(
                        "^$",
                        "^",
                        "\\\\x41",
                        "a\\\\.b",
                        "[.]",
                        "\\\\\\\\d",
                        "^a[]]b$",
                        "^a[^]]b$",
                        "a{2}",
                        "a{1,}",
                        "[{]",
                        "[^]{]",
                        "a\\\\{",
                        "a&&b")) {
            assertEquals(List.of(), lint(head, "\"" + portable + "\"}"), portable);
        }
        for (String notPortable :
                List.of(
                        "a{2}+",
                        "$",
                        "a^",
                        "(?i)a",
                        "\\\\\\\\\\\\d",
                        "\\\\bword",
                        "(?<n>a)",
                        "(a)\\\\1",
                        "\\\\12",
                        // Checked before compiling: PCRE and java.util.regex disagree on these.
                        "[[:alpha:]]",
                        "^[a&&b]$",
                        "^[a[b]]$",
                        "[]a[]",
                        "\\\\0",
                        "\\\\N",
                        "\\\\o{17}",
                        "\\\\g1",
                        "\\\\C",
                        "a{,2}",
                        "a{1",
                        "{",
                        "a{1, 2}(")) {
            assertEquals(
                    List.of("pattern_not_portable"),
                    lint(head, "\"" + notPortable + "\"}"),
                    notPortable);
        }
        // PCRE2 refuses these; java.util.regex alone would compile the first two.
        for (String refused : List.of("\\\\u00e9", "a\\\\1", "(a)\\\\2", "[a")) {
            assertEquals(
                    List.of("pattern_does_not_compile"),
                    lint(head, "\"" + refused + "\"}"),
                    refused);
        }
    }

    @Test
    void aSharedSchemaWithAnUnsupportedKeywordIsRejectedOnLoad(@TempDir Path dir)
            throws IOException {
        Path copy = dir.resolve("protocol").resolve("v1");
        Files.createDirectories(copy);
        for (String file : List.of("health.schema.json", "common.schema.json")) {
            Files.copy(protocolDir().resolve(file), copy.resolve(file));
        }
        SchemaValidator validator = new SchemaValidator(dir);
        Path health = validator.schemaPath("health", "protocol", "v1");
        String text = Files.readString(health, StandardCharsets.UTF_8);
        byte[] live = Files.readAllBytes(protocolDir().resolve("fixtures/valid/health--live.json"));
        assertTrue(validator.validate(live, health).valid());
        for (String injected :
                List.of(
                        "\"patternProperties\":{},",
                        "\"if\":{\"type\":\"object\"},\"then\":{},",
                        "\"dependencies\":{},",
                        "\"format\":\"email\",")) {
            write(dir, "protocol/v1/health.schema.json", text.replaceFirst("\\{", "{" + injected));
            Files.setLastModifiedTime(
                    health,
                    java.nio.file.attribute.FileTime.fromMillis(
                            System.currentTimeMillis() + 10_000));
            SchemaException e =
                    assertThrows(SchemaException.class, () -> validator.validate(live, health));
            String expected =
                    injected.startsWith("\"format\"")
                            ? "unsupported_format"
                            : "unsupported_keyword";
            assertEquals(expected, e.reason(), injected);
            assertEquals("health.schema.json", e.schema());
        }
        Path common = copy.resolve("common.schema.json");
        String commonText = Files.readString(common, StandardCharsets.UTF_8);
        write(dir, "protocol/v1/health.schema.json", text);
        write(
                dir,
                "protocol/v1/common.schema.json",
                commonText.replace(
                        "\"format\": \"uuid\"", "\"format\": \"uuid\", \"contains\": {}"));
        SchemaException e =
                assertThrows(SchemaException.class, () -> validator.validate(live, health));
        assertEquals("unsupported_keyword", e.reason());
        assertEquals("common.schema.json", e.schema());
    }
}
