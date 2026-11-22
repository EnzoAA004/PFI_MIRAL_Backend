package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import ar.edu.uade.pfi.backend.dto.AiMeasurementCorrectionRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisApiRequestDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisHistoryResponseDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisResultDto;
import ar.edu.uade.pfi.backend.dto.AiProductAnalysisReviewRequestDto;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisPersistenceService;
import ar.edu.uade.pfi.backend.service.AiProductAnalysisReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Etapa 75-H / 76-M-O / 78. El Frontend nunca ve la URL del módulo de IA, sus inputIds resueltos ni
 * ningún SHA de modelo — solo ve este subrecurso de un estudio propio del Backend, direccionado por
 * {@code caseId}.
 */
@RestController
@RequestMapping("/api/studies/{caseId}")
public class AiProductAnalysisController {
    private final AiProductAnalysisPersistenceService persistenceService;
    private final AiProductAnalysisReviewService reviewService;
    private final RoleAuthorizationService authorizationService;

    public AiProductAnalysisController(
        AiProductAnalysisPersistenceService persistenceService,
        AiProductAnalysisReviewService reviewService,
        RoleAuthorizationService authorizationService
    ) {
        this.persistenceService = persistenceService;
        this.reviewService = reviewService;
        this.authorizationService = authorizationService;
    }

    /**
     * Etapa 76-M: el body del request ya no necesita {@code sagittalInputId}/{@code axialInputId} —
     * el Backend resuelve ambos a partir de los inputs registrados del propio Study. Los campos todavía se
     * aceptan en el wire (deprecados) pero no tienen efecto.
     */
    @PostMapping("/product-analysis")
    public AiProductAnalysisResultDto analyze(@PathVariable String caseId, @RequestBody(required = false) AiProductAnalysisApiRequestDto request, HttpServletRequest httpRequest) {
        authorizationService.requireStudyWrite(httpRequest, caseId);
        AiProductAnalysisApiRequestDto body = request == null ? new AiProductAnalysisApiRequestDto(null, null, null) : request;
        return persistenceService.analyze(caseId, body.metadata());
    }

    // Harness de servicio local al paquete conservado para los unit tests focalizados del controller; HTTP usa el overload asegurado de arriba.
    AiProductAnalysisResultDto analyze(String caseId, AiProductAnalysisApiRequestDto request) {
        AiProductAnalysisApiRequestDto body = request == null ? new AiProductAnalysisApiRequestDto(null, null, null) : request;
        return persistenceService.analyze(caseId, body.metadata());
    }

    @GetMapping("/product-analyses")
    public AiProductAnalysisHistoryResponseDto history(@PathVariable String caseId, HttpServletRequest httpRequest) {
        authorizationService.requireStudyRead(httpRequest, caseId);
        return new AiProductAnalysisHistoryResponseDto(caseId, persistenceService.history(caseId));
    }

    AiProductAnalysisHistoryResponseDto history(String caseId) { return new AiProductAnalysisHistoryResponseDto(caseId, persistenceService.history(caseId)); }

    @GetMapping("/product-analyses/{analysisId}")
    public AiProductAnalysisResultDto detail(@PathVariable String caseId, @PathVariable String analysisId, HttpServletRequest httpRequest) {
        authorizationService.requireStudyRead(httpRequest, caseId);
        return persistenceService.detail(caseId, analysisId);
    }

    AiProductAnalysisResultDto detail(String caseId, String analysisId) { return persistenceService.detail(caseId, analysisId); }

    /** Etapa 78-B: aceptar/observar/rechazar la corrida como un todo. */
    @PatchMapping("/product-analyses/{analysisId}/review")
    public AiProductAnalysisResultDto reviewRun(
        @PathVariable String caseId,
        @PathVariable String analysisId,
        @RequestBody(required = false) AiProductAnalysisReviewRequestDto request,
        HttpServletRequest httpRequest
    ) {
        authorizationService.requireProfessional(httpRequest, analysisId);
        authorizationService.requireStudyWrite(httpRequest, caseId);
        return reviewService.reviewRun(caseId, analysisId, request, reviewedBy(httpRequest), traceId(httpRequest));
    }

    /** Etapa 78-C: aceptar/observar/rechazar un único nivel de disco, sin afectar la revisión propia de la corrida. */
    @PatchMapping("/product-analyses/{analysisId}/disc-levels/{discLevelId}/review")
    public AiProductAnalysisResultDto reviewDiscLevel(
        @PathVariable String caseId,
        @PathVariable String analysisId,
        @PathVariable String discLevelId,
        @RequestBody(required = false) AiProductAnalysisReviewRequestDto request,
        HttpServletRequest httpRequest
    ) {
        authorizationService.requireProfessional(httpRequest, analysisId);
        authorizationService.requireStudyWrite(httpRequest, caseId);
        return reviewService.reviewDiscLevel(caseId, analysisId, discLevelId, request, reviewedBy(httpRequest), traceId(httpRequest));
    }

    /** Etapa 78-D: agregar una corrección para un campo de medición; el valor original de la IA nunca se sobrescribe. */
    @PostMapping("/product-analyses/{analysisId}/measurements/{measurementId}/corrections")
    public AiProductAnalysisResultDto correctMeasurement(
        @PathVariable String caseId,
        @PathVariable String analysisId,
        @PathVariable String measurementId,
        @RequestBody(required = false) AiMeasurementCorrectionRequestDto request,
        HttpServletRequest httpRequest
    ) {
        authorizationService.requireProfessional(httpRequest, analysisId);
        authorizationService.requireStudyWrite(httpRequest, caseId);
        return reviewService.correctMeasurement(caseId, analysisId, measurementId, request, reviewedBy(httpRequest), traceId(httpRequest));
    }

    /**
     * Nunca confía en un nombre de revisor provisto por el cliente (etapa 78: "no inventar nombre de medico") —
     * se deriva únicamente de los claims propios del principal autenticado, y es {@code null} (no un
     * placeholder) cuando no hay una identidad autenticada disponible.
     */
    private String reviewedBy(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        if (!(value instanceof TokenService.Claims claims)) return null;
        if (claims.email() != null && !claims.email().isBlank()) return claims.email();
        if (claims.subject() != null && !claims.subject().isBlank()) return claims.subject();
        return null;
    }

    private String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) return value;
        String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        return header == null || header.isBlank() ? null : header;
    }
}
