# Shared helpers of the bridge smoke scripts (bash; sourced, not executed).
#
# Isolation rules for every child process:
#   * env -i with an explicit environment; never DISPLAY=:0/:1 or WAYLAND_DISPLAY; no user D-Bus;
#   * HOME and XDG_{CONFIG,CACHE,DATA,STATE}_HOME inside the smoke work directory;
#   * a short private TMPDIR (/tmp/qf-smoke-XXXXXX, removed on exit) holding XDG_RUNTIME_DIR;
#   * -Duser.home, -Djava.util.prefs.userRoot and -Djava.io.tmpdir inside work/temp directories.
# Only processes started by the script are signalled, and only after re-checking PID, start time
# and command line.

set -uo pipefail

SMOKE_SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMOKE_BRIDGE="$(cd "$SMOKE_SCRIPTS/.." && pwd)"
SMOKE_REPO="$(cd "$SMOKE_BRIDGE/../.." && pwd)"

# Toolchain (JAVA_HOME, QUPFLOWR_QUPATH_HOME, QUPFLOWR_XVFB, ...) from the repository root.
# Values the caller already exported win, so tools/validate.R --java-home and --qupath-home reach
# the smoke scripts; dev/toolchain.env only fills the variables that are unset or empty.
if [ ! -f "$SMOKE_REPO/dev/toolchain.env" ]; then
  echo "dev/toolchain.env not found under $SMOKE_REPO" >&2
  exit 2
fi
smoke_toolchain_vars=(JAVA_HOME GRADLE_USER_HOME QUPFLOWR_JAVACPP_CACHE QUPFLOWR_QUPATH_HOME
  QUPFLOWR_QUPATH_SRC QUPFLOWR_XVFB QUPFLOWR_XVFB_LIBS)
declare -A smoke_caller_values=()
for smoke_var in "${smoke_toolchain_vars[@]}"; do
  if [ -n "${!smoke_var:-}" ]; then
    smoke_caller_values[$smoke_var]="${!smoke_var}"
  fi
done
pushd "$SMOKE_REPO" > /dev/null || exit 2
# shellcheck disable=SC1091
. dev/toolchain.env
popd > /dev/null || exit 2
for smoke_var in "${!smoke_caller_values[@]}"; do
  export "$smoke_var=${smoke_caller_values[$smoke_var]}"
done
unset smoke_var smoke_caller_values smoke_toolchain_vars
if ! grep -q '^JAVA_VERSION="25' "$JAVA_HOME/release" 2>/dev/null; then
  echo "JAVA_HOME=$JAVA_HOME is not a Java 25 runtime" >&2
  exit 2
fi
unset DISPLAY WAYLAND_DISPLAY

SMOKE_FAILURES=0
SMOKE_CHECKS=0

# smoke_ts: local time with milliseconds (does not rely on date's %N padding)
smoke_ts() {
  local ns
  ns="$(date +%s%N)"
  printf '%s.%03d%s' "$(date -d "@${ns:0:10}" +%Y-%m-%dT%H:%M:%S)" "$((10#${ns:10:3}))" \
    "$(date +%z)"
}

smoke_log() { printf '%s %s\n' "$(smoke_ts)" "$*" | tee -a "$SMOKE_WORK/smoke.log" >&2; }

# check <name> <expected> <actual>: records PASS/FAIL
check() {
  local name="$1" expected="$2" actual="$3" result=PASS
  SMOKE_CHECKS=$((SMOKE_CHECKS + 1))
  if [ "$expected" != "$actual" ]; then
    result=FAIL
    SMOKE_FAILURES=$((SMOKE_FAILURES + 1))
  fi
  printf '%s\t%s\t%s\t%s\n' "$name" "$expected" "$actual" "$result" >> "$SMOKE_WORK/results.tsv"
  printf '%-58s %-6s expected=%s actual=%s\n' "$name" "$result" "$expected" "$actual" >&2
}

# check_true <name> <command...>: PASS if the command succeeds
check_true() {
  local name="$1"
  shift
  if "$@" > /dev/null 2>&1; then check "$name" yes yes; else check "$name" yes no; fi
}

