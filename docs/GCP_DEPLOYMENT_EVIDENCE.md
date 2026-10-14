# Evidencia de despliegue GCP — PFI RM lumbar

**Fecha:** 2026-10-07  
**Estado:** FINAL FREEZE CLOUD E2E PASS en Frontend `00004-qaq`, Backend `finalverify` y AI `finalrestart`; activación institucional pendiente. El detalle vigente está en la adenda final.
**Proyecto/región:** `pfi-asplanatti-fabrello-v1` / `us-central1`

Este documento se agregó después de construir las imágenes. Su commit documental es `0ddab0db`; no forma parte de la imagen backend, cuyo código es `7074b123...`. No se hizo push.

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
| AI paths (estrategia actual) | `PFI_UPLOAD_DIR=/mnt/pfi-runtime/ai-inputs` persistente; `PFI_OUTPUT_DIR=/tmp/pfi-ai-outputs` efímero |
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
- En la revisión AI inicial `pfi-ai-module-e2e-00002-247`, el mismo `inputId` resolvió HTTP 200 y el overlay anterior estuvo disponible desde Backend, pero la primera reinferencia estricta respondió `502 AI_REAL_INFERENCE_FAILED`; los logs del mount mostraron `429 rateLimitExceeded` al renombrar `ai-outputs/asset_registry/*.json`. La mitigación y validación posterior con outputs locales está descrita en la adenda de cierre abajo.
- Reinicio Backend E2E: inicialmente `00002-6wt` no llegó a registrar `Started AiBackendApplication` ni `Tomcat started`; la causa y corrección están en la adenda. En ese momento `00001-s6n` conservaba el tráfico. La validación final de persistencia tras cambio de revisión se completó con `00006-kez` y se registra más abajo.
- La revisión AI `00002-247` permanece Ready y recibe el 100% del tráfico de AI E2E. No se modificó `pfi-ai-module` productivo.

### Matriz de cierre

| Área | Estado |
| --- | --- |
| Infraestructura frontend/backend/AI/Cloud SQL | PASS para servicios E2E activos (`00001` backend, `00002` frontend, AI `00004`); candidato de reinicio backend FAIL |
| Seguridad AI privada y Backend→AI Google ID Token | PASS |
| Secret Manager | PASS; valores no incluidos en evidencia |
| Demo auth productivo | DISABLED |
| Demo auth E2E | ENABLED, solo en entorno separado |
| Login/upload/inferencia real/resultado/revisión | PASS; UI muestra resultados y review draft persistido |
| Backend restart persistence | NOT DEMONSTRATED: candidata no completó startup en 4 min; tráfico E2E quedó en la revisión Ready anterior |
| AI input recovery | PASS (mismo inputId/slice 200 después de revisión nueva) |
| AI output asset recovery | PASS desde almacenamiento persistente del Backend |
| AI restart persistence end-to-end | Inicialmente FAIL con GCSFuse 429; corregido en adenda con outputs efímeros y reinferencia PASS |
| ADMIN institucional activo | NO (`activeNonDemoAdminCount=0`) |
| Producción institucional | NO; `TECHNICALLY DEPLOYED / INSTITUTIONAL ACTIVATION PENDING` |

## Pendientes de cierre

1. Diagnosticar la revisión backend E2E `00002-6wt`: la app queda después de `HikariDataSource Start completed`, no registra evento Spring `Started`, y Cloud Run declara timeout del startup probe a los cuatro minutos. No se le asignó tráfico; causa exacta no determinada.
2. AI GCSFuse registry 429 resuelto operacionalmente al pasar `PFI_OUTPUT_DIR` a almacenamiento efímero local; dos reinferencias estrictas post-restart pasaron.
3. Repetir persistencia backend tras una revisión Ready y confirmar estudio, corrida, revisión y assets desde esa revisión.
4. Alta institucional queda pendiente de un ADMIN legítimo por el mecanismo de bootstrap de la app; ningún dato profesional ficticio fue utilizado.

Las revisiones E2E no convierten el sistema en producción institucional. No se hizo push.

## Actualización de cierre — 2026-10-07 (adenda que reemplaza el estado E2E anterior)

### AI: causa de los 429 y estrategia final

