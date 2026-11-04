cttir_branding_abort <- get("cttir_branding_abort", mode = "function")

# Helpers are supplied by the sibling scripts loaded
# by cttir_hex.R.
cttir_primitives <- get("cttir_primitives", mode = "function")
cttir_alpha <- get("cttir_alpha", mode = "function")

# Forty-five bespoke vector illustrations, including
# two user-named suites.  Larger shapes define the
# silhouette. Secondary marks disappear in small
# icons.  The drawings are conceptual package
# identities, not scientific measurements.
cttir_motif <- function(name, s, small = FALSE) {
  primitives <- cttir_primitives(s, small)
  a <- s$mid
  p <- s$paper
  l <- s$light
  d <- s$deep
  e <- s$edge

  # SPECTRAL: data cubes, optical acquisition and
  # spectral composition.
  if (name == "hyperloom") {
    # A spectral cube whose crossing bands visibly
    # alternate over and under.  UV coordinates map a
    # rectangular weave onto the upper isometric
    # plane.
    uv <- function(u, v, fill, shadow = FALSE) {
      primitives$P(
        0.5 + 0.36 * (u - v), 0.86 - 0.18 * (u + v),
        fill, shadow = shadow
      )
    }
    primitives$P(
      c(0.14, 0.5, 0.5, 0.14),
      c(0.68, 0.5, 0.19, 0.37),
      d, shadow = TRUE
    )
    primitives$P(
      c(0.5, 0.86, 0.86, 0.5),
      c(0.5, 0.68, 0.37, 0.19),
      a, shadow = TRUE
    )
    centers <- if (small)
      c(0.25, 0.75) else c(0.17, 0.5, 0.83)
    half <- if (small)
      0.15 else 0.105
    for (i in base::seq_along(centers)) {
      z <- centers[i] + c(-half, half)
      # Continue the woven bands down both cube
      # faces.
      primitives$P(
        0.14 + 0.36 * c(z[1], z[2], z[2], z[1]),
        0.68 - 0.18 * c(z[1], z[2], z[2], z[1]) -
          c(0, 0, 0.31, 0.31),
        c(l, p, a)[i]
      )
      primitives$P(
        0.5 + 0.36 * c(z[1], z[2], z[2], z[1]),
        0.5 + 0.18 * c(z[1], z[2], z[2], z[1]) -
          c(0, 0, 0.31, 0.31),
        c(a, l, p)[i]
      )
    }
    uv(
      c(0, 1, 1, 0),
      c(0, 0, 1, 1),
      d
    )
    for (i in base::seq_along(centers)) {
      z <- centers[i] + c(-half, half)
      uv(
        c(z[1], z[2], z[2], z[1]),
        c(0, 0, 1, 1),
        c(l, p, a)[i]
      )
    }
    for (j in base::seq_along(centers)) {
      z <- centers[j] + c(-half, half)
      uv(
        c(0, 1, 1, 0),
        c(z[1], z[1], z[2], z[2]),
        c(a, l, p)[j]
      )
    }
    for (i in base::seq_along(centers)) {
      for (j in base::seq_along(centers)) {
        if ((i + j) %% 2 == 0) {
          uu <- centers[i] + c(-half, half)
          vv <- centers[j] + c(-half, half)
          uv(
            c(uu[1], uu[2], uu[2], uu[1]),
            c(vv[1], vv[1], vv[2], vv[2]),
            c(l, p, a)[i]
          )
        }
      }
    }
    if (!small) {
      primitives$L(
        c(0.14, 0.5, 0.86),
        c(0.68, 0.86, 0.68),
        p, 1.5
      )
      primitives$L(
        c(0.5, 0.5),
        c(0.49, 0.2),
        l, 1.5
      )
    }
  } else if (name == "phenoloom") {
    # Soft tissue threads alternate at crossings.
    # Cells fill the four gaps.
    ribbon <- function(x, y, width, fill, caps = TRUE, shadow = TRUE) {
      dx <- c(
        base::diff(x),
        utils::tail(
          base::diff(x),
          1
        )
      )
      dy <- c(
        base::diff(y),
        utils::tail(
          base::diff(y),
          1
        )
      )
      length <- base::sqrt(dx^2 + dy^2)
      nx <- -dy / length
      ny <- dx / length
      primitives$P(
        c(x + nx * width / 2, base::rev(x - nx * width / 2)),
        c(y + ny * width / 2, base::rev(y - ny * width / 2)),
        fill, shadow = shadow
      )
      if (caps) {
        primitives$C(
          x[1], y[1], width / 2, fill, col = fill,
          lwd = 0.8
        )
        primitives$C(
          utils::tail(x, 1),
          utils::tail(y, 1),
          width / 2, fill, col = fill, lwd = 0.8
        )
      }
    }
    tt <- base::seq(0.14, 0.86, length.out = 100)
    centers <- c(0.2, 0.5, 0.8)
    width <- 0.09
    vx <- function(t, x0) {
      x0 + 0.018 * base::sin((t - 0.14) * 2 * pi)
    }
    hy <- function(t, y0) {
      y0 + 0.018 * base::cos((t - 0.14) * 2 * pi)
    }
    for (i in 1:3) ribbon(
      vx(tt, centers[i]),
      tt, width, c(l, p, a)[i]
    )
    for (j in 1:3) ribbon(
      tt, hy(tt, centers[j]),
      width, c(a, l, p)[j]
    )
    for (i in 1:3) {
      for (j in 1:3) {
        if ((i + j) %% 2 == 0) {
          yy <- hy(centers[i], centers[j])
          part <- base::seq(yy - 0.065, yy + 0.065, length.out = 25)
          ribbon(
            vx(part, centers[i]),
            part, width, c(l, p, a)[i],
            caps = FALSE, shadow = FALSE
          )
        }
      }
    }
    xy <- base::rbind(
      c(0.35, 0.35),
      c(0.65, 0.35),
      c(0.35, 0.65),
      c(0.65, 0.65)
    )
    for (i in 1:4) primitives$cell(
      xy[i, 1], xy[i, 2], 0.085, c(a, l, p, a)[i],
      i
    )
  } else if (name == "hyperspectR") {
    primitives$slab(0.5, 0.31, 0.73, 0.34, 0.065, d)
    primitives$slab(0.5, 0.43, 0.73, 0.34, 0.045, a)
    primitives$slab(0.5, 0.55, 0.73, 0.34, 0.045, l)
    primitives$slab(0.5, 0.7, 0.73, 0.34, 0.07, p, grid = TRUE)
    primitives$P(
      c(0.5, 0.865, 0.865, 0.5),
      c(0.53, 0.7, 0.63, 0.46),
      a
    )
    if (!small)
      for (t in c(0.2, 0.4, 0.6, 0.8)) primitives$L(
        c(0.5 + 0.365 * t, 0.5 + 0.365 * t),
        c(0.53 + 0.17 * t, 0.46 + 0.17 * t),
        l, 2
      )
    primitives$L(
      c(0.14, 0.14),
      c(0.32, 0.7),
      l, 2
    )
    primitives$C(0.14, 0.7, 0.019, p)
  } else if (name == "cuvis.r") {
    primitives$slab(0.48, 0.28, 0.76, 0.33, 0.06, d, TRUE)
    primitives$P(
      c(0.21, 0.69, 0.8, 0.32),
      c(0.46, 0.46, 0.57, 0.57),
      l, shadow = TRUE
    )
    primitives$B(0.46, 0.61, 0.49, 0.34, a, 0.04, shadow = TRUE)
    primitives$P(
      c(0.705, 0.81, 0.81, 0.705),
      c(0.44, 0.55, 0.85, 0.78),
      d
    )
    primitives$P(
      c(0.215, 0.32, 0.81, 0.705),
      c(0.78, 0.87, 0.87, 0.78),
      l
    )
    primitives$disc(0.46, 0.61, 0.185, p)
    primitives$C(0.46, 0.62, 0.13, d)
    primitives$C(0.46, 0.62, 0.087, a)
    primitives$C(0.46, 0.62, 0.041, p)
    if (!small) {
      primitives$A(0.46, 0.62, 0.1, 10, 135, l, 3)
      primitives$B(0.65, 0.735, 0.045, 0.021, p, 0.008)
    }
  } else if (name == "hyperannotatR") {
    primitives$slab(0.48, 0.34, 0.76, 0.37, 0.05, d)
    primitives$slab(0.48, 0.49, 0.76, 0.37, 0.045, l, TRUE)
    primitives$P(
      c(0.28, 0.43, 0.7, 0.58, 0.32),
      c(0.5, 0.65, 0.54, 0.38, 0.4),
      a
    )
    primitives$L(
      c(0.28, 0.43, 0.7, 0.58, 0.32, 0.28),
      c(0.5, 0.65, 0.54, 0.38, 0.4, 0.5),
      p, 3
    )
    for (i in 1:5) primitives$C(
      c(0.28, 0.43, 0.7, 0.58, 0.32)[i],
      c(0.5, 0.65, 0.54, 0.38, 0.4)[i],
      0.025, p
    )
    # An angled stylus points at the selected
    # polygon.
    primitives$P(
      c(0.65, 0.74, 0.88, 0.79),
      c(0.59, 0.55, 0.86, 0.9),
      p, shadow = TRUE
    )
    primitives$P(
      c(0.65, 0.74, 0.63),
      c(0.59, 0.55, 0.5),
      a
    )
    primitives$P(
      c(0.79, 0.88, 0.9, 0.81),
      c(0.9, 0.86, 0.9, 0.94),
      a
    )
  } else if (name == "hypermodR") {
    primitives$slab(0.39, 0.63, 0.5, 0.25, 0.05, a)
    primitives$slab(0.39, 0.76, 0.5, 0.25, 0.06, p, TRUE)
    primitives$L(
      c(0.39, 0.39, 0.73, 0.73),
      c(0.48, 0.4, 0.4, 0.29),
      l, 4
    )
    primitives$L(
      c(0.19, 0.19, 0.39),
      c(0.28, 0.4, 0.4),
      l, 4
    )
    primitives$L(
      c(0.46, 0.46),
      c(0.4, 0.25),
      l, 4
    )
    for (i in 1:3) primitives$H(
      c(0.19, 0.46, 0.73)[i],
      0.22, 0.095, if (i == 2)
        p else a, TRUE
    )
    primitives$B(
      0.73, 0.59, 0.25, 0.25, d, 0.035, col = e,
      shadow = TRUE
    )
    primitives$Q(
      c(0.64, 0.69, 0.76, 0.83),
      c(0.52, 0.52, 0.71, 0.64),
      p, 4
    )
  } else if (name == "hyperspectaculR") {
    # Folded spectral pinwheel: repeated bands create
    # an art-focused silhouette.
    for (i in 0:11) {
      t <- i * pi / 6
      r1 <- 0.14
      r2 <- 0.38
      xx <- c(
        r1 * base::cos(t),
        r2 * base::cos(t + 0.12),
        r2 * 0.78 * base::cos(t + 0.5),
        r1 * base::cos(t + 0.52)
      ) +
        0.5
      yy <- c(
        r1 * base::sin(t),
        r2 * base::sin(t + 0.12),
        r2 * 0.78 * base::sin(t + 0.5),
        r1 * base::sin(t + 0.52)
      ) +
        0.52
      primitives$P(
        xx, yy, c(a, l, p, d)[i %% 4 +
                                1], shadow = TRUE
      )
    }
    primitives$H(0.5, 0.52, 0.145, p, TRUE)
    primitives$H(0.5, 0.52, 0.077, a)
  } else if (name == "libscanR") {
    primitives$slab(0.48, 0.25, 0.75, 0.3, 0.065, a, TRUE)
    primitives$P(
      c(0.44, 0.52, 0.54, 0.42),
      c(0.86, 0.86, 0.42, 0.42),
      cttir_alpha(l, 0.3)
    )
    primitives$B(0.48, 0.85, 0.24, 0.12, p, 0.025, shadow = TRUE)
    primitives$B(0.48, 0.78, 0.09, 0.05, a, 0.01)
    primitives$L(
      c(0.48, 0.48),
      c(0.76, 0.38),
      p, 5
    )
    primitives$C(0.48, 0.36, 0.045, p)
    for (i in 0:4) primitives$L(
      c(0.53, 0.68 + i * 0.035),
      c(0.4, 0.49 + i * 0.065),
      if (i %% 2)
        p else a, 3
    )
    if (!small)
      for (i in 0:3) primitives$B(
        0.74 + i * 0.045, 0.74 + i * 0.025, 0.021,
        0.1 + i * 0.025, l, 0.008
      )
  } else if (name == "tivis.r") {
    primitives$slab(0.4, 0.42, 0.68, 0.4, 0.065, l)
    primitives$cell(0.31, 0.47, 0.075, a, 1)
    primitives$cell(0.48, 0.45, 0.064, d, 2)
    primitives$cell(0.42, 0.57, 0.054, a, 3)
    primitives$B(0.72, 0.68, 0.29, 0.34, p, 0.03, shadow = TRUE)
    primitives$L(
      c(0.62, 0.62, 0.82),
      c(0.79, 0.55, 0.55),
      d, 2
    )
    primitives$Q(
      c(0.63, 0.66, 0.69, 0.72),
      c(0.57, 0.57, 0.91, 0.59),
      a, 4
    )
    primitives$Q(
      c(0.72, 0.76, 0.77, 0.81),
      c(0.59, 0.83, 0.65, 0.62),
      a, 4
    )
    primitives$L(
      c(0.51, 0.59),
      c(0.52, 0.57),
      p, 3, arrow = TRUE
    )

    # SPATIAL BIOLOGY: tissue, segmentation, assay
    # and flow each have a new mark.
  } else if (name == "phenoscapR") {
    xy <- base::rbind(
      c(0.28, 0.68),
      c(0.53, 0.76),
      c(0.75, 0.61),
      c(0.69, 0.32),
      c(0.4, 0.26),
      c(0.22, 0.43)
    )
    primitives$L(
      c(xy[, 1], xy[1, 1]),
      c(xy[, 2], xy[1, 2]),
      d, 9
    )
    for (i in 1:6) {
      primitives$L(
        c(0.48, xy[i, 1]),
        c(0.51, xy[i, 2]),
        l, 2
      )
      primitives$cell(
        xy[i, 1], xy[i, 2], 0.105, c(a, l, a, d, l, a)[i],
        i
      )
    }
    primitives$cell(0.48, 0.51, 0.14, p, 2, FALSE)
    primitives$C(0.48, 0.51, 0.058, a)
    primitives$C(0.467, 0.527, 0.021, l)
    if (!small) {
      primitives$C(0.18, 0.71, 0.018, p)
      primitives$C(0.78, 0.38, 0.021, l)
      primitives$C(0.59, 0.18, 0.014, p)
    }
  } else if (name == "segmantR") {
    primitives$cell(0.38, 0.57, 0.245, a, 1)
    primitives$cell(0.65, 0.43, 0.225, l, 2)
    primitives$P(
      c(0.44, 0.51, 0.46, 0.57, 0.52, 0.6),
      c(0.84, 0.68, 0.55, 0.44, 0.31, 0.18),
      s$face
    )
    primitives$L(
      c(0.49, 0.55, 0.5, 0.6, 0.56),
      c(0.84, 0.68, 0.55, 0.42, 0.19),
      p, 4
    )
    if (!small) {
      primitives$L(
        c(0.17, 0.17, 0.26),
        c(0.69, 0.81, 0.81),
        p, 3
      )
      primitives$L(
        c(0.79, 0.86, 0.86),
        c(0.22, 0.22, 0.33),
        p, 3
      )
      for (i in 0:3) primitives$C(
        0.58 + i * 0.07, 0.68 - i * 0.035, 0.014,
        p
      )
    }
  } else if (name == "cellspecR") {
    primitives$cell(0.34, 0.56, 0.235, a, 2)
    primitives$C(0.32, 0.58, 0.078, d)
    primitives$C(0.3, 0.61, 0.028, p)
    primitives$B(0.71, 0.47, 0.32, 0.47, p, 0.035, shadow = TRUE)
    for (i in 0:2) {
      for (j in 0:3) {
        primitives$B(
          0.615 + i * 0.096, 0.31 + j * 0.105,
          0.067, 0.075, c(l, a, d, p)[(i +
                                         2 * j) %% 4 + 1], 0.012
        )
      }
    }
    primitives$L(
      c(0.5, 0.56),
      c(0.57, 0.57),
      p, 3
    )
    if (!small) {
      primitives$A(0.34, 0.56, 0.28, 75, 180, l, 2)
      primitives$C(0.34, 0.84, 0.022, p)
    }
  } else if (name == "gatekeepR") {
    # A genuine cytometry-like gate and a separate,
    # selected output cluster.
    primitives$L(
      c(0.14, 0.14, 0.58),
      c(0.85, 0.24, 0.24),
      l, 3
    )
    xy <- base::rbind(
      c(0.24, 0.4),
      c(0.29, 0.47),
      c(0.22, 0.61),
      c(0.32, 0.68),
      c(0.39, 0.59),
      c(0.38, 0.46),
      c(0.47, 0.71)
    )
    for (i in 1:7) primitives$C(
      xy[i, 1], xy[i, 2], 0.026, if (i %% 2)
        a else l
    )
    primitives$L(
      c(0.28, 0.43, 0.5, 0.42, 0.28),
      c(0.53, 0.78, 0.62, 0.45, 0.53),
      p, 4
    )
    primitives$L(
      c(0.52, 0.69),
      c(0.57, 0.57),
      p, 4, arrow = TRUE
    )
    primitives$cell(0.78, 0.67, 0.082, a, 1)
    primitives$cell(0.76, 0.43, 0.078, l, 3)
    primitives$cell(0.87, 0.54, 0.06, a, 2)
  } else if (name == "cellreportR") {
    # A microscope slide combines a cellular specimen
    # with an analytical trace.
    primitives$B(0.52, 0.51, 0.67, 0.51, p, 0.04, shadow = TRUE)
    primitives$B(0.26, 0.51, 0.15, 0.51, l, 0.025)
    primitives$cell(0.47, 0.6, 0.118, a, 2)
    primitives$cell(0.68, 0.59, 0.073, d, 1)
    primitives$L(
      c(0.4, 0.73),
      c(0.31, 0.31),
      d, 2
    )
    for (i in 0:3) {
      primitives$B(
        0.43 + i * 0.087, 0.35 + c(0.04, 0.08, 0.11, 0.06)[i + 1],
        0.047, c(0.08, 0.16, 0.22, 0.12)[i + 1], a, 0.009
      )
    }
    if (!small) {
      primitives$L(
        c(0.22, 0.29),
        c(0.63, 0.63),
        d, 2
      )
      primitives$L(
        c(0.22, 0.29),
        c(0.56, 0.56),
        d, 2
      )
    }
  } else if (name == "bloodspottR") {
    # Histology burden: cellular blood spots within a
    # calibrated specimen frame.
    primitives$B(0.5, 0.5, 0.72, 0.56, l, 0.04, shadow = TRUE)
    for (i in 1:5) {
      primitives$disc(
        c(0.3, 0.52, 0.72, 0.4, 0.66)[i],
        c(0.65, 0.65, 0.57, 0.4, 0.35)[i],
        c(0.085, 0.11, 0.065, 0.095, 0.085)[i],
        a
      )
    }
    if (!small)
      for (i in 0:4) primitives$L(
        c(0.22 + i * 0.055, 0.22 + i * 0.055),
        c(0.24, 0.26),
        d, 2
      )
    primitives$L(
      c(0.15, 0.15, 0.25),
      c(0.76, 0.84, 0.84),
      p, 3
    )
    primitives$L(
      c(0.75, 0.85, 0.85),
      c(0.17, 0.17, 0.25),
      p, 3
    )
  } else if (name == "qupflowR") {
    primitives$slab(0.28, 0.42, 0.33, 0.2, 0.035, l)
    primitives$cell(0.28, 0.43, 0.055, a, 1)
    primitives$slab(0.74, 0.42, 0.33, 0.2, 0.035, p)
    primitives$P(
      c(0.68, 0.74, 0.8, 0.74),
      c(0.42, 0.46, 0.42, 0.38),
      a
    )
    primitives$Q(
      c(0.29, 0.37, 0.67, 0.73),
      c(0.63, 0.91, 0.91, 0.63),
      p, 6
    )
    primitives$P(
      c(0.68, 0.77, 0.79),
      c(0.65, 0.6, 0.7),
      p
    )
    primitives$Q(
      c(0.72, 0.63, 0.37, 0.28),
      c(0.28, 0.03, 0.03, 0.28),
      a, 6
    )
    primitives$P(
      c(0.22, 0.25, 0.33),
      c(0.24, 0.33, 0.26),
      a
    )
    primitives$B(0.51, 0.47, 0.15, 0.14, a, 0.035, shadow = TRUE)
    primitives$check(0.51, 0.48, 0.08, p, 3)
  } else if (name == "mssrscopR") {
    # Separated optical peaks replace the previous
    # generic chart-window symbol.
    primitives$L(
      c(0.13, 0.13, 0.87),
      c(0.77, 0.26, 0.26),
      l, 2
    )
    x <- base::seq(0.15, 0.85, length.out = 150)
    broad <- 0.27 + 0.32 * base::exp(-((x - 0.5) / 0.23)^2)
    primitives$P(
      c(x, base::rev(x)),
      c(broad, base::rep(0.27, 150)),
      cttir_alpha(l, 0.4)
    )
    sharp <- 0.27 + 0.47 * base::exp(-((x - 0.36) / 0.065)^2) +
      0.47 * base::exp(-((x - 0.65) / 0.065)^2)
    primitives$P(
      c(x, base::rev(x)),
      c(sharp, base::rep(0.27, 150)),
      a
    )
    primitives$L(x, sharp, p, 3)
    if (!small) {
      primitives$C(0.36, 0.78, 0.026, p)
      primitives$C(0.65, 0.78, 0.026, p)
      primitives$L(
        c(0.42, 0.59),
        c(0.83, 0.83),
        l, 2
      )
    }

    # MOLECULAR DATA: transcript strands and a
    # multiplex assay plate.
  } else if (name == "bambamR") {
    for (i in 0:3) {
      yy <- 0.29 + i * 0.145
      primitives$Q(
        c(0.2, 0.36, 0.61, 0.8),
        c(
          yy + 0.08, yy + 0.19, yy - 0.1, yy +
            0.03
        ),
        if (i %% 2)
          p else a, 9
      )
      if (!small)
        for (t in c(0.3, 0.42, 0.56, 0.7)) primitives$B(
          t, yy + 0.05, 0.055, 0.058, if (i %% 2)
            l else p, 0.012
        )
    }
    primitives$L(
      c(0.17, 0.17),
      c(0.22, 0.86),
      l, 3
    )
  } else if (name == "qviewparsR") {
    primitives$B(0.5, 0.51, 0.79, 0.59, d, 0.055, shadow = TRUE)
    primitives$B(0.5, 0.55, 0.77, 0.59, l, 0.055)
    for (i in 0:4) {
      for (j in 0:3) {
        x <- 0.215 + i * 0.143
        y <- 0.34 + j * 0.141
        primitives$C(x, y, 0.052, d)
        primitives$C(
          x, y + 0.009, 0.042, if ((i + j) %% 3 ==
                                     0)
            p else a
        )
        if (!small)
          primitives$C(x - 0.012, y + 0.023, 0.011, p)
      }
    }
    primitives$L(
      c(0.18, 0.82),
      c(0.21, 0.21),
      p, 2
    )

    # CLINICAL SCIENCE: pathology, plantar pressure,
    # cord imaging and audit.
  } else if (name == "molpathR") {
    for (i in 0:1) {
      t <- base::seq(0, 1, length.out = 100)
      x <- 0.3 + 0.115 * base::sin(t * 2 * pi + i * pi)
      primitives$L(
        x, 0.21 + 0.63 * t, if (i == 0)
          p else a, 7
      )
    }
    if (!small) {
      for (t in base::seq(0.08, 0.94, length.out = 7)) {
        primitives$L(
          0.3 + c(-1, 1) *
            0.115 * base::sin(t * 2 * pi),
          base::rep(0.21 + 0.63 * t, 2),
          l, 3
        )
      }
    }
    primitives$L(
      c(0.42, 0.62, 0.78),
      c(0.51, 0.65, 0.51),
      p, 3
    )
    primitives$L(
      c(0.62, 0.62),
      c(0.65, 0.82),
      p, 3
    )
    primitives$L(
      c(0.62, 0.73),
      c(0.65, 0.3),
      p, 3
    )
    for (i in 1:4) primitives$node(
      c(0.62, 0.78, 0.62, 0.73)[i],
      c(0.65, 0.51, 0.82, 0.3)[i],
      c(0.08, 0.065, 0.055, 0.07)[i],
      c(a, l, p, a)[i]
    )
  } else if (name == "pressR") {
    # Rounded insole with concentric pressure
    # islands, never red/green coding.
    t <- base::seq(0, 2 * pi, length.out = 180)
    x <- 0.47 + 0.2 * base::cos(t) *
      (1 + 0.16 * base::sin(t))
    y <- 0.49 + 0.34 * base::sin(t)
    primitives$P(x, y, l, shadow = TRUE)
    primitives$C(0.45, 0.32, 0.105, a)
    primitives$C(0.45, 0.32, 0.061, d)
    primitives$C(0.45, 0.32, 0.027, p)
    primitives$C(0.48, 0.68, 0.137, a)
    primitives$C(0.48, 0.68, 0.088, d)
    primitives$C(0.48, 0.68, 0.042, p)
    primitives$Q(
      c(0.45, 0.64, 0.33, 0.49),
      c(0.29, 0.44, 0.54, 0.7),
      p, 3
    )
    for (i in 0:3) primitives$C(
      0.66 + i * 0.037, 0.76 - i * 0.06, 0.027 -
        i * 0.003, a
    )
    if (!small)
      primitives$L(
        c(0.73, 0.8),
        c(0.3, 0.3),
        p, 3
      )
  } else if (name == "scimagR") {
    primitives$B(0.5, 0.51, 0.65, 0.68, d, 0.045, shadow = TRUE)
    primitives$B(0.5, 0.53, 0.62, 0.68, l, 0.045)
    # Repeated vertebral volumes surround a
    # continuous spinal cord.
    for (i in 0:4) {
      xx <- 0.49 + 0.025 * base::sin(i)
      yy <- 0.29 + i * 0.116
      primitives$B(xx, yy, 0.22, 0.082, p, 0.028, shadow = TRUE)
      primitives$P(
        xx + c(-0.11, -0.18, -0.11),
        yy + c(0.025, 0, -0.025),
        a
      )
      primitives$P(
        xx + c(0.11, 0.18, 0.11),
        yy + c(0.025, 0, -0.025),
        a
      )
    }
    primitives$Q(
      c(0.5, 0.58, 0.42, 0.52),
      c(0.24, 0.4, 0.67, 0.81),
      a, 5
    )
    primitives$C(0.5, 0.53, 0.045, d)
    primitives$C(0.5, 0.53, 0.021, p)
  } else if (name == "zhncommandR") {
    primitives$B(0.5, 0.55, 0.76, 0.56, p, 0.045, shadow = TRUE)
    primitives$B(0.5, 0.78, 0.76, 0.1, l, 0.03)
    primitives$disc(0.29, 0.58, 0.105, a)
    primitives$C(0.29, 0.59, 0.048, p)
    for (i in 0:2) primitives$B(
      0.5 + i * 0.105, 0.59, 0.069, 0.18 * c(0.6, 1, 0.8)[i +
                                                            1], a, 0.012
    )
    primitives$L(
      c(0.22, 0.41, 0.41, 0.55, 0.55, 0.74),
      c(0.39, 0.39, 0.35, 0.35, 0.31, 0.31),
      d, 4
    )
    primitives$B(0.72, 0.24, 0.24, 0.18, a, 0.04, shadow = TRUE)
    primitives$check(0.72, 0.25, 0.12, p, 5)

    # METHODS: geometric inference and distinct
    # evolving representation motifs.
  } else if (name == "inferguidR") {
    primitives$A(0.49, 0.51, 0.31, 0, 360, l, 4)
    primitives$A(0.49, 0.51, 0.255, 210, 350, a, 9)
    for (i in 0:7) {
      t <- i * pi / 4
      primitives$L(
        0.49 + c(0.325, 0.355) *
          base::cos(t),
        0.51 + c(0.325, 0.355) *
          base::sin(t),
        p, 2
      )
    }
    primitives$P(
      c(0.29, 0.53, 0.71, 0.47),
      c(0.26, 0.47, 0.79, 0.55),
      a, shadow = TRUE
    )
    primitives$P(
      c(0.47, 0.53, 0.71),
      c(0.55, 0.47, 0.79),
      p
    )
    primitives$C(0.49, 0.51, 0.045, d)
    primitives$C(0.49, 0.51, 0.021, p)
  } else if (name == "songR") {
    # A continuous folded neighbourhood lattice; the
    # negative spaces remain clear.
    for (j in 0:3) {
      x <- base::seq(0.18, 0.83, length.out = 70)
      y <- 0.29 + j * 0.12 + 0.15 * base::sin((x - 0.18) * 2 * pi)
      primitives$L(
        x, y, if (j %% 2)
          l else a, 2.5
      )
    }
    for (i in 0:5) {
      x <- 0.18 + i * 0.13
      yy <- 0.29 + 0.15 * base::sin((x - 0.18) * 2 * pi)
      primitives$L(
        base::rep(x, 2),
        c(yy, yy + 0.36),
        l, 2
      )
      for (j in 0:3) primitives$node(
        x, yy + j * 0.12, if (i %% 2)
          0.025 else 0.032, if (j %% 2)
          p else a
      )
    }
  } else if (name == "sorenR") {
    xy <- base::rbind(
      c(0.19, 0.54),
      c(0.34, 0.76),
      c(0.45, 0.47),
      c(0.35, 0.25),
      c(0.65, 0.68),
      c(0.78, 0.42),
      c(0.63, 0.23)
    )
    ed <- base::rbind(
      c(1, 2),
      c(1, 3),
      c(1, 4),
      c(2, 3),
      c(2, 5),
      c(3, 4),
      c(3, 5),
      c(3, 6),
      c(4, 7),
      c(5, 6),
      c(6, 7)
    )
    for (i in base::seq_len(base::nrow(ed))) {
      primitives$L(xy[ed[i, ], 1], xy[ed[i, ], 2], l, 2.5)
    }
    for (i in 1:7) primitives$node(
      xy[i, 1], xy[i, 2], if (i == 3)
        0.085 else 0.054, if (i %% 2)
        a else p
    )
    primitives$A(0.73, 0.76, 0.14, 10, 230, p, 2.5)
    primitives$C(0.85, 0.84, 0.043, p)
    primitives$L(
      c(0.76, 0.83),
      c(0.72, 0.8),
      a, 3
    )

    # SIMULATION: loops, tabletop terrain and
    # resilient systems.
  } else if (name == "dynasimR") {
    primitives$A(0.5, 0.51, 0.3, 30, 145, p, 5)
    primitives$A(0.5, 0.51, 0.3, 155, 270, a, 5)
    primitives$A(0.5, 0.51, 0.3, 280, 395, l, 5)
    for (i in 0:2) {
      t <- (90 + i * 120) * pi / 180
      primitives$H(
        0.5 + 0.3 * base::cos(t),
        0.51 + 0.3 * base::sin(t),
        0.1, c(a, p, l)[i +
                          1], TRUE
      )
    }
    x <- base::seq(0.3, 0.7, length.out = 100)
    primitives$L(
      x, 0.51 + 0.085 * base::sin((x - 0.3) * 5 * pi),
      p, 4
    )
    if (!small)
      primitives$L(
        c(0.3, 0.7),
        c(0.39, 0.39),
        a, 2
      )
  } else if (name == "sandtablR") {
    primitives$slab(0.5, 0.43, 0.82, 0.43, 0.08, l, TRUE)
    primitives$L(
      c(0.22, 0.45, 0.51, 0.76),
      c(0.43, 0.54, 0.41, 0.51),
      d, 4
    )
    primitives$H(0.22, 0.44, 0.063, a, TRUE)
    primitives$B(0.51, 0.44, 0.105, 0.105, p, 0.015, shadow = TRUE)
    primitives$P(
      c(0.7, 0.76, 0.82),
      c(0.51, 0.63, 0.51),
      a, shadow = TRUE
    )
    primitives$B(0.46, 0.72, 0.15, 0.15, p, 0.03, shadow = TRUE)
    primitives$plus(0.46, 0.72, 0.09, d, 3)
    primitives$L(
      c(0.46, 0.46),
      c(0.65, 0.55),
      p, 3
    )
  } else if (name == "shieldR") {
    primitives$P(
      c(
        0.18, 0.5, 0.82, 0.76, 0.65, 0.5, 0.35,
        0.24
      ),
      c(
        0.79, 0.89, 0.79, 0.44, 0.28, 0.17, 0.28,
        0.44
      ),
      l, shadow = TRUE
    )
    primitives$P(
      c(0.5, 0.78, 0.72, 0.61, 0.5),
      c(0.85, 0.76, 0.44, 0.29, 0.21),
      a
    )
    primitives$L(
      c(0.33, 0.49, 0.67),
      c(0.62, 0.38, 0.6),
      d, 4
    )
    primitives$L(
      c(0.33, 0.67),
      c(0.62, 0.6),
      d, 4
    )
    for (i in 1:3) primitives$node(
      c(0.33, 0.49, 0.67)[i],
      c(0.62, 0.38, 0.6)[i],
      0.065, p
    )
    if (!small)
      primitives$L(
        c(0.27, 0.5, 0.73),
        c(0.74, 0.81, 0.74),
        p, 2
      )

    # EVIDENCE: consensus, idea exchange, monitoring
    # and citation topology.
  } else if (name == "brainwritR") {
    primitives$A(0.5, 0.5, 0.29, 25, 330, a, 4)
    for (i in 0:2) {
      t <- (90 + i * 120) * pi / 180
      x <- 0.5 + 0.29 * base::cos(t)
      y <- 0.5 + 0.29 * base::sin(t)
      primitives$B(
        x, y, 0.24, 0.17, c(p, l, a)[i +
                                       1], 0.04, shadow = TRUE
      )
      primitives$P(
        x + c(-0.065, -0.04, 0.015),
        y + c(-0.06, -0.13, -0.06),
        c(p, l, a)[i +
                     1]
      )
      if (!small) {
        primitives$L(
          x + c(-0.06, 0.06),
          base::rep(y + 0.025, 2),
          d, 2
        )
        primitives$L(
          x + c(-0.06, 0.02),
          base::rep(y - 0.025, 2),
          d, 2
        )
      }
    }
    # A central pencil makes creation, rather than
    # collection, the visual verb.
    primitives$P(
      c(0.44, 0.5, 0.62, 0.56),
      c(0.37, 0.34, 0.62, 0.65),
      p, shadow = TRUE
    )
    primitives$P(
      c(0.44, 0.5, 0.41),
      c(0.37, 0.34, 0.29),
      a
    )
  } else if (name %in% c("delphyr", "delphyrApp")) {
    if (name == "delphyrApp") {
      primitives$B(
        0.5, 0.51, 0.79, 0.67, d, 0.05, col = e,
        shadow = TRUE
      )
      primitives$B(0.5, 0.81, 0.79, 0.1, l, 0.035)
      for (x in c(0.19, 0.25, 0.31)) primitives$C(x, 0.81, 0.013, d)
      grid::pushViewport(grid::viewport(0.5, 0.49, 0.81, 0.81))
    }
    for (i in 0:4) {
      t <- (90 + i * 72) * pi / 180
      x <- 0.5 + 0.3 * base::cos(t)
      y <- 0.5 + 0.3 * base::sin(t)
      primitives$L(
        c(0.5, x),
        c(0.5, y),
        l, 4
      )
      primitives$B(
        x, y, 0.15, 0.12, if (i %% 2)
          p else a, 0.035, shadow = TRUE
      )
      if (!small)
        primitives$L(
          x + c(-0.035, 0.035),
          base::rep(y, 2),
          d, 2
        )
    }
    primitives$H(0.5, 0.5, 0.15, p, TRUE)
    primitives$check(0.5, 0.51, 0.16, d, 6)
    if (name == "delphyrApp")
      grid::popViewport()
  } else if (name == "researchR") {
    primitives$C(0.48, 0.5, 0.29, d)
    primitives$A(0.48, 0.5, 0.29, 0, 360, l, 2)
    primitives$A(0.48, 0.5, 0.19, 0, 360, l, 1.7)
    primitives$P(
      c(0.48, 0.48, 0.75),
      c(0.5, 0.79, 0.61),
      cttir_alpha(a, 0.45)
    )
    primitives$L(
      c(0.48, 0.75),
      c(0.5, 0.61),
      p, 4
    )
    for (i in 1:3) primitives$C(
      c(0.33, 0.62, 0.57)[i],
      c(0.56, 0.65, 0.34)[i],
      0.03, p
    )
    primitives$B(0.25, 0.26, 0.23, 0.2, p, 0.025, shadow = TRUE)
    primitives$L(
      c(0.19, 0.31),
      c(0.29, 0.29),
      d, 3
    )
    primitives$L(
      c(0.19, 0.27),
      c(0.24, 0.24),
      a, 3
    )
    if (!small)
      primitives$L(
        c(0.48, 0.48),
        c(0.16, 0.22),
        p, 2
      )
  } else if (name == "scimapR") {
    xy <- base::rbind(
      c(0.25, 0.7),
      c(0.5, 0.8),
      c(0.74, 0.65),
      c(0.32, 0.43),
      c(0.62, 0.39),
      c(0.47, 0.2)
    )
    ed <- base::rbind(
      c(1, 2),
      c(2, 3),
      c(1, 4),
      c(2, 4),
      c(3, 5),
      c(4, 5),
      c(4, 6),
      c(5, 6),
      c(2, 5)
    )
    for (i in 1:9) primitives$L(xy[ed[i, ], 1], xy[ed[i, ], 2], a, 3)
    for (i in 1:6) {
      primitives$H(
        xy[i, 1], xy[i, 2], if (i == 4)
          0.105 else 0.075, if (i %% 2)
          p else l, TRUE
      )
      if (!small)
        primitives$L(
          xy[i, 1] + c(-0.025, 0.025),
          base::rep(xy[i, 2], 2),
          d, 2
        )
    }

    # RESEARCH INFRASTRUCTURE: a recognisable
    # material-object vocabulary.
  } else if (name == "cttiR") {
    primitives$slab(0.56, 0.41, 0.62, 0.32, 0.14, a, TRUE)
    primitives$slab(0.56, 0.68, 0.62, 0.32, 0.06, p, TRUE)
    primitives$L(
      c(0.25, 0.13, 0.13, 0.25),
      c(0.59, 0.59, 0.31, 0.31),
      l, 3
    )
    for (y in c(0.31, 0.45, 0.59)) primitives$H(0.13, y, 0.043, p)
    primitives$P(
      c(0.56, 0.87, 0.87, 0.56),
      c(0.52, 0.68, 0.62, 0.46),
      a
    )
    if (!small) {
      primitives$L(
        c(0.62, 0.79),
        c(0.56, 0.64),
        l, 2
      )
      primitives$L(
        c(0.62, 0.74),
        c(0.51, 0.57),
        l, 2
      )
    }
  } else if (name == "harbouR") {
    # Stacked containers over a sheltered quay, with
    # an anchor stem.
    primitives$slab(0.5, 0.28, 0.79, 0.33, 0.06, d)
    for (i in 0:2) {
      primitives$B(
        0.5, 0.38 + i * 0.145, 0.55, 0.105,
        c(a, l, p)[i + 1], 0.025, shadow = TRUE
      )
      if (!small)
        for (x in c(0.29, 0.35, 0.41, 0.47)) primitives$L(
          base::rep(x, 2),
          0.38 + i * 0.145 + c(-0.025, 0.025),
          d, 1.5
        )
    }
    primitives$C(0.5, 0.86, 0.045, p)
    primitives$C(0.5, 0.86, 0.02, d)
    primitives$L(
      c(0.5, 0.5),
      c(0.81, 0.75),
      p, 3
    )
    primitives$L(
      c(0.17, 0.17, 0.5, 0.83, 0.83),
      c(0.38, 0.25, 0.16, 0.25, 0.38),
      p, 4
    )
  } else if (name == "hexmakR") {
    primitives$H(0.43, 0.52, 0.3, a, TRUE)
    primitives$H(0.43, 0.52, 0.235, l)
    primitives$H(0.43, 0.52, 0.165, p)
    primitives$P(
      c(0.53, 0.64, 0.87, 0.76),
      c(0.28, 0.23, 0.72, 0.78),
      a, shadow = TRUE
    )
    primitives$P(
      c(0.53, 0.64, 0.49),
      c(0.28, 0.23, 0.15),
      p
    )
    primitives$P(
      c(0.76, 0.87, 0.9, 0.79),
      c(0.78, 0.72, 0.79, 0.85),
      p
    )
    if (!small)
      primitives$L(
        c(0.61, 0.81),
        c(0.34, 0.75),
        l, 2
      )
  } else if (name == "reflowR") {
    primitives$slab(0.48, 0.38, 0.6, 0.27, 0.06, a)
    primitives$slab(0.48, 0.54, 0.6, 0.27, 0.06, p)
    primitives$A(0.48, 0.51, 0.36, 35, 155, p, 5)
    primitives$A(0.48, 0.51, 0.36, 215, 335, a, 5)
    primitives$P(
      c(0.15, 0.11, 0.23),
      c(0.68, 0.58, 0.61),
      p
    )
    primitives$P(
      c(0.81, 0.86, 0.74),
      c(0.34, 0.44, 0.42),
      a
    )
    if (!small) {
      primitives$L(
        c(0.4, 0.54),
        c(0.57, 0.64),
        d, 2
      )
      primitives$L(
        c(0.46, 0.6),
        c(0.54, 0.61),
        d, 2
      )
    }
  } else if (name == "themakR") {
    # Three fanned swatch cards, with a strong
    # typographic layout on the top.
    primitives$P(
      c(0.15, 0.33, 0.64, 0.46),
      c(0.34, 0.81, 0.69, 0.22),
      d, shadow = TRUE
    )
    primitives$P(
      c(0.3, 0.43, 0.77, 0.64),
      c(0.25, 0.8, 0.72, 0.17),
      a, shadow = TRUE
    )
    primitives$B(0.61, 0.52, 0.34, 0.61, p, 0.035, shadow = TRUE)
    primitives$B(0.61, 0.71, 0.23, 0.13, l, 0.012)
    primitives$L(
      c(0.53, 0.69),
      c(0.58, 0.58),
      d, 5
    )
    primitives$L(
      c(0.53, 0.65),
      c(0.52, 0.52),
      d, 3
    )
    for (i in 0:2) primitives$B(
      0.54 + i * 0.07, 0.37, 0.045, 0.07, c(d, a, l)[i +
                                                       1], 0.009
    )
  } else if (name == "shinylaunchR") {
    # A launcher arrow becomes a dimensional rocket
    # over an app tile.
    primitives$B(
      0.46, 0.45, 0.57, 0.55, d, 0.06, col = e, shadow = TRUE
    )
    for (i in 0:1) for (j in 0:1) primitives$B(
      0.31 + i * 0.16, 0.31 + j * 0.16, 0.105, 0.105,
      a, 0.025
    )
    primitives$P(
      c(0.4, 0.55, 0.83, 0.71),
      c(0.45, 0.72, 0.88, 0.58),
      p, shadow = TRUE
    )
    primitives$P(
      c(0.55, 0.71, 0.83),
      c(0.72, 0.58, 0.88),
      l
    )
    primitives$P(
      c(0.41, 0.52, 0.41, 0.3),
      c(0.49, 0.59, 0.62, 0.49),
      a
    )
    primitives$P(
      c(0.58, 0.68, 0.65, 0.53),
      c(0.43, 0.55, 0.34, 0.29),
      a
    )
    primitives$C(0.65, 0.69, 0.052, d)
    primitives$C(0.65, 0.69, 0.028, a)
    primitives$L(
      c(0.39, 0.26),
      c(0.41, 0.27),
      p, 5
    )

    # COMPANION APPLICATIONS: retain clear links to
    # their actual operations.
  } else if (name == "argus-one") {
    # An observant eye with a central convergence
    # point.
    primitives$Q(
      c(0.13, 0.3, 0.7, 0.87),
      c(0.51, 0.87, 0.87, 0.51),
      p, 6
    )
    primitives$Q(
      c(0.13, 0.3, 0.7, 0.87),
      c(0.51, 0.15, 0.15, 0.51),
      a, 6
    )
    primitives$disc(0.5, 0.51, 0.19, l)
    primitives$C(0.5, 0.52, 0.115, d)
    primitives$C(0.5, 0.52, 0.059, a)
    primitives$C(0.47, 0.56, 0.025, p)
    if (!small)
      for (i in 0:2) primitives$L(
        c(0.32 + i * 0.18, 0.32 + i * 0.18),
        c(0.84, 0.91),
        l, 3
      )
  } else if (name == "attaclone-dedup") {
    primitives$B(0.36, 0.6, 0.37, 0.48, a, 0.035, shadow = TRUE)
    primitives$B(0.6, 0.44, 0.37, 0.48, p, 0.035, shadow = TRUE)
    # Matching attachment loops, followed by a single
    # retained check seal.
    primitives$A(0.36, 0.63, 0.075, 0, 310, l, 4)
    primitives$A(0.6, 0.51, 0.075, 0, 310, d, 4)
    primitives$disc(0.73, 0.27, 0.12, a)
    primitives$check(0.73, 0.28, 0.12, d, 5)
    if (!small) {
      primitives$L(
        c(0.25, 0.43),
        c(0.44, 0.44),
        l, 3
      )
      primitives$L(
        c(0.49, 0.65),
        c(0.34, 0.34),
        a, 3
      )
    }
  } else if (name == "metadata-mender") {
    primitives$B(0.46, 0.52, 0.55, 0.65, p, 0.04, shadow = TRUE)
    for (i in 0:3) {
      yy <- 0.72 - i * 0.13
      primitives$B(0.29, yy, 0.065, 0.065, a, 0.014)
      primitives$L(
        c(0.38, if (i == 2) 0.46 else 0.63),
        base::rep(yy, 2),
        d, 3
      )
    }
    # A missing structured field is restored by an
    # offset repair tile.
    primitives$B(0.7, 0.47, 0.23, 0.23, a, 0.035, shadow = TRUE)
    primitives$plus(0.7, 0.47, 0.13, d, 5)
    if (!small)
      primitives$L(
        c(0.57, 0.6),
        c(0.46, 0.46),
        a, 3, lty = 2
      )
  } else if (name == "pwa-quest") {
    primitives$B(0.47, 0.52, 0.39, 0.7, l, 0.055, shadow = TRUE)
    primitives$B(0.47, 0.53, 0.32, 0.57, p, 0.025)
    primitives$B(0.47, 0.81, 0.14, 0.026, d, 0.012)
    for (i in 0:2) {
      primitives$B(
        0.38, 0.68 - i * 0.13, 0.045, 0.045, a,
        0.01
      )
      primitives$L(
        c(0.44, 0.57),
        base::rep(0.68 - i * 0.13, 2),
        d, 3
      )
    }
    primitives$disc(0.7, 0.32, 0.135, a)
    primitives$check(0.7, 0.33, 0.15, d, 6)
  } else if (name == "CTTIR") {
    # Three faceted scientific domains join to form a
    # single impossible cube.
    primitives$P(
      c(0.5, 0.82, 0.65, 0.5, 0.35, 0.18),
      c(0.88, 0.7, 0.6, 0.69, 0.6, 0.7),
      p, shadow = TRUE
    )
    primitives$P(
      c(0.18, 0.35, 0.35, 0.5, 0.5, 0.18),
      c(0.7, 0.6, 0.4, 0.31, 0.12, 0.3),
      l, shadow = TRUE
    )
    primitives$P(
      c(0.5, 0.5, 0.65, 0.65, 0.82, 0.82),
      c(0.12, 0.31, 0.4, 0.6, 0.7, 0.3),
      a, shadow = TRUE
    )
    primitives$H(0.5, 0.5, 0.145, s$face)
    if (!small) {
      primitives$L(
        c(0.5, 0.78),
        c(0.83, 0.67),
        l, 2
      )
      primitives$L(
        c(0.22, 0.22),
        c(0.64, 0.33),
        p, 2
      )
    }
  } else {
    cttir_branding_abort(paste0("No original motif registered for: ", name))
  }
  base::invisible(NULL)
}
