/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalisation of the bytes that legitimately differ between two generator runs.
 *
 * <p>QuPath writes the absolute output root (as a path and as {@code file:} URIs), creation and
 * modification timestamps, a random {@code randomizedName} per project entry and a file time in
 * each {@code summary.json}. The generator does not alter those files. Instead the manifest
 * records, next to the raw SHA-256, a SHA-256 of the normalised bytes:
 *
 * <ul>
 *   <li>{@code root}: every spelling of the output root is replaced by {@code <ROOT>} (applies to
 *       any file whose bytes contain the root, including Java-serialised {@code data.qpdata});
 *   <li>{@code qupath_project}: in {@code project.qpproj}, the values of {@code createTimestamp}
 *       and {@code modifyTimestamp} become {@code 0} and each {@code randomizedName} becomes
 *       {@code "<RANDOM>"};
 *   <li>{@code qupath_summary}: in {@code summary.json}, the value of {@code timestamp} becomes
 *       {@code 0}.
 * </ul>
 *
 * <p>Java serialisation stores string lengths. In {@code .qpdata} files, each serialised string
 * record ({@code TC_STRING}, byte 0x74 followed by a two-byte big-endian length) whose content
 * contains the root is therefore rewritten with the replaced content and its new length, so that
 * the normalised bytes do not depend on the length of the output path.
 */
public final class OutputNormaliser {

    /** Placeholder of the output root. */
    public static final String ROOT_PLACEHOLDER = "<ROOT>";

    /** Rule id of root replacement. */
    public static final String RULE_ROOT = "root";

    /** Rule id of the project file rule. */
    public static final String RULE_PROJECT = "qupath_project";

    /** Rule id of the summary rule. */
    public static final String RULE_SUMMARY = "qupath_summary";

    private static final Pattern TIMESTAMPS =
            Pattern.compile("(?<key>\"(?:createTimestamp|modifyTimestamp)\"\\s*:\\s*)-?\\d+");
    private static final Pattern RANDOMIZED_NAME =
            Pattern.compile("(?<key>\"randomizedName\"\\s*:\\s*)\"[^\"]*\"");
    private static final Pattern SUMMARY_TIMESTAMP =
            Pattern.compile("(?<key>\"timestamp\"\\s*:\\s*)-?\\d+");

    private final List<byte[]> rootVariants;
    private final List<String> rootVariantStrings;

