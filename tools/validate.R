#!/usr/bin/env Rscript
# Local validation entry point for qupflowR (lanes L01 to L12 of docs/10).
#
# Usage (from any directory):
#   Rscript tools/validate.R --profile PROFILE [--task-id ID] [--gate-id ID]
#     [--display :NN] [--bundle ZIP] [--online] [--reports-dir DIR]
#     [--artifacts-dir DIR] [--work-dir DIR] [--tmp-prefix PREFIX]
#     [--java-home DIR] [--qupath-home DIR] [--gradle-user-home DIR]
#     [--timeout-scale X] [--keep-runtime] [--quiet] [--list] [--help]
#
# PROFILE is one lane (L01 ... L12), a comma-separated list of lanes, or an
# acceptance profile:
#   t01        L01, L02, L03, L04, L11, L12
#   all-local  every lane L01 ... L12; L10 needs a real second computer, so
#              this profile cannot pass from a local run
# Every lane of the selected profile is required.
#
# Lanes implemented now:
#   L01 core      testthat from the source tree with QUPFLOWR_TEST_PROFILE=core
#                 (integration prerequisites skip with recorded reasons; any
#                 skip outside the lane's reviewed list fails), network
#                 proxies pointed at a closed local port
#   L02 contract  R protocol/schema tests, Gradle test of every bridge module
#                 (fresh JUnit results) and tools/bridge/scripts/
#                 contract-parity.sh
#   L03 worker    tools/bridge/scripts/smoke-worker.sh with the bundle from
#                 <artifacts-dir>/bridge (built by tools/build.R --target
#                 bridge); fixture set 1 generated with QuPath
#                 (tools/fixtures.R) and its oracle tests
#                 (test-fixtures-expected.R) with zero failures and skips
#   L04 gui       tools/bridge/scripts/smoke-extension.sh on a free private
#                 Xvfb display (:94-:99, then :90-:93, or --display)
#   L11 package   tools/build.R --target package --roxygen check into the lane
#                 directory, audit of the actual tarball listing, install into
#                 an empty library, load probes with the full and with a
#                 dependency-only library, R CMD check --as-cran --no-manual
#                 (_R_CHECK_FORCE_SUGGESTS_=false) with the running R and with
#                 every additional R in .tools/R-<x.y.z> (library
#                 .tools/R-lib-<x.y>; or QUPFLOWR_EXTRA_R_HOMES); the declared
#                 R minimum must lie in the series of the oldest passing check
#                 and admit the oldrel-1 job of the shared CI workflow
#   L12 quality   tools/quality.R --mode lint, coverage and api, including
#                 the checks against hidden findings (.lintr divergence,
#                 other lintr configurations, lint and coverage markers,
#                 coverage baseline validity,
#                 API inventory history)
# L05 to L10 report "skip" with the task that introduces them.
#
# Reports: <reports-dir>/<lane>.json (evidence schema of docs/10) with logs
# in <reports-dir>/<lane>/, and <reports-dir>/profile-<name>.json. The
# default reports directory is <repo>/work/reports/lanes. The source
# inventory (git-visible files including uncommitted ones) is recorded before
# and after the run; a change during the run fails the profile.
#
# Exit status: 0 every required lane passed, 1 a required lane failed or the
# sources changed during the run, 2 usage error or unusable environment,
# 3 no lane failed but a required lane was skipped (not qualified).

validate_load_library <- function(lib_dir, file) {
  env <- new.env(parent = globalenv())
  sys.source(file.path(lib_dir, file), envir = env, keep.source = FALSE)
  env
}

validate_script_path <- function() {
  file_arg <- grep("^--file=", commandArgs(trailingOnly = FALSE), value = TRUE)
  if (length(file_arg) != 1L) {
    cli::cli_abort("run this script with Rscript tools/validate.R",
                   class = c("qp_tool_usage_error", "qp_tool_error",
                             "qp_error"))
  }
  normalizePath(sub("^--file=", "", file_arg), winslash = "/", mustWork = TRUE)
}

