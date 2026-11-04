# Foundation maintenance audit, 2026-11-04

Baseline: public main `cc4eea950910d8e98bc60d629904b7a132ae3132`. At intake, the clean original checkout was fingerprinted with 579 file hashes; implementation and qualification used an isolated maintenance worktree. This milestone retains the implemented package foundations and bridge scaffold. It does not implement the planned bridge client, Shiny application, project editing or workflow engine.

## Findings and acceptance

1. **Schema admission (high):** a local `$ref` into arbitrary `const`/`enum` data can name null or an array and silently accept any instance; a data object can bypass the declared subset lint. References must resolve to a supported object/boolean schema, with existing classified errors and valid references unchanged. R and Java must agree. Draft-07 core section 4.3.1 requires object/boolean schema values: <https://json-schema.org/draft-07/draft-handrews-json-schema-01>.
2. **Numeric admission (high):** `1e-400` and `-1e-400` decode to zero although the API rejects values outside its binary64 range. Reject nonzero literals rounded to zero, preserving true zero, representable subnormals, ordinary rounding, error paths and precedence. This is the package's declared bounded representation contract; RFC 8259 permits implementations to limit numeric range/precision: <https://www.rfc-editor.org/rfc/rfc8259#section-6>.
3. **External execution (high):** special-file pins can block checksum reads before the child timeout, and the predictable receipt partial path follows a symlink and overwrites an external file. Require regular files at pin/recheck/output boundaries and install complete receipts through exclusively owned temporary files. Preserve the explicitly trusted Linux execution contract; this is not a sandbox.

4. **Bridge transport admission (high, scaffold):** full connection tables can reject a healthy client while readers have pending input. A second reproduced race evicts a complete request while its buffered header is still being validated. The original code treats an empty socket buffer as proof that parsing has stalled. Coordinate nonblocking header reads and eviction under a per-connection state guard, protect active parsing, and allow a bounded progress wait outside locks. Retain connection/grace/deadline limits and existing body streaming. This concerns the optional Java scaffold; it does not imply that the R package already has a live bridge client.

## Roadmap

- Repair R/Java schema and numeric admission together with shared numerical vectors, baseline-failing regressions and independent checks. Keep existing wire IDs and schema fixtures unchanged.
- Repair external pin/output/receipt boundaries with real Linux subprocess/failure-path tests and independent review.
- Verify the transport repair against baseline-failing real-socket regressions, the unchanged original stress tests, all affected Java modules and resource cleanup.
- Run the existing R tests, package checks, coverage/API/lint gates and isolated Java unit/contract checks. Record optional native QuPath limitations separately; no live-integration or deployment claims without execution.
- Review documentation and exact source package, preserve sole human authorship, then fetch and integrate current main before normal publication after validation closes.

## Baseline evidence

The installed baseline R suite passed 3,504 assertions with zero failures, errors or warnings. Four individual tests skipped because generated native QuPath fixtures are absent; the external-run test file is additionally skipped on macOS. A post-test CSV serialization error affected only the evidence harness; results were recovered from the retained RDS without rerunning tests. External Linux and Java qualification are separate lanes.

Detailed immutable source, probes, original failures and final logs are retained in the external audit evidence directory. Observed results and limits follow.

## Implemented and independently reviewed

The schema and numeric repairs now share regression vectors across R and Java. Invalid reference targets are rejected rather than treated as permissive schemas; nonzero numeric literals that round to zero are refused while representable subnormals and genuine zero remain valid. Existing schema fixtures and wire identifiers are preserved.

External execution now requires regular files before hashing and when checking declared outputs. Receipt publication uses an exclusively created temporary sibling, and a replaced workspace symlink is refused. Complex time limits receive the documented classified validation error. These checks support explicitly trusted executables; they do not provide a hostile-code sandbox or eliminate arbitrary concurrent filesystem races.

The developer branding scripts now satisfy explicit name binding and classified-error conventions. Independent rendering compared all 31 registered assets and 270 distinct motif variants with the original renderer: every asset was byte-identical, and decoded PNG pixels were identical. No committed image was regenerated. The package site receives scoped contrast corrections for syntax comments and rendered output in both themes.

## Observed qualification

- Final source archive SHA-256: `aafdf28a60010ed3386e79c151f28b2e09da134d51fda7fc45f1c31f207e078b`.
- macOS R 4.6.1 full installed check: 0 errors, 0 warnings, 1 development/incoming NOTE; 3,601 passing assertions and six documented skips. The check completed successfully; its surrounding evidence wrapper later encountered a shell variable error, recorded separately.
- Required/test-dependency-only installed check: 3,589 passing assertions and nine optional/platform skips. Its 1 warning and 2 NOTEs concern unavailable optional vignette tools and development/incoming metadata; this is not a warning-free documentation build.
- All 15 installed first-use checks passed. The complete 18-page reference manual and 25-page site were reviewed; 1,126 local HTML/CSS references resolve. Theme and navigation checks cover the observed desktop/mobile states, not a full accessibility certification.
- API inventory: all 21 exports and two S3 methods match the existing baseline.
- The existing R/Java contract parity runner passed all 19 checks, including 200 accepted and 60 rejected generated documents, with identical canonical bytes and rejection reasons.

