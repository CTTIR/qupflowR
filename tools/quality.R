# Local quality gate for qupflowR (validation lane L12).
#
# Modes:
#   lint      The CTTIR lint job exactly as the pinned shared workflow runs it
#             (house linters plus lintr::linters_with_defaults(), blocking),
#             and in addition the linter set declared in the repository
#             .lintr. Both sets are applied to the package tree and to the
#             development scripts under tools/ (including tools/bridge, apart
#             from Gradle output). Any finding fails. The mode also fails
#             when something could hide findings without a review: a .lintr
#             that differs from the shared file, a .lintr exclusion of
#             hand-written code, another lintr configuration that lintr
#             would read instead of the root .lintr (.lintr.R at the root,
#             .github/linters/.lintr[.R], .lintr[.R] below a linted
#             directory), the lintr.linter_file option or the
#             R_LINTR_LINTER_FILE variable, or a nolint comment marker.
#   coverage  covr::package_coverage(type = "tests") with the absolute floor
#             and the committed baseline tests/api/coverage-baseline.json. It
#             fails closed: an invalid baseline, failing tests, a modified
#             tests/testthat.R, changed covr exclusion options, a
#             .covrignore file or nocov comment markers fail the mode.
#   api       tools/api-inventory.R --check (exports, formals, S3 methods,
#             history digest).
#   all       All three modes; fails if any of them fails.
#
# Reviewed exceptions live in tests/api/quality-exceptions.json (optional):
#   {"exceptions_schema_version": 1,
#    "lintr_config": {"sha256": "<sha256 of .lintr>", "reason": "..."},
#    "nolint": [{"file": "R/x.R", "line": "<trimmed line>", "reason": "..."}],
#    "nocov": [{"file": "R/x.R", "line": "<trimmed line>", "reason": "..."}],
#    "covrignore": {"sha256": "<sha256 of .covrignore>", "reason": "..."}}
# Every exception needs a non-empty reason; a marker is matched by file and
# trimmed line text. A file that cannot be read as this structure fails.
#
# Usage (from the repository root):
#   Rscript tools/quality.R --mode lint|coverage|api|all [--report]
#     [--report-dir DIR] [--root DIR]
#   Rscript tools/quality.R --mode coverage --update-baseline --reason TEXT
#     [--allow-decrease]
#
# --report writes work/reports/lanes/L12-<mode>.json below the repository
# root, or into --report-dir. The environment variable QUPFLOWR_CTTIR_GITHUB
# optionally names a CTTIR/.github checkout; by default the vendored copy
# tools/lint-cttir.R is selected. An empty value also selects it. The house
# linter file must match the sha256 pinned below in both cases.
#
# Exit status: 0 pass, 1 quality failure, 2 usage or environment error.

cttir_github_ref <- "d54cd50b95ba0110dcc54e6371e3ebb82ee94afb"
house_linters_sha256 <-
  "d6b283c05090ca5e763ab9e7cfa74f39c0ad1ca3a63963505d833f178cf52905"
shared_lintr_sha256 <-
  "6a7619dd7d2febca449683a7afaa260698cada7835635273058e85b96e2883e5"
default_cttir_github <- ""
coverage_floor <- 90
coverage_tolerance <- 0.5
report_schema_version <- "1.0"
# lintr's and covr's default markers (lintr default_settings; covr .onLoad).
nolint_pattern <- "#[[:space:]]*nolint"
covr_defaults <- list(
  covr.exclude_pattern = "#[[:space:]]*nocov",
  covr.exclude_start = "#[[:space:]]*nocov[[:space:]]*start",
  covr.exclude_end = "#[[:space:]]*nocov[[:space:]]*end",
  covr.covrignore = ".covrignore"
)
# The standard testthat entry file; anything else could hide test failures
# from covr (for example stop_on_failure = FALSE).
testthat_entry <- c("library(testthat)", "library(qupflowR)",
                    "test_check(\"qupflowR\")")

quality_usage_error <- function(...) {
  message("quality: ", ...)
  quit(save = "no", status = 2L)
}

parse_quality_args <- function(args) {
  opts <- list(
    mode = NA_character_, root = ".", report = FALSE,
    report_dir = NA_character_, update_baseline = FALSE,
    reason = NA_character_, allow_decrease = FALSE
  )
  flags <- c("--report", "--update-baseline", "--allow-decrease")
  valued <- c("--mode", "--root", "--report-dir", "--reason")
  i <- 1L
  while (i <= length(args)) {
    arg <- args[[i]]
    key <- gsub("-", "_", sub("^--", "", arg), fixed = TRUE)
    if (arg %in% flags) {
      opts[[key]] <- TRUE
    } else if (arg %in% valued) {
      if (i == length(args)) quality_usage_error("missing value for ", arg)
      opts[[key]] <- args[[i + 1L]]
      i <- i + 1L
    } else {
      quality_usage_error("unknown argument ", arg)
    }
    i <- i + 1L
  }
  if (!opts$mode %in% c("lint", "coverage", "api", "all")) {
    quality_usage_error("--mode must be one of lint, coverage, api, all")
  }
  if (opts$update_baseline && !identical(opts$mode, "coverage")) {
    quality_usage_error("--update-baseline is only valid with --mode coverage")
  }
  if (!is.na(opts$report_dir)) {
    opts$report <- TRUE
  }
  opts
}

require_packages <- function(pkgs) {
  ok <- vapply(pkgs, requireNamespace, logical(1), quietly = TRUE)
  if (!all(ok)) {
    quality_usage_error("required packages not installed: ",
                        paste(pkgs[!ok], collapse = ", "))
  }
}

