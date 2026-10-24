#!/usr/bin/env bash
# Live smoke test of the qupflowR QuPath extension on a private Xvfb display.
#
#   tools/bridge/scripts/smoke-extension.sh [--work-dir work/...] [--display :90]
#       [--bundle <zip>] [--attempts 4] [--simulate-missing-extension]
#
# 1. verifies the bridge bundle (manifest.json sha256 of every member) and installs the extension
#    jar into an isolated QuPath user directory (userPath seeded into an isolated prefs root);
# 2. starts Xvfb and QuPath 0.7.0 with the Temurin launcher (java -cp "<QUPATH_HOME>/lib/app/*"
#    qupath.QuPath, JVM options from QuPath.cfg) and -Dqupflowr.autostart=true;
# 3. retries with backoff when QuPath silently skips the extension directory (inotify exhausted),
#    the extension is not loaded or no manifest appears, and reports the attempts
#    (--simulate-missing-extension hides the jar in the first attempt to exercise this path);
# 4. checks the discovery files, GET /v1/health and /v1/capabilities with the token, 401/403
#    without it, the menu and token hygiene;
# 5. stops QuPath with SIGTERM (exit 143, discovery files removed) and Xvfb.
# Exits non-zero if any assertion fails or the scenario is incomplete.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=smoke-common.sh
. "$SCRIPT_DIR/smoke-common.sh"

work="work/t01/C1/smoke-extension"
display=":90"
bundle=""
attempts=4
simulate_missing=no
while [ $# -gt 0 ]; do
  case "$1" in
    --simulate-missing-extension) simulate_missing=yes; shift ;;
    --work-dir) work="$2"; shift 2 ;;
    --display) display="$2"; shift 2 ;;
    --bundle) bundle="$2"; shift 2 ;;
    --attempts) attempts="$2"; shift 2 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
assert_display_free "$display"
smoke_init "$work"
[ -n "$bundle" ] || bundle="$(default_bundle)"
if [ ! -f "$bundle" ]; then
  smoke_log "bridge bundle not found; build it with ./gradlew bridgeBundle"
  check bundle_present yes no
  smoke_finish smoke-extension
  exit 1
fi

qupath_home="$QUPFLOWR_QUPATH_HOME"
userdir="$SMOKE_WORK/qupath-user"
runtime="$SMOKE_WORK/runtime"

# ------------------------------------------------------------------ bundle verification and install
mkdir -p "$SMOKE_WORK/bundle" "$userdir/extensions"
unzip -q -o "$bundle" -d "$SMOKE_WORK/bundle"
bundle_manifest="$SMOKE_WORK/bundle/manifest.json"
ext_member="$(jq -r '.members[] | select(.role == "extension") | .path' "$bundle_manifest")"
ext_sha="$(jq -r '.members[] | select(.role == "extension") | .sha256' "$bundle_manifest")"
check bundle_extension_sha256 "$ext_sha" "$(sha256_of "$SMOKE_WORK/bundle/$ext_member")"
check bundle_qupath_version 0.7.0 "$(jq -r .qupath_version "$SMOKE_WORK/bundle/manifest.json")"
cp "$SMOKE_WORK/bundle/$ext_member" "$userdir/extensions/"
check extension_installed_sha256 "$ext_sha" "$(sha256_of "$userdir/extensions/$ext_member")"

"${SMOKE_ENV[@]}" "$JAVA_HOME/bin/java" -Djava.awt.headless=true "${SMOKE_JVM_ISOLATION[@]}" \
  "$SCRIPT_DIR/PrefsSeed.java" "$userdir" > "$SMOKE_WORK/logs/prefs-seed.txt" 2>&1
check prefs_seed_user_path "userPath=$userdir" \
  "$(grep '^userPath=' "$SMOKE_WORK/logs/prefs-seed.txt")"
# java.util.prefs encodes node names such as "0.7", so look for the entry itself.
prefs_entry="key=\"userPath\" value=\"$userdir\""
check prefs_written_in_isolated_root 1 \
  "$(grep -rlF "$prefs_entry" "$SMOKE_WORK/prefs" --include=prefs.xml | wc -l)"

# ------------------------------------------------------------------ QuPath launch with retry
start_xvfb "$display" || { check xvfb_started yes no; smoke_finish smoke-extension; exit 1; }
cfg="$qupath_home/lib/app/QuPath.cfg"
mapfile -t cfg_options < <(sed -n 's/^java-options=//p' "$cfg")
qupath_cmd=("${SMOKE_ENV[@]}" DISPLAY="$display" "$JAVA_HOME/bin/java" "${cfg_options[@]}" -Xmx4g
  "${SMOKE_JVM_ISOLATION[@]}" -Dprism.order=sw
  "-Dlogback.configurationFile=$SCRIPT_DIR/logback-smoke.xml"
  "-Dorg.bytedeco.javacpp.cachedir=$QUPFLOWR_JAVACPP_CACHE"
  -Dqupflowr.autostart=true "-Dqupflowr.runtimeDir=$runtime"
  -cp "$qupath_home/lib/app/*" qupath.QuPath --quiet)
