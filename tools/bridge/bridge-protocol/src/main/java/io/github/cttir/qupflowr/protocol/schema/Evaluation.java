/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.cttir.qupflowr.protocol.schema.SchemaValidator.Resolved;
import io.github.cttir.qupflowr.protocol.schema.SchemaValidator.SchemaRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One validation run: evaluates schema nodes against an instance and collects errors in the order
 * of the R validator (type, enum, const; the keywords of the instance type; allOf, anyOf, oneOf,
 * not).
 */
final class Evaluation {

    private static final List<String> NUMBER_BOUNDS =
            List.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum");

    private final SchemaValidator validator;
    private final String refRoot;
    private final Set<String> stack = new HashSet<>();

    Evaluation(SchemaValidator validator, String refRoot) {
        this.validator = validator;
        this.refRoot = refRoot;
    }

    List<ValidationError> check(JsonElement instance, JsonElement node, String path, SchemaRef at) {
        if (JsonValues.isBoolean(node)) {
            if (node.getAsBoolean()) {
                return List.of();
            }
            return List.of(error(path, "false", at, "No value is allowed here."));
        }
        if (node == null || node.isJsonNull() || node.isJsonArray()) {
            // Only reachable through a $ref to a non-schema value; it has no keywords.
            return List.of();
        }
        if (!(node instanceof JsonObject schema)) {
            throw new SchemaException(
                    "schema_not_object", SchemaValidator.name(at.file()), "'" + at.pointer() + "'");
        }
        if (schema.has("$ref") && JsonValues.isString(schema.get("$ref"))) {
            return checkRef(instance, schema.get("$ref").getAsString(), path, at);
        }
        String kind = JsonValues.kind(instance);
        List<ValidationError> errors = new ArrayList<>();
        generic(instance, kind, schema, path, at, errors);
        switch (kind) {
            case "object" -> object(instance.getAsJsonObject(), schema, path, at, errors);
            case "array" -> array(instance.getAsJsonArray(), schema, path, at, errors);
            case "number" -> number(JsonValues.number(instance), schema, path, at, errors);
            case "string" -> string(instance.getAsString(), schema, path, at, errors);
            default -> {
                // null and boolean instances have no type-specific keywords
            }
        }
        combinators(instance, schema, path, at, errors);
        return errors;
    }

    private List<ValidationError> checkRef(
            JsonElement instance, String ref, String path, SchemaRef at) {
        Resolved target = validator.resolve(ref, at, refRoot);
        String key = target.file() + "#" + target.pointer() + "@" + path;
        if (!stack.add(key)) {
            throw new SchemaException("circular_ref", SchemaValidator.name(at.file()), ref);
        }
        try {
            SchemaRef location = new SchemaRef(target.file(), target.pointer());
            return check(instance, target.node(), path, location);
        } finally {
            stack.remove(key);
        }
    }

    private static void generic(
            JsonElement instance,
            String kind,
            JsonObject schema,
            String path,
            SchemaRef at,
            List<ValidationError> errors) {
        if (schema.has("type")) {
            List<String> types = typeNames(schema.get("type"));
            boolean whole =
                    kind.equals("number") && JsonValues.isWhole(JsonValues.number(instance));
            boolean ok = types.contains(kind) || (whole && types.contains("integer"));
            if (!ok) {
                String actual = whole ? "integer" : kind;
                String expected = String.join(" | ", types);
                errors.add(
                        error(
                                path,
                                "type",
                                at,
                                "Expected " + expected + ", found " + actual + "."));
            }
        }
        if (schema.has("enum")) {
            List<String> allowed = new ArrayList<>();
            for (JsonElement value : schema.get("enum").getAsJsonArray()) {
                allowed.add(JsonValues.key(value));
            }
            if (!allowed.contains(JsonValues.key(instance))) {
                String text = String.join(", ", allowed);
                errors.add(
                        error(
                                path,
                                "enum",
                                at,
                                "The value is not allowed; allowed: " + text + "."));
            }
        }
        if (schema.has("const")) {
            String expected = JsonValues.key(schema.get("const"));
            if (!expected.equals(JsonValues.key(instance))) {
                errors.add(error(path, "const", at, "The value must be " + expected + "."));
            }
        }
    }

    private static List<String> typeNames(JsonElement type) {
        List<String> names = new ArrayList<>();
        if (type.isJsonArray()) {
            type.getAsJsonArray().forEach(item -> names.add(item.getAsString()));
        } else {
            names.add(type.getAsString());
        }
        return names;
    }

    private void object(
            JsonObject instance,
            JsonObject schema,
            String path,
            SchemaRef at,
            List<ValidationError> errors) {
        if (schema.get("required") instanceof JsonArray required) {
            for (JsonElement property : required) {
                String name = property.getAsString();
                if (!instance.has(name)) {
                    errors.add(
                            error(
                                    path,
                                    "required",
                                    at,
                                    "Required property `" + name + "` is missing."));
                }
            }
        }
        JsonObject properties =
                schema.get("properties") instanceof JsonObject p ? p : new JsonObject();
        JsonElement additional = schema.get("additionalProperties");
        for (Map.Entry<String, JsonElement> member : instance.entrySet()) {
            String name = member.getKey();
            String child = path + "/" + JsonValues.escape(name);
            if (properties.has(name)) {
                SchemaRef location = at.child("properties", name);
                errors.addAll(check(member.getValue(), properties.get(name), child, location));
            } else if (JsonValues.isFalse(additional)) {
                errors.add(
                        error(
                                child,
                                "additionalProperties",
                                at,
                                "Property `" + name + "` is not allowed here."));
            } else if (additional != null && !JsonValues.isTrue(additional)) {
                SchemaRef location = at.child("additionalProperties");
                errors.addAll(check(member.getValue(), additional, child, location));
            }
        }
    }

