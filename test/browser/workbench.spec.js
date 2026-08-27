const {test, expect} = require("@playwright/test");

function guardBrowserErrors(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console: ${message.text()}`);
  });
  return () => expect(errors, "browser emitted no errors").toEqual([]);
}

test("generated telemetry streams into the workbench without navigation", async ({page}) => {
  const assertNoBrowserErrors = guardBrowserErrors(page);
  let navigations = 0;
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) navigations += 1;
  });

  const streamResponse = page.waitForResponse((response) =>
    response.url().includes("datastar-sse=true") && response.status() === 200);
  await page.goto("/");
  await streamResponse;

  await expect(page.getByRole("heading", {name: "Jolt Observability"})).toBeVisible();
  await expect(page.locator("#otel-live[data-otel-live=true]")).toBeVisible();
  await expect(page.getByText("No traces yet. Generate work to begin.")).toBeVisible();
  await expect(page.getByText("No logs yet.")).toBeVisible();

  const initialURL = page.url();
  const initialNavigations = navigations;
  await page.getByRole("button", {name: "Generate work"}).click();

  const firstTrace = page.locator(".otel-trace-list > li").first();
  await expect(firstTrace).toContainText("HTTP POST /work");
  await expect(firstTrace).toContainText("6 spans");
  await expect(page.locator(".otel-log-list")).toContainText("calling loopback upstream");
  expect(page.url()).toBe(initialURL);
  expect(navigations).toBe(initialNavigations);

  await firstTrace.getByRole("link").click();
  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toBeVisible();
  await expect(dialog.locator(".otel-spans > li")).toHaveCount(6);
  await expect(dialog).toContainText("HTTP GET /upstream");
  await expect(dialog).toContainText("SELECT demo readiness");
  await expect(dialog).toContainText("demo.jobs process");
  await expect(dialog).toContainText("Parent");
  expect(page.url()).toBe(initialURL);

  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
  await expect(firstTrace).toBeVisible();

  await page.getByLabel("Operation or name").fill("demo.jobs");
  await page.getByLabel("Status").selectOption("ok");
  await page.getByRole("button", {name: "Apply filters"}).click();
  await expect(page).toHaveURL(/operation=demo\.jobs.*status=ok/);
  await expect(page.locator(".otel-trace-list > li").first()).toContainText("HTTP POST /work");
  await page.getByRole("link", {name: "Clear"}).click();
  await expect(page).toHaveURL(/\/$/);
  assertNoBrowserErrors();
});

test("the workbench remains functional without JavaScript", async ({browser, baseURL}) => {
  const context = await browser.newContext({javaScriptEnabled: false});
  const page = await context.newPage();
  try {
    await page.goto(baseURL);
    await page.getByRole("button", {name: "Generate work"}).click();
    await page.waitForURL(`${baseURL}/`);
    const trace = page.locator(".otel-trace-list > li").first();
    await expect(trace).toContainText("HTTP POST /work");
    await trace.getByRole("link").click();
    await expect(page.getByRole("link", {name: "All traces"})).toBeVisible();
    await expect(page.locator(".otel-spans > li")).toHaveCount(6);
    await page.getByRole("link", {name: "All traces"}).click();
    await expect(page.getByRole("heading", {name: "Recent traces"})).toBeVisible();
  } finally {
    await context.close();
  }
});
