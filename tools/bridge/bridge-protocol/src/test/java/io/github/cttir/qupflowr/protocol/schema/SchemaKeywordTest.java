/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.check;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.schemaText;
import static io.github.cttir.qupflowr.protocol.schema.SchemaTestSupport.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * Keyword semantics with small inline schemas. The cases and expected errors are the ones of the R
 * package's {@code tests/testthat/test-json-schema.R}, so both validators are held to the same
 * table.
 */
class SchemaKeywordTest {

    @TempDir Path dir;

    private SchemaValidator validator;

    @BeforeEach
    void createValidator() {
        validator = new SchemaValidator(dir);
    }

    private Path schema(String name, String... members) {
        return write(dir, name, schemaText(members));
    }

    private List<String> errors(String instance, Path schema) {
        return check(validator, instance, schema);
    }

    @Test
    void typeDistinguishesIntegersNumbersNullAndContainers() {
        Path integer = schema("a.json", "\"type\":\"integer\"");
        assertEquals(List.of(), errors("1", integer));
        assertEquals(List.of(), errors("1.0", integer));
        assertEquals(List.of(" type"), errors("1.5", integer));
        assertEquals(List.of(" type"), errors("\"1\"", integer));
        Path multi = schema("b.json", "\"type\":[\"string\",\"null\"]");
        assertEquals(List.of(), errors("null", multi));
        assertEquals(List.of(" type"), errors("[]", multi));
        Path object = schema("c.json", "\"type\":\"object\"");
        assertEquals(List.of(), errors("{}", object));
        assertEquals(List.of(" type"), errors("[]", object));
        Path array = schema("d.json", "\"type\":\"array\"");
        assertEquals(List.of(" type"), errors("{}", array));
        Path bool = schema("e.json", "\"type\":\"boolean\"");
        assertEquals(List.of(), errors("true", bool));
        ValidationReport report = validator.validate("2", schema("f.json", "\"type\":\"string\""));
        assertEquals("Expected string, found integer.", report.errors().get(0).message());
    }

    @Test
    void objectKeywordsReportAtTheObjectAndAtExtraMembers() {
        Path s =
                schema(
                        "o.json",
                        "\"type\":\"object\",\"required\":[\"a\",\"b\"],",
                        "\"properties\":{\"a\":{\"type\":\"string\"},",
                        "\"x/y\":{\"type\":\"integer\"}},",
                        "\"additionalProperties\":false");
        assertEquals(List.of("/b additionalProperties"), errors("{\"a\":\"v\",\"b\":1}", s));
        assertEquals(List.of(" required", "/a type"), errors("{\"a\":1}", s));
        assertEquals(
                List.of("/b additionalProperties", "/x~1y type"),
                errors("{\"a\":\"v\",\"b\":1,\"x/y\":0.5}", s));
        Path typed =
                schema("p.json", "\"additionalProperties\":{\"type\":\"integer\",\"minimum\":0}");
        assertEquals(List.of("/k minimum"), errors("{\"k\":-1,\"j\":2}", typed));
        assertEquals(List.of(" type"), errors("[1]", s));
        ValidationReport report = validator.validate("{}", s);
        assertEquals(2, report.errors().size());
        assertEquals(
                "p.json#/additionalProperties/minimum",
                validator.validate("{\"k\":-1}", typed).errors().get(0).schemaPath());
    }