validate_script <- validate_script_path()
validate_lib_dir <- file.path(dirname(validate_script), "lib")
tl <- validate_load_library(validate_lib_dir, "common.R")
lanes_lib <- validate_load_library(validate_lib_dir, "lanes.R")

validate_resolve_profile <- function(profile) {
  catalogue <- lanes_lib$lane_catalogue()
  table <- lanes_lib$profile_table()
  if (profile %in% names(table)) {
    return(list(name = profile, lanes = table[[profile]]))
  }
  lanes <- trimws(strsplit(profile, ",", fixed = TRUE)[[1L]])
  unknown <- setdiff(lanes, names(catalogue))
  if (length(lanes) == 0L || length(unknown) > 0L || anyDuplicated(lanes)) {
    tl$tool_abort("usage", paste(
      "--profile must be one of", toString(names(table)), "or lanes from",
      toString(names(catalogue)), "(comma-separated, no duplicates)"
    ))
  }
  name <- if (length(lanes) == 1L) lanes else paste(lanes, collapse = "-")
  list(name = name, lanes = lanes)
}

validate_list <- function() {
  catalogue <- lanes_lib$lane_catalogue()
  for (id in names(catalogue)) {
    entry <- catalogue[[id]]
    state <- if (is.null(entry$runner)) {
      paste("skip: not implemented before", entry$first_task)
    } else {
      "implemented"
    }
    cat(sprintf("%s  %-15s %s\n", id, entry$name, state))
  }
  table <- lanes_lib$profile_table()
  for (name in names(table)) {
    cat(sprintf("profile %-10s %s\n", name, toString(table[[name]])))
  }
  0L
}

validate_context <- function(opts) {
  root <- normalizePath(file.path(dirname(validate_script), ".."),
                        winslash = "/")
  cwd <- getwd()
  path_opt <- function(name, variable, default) {
    value <- tl$opt_or(opts, name, NA_character_)
    if (is.na(value) && !is.null(variable)) {
      value <- tl$env_or(variable, NA_character_)
    }
    if (is.na(value)) default else tl$absolute_path(value, cwd)
  }
  scale <- suppressWarnings(as.numeric(tl$opt_or(opts, "timeout_scale", "1")))
  if (is.na(scale) || scale <= 0) {
    tl$tool_abort("usage", "--timeout-scale must be a positive number")
  }
  list(
    tl = tl, root = root, opts = opts, label = "validate",
    quiet = isTRUE(opts$quiet),
    task_id = tl$opt_or(opts, "task_id", tl$env_or("QUPFLOWR_TASK_ID", "T01")),
    gate_id = tl$opt_or(opts, "gate_id", NULL),
    reports_dir = path_opt("reports_dir", NULL,
                           file.path(root, "work", "reports", "lanes")),
    artifacts_dir = path_opt("artifacts_dir", NULL,
                             file.path(root, "artifacts")),
    work_dir = path_opt("work_dir", NULL,
                        file.path(root, "work", "validate")),
    java_home = path_opt("java_home", "JAVA_HOME",
                         file.path(root, ".tools", "jdk-25")),
    qupath_home = path_opt("qupath_home", "QUPFLOWR_QUPATH_HOME",
                           "/opt/QuPath-v0.7.0"),
    gradle_home = path_opt("gradle_user_home", "GRADLE_USER_HOME",
                           file.path(root, ".tools", "gradle-home")),
    tmp_prefix = tl$check_tmp_prefix(tl$opt_or(opts, "tmp_prefix",
                                               "qf-validate-"))
  )
}

