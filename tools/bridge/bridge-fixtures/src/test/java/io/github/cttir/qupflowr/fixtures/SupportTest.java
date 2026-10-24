/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cttir.qupflowr.fixtures.FixtureManifest.FileRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JSON writer, value codec, output normaliser and manifest inventory. */
class SupportTest {

    @TempDir Path tmp;

    @Test
    void jsonWriterIsDeterministicAndStrict() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", List.of(1, 2.5, -3L));
        m.put("a", "quote \" backslash \\ tab \t µ");
        m.put("empty_list", List.of());
        m.put("empty_map", Map.of());
        m.put("null", null);
        m.put("nested", new double[][] {{1.0, 2.0}, {3.0, 4.5}});
        assertEquals("{\n"
                + "  \"b\": [1, 2.5, -3],\n"
                + "  \"a\": \"quote \\\" backslash \\\\ tab \\t µ\",\n"
                + "  \"empty_list\": [],\n"
                + "  \"empty_map\": {},\n"
                + "  \"null\": null,\n"
                + "  \"nested\": [\n"
                + "    [1.0, 2.0],\n"
                + "    [3.0, 4.5]\n"
                + "  ]\n"
                + "}\n", JsonWriter.write(m));
        assertEquals("\"\\u0001\"\n", JsonWriter.write("\u0001"));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> JsonWriter.write(List.of(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(Map.of(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(new Object()));
    }

    @Test
    void valueCodecEncodesStatesAndExactBits() {
        assertEquals("finite", ValueCodec.state(0.0));
        assertEquals("nan", ValueCodec.state(Double.NaN));
        assertEquals("pos_inf", ValueCodec.state(Double.POSITIVE_INFINITY));
        assertEquals("neg_inf", ValueCodec.state(Double.NEGATIVE_INFINITY));
        assertEquals("3ff0000000000000", ValueCodec.bits(1.0));
        assertEquals("c029000000000000", ValueCodec.bits(-12.5));
        assertEquals("0000000000000001", ValueCodec.bits(Double.MIN_VALUE));
        assertEquals(Double.POSITIVE_INFINITY, ValueCodec.float32(1e300));
        assertEquals(0.0, ValueCodec.float32(Double.MIN_VALUE));
        assertEquals(1234.56787109375, ValueCodec.float32(1234.5678901234567));
        Map<String, Object> nan = ValueCodec.encode(Double.NaN);
        assertEquals("nan", nan.get("value_state"));
        assertNull(nan.get("value"));
        assertNull(nan.get("bits"));
        assertEquals("missing", ValueCodec.missing().get("value_state"));
        assertEquals("54c3bc6d6f72", ValueCodec.utf8Hex("Tümor"));
    }

    @Test
    void normaliserReplacesEverySpellingOfTheRoot() {
        Path root = tmp.resolve("out dir Ümlaut");
        OutputNormaliser n = new OutputNormaliser(root);
        String abs = root.toAbsolutePath().toString();
        String text = "{\"a\": \"" + abs + "/images/x.tif\", "
                + "\"b\": \"file:" + abs.replace(" ", "%20") + "/p.qpproj\", "
                + "\"c\": \"" + root.toUri().toASCIIString() + "/images/x.tif\"}";
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(n.containsRoot(content));
        String normalised = new String(n.normalise("project/data/1/server.json", content),
                StandardCharsets.UTF_8);
        assertEquals("{\"a\": \"<ROOT>/images/x.tif\", \"b\": \"<ROOT>/p.qpproj\", "
                + "\"c\": \"<ROOT>/images/x.tif\"}", normalised);
        assertEquals(List.of(OutputNormaliser.RULE_ROOT),
                n.rules("project/data/1/server.json", content));
        assertFalse(n.containsRoot("/somewhere/else".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void normaliserMasksQuPathTimestampsAndRandomNames() {
        OutputNormaliser n = new OutputNormaliser(tmp);
        String project = "{\n  \"createTimestamp\": 1789430156614,\n"
                + "  \"modifyTimestamp\": 1789430158443,\n"
                + "  \"randomizedName\": \"bd271487-3688-4e3e-9afd-158cc425f112\",\n"
                + "  \"lastID\": 5\n}";
        String other = project.replace("1789430156614", "1").replace("1789430158443", "2")
                .replace("bd271487-3688-4e3e-9afd-158cc425f112", "x");
        byte[] a = n.normalise("project/project.qpproj", project.getBytes(StandardCharsets.UTF_8));
        byte[] b = n.normalise("project/project.qpproj", other.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(a, b);
        assertTrue(new String(a, StandardCharsets.UTF_8).contains("\"lastID\": 5"));
        byte[] s1 = n.normalise("project/data/4/summary.json",
                "{\"timestamp\":17,\"imageType\":\"FLUORESCENCE\"}".getBytes(
                        StandardCharsets.UTF_8));
        byte[] s2 = n.normalise("project/data/4/summary.json",
                "{\"timestamp\":99,\"imageType\":\"FLUORESCENCE\"}".getBytes(
                        StandardCharsets.UTF_8));
        assertArrayEquals(s1, s2);
        byte[] changed = n.normalise("project/project.qpproj",
                project.replace("\"lastID\": 5", "\"lastID\": 6").getBytes(
                        StandardCharsets.UTF_8));
        assertNotEquals(new String(a, StandardCharsets.UTF_8),
                new String(changed, StandardCharsets.UTF_8));
        assertNull(OutputNormaliser.contentRule("images/summary.json"));
    }

    @Test
    void normaliserReplacesTheRootInsideBinaryContent() {
        OutputNormaliser n = new OutputNormaliser(tmp);
        byte[] root = tmp.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[root.length + 4];
        content[0] = (byte) 0xac;
        content[1] = (byte) 0xed;
        System.arraycopy(root, 0, content, 2, root.length);
        content[content.length - 2] = 0;
        content[content.length - 1] = (byte) 0xff;
        byte[] out = n.normalise("project/data/1/data.qpdata", content);
        assertArrayEquals(new byte[] {(byte) 0xac, (byte) 0xed, '<', 'R', 'O', 'O', 'T', '>', 0,
            (byte) 0xff}, out);
    }

    private static byte[] serialised(Path root) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeUTF("Data file version 3");
            out.writeObject("{\"uri\":\"" + root.toUri().toASCIIString() + "/images/x.tif\"}");
            Map<String, Object> properties = new java.util.HashMap<>();
            properties.put("PROJECT_ENTRY_ID", root + "/project/project.qpproj::1");
            out.writeObject(properties);
            out.writeObject("EOF");
        }
        return bytes.toByteArray();
    }

    @Test
    void serialisedStringsNormaliseIndependentlyOfTheRootLength() throws IOException {
        Path shortRoot = tmp.resolve("r");
        Path longRoot = tmp.resolve("a much longer root with Ümlaut");
        byte[] a = serialised(shortRoot);
        byte[] b = serialised(longRoot);
        assertNotEquals(a.length, b.length);
        byte[] na = new OutputNormaliser(shortRoot).normalise("project/data/1/data.qpdata", a);
        byte[] nb = new OutputNormaliser(longRoot).normalise("project/data/1/data.qpdata", b);
        assertArrayEquals(na, nb);
        // the normalised stream is still valid Java serialisation with the placeholder inside
        try (java.io.ObjectInputStream in =
                new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(na))) {
            assertEquals("Data file version 3", in.readUTF());
            assertEquals("{\"uri\":\"<ROOT>/images/x.tif\"}", in.readObject());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        byte[] plain = new OutputNormaliser(shortRoot).normalise("project/data/1/server.json", a);
        assertNotEquals(a.length, plain.length);
    }

    @Test
    void manifestInventoryIsSortedHashedAndRejectsLinks() throws IOException {
        Path root = tmp.resolve("tree");
        Files.createDirectories(root.resolve("images/b"));
        Files.createDirectories(root.resolve("project/data/1"));
        Files.writeString(root.resolve("images/b/abc.txt"), "abc");
        Files.writeString(root.resolve("images/a.txt"), "");
        Files.writeString(root.resolve("project/data/1/server.json"),
                "\"" + root.toAbsolutePath() + "/images/a.txt\"");
        Files.writeString(root.resolve("manifest.json"), "{}");
        List<FileRecord> records = FixtureManifest.inventory(root, new OutputNormaliser(root));
        List<String> paths = new ArrayList<>();
        records.forEach(r -> paths.add(r.path()));
        assertEquals(List.of("images/a.txt", "images/b/abc.txt", "project/data/1/server.json"),
                paths);
        FileRecord abc = records.get(1);
        assertEquals(3, abc.size());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                abc.sha256());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                records.get(0).sha256());
        assertEquals("image", abc.role());
        assertEquals(List.of(), abc.rules());
        assertNull(abc.sha256Normalised());
        FileRecord server = records.get(2);
        assertEquals("qupath_project", server.role());
        assertEquals(List.of("root"), server.rules());
        assertEquals(Digests.sha256("\"<ROOT>/images/a.txt\"".getBytes(StandardCharsets.UTF_8)),
                server.sha256Normalised());

        Files.createSymbolicLink(root.resolve("images/link.txt"), root.resolve("images/a.txt"));
        assertThrows(IOException.class,
                () -> FixtureManifest.inventory(root, new OutputNormaliser(root)));
    }
}
