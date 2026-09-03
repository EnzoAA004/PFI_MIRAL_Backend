// EXT-04B4: isolated Docker E2E for PDF visual evidence / before-after.
//
// Reuses the EXT-07 EXT07-SUBJECT-001/A/B/C scenario verbatim (same seed SQL, same synthetic
// data) — direct DB seed used only to prepare state; the actual thing under test is the real
// GET /api/studies/{caseId}/report.pdf endpoint, served by a real Backend container over a real
// Docker network, backed by a real (mocked-AI) series-slice fetch for the embedded image.
//
// No Frontend container: the existing "Exportar PDF" button already calls this same endpoint
// unchanged (Phase 18 — Frontend needed no code change this iteration), so there is nothing
// Frontend-specific to exercise here; this script proves the Backend's own PDF generation over
// the real Docker stack. Deep PDF *content* correctness (embedded images, geometry per type,
// before/after pairing, dates, neutral wording, no sensitive data) is already exhaustively
// proven by StudyReportPdfRendererTest (17 PDFBox-based tests) — this script's job is the
// end-to-end wiring: real containers, real network, real HTTP download, real minimum size.
import { execFileSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const backendRoot = __dirname.endsWith("scripts") ? resolve(__dirname, "..") : __dirname;
const composeFile = resolve(backendRoot, "docker-compose.ext04b4-e2e.yml");
const backendUrl = process.env.EXT04B4_BACKEND_URL ?? "http://localhost:8580";
const project = process.env.EXT04B4_COMPOSE_PROJECT ?? "pfi-ext04b4-e2e";
const cycles = Number(process.env.EXT04B4_E2E_CYCLES ?? "2");

const SAGITTAL_INPUT_ID = "inp_2822bf9640ab40a289050a30ce2fe6fd";

function compose(args, options = {}) {
  return execFileSync("docker", ["compose", "-p", project, "-f", composeFile, ...args], {
    cwd: backendRoot,
    stdio: options.stdio ?? "pipe",
    encoding: options.encoding ?? "utf8",
  });
}

function assertOk(value, message) {
  if (!value) throw new Error(`EXT-04B4 E2E assertion failed: ${message}`);
}

// IEEE-754: 12.9 - 12.3 === 0.5999999999999996, not 0.6 -- strict equality on a computed delta
// is the wrong tool here, same lesson already applied in the EXT-04A/EXT-07 harnesses.
function near(actual, expected, message, epsilon = 1e-6) {
  assertOk(typeof actual === "number" && Math.abs(actual - expected) < epsilon, `${message}: expected ${expected}, got ${actual}`);
}

async function waitFor(url, label, timeoutMs = 120000) {
  const started = Date.now();
  let lastError = "";
  while (Date.now() - started < timeoutMs) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error.message;
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(`Timed out waiting for ${label}: ${lastError}`);
}

function seedSql(cycle) {
  const now = `2026-09-0${cycle}T12:00:00Z`;
  // Verbatim re-use of the EXT-07 EXT07-SUBJECT-001/A/B/C scenario (Study B: AI 12.1 ->
  // corrected 12.3; Study C: AI 12.9; B->C delta +0.6) plus one evidence region per study with
  // resolvable baseImage, so the report.pdf for Study C exercises both a standalone "Evidencia
  // visual" image and a "Comparación con estudio previo" pair against Study B's own image.
  return `
DELETE FROM domain_studies WHERE case_id LIKE 'EXT07-%';

INSERT INTO domain_studies(id, case_id, status, subject_ref, study_date, modality, description, review_priority, created_at, updated_at) VALUES
('a0000000-0000-4000-8000-00000000000a', 'EXT07-STUDY-A', 'ready', 'EXT07-SUBJECT-001', '2026-01-10', 'MRI', 'EXT-04B4 synthetic A', 'medium', '${now}', '${now}'),
('b0000000-0000-4000-8000-00000000000b', 'EXT07-STUDY-B', 'ready', 'EXT07-SUBJECT-001', '2026-02-10', 'MRI', 'EXT-04B4 synthetic B', 'medium', '${now}', '${now}'),
('c0000000-0000-4000-8000-00000000000c', 'EXT07-STUDY-C', 'ready', 'EXT07-SUBJECT-001', '2026-03-10', 'MRI', 'EXT-04B4 synthetic C', 'medium', '${now}', '${now}');

-- domain_input_resources.input_id is UNIQUE across the whole table (same real constraint that
-- surfaced during EXT-04B3.5's own first seed attempt) -- each study needs its own distinct
-- opaque id here, even though the AI product analysis run below reuses the same
-- SAGITTAL_INPUT_ID for all three (that column has no such uniqueness constraint).
INSERT INTO domain_input_resources(id, study_id, plane, input_id, format, size_bytes, description, weighting, slice_count, multiplanar, derived, analyzable, created_at) VALUES
('a0000000-0000-4000-8000-000000000101', 'a0000000-0000-4000-8000-00000000000a', 'sagittal', '${SAGITTAL_INPUT_ID}', 'synthetic', 1, 'EXT-04B4 synthetic sagittal A', 'T2', 1, false, false, true, '${now}'),
('b0000000-0000-4000-8000-000000000101', 'b0000000-0000-4000-8000-00000000000b', 'sagittal', 'inp_2822bf9640ab40a289050a30ce2fe6fb', 'synthetic', 1, 'EXT-04B4 synthetic sagittal B', 'T2', 1, false, false, true, '${now}'),
('c0000000-0000-4000-8000-000000000101', 'c0000000-0000-4000-8000-00000000000c', 'sagittal', 'inp_2822bf9640ab40a289050a30ce2fe6fc', 'synthetic', 1, 'EXT-04B4 synthetic sagittal C', 'T2', 1, false, false, true, '${now}');

INSERT INTO domain_study_runs(
  id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
  sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
  sagittal_run_id, axial_run_id, assets, metrics_snapshot, status, review_status, reviewer, reviewed_at, comments, created_at, updated_at
) VALUES
('a0000000-0000-4000-8000-000000000201', 'a0000000-0000-4000-8000-00000000000a', 'EXT04B4-MULTI-A', 'ext04b4-trace-a', 'synthetic', 'synthetic', 'ext04b4-sagittal', '', '', '', 'EXT04B4-SAG-A', '', '{}'::jsonb, '{"sourceSchemaVersion":"pfi.backend-run-snapshot.v2","status":"completed","requestedPlanes":["sagittal"],"completedPlanes":["sagittal"],"planes":{"sagittal":{"measurements":[]}},"governance":{"humanReviewRequired":true,"notClinicalDiagnosis":true}}'::jsonb, 'completed', 'accepted', 'EXT04B4 reviewer', '${now}', 'synthetic', '${now}', '${now}'),
('b0000000-0000-4000-8000-000000000201', 'b0000000-0000-4000-8000-00000000000b', 'EXT04B4-MULTI-B', 'ext04b4-trace-b', 'synthetic', 'synthetic', 'ext04b4-sagittal', '', '', '', 'EXT04B4-SAG-B', '', '{}'::jsonb, '{"sourceSchemaVersion":"pfi.backend-run-snapshot.v2","status":"completed","requestedPlanes":["sagittal"],"completedPlanes":["sagittal"],"planes":{"sagittal":{"measurements":[]}},"governance":{"humanReviewRequired":true,"notClinicalDiagnosis":true}}'::jsonb, 'completed', 'accepted', 'EXT04B4 reviewer', '${now}', 'synthetic corrected', '${now}', '${now}'),
('c0000000-0000-4000-8000-000000000201', 'c0000000-0000-4000-8000-00000000000c', 'EXT04B4-MULTI-C', 'ext04b4-trace-c', 'synthetic', 'synthetic', 'ext04b4-sagittal', '', '', '', 'EXT04B4-SAG-C', '', '{}'::jsonb, '{"sourceSchemaVersion":"pfi.backend-run-snapshot.v2","status":"completed","requestedPlanes":["sagittal"],"completedPlanes":["sagittal"],"planes":{"sagittal":{"measurements":[]}},"governance":{"humanReviewRequired":true,"notClinicalDiagnosis":true}}'::jsonb, 'completed', 'accepted', 'EXT04B4 reviewer', '${now}', 'synthetic', '${now}', '${now}');

INSERT INTO domain_ai_analysis_runs(id, study_id, case_id, schema_version, release_id, status, human_review_required, not_clinical_diagnosis, autonomous_diagnosis, trace_id, sagittal_input_id, axial_input_id, warnings, raw_response, created_at, completed_at) VALUES
('a0000000-0000-4000-8000-000000000301', 'a0000000-0000-4000-8000-00000000000a', 'EXT07-STUDY-A', 'pfi.ai-product-result.v1', 'ext04b4-synthetic', 'completed', true, true, false, 'ext04b4-a', '${SAGITTAL_INPUT_ID}', NULL, '[]'::jsonb, '{}'::jsonb, '2026-01-10T10:00:00Z', '2026-01-10T10:01:00Z'),
('b0000000-0000-4000-8000-000000000301', 'b0000000-0000-4000-8000-00000000000b', 'EXT07-STUDY-B', 'pfi.ai-product-result.v1', 'ext04b4-synthetic', 'completed', true, true, false, 'ext04b4-b', '${SAGITTAL_INPUT_ID}', NULL, '[]'::jsonb, '{}'::jsonb, '2026-02-10T10:00:00Z', '2026-02-10T10:01:00Z'),
('c0000000-0000-4000-8000-000000000301', 'c0000000-0000-4000-8000-00000000000c', 'EXT07-STUDY-C', 'pfi.ai-product-result.v1', 'ext04b4-synthetic', 'completed', true, true, false, 'ext04b4-c', '${SAGITTAL_INPUT_ID}', NULL, '[]'::jsonb, '{}'::jsonb, '2026-03-10T10:00:00Z', '2026-03-10T10:01:00Z');

INSERT INTO domain_ai_disc_level_results(id, analysis_run_id, rank_index, level, localization_status, localization_source, localization_validated, abstention_preserved, axial_pairing_status, axial_pairing_validated, axial_pairing_experimental, axial_pairing_review_required, degenerative_findings_status, automatic_inference_enabled) VALUES
('a0000000-0000-4000-8000-000000000401', 'a0000000-0000-4000-8000-000000000301', 0, 'L4-L5', 'localized', 'synthetic', true, false, 'not_required', true, false, false, NULL, true),
('b0000000-0000-4000-8000-000000000401', 'b0000000-0000-4000-8000-000000000301', 0, 'L4-L5', 'localized', 'synthetic', true, false, 'not_required', true, false, false, NULL, true),
('c0000000-0000-4000-8000-000000000401', 'c0000000-0000-4000-8000-000000000301', 0, 'L4-L5', 'localized', 'synthetic', true, false, 'not_required', true, false, false, NULL, true);

INSERT INTO domain_ai_measurement_results(id, analysis_run_id, disc_level_result_id, plane, level, structure, area_mm2, major_axis_mm, minor_axis_mm, review_required) VALUES
('a0000000-0000-4000-8000-000000000601', 'a0000000-0000-4000-8000-000000000301', 'a0000000-0000-4000-8000-000000000401', 'sagittal', 'L4-L5', 'disc', NULL, 11.9, 3.0, true),
('b0000000-0000-4000-8000-000000000601', 'b0000000-0000-4000-8000-000000000301', 'b0000000-0000-4000-8000-000000000401', 'sagittal', 'L4-L5', 'disc', NULL, 12.1, 3.1, true),
('c0000000-0000-4000-8000-000000000601', 'c0000000-0000-4000-8000-000000000301', 'c0000000-0000-4000-8000-000000000401', 'sagittal', 'L4-L5', 'disc', NULL, 12.9, 3.2, true);

-- Professional correction on B: 12.1 (AI) -> 12.3 (effective), append-only.
INSERT INTO domain_ai_measurement_corrections(id, measurement_id, analysis_run_id, field, original_value, corrected_value, unit, comment, reviewed_by, created_at) VALUES
('b0000000-0000-4000-8000-000000000701', 'b0000000-0000-4000-8000-000000000601', 'b0000000-0000-4000-8000-000000000301', 'majorAxisMm', 12.1, 12.3, 'mm', 'EXT-04B4 professional synthetic correction', 'dr.reviewer@pfi.local', '2026-02-10T12:00:00Z');

-- One evidence region per study at the same level/plane/structure, each with a resolvable
-- baseImage, so Study C's report.pdf carries both a standalone "Evidencia visual" image and a
-- "Comparación con estudio previo" image pair against B.
INSERT INTO domain_ai_evidence_regions(id, analysis_run_id, disc_level_result_id, plane, level, structure, slice_index, origin, geometry_type, geometry, model_id, model_sha256, pipeline_version, experimental, label, created_at) VALUES
('b0000000-0000-4000-8000-000000000801', 'b0000000-0000-4000-8000-000000000301', 'b0000000-0000-4000-8000-000000000401', 'sagittal', 'L4-L5', 'disc', 6, 'ai', 'BBOX', '{"x":60,"y":60,"width":20,"height":20}'::jsonb, 'sagittal_spider', 'sha256:ext04b4', 'pfi.multiplanar-run.v2', false, 'Región de interés', '${now}'),
('c0000000-0000-4000-8000-000000000801', 'c0000000-0000-4000-8000-000000000301', 'c0000000-0000-4000-8000-000000000401', 'sagittal', 'L4-L5', 'disc', 6, 'ai', 'BBOX', '{"x":62,"y":58,"width":20,"height":20}'::jsonb, 'sagittal_spider', 'sha256:ext04b4', 'pfi.multiplanar-run.v2', true, 'Región de interés', '${now}');
`;
}

function resetStack() {
  try {
    compose(["down", "-v", "--remove-orphans"], { stdio: "inherit" });
  } catch {
    // First reset may run before the stack exists.
  }
}

function seed(cycle) {
  execFileSync("docker", ["compose", "-p", project, "-f", composeFile, "exec", "-T", "postgres", "psql", "-U", "pfi_ext04b4", "-d", "pfi_ext04b4", "-v", "ON_ERROR_STOP=1"], {
    cwd: backendRoot,
    input: seedSql(cycle),
    stdio: ["pipe", "inherit", "inherit"],
    encoding: "utf8",
  });
}

async function token() {
  const response = await fetch(`${backendUrl}/api/auth/demo-doctor`, { method: "POST" });
  assertOk(response.ok, `demo-doctor returned HTTP ${response.status}`);
  const body = await response.json();
  return body.accessToken;
}

async function runCycle(cycle) {
  console.log(`EXT-04B4 E2E cycle ${cycle}: reset/start`);
  resetStack();
  compose(["up", "-d", "--build"], { stdio: "inherit" });
  await waitFor(`${backendUrl}/api/system/health`, "backend health");
  seed(cycle);
  const cycleToken = await token();

  // Cross-check via the JSON report (already exhaustively proven end-to-end by EXT-07's own
  // harness) that the scenario's numeric facts are exactly what the PDF is expected to carry.
  const jsonResponse = await fetch(`${backendUrl}/api/studies/EXT07-STUDY-C/report`, { headers: { Authorization: `Bearer ${cycleToken}` } });
  assertOk(jsonResponse.ok, `json report returned ${jsonResponse.status}`);
  const json = await jsonResponse.json();
  const measurement = json.measurements.find((m) => m.level === "L4-L5" && m.structure === "disc");
  assertOk(measurement, "Study C measurement missing");
  near(measurement.effectiveValue, 12.9, "Study C effective value must be 12.9 before generating the PDF");
  const bcRow = json.longitudinalHistory?.comparisons?.find((row) => row.previousCaseId === "EXT07-STUDY-B");
  assertOk(bcRow, "B->C comparison row missing");
  near(bcRow.absoluteDelta, 0.6, "B->C delta must be +0.6 before generating the PDF");
  assertOk(json.visualEvidence.length >= 1, "Study C must carry at least one effective visual evidence item");
  assertOk(json.beforeAfterEvidence.length >= 1, "Study C must carry a before/after evidence pairing against Study B");

  // The actual thing under test: a real PDF download over the real Docker network.
  const pdfResponse = await fetch(`${backendUrl}/api/studies/EXT07-STUDY-C/report.pdf`, { headers: { Authorization: `Bearer ${cycleToken}` } });
  assertOk(pdfResponse.ok, `report.pdf returned ${pdfResponse.status}`);
  assertOk((pdfResponse.headers.get("content-type") ?? "").includes("application/pdf"), "content-type must be application/pdf");
  const pdfBytes = new Uint8Array(await pdfResponse.arrayBuffer());
  assertOk(new TextDecoder().decode(pdfBytes.slice(0, 5)) === "%PDF-", "PDF must start with the %PDF- header");
  // A PDF carrying at least 2 embedded raster images (the standalone evidence item + the
  // before/after pair) must clearly exceed the ~2-3KB a text-only report produced before this
  // iteration (StudyReportPdfRendererTest already proves the images are genuinely embedded,
  // not just padding — this is the E2E-level corroboration over a real Docker-served response).
  assertOk(pdfBytes.length > 5000, `PDF with embedded images should exceed 5KB, got ${pdfBytes.length} bytes`);

  console.log(`EXT-04B4 E2E cycle ${cycle}: OK (report.pdf = ${pdfBytes.length} bytes)`);
}

async function main() {
  for (let cycle = 1; cycle <= cycles; cycle += 1) {
    await runCycle(cycle);
  }
  compose(["down", "-v", "--remove-orphans"], { stdio: "inherit" });
  console.log(`EXT-04B4 PDF visual evidence Docker E2E: ${cycles} cycles OK`);
}

main().catch((error) => {
  console.error(error);
  try {
    compose(["down", "--remove-orphans"], { stdio: "inherit" });
  } catch {
    // Best effort only; do not touch volumes on failure outside explicit cycle reset.
  }
  process.exit(1);
});
