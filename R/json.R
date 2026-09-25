# Protocol JSON -------------------------------------------------------------
#
# All protocol JSON passes through qp_json_encode() and qp_json_decode(). The
# encoder is written here instead of relying on jsonlite::toJSON() so that the
# array/scalar rule, number formatting and the rejection of non-finite numbers
# are exact and identical to the Java bridge. jsonlite is used for parsing.

core_json_max_depth <- 128L
core_json_max_safe_integer <- 9007199254740991
core_json_atomic_classes <- c(
  "scalar", "numeric", "integer", "character", "logical"
)

#' Encode and decode protocol JSON
#'
#' `qp_json_encode()` turns R values into JSON text and `qp_json_decode()`
#' turns JSON text into R values, following one explicit convention so that
#' `qp_json_encode(qp_json_decode(txt))` gives back the same JSON value.
#'
#' @section Encoding convention:
#' * `NULL` becomes `null`.
#' * A [qp_scalar()] value (class `scalar`, as made by [jsonlite::unbox()])
#'   becomes a single number, string, `true` or `false`; `NA` becomes `null`.
#' * Every other atomic vector becomes an array, at length 1 and 0 as well;
#'   `NA` elements become `null`.
#' * An unnamed list, including `list()`, becomes an array.
#' * A named list, including [qp_empty_object()], becomes an object.
#' * Length-1 vectors are never unboxed silently: mark scalars with
#'   [qp_scalar()] or pass `schema_hint`.
#' * Doubles that hold whole numbers with magnitude up to 2^53 are written
#'   as plain digits (`5000000000`, never `5e+09`). Other doubles use the
#'   shortest of 15, 16 or 17 significant digits that reads back to the same
#'   double.
#' * `NaN`, `Inf` and `-Inf` are rejected with a `qp_serialization_error`
#'   (code `JSON_UNREPRESENTABLE`); JSON has no such literals. Measurement
#'   values use `value` plus `value_state`, see [qp_value_state()].
#' * Strings are written as UTF-8. Only `"`, `\` and control characters are
#'   escaped; invalid UTF-8 is rejected.
#' * Object names must be non-empty where given, unique and valid UTF-8.
#' * Factors, dates, matrices, data frames, named atomic vectors, raw vectors,
#'   functions and environments are rejected instead of being guessed.
#'
#' @section Decoding convention:
#' * Objects become named lists (`{}` becomes [qp_empty_object()]), `null`
#'   becomes `NULL`.
#' * Single values become length-1 vectors marked with [qp_scalar()].
#' * Arrays whose elements are all strings, all numbers or all booleans
#'   (with optional `null`s) become atomic vectors with `NA` for `null`;
#'   other arrays become unnamed lists. `[]` becomes `list()`.
#' * Numbers without fraction or exponent that fit a 32-bit integer become
#'   integers, all others doubles. Integers beyond 2^53 lose precision like
#'   in any double-based reader; the protocol does not use them.
#' * Duplicate or empty object keys, a byte-order mark, invalid UTF-8,
#'   escaped NUL characters, unpaired surrogate escapes, numbers outside the
#'   double range and nesting deeper than 128 levels are rejected
#'   (`JSON_INVALID` or `JSON_UNREPRESENTABLE`). So are comments, white
#'   space other than space, tab, line feed and carriage return between
#'   tokens, and an unterminated string, also after a complete value as in
#'   `[1]"` (`JSON_INVALID`, reason `syntax`), as RFC 8259 and the Java bridge
#'   require. R cannot tell an empty member name from a missing one, so empty
#'   names are not supported in either direction.
#' * The checks before parsing take time proportional to the length of the
#'   text.
#'
#' @param x R value to encode.
#' @param schema_hint `NULL`, or a schema name for [qp_schema_path()] or the
#'   path of a schema file. With a hint, unmarked length-1 atomic values are
#'   unboxed wherever the schema only allows a single value, and stay arrays
#'   where the schema allows arrays.
#' @param txt JSON text: a single string, a raw vector of UTF-8 bytes, or text
#'   marked with [qp_as_json()].
#' @return `qp_json_encode()` returns a single UTF-8 string with class `json`.
#'   `qp_json_decode()` returns the decoded R value.
#' @seealso [qp_json_canonical()], [qp_schema_validate()]
#' @export
#' @examples
#' qp_json_encode(list(
#'   image_id = qp_scalar("1"),
#'   object_ids = "8f6589c0-c587-4bfa-b740-8cab06bc8ce1",
#'   size_bytes = qp_scalar(5e9),
#'   name = NULL,
#'   metadata = qp_empty_object()
#' ))
#' x <- qp_json_decode('{"plane":{"c":null,"z":0,"t":1},"ids":["a"]}')
#' x$ids
#' qp_json_encode(x)
qp_json_encode <- function(x, schema_hint = NULL) {
  if (!is.null(schema_hint)) {
    x <- core_schema_box(x, schema_hint, call = environment())
  }
  out <- core_json_encode(x, canonical = FALSE, path = "", depth = 0L)
  structure(enc2utf8(out), class = "json")
}

