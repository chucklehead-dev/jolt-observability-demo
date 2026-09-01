#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
jolt=${JOLT_ASPECT_BIN:?set JOLT_ASPECT_BIN to a Jolt v0.8.x build with compiler aspects}
toolchain=${JOLT_TOOLCHAIN:-}
output=${SAMIZDAT_DEMO_BIN:-$repo/target/samizdat-observability-demo}

run_jolt() {
  if [ -n "$toolchain" ]; then
    "$toolchain" "$jolt" "$@"
  else
    "$jolt" "$@"
  fi
}

version=$(run_jolt --version)
case "$version" in
  "jolt v0.8."*) ;;
  *)
    echo "FAIL: Samizdat demo requires a Jolt v0.8.x compiler, got: $version" >&2
    exit 1
    ;;
esac

cd "$repo/samizdat-demo"
run_jolt build -m demo.samizdat-main -o "$output"
