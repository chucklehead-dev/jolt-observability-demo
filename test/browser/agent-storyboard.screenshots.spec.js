const {test, expect} = require("@playwright/test");
const path = require("node:path");

test.setTimeout(120_000);

async function openNewestTrace(page) {
  const newest = page.locator(".otel-trace-list > li").first();
  await expect(newest).toContainText("samizdat.run");
  await newest.getByRole("link").click();
  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toBeVisible();
  return dialog;
}

async function openGeneration(dialog) {
  const generation = dialog.locator(".otel-spans > li:has(.otel-role-generation)").first();
  await expect(generation).toContainText("chat");
  await generation.locator("summary").click();
  const root = dialog.locator(".otel-spans > li").first().locator("details");
  if (await root.getAttribute("open") !== null) {
    await root.locator("summary").click();
  }
  await dialog.evaluate((element) => { element.scrollTop = 0; });
  return generation;
}

test("capture metadata-only and sanitized-response agent traces", async ({page}) => {
  const streamResponse = page.waitForResponse((response) =>
    response.url().includes("datastar-sse=true") && response.status() === 200);
  await page.goto("/");
  await streamResponse;

  const traces = page.locator(".otel-trace-list > li");
  const before = await traces.count();

  await page.getByRole("button", {name: "Run model (metadata only)"}).click();
  await expect(traces).toHaveCount(before + 1, {timeout: 90_000});
  let dialog = await openNewestTrace(page);
  let generation = await openGeneration(dialog);
  await expect(generation).toContainText("Content not recorded (privacy default)");
  await expect(generation).not.toContainText("Sanitized response");
  await dialog.screenshot({path: path.join("docs", "screenshots",
                                           "05-agent-metadata-only.png")});
  await page.keyboard.press("Escape");

  await page.getByRole("button", {name: "Run model (show response)"}).click();
  await expect(traces).toHaveCount(before + 2, {timeout: 90_000});
  dialog = await openNewestTrace(page);
  generation = await openGeneration(dialog);
  await expect(generation.locator(".otel-response")).toBeVisible();
  await expect(generation).toContainText("Sanitized response");
  await expect(generation).not.toContainText("Content not recorded");

  const html = await page.content();
  if (process.env.DEMO_LEMONADE_BASE_URL) {
    const configuredHostname = new URL(process.env.DEMO_LEMONADE_BASE_URL).hostname;
    expect(html).not.toContain(configuredHostname);
  }
  await dialog.screenshot({path: path.join("docs", "screenshots",
                                           "06-agent-with-response.png")});
});
