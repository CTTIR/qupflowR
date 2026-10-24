#!/usr/bin/env bash
# Live smoke test of the qupflowR worker distribution (no display).
#
#   tools/bridge/scripts/smoke-worker.sh [--work-dir work/...] [--bundle <zip>]
#       [--skip-script-profile]
#
# 1. verifies the bridge bundle manifest, the worker zip digest, checksums.sha256 and launch.json;
# 2. java profile, built only from launch.json: preflight --version, start, ready line, manifest,
#    GET /v1/health with the token (401 without), stdout limited to event lines, token hygiene,
#    end of standard input -> exit 0 and discovery files removed; second start with SIGTERM -> 143;
# 3. qupath-script profile through QuPath's own "script" command (qupath.QuPath main class started
#    with the Temurin launcher, because the jpackage launcher crashes on this host, JDK-8380085)
#    with the sha256-checked Groovy bootstrap and JAVA_TOOL_OPTIONS; a wrong digest is refused;
# 4. records the official launcher preflight (bin/QuPath --version) as information.
# Exits non-zero if any assertion fails or a scenario is incomplete.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "$SCRIPT_DIR/smoke-common.sh"

work="work/t01/C1/smoke-worker"
bundle=""
script_profile=yes
while [ $# -gt 0 ]; do
  case "$1" in
    --work-dir) work="$2"; shift 2 ;;
    --bundle) bundle="$2"; shift 2 ;;
    --skip-script-profile) script_profile=no; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
smoke_init "$work"
[ -n "$bundle" ] || bundle="$(default_bundle)"
if [ ! -f "$bundle" ]; then
  smoke_log "bridge bundle not found; build it with ./gradlew bridgeBundle"
  check bundle_present yes no
  smoke_finish smoke-worker
  exit 1
fi
qupath_home="$QUPFLOWR_QUPATH_HOME"

# ------------------------------------------------------------------ distribution verification
mkdir -p "$SMOKE_WORK/bundle" "$SMOKE_WORK/dist"
unzip -q -o "$bundle" -d "$SMOKE_WORK/bundle"
bundle_manifest="$SMOKE_WORK/bundle/manifest.json"
worker_member() { # <field>
  jq -r --arg f "$1" '.members[] | select(.role == "worker_distribution") | .[$f]' \
    "$bundle_manifest"
}
zip_member="$(worker_member path)"
dist_root="$(worker_member root)"
check worker_zip_sha256 "$(worker_member sha256)" "$(sha256_of "$SMOKE_WORK/bundle/$zip_member")"
unzip -q -o "$SMOKE_WORK/bundle/$zip_member" -d "$SMOKE_WORK/dist"
dist="$SMOKE_WORK/dist/$dist_root"
launch="$dist/launch.json"
check_true checksums_verify bash -c "cd '$dist' && sha256sum --quiet -c checksums.sha256"
check checksums_cover_all_files "$(cd "$dist" && find . -type f ! -name checksums.sha256 | wc -l)" \
  "$(wc -l < "$dist/checksums.sha256")"
check launch_qupath_version 0.7.0 "$(jq -r .qupath_version "$launch")"
for member in worker_jar bootstrap; do
  member_path="$(jq -r --arg m "$member" '.[$m].path' "$launch")"
  check "launch_${member}_sha256" "$(jq -r --arg m "$member" '.[$m].sha256' "$launch")" \
    "$(sha256_of "$dist/$member_path")"
done
check launch_profiles "java,qupath-script" "$(jq -r '[.profiles[].id] | join(",")' "$launch")"

# profile_contains <profile> <member> <value>: prints yes if the profile's array contains value
profile_contains() {
  jq -r --arg p "$1" --arg m "$2" --arg v "$3" \
    '.profiles[] | select(.id == $p) | if (.[$m] // .environment[$m] | index($v)) != null
       then "yes" else "no" end' "$launch"
}
check launch_script_profile_heap_option yes \
  "$(profile_contains qupath-script jvm_options '-Xmx<HEAP>')"
for option in -Djava.awt.headless=true -Dprism.order=sw \
  '-Dorg.bytedeco.javacpp.cachedir=<JAVACPP_CACHE_DIR>'; do
  check "launch_java_profile_has_$option" yes "$(profile_contains java jvm_options "$option")"
done
for variable in DISPLAY WAYLAND_DISPLAY; do
  check "launch_removes_$variable" yes "$(profile_contains java remove "$variable")"
