# Handoff Qwen — continuar desde otra computadora

Fecha del handoff: 2026-10-07

## Repositorios Git

Todos usan `origin` con los remotos oficiales de `EnzoAA004`.

| Repo | Rama para continuar | HEAD/corte conocido | Remote |
|---|---|---|---|
| Backend | `experiment/qwen-cloudrun-e2e` | código Qwen auditado en `5b64ea2e75e1540bd01567a75248397bc395a7a3`; el tip remoto incluye después los commits de evidencia/handoff | `https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Backend.git` |
| AI Module | `gcp-deploy-candidate-20261007` | Final Freeze `8254f3ab5636b80673ad5371c72b007775cc432d` | `https://github.com/EnzoAA004/PFI_MVPTest_Enzo_AImodule.git` |
| Frontend | `main` | Final Freeze `3d7995714c139191359c92228e045890219d4678` | `https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Frontend.git` |

La rama Backend `gcp-deploy-candidate-20261007` preserva la base Final Freeze `ba76fa928c53ece6022bde432ba03a4497e39551`; la rama experimental Qwen parte de esa base. La rama Qwen conserva los cambios experimentales aislados de `main` y de producción.

## SHA de Final Freeze

```text
Backend:  ba76fa928c53ece6022bde432ba03a4497e39551
AI:       8254f3ab5636b80673ad5371c72b007775cc432d
Frontend: 3d7995714c139191359c92228e045890219d4678
```

## Estado GCP/Qwen confirmado

```text
Cloud Resource Manager API: ENABLED
CLI account al último chequeo: enzoandreaasplanatti@gmail.com
Proyecto: pfi-asplanatti-fabrello-v1
Región: us-central1
Qwen Cloud Run service: NOT CREATED
Qwen GPU service: NOT CREATED
Cloud Run loader job: pfi-qwen-model-loader (existe, sin ejecución activa)
Primera ejecución: FAILED, Hugging Face devolvió HTTP 429 en .gitattributes
Segunda ejecución: sin progreso de descarga; cancelada manualmente para limitar consumo
Ejecuciones activas: 0
Bucket: gs://pfi-qwen-models-810538429027 (US-CENTRAL1)
Prefijo Qwen: conversational/qwen2.5-7b-instruct/a09a35458c702b33eeacc393d103063234e8bc28/
Contenido del prefijo: EMPTY; no existen aún pesos ni manifest SHA-256
Final Freeze/producción: sin cambios en esta fase
```

La revisión read-only de Service Usage encontró la métrica `run.googleapis.com/nvidia_l4_gpu_allocation_no_zonal_redundancy` y la dimensión `us-central1`, pero no devolvió valor `effectiveLimit` ni `defaultLimit`. La cuota efectiva L4, por lo tanto, no quedó verificada numéricamente. Cloud Run documenta una posible concesión inicial automática de 3 L4 sin redundancia al primer despliegue, sujeta a capacidad; no se asumió que esa concesión ya esté disponible.

### Configuración GPU prevista, todavía no creada

- Servicio: `pfi-llm-qwen-e2e`.
- Cloud Run L4: una GPU `nvidia-l4`, sin redundancia zonal, `us-central1`.
- CPU/memoria: 8 vCPU / 32 GiB; instancia con billing por instancia.
- Escala: `min-instances=0`, `max-instances=1`, concurrencia 1.
- Precio de lista estimado mientras una instancia está activa: aproximadamente US$1,42/hora para GPU + CPU + memoria; sin instancias al escalar a cero no hay cargo de instancia. No incluye costos auxiliares.
- Snapshot: `Qwen/Qwen2.5-7B-Instruct`, revision `a09a35458c702b33eeacc393d103063234e8bc28`, licencia Apache-2.0.
- vLLM: versión objetivo `v0.31.0`; falta confirmar y fijar el digest OCI antes de crear el servicio GPU.

## Bloqueos y próximo trabajo

1. Resolver el rate limit de Hugging Face antes de reejecutar la carga. El job actualizado implementa reintentos HTTP 429/5xx y validación idempotente. Si el 429 persiste, usar una credencial de solo lectura mediante Secret Manager; no pegar tokens en el repositorio, este documento ni el chat. No ejecutar repetidamente el job durante el rate limit: usa 8 vCPU y 32 GiB.
2. Obtener una cuota L4 efectiva verificable para `us-central1` o confirmar en la consola la asignación inicial. No crear el servicio GPU mientras siga sin verificación.
3. Resolver el digest exacto de `vllm/vllm-openai:v0.31.0` y conservarlo en la configuración.
4. Cuando esos puntos estén claros, continuar con la carga de snapshot y después los servicios paralelos Qwen. Mantener `min=0` / `max=1`; no modificar servicios certificados ni producción.

Servicios Final Freeze que permanecen intactos:

- Backend: `pfi-backend-e2e-finalverify`, imagen `sha256:98565af622ab68e73f09ea0379453f8c53289d206c78ee989393628c53f4c427`.
- AI: `pfi-ai-module-e2e-finalrestart`, imagen `sha256:bf4b4ba371a0cdb5a501b2215cb92bb4a657e227fc7c18b670a0d70df4ebf664`.
- Frontend: `pfi-frontend-e2e-00004-qaq`, imagen `sha256:f6eb2fc89d2c90c8c6a3d39c4f744818b1967ff908fa845a0c4cd096629ca589`.

## Clonar en la computadora nueva

Autenticarse con los proveedores de Git/GCP en esa computadora; no copiar ADC, credenciales locales ni claves de service account desde esta máquina.

```powershell
git clone https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Backend.git PFI_MVPTest_Enzo_Backend
Push-Location PFI_MVPTest_Enzo_Backend
git fetch origin
git switch --track origin/experiment/qwen-cloudrun-e2e
git status --short --branch
git rev-parse HEAD
Pop-Location
```

Para revisar los baselines AI y Frontend:

```powershell
git clone https://github.com/EnzoAA004/PFI_MVPTest_Enzo_AImodule.git PFI_MVPTest_Enzo_AImodule
Push-Location PFI_MVPTest_Enzo_AImodule
git fetch origin
git switch --track origin/gcp-deploy-candidate-20261007
git rev-parse HEAD
Pop-Location

git clone https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Frontend.git PFI_MVPTest_Enzo_Frontend
Push-Location PFI_MVPTest_Enzo_Frontend
git switch main
git rev-parse HEAD
Pop-Location
```

Para reautenticar GCP en la nueva máquina, usar login interactivo y fijar el mismo proyecto:

```powershell
gcloud auth login
gcloud config set project pfi-asplanatti-fabrello-v1
gcloud config get-value account
gcloud config get-value project
```

No se almacenan tokens, passwords, valores de Secret Manager, claves privadas, credenciales locales ni datos de billing en este handoff.

