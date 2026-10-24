/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures;

import java.io.IOException;
import java.io.StringReader;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Independent structural and pixel check of the TIFF fixtures using only the JDK.
 *
 * <p>The parser reads classic (non-Big) TIFF headers, IFD entries and uncompressed strips from the
 * file bytes. It uses no ImageIO reader, Bio-Formats or QuPath class, so a disagreement between a
 * reader and {@link FixtureSpec} can be attributed to the right side. Supported subset:
 * uncompressed, chunky RGB or single-sample grey, 8 or 16 bits.
 */
public final class RawTiffInspector {

    static final int TAG_WIDTH = 256;
    static final int TAG_HEIGHT = 257;
    static final int TAG_BITS_PER_SAMPLE = 258;
    static final int TAG_COMPRESSION = 259;
    static final int TAG_PHOTOMETRIC = 262;
    static final int TAG_IMAGE_DESCRIPTION = 270;
    static final int TAG_STRIP_OFFSETS = 273;
    static final int TAG_SAMPLES_PER_PIXEL = 277;
    static final int TAG_STRIP_BYTE_COUNTS = 279;
    static final int TAG_X_RESOLUTION = 282;
    static final int TAG_Y_RESOLUTION = 283;
    static final int TAG_PLANAR_CONFIGURATION = 284;
    static final int TAG_RESOLUTION_UNIT = 296;

    private RawTiffInspector() {}

    /**
     * One parsed image file directory.
     *
     * @param index zero-based IFD index
     * @param numericTags numeric tag values (RATIONAL values as numerator, denominator pairs)
     * @param imageDescription ImageDescription text, or null
     * @param file the whole file with its byte order
     */
    public record Ifd(
            int index, Map<Integer, long[]> numericTags, String imageDescription, ByteBuffer file) {

        long single(int tag, long defaultValue) {
            long[] v = numericTags.get(tag);
            return v == null || v.length == 0 ? defaultValue : v[0];
        }

        long[] values(int tag) {
            long[] v = numericTags.get(tag);
            return v == null ? new long[0] : v.clone();
        }

        int width() {
            return (int) single(TAG_WIDTH, -1);
        }

        int height() {
            return (int) single(TAG_HEIGHT, -1);
        }

        byte[] pixelBytes() throws IOException {
            long[] offsets = values(TAG_STRIP_OFFSETS);
            long[] counts = values(TAG_STRIP_BYTE_COUNTS);
            if (offsets.length != counts.length || offsets.length == 0) {
                throw new IOException("StripOffsets and StripByteCounts do not match");
            }
            long total = 0;
            for (long c : counts) {
                total += c;
            }
            if (total > Integer.MAX_VALUE) {
                throw new IOException("Strip data too large");
            }
            byte[] out = new byte[(int) total];
            int pos = 0;
            for (int i = 0; i < offsets.length; i++) {
                if (offsets[i] + counts[i] > file.capacity()) {
                    throw new IOException("Strip " + i + " lies outside the file");
                }
                ByteBuffer dup = file.duplicate();
                dup.position((int) offsets[i]);
                dup.get(out, pos, (int) counts[i]);
                pos += (int) counts[i];
            }
            return out;
        }
    }

    /**
     * Parses all IFDs of a classic TIFF file.
     *
     * @param path file
     * @return IFDs in file order
     * @throws IOException if the file is not a supported, well-formed classic TIFF
     */
    public static List<Ifd> parse(Path path) throws IOException {
        return parse(Files.readAllBytes(path));
    }

    /**
     * Parses all IFDs of classic TIFF bytes.
     *
     * @param bytes file content
     * @return IFDs in file order
     * @throws IOException if the bytes are not a supported, well-formed classic TIFF
     */
    public static List<Ifd> parse(byte[] bytes) throws IOException {
        try {
            return parseUnchecked(bytes);
        } catch (IndexOutOfBoundsException | BufferUnderflowException | ArithmeticException e) {
            throw new IOException("Malformed TIFF: " + e.getMessage(), e);
        }
    }

