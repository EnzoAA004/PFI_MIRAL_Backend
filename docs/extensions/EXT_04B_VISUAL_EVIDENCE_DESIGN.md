# EXT-04B1 — Visual Evidence / Annotation Contract: Audit & Design

Estado: **diseño**, no implementado. Ningún código de producto fue modificado para producir este documento.

> **Actualización EXT-04B2**: la fundación de Backend descripta acá ya está implementada en la
> rama `extensions/ext-04-visual-evidence-backend` (desde `89115ac`). Ver
> [EXT_04B_BACKEND_FOUNDATION.md](EXT_04B_BACKEND_FOUNDATION.md) para el detalle de qué se
> implementó, la contradicción encontrada entre este diseño y el código real (el pipeline de
> geometría IA `multiplanar-v2` es distinto del pipeline de revisión/reporte
> `AiProductAnalysisRun`), y cómo se resolvió sin bloquear la iteración.

## 0. Alcance de esta iteración

EXT-04B1 responde una sola pregunta: cuál es el contrato correcto y reutilizable para una región visual
(sugerida por IA, validada/corregida por profesional, o dibujada por profesional) que después pueda
usarse en viewer, reporte y EXT-05. No incluye: dibujo manual implementado, Grad-CAM, reentrenamiento,
feedback loop, cambios a modelos IA, ni acceso a datos reales/cloud.

---

## 1. Inventario Backend (`PFI_MVPTest_Enzo_Backend`, rama `extensions/ext-04-report-enrichment-backend`)

Persistencia: JDBC plano/SQL a mano en toda la base (`PostgresStudyRepository.java`), migraciones en
`docs/migrations/*.sql`. No hay JPA/Hibernate, no hay `@Entity`.

| Clase/Tabla | Path | Responsabilidad | Reutilización |
|---|---|---|---|
| `ReviewerAnnotation` | `domain/ReviewerAnnotation.java` | Geometría arbitraria dibujada por el revisor: `points: List<Map>` en "base normalizada 0..256 compartida con masks y landmarks", `scope{study\|level\|slice}`, `kind`, `plane`, `seriesId`, `sliceIndex`, `level`, `author`, `createdAt` | **La analogía más fuerte que existe.** Ya es, de hecho, una "región dibujada por profesional" sin tipar como tal — sin discriminador de forma (bbox/ellipse/polygon), sin vínculo a un origen IA. |
| `domain_reviewer_annotations` | `docs/migrations/V20260730_013_reviewer_annotations.sql` | Tabla: `points JSONB` + CHECK constraints de scope/kind/plane/unit | Patrón de tabla directamente reutilizable para `domain_evidence_regions`. |
| `AiPlaneMaskV2Dto` | `dto/AiPlaneMaskV2Dto.java` | Máscara IA (pipeline v2): `geometry: Map` (polígono) + `coordinateSpace` nombrado | Geometría como mapa opaco + nombre de espacio de coordenadas — precedente de diseño "opaque geometry + coordinateSpace". |
| `AiPlaneLandmarkV2Dto` | `dto/AiPlaneLandmarkV2Dto.java` | Punto: `x, y, coordinateSpace, source` | `source` ya distingue origen por punto. |
| `AiPlaneMeasurementV2Dto` | `dto/AiPlaneMeasurementV2Dto.java` | `points`, `source`, `status`, `experimental` | Familia **paralela** a la de medición stage-76/78 — no confundir. |
| `AiCoordinateSpaceV2Dto` | `dto/AiCoordinateSpaceV2Dto.java` | `name, width, height, units, origin, xDirection, yDirection, sourceSliceIndex, sourceAxis` | El contrato de marco de coordenadas más cercano que existe hoy. Sin campos DICOM físicos. |
| `CanonicalPlaneRun` | `domain/CanonicalPlaneRun.java` | Contenedor de masks/landmarks/measurements/segmentation por plano, atados a un `coordinateSpace` | Confirma que toda geometría viaja hoy como `Map` opaco, nunca tipada en Java. |
| `AiMeasurementResult` / `domain_ai_measurement_results` | `domain/AiMeasurementResult.java` | `areaMm2, majorAxisMm, minorAxisMm` — **documentado explícitamente sin columnas de bbox/geometría** | Confirma el gap central: nada de geometría llega a las tablas estructuradas hoy. |
| `AiMeasurementCorrection` (stage 78-D) | `domain/AiMeasurementCorrection.java` | Append-only: `originalValue, correctedValue, unit, comment, reviewedBy, createdAt` por campo | **Patrón a espejar** para "profesional corrigió geometría IA". |
| `AiDiscLevelReview` / `AiProductAnalysisRunReview` (stage 78-B/C) | `domain/*.java` | `reviewStatus` (String, no enum Java) ∈ `pending\|accepted\|observed\|rejected` | Vocabulario de revisión a **reutilizar**, no duplicar. |
| `RunReview` / `MeasurementCorrection` (familia V1, paralela) | `domain/RunReview.java`, `domain/MeasurementCorrection.java` | Mismo concepto que arriba pero con `beforeValue/afterValue: Map` opacos | **Riesgo de duplicación ya existente** — dos máquinas de revisión conviven. No crear una tercera. |
| `EffectiveMeasurementResolver` / `EffectiveAnalysisRunSelector` | `service/*.java` | Resuelve `(automaticValue, correctedValue, effectiveValue, source)` sin mutar el original | **Patrón exacto a espejar** para `EffectiveAnnotation`. |
| `StudyReportDto` / `StudyReportService` / `StudyReportPdfRenderer` | `dto/`, `service/` | Reporte EXT-04A; **sin campo de geometría**; PDF es **texto puro vía PDFBox, sin `PDImageXObject` en todo el repo** | Punto de extensión para `visualEvidence`; el PDF con imagen es capacidad nueva, no trivial. |
| `AiBackendController` (`/assets/{runId}/{plane}/{assetName}`, `/series/{inputId}/slices/{index}`) + `RunAssetContentStorage` | `controller/`, `service/` | IDs opacos; **cero** referencias a path de filesystem o UID DICOM en toda la superficie de API | Mecanismo de entrega de imagen/asset a reutilizar tal cual para evidencia visual. |

