const {test, expect} = require("@playwright/test");
const path = require("node:path");

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

test("record the complete agent trace tour", async ({page}) => {
  const streamResponse = page.waitForResponse((response) =>
    response.url().includes("datastar-sse=true") && response.status() === 200);
  await page.goto("/");
  await streamResponse;
  await pause(1200);

  const traces = page.locator(".otel-trace-list > li");
  const before = await traces.count();
  await page.getByRole("button", {name: "Run model (show response)"}).click();
  await expect(traces).toHaveCount(before + 1);
  await pause(1200);

  const newest = traces.first();
  await expect(newest).toContainText("samizdat.run");
  await newest.getByRole("link").click();
  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("samizdat.control-loop");
  await pause(2200);

  const generation = dialog.locator(".otel-spans > li:has(.otel-role-generation)").first();
  await generation.locator("summary").click();
  await expect(generation.locator(".otel-response")).toContainText("square root of -1");
  expect(await page.content()).not.toContain("127.0.0.1");
  await generation.scrollIntoViewIfNeeded();
  await pause(3200);

  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
  await pause(1200);

  const video = page.video();
  await page.close();
  await video.saveAs(path.join("test-results", "agent-trace-tour.webm"));
});