#' @rdname qp_json_encode
#' @export
qp_json_decode <- function(txt) {
  tree <- core_json_parse_tree(txt, call = environment())
  core_json_from_tree(tree)
}

#' Canonical JSON
#'
#' Produces the canonical JSON text used as digest input. Two documents with
#' the same JSON value have byte-identical canonical forms in R and in the
#' Java bridge.
#'
#' Rules:
#' 1. No insignificant whitespace and no trailing newline.
#' 2. Object members are sorted by their names compared as sequences of
#'   Unicode code points (the same order as comparing the UTF-8 bytes). This
#'   differs from RFC 8785, which compares UTF-16 code units, for names that
#'   mix characters above U+FFFF with characters from U+E000 to U+FFFF.
#'   Names must be unique and non-empty.
#' 3. Strings are UTF-8. `"` and `\` are escaped as `\"` and `\\`; U+0008,
#'   U+0009, U+000A, U+000C and U+000D as `\b`, `\t`, `\n`, `\f` and `\r`;
#'   other characters below U+0020 as `\u00xx` with lower-case hex digits.
#'   Every other character, including non-ASCII, is written literally.
#' 4. Numbers must be integers with magnitude at most 2^53 - 1, written as
#'   decimal digits with an optional minus sign, without exponent, fraction
#'   or leading zeros; negative zero is written as `0`. Non-integer numbers
#'   are rejected (`JSON_UNREPRESENTABLE`, reason `non_integer_number`)
#'   because shortest round-trip formatting of doubles is not guaranteed to
#'   agree between languages; digested documents store fractional values as
#'   decimal strings. In JSON text input every number token with a fraction
#'   or exponent is rejected, even `1.0`.
#' 5. Arrays keep their order; `true`, `false` and `null` are literals.
#'
#' @param x An R value following the conventions of [qp_json_encode()], or
#'   JSON text marked with [qp_as_json()] (the result of [qp_json_encode()] is
#'   already marked). Plain character strings are values, not JSON text.
#' @return A single UTF-8 string with class `json`. Use
#'   `charToRaw(qp_json_canonical(x))` for the digest input bytes. A value
#'   that breaks rules 2 or 4 (or cannot be JSON at all) signals a
#'   `qp_serialization_error` with code `JSON_UNREPRESENTABLE`; invalid JSON
#'   text signals a `qp_serialization_error` with code `JSON_INVALID`. Both
#'   carry the rule in `details$reason`.
#' @export
#' @examples
#' qp_json_canonical(qp_as_json('{"b": 2, "a": [true, null, "\\u00e9"]}'))
#' qp_json_canonical(list(z = qp_scalar(1L), a = qp_scalar("x")))
qp_json_canonical <- function(x) {
  if (inherits(x, "json")) {
    txt <- core_json_text(x, call = environment())
    core_json_check_integers(txt, call = environment())
    x <- qp_json_decode(txt)
  }
  out <- core_json_encode(x, canonical = TRUE, path = "", depth = 0L)
  structure(enc2utf8(out), class = "json")
}