    private static List<Ifd> parseUnchecked(byte[] bytes) throws IOException {
        if (bytes.length < 8) {
            throw new IOException("Not a TIFF file: shorter than a header");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (bytes[0] == 'I' && bytes[1] == 'I') {
            buf.order(ByteOrder.LITTLE_ENDIAN);
        } else if (bytes[0] == 'M' && bytes[1] == 'M') {
            buf.order(ByteOrder.BIG_ENDIAN);
        } else {
            throw new IOException("Not a TIFF file: unknown byte order mark");
        }
        int magic = Short.toUnsignedInt(buf.getShort(2));
        if (magic != 42) {
            throw new IOException("Unsupported TIFF magic " + magic + " (only classic TIFF)");
        }
        List<Ifd> ifds = new ArrayList<>();
        long next = Integer.toUnsignedLong(buf.getInt(4));
        while (next != 0) {
            if (ifds.size() > 4096) {
                throw new IOException("Too many IFDs (loop?)");
            }
            int pos = Math.toIntExact(next);
            int n = Short.toUnsignedInt(buf.getShort(pos));
            Map<Integer, long[]> tags = new LinkedHashMap<>();
            String description = null;
            for (int i = 0; i < n; i++) {
                int e = pos + 2 + i * 12;
                int tag = Short.toUnsignedInt(buf.getShort(e));
                int type = Short.toUnsignedInt(buf.getShort(e + 2));
                int count = Math.toIntExact(Integer.toUnsignedLong(buf.getInt(e + 4)));
                int size = typeSize(type);
                long byteLength = (long) size * count;
                int dataPos =
                        byteLength <= 4
                                ? e + 8
                                : Math.toIntExact(Integer.toUnsignedLong(buf.getInt(e + 8)));
                if (type == 2) {
                    byte[] ascii = new byte[count];
                    buf.duplicate().position(dataPos).get(ascii);
                    int len = ascii.length;
                    while (len > 0 && ascii[len - 1] == 0) {
                        len--;
                    }
                    if (tag == TAG_IMAGE_DESCRIPTION) {
                        description = new String(ascii, 0, len, StandardCharsets.UTF_8);
                    }
                } else if (type == 1 || type == 3 || type == 4 || type == 5 || type == 7) {
                    long[] values = new long[type == 5 ? count * 2 : count];
                    for (int k = 0; k < count; k++) {
                        switch (type) {
                            case 1, 7 -> values[k] = Byte.toUnsignedInt(buf.get(dataPos + k));
                            case 3 ->
                                    values[k] = Short.toUnsignedInt(buf.getShort(dataPos + 2 * k));
                            case 4 ->
                                    values[k] =
                                            Integer.toUnsignedLong(buf.getInt(dataPos + 4 * k));
                            default -> {
                                values[2 * k] = Integer.toUnsignedLong(buf.getInt(dataPos + 8 * k));
                                values[2 * k + 1] =
                                        Integer.toUnsignedLong(buf.getInt(dataPos + 8 * k + 4));
                            }
                        }
                    }
                    tags.put(tag, values);
                }
            }
            ifds.add(new Ifd(ifds.size(), tags, description, buf));
            next = Integer.toUnsignedLong(buf.getInt(pos + 2 + n * 12));
        }
        if (ifds.isEmpty()) {
            throw new IOException("TIFF file has no image file directory");
        }
        return ifds;
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 0;
        };
    }

    /**
     * Verifies an RGB fixture pixel by pixel against {@link FixtureSpec#rgb(int, int)} and reports
     * its resolution tags.
     *
     * @param path file
     * @param label relative path used in the report
     * @return ordered report; {@code pass} is true only without any mismatch
     * @throws IOException if the file cannot be parsed
     */
    public static Map<String, Object> checkRgb(Path path, String label) throws IOException {
        List<Ifd> ifds = parse(path);
        Ifd ifd = ifds.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", label);
        result.put("byte_order", ifd.file().order().toString());
        result.put("ifd_count", ifds.size());
        result.put("width", ifd.width());
        result.put("height", ifd.height());
        result.put("bits_per_sample", ifd.values(TAG_BITS_PER_SAMPLE));
        result.put("samples_per_pixel", ifd.single(TAG_SAMPLES_PER_PIXEL, 1));
        result.put("compression", ifd.single(TAG_COMPRESSION, 1));
        result.put("photometric", ifd.single(TAG_PHOTOMETRIC, -1));
        result.put("planar_configuration", ifd.single(TAG_PLANAR_CONFIGURATION, 1));
        result.put("x_resolution_rational", tagOrNull(ifd, TAG_X_RESOLUTION));
        result.put("y_resolution_rational", tagOrNull(ifd, TAG_Y_RESOLUTION));
        result.put("resolution_unit", tagOrNull(ifd, TAG_RESOLUTION_UNIT));
        long mismatches = 0;
        boolean layoutOk =
                ifds.size() == 1
                        && ifd.single(TAG_COMPRESSION, 1) == 1
                        && ifd.single(TAG_SAMPLES_PER_PIXEL, 1) == 3
                        && ifd.single(TAG_PLANAR_CONFIGURATION, 1) == 1
                        && allEqual(ifd.values(TAG_BITS_PER_SAMPLE), 8, 3)
                        && ifd.width() == FixtureSpec.RGB_WIDTH
                        && ifd.height() == FixtureSpec.RGB_HEIGHT;
        if (layoutOk) {
            byte[] px = ifd.pixelBytes();
            int w = ifd.width();
            if (px.length < w * ifd.height() * 3) {
                throw new IOException("RGB strip is shorter than width * height * 3");
            }
            for (int y = 0; y < ifd.height(); y++) {
                for (int x = 0; x < w; x++) {
                    int[] expected = FixtureSpec.rgb(x, y);
                    int base = (y * w + x) * 3;
                    for (int s = 0; s < 3; s++) {
                        if (Byte.toUnsignedInt(px[base + s]) != expected[s]) {
                            mismatches++;
                        }
                    }
                }
            }
        }
        result.put("layout_matches_spec", layoutOk);
        result.put("samples_checked", layoutOk ? (long) ifd.width() * ifd.height() * 3 : 0L);
        result.put("sample_mismatches", mismatches);
        result.put("pass", layoutOk && mismatches == 0);
        return result;
    }

