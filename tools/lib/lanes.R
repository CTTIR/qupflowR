# Validation lanes of tools/validate.R (docs/10, lanes L01 to L12).
#
# A lane runner receives ctx (tl: common helpers, root, lane_id, lane,
# lane_dir, reports_dir, artifacts_dir, rt, opts, cmds: command log, checks,
# java_home, qupath_home, gradle_home, tmp_prefix, task_id, label, quiet)
# and records its assertions with tl$add_check(ctx$checks, ...). It returns
# a list with optional result ("skip" only for lanes that do not exist yet),
# skip_reason, failure_reasons, artifact_digests, fixture_digests, runtime,
# oracle, tolerances, limitations, logs and details. tools/validate.R turns
# that into the evidence report: a lane passes only when it recorded at
# least one check and none failed.

lane_catalogue <- function() {
  list(
    L01 = list(name = "core", runner = "lane_core",
               quality_ids = c("Q02", "Q04", "Q06", "Q10", "Q16")),
    L02 = list(name = "contract", runner = "lane_contract",
               quality_ids = c("Q10", "Q16", "Q19")),
    L03 = list(name = "worker", runner = "lane_worker",
               quality_ids = c("Q16", "Q18")),
    L04 = list(name = "gui", runner = "lane_gui",
               quality_ids = c("Q16", "Q18")),
    L05 = list(name = "app-server", first_task = "T03",
               quality_ids = c("Q05", "Q13", "Q16")),
    L06 = list(name = "browser", first_task = "T06",
               quality_ids = c("Q13", "Q14", "Q16")),
    L07 = list(name = "interchange", first_task = "T04",
               quality_ids = c("Q10", "Q11", "Q15")),
    L08 = list(name = "recovery-scale", first_task = "T02",
               quality_ids = c("Q12", "Q16")),
    L09 = list(name = "workflow", first_task = "T12",
               quality_ids = c("Q16", "Q18")),
    L10 = list(name = "portability", first_task = "T14", never_local = TRUE,
               quality_ids = "Q18"),
    L11 = list(name = "package", runner = "lane_package",
               quality_ids = c("Q06", "Q17", "Q20")),
    L12 = list(name = "quality", runner = "lane_quality",
               quality_ids = c("Q07", "Q08", "Q09", "Q17", "Q19"))
  )
}

# Acceptance profiles: every listed lane is required and must pass. L10 needs
# a real second computer, so all-local can never pass on its own.
profile_table <- function() {
  list(
    t01 = c("L01", "L02", "L03", "L04", "L11", "L12"),
    "all-local" = sprintf("L%02d", 1:12)
  )
}

lane_log <- function(ctx, name) {
  file.path(ctx$lane_dir, "logs", name)
}

lane_script <- function(ctx, ...) {
  file.path(ctx$root, "tools", ...)
}

lane_timeout <- function(ctx, seconds) {
  seconds * as.numeric(ctx$tl$opt_or(ctx$opts, "timeout_scale", "1"))
}

lane_not_implemented <- function(ctx) {
  entry <- ctx$lane
  reason <- sprintf("not implemented before %s", entry$first_task)
  limitations <- list(paste(
    "The lane's functionality does not exist yet; a skip never counts as a",
    "pass in an acceptance profile."
  ))
  if (isTRUE(entry$never_local)) {
    reason <- paste0(reason, "; it can never pass from a local run because ",
                     "it needs a real second computer (machine B)")
  }
  list(result = "skip", skip_reason = reason, limitations = limitations)
}

# ---- shared pieces ----------------------------------------------------------

lane_test_run <- function(ctx, id, env, filter = NULL, prefix = "testthat") {
  tl <- ctx$tl
  out <- file.path(ctx$lane_dir, paste0(prefix, "-summary.json"))
  junit <- file.path(ctx$lane_dir, paste0(prefix, "-junit.xml"))
  args <- c(lane_script(ctx, "lib", "testthat-run.R"), "--root", ctx$root,
            "--out", out, "--junit", junit,
            if (!is.null(filter)) c("--filter", filter))
  status <- tl$run_logged(ctx$cmds, id, tl$rscript_path(), args,
                          lane_log(ctx, paste0(prefix, ".log")), env,
                          ctx$root, timeout = lane_timeout(ctx, 3600),
                          label = ctx$label, quiet = ctx$quiet)
  summary <- tl$read_json_file(out)
  tl$add_check(ctx$checks, paste0(id, "_exit_code"), 0L, status)
  tl$add_check(ctx$checks, paste0(id, "_summary_written"), TRUE,
               !is.null(summary))
  counts <- summary$counts
  tl$add_check(ctx$checks, paste0(id, "_tests_ran"), TRUE,
               isTRUE(counts$tests > 0L) && isTRUE(counts$expectations > 0L))
  tl$add_check(ctx$checks, paste0(id, "_failed_expectations"), 0L,
               counts$failed)
  tl$add_check(ctx$checks, paste0(id, "_erroring_expectations"), 0L,
               counts$errors)
  summary
}

lane_offline_env <- function(ctx, extra = character()) {
  proxy <- "http://127.0.0.1:9"
  local <- "localhost,127.0.0.1,::1"
  ctx$tl$child_env(ctx$rt, extra = c(
    NOT_CRAN = "true", http_proxy = proxy, https_proxy = proxy,
    ftp_proxy = proxy, HTTP_PROXY = proxy, HTTPS_PROXY = proxy,
    ALL_PROXY = proxy, no_proxy = local, NO_PROXY = local, extra
  ))
}

lane_read_jsonl <- function(path) {
  if (!file.exists(path)) {
    return(list())
  }
  lines <- readLines(path, warn = FALSE, encoding = "UTF-8")
  lapply(lines[nzchar(lines)], function(line) {
    tryCatch(jsonlite::fromJSON(line, simplifyVector = FALSE),
             error = function(e) list(unparsed = line))
  })
}

lane_java_env <- function(ctx) {
  tl <- ctx$tl
  tl$child_env(
    ctx$rt, path_dirs = file.path(ctx$java_home, "bin"),
    extra = c(JAVA_HOME = ctx$java_home, GRADLE_USER_HOME = ctx$gradle_home,
              QUPFLOWR_QUPATH_HOME = ctx$qupath_home,
              GRADLE_OPTS = tl$jvm_isolation_options(ctx$rt))
  )
}

lane_java_runtime <- function(ctx) {
  list(jdk = ctx$tl$java_home_info(ctx$java_home),
       qupath = ctx$tl$qupath_home_info(ctx$qupath_home))
}

lane_fixture_count <- function(dir) {
  files <- list.files(dir)
  sum(grepl("\\.json$", files) & !grepl("\\.expect\\.json$", files))
}

# The bridge scripts only accept work directories below <repo>/work/.
lane_dir_below_work <- function(ctx) {
  ctx$tl$add_check(ctx$checks, "lane_directory_below_repository_work", TRUE,
                   ctx$tl$is_within(ctx$lane_dir,
                                    file.path(ctx$root, "work")))
}

lane_tools_present <- function(ctx, tools) {
  for (tool in tools) {
    ctx$tl$add_check(ctx$checks, paste0("tool_", tool), TRUE,
                     nzchar(Sys.which(tool)))
  }
  all(nzchar(Sys.which(tools)))
}

# ---- L01 core --------------------------------------------------------------

