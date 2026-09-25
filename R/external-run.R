#' Run a pinned external method in an isolated process
#'
#' Execute an explicitly trusted method without rewriting its script or
#' analytical settings. This compatibility boundary does not use the live
#' Java bridge and does not imply support for a different QuPath version.
#' No program starts until trust and all file hashes have been checked.
#'
#' @param program Absolute path to the executable, also included in `pins`.
#' @param args Character argument vector. Literal `{{workspace}}` tokens
#'   expand to the new output directory; no shell interprets arguments.
#' @param pins Data frame with `path` and `sha256` character columns. Pin the
#'   executable and every script, model, runtime and input used by the method.
#'   Hashes are checked before and after execution. The function cannot infer
#'   undeclared transitive dependencies of an arbitrary executable.
#' @param outputs Non-empty vector of relative output file paths. Files must
#'   be non-empty and resolve inside the workspace. Their hashes are recorded.
#'   Presence does not validate analytical content: the pinned method must
#'   perform its own semantic validation before returning successfully.
#' @param workspace A new, not-yet-existing directory in an existing parent.
#'   It is retained on both success and failure, with logs and a run receipt.
#' @param timeout Finite positive time limit in seconds.
#' @param trust Exactly `TRUE` to authorize execution of the pinned code.
#'   Hash integrity is not a substitute for deciding to trust that code.
#' @param env Named character vector of additional environment variables.
#'   Home, display, temporary-directory and Java-option overrides are forbidden.
#'   Do not supply secrets: the full explicit child environment is recorded.
#' @return A list with `status = "execution_complete"`, command, pinned file
#'   and output hashes, process exit status, timestamps and workspace. This
#'   proves execution and output presence, not scientific reproduction,
#'   successful project saving or semantic validity of the outputs.
#' @details The child receives a stripped environment, private home/XDG/Java
#'   preference and temporary directories, no display, and a bounded timeout.
#'   Private temporary directories are removed after the child exits. This is
#'   process hygiene, not a sandbox for malicious trusted code. Only processes
#'   started by this call are cleaned up. Existing output directories are never
#'   reused. No downloads, dependency installation or silent runtime upgrade
#'   occur. A nonzero exit or missing output never counts as completion.
#'   The initial execution profile supports Linux only; other platforms fail
#'   before creating a workspace. In particular, no Windows atomic-replacement
#'   or process-cleanup behavior is claimed without platform qualification.
#' @export
#' @examples
#' # Configure executable, script/model/input SHA-256 pins and output paths
#' # before explicitly authorizing a method with trust = TRUE.
qp_run_external <- function(program, args, pins, outputs, workspace,
                            timeout = 600, trust = FALSE,
                            env = character()) {
  if (!identical(trust, TRUE)) qp_abort("TRUST_REQUIRED")
  if (!identical(ext_system(), "Linux")) {
    qp_abort("RUNTIME_UNAVAILABLE", details = list(supported_system = "Linux"))
  }
  qp_check_installed(c("digest", "processx"), "external method execution")
  spec <- ext_validate(program, args, pins, outputs, workspace, timeout, env)
  ext_check_pins(spec$pins)
  if (!dir.create(spec$workspace, mode = "0700", showWarnings = FALSE)) {
    ext_invalid("workspace", "a new writable directory")
  }
  private <- tempfile("qpx-")
  ext_mkdir(private)
  on.exit(unlink(private, recursive = TRUE), add = TRUE)
  child_env <- ext_environment(private, env)
  command_args <- gsub("{{workspace}}", spec$workspace, args, fixed = TRUE)
  record <- list(
    schema_version = "1", status = "running", program = spec$program,
    args = command_args, pins = spec$pins, environment = as.list(child_env),
    workspace = spec$workspace, started = ext_time(), timeout = timeout
  )
  ext_receipt(record)
  result <- tryCatch(processx::run(
    spec$program, command_args,
    wd = spec$workspace, env = child_env,
    timeout = timeout, error_on_status = FALSE, cleanup_tree = TRUE,
    stdout = file.path(spec$workspace, "stdout.log"),
    stderr = file.path(spec$workspace, "stderr.log")
  ), error = function(e) {
    record$status <- "process_error"
    record$finished <- ext_time()
    ext_receipt(record)
    qp_abort("RUNTIME_UNAVAILABLE", parent = e)
  })
  record$exit_status <- result$status
  record$finished <- ext_time()
  if (!identical(result$status, 0L) || isTRUE(result$timeout)) {
    record$status <- if (isTRUE(result$timeout)) "timeout" else "failed"
    ext_receipt(record)
    qp_abort("RUNTIME_UNAVAILABLE", details = list(status = record$status))
  }
  tryCatch(
    {
      ext_check_pins(spec$pins)
      record$outputs <- ext_outputs(outputs, spec$workspace)
    },
    qp_error = function(e) {
      record$status <- "validation_failed"
      ext_receipt(record)
      qp_abort(e$code, parent = e)
    }
  )
  record$status <- "execution_complete"
  ext_receipt(record)
  record
}

ext_invalid <- function(arg, expected) {
  qp_abort("INVALID_ARGUMENT", arg = arg, expected = expected)
}

ext_strings <- function(x, empty = FALSE) {
  is.character(x) && !anyNA(x) && (empty || length(x) > 0L) &&
    all(nzchar(x))
}

