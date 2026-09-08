import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const port = 8998;
const baseUrl = `http://127.0.0.1:${port}`;

let count = 0;
function ok(name) {
  count += 1;
  console.log(`ok ${count} - ${name}`);
}

async function waitForModels() {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const response = await fetch(`${baseUrl}/v1/models`);
      if (response.ok) return;
    } catch {
      // not up yet
    }
    await delay(100);
  }
  throw new Error("mock-llm did not become ready in time");
}

async function chat(message) {
  const response = await fetch(`${baseUrl}/v1/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ messages: [{ role: "user", content: message }] }),
  });
  assert.equal(response.status, 200);
  return response.json();
}

async function main() {
  const child = spawn(process.execPath, ["mock-llm.mjs"], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(port), MOCK_LLM_MODE: "normal" },
    stdio: ["ignore", "ignore", "ignore"],
  });

  try {
    await waitForModels();

    const models = await fetch(`${baseUrl}/v1/models`);
    assert.equal(models.status, 200);
    ok("1 /v1/models responde");

    const response = await chat("¿Qué cambió respecto del estudio anterior?");
    const content = response.choices[0].message.content;
    assert.match(content, /12\.3 mm a 12\.9 mm/);
    assert.match(content, /0\.6 mm/);
    assert.doesNotMatch(content, /mejor[oó]|empeor[oó]|tratamiento recomendado|diagn[oó]stico confirmado/i);
    ok("2 respuesta demo longitudinal determinística y sin diagnóstico");

    const repeat = await chat("¿Qué cambió respecto del estudio anterior?");
    assert.equal(repeat.choices[0].message.content, content);
    ok("3 respuesta repetida idéntica");

    const generic = await chat("Resumí el contexto");
    assert.match(generic.choices[0].message.content, /sin emitir diagnóstico clínico/);
    ok("4 respuesta genérica conserva guardrails clínicos");

    console.log(`mock-llm contract tests passed: ${count}`);
  } finally {
    child.kill();
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
