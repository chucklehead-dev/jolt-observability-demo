const {defineConfig, devices} = require("@playwright/test");

const baseURL = process.env.DEMO_SAMIZDAT_BASE_URL;
if (!baseURL) throw new Error("DEMO_SAMIZDAT_BASE_URL is required");

module.exports = defineConfig({
  testDir: "test/browser",
  testMatch: /samizdat-storyboard\.gif\.spec\.js/,
  timeout: 120_000,
  expect: {timeout: 90_000},
  workers: 1,
  reporter: [["list"]],
  outputDir: "test-results/samizdat-gif",
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    viewport: {width: 1440, height: 1000},
    colorScheme: "dark",
    reducedMotion: "reduce",
    video: {mode: "on", size: {width: 1200, height: 834}},
    trace: "retain-on-failure",
  },
});