La inspección del flujo confirmó que Backend solicita al AI los PNG permitidos al completar la corrida, los guarda en PostgreSQL (`PostgresRunAssetContentStorage`) y los sirve desde PostgreSQL (`AiBackendService.getAsset`) antes de intentar el backfill privado al AI. El backfill queda como recuperación cuando falta el payload; no es la ruta normal después de una copia exitosa. Así, los working outputs del AI pueden ser efímeros sin perder los assets de producto copiados correctamente.

Se desplegaron, sin cambiar IAM ni código de inferencia, `pfi-ai-module-e2e-00003-7cf` y `pfi-ai-module-e2e-00004-hbn`; ambas Ready. AI sigue privada y Backend E2E es el único invocador autorizado. Configuración efectiva: `PFI_UPLOAD_DIR=/mnt/pfi-runtime/ai-inputs` (mount persistente), `PFI_OUTPUT_DIR=/tmp/pfi-ai-outputs`, provider filesystem/local para outputs, CPU, concurrency 1, min 0 / max 1. No se creó bucket nuevo. Los modelos conservan el mount read-only existente.

- Después de `00003-7cf`, el mismo input resolvió slice HTTP 200; una corrida multiplanar estricta completó con `effectiveInferenceMode=real_baseline`, `degradedMode=false`, `humanReviewRequired=true`, `notClinicalDiagnosis=true`. Los overlays sagital y axial fueron HTTP 200 con `X-PFI-Asset-Source=postgres`; UI mostró el estudio analizado y sus estructuras.
- Después del segundo restart, `00004-hbn`, el mismo input resolvió y la misma inferencia estricta volvió a completar en modo real y sin degradación. Búsquedas de Cloud Logging de esa revisión para `rateLimitExceeded` y `AI_REAL_INFERENCE_FAILED` retornaron cero eventos.
- Resultado: `AI_INPUT_PERSISTENCE_AFTER_RESTART=PASS`, `AI_REINFERENCE_AFTER_RESTART=PASS`, `BACKEND_ASSET_DURABILITY=PASS`. `PFI_OUTPUT_DIR` no es persistente por decisión de diseño (`EPHEMERAL BY DESIGN`); los assets visibles al producto son durables en Backend. La causa operacional del 429 fue actualizar repetidamente el asset registry dentro del mount Cloud Storage FUSE.
- La disponibilidad del AI fue demostrada por la integración backend→AI y la inferencia real; el booleano específico `aiModule.available` del endpoint diagnostics no quedó capturado por separado.

### Backend E2E `00002-6wt`: timeline y causa pendiente

Comparación efectiva de `pfi-backend-e2e-00001-s6n` (Ready) y `00002-6wt` (fallida): imagen/digest idénticos (`sha256:20ad59093d3e8b1b178a8c93557aac45cd30ce89a73a7c59c2133f6b2d37a101`), misma service account, env de app/Secrets/AI/DB, CPU 1, memoria 512 MiB, puerto 8080, startup CPU boost, startup TCP probe de 240 s con un intento y sin Cloud SQL attachment/VPC. Diferencias declaradas: `00002` añade `PFI_E2E_RESTART_PROBE=after-e2e-persist` (sin referencia en código) y annotation `maxScale=20`; `00001` no declara esa annotation. `00002` no recibió tráfico; el servicio conserva 100% en `00001-s6n`.

| Evento desde inicio de instancia | E2E `00001-s6n` | E2E `00002-6wt` |
| --- | ---: | ---: |
| JVM/Spring start | ~4.9 s | ~3.5 s |
| Tomcat initialized | ~8.8 s | ~7.8 s |
| WebApplicationContext terminado | ~12.1 s | ~8.0–8.3 s |
| Hikari `Start completed` | ~12.2 s | ~8.1–8.5 s |
| Tomcat started / Spring `Started` | ~15.0 s / ~15.2 s | nunca observado |
| Último log de aplicación | Spring `Started` | Hikari `Start completed`; luego silencio hasta timeout a 240 s |
| Resultado | startup probe pasó al primer intento | `HealthCheckContainerError`, `DEADLINE_EXCEEDED`; segundo intento repitió el silencio |