sha256_file <- function(path) {
  digest::digest(file = path, algo = "sha256")
}

empty_object <- function() {
  structure(list(), names = character())
}

package_version_or_null <- function(pkg) {
  if (!requireNamespace(pkg, quietly = TRUE)) {
    return(NULL)
  }
  as.character(utils::packageVersion(pkg))
}

is_single_string <- function(x) {
  is.character(x) && length(x) == 1L && !is.na(x) && nzchar(trimws(x))
}

# The source inventory of tools/lib/common.R (also used by tools/validate.R):
# sha256 over sorted "<sha256>\t<path>\n" lines of every file git tracks or
# would track, so lane and mode reports name the same state.
source_inventory_summary <- function(root) {
  tl <- new.env()
  sys.source(file.path(root, "tools", "lib", "common.R"), envir = tl)
  inventory <- tl$source_inventory(root)
  tl$inventory_summary(inventory)
}

git_head <- function(root) {
  if (!requireNamespace("processx", quietly = TRUE)) {
    return(NULL)
  }
  res <- tryCatch(
    processx::run("git", c("-C", root, "rev-parse", "--verify", "HEAD"),
                  error_on_status = FALSE),
    error = function(e) NULL
  )
  if (is.null(res) || res$status != 0L) {
    return(NULL)
  }
  trimws(res$stdout)
}

write_report <- function(opts, root, mode, status, body) {
  if (!opts$report) {
    return(invisible(NULL))
  }
  dir <- if (is.na(opts$report_dir)) {
    file.path(root, "work", "reports", "lanes")
  } else {
    opts$report_dir
  }
  dir.create(dir, recursive = TRUE, showWarnings = FALSE)
  path <- file.path(dir, paste0("L12-", mode, ".json"))
  quality_ids <- list(
    lint = c("Q07", "Q08"), coverage = "Q09", api = c("Q17", "Q19"),
    all = c("Q07", "Q08", "Q09", "Q17", "Q19")
  )
  inventory <- source_inventory_summary(root)
  report <- c(list(
    report_schema_version = report_schema_version,
    task_id = Sys.getenv("QUPFLOWR_TASK_ID", "T01"),
    gate_id = NULL,
    feature_ids = list(),
    quality_ids = as.list(quality_ids[[mode]]),
    lane_id = "L12",
    mode = mode,
    generated_at = format(Sys.time(), "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"),
    source_revision = git_head(root),
    source_inventory_sha256 = inventory$sha256,
    source_inventory = inventory,
    artifact_digests = empty_object(),
    fixture_digests = empty_object(),
    runtime_profile = list(
      r_version = as.character(getRversion()),
      platform = R.version$platform,
      packages = list(
        lintr = package_version_or_null("lintr"),
        covr = package_version_or_null("covr"),
        roxygen2 = package_version_or_null("roxygen2"),
        pkgload = package_version_or_null("pkgload"),
        testthat = package_version_or_null("testthat")
      )
    ),
    result = if (status == 0L) "pass" else "fail",
    exit_code = status,
    skip_reason = NULL
  ), body)
  json <- jsonlite::toJSON(report, auto_unbox = TRUE, pretty = TRUE,
                           null = "null", na = "null", digits = NA)
  writeLines(json, path, useBytes = TRUE)
  message("quality: report written to ", path)
  invisible(path)
}

# ---- reviewed exceptions ----------------------------------------------------

exceptions_path <- function(root) {
  file.path(root, "tests", "api", "quality-exceptions.json")
}

# Returns list(ok, problems, lintr_config, nolint, nocov, covrignore). A
# missing file means no exceptions; an unreadable or malformed one is a
# problem, never a silent "no exceptions".
read_exceptions <- function(root) {
  none <- list(ok = TRUE, problems = character(), lintr_config = NULL,
               nolint = list(), nocov = list(), covrignore = NULL)
  path <- exceptions_path(root)
  if (!file.exists(path)) {
    return(none)
  }
  fail <- function(problem) {
    none$ok <- FALSE
    none$problems <- paste("tests/api/quality-exceptions.json:", problem)
    none
  }
  raw <- tryCatch(jsonlite::fromJSON(path, simplifyVector = FALSE),
                  error = function(e) NULL)
  if (!is.list(raw) || is.null(names(raw))) {
    return(fail("not a JSON object"))
  }
  known <- c("exceptions_schema_version", "lintr_config", "nolint", "nocov",
             "covrignore")
  if (length(setdiff(names(raw), known)) > 0L) {
    return(fail(paste("unknown members",
                      toString(setdiff(names(raw), known)))))
  }
  if (!identical(raw$exceptions_schema_version, 1L)) {
    return(fail("exceptions_schema_version must be 1"))
  }
  digest_entry_ok <- function(x) {
    is.null(x) || (is.list(x) && is_single_string(x$sha256) &&
                     grepl("^[0-9a-f]{64}$", x$sha256) &&
                     is_single_string(x$reason))
  }
  entry_ok <- function(e) {
    is.list(e) && is_single_string(e$file) && is_single_string(e$line) &&
      is_single_string(e$reason)
  }
  marker_list_ok <- function(x) {
    is.null(x) ||
      (is.list(x) && is.null(names(x)) &&
         all(vapply(x, entry_ok, logical(1))))
  }
  if (!digest_entry_ok(raw$lintr_config) || !digest_entry_ok(raw$covrignore)) {
    return(fail("lintr_config and covrignore need sha256 and a reason"))
  }
  if (!marker_list_ok(raw$nolint) || !marker_list_ok(raw$nocov)) {
    return(fail("nolint and nocov entries need file, line and a reason"))
  }
  list(ok = TRUE, problems = character(), lintr_config = raw$lintr_config,
       nolint = if (is.null(raw$nolint)) list() else raw$nolint,
       nocov = if (is.null(raw$nocov)) list() else raw$nocov,
       covrignore = raw$covrignore)
}

