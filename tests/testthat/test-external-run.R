skip_on_os(c("windows", "mac"))

external_fixture <- function(lines) {
  root <- tempfile("external-")
  dir.create(root)
  script <- file.path(root, "method with spaces.R")
  writeLines(lines, script)
  program <- normalizePath(file.path(R.home("bin"), "Rscript"))
  pins <- data.frame(path = c(program, script), sha256 = vapply(
    c(program, script), function(p) digest::digest(file = p, algo = "sha256"),
    character(1),
    USE.NAMES = FALSE
  ))
  list(
    program = program, args = c("--vanilla", script, "{{workspace}}"),
    pins = pins, outputs = "result.txt", workspace = file.path(root, "run"),
    trust = TRUE
  )
}

test_that("external methods need explicit trust before process creation", {
  spec <- external_fixture("quit(status=99)")
  spec$trust <- FALSE
  expect_error(do.call(qp_run_external, spec), class = "qp_bundle_error")
  expect_false(dir.exists(spec$workspace))
})

test_that("pinned methods preserve arguments and isolate the environment", {
  withr::local_envvar(c(
    DISPLAY = ":0", WAYLAND_DISPLAY = "private-display",
    QP_UNDECLARED_SECRET = "never-inherit"
  ))
  spec <- external_fixture(c(
    "a <- commandArgs(TRUE)",
    "writeLines(c(a[2], Sys.getenv('QP_DECLARED'), Sys.getenv('HOME'),",
    " Sys.getenv('DISPLAY'), Sys.getenv('WAYLAND_DISPLAY'),",
    " Sys.getenv('QP_UNDECLARED_SECRET')), file.path(a[1], 'result.txt'))"
  ))
  spec$args <- c(spec$args, "a; touch no-shell $HOME `echo no`")
  spec$env <- c(QP_DECLARED = "literal")
  record <- do.call(qp_run_external, spec)
  result <- readLines(file.path(spec$workspace, "result.txt"))
  expect_identical(
    result[c(1, 2, 4, 5, 6)],
    c(spec$args[4], "literal", "", "", "")
  )
  expect_false(dir.exists(result[3]))
  expect_identical(record$status, "execution_complete")
  expect_identical(record$environment$HOME, result[3])
  expect_identical(record$environment$PATH, Sys.getenv("PATH"))
  expect_match(record$environment$JAVA_TOOL_OPTIONS, "java.awt.headless=true",
               fixed = TRUE)
  expect_equal(record$outputs$sha256, digest::digest(
    file = file.path(spec$workspace, "result.txt"), algo = "sha256"
  ))
  receipt <- jsonlite::read_json(file.path(spec$workspace, "run.json"))
  expect_identical(receipt$status, record$status)
  expect_error(do.call(qp_run_external, spec), class = "qp_validation_error")
})

test_that("pin mismatch is rejected before execution or workspace creation", {
  spec <- external_fixture("quit(status=99)")
  spec$pins$sha256[2] <- strrep("0", 64)
  expect_error(do.call(qp_run_external, spec), class = "qp_reproduction_error")
  expect_false(dir.exists(spec$workspace))
})

test_that("nonzero exits and zero-exit missing outputs remain failed", {
  for (code in c(
    "quit(status=7)", "invisible(NULL)",
    "file.create(file.path(commandArgs(TRUE)[1], 'result.txt'))"
  )) {
    spec <- external_fixture(code)
    expect_error(do.call(qp_run_external, spec), class = "qp_runtime_error")
    receipt <- jsonlite::read_json(file.path(spec$workspace, "run.json"))
    expect_false(identical(receipt$status, "execution_complete"))
  }
})

test_that("timeout terminates only the bounded process and records failure", {
  spec <- external_fixture("Sys.sleep(30)")
  spec$timeout <- 0.2
  expect_error(do.call(qp_run_external, spec), class = "qp_runtime_error")
  receipt <- jsonlite::read_json(file.path(spec$workspace, "run.json"))
  expect_identical(receipt$status, "timeout")
})

test_that("mutated pinned sources fail even after successful process exit", {
  spec <- external_fixture(c(
    "a <- commandArgs(TRUE)",
    "writeLines('out', file.path(a[1], 'result.txt'))",
    "writeLines('changed', a[2])"
  ))
  spec$args <- c(spec$args, spec$pins$path[2])
  expect_error(do.call(qp_run_external, spec), class = "qp_reproduction_error")
  receipt <- jsonlite::read_json(file.path(spec$workspace, "run.json"))
  expect_identical(receipt$status, "validation_failed")
})