- Strict repository/shared lint: zero findings and zero suppression problems; no gate exceptions were added.
- Linux/amd64 under emulation, R 4.6.2: the exact installed archive passed 3,664 assertions with zero failures, errors or warnings and seven documented native-fixture/source-only/optional-ZIP skips. All external-run blocks executed. Seven public-API first-use checks passed, including pinned execution, output/receipt hashes and FIFO rejection. The saved suite result was recovered after a separate CSV-reporting error; the reporting helper was then repaired without rerunning the suite.
- The unchanged coverage gate passed at 99.4465% (1,617 of 1,626 executable lines); its committed 99.5% baseline and 0.5-point tolerance remain unchanged. External-run coverage is 169 of 169 lines. All 356 staged Linux qualification inputs retained their hashes.

## Bridge qualification and limits

The first admission retry fixed the unread-input backlog but exposed the separate buffered-parser race. The final seven real-socket regressions fail five cases on unchanged baseline production and retain two bounded-refusal controls. The header-ownership implementation passes all seven, while the unchanged full core suite retains an observed latency failure under Linux/amd64 emulation; isolated stress also exceeded the original one-second limit with successful HTTP responses. These failures remain recorded, not reclassified as passes.

A same-JVM cleanup probe completed 20 measured rounds across six exit paths, with the file-descriptor count remaining at 12 after warmup and every measured round. Native ARM qualification passed all 153 core tests with zero failures, errors or skips, plus strict core/protocol Javadoc. The observed native JVM has eight available processors, while the emulated Linux container has a two-CPU quota; the timing difference is not attributed solely to emulation. All 447 copied bridge/schema inputs matched the reviewed source. The added bridge-core workflow runs protocol/core checks and Javadoc on native Ubuntu with the same explicit Temurin release and the committed Gradle/dependency checksums. It retains test reports; hosted success will only be recorded after observation. The existing R reusable-workflow source pins are unchanged.

The final Linux integration/build run passed: protocol 328, extension 10 and worker 11 tests were rerun successfully; 24 fixture tests retained their earlier exact-source success and were reported UP-TO-DATE. Eight jar-content controls passed, both jars had zero content violations, and independent ZIP readback verified the bundle manifest, checksums and repaired compiled classes. The 416,904-byte bundle SHA-256 is `ac3643d37dd2646005bf77d068cce3d72dfc612a715d1f123ce6c010340ed08f`.

The source milestone is locally qualified as R foundations and a Java scaffold. It is not a native QuPath, clinical/scientific, live-bridge, deployment or CRAN acceptance declaration. Native-Linux hosted checks are a separate observed state; the new CI job has passed local syntax and independent review but has not run at this preparation checkpoint. No test threshold, coverage baseline, dependency lock or original stress assertion was weakened.

## Remaining roadmap

| Milestone | Outcome and dependencies | Validation and completion criteria |
| --- | --- | --- |
| Essential repairs — complete locally | Supported schema/numeric admission in R and Java; safe pinned Linux file/receipt boundaries; parser-owned bridge reads. Existing wire/schema contracts and declared dependencies retained. | Baseline-failing regressions, package checks, actual Linux execution, unchanged API/lint/coverage gates, native core suite and affected modules pass as recorded above. |
| Dependable maintenance — prepared | Dedicated native-Linux protocol/core CI with pinned tools and retained reports. | Observe the new workflow on the published commit; preserve and investigate any real failure. |
| Foundation usability — complete locally | Runnable examples, reviewed manual/site, readable code output and maintainable branding tools. | Installed first use, all local links, selected theme/navigation paths and exact original artwork preservation verified. |
| Future interface and scientific use — outside this milestone | Live QuPath client, Shiny/workflow implementation, native fixtures, real analytical inputs and target-device/runtime qualification. | Separate requirements, actual native integration and independent scientific validation; synthetic unit tests cannot satisfy these gates. |
| Formal submission — deferred | Development version and non-CRAN optional dependency/Remotes require an explicit release plan. | Resolve distribution requirements, qualify the final release archive and obtain any required submission authorization; no CRAN upload, tag or release was performed. |

## First use

R >= 4.5.0 is required; Java and QuPath are optional for these foundations. Install the development source:

```r
install.packages("remotes")
remotes::install_github("CTTIR/qupflowR", build_vignettes = FALSE)
library(qupflowR)
qp_json_decode(qp_json_encode(list(id = qp_scalar("synthetic-example"))))
```

For the trusted Linux external runner, install `digest`, `processx` and `fs`, then follow the pinned synthetic-process example in the vignette. The exact archive was installed into isolated package libraries on macOS and Linux; a separate GitHub installation run is not claimed by this record.
