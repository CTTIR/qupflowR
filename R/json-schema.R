# JSON Schema subset validator ---------------------------------------------
#
# Draft-07 subset shared with the Java bridge (decision D022). Only the
# keywords in core_schema_keywords are allowed; a schema using any other
# keyword is rejected when it is loaded. References are local: a fragment
# ("#/definitions/x") or a relative file inside the schema root, never a URL.

core_schema_keywords <- c(
  "$schema", "$id", "$ref", "definitions", "title", "description", "type",
  "properties", "required", "additionalProperties", "items", "minItems",
  "maxItems", "uniqueItems", "enum", "const", "minimum", "maximum",
  "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern",
  "format", "oneOf", "anyOf", "allOf", "not"
)
core_schema_formats <- c("uuid", "date-time", "sha256", "uri-reference")
core_schema_types <- c(
  "null", "boolean", "object", "array", "number", "integer", "string"
)
core_schema_draft <- "http://json-schema.org/draft-07/schema#"

#' Locate an installed JSON Schema
#'
#' @param name Schema name without extension, for example `"health"` or
#'   `"patch-request"`.
#' @param set Schema set, a directory below `inst/schema` (default
#'   `"protocol"`).
#' @param version Major version directory (default `"v1"`).
#' @return The absolute path of `<set>/<version>/<name>.schema.json` inside
#'   the installed package. A missing schema signals a `qp_validation_error`
#'   with code `SCHEMA_INVALID`.
#' @export
#' @examples
#' basename(qp_schema_path("envelope-error"))
qp_schema_path <- function(name, set = "protocol", version = "v1") {
  call <- environment()
  segment <- "^[a-z0-9][a-z0-9-]*$"
  for (arg in c("name", "set", "version")) {
    value <- get(arg)
    if (!core_is_string(value) || !grepl(segment, value)) {
      core_abort_argument(arg, "expect.schema", call)
    }
  }
  file <- paste0(name, ".schema.json")
  path <- system.file("schema", set, version, file, package = "qupflowR")
  if (!nzchar(path)) {
    qp_abort("SCHEMA_INVALID", schema = file, reason = "not_found",
             details = list(schema = file, reason = "not_found"), call = call)
  }
  path
}

#' Validate JSON against a schema
#'
#' Validates a value with the project's Draft-07 subset validator, the same
#' rules the Java bridge applies to the shared fixture corpus.
#'
#' Supported keywords: `$schema`, `$id`, `$ref` (local only), `definitions`,
#' `title`, `description`, `type`, `properties`, `required`,
#' `additionalProperties`, `items` (single schema), `minItems`, `maxItems`,
#' `uniqueItems`, `enum`, `const`, `minimum`, `maximum`, `exclusiveMinimum`,
#' `exclusiveMaximum`, `minLength`, `maxLength`, `pattern`, `format`,
#' `oneOf`, `anyOf`, `allOf` and `not`. Formats are asserted: `uuid`
#' (lower-case 8-4-4-4-12 hex), `sha256` (64 lower-case hex digits),
#' `date-time` (RFC 3339 with upper-case `T` and `Z`, seconds 00-59) and
#' `uri-reference` (no white space, control characters, `<`, `>`, `"`, `{`,
#' `}`, `|`, backslash, `^` or backtick, and valid percent escapes).
#'
#' Reporting rules, shared with Java: every failing keyword is reported with
#' the JSON pointer of the instance location. `required` is reported at the
#' object, `additionalProperties: false` at the extra member and a `false`
#' schema with the keyword `false`. `oneOf`, `anyOf` and `not` report a
#' single error at their location and none of their branch errors. Errors
#' of `$ref` targets and `allOf` branches are reported as they are. Keywords
#' that do not apply to the instance type are ignored, as in Draft-07.
#'
#' @param x Value to validate: an R value (it is encoded with
#'   [qp_json_encode()] first) or JSON text marked with [qp_as_json()].
#' @param schema Schema name for [qp_schema_path()] or the path of a schema
#'   file.
#' @return A `qp_validation_report`: a list with `valid` (logical), `schema`
#'   (file name) and `errors`, a tibble with the character columns `path`
#'   (JSON pointer, `""` for the root), `keyword`, `message` and
#'   `schema_path` (file and JSON pointer of the failing keyword), sorted by
#'   `path` and `keyword`. An unsupported or unresolvable schema signals a
#'   `qp_validation_error` with code `SCHEMA_INVALID`.
#' @seealso [qp_schema_assert()]
#' @export
#' @examples
#' health <- qp_as_json(paste0(
#'   '{"status":"ready","host_type":"worker","qupath_version":"0.7.0",',
#'   '"bridge_version":"0.1.0-SNAPSHOT","protocol_version":"1.0",',
#'   '"fx_thread_blocked":true}'
#' ))
#' report <- qp_schema_validate(health, "health")
#' report$valid
#' report$errors
qp_schema_validate <- function(x, schema) {
  call <- environment()
  target <- core_schema_target(schema, call)
  txt <- if (inherits(x, "json")) x else qp_json_encode(x)
  tree <- core_json_parse_tree(txt, call = call)
  ctx <- core_schema_context(target$root)
  doc <- core_schema_document(target$path, ctx)
  root <- list(file = target$path, pointer = "")
  raw <- core_schema_eval(tree, doc, "", root, ctx)
  new_qp_validation_report(core_schema_error_table(raw),
                           basename(target$path))
}