#' Mark JSON values
#'
#' `qp_scalar()` marks a length-1 atomic value as a JSON scalar (class
#' `scalar`, compatible with [jsonlite::unbox()]); `NA` encodes as `null`.
#' `qp_empty_object()` returns the empty named list that encodes as `{}`.
#' `qp_as_json()` marks a string as JSON text for [qp_json_canonical()] and
#' [qp_schema_validate()].
#'
#' @param x For `qp_scalar()`, an atomic vector of length 1 or `NULL`. For
#'   `qp_as_json()`, a single string or a raw vector of UTF-8 bytes.
#' @return `qp_scalar()`: `x` with class `scalar` (`NULL` stays `NULL`).
#'   `qp_empty_object()`: `structure(list(), names = character(0))`.
#'   `qp_as_json()`: a single UTF-8 string with class `json`. An `x` of the
#'   wrong type or length signals a `qp_validation_error` with code
#'   `INVALID_ARGUMENT`; text that is not UTF-8, contains NUL or starts with
#'   a byte order mark signals a `qp_serialization_error` with code
#'   `JSON_INVALID`.
#' @export
#' @examples
#' qp_json_encode(list(a = qp_scalar(1L), b = 1L, c = qp_empty_object()))
#' qp_as_json('{"a": 1}')
qp_scalar <- function(x) {
  if (is.null(x)) {
    return(NULL)
  }
  ok <- is.atomic(x) && length(x) == 1L && !is.raw(x) && is.null(dim(x)) &&
    all(oldClass(x) %in% core_json_atomic_classes)
  if (!ok) {
    core_abort_argument("x", "expect.scalar", environment())
  }
  core_mark_scalar(x)
}

#' @rdname qp_scalar
#' @export
qp_empty_object <- function() {
  structure(list(), names = character(0))
}

#' @rdname qp_scalar
#' @export
qp_as_json <- function(x) {
  structure(core_json_text(x, call = environment()), class = "json")
}

#' Special measurement values
#'
#' The protocol carries a measurement as `value` plus `value_state`. Only the
#' state `finite` has a JSON number; the states `missing`, `nan`, `pos_inf`
#' and `neg_inf` have `value = null`. In R they correspond to `NA_real_`,
#' `NaN`, `Inf` and `-Inf`.
#'
#' @param x Numeric vector (integer or double).
#' @param value Numeric vector (with `NA` where the state is not `finite`),
#'   a logical vector of `NA`, or `NULL` for a single non-finite state.
#' @param value_state Character vector of states, parallel to `value`.
#' @return `qp_value_state()`: character vector of states parallel to `x`.
#'   `qp_value_from_state()`: double vector. `qp_value_fields()`: named list
#'   with the scalar-marked fields `value` (or `NULL`) and `value_state`, ready
#'   for [qp_json_encode()]. Non-numeric input, unknown states, a state that
#'   contradicts its value or vectors of different lengths signal a
#'   `qp_validation_error` with code `INVALID_ARGUMENT`.
#' @export
#' @examples
#' x <- c(1.5, NA, NaN, Inf, -Inf)
#' state <- qp_value_state(x)
#' state
#' value <- ifelse(state == "finite", x, NA_real_)
#' identical(qp_value_from_state(value, state), x)
#' qp_json_encode(qp_value_fields(NaN))
qp_value_state <- function(x) {
  if (!is.numeric(x) || is.object(x) && !inherits(x, "scalar")) {
    core_abort_argument("x", "expect.numeric", environment())
  }
  x <- as.double(x)
  state <- rep("finite", length(x))
  state[is.na(x)] <- "missing"
  state[is.nan(x)] <- "nan"
  state[x %in% Inf] <- "pos_inf"
  state[x %in% -Inf] <- "neg_inf"
  state
}

