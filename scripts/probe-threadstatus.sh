#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
probe_dir=${THREADSTATUS_PROBE_DIR:-/tmp/jolt-observability-threadstatus}
jolt_cache=${JOLT_CACHE_DIR:-/home/chuck/.cache/jolt-observability-demo-datastar/aot}
gitlibs_dir=${JOLT_GITLIBS_DIR:-/home/chuck/.cache/jolt-observability-demo-datastar/gitlibs}
chdb_lib=${JOLT_CHDB_LIB:-/home/chuck/.cache/jolt-native/chdb/26.7.0/linux-x86_64/libchdb.so}
hegel_lib=${HEGEL_LIBHEGEL_LIBRARY:-/home/chuck/.cache/jolt-hegel-v030-native/libhegel_c.so}
jolt_wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
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
  if env JOLT_CACHE_DIR="$jolt_cache" \
      JOLT_GITLIBS_DIR="$gitlibs_dir" \
      JOLT_CHDB_LIB="$chdb_lib" \
      HEGEL_LIBHEGEL_LIBRARY="$hegel_lib" \
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
