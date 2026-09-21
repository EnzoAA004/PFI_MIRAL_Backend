const backendUrl = process.env.POST_E50_BACKEND_URL ?? "http://localhost:8180";
const fixtureId = "post-e50-demo-fixture-v1";

const studies = [
  {
    caseId: "DEMO-LUMBAR-2026-01",
    subjectRef: "DEMO-PAT-001",
    studyDate: "2026-02-01",
    description: "Demo sintético histórico A",
    priority: "low",
    expectedMajorAxisMm: 11.9,
  },
  {
    caseId: "DEMO-LUMBAR-2026-02",
    subjectRef: "DEMO-PAT-001",
    studyDate: "2026-05-10",
    description: "Demo sintético histórico B",
    priority: "medium",
    expectedMajorAxisMm: 12.3,
  },
  {
    caseId: "DEMO-LUMBAR-2026-03",
    subjectRef: "DEMO-PAT-001",
    studyDate: "2026-08-25",
    description: "Demo sintético actual con corrección profesional",
    priority: "high",
    expectedMajorAxisMm: 13.1,
    correction: 12.9,
  },
  {
    caseId: "DEMO-LUMBAR-ALT-01",
    subjectRef: "DEMO-PAT-002",
    studyDate: "2026-08-20",
    description: "Demo sintético paciente alternativo",
    priority: "low",
    expectedMajorAxisMm: 12.3,
  },
];

function log(message) {
  console.log(`[demo-seed] ${message}`);
}

async function waitForBackend() {
  for (let attempt = 1; attempt <= 60; attempt += 1) {
    try {
      const response = await fetch(`${backendUrl}/api/system/health`);
      if (response.ok) return;
    } catch {
      // still booting
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  throw new Error(`Backend did not become healthy at ${backendUrl}`);
}

async function request(path, options = {}) {
  const response = await fetch(`${backendUrl}${path}`, {
    ...options,
    headers: {
      ...(options.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
      ...(options.headers ?? {}),
    },
  });
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new Error(`${options.method ?? "GET"} ${path} failed: HTTP ${response.status} ${body}`);
  }
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) return response.json();
  return response.arrayBuffer();
}

async function optional(path, headers) {
  const response = await fetch(`${backendUrl}${path}`, { headers });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`GET ${path} failed: HTTP ${response.status}`);
  return response.json();
}

async function token() {
  const body = await request("/api/auth/demo-doctor", { method: "POST" });
  return body.accessToken;
}

function authHeaders(accessToken) {
  return { Authorization: `Bearer ${accessToken}` };
}

async function createStudy(study, accessToken) {
  const headers = authHeaders(accessToken);
  const existing = await optional(`/api/studies/${encodeURIComponent(study.caseId)}`, headers);
  if (existing) {
    log(`${study.caseId} already exists; not duplicating`);
    return;
  }

  const input = await request("/api/ai/inputs/server-side", {
    method: "POST",
    headers,
    body: JSON.stringify({
      caseId: study.caseId,
      plane: "sagittal",
      sourceKey: "fixture:sagittal_sample",
    }),
  });

  await request("/api/ai/multiplanar/run", {
    method: "POST",
    headers,
    body: JSON.stringify({
      caseId: study.caseId,
      sagittalInputId: input.inputId,
      sagittalModelKey: "sagittal_spider",
      allowContractFallback: false,
      studyMetadata: {
        subjectRef: study.subjectRef,
        studyDate: study.studyDate,
        modality: "MRI",
        description: study.description,
        reviewPriority: study.priority,
      },
      metadata: {
        fixtureId,
        source: "post-e50-demo-seed",
        inferenceMode: "real_baseline",
        requestedInferenceMode: "real_baseline",
        allowContractFallback: false,
        humanReviewRequired: true,
        notClinicalDiagnosis: true,
      },
    }),
  });

  const analysis = await request(`/api/studies/${encodeURIComponent(study.caseId)}/product-analysis`, {
    method: "POST",
    headers,
    body: JSON.stringify({ metadata: { fixtureId, mode: "demo" } }),
  });

  const measurement = analysis.discLevels?.[0]?.measurements?.[0];
  if (study.correction !== undefined && measurement?.measurementId) {
    await request(`/api/studies/${encodeURIComponent(study.caseId)}/product-analyses/${encodeURIComponent(analysis.analysisRunId)}/measurements/${encodeURIComponent(measurement.measurementId)}/corrections`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        field: "majorAxisMm",
        correctedValue: study.correction,
        comment: "Corrección demo ficticia para mostrar human-in-the-loop.",
      }),
    });
  }

  await request(`/api/studies/${encodeURIComponent(study.caseId)}/product-analyses/${encodeURIComponent(analysis.analysisRunId)}/review`, {
    method: "PATCH",
    headers,
    body: JSON.stringify({
      status: "accepted",
      comment: study.correction === undefined
        ? "Revisión demo aceptada sobre fixture sintético."
        : "Revisión demo aceptada con corrección profesional ficticia.",
    }),
  });

  log(`${study.caseId} seeded; expected majorAxisMm=${study.expectedMajorAxisMm}${study.correction !== undefined ? ` effective=${study.correction}` : ""}`);
}

