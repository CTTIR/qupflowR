# Expected-value tables of synthetic fixture set 1 (tests/fixtures/qp-v1).
#
# The first group checks the committed tables for internal consistency by
# recomputing every derived value in R from the documented formulas. The
# second group runs only when a generated fixture tree exists
# (QUPFLOWR_FIXTURES_DIR, default work/fixtures/v1 of a source checkout). It
# checks the generator manifest and expected-values document against the
# tables, reads the TIFF pixel bytes with the small reader below and inspects
# the QuPath project files as JSON.

fx_ns <- "qupflowr-fixtures-v1"

fx_table <- function(...) {
  testthat::test_path("..", "fixtures", "qp-v1", ...)
}

fx_csv <- function(name) {
  utils::read.csv(fx_table(name), colClasses = "character",
                  na.strings = "NA", fileEncoding = "UTF-8",
                  check.names = FALSE)
}

fx_json <- function(path) {
  jsonlite::fromJSON(path, simplifyVector = FALSE)
}

fx_num <- function(x) {
  suppressWarnings(as.numeric(x))
}

fx_hex_bytes <- function(hex) {
  strtoi(substring(hex, seq(1L, nchar(hex) - 1L, 2L),
                   seq(2L, nchar(hex), 2L)), 16L)
}

fx_md5 <- function(bytes) {
  if ("bytes" %in% names(formals(tools::md5sum))) {
    return(unname(tools::md5sum(bytes = bytes)))
  }
  testthat::skip_if_not_installed("digest")
  digest::digest(bytes, algo = "md5", serialize = FALSE)
}

fx_sha256_bytes <- function(bytes) {
  if (exists("sha256sum", envir = asNamespace("tools"), inherits = FALSE) &&
        "bytes" %in% names(formals(tools::sha256sum))) {
    return(unname(tools::sha256sum(bytes = bytes)))
  }
  testthat::skip_if_not_installed("digest")
  digest::digest(bytes, algo = "sha256", serialize = FALSE)
}

fx_sha256_file <- function(path) {
  fx_sha256_bytes(readBin(path, "raw", file.size(path)))
}

# UUID version 3 as java.util.UUID.nameUUIDFromBytes computes it.
fx_uuid <- function(name) {
  bytes <- charToRaw(enc2utf8(paste0(fx_ns, ":", name)))
  h <- fx_hex_bytes(fx_md5(bytes))
  h[7L] <- bitwOr(bitwAnd(h[7L], 15L), 48L)
  h[9L] <- bitwOr(bitwAnd(h[9L], 63L), 128L)
  hex <- paste(sprintf("%02x", h), collapse = "")
  paste(substring(hex, c(1L, 9L, 13L, 17L, 21L), c(8L, 12L, 16L, 20L, 32L)),
        collapse = "-")
}

fx_f32 <- function(x) {
  readBin(writeBin(as.double(x), raw(), size = 4L), "double",
          n = length(x), size = 4L)
}

fx_bits <- function(x) {
  vapply(x, function(v) {
    big <- writeBin(v, raw(), size = 8L, endian = "big")
    paste(sprintf("%02x", as.integer(big)), collapse = "")
  }, character(1), USE.NAMES = FALSE)
}

fx_state <- function(x) {
  out <- rep("finite", length(x))
  out[is.nan(x)] <- "nan"
  out[!is.nan(x) & x == Inf] <- "pos_inf"
  out[!is.nan(x) & x == -Inf] <- "neg_inf"
  out
}

fx_rgb <- function(x, y) {
  fields <- rbind(c(255, 0, 0), c(0, 255, 0), c(0, 0, 255), c(17, 99, 201))
  top <- y < 192
  field <- fields[pmin(x %/% 128, 3) + 1, , drop = FALSE]
  cbind(ifelse(top, field[, 1], x %/% 2),
        ifelse(top, field[, 2], 255 - x %/% 2),
        ifelse(top, field[, 3], (y - 192) %/% 2))
}

fx_ome <- function(c, z, t, x, y) {
  1000 * c + 300 * z + 60 * t + x %% 50 + y %% 7
}

fx_shoelace <- function(p) {
  q <- rbind(p[-1L, , drop = FALSE], p[1L, ])
  abs(sum(p[, 1] * q[, 2] - q[, 1] * p[, 2])) / 2
}

fx_matrix <- function(points) {
  matrix(as.numeric(unlist(points)), ncol = 2L, byrow = TRUE)
}