# Lines of `files` (relative to root) that match `pattern`, as a data frame
# with the trimmed line text and whether a reviewed exception covers it.
find_markers <- function(root, files, pattern, allowed) {
  rows <- list()
  for (file in files) {
    lines <- readLines(file.path(root, file), warn = FALSE, encoding = "UTF-8")
    hits <- which(grepl(pattern, lines, perl = TRUE))
    for (n in hits) {
      text <- trimws(lines[[n]])
      reviewed <- any(vapply(allowed, function(e) {
        identical(e$file, file) && identical(trimws(e$line), text)
      }, logical(1)))
      rows[[length(rows) + 1L]] <- data.frame(
        file = file, line_number = n, line = text, reviewed = reviewed,
        stringsAsFactors = FALSE
      )
    }
  }
  if (length(rows) == 0L) {
    return(data.frame(file = character(), line_number = integer(),
                      line = character(), reviewed = logical(),
                      stringsAsFactors = FALSE))
  }
  do.call(rbind, rows)
}

# ---- lint -----------------------------------------------------------------

resolve_house_linters <- function(root) {
  checkout <- Sys.getenv("QUPFLOWR_CTTIR_GITHUB", default_cttir_github)
  candidate <- file.path(checkout, "config", "lint-cttir.R")
  if (nzchar(checkout) && file.exists(candidate)) {
    origin <- "checkout"
    path <- candidate
  } else {
    origin <- "vendored"
    path <- file.path(root, "tools", "lint-cttir.R")
    if (!file.exists(path)) {
      quality_usage_error("house linters not found in ", checkout,
                          " and no vendored copy at ", path)
    }
  }
  sha <- sha256_file(path)
  if (!identical(sha, house_linters_sha256)) {
    quality_usage_error(
      "house linters at ", path, " have sha256 ", sha,
      ", not the content of CTTIR/.github@", cttir_github_ref, ". Point ",
      "QUPFLOWR_CTTIR_GITHUB at a checkout of that commit, or set it to ",
      "an empty value to use the vendored copy."
    )
  }
  list(path = normalizePath(path), origin = origin, sha256 = sha)
}

# Evaluates one field of the repository's own .lintr, as lintr itself does
# when it reads the file; no other input is evaluated.
lintr_config_field <- function(root, field, warnings_env) {
  cfg <- read.dcf(file.path(root, ".lintr"), all = TRUE)
  if (!field %in% colnames(cfg)) {
    return(NULL)
  }
  expr <- parse(text = cfg[[field]])[[1L]]
  withCallingHandlers(
    eval(expr, envir = new.env(parent = asNamespace("lintr"))),
    warning = function(w) {
      warnings_env$items <- c(warnings_env$items, conditionMessage(w))
      invokeRestart("muffleWarning")
    }
  )
}

# .lintr exclusions that name existing hand-written code. Generated files
# that do not exist (R/RcppExports.R, R/cpp11.R) and data-raw are harmless;
# tests/testthat.R is the pinned testthat entry file.
forbidden_lintr_exclusions <- function(root, exclusions) {
  if (length(exclusions) == 0L) {
    return(character())
  }
  entries <- names(exclusions)
  if (is.null(entries)) {
    entries <- rep("", length(exclusions))
  }
  unnamed <- !nzchar(entries)
  entries[unnamed] <- vapply(exclusions[unnamed], function(e) {
    paste(as.character(e), collapse = "")
  }, character(1))
  paths <- sub("/+$", "", sub("^\\./", "", entries))
  code_area <- grepl("^(R|tests|inst|vignettes|tools)(/|$)", paths)
  exists_now <- file.exists(file.path(root, paths))
  sort(unique(paths[code_area & exists_now & paths != "tests/testthat.R"]),
       method = "radix")
}

# lintr configuration files that could be read instead of the checked root
# .lintr. lintr (find_local_config()) looks in the directory of the linted
# path and then in each parent for <dir>/.lintr.R, <dir>/.lintr,
# <dir>/.github/linters/.lintr.R and <dir>/.github/linters/.lintr, in this
# order, and takes the first that exists: a root .lintr.R replaces the root
# .lintr for the whole package, and a .lintr or .lintr.R below a linted
# directory replaces it for the files linted from there. Names are matched
# case-insensitively because file systems on macOS and Windows are. Gradle
# output below tools/bridge is not linted.
other_lintr_configs <- function(root) {
  pattern <- "^\\.lintr(\\.r)?$"
  at_root <- list.files(root, pattern = pattern, all.files = TRUE,
                        ignore.case = TRUE, include.dirs = TRUE)
  found <- at_root[at_root != ".lintr"]
  linters_dir <- file.path(".github", "linters")
  in_github <- list.files(file.path(root, linters_dir), pattern = pattern,
                          all.files = TRUE, ignore.case = TRUE,
                          include.dirs = TRUE)
  found <- c(found, file.path(linters_dir, in_github))
  linted <- c("R", "tests", "inst", "vignettes", "data-raw", "demo", "exec",
              "tools")
  for (d in linted[dir.exists(file.path(root, linted))]) {
    below <- list.files(file.path(root, d), pattern = pattern,
                        all.files = TRUE, recursive = TRUE, ignore.case = TRUE,
                        include.dirs = TRUE)
    below <- below[!grepl("(^|/)(build|\\.gradle|\\.kotlin)/", below)]
    found <- c(found, file.path(d, below))
  }
  sort(unique(found), method = "radix")
}

