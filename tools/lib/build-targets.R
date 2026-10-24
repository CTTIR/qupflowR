# Build targets of tools/build.R: bridge, package and fixtures.
#
# Each target_<name>(ctx) returns list(result = "pass" | "fail", body) for
# the evidence report and signals qp_tool_environment_error when a
# prerequisite is missing. ctx: tl (common helpers), root, rt, opts,
# report_dir, artifacts_dir, java_home, qupath_home, gradle_home, label,
# quiet.

build_duration <- function(started) {
  round(as.numeric(difftime(Sys.time(), started, units = "secs")), 1)
}

build_failure_reasons <- function(tl, checks) {
  lapply(tl$failed_checks(checks), function(f) {
    paste0(f$id, ": expected ", f$expected, ", found ", f$actual)
  })
}

# Copies `src` into `dest_dir` through a temporary name, writes a fresh
# sha256 sidecar and reports whether the destination content changed.
build_publish <- function(tl, src, dest_dir, name = basename(src)) {
  tl$ensure_dir(dest_dir)
  dest <- file.path(dest_dir, name)
  new_sha <- tl$sha256_files(src)
  old_sha <- if (file.exists(dest)) tl$sha256_files(dest) else NULL
  if (!identical(old_sha, new_sha)) {
    tmp <- paste0(dest, ".tmp-", Sys.getpid())
    if (!file.copy(src, tmp, overwrite = TRUE) || !file.rename(tmp, dest)) {
      unlink(tmp)
      tl$tool_abort("failed", paste("cannot publish", src, "to", dest))
    }
  }
  tl$write_sha256_sidecar(dest)
  list(path = dest, sha256 = tl$sha256_files(dest), previous_sha256 = old_sha,
       changed = !identical(old_sha, new_sha))
}

# ---- bridge ---------------------------------------------------------------

build_bundle_members <- function(tl, bundle, scratch) {
  dir <- tempfile("bundle-", tmpdir = scratch)
  dir.create(dir)
  on.exit(unlink(dir, recursive = TRUE), add = TRUE)
  utils::unzip(bundle, exdir = dir)
  manifest <- tl$read_json_file(file.path(dir, "manifest.json"))
  if (is.null(manifest)) {
    return(list(manifest = NULL, members = list(), ok = FALSE))
  }
  members <- lapply(manifest$members, function(m) {
    path <- file.path(dir, m$path)
    present <- file.exists(path)
    list(path = m$path, role = m$role, present = present,
         size_ok = present && identical(as.numeric(file.size(path)),
                                        as.numeric(m$size)),
         sha256_ok = present && identical(tl$sha256_files(path), m$sha256))
  })
  ok <- length(members) > 0L && all(vapply(members, function(m) {
    m$present && m$size_ok && m$sha256_ok
  }, TRUE))
  list(manifest = manifest[setdiff(names(manifest), "members")],
       members = members, ok = ok)
}

