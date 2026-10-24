#!/usr/bin/env bash
# Cross-language contract parity of the protocol JSON rules (decision D022).
#
#   tools/bridge/scripts/contract-parity.sh [--work-dir work/<dir>] [--seed N] [--count N]
#                                           [--rejects N] [--online]
#
# Steps, all required:
#   1. R testthat for the protocol JSON contract (test-json.R, test-json-canonical.R,
#      test-json-schema.R, test-protocol-fixtures.R, test-schema-meta.R), source package.
#   2. R writes a seeded random corpus (tools/bridge/scripts/contract-parity.R; value domain
#      documented there): --count accepted documents (default 200) and --rejects documents with
#      one defect each (default 60), with R's canonical bytes or rejection code and reason.
#   3. Java: every bridge-protocol JUnit test (shared schema fixtures, canonical vectors, error-code
#      registry parity, ...) plus CanonicalJsonRParityTest on the corpus.
#   4. This script compares the R and Java results file by file (cmp) and by sha256 lists.
#
# Exit status: 0 when every step passed, 1 when a check failed, 2 for usage or environment errors.
# Results: <work>/results.tsv, <work>/contract-parity-report.json, logs in <work>/logs.
#
# Isolation: every child runs under env -i with HOME and XDG_* inside the work directory and a
# private TMPDIR (/tmp/qf-parity-XXXXXX, removed on exit); JVMs get -Duser.home,
# -Djava.util.prefs.userRoot and -Djava.io.tmpdir inside those directories; Gradle runs without a
# daemon. The R user library is only read.

set -uo pipefail

SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRIDGE="$(cd "$SCRIPTS/.." && pwd)"
REPO="$(cd "$BRIDGE/../.." && pwd)"

WORK="$REPO/work/contract-parity"
SEED=20260915
COUNT=200
REJECTS=60
OFFLINE=--offline

usage() {
  sed -n '2,4p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --work-dir) [ $# -ge 2 ] || usage; WORK="$2"; shift 2 ;;
    --seed) [ $# -ge 2 ] || usage; SEED="$2"; shift 2 ;;
    --count) [ $# -ge 2 ] || usage; COUNT="$2"; shift 2 ;;
    --rejects) [ $# -ge 2 ] || usage; REJECTS="$2"; shift 2 ;;
    --online) OFFLINE=""; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

for value in "$SEED" "$COUNT" "$REJECTS"; do
  case "$value" in
    ''|*[!0-9]*) echo "--seed, --count and --rejects take non-negative integers" >&2; exit 2 ;;
  esac
done
if [ "$COUNT" -lt 200 ]; then
  echo "--count must be at least 200 (the Java parity test requires it)" >&2
  exit 2
fi

