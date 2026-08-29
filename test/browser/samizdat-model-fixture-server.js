const http = require("node:http");

const port = Number(process.env.DEMO_MODEL_FIXTURE_PORT || 31829);
const expectedPrompt = process.env.DEMO_EXPECTED_PROMPT || "";
const proof = {requests: 0, rejectedPromptRequests: 0, promptMatches: 0,
  traceparents: [], promptTraceparents: []};

const calls = [
  {name: "task", args: {action: "create", title: "Repair square regression"}},
  {name: "task", args: {action: "claim", id: "TASK_ID"}},
  {name: "read_file", args: {path: "src/calc/core.clj"}},
  {name: "read_file", args: {path: "test/calc/core_test.clj"}},
  {
    name: "edit_file",
    args: {
      path: "src/calc/core.clj",
      old_text: "(defn square [x]\n  (* x 2))",
      new_text: "(defn square [x]\n  (* x x))",
    },
  },
  {name: "shell", args: {command: "jolt -M:test"}},
  {name: "done", args: {answer: "Fixed square and verified its regression test."}},
];

function completion(content) {
  return {
    id: "samizdat-fixture-completion",
    model: "samizdat-coding-fixture",
    choices: [{index: 0, message: {role: "assistant", content}, finish_reason: "stop"}],
    usage: {prompt_tokens: 80, completion_tokens: 20, total_tokens: 100},
  };
}

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, {"Content-Type": "application/json"});
    response.end(JSON.stringify({ok: true}));
    return;
  }

  if (request.method === "GET" && request.url === "/proof") {
    response.writeHead(200, {"Content-Type": "application/json"});
    response.end(JSON.stringify(proof));
    return;
  }

  if (request.method === "POST" && request.url === "/v1/chat/completions") {
    let body = "";
    request.on("data", (chunk) => {
      if (body.length < 256 * 1024) body += chunk.toString("utf8");
    });
    request.on("end", () => {
      let step = 0;
      try {
        const parsed = JSON.parse(body);
        const contents = (parsed.messages || []).map(
          (message) => String(message.content || ""),
        );
        const promptMatched = expectedPrompt.length > 0 &&
          contents.some((content) => content.includes(expectedPrompt));
        const traceparent = String(request.headers.traceparent || "");
        proof.requests += 1;
        if (promptMatched) proof.promptMatches += 1;
        if (/^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$/.test(traceparent)) {
          proof.traceparents.push(traceparent);
        }
        if (!promptMatched) {
          proof.rejectedPromptRequests += 1;
          console.error(JSON.stringify({proofFailure: "prompt",
            expectedPromptLength: expectedPrompt.length,
            messageContentLengths: contents.map((content) => content.length)}));
          response.writeHead(422, {"Content-Type": "application/json"});
          response.end(JSON.stringify({error: "exact expected prompt missing"}));
          return;
        }
        if (!/^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$/.test(traceparent)) {
          console.error(JSON.stringify({proofFailure: "traceparent",
            traceparentLength: traceparent.length}));
          response.writeHead(422, {"Content-Type": "application/json"});
          response.end(JSON.stringify({error: "valid traceparent missing"}));
          return;
        }
        proof.promptTraceparents.push(traceparent);
        step = (parsed.messages || []).filter(
          (message) => message.role === "assistant" &&
            String(message.content || "").includes("tool-call"),
        ).length;
      } catch (_) {
        response.writeHead(400, {"Content-Type": "application/json"});
        response.end(JSON.stringify({error: "invalid model request"}));
        return;
      }
      const call = JSON.parse(JSON.stringify(calls[Math.min(step, calls.length - 1)]));
      if (call.args.id === "TASK_ID") {
        const transcript = JSON.parse(body).messages.map(
          (message) => String(message.content || ""),
        ).join("\n");
        const match = transcript.match(/Created\s+([A-Za-z0-9._-]+)/);
        call.args.id = match ? match[1] : "missing-task-id";
      }
      const content = "```tool-call\n" + JSON.stringify(call) + "\n```";
      response.writeHead(200, {"Content-Type": "application/json"});
      response.end(JSON.stringify(completion(content)));
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
