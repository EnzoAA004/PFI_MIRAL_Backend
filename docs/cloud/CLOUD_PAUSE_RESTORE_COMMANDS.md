# Cloud Environment — Pause / Restore Procedure

Project: `pfi-asplanatti-fabrello-v1` (region `us-central1`).

Status: the executable Cloud infrastructure is **intentionally paused**
while work continues on financial modeling and thesis documentation.
This document is the authoritative record of what was paused, what
was left untouched, and the exact (unexecuted) commands to bring the
environment back. **No command in this document has been run as part
of writing it.** Live state below was captured read-only via
`gcloud ... describe` / `gcloud ... list` on 2026-09-23.

---

## 1. Estado pausado actual

### 1.1 Cloud Run — real mechanism used

A genuine platform limitation was hit and is recorded here rather than
glossed over: Cloud Run's `--max-instances` flag **rejects `0`**
(`Invalid value 0. autoscaling.knative.dev/maxScale annotation must be
a positive integer.`), so a literal "manual scaling = 0" is not an
operation the platform accepts for any of the four services. This was
confirmed live against all four services during the original pause
attempt.

The practical equivalent that **was** already in place, and is
confirmed still in place by the live `describe` calls below, is
**`min-instances` unset (= 0, scale-to-zero)** with the original
`max-instances` preserved. Under this configuration Cloud Run bills
$0 for compute whenever a service is idle (no allocated instance),
which is the same cost outcome a manual scale-to-zero would produce.
No traffic split, image, revision, env var, or IAM binding was changed
on any of the four services.

| Service | Latest ready revision | Traffic revision | Image digest | CPU / RAM | Concurrency | Timeout | maxScale | minScale | Active instances |
|---|---|---|---|---|---|---|---|---|---|
| `pfi-backend-dev` (legacy) | `pfi-backend-dev-stage4h-warm` | `pfi-backend-dev-00002-jwd` @ 100% | `sha256:39ae4cdc3169...` | 1 vCPU / 512Mi | 80 | 300s | 2 | unset (0) | 0 |
| `pfi-backend-v2-dev` (Green) | `pfi-backend-v2-dev-00003-8xf` | `pfi-backend-v2-dev-00003-8xf` @ 100% | `sha256:4a6e404817e0...` | 1 vCPU / 512Mi | 80 | 300s | 2 | unset (0) | 0 |
| `pfi-ai-module` | `pfi-ai-module-00008-gww` | `pfi-ai-module-00008-gww` @ 100% | `sha256:14cdaff9efd8...` | 4 vCPU / 2Gi | 1 | 300s | 1 | unset (0) | 0 |
| `pfi-frontend-dev` | `pfi-frontend-dev-00002-bv5` | `pfi-frontend-dev-00002-bv5` @ 100% | `sha256:c35dd7ae6080...` | 1 vCPU / 256Mi | 80 | 300s | 2 | unset (0) | 0 |

All four confirmed: image, revision, traffic split, env vars, and IAM
identical to the pre-pause baseline (see Section 3 for the full
per-service snapshot).

### 1.2 Cloud SQL — genuinely stopped

`pfi-postgres-dev`:

- `state: STOPPED`
- `settings.activationPolicy: NEVER`
- `settings.tier: db-f1-micro`
- Confirmed stopped: a `gcloud sql databases list` call against it now
  fails with `Invalid request since instance is not running` — this
  is expected/correct evidence that the instance is truly not
  serving, not an error to fix.

Only compute is paused; storage and data are preserved (Cloud SQL
keeps billing a small storage-only charge while stopped, which is
expected and does not require any action).

---

## 2. Recursos persistentes intactos

Confirmed live, read-only, on 2026-09-23 — nothing below was deleted,
resized, or reconfigured:

| Recurso | Estado confirmado |
|---|---|
| `gs://pfi-backend-durable-dev` | Existe, listable, contenido no tocado. |
| `gs://pfi-rm-lumbar-artifacts-2026-ef` | Existe, listable, contenido no tocado (incluye el checkpoint montado por AI). |
| Cloud SQL data/storage (`pfi-postgres-dev`) | Preservado; instancia detenida, no borrada. |
| Artifact Registry (`pfi-containers`, Docker) | Repositorio presente; imágenes usadas por los 4 servicios no eliminadas (dígitos de imagen arriba coinciden con el baseline). |
| Secret Manager | `pfi-dev-database-url`, `pfi-dev-jwt-secret` presentes, sin rotar. |
| Service Accounts | Las 6 cuentas de servicio del proyecto presentes (`pfi-backend-runtime`, `pfi-ai-runtime`, `pfi-frontend-runtime-dev`, `pfi-training-vm`, `pfi-github-artifact-pusher`, cuenta de compute por defecto). |
| IAM | Sin cambios; la política del servicio `pfi-ai-module` sigue sin binding `allUsers`/`allAuthenticatedUsers` (acceso privado intacto). |
| Imágenes Docker | Los 4 servicios siguen referenciando los mismos dígitos `sha256:...` del baseline pre-pausa. |
| Revisiones Cloud Run | Todas las revisiones previas siguen listadas (por ejemplo, 16 revisiones históricas en `pfi-backend-dev`); ninguna fue borrada. |

---

## 3. Snapshot para restore

### 3.1 Frontend (`pfi-frontend-dev`)

- Servicio: `pfi-frontend-dev`
- Revisión activa: `pfi-frontend-dev-00002-bv5` (100% tráfico)
- Imagen: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/frontend@sha256:c35dd7ae60808c9c612c8918ce8662ab9615456db238b32e89927a9295d44103`
- CPU/RAM: 1 vCPU / 256Mi
- Max instances: 2 (min instances: unset/0)
- Concurrency: 80
- Public access: sí (sirve la SPA; no requiere invoker IAM)
- `BACKEND_URL`: `https://pfi-backend-v2-dev-810538429027.us-central1.run.app` (Backend Green — confirma que el switch de Etapa 9 sigue vigente)

### 3.2 Backend Green (`pfi-backend-v2-dev`)

- Servicio: `pfi-backend-v2-dev`
- Revisión activa: `pfi-backend-v2-dev-00003-8xf` (100% tráfico, `latestRevision: true`)
- Imagen: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend@sha256:4a6e404817e068bc568a583294ce9723d72b144e64f74d472e5884db0fa2e80d`
- CPU/RAM: 1 vCPU / 512Mi
- Max instances: 2 (min instances: unset/0)
- Concurrency: 80
- Timeout: 300s
- Env vars críticas:
  - `PFI_PERSISTENCE_MODE=postgres`
  - `PFI_STORAGE_PROVIDER=gcs`, `PFI_STORAGE_GCS_BUCKET=pfi-backend-durable-dev`
  - `DATABASE_URL` ← secret `pfi-dev-database-url`
  - `PFI_AUTH_JWT_SECRET` ← secret `pfi-dev-jwt-secret`
  - `PFI_AUTH_DEMO_ENABLED=true`
  - `PFI_CORS_ALLOWED_ORIGINS` incluye Vercel y el propio dominio de `pfi-frontend-dev`
- Cloud SQL config: conecta a `pfi-postgres-dev` vía `DATABASE_URL` (Cloud SQL Auth Proxy / socket, no se modificó esta sesión)
- AI URL/audience/auth mode:
  - `PFI_AI_SERVICE_URL=https://pfi-ai-module-7cxg46zwea-uc.a.run.app`
  - `PFI_AI_SERVICE_AUDIENCE=https://pfi-ai-module-7cxg46zwea-uc.a.run.app`
  - `PFI_AI_AUTH_MODE=google-id-token`
  - `PFI_AI_PRIVATE_REQUIRED=true` (fail-closed: el Backend rehúsa iniciar si el modo de auth no está correctamente configurado)

### 3.3 Backend legacy (`pfi-backend-dev`)

