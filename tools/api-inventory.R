# Public API inventory check for qupflowR.
#
# Compares the exports, their formal arguments, the export patterns and the
# registered S3 methods of the package source tree with the committed
# baseline tests/api/api-inventory.json. Any difference fails the check until
# it is explained: run the script with --update and a --reason, which records
# the change in the history of the baseline. Before comparing, the script
# regenerates NAMESPACE and man/*.Rd with roxygen2 in a temporary copy and
# fails if the committed files are stale, so the inventory always describes
# the real sources.
#
# The history is part of the check. Every entry of "changes" records
# inventory_sha256, the sha256 of the signature table (sorted
# "<entry>\t<signature>\n" lines) after that change, and the added and
# removed entries. --check fails when the committed exports, export patterns
# and S3 methods do not match the digest of the last history entry, or when
# replaying the added and removed entries of the history does not give the
# committed entries: an edit of the baseline without --update is detected.
#
# Usage (from the repository root):
#   Rscript tools/api-inventory.R --check [--root DIR] [--report FILE]
#   Rscript tools/api-inventory.R --update --reason TEXT [--root DIR]
#   Option --baseline FILE overrides the baseline path.
#
# Exit status: 0 no unexplained change, 1 unexplained change or stale
# generated files, 2 usage or environment error.

inventory_schema_version <- 1L

api_usage_error <- function(...) {
  message("api-inventory: ", ...)
  quit(save = "no", status = 2L)
}

parse_api_args <- function(args) {
  opts <- list(
    mode = NA_character_, root = ".", reason = NA_character_,
    report = NA_character_, baseline = NA_character_
  )
  valued <- c("--root", "--reason", "--report", "--baseline")
  i <- 1L
  while (i <= length(args)) {
    arg <- args[[i]]
    if (arg %in% c("--check", "--update")) {
      if (!is.na(opts$mode)) {
        api_usage_error("give only one of --check and --update")
      }
      opts$mode <- sub("^--", "", arg)
    } else if (arg %in% valued) {
      if (i == length(args)) api_usage_error("missing value for ", arg)
      opts[[sub("^--", "", arg)]] <- args[[i + 1L]]
      i <- i + 1L
    } else {
      api_usage_error("unknown argument ", arg)
    }
    i <- i + 1L
  }
  if (is.na(opts$mode)) api_usage_error("give --check or --update")
  opts
}

require_tool_packages <- function(pkgs) {
  missing <- pkgs[!vapply(pkgs, requireNamespace, logical(1), quietly = TRUE)]
  if (length(missing) > 0L) {
    api_usage_error("required packages not installed: ",
                    paste(missing, collapse = ", "))
  }
}

deparse_default <- function(value) {
  if (is.symbol(value) && identical(as.character(value), "")) {
    return(list(has_default = FALSE, default = NULL))
  }
  text <- paste(deparse(value, width.cutoff = 500L), collapse = "\n")
  list(has_default = TRUE, default = text)
}

describe_formals <- function(fun) {
  args <- formals(fun)
  if (is.primitive(fun)) {
    args <- formals(args(fun))
  }
  lapply(names(args), function(arg_name) {
    c(list(name = arg_name), deparse_default(args[[arg_name]]))
  })
}

describe_object <- function(name, env) {
  obj <- get0(name, envir = env, inherits = FALSE)
  if (is.null(obj) && !exists(name, envir = env, inherits = FALSE)) {
    return(list(name = name, type = "missing", formals = NULL))
  }
  if (is.function(obj)) {
    return(list(name = name, type = "function",
                formals = describe_formals(obj)))
  }
  list(name = name, type = paste(class(obj), collapse = "/"), formals = NULL)
}

s3_method_table <- function(ns_info) {
  s3 <- ns_info$S3methods
  if (is.null(s3) || nrow(s3) == 0L) {
    return(data.frame(generic = character(), class = character(),
                      method = character(), stringsAsFactors = FALSE))
  }
  method <- ifelse(is.na(s3[, 3L]), paste(s3[, 1L], s3[, 2L], sep = "."),
                   s3[, 3L])
  out <- data.frame(generic = s3[, 1L], class = s3[, 2L], method = method,
                    stringsAsFactors = FALSE)
  out[order(out$generic, out$class, method = "radix"), , drop = FALSE]
}

