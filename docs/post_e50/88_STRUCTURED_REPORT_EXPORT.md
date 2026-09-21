# Stage 88 - Structured Report / Export

## Status

`STRUCTURED_REPORT_EXPORT_READY`

The Study report is generated on demand by the Backend as the source of truth. The
Frontend consumes the report contract and only renders/downloads what the Backend
returns.

## Backend contract

Endpoints:

- `GET /api/studies/{caseId}/report`
- `GET /api/studies/{caseId}/report.json`
- `GET /api/studies/{caseId}/report.pdf`

Optional query:

- `baselineCaseId`

Schema:

- `schemaVersion: pfi.study-report.v1`

The report includes:

- Study metadata safe for display.
- Effective analysis summary selected through `EffectiveAnalysisRunSelector`.
- Review status and comment.
- Disc levels.
- Effective measurements resolved through `EffectiveMeasurementResolver`.
- Optional longitudinal comparison through `LongitudinalComparisonService`.
- Warnings, limitations, safety flags, and traceability.

Privacy guard:

- No `subjectRef` is included in the report DTO.
- No `PatientName`, `PatientID`, DICOM UID, internal file path, or raw AI payload is
  emitted by the report contract.

Safety guard:

> Este informe corresponde a un prototipo de apoyo al análisis y no constituye un diagnóstico médico autónomo.

## Frontend integration

The Reading Room "Más" panel now loads the structured report from:

`GET /api/studies/{caseId}/report`

The export buttons download:

- JSON from `/report.json`
- PDF from `/report.pdf`

The Frontend parser rejects stale `mm2` units and requires exact `mm²` for square
millimetres in the Stage 88 report contract.

## Validation

Frontend contract tests:

- `npm run test:post-e50-structured-report`
- `32/32 PASS`

Frontend type/build:

- `npm run typecheck` PASS
- `npm run build` PASS

Backend product compile:

- `mvn "-Dmaven.test.skip=true" package` PASS

Docker/browser E2E:

- `npm run test:e2e:post-e50-structured-report` PASS
- Runtime: `http://localhost:8188` Frontend, `http://localhost:8180` Backend.
- Evidence case: `CASE-88-REPORT-1787771868714`.
- Confirmed UI consumes `/report`.
- Confirmed `/report`, `/report.json`, and `/report.pdf`.
- Confirmed no observed `/api/patients` legacy requests.

Known validation limitation:

- `mvn "-Dtest=StudyReportServiceTest,StudyReportControllerTest" test` is blocked by
  pre-existing repository-wide test compilation errors unrelated to Stage 88. Product
  code compiles successfully when test compilation is skipped.
