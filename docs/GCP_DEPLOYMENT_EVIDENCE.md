# Evidencia de despliegue GCP — PFI RM lumbar

**Fecha:** 2026-10-07  
**Estado:** despliegue técnico y E2E parcial completados en staging; reinicio backend E2E y repetición AI posterior al reinicio fallan. Activación institucional pendiente.
**Proyecto/región:** `pfi-asplanatti-fabrello-v1` / `us-central1`

Este documento se agregó después de construir las imágenes. Su commit documental es `e5ea1bf`; no forma parte de la imagen backend, cuyo código es `7074b123...`. No se hizo push.

## Baseline sincronizado

Los repositorios originales estaban limpios antes de sincronizar. Backend y AI tenían ramas de trabajo divergentes de `origin/main`; se conservaron y se integró `origin/main` en ramas locales candidatas nuevas, sin sobrescribir las ramas existentes. Frontend se avanzó por fast-forward. Ninguna rama se publicó a GitHub.

| Componente | Branch desplegada | SHA | Estado de trabajo |
| --- | --- | --- | --- |
| Backend | `gcp-deploy-candidate-20261007` | `7074b12339e07155232bc737d219546eaded2b05` | limpia |
| AI Module | `gcp-deploy-candidate-20261007` | `8254f3ab5636b80673ad5371c72b007775cc432d` | limpia |
| Frontend | `main` | `3d7995714c139191359c92228e045890219d4678` | limpia, igual a `origin/main` |

Las ramas candidatas backend/AI son worktrees locales en el directorio padre. El `.gcloudignore` del AI está incluido en el SHA desplegado y excluye notebooks, archivos locales y formatos DICOM/NIfTI; se conservaron los dos checkpoints versionados requeridos por su Dockerfile.

## Pruebas previas

- Backend: `mvn verify` con JDK 21 — **BUILD SUCCESS**, 1087 tests, 0 fallos, 87 omitidos por falta de Docker/Testcontainers local.
- AI Module: workflow oficial `.github/workflows/ai-module-ci.yml` en Cloud Build — **SUCCESS** (build `52952322-87c2-4bb6-acc8-596e2e22d4e2`). En Windows, PyTorch no pudo cargar `torch_global_deps.dll`; por eso la suite completa se ejecutó en Linux. La prueba contractual local dirigida pasó (18 tests).
- Frontend: `npm ci`, lint, typecheck, suite `npm run test` y `npm run build` — **SUCCESS**.

## Imágenes

| Componente | Tag inmutable por SHA | Digest |
| --- | --- | --- |
| Backend | `pfi-backend:sha-7074b12339e07155232bc737d219546eaded2b05` | `sha256:20ad59093d3e8b1b178a8c93557aac45cd30ce89a73a7c59c2133f6b2d37a101` |
| AI Module | `pfi-ai-module:sha-8254f3ab5636b80673ad5371c72b007775cc432d` | `sha256:0d5ccef6e8d148692a37ea1fe38497049489bddf5d95b20cdbafa922539dbce` |
| Frontend | `pfi-frontend:sha-3d7995714c139191359c92228e045890219d4678` | `sha256:99aaad35d8a3ab0d30b7e435898501767671ee56611b02f16d38501408cc991f` |