# lintr reads the file named by the option lintr.linter_file (set from the
# environment variable R_LINTR_LINTER_FILE when lintr loads) instead of
# .lintr; an absolute path there replaces every local configuration.
linter_file_overrides <- function() {
  out <- character()
  option <- getOption("lintr.linter_file")
  if (!is.null(option) && !identical(option, ".lintr")) {
    out <- c(out, paste0("R option lintr.linter_file is set to '",
                         paste(option, collapse = " "), "'"))
  }
  env <- Sys.getenv("R_LINTR_LINTER_FILE")
  if (nzchar(env) && !identical(env, ".lintr")) {
    out <- c(out, paste0("environment variable R_LINTR_LINTER_FILE is set ",
                         "to '", env, "'"))
  }
  out
}

tool_scripts <- function(root) {
  files <- list.files(file.path(root, "tools"), pattern = "\\.[Rr]$",
                      recursive = TRUE)
  # The vendored house linter file is a byte-exact, checksum-pinned copy of
  # CTTIR/.github config/lint-cttir.R. Gradle output below tools/bridge is
  # generated; hand-written R scripts there are linted.
  generated <- grepl("(^|/)(build|\\.gradle|\\.kotlin)/", files)
  keep <- files != "lint-cttir.R" & !generated
  file.path("tools", sort(files[keep], method = "radix"))
}

package_lint_files <- function(root) {
  dirs <- c("R", "tests", "inst", "vignettes", "data-raw", "demo", "exec")
  pattern <- "\\.(R|r|Rmd|rmd|qmd|Rnw|Rhtml|Rrst|Rtex|Rtxt)$"
  files <- character()
  for (d in dirs[dir.exists(file.path(root, dirs))]) {
    found <- list.files(file.path(root, d), pattern = pattern,
                        recursive = TRUE)
    files <- c(files, file.path(d, found))
  }
  sort(files, method = "radix")
}

lints_to_frame <- function(lints, root, set_name) {
  frame <- as.data.frame(lints)
  if (nrow(frame) == 0L) {
    return(data.frame(set = character(), filename = character(),
                      line_number = integer(), column_number = integer(),
                      linter = character(), message = character(),
                      stringsAsFactors = FALSE))
  }
  prefix <- paste0(normalizePath(root), "/")
  absolute <- startsWith(frame$filename, "/")
  paths <- ifelse(absolute, frame$filename, file.path(root, frame$filename))
  filename <- sub(prefix, "", normalizePath(paths, mustWork = FALSE),
                  fixed = TRUE)
  data.frame(set = set_name, filename = filename,
             line_number = as.integer(frame$line_number),
             column_number = as.integer(frame$column_number),
             linter = as.character(frame$linter),
             message = as.character(frame$message),
             stringsAsFactors = FALSE)
}

run_lint_set <- function(root, set_name, linters, warnings_env) {
  collect <- function(expr) {
    withCallingHandlers(expr, warning = function(w) {
      warnings_env$items <- c(warnings_env$items, conditionMessage(w))
      invokeRestart("muffleWarning")
    })
  }
  frames <- list(lints_to_frame(
    collect(lintr::lint_package(root, linters = linters)), root, set_name
  ))
  old_wd <- setwd(root)
  on.exit(setwd(old_wd), add = TRUE)
  for (script in tool_scripts(root)) {
    frames[[length(frames) + 1L]] <- lints_to_frame(
      collect(lintr::lint(script, linters = linters)), root, set_name
    )
  }
  do.call(rbind, frames)
}

# The shared workflow installs the package (local::.) before linting, which
# lets object_usage_linter resolve its exports; a temporary library does the
# same here without touching the user library.
install_into_temporary_library <- function(root) {
  lib <- tempfile("qf-lint-lib-")
  dir.create(lib)
  res <- processx::run(
    file.path(R.home("bin"), "R"),
    c("CMD", "INSTALL", "--no-docs", "--no-multiarch", "--no-test-load",
      "-l", lib, root),
    error_on_status = FALSE, stderr_to_stdout = TRUE
  )
  list(lib = lib, status = res$status, output = res$stdout)
}

# Everything that could make lint findings disappear without a review.
lint_suppression_problems <- function(root, exceptions, warnings_env) {
  problems <- exceptions$problems
  lintr_sha <- sha256_file(file.path(root, ".lintr"))
  reviewed <- exceptions$lintr_config
  divergence_reviewed <- is.list(reviewed) &&
    identical(reviewed$sha256, lintr_sha)
  matches_shared <- identical(lintr_sha, shared_lintr_sha256)
  if (!matches_shared && !divergence_reviewed) {
    problems <- c(problems, paste0(
      ".lintr (sha256 ", lintr_sha, ") differs from the shared CTTIR ",
      ".lintr (", shared_lintr_sha256, ") and no reviewed exception with ",
      "that sha256 exists in tests/api/quality-exceptions.json"
    ))
  }
  other_configs <- other_lintr_configs(root)
  for (path in other_configs) {
    problems <- c(problems, paste0(
      "lintr configuration other than the root .lintr: ", path, " (lintr ",
      "would read it instead of the checked .lintr)"
    ))
  }
  problems <- c(problems, linter_file_overrides())
  exclusions <- lintr_config_field(root, "exclusions", warnings_env)
  forbidden <- forbidden_lintr_exclusions(root, exclusions)
  for (path in forbidden) {
    problems <- c(problems, paste0(
      ".lintr excludes hand-written code from linting: ", path
    ))
  }
  marker_settings <- c("exclude", "exclude_next", "exclude_start",
                       "exclude_end", "exclude_linter", "exclude_linter_sep",
                       "exclusions")
  for (setting in paste0("lintr.", marker_settings)) {
    if (!is.null(getOption(setting))) {
      problems <- c(problems, paste("R option", setting, "is set"))
    }
  }
  cfg <- read.dcf(file.path(root, ".lintr"), all = TRUE)
  custom <- intersect(colnames(cfg), setdiff(marker_settings, "exclusions"))
  for (field in custom) {
    problems <- c(problems, paste(".lintr redefines the marker", field))
  }
  files <- c(package_lint_files(root), tool_scripts(root))
  markers <- find_markers(root, files, nolint_pattern, exceptions$nolint)
  for (i in which(!markers$reviewed)) {
    problems <- c(problems, sprintf(
      "unreviewed nolint marker at %s:%d: %s", markers$file[[i]],
      markers$line_number[[i]], markers$line[[i]]
    ))
  }
  list(problems = problems, lintr_sha256 = lintr_sha,
       matches_shared = matches_shared,
       divergence_reviewed = divergence_reviewed,
       other_lintr_configs = other_configs,
       forbidden_exclusions = forbidden, nolint_markers = markers,
       linted_files = length(files))
}