    /**
     * Verifies the OME-TIFF fixture: OME-XML dimensions, calibration, channel names and UUID, the
     * IFD-to-plane mapping of the TiffData elements, and every uint16 sample against {@link
     * FixtureSpec#omeValue(int, int, int, int, int)}.
     *
     * @param path file
     * @param label relative path used in the report
     * @param expectedUuid expected OME UUID (without {@code urn:uuid:})
     * @return ordered report; {@code pass} is true only if every check holds
     * @throws IOException if the file or its OME-XML cannot be parsed
     */
    public static Map<String, Object> checkOme(Path path, String label, String expectedUuid)
            throws IOException {
        List<Ifd> ifds = parse(path);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", label);
        result.put("byte_order", ifds.get(0).file().order().toString());
        result.put("ifd_count", ifds.size());
        String xml = ifds.get(0).imageDescription();
        if (xml == null) {
            throw new IOException("First IFD has no ImageDescription (OME-XML)");
        }
        Document doc = parseXml(xml);
        Element root = doc.getDocumentElement();
        Element pixels = (Element) doc.getElementsByTagNameNS("*", "Pixels").item(0);
        if (pixels == null) {
            throw new IOException("OME-XML has no Pixels element");
        }
        Map<String, Object> xmlInfo = new LinkedHashMap<>();
        xmlInfo.put("uuid", root.hasAttribute("UUID") ? root.getAttribute("UUID") : null);
        for (String attr :
                List.of("DimensionOrder", "Type", "SizeX", "SizeY", "SizeZ", "SizeC", "SizeT",
                        "PhysicalSizeX", "PhysicalSizeXUnit", "PhysicalSizeY",
                        "PhysicalSizeYUnit", "PhysicalSizeZ", "PhysicalSizeZUnit",
                        "BigEndian")) {
            xmlInfo.put(attr, pixels.hasAttribute(attr) ? pixels.getAttribute(attr) : null);
        }
        NodeList channels = pixels.getElementsByTagNameNS("*", "Channel");
        List<String> channelNames = new ArrayList<>();
        for (int i = 0; i < channels.getLength(); i++) {
            channelNames.add(((Element) channels.item(i)).getAttribute("Name"));
        }
        xmlInfo.put("channel_names", channelNames);
        result.put("ome_xml", xmlInfo);

        int nPlanes = FixtureSpec.OME_SIZE_C * FixtureSpec.OME_SIZE_Z * FixtureSpec.OME_SIZE_T;
        NodeList tiffData = pixels.getElementsByTagNameNS("*", "TiffData");
        int[][] czt = new int[ifds.size()][];
        boolean mappingOk = tiffData.getLength() == nPlanes && ifds.size() == nPlanes;
        List<String> tiffDataUuids = new ArrayList<>();
        for (int i = 0; mappingOk && i < tiffData.getLength(); i++) {
            Element td = (Element) tiffData.item(i);
            int ifdIndex = intAttr(td, "IFD");
            if (ifdIndex < 0 || ifdIndex >= ifds.size() || czt[ifdIndex] != null) {
                mappingOk = false;
                break;
            }
            czt[ifdIndex] = new int[] {intAttr(td, "FirstC"), intAttr(td, "FirstZ"),
                intAttr(td, "FirstT")};
            NodeList uuidNodes = td.getElementsByTagNameNS("*", "UUID");
            if (uuidNodes.getLength() == 1) {
                tiffDataUuids.add(uuidNodes.item(0).getTextContent().trim());
            }
            int[] specPlane = FixtureSpec.omePlane(ifdIndex);
            if (!java.util.Arrays.equals(czt[ifdIndex], specPlane)) {
                mappingOk = false;
            }
        }
        result.put("plane_mapping_matches_spec", mappingOk);

        long mismatches = 0;
        long checked = 0;
        List<Map<String, Object>> planes = new ArrayList<>();
        for (Ifd ifd : ifds) {
            Map<String, Object> planeInfo = new LinkedHashMap<>();
            planeInfo.put("ifd", ifd.index());
            int[] p = czt[ifd.index()];
            planeInfo.put("c", p == null ? null : p[0]);
            planeInfo.put("z", p == null ? null : p[1]);
            planeInfo.put("t", p == null ? null : p[2]);
            long planeMismatch = 0;
            boolean layoutOk =
                    p != null
                            && ifd.single(TAG_COMPRESSION, 1) == 1
                            && ifd.single(TAG_SAMPLES_PER_PIXEL, 1) == 1
                            && ifd.single(TAG_BITS_PER_SAMPLE, 0) == 16
                            && ifd.width() == FixtureSpec.OME_WIDTH
                            && ifd.height() == FixtureSpec.OME_HEIGHT;
            if (layoutOk) {
                ByteBuffer px = ByteBuffer.wrap(ifd.pixelBytes()).order(ifd.file().order());
                if (px.capacity() < ifd.width() * ifd.height() * 2) {
                    throw new IOException("OME strip is shorter than width * height * 2");
                }
                for (int y = 0; y < ifd.height(); y++) {
                    for (int x = 0; x < ifd.width(); x++) {
                        int v = Short.toUnsignedInt(px.getShort((y * ifd.width() + x) * 2));
                        checked++;
                        if (v != FixtureSpec.omeValue(p[0], p[1], p[2], x, y)) {
                            planeMismatch++;
                        }
                    }
                }
            }
            planeInfo.put("layout_matches_spec", layoutOk);
            planeInfo.put("sample_mismatches", planeMismatch);
            planes.add(planeInfo);
            mismatches += layoutOk ? planeMismatch : 1;
        }
        result.put("planes", planes);
        result.put("samples_checked", checked);
        result.put("sample_mismatches", mismatches);

        String rootUuid = (String) xmlInfo.get("uuid");
        String expectedUrn = "urn:uuid:" + expectedUuid;
        boolean uuidOk =
                expectedUrn.equals(rootUuid)
                        && tiffDataUuids.size() == nPlanes
                        && tiffDataUuids.stream().allMatch(expectedUrn::equals);
        result.put("expected_uuid", expectedUrn);
        result.put("uuid_matches_spec", uuidOk);
        boolean metadataOk =
                FixtureSpec.OME_CHANNEL_NAMES.equals(channelNames)
                        && FixtureSpec.OME_DIMENSION_ORDER.equals(xmlInfo.get("DimensionOrder"))
                        && "uint16".equals(xmlInfo.get("Type"))
                        && eqInt(xmlInfo.get("SizeX"), FixtureSpec.OME_WIDTH)
                        && eqInt(xmlInfo.get("SizeY"), FixtureSpec.OME_HEIGHT)
                        && eqInt(xmlInfo.get("SizeZ"), FixtureSpec.OME_SIZE_Z)
                        && eqInt(xmlInfo.get("SizeC"), FixtureSpec.OME_SIZE_C)
                        && eqInt(xmlInfo.get("SizeT"), FixtureSpec.OME_SIZE_T)
                        && eqDouble(xmlInfo.get("PhysicalSizeX"), FixtureSpec.OME_PIXEL_WIDTH_UM)
                        && eqDouble(xmlInfo.get("PhysicalSizeY"), FixtureSpec.OME_PIXEL_HEIGHT_UM)
                        && eqDouble(xmlInfo.get("PhysicalSizeZ"), FixtureSpec.OME_Z_SPACING_UM)
                        && "µm".equals(xmlInfo.get("PhysicalSizeXUnit"))
                        && "µm".equals(xmlInfo.get("PhysicalSizeYUnit"))
                        && "µm".equals(xmlInfo.get("PhysicalSizeZUnit"));
        result.put("metadata_matches_spec", metadataOk);
        result.put("pass", metadataOk && mappingOk && uuidOk && mismatches == 0);
        return result;
    }

    private static Document parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setExpandEntityReferences(false);
            return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("OME-XML cannot be parsed: " + e.getMessage(), e);
        }
    }

    private static long[] tagOrNull(Ifd ifd, int tag) {
        return ifd.numericTags().containsKey(tag) ? ifd.values(tag) : null;
    }

    private static boolean allEqual(long[] values, long expected, int count) {
        if (values.length != count) {
            return false;
        }
        for (long v : values) {
            if (v != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean eqInt(Object text, int expected) {
        try {
            return text != null && Integer.parseInt(text.toString()) == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean eqDouble(Object text, double expected) {
        try {
            return text != null && Double.parseDouble(text.toString()) == expected;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int intAttr(Element e, String name) {
        if (!e.hasAttribute(name)) {
            return 0;
        }
        try {
            return Integer.parseInt(e.getAttribute(name));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
