# Plan de migración Google Cloud V1 → V2

Fecha de actualización: 2026-10-09. Estado: migración V2 en curso, rama cloud/gcp-v2-migration. El inventario fuente sigue siendo read-only.

## Reglas

- V1 (pfi-asplanatti-fabrello-v1) es source of truth y permanece intacto.
- Crear recursos exclusivamente en V2 (pfi-asplanatti-fabrello-v2).
- No copiar Cloud SQL ni objetos de estudio hasta confirmar que los datos son demo/synthetic/de-identified.
- No modificar billing, desplegar Qwen GPU ni activar CI/CD en esta fase.
- No reconstruir imágenes. Si el digest copiado cambia, detener ese componente antes del deploy.

## Proyectos

| Campo | V1 | V2 |
|---|---|---|
| Project ID | pfi-asplanatti-fabrello-v1 | pfi-asplanatti-fabrello-v2 |
| Project number | 810538429027 | 1076511912623 |
| Estado | ACTIVE | ACTIVE |
| Billing | habilitado | habilitado |
| Cuenta operativa CLI | enzoandreaasplanatti@gmail.com | enzoandreaasplanatti@gmail.com |
| Región objetivo | us-central1 | us-central1 |

Verificación reanudada: cuenta CLI `enzoandreaasplanatti@gmail.com`; proyecto CLI continúa `pfi-asplanatti-fabrello-v1`; V1 ACTIVE (810538429027), V2 ACTIVE (1076511912623). Cloud Resource Manager está habilitada y responde en V2. No se cambió el proyecto global configurado; cada operación V2 usa `--project` explícito. La consulta de API filtrada en V1 no devolvió resultado, y no se habilitó ni modificó ninguna API de V1.

## Baseline certificado y registro

| Componente | SHA | Digest exacto presente en V1 |
|---|---|---|
| Backend | ba76fa928c53ece6022bde432ba03a4497e39551 | sha256:98565af622ab68e73f09ea0379453f8c53289d206c78ee989393628c53f4c427 |
| AI | 8254f3ab5636b80673ad5371c72b007775cc432d | sha256:bf4b4ba371a0cdb5a501b2215cb92bb4a657e227fc7c18b670a0d70df4ebf664 |
| Frontend | 3d7995714c139191359c92228e045890219d4678 | sha256:f6eb2fc89d2c90c8c6a3d39c4f744818b1967ff908fa845a0c4cd096629ca589 |

Repositorio fuente: us-central1-docker.pkg.dev/pfi-asplanatti-fabrello-v1/pfi-containers (Docker, Standard). Hay digests históricos/experimentales; sólo copiar los tres anteriores usando gcrane cp y comparar digests.

## Inventario de V1

### APIs

Habilitadas: analyticshub, apphub, appoptimize, apptopology, artifactregistry, bigquery, bigqueryconnection, bigquerydatapolicy, bigquerydatatransfer, bigquerymigration, bigqueryreservation, bigquerystorage, cloudaicompanion, cloudapis, cloudasset, cloudbuild, cloudresourcemanager, cloudtrace, compute, containerregistry, dataform, datastore, designcenter, geminicloudassist, iam, iamcredentials, logging, monitoring, oslogin, pubsub, recommender, run, secretmanager, servicemanagement, serviceusage, sql-component, sqladmin, storage-api, storage-component, storage, sts y telemetry; sufijo de todas: .googleapis.com.

V2 estaba ACTIVE con billing habilitado. Se habilitaron sólo estas APIs en V2: run.googleapis.com, artifactregistry.googleapis.com, sqladmin.googleapis.com, secretmanager.googleapis.com, iam.googleapis.com, cloudresourcemanager.googleapis.com, compute.googleapis.com, logging.googleapis.com y billingbudgets.googleapis.com. Storage, Service Usage y Monitoring ya estaban habilitadas. Cloud Build, STS/WIF y APIs de GPU se difieren. La cuenta puede describir V2 y confirmar Cloud Resource Manager habilitada. El servicio AI V2 se desplegó (ver evidencia). Cloud Build, STS/WIF y GPU se difieren.

### Cloud Run

Ubicación us-central1. Entrada de red all se registra separada de la autorización IAM.

