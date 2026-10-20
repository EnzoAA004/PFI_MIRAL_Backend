# MIRA·L — Documento de entrega para elaborar los diagramas de la tesis

Documento autocontenido: describe el estado actual de la versión, no su historia. Los nombres de clases, archivos, endpoints y tablas se verificaron en el código de estas ramas (no se reconstruyeron de memoria). No contiene secretos ni datos personales.

---

## A. Versión de referencia

**Fecha del relevamiento:** 2026-10-19 (actualizado el 2026-10-20 con el cambio de interacción «primera lectura sin IA», C.5.1).

| Repositorio | Rama | SHA completo del código relevado | Enlace permanente |
|---|---|---|---|
| Frontend (`PFI_MVPTest_Enzo_Frontend`) | `agent/study-upload-review-20261006` | `0c915acd66ab77ac4ae0ea8fc62a8345750d00a6` | https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Frontend/commit/0c915acd66ab77ac4ae0ea8fc62a8345750d00a6 |
| Backend (`PFI_MVPTest_Enzo_Backend`) | `agent/patient-clinical-context-20261007` | `7d899cad8691e38963211df846a3a104f86e0477` | https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Backend/commit/7d899cad8691e38963211df846a3a104f86e0477 |
| Módulo IA (`PFI_MVPTest_Enzo_AImodule`) | `agent/sequence-declaration-20261014` | `a4dcdeefdb42e40ec4bf5c1735493839e6953660` | https://github.com/EnzoAA004/PFI_MVPTest_Enzo_AImodule/commit/a4dcdeefdb42e40ec4bf5c1735493839e6953660 |

- **Estado del respaldo remoto:** las tres ramas están publicadas en `origin`; el SHA local coincide con el remoto (verificado con `git ls-remote`, no solo con el upstream).
- **Commit documental:** este archivo se agrega en un commit posterior del Backend. El SHA de **código** relevado del Backend es el de la tabla (`7d899ca…`); el commit que añade este documento es distinto y se informa aparte en el mensaje de entrega. Para construir los diagramas hay que leer el código **en los SHA de la tabla** (el commit documental no cambia código).
- **Fuera del respaldo:** en el Frontend, la carpeta `prereport-e2e-out/` (salidas locales de pruebas, sin versionar a propósito). Nada más está pendiente de publicar. Las capturas de pantalla y los informes de verificación viven fuera de los repositorios.
- **Integración con `main`:** estas ramas **todavía no están integradas con `main`**. No se hizo merge ni rebase.
  - **Frontend:** `main` (`3d79957`) ya está contenido en la rama (la rama está 39 commits por delante y 0 por detrás).
  - **Backend:** la rama está 18 commits por delante y **38 por detrás** de `main` (`233f8d1`). Lo que `main` tiene y la rama no: trabajo de despliegue en **Google Cloud** (migración a GCP/Cloud Run, conector Cloud SQL y pool de conexiones, almacenamiento durable de objetos y reprocesado de DICOM, autenticación privada hacia el LLM autoalojado en Cloud Run, liberación del advisory lock en conexiones agrupadas) y su documentación/evidencia.
  - **Módulo IA:** la rama está 3 commits por delante y **9 por detrás** de `main` (`a939126`). Lo que `main` tiene y la rama no: persistencia y rehidratación durable de assets de las corridas sobre GCS, correcciones asociadas y exclusión de datos locales en las subidas a gcloud.
  - **Consecuencia para los diagramas:** el **despliegue Cloud no está en estas ramas**. Cualquier elemento Cloud (Cloud Run, Cloud SQL, GCS) debe dibujarse, si se dibuja, como *trabajo de `main`* y no como parte de esta versión.

---

## B. Arquitectura realmente implementada (entorno local probado)

### B.1 Componentes
| Componente | Tecnología | Responsabilidad | Se comunica con |
|---|---|---|---|
| **Frontend** | React 19 + Vite + TypeScript, servido como estáticos por un contenedor web (puerto interno 80) | Interfaz profesional: lista de estudios, pacientes, nuevo estudio, visor, revisión, comparación, asistente y preinforme. La configuración (`env.js`) toma la URL pública del Backend | **Navegador → Backend** directamente (HTTP + JWT). No habla con el módulo IA ni con el LLM |
| **Backend** | Java 21, Spring Boot 3.3.5 | API única: autenticación, pacientes/estudios/contexto, orquestación hacia IA, persistencia, revisión, comparación, asistente, preinforme | PostgreSQL (JDBC), módulo IA (HTTP), proveedor LLM (HTTP) |
| **Módulo IA** | Python, FastAPI (`ai_service/pfi_ai_service/api.py`) | Recepción y registro de imágenes (DICOM/ZIP), segmentación, mediciones, clasificación degenerativa (P10.7), servido de assets | Solo con el Backend. Pesos de modelos montados desde el anfitrión (fuera de Git) |
| **PostgreSQL 16** | `postgres:16-alpine` | Estado persistente del Backend (tablas `domain_*` y `doctor_accounts`) | Backend |
| **Almacenamiento de archivos/assets** | (a) IA: volúmenes `ai-uploads` (entradas) y `ai-outputs` (salidas de corridas); (b) Backend: copia durable de assets de cada corrida en PostgreSQL (`domain_run_asset_payloads`) | Imágenes subidas, máscaras, overlays, cortes y DICOM SEG/SR | IA y Backend |
| **Proveedor LLM** | **LM Studio** en el anfitrión (API compatible con OpenAI), modelo `qwen3-4b-instruct-2507` | Solo para el asistente (interpretar la pregunta) | Backend → `POST /v1/chat/completions` (y `GET /v1/models` para comprobar disponibilidad) |

