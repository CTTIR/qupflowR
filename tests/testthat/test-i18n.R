# Message catalogue coverage and qp_msg() behaviour.

catalog_path <- function() {
  system.file("i18n", "messages.json", package = "qupflowR")
}

read_catalog <- function() {
  jsonlite::read_json(catalog_path(), simplifyVector = FALSE)
}

placeholders <- function(template) {
  pattern <- "\\{[A-Za-z][A-Za-z0-9_]*\\}"
  found <- regmatches(template, gregexpr(pattern, template))[[1]]
  sort(unique(found))
}

test_that("the catalogue is valid JSON without duplicate keys", {
  bytes <- readBin(catalog_path(), "raw", file.size(catalog_path()))
  expect_true(validUTF8(rawToChar(bytes)))
  decoded <- qp_json_decode(bytes)
  expect_identical(anyDuplicated(names(decoded$messages)), 0L)
  catalog <- read_catalog()
  expect_identical(unlist(catalog$languages), c("de", "en"))
  expect_identical(catalog$default_language, "de")
  expect_identical(catalog$fallback_language, "en")
  key_pattern <- "^[a-z][a-z0-9_]*([.][a-z0-9_]+)+$"
  expect_true(all(grepl(key_pattern, names(catalog$messages))))
})

test_that("every entry has German and English text with equal placeholders", {
  messages <- read_catalog()$messages
  for (key in names(messages)) {
    entry <- messages[[key]]
    expect_identical(sort(names(entry)), c("de", "en"), info = key)
    expect_true(is.character(entry$de) && nzchar(entry$de), info = key)
    expect_true(is.character(entry$en) && nzchar(entry$en), info = key)
    expect_identical(placeholders(entry$de), placeholders(entry$en),
                     info = key)
  }
})

test_that("every registry message key exists in both languages", {
  registry <- jsonlite::read_json(
    system.file("schema", "protocol", "v1", "error-codes.json",
                package = "qupflowR")
  )
  messages <- read_catalog()$messages
  keys <- vapply(registry, function(e) e$message_key, "")
  expect_identical(setdiff(keys, names(messages)), character())
  for (entry in registry) {
    text <- messages[[entry$message_key]]
    expect_true(nzchar(text$de) && nzchar(text$en), info = entry$code)
    if (!is.null(entry$http_status)) {
      # The bridge sends no interpolation values, so protocol messages have
      # no placeholders.
      expect_identical(placeholders(text$de), character(), info = entry$code)
    }
  }
})

test_that("keys used by the R core exist in the catalogue", {
  expect_keys <- c("character", "error_code", "flag", "json_text", "list",
                   "message_key", "named_values", "numeric",
                   "positive_count", "response_envelope", "scalar",
                   "schema", "string", "value_state")
  validation_keys <- c(
    "additional_properties", "any_of", "const", "enum", "exclusive_maximum",
    "exclusive_minimum", "false_schema", "format", "max_items", "max_length",
    "maximum", "min_items", "min_length", "minimum", "not", "one_of",
    "pattern", "required", "type", "unique_items", "valid", "invalid"
  )
  states <- c("finite", "missing", "nan", "pos_inf", "neg_inf")
  used <- c(
    "error.unknown_code", "warning.unknown_code", "condition.action",
    "condition.host_message", "condition.request_id",
    "condition.validation_error", "condition.more_errors",
    paste0("expect.", expect_keys),
    paste0("validation.", validation_keys),
    "app.title", "language.de", "language.en",
    paste0("value_state.", states)
  )
  expect_identical(setdiff(used, names(read_catalog()$messages)),
                   character())
})

