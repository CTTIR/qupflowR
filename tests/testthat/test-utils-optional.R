# Optional package checks (R/utils-optional.R) ------------------------------

absent_package <- "qupflowRabsentpkg"

fake_installed_library <- function(pkg, version) {
  lib <- withr::local_tempdir("qf-fake-lib-", .local_envir = parent.frame())
  dir.create(file.path(lib, pkg))
  writeLines(c(paste("Package:", pkg), paste("Version:", version),
               "Title: Fake", "License: GPL-3", "Built: R 4.4.0; ; ; unix"),
             file.path(lib, pkg, "DESCRIPTION"))
  lib
}

test_that("package status reports installed versions without loading", {
  lib <- fake_installed_library("qfFakeOptional", "1.2.3")
  withr::local_libpaths(lib, action = "prefix")
  status <- qp_package_status(c("stats", "qfFakeOptional", absent_package),
                              versions = c(NA, "1.2.4", NA))
  expect_s3_class(status, "tbl_df")
  expect_identical(status$package,
                   c("stats", "qfFakeOptional", absent_package))
  expect_identical(status$installed_version,
                   c(as.character(getRversion()), "1.2.3", NA))
  expect_identical(status$required_version, c(NA, "1.2.4", NA))
  expect_identical(status$available, c(TRUE, FALSE, FALSE))
  expect_false(isNamespaceLoaded("qfFakeOptional"))
})

test_that("qp_check_installed returns the packages when all are usable", {
  lib <- fake_installed_library("qfFakeOptional", "1.2.3")
  withr::local_libpaths(lib, action = "prefix")
  expect_invisible(qp_check_installed(c("stats", "qfFakeOptional"),
                                      reason = "a test feature",
                                      versions = c(NA, "1.2.3")))
  expect_identical(qp_check_installed("utils", reason = "a test feature"),
                   "utils")
  expect_false(isNamespaceLoaded("qfFakeOptional"))
})

test_that("missing and outdated packages raise a capability error", {
  lib <- fake_installed_library("qfFakeOptional", "1.2.3")
  withr::local_libpaths(lib, action = "prefix")
  cnd <- expect_error(
    qp_check_installed(c(absent_package, "qfFakeOptional", "stats"),
                       reason = "the Shiny app",
                       versions = c(NA, "2.0", NA)),
    class = "qp_capability_error"
  )
  expect_s3_class(cnd, "qp_error")
  expect_identical(cnd$code, "CAPABILITY_UNAVAILABLE")
  expect_identical(cnd$details$reason, "the Shiny app")
  expect_identical(cnd$details$missing, absent_package)
  expect_identical(cnd$details$outdated, "qfFakeOptional (1.2.3 < 2.0)")
  expect_identical(cnd$action, "install_packages")
  expect_identical(cnd$message_key, "error.optional_packages_unavailable")
  message_text <- conditionMessage(cnd)
  expect_match(message_text, absent_package, fixed = TRUE)
  expect_match(message_text, "qfFakeOptional (1.2.3 < 2.0)", fixed = TRUE)
  expect_match(message_text, "the Shiny app", fixed = TRUE)
})

test_that("invalid arguments raise validation errors", {
  bad_pkgs <- list(character(), NA_character_, "", c("stats", "stats"), 1)
  for (pkgs in bad_pkgs) {
    cnd <- expect_error(qp_check_installed(pkgs, reason = "x"),
                        class = "qp_validation_error")
    expect_identical(cnd$code, "INVALID_ARGUMENT")
    expect_identical(cnd$details$argument, "pkgs")
  }
  for (reason in list(NULL, NA_character_, "  ", c("a", "b"), 1)) {
    cnd <- expect_error(qp_check_installed("stats", reason = reason),
                        class = "qp_validation_error")
    expect_identical(cnd$details$argument, "reason")
  }
  for (versions in list("1.0", c("1.0", "2.0", "3.0"), "not a version", 1)) {
    cnd <- expect_error(
      qp_check_installed(c("stats", "utils"), reason = "x",
                         versions = versions),
      class = "qp_validation_error"
    )
    expect_identical(cnd$details$argument, "versions")
  }
})

