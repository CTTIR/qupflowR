/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.fixtures.qupath;

import io.github.cttir.qupflowr.fixtures.Digests;
import io.github.cttir.qupflowr.fixtures.ExpectedValues;
import io.github.cttir.qupflowr.fixtures.FixtureLayout;
import io.github.cttir.qupflowr.fixtures.FixtureManifest;
import io.github.cttir.qupflowr.fixtures.FixtureManifest.FileRecord;
import io.github.cttir.qupflowr.fixtures.FixtureSpec;
import io.github.cttir.qupflowr.fixtures.JsonWriter;
import io.github.cttir.qupflowr.fixtures.OutputNormaliser;
import io.github.cttir.qupflowr.fixtures.ProjectSpec;
import io.github.cttir.qupflowr.fixtures.RawTiffInspector;
import io.github.cttir.qupflowr.fixtures.TiffWriters;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import loci.formats.FormatTools;
import qupath.lib.common.GeneralTools;

/**
 * Entry point of the synthetic fixture generator.
 *
 * <pre>
 * java -cp qupflowr-bridge-fixtures-VERSION.jar:QUPATH_HOME/lib/app/* \
 *     io.github.cttir.qupflowr.fixtures.qupath.FixtureGeneratorMain --out DIR
 * </pre>
 *
 * <p>Phases, in this order: (1) write {@code expected/expected-values.json} from the
 * specification; (2) write the JDK images; (3) write the OME-TIFF with Bio-Formats; (4) inspect
 * every TIFF with the JDK-only raw inspector; (5) QuPath: initialise image servers, write the main
 * project, delete the missing image, write and move the second project; (6) confirm the negative
 * class case; (7) re-hash the expected values and write {@code manifest.json} last.
 *
 * <p>Exit codes: 0 success, 1 a check failed or generation failed, 2 usage error or unusable
 * output directory.
 */
public final class FixtureGeneratorMain {

    private FixtureGeneratorMain() {}

