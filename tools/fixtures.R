#!/usr/bin/env Rscript
# Synthetic fixture generator driver (fixture set version 1).
#
# Builds the bridge-fixtures generator with the Gradle wrapper, runs it in an
# isolated Java process and verifies the manifest it writes. Output that was
# written by the same generator jar, QuPath installation and Java version into
# the same directory and that passes verification is reused; anything else is
# regenerated from scratch. Fixtures are never written below inst/.
#
# Usage (from any directory):
#   Rscript tools/fixtures.R [--out DIR] [--force] [--verify-only]
#     [--no-build] [--online] [--qupath-home DIR] [--java-home DIR]
#     [--gradle-user-home DIR] [--keep-runtime] [--help]
#
#   --out DIR               output directory (default work/fixtures/v1 below
#                           the repository root)
#   --force                 regenerate even if the output verifies
#   --verify-only           only verify an existing output against its
#                           manifest (no build, no Java)
#   --no-build              use the jar already built by Gradle
#   --online                let Gradle use the network even when the local
#                           cache holds the wrapper distribution
#   --qupath-home DIR       QuPath 0.7.0 installation (default
#                           QUPFLOWR_QUPATH_HOME, then /opt/QuPath-v0.7.0)
#   --java-home DIR         Java 25 runtime (default JAVA_HOME, then
#                           .tools/jdk-25)
#   --gradle-user-home DIR  Gradle cache (default GRADLE_USER_HOME, then
#                           .tools/gradle-home)
#   --keep-runtime          keep the temporary Java/Gradle home and logs
#
# Every child process gets an explicit environment: PATH, HOME, all XDG
# directories and TMPDIR inside a private temporary directory, a UTF-8
# locale, and no DISPLAY, WAYLAND_DISPLAY or JAVA_TOOL_OPTIONS. The generator
# JVM also gets its own java.util.prefs root, user.home, java.io.tmpdir and
# jna.tmpdir. Commands are argument vectors; no shell is involved.
#
# Exit status: 0 fixtures verified (reused or regenerated), 1 generation or
# verification failed, 2 usage or environment error.

fx_main_class <- paste0("io.github.cttir.qupflowr.fixtures.qupath.",
                        "FixtureGeneratorMain")
fx_logback <- "io/github/cttir/qupflowr/fixtures/qupath/logback-fixtures.xml"
fx_fixture_set <- "qupflowr-synthetic"
fx_fixture_version <- "1"
fx_manifest_version <- "1.0"
fx_qupath_core <- "qupath-core-0.7.0.jar"

fx_abort <- function(kind, msg) {
  cli::cli_abort(
    "{msg}",
    class = c(paste0("qp_fixture_", kind, "_error"), "qp_fixture_error",
              "qp_error")
  )
}

fx_usage <- function() {
  lines <- readLines(fx_script_path(), warn = FALSE, encoding = "UTF-8")
  end <- match(FALSE, startsWith(lines, "#")) - 1L
  cat(sub("^# ?", "", lines[seq(2L, end)]), sep = "\n")
}

fx_script_path <- function() {
  file_arg <- grep("^--file=", commandArgs(trailingOnly = FALSE),
                   value = TRUE)
  if (length(file_arg) != 1L) {
    fx_abort("usage", "run this script with Rscript tools/fixtures.R")
  }
  normalizePath(sub("^--file=", "", file_arg), mustWork = TRUE)
}

fx_parse_args <- function(args) {
  opts <- list(out = NA_character_, force = FALSE, verify_only = FALSE,
               build = TRUE, online = FALSE, qupath_home = NA_character_,
               java_home = NA_character_, gradle_home = NA_character_,
               keep_runtime = FALSE, help = FALSE)
  flags <- c("--force" = "force", "--verify-only" = "verify_only",
             "--online" = "online", "--keep-runtime" = "keep_runtime",
             "--help" = "help")
  values <- c("--out" = "out", "--qupath-home" = "qupath_home",
              "--java-home" = "java_home",
              "--gradle-user-home" = "gradle_home")
  i <- 1L
  while (i <= length(args)) {
    arg <- args[[i]]
    if (arg %in% names(flags)) {
      opts[[flags[[arg]]]] <- TRUE
    } else if (identical(arg, "--no-build")) {
      opts$build <- FALSE
    } else if (arg %in% names(values)) {
      if (i == length(args) || !nzchar(args[[i + 1L]])) {
        fx_abort("usage", paste(arg, "needs a value"))
      }
      opts[[values[[arg]]]] <- args[[i + 1L]]
      i <- i + 1L
    } else {
      fx_abort("usage", paste("unknown argument:", arg,
                              "(see --help)"))
    }
    i <- i + 1L
  }
  opts
}

