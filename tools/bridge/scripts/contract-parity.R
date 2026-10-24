# Canonical JSON parity corpus: the R half of contract-parity.sh.
#
#   Rscript contract-parity.R --root <repository> --out <directory>
#     [--seed 20260915] [--count 200] [--rejects 60]
#
# Generates seeded random JSON documents, writes them as text with a writer
# that belongs to this script (not qupflowR's encoder) and records what
# qupflowR::qp_json_canonical() makes of each text. The Java test
# CanonicalJsonRParityTest canonicalises the same files and the shell script
# compares the bytes.
#
# Output in <directory>:
#   input/doc-NNNN.json        JSON text (UTF-8)
#   r/doc-NNNN.canonical       canonical bytes from R (accepted documents)
#   r/doc-NNNN.reject          "<code> <reason>" from R (rejected documents)
#   manifest.json              seed, counts, versions, mutation kinds
#
# Value domain of the accepted documents:
#   * root: object (50 %), array (35 %) or scalar (15 %); containers nest up
#     to depth 5 and hold 0 to 6 members; deeper values are scalars;
#   * scalars: null, true, false, strings of 0 to 12 characters and integers
#     (small values in [-1000, 1000], zero written as -0, the int32 limits
#     and their neighbours, random magnitudes up to 2^53 - 1 and +-(2^53 - 1));
#   * member names: 1 to 8 characters, unique within an object;
#   * characters: ASCII letters, digits, space and `"\/~_-.:{}[],`, the
#     controls U+0001, U+0008, U+0009, U+000A, U+000C, U+000D, U+001F, and
#     U+007F, U+0080, U+00A0, U+00B5, U+00DF, U+00E4, U+00E9, U+2028, U+2029,
#     U+E000, U+FB01, U+FFFD, U+FFFF, U+10000, U+1F600, U+10FFFF;
#   * text form: random white space (space, tab, LF, CR LF) between tokens,
#     random member order, and every character either literal or escaped
#     (short escapes, \/ for solidus, backslash-u escapes with lower- or
#     upper-case hex digits, surrogate pairs above U+FFFF); controls are
#     always escaped.
# Rejected documents wrap an accepted document with one defect: fraction,
# exponent, 2^53, duplicate member name, empty member name, byte order mark,
# NaN, unpaired surrogate escape, escaped U+0000, a trailing comma, or an
# unterminated string after the document (yajl accepts that one). The
# unterminated tails hide a fraction, brackets, an escaped quotation mark or
# an escaped U+0000, so the scans before parsing must treat the tail as
# string content and report `syntax`.

parity_args <- function(args) {
  opts <- list(root = NA_character_, out = NA_character_, seed = "20260915",
               count = "200", rejects = "60")
  i <- 1L
  while (i <= length(args)) {
    key <- sub("^--", "", args[[i]])
    if (!key %in% names(opts) || i == length(args)) {
      parity_fail(paste("unknown or incomplete argument", args[[i]]))
    }
    opts[[key]] <- args[[i + 1L]]
    i <- i + 2L
  }
  if (is.na(opts$root) || is.na(opts$out)) {
    parity_fail("--root and --out are required")
  }
  for (key in c("seed", "count", "rejects")) {
    if (!grepl("^[0-9]+$", opts[[key]])) {
      parity_fail(paste0("--", key, " must be a non-negative integer"))
    }
    opts[[key]] <- as.integer(opts[[key]])
  }
  opts
}

parity_fail <- function(text, status = 2L) {
  cat("contract-parity.R:", text, "\n", file = stderr())
  quit(save = "no", status = status)
}

# ---- random values ---------------------------------------------------------

parity_alphabet <- c(
  utf8ToInt(paste0("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
                   "0123456789 \"\\/~_-.:{}[],")),
  0x01, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x1F, 0x7F, 0x80, 0xA0, 0xB5, 0xDF,
  0xE4, 0xE9, 0x2028, 0x2029, 0xE000, 0xFB01, 0xFFFD, 0xFFFF, 0x10000,
  0x1F600, 0x10FFFF
)

draw <- function(n) sample.int(n, 1L)

random_code_points <- function(min_length, max_length) {
  n <- min_length + draw(max_length - min_length + 1L) - 1L
  parity_alphabet[sample.int(length(parity_alphabet), n, replace = TRUE)]
}

