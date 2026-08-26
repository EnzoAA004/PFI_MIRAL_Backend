import http from "node:http";

/**
 * Stage 81-E: deterministic mock of the FastAPI AI Module, used only by the Post-E50 dev/E2E
 * compose stack (`docker-compose.post-e50.yml`) as a stand-in for the real (checkpoint-heavy)
 * `ai-module` service. Never used by the frozen demo stack. No real model, no GPU, no checkpoints.
 *
 * Endpoints:
 *   GET  /health              -- liveness, matches the real ai-module's healthcheck contract.
 *   POST /v1/product/analyze  -- always returns a fixed, deterministic "partial" result with one
 *                                 disc level, one measurement, and the standard 67B2/67C/P10.7
 *                                 warnings this product already treats as first-class (never
 *                                 silently upgraded to "validated"/"enabled").
 */
const port = Number(process.env.PORT ?? 8000);

function productAnalysisResponse(caseId) {
  return {
    schemaVersion: "pfi.ai-product-result.v1",
    runId: `mock-run-${caseId}`,
    traceId: `mock-trace-${caseId}`,
    caseId,
    release: { releaseId: "post-e50-ai-product-freeze-v1" },
    status: "partial",
    planes: null,
    discLevels: [
      {
        level: "L4-L5",
        localization: { status: "localized", validated: true, source: "67B2", abstentionPreserved: false, adapterPending: false, warnings: [] },
        axialPairing: { status: "experimental", validated: false, experimental: true, reviewRequired: true, adapterPending: false, source: "67C", warnings: [] },
        measurements: [
          { plane: "sagittal", level: "L4-L5", structure: "disc", areaMm2: 12.3, majorAxisMm: 12.3, minorAxisMm: 8.4, reviewRequired: true },
        ],
        degenerativeFindings: { status: "blocked_preprocessing_parity", automaticInferenceEnabled: false, checkpointSha256: "mock-sha", reviewRequired: true },
      },
    ],
    warnings: ["67B2_RUNTIME_PARITY_NOT_PROVEN", "67C_RUNTIME_PARITY_NOT_PROVEN"],
    review: { required: true, status: "pending", professionalReviewRequired: true },
    notClinicalDiagnosis: true,
    autonomousDiagnosis: false,
    humanReviewRequired: true,
  };
}

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ status: "ok" }));
    return;
  }
  if (request.method === "POST" && request.url === "/v1/product/analyze") {
    let body = "";
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      let caseId = "unknown-case";
      try {
        caseId = JSON.parse(body || "{}").caseId ?? caseId;
      } catch {
        // A malformed body still gets a deterministic response -- this mock never 500s on bad JSON.
      }
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify(productAnalysisResponse(caseId)));
    });
    return;
  }
  response.writeHead(404, { "Content-Type": "application/json" });
  response.end(JSON.stringify({ status: "error", code: "NOT_FOUND" }));
});

server.listen(port, () => console.log(`[mock-ai] listening on ${port}`));