    @Test
    void arrayKeywords() {
        Path s =
                schema(
                        "a.json",
                        "\"type\":\"array\",\"minItems\":1,\"maxItems\":3,\"uniqueItems\":true,",
                        "\"items\":{\"type\":\"number\"}");
        assertEquals(List.of(), errors("[1,2]", s));
        assertEquals(List.of(" minItems"), errors("[]", s));
        assertEquals(List.of(" maxItems"), errors("[1,2,3,4]", s));
        assertEquals(List.of(" uniqueItems"), errors("[1,1.0]", s));
        assertEquals(List.of("/1 type"), errors("[1,\"a\"]", s));
        Path objects = schema("b.json", "\"uniqueItems\":true");
        assertEquals(
                List.of(" uniqueItems"),
                errors("[{\"a\":1,\"b\":[1]},{\"b\":[1],\"a\":1.0}]", objects));
        assertEquals(
                List.of(), errors("[{\"a\":1},{\"a\":\"1\"},[1],[\"1\"],null,false]", objects));
        assertEquals(List.of(), errors("[0,-0.5,-0]", schema("c.json", "\"type\":\"array\"")));
        assertEquals(List.of(" uniqueItems"), errors("[0,-0.0]", objects));
        ValidationReport report = validator.validate("[\"a\",\"b\",\"a\"]", objects);
        assertEquals("Items 0 and 2 are equal.", report.errors().get(0).message());
    }

    @Test
    void enumAndConstCompareJsonValues() {
        Path s = schema("e.json", "\"enum\":[1,\"1\",null,{\"a\":[true]}]");
        assertEquals(List.of(), errors("1.0", s));
        assertEquals(List.of(), errors("\"1\"", s));
        assertEquals(List.of(), errors("null", s));
        assertEquals(List.of(), errors("{\"a\":[true]}", s));
        assertEquals(List.of(" enum"), errors("{\"a\":[false]}", s));
        assertEquals(List.of(" enum"), errors("2", s));
        Path constNull = schema("c.json", "\"const\":null");
        assertEquals(List.of(), errors("null", constNull));
        assertEquals(List.of(" const"), errors("0", constNull));
        Path constObject = schema("d.json", "\"const\":{\"b\":1,\"a\":2}");
        assertEquals(List.of(), errors("{\"a\":2,\"b\":1}", constObject));
        Path strings = schema("f.json", "\"enum\":[\"a\\\"b\",\"c\"]");
        assertEquals(List.of(), errors("\"a\\\"b\"", strings));
        assertEquals(List.of(" enum"), errors("[\"a\\\"b\"]", strings));
    }

    @Test
    void numericBounds() {
        Path s =
                schema(
                        "n.json",
                        "\"minimum\":0,\"maximum\":10,\"exclusiveMinimum\":-1,",
                        "\"exclusiveMaximum\":10.5");
        assertEquals(List.of(), errors("0", s));
        assertEquals(List.of(), errors("10", s));
        assertEquals(List.of(" minimum"), errors("-0.5", s));
        assertEquals(List.of(" maximum"), errors("10.25", s));
        assertEquals(List.of(" exclusiveMinimum", " minimum"), errors("-1", s));
        assertEquals(List.of(" exclusiveMaximum", " maximum"), errors("10.5", s));
        assertEquals(List.of(), errors("\"x\"", s));
        assertEquals(List.of(), errors("-0.0", s));
        assertEquals(List.of(), errors("1e-400", s));
    }

    @Test
    void stringLengthsCountCodePointsAndPatternsUseEcmaAnchors() {
        Path s =
                schema(
                        "s.json",
                        "\"minLength\":2,\"maxLength\":3,",
                        "\"pattern\":\"^[a-z\\u00e4\\u00f6\\u00fc]+$\"");
        assertEquals(List.of(), errors("\"\\u00e4\\u00f6\\u00fc\"", s));
        assertEquals(List.of(" minLength"), errors("\"a\"", s));
        assertEquals(List.of(" maxLength"), errors("\"abcd\"", s));
        assertEquals(List.of(" pattern"), errors("\"ab\\n\"", s));
        assertEquals(List.of(" pattern"), errors("\"AB\"", s));
        Path unanchored = schema("u.json", "\"pattern\":\"b\"");
        assertEquals(List.of(), errors("\"abc\"", unanchored));
        Path emoji = schema("m.json", "\"maxLength\":2,\"pattern\":\"^[^:]{2}$\"");
        assertEquals(List.of(), errors("\"\\ud83d\\ude00\\ud83d\\ude00\"", emoji));
        assertEquals(List.of(" maxLength", " pattern"), errors("\"\\ud83d\\ude00ab\"", emoji));
        Path escapedDollar = schema("d.json", "\"pattern\":\"^a\\\\$\"");
        assertEquals(List.of(), errors("\"a$\"", escapedDollar));
        assertEquals(List.of(" pattern"), errors("\"a\"", escapedDollar));
        Path backslashEnd = schema("b.json", "\"pattern\":\"^a\\\\\\\\$\"");
        assertEquals(List.of(), errors("\"a\\\\\"", backslashEnd));
        assertEquals(List.of(" pattern"), errors("\"a\\\\\\n\"", backslashEnd));
    }