fx_sha256 <- function(paths) {
  if (exists("sha256sum", envir = asNamespace("tools"), inherits = FALSE)) {
    return(unname(tools::sha256sum(paths)))
  }
  vapply(paths, function(p) digest::digest(file = p, algo = "sha256"),
         character(1), USE.NAMES = FALSE)
}

fx_abs <- function(path, base) {
  if (!grepl("^(/|[A-Za-z]:[/\\\\]|~)", path)) {
    path <- file.path(base, path)
  }
  normalizePath(path.expand(path), winslash = "/", mustWork = FALSE)
}

fx_is_within <- function(path, dir) {
  path <- paste0(normalizePath(path, winslash = "/", mustWork = FALSE), "/")
  dir <- paste0(normalizePath(dir, winslash = "/", mustWork = FALSE), "/")
  startsWith(path, dir)
}

fx_output_dir <- function(out, root) {
  out <- if (is.na(out)) {
    fx_abs(file.path("work", "fixtures", "v1"), root)
  } else {
    fx_abs(out, getwd())
  }
  forbidden <- file.path(root, c("inst", "R", "man", "tests", "tools",
                                 "setup"))
  if (any(vapply(forbidden, function(d) fx_is_within(out, d), TRUE)) ||
        out %in% c("/", normalizePath(root, winslash = "/"))) {
    fx_abort("usage", paste("refusing to write fixtures to", out,
                            "(not below inst/, R/, man/, tests/, tools/ or",
                            "setup/, and not the repository root)"))
  }
  out
}

fx_env_or <- function(value, variable, fallback) {
  if (!is.na(value)) {
    return(value)
  }
  from_env <- Sys.getenv(variable)
  if (nzchar(from_env)) from_env else fallback
}

fx_java_version <- function(java_home) {
  release <- file.path(java_home, "release")
  java <- file.path(java_home, "bin", "java")
  if (!file.exists(java) || !file.exists(release)) {
    fx_abort("environment", paste("no Java runtime at", java_home,
                                  "(set --java-home or JAVA_HOME)"))
  }
  line <- grep("^JAVA_VERSION=", readLines(release, warn = FALSE),
               value = TRUE)
  version <- gsub("^JAVA_VERSION=\"?|\"$", "", line)
  if (length(version) != 1L || !startsWith(version, "25")) {
    fx_abort("environment", paste("Java 25 is required, found",
                                  paste(version, collapse = " "), "at",
                                  java_home))
  }
  version
}

fx_check_qupath <- function(qupath_home) {
  core <- file.path(qupath_home, "lib", "app", fx_qupath_core)
  if (!file.exists(core)) {
    fx_abort("environment", paste("QuPath 0.7.0 not found:", core,
                                  "is missing (set --qupath-home or",
                                  "QUPFLOWR_QUPATH_HOME)"))
  }
  core
}

fx_runtime_dirs <- function() {
  # Outside the R session temporary directory, so --keep-runtime survives.
  base <- tempfile("qf-fixtures-", tmpdir = dirname(tempdir()))
  if (grepl("\\s", base) && .Platform$OS.type == "unix") {
    base <- tempfile("qf-fixtures-", tmpdir = "/tmp")
  }
  dirs <- list(base = base, home = file.path(base, "home"),
               prefs = file.path(base, "prefs"), tmp = file.path(base, "tmp"),
               javacpp = file.path(base, "javacpp"),
               config = file.path(base, "xdg", "config"),
               cache = file.path(base, "xdg", "cache"),
               data = file.path(base, "xdg", "data"),
               state = file.path(base, "xdg", "state"),
               logs = file.path(base, "logs"))
  for (d in dirs) {
    dir.create(d, recursive = TRUE, showWarnings = FALSE, mode = "0700")
  }
  dirs
}