run_lint <- function(opts, root) {
  require_packages(c("lintr", "digest", "jsonlite", "processx"))
  installed <- install_into_temporary_library(root)
  on.exit(unlink(installed$lib, recursive = TRUE), add = TRUE)
  if (installed$status != 0L) {
    message(installed$output)
    message("quality lint: the package does not install, so it cannot be ",
            "linted as the shared workflow does")
    write_report(opts, root, "lint", 1L, list(
      commands = list("R CMD INSTALL -l <temporary library> ."),
      exit_codes = list(installed$status),
      oracle = "The shared lint workflow installs the package first.",
      tolerances = "none",
      logs = list(installed$output),
      limitations = list(),
      details = list(install_status = installed$status)
    ))
    return(1L)
  }
  old_lib_paths <- .libPaths()
  .libPaths(c(installed$lib, old_lib_paths))
  on.exit(.libPaths(old_lib_paths), add = TRUE)
  house <- resolve_house_linters(root)
  house_env <- new.env()
  sys.source(house$path, envir = house_env)
  house_linters <- house_env$cttir_linters(vocabulary_only = FALSE)
  warnings_env <- new.env()
  warnings_env$items <- character()
  sets <- list(
    shared_workflow = c(house_linters, lintr::linters_with_defaults()),
    repository_config = c(house_linters,
                          lintr_config_field(root, "linters", warnings_env))
  )
  all_lints <- do.call(rbind, lapply(names(sets), function(set_name) {
    run_lint_set(root, set_name, sets[[set_name]], warnings_env)
  }))
  key <- paste(all_lints$filename, all_lints$line_number,
               all_lints$column_number, all_lints$linter, sep = "\r")
  unique_lints <- all_lints[!duplicated(key), , drop = FALSE]
  unique_lints$set <- vapply(key[!duplicated(key)], function(k) {
    paste(sort(unique(all_lints$set[key == k])), collapse = "+")
  }, character(1), USE.NAMES = FALSE)
  for (i in seq_len(nrow(unique_lints))) {
    message(sprintf("%s:%d:%d: %s [%s; %s]", unique_lints$filename[[i]],
                    unique_lints$line_number[[i]],
                    unique_lints$column_number[[i]],
                    unique_lints$message[[i]], unique_lints$linter[[i]],
                    unique_lints$set[[i]]))
  }
  exceptions <- read_exceptions(root)
  suppression <- lint_suppression_problems(root, exceptions, warnings_env)
  for (p in suppression$problems) message("quality lint: FAIL: ", p)
  status <- if (nrow(unique_lints) == 0L &&
                  length(suppression$problems) == 0L) 0L else 1L
  message("quality lint: ", nrow(unique_lints), " finding(s), ",
          length(suppression$problems), " suppression problem(s); house ",
          "linters from ", house$origin, " (", house$path, ")")
  write_report(opts, root, "lint", status, list(
    commands = list(
      "R CMD INSTALL -l <temporary library> .",
      paste("lintr::lint_package(linters = c(cttir_linters(FALSE),",
            "lintr::linters_with_defaults())) + lintr::lint() on tools/"),
      paste("lintr::lint_package(linters = c(cttir_linters(FALSE),",
            "<.lintr linters>)) + lintr::lint() on tools/")
    ),
    exit_codes = list(installed$status, status, status),
    oracle = paste0(
      "CTTIR/.github@", cttir_github_ref, " lint.yaml with blocking=true, ",
      "with-defaults=true, vocabulary-only=false; plus the repository ",
      ".lintr linter set. Zero findings required, and nothing may hide ",
      "findings without a reviewed exception: the .lintr must equal the ",
      "shared file and exclude no hand-written code, no other lintr ",
      "configuration or linter-file override may exist, and no nolint marker ",
      "may appear in the linted files."
    ),
    tolerances = "none",
    logs = as.list(unique(warnings_env$items)),
    limitations = list(paste(
      "The shared workflow passes linters= explicitly, so lintr applies its",
      "own defaults (80 characters, names up to 30 characters) instead of",
      "the .lintr linter list; both sets are enforced here."
    )),
    details = list(
      house_linters = house,
      lintr_config_sha256 = suppression$lintr_sha256,
      lintr_config_matches_shared = suppression$matches_shared,
      lintr_config_divergence_reviewed = suppression$divergence_reviewed,
      other_lintr_configs = as.list(suppression$other_lintr_configs),
      forbidden_lintr_exclusions = as.list(suppression$forbidden_exclusions),
      nolint_markers = suppression$nolint_markers,
      unreviewed_nolint_markers = sum(!suppression$nolint_markers$reviewed),
      exceptions_file_valid = exceptions$ok,
      suppression_problems = as.list(suppression$problems),
      tool_scripts = as.list(tool_scripts(root)),
      finding_count = nrow(unique_lints),
      counts_by_set = as.list(table(factor(all_lints$set,
                                           levels = names(sets)))),
      findings = unique_lints
    )
  ))
  status
}

