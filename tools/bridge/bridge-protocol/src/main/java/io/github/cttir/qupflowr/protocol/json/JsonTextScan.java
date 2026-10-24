/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import java.util.Arrays;

/**
 * The string literals of a JSON text, found in one linear pass. The checks that run before parsing
 * use this scan instead of a backtracking pattern that matches whole literals, which needs
 * quadratic time on an unterminated string full of escaped quotation marks. The R package uses the
 * same definition ({@code core_json_scan()}):
 *
 * <ul>
 *   <li>a quotation mark delimits a string unless an odd number of reverse solidi immediately
 *       precede it;
 *   <li>delimiters alternate between opening and closing a literal;
 *   <li>an opening delimiter without a closing one starts an unterminated string that runs to the
 *       end of the text.
 * </ul>
 *
 * <p>Every character the checks look for is ASCII, so the character positions used here and the
 * byte positions used in R select the same characters.
 */
final class JsonTextScan {

    private static final int NO_CLASS = 0;
    private static final int NUL = 1;
    private static final int HIGH = 2;
    private static final int LOW = 3;

    /** Distance from the {@code u} of one escape to the {@code u} of a directly following one. */
    private static final int NEXT_ESCAPE = 6;

    private final String text;
    private final int[] delimiters;
    private final int count;

    private JsonTextScan(String text, int[] delimiters, int count) {
        this.text = text;
        this.delimiters = delimiters;
        this.count = count;
    }

    /**
     * Scans a text.
     *
     * @param text JSON text
     * @return the scan
     */
    static JsonTextScan of(String text) {
        int[] found = new int[8];
        int n = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                run++;
                continue;
            }
            if (c == '"' && (run & 1) == 0) {
                if (n == found.length) {
                    found = Arrays.copyOf(found, n * 2);
                }
                found[n++] = i;
            }
            run = 0;
        }
        return new JsonTextScan(text, found, n);
    }

    /**
     * Returns whether the text ends inside a string literal.
     *
     * @return true for an unterminated string
     */
    boolean unterminated() {
        return (count & 1) == 1;
    }

    /**
     * Returns whether a solidus, U+000B or U+000C occurs outside every string literal. RFC 8259
     * allows none of them there (no comments, four white space characters only).
     *
     * @return true if such a character occurs
     */
    boolean hasReservedCharacterOutsideStrings() {
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            while (seen < count && delimiters[seen] <= i) {
                seen++;
            }
            if ((seen & 1) == 1) {
                if (seen == count) {
                    return false;
                }
                i = delimiters[seen] - 1;
                continue;
            }
            char c = text.charAt(i);
            if (c == '/' || c == 0x0B || c == '\f') {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the brackets and braces outside string literals open more than {@code max}
     * levels at some point.
     *
     * @param max largest allowed number of open levels
     * @return true if the nesting is deeper
     */
    boolean nestingExceeds(int max) {
        int seen = 0;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            while (seen < count && delimiters[seen] <= i) {
                seen++;
            }
            if ((seen & 1) == 1) {
                if (seen == count) {
                    return false;
                }
                i = delimiters[seen] - 1;
                continue;
            }
            char c = text.charAt(i);
            if (c == '[' || c == '{') {
                depth++;
                if (depth > max) {
                    return true;
                }
            } else if (c == ']' || c == '}') {
                depth--;
            }
        }
        return false;
    }

    /**
     * Returns the text with every character of every string literal, delimiters included, replaced
     * by a space.
     *
     * @return masked text of the same length
     */
    String maskedText() {
        char[] chars = text.toCharArray();
        for (int k = 0; k < count; k += 2) {
            int end = k + 1 < count ? delimiters[k + 1] : chars.length - 1;
            Arrays.fill(chars, delimiters[k], end + 1, ' ');
        }
        return new String(chars);
    }

    /**
     * Checks the backslash-u escapes inside complete string literals: any escaped U+0000 is {@code
     * nul_character}; otherwise a high surrogate escape that is not directly followed by a low
     * surrogate escape, or a low one not directly preceded by a high one, is {@code
     * unpaired_surrogate}.
     *
     * @throws CanonicalJsonException for the first rule that fails
     */
    void checkEscapes() {
        int[] positions = new int[8];
        int n = 0;
        int run = 0;
        int seen = 0;
        for (int i = 0; i < text.length(); i++) {
            while (seen < count && delimiters[seen] <= i) {
                seen++;
            }
            char c = text.charAt(i);
            if (c == '\\') {
                run++;
                continue;
            }
            if (c == 'u' && (run & 1) == 1 && (seen & 1) == 1 && seen < count) {
                if (n == positions.length) {
                    positions = Arrays.copyOf(positions, n * 2);
                }
                positions[n++] = i;
            }
            run = 0;
        }
        int[] classes = new int[n];
        for (int j = 0; j < n; j++) {
            classes[j] = classify(positions[j]);
            if (classes[j] == NUL) {
                throw CanonicalJsonException.unrepresentable(
                        CanonicalJsonException.NUL_CHARACTER, "");
            }
        }
        for (int j = 0; j < n; j++) {
            boolean unpaired =
                    switch (classes[j]) {
                        case HIGH ->
                                j + 1 >= n
                                        || positions[j + 1] != positions[j] + NEXT_ESCAPE
                                        || classes[j + 1] != LOW;
                        case LOW ->
                                j == 0
                                        || positions[j - 1] != positions[j] - NEXT_ESCAPE
                                        || classes[j - 1] != HIGH;
                        default -> false;
                    };
            if (unpaired) {
                throw CanonicalJsonException.invalid(
                        CanonicalJsonException.UNPAIRED_SURROGATE, null);
            }
        }
    }

    /** Class of the four characters after the {@code u} of an escape. */
    private int classify(int u) {
        if (u + 4 >= text.length()) {
            return NO_CLASS;
        }
        char h1 = text.charAt(u + 1);
        char h2 = text.charAt(u + 2);
        char h3 = text.charAt(u + 3);
        char h4 = text.charAt(u + 4);
        if (h1 == '0' && h2 == '0' && h3 == '0' && h4 == '0') {
            return NUL;
        }
        if ((h1 != 'd' && h1 != 'D') || !isAsciiHex(h3) || !isAsciiHex(h4)) {
            return NO_CLASS;
        }
        if ("89abAB".indexOf(h2) >= 0) {
            return HIGH;
        }
        return "cdefCDEF".indexOf(h2) >= 0 ? LOW : NO_CLASS;
    }

    private static boolean isAsciiHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}
