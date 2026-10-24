# Preflight inventory of the local build host (tools/build.R --target
# preflight).
#
# Records, without changing anything outside the report directory:
#   * operating system, architecture, CPU count and memory;
#   * R and the versions of the packages the build, the tests and the
#     package's DESCRIPTION name;
#   * the JDK (path, release file, java -version, javac -version);
#   * the QuPath installation (core jar version and digest, bundled runtime)
#     and the exit status of the official launcher's --version preflight,
#     which is information only: it crashes on some hosts (JDK-8380085,
#     decision D021) and never fails the preflight;
#   * the Gradle wrapper pin and whether an offline build is possible;
#   * the project-local Xvfb and its unresolved shared libraries;
#   * inotify limits and the instances used by the current user (QuPath
#     silently skips its extension directory when they are exhausted);
#   * command-line tools used by the build and the smoke scripts.
# No network access is attempted; the report states that none is required.
#
# run_preflight(ctx) returns list(result, body) for the evidence report.
# ctx: tl (common helpers), root, rt, report_dir, java_home, qupath_home,
# gradle_home, xvfb, xvfb_libs, label, quiet.

preflight_r_packages <- function() {
  c("cli", "jsonlite", "processx", "ps", "tibble", "roxygen2", "testthat",
    "withr", "callr", "pkgload", "covr", "lintr", "knitr", "rmarkdown",
    "digest")
}

preflight_optional_r_packages <- function() {
  c("rcmdcheck", "pkgbuild", "renv", "pkgdown", "xml2", "shiny", "bslib",
    "sf", "httr2", "R6", "fs", "zip", "chromote", "shinytest2", "annotatR",
    "themakR")
}

preflight_tools <- function() {
  list(
    bash = "smoke and parity scripts", jq = "smoke scripts",
    curl = "smoke scripts", ss = "smoke-extension.sh",
    unzip = "smoke scripts", sha256sum = "smoke and parity scripts",
    timeout = "smoke-worker.sh", tar = "R CMD build",
    pandoc = "vignette build", git = "source inventory (optional)",
    prlimit = "launcher preflight without core dumps (optional)"
  )
}

preflight_host <- function(tl) {
  meminfo <- if (file.exists("/proc/meminfo")) {
    readLines("/proc/meminfo", warn = FALSE)
  } else {
    character()
  }
  kib <- function(key) {
    line <- grep(paste0("^", key, ":"), meminfo, value = TRUE)
    if (length(line) == 0L) {
      return(NULL)
    }
    as.numeric(gsub("[^0-9]", "", line[[1L]]))
  }
  total <- kib("MemTotal")
  info <- Sys.info()
  list(
    sysname = info[["sysname"]], release = info[["release"]],
    machine = info[["machine"]], os = tl$os_release(),
    logical_cpus = parallel::detectCores(logical = TRUE),
    ram_total_gib = if (is.null(total)) NULL else round(total / 2^20, 1)
  )
}

preflight_description_deps <- function(root) {
  desc <- read.dcf(file.path(root, "DESCRIPTION"),
                   fields = c("Depends", "Imports", "Suggests"))
  parse_field <- function(field) {
    value <- desc[1L, field]
    if (is.na(value)) {
      return(character())
    }
    items <- trimws(strsplit(value, ",")[[1L]])
    names <- trimws(sub("\\(.*$", "", items))
    names[nzchar(names) & names != "R"]
  }
  lapply(c(Depends = "Depends", Imports = "Imports", Suggests = "Suggests"),
         parse_field)
}

preflight_versions <- function(tl, pkgs) {
  versions <- lapply(pkgs, tl$package_version_or_null)
  names(versions) <- pkgs
  tl$as_object(versions)
}

preflight_first_line <- function(log, pattern) {
  lines <- grep(pattern, preflight_read_lines(log), value = TRUE)
  if (length(lines) == 0L) NULL else trimws(lines[[1L]])
}

preflight_read_lines <- function(path) {
  if (file.exists(path)) readLines(path, warn = FALSE) else character()
}