target_bridge <- function(ctx) {
  tl <- ctx$tl
  started <- Sys.time()
  checks <- tl$new_checks()
  cmds <- tl$new_command_log()
  logs <- file.path(ctx$report_dir, "build", "logs")
  tl$ensure_dir(logs)
  bridge <- file.path(ctx$root, "tools", "bridge")
  java <- tl$java_home_info(ctx$java_home)
  if (!tl$java_home_ok(java)) {
    tl$tool_abort("environment", paste(
      "a JDK 25 with javac is required at", ctx$java_home,
      "(set --java-home or JAVA_HOME; see dev/toolchain.env)"
    ))
  }
  wrapper <- tl$gradle_wrapper(bridge)
  if (!file.exists(wrapper)) {
    tl$tool_abort("environment", paste("Gradle wrapper missing:", wrapper))
  }
  version <- tl$gradle_properties(bridge)$version
  offline <- !isTRUE(ctx$opts$online) &&
    tl$gradle_offline_ok(bridge, ctx$gradle_home)
  jvm <- tl$jvm_isolation_options(ctx$rt)
  args <- c("-p", bridge, "--no-daemon", "--console=plain",
            if (offline) "--offline",
            paste0("-Dorg.gradle.jvmargs=", jvm, " -Xmx2g"),
            paste0("-Pqupflowr.qupathHome=", ctx$qupath_home),
            if (isTRUE(ctx$opts$force)) "--rerun-tasks",
            "test", "bridgeBundle", "verifyJarContents")
  env <- tl$child_env(
    ctx$rt, path_dirs = file.path(ctx$java_home, "bin"),
    extra = c(JAVA_HOME = ctx$java_home, GRADLE_USER_HOME = ctx$gradle_home,
              GRADLE_OPTS = jvm)
  )
  log <- file.path(logs, "bridge-gradle.log")
  status <- tl$run_logged(cmds, "gradle_test_bundle_verify", wrapper, args,
                          log, env, bridge, timeout = 3600,
                          label = ctx$label, quiet = ctx$quiet)
  tl$add_check(checks, "gradle_exit_code", 0L, status)
  junit <- tl$junit_summary(bridge)
  tl$add_check(checks, "junit_tests_ran", TRUE, junit$tests > 0L)
  tl$add_check(checks, "junit_failures_and_errors", 0L,
               junit$failures + junit$errors)

  bundle <- file.path(bridge, "build", "distributions",
                      sprintf("qupflowr-bridge-bundle-%s.zip", version))
  published <- NULL
  members <- NULL
  if (tl$add_check(checks, "bundle_sidecar_matches", TRUE,
                   tl$sidecar_matches(bundle))) {
    members <- build_bundle_members(tl, bundle, ctx$rt$tmp)
    tl$add_check(checks, "bundle_members_match_manifest", TRUE, members$ok)
    if (status == 0L && members$ok) {
      published <- build_publish(tl, bundle,
                                 file.path(ctx$artifacts_dir, "bridge"))
      tl$add_check(checks, "published_bundle_sidecar_matches", TRUE,
                   tl$sidecar_matches(published$path))
      tl$add_check(checks, "published_bundle_equals_build_output",
                   tl$sha256_files(bundle), published$sha256)
    }
  }
  result <- if (length(tl$failed_checks(checks)) == 0L) "pass" else "fail"
  digests <- list()
  if (!is.null(published)) {
    digests[[tl$relative_to(published$path, ctx$root)]] <- published$sha256
  }
  body <- list(
    started_at = tl$utc_now(started), duration_s = build_duration(started),
    quality_ids = list("Q16", "Q19"),
    artifact_digests = digests,
    runtime_profile = tl$runtime_profile(extra = list(
      jdk = java, gradle_offline = offline, gradle_user_home = ctx$gradle_home
    )),
    commands = cmds$commands, exit_codes = tl$exit_codes_of(cmds),
    oracle = paste(
      "Gradle wrapper with strict dependency verification and lock files;",
      "JUnit XML results; bundle manifest sha256/size of every member",
      "recomputed from the extracted zip; published copy compared by sha256."
    ),
    tolerances = "none (exact digests, zero failed tests)",
    checks = checks$items, result = result,
    failure_reasons = build_failure_reasons(tl, checks),
    logs = list(tl$relative_to(log, ctx$root)),
    limitations = list(
      paste("Gradle may report tasks as up to date; their JUnit results are",
            "then those of the last execution with identical inputs (use",
            "--force to rerun every task)."),
      "Java code coverage is not measured."
    ),
    details = list(
      bridge_version = version, gradle_offline = offline,
      gradle_tasks = tl$gradle_task_summary(log),
      junit = junit[c("files", "tests", "failures", "errors", "skipped",
                      "modules")],
      bundle = list(build_output = tl$relative_to(bundle, ctx$root),
                    manifest = members$manifest, members = members$members),
      published = published
    )
  )
  list(result = result, body = body)
}

# ---- package --------------------------------------------------------------

build_description_version <- function(root) {
  unname(read.dcf(file.path(root, "DESCRIPTION"), fields = "Version")[1L, 1L])
}

build_doc_files <- function(dir) {
  man <- list.files(file.path(dir, "man"), pattern = "\\.Rd$")
  files <- c("DESCRIPTION", "NAMESPACE", file.path("man", man))
  files[file.exists(file.path(dir, files))]
}

build_doc_digests <- function(tl, dir) {
  files <- build_doc_files(dir)
  stats::setNames(as.list(tl$sha256_files(file.path(dir, files))), files)
}