random_integer <- function() {
  kind <- draw(6L)
  if (kind == 1L) {
    return(sprintf("%d", draw(2001L) - 1001L))
  }
  if (kind == 2L) {
    return("-0")
  }
  if (kind == 3L) {
    limits <- c("2147483647", "-2147483648", "2147483648", "-2147483649")
    return(limits[[draw(4L)]])
  }
  if (kind == 4L) {
    return(c("9007199254740991", "-9007199254740991")[[draw(2L)]])
  }
  high <- draw(2^26) - 1
  low <- draw(2^27) - 1
  value <- high * 2^27 + low
  paste0(if (draw(2L) == 1L) "-" else "", sprintf("%.0f", value))
}

# A value is a list(kind = , ...); kinds: object, array, string, integer,
# literal.
random_value <- function(depth, root = FALSE) {
  roll <- draw(100L)
  container <- depth < 5L && (if (root) roll <= 85L else roll <= 30L)
  if (container) {
    is_object <- if (root) roll <= 50L else roll <= 15L
    size <- draw(7L) - 1L
    items <- lapply(seq_len(size), function(i) random_value(depth + 1L))
    if (!is_object) {
      return(list(kind = "array", items = items))
    }
    member_names <- character()
    while (length(member_names) < size) {
      candidate <- intToUtf8(random_code_points(1L, 8L))
      if (!candidate %in% member_names) {
        member_names <- c(member_names, candidate)
      }
    }
    return(list(kind = "object", names = member_names, items = items))
  }
  switch(draw(5L),
    list(kind = "literal", text = c("null", "true", "false")[[draw(3L)]]),
    list(kind = "integer", text = random_integer()),
    list(kind = "integer", text = random_integer()),
    list(kind = "string", code_points = random_code_points(0L, 12L)),
    list(kind = "string", code_points = random_code_points(0L, 12L))
  )
}

# ---- writer ----------------------------------------------------------------

space <- function() {
  c("", "", "", " ", "\t", "\n", "\r\n", "  ")[[draw(8L)]]
}

hex_escape <- function(unit) {
  sprintf(if (draw(2L) == 1L) "\\u%04x" else "\\u%04X", unit)
}

escape_code_point <- function(cp) {
  short <- c("8" = "\\b", "9" = "\\t", "10" = "\\n", "12" = "\\f",
             "13" = "\\r")
  if (cp == 0x22) {
    return(if (draw(2L) == 1L) "\\\"" else hex_escape(cp))
  }
  if (cp == 0x5C) {
    return(if (draw(2L) == 1L) "\\\\" else hex_escape(cp))
  }
  if (cp == 0x2F) {
    return(c("/", "\\/", hex_escape(cp))[[draw(3L)]])
  }
  if (cp < 0x20) {
    known <- short[as.character(cp)]
    if (!is.na(known) && draw(2L) == 1L) {
      return(unname(known))
    }
    return(hex_escape(cp))
  }
  if (draw(3L) != 1L) {
    return(intToUtf8(cp))
  }
  if (cp > 0xFFFF) {
    offset <- cp - 0x10000
    return(paste0(hex_escape(0xD800 + offset %/% 0x400),
                  hex_escape(0xDC00 + offset %% 0x400)))
  }
  hex_escape(cp)
}

write_string <- function(code_points) {
  parts <- vapply(code_points, escape_code_point, character(1))
  paste0("\"", paste(parts, collapse = ""), "\"")
}

write_value <- function(value) {
  switch(value$kind,
    literal = value$text,
    integer = value$text,
    string = write_string(value$code_points),
    array = {
      parts <- vapply(value$items, function(item) {
        paste0(space(), write_value(item), space())
      }, character(1))
      paste0("[", paste(parts, collapse = ","), if (!length(parts)) space(),
             "]")
    },
    object = {
      order <- sample.int(length(value$items))
      parts <- vapply(order, function(i) {
        paste0(space(), write_string(utf8ToInt(value$names[[i]])), space(),
               ":", space(), write_value(value$items[[i]]), space())
      }, character(1))
      paste0("{", paste(parts, collapse = ","), if (!length(parts)) space(),
             "}")
    }
  )
}

mutations <- c(
  fraction = "non_integer_number", exponent = "non_integer_number",
  too_large = "integer_out_of_range", duplicate_key = "duplicate_key",
  empty_key = "empty_member_name", byte_order_mark = "byte_order_mark",
  nan_literal = "syntax", lone_surrogate = "unpaired_surrogate",
  nul_escape = "nul_character", trailing_comma = "syntax",
  unterminated_string = "syntax"
)