**Conclusión de coordenadas (Backend):** no existe en ningún lado `ImagePositionPatient`/`PixelSpacing`/`SliceLocation` ni coordenadas físicas DICOM. Toda geometría es pixel/normalizada relativa a un `coordinateSpace` nombrado.

---

## 2. Inventario Frontend (`PFI_MVPTest_Enzo_Frontend`, rama `extensions/ext-04-report-enrichment-frontend`)

| Elemento | Path | Hallazgo |
|---|---|---|
| Viewer principal | `src/components/MriSliceViewer.tsx` | Imagen (`<img>`/`<canvas>` con windowing), máscara de segmentación (`<canvas>` o `overlay.png`), mediciones/landmarks (SVG `MeasurementLayer` + botones), todo dentro de un único `<div class="asset-transform">` con `transform: translate(pan) scale(zoom)` — todo se mueve junto, sin recálculo por capa. |
| **Sistema de coordenadas** | ídem | **Base normalizada fija 0..256**, usada uniformemente por mediciones, landmarks, `ReviewerAnnotation`, y máscaras. `MeasurementLayer` usa `viewBox="0 0 256 256"`. Conversión a mm sólo ocurre en el borde de despliegue (`toMillimetres`), nunca para almacenar geometría. |
| Interacción dibujar/editar | `MriSliceViewer.tsx` (pointer handlers) | Patrón: flag de modo → pointerDown crea/inicia → pointerMove extiende/arrastra → pointerUp finaliza; edición vía handles (`<circle class="mri-measure-handle">`) sólo si `selected && editable`. |
| Segmentación | `src/features/reading/segmentation.ts` | RLE `{encoding:"rle-v1", width, height, data, instances}` — **idéntico encoding** al que emite la IA (`PlaneSegmentationV2`). |
| Tipos de reporte | `src/contracts/studyReportTypes.ts` | `effectiveSource: "ai_original"\|"professional_correction"` — 2 estados, sin geometría en ningún lado. Gap confirmado: no hay campo de región/anotación. |
| Modelo de revisión cliente | `measurements.ts`, `MeasurementLayer.tsx` | `source: "ai"\|"reviewer"\|"derived"` — 3 vías, no existe "validado sin cambios" como estado distinto. |
| Entrega de imagen | `src/authenticatedAssets.ts`, `src/multiplanarApi.ts` | `aiAssetUrl(runId, plane, assetName)`, fetch autenticado (Bearer JWT), allowlist de `Content-Type`, `isAuthorizedBackendUrl` — nunca un path crudo. |
| Panel de capas | `plane-controls-panel` en `MriSliceViewer.tsx` | Toggle + opacidad por capa (segmentación, mediciones, landmarks) — slot reutilizable para una nueva capa de evidencia. |
| Dead end | `subarticularRoi.ts` | Pese al nombre, no es una primitiva de región — es punto + metadata de lateralidad para un flujo de clasificación puntual. |

