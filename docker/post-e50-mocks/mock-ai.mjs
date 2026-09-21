import http from "node:http";
import crypto from "node:crypto";

/**
 * Stage 81-E / Stage 87: deterministic mock of the FastAPI AI Module, used only by the Post-E50
 * dev/E2E compose stack (`docker-compose.post-e50.yml`) as a stand-in for the real
 * (checkpoint-heavy) `ai-module` service. Never used by the frozen demo stack. No real model,
 * no GPU, no checkpoints, no clinical semantics.
 *
 * Endpoints:
 *   GET  /health              -- liveness, matches the real ai-module's healthcheck contract.
 *   POST /inputs               -- Stage 87: multipart upload, mirrors AiServiceClient.uploadInput's
 *                                 real contract (multipart fields: file, caseId, plane) ->
 *                                 {inputId, caseId, plane, format, size} (AiInputResponseDto).
 *   POST /v2/multiplanar/run   -- Stage 87: the pfi.multiplanar-run.v2 contract
 *                                 (AiMultiplanarV2RequestDto in, AiMultiplanarV2ResponseDto out),
 *                                 same wire shape as the real, already-tested fixture at
 *                                 backend/src/test/resources/contracts/
 *                                 ai-module-multiplanar-v2-real-baseline.json -- this mock
 *                                 templates that exact shape instead of inventing a new one.
 *   POST /v1/product/analyze  -- always returns a fixed, deterministic "partial" result with one
 *                                 disc level, one measurement, and the standard 67B2/67C/P10.7
 *                                 warnings this product already treats as first-class (never
 *                                 silently upgraded to "validated"/"enabled"). Unchanged since
 *                                 Stage 81.
 *   GET  /inputs/{id}/slices/{index} -- EXT-04B3.5 E2E harness fixture: stateless per-slice
 *                                 image, always the same fixed 1x1 PNG regardless of inputId/
 *                                 index -- what AiBackendService.getSeriesSlice (already-tested
 *                                 product code) proxies to for StudyReportDto.BaseImageRef.
 *
 * Error modes (87-J): a per-request, deterministic override via a `caseId` substring convention
 * (no headers/query params needed -- the caseId is already the one identifier every caller has in
 * hand), checked case-insensitively:
 *   caseId contains "MOCK-FAIL-UPLOAD" -> POST /inputs returns 500.
 *   caseId contains "MOCK-FAIL-RUN"    -> POST /v2/multiplanar/run returns 500.
 *   caseId contains "MOCK-TIMEOUT-RUN" -> POST /v2/multiplanar/run never responds (simulates a
 *                                          hung AI Module -> Backend's own client-side timeout
 *                                          fires; see AiServiceProperties' configured timeout).
 * A whole-container default is also available via MOCK_AI_MODE (normal|error), mirroring
 * mock-llm.mjs's own convention, for the rare case a whole-stack failure mode is more convenient
 * than a per-caseId one -- most Stage 87 E2E scenarios use the per-caseId convention instead, so a
 * single running stack can exercise both the happy path and failure modes without a restart.
 */
const port = Number(process.env.PORT ?? 8000);
const mode = process.env.MOCK_AI_MODE ?? "normal";
const TINY_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR42mNkAAAAAgABSK+kcQAAAABJRU5ErkJggg==",
  "base64",
);

function productAnalysisResponse(caseId) {
  const majorAxisMm = demoMajorAxisMm(caseId);
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
          { plane: "sagittal", level: "L4-L5", structure: "disc", areaMm2: majorAxisMm, majorAxisMm, minorAxisMm: 8.4, reviewRequired: true },
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

function demoMajorAxisMm(caseId) {
  const normalized = String(caseId ?? "").toUpperCase();
  if (normalized.includes("DEMO-LUMBAR-2026-01")) return 11.9;
  if (normalized.includes("DEMO-LUMBAR-2026-02")) return 12.3;
  if (normalized.includes("DEMO-LUMBAR-2026-03")) return 13.1;
  return 12.3;
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => resolve(Buffer.concat(chunks)));
    request.on("error", reject);
  });
}

