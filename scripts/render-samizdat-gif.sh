#!/usr/bin/env bash
set -euo pipefail

repo=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$repo"

env SAMIZDAT_PLAYWRIGHT_CONFIG="$repo/playwright.samizdat-gif.config.js" \
    DEMO_CAPTURE_MODEL_CONTENT=1 \
    test/samizdat_playwright_e2e.sh

ffmpeg -hide_banner -loglevel error -y \
  -ss 0.4 \
  -i test-results/samizdat-trace-tour.webm \
  -vf "fps=12,scale=960:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
  docs/screenshots/samizdat-trace-tour.gif
