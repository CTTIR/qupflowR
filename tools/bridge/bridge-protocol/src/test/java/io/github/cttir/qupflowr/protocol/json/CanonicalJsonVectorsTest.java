/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Shared canonical JSON vectors read directly from {@code
 * inst/schema/protocol/v1/fixtures/canonical}: accepted inputs give the expected bytes and SHA-256
 * digests (typed by hand, cross-checked with an independent implementation and {@code sha256sum});
 * rejected inputs give the declared code and reason.
 */
class CanonicalJsonVectorsTest {

    private static Path dir() {
        String schemaDir = System.getProperty("qupflowr.test.schemaDir");
        assertNotNull(schemaDir, "system property qupflowr.test.schemaDir");
        return Path.of(schemaDir, "protocol", "v1", "fixtures", "canonical");
    }

    private static List<Path> inputs(boolean rejected) {
        try (Stream<Path> files = Files.list(dir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".expect.json"))
                    .filter(p -> p.getFileName().toString().startsWith("reject-") == rejected)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path sibling(Path input, String suffix) {
        String name = input.getFileName().toString();
        return input.resolveSibling(name.substring(0, name.length() - ".json".length()) + suffix);
    }

    @Test
    void corpusHasTheDocumentedSize() {
        assertTrue(inputs(false).size() >= 8, "accepted vectors");
        assertTrue(inputs(true).size() >= 9, "rejection vectors");
        for (Path input : inputs(false)) {
            assertTrue(Files.isRegularFile(sibling(input, ".canonical")), input.toString());
            assertTrue(Files.isRegularFile(sibling(input, ".sha256")), input.toString());
        }
    }

    @TestFactory
    Stream<DynamicTest> acceptedVectorsGiveTheExpectedBytesAndDigests() {
        return inputs(false).stream()
                .map(
                        input ->
                                DynamicTest.dynamicTest(
                                        input.getFileName().toString(),
                                        () -> checkAccepted(input)));
    }

    private static void checkAccepted(Path input) throws IOException {
        byte[] expected = Files.readAllBytes(sibling(input, ".canonical"));
        String line =
                Files.readString(sibling(input, ".sha256"), StandardCharsets.US_ASCII).strip();
        String[] fields = line.split("  ", 2);
        assertEquals(2, fields.length, line);
        assertEquals(sibling(input, ".canonical").getFileName().toString(), fields[1]);
        String digest = fields[0];

        byte[] actual = CanonicalJson.canonicalize(Files.readAllBytes(input));
        assertArrayEquals(expected, actual, new String(actual, StandardCharsets.UTF_8));
        assertEquals(digest, CanonicalJson.sha256Hex(actual));
        assertEquals(digest, CanonicalJson.sha256Hex(expected));
        for (byte b : actual) {
            assertTrue(b != '\n', "no newline in canonical bytes");
        }
        String text = Files.readString(input, StandardCharsets.UTF_8);
        assertEquals(
                new String(expected, StandardCharsets.UTF_8), CanonicalJson.canonicalize(text));
        assertArrayEquals(expected, CanonicalJson.canonicalize(expected), "idempotent");
        // Trees from other readers canonicalise to the same bytes.
        assertEquals(
                new String(expected, StandardCharsets.UTF_8),
                CanonicalJson.write(ProtocolJson.parse(text)));
        assertEquals(
                new String(expected, StandardCharsets.UTF_8),
                CanonicalJson.write(JsonParser.parseString(text)));
        assertEquals(digest, CanonicalJson.sha256Hex(CanonicalJsonReader.read(text)));
    }

    @TestFactory
    Stream<DynamicTest> rejectionVectorsFailWithTheDeclaredCodeAndReason() {
        return inputs(true).stream()
                .map(
                        input ->
                                DynamicTest.dynamicTest(
                                        input.getFileName().toString(),
                                        () -> checkRejected(input)));
    }

    private static void checkRejected(Path input) throws IOException {
        String sidecar = Files.readString(sibling(input, ".expect.json"), StandardCharsets.UTF_8);
        JsonObject expect = JsonParser.parseString(sidecar).getAsJsonObject();
        byte[] bytes = Files.readAllBytes(input);
        CanonicalJsonException e =
                assertThrows(CanonicalJsonException.class, () -> CanonicalJson.canonicalize(bytes));
        assertEquals(expect.get("code").getAsString(), e.code());
        assertEquals(expect.get("reason").getAsString(), e.reason());
    }
}
