# Prerequisite checks and skip helpers for integration tests.
#
# Each skip_if_no_*() helper checks one external prerequisite without
# starting QuPath, a GUI or a browser, and without reading or writing user
# profiles. Only explicitly configured locations count: nothing is searched on
# PATH or in the home directory, and the desktop display is never used. When a
# prerequisite is missing the test is skipped, and one JSON line with a stable
# reason code is appended to the file named by QUPFLOWR_SKIP_LOG (if set).
#
# Environment variables read here:
#   QUPFLOWR_TEST_PROFILE     "core" skips every integration prerequisite
#   QUPFLOWR_SKIP_LOG         JSON-lines file that receives skip records
#   QUPFLOWR_JAVA             Java executable; otherwise JAVA_HOME/bin/java
#   QUPFLOWR_QUPATH_HOME      QuPath installation root
#   QUPFLOWR_BRIDGE_ARTIFACT  bridge bundle zip, optional <zip>.sha256 file
#   QUPFLOWR_TEST_DISPLAY     isolated X display such as :90
#   CHROMOTE_CHROME           project-local chrome-headless-shell binary
#   QUPFLOWR_PYTHON_PROFILE   pinned Python profile directory

qp_prereq_ok <- function(value, details = list()) {
  list(ok = TRUE, reason_code = NA_character_, reason = NA_character_,
       value = value, details = details)
}

qp_prereq_missing <- function(reason_code, reason, details = list()) {
  list(ok = FALSE, reason_code = reason_code, reason = reason, value = NULL,
       details = details)
}

qp_is_executable_file <- function(path) {
  length(path) == 1L && !is.na(path) && nzchar(path) && file.exists(path) &&
    !dir.exists(path) && isTRUE(unname(file.access(path, 1L) == 0L))
}

qp_frame_value <- function(fun, name) {
  for (i in rev(seq_len(sys.nframe()))) {
    if (identical(sys.function(i), fun)) {
      value <- get0(name, envir = sys.frame(i), inherits = FALSE)
      if (is.character(value) && length(value) == 1L) {
        return(value)
      }
    }
  }
  NA_character_
}

qp_current_test_label <- function() {
  qp_frame_value(testthat::test_that, "desc")
}

qp_current_test_file <- function() {
  path <- qp_frame_value(testthat::source_file, "path")
  if (is.na(path)) path else basename(path)
}

qp_record_skip <- function(helper, reason_code, reason, details = list(),
                           log = Sys.getenv("QUPFLOWR_SKIP_LOG")) {
  if (length(details) == 0L) {
    details <- structure(list(), names = character())
  }
  record <- list(
    record_type = "test_skip",
    time = format(Sys.time(), "%Y-%m-%dT%H:%M:%SZ", tz = "UTC"),
    helper = helper,
    reason_code = reason_code,
    reason = reason,
    test = qp_current_test_label(),
    file = qp_current_test_file(),
    details = details
  )
  if (nzchar(log)) {
    line <- jsonlite::toJSON(record, auto_unbox = TRUE, null = "null",
                             na = "null", digits = NA)
    cat(line, "\n", sep = "", file = log, append = TRUE)
  }
  invisible(record)
}

qp_skip_unless <- function(helper, detect) {
  if (identical(Sys.getenv("QUPFLOWR_TEST_PROFILE"), "core")) {
    result <- qp_prereq_missing(
      "forced_by_profile",
      "QUPFLOWR_TEST_PROFILE=core skips integration prerequisites"
    )
  } else {
    result <- detect()
  }
  if (!isTRUE(result$ok)) {
    qp_record_skip(helper, result$reason_code, result$reason, result$details)
    testthat::skip(sprintf("%s [%s]", result$reason, result$reason_code))
  }
  invisible(result$value)
}

# ---- Java -----------------------------------------------------------------

qp_java_major <- function(version) {
  without_legacy_prefix <- sub("^1\\.", "", version)
  digits <- regmatches(without_legacy_prefix,
                       regexpr("^[0-9]+", without_legacy_prefix))
  if (length(digits) == 0L) NA_integer_ else as.integer(digits)
}