build_digest_diff <- function(before, after) {
  keys <- sort(union(names(before), names(after)), method = "radix")
  keys[!vapply(keys, function(k) identical(before[[k]], after[[k]]), TRUE)]
}

build_roxygen_code <- function(dir) {
  sprintf("roxygen2::roxygenise(%s)", deparse(dir))
}

build_roxygen <- function(ctx, cmds, checks, logs, env) {
  tl <- ctx$tl
  mode <- tl$opt_or(ctx$opts, "roxygen", "update")
  if (identical(mode, "skip")) {
    return(list(mode = mode))
  }
  log <- file.path(logs, paste0("roxygen-", mode, ".log"))
  if (identical(mode, "update")) {
    before <- build_doc_digests(tl, ctx$root)
    status <- tl$run_logged(cmds, "roxygen_update", tl$rscript_path(),
                            c("-e", build_roxygen_code(ctx$root)), log, env,
                            ctx$root, timeout = 900, label = ctx$label,
                            quiet = ctx$quiet)
    tl$add_check(checks, "roxygen_exit_code", 0L, status)
    changed <- build_digest_diff(before, build_doc_digests(tl, ctx$root))
    return(list(mode = mode, changed_files = as.list(changed)))
  }
  copy <- file.path(ctx$rt$tmp, "roxygen-check", "qupflowR")
  tl$ensure_dir(copy)
  parts <- c("DESCRIPTION", "NAMESPACE", ".Rbuildignore", "R", "man", "inst")
  parts <- parts[file.exists(file.path(ctx$root, parts))]
  file.copy(file.path(ctx$root, parts), copy, recursive = TRUE)
  status <- tl$run_logged(cmds, "roxygen_check", tl$rscript_path(),
                          c("-e", build_roxygen_code(copy)), log, env, copy,
                          timeout = 900, label = ctx$label, quiet = ctx$quiet)
  tl$add_check(checks, "roxygen_exit_code", 0L, status)
  stale <- build_digest_diff(build_doc_digests(tl, ctx$root),
                             build_doc_digests(tl, copy))
  tl$add_check(checks, "roxygen_documentation_current", 0L, length(stale))
  unlink(dirname(copy), recursive = TRUE)
  list(mode = mode, stale_files = as.list(stale))
}

# Files of the source inventory that R CMD build would consider: every
# path whose own name or any parent directory matches no .Rbuildignore
# pattern (perl, case-insensitive, as R CMD build applies them).
build_package_inputs <- function(tl, root, inventory) {
  patterns <- character()
  ignore <- file.path(root, ".Rbuildignore")
  if (file.exists(ignore)) {
    patterns <- trimws(readLines(ignore, warn = FALSE))
    patterns <- patterns[nzchar(patterns) & !startsWith(patterns, "#")]
  }
  lines <- inventory$lines
  paths <- sub("\n$", "", sub("^[^\t]*\t", "", lines))
  excluded <- vapply(paths, function(p) {
    parts <- strsplit(p, "/", fixed = TRUE)[[1L]]
    prefixes <- vapply(seq_along(parts), function(i) {
      paste(parts[seq_len(i)], collapse = "/")
    }, "")
    any(vapply(patterns, function(pat) {
      any(grepl(pat, prefixes, perl = TRUE, ignore.case = TRUE))
    }, TRUE))
  }, TRUE)
  kept <- lines[!excluded]
  list(file_count = length(kept),
       sha256 = tl$sha256_text(paste(kept, collapse = "")))
}

build_fingerprint <- function(tl) {
  list(r_version = R.version.string,
       roxygen2 = tl$package_version_or_null("roxygen2"),
       knitr = tl$package_version_or_null("knitr"),
       rmarkdown = tl$package_version_or_null("rmarkdown"))
}

build_tar_listing <- function(tarball) {
  sort(utils::untar(tarball, list = TRUE, tar = "internal"), method = "radix")
}