#' Assert that JSON matches a schema
#'
#' @inheritParams qp_schema_validate
#' @param call Environment reported as the call of the error.
#' @return `x`, invisibly, when it is valid. Otherwise a
#'   `qp_validation_error` with code `VALIDATION_FAILED` is signalled; its
#'   `details` hold `schema` and up to 20 `errors` (each with `path`,
#'   `keyword` and `message`).
#' @export
#' @examples
#' try(qp_schema_assert(list(status = qp_scalar("ready")), "health"))
qp_schema_assert <- function(x, schema, call = parent.frame()) {
  report <- qp_schema_validate(x, schema)
  if (report$valid) {
    return(invisible(x))
  }
  errors <- report$errors
  shown <- utils::head(errors, 5L)
  lines <- vapply(seq_len(nrow(shown)), function(i) {
    qp_msg("condition.validation_error",
           path = core_path_label(shown$path[[i]]),
           message = shown$message[[i]])
  }, character(1))
  bullets <- stats::setNames(lines, rep("x", nrow(shown)))
  hidden <- nrow(errors) - nrow(shown)
  if (hidden > 0L) {
    bullets <- c(bullets, i = qp_msg("condition.more_errors", n = hidden))
  }
  listed <- utils::head(errors, 20L)
  qp_abort(
    "VALIDATION_FAILED",
    message_key = "validation.invalid",
    schema = report$schema,
    n = nrow(errors),
    details = list(
      schema = report$schema,
      errors = lapply(seq_len(nrow(listed)), function(i) {
        list(path = listed$path[[i]], keyword = listed$keyword[[i]],
             message = listed$message[[i]])
      })
    ),
    bullets = bullets,
    call = call
  )
}

new_qp_validation_report <- function(errors, schema) {
  structure(
    list(valid = nrow(errors) == 0L, schema = schema, errors = errors),
    class = "qp_validation_report"
  )
}

#' @export
format.qp_validation_report <- function(x, ...) {
  if (isTRUE(x$valid)) {
    return(qp_msg("validation.valid", schema = x$schema))
  }
  errors <- x$errors
  paths <- vapply(errors$path, core_path_label, character(1),
                  USE.NAMES = FALSE)
  c(
    qp_msg("validation.invalid", schema = x$schema, n = nrow(errors)),
    sprintf("* %s [%s] %s", paths, errors$keyword, errors$message)
  )
}

#' @export
print.qp_validation_report <- function(x, ...) {
  cat(format(x), sep = "\n")
  invisible(x)
}

# Schema loading -----------------------------------------------------------

core_schema_target <- function(schema, call) {
  if (!core_is_string(schema)) {
    core_abort_argument("schema", "expect.schema", call)
  }
  path <- if (grepl("[.]json$", schema) && file.exists(schema)) {
    schema
  } else {
    qp_schema_path(schema)
  }
  path <- normalizePath(path, winslash = "/", mustWork = TRUE)
  installed <- system.file("schema", package = "qupflowR")
  root <- ""
  if (nzchar(installed)) {
    root <- normalizePath(installed, winslash = "/")
  }
  if (!nzchar(root) || !startsWith(path, paste0(root, "/"))) {
    root <- dirname(path)
  }
  list(path = path, root = root)
}

core_schema_context <- function(root) {
  ctx <- new.env(parent = emptyenv())
  ctx$root <- root
  ctx$stack <- character()
  ctx
}

# Parsed and checked schema documents are cached per path, keyed by size and
# modification time so an edited file is read again.
core_schema_document <- function(path, ctx) {
  info <- file.info(path)
  stamp <- paste(info$size, format(as.numeric(info$mtime), digits = 17))
  cache <- core_cache$schemas
  if (is.null(cache)) {
    cache <- list()
  }
  hit <- cache[[path]]
  if (!is.null(hit) && identical(hit$stamp, stamp)) {
    return(hit$tree)
  }
  tree <- tryCatch(
    core_read_json_tree(path),
    qp_error = function(e) {
      core_schema_invalid(path, "unreadable_json",
                          list(problem = e$details$reason))
    }
  )
  issues <- core_schema_lint_tree(tree)
  if (nrow(issues) > 0L) {
    core_schema_invalid(path, issues$problem[[1L]],
                        list(issues = utils::head(issues, 20L)))
  }
  cache[[path]] <- list(stamp = stamp, tree = tree)
  core_cache$schemas <- cache
  tree
}

core_schema_invalid <- function(path, reason, extra = list()) {
  qp_abort(
    "SCHEMA_INVALID",
    schema = basename(path),
    reason = reason,
    details = c(list(schema = basename(path), reason = reason), extra),
    call = NULL
  )
}

# Structural checks of a schema document: returns a data frame of issues
# with the schema pointer, the keyword and a machine-readable problem code.
core_schema_lint_tree <- function(tree) {
  issues <- new.env(parent = emptyenv())
  issues$rows <- list()
  core_schema_lint_node(tree, "", TRUE, issues)
  rows <- issues$rows
  field <- function(name) vapply(rows, function(r) r[[name]], character(1))
  data.frame(
    pointer = field("pointer"),
    keyword = field("keyword"),
    problem = field("problem"),
    stringsAsFactors = FALSE
  )
}

