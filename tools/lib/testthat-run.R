# Runs the package's testthat suite from the source tree and writes a
# machine-readable summary; used by tools/validate.R (lanes L01 and L02).
#
# Usage:
#   Rscript tools/lib/testthat-run.R --root DIR --out FILE [--junit FILE]
#     [--filter REGEX]
#
# The summary counts expectations by type (success, failure, error, skip,
# warning) per file and lists every failure, error and skip message.
# Exit status: 0 all tests ran without failures or errors, 1 failures,
# errors or no tests at all, 2 usage error.

tt_args <- function(args) {
  opts <- list(root = NA_character_, out = NA_character_,
               junit = NA_character_, filter = NULL)
  i <- 1L
  while (i <= length(args)) {
    key <- sub("^--", "", args[[i]])
    if (!key %in% names(opts) || i == length(args)) {
      message("testthat-run: bad or incomplete argument ", args[[i]])
      quit(save = "no", status = 2L)
    }
    opts[[key]] <- args[[i + 1L]]
    i <- i + 2L
  }
  if (is.na(opts$root) || is.na(opts$out)) {
    message("testthat-run: --root and --out are required")
    quit(save = "no", status = 2L)
  }
  opts
}

tt_kind <- function(expectation) {
  kinds <- c("expectation_failure", "expectation_error", "expectation_skip",
             "expectation_warning", "expectation_success")
  hit <- kinds[vapply(kinds, function(k) inherits(expectation, k), TRUE)]
  if (length(hit) == 0L) "other" else sub("^expectation_", "", hit[[1L]])
}

tt_summarise <- function(results) {
  rows <- list()
  messages <- list()
  for (test in results) {
    for (expectation in test$results) {
      kind <- tt_kind(expectation)
      rows[[length(rows) + 1L]] <- data.frame(
        file = test$file, test = test$test, kind = kind,
        stringsAsFactors = FALSE
      )
      if (kind %in% c("failure", "error", "skip", "warning")) {
        messages[[length(messages) + 1L]] <- list(
          file = test$file, test = test$test, kind = kind,
          message = substr(conditionMessage(expectation), 1L, 2000L)
        )
      }
    }
  }
  frame <- if (length(rows) == 0L) {
    data.frame(file = character(), test = character(), kind = character())
  } else {
    do.call(rbind, rows)
  }
  count <- function(k, sub = frame) sum(sub$kind == k)
  files <- sort(unique(vapply(results, function(t) t$file, "")),
                method = "radix")
  per_file <- lapply(files, function(f) {
    sub <- frame[frame$file == f, , drop = FALSE]
    tests <- Filter(function(t) identical(t$file, f), results)
    list(file = f, tests = length(tests), expectations = nrow(sub),
         passed = count("success", sub), failed = count("failure", sub),
         errors = count("error", sub), skipped = count("skip", sub),
         warnings = count("warning", sub))
  })
  list(
    counts = list(files = length(files), tests = length(results),
                  expectations = nrow(frame), passed = count("success"),
                  failed = count("failure"), errors = count("error"),
                  skipped = count("skip"), warnings = count("warning")),
    per_file = per_file,
    problems = Filter(function(m) m$kind %in% c("failure", "error"),
                      messages),
    skips = Filter(function(m) identical(m$kind, "skip"), messages),
    warnings = Filter(function(m) identical(m$kind, "warning"), messages)
  )
}

tt_main <- function() {
  opts <- tt_args(commandArgs(trailingOnly = TRUE))
  test_dir <- file.path(opts$root, "tests", "testthat")
  reporters <- list(testthat::SummaryReporter$new(show_praise = FALSE,
                                                  max_reports = 1000L))
  if (!is.na(opts$junit)) {
    reporters[[2L]] <- testthat::JunitReporter$new(file = opts$junit)
  }
  started <- Sys.time()
  results <- testthat::test_dir(
    test_dir, filter = opts$filter, load_package = "source",
    stop_on_failure = FALSE, stop_on_warning = FALSE,
    reporter = testthat::MultiReporter$new(reporters)
  )
  summary <- tt_summarise(results)
  summary <- c(list(
    runner = "testthat::test_dir(load_package = \"source\")",
    testthat_version = as.character(utils::packageVersion("testthat")),
    filter = opts$filter,
    duration_s = round(as.numeric(difftime(Sys.time(), started,
                                           units = "secs")), 1)
  ), summary)
  dir.create(dirname(opts$out), recursive = TRUE, showWarnings = FALSE)
  writeLines(jsonlite::toJSON(summary, auto_unbox = TRUE, pretty = TRUE,
                              null = "null"),
             opts$out, useBytes = TRUE)
  counts <- summary$counts
  bad <- counts$failed + counts$errors > 0L || counts$tests == 0L
  quit(save = "no", status = if (bad) 1L else 0L)
}

if (!interactive()) {
  tt_main()
}