/**
 * Minimal multipart/form-data parser -- this image has no dependencies (see Dockerfile), so a
 * small hand-rolled parser is simpler than adding an npm dependency for a test-only mock. Only
 * extracts what this mock needs: field values by name, and whether a `file` part was present.
 * Not a general-purpose parser (no header decoding beyond name/filename, no nested multipart).
 */
function parseMultipart(buffer, contentType) {
  const boundaryMatch = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType ?? "");
  const boundary = boundaryMatch ? (boundaryMatch[1] ?? boundaryMatch[2]).trim() : null;
  if (!boundary) return { fields: {}, hasFile: false };
  const delimiter = Buffer.from(`--${boundary}`);
  const parts = [];
  let start = buffer.indexOf(delimiter);
  while (start !== -1) {
    const next = buffer.indexOf(delimiter, start + delimiter.length);
    if (next === -1) break;
    parts.push(buffer.slice(start + delimiter.length, next));
    start = next;
  }
  const fields = {};
  let hasFile = false;
  let fileSize = 0;
  let fileName = "";
  for (const part of parts) {
    const headerEnd = part.indexOf("\r\n\r\n");
    if (headerEnd === -1) continue;
    const headerText = part.slice(0, headerEnd).toString("utf8");
    const nameMatch = /name="([^"]+)"/i.exec(headerText);
    if (!nameMatch) continue;
    const fieldName = nameMatch[1];
    let content = part.slice(headerEnd + 4);
    if (content.slice(-2).toString() === "\r\n") content = content.slice(0, -2);
    if (/filename="/i.test(headerText)) {
      hasFile = hasFile || fieldName === "file";
      fileSize = content.length;
      const filenameMatch = /filename="([^"]*)"/i.exec(headerText);
      fileName = filenameMatch ? filenameMatch[1] : "";
    } else {
      fields[fieldName] = content.toString("utf8");
    }
  }
  return { fields, hasFile, fileSize, fileName };
}

function jsonResponse(response, status, payload) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(payload));
}

function isFailUpload(caseId) {
  return /MOCK-FAIL-UPLOAD/i.test(caseId ?? "");
}

function isFailRun(caseId) {
  return /MOCK-FAIL-RUN/i.test(caseId ?? "");
}

function isTimeoutRun(caseId) {
  return /MOCK-TIMEOUT-RUN/i.test(caseId ?? "");
}

/**
 * Stage 87-D/87-E + Stage 89-D + Stage 89-CONT: deterministic per-upload inputId, derived from
 * caseId+plane+filename+size -- never a real medical UID, never a filesystem path.
 *
 * Root cause of the 89-CONT slice-viewer 400: `AiBackendService.getSeriesSlice` validates every
 * inputId against `INPUT_ID_PATTERN = ^inp_[0-9a-f]{32}$` -- the real AI Module's own inputId
 * shape (confirmed against the Stage-87 real wire fixture, e.g. `inp_2822bf9640ab40a289050a30
 * ce2fe6fd`) -- before ever forwarding the request upstream. The mock's own `mock-input-*` shape
 * failed that check outright (confirmed empirically: `curl .../slices/0` returned
 * `400 {"message":"inputId invalido."}` from the Backend itself, never reaching this mock). This
 * is not a Backend bug -- that validation matches the real, existing contract -- so the fix is
 * entirely on the mock's side: emit the same `inp_<32-hex>` shape, still fully deterministic.
 */
function deterministicInputId(caseId, plane, fileName = "", fileSize = 0) {
  const hex = crypto.createHash("sha256").update(`${caseId}|${plane}|${fileName}|${fileSize}`).digest("hex").slice(0, 32);
  return `inp_${hex}`;
}

function extensionOf(fileName) {
  const match = /\.([a-z0-9]+)$/i.exec(fileName ?? "");
  return match ? match[1].toLowerCase() : "bin";
}

/**
 * Stage 87-F: templates the exact, already-tested pfi.multiplanar-run.v2 wire shape from
 * `backend/src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json` --
 * substituting only the request-specific identifiers (caseId, traceId, run/input ids). This mock
 * never processes real imagery: masks/landmarks/measurements are the same synthetic, technical
 * values as the real fixture (87-K: no diagnostic claim anywhere in them).
 */