case "$WORK" in
  /*) ;;
  *) WORK="$REPO/$WORK" ;;
esac
case "$WORK" in
  "$REPO"/work/?*) ;;
  *) echo "refusing work directory outside $REPO/work: $WORK" >&2; exit 2 ;;
esac
case "$WORK" in
  *..*) echo "refusing work directory with '..': $WORK" >&2; exit 2 ;;
esac

# Toolchain variables (JAVA_HOME, GRADLE_USER_HOME) from the repository root; values the caller
# already exported win (tools/validate.R --java-home), dev/toolchain.env fills the unset ones.
if [ ! -f "$REPO/dev/toolchain.env" ]; then
  echo "dev/toolchain.env not found under $REPO" >&2
  exit 2
fi
declare -A caller_values=()
for var in JAVA_HOME GRADLE_USER_HOME; do
  if [ -n "${!var:-}" ]; then
    caller_values[$var]="${!var}"
  fi
done
pushd "$REPO" > /dev/null || exit 2
# shellcheck disable=SC1091
. dev/toolchain.env
popd > /dev/null || exit 2
for var in "${!caller_values[@]}"; do
  export "$var=${caller_values[$var]}"
done
if ! grep -q '^JAVA_VERSION="25' "$JAVA_HOME/release" 2>/dev/null; then
  echo "JAVA_HOME=$JAVA_HOME is not a Java 25 runtime" >&2
  exit 2
fi

RSCRIPT="$(command -v Rscript || true)"
if [ -z "$RSCRIPT" ]; then
  echo "Rscript not found on PATH" >&2
  exit 2
fi
if [ ! -x "$JAVA_HOME/bin/java" ] || [ ! -x "$BRIDGE/gradlew" ]; then
  echo "Java toolchain ($JAVA_HOME) or Gradle wrapper not found" >&2
  exit 2
fi
for tool in cmp sha256sum; do
  command -v "$tool" > /dev/null || { echo "$tool not found" >&2; exit 2; }
done

rm -rf "$WORK"
mkdir -p "$WORK"/{home,prefs,logs,xdg/config,xdg/cache,xdg/data,xdg/state}
TMP="$(mktemp -d /tmp/qf-parity-XXXXXX)"
chmod 700 "$TMP"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT
CORPUS="$WORK/corpus"
: > "$WORK/results.tsv"

BASE_ENV=(
  PATH="$(dirname "$RSCRIPT"):/usr/bin:/bin"
  HOME="$WORK/home"
  LANG=C.UTF-8
  LC_ALL=C.UTF-8
  TMPDIR="$TMP"
  XDG_CONFIG_HOME="$WORK/xdg/config"
  XDG_CACHE_HOME="$WORK/xdg/cache"
  XDG_DATA_HOME="$WORK/xdg/data"
  XDG_STATE_HOME="$WORK/xdg/state"
)
# R reads the caller's libraries (never writes to them); %p and %v are expanded by R itself.
R_ENV=(env -i "${BASE_ENV[@]}"
  R_LIBS_USER="${R_LIBS_USER:-$HOME/R/%p-library/%v}"
  R_PROFILE_USER=/dev/null
  R_ENVIRON_USER=/dev/null
  NOT_CRAN=true
  QF_REPO="$REPO")
if [ -n "${R_LIBS:-}" ]; then
  R_ENV+=(R_LIBS="$R_LIBS")
fi
# JVM isolation for the Gradle client (GRADLE_OPTS) and daemon (org.gradle.jvmargs). Both split
# their value at white space: gradlew evaluates GRADLE_OPTS with shell quoting rules and Gradle
# reads org.gradle.jvmargs with its own tokenizer that honours double quotes, so every option is
# double-quoted (paths with spaces work; a double quote, backslash, dollar or backtick in a path is
# refused below).
case "$WORK$TMP" in
  *[\"\\\$\`]*) echo "work directory must not contain \", \\, \$ or backquotes: $WORK" >&2; exit 2 ;;
esac
JVM_ISOLATION="\"-Duser.home=$WORK/home\" \"-Djava.util.prefs.userRoot=$WORK/prefs\""
JVM_ISOLATION="$JVM_ISOLATION \"-Djava.io.tmpdir=$TMP\""
GRADLE_ENV=(env -i "${BASE_ENV[@]}"
  PATH="$JAVA_HOME/bin:/usr/bin:/bin"
  JAVA_HOME="$JAVA_HOME"
  GRADLE_USER_HOME="$GRADLE_USER_HOME"
  GRADLE_OPTS="$JVM_ISOLATION")

FAILURES=0
CHECKS=0
STEP_JSON=()

# check <name> <expected> <actual>
check() {
  local result=PASS
  CHECKS=$((CHECKS + 1))
  if [ "$2" != "$3" ]; then
    result=FAIL
    FAILURES=$((FAILURES + 1))
  fi
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$result" >> "$WORK/results.tsv"
  printf '%-52s %-4s expected=%s actual=%s\n' "$1" "$result" "$2" "$3" >&2
}

json_string() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/\\t}"
  s="${s//$'\n'/\\n}"
  printf '"%s"' "$s"
}

# run_step <name> <log> <command...>: runs a command, records its exit code
run_step() {
  local name="$1" log="$2" status
  shift 2
  echo "== $name" >&2
  "$@" > "$log" 2>&1
  status=$?
  STEP_JSON+=("{\"step\":$(json_string "$name"),\"exit_code\":$status,"\
"\"log\":$(json_string "${log#"$REPO"/}")}")
  check "$name exit code" 0 "$status"
  return "$status"
}

# ---- 1. R tests ---------------------------------------------------------------------------------
R_TESTS='
res <- testthat::test_dir(
  file.path(Sys.getenv("QF_REPO"), "tests", "testthat"),
  filter = "^(json|json-canonical|json-schema|protocol-fixtures|schema-meta)$",
  load_package = "source", stop_on_failure = TRUE,
  reporter = testthat::SummaryReporter$new(show_praise = FALSE)
)
df <- as.data.frame(res)
cat(sprintf("R_TEST_SUMMARY files=%d tests=%d expectations=%d failed=%d errors=%d skipped=%d\n",
            length(unique(df$file)), nrow(df), sum(df$nb), sum(df$failed),
            sum(df$error), sum(df$skipped)))
'
run_step "R testthat (protocol JSON contract)" "$WORK/logs/r-tests.log" \
  "${R_ENV[@]}" "$RSCRIPT" -e "$R_TESTS"
R_SUMMARY="$(grep -o 'R_TEST_SUMMARY.*' "$WORK/logs/r-tests.log" | tail -1)"
r_field() { printf '%s\n' "$R_SUMMARY" | tr ' ' '\n' | sed -n "s/^$1=//p"; }
check "R test files run" 5 "$(r_field files)"
check "R failed expectations" 0 "$(r_field failed)"
check "R errors" 0 "$(r_field errors)"

# ---- 2. R corpus --------------------------------------------------------------------------------
run_step "R corpus (seed $SEED)" "$WORK/logs/r-corpus.log" \
  "${R_ENV[@]}" "$RSCRIPT" "$SCRIPTS/contract-parity.R" --root "$REPO" --out "$CORPUS" \
  --seed "$SEED" --count "$COUNT" --rejects "$REJECTS"

# ---- 3. Java tests ------------------------------------------------------------------------------
RESULTS_DIR="$BRIDGE/bridge-protocol/build/test-results/test"
rm -rf "$RESULTS_DIR"
# shellcheck disable=SC2086
run_step "Java bridge-protocol tests" "$WORK/logs/gradle.log" \
  "${GRADLE_ENV[@]}" "$BRIDGE/gradlew" -p "$BRIDGE" $OFFLINE --no-daemon \
  "-Dorg.gradle.jvmargs=$JVM_ISOLATION -Xmx1g" \
  :bridge-protocol:test --rerun "-Pqupflowr.parityDir=$CORPUS"

junit_sum() { # <attribute> [file glob]
  local total=0 value file
  for file in "$RESULTS_DIR"/${2:-TEST-*.xml}; do
    [ -f "$file" ] || continue
    value="$(sed -n "s/.*<testsuite [^>]*$1=\"\([0-9]*\)\".*/\1/p" "$file" | head -1)"
    total=$((total + ${value:-0}))
  done
  echo "$total"
}
JAVA_TESTS="$(junit_sum tests)"
check "Java failures" 0 "$(junit_sum failures)"
check "Java errors" 0 "$(junit_sum errors)"
check "Java skipped" 0 "$(junit_sum skipped)"
check "Java schema corpus test ran" yes \
  "$([ "$(junit_sum tests '*SharedSchemaCorpusTest.xml')" -ge 100 ] && echo yes || echo no)"
