const http = require("node:http");

const port = Number(process.env.DEMO_MODEL_FIXTURE_PORT || 31809);
const finding =
  "The dashboard is not stale; it is merely waiting for the server to finish " +
  "calculating the square root of -1, a process that will conclude in " +
  "approximately four billion years.";

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, {"Content-Type": "application/json"});
    response.end(JSON.stringify({ok: true}));
    return;
  }

  if (request.method === "POST" && request.url === "/v1/chat/completions") {
    request.resume();
    request.on("end", () => {
      response.writeHead(200, {"Content-Type": "application/json"});
      response.end(JSON.stringify({
        id: "fixture-completion",
        model: "fixture-model",
        choices: [{
          index: 0,
          message: {role: "assistant", content: finding},
          finish_reason: "stop",
        }],
        usage: {prompt_tokens: 53, completion_tokens: 36, total_tokens: 89},
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
