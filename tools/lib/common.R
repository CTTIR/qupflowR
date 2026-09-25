# Shared helpers of tools/build.R and tools/validate.R.
#
# Development tooling only; nothing here is part of the R package (tools/ is
# excluded by .Rbuildignore). The entry scripts load this file with
# sys.source() into one environment and call the helpers as tl$<name>(). The
# other files in tools/lib/ receive that environment through their context.
#
# Conventions:
#   * Errors are cli conditions of class qp_tool_<kind>_error. The kinds
#     "usage" and "environment" end a script with status 2, "failed" with
#     status 1.
#   * Child processes run through processx with argument vectors and a fully
#     explicit environment (child_env()): HOME, the XDG directories and
#     TMPDIR are private, and DISPLAY, WAYLAND_DISPLAY, JAVA_TOOL_OPTIONS and
#     the caller's variables are never inherited.
#   * A command that outlives its timeout first receives SIGTERM, so scripts
#     can run their own cleanup traps, and only then is its process tree
#     killed. processx only kills processes that it started itself.
#   * JSON files are written atomically (temporary file, then rename).

report_schema_version <- "1.0"

# ---- conditions and entry points -----------------------------------------

tool_abort <- function(kind, message, details = list()) {
  cli::cli_abort(
    "{message}",
    class = c(paste0("qp_tool_", kind, "_error"), "qp_tool_error", "qp_error"),
    details = details,
    call = NULL
  )
}

# Condition message on one line (cli wraps long messages).
condition_text <- function(cnd) {
  gsub("\\s*\n\\s*", " ", conditionMessage(cnd))
}

say <- function(label, ..., quiet = FALSE) {
  if (!isTRUE(quiet)) {
    message(label, ": ", ...)
  }
  invisible(NULL)
}

run_main <- function(label, fun) {
  report <- function(cnd, prefix = "") {
    message(label, ": ", prefix, condition_text(cnd))
  }
  status <- tryCatch(
    fun(commandArgs(trailingOnly = TRUE)),
    qp_tool_usage_error = function(cnd) {
      report(cnd)
      2L
    },
    qp_tool_environment_error = function(cnd) {
      report(cnd)
      2L
    },
    qp_tool_error = function(cnd) {
      report(cnd)
      1L
    },
    interrupt = function(cnd) {
      message(label, ": interrupted")
      130L
    },
    error = function(cnd) {
      report(cnd, "unexpected error: ")
      1L
    }
  )
  quit(save = "no", status = as.integer(status))
}

# The leading comment block of a script (after an optional shebang line).
usage_text <- function(path) {
  lines <- readLines(path, warn = FALSE, encoding = "UTF-8")
  if (length(lines) > 0L && startsWith(lines[[1L]], "#!")) {
    lines <- lines[-1L]
  }
  n <- match(FALSE, startsWith(lines, "#"), nomatch = length(lines) + 1L) - 1L
  sub("^# ?", "", lines[seq_len(n)])
}

# ---- arguments ------------------------------------------------------------

option_key <- function(arg) {
  gsub("-", "_", sub("^--", "", arg), fixed = TRUE)
}

# flags: switches without a value; values: options that take one value
# (--name VALUE or --name=VALUE). Unknown arguments are usage errors.
parse_args <- function(args, flags = character(), values = character()) {
  opts <- list()
  i <- 1L
  while (i <= length(args)) {
    arg <- args[[i]]
    inline <- regmatches(arg, regexec("^(--[^=]+)=(.*)$", arg))[[1L]]
    if (length(inline) == 3L && inline[[2L]] %in% values) {
      opts[[option_key(inline[[2L]])]] <- inline[[3L]]
    } else if (arg %in% flags) {
      opts[[option_key(arg)]] <- TRUE
    } else if (arg %in% values) {
      if (i == length(args) || !nzchar(args[[i + 1L]])) {
        tool_abort("usage", paste(arg, "needs a value"))
      }
      opts[[option_key(arg)]] <- args[[i + 1L]]
      i <- i + 1L
    } else {
      tool_abort("usage", paste("unknown argument:", arg, "(see --help)"))
    }
    i <- i + 1L
  }
  opts
}

opt_or <- function(opts, name, default) {
  value <- opts[[name]]
  if (is.null(value)) default else value
}

env_or <- function(variable, default) {
  value <- Sys.getenv(variable)
  if (nzchar(value)) value else default
}

check_tmp_prefix <- function(prefix) {
  if (!grepl("^[A-Za-z0-9._-]{1,24}$", prefix)) {
    tool_abort("usage", paste(
      "--tmp-prefix must be 1 to 24 characters from A-Z, a-z, 0-9, '.', '_'",
      "and '-'"
    ))
  }
  prefix
}

require_packages <- function(pkgs) {
  ok <- vapply(pkgs, requireNamespace, logical(1), quietly = TRUE)
  if (!all(ok)) {
    tool_abort("environment", paste(
      "required R packages are not installed:", toString(pkgs[!ok])
    ))
  }
  invisible(pkgs)
}

# ---- paths ----------------------------------------------------------------

absolute_path <- function(path, base = getwd()) {
  path <- path.expand(path)
  if (!grepl("^(/|[A-Za-z]:[/\\\\])", path)) {
    path <- file.path(base, path)
  }
  normalizePath(path, winslash = "/", mustWork = FALSE)
}

