/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.schema;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asserted {@code format} values of the validator subset. */
final class SchemaFormats {

    /** Supported formats. */
    static final List<String> FORMATS = List.of("uuid", "date-time", "sha256", "uri-reference");

    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Pattern DATE_TIME =
            Pattern.compile(
                    "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})"
                            + "([.][0-9]+)?(Z|[+-]([0-9]{2}):([0-9]{2}))");

    private static final Pattern BAD_PERCENT = Pattern.compile("%(?![0-9A-Fa-f]{2})");

    private static final int[] DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private SchemaFormats() {}

    /**
     * Checks a string against a supported format.
     *
     * @param value string instance
     * @param format format name
     * @return true when the value has the format; false for unknown formats
     */
    static boolean check(String value, String format) {
        return switch (format) {
            case "uuid" -> UUID.matcher(value).matches();
            case "sha256" -> SHA256.matcher(value).matches();
            case "date-time" -> dateTime(value);
            case "uri-reference" -> uriReference(value);
            default -> false;
        };
    }

    /**
     * RFC 3339 date-time with upper-case {@code T} and {@code Z}, a real calendar date, hours
     * 00-23, minutes and seconds 00-59 (no leap second), optional fraction and offset hours 00-23.
     */
    static boolean dateTime(String value) {
        Matcher m = DATE_TIME.matcher(value);
        if (!m.matches()) {
            return false;
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));
        boolean zoneOk =
                m.group(8).equals("Z")
                        || (Integer.parseInt(m.group(9)) <= 23
                                && Integer.parseInt(m.group(10)) <= 59);
        if (month < 1 || month > 12) {
            return false;
        }
        boolean leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        int days = month == 2 && leap ? 29 : DAYS[month - 1];
        return day >= 1 && day <= days && hour <= 23 && minute <= 59 && second <= 59 && zoneOk;
    }

    /**
     * URI or IRI reference: no white space, control characters, {@code < > " { } | \ ^} or grave
     * accent, and every percent sign followed by two hexadecimal digits.
     *
     * <p>The excluded code points are exactly the ones the R validator rejects with the POSIX
     * classes {@code [:space:]} and {@code [:cntrl:]} under a glibc UTF-8 locale: U+0000-U+0020,
     * U+007F-U+009F, U+1680, U+2000-U+2006, U+2008-U+200A, U+2028, U+2029, U+205F and U+3000.
     */
    static boolean uriReference(String value) {
        boolean forbidden =
                value.codePoints()
                        .anyMatch(
                                cp ->
                                        cp <= 0x20
                                                || "\"<>\\^`{|}".indexOf(cp) >= 0
                                                || (cp >= 0x7F && cp <= 0x9F)
                                                || cp == 0x1680
                                                || (cp >= 0x2000 && cp <= 0x2006)
                                                || (cp >= 0x2008 && cp <= 0x200A)
                                                || cp == 0x2028
                                                || cp == 0x2029
                                                || cp == 0x205F
                                                || cp == 0x3000);
        return !forbidden && !BAD_PERCENT.matcher(value).find();
    }
}