Registry: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers`.

## Servicios y recursos reutilizados

- Frontend: `pfi-frontend-dev`, revisión `pfi-frontend-dev-00003-mrw`, URL `https://pfi-frontend-dev-810538429027.us-central1.run.app`, min 0 / max 1; `BACKEND_URL` runtime apunta al backend y `VITE_USE_MOCK=false`.
- Backend: `pfi-backend-v2-dev`, imagen del SHA de arriba, revisión healthy `pfi-backend-v2-dev-00004-d8r`, 100% de tráfico; URL `https://pfi-backend-v2-dev-810538429027.us-central1.run.app`, min 0 / max 1. CORS sólo permite el origin del frontend indicado.
- AI Module: `pfi-ai-module`, revisión `pfi-ai-module-00009-7gs` Ready, privada, URL `https://pfi-ai-module-810538429027.us-central1.run.app`, CPU, 4 vCPU / 2 GiB, concurrency 1, min 0 / max 1. `PFI_INFERENCE_DEVICE=cpu`; el checkpoint configurado está en el bucket de artefactos existente.
- PostgreSQL: instancia existente `pfi-postgres-dev`, PostgreSQL 15, `db-f1-micro`, una zona, estado `RUNNABLE`. No se creó ni modificó la base.
- Persistencia: Cloud SQL/DB `pfi`; bucket de assets `pfi-backend-durable-dev`; ambos ya existían. Secret references (nombres únicamente): `pfi-dev-database-url` y `pfi-dev-jwt-secret`.
- Service accounts: `pfi-backend-runtime`, `pfi-ai-runtime`, `pfi-frontend-runtime-dev`. Se añadió `roles/run.invoker` del backend runtime únicamente sobre `pfi-ai-module` y se retiró su binding global de proyecto.
- El backend legado `pfi-backend-dev` también quedó configurado con demo auth apagado y CORS limitado; no se eliminó.

## Configuración funcional y seguridad

- Backend: `PFI_PERSISTENCE_MODE=postgres`, storage GCS, `PFI_AUTH_DEMO_ENABLED=false`, `PFI_AI_AUTH_MODE=google-id-token`, audiencia y URL privadas del AI Module, `PFI_AI_PRIVATE_REQUIRED=true`.
- El contrato `v2` se seleccionó desde los ejemplos de configuración actuales y el flujo estricto de producto del backend; la propiedad en `application.properties` por defecto es `v1`, así que el valor se fijó explícitamente en `v2`.
- AI: CPU forzado; el bucket de checkpoints existente contiene el artefacto ya referido por el servicio. No se descargaron ni inventaron checkpoints.
- No se creó proyecto, Artifact Registry, SQL, bucket, secreto, VM, GPU, VPC, ni nuevo servicio Cloud Run.

## Verificaciones iniciales en GCP

- Frontend `/env.js`: HTTP 200 y API base URL apunta a `pfi-backend-v2-dev`.
- Backend `/api/system/health`: `{"status":"ok"}`.
- CORS preflight desde el origin frontend: HTTP 204 y `Access-Control-Allow-Origin` coincide con ese origin.
- `/api/auth/demo-doctor`: HTTP 401 en la revisión activa, como se requiere con demo auth desactivado.
- AI revision `Ready=True` y startup probe de Cloud Run correcta. No se invocaron directamente `/health` ni `/readiness`: el AI es privado y la sesión local no tiene permiso de impersonación de `pfi-backend-runtime` (`iam.serviceAccounts.getAccessToken` denegado). No se amplió IAM para la prueba.
- La lectura inicial de los mensajes de probe fue ambigua; la consulta de logs por revisión documentada en la actualización abajo confirma que `00005` y `00006` sí completaron el arranque, y fueron retiradas al quedar `00004` fijada con 100% de tráfico.
- Se verificó directamente en PostgreSQL, con consulta read-only, el conteo de cuentas ADMIN no demo activas. No se modificó ninguna cuenta.

## Actualización de diagnóstico y E2E — 2026-10-07

### Revisiones backend originales

Cloud Logging por `resource.labels.revision_name` mostró los siguientes resultados:

