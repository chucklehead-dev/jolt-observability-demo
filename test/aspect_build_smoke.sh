#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
jolt=${JOLT_ASPECT_BIN:?set JOLT_ASPECT_BIN to a Jolt build with compiler aspects}
toolchain=${JOLT_TOOLCHAIN:-}

run_jolt() {
  if [ -n "$toolchain" ]; then
    "$toolchain" "$jolt" "$@"
  else
    "$jolt" "$@"
  fi
}

cd "$repo"
run_jolt -Sdeps \
  '{:paths ["src" "resources" "test"]
    :jolt/build
    {:embed ["resources"]
     :aspects [{:resource "META-INF/jolt/aspects/demo-workbench.edn"
                :provider demo.aspect-provider}]
     :aspect-report "target/aspect-smoke-aspects.edn"}}' \
  build -m demo.aspect-smoke -o target/aspect-smoke
output=$(target/aspect-smoke)

printf '%s\n' "$output" | grep -q ':event-count 2'
printf '%s\n' "$output" | grep -q ':phases \[:enter :return\]'
printf '%s\n' "$output" | grep -q ':contains-prompt? false'
grep -q ':demo.workbench/run-script' target/aspect-smoke-aspects.edn

echo "PASS: demo non-OTel aspect journal"