validate_run_lane <- function(base, lane_id, meta) {
  entry <- lanes_lib$lane_catalogue()[[lane_id]]
  lane_dir <- file.path(base$reports_dir, lane_id)
  tl$reset_dir(lane_dir, base$reports_dir)
  ctx <- c(base, list(lane_id = lane_id, lane = entry, lane_dir = lane_dir,
                      cmds = tl$new_command_log(), checks = tl$new_checks()))
  runner <- if (is.null(entry$runner)) {
    lanes_lib$lane_not_implemented
  } else {
    lanes_lib[[entry$runner]]
  }
  tl$say(ctx$label, "lane ", lane_id, " (", entry$name, ")", quiet = FALSE)
  started <- Sys.time()
  outcome <- tryCatch(
    runner(ctx),
    qp_tool_usage_error = function(cnd) {
      cnd
    },
    qp_tool_error = function(cnd) {
      list(failure_reasons = list(tl$condition_text(cnd)), aborted = TRUE)
    },
    error = function(cnd) {
      list(failure_reasons = list(paste("internal error:",
                                        tl$condition_text(cnd))),
           aborted = TRUE)
    }
  )
  if (inherits(outcome, "qp_tool_usage_error")) {
    tl$tool_abort("usage", tl$condition_text(outcome))
  }
  failed <- tl$failed_checks(ctx$checks)
  result <- if (identical(outcome$result, "skip")) {
    "skip"
  } else if (length(failed) == 0L && length(ctx$checks$items) > 0L &&
               !isTRUE(outcome$aborted)) {
    "pass"
  } else {
    "fail"
  }
  reasons <- c(
    outcome$failure_reasons,
    lapply(failed, function(f) {
      paste0(f$id, ": expected ", f$expected, ", found ", f$actual)
    }),
    if (identical(result, "fail") && length(ctx$checks$items) == 0L) {
      list("the lane recorded no checks, so it cannot pass")
    }
  )
  log_files <- list.files(file.path(lane_dir, "logs"), full.names = TRUE)
  report <- tl$evidence_report(
    c(meta, list(report_id = paste0("lane-", lane_id))),
    list(
      quality_ids = as.list(entry$quality_ids), lane_id = lane_id,
      lane_name = entry$name, started_at = tl$utc_now(started),
      duration_s = round(as.numeric(difftime(Sys.time(), started,
                                             units = "secs")), 1),
      artifact_digests = outcome$artifact_digests,
      runtime_profile = tl$runtime_profile(
        packages = c("testthat", "processx", "covr", "lintr", "roxygen2"),
        extra = if (is.null(outcome$runtime)) list() else outcome$runtime
      ),
      fixture_digests = outcome$fixture_digests,
      commands = ctx$cmds$commands, exit_codes = tl$exit_codes_of(ctx$cmds),
      oracle = outcome$oracle, tolerances = outcome$tolerances,
      checks = ctx$checks$items, result = result,
      skip_reason = outcome$skip_reason, failure_reasons = reasons,
      logs = c(as.list(tl$relative_to(log_files, base$root)), outcome$logs),
      limitations = outcome$limitations,
      details = if (is.null(outcome$details)) tl$empty_object() else
        outcome$details
    )
  )
  path <- file.path(base$reports_dir, paste0(lane_id, ".json"))
  tl$write_json_file(report, path)
  for (reason in utils::head(reasons, 10L)) {
    tl$say(ctx$label, lane_id, ": ", reason, quiet = FALSE)
  }
  tl$say(ctx$label, "lane ", lane_id, ": ", result,
         if (!is.null(outcome$skip_reason)) {
           paste0(" (", outcome$skip_reason, ")")
         }, quiet = FALSE)
  list(lane_id = lane_id, name = entry$name, result = result,
       skip_reason = outcome$skip_reason,
       failure_reasons = utils::head(reasons, 20L),
       report = tl$relative_to(path, base$root),
       report_sha256 = tl$sha256_files(path),
       duration_s = report$duration_s)
}