| Servicio | Revisión lista / tráfico | Imagen | CPU / RAM / concurrencia | Max observado | Configuración relevante |
|---|---|---|---|---|---|
| pfi-ai-module-e2e | pfi-ai-module-e2e-finalrestart / 100% | digest AI certificado | 4 / 2 GiB / 1 | 1 | IAM invoker sólo al Backend E2E; startup TCP 8000/240s. Monta pfi-rm-lumbar-artifacts-2026-ef read-only en /mnt/pfi-artifacts y pfi-ai-runtime-e2e-810538429027 en /mnt/pfi-runtime. |
| pfi-backend-e2e | pfi-backend-e2e-00010-l96 / 100% | digest Backend certificado | 1 / 512 MiB / 80 | 20 | demo/auth habilitados; CORS incluye Frontend E2E; AI ID token; startup TCP 8080/240s. |
| pfi-frontend-e2e | pfi-frontend-e2e-00004-qaq / 100% | digest Frontend certificado | 1 / 256 MiB / 80 | 20 | VITE_USE_MOCK=false; apunta al Backend E2E. |
| pfi-ai-module | pfi-ai-module-00009-7gs / 100% | imagen CPU previa, no baseline E2E | 4 / 2 GiB / 1 | 1 | invoker Backend runtime; startup TCP 8000/240s. |
| pfi-backend-dev | stage4h-warm; el tráfico observado apuntaba a 00002-jwd | histórica | 1 / 512 MiB / 80 | 2 | allUsers invoker; demo auth off. No replicar para el baseline. |
| pfi-backend-v2-dev (vive dentro de V1) | 00004-d8r / 100% | histórica 7074b123 | 1 / 512 MiB / 80 | 2 | allUsers invoker; no confundir con proyecto V2. |
| pfi-frontend-dev | 00003-mrw / 100% | histórica 3d799571 | 1 / 256 MiB / 80 | 2 | allUsers invoker; mock off. |

Se encontraron 52 revisiones de Cloud Run en V1. Las revisiones E2E anteriores son el baseline de migración. min no se configuró explícitamente en todos los servicios. Propuesta V2: min=0, max=1 durante E2E para limitar costo; validar suficiencia para el smoke serial.

### Cloud SQL

- Instancia pfi-postgres-dev: PostgreSQL 15, us-central1, db-f1-micro compartido, zonal, SSD 10 GB, RUNNABLE.
- Backup automático deshabilitado; retención configurada a 7 si se habilita.
- IPv4 público enabled, require SSL false. No reproducir esa postura en V2 sin elegir red/SSL.
- Bases pfi, pfi_e2e y postgres; usuario de app pfi_app.
- No se consultaron valores de credenciales. El conteo/clasificación de filas no quedó validado.
- BLOQUEO: no exportar/importar DB hasta confirmar datos no identificables. El destino previsto es una nueva instancia independiente pfi-postgres-v2.

### Cloud Storage

| Bucket V1 | Ubicación | Bytes agregados | Evaluación |
|---|---|---:|---|
| pfi-ai-runtime-e2e-810538429027 | US-CENTRAL1 | 54,041,304 | revisar contenido/necesidad |
| pfi-asplanatti-fabrello-v1_cloudbuild | US | 76,572,685 | build artifacts; no copiar |
| pfi-backend-durable-dev | US-CENTRAL1 | pendiente | listado completo no terminó; clasificación pendiente |
| pfi-backend-e2e-assets-810538429027 | US-CENTRAL1 | 48,371,252 | assets E2E; clasificación por objeto pendiente |
| pfi-qwen-models-810538429027 | US-CENTRAL1 | 15,242,809,718 | snapshot descrito abajo |
| pfi-rm-lumbar-artifacts-2026-ef | US-CENTRAL1 | pendiente | listado completo no terminó; revisar contenido |

Las descripciones no mostraron lifecycle/retention. En buckets relevantes aparecen roles específicos de SA y bindings heredados Owner/Editor/Viewer del proyecto. V2 usará buckets nuevos, uniform bucket-level access y permisos mínimos.

Qwen sí está completo en V1: prefijo conversational/qwen2.5-7b-instruct/a09a35458c702b33eeacc393d103063234e8bc28/; 15 objetos, 15,242,809,718 bytes (14.20 GiB), cuatro shards y MANIFEST.sha256.json. Copia directa GCS→GCS, sin Hugging Face, después del E2E base. Este hallazgo corrige el handoff anterior que lo marcaba EMPTY.

### Secret Manager