#' @rdname qp_value_state
#' @export
qp_value_from_state <- function(value, value_state) {
  states <- c("finite", "missing", "nan", "pos_inf", "neg_inf")
  valid_states <- is.character(value_state) && !anyNA(value_state) &&
    all(value_state %in% states)
  if (!valid_states) {
    core_abort_argument("value_state", "expect.value_state", environment())
  }
  if (is.null(value)) {
    value <- rep(NA_real_, length(value_state))
  }
  numeric_like <- is.numeric(value) || is.logical(value) && all(is.na(value))
  if (!numeric_like || length(value) != length(value_state)) {
    core_abort_argument("value", "expect.value_state", environment())
  }
  value <- as.double(value)
  finite <- value_state == "finite"
  if (any(finite & !is.finite(value)) || any(!finite & !is.na(value))) {
    core_abort_argument("value", "expect.value_state", environment())
  }
  out <- value
  out[value_state == "missing"] <- NA_real_
  out[value_state == "nan"] <- NaN
  out[value_state == "pos_inf"] <- Inf
  out[value_state == "neg_inf"] <- -Inf
  out
}

#' @rdname qp_value_state
#' @export
qp_value_fields <- function(x) {
  if (!is.numeric(x) || length(x) != 1L) {
    core_abort_argument("x", "expect.numeric", environment())
  }
  state <- qp_value_state(unclass(x))
  value <- NULL
  if (state == "finite") {
    value <- core_mark_scalar(as.vector(unclass(x)))
  }
  list(value = value, value_state = core_mark_scalar(state))
}

# Encoder -------------------------------------------------------------------

core_mark_scalar <- function(x) {
  class(x) <- "scalar"
  x
}

core_json_encode <- function(x, canonical, path, depth) {
  if (depth >= core_json_max_depth) {
    core_json_unrepresentable(path, "nesting_too_deep")
  }
  if (is.null(x)) {
    return("null")
  }
  if (is.list(x)) {
    return(core_json_encode_list(x, canonical, path, depth))
  }
  if (!is.atomic(x) || is.raw(x) || !is.null(dim(x)) ||
        !all(oldClass(x) %in% core_json_atomic_classes)) {
    core_json_unrepresentable(path, "unsupported_type")
  }
  if (!is.null(names(x))) {
    core_json_unrepresentable(path, "named_vector")
  }
  scalar <- inherits(x, "scalar")
  if (scalar && length(x) != 1L) {
    core_json_unrepresentable(path, "scalar_length")
  }
  values <- core_json_format_atomic(unclass(x), canonical, path, scalar)
  if (scalar) values else paste0("[", paste(values, collapse = ","), "]")
}

core_json_encode_list <- function(x, canonical, path, depth) {
  if (is.object(x) || !is.null(dim(x))) {
    core_json_unrepresentable(path, "unsupported_type")
  }
  nms <- names(x)
  if (is.null(nms)) {
    if (length(x) == 0L) {
      return("[]")
    }
    parts <- vapply(seq_along(x), function(i) {
      core_json_encode(x[[i]], canonical, paste0(path, "/", i - 1L), depth + 1L)
    }, character(1))
    return(paste0("[", paste(parts, collapse = ","), "]"))
  }
  if (length(x) == 0L) {
    return("{}")
  }
  if (anyNA(nms) || !all(nzchar(nms))) {
    core_json_unrepresentable(path, "empty_member_name")
  }
  nms <- enc2utf8(nms)
  if (!all(validUTF8(nms))) {
    core_json_unrepresentable(path, "invalid_utf8")
  }
  dup <- anyDuplicated(nms)
  if (dup > 0L) {
    dup_path <- paste0(path, "/", core_pointer_escape(nms[[dup]]))
    core_json_unrepresentable(dup_path, "duplicate_key")
  }
  keys <- core_json_escape(nms)
  idx <- if (canonical) core_json_key_order(nms) else seq_along(x)
  parts <- vapply(idx, function(i) {
    member_path <- paste0(path, "/", core_pointer_escape(nms[[i]]))
    value <- core_json_encode(x[[i]], canonical, member_path, depth + 1L)
    paste0(keys[[i]], ":", value)
  }, character(1))
  paste0("{", paste(parts, collapse = ","), "}")
}