---

## 3. Inventario AI (`PFI_MVPTest_Enzo_AImodule`, sin cambiar de rama, working tree sin tocar)

| Output | Qué es | Estado | Candidato a EvidenceRegion |
|---|---|---|---|
| `PlaneMaskV2.geometry` (contorno) | Polígono por instancia, `multiplanar_v2_models.py` | **Producción** (`pfi.multiplanar-run.v2`); docstring dice literalmente que existe para "hacer la máscara corregible" | **Alto** — polígono |
| `PlaneSegmentationV2` (RLE) + `mask.npy` asset | Máscara de píxeles real, registrada como asset | **Producción** | **Alto** — máscara |
| `PlaneLandmarkV2` | Punto `(x,y)` + `source: ai\|derived_from_mask` | Producción | Medio-alto — punto |
| `PlaneMeasurementV2.points` | Segmento de 2 puntos | Producción | Medio — segmento, no bbox |
| `pfi.degenerative-findings.v1` (el contrato real; **no existe** `pfi.ai-product-result.v1` como tal) | Severidad por condición/nivel, **sin ningún campo de geometría** | Producción | Ninguno directo — requiere join con la geometría del plano vía slice/coordinateSpace |
| Subarticular ROI (P10.7) | Punto `(x,y)` + `instance_number` | **Bloqueado/experimental**: `"requires_external_anatomical_coordinate; no automatic ROI localizer is validated"` — research-only, requiere coordenada externa | Bajo — sólo válido si la aporta un operador/profesional, nunca como IA_SUGGESTED por sí sola |
| `dual_plane_bbox_proxy` (candidato "67C") | Bbox 2D/3D combinando ambos planos | **Experimental, `mappingValidated: False`**, requiere mapeo anatómico provisto por operador | Medio — bbox, pero debe marcarse `experimental: true` siempre |
| Grad-CAM/heatmap/explainability | — | **Ausente por completo**, ni siquiera experimental | No aplica |

Gobernanza (`humanReviewRequired`, `notClinicalDiagnosis`, `autonomousDiagnosis`/`diagnosisGenerated=False`) es consistente en todos los outputs revisados.

---

## 4. Matriz de reutilización

| Capability | Backend | Frontend | AI | Reutilizable para EXT-04B | Limitación |
|---|---|---|---|---|---|
| Sagittal/axial segmentation | `AiPlaneMaskV2Dto`, `CanonicalPlaneRun` | `segmentation.ts` (RLE) | `PlaneMaskV2`/`PlaneSegmentationV2` | **Sí, directo** | Ninguna crítica |
| Measurement geometry | `AiMeasurementResult` (sin geometría) | `measurements.ts` | `PlaneMeasurementV2.points` | Parcial | Backend descarta la geometría al persistir |
| Professional review | `AiDiscLevelReview`/`AiProductAnalysisRunReview` | tipos ad-hoc | flags de gobernanza | **Sí** (vocabulario) | Coexisten 2 familias de revisión — no crear una 3ª |
| Corrections | `AiMeasurementCorrection` (append-only) | — | — | **Sí, patrón completo** | Ninguna |
| Structured report | `StudyReportDto`/`StudyReportService` | `studyReportTypes.ts` | — | **Sí** (extender) | Sin campo de geometría hoy |
| PDF generation | `StudyReportPdfRenderer` (texto puro) | — | — | Parcial | Cero uso de `PDImageXObject` en el repo — imagen embebida es capacidad nueva |
| Product analysis | `AiProductAnalysisRun` | — | `pfi.degenerative-findings.v1` | Parcial | Sin geometría; requiere join |
| Pathology classifier (P10.7) | — | — | `subarticular_frozen_classifier.py` | **No** para IA_SUGGESTED | Bloqueado, research-only |
| Longitudinal history | `LongitudinalComparisonService` | panel EXT-04A | — | Precedente de diseño | No geometría, sólo escalares |
| Reviewer-drawn geometry | `ReviewerAnnotation` | `annotations.ts` | — | **Sí, base fuerte** | Edición in-place, no append-only |