current_inventory <- function(root) {
  pkg <- read.dcf(file.path(root, "DESCRIPTION"), fields = "Package")[1L, 1L]
  ns_info <- parseNamespaceFile(basename(root), dirname(root))
  pkgload::load_all(root, export_all = FALSE, helpers = FALSE,
                    attach_testthat = FALSE, quiet = TRUE)
  ns_env <- asNamespace(pkg)
  exports <- unique(radix_sort(ns_info$exports))
  s3 <- s3_method_table(ns_info)
  s3_entries <- lapply(seq_len(nrow(s3)), function(i) {
    described <- describe_object(s3$method[[i]], ns_env)
    list(generic = s3$generic[[i]], class = s3$class[[i]],
         method = s3$method[[i]], type = described$type,
         formals = described$formals)
  })
  list(
    inventory_schema_version = inventory_schema_version,
    package = pkg,
    exports = lapply(exports, describe_object, env = ns_env),
    export_patterns = as.list(radix_sort(ns_info$exportPatterns)),
    s3_methods = s3_entries
  )
}

to_json_text <- function(x) {
  jsonlite::toJSON(x, auto_unbox = TRUE, pretty = TRUE, null = "null",
                   na = "null", digits = NA)
}

normalise_inventory <- function(x) {
  jsonlite::fromJSON(to_json_text(x), simplifyVector = FALSE)
}

signature_string <- function(entry) {
  if (!identical(entry$type, "function")) {
    return(paste0("<", entry$type, ">"))
  }
  parts <- vapply(entry$formals, function(arg) {
    if (isTRUE(arg$has_default)) {
      paste(arg$name, "=", arg$default)
    } else {
      arg$name
    }
  }, character(1))
  paste0("(", paste(parts, collapse = ", "), ")")
}

signature_table <- function(inv) {
  exports <- vapply(inv$exports, signature_string, character(1))
  names(exports) <- vapply(inv$exports, function(e) e$name, character(1))
  s3 <- vapply(inv$s3_methods, signature_string, character(1))
  names(s3) <- vapply(inv$s3_methods, function(m) {
    paste0("S3method(", m$generic, ", ", m$class, ")")
  }, character(1))
  patterns <- as.character(unlist(inv$export_patterns, use.names = FALSE))
  pattern_table <- rep("<pattern>", length(patterns))
  names(pattern_table) <- sprintf("exportPattern(%s)", patterns)
  c(exports, s3, pattern_table)
}

radix_sort <- function(x) {
  sort(as.character(x), method = "radix")
}

diff_inventories <- function(old, new) {
  a <- signature_table(old)
  b <- signature_table(new)
  a_names <- as.character(names(a))
  b_names <- as.character(names(b))
  common <- intersect(a_names, b_names)
  changed <- common[a[common] != b[common]]
  list(
    added = as.list(radix_sort(setdiff(b_names, a_names))),
    removed = as.list(radix_sort(setdiff(a_names, b_names))),
    changed = lapply(radix_sort(changed), function(key) {
      list(entry = key, before = a[[key]], after = b[[key]])
    })
  )
}

signature_sha256 <- function(inv) {
  table <- signature_table(inv)
  keys <- as.character(names(table))
  ordering <- order(keys, method = "radix")
  lines <- paste0(keys[ordering], "\t", unname(table)[ordering], "\n")
  digest::digest(enc2utf8(paste(lines, collapse = "")), algo = "sha256",
                 serialize = FALSE)
}

# Entry names after applying the added and removed lists of every history
# entry in order, starting from an empty API.
replay_history <- function(changes) {
  keys <- character()
  for (entry in changes) {
    removed <- as.character(unlist(entry$removed))
    keys <- setdiff(keys, removed)
    keys <- union(keys, as.character(unlist(entry$added)))
  }
  radix_sort(keys)
}