is_within <- function(path, dir) {
  path <- paste0(normalizePath(path, winslash = "/", mustWork = FALSE), "/")
  dir <- paste0(normalizePath(dir, winslash = "/", mustWork = FALSE), "/")
  startsWith(path, dir)
}

relative_to <- function(path, root) {
  if (is.null(path) || length(path) == 0L) {
    return(path)
  }
  prefix <- paste0(normalizePath(root, winslash = "/"), "/")
  full <- normalizePath(path, winslash = "/", mustWork = FALSE)
  ifelse(startsWith(full, prefix), substring(full, nchar(prefix) + 1L), full)
}

# Removes and recreates a directory, but only strictly below `guard`.
reset_dir <- function(dir, guard) {
  if (!is_within(dir, guard) ||
        identical(normalizePath(dir, winslash = "/", mustWork = FALSE),
                  normalizePath(guard, winslash = "/", mustWork = FALSE))) {
    tool_abort("usage", paste("refusing to reset", dir, "outside", guard))
  }
  unlink(dir, recursive = TRUE)
  dir.create(dir, recursive = TRUE, showWarnings = FALSE)
  invisible(dir)
}

ensure_dir <- function(dir) {
  dir.create(dir, recursive = TRUE, showWarnings = FALSE)
  if (!dir.exists(dir)) {
    tool_abort("environment", paste("cannot create directory", dir))
  }
  invisible(dir)
}

# ---- hashing and JSON ------------------------------------------------------

sha256_files <- function(paths) {
  if (length(paths) == 0L) {
    return(character())
  }
  unname(tools::sha256sum(paths))
}

sha256_text <- function(text) {
  bytes <- charToRaw(enc2utf8(paste(text, collapse = "")))
  if ("bytes" %in% names(formals(tools::sha256sum))) {
    return(unname(tools::sha256sum(bytes = bytes)))
  }
  digest::digest(bytes, algo = "sha256", serialize = FALSE)
}

empty_object <- function() {
  structure(list(), names = character())
}

# Named list for JSON objects; an empty input stays an object.
as_object <- function(x) {
  if (length(x) == 0L) empty_object() else as.list(x)
}

utc_now <- function(time = Sys.time()) {
  format(time, "%Y-%m-%dT%H:%M:%SZ", tz = "UTC")
}

write_json_file <- function(x, path) {
  json <- jsonlite::toJSON(x, auto_unbox = TRUE, pretty = TRUE, null = "null",
                           na = "null", digits = NA)
  ensure_dir(dirname(path))
  tmp <- paste0(path, ".tmp-", Sys.getpid())
  writeLines(enc2utf8(as.character(json)), tmp, useBytes = TRUE)
  if (!file.rename(tmp, path)) {
    unlink(tmp)
    tool_abort("environment", paste("cannot write", path))
  }
  invisible(path)
}

read_json_file <- function(path) {
  if (!file.exists(path)) {
    return(NULL)
  }
  tryCatch(jsonlite::fromJSON(path, simplifyVector = FALSE),
           error = function(e) NULL)
}

write_sha256_sidecar <- function(file) {
  sidecar <- paste0(file, ".sha256")
  line <- paste0(sha256_files(file), "  ", basename(file))
  writeLines(line, sidecar, useBytes = TRUE)
  invisible(sidecar)
}

# The sha256sum-format sidecar "<hex>  <name>" must name the file and match.
sidecar_matches <- function(file) {
  sidecar <- paste0(file, ".sha256")
  if (!file.exists(file) || !file.exists(sidecar)) {
    return(FALSE)
  }
  line <- readLines(sidecar, warn = FALSE, n = 1L)
  parts <- strsplit(trimws(line), "[ *]+")[[1L]]
  length(parts) == 2L && identical(parts[[2L]], basename(file)) &&
    identical(tolower(parts[[1L]]), sha256_files(file))
}

# Digest of a directory tree: sha256 over "<sha256>\t<relative path>\n"
# lines sorted by path in C order.
tree_digest <- function(dir, root) {
  if (!dir.exists(dir)) {
    return(NULL)
  }
  files <- sort(list.files(dir, recursive = TRUE, all.files = TRUE,
                           no.. = TRUE), method = "radix")
  hashes <- sha256_files(file.path(dir, files))
  lines <- paste0(hashes, "\t", files, "\n")
  list(path = relative_to(dir, root), file_count = length(files),
       sha256 = sha256_text(paste(lines, collapse = "")))
}

# ---- runtime directories and child environments ----------------------------

short_tmp_root <- function() {
  if (.Platform$OS.type == "unix" && file.access("/tmp", 2L) == 0L) {
    "/tmp"
  } else {
    dirname(tempdir())
  }
}