# The only skips the core profile may produce, each with its reason. A skip
# that matches no entry fails the lane, so a test cannot quietly stop
# running.
lane_core_expected_skips <- function() {
  list(
    list(
      file = "test-fixtures-expected.R",
      message = "generated fixtures are not used in the core test profile",
      reason = paste(
        "the generated-tree tests need QuPath-generated fixtures; lane L03",
        "and tools/build.R --target fixtures run them with zero skips"
      )
    ),
    list(
      file = "test-package-load.R",
      message = paste("qupflowR is not installed in .libPaths()",
                      "(source-tree test run)"),
      reason = paste(
        "load isolation is tested on an installed package; lane L11 runs it",
        "inside R CMD check"
      )
    )
  )
}

lane_core_unexpected_skips <- function(skips) {
  allowed <- lane_core_expected_skips()
  Filter(function(skip) {
    !any(vapply(allowed, function(a) {
      identical(skip$file, a$file) &&
        grepl(a$message, paste0(skip$message, ""), fixed = TRUE)
    }, logical(1)))
  }, skips)
}

lane_core <- function(ctx) {
  tl <- ctx$tl
  skip_log <- file.path(ctx$lane_dir, "skips.jsonl")
  env <- lane_offline_env(ctx, c(QUPFLOWR_TEST_PROFILE = "core",
                                 QUPFLOWR_SKIP_LOG = skip_log))
  summary <- lane_test_run(ctx, "testthat_core", env)
  records <- lane_read_jsonl(skip_log)
  codes <- vapply(records, function(r) paste0(r$reason_code, ""), "")
  tl$add_check(ctx$checks, "skip_records_parse", TRUE,
               !any(vapply(records, function(r) !is.null(r$unparsed), TRUE)))
  unexpected <- lane_core_unexpected_skips(summary$skips)
  tl$add_check(ctx$checks, "unexpected_skips", "",
               paste(vapply(unexpected, function(u) {
                 paste0(u$file, ": ", u$test)
               }, ""), collapse = "; "))
  list(
    fixture_digests = Filter(Negate(is.null), list(
      committed_expected_tables = tl$tree_digest(
        file.path(ctx$root, "tests", "fixtures"), ctx$root
      ),
      protocol_fixture_corpus = tl$tree_digest(
        file.path(ctx$root, "inst", "schema", "protocol", "v1", "fixtures"),
        ctx$root
      )
    )),
    oracle = paste(
      "The package's own testthat suite from the source tree; its",
      "expectations compare against committed expected-value tables,",
      "registry tables and hand-computed values. Integration prerequisites",
      "are forced to skip (QUPFLOWR_TEST_PROFILE=core) and every such skip",
      "is recorded in skips.jsonl."
    ),
    tolerances = list(
      failed = 0L, errors = 0L,
      skips = paste("only the skips listed in expected_skips; every other",
                    "skip fails the lane"),
      expected_skips = lane_core_expected_skips()
    ),
    limitations = list(
      paste("Java, QuPath, displays and browsers are withheld through the",
            "environment (no JAVA_HOME, QUPFLOWR_* integration variables,",
            "DISPLAY or CHROMOTE_CHROME); a system java on PATH is not",
            "removed."),
      paste("Network access is discouraged through proxy variables pointing",
            "at 127.0.0.1:9; raw sockets are not blocked.")
    ),
    details = list(
      counts = summary$counts, per_file = summary$per_file,
      problems = summary$problems, skips = summary$skips,
      unexpected_skips = unexpected,
      skip_reason_codes = tl$as_object(as.list(table(codes))),
      environment = list(QUPFLOWR_TEST_PROFILE = "core",
                         skip_log = tl$relative_to(skip_log, ctx$root))
    )
  )
}

# ---- L02 contract ----------------------------------------------------------

lane_contract_r_files <- function() {
  c("conditions", "json", "json-canonical", "json-schema",
    "protocol-fixtures", "schema-meta")
}

lane_gradle_modules <- function(bridge) {
  lines <- readLines(file.path(bridge, "settings.gradle.kts"), warn = FALSE)
  hits <- regmatches(lines, regexec("^\\s*include\\(\"([^\"]+)\"\\)", lines))
  sort(unlist(lapply(hits, function(h) if (length(h) == 2L) h[[2L]])),
       method = "radix")
}

lane_contract <- function(ctx) {
  tl <- ctx$tl
  files <- lane_contract_r_files()
  filter <- paste0("^(", paste(files, collapse = "|"), ")$")
  summary <- lane_test_run(ctx, "testthat_contract",
                           lane_offline_env(ctx, c(
                             QUPFLOWR_TEST_PROFILE = "core"
                           )),
                           filter = filter, prefix = "testthat-contract")
  ran <- vapply(summary$per_file, function(f) paste0(f$file, ""), "")
  tl$add_check(ctx$checks, "contract_test_files_ran",
               sort(paste0("test-", files, ".R"), method = "radix"),
               sort(ran, method = "radix"))

  bridge <- file.path(ctx$root, "tools", "bridge")
  schema_dir <- file.path(ctx$root, "inst", "schema", "protocol", "v1")
  vocabulary_dir <- file.path(ctx$root, "inst", "schema", "annotatr-control",
                              "v1")
  java <- tl$java_home_ok(tl$java_home_info(ctx$java_home))
  junit <- NULL
  parity <- NULL
  below_work <- lane_dir_below_work(ctx)
  if (tl$add_check(ctx$checks, "jdk_25_with_javac", TRUE, java) &&
        below_work) {
    started <- Sys.time()
    jvm <- tl$jvm_isolation_options(ctx$rt)
    offline <- !isTRUE(ctx$opts$online) &&
      tl$gradle_offline_ok(bridge, ctx$gradle_home)
    args <- c("-p", bridge, "--no-daemon", "--console=plain",
              if (offline) "--offline",
              paste0("-Dorg.gradle.jvmargs=", jvm, " -Xmx2g"),
              paste0("-Pqupflowr.qupathHome=", ctx$qupath_home),
              "test", "--rerun")
    status <- tl$run_logged(ctx$cmds, "gradle_test_all_modules",
                            tl$gradle_wrapper(bridge), args,
                            lane_log(ctx, "gradle-test.log"),
                            lane_java_env(ctx), bridge,
                            timeout = lane_timeout(ctx, 3600),
                            label = ctx$label, quiet = ctx$quiet)
    tl$add_check(ctx$checks, "gradle_test_exit_code", 0L, status)
    junit <- tl$junit_summary(bridge, since = started - 1)
    modules <- lane_gradle_modules(bridge)
    with_results <- vapply(junit$modules, function(m) m$module, "")
    tl$add_check(ctx$checks, "junit_results_for_every_module", modules,
                 sort(with_results, method = "radix"))
    tl$add_check(ctx$checks, "junit_results_fresh", TRUE,
                 length(junit$modules) > 0L &&
                   all(vapply(junit$modules, function(m) m$all_fresh, TRUE)))
    tl$add_check(ctx$checks, "junit_failures_and_errors", 0L,
                 junit$failures + junit$errors)
    corpus <- sum(vapply(c(schema_dir, vocabulary_dir), function(dir) {
      lane_fixture_count(file.path(dir, "fixtures", "valid")) +
        lane_fixture_count(file.path(dir, "fixtures", "invalid"))
    }, numeric(1)))
    corpus_tests <- tl$suite_tests(junit, "SharedSchemaCorpusTest$")
    tl$add_check(ctx$checks, "java_schema_corpus_tests_cover_fixtures",
                 paste(">=", corpus), corpus_tests,
                 pass = corpus > 0L && corpus_tests >= corpus)
    canonical <- list.files(file.path(schema_dir, "fixtures", "canonical"))
    vectors <- sum(grepl("\\.canonical$", canonical)) +
      sum(grepl("^reject-.*\\.json$", canonical) &
            !grepl("\\.expect\\.json$", canonical))
    vector_tests <- tl$suite_tests(junit, "CanonicalJsonVectorsTest$")
    tl$add_check(ctx$checks, "java_canonical_vector_tests_cover_vectors",
                 paste(">=", vectors), vector_tests,
                 pass = vectors > 0L && vector_tests >= vectors)
    tl$add_check(ctx$checks, "java_error_registry_parity_tests_ran", TRUE,
                 tl$suite_tests(junit, "ErrorCodeRegistryParityTest$") > 0L)

    parity_dir <- file.path(ctx$lane_dir, "contract-parity")
    parity_args <- c(lane_script(ctx, "bridge", "scripts",
                                 "contract-parity.sh"),
                     "--work-dir", parity_dir,
                     if (isTRUE(ctx$opts$online)) "--online")
    parity_status <- tl$run_logged(
      ctx$cmds, "contract_parity", Sys.which("bash"), parity_args,
      lane_log(ctx, "contract-parity.log"), lane_java_env(ctx), ctx$root,
      timeout = lane_timeout(ctx, 3600), label = ctx$label, quiet = ctx$quiet
    )
    parity <- tl$read_json_file(file.path(parity_dir,
                                          "contract-parity-report.json"))
    tl$add_check(ctx$checks, "contract_parity_exit_code", 0L, parity_status)
    tl$add_check(ctx$checks, "contract_parity_result", "pass", parity$result)
    tl$add_check(ctx$checks, "contract_parity_failed_checks", 0L,
                 parity$failed_checks)
    tl$add_check(ctx$checks, "contract_parity_documents_compared", TRUE,
                 isTRUE(parity$accepted + parity$rejected > 0L))
    tl$add_check(ctx$checks, "contract_parity_mismatches_and_missing", 0L,
                 parity$mismatches + parity$missing)
  }
  corpus_manifest <- file.path(ctx$lane_dir, "contract-parity", "corpus",
                               "manifest.json")
  list(
    fixture_digests = Filter(Negate(is.null), list(
      protocol_schemas_and_fixtures = tl$tree_digest(schema_dir, ctx$root),
      annotatr_capability_vocabulary = tl$tree_digest(vocabulary_dir,
                                                      ctx$root),
      contract_parity_corpus_manifest = if (file.exists(corpus_manifest)) {
        tl$sha256_files(corpus_manifest)
      }
    )),
    runtime = lane_java_runtime(ctx),
    oracle = paste(
      "Shared fixture corpora under inst/schema/protocol/v1 and",
      "inst/schema/annotatr-control/v1 with expected",
      "(path, keyword, schema_path) sidecars and canonical vectors with",
      "sha256; R testthat and Java JUnit both run it; the parity script",
      "compares R and Java results byte by byte on a seeded corpus. The",
      "fixture and vector counts are taken from the directory, not from the",
      "tests."
    ),
    tolerances = "exact bytes and exact error sets; zero failures",
    limitations = list(
      paste("contract-parity.sh reruns :bridge-protocol:test and replaces",
            "its JUnit XML; the lane parses the all-module results first."),
      paste("contract-parity.sh uses the lane's JAVA_HOME and quotes its JVM",
            "options; a work directory containing a double quote, backslash,",
            "dollar sign or backquote is refused."),
      "Java code coverage is not measured."
    ),
    details = list(
      r_tests = list(counts = summary$counts, per_file = summary$per_file,
                     problems = summary$problems),
      gradle_tasks = tl$gradle_task_summary(lane_log(ctx, "gradle-test.log")),
      junit = if (is.null(junit)) NULL else
        junit[c("files", "tests", "failures", "errors", "skipped",
                "modules")],
      contract_parity = parity
    )
  )
}