# Problems of a committed baseline: a body edited without a history entry.
baseline_integrity <- function(baseline) {
  if (is.null(baseline)) {
    return(character())
  }
  problems <- character()
  changes <- baseline$changes
  if (!is.list(changes) || length(changes) == 0L) {
    return("the baseline has no history of changes")
  }
  last <- changes[[length(changes)]]
  recorded <- last$inventory_sha256
  actual <- signature_sha256(baseline)
  if (!is.character(recorded) || length(recorded) != 1L) {
    problems <- c(problems, paste(
      "the last history entry records no inventory_sha256; the committed",
      "entries cannot be tied to the history"
    ))
  } else if (!identical(recorded, actual)) {
    problems <- c(problems, sprintf(paste(
      "the committed entries have digest %s, but the last history entry",
      "records %s: the baseline was edited without --update"
    ), actual, recorded))
  }
  committed <- radix_sort(names(signature_table(baseline)))
  replayed <- replay_history(changes)
  if (!identical(committed, replayed)) {
    problems <- c(problems, paste0(
      "replaying the history gives different entries than the baseline",
      " (only in the baseline: ",
      paste(setdiff(committed, replayed), collapse = ", "),
      "; only in the history: ",
      paste(setdiff(replayed, committed), collapse = ", "), ")"
    ))
  }
  problems
}

has_differences <- function(d) {
  length(d$added) + length(d$removed) + length(d$changed) > 0L
}

copy_for_roxygen <- function(root) {
  target <- file.path(tempfile("qf-api-roxygen-"), "pkg")
  dir.create(target, recursive = TRUE)
  for (entry in c("DESCRIPTION", "NAMESPACE", "R", "man", "inst")) {
    source <- file.path(root, entry)
    if (dir.exists(source)) {
      file.copy(source, target, recursive = TRUE)
    } else if (file.exists(source)) {
      file.copy(source, target)
    }
  }
  target
}

read_generated <- function(dir) {
  files <- character()
  if (file.exists(file.path(dir, "NAMESPACE"))) {
    files <- "NAMESPACE"
  }
  rd <- list.files(file.path(dir, "man"), pattern = "\\.Rd$")
  files <- c(files, file.path("man", sort(rd, method = "radix")))
  contents <- lapply(file.path(dir, files), function(f) {
    readBin(f, "raw", n = file.size(f))
  })
  names(contents) <- files
  contents
}

stale_generated_files <- function(root) {
  copy <- copy_for_roxygen(root)
  on.exit(unlink(dirname(copy), recursive = TRUE), add = TRUE)
  suppressMessages(
    roxygen2::roxygenise(copy, roclets = c("collate", "namespace", "rd"))
  )
  committed <- read_generated(root)
  generated <- read_generated(copy)
  all_files <- union(names(committed), names(generated))
  stale <- all_files[vapply(all_files, function(f) {
    !identical(committed[[f]], generated[[f]])
  }, logical(1))]
  sort(stale, method = "radix")
}

print_differences <- function(d) {
  for (key in unlist(d$added)) message("  added:   ", key)
  for (key in unlist(d$removed)) message("  removed: ", key)
  for (entry in d$changed) {
    message("  changed: ", entry$entry, "\n    before ", entry$before,
            "\n    after  ", entry$after)
  }
}

write_json_file <- function(x, path) {
  dir.create(dirname(path), recursive = TRUE, showWarnings = FALSE)
  writeLines(to_json_text(x), path, useBytes = TRUE)
}

file_sha256 <- function(path) {
  if (!file.exists(path)) {
    return(NULL)
  }
  digest::digest(file = path, algo = "sha256")
}

