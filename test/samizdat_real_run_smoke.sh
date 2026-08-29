#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
binary=${SAMIZDAT_DEMO_BIN:-$repo/target/samizdat-observability-demo}
model_port=${DEMO_MODEL_FIXTURE_PORT:-31829}
demo_port=${DEMO_E2E_PORT:-31009}
scratch=$(mktemp -d /tmp/jolt-samizdat-real-run.XXXXXX)
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
mkdir -p "$scratch/project"
cp -R "$repo/test/fixtures/samizdat-coding-project/." "$scratch/project/"

prompt='Brain the size of a planet, and they ask you to repair arithmetic. Proof nonce samizdat-smoke-63ab19e2: fix square so it multiplies a value by itself, run the regression test, and report what you verified.'
DEMO_MODEL_FIXTURE_PORT=$model_port DEMO_EXPECTED_PROMPT="$prompt" \
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
   DEMO_CAPTURE_CONTENT=1 \
   DEMO_CAPTURE_MAX_CHARS=2048 \
   HARNESS_PROVIDER=local \
   HARNESS_BASE_URL="http://127.0.0.1:$model_port/v1" \
   HARNESS_MODEL=samizdat-coding-fixture \
   JOLT_CHDB_LIB="${JOLT_CHDB_LIB:?set JOLT_CHDB_LIB to libchdb.so}" \
   "$binary") >"$scratch/demo.log" 2>&1 &
demo_pid=$!

i=0
until curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/workbench" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 300 ]; then tail -120 "$scratch/demo.log"; exit 1; fi
  sleep 0.1
done

curl --noproxy '*' -fsS -X POST --data-urlencode "prompt=$prompt" \
  "http://127.0.0.1:$demo_port/workbench" >/dev/null

i=0
until curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/workbench" \
  >"$scratch/workbench.html" 2>/dev/null \
  && grep -q 'Fixed square and verified its regression test' "$scratch/workbench.html"; do
  i=$((i + 1))
  if grep -q '<p><strong>Status:</strong> failed</p>' "$scratch/workbench.html" 2>/dev/null; then
    tail -160 "$scratch/demo.log"
    sleep 3
    traces=$(curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/api/traces" 2>/dev/null || true)
    printf '%s\n' "$traces"
    trace_id=$(printf '%s' "$traces" | sed -n 's/.*"traceId":"\([0-9a-f]*\)".*/\1/p' | head -1)
    if [ -n "$trace_id" ]; then
      curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/api/traces/$trace_id" 2>/dev/null || true
    fi
    tail -80 "$scratch/workbench.html" 2>/dev/null || true
    exit 1
  fi
  if [ "$i" -ge 900 ]; then
    tail -160 "$scratch/demo.log"
    curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/api/summary" 2>/dev/null || true
    curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/api/traces" 2>/dev/null || true
    curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/api/logs" 2>/dev/null || true
    tail -80 "$scratch/workbench.html" 2>/dev/null || true
    exit 1
  fi
  sleep 0.1
done

grep -Fq '(* x x)' "$scratch/project/src/calc/core.clj"
grep -q 'model · enter' "$scratch/workbench.html"
grep -q 'tool · enter' "$scratch/workbench.html"

i=0
until curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/api/summary" \
  >"$scratch/summary.json" 2>/dev/null \
  && grep -Eq '"spanCount":[1-9][0-9]*' "$scratch/summary.json"; do
  i=$((i + 1))
  if [ "$i" -ge 100 ]; then tail -120 "$scratch/demo.log"; exit 1; fi
  sleep 0.1
done

if grep -q 'ThreadStatus: current_thread contains invalid address' "$scratch/demo.log"; then
  tail -160 "$scratch/demo.log"
  exit 1
fi

echo "PASS: real embedded Samizdat coding loop, file edit, journal, and woven spans"
