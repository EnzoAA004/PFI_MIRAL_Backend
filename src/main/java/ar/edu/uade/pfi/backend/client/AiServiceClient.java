package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.AiMultiplanarContractVersion;
import ar.edu.uade.pfi.backend.config.AiProductContractProperties;
import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.util.SafeLogSanitizer;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import ar.edu.uade.pfi.backend.web.error.ApiErrorCode;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2RequestDto;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2ResponseDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalyzeResponseDto;
import ar.edu.uade.pfi.backend.dto.AiStructuredErrorV2Dto;
import ar.edu.uade.pfi.backend.dto.AiSubarticularPredictRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.SubarticularPredictionResponseDto;
import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarUpstreamException;
import ar.edu.uade.pfi.backend.service.AiProductContractValidator;
import ar.edu.uade.pfi.backend.service.AiProductContractViolationException;
import ar.edu.uade.pfi.backend.service.AiProductSafetyViolationException;
import ar.edu.uade.pfi.backend.service.AiProductUpstreamException;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator;
import ar.edu.uade.pfi.backend.service.OperationalMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * El único cliente HTTP del módulo de IA. Nunca envía el JWT/refresh token del usuario,
 * ni su email ni sus roles — esta llamada es backend a módulo de IA, no un passthrough de
 * las credenciales del propio frontend. Cada llamada lleva un header X-Trace-Id: el trace id del
 * request actual (leído del MDC, seteado por TraceIdFilter) cuando existe, o uno
 * recién generado {@code technical-<uuid>} — resuelto una vez por llamada, nunca guardado
 * globalmente, nunca filtrado entre threads — para un proceso en background/sin request HTTP con
 * nada en el MDC. Ver docs/P10_B_ERRORS_AUDIT_OBSERVABILITY.md §9/§14.
 *
 * P10-B.1: nunca arma un mensaje de excepción público concatenando el body de la respuesta
 * upstream, el mensaje propio de una excepción desempaquetada, el body de una WebClientResponseException
 * ni un host/URL — todo mensaje público de acá sale de
 * {@link ApiErrorCode#publicMessage()}. El body upstream solo se usa para
 * deserializar {@link AiStructuredErrorV2Dto} (el contrato de error estructurado v2); cuando
 * no es estructurado, se descarta por completo — ni siquiera su longitud se loguea sin sanitizar.
 */
@Component
public class AiServiceClient implements AiServiceOperations {
    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE = new ParameterizedTypeReference<>() {};

    private final WebClient aiWebClient;
    private final Duration timeout;
    /** Techo corto para las consultas que solo preguntan estado. Ver getDiagnosticMap. */
    private final Duration diagnosticTimeout;
    private final AiMultiplanarContractVersion multiplanarContractVersion;
    private final AiMultiplanarV2RequestMapper v2RequestMapper;
    private final AiMultiplanarV2ResponseAdapter v2ResponseAdapter;
    private final AiMultiplanarV1ResponseAdapter v1ResponseAdapter;
    private final MultiplanarRealBaselineContractValidator v1StrictValidator;
    private final MultiplanarV2RealBaselineValidator v2StrictValidator;
    private final ObjectMapper objectMapper;
    private final OperationalMetricsService metrics;
    private final AiProductContractValidator productContractValidator;

    public AiServiceClient(
        WebClient aiWebClient,
        AiServiceProperties properties,
        AiMultiplanarV2RequestMapper v2RequestMapper,
        AiMultiplanarV2ResponseAdapter v2ResponseAdapter,
        AiMultiplanarV1ResponseAdapter v1ResponseAdapter,
        MultiplanarRealBaselineContractValidator v1StrictValidator,
        MultiplanarV2RealBaselineValidator v2StrictValidator,
        ObjectMapper objectMapper
    ) {
        this(aiWebClient, properties, v2RequestMapper, v2ResponseAdapter, v1ResponseAdapter, v1StrictValidator, v2StrictValidator, objectMapper, null, null);
    }

    @Autowired
    public AiServiceClient(
        WebClient aiWebClient,
        AiServiceProperties properties,
        AiMultiplanarV2RequestMapper v2RequestMapper,
        AiMultiplanarV2ResponseAdapter v2ResponseAdapter,
        AiMultiplanarV1ResponseAdapter v1ResponseAdapter,
        MultiplanarRealBaselineContractValidator v1StrictValidator,
        MultiplanarV2RealBaselineValidator v2StrictValidator,
        ObjectMapper objectMapper,
        @Nullable OperationalMetricsService metrics,
        @Nullable AiProductContractValidator productContractValidator
    ) {
        this.aiWebClient = aiWebClient;
        this.timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
        this.diagnosticTimeout = Duration.ofSeconds(properties.resolvedDiagnosticTimeoutSeconds());
        this.multiplanarContractVersion = properties.resolvedMultiplanarContractVersion();
        this.v2RequestMapper = v2RequestMapper;
        this.v2ResponseAdapter = v2ResponseAdapter;
        this.v1ResponseAdapter = v1ResponseAdapter;
        this.v1StrictValidator = v1StrictValidator;
        this.v2StrictValidator = v2StrictValidator;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.productContractValidator = productContractValidator == null
            ? new AiProductContractValidator(AiProductContractProperties.defaults())
            : productContractValidator;
    }

    /**
     * El trace id del request actual desde el MDC, o un {@code technical-<uuid>} nuevo — resuelto
     * una vez por quien llama y reutilizado durante toda la llamada lógica (header + body v2, donde
     * aplique). Nunca se vuelve a escribir en el MDC — es solo un valor local por llamada.
     */
    private String resolveTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return "technical-" + UUID.randomUUID();
    }

    /** Toda llamada al módulo de IA lleva un header de trace id — ver docs de clase y {@link #resolveTraceId()}. */
    private void applyTraceHeader(HttpHeaders headers) {
        headers.set(TraceIdFilter.TRACE_ID_HEADER, resolveTraceId());
    }

    private void applyTraceHeader(HttpHeaders headers, String traceId) {
        headers.set(TraceIdFilter.TRACE_ID_HEADER, traceId);
    }

    @Override
    public Map<String, Object> health() {
        return getDiagnosticMap("/health");
    }

    @Override
    public Map<String, Object> readiness() {
        return getDiagnosticMap("/readiness");
    }

    @Override
    public Object models() {
        return execute(() -> aiWebClient.get().uri("/models").headers(this::applyTraceHeader).retrieve().bodyToMono(Object.class).block(timeout));
    }

    @Override
    public Map<String, Object> verifyModels() {
        return getDiagnosticMap("/models/verify");
    }

    public Map<String, Object> getModelRuntime() {
        return getDiagnosticMap("/models/runtime");
    }

    @Override
    public Map<String, Object> syncModels(boolean force) {
        return execute(() -> aiWebClient.post()
            .uri(uriBuilder -> uriBuilder.path("/models/sync").queryParam("force", force).build())
            .headers(this::applyTraceHeader)
            .exchangeToMono(response -> mapResponseOrError(response))
            .block(timeout));
    }

    @Override
    public Map<String, Object> warmup() {
        return getMap("/warmup");
    }

    @Override
    public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
        String traceId = resolveTraceId();
        PipelineRunRequestDto tracedRequest = withTraceMetadata(request, traceId);
        return execute(() -> aiWebClient.post()
            .uri("/pipeline/run")
            .headers(headers -> applyTraceHeader(headers, traceId))
            .bodyValue(tracedRequest)
            .exchangeToMono(response -> mapResponseOrError(response))
            .block(timeout));
    }

    @Override
    public AiInputResponseDto uploadInput(MultipartFile file, String caseId, String plane) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file.getResource())
            .filename(file.getOriginalFilename() == null ? "input" : file.getOriginalFilename());
        body.part("caseId", caseId);
        body.part("plane", plane);
        return execute(() -> aiWebClient.post()
            .uri("/inputs")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .headers(this::applyTraceHeader)
            .bodyValue(body.build())
            .retrieve()
            .bodyToMono(AiInputResponseDto.class)
            .block(timeout));
    }

    @Override
    public AiInputResponseDto registerServerSideInput(String caseId, String plane, String sourceKey) {
        return execute(() -> aiWebClient.post()
            .uri("/inputs")
            .contentType(MediaType.APPLICATION_JSON)
            .headers(this::applyTraceHeader)
            .bodyValue(Map.of("caseId", caseId, "plane", plane, "sourceKey", sourceKey))
            .retrieve()
            .bodyToMono(AiInputResponseDto.class)
            .block(timeout));
    }
    @Override
    public Map<String, Object> uploadStudy(MultipartFile file, String caseId) {
        String traceId = resolveTraceId();
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file.getResource())
            .filename(file.getOriginalFilename() == null ? "study.zip" : file.getOriginalFilename());
        body.part("caseId", caseId);
        return execute(() -> aiWebClient.post()
            .uri("/inputs/study")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .headers(headers -> applyTraceHeader(headers, traceId))
            .bodyValue(body.build())
            .exchangeToMono(response -> mapStudyUploadResponseOrError(response, traceId))
            .block(timeout));
    }

    @Override
    public ResponseEntity<byte[]> getAsset(String runId, String plane, String assetName) {
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/assets/{runId}/{plane}/{assetName}").build(runId, plane, assetName))
            .headers(this::applyTraceHeader)
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.toEntity(byte[].class);
                }
                int status = response.statusCode().value();
                if (status == 403 || status == 404) {
                    return response.releaseBody().then(Mono.error(new ResponseStatusException(response.statusCode(), "AI Module asset request failed")));
                }
                return response.releaseBody().then(Mono.error(upstreamUnavailable()));
            })
            .block(timeout));
    }

    /**
     * Un slice de una serie almacenada, haya corrido o no un modelo sobre ella.
     *
     * <p>Se direcciona por input y no por corrida porque estas series no tienen corrida: son
     * las que el estudio traía y la IA no analiza — los T1, el T1 axial
     * sin modelo, el localizador — y el lector las necesita igual.
     */
    public ResponseEntity<byte[]> getSeriesSlice(String inputId, int index) {
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/inputs/{inputId}/slices/{index}").build(inputId, index))
            .headers(this::applyTraceHeader)
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.toEntity(byte[].class);
                }
                int status = response.statusCode().value();
                if (status == 403 || status == 404) {
                    return response.releaseBody().then(Mono.error(new ResponseStatusException(response.statusCode(), "AI Module series slice request failed")));
                }
                return response.releaseBody().then(Mono.error(upstreamUnavailable()));
            })
            .block(timeout));
    }

    @Override
    public ResponseEntity<byte[]> getRunSegmentation(String planeRunId, String plane) {
        return dicomExport("/runs/{runId}/{plane}/segmentation.dcm", planeRunId, plane, "segmentation");
    }

    @Override
    public ResponseEntity<byte[]> getRunMeasurementReport(String planeRunId, String plane) {
        return dicomExport("/runs/{runId}/{plane}/measurements.sr.dcm", planeRunId, plane, "measurement report");
    }

    /**
     * Descarga un objeto DICOM construido en el momento por el modulo de IA.
     *
     * <p>El 409 se propaga tal cual y no se convierte en 502: significa que la corrida
     * existe pero no reune las condiciones para exportar —entrada que no es DICOM,
     * mascara vacia, corrida sin mediciones—. Colapsarlo a "el modulo de IA fallo" haria
     * que el visor ofrezca reintentar algo que nunca va a funcionar.
     */
    private ResponseEntity<byte[]> dicomExport(String path, String planeRunId, String plane, String what) {
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path(path).build(planeRunId, plane))
            .headers(this::applyTraceHeader)
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.toEntity(byte[].class);
                }
                int status = response.statusCode().value();
                if (status == 400 || status == 404 || status == 409) {
                    return response.releaseBody().then(Mono.error(
                        new ResponseStatusException(response.statusCode(), "AI Module " + what + " export failed")));
                }
                return response.releaseBody().then(Mono.error(upstreamUnavailable()));
            })
            .block(timeout));
    }

    /**
     * A-F: resuelve la versión de contrato configurada, arma el request apropiado,
     * llama exactamente a un endpoint, deserializa al DTO correspondiente, adapta a
     * CanonicalMultiplanarRun y lo devuelve. No hay fallback entre contratos
     * ni reintento contra el otro endpoint ante un error.
     */
    @Override
    public CanonicalMultiplanarRun runMultiplanar(MultiplanarRunRequestDto request) {
        return multiplanarContractVersion == AiMultiplanarContractVersion.V2
            ? runMultiplanarV2(request)
            : runMultiplanarV1(request);
    }

    /**
     * Una llamada solo cuenta como {@code aiCallsSucceeded} una vez que toda la operación lógica —
     * HTTP, deserialización, adaptador, validación de contrato — se completó y produjo un
     * resultado canónico utilizable (P10-B.1 §12). Un HTTP 200 con un contrato inválido es una
     * falla, no un éxito; se registra exactamente una vez, sin importar en qué etapa se lance la excepción.
     */
    private CanonicalMultiplanarRun runMultiplanarV1(MultiplanarRunRequestDto request) {
        String traceId = resolveTraceId();
        MultiplanarRunRequestDto tracedRequest = withTraceMetadata(request, traceId);
        long startedAt = System.currentTimeMillis();
        try {
            MultiplanarRunResponseDto response = executeHttp(() -> aiWebClient.post()
                .uri("/multiplanar/run")
                .headers(headers -> applyTraceHeader(headers, traceId))
                .bodyValue(tracedRequest)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(MultiplanarRunResponseDto.class);
                    }
                    if (clientResponse.statusCode().is4xxClientError()) {
                        return clientResponse.releaseBody().then(Mono.error(
                            new ResponseStatusException(clientResponse.statusCode(), "El modulo de IA rechazo la solicitud multiplanar.")));
                    }
                    return clientResponse.releaseBody().then(Mono.error(upstreamUnavailable()));
                })
                .block(timeout));
            v1StrictValidator.validate(tracedRequest, response);
            CanonicalMultiplanarRun canonical = v1ResponseAdapter.toCanonical(response);
            recordAiCall(true, startedAt);
            return canonical;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            if (ex instanceof AiMultiplanarContractViolationException) {
                incrementContractViolations();
            }
            throw ex;
        }
    }

    private CanonicalMultiplanarRun runMultiplanarV2(MultiplanarRunRequestDto request) {
        String traceId = resolveTraceId();
        long startedAt = System.currentTimeMillis();
        try {
            AiMultiplanarV2RequestDto v2Request = v2RequestMapper.toV2Request(request, traceId);
            TraceIdConsistencyGuard.require(v2Request.traceId(), traceId);

            AiMultiplanarV2ResponseDto response = dispatchV2Http(() -> aiWebClient.post()
                .uri("/v2/multiplanar/run")
                .headers(headers -> applyTraceHeader(headers, traceId))
                .bodyValue(v2Request)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(AiMultiplanarV2ResponseDto.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(buildV2Error(body, traceId)));
                })
                .block(timeout), traceId);

            CanonicalMultiplanarRun canonical = v2ResponseAdapter.toCanonical(response);
            v2StrictValidator.validate(request, canonical);
            recordAiCall(true, startedAt);
            return canonical;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            if (ex instanceof AiMultiplanarContractViolationException) {
                incrementContractViolations();
            }
            throw ex;
        }
    }

    /**
     * Clasificacion subarticular sobre una coordenada marcada a mano.
     *
     * <p>Sigue el mismo camino que {@link #runMultiplanarV2}: trace id propagado por
     * header, error estructurado traducido por tabla fija, y metrica de exito o fallo.
     * La unica diferencia es la tabla de codigos, que es la del contrato subarticular.
     *
     * <p>No valida la respuesta acá. La validacion del contrato clinico vive en
     * {@code DegenerativeFindingsV1Validator} y la aplica el controller, que es quien
     * decide que hacer con un hallazgo mal formado.
     */
    @Override
    public SubarticularPredictionResponseDto predictSubarticular(AiSubarticularPredictRequestDto request) {
        String traceId = resolveTraceId();
        long startedAt = System.currentTimeMillis();
        try {
            SubarticularPredictionResponseDto response = dispatchV2Http(() -> aiWebClient.post()
                .uri("/degenerative-findings/subarticular/predict")
                .headers(headers -> applyTraceHeader(headers, traceId))
                .bodyValue(request)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(SubarticularPredictionResponseDto.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(buildSubarticularError(body, traceId)));
                })
                .block(timeout), traceId);

            recordAiCall(true, startedAt);
            return response;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            throw ex;
        }
    }

    /**
     * EXT-03: mismo camino que {@link #predictSubarticular} — trace id propagado por
     * header, mismo mapeo de errores estructurados, misma metrica de exito/fallo. La
     * unica diferencia es el path y el DTO de respuesta (el asset de explicabilidad, no
     * la clasificacion en si).
     */
    @Override
    public ar.edu.uade.pfi.backend.dto.SubarticularExplainResponseDto explainSubarticular(ar.edu.uade.pfi.backend.dto.AiSubarticularExplainRequestDto request) {
        String traceId = resolveTraceId();
        long startedAt = System.currentTimeMillis();
        try {
            ar.edu.uade.pfi.backend.dto.SubarticularExplainResponseDto response = dispatchV2Http(() -> aiWebClient.post()
                .uri("/degenerative-findings/subarticular/explain")
                .headers(headers -> applyTraceHeader(headers, traceId))
                .bodyValue(request)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(ar.edu.uade.pfi.backend.dto.SubarticularExplainResponseDto.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(buildSubarticularError(body, traceId)));
                })
                .block(timeout), traceId);

            recordAiCall(true, startedAt);
            return response;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            throw ex;
        }
    }

    /**
     * Igual que {@link #buildV2Error}, pero resolviendo contra la tabla del contrato
     * subarticular. El body crudo nunca se copia a la excepcion publica.
     */
    private RuntimeException buildSubarticularError(String body, String traceId) {
        AiStructuredErrorV2Dto structured = tryParseStructuredError(body);
        AiMultiplanarV2ErrorCodeMapper.Mapped mapped = structured != null && structured.code() != null && !structured.code().isBlank()
            ? AiSubarticularErrorCodeMapper.resolve(structured.code())
            : AiMultiplanarV2ErrorCodeMapper.UNKNOWN;
        String aiTraceId = structured != null && structured.traceId() != null && !structured.traceId().isBlank()
            ? structured.traceId()
            : traceId;
        return new AiMultiplanarUpstreamException(mapped.status(), mapped.backendCode().name(), mapped.publicMessage(), aiTraceId);
    }

    private void incrementContractViolations() {
        if (metrics != null) metrics.incrementAiContractViolations();
    }

    /**
     * Solo deserializa el body upstream en {@link AiStructuredErrorV2Dto} — el
     * body/mensaje crudo nunca se copia a la excepción pública. Los detalles técnicos
     * (código estructurado, trace id upstream) se guardan únicamente como campos internos de la
     * excepción resultante; el mensaje público siempre sale de
     * {@link ApiErrorCode#publicMessage()} vía {@link AiMultiplanarV2ErrorCodeMapper}.
     */
    private RuntimeException buildV2Error(String body, String traceId) {
        AiStructuredErrorV2Dto structured = tryParseStructuredError(body);
        AiMultiplanarV2ErrorCodeMapper.Mapped mapped = structured != null && structured.code() != null && !structured.code().isBlank()
            ? AiMultiplanarV2ErrorCodeMapper.resolve(structured.code())
            : AiMultiplanarV2ErrorCodeMapper.UNKNOWN;
        String aiTraceId = structured != null && structured.traceId() != null && !structured.traceId().isBlank()
            ? structured.traceId()
            : traceId;
        return new AiMultiplanarUpstreamException(mapped.status(), mapped.backendCode().name(), mapped.publicMessage(), aiTraceId);
    }

    private AiStructuredErrorV2Dto tryParseStructuredError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readValue(body, AiStructuredErrorV2Dto.class);
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public Map<String, Object> getAgentReport(String runId) {
        return execute(() -> aiWebClient.get().uri("/agent/report/{runId}", runId).headers(this::applyTraceHeader).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    @Override
    public Map<String, Object> getAgentReportSummary(String runId) {
        return execute(() -> aiWebClient.get().uri("/agent/report/{runId}/summary", runId).headers(this::applyTraceHeader).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    @Override
    public Map<String, Object> getRecentAgentReports(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/agent/reports").queryParam("limit", safeLimit).build())
            .headers(this::applyTraceHeader)
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(timeout));
    }

    @Override
    public Map<String, Object> getEvaluationSummary() {
        return getMap("/evaluation/summary");
    }

    @Override
    public Map<String, Object> getEvaluationEvidence() {
        return getMap("/evaluation/evidence");
    }

    @Override
    public Map<String, Object> getMultiplanarContract() {
        return getDiagnosticMap("/multiplanar/contract");
    }

    /**
     * POST /v1/product/analyze. Misma forma que {@link #runMultiplanarV2}: trace id propagado por
     * header, fallas de HTTP/transporte traducidas a {@link AiProductUpstreamException}, y un
     * éxito solo se registra una vez que todo el pipeline — HTTP, deserialización Y la validación de
     * contrato/seguridad 75-F — produjo un resultado que Spring acepta devolver. Una respuesta 200
     * que falla la validación de contrato es una falla, no un éxito.
     */
    @Override
    public AiProductAnalyzeResponseDto analyzeProduct(AiProductAnalyzeRequestDto request) {
        String traceId = resolveTraceId();
        AiProductAnalyzeRequestDto tracedRequest = withProductTraceMetadata(request, traceId);
        long startedAt = System.currentTimeMillis();
        try {
            AiProductAnalyzeResponseDto response = dispatchProductHttp(() -> aiWebClient.post()
                .uri("/v1/product/analyze")
                .headers(headers -> applyTraceHeader(headers, traceId))
                .bodyValue(tracedRequest)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(AiProductAnalyzeResponseDto.class);
                    }
                    return clientResponse.releaseBody().then(Mono.error(buildProductError(clientResponse.statusCode(), traceId)));
                })
                .block(timeout), traceId);
            productContractValidator.validate(response);
            recordAiCall(true, startedAt);
            return response;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            if (ex instanceof AiProductContractViolationException || ex instanceof AiProductSafetyViolationException) {
                incrementContractViolations();
            }
            throw ex;
        }
    }

    private AiProductAnalyzeRequestDto withProductTraceMetadata(AiProductAnalyzeRequestDto request, String traceId) {
        Map<String, Object> metadata = mergedTraceMetadata(request.metadata(), traceId);
        return new AiProductAnalyzeRequestDto(request.caseId(), request.sagittalInputId(), request.axialInputId(), metadata);
    }

    /** Preserva el status 4xx upstream tal cual (misma convención que {@link #mapResponseOrError}) — nunca expone el body. */
    private RuntimeException buildProductError(org.springframework.http.HttpStatusCode statusCode, String traceId) {
        if (statusCode.is4xxClientError()) {
            return new AiProductUpstreamException(HttpStatus.valueOf(statusCode.value()), ApiErrorCode.AI_INVALID_REQUEST.name(), ApiErrorCode.AI_INVALID_REQUEST.publicMessage(), traceId);
        }
        return new AiProductUpstreamException(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_MODULE_ERROR.name(), ApiErrorCode.AI_MODULE_ERROR.publicMessage(), traceId);
    }

    /** Como {@link #dispatchV2Http}, para el pipeline de product-analyze. */
    private <T> T dispatchProductHttp(Supplier<T> supplier, String traceId) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            Throwable unwrapped = Exceptions.unwrap(ex);
            if (unwrapped instanceof AiProductUpstreamException upstream) {
                throw upstream;
            }
            if (isTimeout(unwrapped)) {
                throw new AiProductUpstreamException(HttpStatus.GATEWAY_TIMEOUT, ApiErrorCode.AI_MODULE_TIMEOUT.name(), ApiErrorCode.AI_MODULE_TIMEOUT.publicMessage(), traceId);
            }
            throw new AiProductUpstreamException(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_MODULE_ERROR.name(), ApiErrorCode.AI_MODULE_ERROR.publicMessage(), traceId);
        }
    }

    private Map<String, Object> getMap(String path) {
        return getMap(path, timeout);
    }

    /**
     * Consulta de diagnostico: responde rapido o no responde.
     *
     * <p>Se acota el {@code block} y tambien el {@code responseTimeout} de la conexion,
     * porque cortar solo del lado del que espera deja el socket abierto consumiendo un
     * descriptor hasta que venza el techo largo.
     */
    private Map<String, Object> getDiagnosticMap(String path) {
        return getMap(path, diagnosticTimeout);
    }

    private Map<String, Object> getMap(String path, Duration limit) {
        return execute(() -> aiWebClient.get()
            .uri(path)
            .headers(this::applyTraceHeader)
            .httpRequest(request -> {
                if (request.getNativeRequest() instanceof reactor.netty.http.client.HttpClientRequest nativeRequest) {
                    nativeRequest.responseTimeout(limit);
                }
            })
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(limit));
    }

    /** Nunca expone el body upstream — un rechazo solo lleva un mensaje público fijo y seguro. */
    private Mono<Map<String, Object>> mapResponseOrError(org.springframework.web.reactive.function.client.ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(MAP_RESPONSE);
        }
        if (response.statusCode().is4xxClientError()) {
            return response.releaseBody().then(Mono.error(
                new ResponseStatusException(response.statusCode(), "La solicitud enviada al modulo de IA es invalida.")));
        }
        return response.releaseBody().then(Mono.error(upstreamUnavailable()));
    }

    private Mono<Map<String, Object>> mapStudyUploadResponseOrError(org.springframework.web.reactive.function.client.ClientResponse response, String fallbackTraceId) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.toEntity(MAP_RESPONSE).map(entity -> {
                Map<String, Object> body = new LinkedHashMap<>(entity.getBody() == null ? Map.of() : entity.getBody());
                String upstreamTraceId = entity.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER);
                body.put("traceId", upstreamTraceId == null || upstreamTraceId.isBlank() ? fallbackTraceId : upstreamTraceId);
                return body;
            });
        }
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        if (status != null && status.value() == 400) {
            return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "El estudio enviado al modulo de IA es invalido.")));
        }
        if (status != null && status.value() == 413) {
            return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "El estudio supera el tamano maximo permitido.")));
        }
        if (status != null && status.value() == 422) {
            return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "El estudio no contiene planos utilizables.")));
        }
        return response.releaseBody().then(Mono.error(upstreamUnavailable()));
    }

    private ResponseStatusException upstreamUnavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiErrorCode.UPSTREAM_UNAVAILABLE.publicMessage());
    }

    private PipelineRunRequestDto withTraceMetadata(PipelineRunRequestDto request, String traceId) {
        Map<String, Object> metadata = mergedTraceMetadata(request.metadata(), traceId);
        return new PipelineRunRequestDto(request.caseId(), request.plane(), request.modelKey(), request.inputPath(), request.inputId(), metadata);
    }

    private MultiplanarRunRequestDto withTraceMetadata(MultiplanarRunRequestDto request, String traceId) {
        Map<String, Object> metadata = mergedTraceMetadata(request.metadata(), traceId);
        return new MultiplanarRunRequestDto(
            request.caseId(),
            request.sagittalInputId(),
            request.axialInputId(),
            request.sagittalInputPath(),
            request.axialInputPath(),
            request.sagittalModelKey(),
            request.axialModelKey(),
            request.allowContractFallback(),
            request.studySeries(),
            metadata
        );
    }

    private Map<String, Object> mergedTraceMetadata(Map<String, Object> source, String traceId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source != null) {
            metadata.putAll(source);
        }
        metadata.putIfAbsent("traceId", traceId);
        metadata.putIfAbsent("backendTraceId", traceId);
        metadata.putIfAbsent("correlationId", traceId);
        return metadata;
    }

    /** Llamadas simples (health/models/assets/...) — la propia llamada HTTP es toda la operación lógica, así que el éxito/falla se registra automáticamente a su alrededor. */
    private <T> T execute(Supplier<T> supplier) {
        long startedAt = System.currentTimeMillis();
        try {
            T result = supplier.get();
            recordAiCall(true, startedAt);
            return result;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            throw translateException(ex);
        }
    }

    /** Como {@link #execute}, pero sin registro de métricas — se usa dentro de {@link #runMultiplanarV1}, que registra alrededor de todo el pipeline HTTP+validación+adaptación. */
    private <T> T executeHttp(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            throw translateException(ex);
        }
    }

    /** Como {@link #executeHttp}, para el dispatch v2 — el registro de métricas ocurre una sola vez, alrededor de todo el pipeline, en {@link #runMultiplanarV2}. */
    private <T> T dispatchV2Http(Supplier<T> supplier, String traceId) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            Throwable unwrapped = Exceptions.unwrap(ex);
            if (unwrapped instanceof AiMultiplanarUpstreamException upstream) {
                throw upstream;
            }
            if (unwrapped instanceof AiMultiplanarContractViolationException contractViolation) {
                throw contractViolation;
            }
            if (isTimeout(unwrapped)) {
                throw new AiMultiplanarUpstreamException(HttpStatus.GATEWAY_TIMEOUT, ApiErrorCode.AI_MODULE_TIMEOUT.name(), ApiErrorCode.AI_MODULE_TIMEOUT.publicMessage(), traceId);
            }
            throw new AiMultiplanarUpstreamException(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_MODULE_ERROR.name(), ApiErrorCode.AI_MODULE_ERROR.publicMessage(), traceId);
        }
    }

    private void recordAiCall(boolean succeeded, long startedAtMs) {
        if (metrics == null) return;
        metrics.recordAiCall(succeeded, System.currentTimeMillis() - startedAtMs);
    }

    private boolean isTimeout(Throwable unwrapped) {
        return unwrapped instanceof TimeoutException;
    }

    /**
     * Traduce una falla a nivel de transporte en una {@link ResponseStatusException} que lleva
     * solo un mensaje público fijo — nunca el body de la respuesta upstream, el mensaje propio de una
     * excepción desempaquetada, un host ni una URL. Una línea técnica sanitizada, solo de nivel DEBUG,
     * se loguea por separado para diagnóstico.
     */
    public ResponseStatusException translateException(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (unwrapped instanceof ResponseStatusException responseStatusException) {
            return responseStatusException;
        }
        logTechnicalDetail(unwrapped);
        if (unwrapped instanceof WebClientResponseException) {
            return upstreamUnavailable();
        }
        if (isTimeout(unwrapped)) {
            return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, ApiErrorCode.AI_MODULE_TIMEOUT.publicMessage(), unwrapped);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiErrorCode.UPSTREAM_UNAVAILABLE.publicMessage(), unwrapped);
    }

    /** Solo DEBUG y sanitizado — nunca en el nivel de log por defecto, nunca en el body de una respuesta. */
    private void logTechnicalDetail(Throwable unwrapped) {
        if (!log.isDebugEnabled()) return;
        log.debug("event=ai_module_call_failed exceptionType={} message={}",
            unwrapped.getClass().getSimpleName(),
            SafeLogSanitizer.sanitizeMessage(unwrapped.getMessage()));
    }
}

