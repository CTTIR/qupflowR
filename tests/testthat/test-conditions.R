# Classified conditions, the error registry and redaction.

registry_file <- function() {
  system.file("schema", "protocol", "v1", "error-codes.json",
              package = "qupflowR")
}

catch_error <- function(expr) {
  tryCatch(expr, error = function(e) e)
}

request_uuid <- "0d9a3c5e-8f7b-4a21-9c3d-2e1f0a9b8c7d"

test_that("the registry holds the codes of docs/06, SP01b, docs/15, docs/18", {
  # Typed from the contracts, independent of the registry file.
  docs06 <- c(
    "VALIDATION_FAILED", "INVALID_PLANE", "INVALID_GEOMETRY",
    "AUTH_REQUIRED", "SCOPE_DENIED", "SESSION_NOT_FOUND", "OBJECT_NOT_FOUND",
    "REVISION_CONFLICT", "PROJECT_BUSY", "IDEMPOTENCY_CONFLICT",
    "SNAPSHOT_EXPIRED", "PAYLOAD_TOO_LARGE", "CALIBRATION_MISSING",
    "IMAGE_UNAVAILABLE", "CAPABILITY_UNAVAILABLE", "RECOVERY_REQUIRED",
    "HOST_CLOSING", "INTERNAL_ERROR", "PROTOCOL_MISMATCH"
  )
  sp01b <- c(
    "MALFORMED_REQUEST", "ENDPOINT_NOT_FOUND", "METHOD_NOT_ALLOWED",
    "REQUEST_TIMEOUT", "LENGTH_REQUIRED", "REQUEST_LINE_TOO_LONG",
    "EXPECTATION_FAILED", "HEADERS_TOO_LARGE", "HTTP_VERSION_NOT_SUPPORTED",
    "HOST_BUSY"
  )
  docs15 <- c(
    "BUNDLE_INVALID", "BUNDLE_INTEGRITY_FAILED", "BUNDLE_VERSION_UNSUPPORTED",
    "EXECUTOR_UNAVAILABLE", "INPUT_BINDING_REQUIRED", "INPUT_MISMATCH",
    "RUNTIME_UNAVAILABLE", "DEPENDENCY_UNRESOLVED", "LICENSE_ASSET_REQUIRED",
    "OUTPUT_INVALID", "REPRODUCTION_MISMATCH", "TRUST_REQUIRED"
  )
  docs18 <- c(
    "SERIALIZATION_TRUST_REQUIRED", "SERIALIZATION_UNSUPPORTED",
    "R_OBJECT_INVALID", "RESOURCE_MISSING", "RESOURCE_CHANGED",
    "EMBED_LIMIT_EXCEEDED", "STORE_UNAVAILABLE", "MIGRATION_REQUIRED",
    "MIGRATION_UNSUPPORTED"
  )
  codes <- qp_error_codes()
  expect_true(all(c(docs06, sp01b, docs15, docs18) %in% codes$code))
  expect_identical(codes$source[match(sp01b, codes$code)],
                   rep("SP01b", length(sp01b)))
  local_only <- codes$source %in% c("docs/15", "docs/18", "D022")
  expect_true(all(is.na(codes$http_status[local_only])))
})

test_that("qp_error_codes() mirrors the registry file", {
  raw <- jsonlite::read_json(registry_file(), simplifyVector = FALSE)
  codes <- qp_error_codes()
  expect_s3_class(codes, "tbl_df")
  expect_identical(
    names(codes),
    c("code", "http_status", "r_class", "retryable", "message_key", "source")
  )
  expect_identical(nrow(codes), length(raw))
  expect_type(codes$http_status, "integer")
  expect_type(codes$retryable, "logical")
  expect_identical(codes$code, vapply(raw, function(e) e$code, ""))
  expect_identical(anyDuplicated(codes$code), 0L)
})