qp_java_release_version <- function(java_home) {
  release <- file.path(java_home, "release")
  if (!file.exists(release)) {
    return(NA_character_)
  }
  lines <- readLines(release, warn = FALSE)
  hit <- grep("^JAVA_VERSION=\"[^\"]+\"", lines, value = TRUE)
  if (length(hit) == 0L) {
    return(NA_character_)
  }
  sub("^JAVA_VERSION=\"([^\"]+)\".*$", "\\1", hit[[1L]])
}

qp_java_process_version <- function(java) {
  if (!requireNamespace("processx", quietly = TRUE)) {
    return(NA_character_)
  }
  scratch <- tempfile("qf-java-probe-")
  dir.create(scratch)
  on.exit(unlink(scratch, recursive = TRUE), add = TRUE)
  args <- c("-XX:-UsePerfData", paste0("-Djava.io.tmpdir=", scratch),
            paste0("-Djava.util.prefs.userRoot=", scratch),
            paste0("-Duser.home=", scratch), "-version")
  env <- c(PATH = Sys.getenv("PATH"), HOME = scratch, TMPDIR = scratch,
           LANG = "C")
  res <- tryCatch(
    processx::run(java, args, env = env, error_on_status = FALSE,
                  timeout = 30),
    error = function(e) NULL
  )
  if (is.null(res) || !identical(res$status, 0L)) {
    return(NA_character_)
  }
  output <- paste(res$stderr, res$stdout)
  hit <- regmatches(output, regexpr("version \"[^\"]+\"", output))
  if (length(hit) == 0L) {
    return(NA_character_)
  }
  sub("^version \"(.*)\"$", "\\1", hit)
}

qp_detect_java <- function(java = Sys.getenv("QUPFLOWR_JAVA"),
                           java_home = Sys.getenv("JAVA_HOME"),
                           min_major = 25L) {
  if (!nzchar(java) && nzchar(java_home)) {
    exe <- if (.Platform$OS.type == "windows") "java.exe" else "java"
    java <- file.path(java_home, "bin", exe)
  }
  if (!nzchar(java)) {
    return(qp_prereq_missing("java_not_configured",
                             "Neither QUPFLOWR_JAVA nor JAVA_HOME is set"))
  }
  if (!qp_is_executable_file(java)) {
    return(qp_prereq_missing("java_not_found",
                             paste("No executable Java runtime at", java),
                             list(java = java)))
  }
  java <- normalizePath(java)
  version <- qp_java_release_version(dirname(dirname(java)))
  version_source <- "release_file"
  if (is.na(version)) {
    version <- qp_java_process_version(java)
    version_source <- "version_output"
  }
  if (is.na(version)) {
    return(qp_prereq_missing("java_version_unknown",
                             paste("Cannot determine the version of", java),
                             list(java = java)))
  }
  major <- qp_java_major(version)
  details <- list(java = java, version = version, major = major,
                  version_source = version_source)
  if (is.na(major) || major < min_major) {
    return(qp_prereq_missing(
      "java_version_mismatch",
      sprintf("Java %d or newer required, found %s", min_major, version),
      details
    ))
  }
  qp_prereq_ok(java, details)
}

skip_if_no_java <- function(min_major = 25L) {
  qp_skip_unless("skip_if_no_java", function() {
    qp_detect_java(min_major = min_major)
  })
}

# ---- QuPath ---------------------------------------------------------------

qp_detect_qupath <- function(home = Sys.getenv("QUPFLOWR_QUPATH_HOME"),
                             version = "0.7.0") {
  if (!nzchar(home)) {
    return(qp_prereq_missing("qupath_not_configured",
                             "QUPFLOWR_QUPATH_HOME is not set"))
  }
  # Application jar directories of the Linux, macOS and Windows packages.
  candidates <- file.path(home, c("lib/app", "Contents/app", "app"))
  app <- candidates[dir.exists(candidates)]
  if (length(app) == 0L) {
    return(qp_prereq_missing("qupath_not_found",
                             paste("No QuPath application directory in",
                                   home),
                             list(home = home)))
  }
  app <- app[[1L]]
  jars <- list.files(app, pattern = "^qupath-core-[0-9][^/]*\\.jar$")
  found <- sub("^qupath-core-(.*)\\.jar$", "\\1", jars)
  if (length(found) == 0L) {
    return(qp_prereq_missing("qupath_not_found",
                             paste("No qupath-core jar in", app),
                             list(home = home)))
  }
  details <- list(home = normalizePath(home), version = version,
                  found_versions = found)
  if (!version %in% found) {
    return(qp_prereq_missing(
      "qupath_version_mismatch",
      sprintf("QuPath %s required, found %s", version,
              paste(found, collapse = ", ")),
      details
    ))
  }
  qp_prereq_ok(normalizePath(home), details)
}