fx_area <- function(roi) {
  switch(roi$kind,
    rectangle = roi$box[[3]] * roi$box[[4]],
    ellipse = pi * roi$box[[3]] * roi$box[[4]] / 4,
    polygon = fx_shoelace(fx_matrix(roi$points)),
    geometry = sum(vapply(roi$polygons, function(pg) {
      rings <- lapply(pg, fx_matrix)
      fx_shoelace(rings[[1]]) - sum(vapply(rings[-1L], fx_shoelace, 0))
    }, 0)),
    NA_real_
  )
}

fx_resolve <- function(from_dir, relative) {
  parts <- strsplit(from_dir, "/", fixed = TRUE)[[1]]
  for (p in strsplit(relative, "/", fixed = TRUE)[[1]]) {
    parts <- if (p == "..") parts[-length(parts)] else c(parts, p)
  }
  paste(parts, collapse = "/")
}

fx_generated_dir <- function() {
  dir <- Sys.getenv("QUPFLOWR_FIXTURES_DIR")
  if (!nzchar(dir)) {
    dir <- testthat::test_path("..", "..", "work", "fixtures", "v1")
  }
  dir
}

fx_skip_without_generated <- function() {
  dir <- fx_generated_dir()
  if (identical(Sys.getenv("QUPFLOWR_TEST_PROFILE"), "core")) {
    reason <- "generated fixtures are not used in the core test profile"
    code <- "forced_by_profile"
  } else if (!file.exists(file.path(dir, "manifest.json"))) {
    reason <- paste("no generated fixtures at", dir,
                    "(run Rscript tools/fixtures.R)")
    code <- "fixtures_not_generated"
  } else {
    return(normalizePath(dir, winslash = "/"))
  }
  record <- get0("qp_record_skip", mode = "function")
  if (!is.null(record)) {
    record("fixtures", code, reason, list(dir = dir))
  }
  testthat::skip(reason)
}

# Minimal classic TIFF reader (both byte orders): IFD tags and strip samples.
fx_tiff <- function(path) {
  bytes <- readBin(path, "raw", file.size(path))
  order <- rawToChar(bytes[1:2])
  testthat::expect_true(order %in% c("II", "MM"))
  weights <- function(n) {
    w <- 256^(seq_len(n) - 1)
    if (order == "MM") rev(w) else w
  }
  u16 <- function(pos) sum(as.numeric(bytes[pos + 1:2]) * weights(2))
  u32 <- function(pos) sum(as.numeric(bytes[pos + 1:4]) * weights(4))
  testthat::expect_identical(u16(2), 42)
  ifds <- list()
  offset <- u32(4)
  while (offset != 0 && length(ifds) < 64L) {
    n <- u16(offset)
    tags <- list()
    for (i in seq_len(n)) {
      e <- offset + 2 + (i - 1) * 12
      type <- u16(e + 2)
      count <- u32(e + 4)
      size <- c(1, 1, 2, 4, 8)[type]
      data <- if (size * count <= 4) e + 8 else u32(e + 8)
      k <- seq_len(count) - 1
      value <- switch(as.character(type),
        "2" = {
          text <- bytes[data + seq_len(count)]
          text <- rawToChar(text[text != as.raw(0)])
          Encoding(text) <- "UTF-8"
          text
        },
        "3" = vapply(k, function(j) u16(data + 2 * j), 0),
        "4" = vapply(k, function(j) u32(data + 4 * j), 0),
        "5" = vapply(k, function(j) u32(data + 8 * j) / u32(data + 8 * j + 4),
                     0),
        NULL
      )
      tags[[as.character(u16(e))]] <- value
    }
    ifds[[length(ifds) + 1L]] <- tags
    offset <- u32(offset + 2 + n * 12)
  }
  list(bytes = bytes, ifds = ifds, order = order)
}

fx_tiff_sample <- function(tiff, ifd, x, y, s) {
  tags <- tiff$ifds[[ifd + 1L]]
  width <- tags[["256"]]
  spp <- if (is.null(tags[["277"]])) 1 else tags[["277"]]
  bytes_per <- tags[["258"]][[1]] / 8
  rows <- if (is.null(tags[["278"]])) tags[["257"]] else tags[["278"]]
  strip <- y %/% rows
  pos <- tags[["273"]][[strip + 1]] +
    ((y - strip * rows) * width + x) * spp * bytes_per + s * bytes_per
  b <- as.numeric(tiff$bytes[pos + seq_len(bytes_per)])
  w <- 256^(seq_along(b) - 1)
  sum(b * if (tiff$order == "MM") rev(w) else w)
}