test_that("protocol codes map to their HTTP status, class and retry flag", {
  # Hand table from docs/06 and SP01b.
  expected <- data.frame(
    code = c("HOST_BUSY", "REQUEST_TIMEOUT", "REVISION_CONFLICT",
             "AUTH_REQUIRED", "CAPABILITY_UNAVAILABLE", "RECOVERY_REQUIRED",
             "PAYLOAD_TOO_LARGE", "METHOD_NOT_ALLOWED", "SNAPSHOT_EXPIRED"),
    status = c(503L, 408L, 409L, 401L, 501L, 503L, 413L, 405L, 410L),
    class = c("qp_busy_error", "qp_connection_error", "qp_conflict_error",
              "qp_connection_error", "qp_capability_error",
              "qp_recovery_error", "qp_validation_error", "qp_version_error",
              "qp_conflict_error"),
    retryable = c(TRUE, TRUE, FALSE, FALSE, FALSE, FALSE, FALSE, FALSE,
                  FALSE),
    stringsAsFactors = FALSE
  )
  for (i in seq_len(nrow(expected))) {
    code <- expected$code[[i]]
    cnd <- catch_error(qp_abort(code))
    expect_identical(class(cnd)[1:2], c(expected$class[[i]], "qp_error"))
    expect_identical(cnd$code, code)
    expect_identical(cnd$http_status, expected$status[[i]])
    expect_identical(cnd$retryable, expected$retryable[[i]])
    expect_identical(cnd$message_key, paste0("error.", tolower(code)))
  }
})

test_that("every registry code builds a condition with its registry class", {
  raw <- jsonlite::read_json(registry_file(), simplifyVector = FALSE)
  for (entry in raw) {
    cnd <- catch_error(qp_abort(entry$code))
    expect_s3_class(cnd, entry$r_class)
    expect_s3_class(cnd, "qp_error")
    expect_identical(qp_condition_code(cnd), entry$code)
  }
})

test_that("messages follow the language option", {
  withr::local_options(qupflowR.language = "de")
  de <- conditionMessage(catch_error(qp_abort("HOST_BUSY")))
  expect_match(de, "Die Bridge ist ausgelastet", fixed = TRUE)
  withr::local_options(qupflowR.language = "en")
  en <- conditionMessage(catch_error(qp_abort("HOST_BUSY")))
  expect_match(en, "The bridge is busy; the request can be retried later.",
               fixed = TRUE)
})

test_that("qp_abort() fills fields, extra classes and bullets", {
  withr::local_options(qupflowR.language = "en")
  cnd <- catch_error(qp_abort(
    "REVISION_CONFLICT",
    class = "qp_patch_conflict",
    details = list(expected_revision = "rev-7", actual_revision = "rev-8"),
    request_id = request_uuid,
    action = "create_new_diff",
    bullets = c(x = "literal {not_a_variable}")
  ))
  expect_identical(class(cnd)[1:3],
                   c("qp_patch_conflict", "qp_conflict_error", "qp_error"))
  expect_identical(cnd$request_id, request_uuid)
  expect_identical(cnd$action, "create_new_diff")
  expect_identical(cnd$details,
                   list(expected_revision = "rev-7", actual_revision = "rev-8"))
  msg <- conditionMessage(cnd)
  expect_match(msg, "The project has changed in the meantime.", fixed = TRUE)
  expect_match(msg, "literal {not_a_variable}", fixed = TRUE)
  expect_match(msg, paste("Request ID:", request_uuid), fixed = TRUE)
  expect_match(msg, "Suggested action: create_new_diff", fixed = TRUE)
})

test_that("template values are inserted literally and never evaluated", {
  withr::local_options(qupflowR.language = "en")
  withr::local_envvar(QP_TEST_MARKER = "")
  payload <- "{Sys.setenv(QP_TEST_MARKER = 'hit')}"
  cnd <- catch_error(
    qp_abort("INVALID_ARGUMENT", arg = "x", expected = payload)
  )
  expect_match(conditionMessage(cnd),
               paste0("Argument `x` must be ", payload, "."), fixed = TRUE)
  expect_identical(Sys.getenv("QP_TEST_MARKER"), "")
})

test_that("unknown codes keep their code with the class qp_error only", {
  withr::local_options(qupflowR.language = "en")
  cnd <- catch_error(qp_abort("QUPATH_EXPLODED"))
  expect_identical(class(cnd)[[1]], "qp_error")
  expect_false(any(grepl("^qp_.*_error$", class(cnd))))
  expect_identical(cnd$code, "QUPATH_EXPLODED")
  expect_identical(cnd$retryable, NA)
  expect_identical(cnd$http_status, NA_integer_)
  expect_match(conditionMessage(cnd), "unknown error (QUPATH_EXPLODED)",
               fixed = TRUE)
})

test_that("qp_abort() reports the calling function", {
  failing_helper <- function() qp_abort("INTERNAL_ERROR")
  cnd <- catch_error(failing_helper())
  expect_identical(deparse(cnd$call), "failing_helper()")
})

