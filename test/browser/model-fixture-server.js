const http = require("node:http");

const port = Number(process.env.DEMO_MODEL_FIXTURE_PORT || 31809);
const finding =
  "Reset the stream cursor to zero whenever the browser reconnects, then " +
  "poll after each wakeup; add a reconnect smoke test that checks the newest " +
  "trace appears.";
const revisedFinding =
  "Resume from Last-Event-ID, register the waiter before re-reading the " +
  "durable maximum sequence, emit only records newer than the cursor, and " +
  "advance it only after a successful write. Add a deterministic test that " +
  "inserts a notification between the first read and waiter registration, " +
  "then reconnects and asserts no gaps or duplicates.";

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, {"Content-Type": "application/json"});
    response.end(JSON.stringify({ok: true}));
    return;
  }

  if (request.method === "POST" && request.url === "/v1/chat/completions") {
    let body = "";
    request.on("data", (chunk) => {
      if (body.length < 64 * 1024) body += chunk.toString("utf8");
    });
    request.on("end", () => {
      let turnCount = 1;
      try {
        const parsed = JSON.parse(body);
        turnCount = Array.isArray(parsed.messages) ? parsed.messages.length : 1;
      } catch (_) {
        // The demo will surface malformed requests; the fixture stays bounded.
      }
      const content = turnCount > 1 ? revisedFinding : finding;
      response.writeHead(200, {"Content-Type": "application/json"});
      response.end(JSON.stringify({
        id: "fixture-completion",
        model: "fixture-model",
        choices: [{
          index: 0,
          message: {role: "assistant", content},
          finish_reason: "stop",
        }],
        usage: turnCount > 1
          ? {prompt_tokens: 121, completion_tokens: 39, total_tokens: 160}
          : {prompt_tokens: 53, completion_tokens: 36, total_tokens: 89},
      }));
    });
    return;
  }

  response.writeHead(404, {"Content-Type": "application/json"});
  response.end(JSON.stringify({error: "not found"}));
});

server.listen(port, "127.0.0.1");

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