// Frozen-artifact policy for sagittal_spider/sagittal-spider-final-v1 ONLY (mirrors
// MultiplanarV2RealBaselineValidator's own frozen constants exactly -- this mock must satisfy
// the real Backend's strict validator, not invent its own contract). At least 3 masks, 3
// landmarks, 9 measurements are required whenever this exact model/version pair is used.
const SAGITTAL_SPIDER_MODEL_KEY = "sagittal_spider";
const SAGITTAL_SPIDER_MODEL_VERSION = "sagittal-spider-final-v1";
const SAGITTAL_SPIDER_ARTIFACT_HASH = "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944";

function frozenSagittalArtifacts(planeRunId) {
  const centroids = ["vertebra_group", "canal", "disc_group"];
  const masks = centroids.map((classKey) => ({
    id: `mask-sagittal-${classKey}`, classKey, confidence: 0.96, enabled: true, editable: false, coordinateSpace: "model_256x256", geometry: null,
  }));
  const landmarks = centroids.map((classKey) => ({
    id: `lm-mask-sagittal-${classKey}-centroid`, labelKey: `${classKey} centroid`, x: 128.0, y: 128.0, confidence: null, coordinateSpace: "model_256x256", source: "derived_from_mask",
  }));
  const dimensions = ["area", "width", "height"];
  const measurements = centroids.flatMap((classKey) => dimensions.map((dimension) => ({
    id: `sagittal-${classKey}-${dimension}`,
    labelKey: `${classKey} ${dimension}`,
    value: 100.0,
    unit: dimension === "area" ? "mm2" : "mm",
    confidence: 0.96,
    source: "ai",
    status: "pending_review",
    plane: "sagittal",
    level: null,
    measurementBasis: "physical_spacing",
    linkedLandmarkIds: [`lm-mask-sagittal-${classKey}-centroid`],
  })));
  return { masks, landmarks, measurements };
}

