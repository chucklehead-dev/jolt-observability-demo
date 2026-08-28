#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
jolt=${JOLT_ASPECT_BIN:?set JOLT_ASPECT_BIN to a Jolt build with compiler aspects}
output=${JOLT_ASPECT_DEMO_BIN:-target/observability-demo-aspect}

case "$output" in
  /*) output_path=$output ;;
  *) output_path=$repo/$output ;;
esac

cd "$repo"
"$jolt" build -m demo.main -o "$output_path"
DEMO_SERVER_COMMAND="$output_path" \
  npx playwright test --project=chromium

test -s target/aspects.edn
grep -q ':demo.workbench/run-script' target/aspects.edn

echo "PASS: woven demo browser story"