# ---- L03 worker and L04 gui --------------------------------------------------

lane_bundle <- function(ctx) {
  tl <- ctx$tl
  bridge <- file.path(ctx$root, "tools", "bridge")
  version <- tl$gradle_properties(bridge)$version
  name <- sprintf("qupflowr-bridge-bundle-%s.zip", version)
  path <- tl$opt_or(ctx$opts, "bundle",
                    file.path(ctx$artifacts_dir, "bridge", name))
  path <- tl$absolute_path(path)
  latest <- file.path(bridge, "build", "distributions", name)
  sha <- if (file.exists(path)) tl$sha256_files(path) else NULL
  list(
    path = path, exists = file.exists(path),
    sidecar_matches = tl$sidecar_matches(path), sha256 = sha,
    equals_latest_gradle_output = file.exists(latest) &&
      identical(sha, tl$sha256_files(latest))
  )
}

lane_smoke <- function(ctx, script, report_name, extra_args, timeout,
                       extra_env = character()) {
  tl <- ctx$tl
  bundle <- lane_bundle(ctx)
  ok <- tl$add_check(ctx$checks, "bridge_bundle_present", TRUE,
                     bundle$exists)
  ok <- tl$add_check(ctx$checks, "bridge_bundle_sidecar_matches", TRUE,
                     bundle$sidecar_matches) && ok
  java_ok <- tl$add_check(ctx$checks, "jdk_25_with_javac", TRUE,
                          tl$java_home_ok(tl$java_home_info(ctx$java_home)))
  qupath_ok <- tl$add_check(
    ctx$checks, "qupath_core_version", "0.7.0",
    tl$qupath_home_info(ctx$qupath_home)$implementation_version
  )
  tools_ok <- lane_tools_present(ctx, c("bash", "jq", "curl", "ss", "unzip",
                                        "sha256sum", "timeout")) &&
    lane_dir_below_work(ctx)
  smoke <- NULL
  failed <- list()
  work <- file.path(ctx$lane_dir, "smoke")
  if (ok && java_ok && qupath_ok && tools_ok) {
    args <- c(lane_script(ctx, "bridge", "scripts", script), "--work-dir",
              work, "--bundle", bundle$path, extra_args)
    env <- lane_java_env(ctx)
    env[names(extra_env)] <- extra_env
    status <- tl$run_logged(ctx$cmds, sub("\\.sh$", "", script),
                            Sys.which("bash"), args,
                            lane_log(ctx, sub("\\.sh$", ".log", script)),
                            env, ctx$root,
                            timeout = lane_timeout(ctx, timeout),
                            grace = 90, label = ctx$label, quiet = ctx$quiet)
    tl$add_check(ctx$checks, "smoke_exit_code", 0L, status)
    smoke <- tl$read_json_file(file.path(work, paste0(report_name,
                                                      "-report.json")))
    tl$add_check(ctx$checks, "smoke_report_result", "pass", smoke$result)
    tl$add_check(ctx$checks, "smoke_assertions_ran", TRUE,
                 isTRUE(smoke$checks > 0L))
    tl$add_check(ctx$checks, "smoke_failed_assertions", 0L, smoke$failures)
    tsv <- file.path(work, "results.tsv")
    if (file.exists(tsv)) {
      rows <- strsplit(readLines(tsv, warn = FALSE), "\t", fixed = TRUE)
      failed <- lapply(Filter(function(r) identical(r[length(r)], "FAIL"),
                              rows),
                       function(r) paste(r, collapse = " | "))
    }
  }
  list(
    artifact_digests = if (!is.null(bundle$sha256)) {
      stats::setNames(list(bundle$sha256),
                      tl$relative_to(bundle$path, ctx$root))
    },
    runtime = lane_java_runtime(ctx),
    work = work, smoke = smoke, failed_assertions = failed, bundle = bundle
  )
}

