# Canonical JSON: shared vectors with the Java bridge plus R value cases.

canonical_dir <- function() {
  system.file("schema", "protocol", "v1", "fixtures", "canonical",
              package = "qupflowR")
}

read_bytes <- function(path) readBin(path, "raw", file.size(path))

sha256_of_bytes <- function(bytes) {
  path <- withr::local_tempfile()
  writeBin(bytes, path)
  sha256sum <- get0("sha256sum", envir = asNamespace("tools"),
                    inherits = FALSE)
  if (is.function(sha256sum)) {
    return(unname(sha256sum(path)))
  }
  testthat::skip_if_not_installed("digest")
  digest::digest(file = path, algo = "sha256")
}

canonical_text <- function(x) {
  as.character(unclass(qupflowR::qp_json_canonical(x)))
}

with_suffix <- function(path, suffix) sub("[.]json$", suffix, path)

test_that("shared canonical vectors give the expected bytes and digests", {
  sources <- list.files(canonical_dir(), pattern = "[.]json$",
                        full.names = TRUE)
  accepted <- sources[file.exists(with_suffix(sources, ".canonical"))]
  expect_gte(length(accepted), 8L)
  for (source in accepted) {
    name <- basename(source)
    expected <- read_bytes(with_suffix(source, ".canonical"))
    digest_line <- readLines(with_suffix(source, ".sha256"))
    expected_digest <- strsplit(digest_line, "  ", fixed = TRUE)[[1]][[1]]
    out <- qp_json_canonical(qp_as_json(read_bytes(source)))
    bytes <- charToRaw(out)
    expect_identical(bytes, expected, info = name)
    expect_identical(sha256_of_bytes(bytes), expected_digest, info = name)
    expect_identical(sha256_of_bytes(expected), expected_digest, info = name)
    expect_false(any(bytes == as.raw(0x0a)), info = name)
  }
})

test_that("shared rejection vectors fail with the declared code and reason", {
  sources <- list.files(canonical_dir(), pattern = "^reject-.*[.]json$",
                        full.names = TRUE)
  sources <- sources[!grepl("[.]expect[.]json$", sources)]
  expect_gte(length(sources), 9L)
  for (source in sources) {
    expect <- jsonlite::read_json(with_suffix(source, ".expect.json"))
    cnd <- tryCatch(qp_json_canonical(qp_as_json(read_bytes(source))),
                    qp_error = function(e) e)
    expect_s3_class(cnd, "qp_serialization_error")
    expect_identical(cnd$code, expect$code, info = basename(source))
    expect_identical(cnd$details$reason, expect$reason,
                     info = basename(source))
  }
})

test_that("R values canonicalise with sorted keys and no whitespace", {
  x <- list(
    qp_scalar(1L),
    list(b = c(3, 1), a = qp_empty_object()),
    qp_scalar(TRUE),
    NULL,
    list(qp_scalar("x"), NULL, list())
  )
  names(x) <- c("zeta", "alpha", "\u00e9", "Z", "list")
  expect_identical(
    canonical_text(x),
    paste0(r"({"Z":null,"alpha":{"a":{},"b":[3,1]},"list":["x",null,[]],)",
           "\"zeta\":1,\"\u00e9\":true}")
  )
})

test_that("canonical output ignores member order and the input path", {
  a <- list(b = qp_scalar(2L), a = list(y = qp_scalar("1"), x = c(1L, 2L)))
  b <- list(a = list(x = c(1L, 2L), y = qp_scalar("1")), b = qp_scalar(2L))
  expect_identical(canonical_text(a), canonical_text(b))
  expect_identical(canonical_text(qp_json_encode(a)), canonical_text(a))
  expect_identical(canonical_text(qp_json_decode(canonical_text(a))),
                   canonical_text(a))
})

test_that("canonical numbers are integers within 2^53 - 1", {
  expect_identical(canonical_text(c(2^53 - 1, -(2^53 - 1), -0, 5e9)),
                   "[9007199254740991,-9007199254740991,0,5000000000]")
  cases <- list(
    list(qp_scalar(0.5), "non_integer_number"),
    list(qp_scalar(2^53), "integer_out_of_range"),
    list(list(a = c(1, 1.25)), "non_integer_number"),
    list(qp_scalar(NaN), "non_finite_number")
  )
  for (case in cases) {
    cnd <- tryCatch(qp_json_canonical(case[[1]]), qp_error = function(e) e)
    expect_identical(cnd$code, "JSON_UNREPRESENTABLE")
    expect_identical(cnd$details$reason, case[[2]])
  }
  expect_identical(canonical_text(qp_as_json("[-0, 12]")), "[0,12]")
  expect_identical(canonical_text(qp_as_json(r"(["1.5", "2e3"])")),
                   r"(["1.5","2e3"])")
})

test_that("canonical key order is by code point, not UTF-16 or locale", {
  keys <- c("\U0001F600", "\ufb01", "B", "a", "\u00e4", "ab", "A")
  x <- stats::setNames(lapply(seq_along(keys), qp_scalar), keys)
  expected_order <- c("A", "B", "a", "ab", "\u00e4", "\ufb01",
                      "\U0001F600")
  members <- paste0("\"", expected_order, "\":", match(expected_order, keys))
  expected <- paste0("{", paste(members, collapse = ","), "}")
  expect_identical(canonical_text(x), expected)
})

test_that("canonical key order does not follow the collation locale", {
  natural <- c("en_US.UTF-8", "en_GB.UTF-8", "de_DE.UTF-8", "en_US.utf8",
               "en_GB.utf8", "de_DE.utf8")
  old <- Sys.getlocale("LC_COLLATE")
  withr::defer(Sys.setlocale("LC_COLLATE", old))
  found <- FALSE
  for (candidate in natural) {
    if (nzchar(suppressWarnings(Sys.setlocale("LC_COLLATE", candidate)))) {
      found <- TRUE
      break
    }
  }
  skip_if_not(found, "no natural-language collation locale is installed")
  expect_identical(order(c("a", "B")), 1:2)
  x <- list(a = qp_scalar(1L), B = qp_scalar(2L), A = qp_scalar(3L))
  expect_identical(canonical_text(x), r"({"A":3,"B":2,"a":1})")
})
