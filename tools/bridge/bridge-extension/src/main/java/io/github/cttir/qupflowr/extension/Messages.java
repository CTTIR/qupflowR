/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * User interface texts. German is the default language, English the fallback; keys are English.
 * Placeholders {@code {0}}, {@code {1}} ... are replaced literally (no number formatting).
 */
final class Messages {

    /** System property selecting the language ({@code de} or {@code en}). */
    static final String PROPERTY_LANGUAGE = "qupflowr.language";

    /** Supported languages; the first is the default. */
    static final Set<String> LANGUAGES = Set.of("de", "en");

    private static final String DEFAULT_LANGUAGE = "de";
    private static final String FALLBACK_LANGUAGE = "en";

    private final String language;
    private final Map<String, String> selected;
    private final Map<String, String> fallback;

    private Messages(String language, Map<String, String> selected, Map<String, String> fallback) {
        this.language = language;
        this.selected = selected;
        this.fallback = fallback;
    }

    /**
     * Loads the texts for a language; unknown or missing values select German.
     *
     * @param requested language code, may be null
     * @return messages
     */
    static Messages forLanguage(String requested) {
        String normalised = requested == null ? "" : requested.strip().toLowerCase(Locale.ROOT);
        String language = LANGUAGES.contains(normalised) ? normalised : DEFAULT_LANGUAGE;
        return new Messages(language, load(language), load(FALLBACK_LANGUAGE));
    }

    /**
     * Returns the effective language.
     *
     * @return {@code de} or {@code en}
     */
    String language() {
        return language;
    }

    /**
     * Returns a text with placeholders replaced.
     *
     * @param key message key
     * @param arguments placeholder values
     * @return text in the selected language, the English text if missing, or the key itself
     */
    String get(String key, Object... arguments) {
        String text = selected.getOrDefault(key, fallback.getOrDefault(key, key));
        for (int i = 0; i < arguments.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(arguments[i]));
        }
        return text;
    }

    /**
     * Returns all keys of a language file.
     *
     * @param language language code
     * @return sorted keys
     */
    static Set<String> keys(String language) {
        return load(language).keySet();
    }

    private static Map<String, String> load(String language) {
        String resource = "messages_" + language + ".properties";
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing message resource " + resource);
            }
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> map = new TreeMap<>();
            for (String name : properties.stringPropertyNames()) {
                map.put(name, properties.getProperty(name));
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + resource, e);
        }
    }
}