---

## 5. Gaps encontrados

1. **Gap central**: la geometría que la IA ya emite (`PlaneMaskV2.geometry`, `PlaneSegmentationV2`, `PlaneLandmarkV2`) se descarta al persistir en Backend (`domain_ai_measurement_results` confirmado sin columnas de geometría).
2. Dos familias de revisión/corrección ya coexisten en Backend (V1 opaca vs stage-78 tipada) — riesgo de una tercera si no se reutiliza deliberadamente.
3. No existe distinción de 4 estados de origen en ningún lado (frontend tiene 3: ai/reviewer/derived, sin "validado sin cambios").
4. El PDF no tiene capacidad de embeber imágenes — es una capacidad nueva, no una extensión menor.
5. El contrato de hallazgos real (`pfi.degenerative-findings.v1`) no tiene geometría — cualquier EvidenceRegion atada a un finding requiere join entre dos contratos IA distintos (finding + geometría de plano).
6. `ReviewerAnnotation` es mutable/in-place, no append-only — decisión consciente de no copiar ese patrón para regiones con origen IA.
7. P10.7 y 67C están correctamente marcados como bloqueado/experimental en el código — el diseño debe propagar esos flags hasta la EvidenceRegion, no sólo mostrarlos en UI.

---

## 6. Propuesta: `EvidenceRegion`

Entidad conceptual (no implementada):

```
EvidenceRegion:
  id                     # opaco, mismo patrón que ReviewerAnnotation (cliente puede generar, servidor mintea si no es UUID)
  studyId
  analysisRunId          # link a AiProductAnalysisRun (para el join con degenerative-findings)
  planeRunId             # link al CanonicalPlaneRun / multiplanar run (fuente real de la geometría)
  plane: sagittal | axial
  sliceIndex / seriesId
  level: L1-L2..L5-S1 | null
  structure: disc | ...  # o referencia a un AiMeasurementResult/finding específico
  geometry: Geometry     # ver sección 7
  origin: ai | professional
  reviewStatus: pending | accepted | observed | rejected   # reutilizado de AiDiscLevelReview
  provenance: Provenance # ver sección 9
  comment: string | null
  createdAt
  supersedesId: EvidenceRegion.id | null   # encadenamiento append-only
```

Nota: se recomienda una clase **nueva**, no generalizar `ReviewerAnnotation` in-place — su vocabulario de `scope/kind` está orientado a notas/mediciones libres del revisor, no a geometría con origen IA y árbol de correcciones. Sí se reutiliza su patrón de tabla (JSONB + CHECK) y su convención de coordenadas 0..256.

---

## 7. Modelo de Geometry

Discriminador de tipo, evaluando las 4 opciones pedidas:

```
geometry:
  type: BBOX | ELLIPSE | POLYGON | MASK
  # BBOX
  x, y, width, height
  # ELLIPSE
  centerX, centerY, radiusX, radiusY, rotation?
  # POLYGON
  points: [{x, y}, ...]
  # MASK
  assetRef: { runId, plane, assetName } | artifactId   # NUNCA blob inline
```

- BBOX/ELLIPSE/POLYGON: inline en JSONB — tamaño acotado, mismo patrón que `domain_reviewer_annotations.points` y `AiPlaneMaskV2Dto.geometry`.
- MASK: **solo referencia**, nunca el array de píxeles en JSON — reutiliza `RunAssetContentStorage` (direccionado por contenido, `artifactId`+`sha256`), exactamente como ya se hace con `mask.npy`/`overlay.png`. Responde directamente "no almacenar blobs enormes en JSON si existe un asset mechanism mejor": **sí existe**, es `RunAssetContentStorage`.

---

## 8. Sistema de coordenadas canónico

**Recomendación: base normalizada 0..256, la misma que ya usan `ReviewerAnnotation` y todo `MriSliceViewer.tsx`.**

