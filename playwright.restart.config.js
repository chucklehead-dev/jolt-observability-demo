const {defineConfig, devices} = require("@playwright/test");

module.exports = defineConfig({
  testDir: "test/browser",
  testMatch: /persistent-restart\.spec\.js/,
  timeout: 120_000,
  expect: {timeout: 15_000},
  workers: 1,
  reporter: [["list"]],
  use: {
    ...devices["Desktop Chrome"],
    viewport: {width: 1440, height: 1000},
    colorScheme: "dark",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
