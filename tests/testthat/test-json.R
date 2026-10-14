# Protocol JSON encoding and decoding conventions.

enc <- function(x, ...) {
  as.character(unclass(qupflowR::qp_json_encode(x, ...)))
}

json_error <- function(expr) {
  tryCatch(expr, qp_error = function(e) e)
}

uuid <- "8f6589c0-c587-4bfa-b740-8cab06bc8ce1"

test_that("scalars need a mark; atomic vectors are arrays at every length", {
  expect_identical(enc(qp_scalar("a")), r"("a")")
  expect_identical(enc("a"), r"(["a"])")
  expect_identical(enc(c("a", "b")), r"(["a","b"])")
  expect_identical(enc(character()), "[]")
  expect_identical(enc(jsonlite::unbox(TRUE)), "true")
  expect_identical(enc(c(TRUE, NA, FALSE)), "[true,null,false]")
  expect_identical(enc(qp_scalar(NA)), "null")
  expect_identical(enc(qp_scalar(NA_character_)), "null")
  expect_identical(enc(list(ids = uuid, n = qp_scalar(1L))),
                   paste0(r"({"ids":[")", uuid, r"("],"n":1})"))
})

test_that("NULL, empty arrays and empty objects stay distinct", {
  expect_identical(enc(NULL), "null")
  expect_identical(enc(list()), "[]")
  expect_identical(enc(qp_empty_object()), "{}")
  expect_identical(enc(list(a = NULL, b = list(), c = qp_empty_object())),
                   r"({"a":null,"b":[],"c":{}})")
  expect_identical(enc(list(list(), NULL)), "[[],null]")
})

test_that("numbers keep integers exact and doubles round-trip", {
  expect_identical(enc(c(0L, -1L, 2147483647L, NA)),
                   "[0,-1,2147483647,null]")
  expect_identical(
    enc(c(5e9, 2^31, -2^31 - 1, 2^53, -0, 1e15, 1e16)),
    paste0("[5000000000,2147483648,-2147483649,9007199254740992,0,",
           "1000000000000000,1e+16]")
  )
  # Construct 2.5e300 from its IEEE-754 bytes: parsing a decimal R literal
  # can round differently when long double has only double precision.
  large <- readBin(
    as.raw(c(0x7e, 0x4d, 0xdd, 0x4b, 0xaa, 0x00, 0x93, 0x03)),
    "double", n = 1L, size = 8L, endian = "big"
  )
  expect_identical(
    enc(c(0.1, 0.5, 1 / 3, 0.1 + 0.2, 1e-7, large)),
    "[0.1,0.5,0.3333333333333333,0.30000000000000004,1e-07,2.5e+300]"
  )
  expect_identical(enc(qp_scalar(2^53 + 2)), "9007199254740994")
  expect_identical(enc(qp_scalar(2^60)), "1.152921504606847e+18")
})

test_that("doubles survive encode and decode bit for bit", {
  set.seed(20260915)
  x <- c(stats::runif(2000) * 10^sample(-300:300, 2000, replace = TRUE),
         stats::rnorm(2000), .Machine$double.xmin, .Machine$double.xmax,
         5e-324, -stats::runif(100))
  back <- qp_json_decode(qp_json_encode(x))
  expect_identical(back, x)
})

test_that("integers beyond 32 bits decode as exact doubles", {
  txt <- paste0(r"({"size_bytes":5000000000,"big":9007199254740991,)",
                r"("small":-2147483648})")
  x <- qp_json_decode(txt)
  expect_identical(unclass(x$size_bytes), 5e9)
  expect_identical(unclass(x$big), 2^53 - 1)
  expect_identical(unclass(x$small), -2^31)
  expect_type(unclass(qp_json_decode("[2147483647]")), "integer")
  expect_identical(enc(x), txt)
})