Nombres funcionales: pfi-dev-database-url, pfi-dev-jwt-secret, pfi-e2e-database-url y pfi-e2e-jwt-secret. Sólo metadatos/versiones consultados; jamás se leyeron o imprimieron valores. Las URLs de DB deben regenerarse para V2; copiar JWT key por API/CLI en memoria sólo si sigue requerido, sin archivo/log/salida.

### Service accounts, IAM, VPC y CI

- SAs V1: pfi-training-vm, pfi-ai-runtime, Compute default, pfi-backend-runtime, pfi-qwen-model-uploader, pfi-frontend-runtime-dev, pfi-backend-qwen-runtime, pfi-backend-e2e-runtime, pfi-ai-e2e-runtime, pfi-llm-runtime y pfi-github-artifact-pusher.
- AI E2E concede roles/run.invoker únicamente al Backend E2E runtime; no allUsers. Backend/Frontend E2E tienen allUsers invoker para demo/navegador.
- Hay bindings de Cloud SQL Client/Storage para runtimes. No copiar Owner/Editor a SAs; usar grants por recurso.
- WIF V1 tiene pool github-pool y provider github-provider activos. La condición de repo y bindings federados requieren auditoría adicional; CI/CD V2 diferido.
- Redes V1 default y pfi-qwen-vpc; subnet pfi-qwen-us-central1 en us-central1 (10.250.0.0/26, Private Google Access true). Crear equivalente sólo si el flujo V2 lo necesita.
- Cloud Billing Budget API continúa deshabilitada en V1 y no se habilitó allí. Está habilitada sólo en V2. La cuenta operativa no pudo listar presupuestos de la billing account V2: faltan billing.budgets.list y billing.budgets.create en esa billing account. El rol predefinido mínimo que incluye administración de budgets es roles/billing.costsManager. No se otorgó ningún rol ni se modificó billing. No se pudo verificar si ya existe un budget V2.

## Tabla de migración

