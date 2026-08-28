const {defineConfig, devices} = require("@playwright/test");

const port = Number(process.env.DEMO_E2E_PORT || 30808);
const baseURL = `http://127.0.0.1:${port}`;
const joltCommand = process.env.JOLT_WRAPPER
  ? `${process.env.JOLT_WRAPPER} jolt`
  : "jolt";
const demoServerCommand = process.env.DEMO_SERVER_COMMAND ||
  `${joltCommand} -m demo.main`;

module.exports = defineConfig({
  testDir: "test/browser",
  timeout: 30_000,
  expect: {timeout: 10_000},
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL,
    viewport: {width: 1440, height: 1000},
    colorScheme: "dark",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: demoServerCommand,
    url: `${baseURL}/api/summary`,
    timeout: 120_000,
    reuseExistingServer: false,
    env: {
      ...process.env,
      DEMO_PORT: String(port),
      DEMO_CHDB_SPEC: process.env.DEMO_CHDB_SPEC || "chdb::memory:",
    },
  },
  projects: [
    {
      name: "chromium",
      testMatch: /(workbench|editors|oscope)\.spec\.js/,
      use: {...devices["Desktop Chrome"]},
    },
    {
      name: "docs",
      testMatch: /\/storyboard\.screenshots\.spec\.js$/,
      outputDir: "docs/screenshots/test-artifacts",
      use: {...devices["Desktop Chrome"]},
    },
    {
      name: "agent-docs",
      testMatch: /\/agent-storyboard\.screenshots\.spec\.js$/,
      outputDir: "docs/screenshots/test-artifacts",
      use: {...devices["Desktop Chrome"], viewport: {width: 1440, height: 1400}},
    },
  ],
});
