/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.cttir.qupflowr.protocol.schema.SchemaException;
import io.github.cttir.qupflowr.protocol.schema.SchemaValidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Numeric admission vectors shared with R and references into arbitrary schema data. */
class ProtocolBoundaryTest {

    @TempDir Path directory;

    @Test
    void sharedNumericRangeVectorsPreserveNonzeroValuesOrRefuse() throws IOException {
        Path vectors = Path.of(System.getProperty("qupflowr.test.schemaDir"),
                "protocol", "v1", "fixtures", "number-range.json");
        JsonObject cases = JsonParser.parseString(Files.readString(vectors)).getAsJsonObject();
        for (JsonElement item : cases.getAsJsonArray("rejected")) {
            JsonObject c = item.getAsJsonObject();
            String text = c.get("text").getAsString();
            CanonicalJsonException error = assertThrows(CanonicalJsonException.class,
                    () -> CanonicalJsonReader.read(text), text);
            assertEquals("JSON_UNREPRESENTABLE", error.code());
            assertEquals("number_out_of_range", error.reason());
            assertEquals(c.get("pointer").getAsString(), error.pointer());
        }
        Map<String, Double> expected = Map.of(
                "0", 0.0,
                "min_subnormal", Double.MIN_VALUE,
                "negative_min_subnormal", -Double.MIN_VALUE,
                "min_normal", Double.MIN_NORMAL,
                "max_finite", Double.MAX_VALUE,
                "1.25", 1.25);
        for (JsonElement item : cases.getAsJsonArray("accepted")) {
            JsonObject c = item.getAsJsonObject();
            double value = CanonicalJsonReader.read(c.get("text").getAsString()).getAsDouble();
            assertEquals(expected.get(c.get("value").getAsString()), value);
        }
        assertEquals("1e-400", CanonicalJsonReader.read("\"1e-400\"").getAsString());
        CanonicalJsonException error = assertThrows(CanonicalJsonException.class,
                () -> CanonicalJsonReader.read("{\"a\":1,\"a\":1e-400}"));
        assertEquals("duplicate_key", error.reason());
        error = assertThrows(CanonicalJsonException.class,
                () -> CanonicalJsonReader.read("[1e-400,{\"a\":1,\"a\":2}]"));
        assertEquals("number_out_of_range", error.reason());
    }

    private Path referenceToData(String data, int index) throws IOException {
        Path path = directory.resolve("case-" + index + ".json");
        Files.writeString(path, "{\"$schema\":\"http://json-schema.org/draft-07/schema#\","
                + "\"definitions\":{\"data\":{\"const\":" + data + "}},"
                + "\"$ref\":\"#/definitions/data/const\"}");
        return path;
    }

    @Test
    void resolvedDataReceivesTheSameSchemaAdmissionChecks() throws IOException {
        Map<String, String> invalid = new LinkedHashMap<>();
        for (String value : new String[] {"null", "[]", "[1]", "\"text\"", "1"}) {
            invalid.put(value, "schema_not_object");
        }
        invalid.put("{\"type\":\"float\"}", "invalid_type");
        invalid.put("{\"minProperties\":1}", "unsupported_keyword");
        invalid.put("{\"properties\":{\"x\":{\"type\":\"float\"}}}", "invalid_type");
        SchemaValidator validator = new SchemaValidator(directory);
        int index = 0;
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            Path path = referenceToData(entry.getKey(), index++);
            SchemaException error = assertThrows(SchemaException.class,
                    () -> validator.validate("1", path), entry.getKey());
            assertEquals("SCHEMA_INVALID", error.code());
            assertEquals(entry.getValue(), error.reason());
        }
        for (String data : new String[] {"true", "false", "{}", "{\"type\":\"integer\"}"}) {
            assertEquals(!data.equals("false"),
                    validator.validate("1", referenceToData(data, index++)).valid(), data);
        }
    }
}
