# EXT-04 - Structured Report Enrichment

## Alcance de esta iteración

Implementado sólo EXT-04A: tabla de historial longitudinal en el reporte estructurado. EXT-04B, evidencia visual, anotaciones sobre captura, Grad-CAM, heatmaps, reentrenamiento y despliegue cloud quedan fuera.

## Qué reutiliza

- `EffectiveAnalysisRunSelector`: selecciona corridas `completed`/`partial`, prefiere review `accepted`/`observed`, ignora `failed`.
- `EffectiveMeasurementResolver`: resuelve valor efectivo con última corrección profesional válida sobre el valor original IA.
- `LongitudinalComparisonService`: compara por `level + plane + structure + metric`, evita `level=null`, valida mismo `subjectRef`, calcula delta absoluto y porcentaje.

## Qué agrega

`pfi.study-report.v1` suma el bloque `longitudinalHistory` sin remover campos existentes:

- `available`: `true` cuando hay al menos una métrica comparable.
- `currentStudy`: `caseId`, `studyDate`, `analysisRunId`.
- `previousStudies`: estudios previos encontrados para el mismo `subjectRef`, con estado técnico de comparación.
- `comparisons`: filas comparables con fecha previa, nivel, plano, estructura, métrica, unidad, valor previo, valor actual, delta absoluto, delta porcentual y fuente de valores.
- `message`: estado descriptivo para ausencia de historial o métricas comparables.

El campo anterior `longitudinalComparison` se mantiene para compatibilidad cuando el consumidor envía `baselineCaseId`.

## Safety

- No se expone `subjectRef` en el reporte.
- No se exponen paths internos, DICOM UID, tokens ni identificadores de reviewer.
- La comparación no emite diagnóstico, tratamiento ni interpretación clínica longitudinal.
- Los signos `+`, `-` y `±` representan sólo delta matemático (`current - previous`).
- El PDF mantiene la advertencia de revisión profesional.

## Tests

Backend:

- reporte sin historial;
- dos estudios;
- tres estudios;
- cross-patient / `subjectRef` nulo;
- corrección profesional sobre valor IA;
- corrida previa `failed` ignorada;
- `level=null` no emparejado;
- porcentaje nulo con baseline cero;
- JSON report y PDF report;
- ausencia de lenguaje `better/worse/progression` en el nuevo bloque.

Frontend:

- parseo de `longitudinalHistory`;
- ausencia de historial;
- múltiples estudios previos;
- indicador neutral de valor corregido;
- porcentaje nulo;
- estados sin métricas comparables / sin análisis utilizable;
- loading/error del panel;
- contenedor responsive existente;
- ausencia de `better/worse/progression`.

## Validación ejecutada

- Backend: `docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9-eclipse-temurin-21 mvn "-Dtest=StudyReportServiceTest,StudyReportControllerTest,LongitudinalComparisonServiceTest" test`.
  - Resultado final por suites: `StudyReportServiceTest` OK, `LongitudinalComparisonServiceTest` OK, `StudyReportControllerTest` OK en rerun focalizado (42/42, 0 failures, 0 errors).
  - `mvn` no estaba disponible nativamente en Windows, por eso se usó la imagen local Maven (cache en el volumen local `ext04a-maven-cache`).
- Frontend: `npm run test:post-e50-structured-report` (43/43 OK), `npm run typecheck` (limpio), `npm run build` (OK).
- Docker E2E real (aislado, EXT-04 exclusivamente): ejecutado y verde. Ver sección siguiente.

## E2E Docker aislado (EXT-04)

Stack completamente separado del Post-E50/Demo existente, para no tocar el runtime congelado ni sus contenedores/volúmenes/red.

### Compose

`docker-compose.ext-e2e.yml` (Backend), 3 servicios bajo la red `pfi-ext04-e2e`:

- `postgres` (`pfi-ext04-postgres`, `postgres:16-alpine`, puerto host `55404`, volumen `pfi-ext04-postgres-data`).
- `backend` (`pfi-ext04-backend`, build local, puerto host `8280`, `DATABASE_URL` JDBC explícita con `sslmode=disable` para evitar que la conversión interna del Backend fuerce SSL contra un Postgres local sin TLS).
- `frontend` (`pfi-ext04-frontend`, build desde `../PFI_MVPTest_Enzo_Frontend`, puerto host `8288`). El `entrypoint` se **overridea** exclusivamente en este compose (no se toca el Dockerfile histórico ni `docker/entrypoint.sh`/`40-pfi-env.sh`): escribe `window.__PFI_CONFIG__` en `/usr/share/nginx/html/env.js` con la URL del backend EXT-04 y luego ejecuta `nginx -g 'daemon off;'`. Esto evita además un problema conocido y ya documentado en `.gitattributes` del Frontend: `docker/entrypoint.sh` puede quedar con CRLF en el árbol de trabajo en Windows (`core.autocrlf=true`) y el intérprete `#!/bin/sh` falla dentro del contenedor Linux; el override de compose nunca invoca ese script, así que el bug queda evitado sin parchear el Dockerfile.