### B.2 Comunicación
- **Navegador → Backend:** todas las llamadas de la interfaz (`/api/...`), con `Authorization: Bearer` (JWT). CORS permitido por `PFI_CORS_ALLOWED_ORIGINS`.
- **Backend → IA:** HTTP interno. Clase `AiServiceClient` (+ `AiProductCheckpointClient`), URL en `PFI_AI_SERVICE_URL`. El Backend es el único cliente del módulo IA; reexpone al navegador los assets bajo `/api/ai/...`.
- **Backend → PostgreSQL:** JDBC (`DATABASE_URL`), migraciones SQL en `docs/migrations/V*.sql` aplicadas por `SqlMigrationRunner`.
- **Backend → LLM:** `SelfHostedLlmProvider` (implementa `LlmProvider`) — **única** clase que conoce el protocolo del proveedor.

### B.3 Despliegue local probado (stack `lumbarai-clean-20261006`)
Docker Compose: `compose.local.yml` (Backend) + `compose.restart.yml` (nombres de contenedor y red). Contenedores: `lumbarai-clean-frontend`, `lumbarai-clean-backend`, `lumbarai-clean-ai`, `lumbarai-clean-postgres`; red `lumbarai-clean-20261006`.

| Servicio | Puerto en el anfitrión | Puerto interno |
|---|---|---|
| Frontend | 8288 | 80 |
| Backend | 8280 | 8080 |
| Módulo IA | 8200 | 8000 |
| PostgreSQL | definido en `runtime.env` (`POSTGRES_PORT`) | 5432 |
| **LM Studio (fuera de Docker)** | 1234 en el anfitrión | — |

- **LM Studio fuera de Docker:** corre como aplicación del anfitrión. El Backend lo alcanza con `PFI_LLM_BASE_URL=http://host.docker.internal:1234`; `compose.local.yml` agrega `extra_hosts: host.docker.internal:host-gateway` para que el contenedor resuelva el anfitrión. Parámetros: `PFI_LLM_ENABLED`, `PFI_LLM_MODEL`, `PFI_LLM_TIMEOUT_SECONDS`, `PFI_LLM_TEMPERATURE` (0,1), `PFI_LLM_MAX_OUTPUT_TOKENS`, `PFI_LLM_HISTORY_WINDOW_TURNS`. Por defecto el asistente está **apagado** (`PFI_LLM_ENABLED=false`) y nunca usa una API externa.
- **Volúmenes:** `postgres-data`, `ai-outputs`, `ai-uploads`; los checkpoints de modelos se montan **solo lectura** desde el anfitrión (`/models/final`, `/models/subarticular`, `/models/disc-degenerative`).
- **Despliegue Cloud (solo documentado, en `main`):** Cloud Run + Cloud SQL + GCS. **No** forma parte de lo probado en esta versión; el entorno probado es únicamente el local descrito.

---

## C. Flujos que deben representar los diagramas

Convención: **Front** = Frontend; **Back** = Backend; **IA** = módulo IA; **PG** = PostgreSQL. Todas las rutas de Back pasan por `AuthFilter` (JWT) salvo las de `/api/auth` públicas.

### C.1 Autenticación y acceso profesional
- **Actor:** profesional (médico/revisor/admin). **Front:** `authClient.ts`, `App.tsx`, `components/AuthView.tsx`.
- **Llamadas (`AuthController`, `/api/auth`):** `POST /register` → `POST /verify-registration`; `POST /login` → `POST /verify-login` (segundo factor con código); `POST /refresh`; `POST /logout`; `GET /me`; `PATCH /settings` (doble verificación); `GET/PATCH /admin/professionals…` (aprobación y activación). `POST /demo-doctor` existe **solo para pruebas locales** (`PFI_AUTH_DEMO_ENABLED`); es el botón «Entrar con doctor demo» del ingreso.
- **Clases:** `AuthService`, `TokenService` (JWT), `AuthFilter`, `RoleAuthorizationService`, `PasswordHasher`, `AdminBootstrapService`, `PostgresAuthStoreService`.
- **Persistencia:** tabla `doctor_accounts` (correo, hash, roles, `verified`, `approved`, `two_factor_enabled`…).
- **Errores relevantes:** credenciales inválidas (401, «Correo o contraseña incorrectos»), código inválido/vencido (400), cuenta no aprobada (403), demasiados intentos (429), sesión vencida (401 en cualquier llamada → el Front cierra la sesión y avisa).