test_that("messages of the optional checks exist in German and English", {
  catalogue <- jsonlite::fromJSON(
    system.file("i18n", "messages.json", package = "qupflowR"),
    simplifyVector = FALSE
  )
  keys <- c("error.optional_packages_unavailable", "condition.needed_for",
            "condition.packages_missing", "condition.packages_outdated",
            "expect.package_names", "expect.version_strings",
            "expect.string")
  for (key in keys) {
    entry <- catalogue$messages[[key]]
    expect_true(is.character(entry$de) && nzchar(entry$de), label = key)
    expect_true(is.character(entry$en) && nzchar(entry$en), label = key)
  }
})

test_that("capability errors report the caller as their call", {
  feature <- function() qp_check_installed(absent_package, reason = "x")
  cnd <- expect_error(feature(), class = "qp_capability_error")
  expect_identical(conditionCall(cnd), quote(feature()))
})

# Integration prerequisite helpers (tests/testthat/helper-skip.R) -----------

write_script <- function(path, lines) {
  dir.create(dirname(path), recursive = TRUE, showWarnings = FALSE)
  writeLines(c("#!/bin/sh", lines), path)
  Sys.chmod(path, "0755")
  path
}

fake_java_home <- function(release_version = NULL, script = "exit 1") {
  home <- withr::local_tempdir("qf-java-", .local_envir = parent.frame())
  write_script(file.path(home, "bin", "java"), script)
  if (!is.null(release_version)) {
    writeLines(c("IMPLEMENTOR=\"Test\"",
                 sprintf("JAVA_VERSION=\"%s\"", release_version)),
               file.path(home, "release"))
  }
  home
}

test_that("Java major versions follow both version schemes", {
  expect_identical(qp_java_major("25.0.4"), 25L)
  expect_identical(qp_java_major("25"), 25L)
  expect_identical(qp_java_major("26-ea"), 26L)
  expect_identical(qp_java_major("1.8.0_392"), 8L)
  expect_identical(qp_java_major("unknown"), NA_integer_)
})

test_that("Java detection reads the release file of an explicit runtime", {
  skip_on_os("windows")
  expect_identical(qp_detect_java("", "")$reason_code, "java_not_configured")
  home <- fake_java_home("25.0.4")
  found <- qp_detect_java(java = "", java_home = home)
  expect_true(found$ok)
  expect_identical(found$value, normalizePath(file.path(home, "bin", "java")))
  expect_identical(found$details$major, 25L)
  expect_identical(found$details$version_source, "release_file")
  old <- qp_detect_java(java = "", java_home = fake_java_home("21.0.2"))
  expect_identical(old$reason_code, "java_version_mismatch")
  legacy <- qp_detect_java(java = "", java_home = fake_java_home("1.8.0_392"))
  expect_identical(legacy$details$major, 8L)
  expect_false(legacy$ok)
  not_executable <- file.path(withr::local_tempdir(), "java")
  file.create(not_executable)
  expect_identical(qp_detect_java(java = not_executable)$reason_code,
                   "java_not_found")
})

test_that("Java detection without release file runs java in a clean env", {
  skip_on_os("windows")
  skip_if_not_installed("processx")
  script <- c(
    "if [ -n \"$DISPLAY\" ] || [ -n \"$WAYLAND_DISPLAY\" ]; then exit 3; fi",
    "echo 'openjdk version \"25.0.1\" 2025-10-21' >&2",
    "exit 0"
  )
  home <- fake_java_home(NULL, script)
  withr::local_envvar(DISPLAY = ":987", WAYLAND_DISPLAY = "qf-test-wayland")
  found <- qp_detect_java(java = file.path(home, "bin", "java"),
                          java_home = "")
  expect_true(found$ok)
  expect_identical(found$details$version, "25.0.1")
  expect_identical(found$details$version_source, "version_output")
  failing <- fake_java_home(NULL, "exit 1")
  expect_identical(
    qp_detect_java(java = file.path(failing, "bin", "java"))$reason_code,
    "java_version_unknown"
  )
})

test_that("QuPath detection requires the exact core jar version", {
  expect_identical(qp_detect_qupath("")$reason_code, "qupath_not_configured")
  home <- withr::local_tempdir("qf-qupath-")
  expect_identical(qp_detect_qupath(home)$reason_code, "qupath_not_found")
  dir.create(file.path(home, "lib", "app"), recursive = TRUE)
  expect_identical(qp_detect_qupath(home)$reason_code, "qupath_not_found")
  file.create(file.path(home, "lib", "app", "qupath-core-0.6.0.jar"))
  mismatch <- qp_detect_qupath(home, version = "0.7.0")
  expect_identical(mismatch$reason_code, "qupath_version_mismatch")
  expect_identical(mismatch$details$found_versions, "0.6.0")
  file.create(file.path(home, "lib", "app", "qupath-core-0.7.0.jar"))
  found <- qp_detect_qupath(home, version = "0.7.0")
  expect_true(found$ok)
  expect_identical(found$value, normalizePath(home))
})

