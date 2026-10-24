#!/usr/bin/env Rscript
# Local build entry point for qupflowR (R package, Java bridge, fixtures).
#
# Usage (from any directory):
#   Rscript tools/build.R --target preflight|bridge|package|fixtures|all
#     [--force] [--online] [--keep-going] [--roxygen update|check|skip]
#     [--artifacts-dir DIR] [--report-dir DIR] [--work-dir DIR]
#     [--tmp-prefix PREFIX] [--java-home DIR] [--qupath-home DIR]
#     [--gradle-user-home DIR] [--task-id ID] [--keep-runtime] [--quiet]
#     [--help]
#
# Targets:
#   preflight  inventory of R, packages, JDK, QuPath (including the official
#              launcher's --version status, recorded but never fatal), Gradle
#              wrapper, Xvfb, inotify limits and tools; writes
#              <report-dir>/preflight/preflight.json
#   bridge     Gradle wrapper: test, bridgeBundle, verifyJarContents; checks
#              the bundle manifest and copies the bundle and its sha256
#              sidecar to <artifacts-dir>/bridge/
#   package    roxygen2 (update: regenerate in place; check: fail if NAMESPACE
#              or man/ are stale; skip), R CMD build, then
#              <artifacts-dir>/r/qupflowR_<version>.tar.gz with .sha256,
#              .files.txt (tarball listing) and .build.json (inputs digest)
#   fixtures   Rscript tools/fixtures.R, an extra --verify-only pass and the
#              oracle tests tests/testthat/test-fixtures-expected.R against
#              the generated tree (zero failures and zero skips)
#   all        preflight, bridge, package, fixtures in this order; stops at
#              the first failure unless --keep-going
#
# Options:
#   --force             rebuild even when inputs are unchanged (Gradle
#                       --rerun-tasks, new tarball, regenerated fixtures)
#   --online            let Gradle and the fixture build use the network;
#                       otherwise Gradle runs --offline when its cache is
#                       complete
#   --artifacts-dir     default <repo>/artifacts
#   --report-dir        default <repo>/work/reports (build reports go to
#                       <report-dir>/build/<target>.json)
#   --work-dir          runtime directories (private HOME, XDG, Java prefs);
#                       default <repo>/work/build, removed unless
#                       --keep-runtime
#   --tmp-prefix        prefix of the private TMPDIR below /tmp (default
#                       qf-build-)
#   --java-home         default JAVA_HOME, then <repo>/.tools/jdk-25
#   --qupath-home       default QUPFLOWR_QUPATH_HOME, then /opt/QuPath-v0.7.0
#   --gradle-user-home  default GRADLE_USER_HOME, then <repo>/.tools/gradle-home
#   --task-id           task recorded in reports (default QUPFLOWR_TASK_ID,
#                       then T01)
#
# Every child process runs from an argument vector with an explicit
# environment: private HOME, XDG directories and TMPDIR, no DISPLAY or
# WAYLAND_DISPLAY, and JVM user.home, java.util.prefs.userRoot and
# java.io.tmpdir inside the runtime directory.
#
# Exit status: 0 every requested target passed, 1 a target failed, 2 usage
# error or missing prerequisite.

build_targets <- c("preflight", "bridge", "package", "fixtures")

build_load_library <- function(lib_dir, file) {
  env <- new.env(parent = globalenv())
  sys.source(file.path(lib_dir, file), envir = env, keep.source = FALSE)
  env
}

build_script_path <- function() {
  file_arg <- grep("^--file=", commandArgs(trailingOnly = FALSE), value = TRUE)
  if (length(file_arg) != 1L) {
    cli::cli_abort("run this script with Rscript tools/build.R",
                   class = c("qp_tool_usage_error", "qp_tool_error",
                             "qp_error"))
  }
  normalizePath(sub("^--file=", "", file_arg), winslash = "/", mustWork = TRUE)
}

build_script <- build_script_path()
build_lib_dir <- file.path(dirname(build_script), "lib")
tl <- build_load_library(build_lib_dir, "common.R")
preflight_lib <- build_load_library(build_lib_dir, "preflight.R")
targets_lib <- build_load_library(build_lib_dir, "build-targets.R")

build_context <- function(opts) {
  root <- normalizePath(file.path(dirname(build_script), ".."),
                        winslash = "/")
  cwd <- getwd()
  path_opt <- function(name, variable, default) {
    value <- tl$opt_or(opts, name, NA_character_)
    if (is.na(value) && !is.null(variable)) {
      value <- tl$env_or(variable, NA_character_)
    }
    if (is.na(value)) default else tl$absolute_path(value, cwd)
  }
  label <- "build"
  list(
    tl = tl, root = root, opts = opts, label = label,
    quiet = isTRUE(opts$quiet),
    task_id = tl$opt_or(opts, "task_id", tl$env_or("QUPFLOWR_TASK_ID", "T01")),
    artifacts_dir = path_opt("artifacts_dir", NULL,
                             file.path(root, "artifacts")),
    report_dir = path_opt("report_dir", NULL, file.path(root, "work",
                                                        "reports")),
    work_dir = path_opt("work_dir", NULL, file.path(root, "work", "build")),
    java_home = path_opt("java_home", "JAVA_HOME",
                         file.path(root, ".tools", "jdk-25")),
    qupath_home = path_opt("qupath_home", "QUPFLOWR_QUPATH_HOME",
                           "/opt/QuPath-v0.7.0"),
    gradle_home = path_opt("gradle_user_home", "GRADLE_USER_HOME",
                           file.path(root, ".tools", "gradle-home")),
    xvfb = tl$env_or("QUPFLOWR_XVFB",
                     file.path(root, ".tools", "xvfb", "usr", "bin", "Xvfb")),
    xvfb_libs = tl$env_or("QUPFLOWR_XVFB_LIBS",
                          file.path(root, ".tools", "xvfb", "usr", "lib",
                                    "x86_64-linux-gnu"))
  )
}