`00002` alcanzó JVM/Spring, perfil default, contexto web, inicialización Tomcat y pool JDBC; no llegó a registrar `Tomcat started` ni `Started AiBackendApplication`. En una reproducción exacta posterior (`00005-poc`, misma imagen/config y probe) las muestras read-only de PostgreSQL capturaron al holder `pfi_app` PID `41712`, idle (`Client/ClientRead`) con el advisory lock exclusivo concedido; su última consulta era la lectura parametrizada de un payload en `domain_run_asset_payloads`. El waiter PID `42183` estaba active con `wait_event_type=Lock`, `wait_event=advisory`, `granted=false`, ejecutando `SELECT pg_advisory_lock($1)`; `pg_blocking_pids` y el lock key corresponden al holder. El holder llevaba idle desde la consulta de payload (~4 min a la muestra); el waiter permaneció bloqueado hasta que Cloud Run agotó la ventana de 240 s y reemplazó el intento. No se mató ninguna sesión.

Root cause: `SqlMigrationRunner` tomaba un **session-level advisory lock** y confiaba en `Connection.close()` para liberarlo. Los componentes usan una conexión Hikari prestada: `close()` la devuelve al pool, pero PostgreSQL conserva viva la sesión y el advisory lock. Cuando otra revisión creaba una sesión JDBC nueva y ejecutaba el mismo `pg_advisory_lock`, esperaba indefinidamente detrás del holder idle. Esto explica el silencio después de Hikari y por qué la espera no era progreso normal de Spring.

Fix mínimo: `SqlMigrationRunner.apply()` ejecuta `pg_advisory_unlock` en la misma sesión en `finally`, incluso si falla la aplicación de migraciones; si falla el unlock, registra un evento sanitizado y falla cerrado. Se añadieron logs INFO sanitizados para scan/pending/per-migration/lock, auth store, repositorios PostgreSQL, asset storage, SecurityStartupValidator y el skip no-production de AdminBootstrapService. No se modificó probe, lógica de negocio, AI, IAM ni Cloud Storage. La prueba específica pasó (5/5 `SqlMigrationRunnerTest`); el build diagnóstico se desplegó y `00006-kez` confirmó el fix en Cloud Run.

En la etapa previa al fix, `00003-zac` arrancó en 16.4 s y se promovió al 100% del tráfico E2E tras smoke exitoso. `00005-poc` reprodujo el bloqueo y permaneció sin tráfico. El estado de tráfico vigente se registra en la adenda final siguiente.

### Revisión de staging

Desde la UI se guardó en el estudio E2E un borrador pendiente con la nota: “Prueba técnica E2E de persistencia de revisión; anotación no clínica, no modifica mediciones ni implica validación diagnóstica.” Tras recargar el estudio, estado y nota volvieron a aparecer. Se verifica persistencia de una operación soportada de review draft; no es una corrección de medición ni certifica una revisión por profesional institucional. El selector de archivo del frontend no fue ejecutado: `FRONTEND_FILE_PICKER_UPLOAD=NOT_EXECUTED`.

### Matriz vigente

| Requisito | Resultado vigente |
| --- | --- |
| Frontend E2E / carga vía backend / visualización | PASS |
| Backend E2E Ready y tráfico | PASS en `00006-kez`; recibe 100% del tráfico E2E |
| Cloud SQL, modo postgres, schema migrations 20/20 | PASS |
| AI privada; invocación backend con Google ID Token; Secrets | PASS |
| AI input después de restart | PASS, mismo inputId resuelto tras `00003` y `00004` |
| Reinferencia strict real después de restart | PASS en `00003` y `00004`; sin degradación; revisión `00004` sin logs 429/failure |
| Assets de producto después de restart AI | PASS desde persistencia PostgreSQL Backend |
| Review draft técnica/no clínica tras reload | PASS |
| Professional measurement correction | NOT EXECUTED; no se alteró medición del caso |
| Backend restart persistence sobre revisión nueva | PASS en `00006-kez`; recuperación del mismo estudio/run/review/assets desde PostgreSQL |
| Frontend file picker | NOT_EXECUTED |
| `activeNonDemoAdminCount` | 0; sin cambios SQL y sin bootstrap |
| Producción institucional | PENDING; `pfi-backend-v2-dev-00004-d8r` conserva 100% del tráfico y demo auth deshabilitado |

