package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.service.ReviewAnalyticsReport;
import ar.edu.uade.pfi.backend.service.ReviewAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EXT-02: "telemetría de feedback de revisión" de solo lectura — interna, descriptiva, nunca una afirmación de
 * precisión clínica. Ver el javadoc propio de {@link ReviewAnalyticsService} para saber a partir de qué se calcula.
 */
@RestController
public class ReviewAnalyticsController {
    private final ReviewAnalyticsService analyticsService;
    private final RoleAuthorizationService authorizationService;

    public ReviewAnalyticsController(ReviewAnalyticsService analyticsService, RoleAuthorizationService authorizationService) {
        this.analyticsService = analyticsService;
        this.authorizationService = authorizationService;
    }

    /** {@code caseId} es opcional — si se omite, agrega sobre todos los estudios; si está presente, se acota a uno. */
    @GetMapping("/api/analytics/review-feedback")
    public ReviewAnalyticsReport reviewFeedback(@RequestParam(required = false) String caseId, HttpServletRequest request) {
        if (caseId == null || caseId.isBlank()) authorizationService.requireAdmin(request, "global-review-analytics");
        else {
            authorizationService.requireProfessional(request, caseId);
            authorizationService.requireStudyRead(request, caseId);
        }
        return analyticsService.computeReport(caseId);
    }
}