    @Test
    void formatsAreAsserted() {
        Path uuid = schema("f1.json", "\"format\":\"uuid\"");
        String lower = "8f6589c0-c587-4bfa-b740-8cab06bc8ce1";
        assertEquals(List.of(), errors(quoted(lower), uuid));
        assertEquals(List.of(" format"), errors(quoted(lower.toUpperCase()), uuid));
        assertEquals(List.of(" format"), errors(quoted(lower.replace("-", "")), uuid));
        assertEquals(List.of(" format"), errors(quoted(lower + "\\n"), uuid));
        assertEquals(List.of(), errors("12", uuid));
        Path sha = schema("f2.json", "\"format\":\"sha256\"");
        assertEquals(List.of(), errors(quoted("ab".repeat(32)), sha));
        assertEquals(List.of(" format"), errors(quoted("ab".repeat(31)), sha));
        assertEquals(List.of(" format"), errors(quoted("AB".repeat(32)), sha));
        Path dateTime = schema("f3.json", "\"format\":\"date-time\"");
        for (String valid :
                List.of(
                        "2024-02-29T23:59:59Z",
                        "2026-09-15T08:00:00.123456789+02:00",
                        "2000-02-29T00:00:00-00:00",
                        "0000-02-29T00:00:00Z",
                        "2024-02-28T23:59:59-23:59")) {
            assertEquals(List.of(), errors(quoted(valid), dateTime), valid);
        }
        for (String invalid :
                List.of(
                        "2023-02-29T00:00:00Z",
                        "1900-02-29T00:00:00Z",
                        "2026-13-01T00:00:00Z",
                        "2026-00-01T00:00:00Z",
                        "2026-09-00T00:00:00Z",
                        "2026-09-15T24:00:00Z",
                        "2026-09-15T23:60:00Z",
                        "2026-09-15T23:59:60Z",
                        "2026-09-15 08:00:00Z",
                        "2026-09-15T08:00:00",
                        "2026-09-15t08:00:00z",
                        "2026-09-15T08:00:00+24:00",
                        "2026-09-15T08:00:00+00:60",
                        "2026-04-31T00:00:00Z",
                        "2024-02-29T23:59:59Z\\n",
                        "\\u0662024-02-29T23:59:59Z")) {
            assertEquals(List.of(" format"), errors(quoted(invalid), dateTime), invalid);
        }
        Path uri = schema("f4.json", "\"format\":\"uri-reference\"");
        for (String valid :
                List.of(
                        "file:///data/images/slide%201.tif",
                        "images/a.tif?x=1#frag",
                        "",
                        "Bilder/Gr\\u00f6\\u00dfe.tif",
                        "https://example.org/a_b-c~d",
                        "a\\u00a0b",
                        "a\\u2007b",
                        "a\\u202fb",
                        "a\\u200bb",
                        "a\\ufeffb",
                        "a\\ud83d\\ude00b")) {
            assertEquals(List.of(), errors(quoted(valid), uri), valid);
        }
        for (String invalid :
                List.of(
                        "images/slide 1.tif",
                        "a%zzb",
                        "a%2",
                        "a<b",
                        "a\\\\b",
                        "a\\tb",
                        "{x}",
                        "a\\u007fb",
                        "a\\u0085b",
                        "a\\u009fb",
                        "a\\u1680b",
                        "a\\u2000b",
                        "a\\u2006b",
                        "a\\u2008b",
                        "a\\u200ab",
                        "a\\u2028b",
                        "a\\u2029b",
                        "a\\u205fb",
                        "a\\u3000b",
                        "a^b",
                        "a`b",
                        "a|b",
                        "a\\\"b")) {
            assertEquals(List.of(" format"), errors(quoted(invalid), uri), invalid);
        }
    }

