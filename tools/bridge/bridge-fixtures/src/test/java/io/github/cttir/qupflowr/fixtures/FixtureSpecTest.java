/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FixtureSpecTest {

    @Test
    void rgbFormulaMatchesHandComputedValues() {
        assertArrayEquals(new int[] {255, 0, 0}, FixtureSpec.rgb(0, 0));
        assertArrayEquals(new int[] {255, 0, 0}, FixtureSpec.rgb(127, 191));
        assertArrayEquals(new int[] {0, 255, 0}, FixtureSpec.rgb(128, 0));
        assertArrayEquals(new int[] {17, 99, 201}, FixtureSpec.rgb(511, 191));
        assertArrayEquals(new int[] {0, 255, 0}, FixtureSpec.rgb(0, 192));
        assertArrayEquals(new int[] {0, 255, 0}, FixtureSpec.rgb(1, 192));
        assertArrayEquals(new int[] {255, 0, 95}, FixtureSpec.rgb(511, 383));
        assertArrayEquals(new int[] {127, 128, 54}, FixtureSpec.rgb(255, 300));
        assertThrows(IllegalArgumentException.class, () -> FixtureSpec.rgb(512, 0));
        assertThrows(IllegalArgumentException.class, () -> FixtureSpec.rgb(0, -1));
    }

    @Test
    void omeFormulaMatchesHandComputedValues() {
        assertEquals(0, FixtureSpec.omeValue(0, 0, 0, 0, 0));
        assertEquals(2415, FixtureSpec.omeValue(2, 1, 1, 49, 6));
        assertEquals(FixtureSpec.OME_MAX_VALUE, FixtureSpec.omeValue(2, 1, 1, 49, 6));
        assertEquals(1101, FixtureSpec.omeValue(1, 0, 1, 37, 11));
        assertEquals(360, FixtureSpec.omeValue(0, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FixtureSpec.omeValue(3, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FixtureSpec.omeValue(0, 2, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FixtureSpec.omeValue(0, 0, 0, 256, 0));
    }

    @Test
    void planeIndexFollowsXyzctOrder() {
        assertEquals(9, FixtureSpec.omePlaneIndex(1, 1, 1));
        assertEquals(11, FixtureSpec.omePlaneIndex(2, 1, 1));
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            int[] p = FixtureSpec.omePlane(i);
            assertEquals(i, FixtureSpec.omePlaneIndex(p[0], p[1], p[2]));
            seen.add(i);
        }
        assertEquals(12, seen.size());
        assertThrows(IllegalArgumentException.class, () -> FixtureSpec.omePlane(12));
    }

    @Test
    void nameUuidsAreVersionThreeAndMatchTheRTables() {
        UUID id = FixtureSpec.nameUuid("ome:ann_polygon_hole");
        assertEquals(3, id.version());
        assertEquals(2, id.variant());
        for (Map<String, String> row : Tables.csv("objects.csv")) {
            assertEquals(row.get("object_id"),
                    FixtureSpec.nameUuid(row.get("entry_key") + ":" + row.get("object_key"))
                            .toString(),
                    row.get("object_key"));
        }
        String omeUuid = Tables.json("layout.json").getAsJsonObject().get("ome_uuid")
                .getAsString();
        assertEquals(omeUuid,
                "urn:uuid:" + FixtureSpec.omeUuid("ome_3c2z2t_u16.ome.tif"));
    }
}