Razones (no por intuición):
- Es la única base ya compartida hoy entre Backend (`ReviewerAnnotation`, documentado explícitamente como "0..256 shared with masks and landmarks") y Frontend (`MeasurementLayer` `viewBox="0 0 256 256"`, landmarks vía `pointPercent`).
- El viewer aplica `transform: translate(pan) scale(zoom)` a un único contenedor que incluye imagen + canvases + SVG — por construcción, una geometría en 0..256 nunca se desalinea con el resize/zoom/pan, sin matemática adicional.
- Ningún punto del stack (Backend, Frontend, AI) almacena coordenadas físicas DICOM hoy (`ImagePositionPatient`/`PixelSpacing` no aparecen en persistencia en ningún repo) — adoptar DICOM-físico ahora sería una capacidad nueva y no verificada, contra la regla de no inventar sin evidencia.
- La geometría que emite la IA (`PlaneMaskV2.geometry`, `PlaneLandmarkV2.x/y`) llega en su propio `coordinateSpace` (dimensiones reales en píxeles del slice de origen). Al ingerir en Backend se debe **renormalizar una sola vez, en un único punto testeable**, a la base canónica 0..256 (`x_norm = x_px / coordinateSpace.width * 256`), guardando además el `coordinateSpaceRef` original (width/height/sourceSliceIndex/sourceAxis) sólo para trazabilidad/auditoría — no como geometría de despliegue.
- La conversión a mm (para el valor numérico mostrado) sigue siendo, como hoy, un cálculo de borde en el momento de mostrar/exportar (`toMillimetres`), nunca la forma de almacenamiento de la geometría.

Esto cumple el requisito explícito: una anotación no debe desplazarse si la imagen se renderiza con otra resolución, porque 0..256 es independiente de la resolución por diseño.

---

## 9. Source / Provenance

```
Provenance:
  # si origin=ai
  modelId / modelKey
  modelSha256          # ya existe como patrón (AiMeasurementResult family, pfi.degenerative-findings.v1 "model.modelSha256")
  pipelineVersion       # schema_version real: "pfi.multiplanar-run.v2" | "pfi.degenerative-findings.v1"
  confidence: number | null   # SOLO si el modelo de origen realmente lo emite (ej. degenerative-findings.classification.probabilities) — NO inventar para masks/landmarks/measurements donde no existe
  generatedAt

  # si origin=professional
  reviewedBy            # interno, NUNCA serializado a frontend/report (mismo criterio ya probado en EXT-04A: el nombre del reviewer no debe aparecer en el JSON del reporte)
  timestamp
```

---

## 10. Integración con review existente

Pregunta: ¿enum nuevo, derivar de source+review, reutilizar review existente, o combinación?

**Recomendación: combinación (C + derivación), sin enum nuevo de 4 valores.**

Los 4 estados conceptuales del prompt no son un único campo — son el cruce de dos ejes que ya existen por separado:

1. `origin: ai | professional` (2 valores — mismo patrón que `MeasurementFigure.source`/`PlaneLandmarkV2.source`).
2. Existencia de una fila de corrección append-only (mismo patrón que `AiMeasurementCorrection`).
3. `reviewStatus` heredado tal cual del nivel/run (`pending|accepted|observed|rejected`, ya existente en `AiDiscLevelReview`/`AiProductAnalysisRunReview` — no duplicado).

Tabla de derivación (calculada por un resolver, nunca almacenada redundante):

| origin | ¿corrección existe? | reviewStatus | Etiqueta derivada |
|---|---|---|---|
| ai | no | pending | AI_SUGGESTED |
| ai | no | accepted / observed | PROFESSIONAL_VALIDATED |
| ai | sí | (cualquiera) | PROFESSIONAL_CORRECTED |
| professional | — (no hay original IA) | — | PROFESSIONAL_DRAWN |

Esto evita exactamente el riesgo que el prompt pide evitar: dos máquinas de estado divergentes. Sólo se agrega `origin` (2 valores) a la persistencia; el resto ya existe.

---

## 11. Regla de "Effective Annotation"

Espejo directo de `EffectiveMeasurementResolver`, por slot estructural (`studyId + plane + sliceIndex + level + structure`, análogo a `MatchKey`):

1. Si existe corrección profesional sobre una región IA → efectiva = geometría corregida, `source=professional_correction`.
2. Si no, y el origen es profesional (dibujada, sin original IA) → efectiva = geometría profesional, `source=professional_drawn`.
3. Si no, y la región IA tiene `reviewStatus ∈ {accepted, observed}` → efectiva = geometría IA, `source=ai_validated`.
4. Si no (IA sin revisar) → efectiva = geometría IA, `source=ai_suggested`, con `requiresProfessionalReview=true`.
5. Si no existe ninguna región para el slot → no hay evidencia visual efectiva (mensaje neutro, mismo patrón que `longitudinalHistory.message`).