# Private HOME, XDG directories, Java preference root and a short TMPDIR for
# every child process of one script run.
new_runtime <- function(work_dir, label, tmp_prefix) {
  stamp <- format(Sys.time(), "%Y%m%dT%H%M%SZ", tz = "UTC")
  base <- file.path(work_dir, sprintf("%s-%s-%d", label, stamp, Sys.getpid()))
  dirs <- list(
    base = base, home = file.path(base, "home"),
    config = file.path(base, "xdg", "config"),
    cache = file.path(base, "xdg", "cache"),
    data = file.path(base, "xdg", "data"),
    state = file.path(base, "xdg", "state"),
    prefs = file.path(base, "prefs"), logs = file.path(base, "logs")
  )
  for (d in dirs) {
    dir.create(d, recursive = TRUE, showWarnings = FALSE, mode = "0700")
  }
  tmp <- tempfile(tmp_prefix, tmpdir = short_tmp_root())
  dir.create(tmp, mode = "0700")
  if (!dir.exists(tmp) || !dir.exists(dirs$home)) {
    tool_abort("environment", paste("cannot create runtime directories in",
                                    base, "and", tmp))
  }
  dirs$tmp <- tmp
  dirs$empty_file <- file.path(base, "empty-profile")
  file.create(dirs$empty_file)
  dirs
}

cleanup_runtime <- function(rt, keep = FALSE) {
  unlink(rt$tmp, recursive = TRUE)
  if (!isTRUE(keep)) {
    unlink(rt$base, recursive = TRUE)
  }
  invisible(NULL)
}

user_library_paths <- function() {
  libs <- normalizePath(.libPaths(), winslash = "/", mustWork = FALSE)
  base <- normalizePath(c(.Library, .Library.site), winslash = "/",
                        mustWork = FALSE)
  libs[!libs %in% base]
}

child_env <- function(rt, extra = character(), path_dirs = character()) {
  user <- Sys.info()[["user"]]
  locale <- if (identical(Sys.info()[["sysname"]], "Linux")) {
    "C.UTF-8"
  } else {
    "en_US.UTF-8"
  }
  path <- unique(c(path_dirs, R.home("bin"), "/usr/local/bin", "/usr/bin",
                   "/bin"))
  env <- c(
    PATH = paste(path, collapse = .Platform$path.sep),
    HOME = rt$home, USER = user, LOGNAME = user, LANG = locale,
    LC_ALL = locale, TMPDIR = rt$tmp, XDG_CONFIG_HOME = rt$config,
    XDG_CACHE_HOME = rt$cache, XDG_DATA_HOME = rt$data,
    XDG_STATE_HOME = rt$state,
    R_LIBS_USER = paste(user_library_paths(), collapse = .Platform$path.sep),
    R_LIBS_SITE = paste(.Library.site, collapse = .Platform$path.sep),
    R_PROFILE_USER = rt$empty_file, R_ENVIRON_USER = rt$empty_file
  )
  env[names(extra)] <- extra
  env[!is.na(env)]
}

# A JVM system property for option strings that are split on white space
# (GRADLE_OPTS, org.gradle.jvmargs, JAVA_TOOL_OPTIONS); values with white
# space are quoted, which all three accept.
jvm_property <- function(name, value) {
  if (grepl("\\s", value)) {
    sprintf("-D%s=\"%s\"", name, value)
  } else {
    sprintf("-D%s=%s", name, value)
  }
}

jvm_isolation_options <- function(rt) {
  paste(jvm_property("user.home", rt$home),
        jvm_property("java.util.prefs.userRoot", rt$prefs),
        jvm_property("java.io.tmpdir", rt$tmp))
}

# ---- processes ------------------------------------------------------------

new_command_log <- function() {
  log <- new.env(parent = emptyenv())
  log$commands <- list()
  log
}

exit_codes_of <- function(command_log) {
  codes <- lapply(command_log$commands, function(cmd) cmd$exit_code)
  names(codes) <- vapply(command_log$commands, function(cmd) cmd$id, "")
  as_object(codes)
}

# Runs one command with stdout and stderr in `log`, records it in
# `command_log` and returns the exit status. A command killed by a signal
# reports 128 + signal number, a timeout 124 and a command that cannot be
# started 127.
run_logged <- function(command_log, id, command, args, log, env, wd,
                       timeout = 3600, grace = 60, label = "run",
                       quiet = FALSE) {
  ensure_dir(dirname(log))
  command <- unname(command)
  args <- unname(args)
  started <- Sys.time()
  say(label, id, quiet = quiet)
  outcome <- run_supervised(command, args, log, env, wd, timeout, grace)
  status <- outcome$status
  record <- list(
    id = id, argv = as.list(c(command, args)), wd = wd,
    env = as_object(as.list(env)), started_at = utc_now(started),
    duration_s = round(as.numeric(difftime(Sys.time(), started,
                                           units = "secs")), 1),
    exit_code = status, raw_status = outcome$raw_status,
    timed_out = outcome$timed_out, log = log, error = outcome$error
  )
  command_log$commands[[length(command_log$commands) + 1L]] <- record
  if (status != 0L) {
    say(label, id, " exited with status ", status, " (log: ", log, ")",
        quiet = quiet)
  }
  status
}