unterminated_tails <- c("\"", "\"1.5 [[", "\"a\\\"", "\"\\u0000",
                        "\"x\n\\\\")

mutate <- function(text, kind) {
  switch(kind,
    fraction = paste0("[", text, ",1.5]"),
    exponent = paste0("[", text, ",1e3]"),
    too_large = paste0("[", text, ",9007199254740992]"),
    duplicate_key = paste0("{\"k\":", text, ",\"k\":1}"),
    empty_key = paste0("[", text, ",{\"\":1}]"),
    byte_order_mark = paste0("\ufeff", text),
    nan_literal = paste0("[", text, ",NaN]"),
    lone_surrogate = paste0("[", text, ",\"a\\ud800\"]"),
    nul_escape = paste0("[", text, ",\"a\\u0000\"]"),
    trailing_comma = paste0("[", text, ",]"),
    unterminated_string = paste0(text, unterminated_tails[[draw(5L)]])
  )
}

# ---- main ------------------------------------------------------------------

write_bytes <- function(text, path) {
  writeBin(charToRaw(enc2utf8(text)), path)
}

canonical_result <- function(bytes) {
  tryCatch(
    list(ok = TRUE,
         text = qupflowR::qp_json_canonical(qupflowR::qp_as_json(bytes))),
    qp_error = function(e) {
      list(ok = FALSE, text = paste(e$code, e$details$reason))
    }
  )
}

main <- function() {
  opts <- parity_args(commandArgs(trailingOnly = TRUE))
  suppressMessages(pkgload::load_all(opts$root, quiet = TRUE,
                                     export_all = FALSE, helpers = FALSE))
  set.seed(opts$seed, kind = "Mersenne-Twister", normal.kind = "Inversion",
           sample.kind = "Rejection")
  for (sub_dir in c("input", "r")) {
    dir.create(file.path(opts$out, sub_dir), recursive = TRUE,
               showWarnings = FALSE)
  }
  total <- opts$count + opts$rejects
  kinds <- names(mutations)[(seq_len(opts$rejects) - 1L) %%
                              length(mutations) + 1L]
  problems <- character()
  counts <- c(accepted = 0L, rejected = 0L)
  for (i in seq_len(total)) {
    stem <- sprintf("doc-%04d", i)
    text <- paste0(space(), write_value(random_value(0L, root = TRUE)),
                   space())
    reject_kind <- NA_character_
    if (i > opts$count) {
      reject_kind <- kinds[[i - opts$count]]
      text <- mutate(text, reject_kind)
    }
    write_bytes(text, file.path(opts$out, "input", paste0(stem, ".json")))
    result <- canonical_result(charToRaw(enc2utf8(text)))
    if (is.na(reject_kind)) {
      if (!result$ok) {
        problems <- c(problems, paste(stem, "rejected by R:", result$text))
        next
      }
      again <- canonical_result(charToRaw(result$text))
      if (!again$ok || !identical(again$text, result$text)) {
        problems <- c(problems, paste(stem, "canonical form not idempotent"))
      }
      write_bytes(result$text,
                  file.path(opts$out, "r", paste0(stem, ".canonical")))
      counts[["accepted"]] <- counts[["accepted"]] + 1L
    } else {
      expected <- unname(mutations[[reject_kind]])
      if (result$ok || !endsWith(result$text, paste0(" ", expected))) {
        problems <- c(problems, paste(stem, reject_kind, "gave", result$text))
        next
      }
      write_bytes(result$text,
                  file.path(opts$out, "r", paste0(stem, ".reject")))
      counts[["rejected"]] <- counts[["rejected"]] + 1L
    }
  }
  manifest <- list(
    generator = "tools/bridge/scripts/contract-parity.R",
    seed = opts$seed,
    rng = RNGkind(),
    accepted = counts[["accepted"]],
    rejected = counts[["rejected"]],
    mutation_kinds = as.list(table(kinds)),
    r_version = R.version.string,
    qupflowr_version = as.character(utils::packageVersion("qupflowR")),
    problems = problems
  )
  jsonlite::write_json(manifest, file.path(opts$out, "manifest.json"),
                       auto_unbox = TRUE, pretty = TRUE)
  cat(sprintf("contract-parity.R: %d accepted, %d rejected, %d problems\n",
              counts[["accepted"]], counts[["rejected"]], length(problems)))
  if (length(problems) > 0L) {
    cat(problems, sep = "\n", file = stderr())
    quit(save = "no", status = 1L)
  }
}

main()