| V1 resource | V1 identifier | V2 resource | Migration method | Needs data copy | Needs new IAM | Expected downtime | Estimated cost | Status |
|---|---|---|---|---|---|---|---|---|
| Artifact Registry | us-central1/pfi-containers | us-central1/pfi-containers | Docker repo + gcrane cp, sólo los tres digests certificados | sí, imagen | AR Reader V1 / Writer V2 | ninguno en V1 | depende de bytes; transferencia mismo region | CREADO; tres digests iguales, ver evidencia |
| AI E2E | pfi-ai-module-e2e | pfi-ai-module-e2e | deploy por digest, privado, min0/max1, probe equivalente | checkpoint copiado íntegro | AI runtime V2; Backend V2 run.invoker | ninguno en V1 | Cloud Run por uso, min0 | Ready y restart verificado, revisión `pfi-ai-module-e2e-00002-v99` |
| Backend E2E | pfi-backend-e2e | pfi-backend-e2e | deploy por digest, DB/GCS/secrets V2, CORS con origin Frontend V2 real | no para fixture sintética/de-identificada; V2 inicia vacía | SQL Client, Secret Accessor, GCS mínimo, invoker AI | ninguno en V1 | Cloud Run por uso, cap V2 1 | PASS; `pfi-backend-e2e-00003-qjz`; SQL/migraciones/AI/CORS/restart verificados |
| Frontend E2E | pfi-frontend-e2e | pfi-frontend-e2e | deploy por digest; BACKEND_URL V2, mock=false | no | SA frontend sin permisos cloud; invoker público sólo si E2E | ninguno en V1 | Cloud Run por uso, cap V2 1 | PASS; `pfi-frontend-e2e-00001-r4b`; HTTP 200; browser E2E sintético |
| Cloud SQL E2E | pfi-postgres-dev / pfi_e2e | pfi-postgres-v2 / pfi_e2e | instancia PG15/db-f1-micro/10GB; migraciones Backend sobre DB vacía con usuario mínimo | no; usar fixture sintética/de-identificada | bootstrap admin temporal ya limpiado; runtime SQL client | V1 ninguno; V2 durante import | ~USD 9.37/mes según revisión de costos del repo | instancia RUNNABLE; `POSTGRES_RUNTIME_BOOTSTRAP=PASS`; DB vacía, sin datos copiados |
| DB dev | pfi | no incluir salvo necesidad | separar de E2E; evaluar sólo luego | posible | sí | ninguno | según volumen | deferido |
| Secrets | pfi-e2e-* | nombres funcionales bajo V2 | transferencia directa API/CLI; DB URL nueva; nunca archivo/log | secreto | Secret Accessor sólo Backend | ninguno | uso pequeño | depende de SQL |
| Backend assets | pfi-backend-e2e-assets-810538429027 | pfi-backend-e2e-assets-1076511912623 | bucket regional nuevo y copia selectiva tras clasificación/hash | sí | roles por SA V2 | ninguno | 48,371,252 B más operaciones/red | bucket creado; copia BLOQUEADA clasificación |
| Backend durable | pfi-backend-durable-dev | pfi-backend-durable-v2 propuesto | inventariar y copiar sólo artefactos requeridos/clasificados | sí | roles SA por bucket | ninguno | bytes pendientes | auditoría incompleta |
| AI artifacts | pfi-rm-lumbar-artifacts-2026-ef | pfi-rm-lumbar-artifacts-2026-v2-1076511912623 | copia directa GCS→GCS del checkpoint requerido; tamaño/MD5/CRC32C verificados | checkpoint solamente | AI runtime read-only | ninguno | ~21 MiB | checkpoint copiado íntegro; resto no copiado |
| AI runtime E2E bucket | pfi-ai-runtime-e2e-810538429027 | pfi-ai-runtime-e2e-1076511912623 | bucket nuevo; runtime writes aislados | no copiados inputs | SA AI V2 | ninguno | bucket vacío inicialmente | bucket creado; no se copió contenido por clasificación pendiente |
| Qwen snapshot | pfi-qwen-models-810538429027/prefijo de revisión | pfi-qwen-models-1076511912623 | copia GCS→GCS; verificar manifest y 4 hashes | sí, weights 14.20 GiB | identidad de copia temporal; runtime luego | ninguno | aprox USD 0.28/mes en Standard regional antes de operaciones/red | post-E2E base |
| Loader/GPU Qwen | pfi-qwen-model-loader | ninguno en fase base | diferir | no | no | ninguno | cero nuevo en base | diferido |
| WIF/CI | github-pool/provider V1 | pool/provider V2 futuro | auditar y preparar gcp-v2 en paralelo | no | SAs mínimas V2 | ninguno | build/registry por uso | diferido; no tocar variables V1 |
| Budget alerts | estado V1 no verificable | V2 50/75/85/95/100% de USD 300 | sólo alertas, sin autoapagado | no | Billing Budgets account role | ninguno | alertas sin costo relevante | BLOQUEADO: faltan billing.budgets.list/create; mínimo roles/billing.costsManager en billing account V2 |

## Recursos V2 ya creados

- Artifact Registry `us-central1/pfi-containers`; los tres manifests copiados desde V1 conservan los digests exactos certificados.
- Buckets privados us-central1 con UBLA y Public Access Prevention: `pfi-ai-runtime-e2e-1076511912623`, `pfi-backend-e2e-assets-1076511912623`, `pfi-rm-lumbar-artifacts-2026-v2-1076511912623`, `pfi-qwen-models-1076511912623` (Qwen permanece vacío hasta después del E2E base).
- Service accounts runtime: Backend E2E, AI E2E y Frontend V2. IAM de buckets mínimo; AI tiene sólo lectura de artefactos y objeto usuario en su bucket runtime. No se copiaron roles Owner/Editor.
- Checkpoint AI copiado por GCS→GCS: 22,182,638 bytes; MD5 y CRC32C coinciden con V1.
- AI Cloud Run V2 Ready: URL canónica `https://pfi-ai-module-e2e-nuvqxkqhdq-uc.a.run.app` (también `https://pfi-ai-module-e2e-1076511912623.us-central1.run.app`), revisión `pfi-ai-module-e2e-00001-4dw`, 4 CPU/2 GiB, concurrencia 1, minScale no fijado (default 0), maxScale 1. El invoker IAM es privado; sólo Backend runtime V2 tiene `roles/run.invoker`.
- Cloud SQL V2 `pfi-postgres-v2` está RUNNABLE: PostgreSQL 15, `db-f1-micro`, 10 GB SSD, ZONAL, sin backup, IP pública sin authorized networks, deletion protection habilitada. Costo estimado del repo: ~USD 9.37/mes. DB vacía `pfi_e2e` creada; no se importaron datos.
- El bootstrap PostgreSQL runtime V2 quedó aprobado y ejecutado: el script [`scripts/gcp-v2/bootstrap-sql-runtime.ps1`](../scripts/gcp-v2/bootstrap-sql-runtime.ps1) creó/reutilizó `pfi_runtime` NOLOGIN y `pfi_app`, asignó CONNECT sobre `pfi_e2e`, USAGE/CREATE en schema `pfi_app`, y permisos completos a los objetos que el runtime crea. Se verificó `cloudsqlsuperuser=false`, `SUPERUSER=false`, `CREATEDB=false`, `CREATEROLE=false`, `REPLICATION=false`. El usuario `postgres` recibió membership temporal durante el bootstrap y fue revocado; su contraseña temporal fue rotada; el secreto admin temporal se borró y Data API volvió a DISALLOW. La URL JDBC runtime sólo está en `pfi-e2e-database-url` (Secret Manager V2). No se desplegó todavía Backend/Frontend.
- Secretos V2 `pfi-e2e-database-url` y `pfi-e2e-jwt-secret` creados en Secret Manager regional us-central1. Se generó y guardó una nueva clave JWT directamente como versión 1; no se expuso el valor. Backend SA sólo tiene `roles/secretmanager.secretAccessor` sobre esos secretos y `roles/cloudsql.client` en V2.
- No se exportaron/importaron datos. Backend y Frontend Cloud Run todavía no creados. E2E base aún no ejecutado.

