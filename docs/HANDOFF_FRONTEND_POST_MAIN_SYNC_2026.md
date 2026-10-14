# Handoff Frontend después de sincronizar main — 2026-10-13

## Referencias certificadas

- Backend `main`: `10889d5300069eee4175237377df352c80a37499` (merge commit de PR [#31](https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Backend/pull/31)).
- Candidato Backend auditado e incluido: `f3caeec14ca536df8bbbd599a77151fc2da836d3`.
- AI Module `main`: `a93912660f127b31fcf6d0b9b234e82e67eb081d` (merge commit de PR [#19](https://github.com/EnzoAA004/PFI_MVPTest_Enzo_AImodule/pull/19)).
- SHA certificado del AI Module incluido: `8254f3ab5636b80673ad5371c72b007775cc432d`.
- Frontend remoto comparado: `3d7995714c139191359c92228e045890219d4678` (main, cierre de integración de Francisco).

## Funciones disponibles

- Persistencia PostgreSQL/Cloud SQL con pool compartido, migraciones versionadas y recuperación al reiniciar.
- Almacenamiento durable de entradas de estudio y assets en filesystem o GCS; lectura autenticada de assets y rehidratación después de reinicios.
- Reprocesamiento de un estudio desde el DICOM ya almacenado.
- Autenticación Backend → AI Module privado mediante Google ID Token.
- Ejecución multiplanar V1/V2 configurable, revisión profesional, historial y serving de assets bajo los contratos existentes.
- Chat contextual por estudio, con historial de conversaciones y proveedor LLM self-hosted opcional.
- Diagnóstico de runtime sin exponer URLs, buckets, secretos, rutas ni datos de pacientes.

## Endpoints y contratos para el Frontend

### Contratos existentes que se mantienen

- `GET /api/studies`, `GET /api/studies/{caseId}` y `GET /api/studies/{caseId}/runs`: lista, detalle e historial de corridas.
- `POST /api/studies/{caseId}/patient`: asociación del Patient con el Study.
- `POST /api/ai/studies`: carga multipart con campos `file` y `caseId`. La respuesta conserva `caseId`, `studyId`, `seriesFound`, `sagittal`, `axial`, `sagittalT1`, `sagittalT2`, `warnings`, `humanReviewRequired` y `notClinicalDiagnosis`.
- `POST /api/ai/multiplanar/run`: conserva el contrato multiplanar del Backend. Para el esquema V2, configurar `PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION=v2`; el Backend acepta V1 y V2.
- `GET /api/ai/assets/{runId}/{plane}/{assetName}`: sirve assets autorizados desde almacenamiento durable. La URL pública y los nombres de plano no cambian.
- Las rutas de revisión profesional, Patient, Study, anotaciones, historial y reportes conservan los DTOs que consume el Frontend.

### Rutas añadidas por la candidata Backend

- `POST /api/ai/studies/{studyId}/reprocess?caseId={caseId}`: reprocesa el estudio desde su DICOM durable; no requiere volver a subir el ZIP. Devuelve el mismo `StudyUploadResponseDto` de la carga de estudios.
- `GET /api/system/runtime-diagnostics`: lectura pública de estado no secreto. Incluye `status`, estado Cloud Run, modo de persistencia, proveedor de storage y estado/métricas del pool. No contiene datos clínicos ni credenciales.

### Sistema y revisión

- `GET /api/system/health` mantiene el JSON `{"status":"ok"}`. En Cloud Run agrega el header `X-PFI-Revision`, que queda expuesto por CORS.
- `GET /api/system/diagnostics` sigue siendo sólo para ADMIN; `POST /api/system/warmup` también requiere ADMIN.
- No se agregaron, quitaron ni renombraron campos de DTOs existentes en la promoción. El reprocesamiento reutiliza la respuesta de carga; las rutas de diagnóstico son contratos nuevos.

### Chat contextual

- `POST /api/studies/{caseId}/chat`: cuerpo `question`, con `analysisId`, `baselineCaseId` y `conversationId` opcionales. El Frontend sólo manda la pregunta y esas referencias; el Backend resuelve medidas y contexto del lado del servidor.
- `GET /api/studies/{caseId}/chat-conversations` y `GET /api/studies/{caseId}/chat-conversations/{conversationId}`: historial y detalle.
- La respuesta de chat incluye `answer`, `conversationId`, `messageId`, `model`, `provider`, `promptVersion`, `generatedAt`, `warnings`, `humanReviewRequired`, `notClinicalDiagnosis` y `contextSources`.
- Los flags `humanReviewRequired` y `notClinicalDiagnosis` deben seguir tratándose como obligatorios y verdaderos en la UI.

## Autenticación, CORS y variables

- Las rutas de negocio requieren `Authorization: Bearer <JWT>` cuando la autenticación está habilitada. `/api/system/health`, `/api/system/runtime-diagnostics` y las rutas de autenticación son públicas; diagnósticos detallados requieren ADMIN.
- Para AI privado: `PFI_AI_SERVICE_URL`, `PFI_AI_AUTH_MODE=google-id-token`, `PFI_AI_SERVICE_AUDIENCE=<origen HTTPS del AI Module>` y `PFI_AI_PRIVATE_REQUIRED=true`. En Cloud, la identidad runtime del Backend necesita invocar el servicio AI; se usa ADC, sin archivo de service account JSON.
- Para Cloud SQL: `PFI_PERSISTENCE_MODE=postgres` y `DATABASE_URL` o `PFI_DATABASE_URL`. No guardar credenciales reales en el repo.
- Para GCS: `PFI_STORAGE_PROVIDER=gcs` y `PFI_STORAGE_GCS_BUCKET=<bucket privado>`; la identidad runtime necesita permisos mínimos de objeto. En local, el valor por defecto es filesystem.
- CORS se configura con `PFI_CORS_ALLOWED_ORIGINS` usando el origen HTTPS exacto del Frontend, sin wildcard en producción. La configuración acepta `X-Trace-Id` y expone `X-PFI-Revision`.
- El Frontend puede fijar la base API con `VITE_API_BASE_URL` en build o `window.__PFI_CONFIG__.API_BASE_URL` en runtime.

## Estado de Qwen

- La integración `SelfHostedLlmProvider` y el contrato de chat permanecen en Backend main. El chat queda deshabilitado por defecto: `PFI_LLM_ENABLED=false`.
- Para una futura instancia privada, las propiedades son `PFI_LLM_BASE_URL`, `PFI_LLM_MODEL`, `PFI_LLM_AUTH_MODE=google-id-token`, `PFI_LLM_PRIVATE_REQUIRED=true` y `PFI_LLM_AUDIENCE=<origen del servicio>`.
- Nota para configuración: el validador actualmente menciona `PFI_LLM_SERVICE_AUDIENCE` en su texto de error, pero Spring enlaza `pfi.llm.audience`, es decir `PFI_LLM_AUDIENCE`. Usar `PFI_LLM_AUDIENCE`; corregir el texto del validador en un cambio posterior.
- La candidata incluye el cliente y la evidencia/loader de preparación. La evidencia versionada indica que el servicio GPU no fue creado, el bucket Qwen V2 estaba vacío y la cuota/disponibilidad no quedó verificada. El chat contextual está integrado en el Frontend, pero no se debe presentar Qwen como servicio operativo ni habilitarlo hasta comprobar URL, IAM, audience y disponibilidad.

## Verificaciones y límites

- Backend: `mvn verify` con JDK 21 local terminó con BUILD SUCCESS (1.096 tests, 0 fallas, 0 errores); 87 pruebas Testcontainers se omitieron localmente porque Docker no estaba disponible. El CI remoto ejecutó `mvn verify` con 1.096 tests, 0 fallas, 0 errores y 0 omitidos; compilación y build Docker de validación también pasaron.
- AI Module: CI remoto pasó compilación, smoke de contrato, build Docker sin push y 567 tests generales. Nueve tests que necesitan un checkpoint real se omitieron porque ese artefacto no está disponible en CI; tres pruebas `notebook_tooling` se excluyen del job general por diseño.
- La evidencia versionada de GCP V2 registra E2E de Backend/AI, persistencia y reinicios como PASS. Esta sincronización no ejecutó despliegues ni migraciones contra servicios vivos.
- No se descargaron ni modificaron checkpoints; no se agregaron datasets, DICOM privados, credenciales ni archivos de runtime al diff.
- Patient/Study, carga DICOM y respuesta sagital/axial/multiplanar conservan sus contratos. No se encontró incompatibilidad de DTO con el Frontend main indicado arriba.

## Integración segura del trabajo local de Francisco

Ejecutar desde su checkout local del Frontend. Mantener la rama original como respaldo y trabajar sobre una rama nueva desde el main remoto:

```powershell
git status --short --branch
git branch backup/fran-before-backend-main-sync-2026-10-13 HEAD
git stash push -u -m "pre backend main sync 2026-10-13"
git fetch origin --prune
git log --oneline --left-right origin/main...HEAD
git diff --stat origin/main...HEAD
git switch -c fran/integracion-post-backend-main-2026 origin/main
```

Revisar los commits locales de la rama de respaldo y aplicar sólo los commits de Francisco que todavía quiera conservar. Hacer `cherry-pick` por SHA, uno por vez, sobre la rama nueva. No cherry-pickear merges ni ramas experimentales a ciegas. Si había cambios sin commit, revisar `git stash list` y aplicar el stash con `git stash apply --index stash@{0}`; conservarlo hasta verificar el resultado.

Después de integrar:

```powershell
npm ci
npm test
npm run build
git diff --check
git status --short --branch
```

Confirmar que `VITE_API_BASE_URL` o `window.__PFI_CONFIG__.API_BASE_URL` apunte al Backend esperado y que el origen publicado esté en `PFI_CORS_ALLOWED_ORIGINS`. Mantener el JWT/session flow del Frontend; no llamar al AI Module ni al LLM directamente desde el navegador.

## Ramas fuera de esta promoción

- `experiment/qwen-cloudrun-e2e` del Backend queda sin fusionar. No integrar toda esa rama automáticamente: contiene trabajo experimental de GPU/Cloud Run y su commit exclusivo `9dfbc8037bebc87befbf87d186498fd3fb566cdd` documenta evidencia CORS. La plantilla experimental `infra/qwen/cloudrun-vllm-service.yaml` no forma parte del candidato ni fue validada operativamente.
- `integration/final-2026` del AI Module sigue siendo una rama separada de integración. Su rama local se adelantó por fast-forward para coincidir con `origin/integration/final-2026` en `d363113d46284205233aede3f1f48e32b7ebf186`; no se mezcló automáticamente con main porque la promoción certificada se hizo desde `gcp-deploy-candidate-20261007`.
- No se eliminaron ramas históricas. Las ramas candidatas quedan disponibles para auditoría; sus commits certificados ya son ancestros de los respectivos `main`.
