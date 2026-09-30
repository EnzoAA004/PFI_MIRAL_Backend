# EXT-04B2 — Visual Evidence Backend Foundation

Estado: **implementado** (Backend únicamente). Sin frontend, sin PDF con imagen, sin Grad-CAM,
sin feedback loop, sin reentrenamiento. Rama `extensions/ext-04-visual-evidence-backend`, creada
exactamente desde `89115ac` (tip de `extensions/ext-04-report-enrichment-backend`, EXT-04A).

## Contradicción encontrada y resuelta (reportada antes de escribir la migration)

El diseño de EXT-04B1 asumía que la geometría IA (`PlaneMaskV2.geometry`, `PlaneSegmentationV2`,
`PlaneLandmarkV2`) podía integrarse directamente al mismo pipeline que ya usa el sistema de
revisión/reporte (`AiProductAnalysisRun`/`AiDiscLevelResult`/`AiMeasurementResult`,
`AiDiscLevelReview`/`AiProductAnalysisRunReview`, `StudyReportDto`).

Auditando el código real (`AiProductAnalyzeResponseDto`, el DTO que modela literalmente
`pfi.ai-product-result.v1` — la fuente de esa familia) se confirmó que **esa familia no tiene
ningún campo de geometría en ningún nivel**: `Segmentation` sólo trae `maskClasses: List<String>`,
`Morphometry` sólo trae un conteo, `Measurement` sólo trae escalares. La geometría real
(`PlaneMaskV2`/`PlaneSegmentationV2`/`PlaneLandmarkV2`) vive en un pipeline **distinto y separado**
(`pfi.multiplanar-run.v2`, `CanonicalPlaneRun`), con su propio sistema de revisión más antiguo
(`RunReview`/`MeasurementCorrection`, `ReviewerAnnotation`).

**Resolución adoptada:** `AiEvidenceRegion` se ancla al pipeline B (`AiProductAnalysisRun`/
`AiDiscLevelResult`/`AiMeasurementResult`), porque ahí es donde ya viven el sistema de revisión y
`StudyReportDto` que EXT-04A construyó. La ingesta de geometría IA real desde el pipeline A
(`multiplanar-v2`) **no se automatiza en esta iteración** — no existe hoy un mapeo probado y
seguro entre una corrida `multiplanar-v2` y un `AiDiscLevelResult` específico, y construirlo sin
evidencia habría arriesgado emparejar geometría con el hallazgo equivocado. En su lugar se
construyó `EvidenceRegionIngestionService.ingestAiGeometry(...)`: un punto de captura explícito,
ya guardado, ya testeado, que puede persistir geometría IA una vez que un llamador ya resolvió
externamente a qué disc level pertenece — pero que **no está conectado a ningún controller ni
flujo automático todavía**. Ver "Decisiones a revisar antes de Frontend" al final.

## Modelo de dominio

- `EvidenceGeometry` (`domain/EvidenceGeometry.java`): unión sellada `Bbox | Ellipse | Polygon |
  MaskReference`, cada una valida sus invariantes en el constructor compacto (rango 0..256,
  polígono con ≥3 puntos, `MaskReference` sin path traversal). `fromMap`/`toMap` son el único
  punto de (de)serialización a JSONB.
- `AiEvidenceRegion` (`domain/AiEvidenceRegion.java`): la fila raíz — nunca se actualiza ni se
  borra una vez creada. Exige al menos un dato de proveniencia cuando `origin="ai"`. No duplica
  estado de revisión (eso vive en `AiDiscLevelReview`/`AiProductAnalysisRunReview`, reutilizados
  tal cual).
- `AiEvidenceRegionCorrection` (`domain/AiEvidenceRegionCorrection.java`): append-only, espeja
  `AiMeasurementCorrection` exactamente — snapshot de la geometría IA original + geometría
  corregida, nunca sobrescribe la raíz.
- `DerivedEvidenceOrigin` (`domain/DerivedEvidenceOrigin.java`): enum de 4 valores, siempre
  **derivado** (`derive(origin, hasCorrection, reviewStatus)`), nunca persistido como columna.
- `EvidenceCoordinateNormalizer` (`domain/EvidenceCoordinateNormalizer.java`): el único punto de
  conversión pixel-IA → base canónica 0..256.
- `EvidenceIngestionGuard` (`domain/EvidenceIngestionGuard.java`): rechaza geometría de P10.7
  bloqueado; exige `experimental=true` para geometría de 67C.