test_that("non-finite numbers are never written", {
  for (value in list(NaN, Inf, -Inf)) {
    cnd <- json_error(qp_json_encode(list(a = qp_scalar(value))))
    expect_s3_class(cnd, "qp_serialization_error")
    expect_identical(cnd$code, "JSON_UNREPRESENTABLE")
    expect_identical(cnd$details,
                     list(path = "/a", reason = "non_finite_number"))
  }
  cnd <- json_error(qp_json_encode(list(values = c(1, 2, NaN))))
  expect_identical(cnd$details$path, "/values/2")
  expect_identical(enc(c(1, NA)), "[1,null]")
})

test_that("strings are UTF-8 with minimal escaping", {
  s <- paste0("q\" b\\ s/ t\t n\n r\r b\b f\f c", intToUtf8(1L),
              " u", intToUtf8(31L), " d", intToUtf8(127L),
              " \u00e4\u2028\U0001F600")
  expected <- paste0(r"("q\" b\\ s/ t\t n\n r\r b\b f\f c\u0001 u\u001f d)",
                     intToUtf8(127L), " \u00e4\u2028\U0001F600\"")
  out <- enc(qp_scalar(s))
  expect_identical(out, expected)
  expect_identical(Encoding(out), "UTF-8")
  expect_identical(unclass(qp_json_decode(out)), s)
  latin1 <- iconv("K\u00e4se", "UTF-8", "latin1")
  expect_identical(enc(qp_scalar(latin1)), "\"K\u00e4se\"")
})

test_that("invalid values are rejected with a reason and a JSON pointer", {
  invalid_utf8 <- rawToChar(as.raw(c(0x61, 0xff)))
  cases <- list(
    list(list(a = factor("x")), "/a", "unsupported_type"),
    list(list(a = Sys.Date()), "/a", "unsupported_type"),
    list(list(m = matrix(1:4, 2)), "/m", "unsupported_type"),
    list(list(d = data.frame(x = 1)), "/d", "unsupported_type"),
    list(list(r = as.raw(1)), "/r", "unsupported_type"),
    list(list(f = mean), "/f", "unsupported_type"),
    list(list(v = c(a = 1)), "/v", "named_vector"),
    list(list(1, b = 2), "", "empty_member_name"),
    list(stats::setNames(list(1, 2), c("k", "k")), "/k", "duplicate_key"),
    list(list(`a/b~c` = qp_scalar(NaN)), "/a~1b~0c", "non_finite_number"),
    list(list(s = structure(c(1, 2), class = "scalar")), "/s",
         "scalar_length"),
    list(list(x = invalid_utf8), "/x/0", "invalid_utf8")
  )
  for (case in cases) {
    cnd <- json_error(qp_json_encode(case[[1]]))
    expect_identical(cnd$code, "JSON_UNREPRESENTABLE", info = case[[3]])
    expect_identical(cnd$details$path, case[[2]], info = case[[3]])
    expect_identical(cnd$details$reason, case[[3]])
  }
})

test_that("decoding marks scalars and simplifies homogeneous arrays only", {
  x <- qp_json_decode(paste0(
    r"({"plane":{"c":null,"z":0,"t":1},"ids":["a"],"flags":[true,null],)",
    r"("nums":[1,2.5,null],"ints":[1,2],"mixed":[1,"a",null],)",
    r"("nested":[[1],[]],"nulls":[null,null],"obj":{},"arr":[],)",
    r"("objs":[{"a":1}]})"
  ))
  expect_null(x$plane$c)
  expect_true("c" %in% names(x$plane))
  expect_s3_class(x$plane$z, "scalar")
  expect_identical(unclass(x$plane$t), 1L)
  expect_identical(x$ids, "a")
  expect_identical(x$flags, c(TRUE, NA))
  expect_identical(x$nums, c(1, 2.5, NA))
  expect_identical(x$ints, c(1L, 2L))
  expect_identical(x$mixed, list(qp_scalar(1L), qp_scalar("a"), NULL))
  expect_identical(x$nested, list(1L, list()))
  expect_identical(x$nulls, c(NA, NA))
  expect_identical(x$obj, qp_empty_object())
  expect_identical(x$arr, list())
  expect_identical(x$objs, list(list(a = qp_scalar(1L))))
})