## Costos indicativos

- Cloud SQL db-f1-micro: USD 0.0105/h × 730 h ≈ USD 7.67/mes sólo cómputo; añadir SSD 10 GB, backups y red.
- Qwen 14.20 GiB en Standard us-central1: tarifa publicada USD 0.000027397/GiB-h, ≈ USD 0.28/mes.
- Cloud Run min0: cobro por uso. La propuesta V2 cap=1 limita costo de instancia concurrente; confirmar que sirve al smoke E2E.
- Artifact Registry: 0.5 GiB-mes por billing account free, luego USD 0.000136986/GiB-h; tamaño real pendiente.
- No asumir que USD 300 promocionales equivale a hard cap. Budget alerta no detiene consumo.

Tarifas consultadas 2026-10-09: [Cloud SQL](https://cloud.google.com/sql/pricing), [Cloud Storage](https://cloud.google.com/storage/pricing), [Cloud Run](https://cloud.google.com/run/pricing), [Artifact Registry](https://cloud.google.com/artifact-registry/pricing). Método oficial para copiar imágenes: [gcrane copy](https://cloud.google.com/artifact-registry/docs/docker/copy-images).

## Puertas de control

1. Clasificar DB pfi_e2e y cada bucket que contenga study inputs/assets. Hasta entonces no exportar/cargar datos.
2. Completar tamaño/conteo de pfi-backend-durable-dev y pfi-rm-lumbar-artifacts-2026-ef sin exponer nombres de objetos.
3. Confirmar APIs/quotas/roles V2, nombres globales de buckets libres y costo de imagen/copia. Para presupuesto, el administrador de billing debe otorgar roles/billing.costsManager a la cuenta en la billing account V2 o crear las alertas 50/75/85/95/100% de USD 300 desde Console.
4. Crear recursos mínimos V2; verificar digest tras copiar y antes de deploy.
5. Desplegar AI privada → Backend → Frontend; ejecutar E2E de navegador, persistencia/restart.
6. Sólo cerrar con FULL V2 BASE CLOUD E2E=PASS. Qwen GPU y CI/CD continúan diferidos.

## Estado

V1 MODIFIED=NO
V2 RESOURCES CREATED=PARTIAL (AR, buckets, SAs, AI Cloud Run, Cloud SQL, Secret Manager)
CERTIFIED DIGESTS VERIFIED IN V1=YES
CERTIFIED DIGESTS COPIED EXACTLY TO V2=YES
QWEN SNAPSHOT V1=15 OBJECTS / 15,242,809,718 BYTES
QWEN GPU V2=NO
CI/CD V2=NO
SQL/STUDY DATA CLASSIFICATION=NOT VERIFIED; source rows/assets NOT copied
POSTGRES_RUNTIME_BOOTSTRAP=PASS
BACKEND V2=PASS (certified digest; startup/migrations/SQL/AI/CORS/restart verified)
FRONTEND V2=PASS (certified digest; real URL; browser E2E verified)
FULL V2 BASE CLOUD E2E=PASS
