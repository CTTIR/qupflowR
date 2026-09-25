# Classified conditions -----------------------------------------------------
#
# Every error raised by qupflowR carries a stable code from the registry in
# inst/schema/protocol/v1/error-codes.json. The Java bridge reads the same
# file, so a protocol error and the R condition built from it agree on the
# code, the R class, the HTTP status and whether a retry is safe.

core_cache <- new.env(parent = emptyenv())

core_r_classes <- c(
  "qp_connection_error", "qp_version_error", "qp_capability_error",
  "qp_validation_error", "qp_conflict_error", "qp_busy_error",
  "qp_recovery_error", "qp_job_error", "qp_bundle_error", "qp_runtime_error",
  "qp_reproduction_error", "qp_serialization_error", "qp_storage_error"
)

core_code_pattern <- "^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$"
core_key_pattern <- "^[a-z][a-z0-9_]*([.][a-z0-9_]+)+$"
core_uuid_pattern <- paste0(
  "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)

#' Signal a classified qupflowR error
#'
#' `qp_abort()` is the single way qupflowR raises errors. It looks up `code`
#' in the protocol error registry, builds the condition classes
#' `c(class, <registry class>, "qp_error")` and formats the message from the
#' message catalogue in the language chosen by the option
#' `qupflowR.language` (see [qp_msg()]).
#'
#' @param code Error code, a single string of upper-case letters, digits and
#'   underscores such as `"HOST_BUSY"`. Codes that are not in the registry
#'   ([qp_error_codes()]) are kept, but the condition then only has the class
#'   `qp_error`.
#' @param ... Named values for the placeholders of the message template, for
#'   example `arg = "x"` for `error.invalid_argument`. Values are inserted as
#'   text and never evaluated.
#' @param class Additional condition classes placed before the registry class.
#' @param details Named list with machine-readable context. It is passed
#'   through [qp_redact()], so tokens are removed and long values truncated.
#' @param request_id Protocol request ID (UUID string) or `NULL`.
#' @param action Short machine-readable suggested action (for example
#'   `"reconnect"`) or `NULL`.
#' @param message_key Message key that replaces the registry message of the
#'   code, or `NULL` to use the registry message.
#' @param bullets Optional character vector of additional message lines. Names
#'   are cli bullet types (`"x"`, `"i"`, `"*"`, `"!"`, `"v"`); the text is
#'   shown literally.
#' @param parent Parent condition or `NULL`.
#' @param call Environment of the function reported as the error call.
#' @return Does not return; signals a condition inheriting from `qp_error`
#'   with the fields `code`, `message_key`, `details`, `request_id`, `action`,
#'   `retryable` (logical, `NA` if unknown) and `http_status` (integer, `NA`
#'   for codes that are never sent over HTTP). Its class is the registry
#'   class of `code`, for example `qp_busy_error` for `HOST_BUSY`. An invalid
#'   `code`, `class`, `details`, `request_id`, `action`, `message_key` or
#'   `bullets` argument signals a `qp_validation_error` with code
#'   `INVALID_ARGUMENT` instead.
#' @seealso [qp_error_codes()], [qp_condition_code()], [qp_redact()]
#' @export
#' @examples
#' cnd <- tryCatch(
#'   qp_abort("HOST_BUSY", details = list(reason = "connection_limit")),
#'   qp_error = function(e) e
#' )
#' class(cnd)
#' cnd$code
#' cnd$retryable
qp_abort <- function(code, ..., class = NULL, details = list(),
                     request_id = NULL, action = NULL, message_key = NULL,
                     bullets = NULL, parent = NULL, call = parent.frame()) {
  core_check_code(code, call = call)
  values <- core_named_values(list(...), call = call)
  core_check_optional_strings(class, request_id, action, bullets, call = call)
  entry <- qp_error_entry(code)
  if (is.null(message_key)) {
    message_key <- if (is.null(entry)) {
      "error.unknown_code"
    } else {
      entry$message_key
    }
  }
  core_check_key(message_key, "message_key", call = call)
  core_signal_error(
    code = code, entry = entry, message_key = message_key, values = values,
    class = class, details = details, request_id = request_id, action = action,
    bullets = bullets, parent = parent, call = call
  )
}

#' Signal a classified qupflowR warning
#'
#' @param code Warning code, a single string of upper-case letters, digits
#'   and underscores.
#' @param ... Named values for the placeholders of the message template.
#' @param message_key Message key; by default `"warning."` followed by the
#'   lower-case code.
#' @param class Additional condition classes placed before `qp_warning`.
#' @param details Named list with machine-readable context, redacted with
#'   [qp_redact()].
#' @param bullets Optional character vector of additional message lines shown
#'   literally (see [qp_abort()]).
#' @param call Environment of the function reported as the warning call.
#' @return `NULL`, invisibly. Signals a warning inheriting from `qp_warning`
#'   with the fields `code`, `message_key` and `details`. An invalid `code`,
#'   `message_key`, `class`, `details` or `bullets` argument signals a
#'   `qp_validation_error` with code `INVALID_ARGUMENT` instead.
#' @export
#' @examples
#' w <- tryCatch(
#'   qp_warn("SNAPSHOT_NEAR_EXPIRY", message_key = "warning.unknown_code"),
#'   qp_warning = function(w) w
#' )
#' w$code
qp_warn <- function(code, ..., message_key = NULL, class = NULL,
                    details = list(), bullets = NULL, call = parent.frame()) {
  core_check_code(code, call = call)
  values <- core_named_values(list(...), call = call)
  core_check_optional_strings(class, NULL, NULL, bullets, call = call)
  if (is.null(message_key)) {
    message_key <- paste0("warning.", tolower(code))
  }
  core_check_key(message_key, "message_key", call = call)
  template <- core_msg_template(message_key)
  env <- core_msg_env(template, c(values, list(code = code)))
  cli::cli_warn(
    c(template, core_bullet_templates(bullets, env)),
    class = unique(c(class, "qp_warning")),
    code = code,
    message_key = message_key,
    details = qp_redact(core_as_details(details, call)),
    call = call,
    .envir = env
  )
  invisible(NULL)
}

#' Code of a qupflowR condition
#'
#' @param cnd A condition object.
#' @return The condition's `code` as a single string, or `NA_character_` when
#'   `cnd` is not a qupflowR condition with a code. It signals no condition
#'   of its own, whatever `cnd` is.
#' @export
#' @examples
#' cnd <- tryCatch(qp_abort("REVISION_CONFLICT"), error = function(e) e)
#' qp_condition_code(cnd)
#' qp_condition_code(simpleCondition("plain"))
qp_condition_code <- function(cnd) {
  if (!inherits(cnd, c("qp_error", "qp_warning"))) {
    return(NA_character_)
  }
  code <- cnd[["code"]]
  if (core_is_string(code)) as.vector(code) else NA_character_
}

#' Protocol error code registry
#'
#' The registry lists every error code used by the bridge protocol and by the
#' R-side workflow, storage, JSON and schema layers. The same file
#' (`inst/schema/protocol/v1/error-codes.json`) is read by the Java bridge.
#'
#' @return A tibble with one row per code and the columns `code` (character),
#'   `http_status` (integer; `NA` for codes that are never sent over HTTP),
#'   `r_class` (character), `retryable` (logical), `message_key` (character)
#'   and `source` (character, the contract that defines the code). It takes
#'   no arguments and signals no condition with the registry that is
#'   installed with the package.
#' @export
#' @examples
#' codes <- qp_error_codes()
#' codes[codes$retryable, c("code", "http_status")]
qp_error_codes <- function() {
  reg <- core_registry()
  field <- function(name, type) {
    vapply(reg, function(e) e[[name]], type, USE.NAMES = FALSE)
  }
  tibble::tibble(
    code = field("code", character(1)),
    http_status = vapply(reg, function(e) {
      if (is.null(e$http_status)) NA_integer_ else as.integer(e$http_status)
    }, integer(1), USE.NAMES = FALSE),
    r_class = field("r_class", character(1)),
    retryable = field("retryable", logical(1)),
    message_key = field("message_key", character(1)),
    source = field("source", character(1))
  )
}

#' Map a protocol error response to a classified condition
#'
#' Converts the `error` object of a decoded protocol v1 error envelope into
#' the matching qupflowR condition. Known codes get their registry class;
#' unknown codes keep their code with the class `qp_error` only. The host's
#' own message is added as an extra line, and `details` are redacted.
#'
#' @param envelope Decoded error envelope, a named list as returned by
#'   [qp_json_decode()] with the fields `request_id` and `error`.
#' @param signal If `TRUE` (default) the condition is signalled; if `FALSE`
#'   it is returned.
#' @param call Environment reported as the call of the condition.
#' @return With `signal = FALSE` the condition object; otherwise the function
#'   does not return. A malformed envelope signals a `qp_version_error` with
#'   code `PROTOCOL_VIOLATION`.
#' @export
#' @examples
#' envelope <- qp_json_decode(paste0(
#'   '{"protocol_version":"1.0",',
#'   '"request_id":"0d9a3c5e-8f7b-4a21-9c3d-2e1f0a9b8c7d",',
#'   '"instance_id":"5b1f8a2c-3d4e-4f60-8a9b-0c1d2e3f4a5b",',
#'   '"error":{"code":"HOST_BUSY","message_key":"error.host_busy",',
#'   '"message":"Too many open connections; retry later",',
#'   '"details":{"reason":"connection_limit"},"retryable":true},',
#'   '"warnings":[]}'
#' ))
#' cnd <- qp_error_from_response(envelope, signal = FALSE)
#' class(cnd)
#' cnd$retryable
qp_error_from_response <- function(envelope, signal = TRUE,
                                   call = parent.frame()) {
  core_check_flag(signal, "signal", call = call)
  parsed <- core_parse_error_envelope(envelope, call = call)
  entry <- qp_error_entry(parsed$code)
  key <- core_response_key(entry, parsed$message_key, "error.unknown_code")
  retryable <- parsed$retryable
  if (is.na(retryable) && !is.null(entry)) {
    retryable <- isTRUE(entry$retryable)
  }
  build <- function() {
    core_signal_error(
      code = parsed$code, entry = entry, message_key = key, values = list(),
      class = NULL, details = parsed$details, request_id = parsed$request_id,
      action = NULL, bullets = core_host_message_bullet(parsed$message),
      parent = NULL, call = call, retryable = retryable
    )
  }
  if (signal) build() else tryCatch(build(), qp_error = function(e) e)
}

#' Signal the warnings of a protocol response
#'
#' @param envelope Decoded protocol envelope (named list) whose `warnings`
#'   field is an array of warning objects with `code`, `message_key`,
#'   `message` and optional `details`.
#' @param signal If `TRUE` (default) each warning is signalled as a
#'   `qp_warning`; if `FALSE` the conditions are returned instead.
#' @param call Environment reported as the call of the warnings.
#' @return A list of `qp_warning` conditions (invisibly when signalled). A
#'   malformed warning entry becomes a warning with code `PROTOCOL_VIOLATION`.
#'   An `envelope` that is not a named list, or an invalid `signal`, signals
#'   a `qp_validation_error` with code `INVALID_ARGUMENT`.
#' @export
#' @examples
#' envelope <- list(warnings = list(list(
#'   code = qp_scalar("SNAPSHOT_NEAR_EXPIRY"),
#'   message_key = qp_scalar("warning.snapshot_near_expiry"),
#'   message = qp_scalar("Snapshot expires soon")
#' )))
#' ws <- qp_warnings_from_response(envelope, signal = FALSE)
#' ws[[1]]$code
qp_warnings_from_response <- function(envelope, signal = TRUE,
                                      call = parent.frame()) {
  core_check_flag(signal, "signal", call = call)
  if (!is.list(envelope)) {
    core_abort_argument("envelope", "expect.response_envelope", call)
  }
  items <- envelope[["warnings"]]
  if (!is.null(items) && (!is.list(items) || !is.null(names(items)))) {
    items <- list(NULL)
  }
  if (!signal) {
    return(lapply(items, function(w) {
      tryCatch(core_warn_from_item(w, call), qp_warning = function(cnd) cnd)
    }))
  }
  collected <- list()
  for (w in items) {
    withCallingHandlers(
      core_warn_from_item(w, call),
      qp_warning = function(cnd) collected[[length(collected) + 1L]] <<- cnd
    )
  }
  invisible(collected)
}

#' Redact and bound condition details
#'
#' Removes credentials and bounds the size of values before they are stored
#' in a condition, a log or a diagnostic report.
#'
#' Rules:
#' * Elements whose name contains `token`, `secret`, `password`, `passwd`,
#'   `authorization`, `cookie`, `credential(s)`, `api_key` or `private_key`
#'   as a word part are replaced by `"<redacted>"`. Names ending in `_file`,
#'   `_path` or `_dir` (for example `token_file`) name locations, not
#'   secrets, and are kept.
#' * In every string, `Bearer <credential>` and `token=`, `access_token=`,
#'   `api_key=`, `password=` or `secret=` query values are replaced, and a
#'   string that consists only of a 43-character base64url value (the form
#'   of a 256-bit bridge token) becomes `"<redacted>"`.
#' * Strings longer than `max_chars` characters are truncated with a note.
#' * Vectors and lists longer than `max_items` keep their first `max_items`
#'   elements and gain the attribute `truncated_from` (original length).
#' * Lists nested deeper than `max_depth` and raw vectors, functions,
#'   environments and other non-data objects are replaced by a short
#'   description.
#' * JSON scalar marks ([qp_scalar()]) are removed, so decoded protocol
#'   details become plain R values.
#'
#' @param x Value to redact, usually a named list.
#' @param max_chars,max_items,max_depth Positive whole numbers bounding the
#'   string length, the number of elements and the nesting depth.
#' @return The redacted value with the same structure where possible.
#'   Bounds that are not positive whole numbers signal a
#'   `qp_validation_error` with code `INVALID_ARGUMENT`.
#' @export
#' @examples
#' qp_redact(list(
#'   token = "abc",
#'   header = "Authorization: Bearer abc.def",
#'   token_file = "/run/user/1000/qupflowr/token"
#' ))
qp_redact <- function(x, max_chars = 500L, max_items = 50L, max_depth = 5L) {
  for (arg in c("max_chars", "max_items", "max_depth")) {
    core_check_count(get(arg), arg, call = environment())
  }
  core_redact_value(x, max_chars, max_items, max_depth, depth = 0L)
}

# Registry ------------------------------------------------------------------

core_registry <- function() {
  if (is.null(core_cache$registry)) {
    path <- system.file("schema", "protocol", "v1", "error-codes.json",
                        package = "qupflowR")
    reg <- if (nzchar(path)) core_read_json_tree(path) else list()
    names(reg) <- vapply(reg, function(e) e$code, character(1))
    core_cache$registry <- reg
  }
  core_cache$registry
}

qp_error_entry <- function(code) {
  reg <- core_registry()
  if (core_is_string(code) && code %in% names(reg)) {
    reg[[code]]
  } else {
    NULL
  }
}

# Condition construction ----------------------------------------------------

core_signal_error <- function(code, entry, message_key, values, class, details,
                              request_id, action, bullets, parent, call,
                              retryable = NULL) {
  template <- core_msg_template(message_key)
  env <- core_msg_env(template, c(values, list(code = code)))
  extra <- bullets
  if (!is.null(action)) {
    extra <- c(extra, i = qp_msg("condition.action", action = action))
  }
  if (!is.null(request_id)) {
    extra <- c(extra,
               i = qp_msg("condition.request_id", request_id = request_id))
  }
  specific <- if (is.null(entry)) NULL else entry$r_class
  if (is.null(retryable)) {
    retryable <- if (is.null(entry)) NA else isTRUE(entry$retryable)
  }
  http_status <- NA_integer_
  if (!is.null(entry$http_status)) {
    http_status <- as.integer(entry$http_status)
  }
  cli::cli_abort(
    c(template, core_bullet_templates(extra, env)),
    class = unique(c(class, specific, "qp_error")),
    code = code,
    message_key = message_key,
    details = qp_redact(core_as_details(details, call)),
    request_id = request_id,
    action = action,
    retryable = retryable,
    http_status = http_status,
    parent = parent,
    call = call,
    .envir = env
  )
}

# Additional lines are stored as variables so their text is never interpolated.
core_bullet_templates <- function(bullets, env) {
  if (length(bullets) == 0L) {
    return(character())
  }
  types <- names(bullets)
  if (is.null(types)) {
    types <- rep("", length(bullets))
  }
  types[is.na(types) | !types %in% c("x", "i", "*", "!", "v", " ")] <- "*"
  vars <- paste0("qp_line_", seq_along(bullets))
  for (i in seq_along(bullets)) {
    assign(vars[[i]], bullets[[i]], envir = env)
  }
  stats::setNames(paste0("{", vars, "}"), types)
}

core_as_details <- function(details, call) {
  if (is.null(details)) {
    return(list())
  }
  if (!is.list(details)) {
    core_abort_argument("details", "expect.list", call)
  }
  details
}

core_parse_error_envelope <- function(envelope, call) {
  if (!is.list(envelope)) {
    core_abort_argument("envelope", "expect.response_envelope", call)
  }
  err <- envelope[["error"]]
  violation <- function(reason) {
    qp_abort("PROTOCOL_VIOLATION", details = list(reason = reason), call = call)
  }
  if (!is.list(err) || is.null(names(err))) {
    violation("missing_error_object")
  }
  code <- core_plain_string(err[["code"]])
  if (is.null(code) || !grepl(core_code_pattern, code)) {
    violation("invalid_error_code")
  }
  details <- err[["details"]]
  if (!is.list(details) || (length(details) > 0L && is.null(names(details)))) {
    details <- list()
  }
  retryable <- err[["retryable"]]
  retryable <- if (is.logical(retryable) && length(retryable) == 1L) {
    as.vector(retryable)
  } else {
    NA
  }
  request_id <- core_plain_string(envelope[["request_id"]])
  if (!is.null(request_id) && !grepl(core_uuid_pattern, request_id)) {
    request_id <- NULL
  }
  list(
    code = code,
    message_key = core_plain_string(err[["message_key"]]),
    message = core_plain_string(err[["message"]]),
    details = details,
    retryable = retryable,
    request_id = request_id
  )
}

core_response_key <- function(entry, key, fallback) {
  if (!is.null(entry)) {
    return(entry$message_key)
  }
  if (!is.null(key) && grepl(core_key_pattern, key) && core_msg_exists(key)) {
    return(key)
  }
  fallback
}

core_host_message_bullet <- function(message) {
  if (is.null(message) || !nzchar(message)) {
    return(NULL)
  }
  text <- core_redact_value(message, 500L, 1L, 1L, depth = 0L)
  c(i = qp_msg("condition.host_message", message = text))
}

core_warn_from_item <- function(item, call) {
  code <- if (is.list(item)) core_plain_string(item[["code"]]) else NULL
  if (is.null(code) || !grepl(core_code_pattern, code)) {
    qp_warn("PROTOCOL_VIOLATION", message_key = "error.protocol_violation",
            details = list(reason = "invalid_warning"), call = call)
    return(invisible(NULL))
  }
  details <- item[["details"]]
  if (!is.list(details) || (length(details) > 0L && is.null(names(details)))) {
    details <- list()
  }
  key <- core_response_key(NULL, core_plain_string(item[["message_key"]]),
                           "warning.unknown_code")
  host_message <- core_plain_string(item[["message"]])
  qp_warn(code, message_key = key, details = details,
          bullets = core_host_message_bullet(host_message), call = call)
}

# Redaction ------------------------------------------------------------------

core_sensitive_name <- paste0(
  "(?i)(^|[_.-])(token|secret|password|passwd|authorization|cookie|",
  "credentials?|api[_-]?key|private[_-]?key|bearer)([_.-]|$)"
)

core_redact_value <- function(x, max_chars, max_items, max_depth, depth) {
  if (is.null(x)) {
    return(NULL)
  }
  if (is.list(x)) {
    return(core_redact_list(unclass(x), max_chars, max_items, max_depth, depth))
  }
  if (is.raw(x)) {
    return(sprintf("<raw: %d bytes>", length(x)))
  }
  if (!is.atomic(x)) {
    return(sprintf("<%s>", typeof(x)))
  }
  if (inherits(x, "scalar")) {
    x <- unclass(x)
  }
  n <- length(x)
  if (n > max_items) {
    x <- x[seq_len(max_items)]
  }
  if (is.character(x)) {
    x <- core_redact_strings(x, max_chars)
  }
  if (n > max_items) {
    attr(x, "truncated_from") <- n
  }
  x
}

core_redact_list <- function(x, max_chars, max_items, max_depth, depth) {
  if (depth >= max_depth) {
    return(sprintf("<list of %d elements, nested too deeply>", length(x)))
  }
  n <- length(x)
  nms <- names(x)
  keep <- seq_len(min(n, max_items))
  out <- lapply(keep, function(i) {
    nm <- if (is.null(nms)) "" else nms[[i]]
    if (!is.na(nm) && core_is_sensitive_name(nm)) {
      "<redacted>"
    } else {
      core_redact_value(x[[i]], max_chars, max_items, max_depth, depth + 1L)
    }
  })
  if (!is.null(nms)) {
    names(out) <- nms[keep]
  }
  if (n > max_items) {
    attr(out, "truncated_from") <- n
  }
  out
}

core_is_sensitive_name <- function(name) {
  grepl(core_sensitive_name, name, perl = TRUE) &&
    !grepl("(?i)_(file|path|dir)$", name, perl = TRUE)
}

core_redact_strings <- function(x, max_chars) {
  miss <- is.na(x)
  y <- x[!miss]
  y <- gsub("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]+", "\\1 <redacted>", y,
            perl = TRUE)
  y <- gsub("(?i)\\b(token|access_token|api_key|password|secret)=[^&\\s]+",
            "\\1=<redacted>", y, perl = TRUE)
  y[grepl("^[A-Za-z0-9_-]{43}$", y)] <- "<redacted>"
  long <- nchar(y, type = "chars", allowNA = TRUE) > max_chars
  long[is.na(long)] <- FALSE
  if (any(long)) {
    total <- nchar(y[long], type = "chars")
    y[long] <- sprintf("%s... [%d characters truncated]",
                       substr(y[long], 1L, max_chars), total - max_chars)
  }
  x[!miss] <- y
  x
}

# Argument checks -----------------------------------------------------------

core_abort_argument <- function(arg, expected_key, call) {
  qp_abort(
    "INVALID_ARGUMENT",
    arg = arg,
    expected = qp_msg(expected_key),
    details = list(argument = arg),
    call = call
  )
}

core_is_string <- function(x) {
  is.character(x) && length(x) == 1L && !is.na(x)
}

core_plain_string <- function(x) {
  if (core_is_string(x)) as.vector(x) else NULL
}

core_check_string <- function(x, arg, call) {
  if (!core_is_string(x)) {
    core_abort_argument(arg, "expect.string", call)
  }
  invisible(x)
}

core_check_flag <- function(x, arg, call) {
  if (!is.logical(x) || length(x) != 1L || is.na(x)) {
    core_abort_argument(arg, "expect.flag", call)
  }
  invisible(x)
}

core_check_count <- function(x, arg, call) {
  ok <- is.numeric(x) && length(x) == 1L && !is.na(x) && is.finite(x) &&
    x >= 1 && x == trunc(x)
  if (!ok) {
    core_abort_argument(arg, "expect.positive_count", call)
  }
  invisible(x)
}

core_check_code <- function(code, call) {
  if (!core_is_string(code) || !grepl(core_code_pattern, code)) {
    core_abort_argument("code", "expect.error_code", call)
  }
  invisible(code)
}

core_check_key <- function(key, arg, call) {
  if (!core_is_string(key) || !grepl(core_key_pattern, key)) {
    core_abort_argument(arg, "expect.message_key", call)
  }
  invisible(key)
}

core_check_optional_strings <- function(class, request_id, action, bullets,
                                        call) {
  if (!is.null(class) && (!is.character(class) || anyNA(class))) {
    core_abort_argument("class", "expect.character", call)
  }
  if (!is.null(request_id)) core_check_string(request_id, "request_id", call)
  if (!is.null(action)) core_check_string(action, "action", call)
  if (!is.null(bullets) && (!is.character(bullets) || anyNA(bullets))) {
    core_abort_argument("bullets", "expect.character", call)
  }
  invisible(TRUE)
}

core_named_values <- function(values, call) {
  if (length(values) == 0L) {
    return(list())
  }
  nms <- names(values)
  if (is.null(nms) || anyNA(nms) || !all(nzchar(nms))) {
    core_abort_argument("...", "expect.named_values", call)
  }
  values
}