test_that("invalid arguments to qp_abort() are classified", {
  bad <- list(
    catch_error(qp_abort("lower_case")),
    catch_error(qp_abort(c("A", "B"))),
    catch_error(qp_abort(NA_character_)),
    catch_error(qp_abort("HOST_BUSY", "unnamed value")),
    catch_error(qp_abort("HOST_BUSY", details = "not a list")),
    catch_error(qp_abort("HOST_BUSY", request_id = 1)),
    catch_error(qp_abort("HOST_BUSY", message_key = "Not A Key")),
    catch_error(qp_abort("HOST_BUSY", bullets = NA_character_))
  )
  for (cnd in bad) {
    expect_s3_class(cnd, "qp_validation_error")
    expect_identical(cnd$code, "INVALID_ARGUMENT")
  }
  expect_identical(bad[[1]]$details$argument, "code")
  expect_identical(bad[[4]]$details$argument, "...")
  expect_identical(bad[[5]]$details$argument, "details")
})

test_that("message_key overrides the registry message but keeps the class", {
  withr::local_options(qupflowR.language = "en")
  cnd <- catch_error(qp_abort("VALIDATION_FAILED",
                              message_key = "validation.invalid",
                              schema = "health.schema.json", n = 2))
  expect_s3_class(cnd, "qp_validation_error")
  expect_identical(cnd$message_key, "validation.invalid")
  expect_match(conditionMessage(cnd),
               "Invalid against health.schema.json: 2 errors.", fixed = TRUE)
})

test_that("qp_warn() signals classified warnings", {
  withr::local_options(qupflowR.language = "en")
  w <- tryCatch(
    qp_warn("SNAPSHOT_NEAR_EXPIRY",
            details = list(password = "hunter2", remaining_ms = 10L),
            bullets = c(i = "note")),
    warning = function(w) w
  )
  expect_s3_class(w, "qp_warning")
  expect_identical(w$code, "SNAPSHOT_NEAR_EXPIRY")
  expect_identical(w$message_key, "warning.snapshot_near_expiry")
  expect_identical(w$details,
                   list(password = "<redacted>", remaining_ms = 10L))
  expect_match(conditionMessage(w), "warning.snapshot_near_expiry",
               fixed = TRUE)
  expect_match(conditionMessage(w), "note", fixed = TRUE)
  expect_warning(qp_warn("X_WARNING", message_key = "warning.unknown_code"),
                 class = "qp_warning")
  w2 <- tryCatch(qp_warn("X_WARNING", message_key = "warning.unknown_code"),
                 warning = function(w) w)
  expect_match(conditionMessage(w2),
               "The bridge reports a warning with code X_WARNING.",
               fixed = TRUE)
})

test_that("qp_condition_code() returns NA for foreign conditions", {
  expect_identical(qp_condition_code(simpleCondition("x")), NA_character_)
  expect_identical(qp_condition_code(simpleError("x")), NA_character_)
  fake <- structure(list(message = "x", call = NULL, code = 1),
                    class = c("qp_error", "error", "condition"))
  expect_identical(qp_condition_code(fake), NA_character_)
})

test_that("qp_redact() removes credentials by name and by value", {
  token <- "Q2hhbmdlTWUtMzItYnl0ZS10b2tlbi1mb3ItdGVzdHM"
  expect_identical(nchar(token), 43L)
  tile_url <- "http://127.0.0.1:1234/v1/tiles/1?token=xyz&level=2"
  out <- qp_redact(list(
    token = "abc",
    Authorization = "Bearer abc",
    api_key = "k",
    client_secret = "s",
    token_file = "/run/user/1000/qupflowr/x.token",
    header = "Authorization: Bearer abc.def-ghi",
    url = tile_url,
    bare = token,
    nested = list(password = "p", count = 3L),
    keep = c("a", NA)
  ))
  expect_identical(out, list(
    token = "<redacted>",
    Authorization = "<redacted>",
    api_key = "<redacted>",
    client_secret = "<redacted>",
    token_file = "/run/user/1000/qupflowr/x.token",
    header = "Authorization: Bearer <redacted>",
    url = "http://127.0.0.1:1234/v1/tiles/1?token=<redacted>&level=2",
    bare = "<redacted>",
    nested = list(password = "<redacted>", count = 3L),
    keep = c("a", NA)
  ))
})

