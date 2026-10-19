# qupflowR JSON schemas and shared fixtures

This directory is installed with the R package and read by the Java bridge
tests. R and Java validate the same documents with the same rules, so a file
here is a contract, not an example.

```text
schema/
  protocol/v1/
    index.json                 schema set metadata and the list of schemas
    error-codes.json           error code registry (R conditions and Java)
    *.schema.json              Draft-07 schemas of protocol 1.x
    fixtures/valid/            documents that must validate
    fixtures/invalid/          documents that must fail, with *.expect.json
    fixtures/canonical/        canonical JSON vectors
  annotatr-control/v1/
    index.json                 vocabulary set metadata
    capabilities.schema.json   capability report of the hyperannotatR integration
    fixtures/valid/            documents that must validate
    fixtures/invalid/          documents that must fail, with *.expect.json
```

Versions are separate fields: the schema set is `1.0.0`, the protocol is
`1.0` (major `v1` in the path). A document with another protocol major
version fails the `protocol_version` pattern and is reported as
`PROTOCOL_MISMATCH` by the client.

## Schemas

| Schema | Describes |
|---|---|
| `common` | shared definitions: `uuid`, `sha256`, `date_time`, `revision`, `image_id`, `object_id`, `plane`, bounds, `value_state`, measurement values, class paths, ROI (`wkb`/`base64`) and native ROI parameters, warnings |
| `envelope-success`, `envelope-error` | response envelopes; unknown optional members are allowed |
| `error` | the error object of an error envelope |
| `health` | `GET /v1/health` data; `fx_thread_blocked` is boolean for `live`, `null` for `worker` |
| `capabilities` | capability entries with `available`, `reason`, `modes`, `tested_versions`, `limits` |
| `discovery-manifest` | runtime manifest; names the token file and never holds the token |
| `session`, `image-entry`, `image-info` | sessions, project image entries, dimensions and calibration |
| `object-record`, `measurement-record` | objects (docs/07) and long-format measurements |
| `patch-request`, `patch` | patch request body and patch resource |
| `event`, `job` | events and jobs |
| `error-codes` | structure of `error-codes.json` |

Request bodies (`patch-request`) reject unknown members. Response documents
accept unknown members so that a newer minor version can add optional fields.

Rules that a Draft-07 subset cannot express are semantic checks for the
bridge and the R client (T02/T04), not schema rules:

- unique `object_id` values across the objects of one page or patch
  (`uniqueItems` compares whole items; lists of IDs such as
  `object.delete.object_ids` and selection payloads do use `uniqueItems`);
- stale `expected_revision`, snapshot expiry and idempotency key reuse;
- parent existence, no self-reference and no cycles; a new object's parent in
  the same patch;
- geometry validity (a WKB that parses, is not empty and has closed rings) and
  a nucleus inside its cell;
- `channels` length equal to `size_c`, `progress.completed <= total`, `z` and
  `t` inside the image dimensions;
- `message_key` equal to `error.` plus the lower-case `code`;
- class name components without leading or trailing Unicode white space
  (the pattern checks space and tab only).

## hyperannotatR capability vocabulary (decision D029)

`annotatr-control/v1/capabilities.schema.json` describes the capability
report of the optional hyperannotatR (formerly annotatR) integration
(docs/22). It is a separate, self-contained schema set with its own
vocabulary version (`1.0`); it does not reference the protocol set. The
report lists the compatibility profiles by id, each with a fixed name, a
state and, unless available, a reason:

| Profile | Name | Possible states in vocabulary 1.x |
|---|---|---|
| `I0` | `file_interchange` | `available`, `unavailable`, `unsupported`, `planned` |
| `I1` | `r_interface` | `available`, `unavailable`, `unsupported`, `planned` |
| `I2` | `controlled_process` | `unsupported`, `planned` |
| `I3` | `embedded_app` | `unsupported`, `planned` |
| `I4` | `shared_validation` | `available`, `unavailable`, `unsupported`, `planned` |

