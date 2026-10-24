/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import com.google.gson.JsonElement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The portable {@code pattern} subset: a pattern must mean the same in ECMA-262, PCRE (R) and
 * {@code java.util.regex}. The checks are the ones of the R validator, applied to the pattern text.
 */
final class SchemaPatterns {

    /** Shorthand classes, anchors and back references after a reverse solidus. */
    private static final Pattern SHORTHAND = Pattern.compile("\\\\[dDwWsSbBpPhHvVkKQEAzZGRX1-9]");

    /** Groups other than non-capturing groups: look-around, inline flags, named groups. */
    private static final Pattern SPECIAL_GROUP = Pattern.compile("[(][?](?!:)");

    /** Possessive quantifiers. */
    private static final Pattern POSSESSIVE = Pattern.compile("[*+?}][+]");

    /** An escape sequence; the dot excludes only LF, as in PCRE. */
    private static final Pattern ESCAPE = Pattern.compile("\\\\.", Pattern.UNIX_LINES);

    /** A bracket expression, with a literal closing bracket allowed first. */
    private static final Pattern BRACKET = Pattern.compile("\\[\\^?\\]?[^\\]]*\\]");

    /** A final unescaped dollar sign. */
    private static final Pattern FINAL_DOLLAR = Pattern.compile("(^|[^\\\\])(\\\\\\\\)*[$]\\z");

    /** The start of a bounded quantifier. */
    private static final Pattern QUANTIFIER_START = Pattern.compile("\\{[0-9]+(,[0-9]*)?\\}");

    /** A numbered back reference in a pattern without escaped reverse solidus pairs. */
    private static final Pattern BACK_REFERENCE = Pattern.compile("\\\\([1-9][0-9]*)");

    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    private SchemaPatterns() {}

    /**
     * Returns the problem of a {@code pattern} keyword value, or null when it is portable.
     *
     * @param value keyword value
     * @return {@code not_a_string}, {@code pattern_does_not_compile}, {@code pattern_not_portable}
     *     or null
     */
    static String problem(JsonElement value) {
        if (!JsonValues.isString(value)) {
            return "not_a_string";
        }
        String pattern = value.getAsString();
        if (ambiguous(pattern)) {
            return "pattern_not_portable";
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "pattern_does_not_compile";
        }
        String bare = pattern.replace("\\\\", "");
        if (refusedByPcre(bare, compiled.matcher("").groupCount())) {
            return "pattern_does_not_compile";
        }
        if (SHORTHAND.matcher(bare).find()
                || SPECIAL_GROUP.matcher(bare).find()
                || POSSESSIVE.matcher(bare).find()) {
            return "pattern_not_portable";
        }
        String unescaped = ESCAPE.matcher(pattern).replaceAll("");
        String skeleton = BRACKET.matcher(unescaped).replaceAll("");
        int[] codePoints = skeleton.codePoints().toArray();
        int n = codePoints.length;
        boolean misplacedAnchor = false;
        for (int i = 1; i < n - 1; i++) {
            if (codePoints[i] == '$' || codePoints[i] == '^') {
                misplacedAnchor = true;
            }
        }
        if (skeleton.startsWith("$") || (n > 1 && skeleton.endsWith("^"))) {
            misplacedAnchor = true;
        }
        if (skeleton.indexOf('.') >= 0 || misplacedAnchor) {
            return "pattern_not_portable";
        }
        return null;
    }

    /**
     * Constructs that one of the engines refuses or reads differently, checked before compiling so
     * that R and Java report the same problem: the escapes {@code \0}, {@code \C}, {@code \g},
     * {@code \N} and {@code \o}; {@code [} inside a bracket expression (POSIX classes in PCRE,
     * unions in Java) and {@code &&} there (Java intersections); and an opening brace outside
     * brackets that does not start a bounded quantifier such as {@code {2}}, {@code {2,}} or {@code
     * {2,5}} (a literal in PCRE, an error in Java).
     */
    static boolean ambiguous(String pattern) {
        int n = pattern.length();
        boolean inBracket = false;
        int i = 0;
        while (i < n) {
            char c = pattern.charAt(i);
            char following = i + 1 < n ? pattern.charAt(i + 1) : '\0';
            if (c == '\\') {
                if ("0CgNo".indexOf(following) >= 0) {
                    return true;
                }
                i += 2;
                continue;
            }
            if (inBracket) {
                if (c == '[' || (c == '&' && following == '&')) {
                    return true;
                }
                inBracket = c != ']';
            } else if (c == '[') {
                inBracket = true;
                if (following == '^') {
                    i++;
                    following = i + 1 < n ? pattern.charAt(i + 1) : '\0';
                }
                if (following == ']') {
                    i++;
                }
            } else if (c == '{' && !QUANTIFIER_START.matcher(pattern).region(i, n).lookingAt()) {
                return true;
            }
            i++;
        }
        return false;
    }

    /**
     * Constructs that {@code java.util.regex} compiles but PCRE2 refuses, so that both validators
     * report {@code pattern_does_not_compile}: code unit escapes (reverse solidus and {@code u})
     * and back references to a group number below 10 that the pattern does not have.
     */
    private static boolean refusedByPcre(String bare, int groups) {
        if (bare.contains("\\u")) {
            return true;
        }
        Matcher m = BACK_REFERENCE.matcher(bare);
        while (m.find()) {
            String digits = m.group(1);
            if (digits.length() == 1 && Integer.parseInt(digits) > groups) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles a pattern with ECMA-262 end-of-input semantics: a final unescaped {@code $} matches
     * only at the end of the input (evaluated as {@code \z}), not before a final line terminator.
     *
     * @param pattern portable pattern
     * @return compiled pattern for {@link java.util.regex.Matcher#find()}
     */
    static Pattern compile(String pattern) {
        return COMPILED.computeIfAbsent(pattern, SchemaPatterns::compileUncached);
    }

    private static Pattern compileUncached(String pattern) {
        String ecma = pattern;
        if (FINAL_DOLLAR.matcher(pattern).find()) {
            ecma = pattern.substring(0, pattern.length() - 1) + "\\z";
        }
        return Pattern.compile(ecma);
    }
}