target_package <- function(ctx) {
  tl <- ctx$tl
  started <- Sys.time()
  checks <- tl$new_checks()
  cmds <- tl$new_command_log()
  logs <- file.path(ctx$report_dir, "build", "logs")
  tl$ensure_dir(logs)
  mode <- tl$opt_or(ctx$opts, "roxygen", "update")
  if (!mode %in% c("update", "check", "skip")) {
    tl$tool_abort("usage", "--roxygen must be update, check or skip")
  }
  tl$require_packages(c("roxygen2", "knitr", "rmarkdown"))
  version <- build_description_version(ctx$root)
  name <- sprintf("qupflowR_%s.tar.gz", version)
  dest_dir <- file.path(ctx$artifacts_dir, "r")
  env <- tl$child_env(ctx$rt, extra = c(NOT_CRAN = "true"))
  roxygen <- build_roxygen(ctx, cmds, checks, logs, env)

  inputs <- build_package_inputs(tl, ctx$root,
                                 tl$source_inventory(ctx$root, ctx$rt))
  fingerprint <- build_fingerprint(tl)
  record_path <- file.path(dest_dir, sub("\\.tar\\.gz$", ".build.json", name))
  record <- tl$read_json_file(record_path)
  dest <- file.path(dest_dir, name)
  reusable <- !isTRUE(ctx$opts$force) && !is.null(record) &&
    identical(record$inputs_sha256, inputs$sha256) &&
    identical(record$fingerprint, fingerprint) &&
    identical(record$tarball_sha256, tl$sha256_files(dest)[1L]) &&
    tl$sidecar_matches(dest) &&
    length(tl$failed_checks(checks)) == 0L
  build_log <- file.path(logs, "r-cmd-build.log")
  if (length(tl$failed_checks(checks)) > 0L) {
    published <- NULL
  } else if (reusable) {
    tl$say(ctx$label, "reusing ", dest, " (inputs unchanged)",
           quiet = ctx$quiet)
    published <- list(path = dest, sha256 = record$tarball_sha256,
                      changed = FALSE, reused = TRUE)
  } else {
    build_dir <- file.path(ctx$rt$base, "package-build")
    tl$reset_dir(build_dir, ctx$rt$base)
    status <- tl$run_logged(cmds, "r_cmd_build", tl$r_path(),
                            c("CMD", "build", "--no-manual", ctx$root),
                            build_log, env, build_dir, timeout = 1800,
                            label = ctx$label, quiet = ctx$quiet)
    tl$add_check(checks, "r_cmd_build_exit_code", 0L, status)
    built <- file.path(build_dir, name)
    tl$add_check(checks, "tarball_created", name,
                 if (file.exists(built)) name else NULL)
    published <- NULL
    if (file.exists(built) && length(tl$failed_checks(checks)) == 0L) {
      published <- build_publish(tl, built, dest_dir)
      published$reused <- FALSE
    }
  }

  listing <- NULL
  if (!is.null(published)) {
    listing <- build_tar_listing(published$path)
    files_path <- file.path(dest_dir, sub("\\.tar\\.gz$", ".files.txt", name))
    writeLines(listing, files_path, useBytes = TRUE)
    tl$add_check(checks, "tarball_sidecar_matches", TRUE,
                 tl$sidecar_matches(published$path))
    tl$add_check(checks, "tarball_entries_below_package_directory", TRUE,
                 all(startsWith(listing, "qupflowR/")))
    tl$add_check(checks, "tarball_contains_description", TRUE,
                 "qupflowR/DESCRIPTION" %in% listing)
    tl$write_json_file(list(
      tarball = name, tarball_sha256 = published$sha256,
      inputs_sha256 = inputs$sha256, inputs_file_count = inputs$file_count,
      fingerprint = fingerprint, built_at = tl$utc_now()
    ), record_path)
  }
  result <- if (length(tl$failed_checks(checks)) == 0L) "pass" else "fail"
  digests <- list()
  if (!is.null(published)) {
    digests[[tl$relative_to(published$path, ctx$root)]] <- published$sha256
  }
  body <- list(
    started_at = tl$utc_now(started), duration_s = build_duration(started),
    quality_ids = list("Q17", "Q19"),
    artifact_digests = digests,
    runtime_profile = tl$runtime_profile(
      packages = c("roxygen2", "knitr", "rmarkdown", "testthat")
    ),
    commands = cmds$commands, exit_codes = tl$exit_codes_of(cmds),
    oracle = paste(
      "R CMD build output; the tar listing is read from the published",
      "tarball itself; roxygen2 check mode regenerates NAMESPACE and man/",
      "in a copy and compares bytes."
    ),
    tolerances = "none",
    checks = checks$items, result = result,
    failure_reasons = build_failure_reasons(tl, checks),
    logs = as.list(tl$relative_to(list.files(logs, full.names = TRUE,
                                             pattern = "^(roxygen|r-cmd)"),
                                  ctx$root)),
    limitations = list(
      paste("R CMD build output is not byte-reproducible (timestamps);",
            "reuse is decided by the digest of the build inputs and the",
            "R/roxygen2/knitr/rmarkdown versions."),
      "The forbidden-path audit of the tarball is part of validation lane L11."
    ),
    details = list(
      version = version, roxygen = roxygen, inputs = inputs,
      fingerprint = fingerprint, published = published,
      file_list = if (is.null(listing)) NULL else list(
        path = tl$relative_to(file.path(dest_dir, sub("\\.tar\\.gz$",
                                                      ".files.txt", name)),
                              ctx$root),
        entries = length(listing)
      )
    )
  )
  list(result = result, body = body)
}