test_that("bridge artifact detection checks zip, manifest and checksum", {
  skip_if_not_installed("zip")
  dir <- withr::local_tempdir("qf-bridge-")
  expect_identical(qp_detect_bridge_artifact("")$reason_code,
                   "bridge_artifact_not_configured")
  expect_identical(
    qp_detect_bridge_artifact(file.path(dir, "absent.zip"))$reason_code,
    "bridge_artifact_not_found"
  )
  not_zip <- file.path(dir, "not-a-zip.zip")
  writeLines("plain text", not_zip)
  expect_identical(qp_detect_bridge_artifact(not_zip)$reason_code,
                   "bridge_artifact_invalid")
  writeLines("{}", file.path(dir, "manifest.json"))
  writeLines("x", file.path(dir, "other.txt"))
  no_manifest <- file.path(dir, "no-manifest.zip")
  zip::zip(no_manifest, "other.txt", root = dir)
  expect_identical(qp_detect_bridge_artifact(no_manifest)$reason_code,
                   "bridge_artifact_invalid")
  bundle <- file.path(dir, "bundle.zip")
  zip::zip(bundle, c("manifest.json", "other.txt"), root = dir)
  found <- qp_detect_bridge_artifact(bundle)
  expect_true(found$ok)
  expect_false(found$details$checksum_verified)
  writeLines(paste(strrep("0", 64), " bundle.zip"), paste0(bundle, ".sha256"))
  digest_available <- !is.null(qp_sha256_file(bundle))
  skip_if_not(digest_available,
              "neither tools::sha256sum() (R >= 4.5) nor digest is available")
  expect_identical(qp_detect_bridge_artifact(bundle)$reason_code,
                   "bridge_artifact_checksum_mismatch")
  writeLines(paste0(qp_sha256_file(bundle), "  bundle.zip"),
             paste0(bundle, ".sha256"))
  verified <- qp_detect_bridge_artifact(bundle)
  expect_true(verified$ok)
  expect_true(verified$details$checksum_verified)
})

test_that("sha256 of a file uses tools, then digest, then reports none", {
  path <- withr::local_tempfile(fileext = ".txt")
  writeBin(charToRaw("abc"), path)
  # FIPS 180-2 test vector for "abc".
  abc <- "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  no_tools <- new.env(parent = emptyenv())
  if (exists("sha256sum", envir = asNamespace("tools"), inherits = FALSE)) {
    expect_identical(qp_sha256_file(path), abc)
  }
  expect_null(qp_sha256_file(path, tools_ns = no_tools, has_digest = FALSE))
  skip_if_not_installed("digest")
  expect_identical(qp_sha256_file(path, tools_ns = no_tools, has_digest = TRUE),
                   abc)
})

test_that("an unverifiable checksum sidecar is a recorded prerequisite", {
  skip_if_not_installed("zip")
  dir <- withr::local_tempdir("qf-bridge-")
  writeLines("{}", file.path(dir, "manifest.json"))
  bundle <- file.path(dir, "bundle.zip")
  zip::zip(bundle, "manifest.json", root = dir)
  writeLines(paste(strrep("0", 64), " bundle.zip"), paste0(bundle, ".sha256"))
  missing <- qp_detect_bridge_artifact(bundle, sha256 = function(path) NULL)
  expect_false(missing$ok)
  expect_identical(missing$reason_code, "bridge_artifact_checksum_unverifiable")
})

test_that("display detection never accepts the desktop display", {
  skip_on_os("windows")
  sockets <- withr::local_tempdir("qf-x11-")
  detect <- function(display, desktop = "") {
    qp_detect_display(display, desktop = desktop, socket_dir = sockets)
  }
  expect_identical(detect("")$reason_code, "display_not_configured")
  expect_identical(detect("localhost:10.0")$reason_code, "display_invalid")
  expect_identical(detect(":0")$reason_code, "display_refused")
  expect_identical(detect(":0.0")$reason_code, "display_refused")
  expect_identical(detect(":1", desktop = ":1")$reason_code,
                   "display_refused")
  expect_identical(detect(":90")$reason_code, "display_not_running")
  file.create(file.path(sockets, "X90"))
  found <- detect(":90", desktop = ":0")
  expect_true(found$ok)
  expect_identical(found$value, ":90")
})