test_that("qp_redact() bounds strings, vectors, depth and non-data objects", {
  expect_identical(qp_redact(strrep("x", 30), max_chars = 10L),
                   "xxxxxxxxxx... [20 characters truncated]")
  v <- qp_redact(1:10, max_items = 3L)
  expect_identical(as.vector(v), 1:3)
  expect_identical(attr(v, "truncated_from"), 10L)
  l <- qp_redact(as.list(letters), max_items = 2L)
  expect_identical(unclass(l)[1:2], list("a", "b"))
  expect_identical(attr(l, "truncated_from"), 26L)
  deep <- qp_redact(list(a = list(b = list(c = 1))), max_depth = 2L)
  expect_identical(
    deep,
    list(a = list(b = "<list of 1 elements, nested too deeply>"))
  )
  expect_identical(qp_redact(as.raw(1:5)), "<raw: 5 bytes>")
  expect_identical(qp_redact(list(f = function() 1)),
                   list(f = "<closure>"))
  expect_identical(qp_redact(list(e = globalenv())),
                   list(e = "<environment>"))
  expect_null(qp_redact(NULL))
  expect_identical(qp_redact(data.frame(a = 1:2)), list(a = 1:2))
  expect_identical(qp_redact(qp_scalar(2L)), 2L)
  expect_identical(catch_error(qp_redact(1, max_chars = 0))$code,
                   "INVALID_ARGUMENT")
})

test_that("details are redacted inside conditions", {
  cnd <- catch_error(qp_abort("AUTH_REQUIRED", details = list(
    authorization = "Bearer secret", bytes = as.raw(1:3)
  )))
  expect_identical(cnd$details,
                   list(authorization = "<redacted>", bytes = "<raw: 3 bytes>"))
})

error_envelope <- function(code = "HOST_BUSY", retryable = TRUE,
                           message_key = NULL, request_id = request_uuid) {
  if (is.null(message_key)) {
    message_key <- paste0("error.", tolower(code))
  }
  template <- paste0(
    '{"protocol_version":"1.0","request_id":"%s",',
    '"instance_id":"5b1f8a2c-3d4e-4f60-8a9b-0c1d2e3f4a5b",',
    '"error":{"code":"%s","message_key":"%s","message":"Host says {no}",',
    '"details":{"reason":"connection_limit","token":"t"},',
    '"retryable":%s},"warnings":[]}'
  )
  flag <- if (retryable) "true" else "false"
  qupflowR::qp_json_decode(
    sprintf(template, request_id, code, message_key, flag)
  )
}

test_that("qp_error_from_response() maps known codes", {
  withr::local_options(qupflowR.language = "en")
  cnd <- qp_error_from_response(error_envelope(), signal = FALSE)
  expect_s3_class(cnd, "qp_busy_error")
  expect_identical(cnd$code, "HOST_BUSY")
  expect_true(cnd$retryable)
  expect_identical(cnd$http_status, 503L)
  expect_identical(cnd$request_id, request_uuid)
  expect_identical(cnd$details,
                   list(reason = "connection_limit", token = "<redacted>"))
  msg <- conditionMessage(cnd)
  expect_match(msg, "The bridge is busy", fixed = TRUE)
  expect_match(msg, "Bridge message: Host says {no}", fixed = TRUE)
  expect_error(qp_error_from_response(error_envelope()),
               class = "qp_busy_error")
})

test_that("unknown response codes and host retry decisions are kept", {
  withr::local_options(qupflowR.language = "en")
  future <- error_envelope("FUTURE_CODE", retryable = FALSE)
  cnd <- qp_error_from_response(future, signal = FALSE)
  expect_identical(class(cnd)[[1]], "qp_error")
  expect_identical(cnd$code, "FUTURE_CODE")
  expect_false(cnd$retryable)
  expect_identical(cnd$message_key, "error.unknown_code")
  expect_match(conditionMessage(cnd), "unknown error (FUTURE_CODE)",
               fixed = TRUE)
  known_key <- qp_error_from_response(
    error_envelope("FUTURE_CODE", message_key = "error.host_closing"),
    signal = FALSE
  )
  expect_identical(known_key$message_key, "error.host_closing")
  overridden <- qp_error_from_response(
    error_envelope("HOST_BUSY", retryable = FALSE), signal = FALSE
  )
  expect_false(overridden$retryable)
  no_id <- qp_error_from_response(error_envelope(request_id = "not-a-uuid"),
                                  signal = FALSE)
  expect_null(no_id$request_id)
})