`available`: usable now. `unavailable`: supported, but a local prerequisite
is missing. `unsupported`: this qupflowR or hyperannotatR version cannot
provide it. `planned`: a later qupflowR task implements it. Reason codes:
`package_not_installed`, `package_version_too_old`,
`adapter_not_implemented`, `partner_release_required`,
`control_protocol_mismatch`, `validation_contract_missing`. I2 and I3 need
hyperannotatR (annotatR 0.2.0 or later), which publishes `annotatr-control-v1`
and a public app builder; enabling them needs a new vocabulary version.
This version of qupflowR has no hyperannotatR adapter; the vocabulary is
the contract that the adapter (T05/T06) and `qp_annotatr_capabilities()`
will report in. The fixtures follow the rules of the protocol corpus below.

The identifiers keep the old spelling: the directory `annotatr-control`, the
schema set `qupflowr-annotatr-control`, the vocabulary
`qupflowr-annotatr-capabilities`, the field `annotatr_version` and the
partner contract `annotatr-control-v1`. Histology and cell annotation moved
from annotatR to segmantR, the planned histology partner
(`segmantR-control-v1`; there is no segmantR adapter yet).

## Validator subset (decision D022)

Supported keywords: `$schema`, `$id`, `$ref`, `definitions`, `title`,
`description`, `type`, `properties`, `required`, `additionalProperties`,
`items` (single schema), `minItems`, `maxItems`, `uniqueItems`, `enum`,
`const`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`,
`minLength`, `maxLength`, `pattern`, `format`, `oneOf`, `anyOf`, `allOf`,
`not`. A schema with any other keyword is rejected when it is loaded
(`SCHEMA_INVALID`). The R validator is `R/json-schema.R`; the Java validator
is `io.github.cttir.qupflowr.protocol.schema.SchemaValidator` in
`bridge-protocol`, with JSON input read by
`io.github.cttir.qupflowr.protocol.json.CanonicalJsonReader` and canonical
JSON written by `CanonicalJson`.

Semantics both validators implement:

- `$schema` must be `http://json-schema.org/draft-07/schema#` at the root and
  may not appear elsewhere; `$id` is informational and root only.
- `$ref` is either a fragment (`#/definitions/x`) or a relative file path
  with an optional fragment. The file is resolved against the directory of
  the referring file and must stay inside the schema root, also after
  resolving symbolic links. URLs, absolute paths and backslashes are
  rejected; nothing is fetched. A JSON pointer segment is first
  percent-decoded as UTF-8 and then unescaped (`~1` to `/`, `~0` to `~`). A
  `%` not followed by two hexadecimal digits, bytes that are not UTF-8, an
  encoded NUL and array indices with more than nine digits name nothing
  (`unresolved_ref`). A `$ref` to a string or number is `schema_not_object`;
  a `$ref` to `null` or to an array is a schema without keywords. A `$ref`
  may only have `title`, `description` and, at a root, `$schema`, `$id` and
  `definitions` beside it; siblings are otherwise a schema error because
  Draft-07 ignores them.
- `type: integer` accepts any number with a zero fractional part (`1.0`).
- `enum`, `const` and `uniqueItems` compare JSON values: numbers by value,
  objects independent of member order.
- `minLength` and `maxLength` count Unicode code points.
- `pattern` is not anchored implicitly. Patterns must mean the same in
  ECMA-262, PCRE and `java.util.regex`. Checked first, before compiling
  (`pattern_not_portable`): the escapes `\0`, `\C`, `\g`, `\N` and `\o`; a
  `[` inside a bracket expression (POSIX classes in PCRE, unions in Java)
  and `&&` there (intersections in Java); a `{` outside bracket expressions
  that does not start `{n}`, `{n,}` or `{n,m}` (escape it as `\{`). Then a
  pattern must compile (`pattern_does_not_compile`; Java also refuses what
  PCRE2 refuses, such as `\u` escapes and back references to missing groups)
  and must not use `\d`, `\w`, `\s`, `\b` or other shorthand classes,
  look-around, back references, inline flags, possessive quantifiers or an
  unescaped `.`; `^` only first and `$` only last. A final `$` matches only
  at the end of the input (evaluated as `\z`), so a trailing newline does
  not match. The first problem found is the reported reason.