# ---- coverage -------------------------------------------------------------

coverage_baseline_path <- function(root) {
  file.path(root, "tests", "api", "coverage-baseline.json")
}

per_file_coverage <- function(cov) {
  tally <- covr::tally_coverage(cov, by = "line")
  if (nrow(tally) == 0L) {
    return(data.frame(file = character(), lines = integer(),
                      covered = integer(), percent = numeric()))
  }
  files <- sort(unique(tally$filename), method = "radix")
  lines <- vapply(files, function(f) sum(tally$filename == f), integer(1))
  covered <- vapply(files, function(f) {
    sum(tally$filename == f & tally$value > 0)
  }, integer(1))
  data.frame(file = files, lines = unname(lines), covered = unname(covered),
             percent = round(100 * unname(covered) / unname(lines), 2),
             stringsAsFactors = FALSE)
}

is_finite_number <- function(x) {
  is.numeric(x) && length(x) == 1L && isTRUE(is.finite(x))
}

# Checks a parsed baseline file. Returns list(percent, problems); percent is
# NA unless the baseline is valid. A baseline must name the tests-only
# measurement, hold a finite value between the floor and 100, agree with
# its last history entry, and may only have dropped with --allow-decrease.
validate_coverage_baseline <- function(baseline) {
  problems <- character()
  bad <- function(text) paste("coverage baseline:", text)
  if (!is.list(baseline) || is.null(names(baseline))) {
    return(list(percent = NA_real_, problems = bad("not a JSON object")))
  }
  if (!identical(baseline$coverage_schema_version, 1L)) {
    problems <- c(problems, bad("coverage_schema_version must be 1"))
  }
  if (!identical(baseline$type, "tests")) {
    problems <- c(problems, bad("type must be \"tests\""))
  }
  percent <- baseline$baseline_percent
  if (!is_finite_number(percent) || percent < coverage_floor ||
        percent > 100) {
    problems <- c(problems, bad(sprintf(
      "baseline_percent must be a number from %.2f to 100", coverage_floor
    )))
  }
  history <- baseline$history
  if (!is.list(history) || length(history) == 0L ||
        !is.null(names(history))) {
    problems <- c(problems, bad("history must list at least one entry"))
  } else {
    values <- vapply(history, function(e) {
      if (is.list(e) && is_finite_number(e$baseline_percent)) {
        e$baseline_percent
      } else {
        NA_real_
      }
    }, numeric(1))
    if (anyNA(values)) {
      problems <- c(problems, bad("every history entry needs baseline_percent"))
    } else {
      if (is_finite_number(percent) &&
            !isTRUE(all.equal(values[[length(values)]], percent))) {
        problems <- c(problems, bad(sprintf(
          "baseline_percent %.2f differs from the last history entry %.2f",
          percent, values[[length(values)]]
        )))
      }
      dropped <- which(diff(values) < 0) + 1L
      allowed <- vapply(history, function(e) isTRUE(e$allow_decrease),
                        logical(1))
      for (i in dropped[!allowed[dropped]]) {
        problems <- c(problems, bad(sprintf(
          "history entry %d lowers the baseline without allow_decrease", i
        )))
      }
    }
  }
  list(percent = if (length(problems) == 0L) percent else NA_real_,
       problems = problems)
}

read_coverage_baseline <- function(path) {
  if (!file.exists(path)) {
    return(list(exists = FALSE, baseline = NULL, percent = NA_real_,
                problems = character()))
  }
  baseline <- tryCatch(jsonlite::fromJSON(path, simplifyVector = FALSE),
                       error = function(e) NULL)
  checked <- validate_coverage_baseline(baseline)
  c(list(exists = TRUE, baseline = baseline), checked)
}

# Conditions under which covr would silently leave lines or failures out.
coverage_setup_problems <- function(root, exceptions) {
  problems <- exceptions$problems
  entry <- file.path(root, "tests", "testthat.R")
  exprs <- tryCatch(parse(entry, keep.source = FALSE),
                    error = function(e) NULL)
  code <- if (is.null(exprs)) character() else
    vapply(exprs, function(e) paste(deparse(e), collapse = " "), character(1))
  if (!identical(unname(code), testthat_entry)) {
    problems <- c(problems, paste(
      "tests/testthat.R is not the standard testthat entry file",
      "(library(testthat); library(qupflowR); test_check(\"qupflowR\"))"
    ))
  }
  loadNamespace("covr")
  for (name in names(covr_defaults)) {
    value <- as.vector(unclass(getOption(name)))
    if (!identical(value, covr_defaults[[name]])) {
      problems <- c(problems, paste("covr option", name, "was changed"))
    }
  }
  if (nzchar(Sys.getenv("COVR_COVRIGNORE"))) {
    problems <- c(problems, "environment variable COVR_COVRIGNORE is set")
  }
  covrignore <- file.path(root, ".covrignore")
  if (file.exists(covrignore) &&
        !identical(exceptions$covrignore$sha256, sha256_file(covrignore))) {
    problems <- c(problems, ".covrignore exists without a reviewed exception")
  }
  in_r <- list.files(file.path(root, "R"), recursive = TRUE)
  in_src <- list.files(file.path(root, "src"), recursive = TRUE)
  sources <- c(if (length(in_r)) file.path("R", in_r),
               if (length(in_src)) file.path("src", in_src))
  markers <- find_markers(root, sources, covr_defaults$covr.exclude_pattern,
                          exceptions$nocov)
  for (i in which(!markers$reviewed)) {
    problems <- c(problems, sprintf(
      "unreviewed nocov marker at %s:%d: %s", markers$file[[i]],
      markers$line_number[[i]], markers$line[[i]]
    ))
  }
  list(problems = problems, nocov_markers = markers)
}