lane_worker <- function(ctx) {
  tl <- ctx$tl
  out <- lane_smoke(ctx, "smoke-worker.sh", "smoke-worker", character(),
                    timeout = 1200)
  summary <- tl$read_json_file(file.path(out$work, "worker-summary.json"))
  # Synthetic fixture set 1 is generated with the real QuPath 0.7.0 and
  # checked against its independent oracle (committed tables, R TIFF reader,
  # QuPath project files).
  fixtures <- tl$fixture_oracle(list(
    cmds = ctx$cmds, checks = ctx$checks, root = ctx$root,
    out = file.path(ctx$root, "work", "fixtures", "v1"),
    logs = file.path(ctx$lane_dir, "logs"),
    summary = file.path(ctx$lane_dir, "fixtures-oracle-summary.json"),
    env = lane_java_env(ctx), test_env = lane_offline_env(ctx),
    java_home = ctx$java_home, qupath_home = ctx$qupath_home,
    gradle_home = ctx$gradle_home, online = ctx$opts$online, force = FALSE,
    label = ctx$label, quiet = ctx$quiet
  ))
  list(
    artifact_digests = out$artifact_digests, runtime = out$runtime,
    fixture_digests = fixtures$fixture_digests,
    oracle = paste(
      "tools/bridge/scripts/smoke-worker.sh assertions: bundle and",
      "distribution checksums, launch.json profiles, ready line, discovery",
      "manifest, authenticated GET /v1/health (401 without token), token",
      "hygiene, exit 0 on end of input and 143 on SIGTERM, and the",
      "qupath-script profile with a digest-checked bootstrap and quoted",
      "JAVA_TOOL_OPTIONS. Fixture set 1 generated with QuPath 0.7.0",
      "(tools/fixtures.R, reused when generator, QuPath and Java are",
      "unchanged) must pass tests/testthat/test-fixtures-expected.R with zero",
      "failures and zero skips: generated tree against the committed",
      "expected-value tables, an independent R TIFF reader and the QuPath",
      "project files."
    ),
    tolerances = "every assertion must pass; an incomplete scenario fails",
    limitations = list(
      paste("T01 slice: bridge start-up, health and lifecycle only; project,",
            "reader and analysis checks join this lane from T02 to T04."),
      paste("The official jpackage launcher is recorded as information by",
            "the smoke script; worker profiles use a Java 25 launcher."),
      paste("The fixture generator writes QuPath projects with the QuPath",
            "API; reading them back through the bridge joins this lane in",
            "T03 and T04.")
    ),
    details = list(bundle = out$bundle, smoke_report = out$smoke,
                   worker_summary = summary,
                   failed_assertions = out$failed_assertions,
                   work_dir = tl$relative_to(out$work, ctx$root),
                   fixtures = list(
                     output = "work/fixtures/v1",
                     fixture_set = fixtures$manifest$fixture_set,
                     files = length(fixtures$manifest$files),
                     generator = fixtures$manifest$generator,
                     oracle_tests = fixtures$summary[c("counts", "per_file",
                                                       "problems", "skips")]
                   ))
  )
}

lane_display <- function(ctx) {
  tl <- ctx$tl
  requested <- tl$opt_or(ctx$opts, "display", NA_character_)
  if (is.na(requested)) {
    return(tl$free_display())
  }
  number <- suppressWarnings(as.integer(sub("^:", "", requested)))
  if (!grepl("^:[0-9]+$", requested) || is.na(number) || number < 90L) {
    tl$tool_abort("usage", paste(
      "--display must be a private display :90 or above, got", requested
    ))
  }
  if (tl$display_in_use(number)) NULL else requested
}

lane_launch_outcomes <- function(attempts) {
  if (is.null(attempts) || !file.exists(attempts)) {
    return(character())
  }
  rows <- strsplit(readLines(attempts, warn = FALSE)[-1L], "\t", fixed = TRUE)
  vapply(Filter(function(r) length(r) >= 3L, rows), function(r) r[[3L]], "")
}

lane_gui <- function(ctx) {
  tl <- ctx$tl
  display <- lane_display(ctx)
  xvfb <- tl$env_or("QUPFLOWR_XVFB", file.path(ctx$root, ".tools", "xvfb",
                                               "usr", "bin", "Xvfb"))
  display_ok <- tl$add_check(ctx$checks, "free_private_display", TRUE,
                             !is.null(display))
  xvfb_ok <- tl$add_check(ctx$checks, "xvfb_present", TRUE, file.exists(xvfb))
  out <- NULL
  inotify_before <- tl$inotify_usage()
  if (display_ok && xvfb_ok) {
    xvfb_libs <- tl$env_or("QUPFLOWR_XVFB_LIBS", file.path(
      ctx$root, ".tools", "xvfb", "usr", "lib", "x86_64-linux-gnu"
    ))
    out <- lane_smoke(ctx, "smoke-extension.sh", "smoke-extension",
                      c("--display", display), timeout = 1800,
                      extra_env = c(QUPFLOWR_XVFB = xvfb,
                                    QUPFLOWR_XVFB_LIBS = xvfb_libs))
  }
  summary <- if (is.null(out)) NULL else
    tl$read_json_file(file.path(out$work, "launch-summary.json"))
  attempts <- if (is.null(out)) NULL else
    file.path(out$work, "launch-attempts.tsv")
  outcomes <- lane_launch_outcomes(attempts)
  reasons <- list()
  if (length(outcomes) > 0L && all(outcomes == "inotify_skip")) {
    reasons <- list(paste0(
      "QuPath skipped its extension directory on all ", length(outcomes),
      " launch attempts because the user's inotify instances are exhausted ",
      "(\"User limit of inotify instances reached\"; host defect, decision ",
      "D021; fs.inotify.max_user_instances=",
      inotify_before$max_user_instances, ", inotify descriptors of this ",
      "user before the run: ", inotify_before$inotify_descriptors_upper_bound,
      "). Close applications that hold inotify instances or raise the limit, ",
      "then rerun the lane."
    ))
  }
  list(
    failure_reasons = reasons,
    artifact_digests = out$artifact_digests,
    runtime = c(lane_java_runtime(ctx), list(
      xvfb = list(path = xvfb, sha256 = if (file.exists(xvfb)) {
        tl$sha256_files(xvfb)
      }),
      display = display, inotify_before = inotify_before,
      inotify_after = tl$inotify_usage()
    )),
    oracle = paste(
      "tools/bridge/scripts/smoke-extension.sh assertions against a real",
      "QuPath 0.7.0 GUI on a private Xvfb display: extension installed from",
      "the verified bundle, menu present, discovery files with 0700/0600",
      "modes, authenticated health and capabilities, 401/403 cases, token",
      "hygiene, SIGTERM exit 143 with discovery files removed."
    ),
    tolerances = paste("every assertion must pass; launch retries for the",
                       "inotify skip are recorded, not hidden"),
    limitations = list(
      paste("T01 slice: extension start-up, health and lifecycle; selection,",
            "events and dirty-state checks join this lane in T05 and T06."),
      "Xvfb without a window manager; Wayland is not covered."
    ),
    details = list(
      display = display, bundle = out$bundle, smoke_report = out$smoke,
      launch_summary = summary,
      launch_attempts = if (!is.null(attempts) && file.exists(attempts)) {
        as.list(readLines(attempts, warn = FALSE))
      },
      failed_assertions = out$failed_assertions,
      work_dir = if (is.null(out)) NULL else tl$relative_to(out$work, ctx$root)
    )
  )
}

# ---- L11 package -----------------------------------------------------------

