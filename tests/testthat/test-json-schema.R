# Draft-07 subset validator: keyword semantics with small inline schemas.

draft <- "http://json-schema.org/draft-07/schema#"

# Writes schema files into a fresh directory and returns the first path.
local_schemas <- function(..., .env = parent.frame()) {
  dir <- withr::local_tempdir(.local_envir = .env)
  files <- list(...)
  for (name in names(files)) {
    writeLines(files[[name]], file.path(dir, name), useBytes = TRUE)
  }
  file.path(dir, names(files)[[1]])
}

schema_text <- function(...) {
  paste0("{\"$schema\":\"", draft, "\",", paste0(...), "}")
}

check <- function(instance, schema) {
  report <- qupflowR::qp_schema_validate(qupflowR::qp_as_json(instance),
                                         schema)
  paste(report$errors$path, report$errors$keyword, sep = " ")
}

schema_error <- function(expr) tryCatch(expr, qp_error = function(e) e)

quoted <- function(x) paste0("\"", x, "\"")

test_that("type distinguishes integers, numbers, null and containers", {
  s <- local_schemas(a.json = schema_text(r"("type":"integer")"))
  expect_identical(check("1", s), character())
  expect_identical(check("1.0", s), character())
  expect_identical(check("1.5", s), " type")
  expect_identical(check(r"("1")", s), " type")
  multi <- local_schemas(b.json = schema_text(r"("type":["string","null"])"))
  expect_identical(check("null", multi), character())
  expect_identical(check("[]", multi), " type")
  obj <- local_schemas(c.json = schema_text(r"("type":"object")"))
  expect_identical(check("{}", obj), character())
  expect_identical(check("[]", obj), " type")
  arr <- local_schemas(d.json = schema_text(r"("type":"array")"))
  expect_identical(check("{}", arr), " type")
  bool <- local_schemas(e.json = schema_text(r"("type":"boolean")"))
  expect_identical(check("true", bool), character())
})

test_that("object keywords report at the object and at extra members", {
  s <- local_schemas(o.json = schema_text(
    r"("type":"object","required":["a","b"],)",
    r"("properties":{"a":{"type":"string"},"x/y":{"type":"integer"}},)",
    r"("additionalProperties":false)"
  ))
  expect_identical(check(r"({"a":"v","b":1})", s), "/b additionalProperties")
  expect_identical(sort(check(r"({"a":1})", s)), c(" required", "/a type"))
  expect_identical(check(r"({"a":"v","b":1,"x/y":0.5})", s),
                   c("/b additionalProperties", "/x~1y type"))
  typed <- local_schemas(p.json = schema_text(
    r"("additionalProperties":{"type":"integer","minimum":0})"
  ))
  expect_identical(check(r"({"k":-1,"j":2})", typed), "/k minimum")
  expect_identical(check("[1]", s), " type")
  report <- qp_schema_validate(qp_as_json("{}"), s)
  expect_identical(nrow(report$errors), 2L)
  expect_identical(anyDuplicated(report$errors$message), 0L)
})

test_that("array keywords", {
  s <- local_schemas(a.json = schema_text(
    r"("type":"array","minItems":1,"maxItems":3,"uniqueItems":true,)",
    r"("items":{"type":"number"})"
  ))
  expect_identical(check("[1,2]", s), character())
  expect_identical(check("[]", s), " minItems")
  expect_identical(check("[1,2,3,4]", s), " maxItems")
  expect_identical(check("[1,1.0]", s), " uniqueItems")
  expect_identical(check(r"([1,"a"])", s), "/1 type")
  objects <- local_schemas(b.json = schema_text(r"("uniqueItems":true)"))
  expect_identical(check(r"([{"a":1,"b":[1]},{"b":[1],"a":1.0}])", objects),
                   " uniqueItems")
  expect_identical(
    check(r"([{"a":1},{"a":"1"},[1],["1"],null,false])", objects),
    character()
  )
  report <- qp_schema_validate(qp_as_json(r"(["a","b","a"])"), objects)
  expect_match(report$errors$message, "0", fixed = TRUE)
  expect_match(report$errors$message, "2", fixed = TRUE)
})