No se hizo commit adicional ni push; estos cambios documentales quedan locales para revisión conjunta.

### Cierre del fix Backend — 2026-10-07

Se desplegó imagen `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/pfi-backend@sha256:312d1686cb15875572ec0a07cfa80edc9ee06a61d8c37d59c349e1dc3946c488` como revisión `pfi-backend-e2e-00006-kez`, inicialmente con 0% de tráfico. Se conservó la startup TCP probe original (240 s), max 1, service account E2E, secretos por referencia, Postgres, URL/auth de AI y resto de configuración funcional; no se modificó producción.

La cronología observada en los logs de `00006-kez`, desde inicio de contenedor:

| Evento | Tiempo aproximado | Resultado |
| --- | ---: | --- |
| JVM / Spring start | 0–0.4 s | PASS |
| Tomcat initialized | 4.0 s | PASS |
| Contexto web y Hikari listos | 4.1–7.9 s | PASS |
| Auth store migrate/ready | 7.9–10.6 s | PASS |
| Migraciones de repositorios | 10.7–12.8 s | PASS; 20 escaneadas, 0 pendientes |
| Advisory lock | 2–11 ms por adquisición | PASS; cada runner liberó el lock antes de retornar la conexión Hikari |
| SecurityStartupValidator / AdminBootstrap | 12.9 s | PASS; bootstrap saltado por `non_production` |
| Tomcat started / Spring Started | 14.0 / 14.1 s | PASS |
| Cloud Run Ready | 14.2 s desde inicio de instancia; 21.48 s para deployment | PASS |

La revisión etiquetada sin tráfico respondió health 200; login demo E2E y llamada autenticada PASS; `/api/system/diagnostics` informó `database.connected=true`, `database.mode=postgres`, `aiModule.available=true`, `aiModule.status=ok`. La worklist recuperó el mismo caso `E2E-1008446160`; la review de la misma corrida `multi-97020fdb7632109f1252` continuó `pending`, con la misma nota técnica y 0 correcciones; los overlays sagital y axial devolvieron HTTP 200 y `X-PFI-Asset-Source=postgres`.

Tras pasar los checks directos, el servicio E2E movió 100% del tráfico a `00006-kez`. Se repitieron las comprobaciones por la URL estable del servicio con los mismos resultados. `BACKEND_RESTART_PERSISTENCE=PASS`. La revisión `00003-zac` queda en 0% y no fue eliminada. La revisión productiva `pfi-backend-v2-dev-00004-d8r` permanece intacta con 100% de su tráfico.

### Verificación de código

Build de imagen Cloud Build: PASS, digest indicado arriba. Prueba enfocada `SqlMigrationRunnerTest`: 5/5 PASS con JDK 21. Se inició la suite Maven completa; quedó bloqueada en una prueba que intenta adjuntar el agente dinámico de Mockito en este host Windows (error de attach/pipe); se interrumpió ese proceso. Las pruebas de integración Testcontainers reportaron 3 omitidas porque no hay Docker disponible. La suite completa, por tanto, no queda declarada PASS.

No se hizo push ni commit adicional. Cambios de código y evidencia permanecen locales para revisión.

## Costos y pendientes

Cloud Run escala a cero, con máximo una instancia por servicio. Cloud SQL permanece **encendido continuamente** (`db-f1-micro`, no HA) y generará costo continuo; buckets y Artifact Registry generan almacenamiento, Cloud Build consume minutos y tráfico/salidas de GCS puede generar cargos. No se cambió billing ni se crearon recursos persistentes adicionales.

Pendiente para completar el objetivo solicitado: resolver por qué las nuevas revisiones backend se quedan después de `HikariDataSource Start completed` y no terminan el startup probe; confirmar/admin bootstrap con identidad profesional real si no existe un ADMIN activo; luego completar el flujo E2E con el ZIP de prueba desidentificado ya destinado al desarrollo y verificar recuperación post-revisión. Hasta entonces, esta evidencia no certifica el producto E2E.

## Revisión activa y rollback

