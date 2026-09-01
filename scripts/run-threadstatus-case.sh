#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
scenario=${1:?scenario is required}
port=${2:?port is required}

cd "$repo_dir"
jolt=${JOLT_EXE:-${JOLT_BIN:-jolt}}
if [[ -n ${JOLT_WRAPPER:-} ]]; then
  exec "$JOLT_WRAPPER" "$jolt" -M:threadstatus-probe "$scenario" "$port"
fi
exec "$jolt" -M:threadstatus-probe "$scenario" "$port"