test_that("decode then encode reproduces the JSON value", {
  txt <- paste0(
    r"({"a":["x"],"b":"x","c":[null],"d":{},"e":[],"f":null,)",
    r"("g":[1,"a",null],"h":[[[]]],"i":-0.5,"j":12345678901,"k":")",
    "\u00e4\"}"
  )
  expect_identical(enc(qp_json_decode(txt)), txt)
  expect_identical(enc(qp_json_decode(charToRaw(txt))), txt)
})

test_that("malformed or unsupported JSON text is rejected", {
  cases <- list(
    list(r"({"a":1,"a":2})", "JSON_INVALID", "duplicate_key"),
    list(r"({"":1})", "JSON_UNREPRESENTABLE", "empty_member_name"),
    list("[NaN]", "JSON_INVALID", "syntax"),
    list("[1] x", "JSON_INVALID", "syntax"),
    list("[1e400]", "JSON_UNREPRESENTABLE", "number_out_of_range"),
    list(r"(["\u0000"])", "JSON_UNREPRESENTABLE", "nul_character"),
    list(r"(["\ud800"])", "JSON_INVALID", "unpaired_surrogate"),
    list(r"(["\udc00\ud800"])", "JSON_INVALID", "unpaired_surrogate"),
    list("\ufeff[1]", "JSON_INVALID", "byte_order_mark"),
    list(as.raw(c(0x5b, 0x22, 0xff, 0x22, 0x5d)), "JSON_INVALID",
         "invalid_utf8"),
    list(as.raw(c(0x5b, 0x00, 0x5d)), "JSON_INVALID", "nul_byte"),
    # RFC 8259 has no comments and only four white space characters.
    list("[1 /* c */]", "JSON_INVALID", "syntax"),
    list("[1 // c\n]", "JSON_INVALID", "syntax"),
    list("[1,\f2]", "JSON_INVALID", "syntax"),
    list("[1,\v2]", "JSON_INVALID", "syntax"),
    # A complete value followed by an unterminated string; yajl accepts these.
    list("[1]\"", "JSON_INVALID", "syntax"),
    list(r"({"a":1}"x)", "JSON_INVALID", "syntax"),
    list("1 \"", "JSON_INVALID", "syntax"),
    list("null\"abc", "JSON_INVALID", "syntax"),
    list(r"("a"")", "JSON_INVALID", "syntax"),
    list("-54\"5", "JSON_INVALID", "syntax"),
    list("[1]\n\"abc", "JSON_INVALID", "syntax"),
    list(r"([1]"a\")", "JSON_INVALID", "syntax"),
    list(r"([1]"\\)", "JSON_INVALID", "syntax"),
    list(r"([1]"\ud800)", "JSON_INVALID", "syntax"),
    list(r"([1]"\u0000)", "JSON_INVALID", "syntax"),
    list(r"(["\u0000)", "JSON_INVALID", "syntax"),
    # Surrogate escapes pair only when they follow each other in the text.
    list(r"(["\ud800\\\udc00"])", "JSON_INVALID", "unpaired_surrogate"),
    list(r"(["\ud800\u0041"])", "JSON_INVALID", "unpaired_surrogate"),
    list(r"(["\\\u0000"])", "JSON_UNREPRESENTABLE", "nul_character")
  )
  for (case in cases) {
    cnd <- json_error(qp_json_decode(case[[1]]))
    expect_identical(cnd$code, case[[2]], info = case[[3]])
    expect_identical(cnd$details$reason, case[[3]])
  }
  expect_identical(json_error(qp_json_decode(c("[1]", "[2]")))$code,
                   "INVALID_ARGUMENT")
  expect_identical(json_error(qp_json_decode(NA_character_))$code,
                   "INVALID_ARGUMENT")
  expect_identical(qp_json_decode(r"(["\\ud800"])"), r"(\ud800)")
  expect_identical(qp_json_decode(r"({"a/b":"c // d /* e */\f\u000b"})"),
                   list(`a/b` = qp_scalar("c // d /* e */\f\v")))
  expect_identical(qp_json_decode(r"(["\ud83d\ude00"])"), "\U0001F600")
})

test_that("nesting depth is limited to 128 levels", {
  nest <- function(n) paste0(strrep("[", n), strrep("]", n))
  expect_type(qp_json_decode(nest(128)), "list")
  expect_identical(json_error(qp_json_decode(nest(129)))$details$reason,
                   "nesting_too_deep")
  deep_strings <- paste0("[\"", strrep("[", 300), "\"]")
  expect_identical(qp_json_decode(deep_strings), strrep("[", 300))
  value <- list()
  for (i in 1:130) value <- list(value)
  expect_identical(json_error(qp_json_encode(value))$details$reason,
                   "nesting_too_deep")
})

test_that("qp_scalar(), qp_empty_object() and qp_as_json() check input", {
  expect_null(qp_scalar(NULL))
  expect_identical(class(qp_scalar(1)), "scalar")
  for (bad in list(1:2, list(1), as.raw(1), factor("a"), matrix(1))) {
    expect_identical(json_error(qp_scalar(bad))$code, "INVALID_ARGUMENT")
  }
  expect_identical(names(qp_empty_object()), character())
  expect_s3_class(qp_as_json(r"({"a":1})"), "json")
  expect_identical(json_error(qp_as_json(1))$code, "INVALID_ARGUMENT")
})

test_that("value states map special measurement values both ways", {
  x <- c(1.5, NA, NaN, Inf, -Inf, 0)
  state <- qp_value_state(x)
  expect_identical(
    state, c("finite", "missing", "nan", "pos_inf", "neg_inf", "finite")
  )
  expect_identical(qp_value_state(c(3L, NA)), c("finite", "missing"))
  value <- c(1.5, NA, NA, NA, NA, 0)
  expect_identical(qp_value_from_state(value, state), x)
  expect_identical(qp_value_from_state(NULL, "pos_inf"), Inf)
  expect_identical(qp_value_from_state(NA, "nan"), NaN)
  expect_identical(enc(qp_value_fields(NaN)),
                   r"({"value":null,"value_state":"nan"})")
  expect_identical(enc(qp_value_fields(-Inf)),
                   r"({"value":null,"value_state":"neg_inf"})")
  expect_identical(enc(qp_value_fields(2.25)),
                   r"({"value":2.25,"value_state":"finite"})")
  expect_identical(enc(qp_value_fields(NA_real_)),
                   r"({"value":null,"value_state":"missing"})")
  record <- qp_json_decode(r"({"value":null,"value_state":"neg_inf"})")
  expect_identical(
    qp_value_from_state(record$value, unclass(record$value_state)), -Inf
  )
})

test_that("inconsistent value states are rejected", {
  bad <- list(
    function() qp_value_from_state(NA_real_, "finite"),
    function() qp_value_from_state(1, "nan"),
    function() qp_value_from_state(1, "infinite"),
    function() qp_value_from_state(c(1, 2), "finite"),
    function() qp_value_from_state("1", "finite"),
    function() qp_value_state("1"),
    function() qp_value_state(factor(1)),
    function() qp_value_fields(c(1, 2))
  )
  for (f in bad) {
    expect_identical(json_error(f())$code, "INVALID_ARGUMENT")
  }
})

test_that("schema_hint unboxes single values and keeps arrays", {
  health <- list(status = "ready", host_type = "worker",
                 qupath_version = "0.7.0", bridge_version = "0.1.0-SNAPSHOT",
                 protocol_version = "1.0", fx_thread_blocked = NULL)
  expect_identical(
    enc(health, schema_hint = "health"),
    paste0(r"({"status":"ready","host_type":"worker",)",
           r"("qupath_version":"0.7.0","bridge_version":"0.1.0-SNAPSHOT",)",
           r"("protocol_version":"1.0","fx_thread_blocked":null})")
  )
  caps <- list(capabilities = list(list(
    id = "objects.query", available = TRUE, reason = NULL, modes = "worker",
    tested_versions = "0.7.0", limits = list(max_page_size = 1000L)
  )))
  expect_identical(
    enc(caps, schema_hint = "capabilities"),
    paste0(r"({"capabilities":[{"id":"objects.query","available":true,)",
           r"("reason":null,"modes":["worker"],"tested_versions":["0.7.0"],)",
           r"("limits":{"max_page_size":1000}}]})")
  )
  patch <- list(expected_revision = "rev-1", operations = list(list(
    op = "object.delete", image_id = "1", object_ids = uuid,
    strategy = "delete_subtree"
  )))
  encoded <- qp_json_encode(patch, schema_hint = "patch-request")
  expect_match(encoded, paste0(r"("object_ids":[")", uuid, r"("])"),
               fixed = TRUE)
  expect_match(encoded, r"("strategy":"delete_subtree")", fixed = TRUE)
  expect_true(qp_schema_validate(encoded, "patch-request")$valid)
})

test_that("string literals are delimited by unescaped quotation marks", {
  reason <- function(expr) json_error(expr)$details$reason
  # The brackets before an unterminated string count, the ones inside do not.
  expect_identical(reason(qp_json_decode(paste0(strrep("[", 300), "\""))),
                   "nesting_too_deep")
  expect_identical(reason(qp_json_decode(paste0("[1]\"", strrep("[", 300)))),
                   "syntax")
  # Number tokens inside an unterminated string are not scanned.
  expect_identical(reason(qp_json_canonical(qp_as_json("[1]\"1.5"))), "syntax")
  expect_identical(reason(qp_json_canonical(qp_as_json(r"(["\\\"", 1.5])"))),
                   "non_integer_number")
  expect_identical(qp_json_decode(r"(["\\\"", "a\\"])"), c("\\\"", "a\\"))
  # The escape scan runs before the bracket depth scan.
  deep <- paste0(r"(["\ud800\\\udc00",)", strrep("[", 300))
  expect_identical(reason(qp_json_decode(deep)), "unpaired_surrogate")
  expect_identical(reason(qp_json_decode(r"(["\ud800x\udc00"])")),
                   "unpaired_surrogate")
  expect_identical(qp_json_decode(r"(["\\ud800\\u0000"])"),
                   r"(\ud800\u0000)")
  schema <- withr::local_tempfile(fileext = ".json")
  writeLines(r"({"$schema": "http://json-schema.org/draft-07/schema#",
    "type": "array"})", schema)
  cnd <- json_error(qp_schema_validate(qp_as_json("[1]\""), schema))
  expect_identical(cnd$code, "JSON_INVALID")
  expect_identical(cnd$details$reason, "syntax")
})