- `pfi-backend-v2-dev-00005`: Spring registró `Started AiBackendApplication in 123.146 seconds`; el startup probe HTTP a `/api/system/health` terminó exitosamente tras 14 intentos. No se encontró excepción de ApplicationContext, migración, secreto, Cloud SQL, AI auth ni puerto. El único cambio funcional frente a `00004` fue `PFI_AUTH_DEMO_ENABLED=true`.
- `pfi-backend-v2-dev-00006`: Spring registró `Started AiBackendApplication in 63.266 seconds`; el startup probe HTTP terminó exitosamente tras 8 intentos. No hubo error fatal de aplicación. El demo flag volvió a `false`.
- Ambos candidatos quedaron retirados porque el servicio mantenía el tráfico explícitamente fijado a `00004`; no se consideran fallidos por bootstrap ADMIN. Los perfiles quedaron en `default` (sin `SPRING_PROFILES_ACTIVE`) y el bootstrap estaba ausente/default `false`, por lo que la validación de bootstrap del perfil `production` no se ejecutó.

| Configuración | `00004-d8r` | `00005` | `00006` |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | ausente (`default`) | ausente (`default`) | ausente (`default`) |
| `PFI_PERSISTENCE_MODE` | `postgres` | `postgres` | `postgres` |
| `PFI_AUTH_DEMO_ENABLED` | `false` | `true` | `false` |
| `PFI_AUTH_BOOTSTRAP_ADMIN_ENABLED` | ausente (`false`) | ausente (`false`) | ausente (`false`) |
| `DATABASE_URL` configurada | sí, Secret Manager | sí, Secret Manager | sí, Secret Manager |
| Cloud SQL attachment | ninguno; JDBC Cloud SQL Connector | ninguno; JDBC Cloud SQL Connector | ninguno; JDBC Cloud SQL Connector |
| AI URL | URL canónica `pfi-ai-module` | igual | igual |
| AI auth mode | `google-id-token` | igual | igual |
| AI audience | URL canónica `pfi-ai-module` | igual | igual |
| CORS | origin exacto de frontend dev | igual | igual |

`activeNonDemoAdminCount=0` (cuenta debe estar activa/verified/approved, tener rol exacto `ADMIN`, excluyendo demo). No se insertó ni alteró cuenta alguna. Resultado: el servicio original queda **TECHNICALLY DEPLOYED / INSTITUTIONAL ACTIVATION PENDING**; no se solicitaron datos profesionales ni se habilitó bootstrap.

### Entorno STAGING / TECHNICAL E2E

Como el conteo ADMIN fue cero, se crearon servicios aislados, sin cambiar el backend productivo `00004`:

| Recurso | Valor / estado |
| --- | --- |
| Frontend | `pfi-frontend-e2e`, revisión activa `pfi-frontend-e2e-00002-vbw`, `https://pfi-frontend-e2e-810538429027.us-central1.run.app`, min 0 / max 1 |
| Backend | `pfi-backend-e2e`, `pfi-backend-e2e-00001-s6n` Ready; `pfi-backend-e2e-00002-6wt` no llegó a Ready y no recibió tráfico; URL `https://pfi-backend-e2e-810538429027.us-central1.run.app` |
| AI privado | `pfi-ai-module-e2e`, `pfi-ai-module-e2e-00001-fx4` y después `pfi-ai-module-e2e-00002-247` Ready; privada, CPU 4 / 2 GiB, concurrency 1, min 0 / max 1 |
| PostgreSQL | DB separada `pfi_e2e` en la instancia existente `pfi-postgres-dev`; no se creó instancia SQL nueva |
| AI runtime bucket | `gs://pfi-ai-runtime-e2e-810538429027`, regional `us-central1`, UBLA y public access prevention |
| Backend assets bucket | `gs://pfi-backend-e2e-assets-810538429027`, regional `us-central1`, UBLA y public access prevention |
| AI paths | `/mnt/pfi-runtime/ai-inputs` y `/mnt/pfi-runtime/ai-outputs`, env `PFI_UPLOAD_DIR` / `PFI_OUTPUT_DIR` apuntan allí |
| AI volumes | modelo de solo lectura en `/mnt/pfi-artifacts`; runtime bucket read/write en `/mnt/pfi-runtime` |
| Backend auth | `PFI_PERSISTENCE_MODE=postgres`, `PFI_AUTH_DEMO_ENABLED=true`, `PFI_AUTH_EXPOSE_DEV_CODES=false`, bootstrap admin `false` |
| E2E IAM | bindings de secretos y buckets scoped a nivel de secreto/bucket; backend E2E `roles/run.invoker` solo sobre AI E2E; no se amplió invocación de producción |

