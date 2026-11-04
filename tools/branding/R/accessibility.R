cttir_branding_abort <- get("cttir_branding_abort", mode = "function")
# Physiologically based CVD simulation after Machado
# et al. (2009), severity 100.  Matrices transcribed
# from the colorspacious reference implementation.
# This transforms sRGB colours before drawing; it
# never edits a raster image.  Simulations are
# screening aids, not proof of universal colour
# discrimination.
cttir_simulate_cvd <- function(colour, type = "deutan") {
  matrices <- list(
    protan = matrix(
      c(
        0.152286, 1.052583, -0.204868, 0.114503,
        0.786281, 0.099216, -0.003882, -0.048116,
        1.051998
      ),
      3, 3, byrow = TRUE
    ),
    deutan = matrix(
      c(
        0.367322, 0.860646, -0.227968, 0.280085,
        0.672501, 0.047413, -0.01182, 0.04294,
        0.968881
      ),
      3, 3, byrow = TRUE
    ),
    tritan = matrix(
      c(
        1.255528, -0.076749, -0.178779, -0.078411,
        0.930809, 0.147602, 0.004733, 0.691367,
        0.3039
      ),
      3, 3, byrow = TRUE
    )
  )
  rgb <- grDevices::col2rgb(colour) / 255
  linear <- base::ifelse(
    rgb <= 0.04045, rgb / 12.92, ((rgb + 0.055) / 1.055)^2.4
  )
  if (type == "gray") {
    luminance <- c(0.2126, 0.7152, 0.0722) %*%
      linear
    result <- matrix(
      base::rep(luminance, each = 3),
      nrow = 3
    )
  } else {
    if (!type %in% base::names(matrices)) {
      cttir_branding_abort("Unknown vision simulation type.")
    }
    result <- matrices[[type]] %*% linear
  }
  result[] <- base::pmin(1, base::pmax(0, result))
  srgb <- base::ifelse(
    result <= 0.0031308, 12.92 * result, 1.055 * result^(1 / 2.4) -
      0.055
  )
  grDevices::rgb(srgb[1, ], srgb[2, ], srgb[3, ])
}