# Planning, work and tooling areas and hidden directories (version control,
# CI, editor and local tool settings) that must never ship (BLUEPRINT
# section 2, docs/21), plus the files the repository's .Rbuildignore
# excludes on purpose.
lane_forbidden_patterns <- function() {
  areas <- paste(c(
    "setup", "dev", "work", "artifacts", "site", "tools", "renv", "pkgdown",
    "docs", "adr", "workplan", "prompts", "state", "private-data",
    "tests/api", "Meta", "doc"
  ), collapse = "|")
  files <- paste(c(
    "renv\\.lock", "\\.lintr", "_pkgdown\\.yml", "qupflowR_icon\\.svg",
    "LICENSE\\.md", "AGENTS\\.md", "ORCHESTRATOR\\.md", "CONTRACT_MAP\\.md",
    "START_HERE\\.md", "\\.Rprofile", "\\.Renviron", "codecov\\.yml",
    "cran-comments\\.md", "README\\.Rmd", "man/figures/logo\\.png"
  ), collapse = "|")
  c(paste0("^qupflowR/(", areas, ")(/|$)"), paste0("^qupflowR/(", files, ")$"),
    "^qupflowR/\\.[^/]+/", "\\.token$", "(^|/)hs_err_pid[^/]*\\.log$",
    "\\.Rcheck(/|$)", "(^|/)qupflowR_[^/]*\\.tar\\.gz$", "(^|/)\\.DS_Store$",
    "(^|/)\\.Rhistory$", "(^|/)\\.RData$")
}

lane_tarball_audit <- function(listing) {
  patterns <- lane_forbidden_patterns()
  forbidden <- listing[Reduce("|", lapply(patterns, grepl, x = listing))]
  required <- c("qupflowR/DESCRIPTION", "qupflowR/NAMESPACE",
                "qupflowR/tests/testthat.R",
                "qupflowR/inst/i18n/messages.json",
                "qupflowR/inst/schema/protocol/v1/index.json",
                "qupflowR/inst/schema/protocol/v1/error-codes.json",
                "qupflowR/inst/schema/annotatr-control/v1/index.json")
  list(
    entries = length(listing),
    forbidden = as.list(forbidden),
    missing_required = as.list(setdiff(required, listing)),
    r_files = sum(grepl("^qupflowR/R/[^/]+\\.R$", listing)),
    rd_files = sum(grepl("^qupflowR/man/[^/]+\\.Rd$", listing)),
    unsafe = as.list(listing[grepl("^/|(^|/)\\.\\.(/|$)", listing) |
                               !startsWith(listing, "qupflowR/")]),
    paths_over_100_bytes = as.list(listing[nchar(listing, type = "bytes") >
                                             100L]),
    patterns = as.list(patterns)
  )
}

lane_description_packages <- function(desc, fields) {
  values <- desc[intersect(fields, names(desc))]
  values <- values[!is.na(values)]
  if (length(values) == 0L) {
    return(character())
  }
  items <- trimws(sub("\\(.*$", "", unlist(strsplit(values, ","))))
  unique(items[nzchar(items) & items != "R"])
}

# A library with only qupflowR and its hard dependency closure (symbolic
# links into the existing libraries, which are only read); R's own library
# stays visible, site and user libraries are hidden.
lane_restricted_library <- function(ctx, lib) {
  tl <- ctx$tl
  dir <- file.path(ctx$lane_dir, "restricted-library")
  tl$reset_dir(dir, ctx$lane_dir)
  desc <- read.dcf(file.path(lib, "qupflowR", "DESCRIPTION"))[1L, ]
  hard <- lane_description_packages(desc, c("Depends", "Imports",
                                            "LinkingTo"))
  others <- setdiff(.libPaths(), .Library)
  db <- utils::installed.packages(lib.loc = .libPaths())
  deps <- tools::package_dependencies(hard, db = db, recursive = TRUE,
                                      which = c("Depends", "Imports",
                                                "LinkingTo"))
  base <- rownames(utils::installed.packages(lib.loc = .Library))
  closure <- sort(setdiff(unique(c(hard, unlist(deps))), c(base, "R")),
                  method = "radix")
  missing <- character()
  for (pkg in closure) {
    path <- find.package(pkg, lib.loc = others, quiet = TRUE)
    if (length(path) == 0L) {
      missing <- c(missing, pkg)
    } else {
      file.symlink(path[[1L]], file.path(dir, pkg))
    }
  }
  file.symlink(file.path(lib, "qupflowR"), file.path(dir, "qupflowR"))
  list(path = dir, packages = as.list(closure), missing = as.list(missing),
       base_library = .Library)
}

lane_check_sections <- function(lines) {
  starts <- which(startsWith(lines, "* "))
  sections <- list()
  for (k in seq_along(starts)) {
    from <- starts[[k]]
    to <- if (k < length(starts)) starts[[k + 1L]] - 1L else length(lines)
    block <- lines[from:to]
    block <- block[!startsWith(block, "Status: ")]
    first <- block[[1L]]
    severity <- regmatches(first, regexpr("(NOTE|WARNING|ERROR)$", first))
    if (length(severity) == 0L && grepl("\\.\\.\\.$", first)) {
      later <- grep("(\\] |^\\s*)(NOTE|WARNING|ERROR)$", block[-1L],
                    value = TRUE)
      if (length(later) > 0L) {
        severity <- sub("^.*(NOTE|WARNING|ERROR)$", "\\1", later[[1L]])
      }
    }
    if (length(severity) == 1L) {
      sections[[length(sections) + 1L]] <- list(
        check = sub(" \\.\\.\\..*$", "", sub("^\\* ", "", first)),
        severity = severity,
        text = as.list(utils::head(block[-1L], 60L))
      )
    }
  }
  sections
}

# NOTEs that may appear, each split into the items R CMD check lists in it.
# Every item must match one reviewed pattern; an unknown item (for example a
# new invalid URL) fails the lane. Quotes are matched in both the plain and
# the typographic form.
lane_note_allowlist <- function() {
  q <- "[\u2018\u2019']"
  version <- "\\(0\\.0\\.0\\.9000\\)"
  list(
    "checking CRAN incoming feasibility" = list(
      list(pattern = "^Maintainer: ",
           reason = "informational line of every incoming check"),
      list(pattern = "^New submission$",
           reason = "the package has never been submitted to CRAN"),
      list(pattern = paste0("^Version contains large components ", version,
                            "$"),
           reason = "development version number until T11"),
      list(pattern = paste0("^Unknown, possibly misspelled, fields in ",
                            "DESCRIPTION: ", q, "Remotes", q, "$"),
           reason = paste("Remotes names the GitHub repository of the",
                          "optional partner package annotatR")),
      list(pattern = paste0("^Suggests or Enhances not in mainstream ",
                            "repositories: annotatR$"),
           reason = "annotatR is not on CRAN (checked only with --online)"),
      list(pattern = paste0("^Found the following \\(possibly\\) invalid ",
                            "URLs: URL: https://cttir\\.github\\.io/qupflowR/",
                            " From: DESCRIPTION man/qupflowR-package\\.Rd",
                            " Status: 404 Message: Not Found$"),
           reason = paste("pkgdown::check_pkgdown() requires the site URL in",
                          "DESCRIPTION; the site is published in T11, which",
                          "removes this exception (checked only with",
                          "--online)"))
    ),
    "checking for future file timestamps" = list(
      list(pattern = "^unable to verify current time$",
           reason = "needs a network time source, unavailable offline")
    )
  )
}

# Items of a NOTE: its text lines grouped at blank lines, white space
# squeezed.
lane_note_items <- function(text) {
  lines <- trimws(unlist(text))
  groups <- cumsum(!nzchar(lines))
  items <- tapply(lines, groups, function(g) {
    paste(g[nzchar(g)], collapse = " ")
  })
  items <- gsub("[[:space:]]+", " ", unname(items))
  items[nzchar(items)]
}

