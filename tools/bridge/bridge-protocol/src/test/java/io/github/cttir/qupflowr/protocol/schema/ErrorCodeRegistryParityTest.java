/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.protocolDir;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.schemaRoot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.cttir.qupflowr.protocol.ErrorCode;
import io.github.cttir.qupflowr.protocol.ErrorCodeRegistry;
import io.github.cttir.qupflowr.protocol.ErrorCodes;
import io.github.cttir.qupflowr.protocol.json.CanonicalJson;
import io.github.cttir.qupflowr.protocol.json.CanonicalJsonReader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Java error-code registry is the shared file {@code error-codes.json}: same entries, same
 * order, same fields. The shared file is read here with {@link CanonicalJsonReader}, not with the
 * reader the registry itself uses.
 */
class ErrorCodeRegistryParityTest {

    private static final Path SHARED = protocolDir().resolve("error-codes.json");

    /** The thirteen R condition classes of BLUEPRINT section 3. */
    private static final Set<String> R_CLASSES =
            Set.of(
                    "qp_connection_error",
                    "qp_version_error",
                    "qp_capability_error",
                    "qp_validation_error",
                    "qp_conflict_error",
                    "qp_busy_error",
                    "qp_recovery_error",
                    "qp_job_error",
                    "qp_bundle_error",
                    "qp_runtime_error",
                    "qp_reproduction_error",
                    "qp_serialization_error",
                    "qp_storage_error");

    private static JsonArray shared() throws IOException {
        return CanonicalJsonReader.read(Files.readAllBytes(SHARED)).getAsJsonArray();
    }

    @Test
    void sharedRegistryIsValidAgainstItsSchema() throws IOException {
        SchemaValidator validator = new SchemaValidator(schemaRoot());
        ValidationReport report =
                validator.validate(
                        Files.readAllBytes(SHARED),
                        validator.schemaPath("error-codes", "protocol", "v1"));
        assertTrue(report.valid(), report.toString());
    }

    @Test
    void javaRegistryWrittenBackIsCanonicallyEqualToTheSharedFile() throws IOException {
        JsonArray rebuilt = new JsonArray();
        for (ErrorCode code : ErrorCodeRegistry.bundled().all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("code", code.code());
            entry.add(
                    "http_status",
                    code.httpStatus() == null
                            ? JsonNull.INSTANCE
                            : new JsonPrimitive(code.httpStatus()));
            entry.addProperty("r_class", code.rClass());
            entry.addProperty("retryable", code.retryable());
            entry.addProperty("message_key", code.messageKey());
            entry.add(
                    "source",
                    code.source() == null ? JsonNull.INSTANCE : new JsonPrimitive(code.source()));
            rebuilt.add(entry);
        }
        assertEquals(CanonicalJson.write(shared()), CanonicalJson.write(rebuilt));
        try (InputStream in =
                ErrorCodeRegistry.class.getResourceAsStream(ErrorCodeRegistry.RESOURCE)) {
            assertNotNull(in, "bundled registry resource");
            assertEquals(
                    CanonicalJson.sha256Hex(shared()),
                    CanonicalJson.sha256Hex(CanonicalJsonReader.read(in.readAllBytes())));
        }
    }

    @Test
    void registryEntriesFollowTheSharedRules() throws IOException {
        Map<String, JsonObject> byCode = new HashMap<>();
        Set<String> retryable = new TreeSet<>();
        for (JsonElement e : shared()) {
            JsonObject entry = e.getAsJsonObject();
            String code = entry.get("code").getAsString();
            assertEquals(null, byCode.put(code, entry), "duplicate " + code);
            assertEquals(
                    "error." + code.toLowerCase(Locale.ROOT),
                    entry.get("message_key").getAsString(),
                    code);
            assertTrue(R_CLASSES.contains(entry.get("r_class").getAsString()), code);
            JsonElement status = entry.get("http_status");
            if (!status.isJsonNull()) {
                assertTrue(status.getAsInt() >= 400 && status.getAsInt() <= 599, code);
            }
            if (entry.get("retryable").getAsBoolean()) {
                retryable.add(code);
            }
        }
        assertEquals(
                new TreeSet<>(List.of("HOST_BUSY", "PROJECT_BUSY", "REQUEST_TIMEOUT")), retryable);
        for (String emitted : ErrorCodes.emitted()) {
            assertTrue(byCode.containsKey(emitted), emitted);
            assertTrue(!byCode.get(emitted).get("http_status").isJsonNull(), emitted);
        }
        assertEquals(byCode.size(), ErrorCodeRegistry.bundled().all().size());
    }
}
