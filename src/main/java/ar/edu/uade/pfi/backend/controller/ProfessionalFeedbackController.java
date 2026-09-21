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
 * EXT-07D: structured CAPTURE of professional feedback — never training, never online/active
 * learning, never a checkpoint change. See {@link ProfessionalFeedbackCaptureService}'s own
 * javadoc for the full rationale.
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
     * Scans the Study's existing (already-persisted, append-only) corrections and
     * professional-authored regions and captures any not already captured. Idempotent — calling
     * this twice in a row without any new correction in between returns an empty list the second
     * time.
     */
    @PostMapping("/api/studies/{caseId}/feedback/capture")
    public List<ProfessionalFeedbackCandidateDto> capture(@PathVariable String caseId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        return captureService.captureForStudy(caseId).stream().map(ProfessionalFeedbackCandidateDto::from).toList();
    }

    /**
     * EXT-05: all filters optional. {@code from}/{@code to} are ISO-8601 instants filtering on
     * {@code correctedAt} (when the underlying correction/finding was made, not when captured).
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

    /** EXT-05: one candidate's full detail — the list endpoint already carries every field this needs, this is a convenience single-item lookup. */
    @GetMapping("/api/feedback-candidates/{id}")
    public ProfessionalFeedbackCandidateDto detail(@PathVariable String id, HttpServletRequest request) {
        authorizationService.requireProfessional(request, id);
        return captureService.findById(id).map(ProfessionalFeedbackCandidateDto::from)
            .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "FEEDBACK_CANDIDATE_NOT_FOUND", "Candidato de feedback no encontrado: " + id));
    }

    public record CandidateStatusChangeDto(String fromStatus, String toStatus, String reason, String note, Instant changedAt) {
    }

    /** EXT-05: full append-only curation history for one candidate — never includes curatorId (internal only, same rule as reviewedBy). */
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
     * The only way a candidate's status ever changes — always this explicit, human-issued call.
     * {@code "ready_for_training"} (or any value outside {@code captured|eligible_for_curation|
     * excluded|curated}) is rejected with 400 — never silently accepted or auto-assigned. Curator
     * identity is always derived from the JWT, never the request body — same pattern as
     * `EvidenceRegionAnnotationController`'s `authoredBy`/`reviewedBy` derivation.
     */
    @PatchMapping("/api/feedback-candidates/{id}")
    public ProfessionalFeedbackCandidateDto curate(@PathVariable String id, @RequestBody UpdateStatusRequest body, HttpServletRequest request) {
        authorizationService.requireProfessional(request, id);
        return ProfessionalFeedbackCandidateDto.from(captureService.curate(id, body.status(), curatorId(request), body.reason(), body.note()));
    }

    /**
     * EXT-05: internal curated export manifest — see {@code FeedbackDatasetExport}'s own javadoc.
     * Never produces or triggers a training dataset; this is a read-only snapshot of whatever the
     * filters select, wrapped with a schema version, count, and a checksum over the exported
     * records so a later consumer can detect tampering.
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

    /** Same JWT-claims-only identity derivation `EvidenceRegionAnnotationController` already uses — never the request body. */
    private String curatorId(HttpServletRequest request) {
        Object claims = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        if (!(claims instanceof TokenService.Claims tokenClaims)) return null;
        return tokenClaims.email() != null ? tokenClaims.email() : tokenClaims.subject();
    }
}
