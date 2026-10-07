# Evidencia de despliegue GCP — PFI RM lumbar

**Fecha:** 2026-10-07  
**Estado:** despliegue parcial; smoke end-to-end y persistencia tras reinicio pendientes.  
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

## Verificaciones en GCP

- Frontend `/env.js`: HTTP 200 y API base URL apunta a `pfi-backend-v2-dev`.
- Backend `/api/system/health`: `{"status":"ok"}`.
- CORS preflight desde el origin frontend: HTTP 204 y `Access-Control-Allow-Origin` coincide con ese origin.
- `/api/auth/demo-doctor`: HTTP 401 en la revisión activa, como se requiere con demo auth desactivado.
- AI revision `Ready=True` y startup probe de Cloud Run correcta. No se invocaron directamente `/health` ni `/readiness`: el AI es privado y la sesión local no tiene permiso de impersonación de `pfi-backend-runtime` (`iam.serviceAccounts.getAccessToken` denegado). No se amplió IAM para la prueba.
- **No ejecutado:** login profesional, upload/estudio de prueba, inferencia real, revisión, lectura tras cerrar/abrir y prueba de persistencia tras nueva revisión. La ventana temporal autorizada de demo no se pudo completar: la revisión backend `00005` no superó startup probe y no se llegó a crear cuenta demo ni subir un estudio. Cloud Run informó 15 fallos consecutivos de `STARTUP HTTP probe` en `/api/system/health` con `ERROR_CONNECTION_FAILED`; el log de aplicación llegó a `HikariDataSource Start completed` y no registró el final de arranque. El tráfico se fijó explícitamente al backend healthy `00004`, que tiene demo auth apagado. La revisión `00006` con demo apagado también seguía esperando readiness al documentar.
- Una cuenta ADMIN activa preexistente no pudo confirmarse por un endpoint de lectura no privilegiado. El repositorio requiere un ADMIN activo para el perfil `production`; su bootstrap exige email, nombre, número de matrícula, institución y una contraseña fuerte, sin valores ficticios.

## Costos y pendientes

Cloud Run escala a cero, con máximo una instancia por servicio. Cloud SQL permanece **encendido continuamente** (`db-f1-micro`, no HA) y generará costo continuo; buckets y Artifact Registry generan almacenamiento, Cloud Build consume minutos y tráfico/salidas de GCS puede generar cargos. No se cambió billing ni se crearon recursos persistentes adicionales.

Pendiente para completar el objetivo solicitado: resolver por qué las nuevas revisiones backend se quedan después de `HikariDataSource Start completed` y no terminan el startup probe; confirmar/admin bootstrap con identidad profesional real si no existe un ADMIN activo; luego completar el flujo E2E con el ZIP de prueba desidentificado ya destinado al desarrollo y verificar recuperación post-revisión. Hasta entonces, esta evidencia no certifica el producto E2E.

## Revisión activa y rollback

El backend healthy `pfi-backend-v2-dev-00004-d8r` recibe 100% del tráfico. Rollback/corte explícito:

```powershell
gcloud run services update-traffic pfi-backend-v2-dev --project=pfi-asplanatti-fabrello-v1 --region=us-central1 --to-revisions=pfi-backend-v2-dev-00004-d8r=100
```

Para los otros servicios, seleccionar una revisión anterior con `gcloud run revisions list --service=... --region=us-central1` y dirigir tráfico con `gcloud run services update-traffic`. No se ejecutó rollback destructivo.