check "Java canonical vectors test ran" yes \
  "$([ "$(junit_sum tests '*CanonicalJsonVectorsTest.xml')" -ge 18 ] && echo yes || echo no)"
check "Java registry parity test ran" yes \
  "$([ "$(junit_sum tests '*ErrorCodeRegistryParityTest.xml')" -ge 3 ] && echo yes || echo no)"
check "Java R parity test ran" 1 "$(junit_sum tests '*CanonicalJsonRParityTest.xml')"

# ---- 4. byte comparison -------------------------------------------------------------------------
compared=0
mismatched=0
missing=0
accepted=0
rejected=0
if [ -d "$CORPUS/input" ]; then
  for input in "$CORPUS"/input/doc-*.json; do
    [ -f "$input" ] || continue
    stem="$(basename "$input" .json)"
    compared=$((compared + 1))
    if [ -f "$CORPUS/r/$stem.canonical" ]; then
      accepted=$((accepted + 1))
      if [ ! -f "$CORPUS/java/$stem.canonical" ]; then
        missing=$((missing + 1))
      elif ! cmp -s "$CORPUS/r/$stem.canonical" "$CORPUS/java/$stem.canonical"; then
        mismatched=$((mismatched + 1))
        echo "mismatch: $stem" >> "$WORK/logs/compare.log"
      fi
    elif [ -f "$CORPUS/r/$stem.reject" ]; then
      rejected=$((rejected + 1))
      if [ ! -f "$CORPUS/java/$stem.reject" ]; then
        missing=$((missing + 1))
      elif ! cmp -s "$CORPUS/r/$stem.reject" "$CORPUS/java/$stem.reject"; then
        mismatched=$((mismatched + 1))
        echo "mismatch: $stem" >> "$WORK/logs/compare.log"
      fi
    else
      missing=$((missing + 1))
    fi
  done
  (cd "$CORPUS/r" && sha256sum -- * > "$WORK/r.sha256") 2>/dev/null
  (cd "$CORPUS/java" && sha256sum -- * > "$WORK/java.sha256") 2>/dev/null
fi
check "corpus documents compared" "$((COUNT + REJECTS))" "$compared"
check "accepted documents" "$COUNT" "$accepted"
check "rejected documents" "$REJECTS" "$rejected"
check "documents without a Java or R result" 0 "$missing"
check "R/Java byte or reason mismatches" 0 "$mismatched"
check "sha256 lists of R and Java results identical" yes \
  "$(cmp -s "$WORK/r.sha256" "$WORK/java.sha256" 2> /dev/null && echo yes || echo no)"

# ---- report -------------------------------------------------------------------------------------
RESULT=pass
[ "$FAILURES" -eq 0 ] || RESULT=fail
{
  printf '{\n'
  printf '  "report": "contract-parity",\n'
  printf '  "result": "%s",\n' "$RESULT"
  printf '  "generated_at": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  "seed": %s,\n  "accepted": %s,\n  "rejected": %s,\n' "$SEED" "$accepted" "$rejected"
  printf '  "mismatches": %s,\n  "missing": %s,\n' "$mismatched" "$missing"
  printf '  "r_tests": %s,\n' "$(json_string "$R_SUMMARY")"
  printf '  "java_tests": %s,\n' "${JAVA_TESTS:-0}"
  printf '  "checks": %s,\n  "failed_checks": %s,\n' "$CHECKS" "$FAILURES"
  printf '  "steps": [%s],\n' "$(IFS=,; echo "${STEP_JSON[*]}")"
  printf '  "results": %s\n' "$(json_string "${WORK#"$REPO"/}/results.tsv")"
  printf '}\n'
} > "$WORK/contract-parity-report.json"

echo "contract-parity: $RESULT ($((CHECKS - FAILURES))/$CHECKS checks passed)" >&2
[ "$FAILURES" -eq 0 ] || exit 1
exit 0