test_that("browser detection needs chromote and an explicit binary", {
  skip_on_os("windows")
  expect_identical(
    qp_detect_browser("", packages = absent_package)$reason_code,
    "browser_package_missing"
  )
  expect_identical(qp_detect_browser("", packages = "stats")$reason_code,
                   "browser_not_configured")
  dir <- withr::local_tempdir("qf-browser-")
  plain <- file.path(dir, "chrome-plain")
  file.create(plain)
  expect_identical(qp_detect_browser(plain, packages = "stats")$reason_code,
                   "browser_not_found")
  binary <- write_script(file.path(dir, "chrome-headless-shell"), "exit 0")
  found <- qp_detect_browser(binary, packages = "stats")
  expect_true(found$ok)
  expect_identical(found$value, normalizePath(binary))
})

test_that("Python profile detection needs lock files and an interpreter", {
  skip_on_os("windows")
  expect_identical(qp_detect_python_profile("")$reason_code,
                   "python_profile_not_configured")
  profile <- withr::local_tempdir("qf-python-")
  expect_identical(
    qp_detect_python_profile(file.path(profile, "absent"))$reason_code,
    "python_profile_not_found"
  )
  writeLines("[project]", file.path(profile, "pyproject.toml"))
  partial <- qp_detect_python_profile(profile)
  expect_identical(partial$reason_code, "python_profile_incomplete")
  expect_identical(partial$details$missing, "uv.lock")
  writeLines("version = 1", file.path(profile, "uv.lock"))
  expect_identical(qp_detect_python_profile(profile)$details$missing,
                   ".venv interpreter")
  write_script(file.path(profile, ".venv", "bin", "python"), "exit 0")
  found <- qp_detect_python_profile(profile)
  expect_true(found$ok)
  expect_identical(found$value, normalizePath(profile))
})

test_that("skip records are JSON lines with the reason and the test", {
  log <- withr::local_tempfile(fileext = ".jsonl")
  qp_record_skip("skip_if_no_java", "java_not_found", "No runtime",
                 list(java = "/opt/none/bin/java"), log = log)
  qp_record_skip("skip_if_no_display", "display_not_configured", "Unset",
                 log = log)
  lines <- readLines(log)
  expect_length(lines, 2L)
  first <- jsonlite::fromJSON(lines[[1L]], simplifyVector = FALSE)
  expect_identical(first$record_type, "test_skip")
  expect_identical(first$helper, "skip_if_no_java")
  expect_identical(first$reason_code, "java_not_found")
  expect_identical(first$reason, "No runtime")
  expect_identical(first$details, list(java = "/opt/none/bin/java"))
  expect_identical(first$test,
                   "skip records are JSON lines with the reason and the test")
  expect_match(first$time, "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$")
  second <- jsonlite::fromJSON(lines[[2L]], simplifyVector = FALSE)
  expect_identical(second$details, structure(list(), names = character()))
})

test_that("skip helpers skip, log and return the detected value", {
  skip_on_os("windows")
  log <- withr::local_tempfile(fileext = ".jsonl")
  home <- withr::local_tempdir("qf-qupath-")
  dir.create(file.path(home, "lib", "app"), recursive = TRUE)
  file.create(file.path(home, "lib", "app", "qupath-core-0.7.0.jar"))
  withr::local_envvar(QUPFLOWR_SKIP_LOG = log, QUPFLOWR_QUPATH_HOME = home,
                      QUPFLOWR_TEST_PROFILE = "")
  expect_identical(skip_if_no_qupath(), normalizePath(home))
  expect_false(file.exists(log))
  withr::local_envvar(QUPFLOWR_TEST_DISPLAY = "")
  expect_condition(skip_if_no_display(), class = "skip")
  withr::local_envvar(QUPFLOWR_TEST_PROFILE = "core")
  expect_condition(skip_if_no_qupath(), class = "skip")
  records <- lapply(readLines(log), jsonlite::fromJSON)
  expect_identical(vapply(records, function(r) r$helper, character(1)),
                   c("skip_if_no_display", "skip_if_no_qupath"))
  expect_identical(vapply(records, function(r) r$reason_code, character(1)),
                   c("display_not_configured", "forced_by_profile"))
})