core_schema_lint_node <- function(node, pointer, is_root, issues) {
  add <- function(keyword, problem) {
    issues$rows[[length(issues$rows) + 1L]] <- list(
      pointer = pointer, keyword = keyword, problem = problem
    )
  }
  if (is.logical(node) && length(node) == 1L && !is.na(node)) {
    return(invisible(TRUE))
  }
  if (!is.list(node) || is.null(names(node))) {
    add("", "schema_not_object")
    return(invisible(TRUE))
  }
  keys <- names(node)
  core_schema_lint_keys(node, keys, is_root, add)
  core_schema_lint_values(node, keys, add)
  child <- function(value, suffix) {
    core_schema_lint_node(value, paste0(pointer, suffix), FALSE, issues)
  }
  for (container in intersect(keys, c("definitions", "properties"))) {
    members <- node[[container]]
    if (!is.list(members) || is.null(names(members))) {
      add(container, "members_not_object")
      next
    }
    for (name in names(members)) {
      suffix <- paste0("/", container, "/", core_pointer_escape(name))
      child(members[[name]], suffix)
    }
  }
  for (single in intersect(keys, c("items", "not", "additionalProperties"))) {
    value <- node[[single]]
    tuple <- is.list(value) && length(value) > 0L && is.null(names(value))
    if (single == "items" && tuple) {
      add(single, "tuple_items_unsupported")
    } else {
      child(value, paste0("/", single))
    }
  }
  for (combinator in intersect(keys, c("oneOf", "anyOf", "allOf"))) {
    branches <- node[[combinator]]
    if (!is.list(branches) || length(branches) == 0L ||
          !is.null(names(branches))) {
      add(combinator, "branches_not_array")
      next
    }
    for (j in seq_along(branches)) {
      child(branches[[j]], paste0("/", combinator, "/", j - 1L))
    }
  }
  invisible(TRUE)
}

core_schema_lint_keys <- function(node, keys, is_root, add) {
  for (key in setdiff(keys, core_schema_keywords)) {
    add(key, "unsupported_keyword")
  }
  if (!is_root) {
    for (key in intersect(keys, c("$schema", "$id"))) {
      add(key, "root_only_keyword")
    }
  }
  if (is_root && !identical(node[["$schema"]], core_schema_draft)) {
    add("$schema", "draft07_required")
  }
  if ("$ref" %in% keys) {
    if (!core_is_string(node[["$ref"]])) {
      add("$ref", "ref_not_string")
    }
    allowed <- c("$ref", "title", "description", "$schema", "$id",
                 "definitions")
    if (length(setdiff(keys, allowed)) > 0L) {
      add("$ref", "ref_with_siblings")
    }
  }
  invisible(TRUE)
}

core_schema_lint_values <- function(node, keys, add) {
  is_count <- function(v) {
    is.numeric(v) && length(v) == 1L && v >= 0 && v == trunc(v)
  }
  is_string_array <- function(v) {
    is.list(v) && is.null(names(v)) &&
      all(vapply(v, core_is_string, logical(1))) &&
      anyDuplicated(unlist(v)) == 0L
  }
  counts <- c("minItems", "maxItems", "minLength", "maxLength")
  for (key in intersect(keys, counts)) {
    if (!is_count(node[[key]])) add(key, "not_a_count")
  }
  bounds <- c("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")
  for (key in intersect(keys, bounds)) {
    value <- node[[key]]
    if (!(is.numeric(value) && length(value) == 1L)) add(key, "not_a_number")
  }
  for (key in intersect(keys, c("title", "description", "$id"))) {
    if (!core_is_string(node[[key]])) add(key, "not_a_string")
  }
  unique_items <- node[["uniqueItems"]]
  if ("uniqueItems" %in% keys &&
        !(is.logical(unique_items) && length(unique_items) == 1L)) {
    add("uniqueItems", "not_a_boolean")
  }
  if ("type" %in% keys) {
    type <- node[["type"]]
    listed <- core_is_string(type) ||
      (is_string_array(type) && length(type) > 0L)
    if (!(listed && all(unlist(type) %in% core_schema_types))) {
      add("type", "invalid_type")
    }
  }
  if ("required" %in% keys && !is_string_array(node[["required"]])) {
    add("required", "invalid_required")
  }
  enum <- node[["enum"]]
  if ("enum" %in% keys &&
        (!is.list(enum) || length(enum) == 0L || !is.null(names(enum)))) {
    add("enum", "invalid_enum")
  }
  format <- node[["format"]]
  if ("format" %in% keys &&
        !(core_is_string(format) && format %in% core_schema_formats)) {
    add("format", "unsupported_format")
  }
  if ("pattern" %in% keys) {
    problem <- core_schema_pattern_problem(node[["pattern"]])
    if (!is.null(problem)) add("pattern", problem)
  }
  invisible(TRUE)
}

