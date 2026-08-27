#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
scenario=${1:?scenario is required}
port=${2:?port is required}

cd "$repo_dir"
exec /home/chuck/ai-src/tools/jolt-with-chez-10.4.1 \
  jolt -M:threadstatus-probe "$scenario" "$port"