done
worker_jar="$dist/$(jq -r .worker_jar.path "$launch")"
worker_sha="$(jq -r .worker_jar.sha256 "$launch")"

# expand <text>: replaces launch.json placeholders for one worker run (uses run_* variables)
expand() {
  local text="$1"
  text="${text//<DIST>/$dist}"
  text="${text//<QUPATH_HOME>/$qupath_home}"
  text="${text//<JAVA>/$JAVA_HOME/bin/java}"
  text="${text//<PREFS_DIR>/$run_prefs}"
  text="${text//<TMP_DIR>/$SMOKE_TMP}"
  text="${text//<JAVACPP_CACHE_DIR>/$QUPFLOWR_JAVACPP_CACHE}"
  text="${text//<RUNTIME_DIR>/$run_runtime}"
  text="${text//<HEAP>/1g}"
  text="${text//<WORKER_JAR_SHA256>/$run_sha}"
  printf '%s' "$text"
}

# java_tool_options <option...>: the options for JAVA_TOOL_OPTIONS as launch.json's
# java_tool_options_quoting describes: each in single quotes (double quotes when it contains a
# single quote), joined with single spaces. Fails for an option containing both quote characters.
java_tool_options() {
  local option joined="" quoted
  for option in "$@"; do
    case "$option" in
      *\'*\"*|*\"*\'*) echo "option with both quote characters: $option" >&2; return 1 ;;
      *\'*) quoted="\"$option\"" ;;
      *) quoted="'$option'" ;;
    esac
    joined="${joined:+$joined }$quoted"
  done
  printf '%s' "$joined"
}

# build_command <profile>: fills the array "command" from launch.json
build_command() {
  local profile="$1" element option entry
  local -a options=() classpath=()
  while IFS= read -r option; do options+=("$(expand "$option")"); done \
    < <(jq -r --arg p "$profile" '.profiles[] | select(.id == $p) | .jvm_options[]' "$launch")
  while IFS= read -r entry; do classpath+=("$(expand "$entry")"); done \
    < <(jq -r '.classpath[]' "$launch")
  jvm_options_joined="$(java_tool_options "${options[@]}")"
  command=()
  while IFS= read -r element; do
    case "$element" in
      "<JVM_OPTIONS>") command+=("${options[@]}" "-Duser.home=$SMOKE_WORK/home") ;;
      "<CLASSPATH>") command+=("$(IFS=:; echo "${classpath[*]}")") ;;
      *) command+=("$(expand "$element")") ;;
    esac
  done < <(jq -r --arg p "$profile" '.profiles[] | select(.id == $p) | .command[]' "$launch")
}

# await_event <stdout file> <pid> <timeout s>: prints the first event line
await_event() {
  local out="$1" pid="$2" timeout_s="$3" i line
  for i in $(seq 1 $((timeout_s * 10))); do
    line="$(grep -m1 '^{"event":' "$out" 2>/dev/null)"
    if [ -n "$line" ]; then printf '%s' "$line"; return 0; fi
    process_running "$pid" || break
    sleep 0.1
  done
  grep -m1 '^{"event":' "$out" 2>/dev/null
}

