# EXT-04B4 — Visual Evidence in PDF / Preinforme

Apoyo profesional + revisión humana + sin diagnóstico autónomo — igual que todo el resto de este
producto. Nada aquí cambia esa postura.

Branch: `extensions/ext-04b4-pdf-visual-evidence-backend`, worktree
`PFI_MVPTest_Enzo_Backend_ext04b4`, base `b396b4c` (EXT-07 Backend, que ya contiene EXT-04B3.5 —
ancestry verificada antes de crear la rama: `git merge-base 22d5eb0 b396b4c` = `22d5eb0`, y
`git merge-base --is-ancestor 22d5eb0 b396b4c` = sí). Frontend: **sin cambios** — el botón
"Exportar PDF" ya existente llama al mismo endpoint `GET /api/studies/{caseId}/report.pdf` sin
modificación alguna, y ese endpoint ahora sirve un PDF más completo automáticamente.

## Arquitectura PDF (Fase 1 — auditado antes de escribir código)

- Generador: `StudyReportPdfRenderer`, Apache PDFBox (`org.apache.pdfbox:pdfbox` ya en el `pom.xml`
  desde Stage 88), sin ninguna otra librería PDF.
- Antes de esta iteración: **texto puro**, línea por línea, sin ninguna imagen — `visualEvidence`
  y `longitudinalHistory` ya llegaban al DTO desde Stage 88/EXT-04B2, pero el renderer nunca los
  usaba para nada visual, solo para las tablas de texto ya existentes.
- Paginación previa: un cursor `y` global, nueva página cuando `y < 54`, sin ningún concepto de
  "bloque" — cada línea de texto se posicionaba por desplazamiento acumulado (`newLineAtOffset`).
- Esta iteración reemplazó ese modelo por uno de **bloques** (`TextBlock`/`ImageRowBlock`) que
  reserva el alto exacto de cada elemento (línea o imagen) antes de dibujarlo y decide el salto
  de página en base a eso — necesario para que una imagen nunca quede cortada a la mitad
  (Fase 11). Todo el contenido de texto preexistente se mantuvo literal, solo se reorganizó su
  representación interna; el test ya existente
  (`StudyReportServiceTest#rendersPdfWithTitleSafetyTextAndStructuredSections`) sigue pasando sin
  cambios.

## Image resolver (Fase 2)

`EvidenceImageSource` (interfaz) + `AiBackendEvidenceImageSource` (implementación de producción).
Nunca HTTP contra sí mismo: la implementación llama directamente, en el mismo proceso, a los
métodos ya existentes y ya testeados de `AiBackendService`:

- `fetchSeriesSlice(inputId, sliceIndex)` → `AiBackendService.getSeriesSlice(...)` → mismo
  `GET /api/ai/series/{inputId}/slices/{index}` que ya usa el Frontend (EXT-04B3.5), con la misma
  validación de `inputId`/rango ya vigente.
- `fetchAsset(runId, plane, assetName)` → `AiBackendService.getAsset(...)` → mismo
  `GET /api/ai/assets/{runId}/{plane}/{assetName}`, con la misma guarda anti path-traversal ya
  vigente en `EvidenceGeometry.MaskReference`.

Ambos métodos devuelven `Optional.empty()` (nunca lanzan) ante cualquier fallo — 404, upstream
caído, cuerpo vacío — así una imagen no esencial nunca convierte la generación del PDF completo
en un 500 (Fase 12).

## Overlay compositor (Fase 3/4)

`EvidenceOverlayComposer` — Java2D puro (`BufferedImage`/`Graphics2D`), en memoria, sin archivos
temporales, sin ninguna dependencia de GUI/headful (funciona igual en Docker headless). Decodifica
el PNG base con `ImageIO`, dibuja el contorno sobre un lienzo `TYPE_INT_ARGB` del mismo tamaño, y
vuelve a codificar a PNG en memoria (`ByteArrayOutputStream`). Reutiliza el mismo
`ar.edu.uade.pfi.backend.domain.EvidenceGeometry` que ya usan EXT-04B2/B3 — nunca se reinterpretó
la geometría, solo se le agregó un renderer más (Java2D, junto al SVG del Frontend).

- **BBOX** → `Graphics2D.drawRect`.
- **ELLIPSE** → `Graphics2D.drawOval`.
- **POLYGON** → `Graphics2D.drawPolygon`.
- **MASK_REFERENCE** → no se dibuja nada encima: el asset ya resuelto (`fetchAsset`) *es* la
  máscara ya renderizada — mismo precedente que `EvidenceRegionOverlay.tsx` en el Frontend.

## Color (documentado, no solo aplicado)

Contorno rojo (`RGB(220, 38, 38)`), sin relleno, `stroke` de 2.5px. El rojo acá significa
únicamente **"región de interés señalada"** — nunca gravedad, malignidad, patología, ni "peor".
No hay ninguna otra codificación de color por severidad en el PDF.

## Sección "Evidencia visual" (Fase 5)

