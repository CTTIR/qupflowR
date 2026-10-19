# Shared schema fixture corpora (also run by the Java bridge tests): the
# protocol set and the hyperannotatR capability vocabulary.

schema_sets <- c("protocol", "annotatr-control")

fixture_path <- function(..., set = "protocol") {
  system.file("schema", set, "v1", "fixtures", ..., package = "qupflowR")
}

fixture_files <- function(kind, set = "protocol") {
  files <- list.files(fixture_path(kind, set = set), pattern = "[.]json$",
                      full.names = TRUE)
  files[!grepl("[.]expect[.]json$", files)]
}

fixture_text <- function(path) {
  qupflowR::qp_as_json(readBin(path, "raw", file.size(path)))
}

schema_of <- function(path) sub("--.*$", "", basename(path))

schema_file <- function(path, set) {
  qp_schema_path(schema_of(path), set = set)
}

sidecar <- function(path) sub("[.]json$", ".expect.json", path)

error_key <- function(path, keyword, schema_path) {
  sort(paste(path, keyword, schema_path, sep = " | "))
}

# Minimal little-endian WKB reader for Point and Polygon, written for this
# test only; it shares no code with the package. Returns NULL for anything
# it does not recognise.
read_wkb <- function(bytes) {
  con <- rawConnection(bytes)
  on.exit(close(con))
  read_int <- function() {
    readBin(con, "integer", 1L, size = 4L, endian = "little")
  }
  read_doubles <- function(n) {
    readBin(con, "double", n, size = 8L, endian = "little")
  }
  if (!identical(readBin(con, "raw", 1L), as.raw(1L))) {
    return(NULL)
  }
  type <- read_int()
  if (type == 1L) {
    geometry <- list(type = "Point", coordinates = read_doubles(2L))
  } else if (type == 3L) {
    rings <- read_int()
    coordinates <- unlist(lapply(seq_len(rings), function(r) {
      read_doubles(2L * read_int())
    }))
    geometry <- list(type = "Polygon", coordinates = coordinates)
  } else {
    return(NULL)
  }
  if (length(readBin(con, "raw", 1L)) != 0L) {
    return(NULL)
  }
  geometry
}

pointer_get <- function(x, pointer) {
  for (segment in strsplit(sub("^/", "", pointer), "/", fixed = TRUE)[[1]]) {
    x <- x[[segment]]
  }
  x
}

test_that("every valid fixture passes its schema", {
  minimum <- c(protocol = 30L, "annotatr-control" = 2L)
  for (set in schema_sets) {
    files <- fixture_files("valid", set)
    expect_gte(length(files), minimum[[set]])
    for (file in files) {
      report <- qp_schema_validate(fixture_text(file), schema_file(file, set))
      found <- paste(report$errors$path, report$errors$keyword,
                     collapse = "; ")
      expect_true(report$valid, info = paste(set, basename(file), found))
    }
  }
})

test_that("every invalid fixture fails with exactly its declared errors", {
  minimum <- c(protocol = 60L, "annotatr-control" = 3L)
  for (set in schema_sets) {
    files <- fixture_files("invalid", set)
    expect_gte(length(files), minimum[[set]])
    for (file in files) {
      label <- paste(set, basename(file))
      expect_true(file.exists(sidecar(file)), info = label)
      expected <- jsonlite::read_json(sidecar(file))
      expect_identical(expected$schema, schema_of(file))
      expect_false(expected$valid)
      expect_true(nzchar(expected$rule))
      field <- function(name) {
        vapply(expected$errors, function(e) e[[name]], "")
      }
      want <- error_key(field("path"), field("keyword"), field("schema_path"))
      report <- qp_schema_validate(fixture_text(file), schema_file(file, set))
      errors <- report$errors
      got <- unique(error_key(errors$path, errors$keyword, errors$schema_path))
      expect_identical(got, want, info = label)
    }
  }
})