fx_child_env <- function(runtime, java_home, extra = character()) {
  bin <- file.path(java_home, "bin")
  path <- paste(c(bin, "/usr/local/bin", "/usr/bin", "/bin"),
                collapse = .Platform$path.sep)
  locale <- if (Sys.info()[["sysname"]] == "Linux") "C.UTF-8" else
    "en_US.UTF-8"
  c(PATH = path, HOME = runtime$home, XDG_CONFIG_HOME = runtime$config,
    XDG_CACHE_HOME = runtime$cache, XDG_DATA_HOME = runtime$data,
    XDG_STATE_HOME = runtime$state, TMPDIR = runtime$tmp, LANG = locale,
    LC_ALL = locale, JAVA_HOME = java_home, extra)
}

fx_gradle_offline <- function(root, gradle_home) {
  props <- readLines(file.path(root, "tools", "bridge", "gradle", "wrapper",
                               "gradle-wrapper.properties"), warn = FALSE)
  url <- grep("^distributionUrl=", props, value = TRUE)
  dist <- sub("\\.zip$", "", basename(sub("^distributionUrl=", "", url)))
  dir.exists(file.path(gradle_home, "wrapper", "dists", dist)) &&
    dir.exists(file.path(gradle_home, "caches", "modules-2"))
}

fx_find_jar <- function(root) {
  libs <- file.path(root, "tools", "bridge", "bridge-fixtures", "build",
                    "libs")
  jars <- list.files(libs, pattern = "^qupflowr-bridge-fixtures-.*\\.jar$",
                     full.names = TRUE)
  if (length(jars) != 1L) {
    fx_abort("environment", paste("expected one generator jar in", libs,
                                  "but found", length(jars),
                                  "(build it first or drop --no-build)"))
  }
  normalizePath(jars, winslash = "/")
}

fx_build <- function(root, ctx) {
  bridge <- file.path(root, "tools", "bridge")
  wrapper <- if (.Platform$OS.type == "windows") "gradlew.bat" else "gradlew"
  offline <- !ctx$online && fx_gradle_offline(root, ctx$gradle_home)
  args <- c("-p", bridge, "--no-daemon", "--console=plain",
            if (offline) "--offline",
            paste0("-Pqupflowr.qupathHome=", ctx$qupath_home),
            ":bridge-fixtures:jar")
  opts <- paste0("-Duser.home=", ctx$runtime$home,
                 " -Djava.util.prefs.userRoot=", ctx$runtime$prefs,
                 " -Djava.io.tmpdir=", ctx$runtime$tmp)
  env <- fx_child_env(ctx$runtime, ctx$java_home,
                      c(GRADLE_USER_HOME = ctx$gradle_home,
                        GRADLE_OPTS = opts))
  log <- file.path(ctx$runtime$logs, "gradle.log")
  message("fixtures: building the generator (Gradle",
          if (offline) " --offline" else "", ")")
  res <- processx::run(file.path(bridge, wrapper), args, env = env,
                       wd = bridge, error_on_status = FALSE, stdout = log,
                       stderr_to_stdout = TRUE, timeout = 1800,
                       cleanup_tree = TRUE)
  if (!identical(res$status, 0L)) {
    message(paste(utils::tail(readLines(log, warn = FALSE), 30L),
                  collapse = "\n"))
    fx_abort("environment", paste("the Gradle build failed with status",
                                  res$status, "(log:", log, ")"))
  }
  fx_find_jar(root)
}

fx_read_manifest <- function(out) {
  path <- file.path(out, "manifest.json")
  if (!file.exists(path)) {
    return(NULL)
  }
  tryCatch(jsonlite::fromJSON(path, simplifyVector = FALSE),
           error = function(e) list(parse_error = conditionMessage(e)))
}

fx_disk_files <- function(out) {
  files <- list.files(out, recursive = TRUE, all.files = TRUE,
                      no.. = TRUE, include.dirs = FALSE)
  files <- enc2utf8(files[files != "manifest.json"])
  sort(files, method = "radix")
}

