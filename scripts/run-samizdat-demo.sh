#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
binary=${SAMIZDAT_DEMO_BIN:-$repo/target/samizdat-observability-demo}
project=${DEMO_SAMIZDAT_ROOT:?set DEMO_SAMIZDAT_ROOT to the project Samizdat may edit}

test -x "$binary"
project=$(CDPATH= cd -- "$project" && pwd)

# Samizdat adds the target's source roots to eval, but relative file access is
# process-cwd-relative. Launching from the target is therefore part of the
# embed contract, not cosmetic shell behavior.
cd "$project"
exec env DEMO_SAMIZDAT_ROOT="$project" "$binary"
