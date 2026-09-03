package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.ProfessionalFeedbackCandidateDto;
import ar.edu.uade.pfi.backend.service.ProfessionalFeedbackCaptureService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public ProfessionalFeedbackController(ProfessionalFeedbackCaptureService captureService, RoleAuthorizationService authorizationService) {
        this.captureService = captureService;
        this.authorizationService = authorizationService;
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

    @GetMapping("/api/studies/{caseId}/feedback-candidates")
    public List<ProfessionalFeedbackCandidateDto> list(@PathVariable String caseId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId);
        return captureService.findByCase(caseId).stream().map(ProfessionalFeedbackCandidateDto::from).toList();
    }

    public record UpdateStatusRequest(String status) {
    }

    /**
     * The only way a candidate's status ever changes — always this explicit, human-issued call.
     * {@code "ready_for_training"} (or any value outside {@code captured|eligible_for_curation|
     * excluded|curated}) is rejected with 400 — never silently accepted or auto-assigned.
     */
    @PatchMapping("/api/feedback-candidates/{id}")
    public ProfessionalFeedbackCandidateDto updateStatus(@PathVariable String id, @RequestBody UpdateStatusRequest body, HttpServletRequest request) {
        authorizationService.requireProfessional(request, id);
        return ProfessionalFeedbackCandidateDto.from(captureService.updateStatus(id, body.status()));
    }
}
