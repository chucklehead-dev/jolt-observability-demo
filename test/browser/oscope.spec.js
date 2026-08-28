const {test, expect} = require("@playwright/test");

async function summary(request) {
  const response = await request.get("/api/summary");
  expect(response.ok()).toBeTruthy();
  return response.json();
}

function guardBrowserErrors(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console: ${message.text()}`);
  });
  return () => expect(errors, "browser emitted no errors").toEqual([]);
}

async function displayedCount(page) {
  const values = await page.locator("#oscope-screen tbody tr td:nth-child(2)").allTextContents();
  return values.reduce((sum, value) => sum + Number(value), 0);
}

test("oscope renders a no-JavaScript bounded query as a chart and table without feedback", async ({browser, request, baseURL}) => {
  const generated = await request.post("/work");
  expect(generated.ok()).toBeTruthy();
  await expect.poll(async () => (await (await request.get("/api/summary")).json()).spanCount).toBeGreaterThan(0);

  const before = await summary(request);
  const context = await browser.newContext({javaScriptEnabled: false});
  const page = await context.newPage();
  try {
    const navigation = await page.goto(`${baseURL}/oscope`);
    expect(navigation.headers()["content-security-policy"]).toContain("form-action 'self'");
    await expect(page.locator("script")).toHaveCount(0);
    await expect(page.getByRole("heading", {name: "oscope"})).toBeVisible();
    await expect(page.getByRole("img")).toBeVisible();
    await expect(page.getByRole("table", {name: "Bounded query results"})).toBeVisible();

    const queryForm = page.getByRole("form", {name: "Telemetry query"});
    await queryForm.getByLabel("Signal").selectOption("logs");
    await queryForm.getByLabel("Window").selectOption("15m");
    await queryForm.getByLabel("Maximum rows").fill("5");
    await queryForm.getByRole("button", {name: "Run query"}).click();
    await expect(page).toHaveURL(/\/oscope\?signal=logs&field=service-name&window=15m&limit=5$/);
    await expect(page.getByRole("heading", {name: "Service Name in Logs"})).toBeVisible();

    const end = BigInt(Date.now() + 60_000) * 1_000_000n;
    const start = end - 15n * 60n * 1_000_000_000n;
    const startText = start.toString();
    const endText = end.toString();
    expect(startText).toMatch(/^[0-9]{19}$/);
    expect(endText).toMatch(/^[0-9]{19}$/);

    const exportForm = page.getByRole("form", {name: "Raw telemetry export"});
    await exportForm.getByLabel("Signal").selectOption("spans");
    await exportForm.getByLabel("Format").selectOption("parquet");
    await exportForm.getByLabel("Start (Unix ns)").fill(startText);
    await exportForm.getByLabel("End, exclusive (Unix ns)").fill(endText);
    await exportForm.getByLabel("Maximum rows").fill("100");
    await exportForm.getByLabel("Maximum bytes").fill("4194304");

    const downloadPromise = page.waitForEvent("download");
    await exportForm.getByRole("button", {name: "Download"}).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe(
      `oscope-spans-${startText}-${endText}.parquet`
    );
    expect(await download.failure()).toBeNull();
    const downloadURL = new URL(download.url());
    expect(downloadURL.pathname).toBe("/oscope/export");
    expect(downloadURL.searchParams.get("start-unix-nano")).toBe(startText);
    expect(downloadURL.searchParams.get("end-unix-nano")).toBe(endText);
    const stream = await download.createReadStream();
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    const body = Buffer.concat(chunks);
    expect(body.length).toBeGreaterThan(4);
    expect(body.subarray(0, 4).toString("ascii")).toBe("PAR1");

    await expect.poll(() => summary(request)).toEqual(before);
  } finally {
    await context.close();
  }
});

test("oscope live mode refreshes in place, freezes exact export bounds, and cannot observe itself", async ({page, request}) => {
  const assertNoBrowserErrors = guardBrowserErrors(page);
  const woven = process.env.DEMO_EXPECT_WOVEN_DB === "1";
  const spansPerWork = woven ? 6 : 5;
  let refreshRequests = 0;
  page.on("request", (requestEvent) => {
    if (new URL(requestEvent.url()).pathname === "/oscope/refresh") refreshRequests += 1;
  });

  const before = await summary(request);
  await page.goto("/oscope?signal=spans&field=service-name&window=1h&limit=20&live=1");
  await expect(page.locator('script[src="/oscope/live.js"]')).toHaveCount(1);
  await expect(page.locator("#oscope-screen")).toHaveAttribute("data-oscope-view-version", "1");
  await expect(page.getByText("Live refresh is on.")).toBeVisible();
  const initialDisplayed = await displayedCount(page);

  const generated = await request.post("/work");
  expect(generated.ok()).toBeTruthy();
  await expect.poll(() => displayedCount(page), {timeout: 15_000})
    .toBeGreaterThanOrEqual(initialDisplayed + spansPerWork);
  expect(refreshRequests).toBeGreaterThan(0);

  const screen = page.locator("#oscope-screen");
  const frozenStart = await screen.getAttribute("data-oscope-query-start");
  const frozenEnd = await screen.getAttribute("data-oscope-query-end");
  await page.getByRole("button", {name: "Freeze for export"}).click();
  await expect(page.getByRole("button", {name: "Frozen"})).toBeDisabled();
  await expect(page.getByText("Frozen exact window for export.")).toBeVisible();
  await expect(page).not.toHaveURL(/(?:\?|&)live=1(?:&|$)/);

  const exportForm = page.getByRole("form", {name: "Raw telemetry export"});
  await expect(exportForm.getByLabel("Start (Unix ns)")).toHaveValue(frozenStart);
  await expect(exportForm.getByLabel("End, exclusive (Unix ns)")).toHaveValue(frozenEnd);

  const frozenDisplayed = await displayedCount(page);
  const frozenRefreshRequests = refreshRequests;
  const generatedAfterFreeze = await request.post("/work");
  expect(generatedAfterFreeze.ok()).toBeTruthy();
  await page.waitForTimeout(2_500);
  expect(refreshRequests).toBe(frozenRefreshRequests);
  expect(await displayedCount(page)).toBe(frozenDisplayed);
  await expect(screen).toHaveAttribute("data-oscope-query-start", frozenStart);
  await expect(screen).toHaveAttribute("data-oscope-query-end", frozenEnd);

  await expect.poll(async () => (await summary(request)).traceCount)
    .toBe(before.traceCount + 2);
  await expect.poll(async () => (await summary(request)).spanCount)
    .toBe(before.spanCount + (2 * spansPerWork));
  assertNoBrowserErrors();
});
