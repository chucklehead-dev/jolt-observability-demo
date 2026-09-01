#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
binary=${SAMIZDAT_DEMO_BIN:-$repo/target/samizdat-observability-demo}
playwright_config=${SAMIZDAT_PLAYWRIGHT_CONFIG:-$repo/playwright.samizdat.config.js}
model_port=${DEMO_MODEL_FIXTURE_PORT:-31849}
demo_port=${DEMO_E2E_PORT:-31029}
scratch=$(mktemp -d /tmp/jolt-samizdat-playwright.XXXXXX)
expected_prompt='Inspect the arithmetic regression proof nonce samizdat-e2e-7f31c92b: repair calc.square so it multiplies its input by itself, run the focused test, and report the exact verification.'
model_pid=
demo_pid=

cleanup() {
  if [ -n "$demo_pid" ]; then kill "$demo_pid" 2>/dev/null || true; fi
  if [ -n "$model_pid" ]; then kill "$model_pid" 2>/dev/null || true; fi
  if [ -n "$demo_pid" ]; then wait "$demo_pid" 2>/dev/null || true; fi
  if [ -n "$model_pid" ]; then wait "$model_pid" 2>/dev/null || true; fi
  rm -rf -- "$scratch"
}
trap cleanup EXIT INT TERM

test -x "$binary"
test -s "$repo/target/samizdat-aspects.edn"
grep -q ':http-client.core/request' "$repo/target/samizdat-aspects.edn"
grep -q ':http/server-ring-handler' "$repo/target/samizdat-aspects.edn"
grep -q ':http/server-sanitized-response' "$repo/target/samizdat-aspects.edn"
grep -q ':provider demo.samizdat-journal-provider/aspect-provider' \
  "$repo/target/samizdat-aspects.edn"
grep -q ':provider demo.samizdat-aspect-provider/aspect-provider' \
  "$repo/target/samizdat-aspects.edn"
grep -Fq ':advice-role :http/client :consumers [{:advice demo.samizdat-aspect-provider/around-http-client' \
  "$repo/target/samizdat-aspects.edn"
if grep -Fq ':advice-role :http/client :consumers [{:advice demo.samizdat-journal-provider/around' \
    "$repo/target/samizdat-aspects.edn"; then
  echo "FAIL: semantic journal was woven into HTTP client plumbing" >&2
  exit 1
fi
grep -Fq ':advice-role :samizdat/control-loop :consumers [{:advice demo.samizdat-journal-provider/around' \
  "$repo/target/samizdat-aspects.edn"
grep -Fq ':advice-role :samizdat/tool-selection :consumers [{:advice demo.samizdat-journal-provider/around' \
  "$repo/target/samizdat-aspects.edn"
mkdir -p "$scratch/project"
cp -R "$repo/test/fixtures/samizdat-coding-project/." "$scratch/project/"

DEMO_MODEL_FIXTURE_PORT=$model_port \
DEMO_EXPECTED_PROMPT="$expected_prompt" \
  node "$repo/test/browser/samizdat-model-fixture-server.js" \
  >"$scratch/model.log" 2>&1 &
model_pid=$!

i=0
until curl --noproxy '*' -fsS "http://127.0.0.1:$model_port/health" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 100 ]; then tail -80 "$scratch/model.log"; exit 1; fi
  sleep 0.1
done

(cd "$scratch/project"
 exec env DEMO_PORT=$demo_port \
   DEMO_SAMIZDAT_ROOT="$scratch/project" \
   DEMO_SAMIZDAT_DB="$scratch/samizdat.sqlite3" \
   DEMO_CHDB_SPEC=chdb::memory: \
   HARNESS_PROVIDER=local \
   HARNESS_BASE_URL="http://127.0.0.1:$model_port/v1" \
   HARNESS_MODEL=samizdat-coding-fixture \
   OTEL_INSTRUMENTATION_DB_CAPTURE_ROW_COUNTS=true \
   DEMO_CAPTURE_CONTENT="${DEMO_CAPTURE_CONTENT:-0}" \
   DEMO_CAPTURE_MODEL_CONTENT="${DEMO_CAPTURE_MODEL_CONTENT:-0}" \
   JOLT_CHDB_LIB="${JOLT_CHDB_LIB:?set JOLT_CHDB_LIB to libchdb.so}" \
   "$binary") >"$scratch/demo.log" 2>&1 &
demo_pid=$!

i=0
until curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/workbench" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 300 ]; then tail -120 "$scratch/demo.log"; exit 1; fi
  sleep 0.1
done

if ! env DEMO_SAMIZDAT_BASE_URL="http://127.0.0.1:$demo_port" \
         DEMO_SAMIZDAT_PROJECT="$scratch/project" \
         DEMO_EXPECTED_PROMPT="$expected_prompt" \
         DEMO_MODEL_FIXTURE_URL="http://127.0.0.1:$model_port" \
         npx playwright test --config="$playwright_config"; then
  tail -80 "$scratch/model.log"
  tail -160 "$scratch/demo.log"
  exit 1
fi

if grep -Eq 'ThreadStatus: current_thread contains invalid address|Exception in mutex-release|thread does not own mutex|Unhandled exception' "$scratch/demo.log"; then
  tail -160 "$scratch/demo.log"
  exit 1
fi

echo "PASS: standalone Samizdat exact prompt, traceparent, live SSE, edit, response, and span parentage"
