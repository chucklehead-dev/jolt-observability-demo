const {test, expect} = require("@playwright/test");
const path = require("node:path");

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

test("record the real embedded Samizdat coding trace", async ({page, request}) => {
  const prompt = process.env.DEMO_EXPECTED_PROMPT;
  expect(prompt).toContain("samizdat-e2e-7f31c92b");

  const streamResponse = page.waitForResponse((response) =>
    response.url().includes("datastar-sse=true") &&
    response.url().includes("workbench-live") && response.status() === 200);
  await page.goto("/workbench");
  await streamResponse;
  await page.getByLabel("Prompt").fill(prompt);
  await pause(1000);
  await page.getByRole("button", {name: "Run"}).click();
  await expect(page.locator(".workbench-events li").first())
    .toContainText("Run opened", {timeout: 30_000});
  await expect(page.locator("p", {hasText: "Status:"}))
    .toContainText("closed", {timeout: 90_000});
  await expect(page.locator("#workbench-live"))
    .toContainText("Fixed square and verified its regression test.");
  await pause(1800);

  let summary;
  await expect.poll(async () => {
    const summaries = await (await request.get("/api/traces")).json();
    summary = summaries.find((trace) => trace.rootSpan === "samizdat.run");
    return Boolean(summary);
  }).toBe(true);

  await page.locator(".otel-header a.otel-back-link[href=\"/\"]").click();
  const traceLink = page.locator(`a[href="/traces/${summary.traceId}"]`).first();
  await expect(traceLink).toBeVisible();
  await pause(1000);
  await traceLink.click();

  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("samizdat.run");
  await expect(dialog).toContainText("samizdat.turn");
  await expect(dialog).toContainText("SELECT");
  expect(await page.content()).not.toContain("127.0.0.1");
  await pause(1800);

  const generation = dialog.locator(".otel-spans > li:has(.otel-role-generation)")
    .filter({hasText: "Fixed square and verified"}).last();
  await expect(generation).toBeVisible();
  await generation.locator("summary").first().click();
  await expect(generation).toContainText("Captured prompt");
  await expect(generation).toContainText("Captured response");
  await expect(generation).toContainText("samizdat-e2e-7f31c92b");
  await expect(generation).toContainText("Fixed square and verified");
  await generation.scrollIntoViewIfNeeded();
  await pause(3000);

  const tool = dialog.locator(".otel-spans > li:has(.otel-role-tool)").last();
  await expect(tool).toBeVisible();
  await tool.scrollIntoViewIfNeeded();
  await pause(2200);

  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
  await pause(1000);

  const video = page.video();
  await page.close();
  await video.saveAs(path.join("test-results", "samizdat-trace-tour.webm"));
});
