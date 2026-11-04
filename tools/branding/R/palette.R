cttir_branding_abort <- get("cttir_branding_abort", mode = "function")

# Helpers are supplied by the sibling scripts loaded
# by cttir_hex.R.
cttir_token <- get("cttir_token", mode = "function")
cttir_simulate_cvd <- get("cttir_simulate_cvd", mode = "function")

# Colour supports identity; distinct scientific
# silhouettes remain the main cue.  UI link colours
# stay separate from topic colours.
cttir_accents <- cttir_tokens[
  base::grepl("^topic[.]", base::names(cttir_tokens))
]
base::names(cttir_accents) <- base::sub(
  "^topic[.]", "", base::names(cttir_accents)
)
cttir_family_labels <- c(
  spectral = "Spectral imaging", spatial = "Spatial biology",
  omics = "Molecular data", clinical = "Clinical science",
  methods = "Learning & inference", simulation = "Systems & simulation",
  evidence = "Evidence & discovery", platform = "Research infrastructure",
  brand = "Science, connected"
)
cttir_mix <- function(a, b, t) {
  z <- grDevices::col2rgb(a) *
    t + grDevices::col2rgb(b) *
    (1 - t)
  grDevices::rgb(z[1], z[2], z[3], maxColorValue = 255)
}
cttir_alpha <- function(colour, alpha) {
  grDevices::adjustcolor(colour, alpha.f = alpha)
}
cttir_style <- function(family, mode = "light") {
  if (mode == "primary")
    mode <- "dark"
  if (!family %in% base::names(cttir_accents) ||
        !mode %in% c("light", "dark", "mono")) {
    cttir_branding_abort("Unknown family or colour mode.")
  }
  a <- cttir_accents[[family]]
  material <- if (family == "platform")
    "#8FAABD" else a
  theme <- if (mode == "dark")
    "dark" else "light"
  s <- list(
    accent = a, material = material, face = cttir_token(theme, "page"),
    facet = cttir_token(theme, "page"),
    facet2 = cttir_token(theme, "page"),
    rim = cttir_mix(material, "#FFFFFF", 0.65),
    ink = cttir_token(theme, "heading"),
    muted = cttir_token(theme, "text"),
    paper = "#F4F8FC", light = cttir_mix(material, "#FFFFFF", 0.3),
    mid = cttir_mix(material, "#FFFFFF", 0.78),
    deep = cttir_mix(material, "#142235", 0.36),
    edge = cttir_mix(material, "#FFFFFF", 0.45),
    shadow = "#030A12", mode = mode
  )
  if (mode == "light") {
    s$rim <- cttir_mix(material, "#22364A", 0.45)
    s$edge <- cttir_mix(material, "#14283A", 0.5)
  }
  if (mode == "mono") {
    s$face <- "#FAFAFA"
    s$facet <- s$face
    s$facet2 <- s$face
    s$ink <- "#212121"
    s$muted <- "#424242"
    s$shadow <- "#050505"
    s$rim <- "#5C5C5C"
    s$paper <- "#F8F8F8"
    s$light <- "#DEDEDE"
    s$mid <- "#A7A7A7"
    s$deep <- "#5C5C5C"
    s$edge <- "#444444"
    s$accent <- "#A7A7A7"
    s$material <- "#A7A7A7"
    s$mode <- "light"
  }
  # Group-synchronised tile and opaque border;
  # grayscale stays achromatic.
  if (mode != "mono") {
    s$face <- cttir_mix(
      a, cttir_token(theme, "page"),
      if (mode == "light")
        0.14 else 0.24
    )
    s$facet <- s$face
    s$facet2 <- s$face
    s$rim <- a
  }
  vision <- base::getOption("cttir.vision", "normal")
  if (vision != "normal") {
    for (n in base::setdiff(
      base::names(s),
      "mode"
    )) {
      s[[n]] <- cttir_simulate_cvd(s[[n]], vision)
    }
  }
  s
}
