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

env DEMO_PORT=$demo_port \
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
curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/plotje-editor" \
  | grep -q "Plotje editor"
curl --noproxy '*' -fsS "http://127.0.0.1:$demo_port/hiccup-editor" \
  | grep -q "Safe Hiccup editor"
curl --noproxy '*' -fsS -X POST \
  --data-urlencode 'spec={:data [{:service "api" :count 2}] :layers [{:mark :bar :x :service :y :count}]}' \
  "http://127.0.0.1:$demo_port/plotje-editor/preview" \
  | grep -q '<svg'

test -s "$repo/target/samizdat-aspects.edn"
grep -q ':samizdat.embed/beam-run' "$repo/target/samizdat-aspects.edn"
grep -q ':samizdat.agent.infer/model' "$repo/target/samizdat-aspects.edn"

echo "PASS: woven standalone Samizdat viewer and editors"