validate_main <- function(args) {
  opts <- tl$parse_args(
    args,
    flags = c("--online", "--keep-runtime", "--quiet", "--list", "--help"),
    values = c("--profile", "--task-id", "--gate-id", "--display", "--bundle",
               "--reports-dir", "--artifacts-dir", "--work-dir",
               "--tmp-prefix", "--java-home", "--qupath-home",
               "--gradle-user-home", "--timeout-scale")
  )
  if (isTRUE(opts$help)) {
    writeLines(tl$usage_text(validate_script))
    return(0L)
  }
  if (isTRUE(opts$list)) {
    return(validate_list())
  }
  if (is.null(opts$profile)) {
    tl$tool_abort("usage", "--profile is required (see --help or --list)")
  }
  profile <- validate_resolve_profile(opts$profile)
  tl$require_packages(c("cli", "jsonlite", "processx", "ps"))
  base <- validate_context(opts)
  tl$ensure_dir(base$reports_dir)
  tl$ensure_dir(base$work_dir)
  base$rt <- tl$new_runtime(base$work_dir, "validate", base$tmp_prefix)
  on.exit(tl$cleanup_runtime(base$rt, keep = isTRUE(opts$keep_runtime)),
          add = TRUE)
  inventory <- tl$source_inventory(base$root, base$rt)
  listing <- file.path(base$reports_dir, paste0("source-inventory-",
                                                profile$name, ".tsv"))
  tl$write_inventory(inventory, listing)
  task <- tl$task_metadata(base$root, base$task_id)
  meta <- list(
    task_id = base$task_id, gate_id = base$gate_id,
    feature_ids = task$feature_ids, profile = profile$name, root = base$root,
    source_revision = tl$git_revision(base$root, base$rt),
    source_inventory = tl$inventory_summary(inventory, listing, base$root)
  )
  started <- Sys.time()
  outcomes <- lapply(profile$lanes, function(id) {
    validate_run_lane(base, id, meta)
  })
  after <- tl$source_inventory(base$root, base$rt)
  changed <- !identical(after$sha256, inventory$sha256)
  results <- vapply(outcomes, function(o) o$result, "")
  names(results) <- profile$lanes
  status <- if (any(results == "fail") || changed) {
    1L
  } else if (any(results == "skip")) {
    3L
  } else {
    0L
  }
  qualification <- c("pass", "fail", "", "not_qualified")[status + 1L]
  reasons <- c(
    lapply(Filter(function(o) o$result != "pass", outcomes), function(o) {
      paste0(o$lane_id, " ", o$result, if (!is.null(o$skip_reason)) {
        paste0(": ", o$skip_reason)
      })
    }),
    if (changed) {
      list(paste("the source inventory changed during the run from",
                 inventory$sha256, "to", after$sha256))
    }
  )
  summary_path <- file.path(base$reports_dir,
                            paste0("profile-", profile$name, ".json"))
  tl$write_json_file(tl$evidence_report(
    c(meta, list(report_id = paste0("profile-", profile$name))),
    list(
      started_at = tl$utc_now(started),
      duration_s = round(as.numeric(difftime(Sys.time(), started,
                                             units = "secs")), 1),
      quality_ids = as.list(task$quality_ids),
      runtime_profile = tl$runtime_profile(),
      exit_codes = list(validate = status),
      oracle = paste(
        "A profile passes only when every required lane report says pass;",
        "a skipped required lane leaves it not qualified, and a source",
        "inventory change during the run fails it."
      ),
      tolerances = "none",
      result = if (status == 0L) "pass" else "fail",
      failure_reasons = reasons,
      logs = lapply(outcomes, function(o) o$report),
      limitations = list(paste(
        "Lane reports describe the source inventory recorded at the start;",
        "the digest after the run is in details."
      )),
      details = list(
        qualification = qualification,
        required_lanes = as.list(profile$lanes),
        lanes = outcomes,
        source_inventory_after = tl$inventory_summary(after),
        source_changed_during_run = changed
      )
    )
  ), summary_path)
  tl$say(base$label, paste(sprintf("%s=%s", names(results), results),
                           collapse = " "), quiet = FALSE)
  tl$say(base$label, "profile ", profile$name, ": ", qualification,
         " (exit ", status, "; report: ", summary_path, ")", quiet = FALSE)
  status
}

if (!interactive()) {
  tl$run_main("validate", validate_main)
}