### C.2 Paciente, creación de estudio y contexto clínico por estudio
- **Pacientes (`PatientController`, `/api/patients`):** `POST` (crear), `GET ?query=` (buscar), `GET/{id}`, `PATCH/{id}`, `GET/{id}/studies`. Clases `PatientService`, `PostgresPatientRepository`. Tabla `domain_patients` (referencia de paciente única normalizada; nombre/apellido/fecha de nacimiento opcionales, migración `V20261007_023`). Front: `patientApi.ts`, `features/worklist/CreatePatientDialog.tsx`, `PatientSelector`.
- **Estudio y contexto:** el **contexto clínico pertenece al estudio**, no al paciente: `domain_studies` guarda `patient_id`, `age_at_study`, `age_provenance` (calculada o declarada), `clinical_reason`, `symptoms`, `symptom_duration`, `relevant_history`, `context_version` (migración `V20261007_024`). No se copia a otros estudios del mismo paciente ni cambia si se edita el paciente.
- **Alta:** el estudio se crea **al ejecutar el análisis**: `studyMetadata` (caso, paciente, fecha, prioridad, descripción, edad, contexto) viaja dentro de `POST /api/ai/multiplanar/run` y lo persiste `MultiplanarRunPersistenceService` (ver C.4).
- **Edición posterior:** `PUT /api/studies/{caseId}/metadata` (`StudyController`), `PUT /api/studies/{caseId}/patient` (`StudyPatientController`), `GET /api/studies/{caseId}/context` (`StudyContextController`). Historial por paciente: `GET /api/subjects/{ref}/history` (`SubjectHistoryController`, `PatientHistoryService`).
- **Errores:** referencia de paciente duplicada (`DuplicatePatientReferenceException`), ID de estudio inválido, edad fuera de rango.

### C.3 Carga ZIP DICOM y alternativa por plano
- **Front:** `features/worklist/NewAnalysisDrawer.tsx` (3 pasos), `multiplanarApi.ts` (`uploadStudyArchive`, `uploadAiInput`).
- **ZIP (flujo principal):** `POST /api/ai/studies` (multipart: `file`, `caseId`) → `AiBackendController` → `AiServiceClient` → IA `POST /inputs/study`. La IA separa las series por metadata DICOM y devuelve el catálogo (`seriesFound`: plano, ponderación detectada, cortes). Límite `PFI_AI_STUDY_UPLOAD_MAX_BYTES` (200 MB por defecto).
- **Alternativa por plano:** `POST /api/ai/inputs` (multipart: `file`, `caseId`, `plane`, `sequence`, `sequenceDescription`) → IA `POST /inputs`. La **secuencia declarada** por el usuario se registra como *declarada* (frente a *detectada* en el ZIP): `domain_input_resources` (migración `V20261014_025_input_sequence_provenance`), clase `InputSequence`.
- **Lectura de cortes subidos:** `GET /api/ai/series/{inputId}/slices/{index}` (Back) → IA `GET /inputs/{id}/slices/{index}`.
- **Persistencia:** IA guarda los archivos en `ai-uploads` (`input_registry.py`); Back registra el recurso en `domain_input_resources`.
- **Errores:** ZIP inválido/sin DICOM, archivo demasiado grande, falta serie sagital, serie sin secuencia (no se podrá comparar con otro estudio).

