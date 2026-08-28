const {test, expect} = require("@playwright/test");

const wovenDatabase = process.env.DEMO_EXPECT_WOVEN_DB === "1";
const expectedWorkSpanCount = wovenDatabase ? 6 : 5;

function guardBrowserErrors(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console: ${message.text()}`);
  });
  return () => expect(errors, "browser emitted no errors").toEqual([]);
}

test("generated telemetry streams into the workbench without navigation", async ({page, request}) => {
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

  // Other stories and persistent demo runs may already have populated the
  // shared store. The streaming contract is one new result at the top, not an
  // empty database at browser startup.
  const traces = page.locator(".otel-trace-list > li");
  const initialTraceCount = await traces.count();

  const initialURL = page.url();
  const initialNavigations = navigations;
  await page.getByRole("button", {name: "Generate work"}).click();

  await expect(traces).toHaveCount(initialTraceCount + 1);
  const firstTrace = traces.first();
  await expect(firstTrace).toContainText("POST /work");
  await expect(firstTrace).toContainText(`${expectedWorkSpanCount} spans`);
  await expect(page.locator(".otel-log-list")).toContainText("calling loopback upstream");
  expect(page.url()).toBe(initialURL);
  expect(navigations).toBe(initialNavigations);

  const tracePath = await firstTrace.getByRole("link").getAttribute("href");
  const traceId = tracePath.split("/").pop();
  const detail = await (await request.get(`/api/traces/${traceId}`)).json();
  const server = detail.spans.find((span) => span.name === "POST /work");
  const client = detail.spans.find((span) => span.name === "GET" && span.kind === "client");
  const upstream = detail.spans.find((span) =>
    span.name === "GET /upstream" && span.kind === "server");
  expect(server).toBeTruthy();
  expect(client).toBeTruthy();
  expect(upstream).toBeTruthy();
  expect(client.parentSpanId).toBe(server.spanId);
  expect(upstream.parentSpanId).toBe(client.spanId);

  const httpSpans = [server, client, upstream];
  if (wovenDatabase) {
    const database = detail.spans.find((span) => span.name === "SELECT");
    expect(database).toBeTruthy();
    expect(database.parentSpanId).toBe(server.spanId);
    for (const span of httpSpans) {
      expect(span.attributes["demo.instrumentation.mode"]).toBeUndefined();
    }
    expect(server.attributes["http.route"]).toBe("/work");
    expect(client.attributes["url.full"]).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/REDACTED$/);
  } else {
    for (const span of httpSpans) {
      expect(span.attributes["demo.instrumentation.mode"]).toBe("source-fallback");
    }
  }

  await firstTrace.getByRole("link").click();
  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toBeVisible();
  await expect(dialog.locator(".otel-spans > li")).toHaveCount(expectedWorkSpanCount);
  await expect(dialog).toContainText("GET /upstream");
  if (wovenDatabase) {
    await expect(dialog).toContainText("SELECT");
  } else {
    await expect(dialog).not.toContainText("SELECT demo readiness");
  }
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
  await expect(page.locator(".otel-trace-list > li").first()).toContainText("POST /work");
  await page.getByRole("link", {name: "Clear"}).click();
  await expect(page).toHaveURL(/\/$/);
  assertNoBrowserErrors();
});

test("a /workbench run evolves live via SSE to a terminal response", async ({page}) => {
  const assertNoBrowserErrors = guardBrowserErrors(page);
  const streamResponse = page.waitForResponse((response) =>
    response.url().includes("datastar-sse=true") &&
    response.url().includes("workbench-live") &&
    response.status() === 200);
  await page.goto("/workbench");
  await streamResponse;

  await expect(page.getByRole("heading", {name: "Run workbench"})).toBeVisible();
  await expect(page.getByText("No run yet. Enter a prompt above to start one.")).toBeVisible();

  await page.getByLabel("Prompt").fill("repair the SSE reconnect race");
  await page.getByRole("button", {name: "Run"}).click();
  await page.waitForURL(/\/workbench$/);

  await expect(page.locator(".workbench-events li").first())
    .toContainText("Run opened", {timeout: 15000});
  await expect(page.locator("p", {hasText: "Status:"}))
    .toContainText("closed", {timeout: 15000});
  await expect(page.locator("#workbench-live .otel-content pre"))
    .toContainText("Last-Event-ID");

  await page.locator(".otel-header a.otel-back-link[href=\"/\"]").click();
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
    await expect(trace).toContainText("POST /work");
    await trace.getByRole("link").click();
    await expect(page.getByRole("link", {name: "All traces"})).toBeVisible();
    await expect(page.locator(".otel-spans > li")).toHaveCount(expectedWorkSpanCount);
    await page.getByRole("link", {name: "All traces"}).click();
    await expect(page.getByRole("heading", {name: "Recent traces"})).toBeVisible();
  } finally {
    await context.close();
  }
});