- Revisión con tráfico: `pfi-backend-dev-00002-jwd` (100%)
- `latestReadyRevisionName` reportado por la API: `pfi-backend-dev-stage4h-warm` (nombre de una revisión retirada; ya documentado como un artefacto cosmético del historial de despliegues, no un cambio real — ver `CLOUD_STAGE_10B_COST_ANOMALY_CLEANUP.md`)
- Imagen: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/backend@sha256:39ae4cdc31693cf323e2485acd9fc3b0d27463a5bb3105cd945ccdd72368c068`
- Traffic: 100% a `pfi-backend-dev-00002-jwd`
- Scaling previo esperado: `min=0` (sin anotación `minScale`, corregido en Etapa 10B tras detectarse una anomalía de costo), `max=2`
- Este servicio es el Backend **legacy**; el Frontend apunta a Backend Green (`pfi-backend-v2-dev`), no a este. No requiere reactivación salvo necesidad explícita.

### 3.4 AI (`pfi-ai-module`)

- Revisión activa: `pfi-ai-module-00008-gww` (100% tráfico, `latestRevision: true`)
- Imagen: `us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers/pfi-ai-module@sha256:14cdaff9efd8bd2ae601dfd2f02f56a3bbe1ab532056c803565d4c8fdba9a16d`
- CPU/RAM: 4 vCPU / 2Gi
- Max instances: 1 (min instances: unset/0)
- Concurrency: 1
- Timeout: 300s
- Service account: `pfi-ai-runtime@pfi-asplanatti-fabrello-v1.iam.gserviceaccount.com`
- GCS durable config: `PFI_STORAGE_PROVIDER=gcs`, `PFI_STORAGE_GCS_BUCKET=pfi-backend-durable-dev`
- Checkpoint mount: `PFI_P10_7_CHECKPOINT_PATH=/mnt/pfi-artifacts/models/releases/p10_7_spider_degenerative_v1/frozen_p10_7_spider_degenerative_multitask.pt`, `PFI_P10_7_DEVICE=cpu`
- Private IAM: confirmado — `gcloud run services get-iam-policy` devuelve bindings vacíos (sin `allUsers`/`allAuthenticatedUsers`); solo puede invocarse con un ID token válido de una identidad autorizada (el Backend Green usa `google-id-token` como en 3.2).

### 3.5 Cloud SQL (`pfi-postgres-dev`)

- Tier: `db-f1-micro`
- DB name: no recuperable en este snapshot porque la instancia está `STOPPED` (`gcloud sql databases list` falla mientras no está `RUNNABLE`, tal como se espera); el nombre de base de datos de aplicación permanece el documentado en despliegues previos y no requiere acción — se confirmará automáticamente al reiniciar, en el paso de verificación post-restore.
- Region: `us-central1`
- Connection name: `pfi-asplanatti-fabrello-v1:us-central1:pfi-postgres-dev`
- Database version: `POSTGRES_15`
- Activation policy normal esperada (post-restore): `ALWAYS`

---

## 4. Comandos de restauración (documentados, NO ejecutados)

### 4.1 Cloud SQL

```bash
gcloud sql instances patch pfi-postgres-dev \
  --project=pfi-asplanatti-fabrello-v1 \
  --activation-policy=ALWAYS
```

Verificar:

```bash
gcloud sql instances describe pfi-postgres-dev \
  --project=pfi-asplanatti-fabrello-v1 \
  --format="value(state,settings.activationPolicy)"
```

Esperado: `RUNNABLE` / `ALWAYS`. El arranque suele tardar 1-2 minutos
antes de que el Backend pueda conectarse.

### 4.2 Cloud Run

Los cuatro servicios ya están en el equivalente funcional de "pausado"
(`min-instances` sin fijar, `max-instances` original — ver Sección
1.1); no hay ninguna anotación de manual scaling que revertir. Estos
comandos solo son necesarios si una sesión futura cambia `min`/`max`
respecto de este baseline y necesita volver a él:

```bash
# pfi-backend-dev (legacy)
gcloud run services update pfi-backend-dev \
  --project=pfi-asplanatti-fabrello-v1 --region=us-central1 \
  --min-instances=0 --max-instances=2

# pfi-backend-v2-dev (Green)
gcloud run services update pfi-backend-v2-dev \
  --project=pfi-asplanatti-fabrello-v1 --region=us-central1 \
  --min-instances=0 --max-instances=2

# pfi-ai-module
gcloud run services update pfi-ai-module \
  --project=pfi-asplanatti-fabrello-v1 --region=us-central1 \
  --min-instances=0 --max-instances=1

# pfi-frontend-dev
gcloud run services update pfi-frontend-dev \
  --project=pfi-asplanatti-fabrello-v1 --region=us-central1 \
  --min-instances=0 --max-instances=2
