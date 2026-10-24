/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Value states of the data model ({@code finite}, {@code missing}, {@code nan}, {@code pos_inf},
 * {@code neg_inf}) with exact IEEE 754 bit patterns for finite values.
 */
public final class ValueCodec {

    /** State of a finite number. */
    public static final String FINITE = "finite";

    /** State of an absent measurement. */
    public static final String MISSING = "missing";

    private ValueCodec() {}

    /**
     * Value state of a double.
     *
     * @param v value
     * @return {@code finite}, {@code nan}, {@code pos_inf} or {@code neg_inf}
     */
    public static String state(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "pos_inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "neg_inf";
        }
        return FINITE;
    }

    /**
     * Big-endian IEEE 754 binary64 bits as 16 lower-case hex digits.
     *
     * @param v finite value
     * @return hex bits
     */
    public static String bits(double v) {
        return String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(v));
    }

    /**
     * Value after storage in a float32 slot (formula {@code storage.float32.v1}).
     *
     * @param v input
     * @return {@code (double) (float) v}
     */
    public static double float32(double v) {
        return (float) v;
    }

    /**
     * Encodes a value as {@code value_state}, {@code value} and {@code bits}.
     *
     * @param v value
     * @return ordered map; value and bits are null unless finite
     */
    public static Map<String, Object> encode(double v) {
        Map<String, Object> m = new LinkedHashMap<>();
        String state = state(v);
        m.put("value_state", state);
        m.put("value", FINITE.equals(state) ? v : null);
        m.put("bits", FINITE.equals(state) ? bits(v) : null);
        return m;
    }

    /**
     * Encoding of an absent value.
     *
     * @return ordered map with state {@code missing}
     */
    public static Map<String, Object> missing() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value_state", MISSING);
        m.put("value", null);
        m.put("bits", null);
        return m;
    }

    /**
     * UTF-8 bytes of a string as lower-case hex.
     *
     * @param s string, or null
     * @return hex, or null
     */
    public static String utf8Hex(String s) {
        return s == null ? null : HexFormat.of().formatHex(s.getBytes(StandardCharsets.UTF_8));
    }
}
