# Message catalogue ---------------------------------------------------------
#
# User-facing text lives in inst/i18n/messages.json with German and English
# entries per key. Code refers to stable English keys only.

#' Localised message text
#'
#' Looks up `key` in the message catalogue (`inst/i18n/messages.json`) and
#' fills the placeholders of the template with the named values in `...`.
#' The language comes from `.lang`, which defaults to the option
#' `qupflowR.language` (`"de"` when unset). A key missing in that language
#' falls back to English, and a key missing in the catalogue is returned
#' unchanged.
#'
#' Templates use cli inline markup, for example `{.arg {arg}}`. Values are
#' inserted as text: braces inside a value are shown literally and are never
#' evaluated. A placeholder without a value is shown as `<name>`. The result
#' is plain text without ANSI styling, so it can be used in the Shiny app.
#'
#' @param key Message key such as `"error.host_busy"`.
#' @param ... Named values for the placeholders.
#' @param .lang Language code. Region suffixes are ignored (`"de_DE"` uses
#'   `"de"`); unknown languages fall back to English.
#' @return A single string. A `key` that is not a single message key, or
#'   unnamed values in `...`, signal a `qp_validation_error` with code
#'   `INVALID_ARGUMENT`; an unknown key is not an error.
#' @seealso [qp_abort()]
#' @export
#' @examples
#' qp_msg("error.host_busy", .lang = "en")
#' qp_msg("error.invalid_argument", arg = "image_id",
#'        expected = "a single string", .lang = "en")
#' qp_msg("error.host_busy", .lang = "de")
qp_msg <- function(key, ..., .lang = getOption("qupflowR.language", "de")) {
  core_check_key(key, "key", call = environment())
  values <- core_named_values(list(...), call = environment())
  template <- core_msg_template(key, .lang)
  env <- core_msg_env(template, values)
  cli::ansi_strip(cli::format_inline(template, .envir = env))
}

core_catalog <- function() {
  if (is.null(core_cache$catalog)) {
    path <- system.file("i18n", "messages.json", package = "qupflowR")
    catalog <- if (nzchar(path)) core_read_json_tree(path) else list()
    if (!is.list(catalog$messages)) {
      catalog$messages <- list()
    }
    core_cache$catalog <- catalog
  }
  core_cache$catalog
}

core_language <- function(lang) {
  if (!core_is_string(lang)) {
    return("en")
  }
  tolower(sub("[_.-].*$", "", lang))
}

core_msg_exists <- function(key) {
  !is.null(core_catalog()$messages[[key]])
}

core_msg_template <- function(key,
                              lang = getOption("qupflowR.language", "de")) {
  entry <- core_catalog()$messages[[key]]
  if (is.null(entry)) {
    return(key)
  }
  lang <- core_language(lang)
  for (candidate in unique(c(lang, "en"))) {
    text <- entry[[candidate]]
    if (core_is_string(text)) {
      return(text)
    }
  }
  key
}

# Interpolation environment: only the supplied values are visible, so a
# template cannot reach other R objects. Placeholders without a value show
# their name instead of failing while an error message is being built.
core_msg_env <- function(template, values) {
  env <- new.env(parent = emptyenv())
  for (name in names(values)) {
    value <- values[[name]]
    if (is.null(value)) {
      value <- "NULL"
    }
    assign(name, value, envir = env)
  }
  wanted <- core_msg_placeholders(template)
  for (name in setdiff(wanted, names(values))) {
    assign(name, paste0("<", name, ">"), envir = env)
  }
  env
}

core_msg_placeholders <- function(template) {
  pattern <- "\\{[A-Za-z][A-Za-z0-9_]*\\}"
  found <- regmatches(template, gregexpr(pattern, template))[[1]]
  unique(substr(found, 2L, nchar(found) - 1L))
}