El backend healthy `pfi-backend-v2-dev-00004-d8r` recibe 100% del tráfico. Rollback/corte explícito:

```powershell
gcloud run services update-traffic pfi-backend-v2-dev --project=pfi-asplanatti-fabrello-v1 --region=us-central1 --to-revisions=pfi-backend-v2-dev-00004-d8r=100
```

Para los otros servicios, seleccionar una revisión anterior con `gcloud run revisions list --service=... --region=us-central1` y dirigir tráfico con `gcloud run services update-traffic`. No se ejecutó rollback destructivo.

## FINAL FREEZE CLOUD E2E — 2026-10-07

Esta adenda sustituye los SHA, imágenes, revisiones y matrices E2E de las secciones históricas anteriores para el freeze vigente. Se usaron los candidates aprobados; no se reconstruyó desde `origin/main`, no hubo push y producción no se modificó.

### Baselines inmutables e imágenes

| Componente | SHA final | Artifact Registry digest | Build de imagen |
| --- | --- | --- | --- |
| Backend | `ba76fa928c53ece6022bde432ba03a4497e39551` | `sha256:98565af622ab68e73f09ea0379453f8c53289d206c78ee989393628c53f4c427` | `8600be13-5064-4a26-ada8-f5816d8e5a84` — SUCCESS |
| AI Module | `8254f3ab5636b80673ad5371c72b007775cc432d` | `sha256:bf4b4ba371a0cdb5a501b2215cb92bb4a657e227fc7c18b670a0d70df4ebf664` | `e0f28897-8adb-48fb-861e-8e7e0861735a` — SUCCESS |
| Frontend | `3d7995714c139191359c92228e045890219d4678` | `sha256:f6eb2fc89d2c90c8c6a3d39c4f744818b1967ff908fa845a0c4cd096629ca589` | `565dbb9d-7af6-4177-906a-ca08a4f37d81` — SUCCESS |

Registry: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers`; all deployed image references are digest-pinned.

### CI y tests del freeze

| Componente | Build de verificación | Resultado |
| --- | --- | --- |
| Backend | Cloud Build `4bc3c22d-64aa-458d-b5df-922f343cad1d` | `mvn -B verify`: PASS; 1088 tests, 0 failures, 0 errors, 1 skip intencional; JaCoCo ejecutado. |
| AI Module | Cloud Build `d42b9c21-1f64-47c5-a8f8-3a85fc644ae6` | compileall, API smoke, Docker build: PASS; pytest Linux: 567 passed, 12 deselected. Grupo de checkpoints: 9 skips porque falta el checkpoint externo congelado y `PFI_SUBARTICULAR_CHECKPOINT_PATH`; se mantienen como skips, no se ocultan. |
| Frontend | Cloud Build `56afa417-2c3a-4ba3-9d4e-91fc2658772d` | `npm ci`, lint, typecheck, test script, coverage, Vite build y Docker build: PASS. El build Linux evitó el `EPERM realpath` observado localmente en Windows/OneDrive. La imagen reportó 1 vulnerabilidad npm moderate y 4 high; el build no falló. |

La ejecución local completa de pytest en Windows quedó detenida por bloqueo sin salida; la suite Linux de Cloud Build es la evidencia usada. No se afirma que ese intento local terminara PASS.

### Revisiones activas E2E

| Servicio | Revisión activa al cierre | Tráfico | Imagen |
| --- | --- | ---: | --- |
| Frontend `pfi-frontend-e2e` | `pfi-frontend-e2e-00004-qaq` | 100% | Frontend SHA/digest de arriba |
| Backend `pfi-backend-e2e` | `pfi-backend-e2e-finalverify` | 100% | Backend SHA/digest de arriba |
| AI privado `pfi-ai-module-e2e` | `pfi-ai-module-e2e-finalrestart` | 100% | AI SHA/digest de arriba |

Backend conservó `startupProbe` de 240 s, modo PostgreSQL, storage GCS, secret references E2E y AI privado con Google ID Token. La revisión de restart se creó sin tráfico; health 200 y runtime diagnostics confirmaron PostgreSQL/GCS antes de promoverla. AI conserva CPU, `min-instances=0`, `max-instances=1`, `PFI_UPLOAD_DIR=/mnt/pfi-runtime/ai-inputs` en volumen persistente, `PFI_OUTPUT_DIR=/tmp/pfi-ai-outputs` efímero y los mounts existentes. IAM de AI quedó privado: el único miembro `roles/run.invoker` es `pfi-backend-e2e-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com`.

Frontend `/env.js`: HTTP 200; `API_BASE_URL=https://pfi-backend-e2e-7cxg46zwea-uc.a.run.app`; `VITE_USE_MOCK=false` en el release. Backend E2E mantiene `PFI_AUTH_DEMO_ENABLED=true` para el login de test; esto no cambia la configuración institucional.

