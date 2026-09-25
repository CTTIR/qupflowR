# qupflowR

Experimental R foundations for a planned QuPath interface. This first source
milestone contains classified conditions and bilingual messages, protocol JSON
helpers, a JSON Schema subset validator, synthetic contract fixtures and
`qp_run_external()`, a pinned external-method execution boundary.

The live Java bridge, interactive application, project editing, queued jobs and
shareable workflow engine are not included in this milestone. The protocol
schemas describe future interfaces; their presence does not implement them.
No release or CRAN acceptance is claimed.

## Installation and requirements

R >= 4.5.0 is declared. The current candidate was checked locally on Linux with
R 4.6.1; the complete cross-platform CI matrix is a separate qualification.
Java and QuPath are not needed to install or load these foundations. Optional
packages listed in Suggests support tests or planned features and are loaded
only where used. No runtime or model is downloaded automatically.

From a checked-out source tree, use `R CMD build .`, then
`R CMD INSTALL qupflowR_0.0.0.9000.tar.gz`. The getting-started vignette and
`help(package = "qupflowR")` describe the available interfaces.

## Existing-method execution

`qp_run_external()` requires explicit trust, an executable/argument vector,
SHA-256 pins for declared executable/script/runtime/model/input files and a new
workspace. It uses a private child environment, checks inputs again after the
process, retains logs and a receipt, and hashes required outputs. Receipts may
contain private paths or caller environment values and belong with private run
artifacts, not in public repositories.

The external-execution profile is Linux-only. It is a hygiene boundary for
trusted code, not a sandbox. Callers must declare transitive dependencies and
validate analytical outputs themselves. `execution_complete` means process
success and declared output presence, not biological validity or equivalence.
Scripts, model versions, detector settings and measurement definitions are not
rewritten. An older QuPath command-line method can therefore be executed
independently of the planned QuPath 0.7/Java 25 live bridge.

## Validation and development

Run `testthat::test_local()` from R. Generated native-image tests require a
separate explicitly configured fixture tree; their skips do not qualify native
QuPath execution. The synthetic schema/geometry oracles are included. The
native fixture generator and Java scaffold are outside this source milestone.

The retained quality gate supports lint, API and coverage checks:

```sh
QUPFLOWR_CTTIR_GITHUB= Rscript tools/quality.R --mode all --report
```

An empty `QUPFLOWR_CTTIR_GITHUB` selects the hash-pinned vendored house linters;
no local sibling checkout is required. The committed API history and coverage
baseline are retained unchanged. Shared CI workflow callers are pinned to their
existing source revision. Hosted CI success, browser behavior, native runtime
qualification and second-computer reproduction are separate evidence states.

## License

GPL (>= 3), with the full text in `LICENSE.md`. Package code is attributed to
the maintainer declared in DESCRIPTION. External engines and dependencies are
not bundled and retain their own licenses. MIT consumers should use the agreed
external process/file interface rather than silently importing GPL code.