El resolver **devuelve el caso y el flag**, no decide unilateralmente ocultar — esconder o no una sugerencia IA sin revisar es una decisión de política de producto/UI, no de resolución de datos (mismo criterio que ya aplica `StudyReportDto` hoy, que muestra mediciones IA aunque no estén revisadas).

---

## 12. Append-only / versionado

**Recomendación: append-only para regiones con origen IA que se corrigen** (mismo patrón que `AiMeasurementCorrection`: nueva fila con snapshot antes/después, el original nunca se muta).

Divergencia consciente respecto de `ReviewerAnnotation`, que hoy edita en el lugar (`onMoveMaskPoint` muta el punto existente) — ese patrón NO se copia para EvidenceRegion con origen IA, precisamente porque EXT-05 necesitará la región IA original intacta más la corregida, no sólo la última versión. Para regiones dibujadas por profesional desde cero (`PROFESSIONAL_DRAWN`), edición in-place es aceptable mientras no estén incluidas en un reporte ya generado; una vez reportadas, cualquier edición posterior también debería versionar, no mutar.

---

## 13. Representación en report/PDF

`StudyReportDto` sumaría (sin remover nada existente, mismo criterio que `longitudinalHistory` en EXT-04A):

```json
"visualEvidence": [
  {
    "evidenceId": "evidence_<opaque>",
    "level": "L4-L5",
    "plane": "sagittal",
    "structure": "disc",
    "imageRef": { "runId": "...", "plane": "sagittal", "assetName": "input.png" },
    "region": { "type": "BBOX", "x": 0, "y": 0, "width": 0, "height": 0 },
    "source": "professional_corrected",
    "reviewStatus": "accepted",
    "description": "Región de interés",
    "warning": null,
    "experimental": false
  }
]
```

- `imageRef` apunta al endpoint de assets ya existente — nunca un path.
- `description` usa lenguaje neutro ("Región de interés" / "Región señalada" / "Evidencia visual asociada"), nunca "patología confirmada".
- `experimental: true` obligatorio para cualquier geometría derivada de 67C o de una fuente aún no validada.
- **PDF**: dado que `StudyReportPdfRenderer` no embebe imágenes hoy (cero uso de `PDImageXObject` en el repo), el primer incremento realista es textual (descripción estructurada + nota "ver visor interactivo"), dejando el embebido de imagen como una pieza separada y más grande, no parte de "agregar un campo al DTO".

---

## 14. Asset security

No se necesita un mecanismo nuevo: reutilizar `GET /api/ai/assets/{runId}/{plane}/{assetName}` + `RunAssetContentStorage` (direccionado por contenido, `artifactId`+`sha256`), ya probado sin exponer paths ni UIDs DICOM. Recomendación: no generar por defecto una imagen "compuesta" (base + región quemada) en el servidor — seguir componiendo en el cliente como ya hace hoy con la segmentación, y que el reporte JSON sólo lleve `imageRef` + `region` (datos), no una imagen ya render­izada. Si en el futuro hiciera falta una imagen compuesta server-side (para el PDF), debe pasar por el mismo `RunAssetContentStorage`, nunca un path nuevo.

---

## 15. Cambios futuros por repo

**Backend**: entidad `EvidenceRegion` (nueva clase, no generalizar `ReviewerAnnotation`), migración `domain_evidence_regions` (JSONB + CHECK, mismo patrón que `domain_reviewer_annotations`), repositorio JDBC, `EvidenceRegionService` + `EffectiveAnnotationResolver` (espejo de `EffectiveMeasurementResolver`), endpoints para dibujar/corregir (espejo de `ReviewerAnnotationService`), extensión de `StudyReportDto`/`StudyReportService`, extensión de `StudyReportPdfRenderer` (texto primero).

**Frontend**: `contracts/evidenceRegionTypes.ts` (unión discriminada de geometría), cliente API espejo de `studyReportApi.ts`, nueva capa dentro de `asset-transform` en `MriSliceViewer.tsx` (SVG para BBOX/ELLIPSE/POLYGON reutilizando el `viewBox 0 0 256 256`, canvas para MASK reutilizando `paintSegmentation`), interacción de dibujo/corrección reutilizando el patrón de pointer-mode ya existente, fila de toggle+opacidad en `plane-controls-panel`, formatter en `studyReportDisplay.ts`.