Por cada item de `report.visualEvidence()` (ya filtrado a **efectivo** por
`StudyReportService`/`EffectiveAnnotationResolver` antes de llegar al renderer — el renderer
nunca decide qué es efectivo, solo dibuja lo que ya lo es): label neutral
(`EvidenceReportText.displaySource`, los mismos cuatro textos que ya usa el Frontend), plano,
nivel, estructura, estado de revisión, badge "Experimental" cuando `experimental=true`, warning
cuando existe, y la imagen compuesta (o "Imagen base no disponible." si no se pudo resolver).
Vacío → "No hay evidencia visual disponible para este análisis." (nunca un error).

## "Comparación con estudio previo" (Fase 6/7/8)

Nuevo campo aditivo en `StudyReportDto`: `beforeAfterEvidence: List<BeforeAfterEvidencePairDto>`
(nunca omitido, lista vacía cuando no hay historial comparable). Construido en
`StudyReportService.beforeAfterEvidence(...)`, servidor-side equivalente de lo que
`beforeAfterDisplay.ts#matchingEvidence` ya hace del lado del Frontend (EXT-07B): para cada fila
de `longitudinalHistory.comparisons[]` (mismo tope de 4 que ya usa el Frontend), busca un item de
`visualEvidence` con el mismo nivel/plano/estructura en el estudio actual, y resuelve el
`visualEvidence` del estudio previo (reutilizando el mismo `visualEvidence(run)` privado de
`StudyReportService`, sin duplicar lógica) para buscar el mismo emparejamiento ahí. Los valores
numéricos/deltas se copian literales de la fila ya calculada — nunca se recalculan.

En el PDF: "Estudio previo: dd/MM/yyyy" / "Estudio actual: dd/MM/yyyy", imagen+overlay de cada
lado cuando existen (uno o dos, lado a lado), valor previo/actual (con el marcador neutral
"(corregido)" cuando `previousValueSource`/`currentValueSource` es `professional_correction` —
nunca "mejoró"/"empeoró"/"progresión"/"deterioro"), Delta y Delta %. Sin imagen previa
comparable → "Sin imagen previa comparable." + los valores numéricos igual (Fase 6, fallback
textual explícito).

## Effective values / EffectiveAnnotationResolver (Fase 8/9)

El PDF nunca ve una geometría o un valor "crudo" de la IA por su cuenta — todo lo que llega a
`report.visualEvidence()`/`report.measurements()` ya pasó por
`EffectiveAnnotationResolver`/`EffectiveMeasurementResolver`. Una región rechazada, bloqueada, o
una sugerencia experimental nunca aparece como "validada" — el renderer no tiene ninguna lógica
propia de elegibilidad, solo dibuja lo que el DTO ya decidió mostrar. El valor AI original
(`automaticValue`) se preserva en la traza técnica (ya en el JSON/`measurements[]`) mientras el
PDF muestra el valor efectivo con su origen indicado de forma neutra.

## Seguridad (Fase 10)

- `reviewedBy`/`authoredBy` nunca llegan al DTO del reporte (regla ya vigente desde EXT-04A) —
  nada nuevo que filtrar acá.
- `MASK_REFERENCE` solo acepta `{runId, plane, assetName}` ya validados contra path traversal
  (constructor de `EvidenceGeometry.MaskReference`) — el compositor nunca recibe ni interpreta un
  path de filesystem.
- Ningún fetch de imagen sale a una URL externa: ambos métodos de `EvidenceImageSource` llaman
  directamente a `AiBackendService`, que a su vez solo habla con el AI Module configurado
  (`PFI_AI_SERVICE_URL`) — nunca con una URL que venga del contenido del reporte.
- Verificado con test dedicado (`neverLeaksReviewerPathsOrHosts`): ni el reviewer, ni un path
  Windows/Unix, ni un host `http://`, ni el propio `runId`/`plane` de un `MASK_REFERENCE`
  aparecen en el texto extraído del PDF.

## Fallbacks (Fase 12)

Probados explícitamente (`StudyReportPdfRendererTest`): `visualEvidence` vacío, `baseImage` nulo,
slice no resoluble (mismo comportamiento que un 404 real — `EvidenceImageSource` lo transforma en
`Optional.empty()`), geometría que no matchea ningún `EvidenceGeometry` conocido (capturada por el
mismo `catch (RuntimeException | IOException)` genérico), `MASK_REFERENCE` no disponible,
evidencia experimental, historial sin imagen previa. En todos los casos el PDF se genera igual —
nunca un 500 por una imagen no esencial.

## Tests (Fase 13) — 17 métodos, cubren los 18 escenarios pedidos