# Patterns must mean the same in PCRE (R), java.util.regex and ECMA-262: no
# shorthand classes, look-around, back references, inline flags, possessive
# quantifiers or unescaped "." (line terminators differ); "^" only first and
# "$" only last. Non-capturing groups "(?:" are allowed.
core_schema_pattern_problem <- function(pattern) {
  if (!core_is_string(pattern)) {
    return("not_a_string")
  }
  if (core_schema_pattern_ambiguous(pattern)) {
    return("pattern_not_portable")
  }
  compiles <- tryCatch({
    grepl(pattern, "", perl = TRUE)
    TRUE
  }, error = function(e) FALSE, warning = function(w) FALSE)
  if (!compiles) {
    return("pattern_does_not_compile")
  }
  bare <- gsub("\\\\", "", pattern, fixed = TRUE)
  non_portable <- c(
    "\\\\[dDwWsSbBpPhHvVkKQEAzZGRX1-9]",
    "[(][?](?!:)",
    "[*+?}][+]"
  )
  if (any(vapply(non_portable, grepl, logical(1), x = bare, perl = TRUE))) {
    return("pattern_not_portable")
  }
  unescaped <- gsub("\\\\.", "", pattern, perl = TRUE)
  skeleton <- gsub("\\[\\^?\\]?[^]]*\\]", "", unescaped, perl = TRUE)
  inner <- substr(skeleton, 2L, nchar(skeleton) - 1L)
  misplaced_anchor <- grepl("[$^]", inner) || startsWith(skeleton, "$") ||
    (nchar(skeleton) > 1L && endsWith(skeleton, "^"))
  if (grepl(".", skeleton, fixed = TRUE) || misplaced_anchor) {
    return("pattern_not_portable")
  }
  NULL
}

# Constructs that one of the engines refuses or reads differently, checked
# before compiling so that R and Java report the same problem: the escapes
# \0, \C, \g, \N and \o; "[" inside a bracket expression (POSIX classes,
# Java unions) and "&&" there (Java intersections); and a "{" outside
# brackets that does not start a quantifier {n}, {n,} or {n,m} (a literal in
# PCRE, an error in Java).
core_schema_pattern_ambiguous <- function(pattern) {
  chars <- strsplit(pattern, "", fixed = TRUE)[[1]]
  n <- length(chars)
  in_bracket <- FALSE
  i <- 1L
  while (i <= n) {
    char <- chars[[i]]
    following <- if (i < n) chars[[i + 1L]] else ""
    if (char == "\\") {
      if (following %in% c("0", "C", "g", "N", "o")) {
        return(TRUE)
      }
      i <- i + 2L
      next
    }
    if (in_bracket) {
      if (char == "[" || (char == "&" && following == "&")) {
        return(TRUE)
      }
      in_bracket <- char != "]"
    } else if (char == "[") {
      in_bracket <- TRUE
      if (following == "^") {
        i <- i + 1L
        following <- if (i < n) chars[[i + 1L]] else ""
      }
      if (following == "]") {
        i <- i + 1L
      }
    } else if (char == "{") {
      rest <- paste(chars[i:n], collapse = "")
      if (!grepl("^[{][0-9]+(,[0-9]*)?[}]", rest)) {
        return(TRUE)
      }
    }
    i <- i + 1L
  }
  FALSE
}

# ECMA-262 "$" (without the multiline flag) matches only at the end of the
# input, while PCRE "$" also matches before a final newline. A final
# unescaped "$" is therefore evaluated as "\z" in R; the Java validator does
# the same.
core_schema_pcre <- function(pattern) {
  if (grepl("(^|[^\\\\])(\\\\\\\\)*[$]$", pattern, perl = TRUE)) {
    pattern <- paste0(substr(pattern, 1L, nchar(pattern) - 1L), "\\z")
  }
  pattern
}

# References ---------------------------------------------------------------

core_schema_resolve_ref <- function(ref, sref, ctx) {
  hash <- regexpr("#", ref, fixed = TRUE)
  file_part <- if (hash > 0L) substr(ref, 1L, hash - 1L) else ref
  fragment <- if (hash > 0L) substring(ref, hash + 1L) else ""
  non_local <- grepl("^[A-Za-z][A-Za-z0-9+.-]*:", file_part) ||
    startsWith(file_part, "/") || grepl("\\", file_part, fixed = TRUE)
  if (non_local) {
    core_schema_invalid(sref$file, "non_local_ref", list(ref = ref))
  }
  file <- sref$file
  if (nzchar(file_part)) {
    file <- core_schema_ref_file(file_part, sref, ctx, ref)
  }
  doc <- core_schema_document(file, ctx)
  found <- core_json_pointer_get(doc, fragment)
  if (!found$found) {
    core_schema_invalid(sref$file, "unresolved_ref", list(ref = ref))
  }
  list(node = found$node, file = file, pointer = fragment)
}

core_schema_ref_file <- function(file_part, sref, ctx, ref) {
  inside <- function(path) startsWith(path, paste0(ctx$root, "/"))
  file <- core_path_collapse(paste0(dirname(sref$file), "/", file_part))
  if (!inside(file)) {
    core_schema_invalid(sref$file, "ref_outside_schema_root", list(ref = ref))
  }
  if (!file.exists(file)) {
    core_schema_invalid(sref$file, "unresolved_ref", list(ref = ref))
  }
  file <- normalizePath(file, winslash = "/", mustWork = TRUE)
  if (!inside(file)) {
    core_schema_invalid(sref$file, "ref_outside_schema_root", list(ref = ref))
  }
  file
}

