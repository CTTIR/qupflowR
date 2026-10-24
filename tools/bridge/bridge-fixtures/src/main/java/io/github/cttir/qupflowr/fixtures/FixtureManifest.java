/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** File inventory and manifest document of a generated fixture tree. */
public final class FixtureManifest {

    /** Manifest schema version. */
    public static final String MANIFEST_SCHEMA_VERSION = "1.0";

    private FixtureManifest() {}

    /**
     * One file of the inventory.
     *
     * @param path path relative to the output root, {@code /} separated, NFC
     * @param size size in bytes
     * @param sha256 SHA-256 of the raw bytes
     * @param role file role
     * @param rules normalisation rules that apply (empty when the bytes are run-independent)
     * @param sha256Normalised SHA-256 of the normalised bytes, or null without rules
     */
    public record FileRecord(
            String path, long size, String sha256, String role, List<String> rules,
            String sha256Normalised) {}

    /**
     * Role of a file by its path.
     *
     * @param path relative path
     * @return {@code expected_values}, {@code image}, {@code qupath_project},
     *     {@code qupath_project_moved} or {@code other}
     */
    public static String role(String path) {
        if (path.startsWith("expected/")) {
            return "expected_values";
        }
        if (path.startsWith("images/")) {
            return "image";
        }
        if (path.startsWith("project/")) {
            return "qupath_project";
        }
        if (path.startsWith("moved/")) {
            return "qupath_project_moved";
        }
        return "other";
    }

    /**
     * Inventories every regular file below the root except {@code manifest.json}.
     *
     * @param root output root
     * @param normaliser normaliser of this root
     * @return records sorted by path
     * @throws IOException on read errors, symbolic links or non-NFC names
     */
    public static List<FileRecord> inventory(Path root, OutputNormaliser normaliser)
            throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(p)) {
                    throw new IOException("Symbolic link in fixture output: " + p);
                }
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(p);
                }
            }
        }
        List<FileRecord> records = new ArrayList<>();
        for (Path p : files) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (rel.equals(FixtureLayout.MANIFEST)) {
                continue;
            }
            if (!Normalizer.isNormalized(rel, Normalizer.Form.NFC)) {
                throw new IOException("File name is not NFC: " + rel);
            }
            byte[] content = Files.readAllBytes(p);
            List<String> rules = normaliser.rules(rel, content);
            String normalised =
                    rules.isEmpty() ? null : Digests.sha256(normaliser.normalise(rel, content));
            records.add(new FileRecord(rel, content.length, Digests.sha256(content), role(rel),
                    List.copyOf(rules), normalised));
        }
        records.sort((a, b) -> a.path().compareTo(b.path()));
        return records;
    }

    /**
     * Manifest file list as JSON-ready maps.
     *
     * @param records inventory
     * @return list of ordered maps
     */
    public static List<Map<String, Object>> filesSection(List<FileRecord> records) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FileRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.path());
            m.put("role", r.role());
            m.put("size", r.size());
            m.put("sha256", r.sha256());
            m.put("normalisation", r.rules());
            m.put("sha256_normalised", r.sha256Normalised());
            out.add(m);
        }
        return out;
    }
}