El frontend tuvo una variable mal formada (`BACKEND_URL` incluía el texto `VITE_USE_MOCK=false`); se corrigió en la revisión `00002-vbw`. El inicio de sesión en el navegador y carga de `/api/studies` funcionaron desde el origin E2E después del arreglo.

### E2E funcional y persistencia observada

ZIP usado: `PFI_RM_Lumbar_Profesores/test-data/1008446160-20260815T180412Z-1-001.zip`, documentado en el repositorio como datos desidentificados de uso académico/demo. No se modificó el archivo.

- Login demo por Frontend E2E: **PASS**. Logout y nuevo login: **PASS**.
- Upload/clasificación por `POST /api/ai/studies`: **PASS**; caso `E2E-1008446160`, `studyId=0120097cbd244b51bbd28b375c9c7f62`, 3 series analyzable (dos sagittal de 12 cortes y una axial de 24). Los IDs opacos están en logs de sesión, no se guardan aquí.
- PostgreSQL E2E: `databaseConnectivity=PASS`, `schemaMigrations=PASS` (20/20 archivos de migration presentes en `schema_migrations`), `persistenceMode=postgres`.
- Una corrida estricta completa desde el backend a la IA privada terminó **PASS**: `status=completed`, `effectiveInferenceMode=real_baseline`, `degradedMode=false`, `humanReviewRequired=true`, `notClinicalDiagnosis=true`. Backend registró `200`; el AI privado recibió `POST /v2/multiplanar/run` y respondió `200`. El token Google ID fue usado por el backend runtime E2E; no se otorgó acceso a usuario personal.
- La corrida y los assets se mostraron en la UI del frontend E2E; el visor abrió la serie sagital, la segmentación/estructuras y el plano axial.
- Se guardó un borrador de revisión con nota técnica de prueba y se recuperó después de logout/login. No se confirmó una corrección, que no era necesaria para guardar el borrador.
- Después de reiniciar AI creando revisión `pfi-ai-module-e2e-00002-247`, el mismo `inputId` resolvió `GET /inputs/{id}/slices/5` con `200`; el overlay de la corrida anterior volvió a estar disponible desde Backend (`200`, origen persistente Backend/GCS). Esto demuestra recuperación del input y del asset visible, pero la segunda petición de inferencia estricta recibió `502 AI_REAL_INFERENCE_FAILED` desde el endpoint privado AI (`500`). Por ello `AI_RESTART_PERSISTENCE=FAIL` para el criterio completo que exige volver a ejecutar inferencia.
- Logs del mount registraron errores `429 rateLimitExceeded` en operaciones rename de `ai-outputs/asset_registry/*.json`. `gcloud storage ls --recursive` como usuario de esta sesión no mostró objetos en el bucket AI runtime; no se afirma que el registro/output de AI se haya verificado directamente en ese bucket. El ZIP y los outputs visibles del producto sí están en el bucket de assets E2E del Backend. Se requiere resolver las mutaciones GCS del AI runtime antes de declarar persistencia de `PFI_OUTPUT_DIR` validada.
- Reinicio Backend E2E: se creó `pfi-backend-e2e-00002-6wt`, pero su app no llegó a registrar `Started AiBackendApplication` ni `Tomcat started on port 8080`; el log quedó después de `HikariDataSource Start completed`. Cloud Run agotó el startup probe TCP de 4 minutos (`DEADLINE_EXCEEDED`) y comenzó intentos nuevos. La revisión `00001-s6n` sigue Ready y el tráfico del servicio continúa en ella. Por tanto, `BACKEND_RESTART_PERSISTENCE=FAIL` (la recuperación de DB fue validada tras logout/login, pero no tras un reinicio exitoso de Backend).
- La revisión AI `00002-247` permanece Ready y recibe el 100% del tráfico de AI E2E. No se modificó `pfi-ai-module` productivo.

