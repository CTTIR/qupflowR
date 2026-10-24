/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural checks of a schema document, run when it is loaded. Issues are collected depth-first
 * in the order of the R validator: keywords of a node, keyword values, then {@code definitions} and
 * {@code properties} members, then {@code items}, {@code not} and {@code additionalProperties},
 * then {@code oneOf}, {@code anyOf} and {@code allOf} branches, each group in the member order of
 * the node.
 */
final class SchemaLint {

    /** Draft-07 meta-schema URI required at every schema root. */
    static final String DRAFT_07 = "http://json-schema.org/draft-07/schema#";

    /** Supported keywords (BLUEPRINT section 3, decision D022). */
    static final List<String> KEYWORDS =
            List.of(
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

    /** JSON Schema type names. */
    static final List<String> TYPES =
            List.of("null", "boolean", "object", "array", "number", "integer", "string");

    private static final Set<String> COUNTS =
            Set.of("minItems", "maxItems", "minLength", "maxLength");

    private static final Set<String> BOUNDS =
            Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");

    private static final Set<String> TEXTS = Set.of("title", "description", "$id");

    private static final Set<String> CONTAINERS = Set.of("definitions", "properties");

    private static final Set<String> SINGLE_SCHEMAS =
            Set.of("items", "not", "additionalProperties");

    private static final Set<String> COMBINATORS = Set.of("oneOf", "anyOf", "allOf");

    private static final Set<String> ROOT_ONLY = Set.of("$schema", "$id");

    private static final Set<String> REF_SIBLINGS =
            Set.of("$ref", "title", "description", "$schema", "$id", "definitions");

    private SchemaLint() {}

    /**
     * Lints a schema document.
     *
     * @param document parsed schema file
     * @return issues in discovery order; empty for a supported schema
     */
    static List<SchemaIssue> lint(JsonElement document) {
        List<SchemaIssue> issues = new ArrayList<>();
        node(document, "", true, issues);
        return issues;
    }

    private static void node(
            JsonElement node, String pointer, boolean root, List<SchemaIssue> out) {
        if (JsonValues.isBoolean(node)) {
            return;
        }
        if (!(node instanceof JsonObject schema)) {
            out.add(new SchemaIssue(pointer, "", "schema_not_object"));
            return;
        }
        List<String> keys = new ArrayList<>(schema.keySet());
        keys(schema, keys, root, pointer, out);
        values(schema, keys, pointer, out);
        for (String key : keys) {
            if (!CONTAINERS.contains(key)) {
                continue;
            }
            if (!(schema.get(key) instanceof JsonObject members)) {
                out.add(new SchemaIssue(pointer, key, "members_not_object"));
                continue;
            }
            for (Map.Entry<String, JsonElement> member : members.entrySet()) {
                String child = pointer + "/" + key + "/" + JsonValues.escape(member.getKey());
                node(member.getValue(), child, false, out);
            }
        }
        for (String key : keys) {
            if (!SINGLE_SCHEMAS.contains(key)) {
                continue;
            }
            JsonElement value = schema.get(key);
            boolean tuple = value instanceof JsonArray array && !array.isEmpty();
            if (key.equals("items") && tuple) {
                out.add(new SchemaIssue(pointer, key, "tuple_items_unsupported"));
            } else {
                node(value, pointer + "/" + key, false, out);
            }
        }
        for (String key : keys) {
            if (!COMBINATORS.contains(key)) {
                continue;
            }
            if (!(schema.get(key) instanceof JsonArray branches) || branches.isEmpty()) {
                out.add(new SchemaIssue(pointer, key, "branches_not_array"));
                continue;
            }
            for (int j = 0; j < branches.size(); j++) {
                node(branches.get(j), pointer + "/" + key + "/" + j, false, out);
            }
        }
    }

    private static void keys(
            JsonObject schema,
            List<String> keys,
            boolean root,
            String pointer,
            List<SchemaIssue> out) {
        for (String key : keys) {
            if (!KEYWORDS.contains(key)) {
                out.add(new SchemaIssue(pointer, key, "unsupported_keyword"));
            }
        }
        if (!root) {
            for (String key : keys) {
                if (ROOT_ONLY.contains(key)) {
                    out.add(new SchemaIssue(pointer, key, "root_only_keyword"));
                }
            }
        }
        if (root
                && !(JsonValues.isString(schema.get("$schema"))
                        && schema.get("$schema").getAsString().equals(DRAFT_07))) {
            out.add(new SchemaIssue(pointer, "$schema", "draft07_required"));
        }
        if (keys.contains("$ref")) {
            if (!JsonValues.isString(schema.get("$ref"))) {
                out.add(new SchemaIssue(pointer, "$ref", "ref_not_string"));
            }
            if (!REF_SIBLINGS.containsAll(keys)) {
                out.add(new SchemaIssue(pointer, "$ref", "ref_with_siblings"));
            }
        }
    }

    private static void values(
            JsonObject schema, List<String> keys, String pointer, List<SchemaIssue> out) {
        for (String key : keys) {
            if (COUNTS.contains(key) && !isCount(schema.get(key))) {
                out.add(new SchemaIssue(pointer, key, "not_a_count"));
            }
        }
        for (String key : keys) {
            if (BOUNDS.contains(key) && !JsonValues.isNumber(schema.get(key))) {
                out.add(new SchemaIssue(pointer, key, "not_a_number"));
            }
        }
        for (String key : keys) {
            if (TEXTS.contains(key) && !JsonValues.isString(schema.get(key))) {
                out.add(new SchemaIssue(pointer, key, "not_a_string"));
            }
        }
        if (keys.contains("uniqueItems") && !JsonValues.isBoolean(schema.get("uniqueItems"))) {
            out.add(new SchemaIssue(pointer, "uniqueItems", "not_a_boolean"));
        }
        if (keys.contains("type") && !isTypeList(schema.get("type"))) {
            out.add(new SchemaIssue(pointer, "type", "invalid_type"));
        }
        if (keys.contains("required") && !JsonValues.isUniqueStringArray(schema.get("required"))) {
            out.add(new SchemaIssue(pointer, "required", "invalid_required"));
        }
        if (keys.contains("enum")
                && !(schema.get("enum") instanceof JsonArray values && !values.isEmpty())) {
            out.add(new SchemaIssue(pointer, "enum", "invalid_enum"));
        }
        if (keys.contains("format")
                && !(JsonValues.isString(schema.get("format"))
                        && SchemaFormats.FORMATS.contains(schema.get("format").getAsString()))) {
            out.add(new SchemaIssue(pointer, "format", "unsupported_format"));
        }
        if (keys.contains("pattern")) {
            String problem = SchemaPatterns.problem(schema.get("pattern"));
            if (problem != null) {
                out.add(new SchemaIssue(pointer, "pattern", problem));
            }
        }
    }

    private static boolean isCount(JsonElement value) {
        if (!JsonValues.isNumber(value)) {
            return false;
        }
        double d = JsonValues.number(value);
        return d >= 0 && JsonValues.isWhole(d);
    }

    private static boolean isTypeList(JsonElement value) {
        List<String> names = new ArrayList<>();
        if (JsonValues.isString(value)) {
            names.add(value.getAsString());
        } else if (JsonValues.isUniqueStringArray(value) && !value.getAsJsonArray().isEmpty()) {
            value.getAsJsonArray().forEach(item -> names.add(item.getAsString()));
        } else {
            return false;
        }
        return TYPES.containsAll(names);
    }
}