preflight_jdk <- function(ctx, cmds, checks, logs) {
  tl <- ctx$tl
  info <- tl$java_home_info(ctx$java_home)
  env <- tl$child_env(ctx$rt, extra = c(JAVA_HOME = ctx$java_home),
                      path_dirs = file.path(ctx$java_home, "bin"))
  version <- NULL
  javac <- NULL
  if (isTRUE(info$java)) {
    log <- file.path(logs, "java-version.log")
    status <- tl$run_logged(cmds, "java_version",
                            file.path(ctx$java_home, "bin", "java"),
                            "-version", log, env, ctx$rt$base, timeout = 60,
                            label = ctx$label, quiet = ctx$quiet)
    version <- list(exit_code = status,
                    lines = as.list(preflight_read_lines(log)))
  }
  if (isTRUE(info$javac)) {
    log <- file.path(logs, "javac-version.log")
    status <- tl$run_logged(cmds, "javac_version",
                            file.path(ctx$java_home, "bin", "javac"),
                            "-version", log, env, ctx$rt$base, timeout = 60,
                            label = ctx$label, quiet = ctx$quiet)
    javac <- list(exit_code = status,
                  version = preflight_first_line(log, "^javac "))
  }
  tl$add_check(checks, "jdk_java_present", TRUE, info$java)
  tl$add_check(checks, "jdk_javac_present", TRUE, info$javac)
  tl$add_check(checks, "jdk_feature_version", 25L, info$feature_version)
  tl$add_check(checks, "jdk_java_version_runs", 0L,
               if (is.null(version)) NULL else version$exit_code)
  c(info, list(java_version_output = version, javac_output = javac))
}

preflight_qupath <- function(ctx, cmds, checks, logs) {
  tl <- ctx$tl
  info <- tl$qupath_home_info(ctx$qupath_home)
  tl$add_check(checks, "qupath_core_version", "0.7.0",
               info$implementation_version)
  launcher <- list(path = info$launcher, exists = info$launcher_exists,
                   required = FALSE)
  if (isTRUE(info$launcher_exists)) {
    log <- file.path(logs, "qupath-launcher-version.log")
    prlimit <- Sys.which("prlimit")
    command <- if (nzchar(prlimit)) prlimit else info$launcher
    args <- if (nzchar(prlimit)) {
      c("--core=0", "--", info$launcher, "--version")
    } else {
      "--version"
    }
    env <- tl$child_env(ctx$rt, extra = c(
      JAVA_TOOL_OPTIONS = tl$jvm_isolation_options(ctx$rt)
    ))
    status <- tl$run_logged(cmds, "qupath_launcher_version", command, args,
                            log, env, ctx$rt$base, timeout = 60, grace = 5,
                            label = ctx$label, quiet = ctx$quiet)
    output <- preflight_read_lines(log)
    launcher <- c(launcher, list(
      exit_code = status,
      core_dumps_disabled = nzchar(prlimit),
      reports_version = any(grepl("0.7.0", output, fixed = TRUE)),
      interpretation = preflight_launcher_meaning(status),
      output_tail = as.list(utils::tail(output, 10L))
    ))
  }
  c(info, list(launcher_preflight = launcher))
}

preflight_launcher_meaning <- function(status) {
  if (identical(status, 0L)) {
    return("the launcher starts; the worker qupath-script profile may use it")
  }
  if (identical(status, 139L)) {
    return(paste(
      "SIGSEGV: the jpackage launcher crashes on this host (JDK-8380085,",
      "decision D021); start QuPath JVMs with a Java 25 launcher instead"
    ))
  }
  if (identical(status, 124L)) {
    return("timed out after 60 s")
  }
  paste("exit status", status, "(not usable as a worker launch profile)")
}

preflight_gradle <- function(ctx, checks) {
  tl <- ctx$tl
  bridge <- file.path(ctx$root, "tools", "bridge")
  props_file <- file.path(bridge, "gradle", "wrapper",
                          "gradle-wrapper.properties")
  props <- tl$read_key_value_file(props_file)
  wrapper_jar <- file.path(bridge, "gradle", "wrapper", "gradle-wrapper.jar")
  wrapper <- tl$gradle_wrapper(bridge)
  tl$add_check(checks, "gradle_wrapper_present", TRUE,
               file.exists(wrapper) && file.exists(wrapper_jar))
  tl$add_check(checks, "gradle_distribution_checksum_pinned", TRUE,
               nzchar(paste0(props$distributionSha256Sum, "")))
  list(
    wrapper = tl$relative_to(wrapper, ctx$root),
    wrapper_jar_sha256 = if (file.exists(wrapper_jar)) {
      tl$sha256_files(wrapper_jar)
    } else {
      NULL
    },
    distribution_url = gsub("\\\\:", ":", paste0(props$distributionUrl, "")),
    distribution_sha256 = props$distributionSha256Sum,
    gradle_user_home = ctx$gradle_home,
    offline_build_possible = tl$gradle_offline_ok(bridge, ctx$gradle_home),
    bridge_version = tl$gradle_properties(bridge)$version
  )
}