# run_worker <label> <stop: eof|sigterm> <profile>: one complete worker life cycle with assertions
run_worker() {
  local label="$1" stop="$2" profile="$3"
  run_prefs="$SMOKE_WORK/prefs-$label"
  run_runtime="$SMOKE_WORK/runtime-$label"
  run_sha="$worker_sha"
  local out="$SMOKE_WORK/logs/$label.stdout" err="$SMOKE_WORK/logs/$label.stderr"
  local fifo="$SMOKE_TMP/$label.stdin"
  mkdir -p "$run_prefs"
  build_command "$profile"
  local -a launcher=()
  if [ "$profile" = qupath-script ]; then
    local -a cfg_options=()
    mapfile -t cfg_options < <(sed -n 's/^java-options=//p' "$qupath_home/lib/app/QuPath.cfg")
    # QuPath's launcher would run "qupath.QuPath <arguments>" with the QuPath.cfg options; the
    # jpackage binary is replaced by the Temurin java launcher on this host (JDK-8380085).
    launcher=("${SMOKE_ENV[@]}" JAVA_TOOL_OPTIONS="$jvm_options_joined $(java_tool_options "-Duser.home=$SMOKE_WORK/home")"
      "$JAVA_HOME/bin/java" "${cfg_options[@]}" -cp "$qupath_home/lib/app/*" qupath.QuPath
      "${command[@]:1}")
  else
    launcher=("${SMOKE_ENV[@]}" "${command[@]}")
  fi
  printf '%q ' "${launcher[@]}" > "$SMOKE_WORK/run/$label.command"
  mkfifo "$fifo"
  local started_ns
  started_ns="$(date +%s%N)"
  "${launcher[@]}" < "$fifo" > "$out" 2> "$err" &
  local pid=$!
  exec 7> "$fifo"
  sleep 0.3
  record_process "$label" "$pid"
  local event
  event="$(await_event "$out" "$pid" 90)"
  local ready_ms=$(( ($(date +%s%N) - started_ns) / 1000000 ))
  check "${label}_ready_event" ready "$(jq -r .event <<< "$event" 2>/dev/null)"
  check "${label}_ready_fields" "event,instance_id,manifest" \
    "$(jq -r 'keys_unsorted | join(",")' <<< "$event" 2>/dev/null)"
  local manifest token_file port instance status body
  manifest="$(jq -r .manifest <<< "$event" 2>/dev/null)"
  if [ ! -f "$manifest" ]; then
    check "${label}_manifest_present" yes no
    exec 7>&-
    stop_recorded "$label" TERM 15
    wait "$pid" 2>/dev/null
    return
  fi
  token_file="$(jq -r .token_file "$manifest")"
  port="$(jq -r .port "$manifest")"
  instance="$(jq -r .instance_id "$manifest")"
  smoke_log "$label ready after ${ready_ms} ms pid=$pid port=$port"
  echo "$ready_ms" > "$SMOKE_WORK/run/$label.ready_ms"
  check "${label}_manifest_pid" "$pid" "$(jq -r .pid "$manifest")"
  check "${label}_manifest_host_type" worker "$(jq -r .host_type "$manifest")"
  check "${label}_manifest_instance" "$(jq -r .instance_id <<< "$event")" "$instance"
  check "${label}_mode_manifest" 600 "$(stat -c %a "$manifest")"
  check "${label}_mode_token" 600 "$(stat -c %a "$token_file")"
  read -r status body <<< "$(http_request "$port" GET /v1/health "$token_file")"
  check "${label}_health_status" 200 "$status"
  check "${label}_health_host_type" worker "$(jq -r .data.host_type <<< "$body")"
  check "${label}_health_qupath_version" 0.7.0 "$(jq -r .data.qupath_version <<< "$body")"
  check "${label}_health_fx_thread_blocked" null "$(jq -r .data.fx_thread_blocked <<< "$body")"
  read -r status body <<< "$(http_request "$port" GET /v1/health "")"
  check "${label}_health_without_token" 401 "$status"
  check "${label}_environment_display" 0 \
    "$(tr '\0' '\n' < "/proc/$pid/environ" | grep -cE '^(DISPLAY|WAYLAND_DISPLAY)=')"
  local token_copy="$SMOKE_TMP/$label.token-copy"
  (umask 077 && cp "$token_file" "$token_copy")
  check "${label}_token_in_command_line" 0 \
    "$(tr '\0' '\n' < "/proc/$pid/cmdline" | token_in_stream "$token_copy")"
  if [ "$profile" = java ]; then
    check "${label}_stdout_event_lines_only" 0 "$(grep -vc '^{"event":' "$out")"
    check "${label}_headless_property" 1 \
      "$(tr '\0' '\n' < "/proc/$pid/cmdline" | grep -cx -- '-Djava.awt.headless=true')"
  else
    check_true "${label}_java_tool_options_applied" grep -q "Picked up JAVA_TOOL_OPTIONS" "$err"
  fi

  local code
  if [ "$stop" = eof ]; then
    exec 7>&-
    for _ in $(seq 1 300); do process_running "$pid" || break; sleep 0.1; done
    wait "$pid"
    code=$?
    check "${label}_exit_code_stdin_closed" 0 "$code"
  else
    stop_recorded "$label" TERM 30
    exec 7>&-
    wait "$pid"
    code=$?
    check "${label}_exit_code_sigterm" 143 "$code"
  fi
  check "${label}_manifest_removed" no "$([ -e "$manifest" ] && echo yes || echo no)"
  check "${label}_token_file_removed" no "$([ -e "$token_file" ] && echo yes || echo no)"
  curl -sS --noproxy '*' --max-time 3 -o /dev/null "http://127.0.0.1:$port/v1/health" 2>/dev/null
  check "${label}_port_closed_curl_exit" 7 "$?"
  check "${label}_token_in_output" 0 "$(cat "$out" "$err" | token_in_stream "$token_copy")"
  rm -f "$token_copy"
}