### Matriz de infraestructura

| Componente | Estado | Evidencia |
| --- | --- | --- |
| Frontend | PASS | Revisión final Ready, 100% tráfico, `/env.js` HTTP 200 y URL estable de Backend. |
| Backend | PASS | Revisión final Ready/100%, health 200, config Postgres/GCS y startup probe conservada en 240 s. |
| Cloud SQL | PASS | `postgresEnabled=true`; datasource Hikari con conexiones disponibles y sin threads esperando. |
| AI privado | PASS | IAM invoker únicamente para service account de Backend; AI health 200 vía Backend. |
| Secret Manager | PASS | Referencias runtime de DB/JWT E2E presentes; no se imprimió material secreto. |
| Cloud Storage inputs | PASS | Mount persistente `PFI_UPLOAD_DIR`; inputId recuperó una slice HTTP 200 tras restart AI. |
| Backend durable assets | PASS | Input/overlay sagital y axial HTTP 200; artefactos almacenados durables como `postgres_bytea`. |
| Backend → AI ID Token | PASS | Inferencia real completada a través del Backend; el servicio AI siguió privado. |

### FINAL FREEZE funcional

Caso completamente nuevo y desidentificado `FFZ-20261007-001`, subjectRef `FFZ-SUBJ-001`; ZIP seleccionado desde el file picker real del Frontend. Se registraron T1 sagital (12 cortes), T2 sagital (12 cortes) y T2 axial (24 cortes).

| Comprobación | Resultado / evidencia |
| --- | --- |
| Login y worklist | PASS; logout/login posterior volvió a cargar el mismo caso. |
| File picker y upload | `FRONTEND_FILE_PICKER_UPLOAD=PASS`; `POST /api/ai/studies` respondió 200. |
| Ingesta y metadata | PASS; estudio, 3 series y `FFZ-SUBJ-001` visibles en Backend y worklist. |
| AI health/availability | PASS a través de Backend: `aiModuleAvailable=true`, `degradedMode=false`, `defaultInferenceMode=real_baseline`; Google ID Token desde el Backend y servicio AI privado. |
| Inferencia multiplanar | PASS, incluido después del restart AI: `status=completed`, `effectiveInferenceMode=real_baseline`, sagittal/axial `ready`, `degradedMode=false`, `humanReviewRequired=true`, `notClinicalDiagnosis=true`. Run `multi-dae60d88a949569ce186`. |
| Sagittal/axial, overlays y navegación | PASS; estructuras/render de ambos planos. Después de la reinferencia, `input.png` y `overlay.png` de ambos planos respondieron HTTP 200 desde `postgres_bytea`. |
| Mediciones | PASS en la corrida multiplanar y visibles por nivel en UI. El reporte Post-E50 conserva la limitación real: no declara mediciones efectivas cuando esa corrida no las produjo. |
| Review profesional | PASS como borrador pendiente, con nota técnica de no corrección clínica; no se aceptó como revisión clínica final. Persistió al reiniciar Backend y al hacer logout/login. |
| Anotación profesional | PASS; anotación a alcance de estudio guardada junto con el borrador; persistió por Backend, tras reinicio y en la sesión posterior. No se creó corrección de medición artificial. |
| Reporte estructurado / JSON | PASS; `reportStatus=ready`, análisis Post-E50 `partial`, JSON HTTP 200, content type application/json, schema `pfi.study-report.v1`, run `c6260130-7d69-4172-ab78-759111daa26f`. |
| PDF | PASS; HTTP 200, application/pdf, `%PDF-1.6`, 6696 bytes. |
| Historial | PASS para subjectRef `FFZ-SUBJ-001`; una entrada de-identificada, review pending. No había estudio previo comparable: longitudinal `NOT_APPLICABLE`. |
| Concordancia EXT-01 | `NOT_APPLICABLE`; UI deshabilitó el cálculo por ausencia de hallazgos clasificados equiparables. |
| Analytics EXT-02 | PASS; pantalla y telemetría leídas, contadores/tasas en 0 porque no se hicieron correcciones clínicas. No representa una estimación de exactitud. |
| Feedback EXT-05 | `NOT_APPLICABLE`; captura consultada, 0 candidatos porque no hubo corrección, región ni sugerencia profesional rechazada. |
| Región de evidencia | `NOT_APPLICABLE`; no se inventó una región clínica para generar feedback. |
| Grad-CAM | `EXPERIMENTAL_UNAVAILABLE`; la UI lo mantiene deshabilitado sin localización y checkpoint habilitante. |

