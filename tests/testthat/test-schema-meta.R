# Meta checks: supported keywords only, resolvable references, index and
# registry consistency.

schema_root <- function() system.file("schema", package = "qupflowR")

protocol_dir <- function() {
  system.file("schema", "protocol", "v1", package = "qupflowR")
}

all_schema_files <- function() {
  list.files(schema_root(), pattern = "[.]schema[.]json$", recursive = TRUE,
             full.names = TRUE)
}

draft07 <- "http://json-schema.org/draft-07/schema#"

# Keyword list typed from BLUEPRINT section 3 (decision D022).
supported <- c(
  "$schema", "$id", "$ref", "definitions", "title", "description", "type",
  "properties", "required", "additionalProperties", "items", "minItems",
  "maxItems", "uniqueItems", "enum", "const", "minimum", "maximum",
  "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "pattern",
  "format", "oneOf", "anyOf", "allOf", "not"
)

# Collects keywords, $refs and formats at schema positions, independent of
# the validator's own walker.
walk_schema <- function(node) {
  empty <- list(keywords = character(), refs = character(),
                formats = character())
  if (!is.list(node) || is.null(names(node))) {
    return(empty)
  }
  out <- empty
  out$keywords <- names(node)
  if (!is.null(node[["$ref"]])) out$refs <- node[["$ref"]]
  if (!is.null(node[["format"]])) out$formats <- node[["format"]]
  children <- c(unname(node[["definitions"]]), unname(node[["properties"]]))
  for (single in c("additionalProperties", "items", "not")) {
    if (is.list(node[[single]])) children <- c(children, list(node[[single]]))
  }
  for (combinator in c("oneOf", "anyOf", "allOf")) {
    children <- c(children, node[[combinator]])
  }
  for (child in children) {
    out <- Map(c, out, walk_schema(child))
  }
  out
}

test_that("every schema uses only supported keywords and formats", {
  files <- all_schema_files()
  expect_gte(length(files), 17L)
  formats <- c("uuid", "date-time", "sha256", "uri-reference")
  for (file in files) {
    name <- basename(file)
    walked <- walk_schema(jsonlite::read_json(file))
    expect_identical(setdiff(walked$keywords, supported), character(),
                     info = name)
    expect_true(all(walked$formats %in% formats), info = name)
    issues <- core_schema_lint_tree(core_read_json_tree(file))
    expect_identical(nrow(issues), 0L,
                     info = paste(name, issues$problem, collapse = " "))
  }
})

test_that("every $ref of every schema resolves inside the schema root", {
  root <- normalizePath(schema_root(), winslash = "/")
  resolved <- 0L
  for (file in all_schema_files()) {
    path <- normalizePath(file, winslash = "/")
    refs <- unique(walk_schema(jsonlite::read_json(file))$refs)
    ctx <- core_schema_context(root)
    for (ref in refs) {
      expect_false(grepl("^[a-z]+:", ref), info = ref)
      target <- core_schema_resolve_ref(ref, list(file = path, pointer = ""),
                                        ctx)
      expect_true(startsWith(target$file, paste0(root, "/")), info = ref)
      resolved <- resolved + 1L
    }
  }
  expect_gt(resolved, 50L)
})

test_that("schemas are Draft-07 documents matching the index", {
  index <- jsonlite::read_json(file.path(protocol_dir(), "index.json"))
  expect_identical(index$protocol_version, "1.0")
  expect_identical(index$json_schema_draft, draft07)
  listed <- vapply(index$schemas, function(s) s$file, "")
  present <- list.files(protocol_dir(), pattern = "[.]schema[.]json$")
  expect_setequal(listed, present)
  for (entry in index$schemas) {
    schema <- jsonlite::read_json(file.path(protocol_dir(), entry$file))
    expect_identical(schema[["$schema"]], draft07)
    expect_identical(schema[["$id"]], entry$id)
    expect_true(endsWith(entry$id, paste0("/protocol/v1/", entry$file)))
    expect_true(nzchar(schema$title) && nzchar(schema$description),
                info = entry$file)
  }
  expected <- c(
    "common", "envelope-success", "envelope-error", "error", "health",
    "capabilities", "discovery-manifest", "session", "image-entry",
    "image-info", "object-record", "measurement-record", "patch-request",
    "patch", "event", "job", "error-codes"
  )
  expect_setequal(vapply(index$schemas, function(s) s$name, ""), expected)
})

