/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.check;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.schemaText;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.cttir.qupflowr.protocol.json.CanonicalJsonException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

/**
 * References, schema errors and loading, with the cases and reasons of the R package's {@code
 * test-json-schema.R}.
 */
class SchemaReferenceTest {

    @TempDir Path dir;

    private String reason(String schemaBody, String instance) {
        Path schema = write(dir, "s.json", schemaBody);
        SchemaException e =
                assertThrows(
                        SchemaException.class,
                        () -> new SchemaValidator(dir).validate(instance, schema));
        assertEquals("SCHEMA_INVALID", e.code());
        return e.reason();
    }

    @Test
    void localAndRelativeReferencesResolveWithPointerEscapes() {
        Path main =
                write(
                        dir,
                        "main.json",
                        schemaText(
                                "\"properties\":{\"a\":{\"$ref\":\"#/definitions/a~1b\"},",
                                "\"b\":{\"$ref\":\"other.json#/definitions/positive\"},",
                                "\"c\":{\"$ref\":\"sub/third.json\"},",
                                "\"d\":{\"$ref\":\"#/definitions/list/items\"}},",
                                "\"definitions\":{\"a/b\":{\"type\":\"string\"},",
                                "\"list\":{\"type\":\"array\",\"items\":{\"const\":\"x\"}}}"));
        write(
                dir,
                "other.json",
                schemaText("\"definitions\":{\"positive\":{\"type\":\"integer\",\"minimum\":1}}"));
        write(
                dir,
                "sub/third.json",
                schemaText("\"$ref\":\"../other.json#/definitions/positive\""));
        SchemaValidator validator = new SchemaValidator(dir);
        assertEquals(
                List.of(), check(validator, "{\"a\":\"v\",\"b\":1,\"c\":2,\"d\":\"x\"}", main));
        ValidationReport report = validator.validate("{\"a\":1,\"b\":0,\"c\":0,\"d\":\"y\"}", main);
        assertEquals(
                List.of("/a", "/b", "/c", "/d"),
                report.errors().stream().map(ValidationError::path).toList());
        assertEquals(
                List.of("type", "minimum", "minimum", "const"),
                report.errors().stream().map(ValidationError::keyword).toList());
        assertEquals(
                List.of(
                        "main.json#/definitions/a~1b/type",
                        "other.json#/definitions/positive/minimum",
                        "other.json#/definitions/positive/minimum",
                        "main.json#/definitions/list/items/const"),
                report.errors().stream().map(ValidationError::schemaPath).toList());
    }

    @Test
    void percentEncodedPointerSegmentsResolve() {
        Path main =
                write(
                        dir,
                        "p.json",
                        schemaText(
                                "\"definitions\":{\"a b\":{\"type\":\"string\"},",
                                "\"\\u00e4\":{\"type\":\"integer\"}},",
                                "\"properties\":{\"x\":{\"$ref\":\"#/definitions/a%20b\"},",
                                "\"y\":{\"$ref\":\"#/definitions/%C3%A4\"}}"));
        SchemaValidator validator = new SchemaValidator(dir);
        ValidationReport report = validator.validate("{\"x\":1,\"y\":\"1\"}", main);
        assertEquals(
                List.of("p.json#/definitions/a%20b/type", "p.json#/definitions/%C3%A4/type"),
                report.errors().stream().map(ValidationError::schemaPath).toList());
    }