run_api_inventory <- function(opts) {
  require_tool_packages(c("jsonlite", "pkgload", "roxygen2", "digest"))
  root <- normalizePath(opts$root, mustWork = TRUE)
  baseline_path <- if (is.na(opts$baseline)) {
    file.path(root, "tests", "api", "api-inventory.json")
  } else {
    normalizePath(opts$baseline, mustWork = FALSE)
  }
  stale <- stale_generated_files(root)
  current <- normalise_inventory(current_inventory(root))
  baseline <- NULL
  if (file.exists(baseline_path)) {
    baseline <- jsonlite::fromJSON(baseline_path, simplifyVector = FALSE)
  }
  empty <- list(exports = list(), export_patterns = list(),
                s3_methods = list())
  differences <- diff_inventories(if (is.null(baseline)) empty else baseline,
                                  current)
  integrity <- baseline_integrity(baseline)
  for (problem in integrity) message("api-inventory: ", problem)
  if (length(stale) > 0L) {
    message("api-inventory: committed generated files are stale; run ",
            "roxygen2::roxygenise() first:")
    for (f in stale) message("  ", f)
  }
  status <- 0L
  if (identical(opts$mode, "update")) {
    status <- update_baseline(opts, baseline, baseline_path, current,
                              differences, stale, integrity)
  } else {
    if (is.null(baseline)) {
      message("api-inventory: baseline ", baseline_path, " does not exist")
    }
    if (has_differences(differences)) {
      message("api-inventory: unexplained API change against ",
              baseline_path, ":")
      print_differences(differences)
      message("If intended, record it with: Rscript tools/api-inventory.R ",
              "--update --reason \"<why>\"")
    }
    if (is.null(baseline) || has_differences(differences) ||
          length(stale) > 0L || length(integrity) > 0L) {
      status <- 1L
    } else {
      message("api-inventory: ", length(current$exports), " exports and ",
              length(current$s3_methods), " S3 methods match the baseline")
    }
  }
  if (!is.na(opts$report)) {
    write_json_file(list(
      mode = opts$mode,
      result = if (status == 0L) "pass" else "fail",
      exit_code = status,
      baseline = baseline_path,
      baseline_sha256 = file_sha256(baseline_path),
      baseline_exists = !is.null(baseline),
      export_count = length(current$exports),
      s3_method_count = length(current$s3_methods),
      differences = differences,
      history_entries = length(baseline$changes),
      inventory_sha256 = signature_sha256(current),
      integrity_problems = as.list(integrity),
      stale_generated_files = as.list(stale)
    ), opts$report)
  }
  status
}

update_baseline <- function(opts, baseline, baseline_path, current,
                            differences, stale, integrity = character()) {
  if (length(stale) > 0L) {
    message("api-inventory: refusing to update from stale generated files")
    return(1L)
  }
  if (is.na(opts$reason) || !nzchar(trimws(opts$reason))) {
    api_usage_error("--update needs a non-empty --reason")
  }
  if (!is.null(baseline) && !has_differences(differences) &&
        length(integrity) == 0L) {
    message("api-inventory: no API change; baseline left untouched")
    return(0L)
  }
  history <- if (is.null(baseline)) list() else baseline$changes
  # Added and removed entries are taken against the replayed history, so the
  # history stays replayable even when it repairs an edited baseline.
  now <- radix_sort(names(signature_table(current)))
  before <- replay_history(history)
  entry <- list(
    date = format(Sys.time(), "%Y-%m-%d", tz = "UTC"),
    reason = trimws(opts$reason),
    added = as.list(setdiff(now, before)),
    removed = as.list(setdiff(before, now)),
    changed = differences$changed,
    inventory_sha256 = signature_sha256(current)
  )
  if (length(integrity) > 0L) {
    entry$repaired_integrity_problems <- as.list(integrity)
  }
  updated <- current
  updated$changes <- c(history, list(entry))
  write_json_file(updated, baseline_path)
  message("api-inventory: baseline written to ", baseline_path)
  print_differences(differences)
  0L
}

if (!interactive()) {
  exit_status <- tryCatch(
    run_api_inventory(parse_api_args(commandArgs(trailingOnly = TRUE))),
    error = function(e) {
      message("api-inventory: error: ", conditionMessage(e))
      2L
    }
  )
  quit(save = "no", status = exit_status)
}
