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

async function openGeneration(dialog, index = 0) {
  const generation = dialog.locator(".otel-spans > li:has(.otel-role-generation)").nth(index);
  await expect(generation).toContainText("chat");
  await generation.locator("summary").click();
  const root = dialog.locator(".otel-spans > li").first().locator("details");
  if (await root.getAttribute("open") !== null) {
    await root.locator("summary").click();
  }
  await dialog.evaluate((element) => { element.scrollTop = 0; });
  return generation;
}

test("capture private, explicit-exchange, and controller-intervention traces", async ({page}) => {
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
  await expect(generation).not.toContainText("Captured prompt");
  await expect(generation).not.toContainText("Captured response");
  await dialog.screenshot({path: path.join("docs", "screenshots",
                                           "05-agent-metadata-only.png")});
  await page.keyboard.press("Escape");

  await page.getByRole("button", {name: "Run model (show exchange)"}).click();
  await expect(traces).toHaveCount(before + 2, {timeout: 90_000});
  dialog = await openNewestTrace(page);
  generation = await openGeneration(dialog);
  await expect(generation).toContainText("Captured prompt");
  await expect(generation).toContainText("brain the size of a planet");
  await expect(generation).toContainText("Captured response");
  await expect(generation).not.toContainText("Content not recorded");

  const html = await page.content();
  if (process.env.DEMO_LEMONADE_BASE_URL) {
    const configuredHostname = new URL(process.env.DEMO_LEMONADE_BASE_URL).hostname;
    expect(html).not.toContain(configuredHostname);
  }
  await dialog.screenshot({path: path.join("docs", "screenshots",
                                           "06-agent-with-response.png")});
  await page.keyboard.press("Escape");

  await page.getByRole("button", {name: "Run multi-turn intervention"}).click();
  await expect(traces).toHaveCount(before + 3, {timeout: 90_000});
  dialog = await openNewestTrace(page);
  await expect(dialog.locator(".otel-role-turn")).toHaveCount(2);
  const intervention = dialog.locator(".otel-spans > li:has(.otel-role-intervention)");
  await expect(intervention).toContainText("first draft required a concrete correctness review");
  await expect(intervention.locator("details")).toHaveAttribute("open", "");
  generation = await openGeneration(dialog, 1);
  await expect(generation).toContainText("Captured prompt");
  await expect(generation).toContainText("Controller intervention:");
  await expect(generation).toContainText("Captured response");
  await expect(generation).toContainText("Last-Event-ID");
  await generation.scrollIntoViewIfNeeded();
  await dialog.evaluate((element) => {
    element.scrollTop = Math.max(0, element.scrollTop - 220);
  });
  await dialog.screenshot({path: path.join("docs", "screenshots",
                                           "07-agent-controller-intervention.png")});
});
