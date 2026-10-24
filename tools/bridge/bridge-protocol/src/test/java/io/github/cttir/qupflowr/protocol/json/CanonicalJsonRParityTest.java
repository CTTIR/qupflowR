/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Canonicalises the seeded random corpus written by {@code tools/bridge/scripts/contract-parity.R}
 * and compares the bytes with the R results. Runs only through {@code contract-parity.sh}, which
 * passes the corpus directory ({@code -Pqupflowr.parityDir}).
 *
 * <p>Layout: {@code input/doc-NNNN.json} (text from the generator's own writer), {@code
 * r/doc-NNNN.canonical} (bytes of {@code qp_json_canonical()}) or {@code r/doc-NNNN.reject} ({@code
 * <code> <reason>}), and {@code manifest.json}. Java results are written to {@code java/} with the
 * same names so the script can compare them independently.
 */
@EnabledIfSystemProperty(named = "qupflowr.test.parityDir", matches = ".+")
class CanonicalJsonRParityTest {

    @Test
    void everyRDocumentGivesTheSameCanonicalBytesOrRejectionInJava() throws IOException {
        Path corpus = Path.of(System.getProperty("qupflowr.test.parityDir"));
        JsonObject manifest =
                JsonParser.parseString(
                                Files.readString(
                                        corpus.resolve("manifest.json"), StandardCharsets.UTF_8))
                        .getAsJsonObject();
        int accepted = manifest.get("accepted").getAsInt();
        int rejected = manifest.get("rejected").getAsInt();
        assertTrue(accepted >= 200, "accepted documents in the corpus: " + accepted);
        List<Path> inputs;
        try (Stream<Path> files = Files.list(corpus.resolve("input"))) {
            inputs =
                    files.filter(p -> p.toString().endsWith(".json"))
                            .sorted()
                            .collect(Collectors.toList());
        }
        assertEquals(accepted + rejected, inputs.size(), "documents listed in the manifest");
        Path out = Files.createDirectories(corpus.resolve("java"));
        List<String> mismatches = new ArrayList<>();
        int comparedAccepted = 0;
        int comparedRejected = 0;
        for (Path input : inputs) {
            String stem = input.getFileName().toString().replaceAll("\\.json$", "");
            byte[] bytes = Files.readAllBytes(input);
            Path rCanonical = corpus.resolve("r").resolve(stem + ".canonical");
            Path rReject = corpus.resolve("r").resolve(stem + ".reject");
            String javaResult;
            byte[] javaBytes = null;
            try {
                javaBytes = CanonicalJson.canonicalize(bytes);
                Files.write(out.resolve(stem + ".canonical"), javaBytes);
                javaResult = null;
            } catch (CanonicalJsonException e) {
                javaResult = e.code() + " " + e.reason();
                Files.writeString(
                        out.resolve(stem + ".reject"), javaResult, StandardCharsets.UTF_8);
            }
            if (Files.isRegularFile(rCanonical)) {
                comparedAccepted++;
                if (javaBytes == null
                        || !Arrays.equals(Files.readAllBytes(rCanonical), javaBytes)) {
                    mismatches.add(
                            stem
                                    + " (R accepted, Java "
                                    + (javaBytes == null ? javaResult : "differs")
                                    + ")");
                }
            } else if (Files.isRegularFile(rReject)) {
                comparedRejected++;
                String rResult = Files.readString(rReject, StandardCharsets.UTF_8).strip();
                if (!rResult.equals(javaResult)) {
                    mismatches.add(stem + " (R " + rResult + ", Java " + javaResult + ")");
                }
            } else {
                mismatches.add(stem + " (no R result)");
            }
        }
        assertEquals(accepted, comparedAccepted, "accepted documents compared");
        assertEquals(rejected, comparedRejected, "rejected documents compared");
        if (!mismatches.isEmpty()) {
            fail(
                    mismatches.size()
                            + " mismatches: "
                            + mismatches.subList(0, Math.min(20, mismatches.size())));
        }
    }
}
