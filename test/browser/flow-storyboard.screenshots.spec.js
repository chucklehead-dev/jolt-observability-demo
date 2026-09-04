const {test, expect} = require("@playwright/test");
const path = require("node:path");

test("capture the compiler-woven core.async.flow trace", async ({page}) => {
  const streamResponse = page.waitForResponse((response) =>
    response.url().includes("datastar-sse=true") && response.status() === 200);
  await page.goto("/");
  await streamResponse;

  const actionResponse = page.waitForResponse((response) =>
    response.url().endsWith("/flow-work") && response.request().method() === "POST");
  await page.getByRole("button", {name: "Run core.async.flow"}).click();
  await expect((await actionResponse).status()).toBe(204);

  const flowTrace = page.locator(".otel-trace-list li", {
    hasText: "POST /flow-work",
  }).first();
  await expect(flowTrace).toContainText("17 spans");
  await flowTrace.getByRole("link").click();

  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toContainText("core.async.flow create");
  await expect(dialog).toContainText("core.async.flow step transform");
  await dialog.screenshot({
    path: path.join("docs", "screenshots", "08-core-async-flow-trace.png"),
  });
});