# ---- committed tables -------------------------------------------------------

test_that("pixel samples follow the RGB and OME formulas", {
  s <- fx_csv("pixel-samples.csv")
  x <- fx_num(s$x)
  y <- fx_num(s$y)
  c <- fx_num(s$c)
  z <- fx_num(s$z)
  t <- fx_num(s$t)
  value <- fx_num(s$value)
  rgb <- s$image_key %in% c("rgb", "rgb_nores")
  ome <- s$image_key == "ome"
  expect_true(all(rgb | ome))
  expect_identical(sum(rgb), 72L)
  expect_identical(sum(ome), 84L)
  expected_rgb <- fx_rgb(x[rgb], y[rgb])
  expect_identical(value[rgb], expected_rgb[cbind(seq_len(sum(rgb)),
                                                  c[rgb] + 1)])
  expect_identical(value[ome], fx_ome(c[ome], z[ome], t[ome], x[ome],
                                      y[ome]))
  expect_true(all(x[rgb] < 512 & y[rgb] < 384 & z[rgb] == 0 & t[rgb] == 0))
  expect_true(all(x[ome] < 256 & y[ome] < 192))
  planes <- unique(paste(c[ome], z[ome], t[ome]))
  expect_length(planes, 12L)
  expect_identical(max(value[ome]), 2415)
  expect_true(any(value[ome] > 255))
  # hand-computed anchors, independent of both formula implementations
  anchor <- function(key, xx, yy, cc, zz = 0, tt = 0) {
    value[s$image_key == key & x == xx & y == yy & c == cc & z == zz &
            t == tt]
  }
  expect_identical(anchor("rgb", 128, 0, 1), 255)
  expect_identical(anchor("rgb", 511, 383, 2), 95)
  expect_identical(anchor("rgb_nores", 384, 10, 2), 201)
  expect_identical(anchor("ome", 49, 6, 2, 1, 1), 2415)
  expect_identical(anchor("ome", 37, 11, 1, 0, 1), 1101)
})

test_that("the plane table follows the XYZCT order", {
  p <- fx_csv("planes.csv")
  ome <- p[p$image_key == "ome", ]
  ifd <- fx_num(ome$ifd)
  expect_identical(ifd, as.numeric(0:11))
  expect_identical(ifd, fx_num(ome$z) + 2 * (fx_num(ome$c) + 3 *
                                               fx_num(ome$t)))
  expect_true(all(is.na(p$c[p$image_key != "ome"])))
})

test_that("calibration is anisotropic where known and unknown otherwise", {
  cal <- fx_csv("calibration.csv")
  known <- cal$state == "known"
  expect_identical(cal$image_key[known], "ome")
  expect_identical(fx_num(cal$pixel_width[known]), 0.25)
  expect_identical(fx_num(cal$pixel_height[known]), 0.5)
  expect_identical(fx_num(cal$z_spacing[known]), 1.5)
  expect_identical(cal$unit[known], "µm")
  expect_true(all(is.na(cal$pixel_width[!known])))
  expect_setequal(cal$in_file[!known],
                  c("tiff_resolution_unitless_1_1", "absent",
                    "file_missing"))
  expect_identical(cal$exists_in_output[cal$in_file == "file_missing"],
                   "FALSE")
  expect_true(any(grepl("Ordner mit Ümlaut/", cal$path, fixed = TRUE)))
})

