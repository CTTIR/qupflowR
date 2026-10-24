/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import com.google.gson.JsonElement;

import io.github.cttir.qupflowr.protocol.json.CanonicalJsonException;
import io.github.cttir.qupflowr.protocol.json.CanonicalJsonReader;
import io.github.cttir.qupflowr.protocol.json.ProtocolJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * JSON Schema Draft-07 subset validator with the rules of the R package ({@code
 * qp_schema_validate()}, decision D022).
 *
 * <p><b>Keywords.</b> {@code $schema $id $ref definitions title description type properties
 * required additionalProperties items minItems maxItems uniqueItems enum const minimum maximum
 * exclusiveMinimum exclusiveMaximum minLength maxLength pattern format oneOf anyOf allOf not}. A
 * schema with any other keyword, or with a value these rules do not support, is rejected when it is
 * loaded ({@link SchemaException}, reason as listed by {@link #lint(Path)}).
 *
 * <p><b>References.</b> {@code $ref} is a fragment ({@code #/definitions/x}) or a relative file
 * path with an optional fragment, resolved against the directory of the referring file. The file
 * must stay inside the schema root, lexically and after resolving symbolic links. URLs, absolute
 * paths and reverse solidus characters are rejected; nothing is fetched. Pointer segments are
 * percent-decoded, then {@code ~1} and {@code ~0} are unescaped.
 *
 * <p><b>Values.</b> Numbers are compared as doubles; {@code integer} accepts any number without a
 * fractional part. {@code enum}, {@code const} and {@code uniqueItems} compare JSON values (numbers
 * by value, objects independent of member order). String lengths count code points. {@code pattern}
 * is not anchored implicitly and a final {@code $} matches only at the end of the input. Formats
 * are asserted: {@code uuid}, {@code sha256}, {@code date-time}, {@code uri-reference}.
 *
 * <p><b>Errors.</b> Every failing keyword yields one {@link ValidationError} at the JSON pointer of
 * the instance location. {@code required} is reported at the object, {@code additionalProperties:
 * false} at the extra member and a {@code false} schema with the keyword {@code false}. {@code
 * oneOf}, {@code anyOf} and {@code not} report a single error and none of their branch errors;
 * {@code allOf} branches and {@code $ref} targets report their errors as they are. Keywords that do
 * not apply to the instance type are ignored.
 *
 * <p>Instances are read with {@link CanonicalJsonReader}, so invalid JSON text raises {@link
 * CanonicalJsonException} before the schema is loaded. Instances of this class are thread-safe;
 * parsed schema documents are cached per file and reloaded when size or modification time change.
 */
public final class SchemaValidator {

    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    private static final Pattern ARRAY_INDEX = Pattern.compile("0|[1-9][0-9]*");

    private final Path root;
    private final ConcurrentHashMap<Path, CachedDocument> documents = new ConcurrentHashMap<>();

    /**
     * Creates a validator for a schema tree.
     *
     * @param schemaRoot directory that contains the schema sets, for example {@code inst/schema}
     * @throws IllegalArgumentException if the directory does not exist
     */
    public SchemaValidator(Path schemaRoot) {
        Objects.requireNonNull(schemaRoot, "schemaRoot");
        try {
            this.root = schemaRoot.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Schema root not found: " + schemaRoot, e);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Schema root is not a directory: " + schemaRoot);
        }
    }

    /**
     * Returns the real path of the schema root.
     *
     * @return schema root
     */
    public Path root() {
        return root;
    }

    /**
     * Returns the list of supported keywords.
     *
     * @return keywords in the order of decision D022
     */
    public static List<String> supportedKeywords() {
        return SchemaLint.KEYWORDS;
    }

    /**
     * Returns the list of supported formats.
     *
     * @return {@code uuid}, {@code date-time}, {@code sha256} and {@code uri-reference}
     */
    public static List<String> supportedFormats() {
        return SchemaFormats.FORMATS;
    }

    /**
     * Locates a schema of a set by name, like {@code qp_schema_path()} in R.
     *
     * @param name schema name without extension, for example {@code health}
     * @param set schema set directory, for example {@code protocol}
     * @param version major version directory, for example {@code v1}
     * @return {@code <root>/<set>/<version>/<name>.schema.json}
     * @throws IllegalArgumentException if a segment is not lower-case letters, digits and hyphens
     * @throws SchemaException with reason {@code not_found} if the file does not exist
     */
    public Path schemaPath(String name, String set, String version) {
        for (String segment : List.of(name, set, version)) {
            if (segment == null || !SCHEMA_NAME.matcher(segment).matches()) {
                throw new IllegalArgumentException("Invalid schema name segment: " + segment);
            }
        }
        String file = name + ".schema.json";
        Path path = root.resolve(set).resolve(version).resolve(file);
        if (!Files.isRegularFile(path)) {
            throw new SchemaException("not_found", file, null);
        }
        return path;
    }

    /**
     * Validates JSON text.
     *
     * @param json JSON text
     * @param schemaFile schema file
     * @return the report
     * @throws CanonicalJsonException if the text is not acceptable JSON
     * @throws SchemaException if the schema is unsupported, invalid or unresolvable
     */
    public ValidationReport validate(String json, Path schemaFile) {
        Target target = target(schemaFile);
        return evaluate(CanonicalJsonReader.read(json), target);
    }

    /**
     * Validates JSON text given as UTF-8 bytes.
     *
     * @param json JSON text as UTF-8 bytes
     * @param schemaFile schema file
     * @return the report
     * @throws CanonicalJsonException if the text is not acceptable JSON
     * @throws SchemaException if the schema is unsupported, invalid or unresolvable
     */
    public ValidationReport validate(byte[] json, Path schemaFile) {
        Target target = target(schemaFile);
        return evaluate(CanonicalJsonReader.read(json), target);
    }

    /**
     * Validates a JSON value. The value is written with {@link ProtocolJson} and read back, so it
     * is subject to the same rules as JSON text (for example no empty member names).
     *
     * @param instance JSON value
     * @param schemaFile schema file
     * @return the report
     * @throws io.github.cttir.qupflowr.protocol.json.ProtocolJsonException if the value cannot be
     *     written or read as protocol JSON
     * @throws SchemaException if the schema is unsupported, invalid or unresolvable
     */
    public ValidationReport validate(JsonElement instance, Path schemaFile) {
        return validate(ProtocolJson.toJson(instance), schemaFile);
    }

    /**
     * Reads a schema document and returns its structural issues without throwing for them.
     *
     * @param schemaFile schema file
     * @return issues in discovery order; the first one is the reason reported on load
     * @throws SchemaException with reason {@code not_found} or {@code unreadable_json}
     */
    public List<SchemaIssue> lint(Path schemaFile) {
        Target target = target(schemaFile);
        return SchemaLint.lint(parse(target.file()));
    }

    // ------------------------------------------------------------------------ loading

    private record Target(Path file, String root) {}

    private record CachedDocument(long size, long modifiedNanos, JsonElement tree) {}

    /** Resolves the schema file; the reference root is this root if it contains the file. */
    private Target target(Path schemaFile) {
        Objects.requireNonNull(schemaFile, "schemaFile");
        Path file;
        try {
            file = schemaFile.toRealPath();
        } catch (IOException e) {
            throw new SchemaException("not_found", String.valueOf(schemaFile.getFileName()), null);
        }
        String rootText = root.toString();
        if (!file.toString().startsWith(rootText + "/")) {
            rootText = file.getParent().toString();
        }
        return new Target(file, rootText);
    }

    /** Parsed and linted schema document, cached by size and modification time. */
    JsonElement document(Path file) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new SchemaException("unreadable_json", name(file), "cannot read the file");
        }
        long size = attributes.size();
        long modified =
                attributes.lastModifiedTime().toInstant().getEpochSecond() * 1_000_000_000L
                        + attributes.lastModifiedTime().toInstant().getNano();
        CachedDocument cached = documents.get(file);
        if (cached != null && cached.size() == size && cached.modifiedNanos() == modified) {
            return cached.tree();
        }
        JsonElement tree = parse(file);
        List<SchemaIssue> issues = SchemaLint.lint(tree);
        if (!issues.isEmpty()) {
            SchemaIssue first = issues.get(0);
            throw new SchemaException(
                    first.problem(),
                    name(file),
                    "'" + first.keyword() + "' at '" + first.pointer() + "'");
        }
        documents.put(file, new CachedDocument(size, modified, tree));
        return tree;
    }

    private static JsonElement parse(Path file) {
        try {
            return CanonicalJsonReader.read(Files.readAllBytes(file));
        } catch (CanonicalJsonException e) {
            throw new SchemaException("unreadable_json", name(file), e.reason());
        } catch (IOException e) {
            throw new SchemaException("unreadable_json", name(file), "cannot read the file");
        }
    }

    static String name(Path file) {
        return String.valueOf(file.getFileName());
    }

    private ValidationReport evaluate(JsonElement instance, Target target) {
        JsonElement document = document(target.file());
        Evaluation evaluation = new Evaluation(this, target.root());
        List<ValidationError> raw =
                evaluation.check(instance, document, "", new SchemaRef(target.file(), ""));
        List<ValidationError> errors = new ArrayList<>(new LinkedHashSet<>(raw));
        errors.sort(
                Comparator.<ValidationError, String>comparing(
                                ValidationError::path, JsonValues::compareCodePoints)
                        .thenComparing(ValidationError::keyword, JsonValues::compareCodePoints));
        return new ValidationReport(name(target.file()), errors);
    }

    // ------------------------------------------------------------------------ references

    /** Location of a schema node: file and JSON pointer inside it. */
    record SchemaRef(Path file, String pointer) {

        SchemaRef child(String... segments) {
            StringBuilder out = new StringBuilder(pointer);
            for (String segment : segments) {
                out.append('/').append(JsonValues.escape(segment));
            }
            return new SchemaRef(file, out.toString());
        }

        String keywordPath(String keyword) {
            return name(file) + "#" + pointer + "/" + keyword;
        }
    }

    /** A resolved reference target. */
    record Resolved(JsonElement node, Path file, String pointer) {}

    /** Resolves a {@code $ref} value found at {@code from}. */
    Resolved resolve(String ref, SchemaRef from, String refRoot) {
        int hash = ref.indexOf('#');
        String filePart = hash >= 0 ? ref.substring(0, hash) : ref;
        String fragment = hash >= 0 ? ref.substring(hash + 1) : "";
        if (URI_SCHEME.matcher(filePart).find()
                || filePart.startsWith("/")
                || filePart.contains("\\")) {
            throw new SchemaException("non_local_ref", name(from.file()), ref);
        }
        Path file = from.file();
        if (!filePart.isEmpty()) {
            file = referencedFile(filePart, from, refRoot, ref);
        }
        JsonElement document = document(file);
        Optional<JsonElement> node = pointerGet(document, fragment);
        if (node.isEmpty()) {
            throw new SchemaException("unresolved_ref", name(from.file()), ref);
        }
        return new Resolved(node.get(), file, fragment);
    }

    private static Path referencedFile(
            String filePart, SchemaRef from, String refRoot, String ref) {
        String prefix = refRoot + "/";
        String collapsed = collapse(from.file().getParent().toString() + "/" + filePart);
        if (!collapsed.startsWith(prefix)) {
            throw new SchemaException("ref_outside_schema_root", name(from.file()), ref);
        }
        Path candidate = Path.of(collapsed);
        if (!Files.exists(candidate)) {
            throw new SchemaException("unresolved_ref", name(from.file()), ref);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new SchemaException("unresolved_ref", name(from.file()), ref);
        }
        if (!real.toString().startsWith(prefix)) {
            throw new SchemaException("ref_outside_schema_root", name(from.file()), ref);
        }
        return real;
    }

    /** Removes empty, {@code .} and {@code ..} segments lexically ({@code ..} stops at the top). */
    static String collapse(String path) {
        String[] parts = path.split("/");
        List<String> out = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
            } else {
                out.add(part);
            }
        }
        return (parts.length == 0 ? "" : parts[0]) + "/" + String.join("/", out);
    }

    /**
     * Looks up a JSON pointer ({@code ""} is the document). Segments are percent-decoded as UTF-8
     * and then unescaped; a malformed percent escape does not resolve.
     */
    static Optional<JsonElement> pointerGet(JsonElement document, String pointer) {
        if (pointer.isEmpty()) {
            return Optional.of(document);
        }
        if (!pointer.startsWith("/")) {
            return Optional.empty();
        }
        String[] raw = (pointer.substring(1) + "/").split("/", -1);
        JsonElement node = document;
        for (int i = 0; i < raw.length - 1; i++) {
            Optional<String> decoded = percentDecode(raw[i]);
            if (decoded.isEmpty()) {
                return Optional.empty();
            }
            String segment = decoded.get().replace("~1", "/").replace("~0", "~");
            if (node != null && node.isJsonObject()) {
                if (!node.getAsJsonObject().has(segment)) {
                    return Optional.empty();
                }
                node = node.getAsJsonObject().get(segment);
            } else if (node != null && node.isJsonArray()) {
                if (!ARRAY_INDEX.matcher(segment).matches() || segment.length() > 9) {
                    return Optional.empty();
                }
                int index = Integer.parseInt(segment);
                if (index >= node.getAsJsonArray().size()) {
                    return Optional.empty();
                }
                node = node.getAsJsonArray().get(index);
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(node);
    }

    private static Optional<String> percentDecode(String segment) {
        if (segment.indexOf('%') < 0) {
            return Optional.of(segment);
        }
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != '%') {
                out.write(bytes[i]);
                continue;
            }
            if (i + 2 >= bytes.length) {
                return Optional.empty();
            }
            int high = Character.digit(bytes[i + 1], 16);
            int low = Character.digit(bytes[i + 2], 16);
            if (high < 0 || low < 0) {
                return Optional.empty();
            }
            out.write(high * 16 + low);
            i += 2;
        }
        try {
            return Optional.of(
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(ByteBuffer.wrap(out.toByteArray()))
                            .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