preflight_xvfb <- function(ctx, cmds, checks, logs) {
  tl <- ctx$tl
  exists <- file.exists(ctx$xvfb)
  unresolved <- NULL
  if (exists && nzchar(Sys.which("ldd"))) {
    log <- file.path(logs, "xvfb-ldd.log")
    env <- tl$child_env(ctx$rt, extra = c(LD_LIBRARY_PATH = ctx$xvfb_libs))
    status <- tl$run_logged(cmds, "xvfb_ldd", Sys.which("ldd"), ctx$xvfb,
                            log, env, ctx$rt$base, timeout = 60,
                            label = ctx$label, quiet = ctx$quiet)
    if (identical(status, 0L)) {
      lines <- preflight_read_lines(log)
      unresolved <- sum(grepl("not found", lines, fixed = TRUE))
    }
  }
  tl$add_check(checks, "xvfb_present", TRUE, exists)
  tl$add_check(checks, "xvfb_unresolved_libraries", 0L, unresolved)
  list(path = ctx$xvfb, exists = exists,
       sha256 = if (exists) tl$sha256_files(ctx$xvfb) else NULL,
       library_path = ctx$xvfb_libs, unresolved_libraries = unresolved,
       system_xvfb = if (nzchar(Sys.which("Xvfb"))) Sys.which("Xvfb") else NULL)
}

preflight_tool_inventory <- function(ctx, cmds, logs) {
  tl <- ctx$tl
  tools <- preflight_tools()
  out <- lapply(names(tools), function(tool) {
    path <- unname(Sys.which(tool))
    list(tool = tool, path = if (nzchar(path)) path else NULL,
         used_by = tools[[tool]])
  })
  pandoc <- Sys.which("pandoc")
  if (nzchar(pandoc)) {
    log <- file.path(logs, "pandoc-version.log")
    tl$run_logged(cmds, "pandoc_version", pandoc, "--version", log,
                  tl$child_env(ctx$rt), ctx$rt$base, timeout = 60,
                  label = ctx$label, quiet = ctx$quiet)
    out[[which(names(tools) == "pandoc")]]$version <-
      preflight_first_line(log, "^pandoc")
  }
  out
}

# Additional R installations (tools/lib/common.R extra_r_installations())
# with the packages their dependency library provides for R CMD check.
preflight_extra_r <- function(tl, root) {
  needed <- c("cli", "jsonlite", "tibble", "testthat", "withr", "knitr",
              "rmarkdown")
  lapply(tl$extra_r_installations(root), function(inst) {
    present <- if (dir.exists(inst$library)) {
      needed[dir.exists(file.path(inst$library, needed))]
    } else {
      character()
    }
    list(version = inst$version, home = tl$relative_to(inst$home, root),
         library = tl$relative_to(inst$library, root),
         usable = inst$usable && length(present) == length(needed),
         check_packages_present = as.list(present),
         check_packages_missing = as.list(setdiff(needed, present)))
  })
}

# Whether some usable R can check the declared minimum: an R of the same
# release series that is not older than the minimum (lane L11 applies the
# same rule to the checks that actually ran).
preflight_minimum_checkable <- function(tl, r_minimum, extra_r) {
  if (is.na(r_minimum)) {
    return(FALSE)
  }
  versions <- c(as.character(getRversion()),
                vapply(Filter(function(x) isTRUE(x$usable), extra_r),
                       function(x) x$version, ""))
  any(vapply(versions, function(v) {
    identical(tl$r_series_start(v), tl$r_series_start(r_minimum)) &&
      package_version(v) >= package_version(r_minimum)
  }, logical(1)))
}