smoke_init() { # <work dir>
  SMOKE_WORK="$1"
  case "$SMOKE_WORK" in
    /*) ;;
    *) SMOKE_WORK="$SMOKE_REPO/$SMOKE_WORK" ;;
  esac
  case "$SMOKE_WORK" in
    "$SMOKE_REPO"/work/*) ;;
    *) echo "refusing work directory outside $SMOKE_REPO/work: $SMOKE_WORK" >&2; exit 2 ;;
  esac
  rm -rf "$SMOKE_WORK"
  mkdir -p "$SMOKE_WORK"/{home,xdg/config,xdg/cache,xdg/data,xdg/state,prefs,logs,run}
  : > "$SMOKE_WORK/results.tsv"
  SMOKE_TMP="$(mktemp -d /tmp/qf-smoke-XXXXXX)"
  chmod 700 "$SMOKE_TMP"
  mkdir -p "$SMOKE_TMP/xdg-runtime"
  chmod 700 "$SMOKE_TMP/xdg-runtime"
  SMOKE_ENV=(env -i
    PATH=/usr/bin:/bin
    HOME="$SMOKE_WORK/home"
    USER="$(id -un)"
    LOGNAME="$(id -un)"
    LANG=C.UTF-8
    TMPDIR="$SMOKE_TMP"
    XDG_CONFIG_HOME="$SMOKE_WORK/xdg/config"
    XDG_CACHE_HOME="$SMOKE_WORK/xdg/cache"
    XDG_DATA_HOME="$SMOKE_WORK/xdg/data"
    XDG_STATE_HOME="$SMOKE_WORK/xdg/state"
    XDG_RUNTIME_DIR="$SMOKE_TMP/xdg-runtime"
    DBUS_SESSION_BUS_ADDRESS="unix:path=$SMOKE_TMP/xdg-runtime/no-session-bus"
    NO_AT_BRIDGE=1
    GTK_A11Y=none
    GSETTINGS_BACKEND=memory)
  SMOKE_JVM_ISOLATION=(
    "-Duser.home=$SMOKE_WORK/home"
    "-Djava.util.prefs.userRoot=$SMOKE_WORK/prefs"
    "-Djava.io.tmpdir=$SMOKE_TMP"
    "-Djna.tmpdir=$SMOKE_TMP")
  trap smoke_cleanup EXIT
  smoke_log "work=$SMOKE_WORK tmp=$SMOKE_TMP"
}

# record_process <label> <pid>: stores PID, start time and command line
record_process() {
  local label="$1" pid="$2"
  echo "$pid" > "$SMOKE_WORK/run/$label.pid"
  ps -o lstart= -p "$pid" | xargs > "$SMOKE_WORK/run/$label.start"
  tr '\0' ' ' < "/proc/$pid/cmdline" > "$SMOKE_WORK/run/$label.cmdline" 2>/dev/null
}

# same_process <label>: true if the recorded PID is alive with the recorded start time and
# command line
same_process() {
  local label="$1" pid now cmdline
  [ -f "$SMOKE_WORK/run/$label.pid" ] || return 1
  pid="$(cat "$SMOKE_WORK/run/$label.pid")"
  now="$(ps -o lstart= -p "$pid" 2>/dev/null | xargs)"
  [ -n "$now" ] && [ "$now" = "$(cat "$SMOKE_WORK/run/$label.start")" ] || return 1
  cmdline="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
  [ "$cmdline" = "$(cat "$SMOKE_WORK/run/$label.cmdline")" ]
}

# process_running <pid>: alive and not a zombie
process_running() {
  local state
  state="$(ps -o stat= -p "$1" 2>/dev/null | tr -d ' ')"
  [ -n "$state" ] && [ "${state#Z}" = "$state" ]
}

# stop_recorded <label> <signal> <wait seconds>: signals only our own recorded process
stop_recorded() {
  local label="$1" signal="$2" wait_s="$3" pid i
  same_process "$label" || return 0
  pid="$(cat "$SMOKE_WORK/run/$label.pid")"
  smoke_log "kill -$signal $pid ($label)"
  kill "-$signal" "$pid" 2>/dev/null
  for i in $(seq 1 $((wait_s * 10))); do
    process_running "$pid" || return 0
    sleep 0.1
  done
  if same_process "$label"; then
    smoke_log "kill -KILL $pid ($label) after $wait_s s"
    kill -KILL "$pid" 2>/dev/null
  fi
}

# assert_display_free <display>: refuses the user's displays and displays in use
assert_display_free() {
  local display="$1" number="${1#:}"
  case "$number" in
    ''|*[!0-9]*) echo "invalid display $display" >&2; exit 2 ;;
  esac
  if [ "$number" -lt 90 ]; then
    echo "refusing display $display: smoke tests use private displays :90 and above" >&2
    exit 2
  fi
  if [ -e "/tmp/.X11-unix/X$number" ] || [ -e "/tmp/.X$number-lock" ]; then
    echo "display $display is already in use" >&2
    exit 3
  fi
}

start_xvfb() { # <display>
  local display="$1" number="${1#:}" i
  assert_display_free "$display"
  "${SMOKE_ENV[@]}" LD_LIBRARY_PATH="$QUPFLOWR_XVFB_LIBS" "$QUPFLOWR_XVFB" "$display" \
    -screen 0 1600x1000x24 -nolisten tcp -xkbdir /usr/share/X11/xkb \
    > "$SMOKE_WORK/logs/xvfb.log" 2>&1 < /dev/null &
  local pid=$!
  for i in $(seq 1 100); do
    [ -e "/tmp/.X11-unix/X$number" ] && break
    sleep 0.1
  done
  record_process xvfb "$pid"
  SMOKE_XVFB_DISPLAY="$display"
  smoke_log "Xvfb $display pid=$pid"
  [ -e "/tmp/.X11-unix/X$number" ]
}

stop_xvfb() {
  [ -n "${SMOKE_XVFB_DISPLAY:-}" ] || return 0
  stop_recorded xvfb TERM 5
  local number="${SMOKE_XVFB_DISPLAY#:}"
  check "xvfb_socket_removed" no "$([ -e "/tmp/.X11-unix/X$number" ] && echo yes || echo no)"
  SMOKE_XVFB_DISPLAY=""
}

# sha256_of <file>
sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

# default_bundle: the bridge bundle built by ./gradlew bridgeBundle
default_bundle() {
  local dir="$SMOKE_BRIDGE/build/distributions"
  ls "$dir"/qupflowr-bridge-bundle-*.zip 2>/dev/null | head -1
}

# http_request <port> <method> <path> [token file] [extra header...]: prints "status body" with
# the body on one line. The token reaches curl through a private header file, never argv.
http_request() {
  local port="$1" method="$2" path="$3" token_file="${4:-}"
  shift 4 2>/dev/null || shift $#
  local headers="$SMOKE_TMP/headers.$$"
  : > "$headers"
  chmod 600 "$headers"
  if [ -n "$token_file" ]; then
    printf 'Authorization: Bearer %s\n' "$(tr -d '\n' < "$token_file")" > "$headers"
  fi
  local extra
  for extra in "$@"; do
    printf '%s\n' "$extra" >> "$headers"
  done
  local body="$SMOKE_TMP/body.$$"
  local status
  status="$(curl -sS --noproxy '*' --max-time 10 -o "$body" -w '%{http_code}' -X "$method" \
    -H "@$headers" "http://127.0.0.1:$port$path" 2> "$SMOKE_TMP/curl.err")"
  local rc=$?
  rm -f "$headers"
  if [ $rc -ne 0 ]; then
    echo "curl_exit_$rc"
    rm -f "$body"
    return 0
  fi
  printf '%s %s\n' "$status" "$(tr -d '\n' < "$body")"
  rm -f "$body"
}

# token_leak_count <token file> <paths...>: number of files (other than the token file) that
# contain the token. grep reads the token from the file (-f), so it never appears in argv.
token_leak_count() {
  local token_file="$1"
  shift
  grep -rlF --exclude="$(basename "$token_file")" -f "$token_file" "$@" 2>/dev/null | wc -l
}

# token_in_stream <token file>: counts lines of standard input containing the token
token_in_stream() {
  grep -cF -f "$1"
}

smoke_finish() { # <report name>
  local name="$1"
  {
    printf '{\n  "script": "%s",\n  "finished_at": "%s",\n' "$name" "$(smoke_ts)"
    printf '  "checks": %d,\n  "failures": %d,\n' "$SMOKE_CHECKS" "$SMOKE_FAILURES"
    printf '  "result": "%s",\n' "$([ "$SMOKE_FAILURES" -eq 0 ] && echo pass || echo fail)"
    printf '  "results_tsv": "%s"\n}\n' "$SMOKE_WORK/results.tsv"
  } > "$SMOKE_WORK/$name-report.json"
  smoke_log "$name: $SMOKE_CHECKS checks, $SMOKE_FAILURES failures"
  [ "$SMOKE_FAILURES" -eq 0 ]
}

smoke_cleanup() {
  local rc=$?
  for label_file in "$SMOKE_WORK"/run/*.pid; do
    [ -f "$label_file" ] || continue
    local label
    label="$(basename "$label_file" .pid)"
    if same_process "$label"; then
      smoke_log "cleanup: stopping leftover $label"
      stop_recorded "$label" TERM 10
    fi
  done
  if [ -n "${SMOKE_TMP:-}" ] && [ -d "$SMOKE_TMP" ]; then
    rm -rf "$SMOKE_TMP"
  fi
  exit $rc
}