    private void array(
            JsonArray instance,
            JsonObject schema,
            String path,
            SchemaRef at,
            List<ValidationError> errors) {
        int n = instance.size();
        if (schema.has("minItems")) {
            double limit = JsonValues.number(schema.get("minItems"));
            if (n < limit) {
                errors.add(
                        error(
                                path,
                                "minItems",
                                at,
                                "At least "
                                        + JsonValues.displayNumber(limit)
                                        + " items are required."));
            }
        }
        if (schema.has("maxItems")) {
            double limit = JsonValues.number(schema.get("maxItems"));
            if (n > limit) {
                errors.add(
                        error(
                                path,
                                "maxItems",
                                at,
                                "At most "
                                        + JsonValues.displayNumber(limit)
                                        + " items are allowed."));
            }
        }
        if (JsonValues.isTrue(schema.get("uniqueItems")) && n > 1) {
            Map<String, Integer> first = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Integer earlier = first.putIfAbsent(JsonValues.key(instance.get(i)), i);
                if (earlier != null) {
                    errors.add(
                            error(
                                    path,
                                    "uniqueItems",
                                    at,
                                    "Items " + earlier + " and " + i + " are equal."));
                    break;
                }
            }
        }
        if (schema.has("items")) {
            SchemaRef location = at.child("items");
            for (int i = 0; i < n; i++) {
                errors.addAll(
                        check(instance.get(i), schema.get("items"), path + "/" + i, location));
            }
        }
    }

    private static void number(
            double value,
            JsonObject schema,
            String path,
            SchemaRef at,
            List<ValidationError> errors) {
        for (String keyword : NUMBER_BOUNDS) {
            if (!schema.has(keyword)) {
                continue;
            }
            double limit = JsonValues.number(schema.get(keyword));
            String shown = JsonValues.displayNumber(limit);
            String message =
                    switch (keyword) {
                        case "minimum" ->
                                value < limit ? "The value must be at least " + shown + "." : null;
                        case "maximum" ->
                                value > limit ? "The value must be at most " + shown + "." : null;
                        case "exclusiveMinimum" ->
                                value <= limit
                                        ? "The value must be greater than " + shown + "."
                                        : null;
                        default ->
                                value >= limit
                                        ? "The value must be less than " + shown + "."
                                        : null;
                    };
            if (message != null) {
                errors.add(error(path, keyword, at, message));
            }
        }
    }

    private static void string(
            String value,
            JsonObject schema,
            String path,
            SchemaRef at,
            List<ValidationError> errors) {
        int length = value.codePointCount(0, value.length());
        if (schema.has("minLength")) {
            double limit = JsonValues.number(schema.get("minLength"));
            if (length < limit) {
                String shown = JsonValues.displayNumber(limit);
                errors.add(
                        error(
                                path,
                                "minLength",
                                at,
                                "At least " + shown + " characters are required."));
            }
        }
        if (schema.has("maxLength")) {
            double limit = JsonValues.number(schema.get("maxLength"));
            if (length > limit) {
                String shown = JsonValues.displayNumber(limit);
                errors.add(
                        error(
                                path,
                                "maxLength",
                                at,
                                "At most " + shown + " characters are allowed."));
            }
        }
        if (schema.has("pattern")) {
            String pattern = schema.get("pattern").getAsString();
            if (!SchemaPatterns.compile(pattern).matcher(value).find()) {
                errors.add(
                        error(
                                path,
                                "pattern",
                                at,
                                "The text does not match the pattern " + pattern + "."));
            }
        }
        if (schema.has("format")) {
            String format = schema.get("format").getAsString();
            if (!SchemaFormats.check(value, format)) {
                errors.add(
                        error(
                                path,
                                "format",
                                at,
                                "The text does not have the format " + format + "."));
            }
        }
    }

    private void combinators(
            JsonElement instance,
            JsonObject schema,
            String path,
            SchemaRef at,
            List<ValidationError> errors) {
        if (schema.get("allOf") instanceof JsonArray branches) {
            for (int j = 0; j < branches.size(); j++) {
                SchemaRef location = at.child("allOf", Integer.toString(j));
                errors.addAll(check(instance, branches.get(j), path, location));
            }
        }
        if (schema.get("anyOf") instanceof JsonArray branches) {
            boolean matched = false;
            for (int j = 0; j < branches.size() && !matched; j++) {
                SchemaRef location = at.child("anyOf", Integer.toString(j));
                matched = check(instance, branches.get(j), path, location).isEmpty();
            }
            if (!matched) {
                errors.add(error(path, "anyOf", at, "None of the alternatives matches."));
            }
        }
        if (schema.get("oneOf") instanceof JsonArray branches) {
            List<Integer> passing = new ArrayList<>();
            for (int j = 0; j < branches.size(); j++) {
                SchemaRef location = at.child("oneOf", Integer.toString(j));
                if (check(instance, branches.get(j), path, location).isEmpty()) {
                    passing.add(j);
                }
            }
            if (passing.size() != 1) {
                String matched =
                        passing.isEmpty()
                                ? "-"
                                : passing.stream()
                                        .map(String::valueOf)
                                        .collect(Collectors.joining(", "));
                errors.add(
                        error(
                                path,
                                "oneOf",
                                at,
                                "Exactly one alternative must match; matching: " + matched + "."));
            }
        }
        if (schema.has("not")) {
            SchemaRef location = at.child("not");
            if (check(instance, schema.get("not"), path, location).isEmpty()) {
                errors.add(error(path, "not", at, "The value matches an excluded schema."));
            }
        }
    }

    private static ValidationError error(
            String path, String keyword, SchemaRef at, String message) {
        return new ValidationError(path, keyword, message, at.keywordPath(keyword));
    }
}