core_json_format_atomic <- function(x, canonical, path, scalar) {
  if (length(x) == 0L) {
    return(character())
  }
  element_path <- function(i) if (scalar) path else paste0(path, "/", i - 1L)
  out <- rep("null", length(x))
  if (is.logical(x)) {
    ok <- !is.na(x)
    out[ok] <- ifelse(x[ok], "true", "false")
  } else if (is.integer(x)) {
    ok <- !is.na(x)
    out[ok] <- as.character(x[ok])
  } else if (is.double(x)) {
    bad <- which(is.nan(x) | is.infinite(x))
    if (length(bad) > 0L) {
      core_json_unrepresentable(element_path(bad[[1L]]), "non_finite_number")
    }
    ok <- !is.na(x)
    out[ok] <- core_json_format_double(x[ok], canonical, element_path,
                                       which(ok))
  } else {
    ok <- !is.na(x)
    strings <- enc2utf8(x[ok])
    invalid <- which(!validUTF8(strings))
    if (length(invalid) > 0L) {
      position <- which(ok)[[invalid[[1L]]]]
      core_json_unrepresentable(element_path(position), "invalid_utf8")
    }
    out[ok] <- core_json_escape(strings)
  }
  out
}

core_json_format_double <- function(v, canonical, element_path, positions) {
  limit <- if (canonical) core_json_max_safe_integer else 2^53
  whole <- v == trunc(v) & abs(v) <= limit
  if (canonical && !all(whole)) {
    first <- which(!whole)[[1L]]
    reason <- if (v[[first]] == trunc(v[[first]])) {
      "integer_out_of_range"
    } else {
      "non_integer_number"
    }
    core_json_unrepresentable(element_path(positions[[first]]), reason)
  }
  out <- character(length(v))
  out[whole] <- sprintf("%.0f", v[whole])
  out[whole & v == 0] <- "0"
  rest <- which(!whole)
  for (digits in c(15L, 16L, 17L)) {
    if (length(rest) == 0L) {
      break
    }
    candidate <- sprintf(paste0("%.", digits, "g"), v[rest])
    back <- if (digits < 17L) core_json_read_numbers(candidate) else v[rest]
    same <- back == v[rest]
    out[rest[same]] <- candidate[same]
    rest <- rest[!same]
  }
  out
}

# Parse number literals with the same C-library conversion that jsonlite uses.
core_json_read_numbers <- function(literals) {
  array <- paste0("[", paste(literals, collapse = ","), "]")
  as.double(unlist(jsonlite::parse_json(array)))
}

core_json_escape <- function(x) {
  x <- gsub("\\", "\\\\", x, fixed = TRUE)
  x <- gsub("\"", "\\\"", x, fixed = TRUE)
  if (any(grepl("[\\x01-\\x1f]", x, perl = TRUE))) {
    short <- c("8" = "\\b", "9" = "\\t", "10" = "\\n", "12" = "\\f",
               "13" = "\\r")
    for (code in 1:31) {
      char <- intToUtf8(code)
      replacement <- short[as.character(code)]
      if (is.na(replacement)) {
        replacement <- sprintf("\\u%04x", code)
      }
      x <- gsub(char, replacement, x, fixed = TRUE)
    }
  }
  enc2utf8(paste0("\"", x, "\""))
}

# Byte order of UTF-8 equals code point order; hex strings of the bytes sort
# the same way under the locale-independent radix sort.
core_json_key_order <- function(keys) {
  hex <- vapply(keys, function(k) {
    paste(as.character(charToRaw(k)), collapse = "")
  }, character(1), USE.NAMES = FALSE)
  order(hex, method = "radix")
}