# testthat's summary line in the tests output that covr leaves behind with
# clean = FALSE; NULL when there is none.
covr_test_summary <- function(measured, package) {
  lib <- attr(measured, "library")
  if (is.null(lib)) {
    return(NULL)
  }
  # tools::testInstalledPackage() writes <outDir>/<package>-tests/.
  rout <- Sys.glob(file.path(lib, package, c(paste0(package, "-tests"),
                                             "tests"), "testthat.Rout*"))
  if (length(rout) == 0L) {
    return(NULL)
  }
  text <- readLines(rout[[1L]], warn = FALSE)
  pattern <- paste0("\\[ FAIL [0-9]+ \\| WARN [0-9]+ \\| SKIP [0-9]+ ",
                    "\\| PASS [0-9]+ \\]")
  line <- utils::tail(grep(pattern, text, value = TRUE), 1L)
  if (length(line) == 0L) {
    return(NULL)
  }
  nums <- as.integer(regmatches(line, gregexpr("[0-9]+", line))[[1L]])
  list(file = basename(rout[[1L]]), fail = nums[[1L]], warn = nums[[2L]],
       skip = nums[[3L]], pass = nums[[4L]])
}

run_coverage <- function(opts, root) {
  require_packages(c("covr", "digest", "jsonlite"))
  base_body <- list(
    commands = list(
      "covr::package_coverage(type = \"tests\", clean = FALSE)"
    ),
    oracle = paste("Line coverage of R/ from the test suite only; absolute",
                   "floor and committed baseline minus tolerance; the",
                   "testthat summary of the covr run must show FAIL 0."),
    tolerances = list(floor_percent = coverage_floor,
                      baseline_tolerance_percent = coverage_tolerance),
    limitations = list("Java and browser coverage are reported separately.")
  )
  exceptions <- read_exceptions(root)
  setup <- coverage_setup_problems(root, exceptions)
  baseline_path <- coverage_baseline_path(root)
  stored <- read_coverage_baseline(baseline_path)
  package <- read.dcf(file.path(root, "DESCRIPTION"),
                      fields = "Package")[1L, 1L]
  measured <- tryCatch(
    covr::package_coverage(root, type = "tests", quiet = TRUE, clean = FALSE),
    error = function(e) e
  )
  if (!inherits(measured, "error")) {
    on.exit(unlink(attr(measured, "library"), recursive = TRUE), add = TRUE)
  }
  if (inherits(measured, "error")) {
    message("quality coverage: measurement failed: ",
            conditionMessage(measured))
    write_report(opts, root, "coverage", 1L, c(base_body, list(
      exit_codes = list(1L),
      logs = as.list(c(conditionMessage(measured), setup$problems)),
      details = list(error = conditionMessage(measured))
    )))
    return(1L)
  }
  percent <- as.numeric(covr::percent_coverage(measured, by = "line"))
  files <- per_file_coverage(measured)
  tests <- covr_test_summary(measured, package)
  problems <- c(setup$problems, stored$problems)
  if (is.null(tests)) {
    problems <- c(problems, "no testthat summary in the covr test output")
  } else if (!identical(tests$fail, 0L)) {
    problems <- c(problems, sprintf("%d test failure(s) in the covr run",
                                    tests$fail))
  }
  if (!is_finite_number(percent) || percent < coverage_floor) {
    problems <- c(problems, sprintf("coverage %.2f%% is below the floor %.2f%%",
                                    percent, coverage_floor))
  }
  if (!stored$exists && !opts$update_baseline) {
    problems <- c(problems, paste("no coverage baseline at", baseline_path))
  }
  baseline_percent <- stored$percent
  below_baseline <- is_finite_number(baseline_percent) &&
    is_finite_number(percent) &&
    percent < baseline_percent - coverage_tolerance
  if (!opts$update_baseline && below_baseline) {
    problems <- c(problems, sprintf(
      "coverage %.2f%% is more than %.2f points below the baseline %.2f%%",
      percent, coverage_tolerance, baseline_percent
    ))
  }
  if (opts$update_baseline) {
    if (length(problems) > 0L) {
      problems <- c(problems, "refusing to record a baseline")
    } else {
      problems <- update_coverage_baseline(opts, root, stored, baseline_path,
                                           percent, files)
    }
  }
  message(sprintf("quality coverage: %.2f%% (floor %.2f%%, baseline %s)",
                  percent, coverage_floor,
                  if (is_finite_number(baseline_percent)) {
                    sprintf("%.2f%%", baseline_percent)
                  } else {
                    "none"
                  }))
  print(files[order(files$percent, files$file), , drop = FALSE],
        row.names = FALSE)
  for (p in problems) message("quality coverage: FAIL: ", p)
  status <- if (length(problems) == 0L) 0L else 1L
  weak <- files$file[files$percent < coverage_floor]
  write_report(opts, root, "coverage", status, c(base_body, list(
    exit_codes = list(status),
    logs = as.list(problems),
    details = list(
      percent = round(percent, 4),
      floor_percent = coverage_floor,
      baseline_percent = if (is_finite_number(baseline_percent)) {
        baseline_percent
      } else {
        NULL
      },
      baseline_valid = stored$exists && length(stored$problems) == 0L,
      baseline_sha256 = if (file.exists(baseline_path)) {
        sha256_file(baseline_path)
      } else {
        NULL
      },
      tests = tests,
      nocov_markers = setup$nocov_markers,
      excluded_marker_lines = nrow(setup$nocov_markers),
      covr_options = stats::setNames(lapply(names(covr_defaults), function(n) {
        as.vector(unclass(getOption(n)))
      }), names(covr_defaults)),
      files_below_floor = as.list(weak),
      per_file = files
    )
  )))
  status
}

