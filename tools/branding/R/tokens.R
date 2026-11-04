cttir_branding_abort <- function(message) {
  cli::cli_abort("{message}", class = "cttir_branding_error")
}

# This versioned table drives both the R artwork and
# generated documentation CSS.
cttir_load_tokens <- function(path) {
  x <- utils::read.csv(path, stringsAsFactors = FALSE)
  if (base::anyDuplicated(base::paste(x$group, x$token))) {
    cttir_branding_abort("Design token keys must be unique.")
  }
  out <- x$value
  base::names(out) <- base::paste(x$group, x$token, sep = ".")
  out
}
cttir_token <- function(group, token) {
  tokens <- get("cttir_tokens", inherits = TRUE)
  key <- base::paste(group, token, sep = ".")
  if (!key %in% base::names(tokens))
    cttir_branding_abort(paste0("Missing token: ", key))
  tokens[[key]]
}