    @Test
    void unsupportedOrUnresolvableSchemasAreSchemaErrors() {
        assertEquals("unsupported_keyword", reason(schemaText("\"minProperties\":1"), "{}"));
        assertEquals("draft07_required", reason("{\"type\":\"object\"}", "{}"));
        assertEquals("unsupported_format", reason(schemaText("\"format\":\"email\""), "\"a\""));
        assertEquals(
                "pattern_not_portable", reason(schemaText("\"pattern\":\"^\\\\d+$\""), "\"1\""));
        assertEquals("pattern_not_portable", reason(schemaText("\"pattern\":\"^a.b$\""), "\"a\""));
        assertEquals("pattern_not_portable", reason(schemaText("\"pattern\":\"(?=a)a\""), "\"a\""));
        assertEquals("pattern_not_portable", reason(schemaText("\"pattern\":\"a$b\""), "\"a\""));
        assertEquals("pattern_does_not_compile", reason(schemaText("\"pattern\":\"(\""), "\"a\""));
        assertEquals(
                "tuple_items_unsupported",
                reason(schemaText("\"items\":[{\"type\":\"string\"}]"), "[]"));
        assertEquals(
                "non_local_ref", reason(schemaText("\"$ref\":\"http://example.org/s.json\""), "1"));
        assertEquals("non_local_ref", reason(schemaText("\"$ref\":\"/etc/s.json\""), "1"));
        assertEquals("non_local_ref", reason(schemaText("\"$ref\":\"a\\\\b.json\""), "1"));
        assertEquals(
                "unresolved_ref", reason(schemaText("\"$ref\":\"#/definitions/missing\""), "1"));
        assertEquals("unresolved_ref", reason(schemaText("\"$ref\":\"missing.json\""), "1"));
        assertEquals(
                "ref_outside_schema_root", reason(schemaText("\"$ref\":\"../outside.json\""), "1"));
        assertEquals("circular_ref", reason(schemaText("\"$ref\":\"#\""), "1"));
        assertEquals(
                "ref_with_siblings",
                reason(schemaText("\"$ref\":\"#/definitions/x\",\"type\":\"null\""), "1"));
        assertEquals("invalid_type", reason(schemaText("\"type\":\"float\""), "1"));
        assertEquals("unreadable_json", reason("{\"$schema\": ", "1"));
        // Comments are not JSON (RFC 8259). The R reader (jsonlite) currently accepts them; that
        // divergence is reported for the R side instead of being copied here.
        assertEquals("unreadable_json", reason(schemaText("\"a\":1 /* comment */"), "1"));
    }

    @Test
    void invalidInstanceJsonIsReportedBeforeTheSchemaIsLoaded() {
        Path broken = write(dir, "broken.json", "{\"type\":");
        CanonicalJsonException e =
                assertThrows(
                        CanonicalJsonException.class,
                        () -> new SchemaValidator(dir).validate("{\"a\":1,\"a\":2}", broken));
        assertEquals("duplicate_key", e.reason());
        assertEquals("/a", e.pointer());
    }

    @Test
    void pointersIntoArraysAndEmptyObjectsResolve() {
        Path s =
                write(
                        dir,
                        "p.json",
                        schemaText(
                                "\"definitions\":{\"alts\":{\"anyOf\":[{\"type\":\"string\"},",
                                "{\"type\":\"null\"}]}},\"properties\":{",
                                "\"a\":{\"$ref\":\"#/definitions/alts/anyOf/1\"},",
                                "\"b\":{\"enum\":[{}]}}"));
        SchemaValidator validator = new SchemaValidator(dir);
        assertEquals(List.of(), check(validator, "{\"a\":null,\"b\":{}}", s));
        assertEquals(List.of("/a type"), check(validator, "{\"a\":\"x\"}", s));
        assertEquals(List.of("/b enum"), check(validator, "{\"b\":{\"k\":1}}", s));
    }

    @Test
    void malformedPointersDoNotResolve() {
        for (String ref :
                List.of(
                        "#definitions",
                        "#/title/x",
                        "#/definitions/list/anyOf/5",
                        "#/definitions/list/anyOf/first",
                        "#/definitions/list/anyOf/01",
                        "#/definitions/list/anyOf/99999999999",
                        "#/definitions/%zz",
                        "#/definitions/%C3",
                        "#/definitions/%4",
                        "#/definitions/%00",
                        "#/definitions/list/anyOf/1234567890")) {
            String body =
                    schemaText(
                            "\"title\":\"t\",\"definitions\":{\"list\":{\"anyOf\":[true]}},",
                            "\"properties\":{\"a\":{\"$ref\":\"" + ref + "\"}}");
            assertEquals("unresolved_ref", reason(body, "{\"a\":1}"), ref);
        }
    }

    @Test
    void percentDecodingComesBeforeTildeUnescaping() {
        // The same cases as test-json-schema.R: all four references name the member "a/b".
        for (String ref :
                List.of(
                        "#/definitions/a~1b",
                        "#/definitions/a%2Fb",
                        "#/definitions/a%7E1b",
                        "#/definitions/a%7e1b")) {
            Path schema =
                    write(
                            dir,
                            "s.json",
                            schemaText(
                                    "\"definitions\":{\"a/b\":{\"type\":\"integer\"}},",
                                    "\"$ref\":\"" + ref + "\""));
            List<ValidationError> errors =
                    new SchemaValidator(dir).validate("\"a\"", schema).errors();
            assertEquals(1, errors.size(), ref);
            assertEquals("type", errors.get(0).keyword(), ref);
            assertEquals("s.json" + ref + "/type", errors.get(0).schemaPath(), ref);
        }
    }

