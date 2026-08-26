package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.LongitudinalComparisonResultDto;
import ar.edu.uade.pfi.backend.service.LongitudinalComparisonService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 79. Addressed by {@code caseId} on both sides, matching every other endpoint in this
 * controller family (`/api/studies/{caseId}/...`) rather than a raw internal Study UUID.
 *
 * <p>Read-only and ungated by {@code RoleAuthorizationService.requireProfessional} — same
 * convention as the stage-76 GET history/detail endpoints (only the stage-78 mutating
 * PATCH/POST review endpoints require the professional role).
 */
@RestController
@RequestMapping("/api/studies/{caseId}")
public class LongitudinalComparisonController {
    private final LongitudinalComparisonService service;

    public LongitudinalComparisonController(LongitudinalComparisonService service) {
        this.service = service;
    }

    @GetMapping("/longitudinal-comparison")
    public LongitudinalComparisonResultDto compare(@PathVariable String caseId, @RequestParam String baselineCaseId) {
        return service.compare(caseId, baselineCaseId);
    }
}
