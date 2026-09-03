# EXT-00 - Extensions Baseline Verification / Freeze

Fecha: 2026-09-02

## Baseline verificado

| Repo | Path local | Rama fuente Post-E50 | SHA usado |
| --- | --- | --- | --- |
| Backend | `C:\Users\enzoa\Documents\GitHub\PFI_MVPTest_Enzo_Backend` | `origin/research/post-e50-spring-ai-product-contract` | `b0702b5664191033bdc8fbdff47a39ae8f1d06a1` |
| Frontend | `C:\Users\enzoa\Documents\GitHub\PFI_MVPTest_Enzo_Frontend` | `origin/research/post-e50-frontend-product-analysis` | `4410484332f71fe788cb2eeb3197413b34527966` |
| AI Module | `C:\Users\enzoa\Documents\GitHub\PFI_MVPTest_Enzo_AImodule` | `origin/research/post-e50-fastapi-ai-orchestration` | `96c0337d0ccad6ddc16954cf3ee408bb10b69584` |

## Ramas creadas

- Backend: `extensions/post-e50-baseline` desde `b0702b5664191033bdc8fbdff47a39ae8f1d06a1`.
- Frontend: `extensions/post-e50-baseline` desde `4410484332f71fe788cb2eeb3197413b34527966`.
- AI Module: `extensions/post-e50-baseline` desde `96c0337d0ccad6ddc16954cf3ee408bb10b69584`, creada luego de verificar que el working tree no tenía modificaciones trackeadas de código y que `.tmp_pytest/` eran no trackeados/ignorados.

## Inventario funcional observado

- Backend conserva `pfi.study-report.v1`, export JSON/PDF, `EffectiveAnalysisRunSelector`, `EffectiveMeasurementResolver`, `LongitudinalComparisonService`, historial por `Study.subjectRef`, correcciones profesionales y endpoints de reporte.
- Frontend conserva UI de informe estructurado/export, panel de comparación longitudinal, historial de paciente, parseadores estrictos y tests Post-E50.
- AI Module conserva la rama remota `research/post-e50-fastapi-ai-orchestration`; no se modificaron archivos del working tree.

## Stage 89 / Demo Mode

El baseline de extensiones queda congelado desde los heads reales de las ramas Post-E50 verificadas arriba. Cualquier trabajo local o historico de Demo Mode / Stage 89 que no este presente en esos SHAs no forma parte de EXT-00 y no se reconstruye en esta tarea.

## Limitaciones

- No se movieron ramas Post-E50 oficiales ni `main`.
- El fetch ya había sido realizado manualmente antes de esta verificación.
- El AI Module mantiene `.tmp_pytest/` como artefactos no trackeados/ignorados; no se limpiaron por restriccion explicita de auditoria read-only.