fx_verify_manifest <- function(out, fingerprint = NULL) {
  manifest <- fx_read_manifest(out)
  if (is.null(manifest)) {
    return(list(ok = FALSE, problems = "manifest.json is missing"))
  }
  if (!is.null(manifest$parse_error)) {
    return(list(ok = FALSE, problems = paste("manifest.json is invalid:",
                                             manifest$parse_error)))
  }
  found <- new.env(parent = emptyenv())
  found$problems <- character()
  add <- function(...) {
    found$problems <- c(found$problems, paste0(...))
  }
  expect_field <- function(name, value) {
    if (!identical(manifest[[name]], value)) {
      add(name, " is ", format(manifest[[name]]), ", expected ", value)
    }
  }
  expect_field("manifest_schema_version", fx_manifest_version)
  expect_field("fixture_set", fx_fixture_set)
  expect_field("fixture_set_version", fx_fixture_version)
  if (length(manifest$checks$failures) > 0L) {
    add("the generator reported failed checks")
  }
  listed <- vapply(manifest$files, function(f) f$path, character(1))
  unsafe <- grepl("(^|/)\\.\\.(/|$)|^/|\\\\", listed)
  if (any(unsafe)) {
    add("unsafe paths in the manifest: ", paste(listed[unsafe],
                                                collapse = ", "))
    return(list(ok = FALSE, problems = found$problems))
  }
  on_disk <- fx_disk_files(out)
  missing <- setdiff(listed, on_disk)
  extra <- setdiff(on_disk, listed)
  if (length(missing) > 0L) add("missing files: ", toString(missing))
  if (length(extra) > 0L) add("files not in the manifest: ", toString(extra))
  present <- manifest$files[listed %in% on_disk]
  if (length(present) > 0L) {
    paths <- file.path(out, vapply(present, function(f) f$path, ""))
    sizes <- file.size(paths)
    hashes <- fx_sha256(paths)
    for (k in seq_along(present)) {
      f <- present[[k]]
      if (!identical(as.numeric(f$size), as.numeric(sizes[[k]])) ||
            !identical(f$sha256, hashes[[k]])) {
        add("content differs from the manifest: ", f$path)
      }
    }
  }
  for (absent in unlist(manifest$absent_paths)) {
    if (file.exists(file.path(out, absent))) {
      add("path must not exist: ", absent)
    }
  }
  oracle <- manifest$oracle
  expected <- file.path(out, oracle$expected_values %||% "")
  if (!file.exists(expected) ||
        !identical(oracle$expected_values_sha256_at_write,
                   oracle$expected_values_sha256_at_end) ||
        !identical(fx_sha256(expected),
                   oracle$expected_values_sha256_at_write)) {
    add("expected values do not match the digest recorded before the ",
        "readers ran")
  }
  if (!is.null(fingerprint)) {
    seen <- list(jar_sha256 = manifest$generator$jar_sha256,
                 qupath_core_sha256 = manifest$runtime$qupath_core_sha256,
                 java_version = manifest$runtime$java_version,
                 output_root = manifest$output_root)
    for (name in names(fingerprint)) {
      if (!identical(seen[[name]], fingerprint[[name]])) {
        add("generated with a different ", name)
      }
    }
  }
  sizes <- vapply(manifest$files, function(f) as.numeric(f$size), 0)
  list(ok = length(found$problems) == 0L, problems = found$problems,
       files = length(listed), bytes = sum(sizes))
}

fx_generate <- function(out, ctx) {
  previous <- NULL
  if (file.exists(out)) {
    previous <- paste0(out, ".previous-", Sys.getpid())
    if (!file.rename(out, previous)) {
      fx_abort("failed", paste("cannot move the old output", out, "aside"))
    }
  }
  dir.create(dirname(out), recursive = TRUE, showWarnings = FALSE)
  java <- file.path(ctx$java_home, "bin", "java")
  classpath <- paste(c(ctx$jar, file.path(ctx$qupath_home, "lib", "app",
                                          "*")),
                     collapse = .Platform$path.sep)
  rt <- ctx$runtime
  args <- c("-XX:-UsePerfData", "--enable-native-access=ALL-UNNAMED",
            "-Xmx2g", "-Djava.awt.headless=true", "-Dprism.order=sw",
            paste0("-Djava.util.prefs.userRoot=", rt$prefs),
            paste0("-Duser.home=", rt$home),
            paste0("-Djava.io.tmpdir=", rt$tmp),
            paste0("-Djna.tmpdir=", rt$tmp),
            paste0("-Dorg.bytedeco.javacpp.cachedir=", rt$javacpp),
            paste0("-Dlogback.configurationFile=", fx_logback),
            "-cp", classpath, fx_main_class, "--out", out)
  log <- file.path(rt$logs, "generator.log")
  message("fixtures: generating into ", out)
  res <- processx::run(java, args, env = fx_child_env(rt, ctx$java_home),
                       wd = rt$base, error_on_status = FALSE, stdout = log,
                       stderr_to_stdout = TRUE, timeout = 900,
                       cleanup_tree = TRUE)
  check <- if (identical(res$status, 0L)) {
    fx_verify_manifest(out, ctx$fingerprint(out))
  } else {
    list(ok = FALSE, problems = paste("the generator exited with status",
                                      res$status))
  }
  if (!check$ok) {
    message(paste(utils::tail(readLines(log, warn = FALSE), 30L),
                  collapse = "\n"))
    unlink(out, recursive = TRUE)
    if (!is.null(previous)) file.rename(previous, out)
    fx_abort("failed", paste(c("fixture generation failed:",
                               check$problems, paste("log:", log)),
                             collapse = "\n"))
  }
  if (!is.null(previous)) unlink(previous, recursive = TRUE)
  check
}