test_that("malformed error envelopes are protocol violations", {
  cases <- list(
    list(),
    list(error = "boom"),
    list(error = list(code = qp_scalar("lower"))),
    list(error = list(message = qp_scalar("no code")))
  )
  for (envelope in cases) {
    cnd <- catch_error(qp_error_from_response(envelope))
    expect_s3_class(cnd, "qp_version_error")
    expect_identical(cnd$code, "PROTOCOL_VIOLATION")
  }
  expect_identical(catch_error(qp_error_from_response("text"))$code,
                   "INVALID_ARGUMENT")
  expect_identical(
    catch_error(qp_error_from_response(list(), signal = NA))$code,
    "INVALID_ARGUMENT"
  )
})

test_that("qp_warnings_from_response() signals or returns warnings", {
  withr::local_options(qupflowR.language = "en")
  envelope <- qp_json_decode(paste0(
    '{"warnings":[{"code":"SNAPSHOT_NEAR_EXPIRY",',
    '"message_key":"warning.snapshot_near_expiry","message":"expires soon",',
    '"details":{"remaining_ms":10000}},{"code":"bad code"}]}'
  ))
  conditions <- qp_warnings_from_response(envelope, signal = FALSE)
  expect_length(conditions, 2L)
  expect_identical(conditions[[1]]$code, "SNAPSHOT_NEAR_EXPIRY")
  expect_identical(conditions[[1]]$message_key, "warning.unknown_code")
  expect_identical(conditions[[1]]$details, list(remaining_ms = 10000L))
  expect_match(conditionMessage(conditions[[1]]),
               "Bridge message: expires soon", fixed = TRUE)
  expect_identical(conditions[[2]]$code, "PROTOCOL_VIOLATION")
  seen <- character()
  signalled <- withCallingHandlers(
    qp_warnings_from_response(envelope),
    warning = function(w) {
      seen <<- c(seen, w$code)
      invokeRestart("muffleWarning")
    }
  )
  expect_identical(seen, c("SNAPSHOT_NEAR_EXPIRY", "PROTOCOL_VIOLATION"))
  expect_length(signalled, 2L)
  expect_identical(
    qp_warnings_from_response(list(warnings = list()), signal = FALSE),
    list()
  )
  expect_identical(qp_warnings_from_response(list(), signal = FALSE), list())
})

test_that("R code only raises registered codes", {
  r_dir <- testthat::test_path("..", "..", "R")
  files <- list.files(r_dir, pattern = "[.]R$", full.names = TRUE)
  skip_if(length(files) == 0L, "package R sources are not available")
  code <- vapply(files, function(f) {
    lines <- readLines(f, encoding = "UTF-8")
    paste(lines[!grepl("^\\s*#", lines)], collapse = "\n")
  }, character(1))
  pattern <- "qp_abort\\(\\s*\"[A-Z0-9_]+\""
  calls <- unlist(regmatches(code, gregexpr(pattern, code)))
  used <- unique(sub(".*\"([A-Z0-9_]+)\"$", "\\1", calls))
  expect_gt(length(used), 3L)
  unregistered <- setdiff(used, qp_error_codes()$code)
  expect_identical(unregistered, character(),
                   info = paste(unregistered, collapse = ", "))
})

test_that("envelope fields fall back to the registry and plain lists", {
  envelope <- list(error = list(code = qp_scalar("HOST_BUSY"),
                                details = qp_scalar("not an object")))
  cnd <- qp_error_from_response(envelope, signal = FALSE)
  expect_true(cnd$retryable)
  expect_identical(cnd$details, list())
  expect_null(cnd$request_id)
  warnings <- qp_warnings_from_response(
    list(warnings = list(list(code = qp_scalar("X"), details = "text"))),
    signal = FALSE
  )
  expect_identical(warnings[[1]]$details, list())
  not_array <- qp_warnings_from_response(list(warnings = "x"), signal = FALSE)
  expect_identical(not_array[[1]]$code, "PROTOCOL_VIOLATION")
  expect_identical(catch_error(qp_warnings_from_response("x"))$code,
                   "INVALID_ARGUMENT")
})

test_that("bullets without names, NULL details and bad classes", {
  cnd <- catch_error(qp_abort("HOST_BUSY", details = NULL,
                              bullets = c("first", "second")))
  expect_identical(cnd$details, list())
  expect_match(conditionMessage(cnd), "first", fixed = TRUE)
  expect_match(conditionMessage(cnd), "second", fixed = TRUE)
  expect_identical(catch_error(qp_abort("HOST_BUSY", class = 1))$code,
                   "INVALID_ARGUMENT")
})