```

Ninguno de estos comandos cambia imagen, tráfico, variables de
entorno o IAM: solo tocan las anotaciones de autoscaling, y solo si
hicieran falta (es decir, si el estado real se hubiera desviado del
baseline de la Sección 1.1).

---

## 5. Orden recomendado de restore

1. **Cloud SQL** — primero, porque tanto Backend Green como Backend
   legacy dependen de la base de datos para health checks y
   operaciones normales; arrancarla último dejaría a los servicios
   Cloud Run en estado degradado o con errores de conexión durante la
   ventana de arranque de la instancia.
2. **AI** — segundo, porque tiene el cold-start más largo observado
   (~21-40s, dominado por la carga del checkpoint PyTorch) y porque
   Backend Green depende de poder alcanzarlo para reportar salud no
   degradada.
3. **Backend Green** — tercero, una vez que Cloud SQL y AI están
   disponibles, para que sus propios health checks (DB + AI) pasen
   limpio desde el primer request en lugar de reportar degradación
   transitoria.
4. **Frontend** — cuarto; no tiene dependencias de arranque propias
   más allá de poder alcanzar a Backend Green en tiempo de uso, así
   que no hay urgencia en reactivarlo antes que el resto.
5. **Legacy Backend, solo si realmente se necesita** — último y
   opcional: el Frontend ya no le envía tráfico (apunta a Backend
   Green desde la Etapa 9), por lo que reactivarlo no es parte del
   flujo normal de restore.

---

## 6. Verificaciones post-restore

### Cloud SQL

- [ ] `state=RUNNABLE`
- [ ] Conexiones disponibles (un `SELECT 1` a través del Backend, o
      `gcloud sql databases list` ya no falla por instancia detenida)

### AI

- [ ] Health autenticado (ID token válido) devuelve `200`
- [ ] Acceso directo sin autenticar sigue devolviendo `403` (confirma
      que la restauración no debilitó el acceso privado)

### Backend Green

- [ ] Health general `200`
- [ ] `/api/ai/health` en `200`, o en modo degradado controlado
      (`degradedMode:true`, HTTP 200) únicamente durante la ventana de
      cold-start de AI — nunca un error 5xx
- [ ] Conexión a la base de datos confirmada (health check con DB o
      una consulta de lectura simple)

### Frontend

- [ ] `200` en la URL pública
- [ ] `env.js` en runtime sigue apuntando a Backend Green
      (`pfi-backend-v2-dev`, no al legacy)
- [ ] Login demo funcional
- [ ] Los requests de la SPA se observan contra `pfi-backend-v2-dev`,
      no contra `pfi-backend-dev`

### Persistencia

- [ ] Recuperar un asset durable conocido (por ejemplo, uno de los
      utilizados en las mediciones de Etapa 10) y confirmar que
      responde
- [ ] El checksum/tamaño recuperado coincide con el registrado en
      `CLOUD_STAGE_10_REAL_COST_PERFORMANCE_REPORT.md`
- [ ] Un `reprocess` sin re-subir el archivo original funciona
      (confirma que el mecanismo de reprocess-sin-reupload sigue
      intacto tras el restore)

---

## 7. Cost safety

- **No restaurar Cloud SQL ni Cloud Run hasta que el entorno se vaya
  a usar realmente.** Reactivar por conveniencia o "para probar"
  genera costo sin necesidad.
- Cloud SQL vuelve a generar costo fijo de cómputo apenas
  `activationPolicy=ALWAYS` se aplica — no solo cuando alguien lo usa.
- Cloud Run con `min-instances=0` únicamente cobra cuando recibe
  tráfico real; no es necesario "pausarlo" activamente más allá de lo
  que ya está (Sección 1.1), y no genera costo de cómputo por sí solo
  mientras nadie lo invoque.
- Evitar reactivar el Backend legacy (`pfi-backend-dev`) salvo
  necesidad explícita: el Frontend no le envía tráfico, así que
  levantarlo no aporta nada al flujo normal y sí puede generar costo
  de cómputo accidental si algo lo invoca sin querer.

---

## 8. No hacer

Ninguna acción de esta sección fue ni debe ser ejecutada como parte de
este documento:

- No borrar recursos.
- No cambiar IAM.
- No cambiar secrets.
- No borrar imágenes.
- No modificar traffic.
- No modificar código.
- No hacer redeploy.
- No reactivar nada ahora.
- No hacer merge a `main`.

---

## Historial de este documento

- 2026-09-23: versión inicial (borrador local, sin commitear) con el
  baseline pre-pausa de los 4 servicios Cloud Run y los comandos de
  restore de Cloud SQL/Cloud Run.
- 2026-09-23 (esta revisión): reescrito y ampliado a la estructura
  completa de snapshot/restore/checklist/cost-safety, con estado en
  vivo re-verificado por servicio (`gcloud ... describe`, read-only)
  antes de commitear.