core_pointer_escape <- function(x) {
  gsub("/", "~1", gsub("~", "~0", x, fixed = TRUE), fixed = TRUE)
}

core_path_label <- function(path) {
  if (identical(path, "")) "(root)" else path
}

core_json_unrepresentable <- function(path, reason, call = NULL) {
  qp_abort(
    "JSON_UNREPRESENTABLE",
    path = core_path_label(path),
    reason = reason,
    details = list(path = path, reason = reason),
    call = call
  )
}

core_json_invalid <- function(reason, path = NULL, call = NULL) {
  details <- list(reason = reason)
  if (!is.null(path)) {
    details$path <- path
  }
  qp_abort("JSON_INVALID", reason = reason, details = details, call = call)
}

# Decoder -------------------------------------------------------------------

core_json_text <- function(txt, call) {
  if (is.raw(txt)) {
    if (any(txt == as.raw(0L))) {
      core_json_invalid("nul_byte", call = call)
    }
    txt <- rawToChar(txt)
  }
  if (!is.character(txt) || length(txt) != 1L || is.na(txt)) {
    core_abort_argument("txt", "expect.json_text", call)
  }
  txt <- as.vector(unclass(txt))
  if (!validUTF8(txt)) {
    core_json_invalid("invalid_utf8", call = call)
  }
  Encoding(txt) <- "UTF-8"
  if (startsWith(txt, "\ufeff")) {
    core_json_invalid("byte_order_mark", call = call)
  }
  txt
}

core_json_parse_tree <- function(txt, call) {
  txt <- core_json_text(txt, call)
  scan <- core_json_scan(txt)
  if (grepl("\\u", txt, fixed = TRUE)) {
    core_json_check_escapes(scan, call)
  }
  if (length(scan$bytes) > 2L * core_json_max_depth) {
    core_json_check_depth(scan, call)
  }
  core_json_check_grammar(scan, txt, call)
  tree <- tryCatch(
    jsonlite::parse_json(txt, simplifyVector = FALSE),
    error = function(e) core_json_invalid("syntax", call = call)
  )
  core_json_check_tree(tree, "", 0L, call)
  tree
}

# Text scan -------------------------------------------------------------------
#
# The checks before parsing need to know which bytes belong to string
# literals. They share one scan whose cost grows linearly with the text (a
# backtracking pattern that matches literals takes quadratic time on an
# unterminated string full of escaped quotes). Definition, identical in the
# Java reader (JsonTextScan):
# * a quotation mark delimits a string unless an odd number of reverse
#   solidi immediately precede it;
# * delimiters alternate between opening and closing a literal;
# * an opening delimiter without a closing one starts an unterminated string
#   that runs to the end of the text.
# All reserved characters are ASCII, so byte positions are exact in UTF-8.

core_json_scan <- function(txt) {
  bytes <- charToRaw(txt)
  quotes <- which(bytes == as.raw(0x22L))
  delims <- quotes[core_json_backslash_runs(bytes, quotes) %% 2L == 0L]
  list(bytes = bytes, delims = delims,
       unterminated = length(delims) %% 2L == 1L)
}

# Length of the run of reverse solidi that ends just before each position.
core_json_backslash_runs <- function(bytes, positions) {
  runs <- integer(length(positions))
  slash <- as.raw(0x5cL)
  hit <- positions > 1L
  hit[hit] <- bytes[positions[hit] - 1L] == slash
  if (!any(hit)) {
    return(runs)
  }
  slashes <- which(bytes == slash)
  starts <- slashes[c(TRUE, diff(slashes) != 1L)]
  ends <- positions[hit] - 1L
  runs[hit] <- ends - starts[findInterval(ends, starts)] + 1L
  runs
}

# TRUE where a position lies outside every string literal.
core_json_outside <- function(scan, positions) {
  findInterval(positions, scan$delims) %% 2L == 0L
}