test_that("enum and const compare JSON values", {
  s <- local_schemas(e.json = schema_text(
    r"("enum":[1,"1",null,{"a":[true]}])"
  ))
  expect_identical(check("1.0", s), character())
  expect_identical(check(r"("1")", s), character())
  expect_identical(check("null", s), character())
  expect_identical(check(r"({"a":[true]})", s), character())
  expect_identical(check(r"({"a":[false]})", s), " enum")
  expect_identical(check("2", s), " enum")
  const_null <- local_schemas(c.json = schema_text(r"("const":null)"))
  expect_identical(check("null", const_null), character())
  expect_identical(check("0", const_null), " const")
  const_obj <- local_schemas(d.json = schema_text(
    r"("const":{"b":1,"a":2})"
  ))
  expect_identical(check(r"({"a":2,"b":1})", const_obj), character())
})

test_that("numeric bounds", {
  s <- local_schemas(n.json = schema_text(
    r"("minimum":0,"maximum":10,"exclusiveMinimum":-1,)",
    r"("exclusiveMaximum":10.5)"
  ))
  expect_identical(check("0", s), character())
  expect_identical(check("10", s), character())
  expect_identical(check("-0.5", s), " minimum")
  expect_identical(check("10.25", s), " maximum")
  expect_identical(sort(check("-1", s)), c(" exclusiveMinimum", " minimum"))
  expect_identical(sort(check("10.5", s)),
                   c(" exclusiveMaximum", " maximum"))
  expect_identical(check(r"("x")", s), character())
})

test_that("string lengths count code points; patterns use ECMA anchors", {
  s <- local_schemas(s.json = schema_text(
    r"("minLength":2,"maxLength":3,)",
    r"("pattern":"^[a-z\u00e4\u00f6\u00fc]+$")"
  ))
  expect_identical(check(r"("\u00e4\u00f6\u00fc")", s), character())
  expect_identical(check(r"("a")", s), " minLength")
  expect_identical(check(r"("abcd")", s), " maxLength")
  expect_identical(check(r"("ab\n")", s), " pattern")
  expect_identical(check(r"("AB")", s), " pattern")
  unanchored <- local_schemas(u.json = schema_text(r"("pattern":"b")"))
  expect_identical(check(r"("abc")", unanchored), character())
})

test_that("formats are asserted", {
  f <- function(format, env = parent.frame()) {
    local_schemas(f.json = schema_text("\"format\":", quoted(format)),
                  .env = env)
  }
  uuid <- f("uuid")
  lower <- "8f6589c0-c587-4bfa-b740-8cab06bc8ce1"
  expect_identical(check(quoted(lower), uuid), character())
  expect_identical(check(quoted(toupper(lower)), uuid), " format")
  expect_identical(check(quoted(gsub("-", "", lower)), uuid), " format")
  expect_identical(check("12", uuid), character())
  sha <- f("sha256")
  expect_identical(check(quoted(strrep("ab", 32)), sha), character())
  expect_identical(check(quoted(strrep("ab", 31)), sha), " format")
  dt <- f("date-time")
  valid_dates <- c("2024-02-29T23:59:59Z",
                   "2026-09-15T08:00:00.123456789+02:00",
                   "2000-02-29T00:00:00-00:00")
  invalid_dates <- c(
    "2023-02-29T00:00:00Z", "1900-02-29T00:00:00Z", "2026-13-01T00:00:00Z",
    "2026-09-15T24:00:00Z", "2026-09-15T23:60:00Z", "2026-09-15T23:59:60Z",
    "2026-09-15 08:00:00Z", "2026-09-15T08:00:00", "2026-09-15t08:00:00z",
    "2026-09-15T08:00:00+24:00", "2026-04-31T00:00:00Z"
  )
  for (d in valid_dates) {
    expect_identical(check(quoted(d), dt), character(), info = d)
  }
  for (d in invalid_dates) {
    expect_identical(check(quoted(d), dt), " format", info = d)
  }
  uri <- f("uri-reference")
  valid_uris <- c("file:///data/images/slide%201.tif",
                  "images/a.tif?x=1#frag", "",
                  r"(Bilder/Gr\u00f6\u00dfe.tif)",
                  "https://example.org/a_b-c~d")
  invalid_uris <- c("images/slide 1.tif", "a%zzb", "a%2", "a<b", r"(a\\b)",
                    r"(a\tb)", "{x}")
  for (u in valid_uris) {
    expect_identical(check(quoted(u), uri), character(), info = u)
  }
  for (u in invalid_uris) {
    expect_identical(check(quoted(u), uri), " format", info = u)
  }
})

