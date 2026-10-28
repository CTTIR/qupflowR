# qupflowR development

* Standardize the named v5 package logo across README, documentation pages
  and available application headers, with matching light and dark artwork.


* The partner package annotatR was renamed to hyperannotatR (HSI annotation).
  `Suggests` and `Remotes` now name hyperannotatR. The contract identifiers
  keep their names: the schema set `annotatr-control/v1`, the vocabulary
  `qupflowr-annotatr-capabilities`, the field `annotatr_version` and the
  partner contract `annotatr-control-v1`, published since annotatR 0.2.0.
  Descriptions and example reasons in that schema set now name hyperannotatR;
  validation is unchanged. Histology and cell annotation moved from annotatR
  to segmantR, the planned histology partner (`segmantR-control-v1`; there is
  no segmantR adapter yet).
* Made numeric test inputs portable across differing long-double precisions;
  the exact binary reference values and protocol assertions remain unchanged.
* Expanded installation and API guidance, documented scalar/array semantics,
  and added executable schema and synthetic external-method vignette examples.
* Included the grouped reference and article configuration for the shared
  CTTIR documentation theme. Native bridge qualification remains separate.

* Added `qp_run_external()` for explicitly trusted, hash-pinned external methods
  with argument-vector execution, isolated process settings, time limits, fresh
  output directories, input revalidation and machine-readable run receipts.
  This compatibility boundary preserves caller scripts and does not use or
  replace the developing live QuPath bridge.

# qupflowR 0.0.0.9000

Development version; nothing has been released yet.

## Package skeleton (task T01)

* New package skeleton at the repository root: `DESCRIPTION`, roxygen-generated
  `NAMESPACE`, GPL (>= 3) licence, testthat 3rd edition, a getting-started
  vignette and a pkgdown configuration that builds into `site/` with the
  themakR template.
* `library(qupflowR)` loads no optional package and starts no Java, QuPath,
  Python or browser process. Optional packages are checked where they are
  used, with a `qp_capability_error` (code `CAPABILITY_UNAVAILABLE`) that names
  the missing packages.
* Skip helpers for future integration tests detect QuPath, Java, the bridge
  artefact, an isolated display, a project-local browser and a Python profile
  without touching user profiles, and record machine-readable skip reasons.
  So far only their own unit tests use them.
* Package foundations: classified error conditions with one error code
  registry shared with the Java bridge, a German and English message
  catalogue, protocol JSON helpers with canonical JSON, and a JSON Schema
  subset validator with the protocol schemas and their shared fixture corpus.
  JSON text with comments, with form feed or vertical tab between tokens, or
  with an unterminated string (also after a complete value, as in `[1]"`) is
  rejected (`JSON_INVALID`, reason `syntax`), as in the Java bridge. The checks
  before parsing find string literals in one linear pass, so a long
  unterminated string of escaped quotation marks is rejected at once instead
  of taking minutes.
* Measurement names may be empty strings: QuPath stores such names and the
  protocol keeps names byte-exact.
* The schema set `annotatr-control/v1` defines the capability vocabulary of the
  optional annotatR integration: the profiles I0 to I4, their states and reason
  codes. The control profiles I2 and I3 can only be `unsupported` or `planned`
  until an annotatR release publishes its control contract. There is no
  annotatR adapter in this version.
* Quality tooling: thin GitHub Actions callers of the shared CTTIR workflows
  pinned to commit d54cd50, the shared `.lintr`, and `tools/quality.R` as the
  local equivalent: blocking lint without silent exclusions or `# nolint`
  markers, tests-only coverage with a floor of 90 % and a committed baseline
  that must not drop, and a public API inventory whose history is checked.
* `renv.lock` records the development library used for this version; renv is
  not activated for the project. Two entries cannot be restored with
  `renv::restore()`: annotatR 0.0.1 and themakR 0.1.0 were installed from local
  sources, so the lock file has no repository or commit for them.

## Dependency decisions

* R (>= 4.5.0). `R CMD check --as-cran` passes with R 4.6.1 and with R 4.5.3.
  R patch releases add no features, so these checks cover the 4.5 and 4.6
  series. R 4.5 is the oldrel-1 job of the shared CTTIR check workflow, the
  oldest blocking job there; an older minimum will be declared only after a
  check on that version.
* `Imports` holds only packages that the package code uses today: cli,
  jsonlite, stats, tibble and utils. Unused `Imports` produce an R CMD check
  NOTE, so the other planned core dependencies wait in `Suggests` and move to
  `Imports` together with the code that needs them: httr2 with the bridge
  client (T02); processx, callr, R6 and fs with worker control, connections
  and path handling (T03); digest and zip with workflow storage (T15, where
  SHA-256 digests are computed). digest is already used by the tests and the
  development scripts. The base package tools is added when code uses it.
* annotatR is an optional partner package in `Suggests`. It is not on CRAN, so
  `Remotes` points to its GitHub repository for development installs; those
  install its current development version, not the local 0.0.1 recorded in
  `renv.lock`.
