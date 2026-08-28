const {test, expect} = require("@playwright/test");
const fs = require("node:fs/promises");
const path = require("node:path");

const projectRoot = process.env.DEMO_SAMIZDAT_PROJECT;
if (!projectRoot) throw new Error("DEMO_SAMIZDAT_PROJECT is required");
const expectedPrompt = process.env.DEMO_EXPECTED_PROMPT;
if (!expectedPrompt) throw new Error("DEMO_EXPECTED_PROMPT is required");
const modelFixtureUrl = process.env.DEMO_MODEL_FIXTURE_URL;
if (!modelFixtureUrl) throw new Error("DEMO_MODEL_FIXTURE_URL is required");

function guardBrowserErrors(page) {
  const errors = [];
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console: ${message.text()}`);
  });
  return () => expect(errors, "browser emitted no errors").toEqual([]);
}

function isWorkbenchStream(response) {
  return response.status() === 200 &&
    response.url().includes("/workbench?") &&
    response.url().includes("datastar-sse=true");
}

async function json(response) {
  expect(response.ok()).toBeTruthy();
  return response.json();
}

function ancestors(span, byId) {
  const chain = [];
  let parentId = span.parentSpanId;
  const seen = new Set();
  while (parentId && !seen.has(parentId)) {
    seen.add(parentId);
    const parent = byId.get(parentId);
    if (!parent) break;
    chain.push(parent);
    parentId = parent.parentSpanId;
  }
  return chain;
}

test("the standalone Samizdat loop updates live and stores its real span tree", async ({page, request}) => {
  const assertNoBrowserErrors = guardBrowserErrors(page);
  let navigations = 0;
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) navigations += 1;
  });

  const initialStream = page.waitForResponse(isWorkbenchStream);
  await page.goto("/workbench");
  await initialStream;
  await expect(page.getByText("Real Samizdat run · compiler-woven telemetry")).toBeVisible();

  const prompt = expectedPrompt;
  await page.evaluate(() => {
    self.__samizdatLiveMutations = 0;
    const live = document.querySelector("#workbench-live");
    self.__samizdatObserver = new MutationObserver(() => {
      self.__samizdatLiveMutations += 1;
    });
    self.__samizdatObserver.observe(live, {childList: true, subtree: true, characterData: true});
  });
  const navigationsBeforeSubmit = navigations;
  const submitted = await page.evaluate(async (value) => {
    const response = await fetch("/workbench", {
      method: "POST",
      headers: {"Content-Type": "application/x-www-form-urlencoded"},
      body: new URLSearchParams({prompt: value}),
    });
    return {ok: response.ok, path: new URL(response.url).pathname};
  }, prompt);
  expect(submitted).toEqual({ok: true, path: "/workbench"});

  const live = page.locator("#workbench-live");
  await expect(live.locator(".workbench-prompt")).toHaveText(prompt);
  await expect(live.locator("p", {hasText: "Status:"})).toContainText("closed");
  await expect(live).toContainText("Fixed square and verified its regression test.");
  expect(navigations).toBe(navigationsBeforeSubmit);
  expect(await page.evaluate(() => self.__samizdatLiveMutations)).toBeGreaterThan(0);

  const edited = await fs.readFile(path.join(projectRoot, "src", "calc", "core.clj"), "utf8");
  expect(edited).toContain("(* x x)");
  expect(edited).not.toContain("(* x 2)");

  let summaries = [];
  await expect.poll(async () => {
    summaries = await json(await request.get("/api/traces"));
    return summaries.some((trace) => trace.rootSpan === "samizdat.run" && trace.spanCount >= 4);
  }).toBe(true);

  const summary = summaries.find((trace) => trace.rootSpan === "samizdat.run" && trace.spanCount >= 4);
  let detail;
  await expect.poll(async () => {
    detail = await json(await request.get(`/api/traces/${summary.traceId}`));
    const names = new Set(detail.spans.map((span) => span.name));
    return ["samizdat.run", "samizdat.turn", "samizdat.model", "samizdat.tool"]
      .every((name) => names.has(name));
  }).toBe(true);

  const byId = new Map(detail.spans.map((span) => [span.spanId, span]));
  const roots = detail.spans.filter((span) => span.name === "samizdat.run" && !span.parentSpanId);
  const turns = detail.spans.filter((span) => span.name === "samizdat.turn");
  const models = detail.spans.filter((span) => span.name === "samizdat.model");
  const tools = detail.spans.filter((span) => span.name === "samizdat.tool");
  const leaves = [...models, ...tools];
  const clients = detail.spans.filter((span) =>
    span.name === "HTTP POST" && span.kind === "client");
  const duplicateGenericClients = detail.spans.filter((span) =>
    span.name === "POST" && span.kind === "client");
  const databases = detail.spans.filter((span) =>
    ["SELECT", "INSERT", "UPDATE", "DELETE"].includes(span.name) &&
    JSON.stringify(span.attributes).includes("sqlite"));
  expect(roots).toHaveLength(1);
  expect(turns.length).toBeGreaterThan(0);
  expect(leaves.length).toBeGreaterThan(0);
  expect(clients.length).toBeGreaterThan(0);
  expect(duplicateGenericClients).toHaveLength(0);
  expect(databases.length).toBeGreaterThan(0);

  for (const turn of turns) {
    expect(ancestors(turn, byId).some((span) => span.name === "samizdat.run")).toBe(true);
  }
  for (const leaf of leaves) {
    const chain = ancestors(leaf, byId);
    expect(chain.some((span) => span.name === "samizdat.run")).toBe(true);
  }
  for (const tool of tools) {
    expect(ancestors(tool, byId)
      .some((span) => span.name === "samizdat.turn")).toBe(true);
  }
  expect(models.some((model) => ancestors(model, byId)
    .some((span) => span.name === "samizdat.turn"))).toBe(true);

  for (const client of clients) {
    const chain = ancestors(client, byId);
    expect(chain.some((span) => span.name === "samizdat.run")).toBe(true);
  }
  for (const database of databases) {
    expect(ancestors(database, byId)
      .some((span) => span.name === "samizdat.run")).toBe(true);
  }
  expect(databases.some((span) => {
    const returned = span.attributes?.["db.response.returned_rows"];
    return returned !== undefined && Number.isSafeInteger(Number(returned));
  })).toBe(true);

  // The adapter polls Samizdat's durable journal after the semantic work has
  // completed. Those read-model queries are viewer plumbing, not new traces.
  await page.waitForTimeout(1200);
  const afterIdlePoll = await json(await request.get("/api/traces"));
  expect(afterIdlePoll.filter((trace) =>
    ["SELECT", "INSERT", "UPDATE", "DELETE"].includes(trace.rootSpan)))
    .toHaveLength(0);

  const proof = await json(await request.get(`${modelFixtureUrl}/proof`));
  expect(proof.requests).toBeGreaterThan(0);
  expect(proof.promptMatches).toBeGreaterThan(0);
  expect(proof.promptTraceparents).toHaveLength(proof.promptMatches);
  expect(proof.traceparents).toHaveLength(proof.requests);
  const clientContexts = new Set(clients.map(
    (span) => `00-${span.traceId}-${span.spanId}-01`,
  ));
  expect(clientContexts.size).toBe(proof.requests);
  for (const traceparent of proof.traceparents) {
    expect(clientContexts.has(traceparent)).toBe(true);
  }
  const modelClientContexts = new Set(clients.filter((client) => {
    const chain = ancestors(client, byId);
    return chain[0]?.name === "samizdat.model" &&
      chain.some((span) => span.name === "samizdat.turn") &&
      chain.some((span) => span.name === "samizdat.run");
  }).map((span) => `00-${span.traceId}-${span.spanId}-01`));
  expect(modelClientContexts.size).toBeGreaterThan(0);
  for (const traceparent of modelClientContexts) {
    expect(proof.traceparents).toContain(traceparent);
  }
  const storedTrace = JSON.stringify(detail);
  expect(detail.spans.some((span) =>
    span.attributes?.["server.address"] === "127.0.0.1")).toBe(false);
  expect(storedTrace).not.toContain(prompt);
  expect(storedTrace).not.toContain("Fixed square and verified its regression test.");

  await page.locator(".otel-header a.otel-back-link[href=\"/\"]").click();
  await expect(page).toHaveURL(/\/$/);
  const traceLink = page.locator(`a[href="/traces/${summary.traceId}"]`).first();
  await expect(traceLink).toBeVisible();
  await traceLink.click();
  const dialog = page.locator("dialog[data-otel-dialog]");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("samizdat.run");
  await expect(dialog).toContainText("samizdat.model");
  await expect(dialog).toContainText("SELECT");
  const toolObservation = dialog.locator('.otel-observation[aria-label="Tool call"]').first();
  const toolDetails = toolObservation.locator("xpath=ancestor::details[1]");
  await toolDetails.locator(":scope > summary").click();
  await expect(toolObservation).toBeVisible();
  await expect(toolObservation)
    .toContainText("Arguments and result not recorded (privacy default)");

  assertNoBrowserErrors();
});