### Persistencia y límites de reinicio

- Backend reiniciado con la misma imagen/configuración como `pfi-backend-e2e-finalverify`; `Ready=True`, `/api/system/health=200`, PostgreSQL configurado y pool Hikari con una conexión idle/sin esperas. El mismo estudio, corrida, review, anotación, reporte e historial se recuperaron por Backend. Después, la reinferencia AI persistió los assets nuevos y quedaron disponibles desde Backend.
- AI reiniciado con el mismo digest/configuración como `pfi-ai-module-e2e-finalrestart`; `Ready=True`, IAM no cambió. El mismo `inputId` siguió disponible (slice vía Backend HTTP 200) y el contrato estricto `/api/ai/multiplanar/run` repitió la inferencia real sin degradación. La nueva corrida y sus assets se hicieron visibles por Backend.
- La respuesta de producto Post-E50 es `partial`, conserva `67B2_RUNTIME_PARITY_NOT_PROVEN`, localización automática unavailable/abstained y revisión humana requerida. Axial pairing sigue experimental/candidate; P10.7 degenerativo se bloquea por falta de preprocessing parity; subarticular no está configurado. No se presentaron como capacidades validadas.
- Al reabrir el caso tras login, el badge global del Frontend mostró `Modo degradado` aunque `/api/ai/health` reportó `aiModuleAvailable=true`, `degradedMode=false` y la corrida estricta devolvió `degradedMode=false`. Es una discrepancia del indicador de estado del Frontend; no cambia el estado ni la respuesta de la API, y no debe usarse como prueba de degradación de la inferencia.
- Prueba adicional fuera del flujo de producto: el endpoint legacy de una sola serie `/api/ai/pipeline/run` devolvió `AI_CONTRACT_VIOLATION` tras AI restart, aunque AI respondió 200. No se usó como inferencia de producto ni como resultado PASS; el flujo estricto oficial multiplanar descrito arriba sí completó y persistió outputs.
- El endpoint de reproceso de ZIP del Backend no encontró un `study_input` durable para su `studyId`; ese reproceso de archivo bruto no se marca PASS. La condición pedida para AI restart —`inputId` persistente, reinferencia estricta y nuevo output visible por Backend— pasó a través del mount persistente `PFI_UPLOAD_DIR` y del flujo multiplanar. Esta diferencia queda documentada.

### Estado institucional y cierre

Producción institucional no se modificó: `pfi-backend-v2-dev-00004-d8r` conserva 100% de tráfico y `PFI_AUTH_DEMO_ENABLED=false`; el conteo read-only previamente comprobado continúa en `activeNonDemoAdminCount=0`. Estado: **TECHNICALLY DEPLOYED / INSTITUTIONAL ACTIVATION PENDING**.

`FULL FINAL FREEZE CLOUD E2E = PASS`, con las limitaciones esperadas y los dos límites de rutas auxiliares documentados arriba. No se implementó Qwen ni se trabajó sobre billing. Al cerrar esta adenda, el único cambio local pendiente en el candidato Backend era esta documentación (`docs/GCP_DEPLOYMENT_EVIDENCE.md`); todavía no se había hecho commit ni push para revisión.