async function verify(accessToken) {
  const headers = authHeaders(accessToken);
  const subjects = await request("/api/subjects", { headers });
  const history = await request("/api/subjects/DEMO-PAT-001/history", { headers });
  const series = await request("/api/subjects/DEMO-PAT-001/measurement-series?level=L4-L5&plane=sagittal&structure=disc&metric=majorAxisMm", { headers });
  const comparison = await request("/api/studies/DEMO-LUMBAR-2026-03/longitudinal-comparison?baselineCaseId=DEMO-LUMBAR-2026-02", { headers });
  const report = await request("/api/studies/DEMO-LUMBAR-2026-03/report?baselineCaseId=DEMO-LUMBAR-2026-02", { headers });
  const pdf = await request("/api/studies/DEMO-LUMBAR-2026-03/report.pdf", { headers });
  const chat = await request("/api/studies/DEMO-LUMBAR-2026-03/chat", {
    method: "POST",
    headers,
    body: JSON.stringify({ question: "¿Qué cambió respecto del estudio anterior?" }),
  });

  const values = series.points?.map((point) => point.effectiveValue) ?? [];
  const pdfHeader = new TextDecoder().decode(new Uint8Array(pdf).slice(0, 5));
  if (history.studies?.length !== 3) throw new Error(`Expected 3 DEMO-PAT-001 studies, got ${history.studies?.length}`);
  if (JSON.stringify(values) !== JSON.stringify([11.9, 12.3, 12.9])) throw new Error(`Unexpected measurement series: ${JSON.stringify(values)}`);
  const majorComparison = comparison.measurements?.find((measurement) => measurement.metric === "majorAxisMm");
  if (Math.abs((majorComparison?.deltaAbsolute ?? 0) - 0.6) > 0.01) throw new Error("Unexpected comparison delta");
  if (report.schemaVersion !== "pfi.study-report.v1") throw new Error("Unexpected report schema");
  if (pdfHeader !== "%PDF-") throw new Error(`Unexpected PDF signature ${pdfHeader}`);
  if (!JSON.stringify(chat).includes("12.9 mm")) throw new Error("Unexpected deterministic chat response");
  if (!subjects.some((subject) => subject.subjectRef === "DEMO-PAT-001")) throw new Error("DEMO-PAT-001 missing from subject list");
  log("verification ok: subjects/history/series/comparison/report/pdf/chat");
}

async function main() {
  await waitForBackend();
  const accessToken = await token();
  for (const study of studies) await createStudy(study, accessToken);
  await verify(accessToken);
  log(`${fixtureId} ready`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