# Byte positions of `values` in the text, outside string literals.
core_json_outside_positions <- function(scan, values) {
  found <- scan$bytes == as.raw(values[[1L]])
  for (value in values[-1L]) {
    found <- found | scan$bytes == as.raw(value)
  }
  positions <- which(found)
  positions[core_json_outside(scan, positions)]
}

# Escaped NUL characters and unpaired surrogate escapes inside complete string
# literals. Every NUL escape is reported before any surrogate problem. A high
# surrogate escape is paired only with a low surrogate escape that follows it
# directly in the text.
core_json_check_escapes <- function(scan, call) {
  bytes <- scan$bytes
  u <- which(bytes == as.raw(0x75L))
  u <- u[core_json_backslash_runs(bytes, u) %% 2L == 1L]
  open <- findInterval(u, scan$delims)
  u <- u[open %% 2L == 1L & open < length(scan$delims)]
  if (length(u) == 0L) {
    return(invisible(TRUE))
  }
  digit <- function(offset) {
    at <- u + offset
    out <- rep(0L, length(u))
    inside <- at <= length(bytes)
    out[inside] <- as.integer(bytes[at[inside]])
    out
  }
  h <- lapply(1:4, digit)
  if (any(h[[1]] == 0x30L & h[[2]] == 0x30L & h[[3]] == 0x30L &
            h[[4]] == 0x30L)) {
    core_json_unrepresentable("", "nul_character", call = call)
  }
  is_hex <- function(b) {
    b >= 0x30L & b <= 0x39L | b >= 0x41L & b <= 0x46L | b >= 0x61L & b <= 0x66L
  }
  surrogate <- (h[[1]] == 0x44L | h[[1]] == 0x64L) & is_hex(h[[3]]) &
    is_hex(h[[4]])
  high <- surrogate & h[[2]] %in% c(0x38L, 0x39L, 0x41L, 0x42L, 0x61L, 0x62L)
  low <- surrogate & h[[2]] %in% c(0x43:0x46, 0x63:0x66)
  if (!any(high | low)) {
    return(invisible(TRUE))
  }
  low_after <- low[match(u + 6L, u)]
  high_before <- high[match(u - 6L, u)]
  paired_high <- high & !is.na(low_after) & low_after
  paired_low <- low & !is.na(high_before) & high_before
  if (any(high & !paired_high | low & !paired_low)) {
    core_json_invalid("unpaired_surrogate", call = call)
  }
  invisible(TRUE)
}

# An unterminated string literal is a syntax error. The parser (yajl) accepts
# one after a complete value (`[1]"`), and it also accepts comments and the
# white space characters U+000B and U+000C between tokens; RFC 8259 and the
# Java bridge do not. Outside string literals a JSON text never contains "/",
# U+000B or U+000C.
core_json_check_grammar <- function(scan, txt, call) {
  if (scan$unterminated) {
    core_json_invalid("syntax", call = call)
  }
  if (grepl("[/\f\v]", txt, perl = TRUE) &&
        length(core_json_outside_positions(scan, c(0x2fL, 0x0bL, 0x0cL)))) {
    core_json_invalid("syntax", call = call)
  }
  invisible(TRUE)
}

core_json_check_depth <- function(scan, call) {
  brackets <- scan$bytes[core_json_outside_positions(
    scan, c(0x5bL, 0x5dL, 0x7bL, 0x7dL)
  )]
  if (length(brackets) == 0L) {
    return(invisible(TRUE))
  }
  opening <- brackets == as.raw(0x5bL) | brackets == as.raw(0x7bL)
  if (max(cumsum(ifelse(opening, 1L, -1L))) > core_json_max_depth) {
    core_json_invalid("nesting_too_deep", call = call)
  }
  invisible(TRUE)
}