### C.4 Análisis inicial: segmentación, mediciones y clasificación degenerativa
Hay **tres etapas**, orquestadas **por el Frontend** (`features/worklist/productAnalysisFlow.ts`, `runP109ProductFlow`), no encadenadas por el Backend:
1. **Corrida multiplanar (segmentación + mediciones):** `POST /api/ai/multiplanar/run` (`AiMultiplanarController`) → `AiMultiplanarV2RequestMapper` → IA `POST /v2/multiplanar/run` (contrato v2, modo `real_baseline`; modelos `sagittal_spider` y, si hay axial, `axial_t2_alkafri` experimental). Respuesta adaptada por `AiMultiplanarV2ResponseAdapter` y validada (`MultiplanarV2RealBaselineValidator`). **Persistencia:** `MultiplanarRunPersistenceService` → `domain_studies` (estudio y contexto), `domain_study_runs` (corrida, estado de revisión, `metrics_snapshot` JSONB), `domain_run_artifacts`, `domain_run_asset_payloads` (assets), `domain_input_resources`. Aquí se obtienen **segmentación y mediciones morfométricas**.
2. **Segmentación de serie completa:** `POST /api/ai/v2/product/series-segmentation` (`AiProductCheckpointController`) → IA `POST /v2/series-segmentation/run`. El Backend valida el esquema `pfi.full-series-segmentation.v1` y **no la persiste**: devuelve el resultado al Frontend, que la usa en el paso 3.
3. **Clasificación degenerativa (P10.7):** `POST /api/ai/v2/product/disc-degenerative-findings` → IA `POST /v2/degenerative-findings/disc-multitask/from-series-segmentation`. `DiscDegenerativeFindingsPersistenceService` la guarda en `domain_study_runs.metrics_snapshot` bajo `discDegenerativeFindings` (sin tabla propia en este flujo). Se ejecuta **después** de la corrida, solo si esta dejó un sagital evaluable; puede reintentarse **sola** si falla («completado con degradación»).
- **Cuándo se ejecuta cada parte:** inferencia pesada solo en el análisis inicial («Analizar estudio»). Abrir un estudio, compararlo, preguntar al asistente o preparar el preinforme **no** ejecutan inferencia.
- **Errores:** IA caída/timeout (`PFI_AI_TIMEOUT_SECONDS`), violación del contrato (`AiProductContractViolationException`), estudio sin sagital evaluable (el Front no abre el visor y explica el motivo).

### C.5 Visor, recuperación de imágenes/assets y leyendas
- **Front:** `components/StudyReviewView.tsx`, `components/MriSliceViewer.tsx`, `features/reading/PlaneViewport.tsx`, `features/reading/canonicalMasks.ts`. Carga: `GET /api/studies/{caseId}` y `/runs` (`StudyController`).
- **Imágenes/assets (todos pasan por el Backend):** `GET /api/ai/assets/{runId}/{plane}/{assetName}` (imagen de entrada, overlay, máscaras por clase); `GET /api/ai/series/{inputId}/slices/{index}`; `GET /api/ai/v2/product/series-segmentation/{runId}/{plane}/slices/{index}/{assetName}`; `GET /api/ai/runs/{planeRunId}/{plane}/segmentation.dcm` y `measurements.sr.dcm` (DICOM SEG/SR). El Back sirve el asset desde la IA o desde la copia durable en PostgreSQL (`PostgresRunAssetContentStorage`, `domain_run_asset_payloads`).
- **Leyendas:** la leyenda de segmentación sale de las **máscaras del plano canónico de la corrida** (`canonicalRun.planes[plano].masks`: `classKey`, color, contorno), leídas por `canonicalMasksForPlane`. **Cada corrida tiene su propia paleta**; el visor individual y cada lado de la comparación usan la misma lectura. El color de una clase no implica anatomía.
- **Errores:** asset ausente (la imagen no se muestra y se indica), corrida sin clases publicadas (la leyenda lo dice).

### C.5.1 Primera lectura sin IA y activación explícita (cambio de interacción, 2026-10-20)
- **Qué cambió:** al abrir o recargar un estudio el visor muestra la **imagen original**: sin segmentación, trazos automáticos, mediciones de IA, derivadas, puntos de referencia, leyenda ni lista de estructuras; y el panel de resultados (Mediciones y Hallazgos IA) arranca en un estado compacto. Se activan con una acción explícita: botón **«Mostrar IA» / «Ocultar IA»** (accesible por teclado) y **«Mostrar resultados de la IA»**.
- **Dónde vive:** `StudyReviewView.tsx` (estado `{ key, overlays, results }` atado a `caso|corrida`, por encima de las pestañas), `PlaneViewport.tsx` (botón), `MriSliceViewer.tsx` (props `aiVisible` y `evidenceMeasurementIds`; todo lo que dibuja la IA depende de `aiVisible`).
- **Reglas:** el estado **no se persiste** (ni `localStorage` ni `sessionStorage`): abrir otro estudio, reabrirlo o recargar vuelve a empezar apagado; cambiar de pestaña dentro del estudio lo conserva. Mostrar/ocultar IA **no** cambia corte, zoom, ventana/nivel, corrida ni correcciones, y no borra resultados. Las anotaciones del profesional («Mis mediciones») no dependen del interruptor.
- **Visor dual (C.7):** ambos lados empiezan sin superposiciones y cada uno tiene su **control independiente**; los resultados comparados (Diferencias y Hallazgos IA) se abren con **«Mostrar resultados comparados»**. Elegir una medición o un hallazgo muestra **solo su evidencia** (trazo o región evaluada) con estado visible y la posibilidad de ocultarla; cambiar de referencia vuelve a empezar sin IA.
- **Alcance (lo que NO afirma):** esto evita la **exposición inicial automática** a las predicciones; **no** elimina todo sesgo de lectura. El profesional puede activar la IA cuando quiera y los resultados siguen rotulados como asistidos por IA que requieren revisión profesional. No cambia el análisis, los datos guardados ni el Backend.
- **Para los diagramas:** es una transición de estado **solo del Frontend** («IA oculta» → «IA visible» por acción del usuario); no hay llamadas nuevas ni persistencia nueva. Documento detallado en el repositorio del Frontend: `docs/PRIMERA_LECTURA_SIN_IA.md`.