## Sistema de coordenadas — reverificado, no asumido

Se releyó `ReviewerAnnotation.java` (Backend) y `MriSliceViewer.tsx`/`MeasurementLayer.tsx`
(Frontend, repo ya clonado localmente) antes de escribir código: ambos confirman la base
normalizada 0..256 como la convención real y vigente, no sólo la propuesta de EXT-04B1. Se
mantiene esa base — nada de coordenadas físicas DICOM, que no existen en ningún punto de este
stack hoy.

## Persistencia

Migración `docs/migrations/V20260903_019_ai_evidence_regions.sql` (numeración auditada: seguía a
`V20260825_018`). Dos tablas nuevas, ninguna alteración a tablas existentes:

- `domain_ai_evidence_regions` — raíz, `origin`/`geometry_type` con CHECK, `geometry`/
  `coordinate_space_ref` JSONB, FKs a `domain_ai_analysis_runs`/`domain_ai_disc_level_results`/
  `domain_ai_measurement_results` (todas nullable salvo la del run).
- `domain_ai_evidence_region_corrections` — append-only, mismo patrón que
  `domain_ai_measurement_corrections`.

**Validación real ejecutada:** las 15 migraciones (`V20260716_005` .. `V20260903_019`) se
aplicaron en orden alfabético contra un Postgres 16 limpio y descartable (`docker run
postgres:16-alpine`, sin volúmenes históricos, sin `--prune`, contenedor eliminado al terminar) —
las 15 aplicaron sin error, y `\d domain_ai_evidence_regions`/`\d
domain_ai_evidence_region_corrections` confirmaron columnas, CHECKs, índices y FKs exactamente
como se diseñaron. Adicionalmente, `AiEvidenceRegionMigrationContractTest` (mismo patrón que el ya
existente `StudyRunMigrationContractTest`) corre en cada `mvn test` sin depender de Docker.

## Revisión — sin tercera máquina de estados

`AiEvidenceRegion` no tiene columna de estado propia más allá de `origin`. La pregunta "¿está
pendiente/aceptada/observada/rechazada?" se responde con `AiDiscLevelReview` (si la región nombra
un `discLevelResultId`) o `AiProductAnalysisRunReview` (fallback a nivel de run) — exactamente el
mismo fallback que `StudyReportService.discLevels()` ya usaba. `observed` se trata igual que
`accepted` (mismo criterio que `EffectiveAnalysisRunSelector.PREFERRED_REVIEW_STATUSES`).

## `EffectiveAnnotationResolver`

`service/EffectiveAnnotationResolver.java`. Política (contrastada contra el sistema de revisión
real, no adoptada a ciegas):

1. existe corrección → geometría corregida, siempre efectiva;
2. `origin="professional"` → su propia geometría, siempre efectiva;
3. `origin="ai"`, revisión `accepted`/`observed` → efectiva;
4. `origin="ai"`, revisión `pending` → efectiva sólo si el consumidor pasa
   `includeUnreviewedSuggestions=true` (decisión de política, no de datos);
5. `origin="ai"`, revisión `rejected`, sin corrección → **nunca** efectiva. Si luego aparece una
   corrección, deja de ser "la sugerencia rechazada de la IA" y pasa a ser
   `PROFESSIONAL_CORRECTED` — una re-autoría activa del profesional pesa más que un rechazo previo
   más genérico.

Nada se borra ni se oculta a nivel de datos: `effective=false` es una proyección, no un delete —
las filas rechazadas/pendientes siguen existiendo y son consultables.

## Ingesta desde IA

`service/EvidenceRegionIngestionService.java`, método único `ingestAiGeometry(...)`, guardado por
`EvidenceIngestionGuard` (rechaza P10.7, exige `experimental=true` para 67C). **No está conectado
a ningún controller** — ver la sección de contradicción arriba.

## Reporte estructurado

`StudyReportDto.visualEvidence: List<VisualEvidenceSummary>` — aditivo, siempre presente (lista
vacía si no hay evidencia, nunca omitido). Sólo se listan las regiones **efectivas** (mismo
criterio que `MeasurementSummary`, que tampoco expone el historial de correcciones). Cada item:
`evidenceId, plane, level, structure, geometryType, geometry, source, reviewStatus, label,
warning, experimental, baseImage` — `source` ∈ `ai_suggested|ai_validated|professional_corrected|
professional_drawn`. Nunca se serializa `comment`/`authoredBy`/reviewer.