# Unexpected NOTE items as "<check>: <item>".
lane_unexpected_note_items <- function(sections) {
  allow <- lane_note_allowlist()
  out <- character()
  for (x in Filter(function(x) identical(x$severity, "NOTE"), sections)) {
    rules <- allow[[x$check]]
    if (is.null(rules)) {
      out <- c(out, paste0(x$check, ": (NOTE not allowed)"))
      next
    }
    for (item in lane_note_items(x$text)) {
      known <- any(vapply(rules, function(r) {
        grepl(r$pattern, item, perl = TRUE)
      }, logical(1)))
      if (!known) out <- c(out, paste0(x$check, ": ", item))
    }
  }
  out
}

# oldrel-1 of the pinned shared R-CMD-check workflow (CTTIR/.github@d54cd50),
# the oldest R among its blocking jobs (macOS, Windows and Ubuntu release,
# Ubuntu devel, Ubuntu oldrel-1; oldrel-3 is informational). r-lib/actions
# resolves it through api.r-hub.io; --online asks the same service. Offline
# the value resolved on the recorded date is used. oldrel-1 only ever moves
# to newer versions, so a stale value makes the check stricter, never more
# lenient.
lane_ci_oldrel <- function(ctx) {
  url <- "https://api.r-hub.io/rversions/resolve/oldrel/1"
  pinned <- list(version = "4.5.3", resolved_by = "pinned",
                 resolved_on = "2026-09-15", source = url)
  if (!isTRUE(ctx$opts$online)) {
    return(pinned)
  }
  online <- tryCatch(jsonlite::fromJSON(url), error = function(e) NULL)
  version <- online$version
  if (is.character(version) && length(version) == 1L &&
        grepl("^[0-9]+\\.[0-9]+\\.[0-9]+$", version)) {
    return(list(version = version, resolved_by = "online",
                resolved_on = ctx$tl$utc_now(), source = url))
  }
  c(pinned, list(online_error = "api.r-hub.io did not return a version"))
}

# The declared minimum R must be
# * covered by a passing R CMD check: not below the x.y.0 release of the
#   oldest R version whose check passed (patch releases add no features) and
#   not above that version, so the check could install the package;
# * admitted by the blocking oldrel-1 job of the pinned shared CI matrix, or
#   that job cannot install the package (decision D025).
# `checks` holds list(version, passed) for every R that checked the tarball.
lane_r_minimum <- function(tl, root, checks, oldrel) {
  declared <- tl$declared_r_minimum(root)
  passed <- vapply(checks, function(x) isTRUE(x$passed), logical(1))
  versions <- vapply(checks, function(x) x$version, character(1))
  good <- versions[passed & !is.na(versions)]
  oldest <- if (length(good) == 0L) NA_character_ else
    good[order(package_version(good))][[1L]]
  covered_from <- if (is.na(oldest)) NA_character_ else
    tl$r_series_start(oldest)
  known <- !is.na(declared)
  covered <- known && !is.na(oldest) &&
    package_version(declared) >= package_version(covered_from) &&
    package_version(declared) <= package_version(oldest)
  admits <- known &&
    package_version(declared) <= package_version(oldrel$version)
  list(declared = declared, checked = as.list(versions),
       passed = as.list(good), oldest_passing = oldest,
       covered_from = covered_from, covered = covered,
       ci_oldrel_1 = oldrel, admits_ci_oldrel_1 = admits)
}

# R CMD check of the tarball with an additional R installation, using only
# that installation's own dependency library.
lane_series_check <- function(ctx, inst, tarball, remote) {
  tl <- ctx$tl
  version <- if (is.na(inst$version)) "unknown" else inst$version
  id <- paste0("r_", gsub(".", "_", version, fixed = TRUE))
  record <- list(version = inst$version,
                 home = tl$relative_to(inst$home, ctx$root),
                 library = tl$relative_to(inst$library, ctx$root),
                 passed = FALSE)
  if (!tl$add_check(ctx$checks, paste0(id, "_installation_usable"), TRUE,
                    isTRUE(inst$usable))) {
    return(record)
  }
  check_dir <- file.path(ctx$lane_dir, paste0("check-r", version))
  tl$reset_dir(check_dir, ctx$lane_dir)
  env <- tl$child_env(ctx$rt, path_dirs = file.path(inst$home, "bin"),
                      extra = c(
                        R_LIBS_USER = inst$library, R_LIBS = "",
                        R_LIBS_SITE = file.path(ctx$rt$base, "no-site-library"),
                        "_R_CHECK_FORCE_SUGGESTS_" = "false",
                        "_R_CHECK_CRAN_INCOMING_REMOTE_" = remote
                      ))
  status <- tl$run_logged(
    ctx$cmds, paste0(id, "_cmd_check_as_cran"), inst$r,
    c("CMD", "check", "--as-cran", "--no-manual", "-o", check_dir, tarball),
    lane_log(ctx, paste0("r-cmd-check-r", version, ".log")), env,
    ctx$lane_dir, timeout = lane_timeout(ctx, 3600), label = ctx$label,
    quiet = ctx$quiet
  )
  check <- lane_parse_check(check_dir)
  ran <- if (is.null(check)) NA_character_ else check$r_version
  results <- c(
    tl$add_check(ctx$checks, paste0(id, "_cmd_check_exit_code"), 0L, status),
    tl$add_check(ctx$checks, paste0(id, "_cmd_check_used_that_r"), version,
                 ran),
    tl$add_check(ctx$checks, paste0(id, "_cmd_check_errors"), 0L,
                 check$errors),
    tl$add_check(ctx$checks, paste0(id, "_cmd_check_warnings"), 0L,
                 check$warnings),
    tl$add_check(ctx$checks, paste0(id, "_cmd_check_unexpected_note_items"),
                 "", paste(unlist(check$unexpected_notes), collapse = "; "),
                 pass = !is.null(check) &&
                   length(check$unexpected_notes) == 0L),
    tl$add_check(ctx$checks, paste0(id, "_cmd_check_tests_failed"), 0L,
                 check$tests$fail)
  )
  record$passed <- all(results)
  record$exit_code <- status
  record$r_cmd_check <- check
  record
}

lane_parse_check <- function(check_dir) {
  log <- file.path(check_dir, "qupflowR.Rcheck", "00check.log")
  if (!file.exists(log)) {
    return(NULL)
  }
  lines <- readLines(log, warn = FALSE, encoding = "UTF-8")
  status <- grep("^Status: ", lines, value = TRUE)
  status <- if (length(status) == 0L) NULL else status[[length(status)]]
  sections <- lane_check_sections(lines)
  count <- function(s) {
    sum(vapply(sections, function(x) identical(x$severity, s), TRUE))
  }
  unexpected <- lane_unexpected_note_items(sections)
  rout <- Sys.glob(file.path(check_dir, "qupflowR.Rcheck", "tests",
                             "testthat.Rout*"))
  tests <- NULL
  if (length(rout) > 0L) {
    text <- readLines(rout[[1L]], warn = FALSE)
    line <- grep("\\[ FAIL [0-9]+ \\| WARN [0-9]+ \\| SKIP [0-9]+ \\| PASS",
                 text, value = TRUE)
    if (length(line) > 0L) {
      nums <- as.integer(regmatches(line[[length(line)]],
                                    gregexpr("[0-9]+",
                                             line[[length(line)]]))[[1L]])
      tests <- list(file = basename(rout[[1L]]), fail = nums[[1L]],
                    warn = nums[[2L]], skip = nums[[3L]], pass = nums[[4L]])
    }
  }
  used <- grep("^\\* using R version [0-9]", lines, value = TRUE)
  r_version <- if (length(used) == 0L) NULL else
    sub("^\\* using R version ([0-9.]+).*$", "\\1", used[[1L]])
  list(log = log, status = status, r_version = r_version,
       errors = count("ERROR"), warnings = count("WARNING"),
       notes = count("NOTE"), unexpected_notes = unexpected,
       sections = sections, tests = tests)
}