### C.6 Correcciones, anotaciones, guardado y aprobación profesional
- **Front:** pestaña Revisión de `StudyReviewView.tsx`; `features/navigation/*` (aviso de cambios sin guardar); `api.ts`.
- **Endpoints (`AiRunReviewController`, `/api/ai/runs/{multiplanarRunId}`):** `POST|PUT /review` (estado, notas y correcciones de medición), `GET /review`, `PUT|GET /annotations`. Clases `RunReviewService`, `ReviewerAnnotationService`, `EffectiveMeasurementResolver` (valor efectivo = corrección si existe, valor de la IA si no).
- **Persistencia:** estado/revisor/fecha/notas en `domain_study_runs` (`review_status`, `reviewer`, `reviewed_at`, `comments`); correcciones en `domain_review_corrections`; anotaciones en `domain_reviewer_annotations`; auditoría en `domain_audit_events` (`AuditService`, `POST /api/ai/audit`).
- **Estados:** en la API `pendiente | aceptado | observado | descartado` (`ReviewStatusMapper`: `pending|accepted|observed|rejected`). **«Finalizado» en la interfaz es el estado `aceptado`.** Guardar un borrador de revisión conserva el estado «Pendiente»; «Finalizar y aprobar estudio» es una acción explícita con confirmación.
- **Una corrección no modifica el trazo original de la IA ni predicciones.**
- **Errores:** `observado`/`descartado` exigen una nota profesional (validación 400); sesión vencida.

### C.7 Comparación de mediciones y hallazgos entre dos corridas existentes
- **Front:** `features/reading/InitialComparisonPanel.tsx`, `DualComparisonView.tsx` (visor dual), `ComparisonPane.tsx` (un lado), `initialAnalysisComparisonApi.ts`, `useInitialComparison.ts`, `useComparisonSide.ts`.
- **Llamadas:** `GET /api/studies/{caseId}/initial-analysis-comparison/candidates` (estudios del mismo paciente comparables) y `GET /api/studies/{caseId}/initial-analysis-comparison?referenceCaseId=…&currentRunId=…&referenceRunId=…` (`InitialAnalysisComparisonController`).
- **No ejecuta inferencia:** `InitialAnalysisComparisonService` solo lee corridas **ya persistidas** (`InitialAnalysisMeasurementReader`, `StudyRepository`); no usa `AiServiceClient` ni `LlmProvider`.
- **Qué se calcula dónde (verificado):**
  - **Backend:** emparejamiento de mediciones (`InitialMeasurementMatcher`), diferencias absolutas y porcentuales, exclusiones con motivo, validación de secuencias declaradas, regla de selección de corrida (`InitialAnalysisComparisonService.RUN_SELECTION_RULE`).
  - **Frontend:** filtros de la lista («solo distintas de cero», magnitud mínima por métrica), orden, navegación anterior/siguiente, qué trazo dibujar y dónde (`dualComparisonLogic.ts`), y la **comparación de hallazgos degenerativos del visor dual** (`discFindingsComparison.ts › compareFindings`, usada por `DualComparisonView.tsx`) a partir de los snapshots de cada corrida.
  - **Ojo:** existe además `DiscFindingsComparator` en el Backend, usado **solo** por el asistente (`AssistantContextBuilder`) y el preinforme (`PreReportBuilder`). Son dos implementaciones del mismo concepto: no representarlas como un único componente.
- **Qué NO hace:** no hay registro (alineación) anatómico entre estudios. El «corte» de cada lado es el de su propia corrida (p. ej. «Ref. corte 8 / Act. corte 11»); un trazo solo se dibuja si serie, plano y corte coinciden con los de la medición; que el número de corte coincida no prueba equivalencia anatómica. «Ver en el corte» solo navega al corte de origen de una medición/región.
- **Hallazgos:** solo se comparan clasificaciones del mismo nivel entre corridas compatibles; sin datos persistidos se informa «no hay hallazgos comparables» (no se interpreta como ausencia).
- **Endpoint heredado:** `GET /api/studies/{caseId}/longitudinal-comparison` (`LongitudinalComparisonController`) sigue en el Backend y su panel en el Frontend, pero la entrada desde «Herramientas del estudio» está retirada; la herramienta expuesta es la comparación inicial.
- **Errores:** referencia sin análisis, secuencias no declaradas/incompatibles (no comparable), corrida inexistente (404).

