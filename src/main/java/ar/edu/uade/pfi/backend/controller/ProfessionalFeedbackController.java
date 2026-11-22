package ar.edu.uade.pfi.backend.controller;
import ar.edu.uade.pfi.backend.service.exception.RunReviewException;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.dto.ProfessionalFeedbackCandidateDto;
import ar.edu.uade.pfi.backend.service.ProfessionalFeedbackCaptureService;
import ar.edu.uade.pfi.backend.service.ProfessionalFeedbackCaptureService.FeedbackCandidateFilter;
import ar.edu.uade.pfi.backend.service.ProfessionalFeedbackCaptureService.FeedbackDatasetExport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXT-07D: CAPTURA estructurada de feedback profesional — nunca entrenamiento, nunca aprendizaje
 * online/activo, nunca un cambio de checkpoint. Ver el javadoc propio de {@link ProfessionalFeedbackCaptureService}
 * para el fundamento completo.
 */
@RestController
public class ProfessionalFeedbackController {
    private final ProfessionalFeedbackCaptureService captureService;
    private final RoleAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public ProfessionalFeedbackController(ProfessionalFeedbackCaptureService captureService, RoleAuthorizationService authorizationService, ObjectMapper objectMapper) {
        this.captureService = captureService;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Recorre las correcciones existentes del Study (ya persistidas, append-only) y las
     * regiones de autoría profesional, y captura las que todavía no se hayan capturado. Idempotente — llamarlo
     * dos veces seguidas sin ninguna corrección nueva en el medio devuelve una lista vacía la segunda
     * vez.
     */
    @PostMapping("/api/studies/{caseId}/feedback/capture")
    public List<ProfessionalFeedbackCandidateDto> capture(@PathVariable String caseId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        return captureService.captureForStudy(caseId).stream().map(ProfessionalFeedbackCandidateDto::from).toList();
    }

    /**
     * EXT-05: todos los filtros son opcionales. {@code from}/{@code to} son instantes ISO-8601 que filtran por
     * {@code correctedAt} (cuándo se hizo la corrección/hallazgo subyacente, no cuándo se capturó).
     */
    @GetMapping("/api/studies/{caseId}/feedback-candidates")
    public List<ProfessionalFeedbackCandidateDto> list(
        @PathVariable String caseId,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String level,
        @RequestParam(required = false) String plane,
        @RequestParam(required = false) String model,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        HttpServletRequest request
    ) {
        authorizationService.requireProfessional(request, caseId);
        FeedbackCandidateFilter filter = new FeedbackCandidateFilter(state, type, level, plane, model, parseInstant(from), parseInstant(to));
        return captureService.findByCase(caseId, filter).stream().map(ProfessionalFeedbackCandidateDto::from).toList();
    }

    /** EXT-05: detalle completo de un candidato — el endpoint de listado ya trae todos los campos que esto necesita; esta es una consulta de un solo ítem por conveniencia. */
    @GetMapping("/api/feedback-candidates/{id}")
    public ProfessionalFeedbackCandidateDto detail(@PathVariable String id, HttpServletRequest request) {
        authorizationService.requireProfessional(request, id);
        return captureService.findById(id).map(ProfessionalFeedbackCandidateDto::from)
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "FEEDBACK_CANDIDATE_NOT_FOUND", "Candidato de feedback no encontrado: " + id));
    }

    public record CandidateStatusChangeDto(String fromStatus, String toStatus, String reason, String note, Instant changedAt) {
    }

    /** EXT-05: historial completo append-only de curación de un candidato — nunca incluye curatorId (solo interno, misma regla que reviewedBy). */
    @GetMapping("/api/feedback-candidates/{id}/history")
    public List<CandidateStatusChangeDto> history(@PathVariable String id, HttpServletRequest request) {
        authorizationService.requireProfessional(request, id);
        return captureService.findHistory(id).stream()
            .map(change -> new CandidateStatusChangeDto(change.fromStatus(), change.toStatus(), change.reason(), change.note(), change.changedAt()))
            .toList();
    }

    public record UpdateStatusRequest(String status, String reason, String note) {
    }

    /**
     * La única forma en que cambia el estado de un candidato — siempre esta llamada explícita, emitida por una persona.
     * {@code "ready_for_training"} (o cualquier valor fuera de {@code captured|eligible_for_curation|
     * excluded|curated}) se rechaza con 400 — nunca se acepta ni se asigna automáticamente en silencio. La identidad del
     * curador siempre se deriva del JWT, nunca del body del request — mismo patrón que la derivación de
     * `authoredBy`/`reviewedBy` de `EvidenceRegionAnnotationController`.
     */
    @PatchMapping("/api/feedback-candidates/{id}")
    public ProfessionalFeedbackCandidateDto curate(@PathVariable String id, @RequestBody UpdateStatusRequest body, HttpServletRequest request) {
        authorizationService.requireProfessional(request, id);
        return ProfessionalFeedbackCandidateDto.from(captureService.curate(id, body.status(), curatorId(request), body.reason(), body.note()));
    }

    /**
     * EXT-05: manifiesto interno de exportación curada — ver el javadoc propio de {@code FeedbackDatasetExport}.
     * Nunca produce ni dispara un dataset de entrenamiento; es una instantánea de solo lectura de lo que
     * seleccionen los filtros, envuelta con una versión de esquema, un conteo y un checksum sobre los
     * registros exportados para que un consumidor posterior pueda detectar manipulaciones.
     */
    @GetMapping("/api/studies/{caseId}/feedback/export")
    public FeedbackDatasetExport export(
        @PathVariable String caseId,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String level,
        @RequestParam(required = false) String plane,
        @RequestParam(required = false) String model,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        HttpServletRequest request
    ) {
        authorizationService.requireProfessional(request, caseId);
        FeedbackCandidateFilter filter = new FeedbackCandidateFilter(state, type, level, plane, model, parseInstant(from), parseInstant(to));
        return captureService.exportForStudy(caseId, filter, objectMapper);
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    /** La misma derivación de identidad solo desde claims JWT que ya usa `EvidenceRegionAnnotationController` — nunca el body del request. */
    private String curatorId(HttpServletRequest request) {
        Object claims = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        if (!(claims instanceof TokenService.Claims tokenClaims)) return null;
        return tokenClaims.email() != null ? tokenClaims.email() : tokenClaims.subject();
    }
}