update_coverage_baseline <- function(opts, root, stored, baseline_path,
                                     percent, files) {
  if (is.na(opts$reason) || !nzchar(trimws(opts$reason))) {
    quality_usage_error("--update-baseline needs a non-empty --reason")
  }
  old <- stored$percent
  decrease <- is_finite_number(old) && percent < old
  if (decrease && !opts$allow_decrease) {
    return(sprintf(paste("refusing to lower the baseline from %.2f%% to",
                         "%.2f%% without --allow-decrease"), old, percent))
  }
  history <- if (stored$exists) stored$baseline$history else list()
  entry <- list(
    date = format(Sys.time(), "%Y-%m-%d", tz = "UTC"),
    previous_percent = if (is_finite_number(old)) old else NULL,
    baseline_percent = round(percent, 2),
    reason = trimws(opts$reason)
  )
  if (decrease) {
    entry$allow_decrease <- TRUE
  }
  inventory <- source_inventory_summary(root)
  updated <- list(
    coverage_schema_version = 1L,
    type = "tests",
    floor_percent = coverage_floor,
    tolerance_percent = coverage_tolerance,
    baseline_percent = round(percent, 2),
    measured_at = format(Sys.time(), "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"),
    source_inventory_sha256 = inventory$sha256,
    source_inventory_files = inventory$file_count,
    r_version = as.character(getRversion()),
    covr_version = as.character(utils::packageVersion("covr")),
    per_file = files,
    history = c(history, list(entry))
  )
  json <- jsonlite::toJSON(updated, auto_unbox = TRUE, pretty = TRUE,
                           null = "null", na = "null", digits = NA,
                           dataframe = "rows")
  dir.create(dirname(baseline_path), recursive = TRUE, showWarnings = FALSE)
  writeLines(json, baseline_path, useBytes = TRUE)
  message("quality coverage: baseline written to ", baseline_path)
  character()
}

# ---- api ------------------------------------------------------------------

run_api <- function(opts, root) {
  require_packages(c("processx", "jsonlite", "digest"))
  report_file <- tempfile("qf-api-report-", fileext = ".json")
  on.exit(unlink(report_file), add = TRUE)
  args <- c(file.path(root, "tools", "api-inventory.R"), "--check",
            "--root", root, "--report", report_file)
  rscript <- file.path(R.home("bin"), "Rscript")
  res <- processx::run(rscript, args, error_on_status = FALSE,
                       echo = TRUE, wd = root)
  status <- res$status
  details <- if (file.exists(report_file)) {
    jsonlite::fromJSON(report_file, simplifyVector = FALSE)
  } else {
    list(error = "tools/api-inventory.R wrote no report")
  }
  write_report(opts, root, "api", if (status == 0L) 0L else 1L, list(
    commands = list(paste("Rscript tools/api-inventory.R --check --root",
                          root)),
    exit_codes = list(status),
    oracle = paste("Committed baseline tests/api/api-inventory.json, its",
                   "history digests and replay, and NAMESPACE/man",
                   "regenerated by roxygen2 in a copy."),
    tolerances = "none",
    logs = list(),
    limitations = list(paste("A matching inventory proves an unchanged",
                             "signature surface, not unchanged behaviour.")),
    details = details
  ))
  if (status > 1L) {
    return(2L)
  }
  status
}

# ---- main -----------------------------------------------------------------

run_quality <- function(opts) {
  root <- normalizePath(opts$root, mustWork = TRUE)
  if (!file.exists(file.path(root, "DESCRIPTION"))) {
    quality_usage_error("no DESCRIPTION in ", root)
  }
  runners <- list(lint = run_lint, coverage = run_coverage, api = run_api)
  modes <- if (identical(opts$mode, "all")) names(runners) else opts$mode
  statuses <- vapply(modes, function(m) runners[[m]](opts, root), integer(1))
  overall <- max(statuses)
  if (identical(opts$mode, "all")) {
    write_report(opts, root, "all", overall, list(
      commands = as.list(paste("Rscript tools/quality.R --mode", modes)),
      exit_codes = as.list(unname(statuses)),
      oracle = "Aggregate of the lint, coverage and api modes.",
      tolerances = "see the per-mode reports",
      logs = list(),
      limitations = list(),
      details = list(modes = as.list(statuses))
    ))
  }
  message("quality: ", paste(sprintf("%s=%s", modes,
                                     ifelse(statuses == 0L, "pass", "fail")),
                             collapse = " "))
  overall
}

if (!interactive()) {
  exit_status <- tryCatch(
    run_quality(parse_quality_args(commandArgs(trailingOnly = TRUE))),
    error = function(e) {
      message("quality: error: ", conditionMessage(e))
      2L
    }
  )
  quit(save = "no", status = exit_status)
}
