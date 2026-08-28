#!/usr/bin/env bash
set -euo pipefail

npx playwright test --config=playwright.gif.config.js

ffmpeg -hide_banner -loglevel error -y \
  -ss 0.4 \
  -i test-results/agent-trace-tour.webm \
  -vf "fps=12,scale=960:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
  docs/screenshots/agent-trace-tour.gif
