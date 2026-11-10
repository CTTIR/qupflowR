## Submission

This is a new submission.

## R CMD check results

0 errors | 0 warnings | 1 note

* checking CRAN incoming feasibility ... NOTE
  Maintainer: 'R. Heller <raban.heller@uni-ulm.de>'
  New submission
  Suggests or Enhances not in mainstream repositories:
    hyperannotatR
  Availability using Additional_repositories specification:
    hyperannotatR   yes   https://cttir.r-universe.dev

  This is a first submission. 'hyperannotatR' is an optional partner
  package from the same group, available from the declared
  `Additional_repositories`; it is not loaded by the package code, tests,
  examples or vignette.

## Test environments

* Local: macOS (aarch64-apple-darwin), R 4.6.1,
  `R CMD check --as-cran --no-manual` (no LaTeX installation is available
  locally, so the PDF manual was not built there).
* GitHub Actions (r-lib/actions v2, `--as-cran`): macos-latest (release),
  windows-latest (release), ubuntu-latest (devel, release, oldrel-1).

## Notes

* 'QuPath' and a 'Java' runtime (listed in SystemRequirements) are only
  needed for the planned optional bridge. They are never required to
  install, load or check the package; tests that would need them are
  skipped unless explicitly configured through environment variables.
* Examples, tests and the vignette use synthetic data, need no network
  access and write only below `tempdir()`.