build_report_path <- function(ctx, target) {
  if (identical(target, "preflight")) {
    file.path(ctx$report_dir, "preflight", "preflight.json")
  } else {
    file.path(ctx$report_dir, "build", paste0(target, ".json"))
  }
}

build_run_target <- function(ctx, target, meta) {
  runner <- switch(
    target,
    preflight = preflight_lib$run_preflight,
    bridge = targets_lib$target_bridge,
    package = targets_lib$target_package,
    fixtures = targets_lib$target_fixtures
  )
  tl$say(ctx$label, "target ", target, quiet = ctx$quiet)
  outcome <- tryCatch(
    runner(ctx),
    qp_tool_environment_error = function(cnd) {
      list(result = "fail", status = 2L, body = list(
        failure_reasons = list(tl$condition_text(cnd))
      ))
    },
    qp_tool_error = function(cnd) {
      list(result = "fail", status = 1L, body = list(
        failure_reasons = list(tl$condition_text(cnd))
      ))
    }
  )
  status <- if (identical(outcome$result, "pass")) 0L else
    tl$opt_or(outcome, "status", 1L)
  report <- tl$evidence_report(
    c(meta, list(report_id = paste0("build-", target))),
    c(outcome$body, list(result = outcome$result))
  )
  path <- build_report_path(ctx, target)
  tl$write_json_file(report, path)
  for (reason in outcome$body$failure_reasons) {
    tl$say(ctx$label, target, ": ", reason, quiet = FALSE)
  }
  tl$say(ctx$label, "target ", target, ": ", outcome$result, " (report: ",
         path, ")", quiet = FALSE)
  list(target = target, result = outcome$result, exit_code = status,
       report = path, report_sha256 = tl$sha256_files(path))
}

build_main <- function(args) {
  opts <- tl$parse_args(
    args,
    flags = c("--force", "--online", "--keep-going", "--keep-runtime",
              "--quiet", "--help"),
    values = c("--target", "--roxygen", "--artifacts-dir", "--report-dir",
               "--work-dir", "--tmp-prefix", "--java-home", "--qupath-home",
               "--gradle-user-home", "--task-id")
  )
  if (isTRUE(opts$help)) {
    writeLines(tl$usage_text(build_script))
    return(0L)
  }
  target <- tl$opt_or(opts, "target", NA_character_)
  if (!target %in% c(build_targets, "all")) {
    tl$tool_abort("usage", paste("--target must be one of",
                                 toString(c(build_targets, "all"))))
  }
  if (!tl$opt_or(opts, "roxygen", "update") %in%
        c("update", "check", "skip")) {
    tl$tool_abort("usage", "--roxygen must be update, check or skip")
  }
  tl$require_packages(c("cli", "jsonlite", "processx", "ps"))
  ctx <- build_context(opts)
  prefix <- tl$check_tmp_prefix(tl$opt_or(opts, "tmp_prefix", "qf-build-"))
  tl$ensure_dir(ctx$work_dir)
  ctx$rt <- tl$new_runtime(ctx$work_dir, "build", prefix)
  on.exit(tl$cleanup_runtime(ctx$rt, keep = isTRUE(opts$keep_runtime)),
          add = TRUE)
  inventory <- tl$source_inventory(ctx$root, ctx$rt)
  task <- tl$task_metadata(ctx$root, ctx$task_id)
  meta <- list(
    task_id = ctx$task_id, gate_id = NULL, feature_ids = task$feature_ids,
    profile = NULL, root = ctx$root,
    source_revision = tl$git_revision(ctx$root, ctx$rt),
    source_inventory = tl$inventory_summary(inventory)
  )
  selected <- if (identical(target, "all")) build_targets else target
  outcomes <- list()
  for (name in selected) {
    outcome <- build_run_target(ctx, name, meta)
    outcomes[[length(outcomes) + 1L]] <- outcome
    if (outcome$exit_code != 0L && !isTRUE(opts$keep_going)) {
      break
    }
  }
  codes <- vapply(outcomes, function(o) o$exit_code, 0L)
  status <- if (any(codes == 2L)) 2L else if (any(codes != 0L)) 1L else 0L
  if (identical(target, "all")) {
    not_run <- setdiff(selected, vapply(outcomes, function(o) o$target, ""))
    tl$write_json_file(
      tl$evidence_report(
        c(meta, list(report_id = "build-all")),
        list(
          result = if (status == 0L) "pass" else "fail",
          exit_codes = stats::setNames(as.list(codes),
                                       vapply(outcomes, function(o) {
                                         o$target
                                       }, "")),
          oracle = "Aggregate of the per-target build reports.",
          details = list(targets = outcomes, not_run = as.list(not_run)),
          failure_reasons = as.list(c(
            vapply(Filter(function(o) o$exit_code != 0L, outcomes),
                   function(o) paste("target", o$target, "failed"), ""),
            if (length(not_run) > 0L) {
              paste("not run after a failure:", toString(not_run))
            }
          ))
        )
      ),
      build_report_path(ctx, "all")
    )
  }
  tl$say(ctx$label, paste(sprintf("%s=%s", vapply(outcomes, function(o) {
    o$target
  }, ""), vapply(outcomes, function(o) o$result, "")), collapse = " "),
  quiet = FALSE)
  status
}

if (!interactive()) {
  tl$run_main("build", build_main)
}