# Removes "." and ".." segments lexically, so a reference cannot leave the
# schema root through a path that does not exist.
core_path_collapse <- function(path) {
  parts <- strsplit(path, "/", fixed = TRUE)[[1]]
  out <- character()
  for (part in parts[-1L]) {
    if (part %in% c("", ".")) {
      next
    }
    out <- if (part == "..") utils::head(out, -1L) else c(out, part)
  }
  paste0(parts[[1L]], "/", paste(out, collapse = "/"))
}

core_json_pointer_get <- function(doc, pointer) {
  missing <- list(found = FALSE, node = NULL)
  if (identical(pointer, "")) {
    return(list(found = TRUE, node = doc))
  }
  if (!startsWith(pointer, "/")) {
    return(missing)
  }
  body <- paste0(substring(pointer, 2L), "/")
  node <- doc
  for (raw in strsplit(body, "/", fixed = TRUE)[[1]]) {
    segment <- core_pointer_percent_decode(raw)
    if (is.null(segment) || !is.list(node)) {
      return(missing)
    }
    segment <- gsub("~1", "/", segment, fixed = TRUE)
    segment <- gsub("~0", "~", segment, fixed = TRUE)
    pos <- NA_integer_
    if (!is.null(names(node))) {
      pos <- match(segment, names(node))
    } else if (grepl("^(0|[1-9][0-9]{0,8})$", segment)) {
      pos <- as.integer(segment) + 1L
    }
    if (is.na(pos) || pos > length(node)) {
      return(missing)
    }
    node <- node[[pos]]
  }
  list(found = TRUE, node = node)
}

# Percent-decodes a pointer segment as UTF-8. A "%" not followed by two hex
# digits, or bytes that are not UTF-8 (including NUL), give NULL: such a
# segment names no member.
core_pointer_percent_decode <- function(segment) {
  if (!grepl("%", segment, fixed = TRUE)) {
    return(segment)
  }
  codes <- as.integer(charToRaw(segment))
  digit <- function(code) {
    if (code >= 48L && code <= 57L) {
      return(code - 48L)
    }
    lower <- bitwOr(code, 32L)
    if (lower >= 97L && lower <= 102L) lower - 87L else NA_integer_
  }
  out <- integer()
  i <- 1L
  n <- length(codes)
  while (i <= n) {
    if (codes[[i]] != 37L) {
      out <- c(out, codes[[i]])
      i <- i + 1L
      next
    }
    value <- if (i + 2L <= n) {
      16L * digit(codes[[i + 1L]]) + digit(codes[[i + 2L]])
    } else {
      NA_integer_
    }
    if (is.na(value)) {
      return(NULL)
    }
    out <- c(out, value)
    i <- i + 3L
  }
  if (any(out == 0L)) {
    return(NULL)
  }
  out <- as.raw(out)
  decoded <- rawToChar(out)
  if (!validUTF8(decoded)) {
    return(NULL)
  }
  Encoding(decoded) <- "UTF-8"
  decoded
}

# Evaluation ---------------------------------------------------------------

core_schema_err <- function(path, keyword, sref, key, args = list()) {
  list(path = path, keyword = keyword,
       schema_path = paste0(sref$pointer, "/", keyword),
       file = sref$file, key = key, args = args)
}

core_schema_child <- function(sref, ...) {
  suffix <- paste(core_pointer_escape(c(...)), collapse = "/")
  list(file = sref$file, pointer = paste0(sref$pointer, "/", suffix))
}

core_json_kind <- function(x) {
  if (is.null(x)) {
    return("null")
  }
  if (is.list(x)) {
    return(if (is.null(names(x))) "array" else "object")
  }
  switch(typeof(x), character = "string", logical = "boolean", "number")
}

core_schema_type_ok <- function(inst, kind, types) {
  if (kind %in% types) {
    return(TRUE)
  }
  kind == "number" && "integer" %in% types && inst == trunc(inst)
}

core_schema_eval <- function(inst, node, path, sref, ctx) {
  if (isTRUE(node)) {
    return(list())
  }
  if (isFALSE(node)) {
    return(list(core_schema_err(path, "false", sref,
                                "validation.false_schema")))
  }
  if (!is.null(node) && !is.list(node)) {
    # Only reachable through a $ref to a string or number; a null or array
    # target has no keywords and accepts every instance, as in Java.
    core_schema_invalid(sref$file, "schema_not_object",
                        list(pointer = sref$pointer))
  }
  if (!is.null(node[["$ref"]])) {
    return(core_schema_eval_ref(inst, node[["$ref"]], path, sref, ctx))
  }
  kind <- core_json_kind(inst)
  keys <- names(node)
  errs <- core_schema_eval_generic(inst, kind, node, keys, path, sref)
  errs <- c(errs, switch(kind,
    object = core_schema_eval_object(inst, node, keys, path, sref, ctx),
    array = core_schema_eval_array(inst, node, keys, path, sref, ctx),
    number = core_schema_eval_number(inst, node, keys, path, sref),
    string = core_schema_eval_string(inst, node, keys, path, sref),
    list()
  ))
  c(errs, core_schema_eval_combinators(inst, node, keys, path, sref, ctx))
}