lane_probe_checks <- function(ctx, probe, prefix) {
  tl <- ctx$tl
  tl$add_check(ctx$checks, paste0(prefix, "_package_loads"), TRUE,
               isTRUE(probe$loaded))
  tl$add_check(ctx$checks, paste0(prefix, "_suggests_not_loaded"), "",
               paste(unlist(probe$suggests_loaded), collapse = ","))
  fc <- probe$functional_check
  tl$add_check(ctx$checks, paste0(prefix, "_qp_error_codes_rows"),
               fc$expected_rows, fc$rows,
               pass = isTRUE(fc$ok) && isTRUE(fc$rows == fc$expected_rows) &&
                 isTRUE(fc$rows > 0L))
}

lane_package <- function(ctx) {
  tl <- ctx$tl
  version <- unname(read.dcf(file.path(ctx$root, "DESCRIPTION"),
                             fields = "Version")[1L, 1L])
  build_root <- file.path(ctx$lane_dir, "build")
  build_args <- c(lane_script(ctx, "build.R"), "--target", "package",
                  "--roxygen", "check", "--force", "--quiet",
                  "--artifacts-dir", file.path(build_root, "artifacts"),
                  "--report-dir", file.path(build_root, "reports"),
                  "--work-dir", file.path(ctx$rt$base, "build-runtime"),
                  "--tmp-prefix", ctx$tmp_prefix, "--task-id", ctx$task_id)
  env <- tl$child_env(ctx$rt)
  status <- tl$run_logged(ctx$cmds, "build_package_roxygen_check",
                          tl$rscript_path(), build_args,
                          lane_log(ctx, "build-package.log"), env, ctx$root,
                          timeout = lane_timeout(ctx, 2400),
                          label = ctx$label, quiet = ctx$quiet)
  tl$add_check(ctx$checks, "build_package_exit_code", 0L, status)
  tarball <- file.path(build_root, "artifacts", "r",
                       sprintf("qupflowR_%s.tar.gz", version))
  if (!tl$add_check(ctx$checks, "tarball_built", TRUE,
                    file.exists(tarball))) {
    return(list(details = list(build_report = tl$read_json_file(
      file.path(build_root, "reports", "build", "package.json")
    ))))
  }
  tl$add_check(ctx$checks, "tarball_sidecar_matches", TRUE,
               tl$sidecar_matches(tarball))
  listing <- sort(utils::untar(tarball, list = TRUE, tar = "internal"),
                  method = "radix")
  writeLines(listing, file.path(ctx$lane_dir, "tarball-files.txt"),
             useBytes = TRUE)
  audit <- lane_tarball_audit(listing)
  tl$add_check(ctx$checks, "tarball_forbidden_paths", 0L,
               length(audit$forbidden))
  tl$add_check(ctx$checks, "tarball_required_files_missing", 0L,
               length(audit$missing_required))
  tl$add_check(ctx$checks, "tarball_unsafe_paths", 0L, length(audit$unsafe))
  tl$add_check(ctx$checks, "tarball_has_r_and_rd_files", TRUE,
               audit$r_files > 0L && audit$rd_files > 0L)
  extract <- file.path(ctx$rt$tmp, "tarball-description")
  utils::untar(tarball, files = "qupflowR/DESCRIPTION", exdir = extract,
               tar = "internal")
  tar_version <- unname(read.dcf(file.path(extract, "qupflowR",
                                           "DESCRIPTION"),
                                 fields = "Version")[1L, 1L])
  tl$add_check(ctx$checks, "tarball_version_matches_source", version,
               tar_version)

  lib <- file.path(ctx$lane_dir, "library")
  tl$reset_dir(lib, ctx$lane_dir)
  install <- tl$run_logged(ctx$cmds, "r_cmd_install", tl$r_path(),
                           c("CMD", "INSTALL", "--no-multiarch", "-l", lib,
                             tarball),
                           lane_log(ctx, "r-cmd-install.log"), env,
                           ctx$lane_dir, timeout = lane_timeout(ctx, 1200),
                           label = ctx$label, quiet = ctx$quiet)
  tl$add_check(ctx$checks, "install_exit_code", 0L, install)
  probe_script <- lane_script(ctx, "lib", "load-probe.R")
  probes <- list()
  if (install == 0L) {
    full_out <- file.path(ctx$lane_dir, "load-probe-full.json")
    tl$run_logged(ctx$cmds, "load_probe_full_library", tl$rscript_path(),
                  c(probe_script, "--lib", lib, "--out", full_out),
                  lane_log(ctx, "load-probe-full.log"), env, ctx$lane_dir,
                  timeout = 300, label = ctx$label, quiet = ctx$quiet)
    probes$full <- tl$read_json_file(full_out)
    lane_probe_checks(ctx, probes$full, "full_library")

    restricted <- lane_restricted_library(ctx, lib)
    tl$add_check(ctx$checks, "restricted_library_dependencies_found", 0L,
                 length(restricted$missing))
    restricted_out <- file.path(ctx$lane_dir, "load-probe-restricted.json")
    restricted_env <- tl$child_env(ctx$rt, extra = c(
      R_LIBS_USER = restricted$path, R_LIBS = "",
      R_LIBS_SITE = file.path(ctx$rt$base, "no-site-library")
    ))
    tl$run_logged(ctx$cmds, "load_probe_restricted_library",
                  tl$rscript_path(),
                  c(probe_script, "--lib", restricted$path, "--out",
                    restricted_out),
                  lane_log(ctx, "load-probe-restricted.log"), restricted_env,
                  ctx$lane_dir, timeout = 300, label = ctx$label,
                  quiet = ctx$quiet)
    probes$restricted <- tl$read_json_file(restricted_out)
    probes$restricted_library <- restricted
    lane_probe_checks(ctx, probes$restricted, "restricted_library")
    visible <- setdiff(unlist(probes$restricted$suggests_installed),
                       rownames(utils::installed.packages(
                         lib.loc = .Library
                       )))
    tl$add_check(ctx$checks, "restricted_library_hides_optional_packages", "",
                 paste(visible, collapse = ","))
  }

  check_dir <- file.path(ctx$lane_dir, "check")
  tl$reset_dir(check_dir, ctx$lane_dir)
  remote <- if (isTRUE(ctx$opts$online)) "true" else "false"
  check_env <- tl$child_env(ctx$rt, extra = c(
    "_R_CHECK_FORCE_SUGGESTS_" = "false",
    "_R_CHECK_CRAN_INCOMING_REMOTE_" = remote
  ))
  check_status <- tl$run_logged(
    ctx$cmds, "r_cmd_check_as_cran", tl$r_path(),
    c("CMD", "check", "--as-cran", "--no-manual", "-o", check_dir, tarball),
    lane_log(ctx, "r-cmd-check.log"), check_env, ctx$lane_dir,
    timeout = lane_timeout(ctx, 3600), label = ctx$label, quiet = ctx$quiet
  )
  check <- lane_parse_check(check_dir)
  tl$add_check(ctx$checks, "r_cmd_check_exit_code", 0L, check_status)
  tl$add_check(ctx$checks, "r_cmd_check_log_parsed", TRUE, !is.null(check))
  tl$add_check(ctx$checks, "r_cmd_check_errors", 0L, check$errors)
  tl$add_check(ctx$checks, "r_cmd_check_warnings", 0L, check$warnings)
  tl$add_check(ctx$checks, "r_cmd_check_unexpected_note_items", "",
               paste(unlist(check$unexpected_notes), collapse = "; "),
               pass = !is.null(check) &&
                 length(check$unexpected_notes) == 0L)
  tl$add_check(ctx$checks, "r_cmd_check_used_running_r",
               as.character(getRversion()), check$r_version)
  tl$add_check(ctx$checks, "r_cmd_check_tests_failed", 0L, check$tests$fail)
  main_passed <- check_status == 0L && !is.null(check) &&
    identical(check$errors, 0L) && identical(check$warnings, 0L) &&
    length(check$unexpected_notes) == 0L && identical(check$tests$fail, 0L)
  series <- lapply(tl$extra_r_installations(ctx$root), function(inst) {
    lane_series_check(ctx, inst, tarball, remote)
  })
  r_checks <- c(list(list(version = as.character(getRversion()),
                          passed = main_passed)),
                series)
  oldrel <- lane_ci_oldrel(ctx)
  r_minimum <- lane_r_minimum(tl, ctx$root, r_checks, oldrel)
  tl$add_check(ctx$checks, "declared_r_minimum_covered_by_check",
               paste0("R (>= ", r_minimum$covered_from, ") up to ",
                      r_minimum$oldest_passing,
                      " (oldest R with a passing check)"),
               paste0("R (>= ", r_minimum$declared, ")"),
               pass = r_minimum$covered)
  tl$add_check(ctx$checks, "declared_r_minimum_admits_ci_oldrel_1",
               paste0("R (>= x) with x <= ", oldrel$version, " (",
                      oldrel$resolved_by, ")"),
               paste0("R (>= ", r_minimum$declared, ")"),
               pass = r_minimum$admits_ci_oldrel_1)
  list(
    artifact_digests = stats::setNames(list(tl$sha256_files(tarball)),
                                       tl$relative_to(tarball, ctx$root)),
    runtime = list(
      r_cmd_check_env = list(
        "_R_CHECK_FORCE_SUGGESTS_" = "false",
        "_R_CHECK_CRAN_INCOMING_REMOTE_" = remote
      ),
      extra_r_installations = lapply(series, function(x) {
        x[c("version", "home", "library")]
      })
    ),
    oracle = paste(
      "The tar listing of the tarball actually built (not the ignore",
      "patterns) against forbidden planning/work/tooling paths and required",
      "package files; a fresh install into an empty library; library()",
      "in a new R process with the full and with a dependency-only library;",
      "R CMD check --as-cran on the same tarball with the running R and with",
      "every additional R installation (its version read from the check",
      "log); the declared R minimum against the oldest passing check and the",
      "oldrel-1 job of the pinned shared CI matrix."
    ),
    tolerances = list(
      errors = 0L, warnings = 0L,
      allowed_notes = lane_note_allowlist()
    ),
    limitations = list(
      paste0("R CMD check ran with R ",
             paste(unlist(r_minimum$checked), collapse = " and "), ". A ",
             "declared minimum below the oldest passing series needs a check ",
             "with that R (an installation under .tools/R-<x.y.z> with its ",
             "library .tools/R-lib-<x.y>, or QUPFLOWR_EXTRA_R_HOMES)."),
      paste0("The oldrel-1 version of the shared CI matrix is ",
             oldrel$version, " (", oldrel$resolved_by, "); without --online ",
             "it is the value resolved on ", oldrel$resolved_on, "."),
      paste("Remote CRAN incoming checks (URLs, CRAN database) run only with",
            "--online."),
      paste("The pkgdown website build is not part of this lane; it needs",
            "network access for pkgdown's CDN assets and CI=true while",
            "pkgdown/favicon/ does not exist (README, Development).")
    ),
    details = list(
      version = version, tarball = tl$relative_to(tarball, ctx$root),
      tarball_audit = audit, load_probes = probes, r_cmd_check = check,
      r_cmd_check_other_r = series, r_minimum = r_minimum,
      build_report = tl$relative_to(file.path(build_root, "reports", "build",
                                              "package.json"), ctx$root)
    )
  )
}

