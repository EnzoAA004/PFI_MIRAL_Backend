# Stage 89 - Demo Script

Duration: 5-8 minutes.

## Before the Demo

Run:

```powershell
.\scripts\post-e50-demo-reset.ps1
```

Open:

`http://localhost:8188`

Login:

Use the existing demo doctor flow. No sensitive credentials are required; the backend can mint a demo professional token through `POST /api/auth/demo-doctor`, and the UI still supports the demo doctor login path.

## Walkthrough

1. Open **Pacientes**.
   - Expected: the de-identified demo list contains the latest Study for `DEMO-PAT-001` and an alternate demo patient.
   - Explain: longitudinal identity is grouped by `subjectRef`; no new Patient entity was introduced in the MVP.

2. Open the history for the primary demo patient.
   - Expected: three Studies are visible: `DEMO-LUMBAR-2026-01`, `DEMO-LUMBAR-2026-02`, `DEMO-LUMBAR-2026-03`.
   - Explain: history is derived from persisted Studies and analysis runs.

3. Open `DEMO-LUMBAR-2026-03`.
   - Expected: Reading Room loads from persisted data.
   - Explain: refresh/reopen does not require rerunning inference.

4. Open **Hallazgos IA**.
   - Expected: `partial`, `L4-L5`, warnings, human-review requirement.
   - Explain: Spring orchestrates AI service calls and persists versioned results.

5. Open the professional review/correction area.
   - Expected: automatic value `13.1 mm`, reviewer value `12.9 mm`, accepted review.
   - Explain: the professional keeps final control; the AI original value is preserved.

6. Return to the patient history and show evolution.
   - Expected: `11.9 -> 12.3 -> 12.9`.
   - Explain: comparisons are arithmetic deltas over effective measurements, not diagnostic interpretation.

7. Compare Study B vs Study C.
   - Expected: approximately `+0.6 mm`.
   - Explain: the current value includes the professional correction.

8. Open **Más -> Informe / exportación**.
   - Expected: schema `pfi.study-report.v1`, safety text, levels, measurements, warnings, limitations.
   - Explain: the report is backend-authoritative and reproducible.

9. Export JSON and PDF.
   - Expected: JSON contract and PDF `%PDF-`.
   - Explain: the export is a structured technical report, not an autonomous diagnosis.

10. Ask the assistant: "¿Qué cambió respecto del estudio anterior?"
    - Expected: deterministic answer mentioning `12.3 mm`, `12.9 mm`, `0.6 mm`, and professional correction.
    - Explain: the LLM verbalizes structured context; it does not invent clinical findings.

## Plan B

- AI mock unavailable: show seeded persisted analysis.
- LLM mock unavailable: skip assistant; the rest of the product remains functional.
- PDF unavailable: export JSON.
- Browser refresh: reopen the same Study and show persistence.