test_that("object identifiers, parents and planes are consistent", {
  obj <- fx_csv("objects.csv")
  cal <- fx_csv("calibration.csv")
  layout <- fx_json(fx_table("layout.json"))
  expect_identical(obj$object_id,
                   vapply(paste0(obj$entry_key, ":", obj$object_key),
                          fx_uuid, character(1), USE.NAMES = FALSE))
  expect_true(all(grepl("^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab]",
                        obj$object_id)))
  expect_false(anyDuplicated(obj$object_id) > 0L)
  for (i in which(!is.na(obj$parent_key))) {
    row <- obj$entry_key == obj$entry_key[[i]] &
      obj$object_key == obj$parent_key[[i]]
    expect_identical(sum(row), 1L)
    expect_identical(obj$parent_id[[i]], obj$object_id[row])
    expect_lt(which(row), i)
  }
  expect_true(all(obj$type %in% c("annotation", "detection", "cell",
                                  "tile")))
  expect_true(all(is.na(obj$nucleus_kind) | obj$type == "cell"))
  expect_identical(sum(obj$type == "cell" & !is.na(obj$nucleus_kind)), 1L)
  expect_identical(sum(obj$type == "cell" & is.na(obj$nucleus_kind)), 1L)
  components <- unlist(strsplit(obj$class_path[!is.na(obj$class_path)],
                                "|", fixed = TRUE))
  expect_false(any(grepl(":", components, fixed = TRUE)))
  expect_true("Tümor" %in% components)
  expect_true(any(is.na(obj$class_path)))
  expect_identical(obj$qupath_c == "-1", is.na(obj$c))
  expect_true(any(!is.na(obj$c)))
  images <- unlist(lapply(layout$projects, function(p) {
    vapply(p$entries, function(e) e$image_key, "")
  }))
  names(images) <- unlist(lapply(layout$projects, function(p) {
    vapply(p$entries, function(e) paste(p$key, e$key), "")
  }))
  key <- images[paste(obj$project, obj$entry_key)]
  dims <- cal[match(key, cal$image_key), ]
  expect_true(all(fx_num(obj$z) < fx_num(dims$size_z)))
  expect_true(all(fx_num(obj$t) < fx_num(dims$size_t)))
  expect_true(all(is.na(obj$c) | fx_num(obj$c) < fx_num(dims$size_c)))
  for (project in layout$projects) {
    for (entry in project$entries) {
      rows <- obj[obj$project == project$key & obj$entry_key == entry$key, ]
      counts <- list(
        annotations = sum(rows$type == "annotation"),
        detections_incl_cells_tiles = sum(rows$type != "annotation"),
        cells = sum(rows$type == "cell"), tiles = sum(rows$type == "tile"),
        objects = nrow(rows)
      )
      expect_identical(lapply(entry$counts, as.integer), counts)
      expect_identical(entry$root_object_id, fx_uuid(paste0(entry$key,
                                                            ":root")))
    }
  }
})

test_that("areas and stored vertices follow the area and float32 formulas", {
  obj <- fx_csv("objects.csv")
  cal <- fx_csv("calibration.csv")
  rois <- fx_json(fx_table("roi-coordinates.json"))
  expect_identical(vapply(rois, function(r) r$object_key, ""),
                   obj$object_key)
  ome <- cal[cal$image_key == "ome", ]
  for (i in seq_along(rois)) {
    roi <- rois[[i]]$roi
    expect_identical(roi$kind, obj$roi_kind[[i]])
    area <- fx_area(roi)
    expect_equal(fx_num(obj$area_px2[[i]]), area, tolerance = 1e-9)
    if (obj$entry_key[[i]] == "ome" && !is.na(area)) {
      expect_equal(fx_num(obj$area_calibrated[[i]]),
                   area * fx_num(ome$pixel_width) * fx_num(ome$pixel_height),
                   tolerance = 1e-9)
    } else {
      expect_true(is.na(obj$area_calibrated[[i]]))
    }
    float_kind <- roi$kind %in% c("points", "polygon", "polyline")
    expect_identical(obj$storage_precision[[i]],
                     if (float_kind) "float32" else "float64")
    if (!is.null(roi$points)) {
      expect_identical(fx_matrix(roi$stored_points),
                       matrix(fx_f32(fx_matrix(roi$points)), ncol = 2L))
    }
    nucleus <- rois[[i]]$nucleus
    if (!is.null(nucleus)) {
      expect_equal(fx_num(obj$nucleus_area_px2[[i]]), fx_area(nucleus),
                   tolerance = 1e-9)
      expect_identical(nucleus$plane, roi$plane)
    }
  }
  hole <- obj$object_key == "ann_polygon_hole"
  expect_identical(fx_num(obj$area_px2[hole]), 100 * 80 - 20 * 20)
  multi <- obj$object_key == "ann_multipolygon"
  expect_identical(fx_num(obj$area_px2[multi]), 10 * 10 + 20 * 5)
})