run_supervised <- function(command, args, log, env, wd, timeout, grace) {
  proc <- tryCatch(
    processx::process$new(command, args, env = env, wd = wd, stdout = log,
                          stderr = "2>&1", cleanup = TRUE,
                          cleanup_tree = TRUE),
    error = function(e) e
  )
  if (inherits(proc, "error")) {
    cat(conditionMessage(proc), "\n", file = log, append = TRUE)
    return(list(status = 127L, raw_status = NULL, timed_out = FALSE,
                error = conditionMessage(proc)))
  }
  on.exit(if (proc$is_alive()) proc$kill_tree(), add = TRUE)
  deadline <- Sys.time() + timeout
  timed_out <- FALSE
  while (proc$is_alive()) {
    proc$wait(timeout = 1000)
    if (proc$is_alive() && Sys.time() > deadline) {
      timed_out <- TRUE
      proc$signal(tools::SIGTERM)
      proc$wait(timeout = grace * 1000)
      if (proc$is_alive()) {
        proc$kill_tree()
      }
      proc$wait(timeout = 5000)
    }
  }
  raw <- proc$get_exit_status()
  status <- if (timed_out) {
    124L
  } else if (is.null(raw) || is.na(raw)) {
    1L
  } else if (raw < 0L) {
    128L - as.integer(raw)
  } else {
    as.integer(raw)
  }
  list(status = status, raw_status = raw, timed_out = timed_out, error = NULL)
}

rscript_path <- function() {
  exe <- if (.Platform$OS.type == "windows") "Rscript.exe" else "Rscript"
  file.path(R.home("bin"), exe)
}

r_path <- function() {
  exe <- if (.Platform$OS.type == "windows") "R.exe" else "R"
  file.path(R.home("bin"), exe)
}

# ---- fixture set 1 ----------------------------------------------------------

# Generates (or reuses) fixture set 1 with tools/fixtures.R, verifies it and
# runs the independent oracle tests of tests/testthat/test-fixtures-expected.R
# against the generated tree: the committed expected-value tables, the R
# TIFF reader and the QuPath project files. Used by tools/build.R --target
# fixtures and by lane L03. `p` holds cmds, checks, root, out, logs (log
# directory), summary (testthat summary file), env (generator environment),
# test_env (environment of the test run), java_home, qupath_home,
# gradle_home, online, force, label and quiet. Records its checks with the
# prefix "fixtures_" and returns the manifest, the test summary and the
# fixture digests.
fixture_oracle <- function(p) {
  script <- file.path(p$root, "tools", "fixtures.R")
  if (!file.exists(script)) {
    tool_abort("environment", paste("missing", script))
  }
  args <- c(script, "--out", p$out, "--java-home", p$java_home,
            "--qupath-home", p$qupath_home, "--gradle-user-home",
            p$gradle_home, if (isTRUE(p$online)) "--online",
            if (isTRUE(p$force)) "--force")
  status <- run_logged(p$cmds, "fixtures_generate", rscript_path(), args,
                       file.path(p$logs, "fixtures-generate.log"), p$env,
                       p$root, timeout = 3600, label = p$label,
                       quiet = p$quiet)
  add_check(p$checks, "fixtures_exit_code", 0L, status)
  verify <- run_logged(p$cmds, "fixtures_verify_only", rscript_path(),
                       c(script, "--verify-only", "--out", p$out),
                       file.path(p$logs, "fixtures-verify.log"), p$env,
                       p$root, timeout = 900, label = p$label,
                       quiet = p$quiet)
  add_check(p$checks, "fixtures_verify_only_exit_code", 0L, verify)
  manifest_path <- file.path(p$out, "manifest.json")
  manifest <- read_json_file(manifest_path)
  add_check(p$checks, "fixtures_manifest_readable", TRUE, !is.null(manifest))
  summary <- NULL
  if (status == 0L && verify == 0L && !is.null(manifest)) {
    test_args <- c(file.path(p$root, "tools", "lib", "testthat-run.R"),
                   "--root", p$root, "--out", p$summary,
                   "--filter", "^fixtures-expected$")
    env <- p$test_env
    env[["QUPFLOWR_FIXTURES_DIR"]] <- p$out
    env <- env[!names(env) %in% c("QUPFLOWR_TEST_PROFILE",
                                  "QUPFLOWR_SKIP_LOG")]
    tests <- run_logged(p$cmds, "fixtures_oracle_tests", rscript_path(),
                        test_args, file.path(p$logs, "fixtures-oracle.log"),
                        env, p$root, timeout = 1800, label = p$label,
                        quiet = p$quiet)
    summary <- read_json_file(p$summary)
    counts <- summary$counts
    add_check(p$checks, "fixtures_oracle_tests_exit_code", 0L, tests)
    add_check(p$checks, "fixtures_oracle_tests_ran", TRUE,
              isTRUE(counts$tests > 0L) && isTRUE(counts$expectations > 0L))
    add_check(p$checks, "fixtures_oracle_failed_expectations", 0L,
              counts$failed)
    add_check(p$checks, "fixtures_oracle_erroring_expectations", 0L,
              counts$errors)
    # Every generated-tree test must run: a skip here means the oracle did
    # not look at the generated files.
    add_check(p$checks, "fixtures_oracle_skipped_expectations", 0L,
              counts$skipped)
  } else {
    add_check(p$checks, "fixtures_oracle_tests_ran", TRUE, FALSE)
  }
  digests <- list()
  if (!is.null(manifest)) {
    digests[[relative_to(manifest_path, p$root)]] <-
      sha256_files(manifest_path)
    expected <- file.path(p$out, paste0(manifest$oracle$expected_values, ""))
    if (file.exists(expected)) {
      digests[[relative_to(expected, p$root)]] <- sha256_files(expected)
    }
  }
  digests$committed_expected_tables <- tree_digest(
    file.path(p$root, "tests", "fixtures", "qp-v1"), p$root
  )
  list(manifest = manifest, summary = summary, fixture_digests = digests)
}

# ---- source inventory ------------------------------------------------------

