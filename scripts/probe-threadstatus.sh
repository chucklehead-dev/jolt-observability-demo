#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
probe_dir=${THREADSTATUS_PROBE_DIR:-/tmp/jolt-observability-threadstatus}
cache_root=${XDG_CACHE_HOME:-${HOME}/.cache}
jolt_cache=${JOLT_CACHE_DIR:-$cache_root/jolt-observability-threadstatus/aot}
gitlibs_dir=${JOLT_GITLIBS_DIR:-$cache_root/jolt-observability-threadstatus/gitlibs}
scenarios=${THREADSTATUS_PROBE_SCENARIOS:-"startup work post-flush viewer sse sse-work otlp otlp-sse mixed-stress"}
repeat=${THREADSTATUS_PROBE_REPEAT:-1}

cd "$repo_dir"
mkdir -p "$probe_dir"
: > "$probe_dir/summary.tsv"

index=0
for scenario in $scenarios; do
  iteration=0
  while [ "$iteration" -lt "$repeat" ]; do
  iteration=$((iteration + 1))
  index=$((index + 1))
  port=$((28180 + index))
  stdout_file="$probe_dir/$scenario.$iteration.stdout"
  stderr_file="$probe_dir/$scenario.$iteration.stderr"
  transcript_file="$probe_dir/$scenario.$iteration.typescript"
  plain_file="$probe_dir/$scenario.$iteration.plain"
  probe_env=(env JOLT_CACHE_DIR="$jolt_cache" JOLT_GITLIBS_DIR="$gitlibs_dir")
  if [[ -n ${JOLT_CHDB_LIB:-} ]]; then
    probe_env+=(JOLT_CHDB_LIB="$JOLT_CHDB_LIB")
  fi
  if [[ -n ${JOLT_WRAPPER:-} ]]; then
    probe_env+=(JOLT_WRAPPER="$JOLT_WRAPPER")
  fi
  if [[ -n ${JOLT_EXE:-} ]]; then
    probe_env+=(JOLT_EXE="$JOLT_EXE")
  elif [[ -n ${JOLT_BIN:-} ]]; then
    probe_env+=(JOLT_BIN="$JOLT_BIN")
  fi
  if "${probe_env[@]}" \
      script -qefc "$repo_dir/scripts/run-threadstatus-case.sh $scenario $port" \
      "$transcript_file" >"$stdout_file" 2>"$stderr_file"; then
    process_status=0
  else
    process_status=$?
  fi
  sed $'s/\033\\[[0-9;]*[mK]//g' "$transcript_file" >"$plain_file"
  if grep -Fq 'ThreadStatus: current_thread contains invalid address' "$plain_file"; then
    diagnostic=present
  else
    diagnostic=absent
  fi
  printf '%s\titeration=%s\tprocess=%s\tthreadstatus=%s\n' \
    "$scenario" "$iteration" "$process_status" "$diagnostic" | tee -a "$probe_dir/summary.tsv"
  done
done

if grep -Fq $'threadstatus=present' "$probe_dir/summary.tsv"; then
  printf 'ThreadStatus diagnostic reproduced; evidence: %s\n' "$probe_dir" >&2
  exit 1
fi
if grep -Eq $'\tprocess=[1-9][0-9]*\t' "$probe_dir/summary.tsv"; then
  printf 'One or more probe processes failed; evidence: %s\n' "$probe_dir" >&2
  exit 1
fi
printf 'No ThreadStatus diagnostic observed; evidence: %s\n' "$probe_dir"