function multiplanarV2Response(v2Request) {
  const caseId = v2Request.caseId ?? "unknown-case";
  const traceId = v2Request.traceId ?? `mock-trace-${caseId}`;
  const sagittalRequested = v2Request.planes?.sagittal ?? null;
  const axialRequested = v2Request.planes?.axial ?? null;
  const runId = `multi-${hashId(caseId, traceId, sagittalRequested?.inputId, axialRequested?.inputId)}`;

  function planeResponse(planeName, execution) {
    if (!execution) return null;
    const planeRunId = `plane-${hashId(caseId, planeName, execution.inputId)}`;
    const isFrozenSagittalSpider = planeName === "sagittal" && execution.modelKey === SAGITTAL_SPIDER_MODEL_KEY;
    const artifacts = isFrozenSagittalSpider
      ? frozenSagittalArtifacts(planeRunId)
      : {
          masks: [{ id: `mask-${planeName}-vertebra-group`, classKey: "vertebra_group", confidence: 0.9, enabled: true, editable: false, coordinateSpace: "model_256x256", geometry: null }],
          landmarks: [{ id: `lm-${planeName}-vertebra-group-centroid`, labelKey: "vertebra_group centroid", x: 128.0, y: 128.0, confidence: null, coordinateSpace: "model_256x256", source: "derived_from_mask" }],
          measurements: [{ id: `${planeName}-vertebra_group-area`, labelKey: "vertebra_group area", value: 4200.0, unit: "mm2", confidence: 0.9, source: "ai", status: "pending_review", plane: planeName, level: null, measurementBasis: "physical_spacing", linkedLandmarkIds: [`lm-${planeName}-vertebra-group-centroid`] }],
        };
    return {
      status: "ready",
      plane: planeName,
      runId: planeRunId,
      effectiveInferenceMode: "real_baseline",
      model: isFrozenSagittalSpider
        ? {
            key: SAGITTAL_SPIDER_MODEL_KEY,
            version: SAGITTAL_SPIDER_MODEL_VERSION,
            readiness: "real_baseline_ready",
            trainingStatus: "validated_baseline",
            artifactHash: SAGITTAL_SPIDER_ARTIFACT_HASH,
            baselineReady: true,
            availableForRealInference: true,
            manifestStatus: "baseline_manifest_ready",
            manifestValid: true,
          }
        : {
            key: execution.modelKey,
            version: `${execution.modelKey}-mock-v1`,
            readiness: "real_baseline_ready",
            trainingStatus: "validated_baseline",
            artifactHash: crypto.createHash("sha256").update(`${execution.modelKey}-mock`).digest("hex"),
            baselineReady: true,
            availableForRealInference: true,
            manifestStatus: "baseline_manifest_ready",
            manifestValid: true,
          },
      input: {
        inputId: execution.inputId,
        format: "mha",
        sizeBytes: 1024,
        nativeShape: [64, 64, 4],
        canonicalShape: [64, 64, 4],
        orientationTransform: "none",
        spacingXyzMm: [1.0, 1.0, 1.0],
        canonicalAxisSpacingMm: [1.0, 1.0, 1.0],
        selectedSliceIndex: 1,
        sliceCount: 4,
        selectedAxis: 2,
        inPlaneSpacingMm: [1.0, 1.0],
      },
      coordinateSpace: {
        name: "model_256x256", width: 256, height: 256, units: "pixel",
        origin: "top_left", xDirection: "right", yDirection: "down",
        sourceSliceIndex: 1, sourceAxis: 2,
      },
      series: [{ id: `series-${planeName}-mock`, plane: planeName, sequence: null, selectedSliceIndex: 1, sliceCount: 4, status: "served_single_slice" }],
      assets: [
        { assetName: "input.png", role: "input_preview", contentType: "image/png", generated: true, relativePath: `/assets/${planeRunId}/${planeName}/input.png` },
        { assetName: "overlay.png", role: "overlay", contentType: "image/png", generated: true, relativePath: `/assets/${planeRunId}/${planeName}/overlay.png` },
      ],
      masks: artifacts.masks,
      landmarks: artifacts.landmarks,
      measurements: artifacts.measurements,
      segmentation: null,
      quality: {
        maskCount: artifacts.masks.length, landmarkCount: artifacts.landmarks.length, measurementCount: artifacts.measurements.length,
        meanConfidence: 0.95, meanForegroundConfidence: 0.9, foregroundRatio: 0.2,
        slicePreviewCount: 1, volumeGeometry: null, slicePixels: null, discLevels: null, sliceLevels: null, warnings: [],
      },
      synthetic: false,
      fallbackReason: null,
    };
  }

  const sagittal = planeResponse("sagittal", sagittalRequested);
  const axial = planeResponse("axial", axialRequested);
  const completedPlanes = [sagittal ? "sagittal" : null, axial ? "axial" : null].filter(Boolean);
  const workspaceMode = sagittal && axial ? "dual_plane" : sagittal ? "sagittal_only" : "axial_only";

  function qualityFor(planeDto) {
    if (!planeDto) return null;
    return {
      maskCount: planeDto.masks.length, landmarkCount: planeDto.landmarks.length, measurementCount: planeDto.measurements.length,
      meanConfidence: 0.95, meanForegroundConfidence: 0.9, foregroundRatio: 0.2, warnings: [],
    };
  }

  return {
    status: "completed",
    schemaVersion: "pfi.multiplanar-run.v2",
    runId,
    traceId,
    caseId,
    workspaceMode,
    requestedInferenceMode: "real_baseline",
    effectiveInferenceMode: "real_baseline",
    synthetic: false,
    fallbackReason: null,
    requestedPlanes: [sagittalRequested ? "sagittal" : null, axialRequested ? "axial" : null].filter(Boolean),
    completedPlanes,
    planes: { sagittal, axial },
    threeD: {
      enabled: false,
      status: axial ? "blocked_insufficient_geometry" : "blocked_missing_axial",
      sourcePlaneRunIds: { sagittal: sagittal?.runId ?? null, axial: axial?.runId ?? null },
      requiredInputs: ["axial_masks", "spacing", "slice_index_mapping"],
      assets: [],
      reconstruction: null,
      warnings: [],
    },
    quality: {
      planeCount: completedPlanes.length,
      maskCount: (sagittal?.masks.length ?? 0) + (axial?.masks.length ?? 0),
      landmarkCount: (sagittal?.landmarks.length ?? 0) + (axial?.landmarks.length ?? 0),
      measurementCount: (sagittal?.measurements.length ?? 0) + (axial?.measurements.length ?? 0),
      byPlane: { sagittal: qualityFor(sagittal), axial: qualityFor(axial) },
    },
    degenerativeFindings: null,
    review: { status: "pending", required: true, approvalRequiresHumanConfirmation: true },
    governance: { humanReviewRequired: true, notClinicalDiagnosis: true, deidentified: true, diagnosisGenerated: false },
    readiness: { sagittal: Boolean(sagittal), axial: Boolean(axial), dual: Boolean(sagittal && axial) },
  };
}