`StudyReportPdfRendererTest.java` (PDFBox `Loader`/`PDFTextStripper` para texto, conteo de
`PDImageXObject` por página para verificar imágenes realmente embebidas — Fase 14, sin OCR, sin
renderizado headful): sin evidencia, BBOX, ELLIPSE, POLYGON, MASK_REFERENCE, experimental,
verificación de que el renderer nunca agrega una región propia, `baseImage` ausente, slice no
resoluble, before/after con dos imágenes, before/after sin imagen previa, fechas explícitas,
marcador "(corregido)", ausencia de lenguaje clínico (con la salvedad correcta: el disclaimer
obligatorio sí dice "no constituye un diagnóstico" — se excluye esa frase exacta antes de
escanear, para no confundir la negación con una afirmación clínica), ausencia de datos sensibles,
header `%PDF-` + tamaño mínimo razonable, y 12 evidencias forzando múltiples páginas sin perder
ni una imagen. `git status` de esta iteración lista además una actualización menor de
`StudyReportServiceTest`/`StudyReportControllerTest` — únicamente para pasar las dos nuevas
dependencias del renderer y el nuevo campo aditivo del DTO, ningún cambio de comportamiento.

Suite completa del Backend: **970 tests, 0 failures, 0 errors, 1 skipped** (mismo test
Testcontainers-dependiente ya documentado en cierres anteriores).

## E2E (Fase 15/16/17)

`docker-compose.ext04b4-e2e.yml` (namespace propio `pfi-ext04b4-*`, puertos 8580/55435, sin tocar
ningún compose histórico) + `scripts/ext04b4-pdf-visual-evidence-e2e.mjs` — **sin contenedor de
Frontend**: esta iteración no tocó el Frontend y no hay nada visual/de browser que este E2E deba
ejercitar (el botón de descarga ya funcionaba y sigue llamando al mismo endpoint sin cambios), así
que el harness solo levanta `postgres` + `mock-ai` + `backend`. Reutiliza verbatim el escenario
`EXT07-SUBJECT-001`/Study A/B/C de EXT-07 (misma corrección profesional 12.1→12.3, mismo delta
B→C de +0.6) más una región de evidencia por estudio para poder ejercitar tanto la sección
"Evidencia visual" standalone como el emparejamiento "Comparación con estudio previo".

Cada ciclo: `down -v` → `up --build` → seed SQL (test setup, documentado como tal) → cross-check
vía `GET /report` (JSON) de que el escenario numérico es el esperado (12.9 efectivo, delta +0.6,
`visualEvidence`/`beforeAfterEvidence` no vacíos) → descarga real de
`GET /report.pdf` → verifica `Content-Type: application/pdf`, header `%PDF-`, y un tamaño mínimo
(>5KB) claramente mayor al de un PDF sin imágenes — evidencia de que las imágenes realmente se
embebieron sobre un Backend real, no solo en el test unitario. La verificación profunda del
*contenido* del PDF (qué geometría, qué texto exacto, qué imágenes) queda deliberadamente en
`StudyReportPdfRendererTest` (PDFBox, 17 tests) — este E2E prueba el cableado real de punta a
punta, no vuelve a probar el contenido que el unit test ya prueba mejor.

**Cycle 1**: reset limpio → seed → cross-check JSON → `report.pdf` real, `%PDF-`, > 5KB → OK.
**Cycle 2**: repetido completo desde reset limpio → OK. Teardown final (`down -v`) sin dejar
ningún recurso `pfi-ext04b4-*`.

## Frontend (Fase 18)

Sin cambios. El endpoint `GET /api/studies/{caseId}/report.pdf` ya existía y el botón "Exportar
PDF" (`fetchStudyReportExport(caseId, "pdf", baselineCaseId)`, `studyReportApi.ts`) ya lo llamaba
correctamente — la extensión de contenido es enteramente responsabilidad del Backend, y no
requiere que el Frontend sepa nada nuevo sobre el PDF que descarga.

## Regresiones (Fase 20)

Backend: suite completa, 970/970 (0 failures), `git diff --check` limpio. Frontend: no tocado,
sin regresiones que correr (Fase 20 solo pide regresiones de Frontend "si fue tocado").

## Limitaciones

1. El antes/después visual está limitado a las primeras 4 filas de `longitudinalHistory.comparisons`
   (mismo tope que ya aplica el Frontend) — un historial más largo sigue mostrando su tabla
   numérica completa (sección "Evolución", sin cambios) pero no una imagen por cada fila extra.
2. `MASK_REFERENCE` se embebe tal cual (el asset ya es la máscara renderizada) — no se le dibuja
   ningún contorno adicional encima, igual que en el Frontend.
3. El emparejamiento previo/actual es por coincidencia exacta de nivel/plano/estructura — dos
   regiones que en la práctica corresponden al mismo hallazgo pero difieren en alguno de esos tres
   campos (p. ej. una etiquetada `disc` y otra `annulus`) no se emparejan automáticamente.
4. No se implementó Grad-CAM, edición/dibujo interactivo, ni ningún cambio de IA — fuera de
   alcance explícito de esta iteración.
5. El tamaño de imagen en el PDF es de hasta 170pt de alto por fila, escalado preservando aspecto
   dentro de esa caja — no hay una opción de tamaño "grande"/zoom en el PDF (el JSON/PDF liviano
   siguen siendo la vía si se necesita inspeccionar la imagen a mayor resolución).