# ------------------------------------------------------------------ java profile
run_prefs="$SMOKE_WORK/prefs-preflight"
run_runtime="$SMOKE_WORK/runtime-preflight"
run_sha="$worker_sha"
mkdir -p "$run_prefs"
mapfile -t preflight < <(jq -r '.profiles[] | select(.id == "java") | .preflight[0][]' "$launch")
preflight_cmd=()
for element in "${preflight[@]}"; do
  if [ "$element" = "<CLASSPATH>" ]; then
    preflight_cmd+=("$(expand "$(jq -r '.classpath | join(":")' "$launch")")")
  else
    preflight_cmd+=("$(expand "$element")")
  fi
done
preflight_out="$SMOKE_WORK/logs/preflight.stdout"
"${SMOKE_ENV[@]}" "${preflight_cmd[@]}" > "$preflight_out" 2> "$SMOKE_WORK/logs/preflight.stderr"
check java_preflight_exit_code 0 "$?"
check java_preflight_qupath_version 0.7.0 "$(jq -r .qupath_version "$preflight_out")"
check java_preflight_supported true "$(jq -r .qupath_version_supported "$preflight_out")"

run_worker java-eof eof java
run_worker java-sigterm sigterm java

# ------------------------------------------------------------------ qupath-script profile
if [ "$script_profile" = yes ]; then
  run_worker script-eof eof qupath-script
  check_true script-eof_bootstrap_returned \
    grep -q "WorkerMain.run returned 0" "$SMOKE_WORK/logs/script-eof.stderr"

  # A wrong digest must be refused before the worker is loaded.
  run_prefs="$SMOKE_WORK/prefs-script-wrong"
  run_runtime="$SMOKE_WORK/runtime-script-wrong"
  run_sha="$(printf '%s' "$worker_sha" | sed 's/^./0/; t; s/^/0/')"
  [ "$run_sha" = "$worker_sha" ] && run_sha="1${worker_sha:1}"
  mkdir -p "$run_prefs"
  build_command qupath-script
  mapfile -t cfg_options < <(sed -n 's/^java-options=//p' "$qupath_home/lib/app/QuPath.cfg")
  "${SMOKE_ENV[@]}" JAVA_TOOL_OPTIONS="$jvm_options_joined $(java_tool_options "-Duser.home=$SMOKE_WORK/home")" \
    "$JAVA_HOME/bin/java" "${cfg_options[@]}" -cp "$qupath_home/lib/app/*" qupath.QuPath \
    "${command[@]:1}" < /dev/null \
    > "$SMOKE_WORK/logs/script-wrong.stdout" 2> "$SMOKE_WORK/logs/script-wrong.stderr"
  check_true script_wrong_digest_refused grep -q "worker jar digest mismatch" \
    "$SMOKE_WORK/logs/script-wrong.stdout" "$SMOKE_WORK/logs/script-wrong.stderr"
  check script_wrong_digest_no_runtime_dir no "$([ -e "$run_runtime" ] && echo yes || echo no)"
fi

# ------------------------------------------------------------------ official launcher (information)
launcher="$qupath_home/bin/QuPath"
if [ -x "$launcher" ]; then
  (ulimit -c 0; "${SMOKE_ENV[@]}" timeout 30 "$launcher" --version) \
    > "$SMOKE_WORK/logs/launcher-preflight.out" 2>&1
  launcher_exit=$?
else
  launcher_exit=missing
fi
smoke_log "information: official launcher preflight '$launcher --version' exit=$launcher_exit" \
  "(JDK-8380085 is expected on this host)"

ready_ms() { cat "$SMOKE_WORK/run/$1.ready_ms" 2>/dev/null || echo null; }
{
  echo "{"
  echo "  \"worker_jar_sha256\": \"$worker_sha\","
  echo "  \"java_eof_ready_ms\": $(ready_ms java-eof),"
  echo "  \"java_sigterm_ready_ms\": $(ready_ms java-sigterm),"
  echo "  \"script_ready_ms\": $(ready_ms script-eof),"
  echo "  \"official_launcher_preflight_exit\": \"$launcher_exit\""
  echo "}"
} > "$SMOKE_WORK/worker-summary.json"
smoke_finish smoke-worker