### Matriz de cierre

| Área | Estado |
| --- | --- |
| Infraestructura frontend/backend/AI/Cloud SQL | PASS para servicios E2E activos (`00001` backend, `00002` frontend y AI); candidato de reinicio backend FAIL |
| Seguridad AI privada y Backend→AI Google ID Token | PASS |
| Secret Manager | PASS; valores no incluidos en evidencia |
| Demo auth productivo | DISABLED |
| Demo auth E2E | ENABLED, solo en entorno separado |
| Login/upload/inferencia real/resultado/revisión | PASS en la primera corrida E2E; UI muestra los assets |
| Backend restart persistence | FAIL: candidata no completó startup en 4 min; tráfico E2E quedó en la revisión Ready anterior |
| AI input recovery | PASS (mismo inputId/slice 200 después de revisión nueva) |
| AI output asset recovery | PASS desde almacenamiento persistente del Backend |
| AI restart persistence end-to-end | FAIL: re-inferencia posterior respondió 502; errores GCSFuse 429 al actualizar asset registry AI |
| ADMIN institucional activo | NO (`activeNonDemoAdminCount=0`) |
| Producción institucional | NO; `TECHNICALLY DEPLOYED / INSTITUTIONAL ACTIVATION PENDING` |

## Pendientes de cierre

1. Diagnosticar la revisión backend E2E `00002-6wt`: la app queda después de `HikariDataSource Start completed`, no registra evento de Spring `Started`, y Cloud Run declara timeout de startup probe a los cuatro minutos. No se le asignó tráfico.
2. Resolver la escritura/renombrado concurrente o rate-limited de `ai-outputs/asset_registry` en el mount GCSFuse; verificar objetos del bucket AI runtime y completar una nueva inferencia estricta luego del reinicio.
3. Repetir persistencia backend tras una revisión Ready y confirmar estudio, corrida, revisión y assets desde esa revisión.
4. Alta institucional queda pendiente de un ADMIN legítimo por el mecanismo de bootstrap de la app; ningún dato profesional ficticio fue utilizado.

Las revisiones E2E no convierten el sistema en producción institucional. No se hizo push.

## Costos y pendientes

Cloud Run escala a cero, con máximo una instancia por servicio. Cloud SQL permanece **encendido continuamente** (`db-f1-micro`, no HA) y generará costo continuo; buckets y Artifact Registry generan almacenamiento, Cloud Build consume minutos y tráfico/salidas de GCS puede generar cargos. No se cambió billing ni se crearon recursos persistentes adicionales.

Pendiente para completar el objetivo solicitado: resolver por qué las nuevas revisiones backend se quedan después de `HikariDataSource Start completed` y no terminan el startup probe; confirmar/admin bootstrap con identidad profesional real si no existe un ADMIN activo; luego completar el flujo E2E con el ZIP de prueba desidentificado ya destinado al desarrollo y verificar recuperación post-revisión. Hasta entonces, esta evidencia no certifica el producto E2E.

## Revisión activa y rollback

El backend healthy `pfi-backend-v2-dev-00004-d8r` recibe 100% del tráfico. Rollback/corte explícito:

```powershell
gcloud run services update-traffic pfi-backend-v2-dev --project=pfi-asplanatti-fabrello-v1 --region=us-central1 --to-revisions=pfi-backend-v2-dev-00004-d8r=100
```

Para los otros servicios, seleccionar una revisión anterior con `gcloud run revisions list --service=... --region=us-central1` y dirigir tráfico con `gcloud run services update-traffic`. No se ejecutó rollback destructivo.
