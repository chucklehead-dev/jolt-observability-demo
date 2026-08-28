const {test, expect} = require("@playwright/test");

function guardBrowserErrors(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console: ${message.text()}`);
  });
  return () => expect(errors, "browser emitted no errors").toEqual([]);
}

async function traceCount(request) {
  const response = await request.get("/api/summary");
  expect(response.ok()).toBeTruthy();
  return (await response.json()).traceCount;
}

test("Plotje and Hiccup previews update progressively without tracing the editors", async ({page, request}) => {
  const assertNoBrowserErrors = guardBrowserErrors(page);
  const before = await traceCount(request);

  await page.goto("/plotje-editor");
  await expect(page.getByRole("heading", {name: "Plotje editor"})).toBeVisible();
  const plotjePreview = page.locator("#plotje-preview");
  await expect(plotjePreview.locator("svg")).toBeVisible();

  const plotjeSpec = page.getByLabel("Chart specification");
  await plotjeSpec.fill("{:title \"Worker queue\" :data [{:service \"worker\" :count 7}] :layers [{:mark :bar :x :service :y :count}]}");
  await expect(plotjePreview).toContainText("Worker queue");
  await plotjeSpec.fill("{:layers :not-a-vector}");
  await expect(plotjePreview).toContainText("Spec error");
  await plotjeSpec.fill("{:title \"API queue\" :data [{:service \"api\" :count 3}] :layers [{:mark :bar :x :service :y :count}]}");
  await expect(plotjePreview).toContainText("API queue");

  await page.goto("/hiccup-editor");
  await expect(page.getByRole("heading", {name: "Safe Hiccup editor"})).toBeVisible();
  const hiccupPreview = page.locator("#hiccup-preview");
  const hiccupSpec = page.getByLabel("Hiccup value");
  await hiccupSpec.fill("[:section [:h2 \"Queue health\"] [:p \"All workers ready\"]]");
  await expect(hiccupPreview.getByRole("heading", {name: "Queue health"})).toBeVisible();
  await expect(hiccupPreview).toContainText("All workers ready");
  await hiccupSpec.fill("[:script \"alert(1)\"]");
  await expect(hiccupPreview).toContainText("Spec error");

  await expect.poll(() => traceCount(request)).toBe(before);
  assertNoBrowserErrors();
});

test("both editors retain a semantic form fallback without JavaScript", async ({browser, baseURL}) => {
  const context = await browser.newContext({javaScriptEnabled: false});
  const page = await context.newPage();
  try {
    await page.goto(`${baseURL}/plotje-editor`);
    await page.getByLabel("Chart specification").fill(
      "{:title \"Scheduler queue\" :data [{:service \"scheduler\" :count 4}] :layers [{:mark :bar :x :service :y :count}]}",
    );
    await page.getByRole("button", {name: "Render"}).click();
    await expect(page).toHaveURL(`${baseURL}/plotje-editor`);
    await expect(page.locator("#plotje-preview")).toContainText("Scheduler queue");

    await page.goto(`${baseURL}/hiccup-editor`);
    await page.getByLabel("Hiccup value").fill("[:p \"Rendered by the server\"]");
    await page.getByRole("button", {name: "Render"}).click();
    await expect(page).toHaveURL(`${baseURL}/hiccup-editor`);
    await expect(page.locator("#hiccup-preview")).toContainText("Rendered by the server");
  } finally {
    await context.close();
  }
});