fx_run <- function(args) {
  opts <- fx_parse_args(args)
  if (opts$help) {
    fx_usage()
    return(0L)
  }
  root <- normalizePath(file.path(dirname(fx_script_path()), ".."),
                        winslash = "/")
  out <- fx_output_dir(opts$out, root)
  if (opts$verify_only) {
    check <- fx_verify_manifest(out)
    if (!check$ok) {
      fx_abort("failed", paste(c(paste("verification of", out, "failed:"),
                                 check$problems), collapse = "\n"))
    }
    message("fixtures: verified ", check$files, " files in ", out)
    return(0L)
  }
  for (pkg in c("processx", "jsonlite", "cli")) {
    if (!requireNamespace(pkg, quietly = TRUE)) {
      fx_abort("environment", paste("the R package", pkg, "is required"))
    }
  }
  java_home <- fx_abs(fx_env_or(opts$java_home, "JAVA_HOME",
                                file.path(root, ".tools", "jdk-25")), root)
  qupath_home <- fx_abs(fx_env_or(opts$qupath_home, "QUPFLOWR_QUPATH_HOME",
                                  "/opt/QuPath-v0.7.0"), root)
  gradle_home <- fx_abs(fx_env_or(opts$gradle_home, "GRADLE_USER_HOME",
                                  file.path(root, ".tools", "gradle-home")),
                        root)
  java_version <- fx_java_version(java_home)
  core <- fx_check_qupath(qupath_home)
  runtime <- fx_runtime_dirs()
  if (opts$keep_runtime) {
    message("fixtures: runtime and logs kept in ", runtime$base)
  } else {
    on.exit(unlink(runtime$base, recursive = TRUE), add = TRUE)
  }
  ctx <- list(java_home = java_home, qupath_home = qupath_home,
              gradle_home = gradle_home, online = opts$online,
              runtime = runtime)
  ctx$jar <- if (opts$build) fx_build(root, ctx) else fx_find_jar(root)
  jar_sha <- fx_sha256(ctx$jar)
  core_sha <- fx_sha256(core)
  ctx$fingerprint <- function(dir) {
    list(jar_sha256 = jar_sha, qupath_core_sha256 = core_sha,
         java_version = java_version,
         output_root = normalizePath(dir, winslash = "/", mustWork = FALSE))
  }
  if (!opts$force && file.exists(file.path(out, "manifest.json"))) {
    check <- fx_verify_manifest(out, ctx$fingerprint(out))
    if (check$ok) {
      message("fixtures: reused ", check$files, " verified files in ", out)
      return(0L)
    }
    message("fixtures: existing output does not verify, regenerating:\n  ",
            paste(check$problems, collapse = "\n  "))
  }
  check <- fx_generate(out, ctx)
  message("fixtures: generated and verified ", check$files, " files (",
          format(check$bytes, big.mark = ","), " bytes) in ", out)
  0L
}

fx_main <- function() {
  status <- tryCatch(
    fx_run(commandArgs(trailingOnly = TRUE)),
    qp_fixture_usage_error = function(cnd) {
      message("fixtures: ", conditionMessage(cnd))
      2L
    },
    qp_fixture_environment_error = function(cnd) {
      message("fixtures: ", conditionMessage(cnd))
      2L
    },
    qp_fixture_error = function(cnd) {
      message("fixtures: ", conditionMessage(cnd))
      1L
    },
    error = function(cnd) {
      message("fixtures: unexpected error: ", conditionMessage(cnd))
      1L
    }
  )
  quit(save = "no", status = status)
}

if (!interactive() && sys.nframe() == 0L) {
  fx_main()
}