    /**
     * Creates a normaliser for an output root.
     *
     * @param root absolute output root
     */
    public OutputNormaliser(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        Set<String> variants = new LinkedHashSet<>();
        String plain = abs.toString();
        variants.add(plain);
        variants.add(abs.toUri().toString());
        variants.add(abs.toUri().toASCIIString());
        File file = abs.toFile();
        variants.add(file.toURI().toString());
        variants.add(file.toURI().toASCIIString());
        List<String> sorted = new ArrayList<>();
        for (String v : variants) {
            String trimmed =
                    v.endsWith("/") && v.length() > 1 ? v.substring(0, v.length() - 1) : v;
            if (!sorted.contains(trimmed)) {
                sorted.add(trimmed);
            }
        }
        sorted.sort(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));
        this.rootVariantStrings = List.copyOf(sorted);
        List<byte[]> bytes = new ArrayList<>();
        for (String v : sorted) {
            bytes.add(v.getBytes(StandardCharsets.UTF_8));
        }
        this.rootVariants = List.copyOf(bytes);
    }

    /**
     * Spellings of the root that are replaced, longest first.
     *
     * @return variants
     */
    public List<String> rootVariants() {
        return rootVariantStrings;
    }

    /**
     * Whether content contains the output root.
     *
     * @param content bytes
     * @return true if any root spelling occurs
     */
    public boolean containsRoot(byte[] content) {
        for (byte[] v : rootVariants) {
            if (indexOf(content, v, 0) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Content rule of a file.
     *
     * @param relativePath path relative to the output root
     * @return {@link #RULE_PROJECT}, {@link #RULE_SUMMARY} or null
     */
    public static String contentRule(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (name.equals("project.qpproj")) {
            return RULE_PROJECT;
        }
        if (name.equals("summary.json") && relativePath.contains("/data/")) {
            return RULE_SUMMARY;
        }
        return null;
    }

    /**
     * Rules that apply to a file.
     *
     * @param relativePath path relative to the output root
     * @param content bytes
     * @return rule ids in application order (may be empty)
     */
    public List<String> rules(String relativePath, byte[] content) {
        List<String> rules = new ArrayList<>();
        if (containsRoot(content)) {
            rules.add(RULE_ROOT);
        }
        String rule = contentRule(relativePath);
        if (rule != null) {
            rules.add(rule);
        }
        return rules;
    }

    /**
     * Normalises content.
     *
     * @param relativePath path relative to the output root
     * @param content bytes
     * @return normalised bytes (a copy)
     */
    public byte[] normalise(String relativePath, byte[] content) {
        byte[] out = relativePath.endsWith(".qpdata") ? normaliseSerialisedStrings(content)
                : content.clone();
        out = replaceRoot(out);
        String rule = contentRule(relativePath);
        if (rule == null) {
            return out;
        }
        String text = new String(out, StandardCharsets.UTF_8);
        if (RULE_PROJECT.equals(rule)) {
            text = TIMESTAMPS.matcher(text).replaceAll("${key}0");
            text = RANDOMIZED_NAME.matcher(text).replaceAll("${key}\"<RANDOM>\"");
        } else {
            text = SUMMARY_TIMESTAMP.matcher(text).replaceAll("${key}0");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Description of the rules for the manifest.
     *
     * @return ordered rule id to description map
     */
    public static Map<String, Object> ruleDescriptions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(RULE_ROOT, "replace each spelling of the absolute output root (path, file: URI, "
                + "ASCII URI; without trailing slash; longest first) by " + ROOT_PLACEHOLDER
                + "; in .qpdata files the two-byte length of each Java-serialised string "
                + "(0x74 record) containing the root is recomputed");
        m.put(RULE_PROJECT, "project.qpproj: createTimestamp and modifyTimestamp values -> 0, "
                + "randomizedName values -> \"<RANDOM>\"");
        m.put(RULE_SUMMARY, "data/<entry>/summary.json: timestamp value -> 0");
        return m;
    }

    private byte[] replaceRoot(byte[] content) {
        byte[] out = content;
        byte[] placeholder = ROOT_PLACEHOLDER.getBytes(StandardCharsets.UTF_8);
        for (byte[] v : rootVariants) {
            out = replaceAll(out, v, placeholder);
        }
        return out;
    }

    /**
     * Rewrites serialised string records that contain the root with replaced content and a
     * recomputed length; all other bytes are copied unchanged.
     */
    byte[] normaliseSerialisedStrings(byte[] content) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(content.length);
        int i = 0;
        while (i < content.length) {
            if (content[i] == 0x74 && i + 3 <= content.length) {
                int length = ((content[i + 1] & 0xff) << 8) | (content[i + 2] & 0xff);
                int end = i + 3 + length;
                if (end <= content.length) {
                    byte[] segment = java.util.Arrays.copyOfRange(content, i + 3, end);
                    byte[] replaced = containsRoot(segment) ? replaceRoot(segment) : null;
                    if (replaced != null && replaced.length <= 0xffff) {
                        out.write(0x74);
                        out.write((replaced.length >> 8) & 0xff);
                        out.write(replaced.length & 0xff);
                        out.write(replaced, 0, replaced.length);
                        i = end;
                        continue;
                    }
                }
            }
            out.write(content[i]);
            i++;
        }
        return out.toByteArray();
    }

    static byte[] replaceAll(byte[] content, byte[] target, byte[] replacement) {
        int first = indexOf(content, target, 0);
        if (first < 0) {
            return content;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(content.length);
        int pos = 0;
        int hit = first;
        while (hit >= 0) {
            out.write(content, pos, hit - pos);
            out.write(replacement, 0, replacement.length);
            pos = hit + target.length;
            hit = indexOf(content, target, pos);
        }
        out.write(content, pos, content.length - pos);
        return out.toByteArray();
    }

    static int indexOf(byte[] content, byte[] target, int from) {
        if (target.length == 0) {
            return -1;
        }
        outer:
        for (int i = from; i <= content.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (content[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