# ---- fixtures -------------------------------------------------------------

target_fixtures <- function(ctx) {
  tl <- ctx$tl
  started <- Sys.time()
  checks <- tl$new_checks()
  cmds <- tl$new_command_log()
  logs <- file.path(ctx$report_dir, "build", "logs")
  tl$ensure_dir(logs)
  out <- file.path(ctx$root, "work", "fixtures", "v1")
  env <- tl$child_env(ctx$rt, extra = c(
    JAVA_HOME = ctx$java_home, QUPFLOWR_QUPATH_HOME = ctx$qupath_home,
    GRADLE_USER_HOME = ctx$gradle_home
  ))
  oracle <- tl$fixture_oracle(list(
    cmds = cmds, checks = checks, root = ctx$root, out = out, logs = logs,
    summary = file.path(logs, "fixtures-oracle-summary.json"), env = env,
    test_env = tl$child_env(ctx$rt, extra = c(NOT_CRAN = "true")),
    java_home = ctx$java_home, qupath_home = ctx$qupath_home,
    gradle_home = ctx$gradle_home, online = ctx$opts$online,
    force = ctx$opts$force, label = ctx$label, quiet = ctx$quiet
  ))
  manifest <- oracle$manifest
  fixture_digests <- oracle$fixture_digests
  result <- if (length(tl$failed_checks(checks)) == 0L) "pass" else "fail"
  body <- list(
    started_at = tl$utc_now(started), duration_s = build_duration(started),
    quality_ids = list("Q10"),
    fixture_digests = fixture_digests,
    runtime_profile = tl$runtime_profile(extra = list(
      jdk = tl$java_home_info(ctx$java_home),
      qupath = tl$qupath_home_info(ctx$qupath_home)
    )),
    commands = cmds$commands, exit_codes = tl$exit_codes_of(cmds),
    oracle = paste(
      "tools/fixtures.R verifies sizes and sha256 of every generated file",
      "against the manifest and the expected-values digest recorded before",
      "any reader ran; --verify-only repeats that check on the final tree.",
      "tests/testthat/test-fixtures-expected.R then compares the generated",
      "tree with the committed tables in tests/fixtures/qp-v1 (written from",
      "the formulas, not from generator output), reads the TIFF samples",
      "with its own R reader and inspects the QuPath project files; it must",
      "run with zero failures and zero skips."
    ),
    tolerances = "none (exact digests)",
    checks = checks$items, result = result,
    failure_reasons = build_failure_reasons(tl, checks),
    logs = as.list(tl$relative_to(file.path(logs, c(
      "fixtures-generate.log", "fixtures-verify.log", "fixtures-oracle.log",
      "fixtures-oracle-summary.json"
    )), ctx$root)),
    limitations = list(paste(
      "QuPath project files contain absolute paths and timestamps; the",
      "manifest's normalised digests cover them (tools/fixtures.R)."
    )),
    details = list(
      output = tl$relative_to(out, ctx$root),
      fixture_set = manifest$fixture_set,
      fixture_set_version = manifest$fixture_set_version,
      files = length(manifest$files),
      generator = manifest$generator, runtime = manifest$runtime,
      oracle_tests = oracle$summary[c("counts", "per_file", "problems",
                                      "skips")]
    )
  )
  list(result = result, body = body)
}