skip_if_no_qupath <- function(version = "0.7.0") {
  qp_skip_unless("skip_if_no_qupath", function() {
    qp_detect_qupath(version = version)
  })
}

# ---- Bridge artifact ------------------------------------------------------

# SHA-256 of a file with tools::sha256sum() (R >= 4.5) or the suggested
# digest package; NULL when neither is available.
qp_sha256_file <- function(path,
                           tools_ns = asNamespace("tools"),
                           has_digest = requireNamespace("digest",
                                                         quietly = TRUE)) {
  sha256sum <- get0("sha256sum", envir = tools_ns, inherits = FALSE)
  if (is.function(sha256sum)) {
    return(unname(sha256sum(path)))
  }
  if (isTRUE(has_digest)) {
    return(digest::digest(file = path, algo = "sha256"))
  }
  NULL
}

qp_detect_bridge_artifact <- function(path = NULL, sha256 = qp_sha256_file) {
  if (is.null(path)) {
    path <- Sys.getenv("QUPFLOWR_BRIDGE_ARTIFACT")
  }
  if (!nzchar(path)) {
    return(qp_prereq_missing("bridge_artifact_not_configured",
                             "QUPFLOWR_BRIDGE_ARTIFACT is not set"))
  }
  if (!file.exists(path) || dir.exists(path)) {
    return(qp_prereq_missing("bridge_artifact_not_found",
                             paste("No bridge artifact file at", path),
                             list(path = path)))
  }
  listing <- tryCatch(utils::unzip(path, list = TRUE),
                      error = function(e) NULL, warning = function(w) NULL)
  if (is.null(listing)) {
    return(qp_prereq_missing("bridge_artifact_invalid",
                             paste(path, "is not a readable zip archive"),
                             list(path = path)))
  }
  if (!"manifest.json" %in% listing$Name) {
    return(qp_prereq_missing("bridge_artifact_invalid",
                             paste(path, "has no manifest.json at its root"),
                             list(path = path)))
  }
  sidecar <- paste0(path, ".sha256")
  if (file.exists(sidecar)) {
    first <- trimws(readLines(sidecar, n = 1L, warn = FALSE))
    expected <- tolower(sub("[[:space:]].*$", "", first))
    actual <- sha256(path)
    if (is.null(actual)) {
      return(qp_prereq_missing(
        "bridge_artifact_checksum_unverifiable",
        paste("Cannot verify", sidecar, "without tools::sha256sum()",
              "(R >= 4.5) or the digest package"),
        list(path = path)
      ))
    }
    if (length(expected) == 0L || !identical(expected, actual)) {
      return(qp_prereq_missing(
        "bridge_artifact_checksum_mismatch",
        paste("sha256 of", path, "does not match", sidecar),
        list(path = path, actual_sha256 = actual)
      ))
    }
  }
  qp_prereq_ok(normalizePath(path),
               list(path = normalizePath(path),
                    checksum_verified = file.exists(sidecar)))
}

skip_if_no_bridge_artifact <- function() {
  qp_skip_unless("skip_if_no_bridge_artifact", qp_detect_bridge_artifact)
}

# ---- Display --------------------------------------------------------------