test_that("combinators report one error at their location", {
  one <- local_schemas(o.json = schema_text(
    r"("oneOf":[{"type":"integer"},{"minimum":2},)",
    r"({"type":"string","minLength":5}])"
  ))
  expect_identical(check("1", one), character())
  expect_identical(check("2", one), " oneOf")
  expect_identical(check(r"("abc")", one), character())
  expect_identical(check(r"("abcdef")", one), " oneOf")
  report <- qp_schema_validate(qp_as_json("2"), one)
  expect_match(report$errors$message, "0, 1", fixed = TRUE)
  any <- local_schemas(a.json = schema_text(
    r"("anyOf":[{"type":"integer"},{"type":"null"}])"
  ))
  expect_identical(check("null", any), character())
  expect_identical(check(r"("x")", any), " anyOf")
  all <- local_schemas(l.json = schema_text(
    r"("allOf":[{"type":"integer"},{"minimum":3},{"maximum":1}])"
  ))
  expect_identical(sort(check("2", all)), c(" maximum", " minimum"))
  negation <- local_schemas(n.json = schema_text(
    r"("not":{"required":["token"]})"
  ))
  expect_identical(check(r"({"a":1})", negation), character())
  expect_identical(check(r"({"token":1})", negation), " not")
})

test_that("boolean schemas", {
  s <- local_schemas(b.json = schema_text(
    r"("properties":{"yes":true,"no":false},"items":false)"
  ))
  expect_identical(check(r"({"yes":1})", s), character())
  expect_identical(check(r"({"no":1})", s), "/no false")
  expect_identical(check("[]", s), character())
  expect_identical(check("[1]", s), "/0 false")
})

