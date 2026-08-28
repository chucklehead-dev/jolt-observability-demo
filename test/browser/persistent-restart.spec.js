const {test, expect} = require("@playwright/test");
const {spawn} = require("node:child_process");

const port = Number(process.env.DEMO_RESTART_PORT || 30818);
const baseURL = `http://127.0.0.1:${port}`;
const dbSpec = process.env.DEMO_CHDB_SPEC;
const maxTranscriptLength = 128 * 1024;

function appendBounded(transcript, chunk) {
  const combined = transcript.value + chunk.toString("utf8");
  transcript.value = combined.slice(-maxTranscriptLength);
}

function startDemo() {
  const wrapper = process.env.JOLT_WRAPPER;
  const command = wrapper || "jolt";
  const args = wrapper ? ["jolt", "-m", "demo.main"] : ["-m", "demo.main"];
  const transcript = {value: ""};
  const child = spawn(command, args, {
    cwd: process.cwd(),
    env: {...process.env, DEMO_PORT: String(port), DEMO_CHDB_SPEC: dbSpec},
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", (chunk) => appendBounded(transcript, chunk));
  child.stderr.on("data", (chunk) => appendBounded(transcript, chunk));
  return {child, transcript};
}

async function waitUntilReady(processState) {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    if (processState.child.exitCode !== null) {
      throw new Error(`demo exited before readiness\n${processState.transcript.value}`);
    }
    try {
      const response = await fetch(`${baseURL}/api/summary`);
      if (response.status === 200) return;
    } catch (_) {
      // The socket is expected to refuse connections during startup.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`demo readiness timed out\n${processState.transcript.value}`);
}

async function stopDemo(processState) {
  if (processState.child.exitCode === null) {
    const exited = new Promise((resolve) => {
      processState.child.once("exit", (code, signal) => resolve({code, signal}));
    });
    processState.child.kill("SIGTERM");
    const result = await Promise.race([
      exited,
      new Promise((resolve) => setTimeout(() => resolve(null), 10_000)),
    ]);
    if (!result) {
      processState.child.kill("SIGKILL");
      await exited;
      throw new Error(`demo did not stop within 10 seconds\n${processState.transcript.value}`);
    }
  }
  expect(processState.transcript.value).not.toContain(
    "ThreadStatus: current_thread contains invalid address",
  );
}

test("a browser-created trace survives a complete chDB process restart", async ({browser}) => {
  expect(dbSpec, "restart test requires a filesystem-backed DEMO_CHDB_SPEC").toMatch(/^chdb:.+/);
  expect(dbSpec).not.toBe("chdb::memory:");

  let firstProcess = startDemo();
  let secondProcess;
  try {
    await waitUntilReady(firstProcess);
    const firstContext = await browser.newContext();
    const firstPage = await firstContext.newPage();
    await firstPage.goto(baseURL);
    const traces = firstPage.locator(".otel-trace-list > li");
    const before = await traces.count();
    await firstPage.getByRole("button", {name: "Generate work"}).click();
    await expect(traces).toHaveCount(before + 1);
    const durableTrace = traces.first();
    await expect(durableTrace).toContainText("HTTP POST /work");
    await expect(durableTrace).toContainText("5 spans");
    const tracePath = await durableTrace.getByRole("link").getAttribute("href");
    expect(tracePath).toMatch(/^\/traces\/[0-9a-f]{32}$/);
    await firstContext.close();

    await stopDemo(firstProcess);
    firstProcess = null;

    secondProcess = startDemo();
    await waitUntilReady(secondProcess);
    const secondContext = await browser.newContext();
    const secondPage = await secondContext.newPage();
    await secondPage.goto(baseURL);
    await expect(secondPage.locator(`a[href="${tracePath}"]`)).toBeVisible();
    await secondPage.locator(`a[href="${tracePath}"]`).click();
    const dialog = secondPage.locator("dialog[data-otel-dialog]");
    await expect(dialog).toBeVisible();
    await expect(dialog.locator(".otel-spans > li")).toHaveCount(5);
    await expect(dialog).not.toContainText("SELECT demo readiness");
    await expect(dialog).toContainText("demo.jobs process");
    await secondContext.close();
  } finally {
    if (firstProcess) await stopDemo(firstProcess);
    if (secondProcess) await stopDemo(secondProcess);
  }
});
