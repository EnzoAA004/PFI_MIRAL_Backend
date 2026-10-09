# Evidencia de migración Google Cloud V2

Fecha: 2026-10-09. Estado: migración V2 en curso. Rama: cloud/gcp-v2-migration.

Plan detallado: [GCP_V1_TO_V2_MIGRATION_PLAN.md](GCP_V1_TO_V2_MIGRATION_PLAN.md).

## Cuenta/proyectos

| Campo | Valor observado |
|---|---|
| Cuenta CLI | enzoandreaasplanatti@gmail.com |
| V1 | pfi-asplanatti-fabrello-v1 / 810538429027 / ACTIVE |
| V2 | pfi-asplanatti-fabrello-v2 / 1076511912623 / ACTIVE |
| Billing V2 | enabled |
| Región | us-central1 |

Revalidado al reanudar: identidad CLI `enzoandreaasplanatti@gmail.com`; proyecto global de gcloud sigue V1 por seguridad; V1 ACTIVE. V2 también ACTIVE y `cloudresourcemanager.googleapis.com` habilitada allí. No se habilitó ninguna API ni se modificó ningún recurso de V1.

## Baseline

| Componente | SHA | Digest V1 | Digest V2 | Estado |
|---|---|---|---|---|
| Backend | ba76fa928c53ece6022bde432ba03a4497e39551 | sha256:98565af622ab68e73f09ea0379453f8c53289d206c78ee989393628c53f4c427 | sha256:98565af622ab68e73f09ea0379453f8c53289d206c78ee989393628c53f4c427 | copia exacta verificada |
| AI | 8254f3ab5636b80673ad5371c72b007775cc432d | sha256:bf4b4ba371a0cdb5a501b2215cb92bb4a657e227fc7c18b670a0d70df4ebf664 | sha256:bf4b4ba371a0cdb5a501b2215cb92bb4a657e227fc7c18b670a0d70df4ebf664 | copia exacta; servicio V2 Ready |
| Frontend | 3d7995714c139191359c92228e045890219d4678 | sha256:f6eb2fc89d2c90c8c6a3d39c4f744818b1967ff908fa845a0c4cd096629ca589 | sha256:f6eb2fc89d2c90c8c6a3d39c4f744818b1967ff908fa845a0c4cd096629ca589 | copia exacta verificada |

## Auditoría de runtime

- Backend E2E V1 ready: pfi-backend-e2e-00010-l96; Frontend E2E: pfi-frontend-e2e-00004-qaq; AI E2E: pfi-ai-module-e2e-finalrestart.
- AI E2E IAM: run.invoker sólo para pfi-backend-e2e-runtime; no allUsers.
- SQL V1: pfi-postgres-dev, PostgreSQL 15, db-f1-micro, SSD 10 GB, zonal. DBs pfi, pfi_e2e, postgres; cuenta pfi_app. Backup automático deshabilitado.
- Secret Manager: sólo metadatos consultados; no se leyeron valores.
- Clasificación de filas/assets: pendiente; no hubo export ni copy.
- Qwen V1 GCS: prefijo completo, 15 objetos, 15,242,809,718 bytes; cuatro shards y manifest.

## Target V2

En V2 se habilitaron run.googleapis.com, artifactregistry.googleapis.com, sqladmin.googleapis.com, secretmanager.googleapis.com, iam.googleapis.com, cloudresourcemanager.googleapis.com, compute.googleapis.com, logging.googleapis.com y billingbudgets.googleapis.com. Se creó Artifact Registry `us-central1/pfi-containers`, se copiaron los tres digests certificados y se crearon los buckets/SA documentados en el plan. AI V2 se desplegó privado y quedó Ready.

Cloud Billing Budget API responde desde V2, pero el listado en la billing account falla por permisos. Faltan billing.budgets.list y billing.budgets.create sobre esa billing account; el rol predefinido mínimo para administrar budgets es roles/billing.costsManager. No se cambió IAM de billing.

| Recurso | ID destino previsto | Digest/revision/URL | Estado |
|---|---|---|---|
| Artifact Registry | us-central1/pfi-containers | tres digests certificados preservados | creado; verificación PASS |
| Cloud SQL | pfi-postgres-v2 / pfi_e2e | PostgreSQL15 / db-f1-micro / 10GB SSD / zonal | RUNNABLE; DB vacía; runtime mínimo `pfi_app` listo; datos fuente no copiados |
| AI privado | pfi-ai-module-e2e | `pfi-ai-module-e2e-00001-4dw`; `https://pfi-ai-module-e2e-nuvqxkqhdq-uc.a.run.app` | Ready; IAM privada; default min0/max1; invoker sólo Backend SA V2 |
| Backend | pfi-backend-e2e | — | pendiente SQL/secrets/CORS target |
| Frontend | pfi-frontend-e2e | — | pendiente Backend V2 |
| Buckets | mapping en plan | runtime/assets/artifacts/Qwen V2 | creados; sólo checkpoint AI transferido; Qwen vacío hasta E2E |
| Secret Manager | pfi-e2e-database-url, pfi-e2e-jwt-secret | valores omitidos | DB URL runtime y JWT versionadas; secreto admin temporal borrado |

