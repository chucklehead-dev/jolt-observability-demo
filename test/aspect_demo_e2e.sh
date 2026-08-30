#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
jolt=${JOLT_ASPECT_BIN:?set JOLT_ASPECT_BIN to a Jolt build with compiler aspects}
toolchain=${JOLT_TOOLCHAIN:-}
output=${JOLT_ASPECT_DEMO_BIN:-target/observability-demo-aspect}

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
    echo "FAIL: aspect demo requires a Jolt v0.8.x compiler, got: $version" >&2
    exit 1
    ;;
esac

case "$output" in
  /*) output_path=$output ;;
  *) output_path=$repo/$output ;;
esac

cd "$repo"
JOLT_WRAPPER="$toolchain" \
JOLT_BIN="$jolt" \
  DEMO_EXPECT_WOVEN_DB=0 \
  npx playwright test --project=chromium

run_jolt build -m demo.main -o "$output_path"
(cd "$repo/target"
 run_jolt -Srepro -Sdeps "{:paths [\"$repo/test\"]}" \
   -m demo.effect-evidence \
   "$output_path.build/effects.edn" woven "$repo/target/aspects.edn")
DEMO_SERVER_COMMAND="$output_path" \
  DEMO_EXPECT_WOVEN_DB=1 \
  npx playwright test --project=chromium

test -s target/aspects.edn
grep -q ':demo.workbench/run-script' target/aspects.edn
grep -q ':db.jdbc-shim/execute' target/aspects.edn
grep -q ':http-client.core/request' target/aspects.edn
grep -q ':http/server-ring-handler' target/aspects.edn
grep -q ':http/server-sanitized-response' target/aspects.edn

echo "PASS: woven demo browser story"