run_preflight <- function(ctx) {
  tl <- ctx$tl
  checks <- tl$new_checks()
  cmds <- tl$new_command_log()
  logs <- file.path(ctx$report_dir, "preflight", "logs")
  tl$reset_dir(logs, ctx$report_dir)
  started <- Sys.time()

  r_minimum <- tl$declared_r_minimum(ctx$root)
  r_ok <- !is.na(r_minimum) && getRversion() >= r_minimum
  tl$add_check(checks, "r_version_at_least_declared_minimum",
               paste0(">= ", r_minimum), as.character(getRversion()),
               pass = r_ok)
  extra_r <- preflight_extra_r(tl, ctx$root)
  tl$add_check(checks, "r_installation_for_declared_minimum",
               paste0("an R ", r_minimum, " or newer of the series ",
                      sub("\\.[0-9]+$", "", r_minimum),
                      " (running R or .tools/R-<x.y.z> with R-lib-<x.y>)"),
               paste(c(as.character(getRversion()),
                       vapply(Filter(function(x) x$usable, extra_r),
                              function(x) x$version, "")),
                     collapse = ","),
               pass = preflight_minimum_checkable(tl, r_minimum, extra_r))
  required <- preflight_r_packages()
  versions <- preflight_versions(tl, required)
  for (pkg in required) {
    tl$add_check(checks, paste0("r_package_", pkg), TRUE,
                 !is.null(versions[[pkg]]))
  }
  deps <- preflight_description_deps(ctx$root)
  jdk <- preflight_jdk(ctx, cmds, checks, logs)
  qupath <- preflight_qupath(ctx, cmds, checks, logs)
  gradle <- preflight_gradle(ctx, checks)
  xvfb <- preflight_xvfb(ctx, cmds, checks, logs)
  tools <- preflight_tool_inventory(ctx, cmds, logs)
  for (item in tools) {
    if (!item$tool %in% c("git", "prlimit")) {
      tl$add_check(checks, paste0("tool_", item$tool), TRUE,
                   !is.null(item$path))
    }
  }

  failed <- tl$failed_checks(checks)
  result <- if (length(failed) == 0L) "pass" else "fail"
  body <- list(
    started_at = tl$utc_now(started),
    duration_s = round(as.numeric(difftime(Sys.time(), started,
                                           units = "secs")), 1),
    quality_ids = list("Q16", "Q18", "Q20"),
    runtime_profile = list(
      host = preflight_host(tl),
      r = list(
        version = R.version.string, platform = R.version$platform,
        home = R.home(), library_paths = as.list(.libPaths()),
        build_and_test_packages = versions,
        optional_packages = preflight_versions(
          tl, preflight_optional_r_packages()
        ),
        description = list(
          imports = preflight_versions(tl, deps$Imports),
          suggests = preflight_versions(tl, deps$Suggests)
        ),
        declared_minimum = r_minimum,
        extra_installations = extra_r
      ),
      jdk = jdk, qupath = qupath, gradle = gradle, xvfb = xvfb,
      inotify = tl$inotify_usage(), tools = tools,
      network = list(required = FALSE, probed = FALSE,
                     note = paste("Builds run with --offline when the Gradle",
                                  "cache is complete; nothing was downloaded",
                                  "by the preflight."))
    ),
    commands = cmds$commands,
    exit_codes = tl$exit_codes_of(cmds),
    oracle = paste(
      "Versions come from release files, jar manifests and the tools",
      "themselves; required components must be present with the exact",
      "baseline (R at least the DESCRIPTION minimum and an R of the minimum's",
      "release series to check it with, JDK 25 with javac, QuPath core",
      "0.7.0, pinned Gradle wrapper, project Xvfb without unresolved",
      "libraries). Versions of additional R installations come from their",
      "lib/R/include/Rversion.h."
    ),
    tolerances = "exact version strings; inotify headroom below 8 is flagged",
    checks = checks$items,
    result = result,
    failure_reasons = lapply(failed, function(f) {
      paste0(f$id, ": expected ", f$expected, ", found ", f$actual)
    }),
    logs = as.list(tl$relative_to(list.files(logs, full.names = TRUE),
                                  ctx$root)),
    limitations = list(
      paste("The launcher --version status is information only and never",
            "fails the preflight."),
      paste("inotify usage counts only processes of the current user that",
            "are readable in /proc."),
      "Optional R packages and tools are recorded but not required."
    )
  )
  list(result = result, body = body)
}
