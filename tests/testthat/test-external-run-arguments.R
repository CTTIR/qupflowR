# Argument errors of qp_run_external() on every platform. The platform gate is
# mocked so validation runs, but every case fails before a process starts.

local_linux_profile <- function(env = parent.frame()) {
  skip_if_not_installed("digest")
  skip_if_not_installed("processx")
  skip_if_not_installed("fs")
  local_mocked_bindings(ext_system = function() "Linux", .env = env)
}

pinned_file <- function() {
  path <- withr::local_tempfile(.local_envir = parent.frame())
  writeLines("not executed", path)
  normalizePath(path)
}

test_that("argument errors name qp_run_external() as the call", {
  local_linux_profile()
  workspace <- withr::local_tempfile()
  cnd <- tryCatch(
    qp_run_external("/nonexistent/program", character(), data.frame(),
                    "result.txt", workspace, trust = TRUE),
    qp_error = function(e) e
  )
  expect_s3_class(cnd, "qp_validation_error")
  expect_identical(cnd$code, "INVALID_ARGUMENT")
  expect_identical(cnd$details$argument, "program")
  expect_identical(cnd$call[[1]], quote(qp_run_external))
  expect_false(dir.exists(workspace))
})

test_that("pin mismatches name qp_run_external() as the call", {
  local_linux_profile()
  program <- pinned_file()
  workspace <- withr::local_tempfile()
  cnd <- tryCatch(
    qp_run_external(program, character(),
                    data.frame(path = program, sha256 = strrep("0", 64)),
                    "result.txt", workspace, trust = TRUE),
    qp_error = function(e) e
  )
  expect_s3_class(cnd, "qp_reproduction_error")
  expect_identical(cnd$code, "INPUT_MISMATCH")
  expect_identical(cnd$call[[1]], quote(qp_run_external))
  expect_false(dir.exists(workspace))
})

test_that("argument errors use the message catalogue language", {
  local_linux_profile()
  program <- pinned_file()
  pins <- data.frame(path = program,
                     sha256 = digest::digest(file = program, algo = "sha256"))
  message_for <- function(lang, ...) {
    withr::local_options(qupflowR.language = lang)
    tryCatch(qp_run_external(program, ..., trust = TRUE),
             qp_validation_error = conditionMessage)
  }
  de <- message_for("de", character(), pins, "../escape",
                    withr::local_tempfile())
  en <- message_for("en", character(), pins, "../escape",
                    withr::local_tempfile())
  expect_match(de, qp_msg("expect.external_outputs", .lang = "de"),
               fixed = TRUE)
  expect_no_match(de, "without traversal", fixed = TRUE)
  expect_match(en, "distinct relative paths without traversal", fixed = TRUE)
  expect_match(message_for("de", character(), pins, "result.txt",
                           withr::local_tempfile(), timeout = -1),
               qp_msg("expect.external_timeout", .lang = "de"), fixed = TRUE)
})

test_that("every external-run expectation exists in both languages", {
  source_file <- test_path("..", "..", "R", "external-run.R")
  skip_if_not(file.exists(source_file), "package sources are not available")
  code <- readLines(source_file)
  used <- regmatches(code, regexpr('ext_invalid\\("[a-z]+", "[a-z_]+"', code))
  keys <- paste0("expect.external_", unique(sub('.*"([a-z_]+)"$', "\\1", used)))
  expect_gt(length(keys), 5L)
  for (key in keys) {
    expect_false(identical(qp_msg(key, .lang = "de"), key), info = key)
    expect_false(identical(qp_msg(key, .lang = "en"), key), info = key)
    expect_false(identical(qp_msg(key, .lang = "de"),
                           qp_msg(key, .lang = "en")), info = key)
  }
})
