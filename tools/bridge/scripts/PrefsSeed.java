/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.util.prefs.Preferences;

/**
 * Writes QuPath's "userPath" preference into an isolated java.util.prefs user root, so that a
 * QuPath started with the same -Djava.util.prefs.userRoot loads extensions from
 * userPath/extensions.
 *
 * <p>QuPath 0.7.0 reads the node "io.github.qupath/0.7" (PathPrefs) through Preferences.userRoot()
 * and the key "userPath". Usage (single-file source launch): {@code java
 * -Djava.util.prefs.userRoot=DIR PrefsSeed.java <absolute QuPath user directory>}
 */
public class PrefsSeed {

    public static void main(String[] args) throws Exception {
        String userRoot = System.getProperty("java.util.prefs.userRoot");
        if (userRoot == null
                || userRoot.isBlank()
                || args.length != 1
                || !args[0].startsWith("/")) {
            System.err.println(
                    "usage: java -Djava.util.prefs.userRoot=DIR PrefsSeed.java <absolute user"
                            + " dir>");
            System.exit(2);
        }
        Preferences node = Preferences.userRoot().node("io.github.qupath/0.7");
        node.put("userPath", args[0]);
        node.flush();
        System.out.println("userRoot=" + userRoot);
        System.out.println(
                "userPath="
                        + Preferences.userRoot()
                                .node("io.github.qupath/0.7")
                                .get("userPath", null));
    }
}