### C.8 Asistente individual y A/B
- **Front:** `features/reading/ChatPanel.tsx`, `useChat.ts`, `chatApi.ts`, `chatState.ts`.
- **Endpoints (`ChatController`, `/api/studies/{caseId}`):** `POST /chat` (pregunta; `runId`, y para A/B `referenceCaseId` + `referenceRunId`), `GET /chat-context` (qué datos y consultas hay), `GET /chat-conversations`, `GET /chat-conversations/{id}`.
- **Cadena (modo acotado):** `ChatService.structuredChat` → `AssistantContextBuilder` arma **hechos estructurados** (`AssistantFact`, cada uno con una referencia citable) a partir de la corrida persistida → `StructuredAssistant`: (1) si la pregunta es una **consulta sugerida exacta**, se resuelve **sin LLM**; (2) si no, el router envía el vocabulario real al LLM (`SelfHostedLlmProvider` → LM Studio `POST /v1/chat/completions`, `response_format` JSON estricto) que **solo devuelve intención y filtros**; (3) `parsePlan` valida contra una lista permitida y descarta filtros no nombrados en la pregunta; (4) **el servidor construye la respuesta con plantillas**, línea por línea, y `verify` comprueba que cada línea cite **su** referencia; `ChatReferenceVerifier` valida las referencias.
- **Intenciones:** `LIST_MEASUREMENTS`, `LIST_CORRECTIONS`, `LIST_FINDINGS`, `LIST_MISSING_DATA`, `SUMMARY`, `LIST_DIFFERENCES`, `LIST_FINDING_CHANGES`, `LIST_NOT_COMPARABLE`, `UNSUPPORTED` (fuera de alcance: se explica el límite).
- **A/B:** mismo mecanismo con hechos de ambos estudios; las diferencias vienen de la comparación persistida (C.7), no del LLM.
- **Modo libre anterior:** apagado por defecto (`pfi.assistant.free-text-enabled=false`); sus conversaciones guardadas se muestran como «respuesta libre anterior: no verificada».
- **Persistencia:** `domain_chat_conversations`, `domain_chat_messages` (migraciones `V20260825_018`, `V20261017_026`).
- **Errores:** LLM apagado/sin modelo/timeout (el panel lo informa; las consultas sugeridas siguen funcionando sin LLM), respuesta no respaldada (se descarta), 502 del proveedor.

### C.9 Preinforme: preparación, edición, persistencia y exportación
- **Front:** `features/prereport/PreReportEditor.tsx`, `usePreReport.ts`, `prereportHtml.ts`, `staleExplanation.ts`, `prereportApi.ts`.
- **Endpoints (`PreReportController`, `/api/studies/{caseId}/prereport`):** `GET ?runId=` (vista: contenido generado, huella, borrador, desactualización, cambios), `PUT ?runId=` (guardar ediciones con control de versión), `POST /update-sources` (actualización **explícita** a los datos actuales).
- **Generación por código, sin LLM:** `PreReportBuilder` arma el documento (`pfi.prereport.v1`) desde la corrida persistida; ni `PreReportBuilder` ni `PreReportService` usan `LlmProvider` ni `AiServiceClient`.
- **Persistencia:** `domain_report_drafts` (`study_id`, `run_id`, `version`, `source_fingerprint`, `generated_json`, `edits_json`, `updated_by`; único por estudio+corrida; migración `V20261018_027`).
- **Desactualizado:** la huella (`fingerprint`) cubre todo el documento generado; si difiere, el borrador se marca desactualizado y `diff` explica qué cambió: datos del estudio, o el texto de generación (sección `document`). Nunca se reescribe solo.
- **Exportación:** en el navegador. HTML (`prereportHtml.ts`) descargable y PDF por impresión del navegador; variantes «Preinforme» y «Preinforme con anexos». Si falta evidencia visual se advierte dentro del editor y se exporta igual si se confirma.
- **Independencia de la aprobación:** aprobar el estudio (C.6) **no** aprueba el preinforme y guardar el preinforme **no** modifica la revisión del estudio. El preinforme es siempre un borrador pendiente de revisión profesional.
- **Errores:** conflicto de versión del borrador (409), corrida sin datos utilizables, imagen de evidencia no disponible (se conserva el resultado textual y se indica).

---

## D. Distinciones que no deben perderse