core_schema_eval_ref <- function(inst, ref, path, sref, ctx) {
  target <- core_schema_resolve_ref(ref, sref, ctx)
  key <- paste0(target$file, "#", target$pointer, "@", path)
  if (key %in% ctx$stack) {
    core_schema_invalid(sref$file, "circular_ref", list(ref = ref))
  }
  ctx$stack <- c(ctx$stack, key)
  on.exit(ctx$stack <- setdiff(ctx$stack, key))
  core_schema_eval(inst, target$node, path,
                   list(file = target$file, pointer = target$pointer), ctx)
}

# Accumulates the errors of one schema node: add() records a failing keyword
# at the node's instance path (or at `at`), merge() appends nested results.
core_schema_collector <- function(path, sref) {
  env <- new.env(parent = emptyenv())
  env$errors <- list()
  env$add <- function(keyword, key, args = list(), at = path) {
    err <- core_schema_err(at, keyword, sref, key, args)
    env$errors[[length(env$errors) + 1L]] <- err
  }
  env$merge <- function(errors) {
    env$errors <- c(env$errors, errors)
  }
  env
}

core_schema_eval_generic <- function(inst, kind, node, keys, path, sref) {
  out <- core_schema_collector(path, sref)
  if ("type" %in% keys) {
    types <- as.character(unlist(node[["type"]]))
    if (!core_schema_type_ok(inst, kind, types)) {
      actual <- kind
      if (kind == "number" && inst == trunc(inst)) {
        actual <- "integer"
      }
      out$add("type", "validation.type",
              list(expected = paste(types, collapse = " | "),
                   actual = actual))
    }
  }
  if ("enum" %in% keys) {
    allowed <- vapply(node[["enum"]], core_json_tree_key, character(1))
    if (!core_json_tree_key(inst) %in% allowed) {
      out$add("enum", "validation.enum",
              list(allowed = paste(allowed, collapse = ", ")))
    }
  }
  if ("const" %in% keys) {
    expected <- core_json_tree_key(node[["const"]])
    if (!identical(core_json_tree_key(inst), expected)) {
      out$add("const", "validation.const", list(expected = expected))
    }
  }
  out$errors
}

core_schema_eval_object <- function(inst, node, keys, path, sref, ctx) {
  out <- core_schema_collector(path, sref)
  members <- names(inst)
  for (property in as.character(unlist(node[["required"]]))) {
    if (!property %in% members) {
      out$add("required", "validation.required", list(property = property))
    }
  }
  properties <- node[["properties"]]
  additional <- TRUE
  if ("additionalProperties" %in% keys) {
    additional <- node[["additionalProperties"]]
  }
  for (i in seq_along(inst)) {
    name <- members[[i]]
    child_path <- paste0(path, "/", core_pointer_escape(name))
    pos <- match(name, names(properties))
    if (!is.na(pos)) {
      child <- core_schema_child(sref, "properties", name)
      out$merge(core_schema_eval(inst[[i]], properties[[pos]], child_path,
                                 child, ctx))
    } else if (isFALSE(additional)) {
      out$add("additionalProperties", "validation.additional_properties",
              list(property = name), at = child_path)
    } else if (!isTRUE(additional)) {
      child <- core_schema_child(sref, "additionalProperties")
      out$merge(core_schema_eval(inst[[i]], additional, child_path, child,
                                 ctx))
    }
  }
  out$errors
}

core_schema_eval_array <- function(inst, node, keys, path, sref, ctx) {
  out <- core_schema_collector(path, sref)
  n <- length(inst)
  if ("minItems" %in% keys && n < node[["minItems"]]) {
    out$add("minItems", "validation.min_items",
            list(limit = node[["minItems"]]))
  }
  if ("maxItems" %in% keys && n > node[["maxItems"]]) {
    out$add("maxItems", "validation.max_items",
            list(limit = node[["maxItems"]]))
  }
  if (isTRUE(node[["uniqueItems"]]) && n > 1L) {
    item_keys <- vapply(inst, core_json_tree_key, character(1))
    dup <- anyDuplicated(item_keys)
    if (dup > 0L) {
      first <- match(item_keys[[dup]], item_keys) - 1L
      out$add("uniqueItems", "validation.unique_items",
              list(first = first, second = dup - 1L))
    }
  }
  if ("items" %in% keys) {
    child <- core_schema_child(sref, "items")
    for (i in seq_len(n)) {
      item_path <- paste0(path, "/", i - 1L)
      out$merge(core_schema_eval(inst[[i]], node[["items"]], item_path,
                                 child, ctx))
    }
  }
  out$errors
}

core_schema_eval_number <- function(inst, node, keys, path, sref) {
  out <- core_schema_collector(path, sref)
  fails <- list(
    minimum = function(limit) inst < limit,
    maximum = function(limit) inst > limit,
    exclusiveMinimum = function(limit) inst <= limit,
    exclusiveMaximum = function(limit) inst >= limit
  )
  message_keys <- c(
    minimum = "validation.minimum", maximum = "validation.maximum",
    exclusiveMinimum = "validation.exclusive_minimum",
    exclusiveMaximum = "validation.exclusive_maximum"
  )
  for (keyword in intersect(names(fails), keys)) {
    limit <- node[[keyword]]
    if (fails[[keyword]](limit)) {
      out$add(keyword, message_keys[[keyword]], list(limit = limit))
    }
  }
  out$errors
}

