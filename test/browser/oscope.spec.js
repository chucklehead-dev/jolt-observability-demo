const {test, expect} = require("@playwright/test");

async function summary(request) {
  const response = await request.get("/api/summary");
  expect(response.ok()).toBeTruthy();
  return response.json();
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
