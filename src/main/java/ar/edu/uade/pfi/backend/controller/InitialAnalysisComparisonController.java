package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.InitialAnalysisComparisonDto;
import ar.edu.uade.pfi.backend.service.InitialAnalysisComparisonService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comparación de mediciones del análisis inicial (real) entre dos estudios del mismo paciente.
 *
 * <p>Exige rol profesional y permiso de lectura sobre los estudios consultados.
 */
@RestController
@RequestMapping("/api/studies/{caseId}/initial-analysis-comparison")
public class InitialAnalysisComparisonController {
    private final InitialAnalysisComparisonService service;
    private final RoleAuthorizationService authorizationService;

    public InitialAnalysisComparisonController(InitialAnalysisComparisonService service, RoleAuthorizationService authorizationService) {
        this.service = service;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public InitialAnalysisComparisonDto compare(
        @PathVariable String caseId,
        @RequestParam String referenceCaseId,
        @RequestParam(required = false) String currentRunId,
        @RequestParam(required = false) String referenceRunId,
        HttpServletRequest request
    ) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyRead(request, caseId);
        authorizationService.requireStudyRead(request, referenceCaseId);
        return service.compare(caseId, referenceCaseId, currentRunId, referenceRunId);
    }

    @GetMapping("/candidates")
    public InitialAnalysisComparisonDto.Candidates candidates(
        @PathVariable String caseId,
        @RequestParam(required = false) String currentRunId,
        HttpServletRequest request
    ) {
        authorizationService.requireProfessional(request, caseId);
        authorizationService.requireStudyRead(request, caseId);
        Set<String> readableCases = Set.copyOf(authorizationService.accessibleStudyCaseIds(request));
        return service.candidates(caseId, currentRunId, readableCases::contains);
    }
}