test_that("measurement states follow the float32 storage formula", {
  m <- fx_csv("measurements.csv")
  obj <- fx_csv("objects.csv")
  present <- m$input_state != "missing"
  input <- fx_num(m$input_value)
  input[m$input_state == "nan"] <- NaN
  input[m$input_state == "pos_inf"] <- Inf
  input[m$input_state == "neg_inf"] <- -Inf
  expect_identical(fx_state(input[present]), m$input_state[present])
  finite <- present & m$input_state == "finite"
  expect_identical(fx_bits(input[finite]), m$input_bits[finite])
  owner <- obj[match(paste(m$entry_key, m$object_key),
                     paste(obj$entry_key, obj$object_key)), ]
  expect_identical(m$object_id, owner$object_id)
  expect_identical(m$storage[present], owner$measurement_precision[present])
  stored <- ifelse(m$storage == "float32", fx_f32(input), input)
  expect_identical(fx_state(stored[present]), m$stored_state[present])
  sfinite <- present & m$stored_state == "finite"
  expect_identical(fx_bits(stored[sfinite]), m$stored_bits[sfinite])
  expect_equal(fx_num(m$stored_value[sfinite]), stored[sfinite],
               tolerance = 0)
  absent <- !present
  expect_true(all(m$stored_state[absent] == "missing"))
  expect_true(all(is.na(m$input_bits[absent]) & is.na(m$storage[absent])))
  expect_setequal(unique(c(m$input_state, m$stored_state)),
                  c("finite", "nan", "pos_inf", "neg_inf", "missing"))
  for (entry in unique(m$entry_key)) {
    rows <- m[m$entry_key == entry, ]
    expect_identical(nrow(rows), length(unique(rows$object_key)) *
                       length(unique(rows$name)))
    counted <- tapply(rows$input_state != "missing", rows$object_key, sum)
    owners <- obj[obj$entry_key == entry, ]
    measured <- owners[owners$measurement_count != "0", ]
    expect_identical(as.integer(counted[measured$object_key]),
                     as.integer(measured$measurement_count))
  }
  big <- m$name == "big double" & m$storage %in% "float32"
  expect_true(all(m$stored_state[big] == "pos_inf"))
  tiny <- m$name == "tiny double" & m$storage %in% "float32"
  expect_true(all(m$stored_bits[tiny] == "0000000000000000"))
  expect_true("" %in% m$name)
  expect_true("Area µm²" %in% m$name)
  expect_identical(sort(unique(m$name[m$dynamic_name_collision == "TRUE"])),
                   c("Area px^2", "Area µm^2"))
})

test_that("the layout describes the moved project and the deleted image", {
  layout <- fx_json(fx_table("layout.json"))
  cal <- fx_csv("calibration.csv")
  existing <- cal$path[cal$exists_in_output == "TRUE"]
  expect_identical(layout$fixture_set, "qupflowr-synthetic")
  expect_identical(layout$fixture_set_version, "1")
  expect_identical(layout$id_namespace, fx_ns)
  ome_uuid <- fx_uuid("ome-tiff:ome_3c2z2t_u16.ome.tif")
  expect_identical(layout$ome_uuid, paste0("urn:uuid:", ome_uuid))
  paths <- vapply(layout$expected_files, function(f) f$path, "")
  expect_identical(paths, sort(paths, method = "radix"))
  expect_false(anyDuplicated(paths) > 0L)
  expect_true(all(cal$path[cal$exists_in_output == "TRUE"] %in% paths))
  expect_false(any(cal$path[cal$exists_in_output == "FALSE"] %in% paths))
  absent <- unlist(layout$absent_paths)
  expect_false(any(vapply(absent, function(a) {
    any(paths == a | startsWith(paths, paste0(a, "/")))
  }, TRUE)))
  main <- layout$projects[[1]]
  expect_identical(vapply(main$entries, function(e) e$entry_id, ""),
                   as.character(1:5))
  for (e in main$entries) {
    expect_identical(e$image_exists, e$image_path %in% existing)
  }
  moved <- layout$projects[[2]]
  before <- dirname(moved$written_as)
  after <- dirname(moved$project_file)
  for (e in moved$entries) {
    to <- strsplit(e$image_path, "/", fixed = TRUE)[[1]]
    from <- strsplit(before, "/", fixed = TRUE)[[1]]
    common <- sum(cumprod(from[seq_len(min(length(from), length(to)))] ==
                            to[seq_len(min(length(from), length(to)))]))
    rest <- if (common > 0) to[-seq_len(common)] else to
    relative <- paste(c(rep("..", length(from) - common), rest),
                      collapse = "/")
    expect_identical(e$relative_to_project_dir, relative)
    expect_identical(e$relative_resolved, fx_resolve(after, relative))
    expect_identical(e$stored_path_exists, e$image_path %in% existing)
    expect_identical(e$relative_exists, e$relative_resolved %in% existing)
  }
  flags <- vapply(moved$entries, function(e) {
    c(e$stored_path_exists, e$relative_exists)
  }, logical(2))
  expect_identical(flags, matrix(c(FALSE, TRUE, TRUE, FALSE), 2L))
  negative <- layout$negative_cases[[1]]
  expect_true(grepl(":", negative$component, fixed = TRUE))
  expect_identical(negative$bridge_outcome, "VALIDATION_FAILED")
  expect_false(negative$object_created)
})

