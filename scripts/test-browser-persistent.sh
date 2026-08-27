#!/usr/bin/env bash
set -euo pipefail

test_db_root="$(mktemp -d "${TMPDIR:-/tmp}/jolt-otel-playwright.XXXXXX")"
cleanup() {
  rm -rf -- "$test_db_root"
}
trap cleanup EXIT INT TERM

export DEMO_CHDB_SPEC="chdb:$test_db_root/chdb"
npm run test:browser
