const {test, expect} = require("@playwright/test");
const path = require("node:path");

const screenshot = (page, name) => page.screenshot({
  path: path.join("docs", "screenshots", name),
  fullPage: true,
});

test("capture the initial workbench and live trace story", async ({page}) => {
  const streamResponse = page.waitForResponse((response) =>
    response.url().includes("datastar-sse=true") && response.status() === 200);
  await page.goto("/");
  await streamResponse;
  await expect(page.getByText("No traces yet. Generate work to begin.")).toBeVisible();
  await screenshot(page, "01-empty-workbench.png");

  await page.getByRole("button", {name: "Generate work"}).click();
  const trace = page.locator(".otel-trace-list > li").first();
  await expect(trace).toContainText("POST /work");
  await screenshot(page, "02-live-trace-arrives.png");

  await trace.getByRole("link").click();
  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toBeVisible();
  await expect(dialog.locator(".otel-spans > li")).toHaveCount(5);
  await dialog.screenshot({path: path.join("docs", "screenshots",
                                           "03-trace-waterfall-dialog.png")});
  await page.keyboard.press("Escape");

  await page.getByLabel("Operation or name").fill("demo.jobs");
  await page.getByLabel("Status").selectOption("ok");
  await page.getByRole("button", {name: "Apply filters"}).click();
  await expect(page.locator(".otel-trace-list > li").first()).toContainText("POST /work");
  await screenshot(page, "04-filtered-workbench.png");
});