test_that("the raw TIFF digest is rebuilt from the TIFF layout and formula", {
  le16 <- function(v) as.raw(c(v %% 256, (v %/% 256) %% 256))
  le32 <- function(v) {
    as.raw(c(v %% 256, (v %/% 256) %% 256, (v %/% 65536) %% 256,
             (v %/% 16777216) %% 256))
  }
  entry <- function(tag, type, count, value) {
    field <- if (type == 3 && count == 1) c(le16(value), le16(0)) else
      le32(value)
    c(le16(tag), le16(type), le32(count), field)
  }
  pixels <- fx_rgb(rep(0:511, times = 384), rep(0:383, each = 512))
  bytes <- c(charToRaw("II"), le16(42), le32(8), le16(10),
             entry(256, 3, 1, 512), entry(257, 3, 1, 384),
             entry(258, 3, 3, 134), entry(259, 3, 1, 1),
             entry(262, 3, 1, 2), entry(273, 4, 1, 140),
             entry(277, 3, 1, 3), entry(278, 3, 1, 384),
             entry(279, 4, 1, 512 * 384 * 3), entry(284, 3, 1, 1), le32(0),
             le16(8), le16(8), le16(8), as.raw(c(t(pixels))))
  d <- fx_csv("digests.csv")
  row <- d$provenance == "r_reconstruction"
  expect_identical(d$path[row], "images/rgb_512x384_nores.tif")
  expect_identical(fx_num(d$size[row]), as.numeric(length(bytes)))
  expect_identical(d$sha256[row], fx_sha256_bytes(bytes))
})

# ---- generated fixture tree -------------------------------------------------

test_that("the generated manifest matches the committed expectations", {
  dir <- fx_skip_without_generated()
  manifest <- fx_json(file.path(dir, "manifest.json"))
  layout <- fx_json(fx_table("layout.json"))
  expect_identical(manifest$manifest_schema_version,
                   layout$manifest_schema_version)
  expect_identical(manifest$fixture_set, layout$fixture_set)
  expect_identical(manifest$fixture_set_version,
                   layout$fixture_set_version)
  expect_identical(manifest$generator$version, layout$generator_version)
  expect_identical(manifest$formula_ids, layout$formula_ids)
  expect_length(manifest$checks$failures, 0L)
  expect_identical(manifest$output_root, dir)
  files <- manifest$files
  keys <- c("path", "role", "normalisation")
  expect_identical(lapply(files, function(f) f[keys]), layout$expected_files)
  on_disk <- list.files(dir, recursive = TRUE, all.files = TRUE)
  expect_setequal(enc2utf8(on_disk),
                  c("manifest.json", vapply(files, function(f) f$path, "")))
  paths <- file.path(dir, vapply(files, function(f) f$path, ""))
  expect_identical(vapply(files, function(f) as.numeric(f$size), 0),
                   as.numeric(file.size(paths)))
  expect_identical(vapply(files, function(f) f$sha256, ""),
                   vapply(paths, fx_sha256_file, "", USE.NAMES = FALSE))
  d <- fx_csv("digests.csv")
  sha <- stats::setNames(vapply(files, function(f) f$sha256, ""),
                         vapply(files, function(f) f$path, ""))
  expect_identical(unname(sha[d$path]), d$sha256)
  for (absent in unlist(layout$absent_paths)) {
    expect_false(file.exists(file.path(dir, absent)))
  }
  oracle <- manifest$oracle
  expect_true(oracle$written_before_any_reader)
  expect_identical(oracle$expected_values_sha256_at_write,
                   oracle$expected_values_sha256_at_end)
  expect_identical(fx_sha256_file(file.path(dir, oracle$expected_values)),
                   oracle$expected_values_sha256_at_write)
  phases <- vapply(manifest$phases, function(p) p$phase, "")
  expect_lt(match("expected_values_written", phases),
            match("main_project_written", phases))
})