    @Test
    void referencesToValuesThatAreNotSchemas() {
        String body =
                schemaText(
                        "\"definitions\":{\"x\":{\"type\":\"string\",\"enum\":[\"a\"]}},",
                        "\"properties\":{\"p\":{\"$ref\":\"#/definitions/x/type\"},",
                        "\"q\":{\"$ref\":\"#/definitions/x/enum\"}}");
        // An array target has no keywords and a string target is schema_not_object, in R as well.
        assertEquals("schema_not_object", reason(body, "{\"p\":1}"));
        Path schema = write(dir, "s.json", body);
        assertEquals(List.of(), check(new SchemaValidator(dir), "{\"q\":1}", schema));
    }

    @Test
    void symbolicLinksCannotLeaveTheSchemaRoot(@TempDir Path outside) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        write(outside, "o.json", schemaText("\"type\":\"string\""));
        Path main = write(root, "main.json", schemaText("\"$ref\":\"link.json\""));
        Files.createSymbolicLink(root.resolve("link.json"), outside.resolve("o.json"));
        SchemaException e =
                assertThrows(
                        SchemaException.class, () -> new SchemaValidator(root).validate("1", main));
        assertEquals("ref_outside_schema_root", e.reason());
        Path inner = write(root, "inner/real.json", schemaText("\"type\":\"string\""));
        Files.createSymbolicLink(root.resolve("inside.json"), inner);
        Path viaLink = write(root, "via.json", schemaText("\"$ref\":\"inside.json\""));
        assertEquals(List.of(" type"), check(new SchemaValidator(root), "1", viaLink));
    }

    @Test
    void aSchemaOutsideTheRootUsesItsOwnDirectoryAsReferenceRoot(@TempDir Path other) {
        Path schema = write(other, "x.json", schemaText("\"$ref\":\"../escape.json\""));
        SchemaException e =
                assertThrows(
                        SchemaException.class,
                        () -> new SchemaValidator(dir).validate("1", schema));
        assertEquals("ref_outside_schema_root", e.reason());
    }

    @Test
    void schemaDocumentsAreReloadedWhenTheyChange() throws IOException {
        Path s = write(dir, "c.json", schemaText("\"type\":\"integer\""));
        SchemaValidator validator = new SchemaValidator(dir);
        assertEquals(List.of(" type"), check(validator, "\"a\"", s));
        Files.writeString(
                s, schemaText("\"type\":\"string\",\"maxLength\":10"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(s, FileTime.from(Instant.now().plusSeconds(5)));
        assertEquals(List.of(), check(validator, "\"a\"", s));
    }

    @Test
    void schemaPathLocatesInstalledSchemasByName() {
        SchemaValidator validator = new SchemaValidator(SchemaTestSupport.schemaRoot());
        Path health = validator.schemaPath("health", "protocol", "v1");
        assertTrue(Files.isRegularFile(health));
        assertEquals("health.schema.json", health.getFileName().toString());
        SchemaException missing =
                assertThrows(
                        SchemaException.class,
                        () -> validator.schemaPath("no-such-schema", "protocol", "v1"));
        assertEquals("not_found", missing.reason());
        assertThrows(
                IllegalArgumentException.class,
                () -> validator.schemaPath("../health", "protocol", "v1"));
        assertThrows(
                IllegalArgumentException.class, () -> validator.schemaPath("health", "Up", "v1"));
        assertThrows(IllegalArgumentException.class, () -> new SchemaValidator(dir.resolve("no")));
    }

    @Test
    void valuesAreValidatedLikeTheirJsonText() {
        Path s =
                write(
                        dir,
                        "v.json",
                        schemaText(
                                "\"type\":\"object\",\"properties\":{",
                                "\"ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},",
                                "\"n\":{\"type\":\"integer\"}}"));
        SchemaValidator validator = new SchemaValidator(dir);
        JsonObject value = new JsonObject();
        value.add("n", new JsonPrimitive(2.5));
        assertEquals(
                List.of("/n type"),
                validator.validate(value, s).errors().stream()
                        .map(e -> e.path() + " " + e.keyword())
                        .toList());
        JsonObject empty = new JsonObject();
        empty.add("", new JsonPrimitive(1));
        CanonicalJsonException e =
                assertThrows(CanonicalJsonException.class, () -> validator.validate(empty, s));
        assertEquals("empty_member_name", e.reason());
    }
}