test_that("local and relative references resolve, with pointer escapes", {
  main <- local_schemas(
    main.json = schema_text(
      r"("properties":{"a":{"$ref":"#/definitions/a~1b"},)",
      r"("b":{"$ref":"other.json#/definitions/positive"},)",
      r"("c":{"$ref":"sub/third.json"},)",
      r"("d":{"$ref":"#/definitions/list/items"}},)",
      r"("definitions":{"a/b":{"type":"string"},)",
      r"("list":{"type":"array","items":{"const":"x"}}})"
    ),
    other.json = schema_text(
      r"("definitions":{"positive":{"type":"integer","minimum":1}})"
    )
  )
  dir.create(file.path(dirname(main), "sub"))
  writeLines(schema_text(r"("$ref":"../other.json#/definitions/positive")"),
             file.path(dirname(main), "sub", "third.json"))
  expect_identical(check(r"({"a":"v","b":1,"c":2,"d":"x"})", main),
                   character())
  instance <- qp_as_json(r"({"a":1,"b":0,"c":0,"d":"y"})")
  report <- qp_schema_validate(instance, main)
  expect_identical(report$errors$path, c("/a", "/b", "/c", "/d"))
  expect_identical(report$errors$keyword,
                   c("type", "minimum", "minimum", "const"))
  expect_identical(report$errors$schema_path, c(
    "main.json#/definitions/a~1b/type",
    "other.json#/definitions/positive/minimum",
    "other.json#/definitions/positive/minimum",
    "main.json#/definitions/list/items/const"
  ))
})

test_that("unsupported or unresolvable schemas are schema errors", {
  expect_reason <- function(body, instance, reason) {
    schema <- local_schemas(s.json = body)
    cnd <- schema_error(qp_schema_validate(qp_as_json(instance), schema))
    expect_s3_class(cnd, "qp_validation_error")
    expect_identical(cnd$code, "SCHEMA_INVALID")
    expect_identical(cnd$details$reason, reason, info = body)
  }
  expect_reason(schema_text(r"("minProperties":1)"), "{}",
                "unsupported_keyword")
  expect_reason(r"({"type":"object"})", "{}", "draft07_required")
  expect_reason(schema_text(r"("format":"email")"), r"("a")",
                "unsupported_format")
  expect_reason(schema_text(r"("pattern":"^\\d+$")"), r"("1")",
                "pattern_not_portable")
  expect_reason(schema_text(r"("pattern":"^a.b$")"), r"("a")",
                "pattern_not_portable")
  expect_reason(schema_text(r"("pattern":"(?=a)a")"), r"("a")",
                "pattern_not_portable")
  expect_reason(schema_text(r"("pattern":"a$b")"), r"("a")",
                "pattern_not_portable")
  expect_reason(schema_text(r"("pattern":"(")"), r"("a")",
                "pattern_does_not_compile")
  expect_reason(schema_text(r"("items":[{"type":"string"}])"), "[]",
                "tuple_items_unsupported")
  expect_reason(schema_text(r"("$ref":"http://example.org/s.json")"), "1",
                "non_local_ref")
  expect_reason(schema_text(r"("$ref":"#/definitions/missing")"), "1",
                "unresolved_ref")
  expect_reason(schema_text(r"("$ref":"missing.json")"), "1",
                "unresolved_ref")
  expect_reason(schema_text(r"("$ref":"../outside.json")"), "1",
                "ref_outside_schema_root")
  expect_reason(schema_text(r"("$ref":"#")"), "1", "circular_ref")
  expect_reason(schema_text(r"("$ref":"#/definitions/x","type":"null")"),
                "1", "ref_with_siblings")
  expect_reason(schema_text(r"("type":"float")"), "1", "invalid_type")
  expect_reason(r"({"$schema": )", "1", "unreadable_json")
  # A commented schema is unreadable JSON, not an unsupported keyword.
  expect_reason(paste0(schema_text(r"("type":"string")"), " /* note */"),
                r"("a")", "unreadable_json")
})

test_that("malformed reference targets are classified schema errors", {
  expect_reason <- function(ref, reason, extra = "") {
    body <- schema_text(
      r"("definitions":{"x":{"type":"string","enum":["a"]},)",
      "\"\u00e9\":{\"type\":\"number\"}},", extra, r"("$ref":")", ref, "\""
    )
    schema <- local_schemas(s.json = body)
    cnd <- schema_error(qp_schema_validate(qp_as_json(r"("a")"), schema))
    expect_s3_class(cnd, "qp_validation_error")
    expect_identical(cnd$code, "SCHEMA_INVALID", info = ref)
    expect_identical(cnd$details$reason, reason, info = ref)
  }
  # A string or number is no schema; malformed or non-UTF-8 percent escapes
  # and array indices beyond nine digits name no member.
  expect_reason("#/definitions/x/type", "schema_not_object")
  expect_reason("#/definitions/%C3", "unresolved_ref")
  expect_reason("#/definitions/%zz", "unresolved_ref")
  expect_reason("#/definitions/%4", "unresolved_ref")
  expect_reason("#/definitions/%00", "unresolved_ref")
  expect_reason("#/definitions/x/enum/1234567890", "unresolved_ref")
  valid <- function(ref) {
    body <- schema_text(
      r"("definitions":{"x":{"type":"string","enum":["a",null]},)",
      "\"\u00e9\":{\"type\":\"number\"},\"n\":{\"const\":null},",
      r"("a/b":{"type":"integer"}},)",
      r"("$ref":")", ref, "\""
    )
    qp_schema_validate(qp_as_json(r"("a")"), local_schemas(s.json = body))
  }
  # Percent escapes decode as UTF-8 before "~1" and "~0" are unescaped.
  report <- valid("#/definitions/%C3%A9")
  expect_identical(report$errors$keyword, "type")
  expect_identical(report$errors$schema_path,
                   "s.json#/definitions/%C3%A9/type")
  for (ref in c("#/definitions/a~1b", "#/definitions/a%2Fb",
                "#/definitions/a%7E1b", "#/definitions/a%7e1b")) {
    report <- valid(ref)
    expect_identical(report$errors$keyword, "type", info = ref)
    expect_identical(report$errors$schema_path,
                     paste0("s.json", ref, "/type"), info = ref)
  }
  # An array or null target has no keywords and accepts every instance.
  expect_true(valid("#/definitions/x/enum")$valid)
  expect_true(valid("#/definitions/n/const")$valid)
})

test_that("patterns the engines read differently are not portable", {
  problem <- function(pattern) {
    body <- schema_text(r"("pattern":)", qp_json_encode(qp_scalar(pattern)))
    schema <- local_schemas(p.json = body)
    cnd <- schema_error(qp_schema_validate(qp_as_json(r"("a")"), schema))
    if (inherits(cnd, "qp_error")) cnd$details$reason else "portable"
  }
  ambiguous <- c("[[:alpha:]]", "^[a&&b]$", "^[a[b]]$", "[]a[]", "\\0", "\\N",
                 "\\o{17}", "\\g1", "\\C", "a{,2}", "a{1", "{", "a{1, 2}(")
  for (pattern in ambiguous) {
    expect_identical(problem(pattern), "pattern_not_portable", info = pattern)
  }
  portable <- c("a{2}", "a{1,}", "[{]", "[^]{]", "a\\{", "a&&b", "^a[]]b$")
  for (pattern in portable) {
    expect_identical(problem(pattern), "portable", info = pattern)
  }
  expect_identical(problem("[a"), "pattern_does_not_compile")
})

test_that("uri-reference rejects a fixed set of code points in any locale", {
  s <- local_schemas(u.json = schema_text(r"("format":"uri-reference")"))
  rejected <- c(0x01, 0x09, 0x20, 0x7f, 0x85, 0x9f, 0x1680, 0x2000, 0x2006,
                0x2008, 0x200a, 0x2028, 0x2029, 0x205f, 0x3000)
  for (cp in rejected) {
    text <- qp_json_encode(qp_scalar(paste0("a", intToUtf8(cp), "b")))
    expect_identical(check(text, s), " format", info = sprintf("%04x", cp))
  }
  accepted <- c(0xa0, 0xe9, 0x2007, 0x200b, 0x202f, 0xfeff)
  for (cp in accepted) {
    text <- qp_json_encode(qp_scalar(paste0("a", intToUtf8(cp), "b")))
    expect_identical(check(text, s), character(), info = sprintf("%04x", cp))
  }
  bad_uris <- c("a<b", "a\"b", "a\\b", "a^b", "a`b", "a{b", "a|b", "%4",
                "%zz")
  for (bad in bad_uris) {
    expect_identical(check(qp_json_encode(qp_scalar(bad)), s), " format",
                     info = bad)
  }
  expect_identical(check(r"("../x.json#/a%20b")", s), character())
})

test_that("R values are encoded before validation like JSON text", {
  s <- local_schemas(v.json = schema_text(
    r"("type":"object","properties":{)",
    r"("ids":{"type":"array","items":{"type":"string"}},)",
    r"("n":{"type":"integer"}})"
  ))
  marked <- list(ids = "a", n = qp_scalar(2L))
  expect_true(qp_schema_validate(marked, s)$valid)
  unmarked <- qp_schema_validate(list(ids = "a", n = 2L), s)
  expect_identical(unmarked$errors$path, "/n")
  expect_identical(unmarked$errors$keyword, "type")
  as_text <- qp_json_encode(list(ids = "a", n = 2L))
  expect_identical(qp_schema_validate(as_text, s)$errors, unmarked$errors)
})

test_that("reports are sorted tibbles with readable output", {
  withr::local_options(qupflowR.language = "en")
  s <- local_schemas(r.json = schema_text(
    r"("type":"object","required":["z"],)",
    r"("properties":{"b":{"type":"string"},"a":{"type":"string"}})"
  ))
  report <- qp_schema_validate(qp_as_json(r"({"b":1,"a":2})"), s)
  expect_s3_class(report, "qp_validation_report")
  expect_false(report$valid)
  expect_identical(report$schema, "r.json")
  expect_s3_class(report$errors, "tbl_df")
  expect_identical(names(report$errors),
                   c("path", "keyword", "message", "schema_path"))
  expect_identical(report$errors$path, c("", "/a", "/b"))
  expect_identical(format(report), c(
    "Invalid against r.json: 3 errors.",
    "* (root) [required] Required property `z` is missing.",
    "* /a [type] Expected string, found integer.",
    "* /b [type] Expected string, found integer."
  ))
  expect_output(print(report), "Invalid against r.json", fixed = TRUE)
  ok <- qp_schema_validate(qp_as_json(r"({"z":null})"), s)
  expect_true(ok$valid)
  expect_identical(nrow(ok$errors), 0L)
  expect_identical(format(ok), "Valid against r.json.")
})

test_that("qp_schema_assert() returns the value or signals the errors", {
  withr::local_options(qupflowR.language = "en")
  s <- local_schemas(t.json = schema_text(
    r"("type":"array","items":{"type":"integer"})"
  ))
  value <- c(1L, 2L)
  expect_identical(qp_schema_assert(value, s), value)
  bad <- qp_as_json(r"(["a",1,2,3,4,5,"b","c","d","e","f"])")
  cnd <- schema_error(qp_schema_assert(bad, s))
  expect_s3_class(cnd, "qp_validation_error")
  expect_identical(cnd$code, "VALIDATION_FAILED")
  expect_identical(cnd$details$schema, "t.json")
  expect_length(cnd$details$errors, 6L)
  expect_identical(
    cnd$details$errors[[1]],
    list(path = "/0", keyword = "type",
         message = "Expected integer, found string.")
  )
  msg <- conditionMessage(cnd)
  expect_match(msg, "Invalid against t.json: 6 errors.", fixed = TRUE)
  expect_match(msg, "/0: Expected integer, found string.", fixed = TRUE)
  expect_match(msg, "and 1 more error.", fixed = TRUE)
})

test_that("installed schemas are found by name", {
  path <- qp_schema_path("health")
  expect_true(file.exists(path))
  expect_identical(basename(path), "health.schema.json")
  expect_identical(schema_error(qp_schema_path("no-such-schema"))$code,
                   "SCHEMA_INVALID")
  expect_identical(schema_error(qp_schema_path("../health"))$code,
                   "INVALID_ARGUMENT")
  expect_identical(schema_error(qp_schema_path("health", set = "Up"))$code,
                   "INVALID_ARGUMENT")
  expect_identical(schema_error(qp_schema_validate(1, 1))$code,
                   "INVALID_ARGUMENT")
})

test_that("schema caches notice changed files", {
  s <- local_schemas(c.json = schema_text(r"("type":"integer")"))
  expect_identical(check(r"("a")", s), " type")
  writeLines(schema_text(r"("type":"string","maxLength":10)"), s)
  Sys.setFileTime(s, Sys.time() + 5)
  expect_identical(check(r"("a")", s), character())
})

test_that("pointers into arrays and empty objects resolve", {
  s <- local_schemas(p.json = schema_text(
    r"("definitions":{"alts":{"anyOf":[{"type":"string"},)",
    r"({"type":"null"}]}},"properties":{)",
    r"("a":{"$ref":"#/definitions/alts/anyOf/1"},"b":{"enum":[{}]}})"
  ))
  expect_identical(check(r"({"a":null,"b":{}})", s), character())
  expect_identical(check(r"({"a":"x"})", s), "/a type")
  expect_identical(check(r"({"b":{"k":1}})", s), "/b enum")
})

test_that("malformed pointers and symbolic links cannot escape", {
  unresolved <- function(ref) {
    schema <- local_schemas(u.json = schema_text(
      r"("title":"t","definitions":{"list":{"anyOf":[true]}},)",
      "\"properties\":{\"a\":{\"$ref\":", quoted(ref), "}}"
    ))
    cnd <- schema_error(qp_schema_validate(qp_as_json(r"({"a":1})"), schema))
    cnd$details$reason
  }
  expect_identical(unresolved("#definitions"), "unresolved_ref")
  expect_identical(unresolved("#/title/x"), "unresolved_ref")
  expect_identical(unresolved("#/definitions/list/anyOf/5"), "unresolved_ref")
  expect_identical(unresolved("#/definitions/list/anyOf/first"),
                   "unresolved_ref")
  skip_on_os("windows")
  outside <- withr::local_tempdir()
  writeLines(schema_text(r"("type":"string")"), file.path(outside, "o.json"))
  main <- local_schemas(main.json = schema_text(r"("$ref":"link.json")"))
  link <- file.path(dirname(main), "link.json")
  file.symlink(file.path(outside, "o.json"), link)
  cnd <- schema_error(qp_schema_validate(qp_as_json("1"), main))
  expect_identical(cnd$details$reason, "ref_outside_schema_root")
})