inventory_exclusions <- function() {
  c("^\\.git/", "^\\.tools/", "^work/", "^artifacts/", "^site/", "^setup/",
    "^renv/(library|staging|local)/", "\\.Rcheck/", "^tools/bridge/\\.gradle/",
    "^tools/bridge/\\.kotlin/", "^tools/bridge/(.*/)?build/",
    "^qupflowR_.*\\.tar\\.gz$", "(^|/)\\.Rproj\\.user/", "(^|/)\\.Rhistory$")
}

# Every source file the way git sees it (tracked, and untracked but not
# ignored), so uncommitted changes count. Without git the tree is walked
# with the exclusions above. The digest is sha256 over the sorted lines
# "<sha256>\t<path>\n".
source_inventory <- function(root, rt = NULL) {
  files <- NULL
  method <- "walk"
  git <- Sys.which("git")
  if (nzchar(git) && file.exists(file.path(root, ".git"))) {
    # Without a runtime (tools/quality.R) git gets a minimal environment with
    # a temporary HOME, so no user configuration changes the listing.
    env <- if (is.null(rt)) {
      c(PATH = Sys.getenv("PATH"), HOME = tempdir(), LANG = "C.UTF-8",
        LC_ALL = "C.UTF-8")
    } else {
      child_env(rt)
    }
    res <- tryCatch(
      processx::run(git, c("-C", root, "-c", "core.quotepath=off", "ls-files",
                           "--cached", "--others", "--exclude-standard"),
                    env = env, error_on_status = FALSE),
      error = function(e) NULL
    )
    if (!is.null(res) && identical(res$status, 0L)) {
      files <- strsplit(res$stdout, "\n", fixed = TRUE)[[1L]]
      files <- files[nzchar(files)]
      method <- "git ls-files --cached --others --exclude-standard"
    }
  }
  if (is.null(files)) {
    files <- list.files(root, recursive = TRUE, all.files = TRUE, no.. = TRUE)
    drop <- Reduce("|", lapply(inventory_exclusions(), grepl, x = files))
    files <- files[!drop]
  }
  paths <- file.path(root, files)
  files <- sort(unique(files[file.exists(paths) & !dir.exists(paths)]),
                method = "radix")
  hashes <- sha256_files(file.path(root, files))
  lines <- paste0(hashes, "\t", files, "\n")
  list(method = method, file_count = length(files),
       sha256 = sha256_text(paste(lines, collapse = "")),
       lines = lines, includes_uncommitted = TRUE)
}

inventory_summary <- function(inventory, tsv = NULL, root = NULL) {
  list(algorithm = "sha256", method = inventory$method,
       format = "sha256 over sorted lines '<sha256>\\t<path>\\n'",
       includes_uncommitted = TRUE, file_count = inventory$file_count,
       sha256 = inventory$sha256,
       listing = if (is.null(tsv)) NULL else relative_to(tsv, root))
}

write_inventory <- function(inventory, path) {
  ensure_dir(dirname(path))
  writeLines(sub("\n$", "", inventory$lines), path, useBytes = TRUE)
  invisible(path)
}

git_revision <- function(root, rt) {
  git <- Sys.which("git")
  if (!nzchar(git)) {
    return(NULL)
  }
  res <- tryCatch(
    processx::run(git, c("-C", root, "rev-parse", "--verify", "-q", "HEAD"),
                  env = child_env(rt), error_on_status = FALSE),
    error = function(e) NULL
  )
  if (is.null(res) || !identical(res$status, 0L)) {
    return(NULL)
  }
  trimws(res$stdout)
}

# Task frontmatter (feature_ids, quality_ids, validation_lanes) from the
# local planning kit, when it is present (setup/ is not part of the
# repository).
task_metadata <- function(root, task_id) {
  dir <- file.path(root, "setup", "qupathr-build-kit", "workplan", "tasks")
  file <- list.files(dir, pattern = paste0("^", task_id, "_.*\\.md$"),
                     full.names = TRUE)
  if (length(file) != 1L) {
    return(list(feature_ids = list(), quality_ids = list(),
                validation_lanes = list(), source = NULL))
  }
  lines <- readLines(file, warn = FALSE, n = 20L, encoding = "UTF-8")
  field <- function(name) {
    line <- grep(paste0("^", name, ":"), lines, value = TRUE)
    if (length(line) == 0L) {
      return(list())
    }
    items <- strsplit(gsub("^[^\\[]*\\[|\\].*$", "", line[[1L]]), ",")[[1L]]
    as.list(trimws(items[nzchar(trimws(items))]))
  }
  list(feature_ids = field("feature_ids"), quality_ids = field("quality_ids"),
       validation_lanes = field("validation_lanes"),
       source = relative_to(file, root), source_sha256 = sha256_files(file))
}

# ---- runtime facts ---------------------------------------------------------

package_version_or_null <- function(pkg) {
  tryCatch(as.character(utils::packageVersion(pkg)), error = function(e) NULL)
}

os_release <- function() {
  path <- "/etc/os-release"
  if (!file.exists(path)) {
    return(NULL)
  }
  line <- grep("^PRETTY_NAME=", readLines(path, warn = FALSE), value = TRUE)
  if (length(line) == 0L) NULL else gsub("^PRETTY_NAME=\"?|\"$", "", line[[1L]])
}

