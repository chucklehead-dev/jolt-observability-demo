#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
binary=${SAMIZDAT_DEMO_BIN:-$repo/target/samizdat-observability-demo}
model_port=${DEMO_MODEL_FIXTURE_PORT:-31819}
demo_port=${DEMO_E2E_PORT:-30999}
scratch=$(mktemp -d /tmp/jolt-samizdat-demo-smoke.XXXXXX)
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

DEMO_MODEL_FIXTURE_PORT=$model_port \
  node "$repo/test/browser/model-fixture-server.js" \
  >"$scratch/model.log" 2>&1 &
model_pid=$!

i=0
until curl --noproxy '*' -fsS "http://127.0.0.1:$model_port/health" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 100 ]; then
    tail -80 "$scratch/model.log"
    exit 1
  fi
  sleep 0.1
done

env --default-signal=INT DEMO_PORT=$demo_port \
  DEMO_SAMIZDAT_ROOT="$scratch/project" \
  DEMO_SAMIZDAT_DB="$scratch/samizdat.sqlite3" \
  DEMO_CHDB_SPEC=chdb::memory: \
  HARNESS_PROVIDER=local \
  HARNESS_BASE_URL="http://127.0.0.1:$model_port/v1" \
  HARNESS_MODEL=fixture-model \
  JOLT_CHDB_LIB="${JOLT_CHDB_LIB:?set JOLT_CHDB_LIB to libchdb.so}" \
  "$binary" >"$scratch/demo.log" 2>&1 &
demo_pid=$!

i=0
until curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/api/summary" \
  >"$scratch/summary.json" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 300 ]; then
    tail -120 "$scratch/demo.log"
    exit 1
  fi
  sleep 0.1
done

curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/workbench" \
  | grep -q "Real Samizdat run"
curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/oscope/edit/plotje" \
  | grep -q "Plotje editor"
curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/oscope/edit/hiccup" \
  | grep -q "Safe Hiccup editor"
curl --noproxy '*' -fsS -X POST \
  --data-urlencode 'spec={:data [{:service "api" :count 2}] :layers [{:mark :bar :x :service :y :count}]}' \
  "http://127.0.0.1:$demo_port/oscope/edit/plotje/preview" \
  | grep -q '<svg'

test -s "$repo/target/samizdat-aspects.edn"
grep -q ':samizdat.embed/beam-run' "$repo/target/samizdat-aspects.edn"
grep -q ':samizdat.agent.infer/model' "$repo/target/samizdat-aspects.edn"
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
grep -q ':http-client.core/request' "$repo/target/samizdat-aspects.edn"
grep -q ':http/server-ring-handler' "$repo/target/samizdat-aspects.edn"
grep -q ':http/server-sanitized-response' "$repo/target/samizdat-aspects.edn"

kill -INT "$demo_pid"
i=0
while kill -0 "$demo_pid" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -ge 100 ]; then
    tail -120 "$scratch/demo.log"
    echo "FAIL: demo did not stop within 10 seconds of SIGINT" >&2
    exit 1
  fi
  sleep 0.1
done
set +e
wait "$demo_pid"
demo_status=$?
set -e
demo_pid=
if [ "$demo_status" -ne 0 ]; then
  tail -120 "$scratch/demo.log"
  echo "FAIL: demo exited $demo_status after SIGINT" >&2
  exit 1
fi
if grep -Eq 'ThreadStatus: current_thread contains invalid address|Exception in mutex-release|thread does not own mutex|Unhandled exception' "$scratch/demo.log"; then
  tail -120 "$scratch/demo.log"
  echo "FAIL: demo emitted a native-thread shutdown diagnostic" >&2
  exit 1
fi

echo "PASS: woven standalone Samizdat viewer, editors, and SIGINT shutdown"
