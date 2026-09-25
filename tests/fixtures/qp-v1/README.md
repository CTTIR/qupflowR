# Expected values of synthetic fixture set 1

These tables describe what `tools/fixtures.R` (Java module
`tools/bridge/bridge-fixtures`) generates. They were written in R from hand
constants and closed-form formulas, not from generator output, and are checked
by `tests/testthat/test-fixtures-expected.R`: always for internal consistency,
and against a generated fixture tree when one exists (`QUPFLOWR_FIXTURES_DIR`,
default `work/fixtures/v1`).

Coordinates are full-resolution pixels, origin top-left, x to the right and y
downwards. `x`, `y`, `c`, `z` and `t` are zero-based; `c = NA` means no single
channel (QuPath stores -1). IDs are character strings.

| File | Content | Oracle |
|---|---|---|
| `pixel-samples.csv` | sample values of the RGB fixtures and all 12 OME planes | formulas `rgb.fields_gradient.v1`, `ome.linear_czt.v1` |
| `planes.csv` | IFD index to (c, z, t) | XYZCT order: ifd = z + 2 (c + 3 t) |
| `calibration.csv` | image size, pixel type, calibration and whether the file remains | generator constants |
| `objects.csv` | objects of both QuPath projects: IDs, parents, type, classes (`\|`-separated components), plane, areas, precision | hand constants; IDs by `object_id.name_uuid_v3.v1`, areas by `area.px2.v1` |
| `roi-coordinates.json` | ROI parameters, vertices and float32-stored vertices | hand constants; `storage.float32.v1` |
| `measurements.csv` | stored measurement inputs and stored values with IEEE 754 bits; absent names are `missing` | hand constants; `storage.float32.v1` for detections, cells and tiles |
| `layout.json` | output files, normalisation rules, absent paths, project entries, the moved project, the rejected class name | layout constants; file inventory and normalisation rules of QuPath 0.7.0 project files as observed |
| `digests.csv` | SHA-256 of the image files | `r_reconstruction` rebuilds the file bytes in R; `observed_pin_*` are regression pins of a writer, not oracles |

Formulas:

- `rgb.fields_gradient.v1`: for y < 192 the colour is
  `[[255,0,0],[0,255,0],[0,0,255],[17,99,201]][floor(x / 128)]`; otherwise
  r = floor(x / 2), g = 255 - floor(x / 2), b = floor((y - 192) / 2).
- `ome.linear_czt.v1`: v = 1000 c + 300 z + 60 t + (x mod 50) + (y mod 7),
  uint16, 256 x 192, 3 channels, 2 z-slices, 2 timepoints.
- `object_id.name_uuid_v3.v1`: UUID version 3 of the UTF-8 bytes of
  `qupflowr-fixtures-v1:<entry_key>:<object_key>` (MD5; version and variant
  bits set as in `java.util.UUID.nameUUIDFromBytes`). Hierarchy roots use
  `<entry_key>:root`, the OME-XML UUID uses `ome-tiff:<file name>`.
- `storage.float32.v1`: the stored value is the input rounded to the nearest
  float32 (overflow to infinity).
- `area.px2.v1`: rectangle w h; ellipse pi w h / 4; polygon shoelace of the
  input vertices; geometry exterior minus holes summed over polygons.

Tolerances: IDs, planes, classes, states, bits, counts and pixel values are
compared exactly; areas within 1e-9 relative (derived by different code in R
and Java).
