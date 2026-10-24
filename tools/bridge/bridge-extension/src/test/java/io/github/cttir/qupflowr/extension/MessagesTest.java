/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test
    void germanIsTheDefaultLanguage() {
        assertEquals("de", Messages.forLanguage(null).language());
        assertEquals("de", Messages.forLanguage("fr").language());
        assertEquals("de", Messages.forLanguage(" DE ").language());
        assertEquals("en", Messages.forLanguage("en").language());
        assertEquals("Bridge starten", Messages.forLanguage(null).get("menu.start"));
        assertEquals("Start bridge", Messages.forLanguage("en").get("menu.start"));
    }

    @Test
    void bothLanguagesHaveTheSameKeys() {
        assertEquals(Messages.keys("de"), Messages.keys("en"));
        assertTrue(
                Messages.keys("de")
                        .containsAll(
                                java.util.Set.of(
                                        "menu.start",
                                        "menu.stop",
                                        "status.stopped",
                                        "status.starting",
                                        "status.running",
                                        "status.failed",
                                        "status.unsupported",
                                        "extension.description")));
    }

    @Test
    void placeholdersAreReplacedLiterallyAndUmlautsSurvive() {
        Messages de = Messages.forLanguage("de");
        assertEquals("Status: läuft auf 127.0.0.1:51234", de.get("status.running", 51234));
        assertEquals(
                "Status: QuPath 0.6.0 wird nicht unterstützt (benötigt 0.7.0)",
                de.get("status.unsupported", "0.6.0", "0.7.0"));
        assertEquals("unknown.key", de.get("unknown.key"));
        assertFalse(de.get("status.running", 1).contains("{0}"));
    }
}