test_that("the hyperannotatR capability vocabulary is its own indexed set", {
  dir <- system.file("schema", "annotatr-control", "v1", package = "qupflowR")
  index <- jsonlite::read_json(file.path(dir, "index.json"))
  expect_identical(index$json_schema_draft, draft07)
  expect_identical(index$partner_contract, "annotatr-control-v1")
  listed <- vapply(index$schemas, function(s) s$file, "")
  expect_setequal(listed, list.files(dir, pattern = "[.]schema[.]json$"))
  for (entry in index$schemas) {
    schema <- jsonlite::read_json(file.path(dir, entry$file))
    expect_identical(schema[["$id"]], entry$id)
    expect_true(endsWith(entry$id,
                         paste0("/annotatr-control/v1/", entry$file)))
    refs <- walk_schema(schema)$refs
    expect_true(all(startsWith(refs, "#")), info = "self-contained set")
  }
  capabilities <- jsonlite::read_json(file.path(dir,
                                                "capabilities.schema.json"))
  profiles <- capabilities$properties$profiles
  expect_identical(unlist(profiles$required), sprintf("I%d", 0:4))
  names_by_id <- vapply(profiles$properties, function(p) {
    p$allOf[[2]]$properties$name$const
  }, "")
  expect_identical(names_by_id, c(
    I0 = "file_interchange", I1 = "r_interface", I2 = "controlled_process",
    I3 = "embedded_app", I4 = "shared_validation"
  ))
  reason_codes <- unlist(capabilities$definitions$profile$properties$
                           reason_code$enum)
  expect_setequal(reason_codes, c(
    "package_not_installed", "package_version_too_old",
    "adapter_not_implemented", "partner_release_required",
    "control_protocol_mismatch", "validation_contract_missing"
  ))
})

test_that("the error registry is consistent", {
  file <- file.path(protocol_dir(), "error-codes.json")
  text <- qp_as_json(readBin(file, "raw", file.size(file)))
  report <- qp_schema_validate(text, "error-codes")
  expect_true(report$valid,
              info = paste(report$errors$path, report$errors$keyword))
  raw <- jsonlite::read_json(file)
  codes <- vapply(raw, function(e) e$code, "")
  expect_identical(anyDuplicated(codes), 0L)
  classes <- c(
    "qp_connection_error", "qp_version_error", "qp_capability_error",
    "qp_validation_error", "qp_conflict_error", "qp_busy_error",
    "qp_recovery_error", "qp_job_error", "qp_bundle_error",
    "qp_runtime_error", "qp_reproduction_error", "qp_serialization_error",
    "qp_storage_error"
  )
  for (entry in raw) {
    expect_identical(entry$message_key, paste0("error.", tolower(entry$code)))
    if (!is.null(entry$http_status)) {
      expect_true(entry$http_status >= 400 && entry$http_status <= 599,
                  info = entry$code)
    }
    expect_true(entry$r_class %in% classes, info = entry$code)
  }
  retryable <- sort(codes[vapply(raw, function(e) isTRUE(e$retryable), NA)])
  expect_identical(retryable, c("HOST_BUSY", "PROJECT_BUSY", "REQUEST_TIMEOUT"))
})

test_that("the lint rules reject what the validator does not support", {
  lint <- function(...) {
    core_schema_lint_tree(jsonlite::parse_json(paste0(...)))$problem
  }
  head <- paste0("{\"$schema\":\"", draft07, "\"")
  expect_identical(lint(head, "}"), character())
  expect_identical(lint(head, r"(,"patternProperties":{}})"),
                   "unsupported_keyword")
  expect_identical(lint(head, r"(,"if":{},"then":{}})"),
                   c("unsupported_keyword", "unsupported_keyword"))
  expect_identical(lint(head, r"(,"properties":{"a":{"$schema":"x"}}})"),
                   "root_only_keyword")
  expect_identical(lint(head, r"(,"required":["a","a"]})"),
                   "invalid_required")
  expect_identical(lint(head, r"(,"minLength":-1})"), "not_a_count")
  expect_identical(lint(head, r"(,"enum":[]})"), "invalid_enum")
  expect_identical(lint(head, r"(,"oneOf":[]})"), "branches_not_array")
  expect_identical(lint(head, r"(,"pattern":"a++"})"), "pattern_not_portable")
  expect_identical(lint(head, r"(,"pattern":"^(?:a|b)[.]$"})"), character())
  expect_identical(lint(head, r"(,"uniqueItems":"yes"})"), "not_a_boolean")
  expect_identical(lint("[]"), "schema_not_object")
})

test_that("the lint rules check keyword value types", {
  lint <- function(...) {
    core_schema_lint_tree(jsonlite::parse_json(paste0(...)))$problem
  }
  head <- paste0("{\"$schema\":\"", draft07, "\"")
  expect_identical(lint(head, r"(,"properties":[]})"), "members_not_object")
  expect_identical(lint(head, r"(,"$ref":1})"), "ref_not_string")
  expect_identical(lint(head, r"(,"minimum":"1"})"), "not_a_number")
  expect_identical(lint(head, r"(,"title":1})"), "not_a_string")
  expect_identical(lint(head, r"(,"pattern":1})"), "not_a_string")
})

test_that("installed schema paths fit portable tar file names", {
  files <- list.files(schema_root(), recursive = TRUE)
  paths <- file.path("qupflowR", "inst", "schema", files)
  expect_true(all(nchar(paths, type = "bytes") <= 100L),
              info = paste(paths[nchar(paths, type = "bytes") > 100L],
                           collapse = ", "))
})