## Gate de bootstrap PostgreSQL runtime

`POSTGRES_RUNTIME_BOOTSTRAP=PASS` el 2026-10-09 en el proyecto V2. Usuario `pfi_app`, con login, miembro de `pfi_runtime` (NOLOGIN). Se verificó explícitamente:

| Atributo runtime | Resultado |
|---|---|
| cloudsqlsuperuser | false |
| SUPERUSER | false |
| CREATEDB | false |
| CREATEROLE | false |
| REPLICATION | false |
| `pfi_e2e` CONNECT | concedido vía `pfi_runtime` |
| schema `pfi_app` USAGE, CREATE | concedidos vía `pfi_runtime` |
| Migraciones/CRUD/secuencias | `pfi_app` crea y posee sus objetos; tiene permisos completos sobre ellos |

La base comienza vacía. No se importaron usuarios, estudios ni filas de V1. Para bootstrap se generó la contraseña administrativa sólo en memoria, se guardó en un secreto regional temporal de Secret Manager V2 y se usó mediante Cloud SQL Data API. Se revocó la membership temporal `pfi_runtime` del usuario `postgres`; la contraseña temporal de `postgres` fue rotada al finalizar. Se borró el secreto admin temporal y se restauró `dataApiAccess=DISALLOW_DATA_API`. Los valores secretos no se imprimieron ni se agregaron al repositorio. Procedimiento reproducible: [`scripts/gcp-v2/bootstrap-sql-runtime.ps1`](../scripts/gcp-v2/bootstrap-sql-runtime.ps1).

`pfi-e2e-database-url` recibió una nueva versión que referencia `pfi_app`; su valor permanece sólo en Secret Manager. El Backend SA ya cuenta con `roles/cloudsql.client` y acceso limitado a los dos secretos E2E.

## Tests y cierre

Frontend HTTP 200: PENDING
Backend health: PENDING (siguiente paso)
Backend→AI ID token: PENDING
PostgreSQL conectado: PENDING
Browser E2E: PENDING
Persistencia/restart: PENDING
FULL V2 BASE CLOUD E2E: NOT RUN
V1 MODIFIED: NO
QWEN GPU DEPLOYED: NO
CI/CD V2 ACTIVE: NO

Checkpoint de AI copiado directamente GCS→GCS: 22,182,638 bytes; MD5 y CRC32C coincidentes. AI V2 Ready usa el digest exacto del Final Freeze, 4 CPU/2 GiB/concurrency 1, startup TCP :8000, min0/max1, checkpoint read-only bajo `/mnt/pfi-artifacts` y storage runtime separado. La health check y la invocación privada Backend→AI siguen pendientes del Backend V2.

Buckets V2 creados con UBLA y Public Access Prevention: `pfi-ai-runtime-e2e-1076511912623`, `pfi-backend-e2e-assets-1076511912623`, `pfi-rm-lumbar-artifacts-2026-v2-1076511912623`, `pfi-qwen-models-1076511912623`. IAM de servicio se mantiene limitado por recurso. No se copiaron filas SQL ni objetos de estudio.

Budget alertas 50/75/85/95/100% sobre USD 300: BLOQUEADO por permisos `billing.budgets.list` y `billing.budgets.create` sobre billing account V2; no se cambió billing ni se otorgó rol. Requiere `roles/billing.costsManager` en esa billing account o creación manual de las alertas por un billing administrator.

Cloud SQL `pfi-postgres-v2` quedó RUNNABLE el 2026-10-09: `POSTGRES_15`, `db-f1-micro`, Enterprise, us-central1, 10 GB SSD, sin auto-resize, ZONAL, backups off, IPv4 habilitado y sin authorized networks, deletion protection on. Base vacía `pfi_e2e` creada; no hay filas importadas. Estimación documentada para esta configuración: ~USD 9.37/mes.

El bootstrap autorizado ya completó `pfi_runtime` (NOLOGIN) y `pfi_app` con permisos mínimos en schema dedicado `pfi_app`. Se comprobaron todos los flags administrativos en false, se borró el secreto administrativo temporal y se deshabilitó Data API. Backend/Frontend V2 todavía no se desplegaron; la siguiente etapa comienza desplegando Backend V2 con el digest certificado y la URL de DB runtime de Secret Manager.