- `format` is asserted, not an annotation:
  - `uuid`: lower-case `8-4-4-4-12` hexadecimal, as `java.util.UUID#toString`
    writes it;
  - `sha256`: 64 lower-case hexadecimal digits;
  - `date-time`: RFC 3339 with upper-case `T` and `Z`, a real calendar date,
    hours 00-23, minutes 00-59, seconds 00-59 (no leap second), optional
    fraction, offset hours 00-23;
  - `uri-reference`: none of the code points U+0001-U+0020, U+007F-U+009F,
    U+1680, U+2000-U+2006, U+2008-U+200A, U+2028, U+2029, U+205F and U+3000
    (white space and control characters, the same list in every locale), no
    `<`, `>`, `"`, `{`, `}`, `|`, backslash, `^` or backtick, and every `%`
    followed by two hex digits. Other non-ASCII characters are allowed (IRI
    references), so project paths with umlauts need no extra encoding.
- Numbers are compared as doubles in `minimum`, `maximum`, `enum`, `const`
  and `uniqueItems`.

Error reporting, used by the fixture sidecars:

- Every failing keyword yields one error with the JSON pointer of the
  instance location (`""` is the root), the keyword and the schema path
  `<file>#<pointer to the keyword>`.
- `required` is reported at the object. `additionalProperties: false` is
  reported at the extra member. A `false` schema reports the keyword `false`.
- `oneOf`, `anyOf` and `not` report one error at their own location and none
  of their branch errors. `allOf` branches and `$ref` targets report their
  errors as they are.
- Keywords that do not apply to the instance type are ignored.
- Errors are compared as a set of `(path, keyword, schema_path)`.

## JSON conventions (R)

`qp_json_encode()` and `qp_json_decode()` implement one convention:
`NULL` is `null`; values marked with `qp_scalar()` are JSON scalars; every
other atomic vector is an array, also at length 1; unnamed lists are arrays;
named lists are objects; `qp_empty_object()` is `{}`. `NaN`, `Inf` and
`-Inf` are never written: measurements carry `value` and `value_state`
(`finite`, `missing`, `nan`, `pos_inf`, `neg_inf`), and only `finite` has a
number. Whole doubles up to 2^53 are written as plain digits. Decoding
rejects duplicate or empty member names, a byte-order mark, invalid UTF-8,
escaped NUL characters, unpaired surrogate escapes, numbers outside the
double range and nesting deeper than 128 levels. It also rejects comments,
the white space characters U+000B and U+000C between tokens and an
unterminated string anywhere, including after a complete value such as
`[1]"` (`JSON_INVALID`, reason `syntax`). The underlying R parser (yajl)
accepts these, RFC 8259 does not.

The checks before parsing locate string literals with one linear scan,
defined identically in R (`core_json_scan()`) and Java (`JsonTextScan`):

- a quotation mark delimits a string unless an odd number of reverse
  solidi immediately precede it;
- delimiters alternate between opening and closing a literal;
- an opening delimiter without a closing one starts an unterminated string
  that runs to the end of the text.

For valid JSON this is the grammar's own notion of a string; for invalid
text it fixes which defect is reported first. No check uses a backtracking
pattern over the whole text, so their time grows linearly with its length
(an unterminated string of 1 MiB of escaped quotation marks is rejected in
well under a second in both languages).

Decoding checks run in this order in R and Java, and the first failure is
reported:

1. text encoding (NUL byte, UTF-8, byte-order mark);
2. escape scan, only inside complete string literals: any escaped U+0000
   (`nul_character`) first, then unpaired surrogates. A high surrogate
   escape is paired only with a low surrogate escape that follows it
   directly in the text, so `"\ud800\\\udc00"` (an escaped reverse solidus
   between them) is `unpaired_surrogate`;