1. **Circuito real frente al antiguo de demostración.** Existen en el código restos del modo demostración (`POST /api/studies/demo`, `GET /api/studies/demo-review`, `GET /api/ai/studies/demo-review`, `dataOrigin: "demo"`, inferencia `mock`, `isDemoRun`). Los **accesos de interfaz** a esas herramientas se retiraron (`RETIRED_INSPECTOR_TOOLS` incluye `demo`, `report`, `concordance`, `feedback`, `audit`, `three-d`, `technical`); las implementaciones y endpoints siguen. **Los diagramas deben representar solo el circuito real.** Las herramientas expuestas hoy en «Herramientas del estudio» son **historial, comparación entre estudios y asistente**. El botón «Entrar con doctor demo» es solo una comodidad local de autenticación, no el circuito demo de datos.
2. **Dónde se calcula la comparación:** ver C.7 (mediciones: Backend; filtros, navegación, trazos y comparación de hallazgos del visor dual: Frontend).
3. **Asistente:** el LLM **solo** interpreta intención y filtros; el servidor valida y construye la respuesta respaldada por referencias (C.8).
4. **El preinforme se genera por código**, no con el LLM (C.9).
5. **Aprobar un estudio no aprueba el preinforme** (C.6/C.9).
6. **La comparación no ejecuta inferencia** (C.7).
7. **Paciente ≠ contexto histórico del estudio:** el paciente (`domain_patients`) aporta identidad de referencia; edad y contexto clínico son del **estudio** (`domain_studies`) y no se propagan (C.2).
8. **Región evaluada ≠ segmentación ≠ delimitación de lesión.** *Segmentación*: máscaras por clase del modelo de segmentación (vértebras, discos, canal…). *Región evaluada*: recuadro y cruz que indican **dónde evaluó el clasificador P10.7** cada resultado (incluye los cortes evaluados); **no delimita una lesión** ni valida la clasificación. No existe delimitación de lesiones.
9. **Comparación visual ≠ navegación a cortes de origen ≠ registro anatómico:** solo existen las dos primeras (C.7).
10. **Las predicciones P10.7 y el grado de Pfirrmann son experimentales**, sin validación externa; un resultado «ausente» es una predicción, no una conclusión. La correspondencia anatómica entre una medición (un corte) y una clasificación (serie completa) **no está verificada**.

---

## E. Guía para construir los diagramas

**Diagrama de componentes** — mostrar: Navegador/Frontend (estáticos), Backend (módulos: autenticación; pacientes y estudios; orquestación de IA y persistencia de corridas; revisión; comparación; asistente; preinforme; cliente LLM), Módulo IA (ingesta, segmentación, mediciones, P10.7, servido de assets), PostgreSQL, almacenamiento de assets (volúmenes de IA + copia durable en PG), LM Studio. **Relaciones:** Navegador→Backend (única conexión del cliente); Backend→IA; Backend→PG; Backend→LM Studio. **No dibujar:** Frontend→IA, Frontend→LLM, IA→PG, comparación→IA, preinforme→LLM.

**Diagrama de despliegue local** — anfitrión Windows/Docker: cuatro contenedores (`frontend:8288→80`, `backend:8280→8080`, `ai:8200→8000`, `postgres`), red `lumbarai-clean-20261006`, volúmenes `postgres-data`, `ai-outputs`, `ai-uploads`, montajes de modelos (solo lectura); **LM Studio fuera de Docker** (`:1234`) alcanzado como `host.docker.internal`. Cloud solo como nota «`main`, no probado aquí».

**Secuencias**
- *Carga/análisis:* Profesional → Front (paso 1–3) → `POST /api/ai/studies` (ZIP) → Back → IA `/inputs/study` → catálogo de series → «Analizar» → `POST /api/ai/multiplanar/run` → Back → IA `/v2/multiplanar/run` → persistencia (`domain_*`) → `POST …/series-segmentation` → IA → `POST …/disc-degenerative-findings` → IA → guardado en `metrics_snapshot` → Front abre el visor. Incluir el reintento solo de la clasificación y el estado «completado con degradación».
- *Comparación:* Front → `GET …/initial-analysis-comparison/candidates` → `GET …/initial-analysis-comparison?referenceCaseId=…` → Back lee dos corridas persistidas y empareja (sin IA) → Front filtra y dibuja; cada lado pide sus assets a `/api/ai/assets/...`. Hallazgos: calculados en el Front desde ambos snapshots.
- *Asistente:* Front → `POST /api/studies/{caseId}/chat` → Back arma hechos → [consulta sugerida exacta: responde sin LLM] / [pregunta libre: LLM → intención+filtros] → validación → plantillas → verificación de referencias → guardado de conversación → respuesta con referencias.
- *Preinforme:* Front → `GET …/prereport?runId=` → Back genera por código + compara huella → Front edita → `PUT …/prereport` (versión) → [corrección en la revisión → huella distinta → desactualizado] → `POST …/update-sources` (explícito) → exportación HTML/PDF en el navegador.

