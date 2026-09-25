# ─────────────────────────────────────────────────────────────────────────────
# CTTIR house linters
#
# The cheap version of the shared vocabulary. Before any foundation package
# exists, a linter can hold the naming conventions across all 22 packages at
# roughly 2% of the cost: no runtime dependency, no breaking change, and it
# runs in CI that already exists.
#
# Source this file, then pass cttir_linters() to lintr.
#
#   source("config/lint-cttir.R")
#   lintr::lint_package(linters = cttir_linters())
#
# Every rule here was derived from a divergence actually found in the estate on
# 2026-08-19, not from generic style advice.
# ─────────────────────────────────────────────────────────────────────────────

# Build a line-based linter from a named list of regex rules. Each rule is
# list(pattern = , message = ). Comment-only lines are still checked, because
# a wrong column name in a roxygen @return is as misleading as one in code.
cttir_regex_linter <- function(rules, type = "style") {
  force(rules)
  force(type)
  lintr::Linter(function(source_expression) {
    if (!lintr::is_lint_level(source_expression, "file")) {
      return(list())
    }
    lines <- source_expression$file_lines
    lints <- list()
    for (rule in rules) {
      hits <- grep(rule$pattern, lines, perl = TRUE)
      for (i in hits) {
        # gregexpr, not regexpr: a line like `ci_low = 0, ci_high = 1` carries
        # two violations of the same rule and both should be reported, or the
        # second only surfaces on the next run after the first is fixed.
        m <- gregexpr(rule$pattern, lines[[i]], perl = TRUE)[[1L]]
        starts <- as.integer(m)
        lens <- attr(m, "match.length")
        for (k in seq_along(starts)) {
          lints[[length(lints) + 1L]] <- lintr::Lint(
            filename = source_expression$filename,
            line_number = i,
            column_number = starts[[k]],
            type = type,
            message = rule$message,
            line = lines[[i]],
            ranges = list(c(starts[[k]], starts[[k]] + lens[[k]] - 1L))
          )
        }
      }
    }
    lints
  })
}

# ── 1. Statistics vocabulary ────────────────────────────────────────────────
# broom's spelling is non-negotiable: broom and tidymodels consume these names,
# so a package that renames them cannot be piped into either. The estate
# currently carries five spellings of p-value and five of confidence bound.
cttir_stats_vocab_linter <- function() {
  cttir_regex_linter(list(
    list(
      pattern = "\\b(p_value|pvalue|p_val|padj|p_adjusted)\\b",
      message = "Use broom's spelling `p.value` (and `p.adj` for adjusted)."
    ),
    list(
      pattern = "\\b(ci_low|ci_lower|CI_low|CI_lower|ci_hi|ci_high|ci_upper|CI_high|CI_upper)\\b",
      message = "Use broom's spelling `conf.low` / `conf.high`."
    ),
    list(
      pattern = "\\b(std_error|stderr_val|se_val)\\b",
      message = "Use broom's spelling `std.error`."
    )
  ))
}

# ── 2. Domain vocabulary ────────────────────────────────────────────────────
# Units and axes belong in the vocabulary, not in the column name. `wavelength`
# is always nm across the suite, so the `_nm` suffix is noise that also blocks
# a shared accessor working across hyperspectR, libscanR, cuvis.r and tivis.r.
cttir_domain_vocab_linter <- function() {
  cttir_regex_linter(list(
    list(
      pattern = "\\bwavelength_nm\\b",
      message = "Use `wavelength`; the suite pins it to nm in the vocabulary."
    ),
    list(
      pattern = "\\b(centroid_row|centroid_col)\\b",
      message = "Use `y` (row direction) and `x` (column direction)."
    ),
    list(
      pattern = "\\b(pat_id|patid|patient_no|subj_id)\\b",
      message = "Use `patient_id` / `subject_id`, always character, always a column."
    ),
    list(
      pattern = "\\bcell_area\\b",
      message = "Use `area`; qualify the unit in the vocabulary, not the name."
    )
  ))
}

# ── 3. Error handling ───────────────────────────────────────────────────────
# cli::cli_abort() carries a condition class and a call, so callers can catch
# a specific failure. stop() gives neither. The estate has ~1,048 hand-written
# `call =` propagations precisely because this was inconsistent.
cttir_condition_linter <- function() {
  cttir_regex_linter(
    list(
      list(
        pattern = "(?<![\\w.$@])stopifnot\\s*\\(",
        message = "Use cli::cli_abort() with a condition class rather than stopifnot()."
      ),
      list(
        pattern = "(?<![\\w.$@:])stop\\s*\\(",
        message = "Use cli::cli_abort() rather than stop(); it carries a class and a call."
      ),
      list(
        pattern = "(?<![\\w.$@:])warning\\s*\\(",
        message = "Use cli::cli_warn() rather than warning()."
      )
    ),
    type = "warning"
  )
}

# ── 4. globalVariables abuse ────────────────────────────────────────────────
# A multi-entry utils::globalVariables() is almost always suppressing the
# R CMD check note caused by not importing rlang::.data. Ten packages carry
# one; two of them list the string ".data" itself, which is the tell.
cttir_globals_linter <- function() {
  cttir_regex_linter(
    list(list(
      pattern = "utils::globalVariables\\s*\\(\\s*c\\s*\\(",
      message = paste(
        "Multi-entry globalVariables() suppresses the note instead of fixing it.",
        "Import rlang::.data and use .data$col in tidy evaluation."
      )
    )),
    type = "warning"
  )
}

# ── 5. Chattiness argument ──────────────────────────────────────────────────
# 80 `verbose`, 3 `quiet` and assorted `progress` across the estate. readr and
# usethis both spell it `quiet`, so the suite follows them.
cttir_quiet_linter <- function() {
  cttir_regex_linter(list(list(
    pattern = "\\bverbose\\s*=\\s*(TRUE|FALSE)\\b",
    message = "Use `quiet` (readr/usethis spelling) as the single chattiness argument."
  )))
}

#' All CTTIR house linters, on top of lintr's defaults.
#'
#' @param vocabulary_only If TRUE, return only the naming rules. Useful when
#'   rolling out to a repository that is not ready for the style rules.
cttir_linters <- function(vocabulary_only = FALSE) {
  house <- list(
    cttir_stats_vocab = cttir_stats_vocab_linter(),
    cttir_domain_vocab = cttir_domain_vocab_linter()
  )
  if (!vocabulary_only) {
    house <- c(house, list(
      cttir_condition = cttir_condition_linter(),
      cttir_globals = cttir_globals_linter(),
      cttir_quiet = cttir_quiet_linter()
    ))
  }
  house
}