test_that("generated expected values agree with the committed tables", {
  dir <- fx_skip_without_generated()
  ev <- fx_json(file.path(dir, "expected", "expected-values.json"))
  s <- fx_csv("pixel-samples.csv")
  gen <- do.call(rbind, lapply(ev$pixel_samples, as.data.frame))
  expect_identical(gen$image_key, s$image_key)
  for (col in c("x", "y", "c", "z", "t", "value")) {
    expect_identical(as.numeric(gen[[col]]), fx_num(s[[col]]))
  }
  planes <- fx_csv("planes.csv")
  expect_identical(vapply(ev$planes, function(p) {
    paste(p$image_key, p$ifd, if (is.null(p$c)) "NA" else p$c, p$z, p$t)
  }, ""), paste(planes$image_key, planes$ifd, planes$c, planes$z, planes$t))
  cal <- fx_csv("calibration.csv")
  for (img in ev$images) {
    row <- cal[cal$image_key == img$key, ]
    expect_identical(img$path, row$path)
    expect_identical(img$calibration$state, row$state)
    expect_identical(img$calibration$in_file, row$in_file)
    expect_identical(img$exists_in_output, row$exists_in_output == "TRUE")
    expect_identical(as.numeric(c(img$width, img$height, img$size_c,
                                  img$size_z, img$size_t)),
                     fx_num(unlist(row[c("width", "height", "size_c",
                                         "size_z", "size_t")])))
  }
  obj <- fx_csv("objects.csv")
  rois <- fx_json(fx_table("roi-coordinates.json"))
  layout <- fx_json(fx_table("layout.json"))
  i <- 0L
  for (p in seq_along(ev$projects)) {
    gp <- ev$projects[[p]]
    cp <- layout$projects[[p]]
    expect_identical(gp$project_file, cp$project_file)
    expect_identical(gp$written_as, cp$written_as)
    keep <- setdiff(names(cp$entries[[1]]), "objects")
    expect_identical(lapply(gp$entries, function(e) e[keep]),
                     lapply(cp$entries, function(e) e[keep]))
    for (e in gp$entries) {
      for (o in e$objects) {
        i <- i + 1L
        row <- obj[i, ]
        expect_identical(c(o$key, o$object_id, o$type, o$roi$kind),
                         c(row$object_key, row$object_id, row$type,
                           row$roi_kind))
        parent <- if (is.null(o$parent_id)) NA_character_ else o$parent_id
        expect_identical(parent, row$parent_id)
        class_path <- if (is.null(o$class_path)) {
          NA_character_
        } else {
          paste(unlist(o$class_path), collapse = "|")
        }
        expect_identical(class_path, row$class_path)
        expect_identical(o$class_probability$value_state,
                         row$class_probability_state)
        expect_identical(o$roi$plane, rois[[i]]$roi$plane)
        area <- if (is.null(o$roi$area_px2)) NA_real_ else o$roi$area_px2
        expect_equal(area, fx_num(row$area_px2), tolerance = 1e-9)
        expect_identical(fx_matrix(o$roi$stored_points),
                         fx_matrix(rois[[i]]$roi$stored_points))
        expect_identical(o$measurement_count,
                         as.integer(row$measurement_count))
      }
    }
  }
  expect_identical(i, nrow(obj))
  m <- fx_csv("measurements.csv")
  expect_length(ev$measurements, nrow(m))
  gm <- vapply(ev$measurements, function(r) {
    paste(r$object_id, r$name, r$input$value_state, r$stored$value_state,
          if (is.null(r$stored$bits)) "NA" else r$stored$bits,
          r$dynamic_name_collision)
  }, "")
  expect_identical(gm, paste(m$object_id, m$name, m$input_state,
                             m$stored_state, m$stored_bits,
                             m$dynamic_name_collision))
  expect_identical(ev$negative_cases[[1]]$component,
                   layout$negative_cases[[1]]$component)
})