printf '%q ' "${qupath_cmd[@]}" > "$SMOKE_WORK/run/qupath.command"

inotify_instances() { find /proc/[0-9]*/fd -lname 'anon_inode:inotify' 2>/dev/null | wc -l; }

attempts_tsv="$SMOKE_WORK/launch-attempts.tsv"
printf 'attempt\tpid\toutcome\tinotify_fds_before\n' > "$attempts_tsv"
launched=no
skips=0
pause=10
qupath_log=""
for n in $(seq 1 "$attempts"); do
  qupath_log="$SMOKE_WORK/logs/qupath-a$n.log"
  rm -rf "$runtime"
  before="$(inotify_instances)"
  if [ "$simulate_missing" = yes ] && [ "$n" -eq 1 ]; then
    mv "$userdir/extensions/$ext_member" "$SMOKE_WORK/run/"
  elif [ -f "$SMOKE_WORK/run/$ext_member" ]; then
    mv "$SMOKE_WORK/run/$ext_member" "$userdir/extensions/"
  fi
  "${qupath_cmd[@]}" > "$qupath_log" 2>&1 < /dev/null &
  pid=$!
  sleep 0.5
  record_process qupath "$pid"
  echo "$qupath_log" > "$SMOKE_WORK/run/qupath.log"
  outcome=timeout
  watching_since=""
  for i in $(seq 1 480); do
    if grep -q "Installing qupflowR extension" "$qupath_log"; then outcome=installed; break; fi
    if grep -q "Error when creating files watcher" "$qupath_log"; then
      outcome=inotify_skip
      break
    fi
    process_running "$pid" || { outcome=jvm_exited; break; }
    # The extension manager watches the directory but our extension did not install within 20 s.
    if [ -z "$watching_since" ] \
      && grep -q "resetting directory watcher to watch $userdir/extensions" "$qupath_log"; then
      watching_since=$i
    fi
    if [ -n "$watching_since" ] && [ $((i - watching_since)) -ge 80 ]; then
      outcome=extension_not_loaded
      break
    fi
    sleep 0.25
  done
  if [ "$outcome" = installed ]; then
    outcome=no_manifest
    for _ in $(seq 1 240); do
      if grep -qs "\"pid\": $pid," "$runtime"/instances/*.json; then outcome=manifest_ok; break; fi
      process_running "$pid" || { outcome=jvm_exited_after_install; break; }
      sleep 0.25
    done
  fi
  printf '%s\t%s\t%s\t%s\n' "$n" "$pid" "$outcome" "$before" >> "$attempts_tsv"
  smoke_log "launch attempt $n pid=$pid outcome=$outcome inotify_fds_before=$before"
  if [ "$outcome" = manifest_ok ]; then launched=yes; break; fi
  [ "$outcome" = inotify_skip ] && skips=$((skips + 1))
  stop_recorded qupath TERM 30
  wait "$pid" 2>/dev/null
  if [ "$n" -lt "$attempts" ]; then
    sleep "$pause"
    pause=$((pause * 2 > 60 ? 60 : pause * 2))
  fi
done
check qupath_launched_with_manifest yes "$launched"
smoke_log "launch attempts=$n inotify_skips=$skips"
if [ "$simulate_missing" = yes ]; then
  check simulated_first_attempt_outcome extension_not_loaded \
    "$(awk -F'\t' '$1 == 1 {print $3}' "$attempts_tsv")"
  check simulated_retry_attempts 2 "$n"
fi
if [ "$launched" != yes ]; then
  stop_xvfb
  smoke_finish smoke-extension
  exit 1
fi

# ------------------------------------------------------------------ discovery files
manifest="$(grep -ls "\"pid\": $pid," "$runtime"/instances/*.json | head -1)"
token_file="$(jq -r .token_file "$manifest")"
port="$(jq -r .port "$manifest")"
instance="$(jq -r .instance_id "$manifest")"
check manifest_host_type live "$(jq -r .host_type "$manifest")"
check manifest_host 127.0.0.1 "$(jq -r .host "$manifest")"
check manifest_qupath_version 0.7.0 "$(jq -r .qupath_version "$manifest")"
check manifest_protocol_version 1.0 "$(jq -r .protocol_version "$manifest")"
check manifest_file_name "$instance.json" "$(basename "$manifest")"
check mode_runtime_dir 700 "$(stat -c %a "$runtime")"
check mode_instances_dir 700 "$(stat -c %a "$runtime/instances")"
check mode_manifest 600 "$(stat -c %a "$manifest")"
check mode_token 600 "$(stat -c %a "$token_file")"
check token_length 43 "$(tr -d '\n' < "$token_file" | wc -c)"
check token_not_in_manifest 0 "$(token_in_stream "$token_file" < "$manifest")"
check listener_ipv4_loopback "127.0.0.1:$port" \
  "$(ss -ltnH "sport = :$port" | awk '{print $4}' | head -1)"

# ------------------------------------------------------------------ HTTP
read -r status body <<< "$(http_request "$port" GET /v1/health "$token_file")"
check health_status 200 "$status"
check health_qupath_version 0.7.0 "$(jq -r .data.qupath_version <<< "$body")"
check health_host_type live "$(jq -r .data.host_type <<< "$body")"
check health_protocol_version 1.0 "$(jq -r .protocol_version <<< "$body")"
check health_instance_id "$instance" "$(jq -r .instance_id <<< "$body")"
check health_fx_thread_blocked false "$(jq -r .data.fx_thread_blocked <<< "$body")"
read -r status body <<< "$(http_request "$port" GET /v1/capabilities "$token_file")"
check capabilities_status 200 "$status"
check capabilities_available "bridge.health,bridge.capabilities" \
  "$(jq -r '[.data.capabilities[] | select(.available) | .id] | join(",")' <<< "$body")"
check capabilities_unavailable_reason_code not_implemented \
  "$(jq -r '[.data.capabilities[] | select(.available | not) | .reason_code] | unique | join(",")' \
    <<< "$body")"
read -r status body <<< "$(http_request "$port" GET /v1/health "")"
check health_without_token_status 401 "$status"
check health_without_token_code AUTH_REQUIRED "$(jq -r .error.code <<< "$body")"
printf 'wrong-token\n' > "$SMOKE_TMP/wrong.token"
read -r status body <<< "$(http_request "$port" GET /v1/health "$SMOKE_TMP/wrong.token")"
check health_wrong_token_status 401 "$status"
read -r status body \
  <<< "$(http_request "$port" GET /v1/health "$token_file" "Origin: http://127.0.0.1:$port")"
check health_origin_status 403 "$status"
read -r status body <<< "$(http_request "$port" GET /v1/health "$token_file" "Host: evil.example")"
check health_foreign_host_status 403 "$status"
check health_foreign_host_reason host_not_allowed "$(jq -r .error.details.reason <<< "$body")"

# ------------------------------------------------------------------ GUI state and hygiene
menu_line="Menu verification: found 'qupflowR' under 'Extensions' with items"
menu_line="$menu_line [Bridge starten | Bridge stoppen | --- | Status"
check_true menu_verified grep -qF "$menu_line" "$qupath_log"
check_true bridge_state_running \
  grep -qF "qupflowR bridge state: RUNNING (Status: läuft auf 127.0.0.1:$port)" "$qupath_log"
jvm_environ="$(tr '\0' '\n' < "/proc/$pid/environ")"
check jvm_environment_display "DISPLAY=$display" "$(grep '^DISPLAY=' <<< "$jvm_environ")"
check jvm_environment_wayland 0 "$(grep -c '^WAYLAND_DISPLAY=' <<< "$jvm_environ")"
check_true jvm_prefs_root_isolated \
  grep -qF "java.util.prefs.userRoot=$SMOKE_WORK/prefs" "$qupath_log"
check token_in_logs_or_work_files 0 \
  "$(token_leak_count "$token_file" "$SMOKE_WORK/logs" "$SMOKE_WORK/run" "$SMOKE_WORK/results.tsv")"
check token_in_jvm_command_line 0 \
  "$(tr '\0' '\n' < "/proc/$pid/cmdline" | token_in_stream "$token_file")"

# ------------------------------------------------------------------ stop
exit_file="$SMOKE_WORK/run/qupath.exit"
stop_recorded qupath TERM 60
wait "$pid"
echo $? > "$exit_file"
check qupath_exit_code_sigterm 143 "$(cat "$exit_file")"
check manifest_removed no "$([ -e "$manifest" ] && echo yes || echo no)"
check token_removed no "$([ -e "$token_file" ] && echo yes || echo no)"
check_true shutdown_hook_ran \
  grep -qF "qupflowR shutdown hook: discovery files removed for instance $instance" "$qupath_log"
curl -sS --noproxy '*' --max-time 3 -o /dev/null "http://127.0.0.1:$port/v1/health" 2>/dev/null
check port_closed_curl_exit 7 "$?"
stop_xvfb

{
  echo "{"
  echo "  \"launch_attempts\": $n,"
  echo "  \"inotify_skips\": $skips,"
  echo "  \"simulated_missing_extension\": \"$simulate_missing\","
  echo "  \"qupath_pid\": $pid,"
  echo "  \"extension_jar_sha256\": \"$ext_sha\","
  echo "  \"display\": \"$display\""
  echo "}"
} > "$SMOKE_WORK/launch-summary.json"
smoke_finish smoke-extension