3. bracket depth outside string literals for texts longer than 256 bytes;
   brackets inside an unterminated string do not count;
4. grammar before parsing: an unterminated string, then a solidus, U+000B
   or U+000C outside string literals;
5. parsing (every other grammar violation is `syntax`);
6. a depth-first walk that checks depth, empty and duplicate member names
   and the number range.

For JSON text, `qp_json_canonical()` first scans number tokens outside
string literals (fraction or exponent, then more than 16 digits or a value
above 2^53 - 1; the characters of an unterminated string are not scanned)
and then decodes.

## Canonical JSON

`fixtures/canonical/` holds the digest vectors. For each accepted case
`<name>.json` is the input, `<name>.canonical` the exact expected bytes and
`<name>.sha256` their SHA-256 in `sha256sum` format. `reject-<name>.json`
inputs must fail with the code and reason in `reject-<name>.expect.json`.

Rules:

1. No insignificant white space and no trailing newline.
2. Members sorted by name as sequences of Unicode code points (equal to the
   order of the UTF-8 bytes). This differs from RFC 8785, which compares
   UTF-16 code units, when names mix characters above U+FFFF with characters
   from U+E000 to U+FFFF (vector `unicode-key-order`). Names are unique and
   non-empty.
3. Strings in UTF-8; `"` and `\` escaped as `\"` and `\\`; U+0008, U+0009,
   U+000A, U+000C, U+000D as `\b`, `\t`, `\n`, `\f`, `\r`; other characters
   below U+0020 as `\u00xx` with lower-case hex; everything else literal,
   including U+007F, U+2028 and non-ASCII characters.
4. Numbers are integers with magnitude at most 2^53 - 1, written as digits
   with an optional minus sign; `-0` becomes `0`. Non-integer numbers are
   rejected (`JSON_UNREPRESENTABLE`, reason `non_integer_number`): exact
   shortest round-trip formatting of doubles is not guaranteed to agree
   between R and Java, so digested documents store fractional values as
   decimal strings. In JSON text every number token with a fraction or an
   exponent is rejected, even `1.0`; larger integers give
   `integer_out_of_range`.
5. Arrays keep their order.

The expected bytes were typed by hand and cross-checked once with an
independent implementation (Python `json.dumps` with sorted keys, compact
separators and `ensure_ascii=False`); the digests were checked with
`sha256sum -c`.

## Fixture corpus

File names are `<schema>--<case>.json`; the schema name is the part before
`--`. Every schema listed with `"fixtures": true` in `index.json` has at
least one valid and three invalid fixtures that break different rules.

Each invalid fixture has a sidecar `<schema>--<case>.expect.json`:

```json
{
  "schema": "object-record",
  "valid": false,
  "rule": "c = -1 is QuPath's internal sentinel; the protocol uses c = null",
  "errors": [
    {"path": "/plane/c", "keyword": "minimum",
     "schema_path": "common.schema.json#/definitions/plane/properties/c/minimum"}
  ]
}
```

The expected errors were declared from the schema rules before the
validators ran and are compared as an exact set.

Valid object fixtures may have a sidecar with `"wkb"` expectations that map a
JSON pointer to the geometry type and coordinates. The base64 WKB was
computed from these coordinates with an independent encoder (little-endian
ISO WKB, 2D) and the R test decodes it with its own reader:

| Fixture pointer | Geometry |
|---|---|
| `object-record--annotation-rectangle` `/roi/data` | `POLYGON((0 0, 100 0, 100 80, 0 80, 0 0))` |
| `object-record--cell-with-nucleus` `/roi/data` | `POLYGON((10 20, 18 20, 18 26, 10 26, 10 20))` |
| `object-record--cell-with-nucleus` `/nucleus_roi/data` | `POLYGON((12 22, 16 22, 16 24, 12 24, 12 22))` |
| `object-record--detection-point-channel` `/roi/data` | `POINT(42.5 17.25)` |

All IDs, names and values are synthetic.