test_that("an independent R reader finds the pixel samples in the TIFFs", {
  dir <- fx_skip_without_generated()
  s <- fx_csv("pixel-samples.csv")
  cal <- fx_csv("calibration.csv")
  planes <- fx_csv("planes.csv")
  files <- c(rgb = "rgb", rgb_umlaut_dir = "rgb", moved_rgb = "rgb",
             rgb_nores = "rgb_nores", ome = "ome")
  for (image in names(files)) {
    path <- file.path(dir, cal$path[cal$image_key == image])
    tiff <- fx_tiff(path)
    rows <- s[s$image_key == files[[image]], ]
    for (k in seq_len(nrow(rows))) {
      r <- fx_num(unlist(rows[k, c("x", "y", "c", "z", "t", "value")]))
      if (files[[image]] == "ome") {
        pl <- planes[planes$image_key == "ome" & fx_num(planes$c) == r[[3]] &
                       fx_num(planes$z) == r[[4]] &
                       fx_num(planes$t) == r[[5]], ]
        got <- fx_tiff_sample(tiff, fx_num(pl$ifd), r[[1]], r[[2]], 0)
      } else {
        got <- fx_tiff_sample(tiff, 0L, r[[1]], r[[2]], r[[3]])
      }
      expect_identical(got, r[[6]], label = paste(image, k))
    }
  }
  nores <- fx_tiff(file.path(dir, cal$path[cal$image_key == "rgb_nores"]))
  expect_false(any(c("282", "283", "296") %in% names(nores$ifds[[1]])))
  rgb <- fx_tiff(file.path(dir, cal$path[cal$image_key == "rgb"]))
  expect_identical(rgb$ifds[[1]][["296"]], 1)
  ome <- fx_tiff(file.path(dir, cal$path[cal$image_key == "ome"]))
  expect_length(ome$ifds, 12L)
  xml <- ome$ifds[[1]][["270"]]
  attr_value <- function(name) {
    sub(paste0(".*\\b", name, "=\"([^\"]*)\".*"), "\\1", xml)
  }
  row <- cal[cal$image_key == "ome", ]
  expect_identical(fx_num(attr_value("PhysicalSizeX")),
                   fx_num(row$pixel_width))
  expect_identical(fx_num(attr_value("PhysicalSizeY")),
                   fx_num(row$pixel_height))
  expect_identical(fx_num(attr_value("PhysicalSizeZ")), fx_num(row$z_spacing))
  expect_identical(attr_value("PhysicalSizeXUnit"), row$unit)
  channels <- regmatches(xml, gregexpr("<Channel [^>]*>", xml))[[1]]
  expect_identical(sub(".*Name=\"([^\"]*)\".*", "\\1", channels),
                   c("DAPI", "CD8 µ", "Kanal Ü"))
  layout <- fx_json(fx_table("layout.json"))
  uuids <- regmatches(xml, gregexpr("urn:uuid:[0-9a-f-]{36}", xml))[[1]]
  expect_identical(unique(uuids), layout$ome_uuid)
  expect_length(uuids, 13L)
})

test_that("the generated QuPath projects reference the expected images", {
  dir <- fx_skip_without_generated()
  layout <- fx_json(fx_table("layout.json"))
  obj <- fx_csv("objects.csv")
  for (project in layout$projects) {
    qp <- fx_json(file.path(dir, project$project_file))
    expect_identical(qp$version, "0.7.0")
    expect_identical(qp$lastID, length(project$entries))
    written <- utils::URLdecode(sub("^file:", "", qp$uri))
    expect_identical(enc2utf8(written),
                     file.path(dir, project$written_as))
    expect_length(qp$images, length(project$entries))
    for (k in seq_along(project$entries)) {
      e <- project$entries[[k]]
      img <- qp$images[[k]]
      expect_identical(as.character(img$entryID), e$entry_id)
      expect_identical(img$imageName, e$image_name)
      expect_identical(img$metadata[["qupflowr.fixture"]], e$key)
      uri <- enc2utf8(utils::URLdecode(sub("^file:", "",
                                           img$serverBuilder$uri)))
      expect_identical(uri, file.path(dir, e$image_path))
      stored_exists <- if (is.null(e$stored_path_exists)) e$image_exists else
        e$stored_path_exists
      expect_identical(file.exists(uri), stored_exists)
      data_dir <- file.path(dirname(file.path(dir, project$project_file)),
                            "data", e$entry_id)
      expect_true(file.exists(file.path(data_dir, "data.qpdata")))
      summary <- fx_json(file.path(data_dir, "summary.json"))
      n_objects <- sum(obj$project == project$key &
                         obj$entry_key == e$key)
      expect_identical(summary$hierarchy$nObjects, n_objects + 1L)
    }
  }
  classes <- fx_json(file.path(dir, "project", "classifiers",
                               "classes.json"))
  expect_identical(vapply(classes$pathClasses, function(x) x$name, ""),
                   vapply(layout$projects[[1]]$classes, function(x) {
                     paste(unlist(x), collapse = ": ")
                   }, ""))
})