test_that("unsafe outputs and isolation overrides are rejected", {
  for (path in c(
    "../outside", "/absolute", "a/../../outside", "a\\outside",
    "C:/absolute", "run.json", "stdout.log", "stderr.log"
  )) {
    spec <- external_fixture("quit(status=99)")
    spec$outputs <- path
    expect_error(do.call(qp_run_external, spec), class = "qp_validation_error")
    expect_false(dir.exists(spec$workspace))
  }
  for (key in c("HOME", "DISPLAY", "JAVA_TOOL_OPTIONS", "XDG_DATA_HOME")) {
    spec <- external_fixture("quit(status=99)")
    spec$env <- stats::setNames("unsafe", key)
    expect_error(do.call(qp_run_external, spec), class = "qp_validation_error")
  }
})

test_that("symlink outputs cannot escape the new workspace", {
  skip_on_os("windows")
  spec <- external_fixture(c(
    "a <- commandArgs(TRUE)",
    "file.symlink(a[2], file.path(a[1], 'result.txt'))"
  ))
  spec$args <- c(spec$args, spec$pins$path[2])
  expect_error(do.call(qp_run_external, spec), class = "qp_runtime_error")
})

test_that("malformed executable pins vectors and time limits fail closed", {
  spec <- external_fixture("quit(status=99)")
  bad <- list(
    program = "missing", args = NA_character_, pins = data.frame(),
    outputs = character(), workspace = dirname(spec$workspace),
    timeout = Inf, env = c(UNNAMED = NA_character_)
  )
  for (key in names(bad)) {
    candidate <- spec
    candidate[[key]] <- bad[[key]]
    expect_error(do.call(qp_run_external, candidate),
                 class = "qp_validation_error")
  }
  spec$pins <- spec$pins[2, , drop = FALSE]
  expect_error(do.call(qp_run_external, spec), class = "qp_validation_error")
})

test_that("spawn failures retain a failed run receipt", {
  skip_on_os("windows")
  spec <- external_fixture("not an executable")
  spec$program <- spec$pins$path[2]
  expect_error(do.call(qp_run_external, spec), class = "qp_runtime_error")
  receipt <- jsonlite::read_json(file.path(spec$workspace, "run.json"))
  expect_identical(receipt$status, "process_error")
})

test_that("workspace creation races cannot overwrite existing work", {
  spec <- external_fixture("quit(status=99)")
  validate <- qupflowR:::ext_validate
  local_mocked_bindings(ext_validate = function(...) {
    result <- validate(...)
    dir.create(result$workspace)
    result
  })
  expect_error(do.call(qp_run_external, spec), class = "qp_validation_error")
})

test_that("failed atomic receipt publication cannot report success", {
  root <- tempfile("receipt-")
  dir.create(root)
  dir.create(file.path(root, "run.json"))
  expect_error(qupflowR:::ext_receipt(list(workspace = root)),
               class = "qp_runtime_error")
})


test_that("unqualified platforms fail before workspace creation", {
  spec <- external_fixture("quit(status=99)")
  local_mocked_bindings(ext_system = function() "Windows")
  expect_error(do.call(qp_run_external, spec), class = "qp_runtime_error")
  expect_false(dir.exists(spec$workspace))
})

test_that("private directory failures are classified before process start", {
  path <- tempfile("not-a-directory-")
  writeLines("preserve", path)
  expect_error(qupflowR:::ext_mkdir(path), class = "qp_runtime_error")
  expect_identical(readLines(path), "preserve")
})

test_that("Java properties quote spaces and embedded quotes without a shell", {
  expect_identical(qupflowR:::ext_java_quote("a b"), '\"a b\"')
  expect_identical(qupflowR:::ext_java_quote('a"b'), '\"a\"\'\"\'\"b\"')
  root <- tempfile("home with spaces ")
  dir.create(root)
  env <- qupflowR:::ext_environment(root, character())
  expect_match(env[["JAVA_TOOL_OPTIONS"]], paste0('"', root, '/home"'),
               fixed = TRUE)
})