**Modelo de datos principal** (relaciones verificadas por las migraciones):
`domain_patients` 1—N `domain_studies` (`patient_id`; contexto clínico en `domain_studies`) · `domain_studies` 1—N `domain_study_runs` (corrida; estado de revisión y `metrics_snapshot`) · `domain_study_runs` 1—N `domain_run_artifacts` y `domain_run_asset_payloads` · `domain_study_runs` 1—N `domain_review_corrections` y `domain_reviewer_annotations` · `domain_studies` 1—N `domain_report_drafts` (único por estudio+corrida) · `domain_studies` 1—N `domain_chat_conversations` 1—N `domain_chat_messages` · `domain_input_resources` (entradas y procedencia de secuencia) · `domain_audit_events` · `doctor_accounts`. Las tablas `domain_ai_*` y `domain_professional_feedback_*` pertenecen a flujos de análisis de producto v1 y de curación de feedback que **no** son el circuito principal de lectura; incluirlas solo si se documenta ese circuito.

---

## F. Estado y límites

**Implementado y probado** (pruebas unitarias del Backend: 1176 sin fallos, 78 omitidas preexistentes; pruebas del Frontend con salida 0; verificaciones reales en el navegador contra el stack local con inferencia y asistente reales):
- Autenticación con segundo factor, pacientes, estudios y contexto por estudio.
- Carga ZIP y por plano con secuencia declarada; análisis real (segmentación, mediciones, P10.7).
- Visor, leyendas por corrida, correcciones, anotaciones y aviso de cambios sin guardar.
- Comparación de mediciones (par real OSF) y visor dual; asistente individual con modelo local real y modo acotado.
- Preinforme: generación, edición, borrador persistente, aviso de desactualización explicado por causa, exportación HTML/PDF.

**Implementado, con verificación pendiente:**
- **«Finalizar y aprobar estudio»:** el entorno de pruebas denegó la acción y no se eludió. Pasos manuales sobre un caso de prueba propio (`TESIS-PRUEBA-1019`, paciente sintético): abrir el estudio → Revisión → elegir «Finalizado» y confirmar → «Finalizar y aprobar estudio». Esperado: estado «Finalizado» con revisor y fecha; al recargar se mantiene y coincide en la lista y en el historial del paciente. No hacerlo en estudios de datasets reales.
- **Asistente A/B con respuesta nueva en un par real:** no se ejecutó una pregunta nueva en el par OSF (para no escribir en él). Las conversaciones guardadas de ese par pertenecen a una versión anterior del asistente. El intento de capturar el visor dual del par de **prueba** (`REVFIN-2110` frente a `PREISO-58044`, dos estudios cargados desde el mismo ZIP) terminó por **tiempo de espera de 30 s en un clic del script de captura**; la causa **no se diagnosticó** y **no es un fallo confirmado del LLM ni del producto**.
- Comparación de hallazgos degenerativos **con cambios de clasificación:** el par OSF informa «no hay hallazgos comparables». Existe un **par de prueba** (`caso1` referencia, `caso2` actual; mismo paciente de prueba `Prueba-001`) con 40 clasificaciones comparadas: 31 sin cambio, 9 con cambio (7 principales y 2 de investigación) y 0 no comparables. Sus archivos coinciden por hash con las dos visitas preparadas del paciente ID10 del dataset OSF, pero en el sistema es un **par de prueba: no se presenta como seguimiento clínico verificado** (no hay fechas de adquisición ni identificador de paciente en la base que lo confirme).
- Edad **calculada** (requiere fecha de nacimiento y del estudio): no se verificó en pantalla con datos reales; se verificó la edad declarada.
- Cierre de sesión en otra pestaña, registro de cuenta nueva, error 500 de la lista y carga con imágenes faltantes: no verificados.
- Exportar a PDF en el navegador del usuario (verificado solo en Chromium automatizado).

**No implementado o fuera del alcance:**
- Registro (alineación) anatómico entre estudios; delimitación de lesiones; validación clínica de las predicciones; diagnóstico automático.
- Despliegue Cloud en estas ramas (existe en `main`).
- La modalidad del estudio no se registra; la fecha y la edad no se infieren del DICOM (se cargan a mano o quedan «No informadas»).
- Aprobación o firma del preinforme (es solo borrador).
- Integración con `main`.

**Entorno de presentación (local, no versionado):** como el sistema no tiene un mecanismo soportado para eliminar estudios (no hay endpoint de borrado en el Backend ni en el módulo IA, ni acción en la interfaz), la presentación usa un **entorno aislado** con otro proyecto Docker (frontend `8388`, backend `8380`, IA `8300`, PostgreSQL `54359`, volúmenes propios) que contiene únicamente tres estudios copiados con INSERT desde el entorno principal (`8288`, que no se modificó). Para los diagramas de despliegue sigue valiendo la topología de B; el entorno aislado es solo otra instancia de la misma.