core_schema_eval_string <- function(inst, node, keys, path, sref) {
  out <- core_schema_collector(path, sref)
  length_chars <- nchar(inst, type = "chars")
  if ("minLength" %in% keys && length_chars < node[["minLength"]]) {
    out$add("minLength", "validation.min_length",
            list(limit = node[["minLength"]]))
  }
  if ("maxLength" %in% keys && length_chars > node[["maxLength"]]) {
    out$add("maxLength", "validation.max_length",
            list(limit = node[["maxLength"]]))
  }
  pattern <- node[["pattern"]]
  if ("pattern" %in% keys &&
        !grepl(core_schema_pcre(pattern), inst, perl = TRUE)) {
    out$add("pattern", "validation.pattern", list(pattern = pattern))
  }
  format <- node[["format"]]
  if ("format" %in% keys && !core_schema_format_ok(inst, format)) {
    out$add("format", "validation.format", list(format = format))
  }
  out$errors
}

core_schema_eval_combinators <- function(inst, node, keys, path, sref, ctx) {
  out <- core_schema_collector(path, sref)
  branch_errors <- function(keyword, j) {
    branch <- if (is.null(j)) node[[keyword]] else node[[keyword]][[j]]
    suffix <- if (is.null(j)) keyword else c(keyword, j - 1L)
    core_schema_eval(inst, branch, path, core_schema_child(sref, suffix), ctx)
  }
  for (j in seq_along(node[["allOf"]])) {
    out$merge(branch_errors("allOf", j))
  }
  if ("anyOf" %in% keys) {
    matched <- FALSE
    for (j in seq_along(node[["anyOf"]])) {
      if (length(branch_errors("anyOf", j)) == 0L) {
        matched <- TRUE
        break
      }
    }
    if (!matched) {
      out$add("anyOf", "validation.any_of")
    }
  }
  if ("oneOf" %in% keys) {
    passes <- vapply(seq_along(node[["oneOf"]]), function(j) {
      length(branch_errors("oneOf", j)) == 0L
    }, logical(1))
    passing <- which(passes) - 1L
    if (length(passing) != 1L) {
      label <- if (length(passing)) paste(passing, collapse = ", ") else "-"
      out$add("oneOf", "validation.one_of", list(matched = label))
    }
  }
  if ("not" %in% keys && length(branch_errors("not", NULL)) == 0L) {
    out$add("not", "validation.not")
  }
  out$errors
}

core_schema_format_ok <- function(x, format) {
  switch(format,
    uuid = grepl(core_uuid_pattern, x),
    sha256 = grepl("^[0-9a-f]{64}$", x),
    "date-time" = core_schema_datetime_ok(x),
    "uri-reference" = core_schema_uri_ok(x),
    FALSE
  )
}

core_schema_datetime_ok <- function(x) {
  pattern <- paste0(
    "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})",
    "([.][0-9]+)?(Z|[+-]([0-9]{2}):([0-9]{2}))$"
  )
  m <- regmatches(x, regexec(pattern, x))[[1]]
  if (length(m) == 0L) {
    return(FALSE)
  }
  n <- suppressWarnings(as.integer(m[c(2:7, 10:11)]))
  year <- n[[1]]
  leap <- year %% 4L == 0L && (year %% 100L != 0L || year %% 400L == 0L)
  days <- c(31L, if (leap) 29L else 28L, 31L, 30L, 31L, 30L, 31L, 31L, 30L,
            31L, 30L, 31L)
  month_ok <- n[[2]] >= 1L && n[[2]] <= 12L
  zone_ok <- m[[9]] == "Z" || (n[[7]] <= 23L && n[[8]] <= 59L)
  month_ok && n[[3]] >= 1L && n[[3]] <= days[[n[[2]]]] &&
    n[[4]] <= 23L && n[[5]] <= 59L && n[[6]] <= 59L && zone_ok
}

# Explicit code points instead of the locale-dependent POSIX classes: the set
# that [:space:] and [:cntrl:] cover under a glibc UTF-8 locale, as in Java.
core_schema_uri_ok <- function(x) {
  cp <- utf8ToInt(x)
  forbidden <- cp <= 0x20L | cp %in% utf8ToInt("\"<>\\^`{|}") |
    (cp >= 0x7fL & cp <= 0x9fL) | (cp >= 0x2000L & cp <= 0x2006L) |
    (cp >= 0x2008L & cp <= 0x200aL) |
    cp %in% c(0x1680L, 0x2028L, 0x2029L, 0x205fL, 0x3000L)
  !any(forbidden) && !grepl("%(?![0-9A-Fa-f]{2})", x, perl = TRUE)
}

# A string key that is equal for equal JSON values: numbers compare by
# value, object members are sorted. Used for enum, const and uniqueItems.
core_json_tree_key <- function(x) {
  switch(core_json_kind(x),
    null = "null",
    boolean = if (isTRUE(x)) "true" else "false",
    number = if (x == 0) "0" else sprintf("%.17g", as.double(x)),
    string = core_json_escape(x),
    array = {
      items <- vapply(x, core_json_tree_key, character(1))
      paste0("[", paste(items, collapse = ","), "]")
    },
    object = core_json_object_key(x)
  )
}