qp_detect_display <- function(display = Sys.getenv("QUPFLOWR_TEST_DISPLAY"),
                              desktop = Sys.getenv("DISPLAY"),
                              socket_dir = "/tmp/.X11-unix") {
  if (!identical(.Platform$OS.type, "unix")) {
    return(qp_prereq_missing("display_unsupported_platform",
                             "Isolated X displays are only used on Unix"))
  }
  if (!nzchar(display)) {
    return(qp_prereq_missing(
      "display_not_configured",
      "QUPFLOWR_TEST_DISPLAY is not set; the desktop display is never used"
    ))
  }
  if (!grepl("^:[0-9]+(\\.[0-9]+)?$", display)) {
    return(qp_prereq_missing("display_invalid",
                             paste("Not a local X display name:", display),
                             list(display = display)))
  }
  number <- as.integer(sub("^:([0-9]+).*$", "\\1", display))
  desktop_number <- NA_integer_
  if (grepl("^[^:]*:[0-9]+", desktop)) {
    desktop_number <- as.integer(sub("^[^:]*:([0-9]+).*$", "\\1", desktop))
  }
  if (number == 0L || identical(number, desktop_number)) {
    return(qp_prereq_missing(
      "display_refused",
      paste("Display", display, "may belong to the desktop session"),
      list(display = display)
    ))
  }
  socket <- file.path(socket_dir, paste0("X", number))
  if (!file.exists(socket)) {
    return(qp_prereq_missing("display_not_running",
                             paste("No X server socket at", socket),
                             list(display = display, socket = socket)))
  }
  qp_prereq_ok(display, list(display = display, socket = socket))
}

skip_if_no_display <- function() {
  qp_skip_unless("skip_if_no_display", qp_detect_display)
}

# ---- Browser --------------------------------------------------------------

qp_detect_browser <- function(chrome = Sys.getenv("CHROMOTE_CHROME"),
                              packages = "chromote") {
  installed <- vapply(packages, function(p) {
    nzchar(system.file(package = p))
  }, logical(1))
  if (!all(installed)) {
    return(qp_prereq_missing(
      "browser_package_missing",
      paste("Not installed:", paste(packages[!installed], collapse = ", ")),
      list(packages = packages[!installed])
    ))
  }
  if (!nzchar(chrome)) {
    return(qp_prereq_missing(
      "browser_not_configured",
      "CHROMOTE_CHROME is not set; system browsers are never used"
    ))
  }
  if (!qp_is_executable_file(chrome)) {
    return(qp_prereq_missing("browser_not_found",
                             paste("No executable browser at", chrome),
                             list(chrome = chrome)))
  }
  qp_prereq_ok(normalizePath(chrome), list(chrome = normalizePath(chrome)))
}

skip_if_no_browser <- function() {
  qp_skip_unless("skip_if_no_browser", qp_detect_browser)
}

# ---- Python profile -------------------------------------------------------

qp_detect_python_profile <- function(profile = NULL) {
  if (is.null(profile)) {
    profile <- Sys.getenv("QUPFLOWR_PYTHON_PROFILE")
  }
  if (!nzchar(profile)) {
    return(qp_prereq_missing("python_profile_not_configured",
                             "QUPFLOWR_PYTHON_PROFILE is not set"))
  }
  if (!dir.exists(profile)) {
    return(qp_prereq_missing("python_profile_not_found",
                             paste("No Python profile directory at", profile),
                             list(profile = profile)))
  }
  required <- c("pyproject.toml", "uv.lock")
  absent <- required[!file.exists(file.path(profile, required))]
  if (length(absent) > 0L) {
    return(qp_prereq_missing(
      "python_profile_incomplete",
      paste("Python profile lacks", paste(absent, collapse = ", ")),
      list(profile = profile, missing = absent)
    ))
  }
  interpreters <- file.path(profile, ".venv",
                            c("bin/python", "Scripts/python.exe"))
  usable <- interpreters[vapply(interpreters, qp_is_executable_file,
                                logical(1))]
  if (length(usable) == 0L) {
    return(qp_prereq_missing(
      "python_profile_incomplete",
      "Python profile has no interpreter in .venv",
      list(profile = profile, missing = ".venv interpreter")
    ))
  }
  qp_prereq_ok(normalizePath(profile),
               list(profile = normalizePath(profile), python = usable[[1L]]))
}

skip_if_no_python_profile <- function() {
  qp_skip_unless("skip_if_no_python_profile", qp_detect_python_profile)
}