# The text with every byte of every string literal, delimiters included,
# replaced by a space.
core_json_mask_strings <- function(scan) {
  bytes <- scan$bytes
  delims <- scan$delims
  if (length(delims) > 0L) {
    odd <- seq_along(delims) %% 2L == 1L
    opening <- delims[odd]
    closing <- delims[!odd]
    if (scan$unterminated) {
      closing <- c(closing, length(bytes))
    }
    bytes[sequence(closing - opening + 1L, from = opening)] <- as.raw(0x20L)
  }
  rawToChar(bytes)
}

core_json_check_tree <- function(node, path, depth, call) {
  if (depth >= core_json_max_depth) {
    core_json_invalid("nesting_too_deep", call = call)
  }
  if (is.list(node)) {
    nms <- names(node)
    if (!is.null(nms)) {
      if (!all(nzchar(nms))) {
        core_json_unrepresentable(paste0(path, "/"), "empty_member_name",
                                  call = call)
      }
      dup <- anyDuplicated(nms)
      if (dup > 0L) {
        dup_path <- paste0(path, "/", core_pointer_escape(nms[[dup]]))
        core_json_invalid("duplicate_key", dup_path, call = call)
      }
    }
    for (i in seq_along(node)) {
      segment <- if (is.null(nms)) i - 1L else core_pointer_escape(nms[[i]])
      core_json_check_tree(node[[i]], paste0(path, "/", segment), depth + 1L,
                           call)
    }
  } else if (is.double(node) && !is.finite(node)) {
    core_json_unrepresentable(path, "number_out_of_range", call = call)
  }
  invisible(TRUE)
}

core_json_from_tree <- function(node) {
  if (is.null(node)) {
    return(NULL)
  }
  if (!is.list(node)) {
    return(core_mark_scalar(node))
  }
  nms <- names(node)
  if (!is.null(nms)) {
    out <- lapply(node, core_json_from_tree)
    names(out) <- nms
    return(out)
  }
  if (length(node) == 0L) {
    return(list())
  }
  leaf <- vapply(node, function(e) is.null(e) || !is.list(e), logical(1))
  if (all(leaf)) {
    types <- vapply(node, function(e) {
      if (is.null(e)) "null" else typeof(e)
    }, character(1))
    kinds <- unique(types[types != "null"])
    kinds[kinds == "integer" | kinds == "double"] <- "number"
    kinds <- unique(kinds)
    if (length(kinds) <= 1L) {
      return(core_json_atomic_array(node, types, kinds))
    }
  }
  lapply(node, core_json_from_tree)
}

core_json_atomic_array <- function(node, types, kinds) {
  present <- types != "null"
  kind <- if (length(kinds) == 0L) "logical" else kinds
  out <- switch(kind,
    logical = rep(NA, length(node)),
    character = rep(NA_character_, length(node)),
    number = if (all(types[present] == "integer")) {
      rep(NA_integer_, length(node))
    } else {
      rep(NA_real_, length(node))
    }
  )
  out[present] <- unlist(node[present], use.names = FALSE)
  out
}

core_json_check_integers <- function(txt, call) {
  stripped <- core_json_mask_strings(core_json_scan(txt))
  tokens <- regmatches(stripped, gregexpr("-?[0-9][0-9.eE+-]*", stripped,
                                          useBytes = TRUE))[[1]]
  if (any(grepl("[.eE]", tokens))) {
    core_json_unrepresentable("", "non_integer_number", call = call)
  }
  digits <- sub("^-", "", tokens)
  too_long <- nchar(digits) > 16L
  # A token such as "1-2" is no number (NA); the parser reports it as syntax.
  too_large <- grepl("^[0-9]+$", digits) &
    suppressWarnings(as.double(digits)) > core_json_max_safe_integer
  if (any(too_long) || any(too_large, na.rm = TRUE)) {
    core_json_unrepresentable("", "integer_out_of_range", call = call)
  }
  invisible(TRUE)
}

core_read_json_tree <- function(path) {
  size <- file.size(path)
  bytes <- readBin(path, what = "raw", n = size)
  core_json_parse_tree(bytes, call = NULL)
}
