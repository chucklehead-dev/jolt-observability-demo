const {defineConfig, devices} = require("@playwright/test");

const appPort = Number(process.env.DEMO_GIF_PORT || 31808);
const modelPort = Number(process.env.DEMO_MODEL_FIXTURE_PORT || 31809);
const baseURL = `http://127.0.0.1:${appPort}`;
const joltExecutable = process.env.JOLT_EXE || process.env.JOLT_BIN || "jolt";
const joltCommand = process.env.JOLT_WRAPPER
  ? `${process.env.JOLT_WRAPPER} ${joltExecutable}`
  : joltExecutable;

module.exports = defineConfig({
  testDir: "test/browser",
  testMatch: /agent-storyboard\.gif\.spec\.js/,
  timeout: 60_000,
  expect: {timeout: 10_000},
  workers: 1,
  reporter: [["list"]],
  outputDir: "docs/screenshots/agent-gif-artifacts",
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    viewport: {width: 1280, height: 900},
    colorScheme: "dark",
    reducedMotion: "reduce",
    video: {mode: "on", size: {width: 1280, height: 900}},
  },
  webServer: [
    {
      command: "node test/browser/model-fixture-server.js",
      url: `http://127.0.0.1:${modelPort}/health`,
      timeout: 30_000,
      reuseExistingServer: false,
      env: {...process.env, DEMO_MODEL_FIXTURE_PORT: String(modelPort)},
    },
    {
      command: `${joltCommand} -m demo.main`,
      url: `${baseURL}/api/summary`,
      timeout: 120_000,
      reuseExistingServer: false,
      env: {
        ...process.env,
        DEMO_PORT: String(appPort),
        DEMO_CHDB_SPEC: "chdb::memory:",
        DEMO_LEMONADE_BASE_URL: `http://127.0.0.1:${modelPort}/v1`,
        DEMO_LEMONADE_MODEL: "fixture-model",
        DEMO_LEMONADE_TELEMETRY_ADDRESS: "local-model-host",
        DEMO_LEMONADE_DISABLE_THINKING: "true",
      },
    },
  ],
});