# ---- L12 quality -----------------------------------------------------------

lane_quality <- function(ctx) {
  tl <- ctx$tl
  script <- lane_script(ctx, "quality.R")
  passthrough <- Sys.getenv("QUPFLOWR_CTTIR_GITHUB", unset = NA)
  env <- tl$child_env(ctx$rt, extra = c(
    QUPFLOWR_TASK_ID = ctx$task_id,
    QUPFLOWR_CTTIR_GITHUB = if (is.na(passthrough)) NA else passthrough
  ))
  reports <- list()
  for (mode in c("lint", "coverage", "api")) {
    status <- tl$run_logged(ctx$cmds, paste0("quality_", mode),
                            tl$rscript_path(),
                            c(script, "--mode", mode, "--report-dir",
                              ctx$lane_dir, "--root", ctx$root),
                            lane_log(ctx, paste0("quality-", mode, ".log")),
                            env, ctx$root, timeout = lane_timeout(ctx, 3600),
                            label = ctx$label, quiet = ctx$quiet)
    report_path <- file.path(ctx$lane_dir, paste0("L12-", mode, ".json"))
    report <- tl$read_json_file(report_path)
    reports[[mode]] <- report
    tl$add_check(ctx$checks, paste0(mode, "_exit_code"), 0L, status)
    tl$add_check(ctx$checks, paste0(mode, "_report_result"), "pass",
                 report$result)
  }
  lint <- reports$lint$details
  coverage <- reports$coverage$details
  api <- reports$api$details
  tl$add_check(ctx$checks, "lint_lintr_config_shared_or_reviewed", TRUE,
               isTRUE(lint$lintr_config_matches_shared) ||
                 isTRUE(lint$lintr_config_divergence_reviewed))
  tl$add_check(ctx$checks, "lint_suppression_problems", 0L,
               length(lint$suppression_problems))
  tl$add_check(ctx$checks, "coverage_baseline_valid", TRUE,
               isTRUE(coverage$baseline_valid))
  tl$add_check(ctx$checks, "coverage_run_test_failures", 0L,
               coverage$tests$fail)
  tl$add_check(ctx$checks, "api_inventory_integrity_problems", 0L,
               length(api$integrity_problems),
               pass = is.list(api) && length(api$integrity_problems) == 0L &&
                 !is.null(api$inventory_sha256))
  list(
    oracle = paste(
      "tools/quality.R: the pinned CTTIR lint workflow's linter set plus the",
      "repository .lintr set with zero findings; covr tests-only coverage",
      "against the floor and the committed baseline; the API inventory",
      "baseline tests/api/api-inventory.json."
    ),
    tolerances = list(lint_findings = 0L, lint_suppression_problems = 0L,
                      coverage_floor_percent = 90,
                      baseline_tolerance_percent = 0.5,
                      coverage_run_test_failures = 0L),
    limitations = list("Java and browser coverage are reported separately."),
    logs = as.list(tl$relative_to(Sys.glob(file.path(ctx$lane_dir,
                                                     "L12-*.json")),
                                  ctx$root)),
    details = list(
      lint = list(result = reports$lint$result,
                  finding_count = lint$finding_count,
                  counts_by_set = lint$counts_by_set,
                  lintr_config_matches_shared =
                    lint$lintr_config_matches_shared,
                  suppression_problems = lint$suppression_problems,
                  tool_scripts = lint$tool_scripts),
      coverage = list(result = reports$coverage$result,
                      percent = coverage$percent,
                      baseline_percent = coverage$baseline_percent,
                      baseline_valid = coverage$baseline_valid,
                      tests = coverage$tests,
                      files_below_floor = coverage$files_below_floor,
                      problems = reports$coverage$logs),
      api = list(result = reports$api$result,
                 exit_codes = reports$api$exit_codes,
                 inventory_sha256 = api$inventory_sha256,
                 integrity_problems = api$integrity_problems)
    )
  )
}