runtime_profile <- function(packages = character(), extra = list()) {
  info <- Sys.info()
  pkgs <- lapply(packages, package_version_or_null)
  names(pkgs) <- packages
  c(list(
    os = list(sysname = info[["sysname"]], release = info[["release"]],
              machine = info[["machine"]], pretty_name = os_release()),
    r = list(version = R.version.string, platform = R.version$platform),
    packages = as_object(pkgs)
  ), extra)
}

read_key_value_file <- function(path) {
  if (!file.exists(path)) {
    return(list())
  }
  lines <- grep("^[A-Za-z_][A-Za-z0-9_]*=", readLines(path, warn = FALSE),
                value = TRUE)
  values <- gsub("^\"|\"$", "", sub("^[^=]*=", "", lines))
  stats::setNames(as.list(values), sub("=.*$", "", lines))
}

java_home_info <- function(java_home) {
  release <- read_key_value_file(file.path(java_home, "release"))
  version <- release$JAVA_VERSION
  feature <- if (is.null(version)) NA_integer_ else
    suppressWarnings(as.integer(sub("^(1\\.)?([0-9]+).*$", "\\2", version)))
  list(
    java_home = java_home, exists = dir.exists(java_home),
    java = file.exists(file.path(java_home, "bin", "java")),
    javac = file.exists(file.path(java_home, "bin", "javac")),
    java_version = version, feature_version = feature,
    runtime_version = release$JAVA_RUNTIME_VERSION,
    implementor = release$IMPLEMENTOR,
    implementor_version = release$IMPLEMENTOR_VERSION,
    image_type = release$IMAGE_TYPE, os_arch = release$OS_ARCH
  )
}

# The declared R minimum of a DESCRIPTION file, or NA.
declared_r_minimum <- function(root) {
  depends <- read.dcf(file.path(root, "DESCRIPTION"),
                      fields = "Depends")[1L, 1L]
  hit <- regmatches(depends,
                    regexec("R \\(>= *([0-9.]+)\\)", depends))[[1L]]
  if (length(hit) == 2L) hit[[2L]] else NA_character_
}

# "x.y.0" of the release series of a version string.
r_series_start <- function(version) {
  paste0(sub("^([0-9]+\\.[0-9]+).*$", "\\1", version), ".0")
}

# R installations besides the running R that check the package in lane L11
# (and are recorded by the preflight). QUPFLOWR_EXTRA_R_HOMES lists
# installation prefixes (directories holding bin/R and lib/R), separated by
# the path separator; without it every <root>/.tools/R-<x.y.z> is used. The
# dependency library of an installation is the sibling directory
# R-lib-<x.y>: packages built by that R series, never the user library of the
# running R.
extra_r_installations <- function(root) {
  spec <- Sys.getenv("QUPFLOWR_EXTRA_R_HOMES")
  homes <- if (nzchar(spec)) {
    strsplit(spec, .Platform$path.sep, fixed = TRUE)[[1L]]
  } else {
    Sys.glob(file.path(root, ".tools", "R-[0-9]*.[0-9]*.[0-9]*"))
  }
  homes <- sort(unique(normalizePath(homes[nzchar(homes)], winslash = "/",
                                     mustWork = FALSE)), method = "radix")
  lapply(homes, function(home) {
    header <- file.path(home, "lib", "R", "include", "Rversion.h")
    lines <- if (file.exists(header)) readLines(header, warn = FALSE) else ""
    define <- function(name) {
      line <- grep(paste0("^#define ", name, " +\"[0-9.]+\""), lines,
                   value = TRUE)
      if (length(line) == 1L) sub("^[^\"]*\"([0-9.]+)\".*$", "\\1", line) else
        NA_character_
    }
    version <- paste0(define("R_MAJOR"), ".", define("R_MINOR"))
    if (!grepl("^[0-9]+\\.[0-9]+\\.[0-9]+$", version)) {
      version <- NA_character_
    }
    series <- sub("\\.[0-9]+$", "", version)
    library <- file.path(dirname(home), paste0("R-lib-", series))
    r <- file.path(home, "bin", "R")
    list(home = home, r = r, version = version, series = series,
         library = library,
         usable = !is.na(version) && file.exists(r) && dir.exists(library))
  })
}

java_home_ok <- function(info, feature = 25L) {
  isTRUE(info$java) && isTRUE(info$javac) &&
    identical(info$feature_version, as.integer(feature))
}

read_zip_entry <- function(zip, entry) {
  con <- unz(zip, entry)
  on.exit(close(con))
  suppressWarnings(readLines(con, warn = FALSE, encoding = "UTF-8"))
}

jar_manifest <- function(jar) {
  lines <- tryCatch(read_zip_entry(jar, "META-INF/MANIFEST.MF"),
                    error = function(e) character())
  lines <- sub("\r$", "", lines)
  merged <- character()
  for (line in lines) {
    if (startsWith(line, " ") && length(merged) > 0L) {
      merged[[length(merged)]] <- paste0(merged[[length(merged)]],
                                         substring(line, 2L))
    } else if (nzchar(line)) {
      merged <- c(merged, line)
    }
  }
  keyed <- grepl("^[^:]+: ", merged)
  stats::setNames(as.list(sub("^[^:]+: ", "", merged[keyed])),
                  sub(": .*$", "", merged[keyed]))
}

