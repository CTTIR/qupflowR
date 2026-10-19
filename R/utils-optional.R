# Optional dependencies ------------------------------------------------------
#
# Packages in Suggests (the Shiny app, sf geometry conversion, hyperannotatR
# interoperability, ...) are checked where they are used. Both helpers read
# installed package metadata only and never load a namespace, so a capability
# report does not change the session.

#' Installation status of packages
#'
#' @param pkgs Character vector of package names without `NA`, empty strings
#'   or duplicates.
#' @param versions `NULL`, or a character vector parallel to `pkgs` holding
#'   the minimum version for each package (`NA` means any version).
#' @param call Environment reported as the call of validation errors.
#' @return A tibble with one row per package and the character columns
#'   `package`, `required_version` and `installed_version` (`NA` when not
#'   installed) plus the logical column `available`.
#' @noRd
qp_package_status <- function(pkgs, versions = NULL, call = parent.frame()) {
  qp_check_package_args(pkgs, versions, call)
  if (is.null(versions)) {
    versions <- rep(NA_character_, length(pkgs))
  }
  installed <- vapply(pkgs, qp_installed_version, character(1),
                      USE.NAMES = FALSE)
  installed_numeric <- numeric_version(ifelse(is.na(installed), "0",
                                              installed), strict = FALSE)
  required_numeric <- numeric_version(ifelse(is.na(versions), "0", versions),
                                      strict = FALSE)
  sufficient <- !is.na(installed) &
    (is.na(versions) | installed_numeric >= required_numeric)
  sufficient[is.na(sufficient)] <- FALSE
  tibble::tibble(
    package = pkgs,
    required_version = unname(versions),
    installed_version = installed,
    available = sufficient
  )
}

#' Require optional packages at the point of use
#'
#' @param pkgs,versions As for `qp_package_status()`.
#' @param reason Single non-empty string naming the feature that needs the
#'   packages. It appears in the error message and in the condition details.
#' @param call Environment reported as the call of the condition.
#' @return `pkgs`, invisibly, when every package is installed in a sufficient
#'   version. Otherwise a `qp_capability_error` with code
#'   `CAPABILITY_UNAVAILABLE` is signalled; its `details` hold `reason`,
#'   `missing` (packages that are not installed) and `outdated` (packages
#'   installed in a lower version, as `"pkg (installed < required)"`).
#' @noRd
qp_check_installed <- function(pkgs, reason, versions = NULL,
                               call = parent.frame()) {
  if (!is.character(reason) || length(reason) != 1L || is.na(reason) ||
        !nzchar(trimws(reason))) {
    qp_abort_optional_argument("reason", "expect.string", call)
  }
  status <- qp_package_status(pkgs, versions, call = call)
  if (all(status$available)) {
    return(invisible(pkgs))
  }
  unavailable <- status[!status$available, , drop = FALSE]
  not_installed <- is.na(unavailable$installed_version)
  missing <- unavailable$package[not_installed]
  outdated <- sprintf("%s (%s < %s)", unavailable$package[!not_installed],
                      unavailable$installed_version[!not_installed],
                      unavailable$required_version[!not_installed])
  # Package names and versions are data, so they are appended to the
  # translated labels instead of being interpolated into the templates.
  lines <- c(i = paste(qp_msg("condition.needed_for"), reason))
  if (length(missing) > 0L) {
    lines <- c(lines, x = paste(qp_msg("condition.packages_missing"),
                                paste(missing, collapse = ", ")))
  }
  if (length(outdated) > 0L) {
    lines <- c(lines, x = paste(qp_msg("condition.packages_outdated"),
                                paste(outdated, collapse = ", ")))
  }
  qp_abort(
    "CAPABILITY_UNAVAILABLE",
    message_key = "error.optional_packages_unavailable",
    details = list(reason = reason, missing = missing, outdated = outdated),
    action = "install_packages",
    bullets = lines,
    call = call
  )
}

qp_installed_version <- function(pkg) {
  path <- find.package(pkg, quiet = TRUE)
  if (length(path) == 0L) {
    return(NA_character_)
  }
  # find.package() only returns directories that contain a DESCRIPTION file.
  description <- file.path(path[[1L]], "DESCRIPTION")
  unname(read.dcf(description, fields = "Version")[1L, 1L])
}

qp_check_package_args <- function(pkgs, versions, call) {
  if (!is.character(pkgs) || length(pkgs) == 0L || anyNA(pkgs) ||
        !all(nzchar(pkgs)) || anyDuplicated(pkgs) > 0L) {
    qp_abort_optional_argument("pkgs", "expect.package_names", call)
  }
  if (is.null(versions)) {
    return(invisible(TRUE))
  }
  valid_versions <- is.character(versions) &&
    length(versions) == length(pkgs) &&
    !anyNA(numeric_version(versions[!is.na(versions)], strict = FALSE))
  if (!valid_versions) {
    qp_abort_optional_argument("versions", "expect.version_strings", call)
  }
  invisible(TRUE)
}

qp_abort_optional_argument <- function(arg, expected_key, call) {
  qp_abort(
    "INVALID_ARGUMENT",
    arg = arg,
    expected = qp_msg(expected_key),
    details = list(argument = arg),
    call = call
  )
}
