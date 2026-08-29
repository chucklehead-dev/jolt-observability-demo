const {defineConfig, devices} = require("@playwright/test");

const baseURL = process.env.DEMO_SAMIZDAT_BASE_URL;
if (!baseURL) throw new Error("DEMO_SAMIZDAT_BASE_URL is required");

module.exports = defineConfig({
  testDir: "test/browser",
  testMatch: /samizdat-standalone\.spec\.js/,
  timeout: 120_000,
  expect: {timeout: 90_000},
  workers: 1,
  reporter: [["list"]],
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    viewport: {width: 1440, height: 1000},
    colorScheme: "dark",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