qupath_home_info <- function(qupath_home, version = "0.7.0") {
  app <- file.path(qupath_home, "lib", "app")
  core <- file.path(app, paste0("qupath-core-", version, ".jar"))
  manifest <- if (file.exists(core)) jar_manifest(core) else list()
  runtime <- read_key_value_file(file.path(qupath_home, "lib", "runtime",
                                           "release"))
  launcher <- file.path(qupath_home, "bin", "QuPath")
  list(
    qupath_home = qupath_home, exists = dir.exists(qupath_home),
    core_jar = if (file.exists(core)) core else NULL,
    core_jar_sha256 = if (file.exists(core)) sha256_files(core) else NULL,
    implementation_version = manifest[["Implementation-Version"]],
    latest_commit = manifest[["QuPath-latest-commit"]],
    jar_count = length(list.files(app, pattern = "\\.jar$")),
    bundled_runtime_java_version = runtime$JAVA_VERSION,
    launcher = launcher, launcher_exists = file.exists(launcher)
  )
}

gradle_properties <- function(bridge_dir) {
  read_key_value_file(file.path(bridge_dir, "gradle.properties"))
}

# Offline Gradle works when the wrapper distribution and the module cache
# already exist in the Gradle user home.
gradle_offline_ok <- function(bridge_dir, gradle_home) {
  props <- read_key_value_file(file.path(bridge_dir, "gradle", "wrapper",
                                         "gradle-wrapper.properties"))
  url <- gsub("\\\\:", ":", paste0(props$distributionUrl, ""))
  dist <- sub("\\.zip$", "", basename(url))
  nzchar(dist) &&
    dir.exists(file.path(gradle_home, "wrapper", "dists", dist)) &&
    dir.exists(file.path(gradle_home, "caches", "modules-2"))
}

gradle_wrapper <- function(bridge_dir) {
  name <- if (.Platform$OS.type == "windows") "gradlew.bat" else "gradlew"
  file.path(bridge_dir, name)
}

# JUnit XML results of one Gradle test task directory per module.
junit_summary <- function(bridge_dir, since = NULL) {
  files <- Sys.glob(file.path(bridge_dir, "*", "build", "test-results",
                              "test", "TEST-*.xml"))
  attribute <- function(tag, name) {
    value <- regmatches(tag, regexec(paste0(" ", name, "=\"([0-9]+)\""),
                                     tag))[[1L]]
    if (length(value) == 2L) as.integer(value[[2L]]) else 0L
  }
  rows <- lapply(files, function(f) {
    text <- paste(readLines(f, warn = FALSE, n = 5L), collapse = " ")
    tag <- regmatches(text, regexpr("<testsuite [^>]*>", text))
    suite <- regmatches(tag, regexec(" name=\"([^\"]+)\"", tag))[[1L]]
    list(
      module = basename(dirname(dirname(dirname(dirname(f))))),
      suite = if (length(suite) == 2L) suite[[2L]] else basename(f),
      tests = attribute(tag, "tests"), failures = attribute(tag, "failures"),
      errors = attribute(tag, "errors"), skipped = attribute(tag, "skipped"),
      fresh = is.null(since) || file.mtime(f) >= since
    )
  })
  modules <- sort(unique(vapply(rows, function(r) r$module, "")))
  per_module <- lapply(modules, function(m) {
    mine <- Filter(function(r) identical(r$module, m), rows)
    total <- function(field) sum(vapply(mine, function(r) r[[field]], 0L))
    list(module = m, suites = length(mine), tests = total("tests"),
         failures = total("failures"), errors = total("errors"),
         skipped = total("skipped"),
         all_fresh = all(vapply(mine, function(r) r$fresh, TRUE)))
  })
  total <- function(field) sum(vapply(rows, function(r) r[[field]], 0L))
  list(files = length(rows), tests = total("tests"),
       failures = total("failures"), errors = total("errors"),
       skipped = total("skipped"), modules = per_module, suites = rows)
}

# "N actionable tasks: X executed, Y up-to-date" from a Gradle log.
gradle_task_summary <- function(log) {
  lines <- if (file.exists(log)) readLines(log, warn = FALSE) else character()
  line <- grep("actionable tasks?:", lines, value = TRUE)
  if (length(line) == 0L) {
    return(NULL)
  }
  line <- line[[length(line)]]
  number <- function(pattern) {
    hit <- regmatches(line, regexec(paste0("([0-9]+) ", pattern), line))[[1L]]
    if (length(hit) == 2L) as.integer(hit[[2L]]) else 0L
  }
  list(text = trimws(line), actionable = number("actionable"),
       executed = number("executed"), up_to_date = number("up-to-date"),
       from_cache = number("from cache"))
}

suite_tests <- function(junit, pattern) {
  hits <- Filter(function(r) grepl(pattern, r$suite), junit$suites)
  sum(vapply(hits, function(r) r$tests, 0L))
}

# ---- host limits ------------------------------------------------------------

read_int_file <- function(path) {
  if (!file.exists(path)) {
    return(NULL)
  }
  suppressWarnings(as.integer(readLines(path, warn = FALSE, n = 1L)))
}

