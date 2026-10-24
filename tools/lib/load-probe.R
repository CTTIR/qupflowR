# Loads an installed qupflowR in a fresh R process and records what loading
# did; used by tools/validate.R lane L11.
#
# Usage:
#   Rscript tools/lib/load-probe.R --lib DIR --out FILE
#
# The probe prepends --lib to the library path, notes the loaded
# namespaces, options and search path before and directly after
# library(qupflowR), lists which Suggests of the installed DESCRIPTION are
# installed and which were loaded, and then calls qp_error_codes() as a
# small functional check whose row count is compared with the installed
# error-codes.json read by jsonlite.
#
# Exit status: 0 the package loaded and the summary was written, 1 loading
# failed, 2 usage error.

lp_args <- function(args) {
  if (length(args) != 4L || !identical(args[[1L]], "--lib") ||
        !identical(args[[3L]], "--out")) {
    message("load-probe: usage: --lib DIR --out FILE")
    quit(save = "no", status = 2L)
  }
  list(lib = normalizePath(args[[2L]], winslash = "/", mustWork = TRUE),
       out = args[[4L]])
}

lp_field_packages <- function(desc, field) {
  value <- desc[[field]]
  if (is.null(value) || is.na(value)) {
    return(character())
  }
  items <- trimws(sub("\\(.*$", "", strsplit(value, ",")[[1L]]))
  items[nzchar(items) & items != "R"]
}

lp_main <- function() {
  opts <- lp_args(commandArgs(trailingOnly = TRUE))
  .libPaths(c(opts$lib, .libPaths()))
  before_ns <- loadedNamespaces()
  before_opts <- names(options())
  before_search <- search()
  loaded <- tryCatch({
    do.call(library, list(package = "qupflowR", lib.loc = opts$lib,
                          character.only = TRUE))
    TRUE
  }, error = function(e) conditionMessage(e))
  after_ns <- loadedNamespaces()
  after_opts <- names(options())
  after_search <- search()
  desc_path <- file.path(opts$lib, "qupflowR", "DESCRIPTION")
  desc <- as.list(read.dcf(desc_path)[1L, ])
  suggests <- lp_field_packages(desc, "Suggests")
  imports <- lp_field_packages(desc, "Imports")
  installed <- vapply(suggests, function(p) {
    nzchar(system.file(package = p))
  }, TRUE)
  functional <- NULL
  if (isTRUE(loaded)) {
    codes <- tryCatch(qupflowR::qp_error_codes(), error = function(e) e)
    registry <- system.file("schema", "protocol", "v1", "error-codes.json",
                            package = "qupflowR", lib.loc = opts$lib)
    expected <- length(jsonlite::fromJSON(registry, simplifyVector = FALSE))
    functional <- list(
      function_name = "qp_error_codes",
      ok = !inherits(codes, "error"),
      rows = if (inherits(codes, "error")) NULL else nrow(codes),
      expected_rows = expected,
      error = if (inherits(codes, "error")) conditionMessage(codes) else NULL
    )
  }
  new_ns <- sort(setdiff(after_ns, before_ns), method = "radix")
  summary <- list(
    loaded = isTRUE(loaded),
    load_error = if (isTRUE(loaded)) NULL else loaded,
    package_path = find.package("qupflowR", lib.loc = opts$lib,
                                quiet = TRUE),
    version = desc$Version,
    library_paths = as.list(.libPaths()),
    new_namespaces = as.list(new_ns),
    imports = as.list(imports),
    suggests_installed = as.list(suggests[installed]),
    suggests_not_installed = as.list(suggests[!installed]),
    suggests_loaded = as.list(intersect(suggests, new_ns)),
    new_options = as.list(setdiff(after_opts, before_opts)),
    new_search_entries = as.list(setdiff(after_search, before_search)),
    functional_check = functional
  )
  dir.create(dirname(opts$out), recursive = TRUE, showWarnings = FALSE)
  writeLines(jsonlite::toJSON(summary, auto_unbox = TRUE, pretty = TRUE,
                              null = "null"),
             opts$out, useBytes = TRUE)
  quit(save = "no", status = if (isTRUE(loaded)) 0L else 1L)
}

if (!interactive()) {
  lp_main()
}
