description_field_packages <- function(field_value) {
  if (is.null(field_value) || is.na(field_value)) {
    return(character())
  }
  entries <- trimws(strsplit(field_value, ",", fixed = TRUE)[[1L]])
  entries <- sub("[[:space:]]*\\(.*\\)$", "", entries)
  entries[nzchar(entries)]
}

test_that("DESCRIPTION carries the identity fixed in the blueprint", {
  desc <- utils::packageDescription("qupflowR")
  expect_identical(desc$Package, "qupflowR")
  expect_identical(desc$Version, "0.0.0.9000")
  expect_identical(desc$License, "GPL (>= 3)")
  # Authors@R is R code by definition; R CMD build evaluates it the same way.
  authors <- eval(parse(text = desc[["Authors@R"]]))
  expect_length(authors, 1L)
  expect_identical(authors$given, "R.")
  expect_identical(authors$family, "Heller")
  expect_identical(authors$email,
                   "58561665+r-heller@users.noreply.github.com")
  expect_setequal(authors$role, c("aut", "cre", "cph"))
})

test_that("optional app, geometry and partner packages are not imported", {
  desc <- utils::packageDescription("qupflowR")
  hard <- c(description_field_packages(desc$Depends),
            description_field_packages(desc$Imports))
  optional <- c("arrow", "bslib", "chromote", "DT", "htmltools", "htmlwidgets",
                "hyperannotatR", "plotly", "promises", "reticulate", "rJava",
                "segmantR", "sf", "shiny", "shinytest2")
  expect_identical(intersect(hard, optional), character())
  expect_true(all(c("shiny", "sf", "hyperannotatR") %in%
                    description_field_packages(desc$Suggests)))
})

test_that("library(qupflowR) loads no optional package and writes no files", {
  # covr instruments the installed copy, which then loads covr and sets its
  # options on load; R CMD check and the package lane test the real build.
  skip_on_covr()
  skip_if_not_installed("callr")
  installed <- find.package("qupflowR", lib.loc = .libPaths(), quiet = TRUE)
  skip_if(length(installed) == 0L,
          "qupflowR is not installed in .libPaths() (source-tree test run)")
  installed_version <- read.dcf(file.path(installed[[1L]], "DESCRIPTION"),
                                fields = "Version")[1L, 1L]
  skip_if(!identical(unname(installed_version),
                     utils::packageDescription("qupflowR")$Version),
          "the installed qupflowR is a different version than the tests")
  home <- withr::local_tempdir("qf-load-home-")
  user_dirs <- c(
    XDG_CONFIG_HOME = "config", XDG_CACHE_HOME = "cache",
    XDG_DATA_HOME = "data", XDG_STATE_HOME = "state",
    R_USER_CONFIG_DIR = "r-config", R_USER_CACHE_DIR = "r-cache",
    R_USER_DATA_DIR = "r-data"
  )
  env <- c(callr::rcmd_safe_env(), HOME = home,
           vapply(user_dirs, function(d) file.path(home, d), character(1)))
  result <- callr::r(function() {
    namespaces_before <- loadedNamespaces()
    options_before <- names(options())
    suppressPackageStartupMessages(library("qupflowR", character.only = TRUE))
    children <- character()
    task_dirs <- list.files("/proc/self/task", full.names = TRUE)
    for (task in task_dirs) {
      children_file <- file.path(task, "children")
      if (file.exists(children_file)) {
        children <- c(children, readLines(children_file, warn = FALSE))
      }
    }
    list(
      new_namespaces = setdiff(loadedNamespaces(), namespaces_before),
      new_options = setdiff(names(options()), options_before),
      home_files = list.files(Sys.getenv("HOME"), all.files = TRUE,
                              recursive = TRUE, no.. = TRUE),
      child_processes = trimws(paste(children, collapse = " ")),
      suggests = utils::packageDescription("qupflowR")$Suggests
    )
  }, libpath = .libPaths(), env = env)
  expect_true("qupflowR" %in% result$new_namespaces)
  suggested <- description_field_packages(result$suggests)
  expect_gt(length(suggested), 0L)
  expect_identical(intersect(result$new_namespaces, suggested), character())
  expect_identical(result$new_options, character())
  expect_identical(result$home_files, character())
  expect_identical(result$child_processes, "")
})