    @Test
    void combinatorsReportOneErrorAtTheirLocation() {
        Path one =
                schema(
                        "o.json",
                        "\"oneOf\":[{\"type\":\"integer\"},{\"minimum\":2},",
                        "{\"type\":\"string\",\"minLength\":5}]");
        assertEquals(List.of(), errors("1", one));
        assertEquals(List.of(" oneOf"), errors("2", one));
        assertEquals(List.of(), errors("\"abc\"", one));
        assertEquals(List.of(" oneOf"), errors("\"abcdef\"", one));
        ValidationReport report = validator.validate("2", one);
        assertEquals(
                "Exactly one alternative must match; matching: 0, 1.",
                report.errors().get(0).message());
        Path any = schema("a.json", "\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}]");
        assertEquals(List.of(), errors("null", any));
        assertEquals(List.of(" anyOf"), errors("\"x\"", any));
        Path all =
                schema(
                        "l.json",
                        "\"allOf\":[{\"type\":\"integer\"},{\"minimum\":3},{\"maximum\":1}]");
        assertEquals(List.of(" maximum", " minimum"), errors("2", all));
        assertEquals(
                List.of("l.json#/allOf/2/maximum", "l.json#/allOf/1/minimum"),
                validator.validate("2", all).errors().stream()
                        .map(ValidationError::schemaPath)
                        .toList());
        Path negation = schema("n.json", "\"not\":{\"required\":[\"token\"]}");
        assertEquals(List.of(), errors("{\"a\":1}", negation));
        assertEquals(List.of(" not"), errors("{\"token\":1}", negation));
        assertEquals(
                "n.json#/not",
                validator.validate("{\"token\":1}", negation).errors().get(0).schemaPath());
    }

    @Test
    void booleanSchemas() {
        Path s = schema("b.json", "\"properties\":{\"yes\":true,\"no\":false},\"items\":false");
        assertEquals(List.of(), errors("{\"yes\":1}", s));
        assertEquals(List.of("/no false"), errors("{\"no\":1}", s));
        assertEquals(List.of(), errors("[]", s));
        assertEquals(List.of("/0 false"), errors("[1]", s));
        assertEquals(
                "b.json#/items/false", validator.validate("[1]", s).errors().get(0).schemaPath());
    }

    @Test
    void duplicateErrorsAreReportedOnceAndSorted() {
        Path s =
                schema(
                        "r.json",
                        "\"type\":\"object\",\"required\":[\"z\"],",
                        "\"properties\":{\"b\":{\"type\":\"string\"},",
                        "\"a\":{\"type\":\"string\"}},",
                        "\"allOf\":[{\"$ref\":\"#/definitions/s\"},",
                        "{\"$ref\":\"#/definitions/s\"}],",
                        "\"definitions\":{\"s\":{\"required\":[\"q\"]}}");
        ValidationReport report = validator.validate("{\"b\":1,\"a\":2}", s);
        assertEquals(
                List.of(" required", " required", "/a type", "/b type"),
                report.errors().stream().map(e -> e.path() + " " + e.keyword()).toList());
        assertEquals(
                List.of("r.json#/required", "r.json#/definitions/s/required"),
                report.errors().subList(0, 2).stream().map(ValidationError::schemaPath).toList());
        assertTrue(report.toString().startsWith("Invalid against r.json: 4 errors."));
        assertEquals(
                "Valid against r.json.", validator.validate("{\"z\":0,\"q\":0}", s).toString());
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }
}