test_that("each schema has a valid and three distinct invalid fixtures", {
  minimum <- c(protocol = 15L, "annotatr-control" = 1L)
  for (set in schema_sets) {
    index <- jsonlite::read_json(
      system.file("schema", set, "v1", "index.json", package = "qupflowR")
    )
    listed <- Filter(function(s) isTRUE(s$fixtures), index$schemas)
    with_fixtures <- vapply(listed, function(s) s$name, "")
    expect_gte(length(with_fixtures), minimum[[set]])
    valid_schemas <- vapply(fixture_files("valid", set), schema_of, "",
                            USE.NAMES = FALSE)
    invalid <- fixture_files("invalid", set)
    invalid_schemas <- vapply(invalid, schema_of, "", USE.NAMES = FALSE)
    for (schema in with_fixtures) {
      expect_true(schema %in% valid_schemas, info = paste(set, schema))
      signatures <- vapply(invalid[invalid_schemas == schema], function(f) {
        e <- jsonlite::read_json(sidecar(f))$errors
        parts <- vapply(e, function(x) {
          paste(x$path, x$keyword, x$schema_path)
        }, "")
        paste(sort(parts), collapse = ";")
      }, "")
      expect_gte(length(unique(signatures)), 3L)
    }
    expect_setequal(unique(c(valid_schemas, invalid_schemas)), with_fixtures)
  }
})

test_that("measurement names are byte-exact, including the empty name", {
  file <- fixture_path("valid", "measurement-record--empty-name.json")
  record <- qp_json_decode(fixture_text(file))
  expect_identical(unclass(record$name), "")
  expect_true(qp_schema_validate(fixture_text(file),
                                 "measurement-record")$valid)
})

test_that("hyperannotatR control profiles cannot be advertised as available", {
  base <- jsonlite::read_json(fixture_path(
    "valid", "capabilities--installed.json", set = "annotatr-control"
  ))
  schema <- qp_schema_path("capabilities", set = "annotatr-control")
  states <- c("available", "unavailable", "unsupported", "planned")
  allowed <- list(I0 = states, I1 = states, I2 = states[3:4],
                  I3 = states[3:4], I4 = states)
  for (id in names(allowed)) {
    for (state in states) {
      doc <- base
      doc$profiles[[id]]$state <- state
      if (state == "available") {
        doc$profiles[[id]]["reason_code"] <- list(NULL)
        doc$profiles[[id]]["reason"] <- list(NULL)
      } else {
        doc$profiles[[id]]$reason_code <- "partner_release_required"
        doc$profiles[[id]]$reason <- "Explained."
      }
      text <- qp_as_json(jsonlite::toJSON(doc, auto_unbox = TRUE,
                                          null = "null"))
      report <- qp_schema_validate(text, schema)
      expect_identical(report$valid, state %in% allowed[[id]],
                       info = paste(id, state))
    }
  }
})

test_that("object fixtures carry real WKB for documented coordinates", {
  expectations <- list.files(fixture_path("valid"),
                             pattern = "[.]expect[.]json$",
                             full.names = TRUE)
  expect_gte(length(expectations), 3L)
  checked <- 0L
  for (file in expectations) {
    spec <- jsonlite::read_json(file)
    instance <- jsonlite::read_json(sub("[.]expect[.]json$", ".json", file))
    for (pointer in names(spec$wkb)) {
      label <- paste(basename(file), pointer)
      bytes <- jsonlite::base64_dec(pointer_get(instance, pointer))
      geometry <- read_wkb(bytes)
      want <- spec$wkb[[pointer]]
      expect_false(is.null(geometry), info = label)
      expect_identical(geometry$type, want$type, info = label)
      expect_identical(geometry$coordinates,
                       as.double(unlist(want$coordinates)), info = label)
      checked <- checked + 1L
    }
  }
  expect_gte(checked, 4L)
})

test_that("object fixtures cover planes, classes and special values", {
  cell <- qp_json_decode(fixture_text(
    fixture_path("valid", "object-record--cell-with-nucleus.json")
  ))
  expect_null(cell$plane$c)
  expect_identical(cell$class_path, c("Tumor", "Positive"))
  probability <- cell$class_probability
  expect_identical(
    qp_value_from_state(probability$value, unclass(probability$value_state)),
    NaN
  )
  detection <- qp_json_decode(fixture_text(
    fixture_path("valid", "object-record--detection-point-channel.json")
  ))
  expect_identical(unclass(detection$plane$c), 1L)
  expect_identical(detection$class_path[[1]],
                   "Gr\u00f6\u00dfe (\u00b5m)")
  root <- qp_json_decode(fixture_text(
    fixture_path("valid", "object-record--root.json")
  ))
  expect_null(root$roi)
})