test_that("the scans before parsing take linear time", {
  skip_on_cran()
  # 1 MiB of escaped quotation marks: a backtracking literal pattern needs
  # minutes for this text and hours for a few MiB.
  n <- 524287L
  unterminated <- paste0("[\"", strrep("\\\"", n))
  expect_identical(nchar(unterminated, type = "bytes"), 1048576L)
  elapsed <- system.time(
    cnd <- json_error(qp_json_decode(unterminated))
  )[["elapsed"]]
  expect_identical(cnd$details$reason, "syntax")
  expect_lt(elapsed, 1)
  elapsed <- system.time(
    cnd <- json_error(qp_json_canonical(qp_as_json(unterminated)))
  )[["elapsed"]]
  expect_identical(cnd$details$reason, "syntax")
  expect_lt(elapsed, 1)
  terminated <- paste0(unterminated, "\"]")
  elapsed <- system.time(value <- qp_json_decode(terminated))[["elapsed"]]
  expect_identical(value, strrep("\"", n))
  expect_lt(elapsed, 1)
  escapes <- paste0("[\"", strrep(r"(\ud83d\ude00)", 87381L), "\"]")
  elapsed <- system.time(value <- qp_json_decode(escapes))[["elapsed"]]
  expect_identical(nchar(value), 87381L)
  expect_lt(elapsed, 1)
})

test_that("long texts without containers and invalid member names", {
  long <- paste0("\"", strrep("x", 400), "\"")
  expect_identical(unclass(qp_json_decode(long)), strrep("x", 400))
  bad_name <- stats::setNames(list(1), rawToChar(as.raw(0xff)))
  cnd <- json_error(qp_json_encode(bad_name))
  expect_identical(cnd$details$reason, "invalid_utf8")
})
