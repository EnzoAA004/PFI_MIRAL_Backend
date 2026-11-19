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
 * Stage 75-H / 76-M-O / 78. The Frontend never sees the AI Module's URL, its resolved inputIds, or
 * any model SHA — it only sees this Backend-owned sub-resource of a study, addressed by
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
     * Stage 76-M: the request body no longer needs {@code sagittalInputId}/{@code axialInputId} —
     * the Backend resolves both from the Study's own registered inputs. The fields are still
     * accepted on the wire (deprecated) but have no effect.
     */
    @PostMapping("/product-analysis")
    public AiProductAnalysisResultDto analyze(@PathVariable String caseId, @RequestBody(required = false) AiProductAnalysisApiRequestDto request, HttpServletRequest httpRequest) {
        authorizationService.requireStudyWrite(httpRequest, caseId);
        AiProductAnalysisApiRequestDto body = request == null ? new AiProductAnalysisApiRequestDto(null, null, null) : request;
        return persistenceService.analyze(caseId, body.metadata());
    }

    // Package-local service harness retained for the controller's focused unit tests; HTTP uses the secured overload above.
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

    /** Stage 78-B: accept/observe/reject the run as a whole. */
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

    /** Stage 78-C: accept/observe/reject a single disc level, without affecting the run's own review. */
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

    /** Stage 78-D: append a correction for one measurement field; the AI's original value is never overwritten. */
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
     * Never trusts a client-supplied reviewer name (stage 78: "no inventar nombre de medico") —
     * derived only from the authenticated principal's own claims, and {@code null} (not a
     * placeholder) when no authenticated identity is available.
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