core_json_object_key <- function(x) {
  if (length(x) == 0L) {
    return("{}")
  }
  nms <- names(x)
  parts <- vapply(core_json_key_order(enc2utf8(nms)), function(i) {
    paste0(core_json_escape(nms[[i]]), ":", core_json_tree_key(x[[i]]))
  }, character(1))
  paste0("{", paste(parts, collapse = ","), "}")
}

core_schema_error_table <- function(raw) {
  if (length(raw) == 0L) {
    return(tibble::tibble(path = character(), keyword = character(),
                          message = character(), schema_path = character()))
  }
  field <- function(name) vapply(raw, function(e) e[[name]], character(1))
  messages <- vapply(raw, function(e) {
    do.call(qp_msg, c(list(e$key), e$args))
  }, character(1))
  table <- tibble::tibble(
    path = field("path"),
    keyword = field("keyword"),
    message = messages,
    schema_path = paste0(basename(field("file")), "#", field("schema_path"))
  )
  table <- table[!duplicated(table), , drop = FALSE]
  table[order(table$path, table$keyword, method = "radix"), , drop = FALSE]
}

# Schema-guided boxing for qp_json_encode(schema_hint = ) ------------------

core_schema_box <- function(x, schema, call) {
  target <- core_schema_target(schema, call)
  ctx <- core_schema_context(target$root)
  doc <- core_schema_document(target$path, ctx)
  start <- list(node = doc, sref = list(file = target$path, pointer = ""))
  core_schema_box_value(x, list(start), ctx, 0L)
}

core_schema_box_value <- function(x, candidates, ctx, depth) {
  if (is.null(x) || length(candidates) == 0L ||
        depth > core_json_max_depth) {
    return(x)
  }
  candidates <- core_schema_expand(candidates, ctx)
  kinds <- core_schema_kinds(candidates)
  if (is.atomic(x)) {
    scalar_only <- !is.null(kinds) && !"array" %in% kinds &&
      any(kinds %in% c("string", "number", "boolean"))
    plain <- is.null(oldClass(x)) && is.null(names(x))
    if (length(x) == 1L && plain && scalar_only) {
      x <- core_mark_scalar(x)
    }
    return(x)
  }
  if (!is.list(x) || is.object(x)) {
    return(x)
  }
  nms <- names(x)
  for (i in seq_along(x)) {
    sub <- if (is.null(nms)) {
      core_schema_subschemas(candidates, "items", NULL)
    } else {
      core_schema_subschemas(candidates, "properties", nms[[i]])
    }
    if (!is.null(x[[i]])) {
      x[[i]] <- core_schema_box_value(x[[i]], sub, ctx, depth + 1L)
    }
  }
  x
}

# Follows $ref and flattens allOf/anyOf/oneOf branches into one candidate
# list.
core_schema_expand <- function(candidates, ctx, depth = 0L) {
  out <- list()
  for (cand in candidates) {
    node <- cand$node
    if (!is.list(node) || depth > 32L) {
      next
    }
    if (!is.null(node[["$ref"]])) {
      target <- core_schema_resolve_ref(node[["$ref"]], cand$sref, ctx)
      sref <- list(file = target$file, pointer = target$pointer)
      resolved <- list(list(node = target$node, sref = sref))
      out <- c(out, core_schema_expand(resolved, ctx, depth + 1L))
      next
    }
    out <- c(out, list(cand))
    combinators <- intersect(names(node), c("allOf", "anyOf", "oneOf"))
    for (combinator in combinators) {
      branches <- lapply(node[[combinator]], function(b) {
        list(node = b, sref = cand$sref)
      })
      out <- c(out, core_schema_expand(branches, ctx, depth + 1L))
    }
  }
  out
}

core_schema_kinds <- function(candidates) {
  kinds <- character()
  for (cand in candidates) {
    node <- cand$node
    type <- node[["type"]]
    if (!is.null(type)) {
      type <- as.character(unlist(type))
      kinds <- c(kinds, ifelse(type == "integer", "number", type))
    } else if ("enum" %in% names(node)) {
      kinds <- c(kinds, vapply(node[["enum"]], core_json_kind, character(1)))
    } else if ("const" %in% names(node)) {
      kinds <- c(kinds, core_json_kind(node[["const"]]))
    }
  }
  if (length(kinds) == 0L) NULL else unique(kinds)
}

core_schema_subschemas <- function(candidates, container, name) {
  out <- list()
  for (cand in candidates) {
    node <- cand$node
    if (container == "items") {
      items <- node[["items"]]
      if (is.list(items) && !is.null(names(items))) {
        out <- c(out, list(list(node = items, sref = cand$sref)))
      }
      next
    }
    properties <- node[["properties"]]
    additional <- node[["additionalProperties"]]
    pos <- match(name, names(properties))
    if (!is.na(pos)) {
      out <- c(out, list(list(node = properties[[pos]], sref = cand$sref)))
    } else if (is.list(additional)) {
      out <- c(out, list(list(node = additional, sref = cand$sref)))
    }
  }
  out
}
