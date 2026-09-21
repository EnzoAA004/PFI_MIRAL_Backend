import http from "node:http";

/**
 * Stage 81-E: deterministic mock of an OpenAI-chat-completions-compatible LLM server, used only
 * by the Post-E50 dev/E2E compose stack as a stand-in for the real self-hosted Qwen2.5-7B-Instruct
 * + vLLM deployment (see docs/post_e50/80_SELF_HOSTED_LLM_GOOGLE_CLOUD.md). No real model, no GPU.
 *
 * Endpoints:
 *   GET  /v1/models            -- liveness probe target used by `SelfHostedLlmProvider.
 *                                  checkAvailability()` (Stage 81-T).
 *   POST /v1/chat/completions  -- the chat completion itself.
 *
 * `MOCK_LLM_MODE` env var controls behavior for the negative-path E2E cases (81-S timeout audit):
 *   normal (default) -- 200 with a fixed deterministic answer.
 *   error             -- 500 (simulates an upstream failure -> Backend translates to 502).
 *   timeout           -- never responds (simulates a hung provider -> Backend translates to 504).
 */
const port = Number(process.env.PORT ?? 8001);
const mode = process.env.MOCK_LLM_MODE ?? "normal";

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/v1/models") {
    if (mode === "timeout") return; // never respond -- exercises the liveness-probe timeout path too.
    if (mode === "error") {
      response.writeHead(500);
      response.end();
      return;
    }
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ data: [{ id: "Qwen2.5-7B-Instruct" }] }));
    return;
  }

  if (request.method === "POST" && request.url === "/v1/chat/completions") {
    let body = "";
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      if (mode === "timeout") return; // deliberately never respond.
      if (mode === "error") {
        response.writeHead(500, { "Content-Type": "application/json" });
        response.end(JSON.stringify({ error: "simulated upstream failure" }));
        return;
      }
      console.log("[mock-llm] received request body:", body);
      const answer = answerFor(body);
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({
        choices: [{ message: { role: "assistant", content: answer } }],
      }));
    });
    return;
  }

  response.writeHead(404, { "Content-Type": "application/json" });
  response.end(JSON.stringify({ status: "error", code: "NOT_FOUND" }));
});

server.listen(port, () => console.log(`[mock-llm] listening on ${port} (mode=${mode})`));

function answerFor(rawBody) {
  const normalized = String(rawBody ?? "").toLowerCase();
  if (normalized.includes("qué cambió") || normalized.includes("que cambio") || normalized.includes("estudio anterior")) {
    return "En la medición seleccionada, el valor efectivo pasó de 12.3 mm a 12.9 mm, una diferencia de aproximadamente 0.6 mm. El valor actual incluye una corrección profesional. No constituye diagnóstico clínico.";
  }
  return "Respuesta determinista del asistente contextual (mock). El sistema resume mediciones y revisión profesional sin emitir diagnóstico clínico.";
}