Todos los recursos usan el prefijo `pfi-ext04-*` (contenedores, red, volumen, imágenes `pfi-ext04-e2e-backend`/`pfi-ext04-e2e-frontend` con el project name `pfi-ext04-e2e`), aislados de cualquier otro stack.

### Runner y comandos

Frontend: `scripts/playwright-ext04a-longitudinal-e2e.mjs`, invocado con `npm run test:e2e:ext04a-longitudinal`. Por ciclo:

1. `docker compose -p pfi-ext04-e2e -f docker-compose.ext-e2e.yml down -v --remove-orphans` (reset exclusivo de recursos `pfi-ext04-*`).
2. `docker compose ... up -d --build`.
3. Espera health del backend (`/api/system/health`) y de `/env.js` del frontend.
4. Seed sintético vía `psql` dentro del contenedor `postgres` (sujeto `EXT04-SUBJECT-A`, estudios `EXT04-STUDY-A/B/C` + casos límite: baseline cero, sin historial, cross-patient, corrida `failed`).
5. Autenticación (`/api/auth/demo-doctor`), validación de API (historial, comparaciones A→B y B→C, corrección profesional > valor IA original, delta absoluto/porcentual, `level_unresolved` para nivel nulo, `deltaPercent=null` con baseline cero, 404 fail-closed cross-patient), `report` JSON (`schema=pfi.study-report.v1`, sin campos sensibles), PDF real (`%PDF-`, texto de evolución), y verificación en navegador real (Playwright/Chromium) de la sección "Evolución / comparación con estudios previos" dentro de la pestaña "Más" → `<details>Informe / exportación</details>` del `StudyReviewView`, incluyendo persistencia tras `reload()`.
6. Repite un segundo ciclo completo con reset exclusivo de `pfi-ext04-*` entre medio.
7. Cleanup final (`down -v --remove-orphans`) también en caso de fallo.

### Resultado

Dos ciclos consecutivos verdes: `EXT-04A E2E cycle 1: OK`, `EXT-04A E2E cycle 2: OK`, `EXT-04A longitudinal Docker E2E: 2 cycles OK` (exit code 0). Verificado también manualmente contra el backend real (`curl` directo) que el endpoint standalone `/api/studies/{caseId}/longitudinal-comparison` devuelve el contrato `LongitudinalComparisonResultDto` (`measurements[]`, cada fila con `baseline`/`current`), distinto del bloque `longitudinalHistory.comparisons` embebido en `/report` (`StudyReportDto`, Stage 88) — ambos contratos son correctos y ya existían; el harness inicial confundía sus nombres de campo.

### Cleanup

Al finalizar cada corrida del runner no quedan `pfi-ext04-*` containers, networks ni volúmenes (`down -v --remove-orphans`). Verificado con `docker ps -a`, `docker network ls`, `docker volume ls` filtrados por `pfi-ext04`. El único recurso que sobrevive entre corridas manuales de diagnóstico es el volumen nombrado `pfi-ext04-postgres-data`, que el propio `resetStack()` de cada ciclo elimina con `-v` al arrancar el siguiente ciclo o la siguiente corrida.

## Limitaciones

- La selección de estudios previos usa `studyDate` cuando está disponible; si el estudio actual no tiene fecha, se aceptan otros estudios del mismo sujeto como candidatos.
- El AI Module no requiere cambios para EXT-04A.
- El E2E Docker aislado corre contra un stack sintético propio (`pfi-ext04-*`), nunca contra el stack Post-E50/Demo ni el runtime congelado, y usa exclusivamente datos sintéticos (sin datasets reales, sin DICOM real, sin acceso a cloud).
- El panel de historial en la UI muestra fecha/estado/cantidad de métricas comparables para cada estudio previo (`studyDisplay.displayStudyDate`), no el `caseId` crudo de los estudios previos — sólo el estudio actual expone su propio `caseId` en el bloque técnico del reporte. Esto no es una omisión: es el diseño ya existente del componente.
- No se declara validación clínica en ningún punto de este documento ni del harness: las comparaciones son aritméticas (`current - previous`), sin diagnóstico, tratamiento ni juicio de progresión clínica.