function hashId(...parts) {
  return crypto.createHash("sha256").update(parts.map((part) => String(part ?? "")).join("|")).digest("hex").slice(0, 20);
}

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ status: "ok" }));
    return;
  }

  if (request.method === "POST" && request.url === "/inputs") {
    readBody(request).then((buffer) => {
      const { fields, hasFile, fileSize, fileName } = parseMultipart(buffer, request.headers["content-type"]);
      const caseId = fields.caseId ?? "";
      const plane = fields.plane ?? "sagittal";
      if (mode === "error" || isFailUpload(caseId)) {
        return jsonResponse(response, 500, { status: "error", code: "MOCK_UPLOAD_FAILURE", message: "Simulated AI Module upload failure." });
      }
      if (!hasFile) {
        return jsonResponse(response, 400, { status: "error", code: "MISSING_FILE", message: "El multipart no incluyo un part 'file'." });
      }
      jsonResponse(response, 200, {
        inputId: deterministicInputId(caseId, plane, fileName, fileSize),
        caseId,
        plane,
        format: extensionOf(fileName),
        size: fileSize,
      });
    });
    return;
  }

  if (request.method === "POST" && request.url === "/v2/multiplanar/run") {
    readBody(request).then((buffer) => {
      let v2Request = {};
      try {
        v2Request = JSON.parse(buffer.toString("utf8") || "{}");
      } catch {
        return jsonResponse(response, 400, { status: "error", code: "INVALID_JSON", message: "Cuerpo invalido." });
      }
      const caseId = v2Request.caseId ?? "";
      if (isTimeoutRun(caseId)) return; // deliberately never respond -- exercises Backend's own client timeout.
      if (mode === "error" || isFailRun(caseId)) {
        return jsonResponse(response, 500, { status: "error", code: "MOCK_RUN_FAILURE", message: "Simulated AI Module multiplanar run failure." });
      }
      jsonResponse(response, 200, multiplanarV2Response(v2Request));
    });
    return;
  }

  // EXT-04B3.5 E2E harness fixture gap: this mock never implemented the real AI Module's
  // per-slice image route, which AiBackendService.getSeriesSlice (already-tested product code)
  // proxies to. Test-only harness setup, never a change to productive Backend or Frontend code.
  // Stateless: any inputId/index is served the same fixed 1x1 PNG (TINY_PNG), matching this
  // mock's existing no-persistence convention. Stage 89 Demo Mode adds the X-Mock-* echo headers
  // so a demo run can be traced back to the inputId/index that produced each slice.
  const sliceMatch = /^\/inputs\/([^/]+)\/slices\/(\d+)$/.exec(request.url ?? "");
  if (request.method === "GET" && sliceMatch) {
    response.writeHead(200, {
      "Content-Type": "image/png",
      "Cache-Control": "no-store",
      "X-Mock-Input-Id": decodeURIComponent(sliceMatch[1]),
      "X-Mock-Slice-Index": sliceMatch[2],
    });
    response.end(TINY_PNG);
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

server.listen(port, () => console.log(`[mock-ai] listening on ${port} (mode=${mode})`));
