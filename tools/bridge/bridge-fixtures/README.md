# bridge-fixtures: synthetic image and QuPath project fixtures

This module generates fixture set version 1 for reader, protocol and project
tests. No Python is involved. Run it through the R driver, which builds the
jar, isolates the Java process and verifies the result:

```sh
Rscript tools/fixtures.R                 # default output work/fixtures/v1
Rscript tools/fixtures.R --out "/tmp/fixtures v1 ä" --force
Rscript tools/fixtures.R --verify-only   # hashes only; no build, no Java
```

Exit status: 0 verified (reused or regenerated), 1 generation or verification
failed, 2 usage or environment error. Existing output is reused only when it
was written by the same generator jar, QuPath core jar and Java version into
the same directory and every file still matches the manifest.

## Source sets

| Source set | Needs | Content |
|---|---|---|
| `main` | JDK only | `FixtureSpec` (formulas, constants), `FixtureLayout`, `ProjectSpec` and `FixtureModel` (objects, measurements, paths), `TiffWriters` (ImageIO and raw TIFF writers), `RawTiffInspector`, `ExpectedValues`, `JsonWriter`, `OutputNormaliser`, `FixtureManifest` |
| `qupath` | QuPath 0.7.0 installation | `OmeTiffFixtureWriter` (Bio-Formats), `ProjectFixtureWriter`, `FixtureGeneratorMain` |

The `qupath` source set compiles against eight jars of
`<QUPATH_HOME>/lib/app` whose SHA-256 values are pinned in `build.gradle.kts`
(task `verifyQuPathCompileJars`). Set the installation with
`-Pqupflowr.qupathHome=DIR` or `QUPFLOWR_QUPATH_HOME`; the default is
`/opt/QuPath-v0.7.0`. The unit tests (`./gradlew :bridge-fixtures:test`) run
the `main` classes on a class path without any QuPath or Bio-Formats jar and
compare them with the independent R tables in `tests/fixtures/qp-v1`
(override with `-Pqupflowr.fixtureTablesDir=DIR`).

## Output

| Path | Written by |
|---|---|
| `expected/expected-values.json` | the specification, before any file is read |
| `expected/raw-tiff-check.json` | JDK-only TIFF inspection of every sample |
| `images/rgb_512x384.tif` | JDK ImageIO TIFF writer (resolution 1/1, unit "none") |
| `images/rgb_512x384_nores.tif` | raw writer without resolution tags |
| `images/Ordner mit Ümlaut/rgb_512x384.tif` | byte copy |
| `images/ome_3c2z2t_u16.ome.tif` | Bio-Formats `OMETiffWriter`, 3c/2z/2t uint16, 0.25 x 0.5 µm, z 1.5 µm |
| `project/` | QuPath project, entries 1-5 (RGB, umlaut directory, no calibration, OME with 12 objects, deleted image) |
| `moved/copy/` | a second project written under `staging/` and then moved |
| `manifest.json` | file list with size, SHA-256, role and normalisation; generator, runtime, oracle digests, phases |

Formulas, the object hierarchy and the moved-project semantics are documented
in `FixtureSpec`, `ProjectSpec` and `tests/fixtures/qp-v1/README.md`.

## Oracle discipline

1. `expected-values.json` is written from constants and formulas first; its
   SHA-256 is recorded, then checked again at the end.
2. The TIFF files are written (JDK writers, then Bio-Formats as a writer).
3. `RawTiffInspector` parses the TIFF bytes with the JDK only and compares all
   589 824 RGB samples per file and all 589 824 OME samples with the formulas,
   plus the OME-XML dimensions, calibration, channel names, UUID and IFD
   mapping. Any mismatch stops the generator before QuPath runs.
4. Only then are QuPath image servers initialised (`ImageServers` before any
   project I/O) and the projects written, with the Bio-Formats server builder
   selected explicitly.
5. QuPath's rejection of `:` inside a class component is confirmed; the case is
   documented as a negative fixture and no such object is created.

## Reproducibility

Two runs produce byte-identical image files and expected values, also into
different output directories. Two deliberate choices make this possible:

- the OME-XML UUID is the name-based UUID of formula
  `ome_uuid.name_uuid_v3.v1`, preset in `OMETiffWriter`'s UUID cache before it
  writes (Bio-Formats would otherwise draw a random UUID);
- every hierarchy root object receives the name-based ID `<entry_key>:root`
  (QuPath would otherwise serialise a random root UUID into `data.qpdata`).

QuPath project files still legitimately differ: `project.qpproj` holds
creation/modification timestamps, a random `randomizedName` per entry and the
absolute output root; `summary.json` holds a file time; `server.json` and
`data.qpdata` hold the absolute output root. The manifest therefore lists the
normalisation rules for each file and a `sha256_normalised`. In the
Java-serialised `data.qpdata` the root sits inside string records that carry
their own length, so the `root` rule also recomputes the length of each such
record. Normalised digests are then equal across runs into the same directory
and into directories of any other path length.
