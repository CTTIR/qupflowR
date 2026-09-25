#' qupflowR: connect R and Shiny to QuPath through a local bridge
#'
#' @description
#' qupflowR is being built as an interface between R and
#' [QuPath](https://qupath.github.io/) 0.7.0. The planned design has three
#' parts that share one R API: a local Java bridge (a QuPath extension for the
#' desktop and a headless worker) reached over an authenticated loopback
#' protocol, a Shiny app for people who do not program, and shareable
#' analysis workflows.
#'
#' @section Current scope:
#' This development version contains only the package foundations:
#' classified error conditions, a message catalogue, protocol JSON helpers and
#' a JSON Schema subset validator with the protocol schemas. The bridge client,
#' project and object functions, the Shiny app and the workflow engine do not
#' exist yet; see `NEWS.md` for what each version adds.
#'
#' Loading the package starts no Java, QuPath, Python or browser process,
#' performs no download and does not load optional packages such as shiny,
#' sf or annotatR. Optional packages are checked where they are used.
#'
#' @section Options:
#' * `qupflowR.language`: language of user-facing messages, `"de"` (default)
#'   or `"en"`. Messages missing in the chosen language fall back to English.
#'
#' @keywords internal
"_PACKAGE"