test_that("qp_msg() uses the language option, .lang and the fallback", {
  closing_de <- "Die Bridge wird gerade beendet."
  closing_en <- "The bridge is shutting down."
  withr::local_options(qupflowR.language = "de")
  expect_identical(qp_msg("error.host_closing"), closing_de)
  expect_identical(qp_msg("error.host_closing", .lang = "en"), closing_en)
  expect_identical(qp_msg("error.host_closing", .lang = "de_AT"), closing_de)
  expect_identical(qp_msg("error.host_closing", .lang = "fr"), closing_en)
  expect_identical(qp_msg("error.host_closing", .lang = NA), closing_en)
  withr::local_options(qupflowR.language = NULL)
  expect_identical(qp_msg("language.en"), "Englisch")
})

test_that("a key missing in a language falls back to English, else itself", {
  catalog <- core_catalog()
  withr::defer(core_cache$catalog <- catalog)
  patched <- catalog
  patched$messages[["test.only_english"]] <- list(en = "Only English")
  core_cache$catalog <- patched
  expect_identical(qp_msg("test.only_english", .lang = "de"), "Only English")
  expect_identical(qp_msg("test.not_in_catalogue", .lang = "de"),
                   "test.not_in_catalogue")
})

test_that("qp_msg() inserts values literally and marks missing ones", {
  withr::local_options(qupflowR.language = "en")
  withr::local_envvar(QP_TEST_MARKER = "")
  payload <- "{Sys.setenv(QP_TEST_MARKER = 'hit')}"
  expect_identical(qp_msg("condition.host_message", message = payload),
                   paste("Bridge message:", payload))
  expect_identical(Sys.getenv("QP_TEST_MARKER"), "")
  expect_identical(qp_msg("condition.request_id"),
                   "Request ID: <request_id>")
  expect_identical(
    qp_msg("error.invalid_argument", arg = "image_id",
           expected = "a single string"),
    "Argument `image_id` must be a single string."
  )
  expect_identical(qp_msg("validation.invalid", schema = "x", n = 1),
                   "Invalid against x: 1 error.")
  expect_identical(qp_msg("validation.invalid", schema = "x", n = 3),
                   "Invalid against x: 3 errors.")
})

test_that("qp_msg() returns plain text without ANSI styling", {
  withr::local_options(cli.num_colors = 256L, qupflowR.language = "en")
  text <- qp_msg("error.invalid_argument", arg = "x", expected = "y")
  expect_false(grepl("\033", text, fixed = TRUE))
})

test_that("every template renders in both languages", {
  messages <- read_catalog()$messages
  for (key in names(messages)) {
    found <- placeholders(messages[[key]]$en)
    wanted <- substr(found, 2L, nchar(found) - 1L)
    values <- stats::setNames(as.list(rep("v", length(wanted))), wanted)
    values[names(values) == "n"] <- list(2L)
    for (lang in c("de", "en")) {
      text <- do.call(qp_msg, c(list(key), values, list(.lang = lang)))
      expect_true(is.character(text) && length(text) == 1L, info = key)
      expect_true(validUTF8(text), info = key)
      expect_false(grepl("<[a-z_]+>", text), info = paste(key, lang))
    }
  }
})

test_that("German texts keep their umlauts", {
  expect_identical(
    qp_msg("error.host_busy", .lang = "de"),
    paste("Die Bridge ist ausgelastet; die Anfrage kann",
          "sp\u00e4ter wiederholt werden.")
  )
})

test_that("qp_msg() validates its arguments", {
  code_of <- function(expr) tryCatch(expr, error = function(e) e$code)
  expect_identical(code_of(qp_msg("Bad Key")), "INVALID_ARGUMENT")
  expect_identical(code_of(qp_msg(1)), "INVALID_ARGUMENT")
  expect_identical(code_of(qp_msg("error.host_busy", "x")),
                   "INVALID_ARGUMENT")
})

test_that("NULL values and entries without text are handled", {
  expect_identical(qp_msg("condition.action", action = NULL, .lang = "en"),
                   "Suggested action: NULL")
  catalog <- core_catalog()
  withr::defer(core_cache$catalog <- catalog)
  patched <- catalog
  patched$messages[["test.no_text"]] <- list(de = 1L)
  core_cache$catalog <- patched
  expect_identical(qp_msg("test.no_text"), "test.no_text")
})