    /**
     * Runs the generator and exits with its status.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the generator.
     *
     * @param args {@code --out DIR}, {@code --version} or {@code --help}
     * @param out standard output
     * @param err standard error
     * @return exit status
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        String outDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--version" -> {
                    out.println(JsonWriter.write(versionInfo()).strip());
                    return 0;
                }
                case "--help" -> {
                    out.println("Usage: FixtureGeneratorMain --out DIR | --version | --help");
                    return 0;
                }
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        err.println("--out needs a directory");
                        return 2;
                    }
                    outDir = args[++i];
                }
                default -> {
                    err.println("Unknown argument: " + args[i]);
                    return 2;
                }
            }
        }
        if (outDir == null) {
            err.println("Usage: FixtureGeneratorMain --out DIR | --version | --help");
            return 2;
        }
        Path root = Path.of(outDir).toAbsolutePath().normalize();
        try {
            if (Files.exists(root)) {
                if (!Files.isDirectory(root)) {
                    err.println("Output exists and is not a directory: " + root);
                    return 2;
                }
                try (Stream<Path> listing = Files.list(root)) {
                    if (listing.findAny().isPresent()) {
                        err.println("Output directory is not empty: " + root);
                        return 2;
                    }
                }
            }
            Files.createDirectories(root);
            List<String> failures = generate(root, out);
            if (!failures.isEmpty()) {
                failures.forEach(f -> err.println("check failed: " + f));
                return 1;
            }
            out.println("fixtures written and verified: " + root);
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("fixture generation failed: " + e);
            return 1;
        }
    }

    private static List<String> generate(Path root, PrintStream out) throws IOException {
        // Serialised data (java.util.Locale in data.qpdata) and number formats are fixed.
        Locale.setDefault(Locale.US);
        List<Map<String, Object>> phases = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        Path expected = root.resolve(FixtureLayout.EXPECTED_VALUES);
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, JsonWriter.write(ExpectedValues.document()),
                StandardCharsets.UTF_8);
        String expectedSha = Digests.sha256(expected);
        phase(phases, out, "expected_values_written", "specification only; no file read");

        Path rgb = root.resolve(FixtureLayout.RGB);
        Files.createDirectories(rgb.getParent());
        String imageIoWriter = TiffWriters.writeImageIoRgb(
                rgb, FixtureSpec.RGB_WIDTH, FixtureSpec.RGB_HEIGHT, FixtureSpec::rgb);
        TiffWriters.writeRawRgbWithoutResolution(
                root.resolve(FixtureLayout.RGB_NORES), FixtureSpec.RGB_WIDTH,
                FixtureSpec.RGB_HEIGHT, FixtureSpec::rgb);
        Path copy = root.resolve(FixtureLayout.RGB_UMLAUT_COPY);
        Files.createDirectories(copy.getParent());
        Files.copy(rgb, copy);
        phase(phases, out, "jdk_images_written", imageIoWriter + "; raw writer; byte copy");

        OmeTiffFixtureWriter.write(root.resolve(FixtureLayout.OME));
        phase(phases, out, "ome_tiff_written",
                "loci.formats.out.OMETiffWriter " + FormatTools.VERSION);

        List<Object> raw = new ArrayList<>();
        for (String rel : List.of(FixtureLayout.RGB, FixtureLayout.RGB_UMLAUT_COPY,
                FixtureLayout.RGB_NORES)) {
            Map<String, Object> report = RawTiffInspector.checkRgb(root.resolve(rel), rel);
            raw.add(report);
            if (!Boolean.TRUE.equals(report.get("pass"))) {
                failures.add("raw TIFF check " + rel);
            }
        }
        Map<String, Object> omeReport = RawTiffInspector.checkOme(
                root.resolve(FixtureLayout.OME), FixtureLayout.OME,
                FixtureSpec.omeUuid(root.resolve(FixtureLayout.OME).getFileName().toString())
                        .toString());
        raw.add(omeReport);
        if (!Boolean.TRUE.equals(omeReport.get("pass"))) {
            failures.add("raw TIFF check " + FixtureLayout.OME);
        }
        Map<String, Object> nores = castMap(raw.get(2));
        if (nores.get("resolution_unit") != null || nores.get("x_resolution_rational") != null) {
            failures.add("raw TIFF " + FixtureLayout.RGB_NORES + " has resolution tags");
        }
        Map<String, Object> rgbReport = castMap(raw.get(0));
        if (rgbReport.get("resolution_unit") == null) {
            failures.add("raw TIFF " + FixtureLayout.RGB + " has no ResolutionUnit tag");
        }
        Map<String, Object> rawDoc = new LinkedHashMap<>();
        rawDoc.put("document", "qupflowr-raw-tiff-check");
        rawDoc.put("inspector", RawTiffInspector.class.getName() + " (JDK only)");
        rawDoc.put("reports", raw);
        Files.writeString(root.resolve(FixtureLayout.RAW_TIFF_CHECK), JsonWriter.write(rawDoc),
                StandardCharsets.UTF_8);
        phase(phases, out, "raw_tiff_inspected", failures.isEmpty() ? "pass" : "fail");
        if (!failures.isEmpty()) {
            return failures;
        }

        ProjectFixtureWriter.initialiseImageServers();
        ProjectFixtureWriter projects = new ProjectFixtureWriter(root);
        Path deleted = root.resolve(FixtureLayout.DELETED_IMAGE);
        Files.createDirectories(deleted.getParent());
        Files.copy(rgb, deleted);
        projects.writeProject(FixtureLayout.PROJECT_DIR, ProjectSpec.mainEntries(),
                ProjectSpec.PROJECT_CLASSES);
        Files.delete(deleted);
        Files.delete(root.resolve(FixtureLayout.DELETED_DIR));
        phase(phases, out, "main_project_written",
                "5 entries; deleted " + FixtureLayout.DELETED_IMAGE);

        Path stagingRgb = root.resolve(FixtureLayout.STAGING_RGB);
        Files.createDirectories(stagingRgb.getParent());
        Files.copy(rgb, stagingRgb);
        projects.writeProject(Path.of(FixtureLayout.STAGING_PROJECT_FILE).getParent().toString()
                .replace('\\', '/'), ProjectSpec.movedEntries(), List.of());
        Files.createDirectories(root.resolve(FixtureLayout.MOVED_PARENT));
        Files.move(root.resolve(FixtureLayout.STAGING_DIR), root.resolve(FixtureLayout.MOVED_DIR),
                StandardCopyOption.ATOMIC_MOVE);
        phase(phases, out, "moved_project_written",
                FixtureLayout.STAGING_DIR + " moved to " + FixtureLayout.MOVED_DIR);

        projects.checkColonComponentRejected();
        failures.addAll(projects.failures());
        phase(phases, out, "qupath_checks", projects.checks().size() + " checks, "
                + projects.failures().size() + " failed");

        for (String absent : FixtureLayout.ABSENT_PATHS) {
            if (Files.exists(root.resolve(absent))) {
                failures.add("path must not exist: " + absent);
            }
        }
        String expectedShaAtEnd = Digests.sha256(expected);
        if (!expectedSha.equals(expectedShaAtEnd)) {
            failures.add("expected values changed during generation");
        }
        OutputNormaliser normaliser = new OutputNormaliser(root);
        List<FileRecord> files = FixtureManifest.inventory(root, normaliser);
        phase(phases, out, "inventory", files.size() + " files");

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifest_schema_version", FixtureManifest.MANIFEST_SCHEMA_VERSION);
        manifest.put("fixture_set", FixtureSpec.FIXTURE_SET);
        manifest.put("fixture_set_version", FixtureSpec.FIXTURE_SET_VERSION);
        manifest.put("generator", generatorInfo());
        manifest.put("runtime", runtimeInfo());
        manifest.put("output_root", root.toString());
        manifest.put("formula_ids", FixtureSpec.FORMULA_IDS);
        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("expected_values", FixtureLayout.EXPECTED_VALUES);
        oracle.put("expected_values_sha256_at_write", expectedSha);
        oracle.put("expected_values_sha256_at_end", expectedShaAtEnd);
        oracle.put("written_before_any_reader", true);
        oracle.put("raw_tiff_check", FixtureLayout.RAW_TIFF_CHECK);
        manifest.put("oracle", oracle);
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("raw_tiff_samples_checked", sumSamples(raw));
        checks.put("qupath_checks", projects.checks().size());
        checks.put("failures", failures);
        manifest.put("checks", checks);
        manifest.put("phases", phases);
        manifest.put("normalisation_rules", OutputNormaliser.ruleDescriptions());
        manifest.put("root_variants_replaced", normaliser.rootVariants().size());
        manifest.put("absent_paths", FixtureLayout.ABSENT_PATHS);
        manifest.put("files", FixtureManifest.filesSection(files));
        if (failures.isEmpty()) {
            Files.writeString(root.resolve(FixtureLayout.MANIFEST), JsonWriter.write(manifest),
                    StandardCharsets.UTF_8);
        }
        return failures;
    }

    private static long sumSamples(List<Object> reports) {
        long sum = 0;
        for (Object r : reports) {
            Object n = castMap(r).get("samples_checked");
            if (n instanceof Number number) {
                sum += number.longValue();
            }
        }
        return sum;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static void phase(List<Map<String, Object>> phases, PrintStream out, String name,
            String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequence", phases.size() + 1);
        m.put("phase", name);
        m.put("detail", detail);
        phases.add(m);
        out.println("phase " + (phases.size()) + " " + name + ": " + detail);
    }

    private static Map<String, Object> versionInfo() {
        Map<String, Object> m = generatorInfo();
        m.putAll(runtimeInfo());
        return m;
    }

    private static Map<String, Object> generatorInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", FixtureGeneratorMain.class.getName());
        m.put("version", FixtureSpec.GENERATOR_VERSION);
        Package pkg = FixtureGeneratorMain.class.getPackage();
        m.put("bridge_version", pkg == null ? null : pkg.getImplementationVersion());
        m.put("jar_sha256", codeSourceSha256(FixtureGeneratorMain.class));
        return m;
    }

    private static Map<String, Object> runtimeInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("java_version", System.getProperty("java.version"));
        m.put("java_vendor", System.getProperty("java.vendor"));
        m.put("qupath_version", GeneralTools.getVersion());
        m.put("qupath_core_sha256", codeSourceSha256(GeneralTools.class));
        m.put("bioformats_version", FormatTools.VERSION);
        m.put("os_name", System.getProperty("os.name"));
        m.put("os_arch", System.getProperty("os.arch"));
        return m;
    }

    private static String codeSourceSha256(Class<?> type) {
        try {
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) ? Digests.sha256(path) : null;
        } catch (IOException | URISyntaxException | SecurityException e) {
            return null;
        }
    }
}