## Image context (EXT-04B3.5) — `baseImage`

Problema real encontrado al auditar el consumo desde Frontend: `VisualEvidenceSummary` no traía
ninguna referencia a una imagen de corte, así que Frontend podía dibujar la geometría pero nunca
alinearla contra el corte real que la originó.

Auditado antes de tocar código (per 89-CONT: rastrear la cadena real, no inferirla):

- `AiEvidenceRegion` ya tiene `analysisRunId`, `plane`, `sliceIndex` — el `sliceIndex` no se
  usaba en ningún lado del reporte hasta ahora.
- `analysisRunId` referencia un `AiProductAnalysisRun`, **no** un `planeRunId` de
  `multiplanar-v2` — son dos pipelines distintos en este código (ya documentado arriba, sección
  de contradicción). El endpoint de assets existente,
  `GET /api/ai/assets/{runId}/{plane}/{assetName}` (`AiBackendService.getAsset`), resuelve
  `RunArtifact` por `planeRunId` (`MultiplanarRunPersistenceService`) — un identificador que un
  `AiProductAnalysisRun` **no tiene**. Reutilizar ese endpoint habría requerido inventar un
  puente entre dos pipelines no relacionados, justo lo que esta iteración tiene prohibido hacer
  ("no inventar un segundo sistema de assets" se lee aquí como "no inventar un puente no
  probado tampoco").
- `AiProductAnalysisRun` sí tiene `sagittalInputId`/`axialInputId` — el mismo `inputId` opaco
  (`inp_<32-hex>`) que ya usa `POST /inputs` y que `AiBackendService.getSeriesSlice` ya valida y
  sirve en `GET /api/ai/series/{inputId}/slices/{index}` (Stage 87/89 — el mismo endpoint cuyo
  contrato de `inputId` se corrigió en el mock del Stage 89-CONT).

Conclusión: **no se creó ningún mecanismo nuevo**. `baseImage` es un `record BaseImageRef(String
inputId, Integer sliceIndex)`, resuelto en `StudyReportService.baseImageRef(region, run)` como
`run.sagittalInputId()` o `run.axialInputId()` según `region.plane()`, junto con
`region.sliceIndex()`. Frontend ya sabe pedir `GET /api/ai/series/{inputId}/slices/{index}` desde
Stage 87/89 — esto sólo le da los dos valores que le faltaban para hacerlo por región de
evidencia.

Fail-safe, nunca fabricado: `baseImage` es `null` cuando `region.sliceIndex()` es `null`, cuando
`region.plane()` no es `"sagittal"` ni `"axial"`, o cuando el run no tiene `inputId` para ese
plano. Nunca se inventa un `sliceIndex` por defecto (p. ej. `0`) para una región que no lo trae.

No se tocó: la geometría canónica 0..256, `EffectiveAnnotationResolver`, el append-only de
correcciones, ni la semántica de revisión — exactamente lo que el pedido de esta iteración exige.

## Asset security

Sin endpoint nuevo. `MASK_REFERENCE` sólo permite `{runId, plane, assetName}`, validado contra
path traversal en el constructor de `EvidenceGeometry.MaskReference` (rechaza `..`, `/`, `\`).
Se resuelve, cuando corresponda, contra el endpoint ya existente
`/api/ai/assets/{runId}/{plane}/{assetName}`.

## Tests (25/25 del pedido, ver mapeo)

| # | Escenario | Archivo |
|---|---|---|
| 1-5 | BBOX/ELLIPSE/POLYGON/MASK_REFERENCE válidos + tipo inválido rechazado | `EvidenceGeometryTest` |
| 6-7 | Normalización de coordenadas + fuera de rango | `EvidenceCoordinateNormalizerTest` |
| 8 | Proveniencia IA preservada | `EffectiveAnnotationResolverTest.aiOriginPreservesModelProvenance` |
| 9 | Origen profesional | `EffectiveAnnotationResolverTest.professionalOriginNeedsNoAiProvenanceAndIsAlwaysEffective` |
| 10 | Reviewer no leak | `StudyReportServiceTest.reportIncludesEffectiveVisualEvidenceAndExcludesRejectedOnes` |
| 11 | Corrección append-only | `EffectiveAnnotationResolverTest.appendOnlyCorrectionPreservesTheOriginalAiGeometryUnmodified` |
| 12 | Original IA preservada | ídem |
| 13 | Rechazada no es efectiva | `EffectiveAnnotationResolverTest.rejectedAiEvidenceWithoutCorrectionIsNotEffective` |
| 14 | Corregida > IA | `EffectiveAnnotationResolverTest.latestOfMultipleCorrectionsWins` |
| 15 | Dibujada por profesional | `professionalOriginNeedsNoAiProvenanceAndIsAlwaysEffective` |
| 16-17 | Sugerida permitida/bloqueada | `unreviewedAiSuggestionIsEffectiveOnlyWhenTheConsumerAllowsSuggestedEvidence` |
| 18 | Derivación de estado | `DerivedEvidenceOriginTest` (6 casos) |
| 19 | Experimental preservado | `experimentalFlagSurvivesIntoTheEffectiveAnnotation` |
| 20 | P10.7 no elevado | `EvidenceIngestionGuardTest` + `EvidenceRegionIngestionServiceTest.refusesToIngestGeometryFromBlockedP10_7` |
| 21 | 67C no elevado | ídem, `refusesToIngest67CGeometryNotFlaggedExperimental` |
| 22-23 | Reporte sin/con evidencia | `StudyReportServiceTest.reportHasEmptyVisualEvidenceWhenNoRegionsExist` / `reportIncludesEffectiveVisualEvidenceAndExcludesRejectedOnes` |
| 24 | Backward compatibility | `oldReportShapeStillDeserializesWithoutTheVisualEvidenceField` |
| 25 | Sin leak de paths/tokens/reviewer | `reportIncludesEffectiveVisualEvidenceAndExcludesRejectedOnes` (asserts adicionales) |

Suite completa del Backend: **935 tests, 0 failures, 0 errors, 68 skipped** (los 68 dependen de
Testcontainers/Docker-in-Docker, no disponible en este entorno de verificación — mismo gap ya
documentado en el cierre de EXT-04A).

**EXT-04B3.5 addendum**: 6 tests nuevos en `StudyReportServiceTest`
(`visualEvidenceExposesBaseImageReferenceFromTheRunsOwnSagittalInputId`,
`visualEvidenceBaseImageUsesTheRegionsOwnSliceIndexAndAxialPlane`,
`visualEvidenceBaseImageIsNullWhenTheRegionHasNoSliceIndex`,
`visualEvidenceJsonWithBaseImageStillLeaksNoReviewerOrPath`,
`visualEvidenceExperimentalRegionKeepsBaseImageAndExperimentalFlagTogether`,
`oldReportShapeWithVisualEvidenceButNoBaseImageFieldStillDeserializes`). Suite dirigida
(`EvidenceRegion*`, `StudyReport*`, `*Review*`, `*Longitudinal*`, `*AiProduct*`): **176 tests, 0
failures, 0 errors, 21 skipped** (mismo gap de Testcontainers/Docker).

## Limitaciones

- Ninguna geometría IA real fue ingerida automáticamente todavía — el punto de captura existe y
  está testeado, pero no está conectado a `/v1/product/analyze` ni a ningún otro flujo.
- No hay UI, no hay dibujo, no hay PDF con imagen.
- Un profesional revisando su propia región dibujada (no una corrección de IA) no tiene todavía
  un mecanismo de versionado propio — se puede crear una nueva fila `origin="professional"`, pero
  no hay una tabla de versiones para ese caso específico (sí para corrección de geometría IA).
- Los 68 tests dependientes de Testcontainers no se ejecutaron contra Postgres real en CI de esta
  sesión (entorno), aunque la migración sí se validó manualmente contra Postgres real limpio.

## Decisiones a revisar antes de Frontend

1. **La más importante**: cómo (y si) automatizar el puente entre `multiplanar-v2` y
   `AiProductAnalysisRun`/`AiDiscLevelResult` para que `EvidenceRegionIngestionService` deje de
   ser un método sin llamador. Esto no es un defecto de esta iteración — es, a propósito, la
   pieza que se dejó sin resolver por falta de evidencia suficiente para hacerlo con seguridad.
2. Si el Frontend necesita distinguir `professional_corrected` de una futura "revisión propia"
   de una región `professional_drawn`, decidir si conviene generalizar
   `AiEvidenceRegionCorrection` para cubrir ambos casos o mantenerlos separados.
3. Confirmar con Frontend si `VisualEvidenceSummary.geometry` en formato `Map<String,Object>`
   crudo es suficiente o si conviene un DTO más tipado del lado del cliente.
