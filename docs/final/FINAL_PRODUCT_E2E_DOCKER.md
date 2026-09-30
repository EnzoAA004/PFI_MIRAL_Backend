# FINAL_PRODUCT_E2E_DOCKER

Fecha: 2026-09-08 ART / 2026-09-09 UTC
Rama evaluada: integration/final-2026
Veredicto: BLOCKED

## SHAs evaluados

- Backend integration/final-2026: 3fa0de119aada74ff6314ae185444cabca54d64e
- Frontend integration/final-2026: bc5da42108d45f87a4f6b4559b09c7cc4dc92df9
- AI integration/final-2026: 8b71d2981a14dea78404eef7fd3131e70065bdd4
- Backend main/origin main observado: 646f4a123e77f9572fa0c5c4ede0990b1535945d
- Frontend main/origin main observado: 86991d7e391fd9ef964bc74c4b05c434e96225cb
- AI main/origin main observado: 90f4a8e076cc18efa9ebce10c604e1c3d1f3e35c

## Stack Docker local

Se construyo un compose local aislado en docker-compose.final-e2e.yml con Postgres, AI Module real, mock LLM, Backend y Frontend. No se hicieron despliegues ni escrituras GCP.

Servicios levantados:

- postgres: healthy
- ai: healthy
- mock-llm: healthy
- backend: healthy en http://localhost:58180
- frontend: healthy en http://localhost:58188

Imagenes locales:

- pfi-final-e2e-backend:local, 376MB, fb04339245a1
- pfi-final-e2e-frontend:local, 75.6MB, d8562a291448
- pfi-final-e2e-ai:local, 2.12GB, 2958a327106c
- pfi-final-e2e-mock-llm:local, 193MB, 39ae106bf8b4

Health checks observados:

- Backend /api/system/health: {"status":"ok"}
- Frontend /env.js: API_BASE_URL=http://localhost:58180
- Mock LLM /v1/models: Qwen2.5-7B-Instruct

## Bloqueo P0

El flujo demo/E2E no puede completar con los tres candidatos finales reales porque el seed de Demo Mode falla al ejecutar /api/ai/multiplanar/run. Backend devuelve 502 AI_REAL_INFERENCE_FAILED y el AI Module responde 500 en /v2/multiplanar/run.

Readiness relevante del AI Module:

- status: contract_ready
- readyForDemo: true
- readyForRealInference: false
- recommendedInferenceMode: contract
- reports.count: 0
- absoluteDiscLocalization67B2: blocked, 67B2_RUNTIME_PARITY_NOT_PROVEN
- axialPairing67C: blocked, 67C_RUNTIME_PARITY_NOT_PROVEN
- pathologyP107: blocked, blocked_preprocessing_parity

Readiness via Backend:

- aiModuleAvailable: true
- degradedMode: false
- backendReady: true
- sagittalReadyForRealInference: true
- axialReadyForRealInference: false

## Correcciones locales aplicadas

- Backend: se agrego @Autowired al constructor completo de SecurityStartupValidator para que Spring Boot no falle por multiples constructores.
- Frontend: se cerro una regla CSS incompleta en src/features/reading/reading.css que generaba warning de minificado durante build.
- Docker: se removieron puertos host innecesarios para Postgres, AI y mock LLM; quedan expuestos solo dentro de la red compose para evitar puertos reservados de Windows.

Estas correcciones quedaron locales y sin push porque la compuerta final E2E sigue bloqueada por el AI Module real.

## Validaciones ejecutadas

Backend:

- docker compose build backend: PASS
- docker compose up: backend healthy despues de la correccion local
- mvn -Dtest=SecurityStartupValidatorTest test: PASS, 24 tests
- mvn test en host local: FAIL por incompatibilidad de Java 25 con Byte Buddy/Mockito/Jacoco; no valida regresion funcional completa en este host.

Frontend:

- npm run lint: PASS
- npm run typecheck: PASS
- npm run build: PASS
- npm test: PASS
- Playwright demo E2E: FAIL por ausencia de datos demo; el seed previo falla por AI_REAL_INFERENCE_FAILED.

AI Module:

- docker build/runtime health: PASS
- readiness: contract_ready, pero readyForRealInference=false
- pytest local/container: no ejecutado; el host no tiene python/pytest usable y la imagen runtime no incluye pytest.

## Restricciones respetadas

- No merge a main.
- No force push.
- No deploy.
- No push a Artifact Registry.
- No escrituras GCP.
- No limpieza destructiva de artefactos.
