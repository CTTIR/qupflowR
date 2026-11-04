# Shared numerical admission vectors and references into non-schema data.
test_that("nonzero numbers below binary64 range cannot become zero", {
  vectors <- jsonlite::read_json(system.file(
    "schema/protocol/v1/fixtures/number-range.json", package = "qupflowR"
  ))
  for (case in vectors$rejected) {
    cnd <- tryCatch(qp_json_decode(case$text), qp_error = identity)
    expect_s3_class(cnd, "qp_serialization_error")
    if (!inherits(cnd, "qp_error")) next
    expect_identical(cnd$code, "JSON_UNREPRESENTABLE")
    expect_identical(cnd$details$reason, "number_out_of_range")
    expect_identical(cnd$details$path, case$pointer)
  }
  values <- c("0" = 0, min_subnormal = 2^-1074,
              negative_min_subnormal = -2^-1074,
              min_normal = .Machine$double.xmin,
              max_finite = .Machine$double.xmax, "1.25" = 1.25)
  for (case in vectors$accepted) {
    decoded <- as.numeric(qp_json_decode(case$text))
    expect_identical(decoded, unname(values[[case$value]]), info = case$text)
    encoded <- qp_json_encode(qp_scalar(decoded))
    expect_identical(as.numeric(qp_json_decode(encoded)), decoded)
  }
  expect_identical(qp_json_decode('"1e-400"'), qp_scalar("1e-400"))
  # Structural error precedence remains depth-first, even with underflow.
  cnd <- tryCatch(qp_json_decode('{"a":1,"a":1e-400}'), qp_error = identity)
  expect_identical(cnd$details$reason, "duplicate_key")
  cnd <- tryCatch(qp_json_decode('[1e-400,{"a":1,"a":2}]'), qp_error = identity)
  expect_identical(cnd$details$reason, "number_out_of_range")
})

test_that("references into data must themselves be supported schemas", {
  directory <- withr::local_tempdir()
  cases <- list("null" = "schema_not_object", "[]" = "schema_not_object",
                "[1]" = "schema_not_object", '"text"' = "schema_not_object",
                "1" = "schema_not_object", '{"type":"float"}' = "invalid_type",
                '{"minProperties":1}' = "unsupported_keyword",
                '{"properties":{"x":{"type":"float"}}}' = "invalid_type")
  for (value in names(cases)) {
    name <- paste0("case-", match(value, names(cases)), ".json")
    path <- file.path(directory, name)
    text <- paste0('{"$schema":"http://json-schema.org/draft-07/schema#",',
                   '"definitions":{"data":{"const":', value, "}},",
                   '"$ref":"#/definitions/data/const"}')
    writeLines(text, path)
    for (hint in c(FALSE, TRUE)) {
      cnd <- tryCatch({
        if (hint) {
          qp_json_encode(1, schema_hint = path)
        } else {
          qp_schema_validate(qp_as_json("1"), path)
        }
      }, qp_error = identity)
      expect_s3_class(cnd, "qp_validation_error")
      if (!inherits(cnd, "qp_error")) next
      expect_identical(cnd$code, "SCHEMA_INVALID")
      expect_identical(cnd$details$reason, cases[[value]])
    }
  }
  valid <- c("true", "false", "{}", '{"type":"integer"}')
  for (value in valid) {
    name <- paste0("valid-", match(value, valid), ".json")
    path <- file.path(directory, name)
    writeLines(paste0('{"$schema":"http://json-schema.org/draft-07/schema#",',
                      '"definitions":{"data":{"const":', value, "}},",
                      '"$ref":"#/definitions/data/const"}'), path)
    report <- qp_schema_validate(qp_as_json("1"), path)
    expect_identical(report$valid, value != "false", info = value)
  }
})