**AI**: **no es necesario tocarlo para arrancar EXT-04B.** La geometría que alimentaría `AI_SUGGESTED` (`PlaneMaskV2.geometry`, `PlaneSegmentationV2`, `PlaneLandmarkV2`) ya existe y ya viaja en `pfi.multiplanar-run.v2`; el gap real es que Backend la descarta al persistir. AI sólo tendría que cambiar si en el futuro `degenerative-findings` necesitara emitir su propia geometría directamente (hoy es puramente escalar) — se deja anotado como posible follow-up, no como requisito de EXT-04B.

---

## 16. ¿Hace falta tocar AI? — No, por ahora

Confirmado por auditoría: la geometría fuente ya existe en el contrato de producción del AI service. El trabajo de EXT-04B es de Backend (persistir + resolver + exponer) y Frontend (renderizar + permitir corrección), no de AI.

---

## 17. Branch strategy (propuesta, sin crear branches)

- **Backend**: `extensions/ext-04-visual-evidence-backend`, partiendo de `89115ac` (tip de `extensions/ext-04-report-enrichment-backend`) — no del baseline EXT-00 (`b0702b5`), porque necesita `StudyReportDto`/`StudyReportService`/`EffectiveMeasurementResolver` ya mergeados en EXT-04A.
- **Frontend**: `extensions/ext-04-visual-evidence-frontend`, partiendo de `6e25a3c` (tip de `extensions/ext-04-report-enrichment-frontend`) — misma razón (`studyReportTypes.ts`, panel "Informe / exportación").
- **AI**: no se crea branch ahora; si el join futuro requiere nuevos campos emitidos por AI, partir en ese momento del baseline post-e50 (`96c0337`).

No se creó ninguna branch en esta iteración.

---

## 18. Riesgos

1. Confundir el `coordinateSpace` por-slice de la IA con la base canónica 0..256 si la renormalización no queda en un único punto testeado del Backend.
2. Crear una tercera máquina de estados de revisión si no se deriva estrictamente de `origin` + `reviewStatus` + existencia de corrección.
3. Crecimiento no acotado de JSONB si alguna vez se guarda una MASK inline en vez de por referencia a asset.
4. Scope creep: mezclar "agregar `visualEvidence` al JSON" con "embeber imágenes en el PDF" — son tamaños de esfuerzo muy distintos y deberían ser incrementos separados.
5. Olvidar extender la sanitización ya probada en EXT-04A (sin reviewer, sin IDs completos) al nuevo bloque `visualEvidence`.
6. Usar geometría de 67C o P10.7 como si fuera una fuente limpia si el flag `experimental`/`researchOnly` no se propaga hasta la EvidenceRegion misma (no sólo hasta la UI).

---

## 19. Relación futura con EXT-05

El diseño deja preparados, sin implementar nada de aprendizaje: origen (`ai`/`professional`), historial append-only (región IA original nunca mutada), `modelId`/`modelSha256`/`pipelineVersion` en cada fila con origen IA, y un delta geométrico calculable (corregida vs. original, mismo formato que el before/after de `AiMeasurementCorrection`). Eso es exactamente lo que EXT-05 — Professional Feedback Learning Loop necesitará como evidencia candidata (región IA original, región corregida, región profesional, outcome de revisión, versión de modelo), sin que EXT-04B ejecute ni prepare ningún reentrenamiento.

---

## 20. Recomendación final de implementación

Orden sugerido para una futura iteración de implementación (no ejecutar ahora):

1. Backend: migración + entidad + repositorio + `EffectiveAnnotationResolver`, sin tocar el reporte todavía. Tests unitarios primero (mismo patrón que `LongitudinalComparisonServiceTest`).
2. Backend: ingesta — el punto único de renormalización de `coordinateSpace` IA → 0..256, con tests de esa conversión aislados.
3. Backend: extensión de `StudyReportDto`/`StudyReportService` con `visualEvidence` (JSON primero, PDF textual después, imagen embebida en PDF como incremento separado y posterior).
4. Frontend: tipos + cliente API, luego overlay de sólo-lectura en el viewer (mostrar, no editar) antes de agregar interacción de dibujo/corrección.
5. Frontend: interacción de dibujo/corrección reutilizando el patrón de pointer-mode existente.
6. E2E Docker aislado (mismo patrón que EXT-04A) antes de cerrar cualquier sub-iteración.

No se toca AI en ningún paso de esta lista salvo que aparezca evidencia concreta de que hace falta.