# inotify instances are limited per user, and only the current user's
# processes are readable in /proc, which is exactly the population that
# shares the limit. A forked process shares its parent's instance, so the
# number of inotify descriptors is an upper bound; descriptors with
# identical fdinfo (same watch list) are counted once for the lower bound,
# which undercounts distinct instances that watch nothing.
inotify_usage <- function() {
  max_instances <- read_int_file("/proc/sys/fs/inotify/max_user_instances")
  max_watches <- read_int_file("/proc/sys/fs/inotify/max_user_watches")
  pids <- list.files("/proc", pattern = "^[0-9]+$")
  fds_total <- 0L
  holders <- 0L
  readable <- 0L
  fdinfo <- character()
  for (pid in pids) {
    fds <- list.files(file.path("/proc", pid, "fd"), full.names = TRUE)
    if (length(fds) == 0L) {
      next
    }
    readable <- readable + 1L
    mine <- fds[Sys.readlink(fds) %in% "anon_inode:inotify"]
    fds_total <- fds_total + length(mine)
    holders <- holders + as.integer(length(mine) > 0L)
    for (fd in mine) {
      info <- file.path("/proc", pid, "fdinfo", basename(fd))
      text <- tryCatch(readLines(info, warn = FALSE), error = function(e) NULL)
      if (!is.null(text)) {
        fdinfo <- c(fdinfo, paste(grep("^inotify ", text, value = TRUE),
                                  collapse = "\n"))
      }
    }
  }
  lower <- length(unique(fdinfo[nzchar(fdinfo)])) +
    as.integer(any(!nzchar(fdinfo)))
  known <- length(max_instances) == 1L && !is.na(max_instances)
  status <- if (!known) {
    "unknown"
  } else if (lower >= max_instances - 8L) {
    "low: QuPath will likely skip its extension directory silently"
  } else if (fds_total >= max_instances - 8L) {
    "possibly low: shared descriptors make the exact count unknown"
  } else {
    "ok"
  }
  list(
    max_user_instances = max_instances, max_user_watches = max_watches,
    inotify_descriptors_upper_bound = fds_total,
    distinct_watch_lists_lower_bound = lower,
    processes_holding_descriptors = holders,
    readable_processes = readable, status = status, required = FALSE
  )
}

# ---- checks and reports -----------------------------------------------------

new_checks <- function() {
  checks <- new.env(parent = emptyenv())
  checks$items <- list()
  checks
}

# Records one check; `pass` defaults to string equality of expected/actual.
add_check <- function(checks, id, expected, actual, pass = NULL) {
  expected_text <- paste(as.character(expected), collapse = ",")
  actual_text <- if (is.null(actual)) "<none>" else
    paste(as.character(actual), collapse = ",")
  if (is.null(pass)) {
    pass <- identical(expected_text, actual_text)
  }
  checks$items[[length(checks$items) + 1L]] <- list(
    id = id, expected = expected_text, actual = actual_text,
    result = if (isTRUE(pass)) "pass" else "fail"
  )
  isTRUE(pass)
}

failed_checks <- function(checks) {
  Filter(function(item) identical(item$result, "fail"), checks$items)
}

# Evidence report in the schema of docs/10 (report_schema_version, task_id,
# gate_id, feature_ids, quality_ids, lane_id, source revision and inventory,
# artifact_digests, runtime_profile, fixture_digests, commands, exit_codes,
# oracle, tolerances, result, skip_reason, logs, limitations).
evidence_report <- function(meta, body) {
  get <- function(name, default = NULL) {
    if (is.null(body[[name]])) default else body[[name]]
  }
  list(
    report_schema_version = report_schema_version,
    report_id = meta$report_id,
    task_id = meta$task_id,
    gate_id = meta$gate_id,
    feature_ids = as.list(meta$feature_ids),
    quality_ids = as.list(get("quality_ids", list())),
    lane_id = get("lane_id"),
    lane_name = get("lane_name"),
    profile = meta$profile,
    generated_at = utc_now(),
    started_at = get("started_at"),
    duration_s = get("duration_s"),
    root = meta$root,
    source_revision = meta$source_revision,
    source_inventory = meta$source_inventory,
    artifact_digests = as_object(get("artifact_digests", list())),
    runtime_profile = get("runtime_profile", empty_object()),
    fixture_digests = as_object(get("fixture_digests", list())),
    commands = get("commands", list()),
    exit_codes = get("exit_codes", empty_object()),
    oracle = get("oracle"),
    tolerances = get("tolerances"),
    checks = get("checks", list()),
    result = get("result"),
    skip_reason = get("skip_reason"),
    failure_reasons = as.list(get("failure_reasons", list())),
    logs = as.list(get("logs", list())),
    limitations = as.list(get("limitations", list())),
    details = get("details", empty_object())
  )
}

# ---- displays -------------------------------------------------------------

display_in_use <- function(number) {
  socket <- sprintf("/tmp/.X11-unix/X%d", number)
  lock <- sprintf("/tmp/.X%d-lock", number)
  abstract <- if (file.exists("/proc/net/unix")) {
    any(grepl(paste0("@", socket, "$"), readLines("/proc/net/unix",
                                                  warn = FALSE)))
  } else {
    FALSE
  }
  file.exists(socket) || file.exists(lock) || abstract
}

# Private X displays only: :90 and above, never the desktop display.
free_display <- function(candidates = c(94:99, 90:93)) {
  for (number in candidates) {
    if (number >= 90L && !display_in_use(number)) {
      return(paste0(":", number))
    }
  }
  NULL
}
