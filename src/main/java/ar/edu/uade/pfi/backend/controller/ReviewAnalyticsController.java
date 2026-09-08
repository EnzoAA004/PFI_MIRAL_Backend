package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.service.ReviewAnalyticsReport;
import ar.edu.uade.pfi.backend.service.ReviewAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXT-02: read-only "review feedback telemetry" — internal, descriptive, never a clinical
 * accuracy claim. See {@link ReviewAnalyticsService}'s own javadoc for what this computes from.
 */
@RestController
public class ReviewAnalyticsController {
    private final ReviewAnalyticsService analyticsService;
    private final RoleAuthorizationService authorizationService;

    public ReviewAnalyticsController(ReviewAnalyticsService analyticsService, RoleAuthorizationService authorizationService) {
        this.analyticsService = analyticsService;
        this.authorizationService = authorizationService;
    }

    /** {@code caseId} optional — omitted, this aggregates across every study; present, it scopes to one. */
    @GetMapping("/api/analytics/review-feedback")
    public ReviewAnalyticsReport reviewFeedback(@RequestParam(required = false) String caseId, HttpServletRequest request) {
        authorizationService.requireProfessional(request, caseId == null ? "global-review-analytics" : caseId);
        return analyticsService.computeReport(caseId);
    }
}
