# Helpers are supplied by the sibling scripts loaded
# by cttir_hex.R.
cttir_alpha <- get("cttir_alpha", mode = "function")
cttir_mix <- get("cttir_mix", mode = "function")

# Shared geometric vocabulary. All coordinates use a
# square local viewport.  Cast shadows use offset
# vector geometry, so SVG/PDF remain entirely vector.
cttir_primitives <- function(s, small = FALSE) {
  polygon <- function(
    x, y, fill = s$mid, col = NA, lwd = 1.5, shadow = FALSE
  ) {
    if (s$mode == "light" && base::is.na(col) &&
          fill %in% c(s$paper, s$light)) {
      col <- s$edge
      lwd <- 0.8
    }
    if (shadow) {
      grid::grid.polygon(
        x + 0.012, y - 0.021, gp = grid::gpar(
          fill = cttir_alpha(s$shadow, 0.25),
          col = NA
        )
      )
    }
    grid::grid.polygon(
      x, y, gp = grid::gpar(
        fill = fill, col = col, lwd = lwd, linejoin = "round"
      )
    )
  }
  line <- function(x, y, col = s$paper, lwd = 3, lty = 1, arrow = FALSE) {
    # White or pastel free-standing strokes need dark
    # ink on a pale badge.
    if (s$mode == "light" && col %in% c(s$paper, s$light))
      col <- s$edge
    grid::grid.lines(
      x, y, arrow = if (arrow)
        grid::arrow(
          length = grid::unit(0.035, "npc"),
          type = "closed"
        ) else NULL, gp = grid::gpar(
        col = col, fill = col, lwd = lwd, lty = lty,
        lineend = "round", linejoin = "round"
      )
    )
  }
  circle <- function(
    x, y, r = 0.05, fill = s$paper, col = NA, lwd = 2,
    shadow = FALSE
  ) {
    if (s$mode == "light" && base::is.na(col) &&
          fill %in% c(s$paper, s$light)) {
      col <- s$edge
      lwd <- 0.8
    }
    if (shadow) {
      grid::grid.circle(
        x + 0.01, y - 0.016, r, gp = grid::gpar(
          fill = cttir_alpha(s$shadow, 0.25),
          col = NA
        )
      )
    }
    grid::grid.circle(
      x, y, r, gp = grid::gpar(fill = fill, col = col, lwd = lwd)
    )
  }
  box <- function(
    x, y, w, h, fill = s$paper, r = 0.025, col = NA,
    lwd = 1.5, shadow = FALSE
  ) {
    if (s$mode == "light" && base::is.na(col) &&
          fill %in% c(s$paper, s$light)) {
      col <- s$edge
      lwd <- 0.8
    }
    if (shadow) {
      grid::grid.roundrect(
        x + 0.012, y - 0.021, w, h, r = grid::unit(r, "npc"),
        gp = grid::gpar(
          fill = cttir_alpha(s$shadow, 0.28),
          col = NA
        )
      )
    }
    grid::grid.roundrect(
      x, y, w, h, r = grid::unit(r, "npc"),
      gp = grid::gpar(fill = fill, col = col, lwd = lwd)
    )
  }
  arc <- function(
    x, y, r, start = 0, end = 360, col = s$paper, lwd = 3
  ) {
    t <- base::seq(start, end, length.out = 80) *
      pi / 180
    line(
      x + r * base::cos(t),
      y + r * base::sin(t),
      col, lwd
    )
  }
  # Cubic Bezier curves, sampled as vector paths with
  # deterministic geometry.
  curve <- function(x, y, col = s$paper, lwd = 3, fill = NULL) {
    t <- base::seq(0, 1, length.out = 70)
    xx <- (1 - t)^3 * x[1] + 3 * (1 - t)^2 * t * x[2] +
      3 * (1 - t) * t^2 * x[3] + t^3 * x[4]
    yy <- (1 - t)^3 * y[1] + 3 * (1 - t)^2 * t * y[2] +
      3 * (1 - t) * t^2 * y[3] + t^3 * y[4]
    if (base::is.null(fill))
      line(xx, yy, col, lwd) else polygon(xx, yy, fill, col, lwd)
  }
  hexagon <- function(
    x, y, r = 0.16, fill = s$mid, shadow = FALSE, col = NA
  ) {
    t <- (90 + 0:5 * 60) * pi / 180
    polygon(
      x + r * base::cos(t),
      y + r * base::sin(t),
      fill, col, shadow = shadow
    )
  }
  # Parallel-sided extruded rhombus, a shared
  # spectral/material plane.
  slab <- function(
    x = 0.5, y = 0.5, w = 0.65, h = 0.31, d = 0.055,
    fill = s$mid, grid = FALSE
  ) {
    polygon(
      x + c(-w / 2, 0, 0, -w / 2),
      y + c(0, -h / 2, -h / 2 - d, -d),
      s$deep
    )
    polygon(
      x + c(0, w / 2, w / 2, 0),
      y + c(-h / 2, 0, -d, -h / 2 - d),
      cttir_mix(fill, s$deep, 0.5)
    )
    polygon(
      x + c(-w / 2, 0, w / 2, 0),
      y + c(0, h / 2, 0, -h / 2),
      fill, shadow = FALSE
    )
    if (grid && !small) {
      for (t in c(0.25, 0.5, 0.75)) {
        line(
          x + c(-w / 2 + t * w / 2, t * w / 2),
          y + c(t * h / 2, -h / 2 + t * h / 2),
          cttir_alpha(s$paper, 0.4),
          1
        )
        line(
          x + c(-t * w / 2, w / 2 - t * w / 2),
          y + c(-h / 2 + t * h / 2, t * h / 2),
          cttir_alpha(s$paper, 0.4),
          1
        )
      }
    }
    line(
      x + c(-w / 2, 0, w / 2),
      y + c(0, h / 2, 0),
      cttir_alpha(s$paper, 0.62),
      1.5
    )
  }
  cell <- function(
    x, y, r = 0.1, fill = s$mid, phase = 0, nucleus = TRUE
  ) {
    t <- base::seq(0, 2 * pi, length.out = 90)
    rr <- r * (1 + 0.055 * base::sin(5 * t + phase) +
                 0.035 * base::cos(3 * t - phase))
    polygon(
      x + rr * base::cos(t),
      y + rr * base::sin(t),
      fill, shadow = TRUE
    )
    if (!small)
      arc(
        x, y, r * 0.83, 25, 145, cttir_alpha(s$paper, 0.6),
        2
      )
    if (nucleus) {
      circle(x - r * 0.08, y + r * 0.04, r * 0.32, s$deep)
      circle(x - r * 0.11, y + r * 0.11, r * 0.13, s$paper)
    }
  }
  node <- function(x, y, r = 0.04, fill = s$mid) {
    circle(x, y, r, fill, shadow = TRUE)
    if (!small)
      circle(x - r * 0.18, y + r * 0.2, r * 0.26, s$paper)
  }
  capsule <- function(x, y, w = 0.22, h = 0.09, fill = s$mid) {
    box(x, y, w, h, fill, r = h / 2, shadow = TRUE)
  }
  # arc faceted circular disc, useful for lenses,
  # wells and embedded controls.
  disc <- function(x, y, r = 0.17, fill = s$mid) {
    circle(x, y, r, s$deep, shadow = TRUE)
    circle(x, y + 0.012, r * 0.91, fill)
    arc(x, y + 0.012, r * 0.78, 28, 150, s$light, 3)
  }
  check <- function(x, y, size = 0.12, col = s$deep, lwd = 6) {
    line(
      x + c(-0.5, -0.12, 0.65) *
        size, y + c(0, -0.4, 0.5) *
        size, col, lwd
    )
  }
  plus <- function(x, y, size = 0.1, col = s$paper, lwd = 5) {
    line(
      x + c(-0.5, 0.5) *
        size, base::rep(y, 2),
      col, lwd
    )
    line(
      base::rep(x, 2),
      y + c(-0.5, 0.5) *
        size, col, lwd
    )
  }
  list(
    P = polygon, L = line, C = circle, B = box, A = arc,
    Q = curve, H = hexagon, slab = slab, cell = cell,
    node = node, capsule = capsule, disc = disc, check = check,
    plus = plus
  )
}