ext_validate <- function(program, args, pins, outputs, workspace,
                         timeout, env) {
  if (!ext_strings(program) || length(program) != 1L ||
        !grepl("^(/|[A-Za-z]:[/\\])", program) ||
        !file.exists(program) || dir.exists(program)) {
    ext_invalid("program", "an existing executable file")
  }
  program <- normalizePath(program, winslash = "/", mustWork = TRUE)
  if (!ext_strings(args, empty = TRUE)) {
    ext_invalid("args", "a character argument vector")
  }
  if (!is.data.frame(pins) || !all(c("path", "sha256") %in% names(pins)) ||
        !ext_strings(pins$path) || !ext_strings(pins$sha256) ||
        any(!grepl("^[a-f0-9]{64}$", pins$sha256)) ||
        any(!file.exists(pins$path)) || any(dir.exists(pins$path))) {
    ext_invalid("pins", "existing files with lowercase SHA-256 hashes")
  }
  pins <- pins[, c("path", "sha256"), drop = FALSE]
  pins$path <- normalizePath(pins$path, winslash = "/", mustWork = TRUE)
  if (anyDuplicated(pins$path) || !program %in% pins$path) {
    ext_invalid("pins", "unique files including the executable")
  }
  if (!ext_strings(outputs) || anyDuplicated(outputs) ||
        any(grepl("(^/|^[A-Za-z]:|\\\\|(^|/)\\.\\.?(/|$))", outputs)) ||
        any(outputs %in% c("stdout.log", "stderr.log", "run.json"))) {
    ext_invalid("outputs", "distinct relative paths without traversal")
  }
  if (!ext_strings(workspace) || length(workspace) != 1L ||
        file.exists(workspace) || !dir.exists(dirname(workspace)) ||
        basename(workspace) %in% c(".", "..")) {
    ext_invalid("workspace", "a new directory under an existing parent")
  }
  workspace <- file.path(
    normalizePath(dirname(workspace), winslash = "/"),
    basename(workspace)
  )
  if (!is.numeric(timeout) || length(timeout) != 1L ||
        !is.finite(timeout) || timeout <= 0) {
    ext_invalid("timeout", "one positive finite number")
  }
  reserved <- c(
    "PATH", "LANG", "HOME", "DISPLAY", "WAYLAND_DISPLAY",
    "TMPDIR", "TMP", "TEMP",
    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"
  )
  valid_names <- TRUE
  if (length(env)) {
    valid_names <- !is.null(names(env)) && !anyNA(names(env)) &&
      all(grepl("^[A-Za-z_][A-Za-z0-9_]*$", names(env))) &&
      !anyDuplicated(names(env)) && !any(names(env) %in% reserved) &&
      !any(startsWith(names(env), "XDG_"))
  }
  if (!is.character(env) || anyNA(env) || !valid_names) {
    ext_invalid("env", "named variables without isolation overrides")
  }
  list(program = program, pins = pins, workspace = workspace)
}

ext_check_pins <- function(pins) {
  for (i in seq_len(nrow(pins))) {
    observed <- if (file.exists(pins$path[i])) {
      digest::digest(file = pins$path[i], algo = "sha256")
    } else {
      NA_character_
    }
    if (!identical(observed, pins$sha256[i])) {
      qp_abort("INPUT_MISMATCH", details = list(pin_index = i))
    }
  }
}

ext_environment <- function(private, env) {
  dirs <- file.path(private, c(
    "home", "config", "cache", "data", "state",
    "tmp", "prefs"
  ))
  for (dir in dirs) ext_mkdir(dir)
  java <- paste0(
    c(
      "-Duser.home=", "-Djava.util.prefs.userRoot=",
      "-Djava.io.tmpdir=", "-Djna.tmpdir="
    ),
    ext_java_quote(dirs[c(1L, 7L, 6L, 6L)])
  )
  c(
    PATH = Sys.getenv("PATH"), LANG = "C.UTF-8", HOME = dirs[1L],
    XDG_CONFIG_HOME = dirs[2L], XDG_CACHE_HOME = dirs[3L],
    XDG_DATA_HOME = dirs[4L], XDG_STATE_HOME = dirs[5L],
    TMPDIR = dirs[6L], TMP = dirs[6L], TEMP = dirs[6L],
    JAVA_TOOL_OPTIONS = paste(c(
      "-Djava.awt.headless=true", "-Dprism.order=sw",
      java
    ), collapse = " "), env
  )
}

ext_outputs <- function(outputs, workspace) {
  paths <- file.path(workspace, outputs)
  real <- normalizePath(paths, winslash = "/", mustWork = FALSE)
  if (any(!file.exists(paths)) || any(dir.exists(paths)) ||
        any(!startsWith(real, paste0(workspace, "/"))) ||
        any(file.info(paths)$size <= 0)) {
    qp_abort("OUTPUT_INVALID")
  }
  data.frame(
    path = outputs, bytes = as.double(file.info(paths)$size),
    sha256 = vapply(paths, function(p) {
      digest::digest(file = p, algo = "sha256")
    }, character(1), USE.NAMES = FALSE)
  )
}

ext_time <- function() format(Sys.time(), "%Y-%m-%dT%H:%M:%OSZ", tz = "UTC")

ext_receipt <- function(record) {
  target <- file.path(record$workspace, "run.json")
  temp <- paste0(target, ".partial")
  jsonlite::write_json(record, temp,
    auto_unbox = TRUE, pretty = TRUE,
    dataframe = "rows", null = "null"
  )
  if (!suppressWarnings(file.rename(temp, target))) qp_abort("OUTPUT_INVALID")
  invisible(record)
}

ext_system <- function() unname(Sys.info()[["sysname"]])

ext_mkdir <- function(path) {
  if (!dir.create(path, mode = "0700", showWarnings = FALSE)) {
    qp_abort("RUNTIME_UNAVAILABLE",
             details = list(reason = "private_directory_creation_failed"))
  }
  invisible(path)
}

ext_java_quote <- function(value) {
  paste0('"', gsub('"', "\"'\"'\"", value, fixed = TRUE), '"')
}
