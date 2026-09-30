import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Stage 87-AA: direct contract tests against `mock-ai.mjs`, run standalone (no Backend, no
 * Docker) -- spawns the real script as a child process on a throwaway port and talks HTTP to it,
 * the same way `AiServiceClient` will in the real stack. Not wired into `npm test`/`mvn test`
 * (this repo has no JS test runner for Backend scripts) -- run directly with
 * `node docker/post-e50-mocks/mock-ai-contract-tests.mjs`.
 */
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const port = 8999;
const baseUrl = `http://127.0.0.1:${port}`;

let count = 0;
function ok(name) {
  count += 1;
  console.log(`ok ${count} - ${name}`);
}

function multipartBody(fields, fileContent) {
  const boundary = "----mockAiContractTestBoundary";
  const parts = [];
  for (const [key, value] of Object.entries(fields)) {
    parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="${key}"\r\n\r\n${value}\r\n`);
  }
  parts.push(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.dcm"\r\nContent-Type: application/octet-stream\r\n\r\n`);
  const head = Buffer.from(parts.join(""), "utf8");
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`, "utf8");
  return { body: Buffer.concat([head, Buffer.from(fileContent), tail]), contentType: `multipart/form-data; boundary=${boundary}` };
}

async function waitForHealth() {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) return;
    } catch {
      // not up yet
    }
    await delay(100);
  }
  throw new Error("mock-ai did not become healthy in time");
}

async function main() {
  const child = spawn(process.execPath, ["mock-ai.mjs"], {
    cwd: __dirname,
    env: { ...process.env, PORT: String(port), MOCK_AI_MODE: "normal" },
    stdio: ["ignore", "ignore", "ignore"],
  });

  try {
    await waitForHealth();

    // 1. health.
    const health = await fetch(`${baseUrl}/health`);
    assert.equal(health.status, 200);
    const healthBody = await health.json();
    assert.equal(healthBody.status, "ok");
    ok("1 health responde 200 status ok");

    // 2. upload success.
    const upload = multipartBody({ caseId: "CASE-87-CONTRACT-1", plane: "sagittal" }, "synthetic-bytes");
    const uploadResponse = await fetch(`${baseUrl}/inputs`, { method: "POST", headers: { "Content-Type": upload.contentType }, body: upload.body });
    assert.equal(uploadResponse.status, 200);
    const uploadBody = await uploadResponse.json();
    assert.equal(uploadBody.caseId, "CASE-87-CONTRACT-1");
    assert.equal(uploadBody.plane, "sagittal");
    assert.match(uploadBody.inputId, /^inp_[0-9a-f]{32}$/);
    ok("2 upload success devuelve 200 con inputId/caseId/plane");

    // 2c. 89-CONT-G: reproduces AiBackendService.getSeriesSlice's own INPUT_ID_PATTERN exactly
    // (`^inp_[0-9a-f]{32}$`, backend/src/main/java/.../AiBackendService.java) -- this must fail
    // before the 89-CONT fix (a `mock-input-*` id fails it) and pass after (an `inp_<32-hex>` id
    // satisfies it). A conforming id is a precondition for the slice viewer to ever reach the
    // mock at all: the Backend rejects a non-conforming inputId with 400 before forwarding
    // upstream, regardless of what this mock itself would return.
    const backendInputIdPattern = /^inp_[0-9a-f]{32}$/;
    assert.match(uploadBody.inputId, backendInputIdPattern, "inputId no conforma al contrato real que valida AiBackendService antes de reenviar al AI Module");
    ok("2c inputId conforma al INPUT_ID_PATTERN real del Backend (root cause del 400 de slices, ahora corregido)");

    const repeatUploadResponse = await fetch(`${baseUrl}/inputs`, { method: "POST", headers: { "Content-Type": upload.contentType }, body: upload.body });
    assert.equal(repeatUploadResponse.status, 200);
    const repeatUploadBody = await repeatUploadResponse.json();
    assert.equal(repeatUploadBody.inputId, uploadBody.inputId);
    ok("2b upload success es determinístico para caseId/plano/archivo/tamaño");

    // 3. upload deterministic response shape.
    assert.equal(typeof uploadBody.format, "string");
    assert.equal(typeof uploadBody.size, "number");
    assert.ok(uploadBody.size > 0);
    ok("3 upload response tiene el shape determinístico esperado (format/size)");

    // The deterministic demo seed uses the real AI Module's JSON registration form.
    const serverSideResponse = await fetch(`${baseUrl}/inputs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        caseId: "DEMO-LUMBAR-2026-01",
        plane: "sagittal",
        sourceKey: "fixture:sagittal_sample",
      }),
    });
    assert.equal(serverSideResponse.status, 200);
    const serverSideBody = await serverSideResponse.json();
    assert.match(serverSideBody.inputId, backendInputIdPattern);
    assert.equal(serverSideBody.caseId, "DEMO-LUMBAR-2026-01");
    assert.equal(serverSideBody.plane, "sagittal");
    assert.equal(serverSideBody.format, "npy");
    assert.equal(serverSideBody.size, 262272);
    ok("3b registro server-side JSON replica el contrato real del AI Module");

    const mismatchResponse = await fetch(`${baseUrl}/inputs`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        caseId: "DEMO-LUMBAR-2026-01",
        plane: "axial",
        sourceKey: "fixture:sagittal_sample",
      }),
    });
    assert.equal(mismatchResponse.status, 400);
    ok("3c registro server-side rechaza plane/sourceKey incompatibles");

    // 4. invalid upload (no file part).
    const noFileBody = Buffer.from(`------x\r\nContent-Disposition: form-data; name="caseId"\r\n\r\nCASE-87-NO-FILE\r\n------x--\r\n`, "utf8");
    const invalidUpload = await fetch(`${baseUrl}/inputs`, { method: "POST", headers: { "Content-Type": "multipart/form-data; boundary=----x" }, body: noFileBody });
    assert.equal(invalidUpload.status, 400);
    ok("4 invalid upload (sin part file) responde 400");

    // 5. run success.
    const runRequest = {
      caseId: "CASE-87-CONTRACT-2",
      traceId: "trace-contract-2",
      inferenceMode: "real_baseline",
      allowContractFallback: false,
      planes: { sagittal: { inputId: uploadBody.inputId, modelKey: "sagittal_spider" }, axial: null },
      options: { sliceIndex: null, sliceAxis: null, sliceWindowRadius: 3, inputOrientationTransform: null },
    };
    const runResponse = await fetch(`${baseUrl}/v2/multiplanar/run`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(runRequest) });
    assert.equal(runResponse.status, 200);
    const runBody = await runResponse.json();
    assert.equal(runBody.status, "completed");
    assert.equal(runBody.schemaVersion, "pfi.multiplanar-run.v2");
    assert.ok(runBody.runId.startsWith("multi-"));
    assert.equal(runBody.caseId, "CASE-87-CONTRACT-2");
    ok("5 run success devuelve el contrato v2 completo");

    const repeatRunResponse = await fetch(`${baseUrl}/v2/multiplanar/run`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(runRequest) });
    assert.equal(repeatRunResponse.status, 200);
    const repeatRunBody = await repeatRunResponse.json();
    assert.equal(repeatRunBody.runId, runBody.runId);
    assert.equal(repeatRunBody.planes.sagittal.runId, runBody.planes.sagittal.runId);
    ok("5b run success es determinístico para caseId/trace/inputIds");

    // 6. run unknown input (mock does not validate inputId existence -- it is stateless per 87-I,
    // so an unrecognized inputId still gets a deterministic, contractually valid response; the
    // Backend's own persistence layer is what would notice a truly unknown input downstream).
    const unknownInputId = "inp_00000000000000000000000000000000";
    const unknownInputRequest = { ...runRequest, caseId: "CASE-87-CONTRACT-3", planes: { sagittal: { inputId: unknownInputId, modelKey: "sagittal_spider" }, axial: null } };
    const unknownInputResponse = await fetch(`${baseUrl}/v2/multiplanar/run`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(unknownInputRequest) });
    assert.equal(unknownInputResponse.status, 200);
    const unknownInputBody = await unknownInputResponse.json();
    assert.equal(unknownInputBody.planes.sagittal.input.inputId, unknownInputId);
    ok("6 run con inputId desconocido sigue siendo determinístico (mock stateless, 87-I)");

    // 7. sagittal_spider frozen artifact counts (>= 3 masks/landmarks, >= 9 measurements --
    // MultiplanarV2RealBaselineValidator's own frozen-release floor).
    assert.ok(runBody.planes.sagittal.masks.length >= 3);
    assert.ok(runBody.planes.sagittal.landmarks.length >= 3);
    assert.ok(runBody.planes.sagittal.measurements.length >= 9);
    assert.equal(runBody.planes.sagittal.model.version, "sagittal-spider-final-v1");
    assert.equal(runBody.planes.sagittal.model.artifactHash, "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944");
    ok("7 sagittal_spider frozen artifact counts y model fields coinciden con el validador real");

    // 8. product analyze unchanged (warnings/measurements shape from Stage 81).
    const productResponse = await fetch(`${baseUrl}/v1/product/analyze`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ caseId: "CASE-87-CONTRACT-4" }) });
    assert.equal(productResponse.status, 200);
    const productBody = await productResponse.json();
    assert.equal(productBody.status, "partial");
    assert.equal(productBody.discLevels[0].level, "L4-L5");
    assert.equal(productBody.discLevels[0].measurements[0].structure, "disc");
    ok("8 product analyze conserva forma completed/partial con measurements reales");

    const demoProductResponse = await fetch(`${baseUrl}/v1/product/analyze`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ caseId: "DEMO-LUMBAR-2026-03" }) });
    assert.equal(demoProductResponse.status, 200);
    const demoProductBody = await demoProductResponse.json();
    assert.equal(demoProductBody.discLevels[0].measurements[0].majorAxisMm, 13.1);
    assert.equal(demoProductBody.humanReviewRequired, true);
    assert.equal(demoProductBody.notClinicalDiagnosis, true);
    ok("8b product analyze soporta fixture demo case-specific sin random");

    const sliceResponse = await fetch(`${baseUrl}/inputs/${encodeURIComponent(uploadBody.inputId)}/slices/2`);
    assert.equal(sliceResponse.status, 200);
    assert.equal(sliceResponse.headers.get("content-type"), "image/png");
    const sliceBytes = new Uint8Array(await sliceResponse.arrayBuffer());
    assert.deepEqual(Array.from(sliceBytes.slice(1, 4)), [80, 78, 71]);
    ok("8c full-series slice route devuelve PNG determinístico");

    // 9. product warnings unchanged (67B2/67C).
    assert.deepEqual(productBody.warnings, ["67B2_RUNTIME_PARITY_NOT_PROVEN", "67C_RUNTIME_PARITY_NOT_PROVEN"]);
    assert.equal(productBody.discLevels[0].degenerativeFindings.status, "blocked_preprocessing_parity");
    ok("9 product analyze conserva los warnings 67B2/67C y P10.7 blocked sin reinterpretar");

    // 10. upload error mode (caseId fixture convention).
    const failUpload = multipartBody({ caseId: "CASE-87-MOCK-FAIL-UPLOAD", plane: "sagittal" }, "synthetic-bytes");
    const failUploadResponse = await fetch(`${baseUrl}/inputs`, { method: "POST", headers: { "Content-Type": failUpload.contentType }, body: failUpload.body });
    assert.equal(failUploadResponse.status, 500);
    const failUploadBody = await failUploadResponse.json();
    assert.equal(failUploadBody.code, "MOCK_UPLOAD_FAILURE");
    ok("10 upload error mode (caseId contiene MOCK-FAIL-UPLOAD) responde 500 determinístico");

    // 11. run error mode (caseId fixture convention).
    const failRunRequest = { ...runRequest, caseId: "CASE-87-MOCK-FAIL-RUN" };
    const failRunResponse = await fetch(`${baseUrl}/v2/multiplanar/run`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(failRunRequest) });
    assert.equal(failRunResponse.status, 500);
    const failRunBody = await failRunResponse.json();
    assert.equal(failRunBody.code, "MOCK_RUN_FAILURE");
    ok("11 run error mode (caseId contiene MOCK-FAIL-RUN) responde 500 determinístico");

    // 11b. timeout mode never responds (bounded wait, not the full real Backend timeout).
    const timeoutRunRequest = { ...runRequest, caseId: "CASE-87-MOCK-TIMEOUT-RUN" };
    const timeoutController = new AbortController();
    const timeoutPromise = fetch(`${baseUrl}/v2/multiplanar/run`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(timeoutRunRequest), signal: timeoutController.signal });
    let timedOut = false;
    const raceResult = await Promise.race([
      timeoutPromise.then(() => "responded"),
      delay(1500).then(() => { timedOut = true; timeoutController.abort(); return "timed-out"; }),
    ]);
    assert.equal(raceResult, "timed-out");
    assert.ok(timedOut);
    ok("11b timeout mode (caseId contiene MOCK-TIMEOUT-RUN) nunca responde");

    // 12. no clinical fields anywhere in either mock response.
    const clinicalPattern = /hernia confirmada|patolog[ií]a diagnosticada|tratamiento recomendado|diagn[oó]stico confirmado/i;
    assert.doesNotMatch(JSON.stringify(runBody), clinicalPattern);
    assert.doesNotMatch(JSON.stringify(productBody), clinicalPattern);
    ok("12 ninguna respuesta del mock contiene lenguaje clínico/diagnóstico");

    console.log(`mock-ai contract tests passed: ${count}`);
  } finally {
    child.kill();
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
